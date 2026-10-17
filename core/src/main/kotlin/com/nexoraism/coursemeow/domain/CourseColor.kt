package com.nexoraism.coursemeow.domain

import com.nexoraism.coursemeow.data.Course

/**
 * 按课名算一个稳定的颜色。
 *
 * 学校导出的表格里没有颜色这一列，云端解析也不返回颜色 —— 但课表格子上有颜色是
 * 「一眼认出来」的关键（同一门课在不同时间上课要长得一样），所以按课名算：
 * 同名同色，不同课尽量错开。纯函数，不依赖界面层，导入与云端解析共用一份。
 *
 * 用的是与手动添加课程时同一套色板（`ui/theme/CoursePalette` 的同色值，
 * 这里以 ARGB 整数形式写一份：domain 层不该反向依赖 ui 层）。
 */
fun courseColorFor(name: String): Int = PALETTE[colorIndexOf(name)]

/**
 * 给一张课表里的**所有课名**分配颜色：同课同色、不同课尽量不同。
 *
 * 规则：
 * 1. 每门课先用 [colorIndexOf] 算一个稳定的起点色（纯函数，与课表内容无关）——
 *    所以同一门课在任何课表、任何一次导入里的起点都一样；
 * 2. 起点色若已被本张课表里的其他课占了，就沿色板顺延到下一个没被占用的色，
 *    于是「同一张课表内不同课不撞色」（12 色板，超过 12 门才会回环复用）；
 * 3. 同名课只分配一次，它的所有条目共用同一个颜色。
 *
 * @param names 课表里所有课名（可含重复，按首次出现顺序处理）
 * @return 课名 → ARGB 颜色
 */
fun assignCourseColors(names: List<String>): Map<String, Int> {
    val used = HashSet<Int>()
    val out = HashMap<String, Int>(names.size * 2)
    for (name in names.distinct()) {
        var index = colorIndexOf(name)
        var step = 0
        // 顺延找第一个没被占用的颜色；全被占满（课比颜色多）时退回起点色
        while (step < PALETTE.size && PALETTE[index] in used) {
            index = (index + 1) % PALETTE.size
            step++
        }
        used += PALETTE[index]
        out[name] = PALETTE[index]
    }
    return out
}

/**
 * 就地把一张课表的颜色换成「整表统一分配」的结果（同课同色、不同课错开）。
 *
 * 各导入解析器建完课程列表后调一次即可；只动颜色，其余字段原样。
 */
fun assignTimetableColors(courses: MutableList<Course>) {
    val colors = assignCourseColors(courses.map { it.name })
    for (i in courses.indices) {
        val c = courses[i]
        courses[i] = c.copy(color = colors[c.name] ?: c.color)
    }
}

/** 课名到色板下标的稳定映射（同名恒定，与课表内容无关）。 */
private fun colorIndexOf(name: String): Int {
    if (name.isEmpty()) return 0
    var h = 0
    name.forEach { h = h * 31 + it.code }
    return Math.floorMod(h, PALETTE.size)
}

/** 与界面色板同值的十二色（冷色家族，同一张课表看起来是一个色系）。 */
private val PALETTE = intArrayOf(
    0xFF1E3A8A.toInt(), 0xFF1D4ED8.toInt(), 0xFF3B82F6.toInt(), 0xFF60A5FA.toInt(),
    0xFF06B6D4.toInt(), 0xFF0891B2.toInt(), 0xFF0F766E.toInt(), 0xFF14B8A6.toInt(),
    0xFF3730A3.toInt(), 0xFF4F46E5.toInt(), 0xFF6D28D9.toInt(), 0xFF7C3AED.toInt()
)
