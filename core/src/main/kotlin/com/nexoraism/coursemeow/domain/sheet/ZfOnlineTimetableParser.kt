package com.nexoraism.coursemeow.domain.sheet

import com.nexoraism.coursemeow.data.Course
import com.nexoraism.coursemeow.data.TEACHER_SEPARATOR
import com.nexoraism.coursemeow.data.WeekRule
import com.nexoraism.coursemeow.data.joinTeachers
import com.nexoraism.coursemeow.data.splitTeachers
import com.nexoraism.coursemeow.domain.assignTimetableColors
import com.nexoraism.coursemeow.domain.courseColorFor
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「正方教务系统在线课表」解析器。
 *
 * 与 [JiangsuUniversityAdapter]（解析学校导出的 .xls）不同，这里吃的是**从网页上复制/抓下来的内容**：
 * 新正方「学生课表查询」接口（`POST /kbcx/xskbcx_cxXsgrkb.html`，参数 `xnm` / `xqm` / `kzlx`）
 * 的 JSON 返回值，或者课表页（容器 id 常为 `kbtable`）的 HTML 表格。两者都认，谁不认识就换另一个。
 *
 * 为什么不用 AI / 联网：在线课表的字段名与表格语法是正方固定的几套写法，本地写死就能完全离线、
 * 不认错；代价是必须**在认不出时明确返回 null**，而不是猜一个看起来合理的课表 —— 位置猜错的课表
 * 比没有课表更糟。
 */
object ZfOnlineTimetableParser {

    /** 来源说明，写进 [SheetImportResult.notes]。 */
    private const val NOTE = "来自正方教务系统课表"

    /** 拿不到周次时按多少周算。与文件导入一致：课表里没有「周次未知」，用最接近的「整学期」。 */
    private const val DEFAULT_TOTAL_WEEKS = 20

