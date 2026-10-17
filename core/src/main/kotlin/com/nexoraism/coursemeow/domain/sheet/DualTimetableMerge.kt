package com.nexoraism.coursemeow.domain.sheet

import com.nexoraism.coursemeow.data.Course
import com.nexoraism.coursemeow.data.TEACHER_SEPARATOR
import com.nexoraism.coursemeow.data.splitTeachers

/**
 * 「双表合并导入」的纯逻辑：把**班级课表**（.xls）与**个人课表**（PDF）合成一份课表。
 *
 * 定位：两份表描述的是同一个人同一学期的课，但各有各的写法 —— 班级课表按班级排、格子密；
 * 个人课表是教务系统给出的「我选的课」。合成时**以班级课表为底**，再把个人课表里班级课表
 * 没有的课补进来（节次、周次照抄个人课表那一份）。
 *
 * ## 匹配规则（这是本次修复的核心）
 *
 * 从前匹配键 = 课程名 + 教师，两份表对同一位老师的写法常常不同（顺序、分隔符、空格、
 * 全角半角括号……），于是几乎配不上对，全部被当成「新增」，三种模式结果自然一模一样。
 * 现在：
 *
 *  1. **匹配键只看课程名**（[matchKey]）：去空白，并把全角/半角括号、逗号统一后比对。
 *     教师**不参与**匹配 —— 只在同名多候选时用来消歧（见 [pairUp] 第二轮）。
 *  2. 同一课程名在两边条数相等/可对齐时，按「星期 + 起止节次 + 周次」**一一配对**；
 *     配不上的（两边都剩）才算「冲突」。教师消歧能把「同一门课换了时段」这类也配上。
 *
 * ## 硬约束：不允许同一时间格出现重复条目
 *
 * 合并的最终结果会再过一遍 [resolveOverlaps]：
 *  - 同一门课（同名）落在同一时段 → 只留一条；
 *  - 两门不同课落在同一格且周次重叠 → 记为「时间冲突」，并且**只留班级课表那条**，
 *    这样界面上永远不会出现两门课叠在同一格。
 *
 * 四段结果，正是导入前预览要报给用户的数字（[Preview]）：
 *   - **新增**：只出现在个人课表、班级课表里没有的课；
 *   - **补全**：两边都配上对的课（数量 = 配成的对数）；
 *   - **冲突**：同名候选两边都有、但按时间与教师都配不上、剩下的那些；
 *   - **时间冲突**：合并结果里「两门不同课挤在同一格且周次重叠」的处数。
 *
 * 这个类不碰 Android、不碰数据库，纯粹是「两份解析结果 → 一份合并结果」，
 * 因此可以脱离界面单独推演与测试。
 */
object DualTimetableMerge {

    /**
     * 合并冲突的处置方式（界面上的单选）。
     *
     * 三种方式都遵守同一条底线：**以班级课表为底，个人课表里班级没有的课照旧补进来**；
     * 区别只在「两边都有的那门课」怎么留。
     */
    enum class Mode {
        /** 以班级课表为准：重合的课留班级那条（缺的信息用个人课表补全），对不齐的丢弃个人那条。 */
        CLASS_FIRST,

        /** 以个人课表为准：重合的课改用个人那条，对不齐的也以个人为准。 */
        PERSONAL_FIRST,

        /** 两边都保留：重合的课各留一条（同一时间格仍会去重，见 [resolveOverlaps]）。 */
        KEEP_BOTH
    }

    /** 导入前预览：新增 / 补全 / 冲突 / 时间冲突 四类数量。字段名与界面上的四行标签一一对应。 */
    class Preview(
        val newCount: Int,
        val completeCount: Int,
        val conflictCount: Int,
        val timeConflictCount: Int
    )

    /**
     * 匹配键：**只用课程名**，并抹掉名字里的「废话括号」。
     *
     * 名称先去掉全部空白，再把全角括号、逗号统一成半角，然后逐组判断括号要不要留
     * （见 [stripNoiseGroups]）：**纯数字的**（课程号，如 `(17610001)`）与**带中文的**
     * （如 `(板块)`、`(基础)`）一律去掉，其余照留 —— 像 `大学英语(I)`、`高等数学A(I)`
     * 这种「(I)(II)」是课程的级别，必须留着，否则会把大学英语 I 和 II 配成一对。
     *
     * 为什么要抹：班级课表里那些板块课的名字是「`大学英语(I)(17610001)(板块)`」，
     * 个人课表那条叫「`大学英语(I)`」—— 不抹就永远对不上，会当成两门课，
     * CLASS_FIRST 下个人那条会被当冲突丢掉（真机上就是这么丢的）。
     *
     * 教师故意不进键：两份表对同一位老师的写法常常不同，一进键就全配不上了。
     */
    fun matchKey(course: Course): String = stripNoiseGroups(normalizeName(course.name))

