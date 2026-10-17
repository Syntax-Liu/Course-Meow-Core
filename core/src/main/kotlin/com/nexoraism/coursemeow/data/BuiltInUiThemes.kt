package com.nexoraism.coursemeow.data

/**
 * 内置界面风格。
 *
 * 这不是「导入的 UI 文件」，而是编译进应用的一套令牌预设：颜色、圆角、疏密、
 * 字号按对应系统的设计语言调好，在「设置 → UI 主题 → 内置风格」里点一下即可切换。
 *
 * 只覆盖**令牌层**（颜色 / 圆角 / 间距 / 字号），不改业务布局与组件结构：
 * 课表网格、设置页结构保持应用原有的组织方式，切换后立刻生效、不需要重启。
 * 组件层对现代风格的适配（大标题、无描边卡片、抬高行高）由 `AppTheme.style`
 * 分支实现，同样只影响外观，不动交互。
 */
object BuiltInUiThemes {

    /** 内置 id 的统一前缀，避免与用户导入的主题撞 id。 */
    const val PREFIX = "builtin:"

    /** 鸿蒙风格的 id。 */
    const val HARMONY_ID = PREFIX + "harmony"

    /**
     * 鸿蒙风格：系统蓝 #0A59F7、16vp 大圆角卡片、白色卡片浮在浅灰底上、
     * 页面留白 16vp、卡片间距 5vp（两卡之间 10vp）。
     */
    val harmony = UiTheme(
        id = HARMONY_ID,
        name = "HarmonyOS",
        author = "拾课喵",
        description = "鸿蒙：大圆角卡片、系统蓝、宽松留白",
        light = UiPalette(
            background = 0xFFF1F3F5, card = 0xFFFFFFFF, border = 0xFFE5E5E5,
            textPrimary = 0xFF182431, textSecondary = 0xFF66707D, chip = 0xFFF0F1F3,
            accent = 0xFF0A59F7, accentDark = 0xFF0846C4, accentLight = 0xFF4D8DFF,
            onAccent = 0xFFFFFFFF, danger = 0xFFE84026, dangerSoft = 0xFFFFECE8,
            success = 0xFF2E9E5B, successSoft = 0xFFE7F6ED,
            warning = 0xFFB26A00, warningSoft = 0xFFFFF4E3,
            appBar = 0xFFF7F8FA, onAppBar = 0xFF182431, drawer = 0xFF0A59F7,
            onDrawer = 0xFFFFFFFF, field = 0xFFF5F6F7,
            // 鸿蒙的卡片靠底色差分层，不画描边
            cardBorder = 0x00000000,
            iconTint = 0xFF0A59F7, iconSoft = 0xFFE8F0FF, iconBorder = 0xFFB3CCFF,
            textDisabled = 0xFFA0A6AD, accentSoft = 0xFFE8F0FF
        ),
        dark = UiPalette(
            background = 0xFF000000, card = 0xFF1A1A1A, border = 0xFF303030,
            textPrimary = 0xFFE6E6E6, textSecondary = 0xFF999999, chip = 0xFF262626,
            accent = 0xFF3D7EFF, accentDark = 0xFF6BA0FF, accentLight = 0xFF6BA0FF,
            onAccent = 0xFFFFFFFF, danger = 0xFFFF6B5B, dangerSoft = 0xFF4A1F1A,
            success = 0xFF4BC97F, successSoft = 0xFF12301F,
            warning = 0xFFE5A100, warningSoft = 0xFF3A2A00,
            appBar = 0xFF1A1A1A, onAppBar = 0xFFE6E6E6, drawer = 0xFF0A59F7,
            onDrawer = 0xFFFFFFFF, field = 0xFF1A1A1A,
            cardBorder = 0x00000000,
            iconTint = 0xFF3D7EFF, iconSoft = 0xFF0F2447, iconBorder = 0xFF1E4F99,
            textDisabled = 0xFF666666, accentSoft = 0xFF0F2447
        ),
        shapes = UiShapes(
            cardRadius = 16,
            // 胶囊控件：圆角取到足够大，任意高度下都是半圆端
            controlRadius = 24,
            chipRadius = 24,
            insetRadius = 12,
            courseRadius = 8,
            dialogRadius = 20,
            borderWidth = 1,
            cardPadding = 16,
            timeColumnWidth = 46
        ),
        layout = UiLayout(
            density = UiLayout.NORMAL,
            screenPadding = 16,
            cardGap = 5
        ),
        typography = UiTypography(scale = 1f, family = UiTypography.SANS)
    )

    /**
     * 现在只有鸿蒙一套可选风格。
     *
     * 荣耀那套已经变成内置 UI 本身（`DEFAULT_UI_THEME_ID`）：组件做法固定为
     * 「无描边大圆角卡片 + 12dp 圆角矩形按钮」，颜色则跟着用户选的配色方案走，
     * 默认是科技蓝（抽屉深蓝）。所以它不在这里重复列一遍。
     */
    val all: List<UiTheme> = listOf(harmony)

    fun byId(id: String): UiTheme? = all.firstOrNull { it.id == id }
}
