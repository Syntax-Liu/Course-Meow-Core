package com.nexoraism.coursemeow.domain

import com.nexoraism.coursemeow.data.TimetableSettings
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 一组节假日。
 */
data class HolidayGroup(
    val name: String,
    /** 放假日（yyyy-MM-dd）。 */
    val dates: List<String>,
    /** 调休上班日（周六/周日需要上班），仅作展示提示。 */
    val workdays: List<String> = emptyList(),
    /**
     * 仅特定民族放假时写明（例如「仅限傈僳族放假」）；空串表示该地区全体公民放假。
     *
     * 只有德宏州的阔时节、阿露窝罗节会用到 —— 那是「少数民族习惯节日」里少见的
     * 「只给某个民族放假」的情形，绝大多数地区都是该行政区全体放。
     */
    val scope: String = "",
    /** 依据说明（法规名称或发文号），原样展示，不进翻译表。 */
    val basis: String = "",
    /**
     * 所属地区代码（例如 `guangxi`）；全国性法定节假日为空串。
     *
     * 民族节日要按地区折叠展示，靠的就是这一项：以前地区只藏在数据的分组函数里，
     * 界面上无法分组，只能平铺；搬上云时把地区也一并序列化下来。
     */
    val region: String = "",
    /** 地区展示名（例如「广西」）；[region] 为空时也为空。 */
    val regionName: String = ""
)

/**
 * 一份完整的节日数据：法定节假日 + 少数民族节日。
 *
 * 这是「服务器下发、客户端缓存」的那一份数据。内置在客户端里的那一份只是
 * 首次安装、以及云端拿不到时的种子（见 [HolidayCalendar.builtinCatalog]）。
 */
data class HolidayCatalog(
    /** 数据年度（界面要显示出来）。 */
    val year: Int,
    /** 全国性法定节假日。 */
    val legal: List<HolidayGroup>,
    /** 少数民族习惯节日（带地区信息）。 */
    val ethnic: List<HolidayGroup>
)

/**
 * 某个日期上的课程在节假日规则下该怎么处理 —— 渲染方只看这一个结果，
 * 不再各自去问「是不是节假日」再自己决定隐藏还是淡化。
 */
enum class HolidayCourseMode {
    /** 正常显示。 */
    NORMAL,

    /** 隐藏：当天课程不显示。 */
    HIDDEN,

    /** 淡化：课程照常显示，但整体变灰。 */
    DIMMED
}

/**
 * 生效的放假日期集合：规则只有这一处，UI 与全局状态都用它。
 *
 * - 法定节假日受 [TimetableSettings.skipHolidays] 总开关控制，分组可以单独关闭；
 * - 少数民族节日**默认都不生效**，用户把自己会放的那几个打开才生效
 *   —— 放假权在地方人民政府，每个地区每一年都不一样，只能由用户自己认领；
 * - 自定义假期与自定义补课**不受总开关控制**，始终生效；
 * - 补课日优先级最高：同一天既在放假又在补课时按上课算。
 */
fun TimetableSettings.activeHolidayDates(): Set<String> {
    val out = HashSet<String>()
    if (skipHolidays) {
        HolidayCalendar.groups.forEach { group ->
            if (group.name !in disabledHolidayGroups) out.addAll(group.dates)
        }
        // 民族节日是「点开才生效」，所以判据是启用的那一份名单，与法定节假日的
        // 「默认开、记关闭的那些」正好相反，两套名字各存一份、互不干扰。
        HolidayCalendar.ethnicGroups.forEach { group ->
            if (group.name in enabledEthnicGroups) out.addAll(group.dates)
        }
    }
    out.addAll(customHolidays)
    out.removeAll(customMakeupDays.keys)
    return out
}

/** 当前设置下真正会放假的日期数量（含自定义假期，不含补课日）。 */
fun TimetableSettings.activeHolidayCount(): Int = activeHolidayDates().size