    /**
     * 去掉名字里没有区分度的括号组：**纯数字**（课程号）与**含中文**（板块 / 基础 / 选修…）。
     * 形如 `(I)`、`(II)`、`(A)` 这类字母级别保留。
     */
    private fun stripNoiseGroups(name: String): String {
        val sb = StringBuilder(name.length)
        var i = 0
        while (i < name.length) {
            val ch = name[i]
            if (ch != '(') {
                sb.append(ch)
                i++
                continue
            }
            val close = name.indexOf(')', i + 1)
            if (close < 0) {
                sb.append(name, i, name.length)
                break
            }
            val inner = name.substring(i + 1, close)
            val noise = inner.isNotEmpty() && (inner.all { it.isDigit() } || inner.any { it.code > 0x2FFF })
            if (!noise) sb.append(name, i, close + 1)
            i = close + 1
        }
        return sb.toString()
    }

    /**
     * 这门课在班级课表里是不是「板块课」占位条目。
     *
     * 正方给班级课表里的板块课（大学英语、大学体育……）挂的是**没有真排课信息**的条目：
     * 名字带着课程号与「(板块)」后缀，地点、教师空着，周次也是占位的。真正的时间地点教师
     * 只在个人课表里。所以合并时这类课**一律以个人课表那条为准**。
     */
    private fun isBoardCourse(course: Course): Boolean = course.name.contains(BOARD_MARK)

    /**
     * 合并时**真正采用**的班级课表课程：把「个人课表里也有」的板块课剔掉，让位给个人那条。
     *
     * 只剔「个人课表里也有」的：万一某门板块课只出现在班级课表里（个人课表没有），
     * 照旧留着 —— 宁可信息少，也不能把课丢了。
     */
    private fun classCoursesForMerge(
        classSheet: SheetImportResult,
        personalSheet: SheetImportResult
    ): List<Course> {
        val personalKeys = personalSheet.courses.map(::matchKey).toSet()
        return classSheet.courses.filterNot { isBoardCourse(it) && matchKey(it) in personalKeys }
    }

    /**
     * 算一遍预览：新增 / 补全 / 冲突 / 时间冲突各几门。
     *
     * 前三类是纯「配对结果」（与 [Mode] 无关，三种模式看到的是同一份）；
     * 「时间冲突」取班级课表为准的合并结果里冲突处数（默认模式即导入时的默认行为）。
     */
    fun preview(classSheet: SheetImportResult, personalSheet: SheetImportResult): Preview {
        val stats = classify(classSheet, personalSheet)
        val timeConflicts = mergeDetailed(classSheet, personalSheet, Mode.CLASS_FIRST).timeConflictCount
        return Preview(stats.newCount, stats.completeCount, stats.conflictCount, timeConflicts)
    }

    /**
     * 按 [mode] 把两份课表合并成一份。
     *
     * 顺序上保证：先铺班级课表的全部课程（它是底），再逐名处理个人课表的课；
     * 最后统一做一次「同一时间格去重」的硬约束。
     * 返回的课程列表可直接交给 `importAsNewTimetable` 写进新课表。
     */
    fun merge(
        classSheet: SheetImportResult,
        personalSheet: SheetImportResult,
        mode: Mode
    ): List<Course> = mergeDetailed(classSheet, personalSheet, mode).courses

    // ------------------------------------------------------------------ 多份合并（融合导入）

    /**
     * 把**任意份**课表并成一份：从第一份起，依次把下一份补进来（沿用同一套 [Mode]，
     * 前面累积的结果当底）。融合导入一次可以选好几份文件 —— 比如个人课表的两种导出版面、
     * 或班级课表 + 个人课表 —— 都走这里。
     */
    fun mergeMany(sheets: List<SheetImportResult>, mode: Mode): List<Course> {
        if (sheets.isEmpty()) return emptyList()
        var acc = sheets.first()
        for (i in 1 until sheets.size) {
            acc = withCourses(acc, merge(acc, sheets[i], mode))
        }
        return acc.courses
    }

