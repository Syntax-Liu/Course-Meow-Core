package com.nexoraism.coursemeow.data

import androidx.compose.runtime.Immutable
import com.nexoraism.coursemeow.isProtectedCopy
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI 主题（用户自己开发的那套界面）。
 *
 * 一个 UI 主题就是一份 JSON 文件，覆盖的都是**界面本身**：
 * - **颜色令牌**：浅色 / 深色两套，各自可以只填一部分，没填的继续按内置规则推导；
 * - **尺寸令牌**：卡片圆角、控件圆角、课程块圆角、内边距等；
 * - **布局**：疏密、页面内边距、隐藏哪些界面块、设置项顺序；
 * - **文案**：替换界面上写死的固定文案（也支持 `*` 通配符）；
 * - **图标**：用 SVG 路径替换界面上的图标；
 * - **字体与字号**：整体字号缩放与字体族；
 * - **课程配色**：选择课程颜色时给出的色板。
 *
 * 有两样东西改不了，这是设计使然：
 * - **课表内容**（课程名、教师、上课地点、备注、账号资料……）是用户的数据，不是界面。
 *   文案替换只认 [UiCopyCatalog] 里的固定文案，别的键一概不收；
 * - **创作者信息**（作者名、联系方式），见 `PROTECTED_COPY`。
 *
 * [source] 保留原始文件内容，用户删掉重新导入、或者我们做导出时都还能拿到原文。
 */
@Immutable
data class UiTheme(
    val id: String,
    val name: String,
    val author: String = "",
    val description: String = "",
    val light: UiPalette = UiPalette(),
    val dark: UiPalette = UiPalette(),
    val shapes: UiShapes = UiShapes(),
    val layout: UiLayout = UiLayout(),
    /** 文案替换：键是界面上的原文案，值是替换后的文案。 */
    val copy: Map<String, String> = emptyMap(),
    /** 图标替换：键是图标名（如 `settings`），值是 SVG 的 path data（24×24 视图盒）。 */
    val icons: Map<String, String> = emptyMap(),
    val typography: UiTypography = UiTypography(),
    /** 课程色板；为空表示沿用内置的 12 色。 */
    val courseColors: List<Long> = emptyList(),
    /** 导入时被忽略的内容说明（例如与创作者信息冲突的文案）。 */
    val warnings: List<String> = emptyList(),
    /** 原始 JSON 文本（用于持久化与再次导出）。 */
    val source: String = ""
) {
    /** 一句话摘要，列表里显示用。 */
    val summary: String
        get() = buildList {
            val tokens = light.count + dark.count
            if (tokens > 0) add("自定义 $tokens 个颜色")
            if (copy.isNotEmpty()) add("替换 ${copy.size} 条文案")
            if (icons.isNotEmpty()) add("替换 ${icons.size} 个图标")
            if (typography.scale != 1f) add("字号 ${(typography.scale * 100).toInt()}%")
            if (typography.family != UiTypography.SYSTEM) add("字体 ${typography.label}")
            if (courseColors.isNotEmpty()) add("课程色板 ${courseColors.size} 色")
            if (layout.hidden.isNotEmpty()) add("隐藏 ${layout.hidden.size} 处界面块")
            if (layout.settingsOrder.isNotEmpty()) add("调整了设置项顺序")
            if (layout.density != UiLayout.NORMAL) add("密度 ${layout.densityLabel}")
            if (shapes.isCustomized) add("自定义圆角与间距")
            if (author.isNotBlank()) add("作者：$author")
        }.joinToString(" · ").ifBlank { "只调整了少量样式" }
}

/**
 * 字体与整体字号。
 *
 * 字体只提供系统自带的几个族：自定义字体文件体积太大，不适合塞在一份 JSON 里，
 * 所以 [family] 取值是 `system` / `sans` / `serif` / `monospace`。
 */
@Immutable
data class UiTypography(
    /** 整体字号缩放：1.0 为基准，允许 0.8 – 1.4。 */
    val scale: Float = 1f,
    /** 字体族。 */
    val family: String = SYSTEM
) {
    /** 界面展示用的字体名。 */
    val label: String
        get() = when (family) {
            SANS -> "无衬线"
            SERIF -> "衬线"
            MONOSPACE -> "等宽"
            else -> "系统默认"
        }

    companion object {
        const val SYSTEM = "system"
        const val SANS = "sans"
        const val SERIF = "serif"
        const val MONOSPACE = "monospace"

        val FAMILIES = listOf(SYSTEM, SANS, SERIF, MONOSPACE)
    }
}

