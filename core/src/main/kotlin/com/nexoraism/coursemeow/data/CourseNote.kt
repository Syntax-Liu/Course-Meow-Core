package com.nexoraism.coursemeow.data

import androidx.room.Entity
import androidx.room.Index

/**
 * 注释的输入形态。
 *
 * 决定编辑界面上给什么样的控件，也决定存储时 [CourseNote.value] 里放什么。
 */
enum class NoteInput {
    /** 自由文本。 */
    TEXT,

    /** 三档等级（见 [ImportanceLevel]），存的是等级的 id。 */
    LEVEL,

    /** 开关，存 "1" / "0"。 */
    FLAG
}

/**
 * 注释类型。
 *
 * [permanentByDefault] 是**系统默认**的作用域：
 * - `true`  跟随课程：填一次，这门课的所有节次都带上；
 * - `false` 只作用于当前节次：在哪一节填的，就只有那一节有。
 *
 * 用户在设置里可以逐个改这个默认值（见
 * [TimetableSettings.noteDefaultsPermanent]），改的只是「新建时默认选哪边」，
 * 已经填好的注释会跟着它自己记录的作用域走，不会因为改设置而搬家。
 */
enum class NoteKind(
    val id: String,
    val permanentByDefault: Boolean,
    val input: NoteInput,
    /**
     * 由系统自动写入、用户改不了的类型（目前只有天气）。
     *
     * 这类不进「备注」编辑页的可填清单，也不进「默认作用范围」设置 ——
     * 给一个自己会变、又不让改的输入框，只会让人以为它坏了。
     */
    val systemWritten: Boolean = false
) {
    /** 教材。 */
    TEXTBOOK("textbook", true, NoteInput.TEXT),

    /** 设备（要带的东西）。 */
    DEVICE("device", true, NoteInput.TEXT),

    /** 重要程度。 */
    IMPORTANCE("importance", true, NoteInput.LEVEL),

    /** 作业。 */
    HOMEWORK("homework", false, NoteInput.TEXT),

    /** 临时教室：填了之后这一次课的地点用它，而不是课程本身的地点。 */
    ROOM("room", false, NoteInput.TEXT),

    /** 考试。 */
    EXAM("exam", false, NoteInput.TEXT),

    /** 停课：这一次课不上。 */
    CANCELLED("cancelled", false, NoteInput.FLAG),

    /** 自定义备注：和课程编辑页的「备注」是同一个东西（见 [Course.note]）。 */
    CUSTOM("custom", false, NoteInput.TEXT),

    /**
     * 天气：这一节课开始时的天气，由后台任务自动获取并写进来，用户改不了。
     *
     * 只作用于「这一次课」，所以永远按日期存，不写「跟随课程」那一层 ——
     * 整门课共用一份天气没有意义。
     */
    WEATHER("weather", false, NoteInput.TEXT, systemWritten = true);

    companion object {
        fun of(id: String): NoteKind? = entries.firstOrNull { it.id == id }

        /** 会占用通知正文的注释类型（按通知里出现的先后顺序）。 */
        val notifyOrder: List<NoteKind> = listOf(
            ROOM, DEVICE, TEXTBOOK, HOMEWORK, EXAM, IMPORTANCE, WEATHER, CUSTOM
        )

        /** 用户能自己填的类型（编辑界面按这个清单出输入框）。 */
        val editable: List<NoteKind> = entries.filterNot { it.systemWritten }
    }
}

