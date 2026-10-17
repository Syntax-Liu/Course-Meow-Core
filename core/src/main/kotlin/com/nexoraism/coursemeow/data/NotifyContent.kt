package com.nexoraism.coursemeow.data

/**
 * 通知正文里的一个片段，目前只有「变量」这一种。
 *
 * - [Variable]：值取自课程数据（时间、地点、教师、教材……），用户只能排序和开关，改不了文字。
 *
 * 变量**不允许重复出现**：一个变量的 id 唯一对应一处数据来源，出现两次只会把同一句话
 * 重复一遍，而且「勾选」与「顺序」两套状态会互相打架（同一个 id 在两处有不同开关值）。
 * 因此 [com.nexoraism.coursemeow.data.TimetableSettings.notifySegments] 里每个变量 id 最多一份。
 */
sealed interface NotifySegment {
    data class Variable(val id: String) : NotifySegment
}

/**
 * 能排序的变量 id，按引入本功能前的通知正文先后排列。
 *
 * 临时教室与停课刻意不在其中：
 * - **临时教室**不是「额外显示一行」，而是**顶替**地点那一行，单独列出来只会让人误会；
 * - **停课**的节次根本不发提醒，不存在「要不要显示」的问题。
 */
val NotifyVariableIds: List<String> = listOf(
    "name",
    "time",
    "location",
    "teacher",
    NoteKind.DEVICE.id,
    NoteKind.TEXTBOOK.id,
    NoteKind.HOMEWORK.id,
    NoteKind.EXAM.id,
    NoteKind.IMPORTANCE.id,
    NoteKind.WEATHER.id,
    NoteKind.CUSTOM.id
)

/** 变量在界面上的名字（「课程名 / 时间 / 地点 / 教师」以及各类注释的现有叫法）。 */
fun notifyVarLabel(id: String): String = when (id) {
    "name" -> "课程名"
    "time" -> "时间"
    "location" -> "地点"
    "teacher" -> "教师"
    else -> NoteKind.of(id)?.label() ?: id
}

/**
 * 「通知内容」实时预览用的示例值。
 *
 * 必须与 [com.nexoraism.coursemeow.notification.AppNotifications.postSampleReminder] 里那套
 * 示例数据一致，否则预览和真发出去的示例提醒会对不上；这里给的是**展示文本**，
 * 重要程度 / 天气已经翻成人看的话，而示例提醒里存的是原始值交回正文组装时再翻。
 */
fun notifySampleValue(settings: TimetableSettings, varId: String): String {
    val start = settings.periodStartTimes.getOrNull(0).orEmpty().ifBlank { "08:00" }
    val end = settings.periodEndTimes.getOrNull(1).orEmpty().ifBlank { "09:40" }
    return when (varId) {
        "name" -> "示例课程"
        "time" -> "$start-$end"
        "location" -> "第一教学楼 302"
        "teacher" -> "王老师"
        NoteKind.TEXTBOOK.id -> "高等数学 上册"
        NoteKind.DEVICE.id -> "计算器、实验服"
        NoteKind.HOMEWORK.id -> "习题 3.1 - 3.5"
        NoteKind.EXAM.id -> "期中考试"
        NoteKind.IMPORTANCE.id -> "中"
        // 天气与「示例提醒」共用同一个示例值，并按用户选的温度单位换算，
        // 保证预览与真发出去的提醒逐字一致（见 SAMPLE_WEATHER）。
        NoteKind.WEATHER.id -> SAMPLE_WEATHER.text(settings.temperatureUnit)
        NoteKind.CUSTOM.id -> "别忘了带作业本"
        else -> ""
    }
}