/**
 * 一套配色令牌的覆盖项。
 *
 * 每个字段都是「可以没有」：null 表示这一项继续用内置推导值，
 * 因此用户只写想改的那几个颜色就够了，不至于因为漏写而把界面弄坏。
 */
@Immutable
data class UiPalette(
    val background: Long? = null,
    val card: Long? = null,
    val border: Long? = null,
    val textPrimary: Long? = null,
    val textSecondary: Long? = null,
    val chip: Long? = null,
    val accent: Long? = null,
    val accentDark: Long? = null,
    val accentLight: Long? = null,
    val onAccent: Long? = null,
    val danger: Long? = null,
    val dangerSoft: Long? = null,
    val success: Long? = null,
    val successSoft: Long? = null,
    val warning: Long? = null,
    val warningSoft: Long? = null,
    val appBar: Long? = null,
    val onAppBar: Long? = null,
    val field: Long? = null,
    val iconTint: Long? = null,
    val iconSoft: Long? = null,
    val iconBorder: Long? = null,
    /** 账号页（抽屉）底色；不填时跟随主题色。 */
    val drawer: Long? = null,
    val onDrawer: Long? = null,
    /** 卡片描边；浅色主题下默认透明，深色主题下默认取分隔线色。 */
    val cardBorder: Long? = null,
    /** 禁用状态的文字与图标色。 */
    val textDisabled: Long? = null,
    /** 主色的浅底（选中行、今天那一列、提示条这类淡主色块）。 */
    val accentSoft: Long? = null,
    /** 危险色实心底上的文字色。 */
    val onDanger: Long? = null,
    /** 全屏遮罩。 */
    val scrim: Long? = null
) {
    /** 一共填了几个颜色。 */
    val count: Int = listOf(
        background, card, border, textPrimary, textSecondary, chip, accent,
        accentDark, accentLight, onAccent, danger, dangerSoft, success,
        successSoft, warning, warningSoft, appBar, onAppBar, field,
        iconTint, iconSoft, iconBorder, drawer, onDrawer, cardBorder,
        textDisabled, accentSoft, onDanger, scrim
    ).count { it != null }

    /** 所有颜色令牌的名称与取值（按固定顺序，界面与文档共用）。 */
    fun entries(): List<Pair<String, Long?>> = listOf(
        "background" to background,
        "card" to card,
        "border" to border,
        "textPrimary" to textPrimary,
        "textSecondary" to textSecondary,
        "chip" to chip,
        "accent" to accent,
        "accentDark" to accentDark,
        "accentLight" to accentLight,
        "onAccent" to onAccent,
        "danger" to danger,
        "dangerSoft" to dangerSoft,
        "success" to success,
        "successSoft" to successSoft,
        "warning" to warning,
        "warningSoft" to warningSoft,
        "appBar" to appBar,
        "onAppBar" to onAppBar,
        "drawer" to drawer,
        "onDrawer" to onDrawer,
        "field" to field,
        "cardBorder" to cardBorder,
        "iconTint" to iconTint,
        "iconSoft" to iconSoft,
        "iconBorder" to iconBorder,
        "textDisabled" to textDisabled,
        "accentSoft" to accentSoft,
        "onDanger" to onDanger,
        "scrim" to scrim
    )
}

/**
 * 布局定制。
 *
 * Compose 的界面是「写死的组件树」，没法把整棵树的摆放交给一个文件
 * （那需要先把应用重写成数据驱动的渲染引擎）。但布局里真正影响观感的这几件事
 * 是可以开放的：**哪些块显示、设置页各项的顺序、疏密与边距**。
 *
 * [hidden] 里写的是界面块的键（见 [UiLayout.BLOCKS]）；
 * **创作者信息相关的块不允许隐藏**，写了也会被忽略。
 */