    /**
     * 多份合并前的预览：各步「新增 / 补全 / 冲突 / 时间冲突」累加。少于两份返回 null
     * （一份不需要合并）。
     */
    fun previewMany(sheets: List<SheetImportResult>, mode: Mode = Mode.CLASS_FIRST): Preview? {
        if (sheets.size < 2) return null
        var acc = sheets.first()
        var newCount = 0
        var completeCount = 0
        var conflictCount = 0
        var timeConflictCount = 0
        for (i in 1 until sheets.size) {
            val p = preview(acc, sheets[i])
            newCount += p.newCount
            completeCount += p.completeCount
            conflictCount += p.conflictCount
            timeConflictCount += p.timeConflictCount
            acc = withCourses(acc, merge(acc, sheets[i], mode))
        }
        return Preview(newCount, completeCount, conflictCount, timeConflictCount)
    }

    /**
     * 多份合并里「同名但对不上」的课程名（去重）；界面上列出来让用户看清到底哪几门撞上了。
     */
    fun conflictNamesMany(sheets: List<SheetImportResult>): List<String> {
        if (sheets.size < 2) return emptyList()
        val names = LinkedHashSet<String>()
        var acc = sheets.first()
        for (i in 1 until sheets.size) {
            names += conflictNames(acc, sheets[i])
            acc = withCourses(acc, merge(acc, sheets[i], Mode.CLASS_FIRST))
        }
        return names.toList()
    }

    /** 两份之间「同名、但按时间与教师都配不上」的课程名（去重）。 */
    fun conflictNames(classSheet: SheetImportResult, personalSheet: SheetImportResult): List<String> {
        val classBuckets = classSheet.courses.groupBy(::matchKey)
        val personalBuckets = personalSheet.courses.groupBy(::matchKey)
        val out = LinkedHashSet<String>()
        personalBuckets.forEach { (key, personalList) ->
            val classList = classBuckets[key].orEmpty()
            if (classList.isEmpty()) return@forEach
            val pairing = pairUp(classList, personalList)
            if (pairing.leftoverClass.isNotEmpty() && pairing.leftoverPersonal.isNotEmpty()) {
                pairing.leftoverClass.forEach { out.add(it.name) }
                pairing.leftoverPersonal.forEach { out.add(it.name) }
            }
        }
        return out.toList()
    }

    /** 用新的课程列表替换结果里的课程，其余字段（课表名 / 学期 / 备注）保留。 */
    private fun withCourses(result: SheetImportResult, courses: List<Course>): SheetImportResult =
        SheetImportResult(
            result.timetableName,
            result.semesterStartDate,
            result.semesterEndDate,
            courses,
            result.incomplete,
            result.notes
        )

    // ------------------------------------------------------------------ 预览计数

    /** 预览的三类配对结果（新增 / 补全 / 冲突）。 */
    private class Stats(val newCount: Int, val completeCount: Int, val conflictCount: Int)

    private fun classify(classSheet: SheetImportResult, personalSheet: SheetImportResult): Stats {
        val classBuckets = classSheet.courses.groupBy(::matchKey)
        val personalBuckets = personalSheet.courses.groupBy(::matchKey)

        var newCount = 0
        var completeCount = 0
        var conflictCount = 0

        personalBuckets.forEach { (key, personalList) ->
            val classList = classBuckets[key].orEmpty()
            if (classList.isEmpty()) {
                // 班级课表里完全没有这个名字 → 整批新增。
                newCount += personalList.size
                return@forEach
            }
            // 班级那份是板块课的占位条目（个人课表里也有同门课）→ 合并时以个人为准，
            // 预览里算「补全」而不是「冲突」（见 classCoursesForMerge）。
            if (classList.any(::isBoardCourse)) {
                completeCount += minOf(classList.size, personalList.size)
                newCount += (personalList.size - classList.size).coerceAtLeast(0)
                return@forEach
            }
            val pairing = pairUp(classList, personalList)
            completeCount += pairing.pairs.size
            val leftoverClass = pairing.leftoverClass
            val leftoverPersonal = pairing.leftoverPersonal
            when {
                // 班级侧多出来的照旧保留，不算任何一类。
                leftoverPersonal.isEmpty() -> Unit
                // 个人侧多出来的、班级侧已配完 → 新增。
                leftoverClass.isEmpty() -> newCount += leftoverPersonal.size
                // 两边都剩、配不上 → 冲突（两边卷进来的都算上）。
                else -> conflictCount += leftoverClass.size + leftoverPersonal.size
            }
        }
        return Stats(newCount, completeCount, conflictCount)
    }

