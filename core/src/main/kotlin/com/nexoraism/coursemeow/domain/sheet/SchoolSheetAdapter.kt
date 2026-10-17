package com.nexoraism.coursemeow.domain.sheet

import com.nexoraism.coursemeow.data.Course
import com.nexoraism.coursemeow.data.TEACHER_SEPARATOR
import com.nexoraism.coursemeow.data.WeekRule
import com.nexoraism.coursemeow.domain.assignTimetableColors
import com.nexoraism.coursemeow.domain.courseColorFor
import java.time.LocalDate
import java.time.ZoneId

/**
 * 一次导入的结果。
 *
 * [incomplete] 是「文件里信息不全」的那些课（如「大学体育(V)(选项)(板块)」，体育与英语由
 * 学校另一个系统排课，所以没写周次与地点）。它们**照样导入** —— 位置来自格子所在的行列，
 * 文件没写的字段留空。这份清单只是用来在导入结果里告诉用户「有几门课信息不全，可能要自己补」。
 */
class SheetImportResult(
    val timetableName: String,
    val semesterStartDate: Long,
    val semesterEndDate: Long,
    val courses: List<Course>,
    val incomplete: List<String>,
    val notes: List<String>
)

/**
 * 一所学校导出的课表文件的适配器。
 *
 * 为什么按学校写：每所学校教务系统导出的表格**版面和单元格语法是固定不变的**，
 * 所以适配器可以写死，不需要 AI、不会认错、还能完全离线跑。代价是每支援一所学校
 * 要加一个实现（见 [SchoolSheetAdapters]），但那只在新增学校时发生一次。
 *
 * 加一所学校要做的事：写一个 [matches] 能认出来的特征（表头长什么样）+
 * [parse] 把格子拆成课程。参考 [JiangsuUniversityAdapter]。
 */
interface SchoolSheetAdapter {
    /** 稳定标识，用于日志与去重。 */
    val id: String

    /** 显示用：学校名 + 教务系统名。 */
    val label: String

    /** 这份表格是不是我认得的形状。 */
    fun matches(grid: SheetGrid): Boolean

    /** 解析；失败抛 [UnsupportedXls]。 */
    fun parse(grid: SheetGrid, timetableId: Long = 0L): SheetImportResult
}

/** 全部已支援的适配器。按顺序尝试，第一个认出来的胜出。 */
object SchoolSheetAdapters {

    val all: List<SchoolSheetAdapter> = listOf(JiangsuUniversityAdapter)

    fun detect(grid: SheetGrid): SchoolSheetAdapter? = all.firstOrNull { it.matches(grid) }
}

/**
 * 江苏大学（正方教务系统导出）课表适配器。
 *
 * 版面（实测 76 个班级文件完全一致）：
 *   第 1 行  标题：学期 | 班级课表 | 专业
 *   第 2 行  表头：A-B 合并「节次」+ C..I = 星期一..星期日
 *   第 3-7 行 A=上午/下午/晚上，B=一..五（大节），C..I = 课程格
 *   第 8 行  实践课程（整行合并）
 *   第 9 行  图例：注--内容顺序为：课程<>周次<>地点<>教师<>教学班组成 + 学期起止 + 总周数
 *
 * 单元格语法：每门课一行，行内按 `/` 切五段；一格可能有多门课（按换行分隔）；
 * 周次段形如「(3-4节)2-14周(双)」「1-14周」「2周」「1-14周,16-18周」。
 */
object JiangsuUniversityAdapter : SchoolSheetAdapter {

    override val id: String = "ujs-zhengfang-v1"
    override val label: String = "江苏大学（正方教务系统）"

    /** 第 2 列是大节序号（一/二/三/四/五），对应 1-2 / 3-4 / 5-6 / 7-8 / 9-10 节。 */
    private val slotPeriods = mapOf(
        "一" to (1 to 2),
        "二" to (3 to 4),
        "三" to (5 to 6),
        "四" to (7 to 8),
        "五" to (9 to 10)
    )

    /** C..I → 周一..周日（合并「节次」占了两列，所以数据列从 2 开始）。 */
    private const val FIRST_DAY_COL = 2

    /** 颜色只是为了课表能看：文件里没有颜色，按课名分配一个稳定的颜色（见 courseColorFor）。 */

    override fun matches(grid: SheetGrid): Boolean {
        val header = headerRow(grid) ?: return false
        val line = (0 until minOf(grid.rows.getOrNull(header)?.size ?: 0, 12))
            .joinToString("|") { grid.cell(header, it) }
        return line.contains("节次") && line.contains("星期一") && legend(grid) != null
    }

