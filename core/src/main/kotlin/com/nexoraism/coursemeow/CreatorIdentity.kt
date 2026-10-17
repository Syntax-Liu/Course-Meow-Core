package com.nexoraism.coursemeow

/**
 * 创作者身份信息（署名与联系方式）。
 *
 * 从 `AppVersion.kt` 抽出来放进 core：这些值被 [isProtectedCopy] 与 UI 主题的导入校验用到，
 * 而设置模型（`TimetableSettings` → `UiTheme`）属于跨平台共享层，
 * 不能反过来依赖 App 里的版本日志文件。
 */

/** 开发者署名。 */
const val DEVELOPER_NAME = "桃花坞里柳边风"
const val DEVELOPER_NAME_EN = "Syntax"

/** 联系方式。 */
const val CREATOR_PHONE = "18425391736"
const val CREATOR_QQ = "3763277412"
const val CREATOR_QQ_GROUP = "1027766811"
const val CREATOR_EMAIL = "3260604029@stmail.ujs.edu.cn"

/**
 * 不允许被 UI 主题改写的文案。
 *
 * 创作者信息属于「作者身份的声明」，任何 UI 主题都不能替换、翻译或隐藏它，
 * 所以在两处把关：导入时丢掉这些条目；真正渲染时再拦一次
 * （防止用通配符间接命中，例如 `"*创作者*"`）。
 */
val PROTECTED_COPY: List<String> = listOf(
    DEVELOPER_NAME,
    DEVELOPER_NAME_EN,
    CREATOR_PHONE,
    CREATOR_QQ,
    CREATOR_QQ_GROUP,
    CREATOR_EMAIL,
    "创作者"
)

/** 这段文案是否属于不允许改写的创作者信息。 */
fun isProtectedCopy(text: String): Boolean =
    PROTECTED_COPY.any { it.isNotBlank() && text.contains(it) }