@Immutable
data class UiLayout(
    /** 整体疏密：compact / normal / comfortable。 */
    val density: String = NORMAL,
    /** 页面左右内边距（dp）。 */
    val screenPadding: Int = 12,
    /** 卡片之间的纵向间距（dp）。 */
    val cardGap: Int = 6,
    /** 要隐藏的界面块。 */
    val hidden: Set<String> = emptySet(),
    /** 设置页各条目的顺序（写键名，没写的排在后面、保持默认顺序）。 */
    val settingsOrder: List<String> = emptyList()
) {
    val densityLabel: String
        get() = when (density) {
            COMPACT -> "紧凑"
            COMFORTABLE -> "宽松"
            else -> "标准"
        }

    /** 疏密系数：乘到内边距与行距上。 */
    val spacing: Float
        get() = when (density) {
            COMPACT -> 0.85f
            COMFORTABLE -> 1.18f
            else -> 1f
        }

    /** 这个块是否被隐藏（创作者信息永远不隐藏）。 */
    fun isHidden(key: String): Boolean =
        key in hidden && key !in PROTECTED_BLOCKS

    companion object {
        const val COMPACT = "compact"
        const val NORMAL = "normal"
        const val COMFORTABLE = "comfortable"

        val DENSITIES = listOf(COMPACT, NORMAL, COMFORTABLE)

        /** 可以隐藏的界面块。[key] 是写进文件里的名字。 */
        val BLOCKS: List<Pair<String, String>> = listOf(
            "appbar.today" to "顶栏「回到今天 / 回到本周」",
            "appbar.viewToggle" to "顶栏「周视图 / 日视图」切换",
            "appbar.switcher" to "顶栏「切换课表」",
            // 「设置」已经挪进抽屉最下面，且必须始终可达，所以不再作为可隐藏的界面块
            "home.nowBar" to "主页「正在上 / 下一节」提示栏",
            "week.timeColumn" to "周视图左侧的节次 / 时间列",
            "week.makeupMark" to "周视图表头的「补X」标记",
            "day.addCourse" to "日视图底部的「添加课程」按钮"
        )

        /** 不允许隐藏的块：创作者信息属于作者身份声明。 */
        val PROTECTED_BLOCKS: Set<String> = setOf("settings.creator", "settings.contact")

        /**
         * 设置页各组件的键与名称。
         *
         * 这里只列**当前真的在设置页上**的条目：课表管理 / 天气 / 夏令时 / 提醒 / 节假日
         * 已经移到抽屉里，不属于设置页，写进来只会让主题作者以为能排序。
         * 键与 `SettingsScreen` 里 `SettingsGroup` 用的键一一对应。
         */
        val SETTINGS_ORDER: List<Pair<String, String>> = listOf(
            "appearance" to "外观",
            "language" to "语言",
            "uitheme" to "UI 主题",
            "widgets" to "桌面小组件",
            "checkUpdate" to "检查更新",
            "about" to "关于"
        )
    }
}

/** 尺寸令牌：界面各处圆角、内边距与描边宽度（单位 dp）。 */
@Immutable
data class UiShapes(
    val cardRadius: Int = 12,
    val controlRadius: Int = 10,
    val chipRadius: Int = 20,
    val insetRadius: Int = 8,
    val courseRadius: Int = 8,
    val dialogRadius: Int = 16,
    val borderWidth: Int = 1,
    /** 卡片内边距。 */
    val cardPadding: Int = 16,
    /** 周视图左侧时间列宽度。 */
    val timeColumnWidth: Int = 46
) {
    /** 是否与内置默认值不同。 */
    val isCustomized: Boolean
        get() = this != UiShapes()

    fun entries(): List<Pair<String, Int>> = listOf(
        "cardRadius" to cardRadius,
        "controlRadius" to controlRadius,
        "chipRadius" to chipRadius,
        "insetRadius" to insetRadius,
        "courseRadius" to courseRadius,
        "dialogRadius" to dialogRadius,
        "borderWidth" to borderWidth,
        "cardPadding" to cardPadding,
        "timeColumnWidth" to timeColumnWidth
    )
}

/** UI 主题文件里的固定标记与版本号。 */
object UiThemeFile {
    /** 文件格式标记，写在 JSON 顶层的 `format` 字段里。 */
    const val MARK = "coursemeow-ui"

    /** 当前格式版本。 */
    const val VERSION = 1

    /** 建议的文件后缀（`我的主题.coursemeow.json`）。 */
    const val SUFFIX = ".coursemeow.json"
}

/** 圆角允许的范围，超出会被收敛到这个区间。 */
private val RadiusRange = 0..48

/** 描边宽度允许的范围。 */
private val BorderWidthRange = 0..4

/** 卡片内边距允许的范围。 */
private val CardPaddingRange = 4..32

/** 时间列宽度允许的范围。 */
private val TimeColumnWidthRange = 28..96