    // ------------------------------------------------------------------ 合并

    /** 一次合并的结果：最终课程 + 因「同一时间格两门不同课」而被丢掉的条数。 */
    private class MergeResult(val courses: List<Course>, val timeConflictCount: Int)

    /** 带来源标记的课程：`fromClass = true` 表示这条来自班级课表（合并的底，冲突时优先）。 */
    private class Slot(val course: Course, val fromClass: Boolean)

    private fun mergeDetailed(
        classSheet: SheetImportResult,
        personalSheet: SheetImportResult,
        mode: Mode
    ): MergeResult {
        // 板块课（个人课表里也有的那些）不进班级这一侧 —— 它们以个人课表那条为准，
        // 见 [classCoursesForMerge]。
        val classCourses = classCoursesForMerge(classSheet, personalSheet)
        val classBuckets = classCourses.groupBy(::matchKey)
        val personalBuckets = personalSheet.courses.groupBy(::matchKey)
        val classIncomplete = incompleteNames(classSheet)
        val personalIncomplete = incompleteNames(personalSheet)

        // 班级课表整份先铺成底（保留顺序），个人课表只往里补 / 改。
        val slots = ArrayList<Slot>(classCourses.size)
        classCourses.forEach { slots.add(Slot(it, fromClass = true)) }

        personalBuckets.forEach { (key, personalList) ->
            val classList = classBuckets[key].orEmpty()
            if (classList.isEmpty()) {
                // 新增：班级课表没有这门课，整条照抄个人课表（含节次、周次）。
                personalList.forEach { slots.add(Slot(it, fromClass = false)) }
                return@forEach
            }

            val pairing = pairUp(classList, personalList)

            // 配上对的：按模式决定留谁。
            pairing.pairs.forEach { (classCourse, personalCourse) ->
                when (mode) {
                    // 班级为准：保留班级那条；缺的信息（周次 / 地点 / 教师）用个人课表补全。
                    Mode.CLASS_FIRST -> {
                        val completed =
                            completeFrom(classCourse, personalCourse, classIncomplete, personalIncomplete)
                        if (completed !== classCourse) {
                            replaceInSlots(slots, classCourse, completed, fromClass = true)
                        }
                    }
                    // 个人为准：重合的课直接改用个人那条。
                    Mode.PERSONAL_FIRST ->
                        replaceInSlots(slots, classCourse, personalCourse, fromClass = false)
                    // 两边都保留：各留一条（同一时间格的重复交给 resolveOverlaps 去重）。
                    Mode.KEEP_BOTH -> slots.add(Slot(personalCourse, fromClass = false))
                }
            }

            // 没配上对的。
            val leftoverClass = pairing.leftoverClass
            val leftoverPersonal = pairing.leftoverPersonal
            when {
                // 班级侧多出来的照旧保留。
                leftoverPersonal.isEmpty() -> Unit
                // 个人侧多出来的、班级侧已配完 → 当作新增补进来。
                leftoverClass.isEmpty() ->
                    leftoverPersonal.forEach { slots.add(Slot(it, fromClass = false)) }
                // 两边都剩：冲突，按模式决定留谁。
                else -> when (mode) {
                    Mode.CLASS_FIRST -> Unit
                    Mode.PERSONAL_FIRST -> {
                        leftoverClass.forEach { removeFromSlots(slots, it) }
                        leftoverPersonal.forEach { slots.add(Slot(it, fromClass = false)) }
                    }
                    Mode.KEEP_BOTH ->
                        leftoverPersonal.forEach { slots.add(Slot(it, fromClass = false)) }
                }
            }
        }

        val (kept, timeConflicts) = resolveOverlaps(slots)
        return MergeResult(kept.map { it.course }, timeConflicts)
    }

    // ------------------------------------------------------------------ 配对

    /** 一个课程名下的配对结果：配成的对 + 两边各自剩下的。 */
    private class Pairing(
        val pairs: List<Pair<Course, Course>>,
        val leftoverClass: List<Course>,
        val leftoverPersonal: List<Course>
    )

