package com.nexoraism.coursemeow.domain

import com.nexoraism.coursemeow.data.Course
import com.nexoraism.coursemeow.data.TimeMode
import com.nexoraism.coursemeow.data.TimetableSettings
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** 一次课程的完整时间定位（用于提醒与常驻通知）。 */
data class ClassSession(
    val course: Course,
    val date: LocalDate,
    val start: LocalDateTime,
    val end: LocalDateTime
)

/** 周视图里的一列：星期几（1 = 周一 … 7 = 周日）与这一列对应的日期。 */
data class WeekViewDay(val dayOfWeek: Int, val date: LocalDate)

object TimeCalc {

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    // ---------- 周次 / 日期 ----------

    /** 计算 [date] 处于开学以来的第几周（第 1 周从 semesterStart 所在周一算起）。 */
    fun currentWeekNumber(semesterStart: Long, date: LocalDate = LocalDate.now()): Int {
        val start = toDate(semesterStart).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val diff = ChronoUnit.DAYS.between(start, date)
        return (diff / 7).toInt() + 1
    }

    /** 第 [week] 周的周一日期。 */
    fun mondayOfWeek(semesterStart: Long, week: Int): LocalDate =
        toDate(semesterStart).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks((week - 1).toLong())

    /** 某周内从周一到周日的日期列表。 */
    fun weekDates(semesterStart: Long, week: Int): List<LocalDate> {
        val monday = mondayOfWeek(semesterStart, week)
        return (0L..6L).map { monday.plusDays(it) }
    }

    /**
     * 第 [week] 周在周视图里显示的 7 天（未按「显示周末」开关过滤）。
     *
     * 这是「列顺序 + 每列日期」的唯一来源：表头、课程格子、点击落点与顶部区间标签
     * （[weekTitle]）都从它取数，避免分开算之后再次对不上。
     *
     * 一周固定从**周一**排到周日：曾短暂做过「一周开始日可选周日」，因为要让周日排在最前
     * 就得把整段日期往前挪一天，于是「第 N 周」这一屏里会出现上一天的课、周日当天打开
     * 「本周」反而看不到今天 —— 周次编号、提醒与小组件都按周一算，这个错位不值得，已撤掉。
     */
    fun weekViewDays(semesterStart: Long, week: Int): List<WeekViewDay> {
        val monday = mondayOfWeek(semesterStart, week)
        return (0L..6L).map { offset -> WeekViewDay(offset.toInt() + 1, monday.plusDays(offset)) }
    }

    /**
     * 学期总周数：由开学日期与结束日期推算。
     * 结束日期未设置时返回 null（表示不限制）。
     */
    fun totalWeeks(semesterStart: Long, semesterEnd: Long): Int? {
        if (semesterStart <= 0L || semesterEnd <= 0L) return null
        val monday = toDate(semesterStart).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastSunday = toDate(semesterEnd).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        if (lastSunday.isBefore(monday)) return null
        return (ChronoUnit.DAYS.between(monday, lastSunday) / 7).toInt() + 1
    }

    /**
     * 毫秒时间戳 → 本地日期。
     *
     * 注意这里**不能**写成 `LocalDate.ofInstant(...)`：那个方法是 Java 9 才加的，
     * 安卓到 14（API 34）才提供。我们最低支持 8.0（API 26），在安卓 8~13 上
     * 调用它会抛 NoSuchMethodError —— 而且是启动时就算周次，所以直接闪退。
     * `Instant.atZone(...).toLocalDate()` 是 Java 8 就有的，API 26 起可用。
     */
    fun toDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    // ---------- 课程筛选 ----------

    /**
     * 某一天的课程（按实际上课时间排序）。
     *
     * [forReminder] 区分「显示用」与「推送用」两种取数场景——两者对「淡化」的处理相反：
     * - 显示（false，默认）：淡化是「照常显示、画成灰的」，这里必须把课返回，否则界面就空了；
     * - 推送（true）：只要这一天是节假日（隐藏或淡化都算），当天一律不排提醒也不推送，
     *   所以淡化在推送侧也要当作没有课 —— 用户反馈的正是「淡化仍会推」。
     * 判定仍然只走 [HolidayCalendar.courseDisplayOn] 这一个入口，不另起一套。
     */
    fun coursesOnDate(
        courses: List<Course>,
        settings: TimetableSettings,
        date: LocalDate,
        forReminder: Boolean = false
    ): List<Course> {
        // 节假日当天的课程：选「隐藏」时这里直接没有课；
        // 选「淡化」时课照常返回、由界面画成灰的（判定统一走 HolidayCalendar.courseDisplayOn）。
        // 周六日原本有课的情况不属于节假日，正常显示；用户自己加的补课日会覆盖放假判定，
        // 见 HolidayCalendar.activeHolidayDates
        val mode = HolidayCalendar.courseDisplayOn(date)
        if (mode == HolidayCourseMode.HIDDEN) return emptyList()
        // 淡化在界面上要照常显示，但推送侧不能推：只有 forReminder 时才把淡化也当作没有课
        if (forReminder && mode == HolidayCourseMode.DIMMED) return emptyList()
        val week = currentWeekNumber(settings.semesterStartDate, date)
        // 补课日按「要补的那一天」上课：例如周六补周三的课，这天就显示周三的课程
        val day = settings.customMakeupDays[date.toString()] ?: date.dayOfWeek.value
        return courses
            .filter { it.dayOfWeek == day && it.occursOnWeek(week) }
            .sortedBy { courseTimeRange(it, settings)?.first ?: LocalTime.MAX }
    }

