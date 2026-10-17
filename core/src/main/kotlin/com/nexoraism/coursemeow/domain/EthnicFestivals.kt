package com.nexoraism.coursemeow.domain

/**
 * 少数民族习惯节日的预置数据（按年度）。
 *
 * 为什么必须逐年预置、不能靠推算：
 * - 《全国年节及纪念日放假办法》第四条把少数民族节日的放假权交给了「少数民族聚居地区的
 *   地方人民政府」，所以**不存在全国统一的民族假**，每个地区、每一年都要看当地政府的通知；
 * - 日期分四类，只有两类能算：农历节日（三月三、火把节、苗年）可以接农历库推算，固定公历
 *   的（延边 9·3 州庆、凉山建州纪念日）能直接写死；而**伊斯兰历**（肉孜节、古尔邦节）与
 *   **藏历、傣历**（藏历新年、泼水节）没法用简单算法推算 —— 伊斯兰历每年提前约 11 天，
 *   藏历与春节可能同日、差一天、也可能差一个月；
 * - 更关键的是「法定天数是死的、实际放假是活的」：同一个节日在不同地区给的天数不同
 *   （2026 年古尔邦节新疆放 5 天、宁夏只放 2 天），同一个地区不同年份也能靠补休拉长
 *   （2026 年广西三月三法定 2 天、靠补休变成 4 天）。
 *
 * 因此这里的数据是「某一年的实际放假安排」，不是可以外推的规则。数据年份见 [YEAR]，
 * 界面必须把年份显示出来。次年通知集中在上一年的 11-12 月发布，届时补数据即可。
 *
 * 每条数据都写明依据（[HolidayGroup.basis]），便于以后核对与更新。
 *
 * **为什么按地区折叠、而不是让用户先选地区。** 放假地区是省 / 自治州两级、收录到的有近
 * 二十个，让用户在下拉里找自己的州反而比直接看列表更费事；而且同一个州里不同学校、
 * 不同民族的情况也不一样。所以展示上按地区收成一层可展开的标题（[HolidayGroup.region]），
 * 但**默认都不生效**，用户把自己会放的那几个打开即可（见 [all]）。
 * 节日名自带地区前缀（例如「凉山火把节」），折叠收起时也能一眼看出是哪里的。
 */
object EthnicFestivals {

    /** 当前这份数据的年度。 */
    const val YEAR = 2026

    private fun range(start: String, end: String): List<String> {
        val s = java.time.LocalDate.parse(start)
        val e = java.time.LocalDate.parse(end)
        val out = ArrayList<String>()
        var d = s
        while (!d.isAfter(e)) {
            out += d.toString()
            d = d.plusDays(1)
        }
        return out
    }

