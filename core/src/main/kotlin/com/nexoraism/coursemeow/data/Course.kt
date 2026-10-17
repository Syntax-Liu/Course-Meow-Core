package com.nexoraism.coursemeow.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 单双周规则常量。 */
object WeekRule {
    const val EVERY = 0     // 每周
    const val ODD = 1       // 单周
    const val EVEN = 2      // 双周
    const val CUSTOM = 3    // 自定义周
}

/** 课程时间模式。 */
object TimeMode {
    /** 固定时间：严格按照课程表中设定的节次上下课时间。 */
    const val FIXED = 0

    /** 自由时间：不强制对应节次，可自定义起止时间。 */
    const val FREE = 1
}

/** 多个教师之间的分隔符（存储、导出时统一用顿号连接）。 */
const val TEACHER_SEPARATOR = "、"

/** 拆分教师时认的分隔符：顿号 / 中英文逗号 / 中英文分号 / 斜杠，兼容以前手打的数据。 */
private val TeacherSeparators = charArrayOf('、', ',', '，', ';', '；', '/', '\\')

/** 把多个教师拼成存储用的字符串（去空白、去重、去掉空项）。 */
fun joinTeachers(teachers: List<String>): String = teachers
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .joinToString(TEACHER_SEPARATOR)

/** 把教师字符串拆成一个个教师（兼容旧数据里手打的顿号、逗号等分隔符）。 */
fun splitTeachers(raw: String): List<String> = raw
    .split(*TeacherSeparators)
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()

/**
 * 课程实体。
 *
 * @property timetableId 所属课表 id
 * @property dayOfWeek  星期几，1=周一 … 7=周日
 * @property startPeriod 起始节次（从 1 开始）
 * @property endPeriod   结束节次（含），>= startPeriod
 * @property startWeek   起始周（从 1 开始）
 * @property endWeek     结束周（含）
 * @property weekType    见 [WeekRule]
 * @property customWeeks 自定义周，逗号分隔，如 "2,3,5,7"
 * @property color       课程颜色（ARGB Int）
 * @property timeMode    见 [TimeMode]
 * @property freeStartTime 自由模式下的开始时间（HH:mm）
 * @property freeEndTime   自由模式下的结束时间（HH:mm）
 * @property credits       学分（来自课表文件，如 "3.0"；解析不到时为空串）
 * @property totalHours    总学时（来自课表文件，如 "48"；解析不到时为空串）
 * @property nature        课程性质（来自课表文件，如 必修 / 选修；解析不到时为空串）
 * @property assessment    考核方式（来自课表文件，如 考试 / 考查；解析不到时为空串）
 */
@Entity(tableName = "courses")
data class Course(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timetableId: Long = 0,
    val name: String,
    val teacher: String,
    val building: String,
    val room: String,
    val dayOfWeek: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val startWeek: Int,
    val endWeek: Int,
    val weekType: Int = WeekRule.EVERY,
    val customWeeks: String = "",
    /**
     * 课程颜色。
     *
     * 存的是「旧版固定色板的第几号」（值本身也是那套色板里的颜色）：
     * 界面渲染时再映射成当前主题算出来的课程色，所以换配色方案时整张课表会跟着换。
     * 默认值是槽位 0（旧色板的深蓝）。
     */
    val color: Int = 0xFF1E3A8A.toInt(),
    val note: String = "",
    val timeMode: Int = TimeMode.FIXED,
    val freeStartTime: String = "",
    val freeEndTime: String = "",
    // 以下四项来自课表文件（学分 / 总学时 / 课程性质 / 考核方式）。Course 是 Room 实体，
    // 老数据这几列没有值，所以一律给默认空串；解析器拿不到就保持空串，不编造。
    val credits: String = "",
    val totalHours: String = "",
    val nature: String = "",
    val assessment: String = ""
) {
    /** 判断该课程在第 [week] 周是否上课。 */
    fun occursOnWeek(week: Int): Boolean {
        if (week < startWeek || week > endWeek) return false
        return when (weekType) {
            WeekRule.ODD -> week % 2 == 1
            WeekRule.EVEN -> week % 2 == 0
            WeekRule.CUSTOM -> parseCustomWeeks().contains(week)
            else -> true
        }
    }

    fun parseCustomWeeks(): Set<Int> =
        customWeeks.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

    /**
     * 这门课的所有教师。
     *
     * 数据库与导出用的还是 [teacher] 这一个字符串（顿号连接），
     * 界面上一律通过这里拿列表，不再自己按顿号切。
     */
    val teachers: List<String>
        get() = splitTeachers(teacher)

    /** 展示用的教师文本，多个之间用顿号连接。 */
    val teacherText: String
        get() = teachers.joinToString(TEACHER_SEPARATOR)

    val location: String
        get() = listOf(building, room).filter { it.isNotBlank() }.joinToString(" ")

    val isFreeTime: Boolean
        get() = timeMode == TimeMode.FREE
}