    /**
     * 当天的课程 + 晚自习虚拟条目（**界面取数入口**）。
     *
     * 周视图 / 日视图 / 主页「正在上」栏 / 桌面小组件都用它；提醒排期
     * （[upcomingSessions]）与自动静音仍走 [coursesOnDate]，所以晚自习不排提醒、不静音。
     */
    fun coursesOnDatePlanning(
        courses: List<Course>,
        settings: TimetableSettings,
        date: LocalDate
    ): List<Course> {
        val base = coursesOnDate(courses, settings, date)
        val hall = StudyHall.synthesize(
            settings = settings,
            date = date,
            todayCourses = base,
            tomorrowHasClass = hasCoursesOn(courses, settings, date.plusDays(1))
        ) ?: return base
        return (base + hall).sortedBy { courseTimeRange(it, settings)?.first ?: LocalTime.MAX }
    }

    /**
     * 这一天有没有课（只看有没有，不排序、不解析时间）。
     *
     * 与 [coursesOnDate] 的过滤口径一致：节假日「隐藏」视为没课，补课日按要补的那天算。
     * 晚自习的「次日有课」判定只需要一个布尔值 —— 周视图每页要对 7 天各判一次，
     * 走 [coursesOnDate] 会顺带排一次序、解析一堆时间字符串，翻页时纯属浪费。
     */
    fun hasCoursesOn(courses: List<Course>, settings: TimetableSettings, date: LocalDate): Boolean {
        if (HolidayCalendar.courseDisplayOn(date) == HolidayCourseMode.HIDDEN) return false
        val week = currentWeekNumber(settings.semesterStartDate, date)
        val day = settings.customMakeupDays[date.toString()] ?: date.dayOfWeek.value
        return courses.any { it.dayOfWeek == day && it.occursOnWeek(week) }
    }

    /** 某周内每天的课程，返回 Map<dayOfWeek(1..7), List<Course>>。 */
    fun coursesPerDayOfWeek(
        courses: List<Course>,
        settings: TimetableSettings,
        week: Int
    ): Map<Int, List<Course>> {
        val result = mutableMapOf<Int, List<Course>>()
        for (day in 1..7) {
            result[day] = courses
                .filter { it.dayOfWeek == day && it.occursOnWeek(week) }
                .sortedBy { courseTimeRange(it, settings)?.first ?: LocalTime.MAX }
        }
        return result
    }

    // ---------- 时间 ----------

    fun parseTime(raw: String?): LocalTime? =
        raw?.let { runCatching { LocalTime.parse(it.trim(), timeFmt) }.getOrNull() }

    fun isValidTime(raw: String): Boolean = parseTime(raw) != null

    /**
     * 课程真实的上 / 下课时间。
     *
     * - 固定时间：取首节课开始、末节课结束的时间；开了「按教学楼自定义节次时间」时，
     *   按这节课所在教学楼的作息取（没单独设置的楼仍用默认作息）；
     * - 自由时间：直接使用自定义的起止时间。
     */
    fun courseTimeRange(
        course: Course,
        settings: TimetableSettings
    ): Pair<LocalTime, LocalTime>? {
        if (course.timeMode == TimeMode.FREE) {
            val s = parseTime(course.freeStartTime) ?: return null
            val e = parseTime(course.freeEndTime) ?: return null
            return if (!e.isBefore(s)) s to e else null
        }
        val (starts, ends) = settings.timesForBuilding(course.building)
        val start = parseTime(starts.getOrNull(course.startPeriod - 1))
        val end = parseTime(ends.getOrNull(course.endPeriod - 1))
        return if (start != null && end != null && !end.isBefore(start)) start to end else null
    }

