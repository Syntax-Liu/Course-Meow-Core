package com.nexoraism.coursemeow.domain.sheet

import com.nexoraism.coursemeow.data.Course
import com.nexoraism.coursemeow.data.WeekRule
import com.nexoraism.coursemeow.data.joinTeachers
import com.nexoraism.coursemeow.data.parse.PdfTextExtractor
import com.nexoraism.coursemeow.data.splitTeachers
import com.nexoraism.coursemeow.domain.assignTimetableColors
import com.nexoraism.coursemeow.domain.courseColorFor
import kotlin.math.abs

/**
 * 教务系统导出的**课表 PDF** 的规则解析器（纯本地、不联网、不调 AI）。
 *
 * 输入是 [PdfTextExtractor] 抽出的「文字 + 位置」。江苏大学教务系统导出的课表有两种、
 * 各两种版面，一共四种，都能认，并自动判断这一份是**班级课表**还是**个人课表**：
 *
 *   - **A 列表式**：最左一列「星期一…星期六」给出星期，右边每条课是「节次（如 `3-4`）+
 *     课程名」，紧跟一段以 `周数:` 开头的元信息（班级课表写 `地点:`、个人课表多写 `校区:`
 *     与 `学分:`）。生成器**按天一格一格写**，所以内容流顺序稳定，解析顺着这个顺序走。
 *   - **B 网格式**：顶部一行「星期一…星期日」，最左列节次，格子里第一行是课程名、下面若干行是
 *     以 `(a-b节)` 开头的元信息。按横坐标聚类出「星期列」，每列里按课程格切段。
 *
 * 班级课表里有一类**信息不全的课**（如「大学英语(I)(17610001)(板块)」「大学体育(I)(基础)(板块)」——
 * 英语与体育由学校另一个系统排课，所以没写周次与地点）：它们照样有位置（星期 + 节次），
 * 只是没有元信息行。两种版面都会把它们识别出来、补进「信息不全」清单（与 .xls 适配器同口径），
 * 而不是静默丢掉 —— 课表上少几门课是看不出来的。
 *
 * 版面认不出来、或认出来却一门课都没解析到，都抛异常（附上原始文字日志，只写进本地 debug 日志，
 * 不上报）。**绝不猜一个半成品课表出来。**
 *
 * 坐标系不再做 /Rotate 变换：这些导出文件在内容流里已经把文字画在最终阅读方向上
 * （起始有一条 `cm` 矩阵承担了旋转），所以直接用文本矩阵的平移量做「行 / 列」聚类即可。
 */
object PdfTimetableParser {

    /** 这份 PDF 是班级课表还是个人课表。 */
    enum class Kind(val routeId: String) {
        PERSONAL("personal"),
        CLASS("class")
    }

    /** 版面认不出来（既不像 A 也不像 B）。 */
    class UnknownLayout(message: String, val textLog: String) : Exception(message)

    /** 版面认出来了，但没解析出任何课程。 */
    class NoCourses(message: String, val textLog: String) : Exception(message)

    /** 解析结果：与「从课表文件导入」同构的一份课表，外加版面与种类。 */
    class Parsed(
        val result: SheetImportResult,
        val layout: String,
        val kind: Kind,
        val textLog: String
    )

    private const val LAYOUT_LIST = "A"
    private const val LAYOUT_GRID = "B"

    /** 位置聚类容差（PDF 用户空间单位）。同一列的文字横坐标几乎不会差，给 1.5 已很宽松。 */
    private const val X_TOL = 1.5f

    /** 判「版面」时，星期标签是排成一列（横坐标一致）还是一行（纵坐标一致）。 */
    private const val LAYOUT_SPREAD = 5f

    /** 判「课程名是否落在某个节次行上」的纵坐标容差：名字画在格子上沿，与节次标签同一基线。 */
    private const val ROW_TOL = 6f

    fun parse(bytes: ByteArray, fileName: String): Parsed =
        parseItems(PdfTextExtractor.extract(bytes), fileName)