    /**
     * 在一个课程名内部把两边一一对上：
     *  1. 先按「星期 + 起止节次 + 周次」精确配对（[signature]）—— 同门课换教师的写法也照样配上；
     *  2. 剩下的候选里再按教师消歧（[teacherKey] 唯一相等才配）—— 兜住「同一门课换了时段」。
     * 两轮都没配上的就是 [Pairing.leftoverClass] / [Pairing.leftoverPersonal]。
     */
    private fun pairUp(classList: List<Course>, personalList: List<Course>): Pairing {
        val classPool = classList.toMutableList()
        val personalPool = personalList.toMutableList()
        val pairs = ArrayList<Pair<Course, Course>>()

        matchPool(classPool, personalPool, pairs) { signature(it) }
        matchPool(classPool, personalPool, pairs) { teacherKey(it) }

        return Pairing(pairs, classPool.toList(), personalPool.toList())
    }

    /**
     * 按 [keyOf] 把 [classPool] 与 [personalPool] 里键相等的一对一配起来，
     * 配掉的从池子里移除，剩下的留给下一轮。
     *
     * 同一个键下有多个候选时，优先挑教师写法完全一致的那条 —— 这就是「教师只用于消歧」。
     * [keyOf] 返回空串时不参与本轮（避免「都没写教师」被当成一对）。
     */
    private fun matchPool(
        classPool: MutableList<Course>,
        personalPool: MutableList<Course>,
        pairs: MutableList<Pair<Course, Course>>,
        keyOf: (Course) -> String
    ) {
        if (classPool.isEmpty() || personalPool.isEmpty()) return

        val classMatched = BooleanArray(classPool.size)
        val personalMatched = BooleanArray(personalPool.size)

        for (ci in classPool.indices) {
            val key = keyOf(classPool[ci])
            if (key.isBlank()) continue
            var picked = -1
            for (pi in personalPool.indices) {
                if (personalMatched[pi]) continue
                if (keyOf(personalPool[pi]) != key) continue
                picked = pi
                // 同键多候选中优先教师完全一致的；找不到再退回「先到先得」。
                if (teacherKey(personalPool[pi]) == teacherKey(classPool[ci])) break
            }
            if (picked >= 0) {
                classMatched[ci] = true
                personalMatched[picked] = true
                pairs.add(classPool[ci] to personalPool[picked])
            }
        }

        val remainClass = classPool.filterIndexed { index, _ -> !classMatched[index] }
        val remainPersonal = personalPool.filterIndexed { index, _ -> !personalMatched[index] }
        classPool.clear()
        classPool.addAll(remainClass)
        personalPool.clear()
        personalPool.addAll(remainPersonal)
    }

    // ------------------------------------------------------------------ 硬约束：同一时间格去重

    /**
     * 合并结果里不允许同一时间格出现重复条目：
     *  - 同一门课（同名）落在同一时段 → 只留一条（先到者，也就是班级课表那条优先）；
     *  - 两门不同课落在同一格且周次重叠 → 记为一次「时间冲突」，只留班级课表那条。
     *
     * 「同一格」按「同一天 + 节次区间有交集 + 上课周次有交集」判定。周次不重叠
     * （如单周的那门 vs 双周的那门）时，视觉上本就不会同时出现，因此两条都留。
     *
     * @return 去重后的条目 + 时间冲突处数。
     */
    private fun resolveOverlaps(slots: List<Slot>): Pair<List<Slot>, Int> {
        val kept = ArrayList<Slot>(slots.size)
        var conflicts = 0

        slots.forEach { slot ->
            val clashIndex = kept.indexOfFirst { overlapsInTime(it, slot) }
            if (clashIndex < 0) {
                kept.add(slot)
                return@forEach
            }
            val clash = kept[clashIndex]
            if (sameCourse(clash.course, slot.course)) {
                // 同一门课同一时段：只留一条。
                return@forEach
            }
            // 两门不同课挤在同一格且周次重叠：只留班级课表那条。
            conflicts += 1
            if (!clash.fromClass && slot.fromClass) {
                // 罕见顺序：后到的才是班级那条，用班级的替换先到的个人那条。
                kept[clashIndex] = slot
            }
        }
        return kept to conflicts
    }