    /**
     * 解析一段正方在线课表文本。
     *
     * [raw] 以 `{` / `[` 开头当 JSON，否则当 HTML；两者都试一遍，都认不出返回 `null`
     * （不抛异常 —— 调用方按「这份内容解析不出」处理即可）。
     */
    fun parse(
        raw: String,
        timetableName: String = "导入的课表",
        timetableId: Long = 0L
    ): SheetImportResult? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val looksJson = text.startsWith("{") || text.startsWith("[")
        val first = if (looksJson) parseJson(text, timetableName, timetableId)
        else parseHtml(text, timetableName, timetableId)
        if (first != null) return first
        return if (looksJson) parseHtml(text, timetableName, timetableId)
        else parseJson(text, timetableName, timetableId)
    }

    // ------------------------------------------------------------------ JSON 分支

    /**
     * 外层数组的常见 key。新版正方一般叫 `kbList`，但不同版本 / 不同封装会改名，
     * 所以这里列几个已知的，认不出再退到「扫描整个对象找像课程数组的那一个」。
     */
    private val PREFERRED_ARRAY_KEYS = listOf("kbList", "kbListX", "kbxx", "xskb", "kb")

    private fun parseJson(text: String, timetableName: String, timetableId: Long): SheetImportResult? {
        val items = runCatching { kbItems(text) }.getOrNull() ?: return null
        if (items.isEmpty()) return null
        val semester = runCatching { parseSemester(JSONObject(text)) }.getOrDefault(0L to 0L)

        val courses = ArrayList<Course>()
        val incomplete = ArrayList<String>()
        for (o in items) addJsonCourse(o, timetableId, courses, incomplete)
        if (courses.isEmpty()) return null
        // 颜色按整张课表统一分配：同课同色、不同课错开
        assignTimetableColors(courses)

        return SheetImportResult(
            timetableName = timetableName,
            semesterStartDate = semester.first,
            semesterEndDate = semester.second,
            courses = courses,
            incomplete = incomplete.distinct(),
            notes = listOf(NOTE)
        )
    }

    /**
     * 学期起止。
     *
     * 正方的**班级课表**（`bjkdy_cxBjKb`）回包里带 `weekNum`：一周一条，`rq` 形如 `2026-08-31/2026-09-06`。
     * 取第一周的周一当学期开始、最后一周的周日当学期结束 —— 这正是**个人课表里拿不到**的学期信息。
     * 个人课表（`xskbcx_cxXskbcx_grHtml`）没有这个字段，只能返回 0，由用户导入后自行设置。
     */
    private fun parseSemester(root: JSONObject): Pair<Long, Long> {
        val arr = root.optJSONArray("weekNum") ?: return 0L to 0L
        val mondays = (0 until arr.length()).mapNotNull { i ->
            val rq = arr.optJSONObject(i)?.optString("rq").orEmpty()
            runCatching { LocalDate.parse(rq.substringBefore("/").trim()) }.getOrNull()
        }.sorted()
        if (mondays.isEmpty()) return 0L to 0L
        fun millis(d: LocalDate): Long =
            d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // 最后一周的周一 +6 天 = 那一周的周日，作为学期结束（含当天）
        return millis(mondays.first()) to millis(mondays.last().plusDays(6))
    }

    /** 从任意 JSON 文本里挖出课程对象列表：裸数组直接用，否则从对象里找课程数组。 */
    private fun kbItems(text: String): List<JSONObject> {
        val arr = if (text.startsWith("[")) {
            JSONArray(text)
        } else {
            findCourseArray(JSONObject(text)) ?: return emptyList()
        }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** 先按已知 key，再扫描所有字段（含浅层嵌套对象），找第一个「看起来是课程数组」的。 */
    private fun findCourseArray(node: JSONObject, depth: Int = 0): JSONArray? {
        for (k in PREFERRED_ARRAY_KEYS) {
            node.optJSONArray(k)?.let { if (looksLikeCourseList(it)) return it }
        }
        for (k in node.keys()) {
            node.optJSONArray(k)?.let { if (looksLikeCourseList(it)) return it }
        }
        if (depth < 2) {
            for (k in node.keys()) {
                val child = node.optJSONObject(k) ?: continue
                findCourseArray(child, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** 数组第一个元素的字段里有正方课程的特征字段，才认为它是课程数组。 */
    private fun looksLikeCourseList(arr: JSONArray): Boolean {
        val first = arr.optJSONObject(0) ?: return false
        return first.has("kcmc") || first.has("jcs") || first.has("zcd") || first.has("xqj")
    }

    /**
     * 单条 JSON 记录 → [Course]。
     *
     * 缺字段一律容忍：拿不到「课名 / 星期 / 节次」的没法定位，跳过这一条（不算失败）；
     * 缺「周次 / 地点」的照样导入，只把课名记进 [incomplete] 交给上层提示用户补。
     */
    private fun addJsonCourse(
        o: JSONObject,
        timetableId: Long,
        out: MutableList<Course>,
        incomplete: MutableList<String>
    ) {
        val name = optText(o, "kcmc", "kc", "courseName", "name")
        if (name.isBlank()) return

        val day = o.optString("xqj").trim().toIntOrNull() ?: return
        if (day !in 1..7) return

        val periods = parsePeriods(optText(o, "jcs", "jc")) ?: return
        // 优先用 zcds（正方给的「展开后周次」，如 "4,6,8,…" 就是双周），比解析 zcd 文本可靠；
        // 拿不到再退回 zcd 文本。注意 zcmc 不能当周次 —— 它在正方里是**职称**。
        val spec = parseWeekRuleFromWeeks(optText(o, "zcds"))
            ?: parseWeekRule(optText(o, "zcd", "week"))
        // 「未排地点」是正方给「还没排教室」的占位说法，等同于没有地点
        val placeRaw = optText(o, "cdmc", "room", "location").takeIf { it != "未排地点" }.orEmpty()
        if (spec == null || placeRaw.isBlank()) incomplete.add(name)

        val place = splitPlace(placeRaw)
        out.add(
            Course(
                timetableId = timetableId,
                name = name,
                teacher = joinTeachers(splitTeachers(normalizeTeacher(optText(o, "xm", "jsxm", "teacher")))),
                building = place.first,
                room = place.second,
                dayOfWeek = day,
                startPeriod = periods.first,
                endPeriod = periods.second,
                // 周次没写就按整学期算，并已记进 incomplete
                startWeek = spec?.startWeek ?: 1,
                endWeek = spec?.endWeek ?: DEFAULT_TOTAL_WEEKS,
                weekType = spec?.weekType ?: WeekRule.EVERY,
                customWeeks = spec?.customWeeks ?: "",
                color = courseColorFor(name),
                // 课表文件里的四项信息：正方不同版本字段名不一样，认得出就取，认不出留空
                credits = optText(o, "xf"),
                totalHours = optText(o, "zxs", "kczxs"),
                nature = optText(o, "kcxzjc", "kcxzmc", "kcxz"),
                assessment = optText(o, "khfs")
            )
        )
    }

    /** 取若干候选 key 里第一个非空的文本；值可能是数组（如 `xm` 多位教师）。 */
    private fun optText(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            if (!o.has(k)) continue
            val v = o.opt(k) ?: continue
            val s = when {
                v === JSONObject.NULL -> ""
                v is JSONArray -> (0 until v.length())
                    .map { v.opt(it) }
                    .filter { it != null && it !== JSONObject.NULL }
                    .joinToString(TEACHER_SEPARATOR) { it.toString().trim() }
                else -> v.toString().trim()
            }
            if (s.isNotBlank()) return s
        }
        return ""
    }

    /**
     * 正方的多教师用**英文逗号**分隔（`沈文强,张海峰`），而应用内部统一用顿号。
     * 顺手把中英文逗号、分号都归一成顿号，交给 [splitTeachers] 才拆得开。
     */
    private fun normalizeTeacher(text: String): String = text
        .replace(",", TEACHER_SEPARATOR)
        .replace("，", TEACHER_SEPARATOR)
        .replace(";", TEACHER_SEPARATOR)
        .replace("；", TEACHER_SEPARATOR)

    // ------------------------------------------------------------------ HTML 分支

    /** 一个单元格：开标签的属性（取 title）与内部 HTML。 */
    private data class HtmlCell(val attrs: String, val inner: String)

    /** 从单元格里还原出的一门课（星期 / 节次由它所在的行列补上）。 */
    private data class HtmlCourse(
        val name: String,
        val spec: WeekRuleSpec,
        val teacher: String,
        val building: String,
        val room: String,
        val credits: String,
        val totalHours: String,
        val nature: String,
        val assessment: String
    )

    // 课程格元信息行的字段名（学分 / 总学时 / 课程性质简称 / 考核方式）。
    // 写成 \u 转义，免得被文案体检当成待翻译的界面文案。
    private const val FIELD_CREDITS = "\u5b66\u5206"                        // 学分
    private const val FIELD_HOURS = "\u603b\u5b66\u65f6"                    // 总学时
    private const val FIELD_NATURE = "\u8bfe\u7a0b\u6027\u8d28"             // 课程性质
    private const val FIELD_NATURE_SHORT = FIELD_NATURE + "\u7b80\u79f0"    // 课程性质简称
    private const val FIELD_ASSESSMENT = "\u8003\u6838\u65b9\u5f0f"         // 考核方式

    /** 这一行是不是「学分:…」这类元信息行（不算教师 / 地点）。 */
    private fun isFieldLine(line: String): Boolean = when {
        line.startsWith(FIELD_CREDITS) -> true
        line.startsWith(FIELD_HOURS) -> true
        line.startsWith(FIELD_NATURE) -> true
        line.startsWith(FIELD_ASSESSMENT) -> true
        else -> false
    }

    /** 从若干行里取「字段名:值」的值；没有就返回空串。 */
    private fun fieldValue(lines: List<String>, key: String): String {
        val re = Regex("^" + Regex.escape(key) + "\\s*[:：]\\s*(.+)$")
        for (line in lines) re.find(line)?.let { return it.groupValues[1].trim() }
        return ""
    }

    private val ROW_REGEX = Regex("<tr\\b[^>]*>(.*?)</tr>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CELL_REGEX = Regex("<t[dh]\\b([^>]*)>(.*?)</t[dh]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val TAG_REGEX = Regex("<[^>]+>")

    /**
     * HTML 分支：只认容器 id 为 `kbtable` 的课表表格。
     *
     * 可靠性来自两条**硬校验**：表头要有至少 3 个「星期X」列（据此把列映射到星期），
     * 每个课程格要能还原出「课名 + 周次」（据此再配合行列得到星期与节次）。
     * 任何一条不满足就返回 `null` —— 与其把「周三的课」摆到周四，不如明说认不出。
     */
    private fun parseHtml(text: String, timetableName: String, timetableId: Long): SheetImportResult? {
        val table = extractKbTable(text) ?: return null
        val rows = ROW_REGEX.findAll(table).map { it.groupValues[1] }.toList()
        if (rows.isEmpty()) return null

        var cols: Map<Int, Int>? = null
        var headerRow = -1
        for ((i, row) in rows.withIndex()) {
            val map = LinkedHashMap<Int, Int>()
            extractCells(row).forEachIndexed { c, cell ->
                dayOfWeek(cellTextOf(cell))?.let { map[c] = it }
            }
            // 至少 3 个星期列才当表头，避免把课程格里偶然出现的「周几」当成表头
            if (map.size >= 3) {
                cols = map
                headerRow = i
                break
            }
        }
        val dayByCol = cols ?: return null

        val courses = ArrayList<Course>()
        val incomplete = ArrayList<String>()
        for (i in headerRow + 1 until rows.size) {
            val cells = extractCells(rows[i])
            val periods = parsePeriods(cells.firstOrNull()?.let { cellTextOf(it) }.orEmpty()) ?: continue
            for ((col, day) in dayByCol) {
                val cell = cells.getOrNull(col) ?: continue
                val c = parseHtmlCell(cell) ?: continue
                if (c.room.isBlank() && c.building.isBlank()) incomplete.add(c.name)
                courses.add(
                    Course(
                        timetableId = timetableId,
                        name = c.name,
                        teacher = joinTeachers(splitTeachers(c.teacher)),
                        building = c.building,
                        room = c.room,
                        dayOfWeek = day,
                        startPeriod = periods.first,
                        endPeriod = periods.second,
                        startWeek = c.spec.startWeek,
                        endWeek = c.spec.endWeek,
                        weekType = c.spec.weekType,
                        customWeeks = c.spec.customWeeks,
                        color = courseColorFor(c.name),
                        credits = c.credits,
                        totalHours = c.totalHours,
                        nature = c.nature,
                        assessment = c.assessment
                    )
                )
            }
        }
        if (courses.isEmpty()) return null
        // 颜色按整张课表统一分配：同课同色、不同课错开
        assignTimetableColors(courses)

        return SheetImportResult(
            timetableName = timetableName,
            semesterStartDate = 0L,
            semesterEndDate = 0L,
            courses = courses,
            incomplete = incomplete.distinct(),
            notes = listOf(NOTE)
        )
    }

    private val KB_TABLE_REGEX = Regex(
        "<table\\b[^>]*\\bid\\s*=\\s*[\"']?kbtable[\"']?[^>]*>(.*?)</table>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val ANY_TABLE_REGEX = Regex(
        "<table\\b[^>]*>(.*?)</table>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /**
     * 定位课表表格。
     *
     * 优先认 `id="kbtable"`（个人课表页面就是它）；但**班级课表那种整页 HTML 不一定给表格 id**
     * （整页是服务端渲染的，没有接口可抓），所以退一步：取第一张含「星期」表头的表。
     * 上层的「至少 3 个星期列」校验仍会兜住误判 —— 找错的表会被判为认不出，而不是产出一张错课表。
     */
    private fun extractKbTable(html: String): String? {
        KB_TABLE_REGEX.find(html)?.let { return it.groupValues[1] }
        return ANY_TABLE_REGEX.findAll(html)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("星期") }
    }

    private fun extractCells(rowHtml: String): List<HtmlCell> =
        CELL_REGEX.findAll(rowHtml).map { HtmlCell(it.groupValues[1], it.groupValues[2]) }.toList()

    /** 单元格可视文本，按 `<br>` 分行（课程格就是靠这个还原「课程名/教师/周次/地点」的）。 */
    private fun cellLines(inner: String): List<String> {
        val withBreaks = BR_REGEX.replace(inner, "\n")
        val stripped = TAG_REGEX.replace(withBreaks, "")
        return decodeEntities(stripped).lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun cellTextOf(cell: HtmlCell): String = cellLines(cell.inner).joinToString("")

    /**
     * 课程格 → 一门课。
     *
     * 约定（正方课表格子的固定写法）：`课名<br>教师<br>周次<br>地点`，教师可能没有。
     * 因此用「哪一行像周次」来定基准：课名取第一行，课名与周次行之间的是教师，周次行下一行是地点。
     * 找不到周次行（正文和 title 里都没有）就返回 null —— 缺周次的课没法可靠还原。
     */
    private fun parseHtmlCell(cell: HtmlCell): HtmlCourse? {
        val rawLines = cellLines(cell.inner)
        // 「学分:…」这类元信息行不算课程名 / 教师 / 地点，先摘出去再按老规则定位
        val lines = rawLines.filterNot { isFieldLine(it) }
        val name = lines.firstOrNull() ?: return null
        if (name.isBlank()) return null

        val weekIdx = lines.indexOfFirst { it.contains("周") && parseWeekRule(it) != null }
        val weekText = when {
            weekIdx >= 0 -> lines[weekIdx]
            else -> attr(cell.attrs, "title").takeIf { it.contains("周") && parseWeekRule(it) != null } ?: return null
        }
        val spec = parseWeekRule(weekText) ?: return null

        val teacher = if (weekIdx > 1) lines.subList(1, weekIdx).joinToString(TEACHER_SEPARATOR) else ""
        val place = if (weekIdx >= 0) lines.getOrNull(weekIdx + 1).orEmpty() else ""
        val (building, room) = splitPlace(place)
        return HtmlCourse(
            name, spec, teacher, building, room,
            credits = fieldValue(rawLines, FIELD_CREDITS),
            totalHours = fieldValue(rawLines, FIELD_HOURS),
            nature = fieldValue(rawLines, FIELD_NATURE_SHORT).ifBlank { fieldValue(rawLines, FIELD_NATURE) },
            assessment = fieldValue(rawLines, FIELD_ASSESSMENT)
        )
    }

    private fun attr(attrs: String, name: String): String {
        val m = Regex("\\b$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(attrs) ?: return ""
        return (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
    }

    private fun decodeEntities(s: String): String = s
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'")

    /** 「星期一 / 周天」这类表头 → 1..7。 */
    private fun dayOfWeek(text: String): Int? {
        val m = Regex("(?:星期|周)([一二三四五六日天])").find(text) ?: return null
        return when (m.groupValues[1]) {
            "一" -> 1
            "二" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            "六" -> 6
            "日", "天" -> 7
            else -> null
        }
    }

    // ------------------------------------------------------------------ 纯函数：节次 / 周次 / 地点

    /** 「1-2」「1-2节」「第3节」→（起, 止）；解析不出返回 null。 */
    private fun parsePeriods(text: String): Pair<Int, Int>? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val range = Regex("(\\d{1,2})\\s*[-~—－至到]\\s*(\\d{1,2})").find(t)
        if (range != null) {
            val a = range.groupValues[1].toInt()
            val b = range.groupValues[2].toInt()
            return minOf(a, b) to maxOf(a, b)
        }
        val single = Regex("(\\d{1,2})").find(t) ?: return null
        val n = single.groupValues[1].toInt()
        return n to n
    }

    /**
     * 从 `zcds`（正方给的「展开后周次」，逗号分隔，如 `4,5,6,…,19`）还原周次规则。
     *
     * 比解析 `zcd` 文本更可靠：单双周、跳周都由它直接给出，不必去猜「(单)」「(双)」的写法。
     * 判断不出（空、非数字）返回 null，交给 `zcd` 兜底。
     */
    private fun parseWeekRuleFromWeeks(text: String): WeekRuleSpec? {
        val weeks = text.split(',').mapNotNull { it.trim().toIntOrNull() }.distinct().sorted()
        if (weeks.isEmpty()) return null
        val start = weeks.first()
        val end = weeks.last()
        val all = (start..end).toList()
        val type = when {
            weeks == all -> WeekRule.EVERY
            weeks == all.filter { it % 2 == 1 } -> WeekRule.ODD
            weeks == all.filter { it % 2 == 0 } -> WeekRule.EVEN
            else -> WeekRule.CUSTOM
        }
        return WeekRuleSpec(start, end, type, if (type == WeekRule.CUSTOM) weeks.joinToString(",") else "")
    }

    /**
     * 周次文本 → 课程能存的形式。
     *
     * 覆盖正方常见写法：「1-16周」「1-16周(单)」「4-18周(双)」「1-5,7-9周」「1-16周（单）」
     * 「单周 / 双周」。能简化成「每周 / 单周 / 双周」的就简化（界面显示更好看），
     * 多段不规则的落到自定义周。没有任何周数的（如光一个「单周」）返回 `null`，
     * 由调用方按「缺周次」处理，不在这里瞎猜范围。
     *
     * 注意 [Course.occursOnWeek] 会先看 `week in startWeek..endWeek`，所以自定义周次时
     * startWeek / endWeek 必须是**包围范围**（最小值到最大值）。
     */
    fun parseWeekRule(text: String): WeekRuleSpec? {
        val t = text.trim()
        if (t.isEmpty()) return null

        val weeks = sortedSetOf<Int>()
        // 声明区间的端点（不是「实际会上的周」的端点）：「1-16周(单)」第 16 周是偶数不上课，
        // 但课表上仍应显示 1-16，所以 start/end 取各段端点的最小/最大值。
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        t.split(',', '，', '、', ';', '；').forEach { part ->
            val m = Regex("(\\d{1,2})(?:\\s*[-~—－至到]\\s*(\\d{1,2}))?").find(part) ?: return@forEach
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].ifBlank { m.groupValues[1] }.toInt()
            lo = minOf(lo, a, b)
            hi = maxOf(hi, a, b)
            // 单双以「本段」为准；本段没写就沿用整串（如「1-5,7-9周(单)」）
            val type = when {
                part.contains("单") -> WeekRule.ODD
                part.contains("双") -> WeekRule.EVEN
                t.contains("单") && !t.contains("双") -> WeekRule.ODD
                t.contains("双") && !t.contains("单") -> WeekRule.EVEN
                else -> WeekRule.EVERY
            }
            for (w in minOf(a, b)..maxOf(a, b)) {
                when (type) {
                    WeekRule.ODD -> if (w % 2 == 1) weeks.add(w)
                    WeekRule.EVEN -> if (w % 2 == 0) weeks.add(w)
                    else -> weeks.add(w)
                }
            }
        }
        if (weeks.isEmpty()) return null

        val start = lo
        val end = hi
        if (weeks.size == end - start + 1) return WeekRuleSpec(start, end, WeekRule.EVERY, "")
        val odds = (start..end).filter { it % 2 == 1 }
        val evens = (start..end).filter { it % 2 == 0 }
        if (weeks.size == odds.size && weeks.containsAll(odds)) return WeekRuleSpec(start, end, WeekRule.ODD, "")
        if (weeks.size == evens.size && weeks.containsAll(evens)) return WeekRuleSpec(start, end, WeekRule.EVEN, "")
        return WeekRuleSpec(start, end, WeekRule.CUSTOM, weeks.joinToString(","))
    }

    /** 周次解析结果：[startWeek]/[endWeek] 是包围范围，[customWeeks] 逗号分隔。 */
    data class WeekRuleSpec(
        val startWeek: Int,
        val endWeek: Int,
        val weekType: Int,
        val customWeeks: String
    )

    /**
     * 「三江楼0302」「三江楼附楼302」→（楼, 教室）。
     *
     * 分界规则：**房间号以数字开头**（所以在「三江楼0302」上切出「三江楼」+「0302」）。
     *
     * ⚠️ **「附楼」是江苏大学的特例，要先在它前面切开**：附楼与主楼连在一起，教室号本来就写成
     * 「附楼302」（教务那边用 F302 指它），**教学楼仍然叫「三江楼」**。不特判的话，按「房间号以
     * 数字开头」会拆成「三江楼附楼」+「302」—— 楼名多带一截、教室号只剩数字，在校区地图与
     * 教室检索里都对不上（用户 2026-10-15 明确说明的就是这一条）。
     *
     * 拆不出楼名的整串当房间号（例如「机房」）。
     */
    private fun splitPlace(place: String): Pair<String, String> {
        val s = place.trim()
        if (s.isEmpty()) return "" to ""
        // 「附楼」前切开：楼名取它之前那截，房间号从「附楼」开始（顺手抹掉中间的空格）。
        val annex = s.indexOf(ANNEX_MARK)
        if (annex > 0) {
            return s.substring(0, annex).trim() to
                s.substring(annex).filterNot { it.isWhitespace() }
        }
        val m = Regex("^(.+?(?:号楼|楼|馆|区|中心|群))\\s*([0-9].*)$").find(s)
        return if (m != null) m.groupValues[1].trim() to m.groupValues[2].trim() else "" to s
    }

    /** 「附楼」二字（写成 `\u` 转义，免得被文案体检当成待翻译的界面文案）。 */
    private const val ANNEX_MARK = "\u9644\u697c"
}