/** 重要程度的三档。存 id，展示时按界面语言翻。 */
enum class ImportanceLevel(val id: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low");

    companion object {
        fun of(id: String): ImportanceLevel? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 一条注释。
 *
 * 主键是「课程 + 类型 + 作用域」三件套：
 * - [scopeKey] 为空串表示**跟随课程**（永久），这门课的所有节次共用；
 * - 否则是某一天的日期（`yyyy-MM-dd`），表示**只作用于那一次课**（临时）。
 *
 * 取值时先找当天那条，找不到再退回跟随课程的那条 —— 于是「临时」天然覆盖「永久」，
 * 这也正是用户直觉里「这一次改一下，别动整门课」的语义。
 *
 * 同类型同作用域只会有一条：用户改来改去都是覆盖写，不会越攒越多。
 */
@Entity(
    tableName = "course_notes",
    primaryKeys = ["courseId", "kind", "scopeKey"],
    indices = [Index("courseId")]
)
data class CourseNote(
    val courseId: Long,
    /** 见 [NoteKind.id]。 */
    val kind: String,
    /** 空串 = 跟随课程；否则为 `yyyy-MM-dd`。 */
    val scopeKey: String,
    val value: String = "",
    val updatedAt: Long = 0L
)

/** 「跟随课程」的作用域键。 */
const val NOTE_SCOPE_COURSE = ""

/** 某一次课（某个日期）的作用域键。 */
fun noteScopeOf(date: java.time.LocalDate): String = date.toString()

/**
 * 一次课上真正生效的注释。
 *
 * [permanent] 为 true 表示这条注释来自「跟随课程」，false 表示来自「这一次课」。
 */
data class ResolvedNote(
    val kind: NoteKind,
    val value: String,
    val permanent: Boolean
) {
    val isBlank: Boolean get() = value.isBlank()
}

/**
 * 把某个日期上的一堆注释，按「临时覆盖永久」的规则摊平成每类一份。
 *
 * @param notes 这门课的全部注释；调用方不用自己筛日期。
 * @param date  要解析的那一次课的日期；传 null 表示只看「跟随课程」那一层
 *              （课程列表、课程详情这类没有具体日期的场景用）。
 */
fun resolveNotes(notes: List<CourseNote>, date: java.time.LocalDate? = null): Map<NoteKind, ResolvedNote> {
    val key = date?.let { noteScopeOf(it) }
    val out = LinkedHashMap<NoteKind, ResolvedNote>()
    NoteKind.entries.forEach { kind ->
        val sameKind = notes.filter { it.kind == kind.id }
        val sessionOne = key?.let { k -> sameKind.firstOrNull { it.scopeKey == k } }
        val courseOne = sameKind.firstOrNull { it.scopeKey == NOTE_SCOPE_COURSE }
        val picked = sessionOne ?: courseOne
        if (picked != null && picked.value.isNotBlank()) {
            out[kind] = ResolvedNote(kind, picked.value, picked === courseOne)
        }
    }
    return out
}

/** 这次课是不是被标了停课。 */
fun Map<NoteKind, ResolvedNote>.isCancelled(): Boolean =
    this[NoteKind.CANCELLED]?.value == FLAG_ON

/** 这一次课实际要用的地点：填了临时教室就用它。 */
fun Map<NoteKind, ResolvedNote>.effectiveLocation(course: Course): String =
    this[NoteKind.ROOM]?.value?.takeIf { it.isNotBlank() } ?: course.location

/**
 * 「备注」的最终取值。
 *
 * 永久那一层不落在 course_notes 里，而是直接写在 [Course.note] 上（两处共用一个值），
 * 所以这里要先看有没有当天那条临时的，没有才回落到课程自身的备注。
 */
fun Map<NoteKind, ResolvedNote>.effectiveCustomNote(course: Course): String =
    this[NoteKind.CUSTOM]?.value?.takeIf { it.isNotBlank() } ?: course.note

const val FLAG_ON = "1"
const val FLAG_OFF = "0"

/**
 * 注释类型在界面上的名字。
 *
 * 返回简体中文原文，翻译交给文案替换表 —— 这是全项目统一的 i18n 路子，
 * 界面上的 `Text(...)` 会自动按当前语言查表。
 *
 * 「备注」刻意不带「自定义」三个字：它和课程编辑页那个备注框是同一个值，
 * 叫法不一致会让人以为是两个东西。
 */
fun NoteKind.label(): String = when (this) {
    NoteKind.TEXTBOOK -> "教材"
    NoteKind.DEVICE -> "设备"
    NoteKind.IMPORTANCE -> "重要程度"
    NoteKind.HOMEWORK -> "作业"
    NoteKind.ROOM -> "临时教室"
    NoteKind.EXAM -> "考试"
    NoteKind.CANCELLED -> "停课"
    NoteKind.CUSTOM -> "备注"
    NoteKind.WEATHER -> "天气"
}

/** 重要程度三档的名字。 */
fun ImportanceLevel.label(): String = when (this) {
    ImportanceLevel.HIGH -> "高"
    ImportanceLevel.MEDIUM -> "中"
    ImportanceLevel.LOW -> "低"
}

/**
 * 一行注释的展示文本：重要程度翻成中文，开关类翻成「是 / 否」，天气翻成「小雨 23℃」。
 *
 * [unit] 只对天气有意义：库里存的天气永远是摄氏度，这里按用户选的单位换算。
 * 默认摄氏度是为了让拿不到设置的调用方（例如课程详情只看「跟随课程」那一层、
 * 根本不含天气）保持旧行为，不必强行传参。
 */
fun ResolvedNote.displayText(unit: TemperatureUnit = TemperatureUnit.CELSIUS): String =
    if (kind == NoteKind.WEATHER) {
        // 天气存的是「代码|温度」，直接显示原文会是一串数字，这里翻成人看的话
        WeatherValue.decode(value)?.text(unit) ?: value
    } else {
        when (kind.input) {
            NoteInput.LEVEL -> ImportanceLevel.of(value)?.label() ?: value
            NoteInput.FLAG -> if (value == FLAG_ON) "是" else "否"
            NoteInput.TEXT -> value
        }
    }