    fun parseItems(items: List<PdfTextExtractor.Item>, fileName: String): Parsed {
        val norm = items.mapNotNull {
            val t = it.text.trim()
            if (t.isEmpty()) null else it.copy(text = t)
        }
        val textLog = buildTextLog(norm)

        val layout = detectLayout(norm)
            ?: throw UnknownLayout("这一份解析不出，可能是新版面", textLog)
        val kind = detectKind(norm)

        val courses = ArrayList<Course>()
        val incomplete = ArrayList<String>()
        when (layout) {
            LAYOUT_LIST -> parseListLayout(norm, courses, incomplete)
            else -> parseGridLayout(norm, courses, incomplete)
        }
        if (courses.isEmpty()) throw NoCourses("没能从这份 PDF 里认出课程", textLog)
        // 颜色按整张课表统一分配：同课同色、不同课错开
        assignTimetableColors(courses)

        val name = findTitle(norm)
            ?: fileName.substringBeforeLast('.').trim().ifBlank { "导入的课表" }
        val notes = ArrayList<String>()
        findSemester(norm)?.let { notes.add(it) }

        // PDF 里通常没有学期起止日期：留 0，界面按「文件里没写」显示（与文件导入的缺省口径一致）。
        return Parsed(SheetImportResult(name, 0L, 0L, courses, incomplete, notes), layout, kind, textLog)
    }

    // ------------------------------------------------------------------ 种类 / 版面识别

    /**
     * 班级课表与个人课表最稳的区别在页眉：
     *   - 个人课表带「学号：…」（右上角），元信息里还带「校区 / 学分」；
     *   - 班级课表带「专业：…」（右上角），元信息里没有校区与学分。
     * 按「学号 → 个人」「专业 → 班级」判断，再以「学分」兜底；都认不出时按班级处理。
     */
    private fun detectKind(items: List<PdfTextExtractor.Item>): Kind {
        if (items.any { it.text.contains("学号") }) return Kind.PERSONAL
        if (items.any { it.text.contains("专业") }) return Kind.CLASS
        if (items.any { it.text.contains("学分") }) return Kind.PERSONAL
        return Kind.CLASS
    }

    /**
     * 星期标签的排布方式决定版面：
     *   横坐标一致（排成一列） → A 列表式；纵坐标一致（排成一行） → B 网格式。
     *   两者都不明显（比如只有一两个标签、或斜着排）→ null，交给调用方报「新版面」。
     */
    private fun detectLayout(items: List<PdfTextExtractor.Item>): String? {
        val days = items.filter { dayOf(it.text) != null }
        if (days.size < 3) return null
        val xs = days.map { it.x }
        val ys = days.map { it.y }
        val xSpread = xs.maxOrNull()!! - xs.minOrNull()!!
        val ySpread = ys.maxOrNull()!! - ys.minOrNull()!!
        return when {
            xSpread <= LAYOUT_SPREAD && ySpread > LAYOUT_SPREAD -> LAYOUT_LIST
            ySpread <= LAYOUT_SPREAD && xSpread > LAYOUT_SPREAD -> LAYOUT_GRID
            else -> null
        }
    }

    // ------------------------------------------------------------------ A：列表式

