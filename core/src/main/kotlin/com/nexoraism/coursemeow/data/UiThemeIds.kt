package com.nexoraism.coursemeow.data

/**
 * 内置 UI 主题的 id：用它表示「什么都不覆盖，完全按内置样式显示」。
 *
 * 放在 core 而不是跟着 UiTheme.kt 走：[TimetableSettings] 有一个 `uiThemeId` 字段，
 * 默认值就是它；设置模型属于跨平台共享层，不能让共享层反过来依赖界面层的文件。
 */
const val DEFAULT_UI_THEME_ID = "default"
