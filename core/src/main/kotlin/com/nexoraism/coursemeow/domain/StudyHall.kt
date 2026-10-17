package com.nexoraism.coursemeow.domain

import com.nexoraism.coursemeow.data.Course
import com.nexoraism.coursemeow.data.TimeMode
import com.nexoraism.coursemeow.data.TimetableSettings
import com.nexoraism.coursemeow.data.WeekRule
import java.time.LocalDate

/** 晚自习的合成规则（对应设置项 [TimetableSettings.studyHallRule]）。 */
enum class StudyHallRule(val id: Int) {
    /** 关闭：不合成晚自习。 */
    OFF(0),

    /** 次日有课（补课日按开关决定算不算）才上晚自习。 */
    NEXT_DAY_HAS_CLASS(1),

    /** 按星期固定：星期命中就上，不看次日。 */
    FIXED_DAYS(2);

    companion object {
        fun of(id: Int): StudyHallRule = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/**
 * 晚自习：一个按规则合成的「虚拟课程」。
 *
 * 大学晚自习不是每天都有，所以**不落库**，而是取数时现算：每到一天，判断这天该不该
 * 上晚自习，该上就往当天的课程列表里塞一条 id = -1 的虚拟条目。
 *
 * 判定按下面的顺序短路（命中就返回），参考高校通行口径：
 *
 * 1. **生效周次**：不在 `studyHallStartWeek..studyHallEndWeek` 内 → 不上
 *    （新生常因军训延后启动、学期末提前结束）；
 * 2. **强制停名单**：日期在 `studyHallForceOffDates` 里 → 不上（极端天气、校级活动）；
 * 3. **强制开名单**：日期在 `studyHallForceOnDates` 里 → 跳过星期与次日检查，
 *    直接判「上」（校运会补自习这类临时安排）；但如果当晚确实有课，仍然不叠加；
 * 4. **生效星期**：星期不在 `studyHallDays` 里 → 不上；
 * 5. **规则**：
 *    - [StudyHallRule.NEXT_DAY_HAS_CLASS]：明天的课程由 [TimeCalc.coursesOnDate] 算好
 *      （节假日、补课日都已处理），为空 → 不上；开了「补课日算有课」时补课日也算；
 *    - [StudyHallRule.FIXED_DAYS]：不看次日；
 * 6. **今晚时段内已有课** → 不上（有课就正常上那节课，不叠一个晚自习）。
 *
 * 虚拟条目 id = -1，绝不能进任何以 courseId 为主键的路径：注释按 id 取值，
 * -1 不会命中任何真实注释；提醒排期走 [TimeCalc.upcomingSessions]，它不过这里，
 * 所以晚自习不排提醒、也不会被自动静音。
 */
object StudyHall {

    /** 虚拟条目的 id（真实课程 id 从 1 起，不会撞）。 */
    const val VIRTUAL_ID = -1L

    /** 显示用的名字。 */
    const val NAME = "晚自习"

    /** 虚拟条目「不受周次限制」用的哨兵周次。 */
    private const val VIRTUAL_END_WEEK = 999

    /**
     * 合成当天的晚自习；不该上返回 null。
     *
     * [todayCourses] 由调用方按当天的课程算好传进来；
     * [tomorrowHasClass] 是「明天有没有课」（节假日、补课日都已处理）。
     *
     * **刻意只收一个布尔值而不是明天的课程列表**：周视图每翻一页都要对 7 天各判一次，
     * 如果每天都重新筛一遍全表并排序（还带 LocalTime 解析），翻页时就是白烧的。
     */
    fun synthesize(
        settings: TimetableSettings,
        date: LocalDate,
        todayCourses: List<Course>,
        tomorrowHasClass: Boolean
    ): Course? {
        val rule = StudyHallRule.of(settings.studyHallRule)
        if (rule == StudyHallRule.OFF) return null

        // 1. 生效周次
        val week = TimeCalc.currentWeekNumber(settings.semesterStartDate, date)
        if (week < settings.studyHallStartWeek) return null
        if (settings.studyHallEndWeek > 0 && week > settings.studyHallEndWeek) return null

        val key = date.toString()
        // 2. 强制停
        if (key in settings.studyHallForceOffDates) return null

        // 3. 强制开：跳过星期与次日检查
        val forced = key in settings.studyHallForceOnDates
        if (!forced) {
            // 4. 生效星期
            if (date.dayOfWeek.value !in settings.studyHallDays) return null
            // 5. 次日有课
            if (rule == StudyHallRule.NEXT_DAY_HAS_CLASS) {
                val tomorrow = date.plusDays(1)
                val tomorrowIsMakeup = settings.customMakeupDays.containsKey(tomorrow.toString())
                val counted = tomorrowHasClass &&
                    !(tomorrowIsMakeup && !settings.studyHallCountMakeupDays)
                if (!counted) return null
            }
        }

        // 6. 今晚时段内已经有课就跳过（按周视图里占的节次行判断，与显示一致）
        val count = maxOf(1, settings.periodCount)
        val from = settings.studyHallStartPeriod.coerceIn(1, count)
        val to = settings.studyHallEndPeriod.coerceIn(from, count)
        val occupied = todayCourses.any { course ->
            val span = TimeCalc.gridRowSpan(course, settings)
            span.first <= to && span.last >= from
        }
        if (occupied) return null

        return Course(
            id = VIRTUAL_ID,
            name = NAME,
            teacher = "",
            building = "",
            room = "",
            dayOfWeek = date.dayOfWeek.value,
            startPeriod = from,
            endPeriod = to,
            startWeek = 1,
            endWeek = VIRTUAL_END_WEEK,
            weekType = WeekRule.EVERY,
            color = settings.studyHallColor,
            timeMode = TimeMode.FIXED
        )
    }
}