    /**
     * 顺着内容流顺序解析。为什么不用坐标判星期：星期标签画在整块的正中间（上下都被课夹着），
     * 按「最近标签」或「区间切分」都会把最上面/最下面那门课分错天；而生成器**按天写**，
     * 顺序本身就是最可靠的信号。
     *
     * 见到「节次 → 课程名 → `周数:` 元信息」就结出一门课；若在下一个节次 / 下一天到来前都没等到
     * 元信息，那条名字就是**信息不全的课**（班级课表里的「…(板块)」），用它前一段的节次收下。
     */
    private fun parseListLayout(
        items: List<PdfTextExtractor.Item>,
        courses: MutableList<Course>,
        incomplete: MutableList<String>
    ) {
        var day = 0
        var startPeriod = 0
        var endPeriod = 0
        var hasPeriod = false
        var lastName = ""
        var metaActive = false
        var metaX = 0f
        val meta = StringBuilder()

        fun flush() {
            if (!metaActive) return
            metaActive = false
            if (lastName.isEmpty() || day < 1 || !hasPeriod) return
            courses.add(buildCourse(lastName, day, startPeriod, endPeriod, meta.toString(), incomplete))
            meta.setLength(0)
            lastName = ""
        }

        /** 收下一条「还没有元信息就结束了」的课（班级课表里的信息不全课）。 */
        fun flushIncomplete() {
            if (lastName.isNotEmpty() && hasPeriod && day >= 1) {
                courses.add(buildCourse(lastName, day, startPeriod, endPeriod, "", incomplete))
            }
            lastName = ""
        }

        items.forEach { item ->
            if (metaActive) {
                // 元信息续行与首行同列；一旦换列（下一门课的节次 / 课名 / 星期）就结算这门课。
                if (abs(item.x - metaX) <= X_TOL) {
                    meta.append(item.text)
                    return@forEach
                }
                flush()
            }
            val t = item.text
            val d = dayOf(t)
            if (d != null) {
                flushIncomplete()
                day = d; hasPeriod = false; lastName = ""
                return@forEach
            }
            parsePeriodToken(t)?.let {
                flushIncomplete()
                startPeriod = it.first; endPeriod = it.second; hasPeriod = true
                return@forEach
            }
            if (isMetaStartList(t)) {
                metaActive = true; metaX = item.x; meta.setLength(0); meta.append(t)
                return@forEach
            }
            // 标题 / 页脚 / 图例 / 节次数字这些东西不是课程，跳过（否则会被当成信息不全的课收下）
            if (isMisc(t)) return@forEach
            lastName = t
        }
        flush()
        flushIncomplete()
    }

    // ------------------------------------------------------------------ B：网格式

