package com.nexoraism.coursemeow.domain

import com.nexoraism.coursemeow.data.Course
import com.nexoraism.coursemeow.data.TimeMode
import com.nexoraism.coursemeow.data.TimetableSettings
import com.nexoraism.coursemeow.data.WeekRule

/**
 * 课程时间冲突检测。
 *
 * 判定两门课程冲突需同时满足：
 * 1. 同一天上课；
 * 2. 真实起止时间区间有重叠（固定时间与自由时间统一按时间比较）；
 * 3. 上课周次存在交集（考虑单双周与自定义周）。
 */
object ConflictChecker {

    /** 返回与 [target] 存在冲突的其它课程。 */
    fun findConflicts(
        target: Course,
        all: List<Course>,
        settings: TimetableSettings
    ): List<Course> =
        all.filter { it.id != target.id && isConflict(target, it, settings) }
            .sortedWith(compareBy({ it.dayOfWeek }, { courseStartMinutes(it, settings) }))

    /** 判断两门课程是否冲突。 */
    fun isConflict(a: Course, b: Course, settings: TimetableSettings): Boolean {
        if (a.dayOfWeek != b.dayOfWeek) return false
        val ra = TimeCalc.courseTimeRange(a, settings) ?: return false
        val rb = TimeCalc.courseTimeRange(b, settings) ?: return false
        // 结束时间等于开始时间不算重叠
        if (!ra.first.isBefore(rb.second)) return false
        if (!rb.first.isBefore(ra.second)) return false
        return weeksOverlap(a, b)
    }

    /** 两门课程的上课周次是否有交集。 */
    private fun weeksOverlap(a: Course, b: Course): Boolean {
        val bw = activeWeeks(b)
        if (bw.isEmpty()) return false
        return activeWeeks(a).any { it in bw }
    }

    /** 课程实际会上课的周次集合（受单双周 / 自定义周限制）。 */
    fun activeWeeks(course: Course): Set<Int> {
        val from = maxOf(1, minOf(course.startWeek, course.endWeek))
        val to = maxOf(course.startWeek, course.endWeek)
        if (to < 1) return emptySet()
        val range = (from..to)
        return when (course.weekType) {
            WeekRule.ODD -> range.filter { it % 2 == 1 }.toSet()
            WeekRule.EVEN -> range.filter { it % 2 == 0 }.toSet()
            WeekRule.CUSTOM -> course.parseCustomWeeks().filter { it in range }.toSet()
            else -> range.toSet()
        }
    }

    /** 生成用于提示的冲突描述行，例如「高等数学（周一 第3-4节 第1-16周）」。 */
    fun describe(course: Course, settings: TimetableSettings): String {
        val day = dayName(course.dayOfWeek)
        val timeText = if (course.timeMode == TimeMode.FREE) {
            val r = TimeCalc.courseTimeRange(course, settings)
            r?.let { "${TimeCalc.formatTime(it.first)}-${TimeCalc.formatTime(it.second)}" }
                ?: "自由时间"
        } else {
            TimeCalc.periodText(course)
        }
        val weekType = when (course.weekType) {
            WeekRule.ODD -> "（单周）"
            WeekRule.EVEN -> "（双周）"
            WeekRule.CUSTOM -> "（${course.parseCustomWeeks().sorted().joinToString(",")}周）"
            else -> ""
        }
        val name = course.name.ifBlank { "未命名课程" }
        return "$name（$day $timeText 第${course.startWeek}-${course.endWeek}周$weekType）"
    }

    /** 生成用于提示的冲突描述行。 */
    private fun courseStartMinutes(course: Course, settings: TimetableSettings): Int =
        TimeCalc.courseTimeRange(course, settings)
            ?.first
            ?.let { it.hour * 60 + it.minute }
            ?: Int.MAX_VALUE

    private fun dayName(day: Int): String = when (day) {
        1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
        5 -> "周五"; 6 -> "周六"; 7 -> "周日"; else -> "未知"
    }

    // ---------------------------------------------------------------- 整张课表的检查