// ---------------------------------------------------------------- JSON 编解码

/**
 * 解析云端下发的节日 JSON；格式不对或两份分组都为空时返回 null。
 *
 * 返回 null 是给调用方一个明确的「这份数据不能用」信号，好让它回退到内置那份
 * —— 宁可整页退回种子数据，也不能因为云端坏了一份 JSON 就让用户看到空白的节假日页。
 */
fun parseHolidayCatalog(raw: String?): HolidayCatalog? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
        val root = JSONObject(raw)
        val legal = parseHolidayGroups(root.optJSONArray("legal"))
        val ethnic = parseHolidayGroups(root.optJSONArray("ethnic"))
        if (legal.isEmpty() && ethnic.isEmpty()) {
            null
        } else {
            HolidayCatalog(
                // 云端没写年度就退回种子年度：界面要显示「按 X 年整理」，显示 0 年没有意义
                year = root.optInt("year", 0).takeIf { it > 0 } ?: EthnicFestivals.YEAR,
                legal = legal,
                ethnic = ethnic
            )
        }
    }.getOrNull()
}

private fun parseHolidayGroups(array: JSONArray?): List<HolidayGroup> {
    if (array == null) return emptyList()
    val out = ArrayList<HolidayGroup>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val name = item.optString("name")
        if (name.isBlank()) continue
        out += HolidayGroup(
            name = name,
            dates = parseHolidayStringList(item.optJSONArray("dates")),
            workdays = parseHolidayStringList(item.optJSONArray("workdays")),
            scope = item.optString("scope"),
            basis = item.optString("basis"),
            region = item.optString("region"),
            regionName = item.optString("regionName")
        )
    }
    return out
}

private fun parseHolidayStringList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    val out = ArrayList<String>(array.length())
    for (i in 0 until array.length()) {
        val text = array.optString(i)
        if (text.isNotBlank()) out += text
    }
    return out
}

/**
 * 把一份节日数据序列化成 JSON —— 与 [parseHolidayCatalog] 严格互逆，
 * 管理员用它把客户端那份内置数据原样搬上云（字段一个不落：起止、补课日、依据、地区）。
 */
fun encodeHolidayCatalog(catalog: HolidayCatalog): String {
    val root = JSONObject()
    root.put("version", 1)
    root.put("year", catalog.year)
    root.put("legal", encodeHolidayGroups(catalog.legal))
    root.put("ethnic", encodeHolidayGroups(catalog.ethnic))
    return root.toString()
}

private fun encodeHolidayGroups(groups: List<HolidayGroup>): JSONArray {
    val array = JSONArray()
    groups.forEach { group ->
        array.put(
            JSONObject()
                .put("name", group.name)
                .put("dates", JSONArray(group.dates))
                .put("workdays", JSONArray(group.workdays))
                .put("scope", group.scope)
                .put("basis", group.basis)
                .put("region", group.region)
                .put("regionName", group.regionName)
        )
    }
    return array
}

/**
 * 国家法定节假日。
 *
 * 数据来源：国务院办公厅《关于2026年部分节假日安排的通知》
 * （国办发明电〔2025〕7号）。
 *
 * 说明：2027 年的放假安排国务院通常在当年 11 月发布，目前尚未公布，
 * 因此暂不包含 2027 年元旦与春节；待公布后可在后续版本更新数据。
 *
 * 这一段只是**内置种子**：正常情况下客户端会从云端 `holidays` 接口取最新的一份，
 * 取不到（未登录 / 断网 / 云端还没放数据）时才用它。
 */
object HolidayCalendar {

    private fun range(start: String, end: String): List<String> {
        val s = LocalDate.parse(start)
        val e = LocalDate.parse(end)
        val out = ArrayList<String>()
        var d = s
        while (!d.isAfter(e)) {
            out += d.toString()
            d = d.plusDays(1)
        }
        return out
    }