    override fun parse(grid: SheetGrid, timetableId: Long): SheetImportResult {
        val header = headerRow(grid) ?: throw XlsReader.UnsupportedXls("找不到表头行")
        val legendText = legend(grid) ?: ""

        // 标题行整行都是合并的（学期 | 班级课表 | 专业），值只在合并区的左上角，
        // 所以必须用 mergedValue —— 用 cell() 会读成一串空格子。
        val titleCells = (0 until (grid.rows.getOrNull(header - 1)?.size ?: 0))
            .map { grid.mergedValue(header - 1, it) }
            .filter { it.isNotBlank() }
            .distinct()
        val semester = titleCells.firstOrNull { it.contains("学期") } ?: ""
        val rawClassName = titleCells.firstOrNull { it.contains("课表") } ?: "导入的课表"
        val major = titleCells.firstOrNull { it.startsWith("专业") } ?: ""

        // 课表名 = 专业 + 班级（表头两项都写着，就是用来认这一张表是谁的）。
        // 两者互相包含时只留一个，免得出现「计算机 计算机2301」这种重复。
        val majorShort = major.removePrefix("专业：").removePrefix("专业:").trim()
        val classShort = rawClassName.removeSuffix("课表").trim().ifBlank { "导入的课表" }
        val timetableName = when {
            majorShort.isBlank() -> classShort
            classShort.contains(majorShort) || majorShort.contains(classShort) -> classShort
            else -> "$majorShort $classShort"
        }

        // 学期起止与总周数写在第 9 行那段说明里，不用猜
        val span = Regex("(\\d{4}-\\d{2}-\\d{2})\\s*正式上课至\\s*(\\d{4}-\\d{2}-\\d{2})").find(legendText)
        val weeks = Regex("共\\s*(\\d{1,2})\\s*周").find(legendText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val start = span?.groupValues?.get(1)?.let { toMillis(it) } ?: 0L
        val end = span?.groupValues?.get(2)?.let { toMillis(it) } ?: 0L
        val fallbackEnd = if (end <= 0L && start > 0L && weeks > 0) start + weeks * 7L * 24 * 3600 * 1000 else end

        val courses = ArrayList<Course>()
        val incomplete = ArrayList<String>()

        for (row in header + 1 until grid.rowCount) {
            val first = grid.mergedValue(row, 0).trim()
            if (first.contains("实践课程")) {
                // 整行合并的实践课程说明，没有节次也没有星期，抄不成课程
                continue
            }
            val label = grid.cell(row, 1).trim()
            val slot = slotPeriods[label] ?: continue

            for (col in FIRST_DAY_COL until (grid.rows.getOrNull(row)?.size ?: 0)) {
                val raw = grid.cell(row, col).trim()
                if (raw.isEmpty()) continue
                val day = col - FIRST_DAY_COL + 1
                if (day !in 1..7) continue

                raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
                    val parts = entry.split('/').map { it.trim() }
                    val name = parts.getOrNull(0).orEmpty()
                    if (name.isEmpty()) return@forEach

                    // 星期与节次永远取自格子所在的行列 —— 文件把它摆在哪里就是哪里。
                    // 信息不全的格子（「大学体育(V)(选项)(板块)」）同样有位置，只是没写周次、
                    // 地点与教师：那就照抄位置，把文件没说的那几项留空。
                    val weekText = parts.getOrNull(1).orEmpty()
                    val segments = parseWeekSegments(weekText)
                    val textPeriods = Regex("[（(]\\s*(\\d{1,2})\\s*[-~至到]\\s*(\\d{1,2})\\s*节[）)]")
                        .find(weekText)
                    val periods = if (textPeriods != null) {
                        textPeriods.groupValues[1].toInt() to textPeriods.groupValues[2].toInt()
                    } else {
                        slot
                    }
                    val place = splitPlace(parts.getOrNull(2).orEmpty())
                    val teacher = parts.getOrNull(3).orEmpty()
                    // 周次没写就按整学期算：课表里没有「周次未知」这种状态，
                    // 而「每周」是含义上最接近「没特别说明」的取值（总周数取自文件里的说明行）。
                    val spec = if (segments.isEmpty()) {
                        incomplete.add(name)
                        WeekRuleSpec(1, if (weeks > 0) weeks else 20, WeekRule.EVERY, "")
                    } else {
                        toWeekRule(segments)
                    }

                    courses.add(
                        Course(
                            timetableId = timetableId,
                            name = name,
                            // 文件里多位教师用逗号分隔，入库统一用顿号
                            teacher = teacher.split(',', '，', ';', '；', '、')
                                .map { it.trim() }.filter { it.isNotEmpty() }
                                .joinToString(TEACHER_SEPARATOR),
                            building = place.first,
                            room = place.second,
                            dayOfWeek = day,
                            startPeriod = periods.first,
                            endPeriod = periods.second,
                            startWeek = spec.start,
                            endWeek = spec.end,
                            weekType = spec.type,
                            customWeeks = spec.custom,
                            color = courseColorFor(name)
                        )
                    )
                }
            }
        }

        if (courses.isEmpty()) throw XlsReader.UnsupportedXls("这份表格里没解析出任何课程")
        // 颜色按整张课表统一分配：同课同色、不同课错开
        assignTimetableColors(courses)

        val notes = ArrayList<String>()
        if (semester.isNotBlank()) notes.add(semester)
        if (major.isNotBlank()) notes.add(major)
        if (weeks > 0) notes.add("共 $weeks 周")

        return SheetImportResult(
            timetableName = timetableName,
            semesterStartDate = start,
            semesterEndDate = fallbackEnd,
            courses = courses,
            incomplete = incomplete,
            notes = notes
        )
    }