/** 整体字号缩放允许的范围。 */
private val TextScaleRange = 0.8f..1.4f

/**
 * 解析一份 UI 主题文件。
 *
 * 解析是「宽容」的：颜色写错、数值超范围只会忽略这一项并记下原因，
 * 不会整份文件报废 —— 用户手写 JSON 很难一次全对。
 */
fun parseUiTheme(source: String): Result<UiTheme> = runCatching {
    val text = source.trim()
    if (text.isEmpty()) throw IllegalArgumentException("文件是空的")

    val root = try {
        JSONObject(text)
    } catch (e: Exception) {
        throw IllegalArgumentException("不是合法的 JSON：${e.message ?: "解析失败"}")
    }

    val format = root.optString("format", "").trim()
    if (format.isNotEmpty() && format != UiThemeFile.MARK) {
        throw IllegalArgumentException("format 应为 \"${UiThemeFile.MARK}\"，当前是 \"$format\"")
    }

    val name = root.optString("name", "").trim()
    if (name.isEmpty()) throw IllegalArgumentException("缺少 name 字段（UI 主题名称）")

    val version = root.optInt("version", UiThemeFile.VERSION)
    if (version > UiThemeFile.VERSION) {
        throw IllegalArgumentException("文件版本 $version 比当前应用支持的 ${UiThemeFile.VERSION} 更新")
    }

    val id = root.optString("id", "").trim().ifEmpty { slugOf(name) }
    val colors = root.optJSONObject("colors")

    val warnings = mutableListOf<String>()
    val copy = parseCopy(root.optJSONObject("copy"), warnings)
    val icons = parseIcons(root.optJSONObject("icons"))

    UiTheme(
        id = id,
        name = name,
        author = root.optString("author", "").trim(),
        description = root.optString("description", "").trim(),
        light = parsePalette(colors?.optJSONObject("light")),
        dark = parsePalette(colors?.optJSONObject("dark")),
        shapes = parseShapes(root.optJSONObject("shapes")),
        layout = parseLayout(root.optJSONObject("layout"), warnings),
        copy = copy,
        icons = icons,
        typography = parseTypography(root.optJSONObject("typography")),
        courseColors = parseCourseColors(root.optJSONArray("courseColors")),
        warnings = warnings,
        source = text
    )
}

/**
 * 文案替换表。
 *
 * 只收 [UiCopyCatalog] 里的键 —— 那是界面上写死的固定文案（按钮名、标题、说明），
 * 不是课表内容。课程名、教师、上课地点这些即便写了也进不来，
 * 于是 UI 主题改得了界面、改不了数据。
 *
 * 两条另外的规则：
 * - 值为空串表示「不改这条」（模板里就是这样留白的），不算错误；
 * - 与创作者信息冲突的条目会被丢掉并记一条说明 —— 创作者信息不允许被任何 UI 改写。
 */
private fun parseCopy(json: JSONObject?, warnings: MutableList<String>): Map<String, String> {
    if (json == null) return emptyMap()
    val result = LinkedHashMap<String, String>()
    var blocked = 0
    var unknown = 0
    json.keys().forEach { key ->
        val value = json.optString(key, "")
        when {
            key.isBlank() -> Unit
            // 留空 = 这一条不动。模板会把清单里的键都列出来等着填，所以这属于常态
            value.isBlank() -> Unit
            isProtectedCopy(key) -> blocked += 1
            !UiCopyCatalog.contains(key) -> unknown += 1
            else -> result[key] = value
        }
    }
    if (blocked > 0) warnings += "已忽略 $blocked 条与创作者信息冲突的文案"
    if (unknown > 0) {
        warnings += "已忽略 $unknown 条不在界面文案清单里的键（只能改界面固定文案，改不了课程内容）"
    }
    return result
}

/**
 * 图标替换表：键统一成「规范化图标名」，见 [normalizeIconKey]。
 *
 * 这里不设白名单：图标名是应用内部的东西，不含任何用户数据，多写几个无害；
 * 想知道有哪些能改，看 [UiIconCatalog]（模板就是从那里生成的）。
 */
private fun parseIcons(json: JSONObject?): Map<String, String> {
    if (json == null) return emptyMap()
    val result = LinkedHashMap<String, String>()
    json.keys().forEach { key ->
        val path = json.optString(key, "").trim()
        if (path.isEmpty()) return@forEach
        result[normalizeIconKey(key)] = path
    }
    return result
}