    /** 内置的法定节假日分组（全国性）。 */
    private val builtinLegal: List<HolidayGroup> = listOf(
        HolidayGroup(
            name = "元旦",
            dates = range("2026-01-01", "2026-01-03"),
            workdays = listOf("2026-01-04")
        ),
        HolidayGroup(
            name = "春节",
            dates = range("2026-02-15", "2026-02-23"),
            workdays = listOf("2026-02-14", "2026-02-28")
        ),
        HolidayGroup(
            name = "清明节",
            dates = range("2026-04-04", "2026-04-06")
        ),
        HolidayGroup(
            name = "劳动节",
            dates = range("2026-05-01", "2026-05-05"),
            workdays = listOf("2026-05-09")
        ),
        HolidayGroup(
            name = "端午节",
            dates = range("2026-06-19", "2026-06-21")
        ),
        HolidayGroup(
            name = "中秋节",
            dates = range("2026-09-25", "2026-09-27")
        ),
        HolidayGroup(
            name = "国庆节",
            dates = range("2026-10-01", "2026-10-07"),
            workdays = listOf("2026-09-20", "2026-10-10")
        )
    )

    /**
     * 内置的完整节日数据（首次安装的缓存种子，也是云端不可用时的回退）。
     *
     * 用 lazy 只建一次：它是常量，每次访问都重建四十多条没有意义。
     */
    val builtinCatalog: HolidayCatalog by lazy {
        HolidayCatalog(
            year = EthnicFestivals.YEAR,
            legal = builtinLegal,
            ethnic = EthnicFestivals.all
        )
    }

    /**
     * 云端下发的最新一份；还没拉到过时为 null，此时一律看 [builtinCatalog]。
     *
     * 为什么把「云端那份」和「内置那份」分开存：内置那份要作为回退一直留着，
     * 拉取失败时不能把它覆盖掉。
     */
    @Volatile
    private var fetchedCatalog: HolidayCatalog? = null

    /**
     * 数据版本号：每次换成云端那份就 +1。
     *
     * [dates] 的缓存键里必须带上它 —— 否则换了数据但设置没变，键没变，
     * 会直接返回上一份集合，云端更新就白拉了。
     */
    @Volatile
    private var catalogVersion: Int = 0

    /** 当前生效的节日数据。 */
    val catalog: HolidayCatalog get() = fetchedCatalog ?: builtinCatalog

    /** 全部法定节假日分组（界面与全局状态都用它）。 */
    val groups: List<HolidayGroup> get() = catalog.legal

    /** 收录的全部民族节日（带地区信息）。 */
    val ethnicGroups: List<HolidayGroup> get() = catalog.ethnic

    /** 当前这份数据的年度（界面要显示出来）。 */
    val year: Int get() = catalog.year

    /** 换用一份新的节日数据（云端拉到或解析缓存成功时调用）。 */
    fun useCatalog(catalog: HolidayCatalog) {
        fetchedCatalog = catalog
        catalogVersion++
    }

    /** 是否启用「法定节假日不上课」，由设置写入。 */
    @Volatile
    var enabled: Boolean = true

    /**
     * 节假日当天课程是否为「淡化」（false = 隐藏）。
     *
     * 与 [enabled] 一起表达界面上那三档：`!enabled` 是「关闭」，`enabled && dim` 是「淡化」，
     * `enabled && !dim` 是「隐藏」。之所以用两个布尔而不是一个枚举：`enabled` 是原有字段，
     * 已有设置与课表备份都按它存，保留它老数据才不会丢；三档只是界面上的一个选项。
     */
    @Volatile
    var dim: Boolean = false

    /** 被用户关闭的法定节假日分组名称（法定是「默认开、记关闭的」）。 */
    @Volatile
    var disabledGroups: Set<String> = emptySet()