    // ------------------------------------------------------------------ 工具

    private fun headerRow(grid: SheetGrid): Int? {
        for (r in 0 until minOf(grid.rowCount, 8)) {
            val cells = (0 until minOf(grid.rows[r].size, 12)).map { grid.cell(r, it) }
            if (cells.any { it.contains("节次") } && cells.any { it.contains("星期一") }) return r
        }
        return null
    }

    private fun legend(grid: SheetGrid): String? {
        for (r in grid.rowCount - 1 downTo 0) {
            val line = grid.rows[r].joinToString("")
            if (line.contains("内容顺序")) return line
        }
        return null
    }

    private fun toMillis(iso: String): Long = runCatching {
        LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    /** 「京江2号楼2208」「计算机楼113」「讲堂群301」→（楼, 房间）。 */
    private fun splitPlace(place: String): Pair<String, String> {
        val s = place.trim()
        if (s.isEmpty()) return "" to ""
        val m = Regex("^(.{1,14}?(?:号楼|楼|馆|区|中心|群))\\s*(.*)$").find(s)
        return if (m != null && m.groupValues[2].isNotBlank()) {
            m.groupValues[1] to m.groupValues[2]
        } else {
            "" to s
        }
    }

    /** 一段周次文本里的一个区间。 */
    private data class WeekSegment(val start: Int, val end: Int, val weekType: Int)

    /**
     * 拆「(3-4节)2-14周(双)」「1-14周,16-18周」「第2周」这类文本。
     * 先用「节」字把节次前缀去掉，再按逗号分段，每段各自判单双。
     */
    private fun parseWeekSegments(text: String): List<WeekSegment> {
        val body = text.replace(Regex("[（(][^）)]*节[）)]"), " ")
        val out = ArrayList<WeekSegment>()
        body.split(',', '，', '、', ';', '；').forEach { part ->
            val m = Regex("(\\d{1,2})(?:\\s*[-~至到]\\s*(\\d{1,2}))?").find(part) ?: return@forEach
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].ifBlank { m.groupValues[1] }.toInt()
            val type = when {
                part.contains("单") -> WeekRule.ODD
                part.contains("双") -> WeekRule.EVEN
                else -> WeekRule.EVERY
            }
            out.add(WeekSegment(minOf(a, b), maxOf(a, b), type))
        }
        return out
    }

    /** 把若干区间压成课程能存的形式。 */
    private class WeekRuleSpec(val start: Int, val end: Int, val type: Int, val custom: String)

    /**
     * 多段周次用「自定义周」精确表达。
     *
     * 注意 [Course.occursOnWeek] 会先看 `week in startWeek..endWeek`，所以自定义周次必须
     * 让 startWeek / endWeek 是**包围范围**（最小值到最大值），真正上哪些周由 customWeeks 决定。
     *
     * 能简化成「每周 / 单周 / 双周」的（也就是单段、或整段恰好是奇数/偶数周）就简化，
     * 这样界面上的显示更好看；剩下的才落到自定义。
     */
    private fun toWeekRule(segments: List<WeekSegment>): WeekRuleSpec {
        val weeks = sortedSetOf<Int>()
        segments.forEach { seg ->
            for (w in seg.start..seg.end) {
                when (seg.weekType) {
                    WeekRule.ODD -> if (w % 2 == 1) weeks.add(w)
                    WeekRule.EVEN -> if (w % 2 == 0) weeks.add(w)
                    else -> weeks.add(w)
                }
            }
        }
        if (weeks.isEmpty()) return WeekRuleSpec(1, 1, WeekRule.EVERY, "")
        val start = weeks.first()
        val end = weeks.last()
        val span = end - start + 1

        if (weeks.size == span) return WeekRuleSpec(start, end, WeekRule.EVERY, "")
        val odds = (start..end).filter { it % 2 == 1 }
        val evens = (start..end).filter { it % 2 == 0 }
        if (weeks.size == odds.size && weeks.containsAll(odds)) return WeekRuleSpec(start, end, WeekRule.ODD, "")
        if (weeks.size == evens.size && weeks.containsAll(evens)) return WeekRuleSpec(start, end, WeekRule.EVEN, "")
        return WeekRuleSpec(start, end, WeekRule.CUSTOM, weeks.joinToString(","))
    }
}