    /**
     * 某个地区的节日分组（维护时的组织方式，不对外暴露）。
     *
     * 分组的 `name` 一律带地区前缀（例如「凉山火把节」）：一是「开不开这个节」是按名字记的，
     * 不带前缀时「州庆」在很多个州里都有、开关会互相串味；二是平铺之后用户仍要一眼看出
     * 这是哪里的节日。
     */
    private fun groupsFor(code: String): List<HolidayGroup> = when (code) {
        "guangxi" -> listOf(
            HolidayGroup(
                name = "广西三月三",
                dates = range("2026-04-17", "2026-04-20"),
                basis = "广西壮族自治区人民政府令第98号 · 2026年3月4日放假通知"
            )
        )

        "xinjiang" -> listOf(
            HolidayGroup(
                name = "新疆肉孜节",
                dates = range("2026-03-20", "2026-03-22"),
                basis = "新疆维吾尔自治区人民政府令第174号 · 2026年放假时间安排"
            ),
            HolidayGroup(
                name = "新疆古尔邦节",
                dates = range("2026-05-27", "2026-05-31"),
                basis = "同上 · 新疆2026年放假时间安排"
            )
        )

        // 2026 年开斋节落在 3 月 21 日（周六），通知明确「周末正常休息」、未额外增加天数，
        // 所以这里没有开斋节这一条 —— 那不是放假，就是个普通周末。
        "ningxia" -> listOf(
            HolidayGroup(
                name = "宁夏古尔邦节",
                dates = range("2026-05-27", "2026-05-28"),
                basis = "宁政办发〔2025〕36号"
            )
        )

        // 西藏只查到拉萨市口径的通知（自治区层面没有统一的民族节日放假通知），
        // 所以这里用的是拉萨市的安排，界面上的依据也照实写明。
        // 藏历新年与春节连休，春节那几天已经由全国性分组覆盖，这里只补超出的部分。
        "tibet" -> listOf(
            HolidayGroup(
                name = "西藏藏历新年",
                dates = range("2026-02-24", "2026-02-26"),
                basis = "拉萨市人民政府办公室 2026 年春节藏历新年放假通知（与春节连休）"
            ),
            HolidayGroup(
                name = "西藏雪顿节",
                dates = listOf("2026-08-14"),
                basis = "拉萨市人民政府办公室 2026 年雪顿节放假通知"
            )
        )

        "yanbian" -> listOf(
            HolidayGroup(
                name = "延边州庆",
                dates = range("2026-09-02", "2026-09-04"),
                basis = "延边州人民政府办公室 2026 年州庆放假通告 · 依自治条例"
            )
        )

        "liangshan" -> listOf(
            HolidayGroup(
                name = "凉山民族团结进步节",
                dates = listOf("2026-08-05"),
                basis = "凉府办函〔2025〕76号"
            ),
            HolidayGroup(
                name = "凉山火把节",
                dates = range("2026-08-06", "2026-08-12"),
                workdays = listOf("2026-08-02", "2026-08-15"),
                basis = "凉府办函〔2025〕76号 · 农历六月二十四"
            ),
            HolidayGroup(
                name = "凉山建州纪念日",
                dates = listOf("2026-10-08"),
                basis = "凉府办函〔2025〕76号（国庆节在建州纪念日合并放至 10 月 8 日）"
            ),
            HolidayGroup(
                name = "凉山彝历新年",
                dates = range("2026-11-20", "2026-11-26"),
                workdays = listOf("2026-11-15", "2026-11-28"),
                basis = "凉府办函〔2025〕76号 · 公历 11 月 20 日彝族年"
            )
        )

        "ganzi" -> listOf(
            HolidayGroup(
                name = "甘孜藏历新年",
                dates = range("2026-02-24", "2026-03-02"),
                basis = "甘孜州人民政府办公室 2026 年部分节假日安排的通知（与春节连休）"
            ),
            HolidayGroup(
                name = "甘孜民族团结进步节",
                dates = listOf("2026-09-16"),
                basis = "同上 · 甘孜州2026年部分节假日安排"
            ),
            HolidayGroup(
                name = "甘孜建州节",
                dates = range("2026-11-23", "2026-11-25"),
                basis = "同上 · 甘孜州2026年部分节假日安排"
            )
        )

        "aba" -> listOf(
            HolidayGroup(
                name = "阿坝建州纪念日",
                dates = range("2026-02-24", "2026-02-26"),
                basis = "阿府办函〔2025〕22号"
            ),
            HolidayGroup(
                name = "阿坝藏历新年",
                dates = listOf("2026-02-27", "2026-02-28", "2026-03-02"),
                basis = "阿府办函〔2025〕22号"
            ),
            HolidayGroup(
                name = "阿坝民族团结进步节",
                dates = range("2026-09-12", "2026-09-14"),
                basis = "阿府办函〔2025〕22号"
            ),
            HolidayGroup(
                name = "阿坝羌历新年",
                dates = range("2026-11-07", "2026-11-11"),
                basis = "阿府办函〔2025〕22号 · 羌历新年"
            )
        )

        "qiandongnan" -> listOf(
            HolidayGroup(
                name = "黔东南苗年",
                dates = listOf("2026-11-16"),
                basis = "黔东南州2026年节假日安排 · 依自治条例，农历十月初七苗年"
            ),
            HolidayGroup(
                name = "黔东南侗年",
                dates = listOf("2026-12-09"),
                basis = "黔东南州2026年节假日安排 · 依自治条例，农历十一月初一侗年"
            ),
            HolidayGroup(
                name = "黔东南州庆",
                dates = listOf("2026-07-23", "2026-07-24"),
                basis = "黔东南州2026年节假日安排"
            )
        )

        "qianxinan" -> listOf(
            HolidayGroup(
                name = "黔西南六月六",
                dates = listOf("2026-07-19", "2026-07-20"),
                basis = "黔西南州2026年部分节假日安排的通知 · 农历六月初六"
            ),
            HolidayGroup(
                name = "黔西南八月八",
                dates = listOf("2026-09-18"),
                basis = "同上 · 农历八月初八"
            ),
            HolidayGroup(
                name = "黔西南州庆",
                dates = listOf("2026-04-30", "2026-05-06"),
                basis = "同上 · 黔西南州2026年部分节假日安排"
            )
        )

        "qiannan" -> listOf(
            HolidayGroup(
                name = "黔南四月八",
                dates = listOf("2026-05-24", "2026-05-25"),
                basis = "黔南府办函〔2025〕42号 · 苗族传统节日（四月八）"
            ),
            HolidayGroup(
                name = "黔南六月六",
                dates = listOf("2026-07-19", "2026-07-20"),
                basis = "黔南府办函〔2025〕42号 · 布依族传统节日（六月六）"
            ),
            HolidayGroup(
                name = "黔南州庆",
                dates = range("2026-08-08", "2026-08-11"),
                basis = "黔南府办函〔2025〕42号"
            )
        )

        // 德宏是「只给特定民族放假」的特例：阔时节只给傈僳族、阿露窝罗节只给阿昌族，
        // 这两个节日用 scope 标出来，界面会提示「仅限某族放假」。
        "dehong" -> listOf(
            HolidayGroup(
                name = "德宏阔时节",
                dates = listOf("2026-02-25", "2026-02-26"),
                scope = "仅限傈僳族放假",
                basis = "德政办发〔2025〕56号"
            ),
            HolidayGroup(
                name = "德宏目瑙纵歌节",
                dates = listOf("2026-03-02", "2026-03-03"),
                basis = "德政办发〔2025〕56号"
            ),
            HolidayGroup(
                name = "德宏阿露窝罗节",
                dates = listOf("2026-03-19", "2026-03-20"),
                scope = "仅限阿昌族放假",
                basis = "德政办发〔2025〕56号"
            ),
            HolidayGroup(
                name = "德宏泼水节",
                dates = listOf("2026-04-13", "2026-04-14"),
                basis = "德政办发〔2025〕56号"
            ),
            HolidayGroup(
                name = "德宏州庆",
                dates = listOf("2026-07-23", "2026-07-24"),
                basis = "德政办发〔2025〕56号"
            )
        )

        "xishuangbanna" -> listOf(
            HolidayGroup(
                name = "西双版纳州庆",
                dates = listOf("2026-01-22", "2026-01-23"),
                basis = "西政办函〔2025〕25号"
            ),
            HolidayGroup(
                name = "西双版纳泼水节",
                dates = range("2026-04-13", "2026-04-15"),
                basis = "西政办函〔2025〕25号 · 傣历新年节"
            )
        )

        "dali" -> listOf(
            HolidayGroup(
                name = "大理三月街",
                dates = range("2026-05-01", "2026-05-03"),
                basis = "大理州2026年部分节假日安排的通知 · 与劳动节重合"
            ),
            HolidayGroup(
                name = "大理州庆",
                dates = listOf("2026-11-22", "2026-11-23"),
                basis = "大理州2026年部分节假日安排的通知"
            )
        )

        "wenshan" -> listOf(
            HolidayGroup(
                name = "文山州庆",
                dates = listOf("2026-04-01", "2026-04-02"),
                basis = "文政办发〔2025〕104号"
            ),
            HolidayGroup(
                name = "文山三月三",
                dates = listOf("2026-04-03"),
                basis = "文政办发〔2025〕104号 · 壮族三月三"
            ),
            HolidayGroup(
                name = "文山闹兜阳",
                dates = range("2026-06-22", "2026-06-24"),
                basis = "文政办发〔2025〕104号 · 苗族闹兜阳"
            )
        )

        // 红河只查到「矻扎扎节」的准信（州教体局提示 6 月 22-23 日）；火把节与州庆的
        // 2026 年通知原文没有检索到，所以宁可不列，也不写一个猜的日期。
        "honghe" -> listOf(
            HolidayGroup(
                name = "红河矻扎扎节",
                dates = listOf("2026-06-22", "2026-06-23"),
                basis = "红河州教育体育局 2026 年提示 · 依自治条例"
            )
        )

        "chuxiong" -> listOf(
            HolidayGroup(
                name = "楚雄火把节",
                dates = range("2026-08-05", "2026-08-07"),
                basis = "楚雄州人民政府 2026 年火把节放假安排 · 农历六月二十四"
            )
        )

        "enshi" -> listOf(
            HolidayGroup(
                name = "恩施州庆",
                dates = listOf("2026-08-19", "2026-08-20"),
                basis = "恩施州人民政府办公室 2026 年州庆放假通知 · 依自治条例"
            )
        )

        "xiangxi" -> listOf(
            HolidayGroup(
                name = "湘西少数民族习惯年节",
                dates = listOf("2026-02-14"),
                basis = "湘西州人民政府办公室 2026 年部分节假日放假调休通知"
            ),
            HolidayGroup(
                name = "湘西州成立纪念日",
                dates = listOf("2026-09-20", "2026-09-21"),
                basis = "同上 · 依自治条例全体公民放假 2 天"
            )
        )

        else -> emptyList()
    }