    /**
     * 课程在周视图网格中占据的节次行范围（闭区间，从 1 开始）。
     *
     * 自由时间的课程会按其真实起止时间映射到对应的节次行。
     */
    fun gridRowSpan(course: Course, settings: TimetableSettings): IntRange {
        val count = maxOf(1, settings.periodCount)
        if (course.timeMode != TimeMode.FREE) {
            val s = course.startPeriod.coerceIn(1, count)
            val e = course.endPeriod.coerceIn(s, count)
            return s..e
        }
        val range = courseTimeRange(course, settings)
            ?: return course.startPeriod.coerceIn(1, count)..course.endPeriod.coerceIn(1, count)

        val rows = minOf(count, settings.periodStartTimes.size, settings.periodEndTimes.size)
        if (rows <= 0) return 1..1

        var startRow = 1
        for (i in 1..rows) {
            val rowStart = parseTime(settings.periodStartTimes[i - 1]) ?: continue
            if (!rowStart.isAfter(range.first)) startRow = i
        }
        var endRow = startRow
        for (i in startRow..rows) {
            endRow = i
            val rowEnd = parseTime(settings.periodEndTimes[i - 1]) ?: continue
            if (!rowEnd.isBefore(range.second)) break
        }
        return startRow..endRow
    }

    // ---------- 当前 / 下一节课 ----------

    /** 当前正在上的课（时间处于 [start, end) 区间内）。 */
    fun currentClass(
        courses: List<Course>,
        settings: TimetableSettings,
        now: LocalDateTime = LocalDateTime.now()
    ): Course? = firstOngoing(coursesOnDate(courses, settings, now.toLocalDate()), settings, now)

    /** 与 [currentClass] 同义，但课程按「含晚自习」的规划视图取数（主页「正在上」栏用）。 */
    fun currentClassPlanning(
        courses: List<Course>,
        settings: TimetableSettings,
        now: LocalDateTime = LocalDateTime.now()
    ): Course? = firstOngoing(coursesOnDatePlanning(courses, settings, now.toLocalDate()), settings, now)

    private fun firstOngoing(
        dayCourses: List<Course>,
        settings: TimetableSettings,
        now: LocalDateTime
    ): Course? {
        val time = now.toLocalTime()
        return dayCourses.firstOrNull {
            courseTimeRange(it, settings)?.let { (s, e) -> !time.isBefore(s) && time.isBefore(e) }
                ?: false
        }
    }

    /** 今天接下来要上的课（上课时间在当前时间之后的第一节）。 */
    fun nextClassToday(
        courses: List<Course>,
        settings: TimetableSettings,
        now: LocalDateTime = LocalDateTime.now()
    ): Course? = firstUpcoming(coursesOnDate(courses, settings, now.toLocalDate()), settings, now)

    /** 与 [nextClassToday] 同义，但课程按「含晚自习」的规划视图取数。 */
    fun nextClassTodayPlanning(
        courses: List<Course>,
        settings: TimetableSettings,
        now: LocalDateTime = LocalDateTime.now()
    ): Course? = firstUpcoming(coursesOnDatePlanning(courses, settings, now.toLocalDate()), settings, now)

    private fun firstUpcoming(
        dayCourses: List<Course>,
        settings: TimetableSettings,
        now: LocalDateTime
    ): Course? {
        val time = now.toLocalTime()
        return dayCourses.firstOrNull {
            courseTimeRange(it, settings)?.let { (s, _) -> s.isAfter(time) } ?: false
        }
    }

    /** 未来若干天内最早开始的几节课（用于提醒排期）。 */
    fun upcomingSessions(
        courses: List<Course>,
        settings: TimetableSettings,
        from: LocalDateTime,
        days: Int = 7
    ): List<ClassSession> {
        val list = mutableListOf<ClassSession>()
        for (d in 0 until days) {
            val date = from.toLocalDate().plusDays(d.toLong())
            // 提醒排期只看「真正会上课」的日期：节假日（隐藏或淡化）当天不排任何提醒，
            // 所以这里传 forReminder = true，让淡化也按没有课处理
            val dayCourses = coursesOnDate(courses, settings, date, forReminder = true)
            for (c in dayCourses) {
                val (startTime, endTime) = courseTimeRange(c, settings) ?: continue
                val start = LocalDateTime.of(date, startTime)
                val end = LocalDateTime.of(date, endTime)
                if (!start.isBefore(from)) {
                    list += ClassSession(c, date, start, end)
                }
            }
        }
        return list.sortedBy { it.start }
    }

    // ---------- 展示 ----------

    fun formatTime(t: LocalTime): String = t.format(timeFmt)

    /**
     * 顶部那行区间标签（如「第 3 周 10.6 - 10.12」）。
     *
     * 起止日期与周视图这一屏显示的 7 天一致（见 [weekViewDays]）。
     * 周日起始是「周日 - 周六」（首尾都比周一起始整体早一天），与实际画出来的那一屏一致。
     */
    fun weekTitle(settings: TimetableSettings, week: Int): String {
        val days = weekViewDays(settings.semesterStartDate, week)
        val m = DateTimeFormatter.ofPattern("M.d")
        return "第 $week 周  ${days.first().date.format(m)} - ${days.last().date.format(m)}"
    }

    /** 课程节次描述。 */
    fun periodText(course: Course): String =
        if (course.startPeriod == course.endPeriod) "第${course.startPeriod}节"
        else "第${course.startPeriod}-${course.endPeriod}节"
}