    /**
     * 被用户打开的少数民族节日名称（民族节日是「默认关、记打开的」，与上面正好相反）。
     *
     * 单独存一份而不是复用 [disabledGroups]：两边的默认值相反，
     * 混在一起还要靠名字去区分是哪种节日，容易出错。
     */
    @Volatile
    var enabledEthnicGroups: Set<String> = emptySet()

    /** 用户自己添加的假期日期（yyyy-MM-dd）。 */
    @Volatile
    var customDates: Set<String> = emptySet()

    /** 用户自己添加的补课日期（yyyy-MM-dd -> 补哪一天的课）：这些日期算上课，会覆盖放假判定。 */
    @Volatile
    var makeupDates: Map<String, Int> = emptyMap()

    /**
     * 把全局状态拼成一份等价设置，再用唯一的规则算出放假日期。
     *
     * 这样「UI 用设置算」和「业务用全局状态算」不会出现两套规则走偏。
     */
    private fun currentSettings(): TimetableSettings = TimetableSettings(
        skipHolidays = enabled,
        disabledHolidayGroups = disabledGroups,
        enabledEthnicGroups = enabledEthnicGroups,
        customHolidays = customDates,
        customMakeupDays = makeupDates
    )

    /** 缓存：避免每次绘制都重建集合。 */
    @Volatile
    private var cacheKey: String = ""
    @Volatile
    private var cacheValue: Set<String> = emptySet()

    private fun dates(): Set<String> {
        // 每一项影响结果的输入都必须进 key，否则改了它 key 却没变，会直接返回上一份集合
        val key = "$catalogVersion|$enabled|${disabledGroups.sorted().joinToString(",")}|" +
            enabledEthnicGroups.sorted().joinToString(",") + "|" +
            customDates.sorted().joinToString(",") + "|" +
            makeupDates.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
        if (key != cacheKey) {
            cacheValue = currentSettings().activeHolidayDates()
            cacheKey = key
        }
        return cacheValue
    }

    /** 该日期是否为「放假不上课」的日期。 */
    fun isHoliday(date: LocalDate): Boolean = dates().contains(date.toString())

    /**
     * 该日期上的课程该怎么处理 —— **节假日判定的唯一入口**。
     *
     * 以前「是不是节假日」的判断散在 TimeCalc、周视图、日视图、小组件好几处，
     * 每处各自决定「当天有没有课」；加了「淡化」这一档之后，光知道「是不是」
     * 已经不够（隐藏与淡化都要先知道是节假日，但结果相反），所以把判定收在这里，
     * 各处只认返回值。
     */
    fun courseDisplayOn(date: LocalDate): HolidayCourseMode {
        // isHoliday 已经把「总开关关掉」时的法定 / 民族节日排除了；
        // 但自定义假期不受总开关控制（见 activeHolidayDates），总开关为「关闭」时它照样是
        // 放假日，因此这里不能再按 enabled 直接返回 NORMAL —— 那会让 OFF 档比现在更宽松。
        if (!isHoliday(date)) return HolidayCourseMode.NORMAL
        return if (enabled && dim) HolidayCourseMode.DIMMED else HolidayCourseMode.HIDDEN
    }

    /** 该日期所属的节假日名称（不是节假日时返回 null）。 */
    fun holidayName(date: LocalDate): String? {
        val text = date.toString()
        // 自定义假期与总开关无关，先判
        if (text in customDates) return "自定义假期"
        if (!enabled) return null
        groups.firstOrNull { it.name !in disabledGroups && text in it.dates }?.let { return it.name }
        return ethnicGroups
            .firstOrNull { it.name in enabledEthnicGroups && text in it.dates }?.name
    }

    /** 是否为调休上班日。 */
    fun isMakeupWorkday(date: LocalDate): Boolean {
        if (!enabled) return false
        val text = date.toString()
        if (groups.any { it.name !in disabledGroups && text in it.workdays }) return true
        return ethnicGroups.any { it.name in enabledEthnicGroups && text in it.workdays }
    }
}