/**
 * 把图标名规范化。
 *
 * 代码里的图标是 Material 的矢量图，名字形如 `Filled.Settings`、
 * `Outlined.EventBusy`、`AutoMirrored.Filled.ArrowBack`。
 * 这里只保留最后一段并去掉符号，于是 `Filled.Settings` 与 `settings` 等价，
 * 同一图形的实心 / 线框两种写法也共用一份覆盖。
 */
fun normalizeIconKey(raw: String): String =
    raw.substringAfterLast('.').lowercase().filter { it.isLetterOrDigit() }

private fun parseTypography(json: JSONObject?): UiTypography {
    if (json == null) return UiTypography()
    val scale = json.optDouble("scale", 1.0).toFloat()
    val family = json.optString("family", UiTypography.SYSTEM).trim().lowercase()
    return UiTypography(
        scale = scale.coerceIn(TextScaleRange.start, TextScaleRange.endInclusive),
        family = if (family in UiTypography.FAMILIES) family else UiTypography.SYSTEM
    )
}

private fun parseCourseColors(json: JSONArray?): List<Long> {
    if (json == null) return emptyList()
    return (0 until json.length())
        .mapNotNull { parseColorValue(json.opt(it)) }
        .take(24)
}

/**
 * 布局定制。
 *
 * 不认识的块键会被忽略并记一条说明（写错名字时用户能立刻发现，
 * 而不是以为「隐藏了但没生效」）。
 */
private fun parseLayout(json: JSONObject?, warnings: MutableList<String>): UiLayout {
    val base = UiLayout()
    if (json == null) return base

    val density = json.optString("density", UiLayout.NORMAL).trim().lowercase()
    val known = UiLayout.BLOCKS.map { it.first }.toSet()
    val hidden = LinkedHashSet<String>()
    var unknownBlocks = 0
    var blockedBlocks = 0

    json.optJSONArray("hidden")?.let { array ->
        for (i in 0 until array.length()) {
            val key = array.optString(i, "").trim()
            if (key.isEmpty()) continue
            when {
                key in UiLayout.PROTECTED_BLOCKS -> blockedBlocks += 1
                key in known -> hidden += key
                else -> unknownBlocks += 1
            }
        }
    }

    val order = json.optJSONArray("settingsOrder")
        ?.let { array ->
            (0 until array.length())
                .map { array.optString(it, "").trim() }
                .filter { it.isNotEmpty() }
        }
        .orEmpty()

    if (blockedBlocks > 0) warnings += "已忽略 $blockedBlocks 处与创作者信息相关的隐藏项"
    if (unknownBlocks > 0) warnings += "已忽略 $unknownBlocks 个不认识的界面块键"

    return UiLayout(
        density = if (density in UiLayout.DENSITIES) density else UiLayout.NORMAL,
        screenPadding = json.optInt("screenPadding", base.screenPadding).coerceIn(0, 32),
        cardGap = json.optInt("cardGap", base.cardGap).coerceIn(0, 24),
        hidden = hidden,
        settingsOrder = order
    )
}

/** 由名称生成一个稳定的 id（用户没写 id 时用）。 */
private fun slugOf(name: String): String =
    "ui-" + name.hashCode().toUInt().toString(16)

private fun parsePalette(json: JSONObject?): UiPalette {
    if (json == null) return UiPalette()
    fun color(key: String): Long? = parseColorValue(json.opt(key))
    return UiPalette(
        background = color("background"),
        card = color("card"),
        border = color("border"),
        textPrimary = color("textPrimary"),
        textSecondary = color("textSecondary"),
        chip = color("chip"),
        accent = color("accent"),
        accentDark = color("accentDark"),
        accentLight = color("accentLight"),
        onAccent = color("onAccent"),
        danger = color("danger"),
        dangerSoft = color("dangerSoft"),
        success = color("success"),
        successSoft = color("successSoft"),
        warning = color("warning"),
        warningSoft = color("warningSoft"),
        appBar = color("appBar"),
        onAppBar = color("onAppBar"),
        field = color("field"),
        iconTint = color("iconTint"),
        iconSoft = color("iconSoft"),
        iconBorder = color("iconBorder"),
        drawer = color("drawer"),
        onDrawer = color("onDrawer"),
        cardBorder = color("cardBorder"),
        textDisabled = color("textDisabled"),
        accentSoft = color("accentSoft"),
        onDanger = color("onDanger"),
        scrim = color("scrim")
    )
}