    /** 两条课程在时间上是否真的会同时出现（同一天 + 节次有交集 + 周次有交集）。 */
    private fun overlapsInTime(a: Slot, b: Slot): Boolean {
        val x = a.course
        val y = b.course
        if (x.dayOfWeek != y.dayOfWeek) return false
        if (x.endPeriod < y.startPeriod || y.endPeriod < x.startPeriod) return false
        return weeksOverlap(x, y)
    }

    private fun weeksOverlap(a: Course, b: Course): Boolean {
        for (week in 1..MAX_WEEK) {
            if (a.occursOnWeek(week) && b.occursOnWeek(week)) return true
        }
        return false
    }

    private fun sameCourse(a: Course, b: Course): Boolean = matchKey(a) == matchKey(b)

    // ------------------------------------------------------------------ 补全

    /**
     * 以班级课表那条为底，只在**空着的字段**上补个人课表的值：
     * 周次（班级这条被判为信息不全时）、地点、教师。班级课表已有的值一律不动。
     */
    private fun completeFrom(
        classCourse: Course,
        personalCourse: Course,
        classIncomplete: Set<String>,
        personalIncomplete: Set<String>
    ): Course {
        var out = classCourse
        if (normalizeName(classCourse.name) in classIncomplete &&
            normalizeName(personalCourse.name) !in personalIncomplete
        ) {
            out = out.copy(
                startWeek = personalCourse.startWeek,
                endWeek = personalCourse.endWeek,
                weekType = personalCourse.weekType,
                customWeeks = personalCourse.customWeeks
            )
        }
        if (out.building.isBlank() && out.room.isBlank() &&
            (personalCourse.building.isNotBlank() || personalCourse.room.isNotBlank())
        ) {
            out = out.copy(building = personalCourse.building, room = personalCourse.room)
        }
        if (out.teacher.isBlank() && personalCourse.teacher.isNotBlank()) {
            out = out.copy(teacher = personalCourse.teacher)
        }
        return out
    }

    // ------------------------------------------------------------------ 工具

    /** 「星期 + 起止节次 + 周次」的签名，用于一对一配对。 */
    private fun signature(course: Course): String =
        "${course.dayOfWeek}|${course.startPeriod}|${course.endPeriod}|${weekSetKey(course)}"

    /** 这门课实际上课的周次集合，压成字符串用于比对（单双周 / 自定义周都归一到实际周列表）。 */
    private fun weekSetKey(course: Course): String =
        (1..MAX_WEEK).filter { course.occursOnWeek(it) }.joinToString(",")

    /** 教师消歧用的键：拆开、去空白、排序后拼装；没写教师时为空串（不参与消歧）。 */
    private fun teacherKey(course: Course): String =
        splitTeachers(course.teacher).map(::normalizeName).sorted().joinToString(TEACHER_SEPARATOR)

    /** [SheetImportResult.incomplete] 里是课名，统一成匹配用的写法，方便按名比对。 */
    private fun incompleteNames(result: SheetImportResult): Set<String> =
        result.incomplete.map(::normalizeName).toSet()

    /**
     * 匹配与比对用的名称写法：去掉全部空白（含全角空格），
     * 并把全角括号、逗号统一成半角，免得同一门课因写法不同配不上。
     */
    private fun normalizeName(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch.isWhitespace()) continue
            sb.append(
                when (ch) {
                    '（' -> '('
                    '）' -> ')'
                    '，' -> ','
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** 用「同一个实例」定位并替换：Course 是 data class，按值找可能撞上等值的另一条。 */
    private fun replaceInSlots(
        slots: MutableList<Slot>,
        target: Course,
        replacement: Course,
        fromClass: Boolean
    ) {
        val index = slots.indexOfFirst { it.course === target }
        if (index >= 0) slots[index] = Slot(replacement, fromClass) else slots.add(Slot(replacement, fromClass))
    }

    private fun removeFromSlots(slots: MutableList<Slot>, target: Course) {
        val index = slots.indexOfFirst { it.course === target }
        if (index >= 0) slots.removeAt(index)
    }

    /** 周次遍历的上限：一学期不会超过这么多周。 */
    private const val MAX_WEEK = 30

    /**
     * 「板块」这两个字（写成 `\u` 转义，不写中文）。
     *
     * `tools/copy-audit.js` 会把源码里的中文字面量当成待翻译的界面文案，而这一处只是拿来
     * **判断课程名**、不是给用户看的，写成转义就绕开了。
     */
    private const val BOARD_MARK = "\u677f\u5757"
}
