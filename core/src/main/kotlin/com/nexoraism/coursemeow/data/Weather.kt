package com.nexoraism.coursemeow.data

import kotlin.math.roundToInt

/**
 * 天气归类。
 *
 * 取值来自 WMO 天气代码（Open-Meteo 返回的就是这一套），归成九类之后再对应到图标。
 * 代码表见 <https://open-meteo.com/en/docs>：0 晴、1~2 多云、3 阴、45/48 雾、
 * 51~57 毛毛雨、61~67 雨、71~77 与 85/86 雪、80~82 阵雨、95~99 雷阵雨。
 *
 * [label] 是简体中文原文，翻译交给文案替换表 —— 和项目里其他文案一个路子。
 */
enum class WeatherKind(val label: String) {
    CLEAR("晴"),
    CLOUDY("多云"),
    OVERCAST("阴"),
    FOG("雾"),
    DRIZZLE("小雨"),
    RAIN("雨"),
    SHOWER("阵雨"),
    SNOW("雪"),
    THUNDER("雷阵雨");

    companion object {
        fun of(code: Int): WeatherKind = when (code) {
            0, 1 -> CLEAR
            2 -> CLOUDY
            3 -> OVERCAST
            45, 48 -> FOG
            51, 53, 55, 56, 57 -> DRIZZLE
            61, 63, 65, 66, 67 -> RAIN
            71, 73, 75, 77, 85, 86 -> SNOW
            80, 81, 82 -> SHOWER
            95, 96, 99 -> THUNDER
            // 没见过的新代码：当成「多云」，比留空或者显示成一片空白要好
            else -> CLOUDY
        }
    }
}

/**
 * 一次课的天气：天气代码 + 那一刻的气温（**摄氏度**，取整）。
 *
 * [temperature] 永远是摄氏度：数据源给的是摄氏，库里存的也是摄氏（见 [WeatherValue]）。
 * 华氏度只是显示偏好，在 [text] 里按 [TemperatureUnit] 现换算。
 */
data class WeatherInfo(val code: Int, val temperature: Int) {

    val kind: WeatherKind get() = WeatherKind.of(code)

    /** 给人看的一行：`小雨 23℃`；单位传华氏度时是 `小雨 73℉`。 */
    fun text(unit: TemperatureUnit): String = "${kind.label} ${formatTemp(temperature, unit)}"
}

/**
 * 把**摄氏度**换算成要显示的温度文字（含单位）。
 *
 * 全项目只在这里做换算：数据源与存储一律是摄氏度，华氏度是纯粹的显示偏好。
 * 换算散到各个界面去写（各写一遍 ×1.8+32）迟早会有一处忘改、对不上。
 */
fun formatTemp(celsius: Int, unit: TemperatureUnit): String = when (unit) {
    TemperatureUnit.CELSIUS -> "$celsius℃"
    TemperatureUnit.FAHRENHEIT -> "${(celsius * 1.8 + 32).roundToInt()}℉"
}

/**
 * 「通知内容」预览与「示例提醒」共用的示例天气。
 *
 * 两处示例必须是同一条，看起来才对得上；分头写死两个值，迟早会出现
 * 「预览是小雨、真发出去是雨」这种对不上的情况。code 51 = 小雨（见 [WeatherKind]）。
 */
val SAMPLE_WEATHER = WeatherInfo(code = 51, temperature = 23)

/**
 * 最近一次定位到的坐标。
 *
 * [locatedAt] 是定位时刻（毫秒）。坐标缓存起来是为了取天气时不必每次都重新定位 ——
 * 定位比一次网络请求慢得多，也更费电。
 */
data class WeatherLocation(
    val latitude: Double,
    val longitude: Double,
    val locatedAt: Long
)

/**
 * 天气在注释里的存法：`"天气代码|气温"`，例如 `61|23`。
 *
 * 为什么只存这两个数、不把「小雨」这样的描述一起存下来：
 * 描述要跟着界面语言走，存成文字就固定成「写入那一刻的语言」了。
 * 图标也一样 —— 存代码，图标在界面上再按代码取。
 */
object WeatherValue {

    private const val SEP = '|'

    fun encode(code: Int, temperature: Double): String =
        "$code$SEP${temperature.roundToInt()}"

    fun decode(raw: String?): WeatherInfo? {
        val parts = raw?.trim()?.split(SEP) ?: return null
        if (parts.size != 2) return null
        val code = parts[0].trim().toIntOrNull() ?: return null
        val temperature = parts[1].trim().toIntOrNull() ?: return null
        return WeatherInfo(code, temperature)
    }
}