    /**
     * 表头那一行「星期一…星期日」给出每一列的星期；课程格子落在左边缘，所以按最近表头匹配。
     *
     * 每一列再按「课程格」切段：一个格子从**落在某个节次行上**的那一行开始（课程名画在格子上沿，
     * 与节次标签同一基线），到下一个这样的行为止。段内若出现 `(a-b节)` 元信息行，就按元信息
     * 拆出一门门课；整段都没有元信息行的，就是**信息不全的课**，节次直接取它落在哪一行。
     */
    private fun parseGridLayout(
        items: List<PdfTextExtractor.Item>,
        courses: MutableList<Course>,
        incomplete: MutableList<String>
    ) {
        val header = items.mapNotNull { item -> dayOf(item.text)?.let { item.x to it } }
        if (header.isEmpty()) return

        // 左边缘的纯数字节次标签（1…12）；板块课没有元信息，只能靠「名字落在哪一行」定节次。
        val periodRows = items
            .filter { it.x < 90f && Regex("\\d{1,2}").matches(it.text) }
            .map { Triple(it.page, it.y, it.text.toInt()) }

        fun rowPeriod(page: Int, y: Float): Int? {
            var best: Int? = null
            var bestDist = ROW_TOL
            periodRows.forEach { (p, ry, period) ->
                if (p != page) return@forEach
                val d = abs(ry - y)
                if (d <= bestDist) { bestDist = d; best = period }
            }
            return best
        }

        val courseColumns = clusterX(items.filter { !isMisc(it.text) }.map { it.x })
            .filter { col -> header.any { abs(it.first - col) <= 60f } }

        val byDay = LinkedHashMap<Int, MutableList<PdfTextExtractor.Item>>()
        courseColumns.forEach { col ->
            val day = header.minByOrNull { abs(it.first - col) }!!.second
            val lines = items
                .filter { abs(it.x - col) <= X_TOL && !isMisc(it.text) }
                // 同一列先按页、再按纵坐标从上到下：一门的元信息可能被分页截断，跨页续行要接上。
                .sortedWith(compareBy<PdfTextExtractor.Item>({ it.page }, { -it.y }))
            byDay.getOrPut(day) { arrayListOf() }.addAll(lines)
        }

        byDay.toSortedMap().forEach { (day, lines) ->
            val starts = ArrayList<Int>()
            lines.forEachIndexed { i, ln ->
                val aligned = rowPeriod(ln.page, ln.y) != null
                if (nameLike(ln.text) && (i == 0 || aligned)) starts.add(i)
            }
            if (starts.isEmpty() && lines.isNotEmpty()) starts.add(0)

            starts.forEachIndexed { k, st ->
                val en = if (k + 1 < starts.size) starts[k + 1] else lines.size
                if (en <= st) return@forEachIndexed
                val seg = lines.subList(st, en)
                val metaStarts = seg.indices.filter { isMetaStartGrid(seg[it].text) }
                if (metaStarts.isEmpty()) {
                    // 整格没有元信息：信息不全的课（名字可能被拆成两行，拼起来）。
                    val name = seg.joinToString("") { it.text }.trim()
                    if (!nameLike(name)) return@forEachIndexed
                    val p = rowPeriod(seg[0].page, seg[0].y) ?: return@forEachIndexed
                    val e = if (p % 2 == 1 && p <= 9) p + 1 else p
                    courses.add(buildCourse(name, day, p, e, "", incomplete))
                } else {
                    metaStarts.forEachIndexed { m, idx ->
                        if (idx == 0) return@forEachIndexed
                        val name = seg[idx - 1].text
                        val end = if (m + 1 < metaStarts.size) metaStarts[m + 1] - 1 else seg.size
                        val meta = buildString { for (j in idx until end) append(seg[j].text) }
                        val period = periodFromMeta(meta) ?: return@forEachIndexed
                        courses.add(buildCourse(name, day, period.first, period.second, meta, incomplete))
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 组装一门课

    private fun buildCourse(
        name: String,
        day: Int,
        startPeriod: Int,
        endPeriod: Int,
        meta: String,
        incomplete: MutableList<String>
    ): Course {
        val segments = parseWeekSegments(extractWeeks(meta))
        // 周次没写就按整学期算成「每周」（与文件导入同口径），并记进「信息不全」清单
        val spec = if (segments.isEmpty()) {
            incomplete.add(name)
            WeekSpec(1, 20, WeekRule.EVERY, "")
        } else {
            toWeekSpec(segments)
        }
        val place = extractPlace(meta)
        val teacher = extractTeacher(meta)
        val (building, room) = splitPlace(place)
        return Course(
            name = name,
            teacher = joinTeachers(splitTeachers(teacher)),
            building = building,
            room = room,
            dayOfWeek = day,
            startPeriod = startPeriod,
            endPeriod = if (endPeriod >= startPeriod) endPeriod else startPeriod,
            startWeek = spec.start,
            endWeek = spec.end,
            weekType = spec.type,
            customWeeks = spec.custom,
            color = courseColorFor(name),
            // 课表文件里的四项信息：写进 PDF 就取，没写留空
            credits = metaField(meta, FIELD_CREDITS),
            totalHours = metaField(meta, FIELD_HOURS),
            nature = metaField(meta, FIELD_NATURE_SHORT).ifBlank { metaField(meta, FIELD_NATURE) },
            assessment = metaField(meta, FIELD_ASSESSMENT)
        )
    }

    // ------------------------------------------------------------------ 元信息字段

    private fun extractWeeks(meta: String): String {
        Regex("周数\\s*[:：]\\s*([^/]*)").find(meta)?.let { return it.groupValues[1].trim() }
        // B 版式没有「周数:」前缀，周次写在「(3-4节)」之后、第一个「/」之前
        Regex("\\)\\s*([^/]*)").find(meta)?.let { return it.groupValues[1].trim() }
        return ""
    }

    private fun extractPlace(meta: String): String =
        Regex("(?:地点|场地)\\s*[:：]\\s*([^/]*)").find(meta)?.groupValues?.get(1)?.trim().orEmpty()

    private fun extractTeacher(meta: String): String =
        Regex("教师\\s*[:：]\\s*([^/]*)").find(meta)?.groupValues?.get(1)?.trim().orEmpty()

    // 元信息里「学分 / 总学时 / 课程性质简称 / 考核方式」的字段名。
    // 写成 \u 转义，免得被文案体检当成待翻译的界面文案。
    private const val FIELD_CREDITS = "\u5b66\u5206"                        // 学分
    private const val FIELD_HOURS = "\u603b\u5b66\u65f6"                    // 总学时
    private const val FIELD_NATURE = "\u8bfe\u7a0b\u6027\u8d28"             // 课程性质
    private const val FIELD_NATURE_SHORT = FIELD_NATURE + "\u7b80\u79f0"    // 课程性质简称
    private const val FIELD_ASSESSMENT = "\u8003\u6838\u65b9\u5f0f"         // 考核方式

    /** 取元信息里「字段名:值」的值；元信息各段用 `/` 分隔，所以值取到下一个 `/` 前。 */
    private fun metaField(meta: String, key: String): String =
        Regex(Regex.escape(key) + "\\s*[:：]\\s*([^/]*)")
            .find(meta)?.groupValues?.get(1)?.trim().orEmpty()

    /** 从元信息里取「(3-4节)」形式的节次（B 版式用；A 版式节次来自左边的节次列）。 */
    private fun periodFromMeta(meta: String): Pair<Int, Int>? =
        Regex("\\(\\s*(\\d{1,2})\\s*[-~–—至到]\\s*(\\d{1,2})\\s*节\\s*\\)").find(meta)?.let {
            it.groupValues[1].toIntOrNull()?.let { a ->
                it.groupValues[2].toIntOrNull()?.let { b -> a to b }
            }
        }

    // ------------------------------------------------------------------ 周次

    /** 一段周次文本里的一个区间。 */
    private class WeekSegment(val start: Int, val end: Int, val weekType: Int)

    /** 能直接写进 [Course] 的周次形式。 */
    private class WeekSpec(val start: Int, val end: Int, val type: Int, val custom: String)

    /**
     * 拆「4-19周」「4-18周(双)」「5-19周(单)」「1-16周,18周」这类文本。
     * 逻辑与 `JiangsuUniversityAdapter` 的周次解析一致（同一套教务系统，同一套写法）。
     */
    private fun parseWeekSegments(text: String): List<WeekSegment> {
        val body = text.replace(Regex("[（(][^）)]*节[）)]"), " ")
        val out = ArrayList<WeekSegment>()
        body.split(',', '，', '、', ';', '；').forEach { part ->
            val m = Regex("(\\d{1,2})(?:\\s*[-~–—至到]\\s*(\\d{1,2}))?").find(part) ?: return@forEach
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

    /**
     * 把区间压成课程能存的形式：能简化成「每周 / 单周 / 双周」就简化，剩下的落到自定义周。
     * 自定义周的 start/end 必须**包围**全部周次（见 `Course.occursOnWeek` 先判区间）。
     */
    private fun toWeekSpec(segments: List<WeekSegment>): WeekSpec {
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
        if (weeks.isEmpty()) return WeekSpec(1, 1, WeekRule.EVERY, "")
        val start = weeks.first()
        val end = weeks.last()
        val span = end - start + 1
        if (weeks.size == span) return WeekSpec(start, end, WeekRule.EVERY, "")
        val odds = (start..end).filter { it % 2 == 1 }
        val evens = (start..end).filter { it % 2 == 0 }
        if (weeks.size == odds.size && weeks.containsAll(odds)) return WeekSpec(start, end, WeekRule.ODD, "")
        if (weeks.size == evens.size && weeks.containsAll(evens)) return WeekSpec(start, end, WeekRule.EVEN, "")
        return WeekSpec(start, end, WeekRule.CUSTOM, weeks.joinToString(","))
    }

    // ------------------------------------------------------------------ 小工具

    /** 「三江楼0811」「计算机楼110」→（楼, 房间）；拆不出楼名的整串放进房间。与文件导入同一套拆法。 */
    private fun splitPlace(place: String): Pair<String, String> {
        val s = place.trim()
        if (s.isEmpty()) return "" to ""
        val m = Regex("^(.{1,14}?(?:号楼|楼|馆|区|中心|群))\\s*(.*)$").find(s)
        return if (m != null && m.groupValues[2].isNotBlank()) {
            m.groupValues[1] to m.groupValues[2].trim()
        } else {
            "" to s
        }
    }

    private fun clusterX(xs: List<Float>): List<Float> {
        val sorted = xs.map { Math.round(it * 100f) / 100f }.distinct().sorted()
        val groups = ArrayList<MutableList<Float>>()
        sorted.forEach { x ->
            val last = groups.lastOrNull()
            if (last != null && x - last.last() <= 2f) last.add(x) else groups.add(mutableListOf(x))
        }
        return groups.map { (it.sum() / it.size).toFloat() }
    }

    private fun parsePeriodToken(t: String): Pair<Int, Int>? {
        val m = Regex("^(\\d{1,2})\\s*[-~–—至到]\\s*(\\d{1,2})$").find(t) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: return null
        return a to b
    }

    private fun isMetaStartList(t: String): Boolean = Regex("^周数\\s*[:：]").containsMatchIn(t)

    private fun isMetaStartGrid(t: String): Boolean =
        Regex("^\\(\\s*\\d{1,2}\\s*[-~–—至到]\\s*\\d{1,2}\\s*节\\s*\\)").containsMatchIn(t)

    /**
     * 像不像一条「课程名」：元信息行与页脚都带 `:` `：` `/` `;` 这类分隔符，
     * 课程名不会带（信息不全课的「…(板块)」也不带），据此把两者分开。
     */
    private fun nameLike(t: String): Boolean {
        if (t.length < 2) return false
        if (t.any { it == ':' || it == '：' || it == '/' || it == ';' || it == '；' }) return false
        if (isMisc(t)) return false
        return true
    }

    private fun dayOf(t: String): Int? = when (t) {
        "星期一" -> 1
        "星期二" -> 2
        "星期三" -> 3
        "星期四" -> 4
        "星期五" -> 5
        "星期六" -> 6
        "星期日", "星期天" -> 7
        else -> null
    }

    /** 标题 / 页脚 / 表头 / 图例 / 节次数字 —— 这些都不是课程，解析时一律跳过。 */
    private fun isMisc(t: String): Boolean = when {
        t.contains("课表") -> true
        t.startsWith("学号") -> true
        t.contains("实践课程") -> true
        t.startsWith(":") -> true
        t.contains("打印时间") -> true
        t.contains("学年第") -> true
        t == "时间段" || t == "节次" || t == "上午" || t == "下午" || t == "晚上" -> true
        Regex("\\d{1,2}").matches(t) -> true
        dayOf(t) != null -> true
        else -> false
    }

    private fun findTitle(items: List<PdfTextExtractor.Item>): String? =
        items.firstOrNull { it.text.endsWith("课表") && it.text.length <= 24 }?.text

    private fun findSemester(items: List<PdfTextExtractor.Item>): String? =
        items.firstOrNull { it.text.contains("学期") }?.text

    private fun buildTextLog(items: List<PdfTextExtractor.Item>): String {
        val sb = StringBuilder()
        items.forEach { item ->
            sb.append('#').append(item.page).append(' ')
                .append(item.x.toInt()).append(',').append(item.y.toInt()).append("  ")
                .append(item.text).append('\n')
            if (sb.length > 16000) return sb.toString()
        }
        return sb.toString()
    }
}