    /**
     * 收录了数据的地区，顺序就是 [all] 里的顺序。
     *
     * 只用来在维护时把数据按地区分组，界面**不再**按地区筛选 —— 平铺出来让用户自己挑。
     */
    private val regionCodes = listOf(
        "guangxi", "xinjiang", "ningxia", "tibet",
        "yanbian", "liangshan", "ganzi", "aba",
        "qiandongnan", "qianxinan", "qiannan",
        "dehong", "xishuangbanna", "dali", "wenshan", "honghe", "chuxiong",
        "enshi", "xiangxi"
    )

    /** 地区代码 -> 界面标题用的地区名。 */
    private val regionNames: Map<String, String> = mapOf(
        "guangxi" to "广西",
        "xinjiang" to "新疆",
        "ningxia" to "宁夏",
        "tibet" to "西藏",
        "yanbian" to "延边",
        "liangshan" to "凉山",
        "ganzi" to "甘孜",
        "aba" to "阿坝",
        "qiandongnan" to "黔东南",
        "qianxinan" to "黔西南",
        "qiannan" to "黔南",
        "dehong" to "德宏",
        "xishuangbanna" to "西双版纳",
        "dali" to "大理",
        "wenshan" to "文山",
        "honghe" to "红河",
        "chuxiong" to "楚雄",
        "enshi" to "恩施",
        "xiangxi" to "湘西"
    )

    /** 某个地区代码的展示名；认不出来时退回代码本身，避免出现空标题。 */
    fun regionNameOf(code: String): String = regionNames[code] ?: code

    /**
     * 全部民族节日，按上面的地区顺序排列（同地区的自然连在一起），并把地区补进每一条。
     *
     * 地区是「按地区折叠展示」与「序列化上云」都要用的字段，所以在这里一次补好，
     * 而不是等界面拿名字前缀去猜。
     *
     * 用 lazy 只建一次：这份数据是常量，每次重组都重建四十多条没有意义。
     */
    val all: List<HolidayGroup> by lazy {
        regionCodes.flatMap { code ->
            val name = regionNameOf(code)
            groupsFor(code).map { it.copy(region = code, regionName = name) }
        }
    }
}