private fun parseShapes(json: JSONObject?): UiShapes {
    val base = UiShapes()
    if (json == null) return base
    fun radius(key: String, fallback: Int): Int =
        json.optInt(key, fallback).coerceIn(RadiusRange.first, RadiusRange.last)
    return UiShapes(
        cardRadius = radius("cardRadius", base.cardRadius),
        controlRadius = radius("controlRadius", base.controlRadius),
        chipRadius = radius("chipRadius", base.chipRadius),
        insetRadius = radius("insetRadius", base.insetRadius),
        courseRadius = radius("courseRadius", base.courseRadius),
        dialogRadius = radius("dialogRadius", base.dialogRadius),
        borderWidth = json.optInt("borderWidth", base.borderWidth)
            .coerceIn(BorderWidthRange.first, BorderWidthRange.last),
        cardPadding = json.optInt("cardPadding", base.cardPadding)
            .coerceIn(CardPaddingRange.first, CardPaddingRange.last),
        timeColumnWidth = json.optInt("timeColumnWidth", base.timeColumnWidth)
            .coerceIn(TimeColumnWidthRange.first, TimeColumnWidthRange.last)
    )
}

/**
 * 解析一个颜色值。
 *
 * 支持 `"#RRGGBB"`、`"#AARRGGBB"`、`"RRGGBB"` 与 `"0xFFRRGGBB"` 这类写法；
 * 看不懂就返回 null（这一项按内置规则来）。
 */
internal fun parseColorValue(value: Any?): Long? {
    val raw = when (value) {
        null -> return null
        is Number -> return value.toLong() and 0xFFFFFFFFL
        is String -> value.trim()
        else -> return null
    }
    if (raw.isEmpty()) return null
    var hex = raw
    if (hex.startsWith("0x", ignoreCase = true)) hex = hex.substring(2)
    hex = hex.removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    val parsed = hex.toLongOrNull(16) ?: return null
    return if (hex.length == 6) 0xFF000000L or parsed else parsed
}

/** 把导入的 UI 主题列表编码成可持久化的字符串（JSON 数组，元素是原始文件内容）。 */
fun encodeUiThemes(themes: List<UiTheme>): String {
    val arr = JSONArray()
    themes.forEach { arr.put(it.source) }
    return arr.toString()
}

/** 读取持久化的 UI 主题列表；单份解析失败就跳过它，不影响其他主题。 */
fun decodeUiThemes(raw: String?): List<UiTheme> {
    val text = raw?.takeIf { it.isNotBlank() } ?: return emptyList()
    val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val source = arr.optString(i, "")
        parseUiTheme(source).getOrNull()
    }
}

/**
 * 在已导入的列表里找当前生效的主题；找不到（或选的是内置）返回 null。
 *
 * 旧版本的内置风格（Material 3 / Honor MagicOS / 其余七套）都已经下线。
 * 用户设置里可能仍存着 `builtin:honor` 这类 id：统一回落到内置 UI（null）——
 * 也就是现在的默认样式（荣耀组件做法 + 配色方案），不能返回 harmony，
 * 否则会把人莫名其妙切到鸿蒙那套颜色上。
 */
fun resolveUiTheme(id: String, themes: List<UiTheme>): UiTheme? =
    if (id == DEFAULT_UI_THEME_ID) null
    else BuiltInUiThemes.byId(id)
        ?: if (id.startsWith(BuiltInUiThemes.PREFIX)) null
        else themes.firstOrNull { it.id == id }

/**
 * UI 开发模式里的临时改动（原文 → 新文案）存成 JSON。
 *
 * 存的是「改过的那几条」而不是整份清单：一条没改就是空对象，
 * 重启回来也能接着改，但不会在设置里留下一大坨没用的键。
 */
fun encodeCopyMap(map: Map<String, String>): String {
    val json = JSONObject()
    map.forEach { (key, value) -> if (key.isNotBlank()) json.put(key, value) }
    return json.toString()
}

/** 读回 UI 开发模式的临时改动；解析失败就当没改过。 */
fun decodeCopyMap(raw: String?): Map<String, String> {
    val text = raw?.takeIf { it.isNotBlank() } ?: return emptyMap()
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    json.keys().forEach { key -> out[key] = json.optString(key, "") }
    return out
}