    /**
     * 一份课表里所有「同一时间段有两节课」的组合。
     *
     * 与 [findConflicts] 的区别：那个是「一门课和别的课」，
     * 这个是**整张课表**两两检查，用来在「改作息时间」这种会波及全部课程的操作前先看一眼。
     */
    fun findOverlaps(courses: List<Course>, settings: TimetableSettings): List<Pair<Course, Course>> {
        val out = mutableListOf<Pair<Course, Course>>()
        for (i in courses.indices) {
            for (j in i + 1 until courses.size) {
                if (isConflict(courses[i], courses[j], settings)) out += courses[i] to courses[j]
            }
        }
        return out
    }

    /**
     * 用 [after] 这套作息算一遍，改完之后会不会**多出**时间重叠。
     *
     * 只报「新的重叠」：改之前就存在的那几对（比如以前导入留下的）不拦着 ——
     * 否则用户会被一张已经坏掉的课表卡死，连作息时间都改不了。
     */
    fun newOverlaps(
        courses: List<Course>,
        before: TimetableSettings,
        after: TimetableSettings
    ): List<Pair<Course, Course>> {
        val old = findOverlaps(courses, before).map { pairKey(it.first, it.second) }.toSet()
        return findOverlaps(courses, after).filter { pairKey(it.first, it.second) !in old }
    }

    /** 重叠的一对课程用两个 id 记下来（顺序无关），用来比较「改之前有没有这一对」。 */
    private fun pairKey(a: Course, b: Course): Pair<Long, Long> =
        if (a.id <= b.id) a.id to b.id else b.id to a.id

    // ---------------------------------------------------------------- 批量写入前的整理

    /**
     * 把一批要写进课表的课程整理成「没有时间重叠」的一份。
     *
     * 规则只有一条：**一个时间段只留一节课**。按 [incoming] 的顺序逐门检查，
     * 一门课要被收下，得同时满足两件事：
     * 1. 不和已经确定收下的那几门撞（同一批数据里的重复一律只留第一门）；
     * 2. 不和 [existing] 里已有的课撞；撞了就看 [preferIncoming] ——
     *    true 表示「用新的替换」（[OverlapResolution.removed] 里会带上要让位的旧课），
     *    false 表示「保留旧的」（这门新课不进 [OverlapResolution.kept]）。
     *
     * 先查内部、再查旧的：这样被内部重复挤掉的课不会白白连累一门旧课被删。
     *
     * 导入口令这类「一次写一批」的入口都走这里，
     * 于是「课表里不会出现同一个时间段两节课」这件事只需要在一个地方保证。
     */
    fun resolve(
        incoming: List<Course>,
        existing: List<Course>,
        settings: TimetableSettings,
        preferIncoming: Boolean
    ): OverlapResolution {
        val kept = mutableListOf<Course>()
        val removed = mutableListOf<Course>()
        var skippedByExisting = 0
        var skippedByInner = 0

        incoming.forEach { course ->
            if (kept.any { isConflict(course, it, settings) }) {
                skippedByInner++
                return@forEach
            }
            val hits = existing.filter { isConflict(course, it, settings) }
            if (hits.isNotEmpty()) {
                if (preferIncoming) {
                    removed += hits
                } else {
                    skippedByExisting++
                    return@forEach
                }
            }
            kept += course
        }

        return OverlapResolution(
            kept = kept,
            removed = removed.distinctBy { it.id },
            skippedByExisting = skippedByExisting,
            skippedByInner = skippedByInner
        )
    }
}

/**
 * 批量写入的整理结果：**已经保证不会出现时间重叠**。
 *
 * [kept] 里每一门都和其它课互不冲突，[removed] 是要让位删掉的旧课，
 * 两个数字用来告诉用户「这次一共动了几门」，免得被跳过的课无人知晓。
 */
data class OverlapResolution(
    val kept: List<Course>,
    val removed: List<Course> = emptyList(),
    /** 因为和已有课程撞了而跳过的门数。 */
    val skippedByExisting: Int = 0,
    /** 因为和同一批里的另一门撞了而跳过的门数。 */
    val skippedByInner: Int = 0
) {
    /** 有没有课被跳过。 */
    val hasSkipped: Boolean get() = skippedByExisting > 0 || skippedByInner > 0
}
