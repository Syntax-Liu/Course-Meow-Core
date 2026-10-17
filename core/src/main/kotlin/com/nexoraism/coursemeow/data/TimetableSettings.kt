package com.nexoraism.coursemeow.data

import androidx.compose.runtime.Immutable
import java.time.LocalDate

/** 主题模式。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 动画风格：刚性（到边界后无动画）/ 柔性（到边界后有回弹动画）。 */
enum class AnimationStyle { RIGID, SOFT }

/**
 * 动画效果档位（性能选项）。
 *
 * - [STANDARD]：所有过渡动画都保留；
 * - [SMOOTH]：页面切换与抽屉不做动画（少掉一批过渡帧）；
 * - [MINIMAL]：在 [SMOOTH] 基础上，配色切换与「回到本周」的滚动也直接到位。
 *
 * 只影响「效果」，不改任何内容与数据。
 */
enum class PerformanceMode { STANDARD, SMOOTH, MINIMAL }

/**
 * 温度的显示单位。
 *
 * 天气数据源（Open-Meteo）给的、以及库里存的，永远是摄氏度；这里只决定显示时
 * 要不要换算成华氏度，不改变任何存储值。
 */
enum class TemperatureUnit { CELSIUS, FAHRENHEIT }

/**
 * 节假日当天课程的处理方式（默认隐藏）。
 *
 * 界面上是「一个总开关 + 隐藏 / 淡化二选一」（见 [holidayMode]）：开关关掉即 [OFF]。
 * 枚举保留三个取值，是因为渲染逻辑按它分支，且 [skipHolidays] / [dimHolidayCourses]
 * 两个既有布尔字段的语义不变。
 */
enum class HolidayMode {
    /** 隐藏：当天的课不显示。 */
    HIDDEN,

    /** 淡化：课程照常显示，但整体变灰。 */
    DIMMED,

    /** 关闭：不做任何处理，节假日与平常日期一样（对应界面上的总开关关闭）。 */
    OFF
}

/** 周视图左侧时间列的显示方式。 */
enum class TimeColumnStyle {
    /** 节次 + 上下课时间（默认）。 */
    FULL,
    /** 只显示上下课时间，省一行。 */
    TIME_ONLY,
    /** 不显示时间列，把宽度全留给课程。 */
    HIDDEN
}

/**
 * 一周的第一天。
 *
 * [MONDAY]：周视图显示 周一…周日，区间就是该自然周（[周一, 周日]）。
 * [SUNDAY]：周视图显示 周日…周六，把该周周一往前挪一天当第一列，
 * 区间是 [周一 - 1, 周一 + 5]；这样列顺序与日期都连续递增，不会再出现
 * 「周日排最前、日期数字反而最大」。
 *
 * 无论选哪个，周次编号始终以周一为锚（学期第 N 周从该周周一起算），改这里只影响
 * 周视图画出来的那 7 天，不会让提醒、小组件、节假日判定用的「第几周」跟着变。
 */

/**
 * 配色方案：包含浅色与深色两套背景色和主题色。
 *
 * 深色模式使用独立的 [darkBg] / [darkAccent]，而不是简单地把浅色方案调暗，
 * 这样明暗两种模式才能有明显区分。
 *
 * [lightAppBar] / [darkAppBar] 与 [lightDrawer] / [darkDrawer] 都是「结构色」，
 * 不填就跟主题色一致（保持不填时的老观感）；填了就可以把标题栏、账号页
 * 单独指定成另外的颜色，例如：白色页面 + 浅蓝标题栏 + 深蓝账号页。
 *
 * [lightButton] / [darkButton] 是「主按钮」（确定 / 保存 / 发送这类实心按钮）的底色：
 * 不填时跟主题色 [lightAccent] / [darkAccent] 一致（老观感不变），
 * 填了就能让按钮单独用一支颜色（例如浅蓝底配深色文字），
 * 而链接、选中态这些仍走主题色，二者互不影响。
 */
data class ColorScheme(
    val name: String,
    val lightBg: Long,
    val lightAccent: Long,
    val darkBg: Long,
    val darkAccent: Long,
    /** 浅色模式的标题栏（顶栏）底色；不填用 [lightAccent]。 */
    val lightAppBar: Long? = null,
    /** 深色模式的标题栏（顶栏）底色；不填用 [darkAccent]。 */
    val darkAppBar: Long? = null,
    /** 浅色模式的账号页（抽屉）底色；不填用 [lightAccent]。 */
    val lightDrawer: Long? = null,
    /** 深色模式的账号页（抽屉）底色；不填用 [darkAccent]。 */
    val darkDrawer: Long? = null,
    /** 浅色模式的主按钮底色；不填用 [lightAccent]（老方案观感不变）。 */
    val lightButton: Long? = null,
    /** 深色模式的主按钮底色；不填用 [darkAccent]（老方案观感不变）。 */
    val darkButton: Long? = null
)

/**
 * 内置配色方案，名称统一为三个字。
 *
 * 十二套按色相分布挑选，相邻两套的第一个字不会重复：
 * 蓝 → 靛 → 紫 → 玫红 → 朱红 → 橙 → 金 → 青柠 → 翡翠 → 湖青 → 天空 → 石墨。
 * 之前蓝色占了四套（科技 / 宝石 / 靛青 / 天空），区分度太低，已收敛成一套蓝 + 一套天空青。
 */
val ColorSchemes: List<ColorScheme> = listOf(
    // 默认方案「科技蓝」：明亮科技蓝基调 —— 白色卡片浮在淡蓝页底上，
    // 主色 #2563EB 贯穿链接 / 选中态 / 图标 / 主按钮，替代原来的深海军蓝 #0E2052。
    // 浅色：背景 #F5F8FD、顶栏 #F0F4FD、账号页 #0E2052（深蓝，观感更沉得住）、按钮 #2563EB
    // 深色：背景 #0B1220、顶栏 #0B1220、账号页 #0E2052、按钮 #4C8DFF
    //   （深色按钮用 #4C8DFF 而非 #6BA3FF —— 白字压 #6BA3FF 只有 3.2:1 不达标）
    ColorScheme(
        name = "科技蓝",
        lightBg = 0xFFF5F8FD, lightAccent = 0xFF2563EB,
        darkBg = 0xFF0B1220, darkAccent = 0xFF6BA3FF,
        lightAppBar = 0xFFF0F4FD, darkAppBar = 0xFF0B1220,
        lightDrawer = 0xFF0E2052, darkDrawer = 0xFF0E2052,
        lightButton = 0xFF2563EB, darkButton = 0xFF4C8DFF
    ),
    ColorScheme(
        name = "靛青蓝",
        lightBg = 0xFFF0F1FA, lightAccent = 0xFF4F46E5,
        darkBg = 0xFF171A33, darkAccent = 0xFF818CF8,
        lightAppBar = 0xFFE6E8F6, darkAppBar = 0xFF171A33,
        lightDrawer = 0xFF211C63, darkDrawer = 0xFF211C63
    ),
    ColorScheme(
        name = "紫罗兰",
        lightBg = 0xFFF2F0F8, lightAccent = 0xFF7C3AED,
        darkBg = 0xFF1E1730, darkAccent = 0xFFA78BFA,
        lightAppBar = 0xFFE8E5F3, darkAppBar = 0xFF1E1730,
        lightDrawer = 0xFF2B1A55, darkDrawer = 0xFF2B1A55
    ),
    ColorScheme(
        name = "玫瑰红",
        lightBg = 0xFFF9F0F4, lightAccent = 0xFFDB2777,
        darkBg = 0xFF2B1520, darkAccent = 0xFFF472B6,
        lightAppBar = 0xFFF3E3EB, darkAppBar = 0xFF2B1520,
        lightDrawer = 0xFF4A1130, darkDrawer = 0xFF4A1130
    ),
    ColorScheme(
        name = "朱砂红",
        lightBg = 0xFFFBF1F1, lightAccent = 0xFFDC2626,
        darkBg = 0xFF241012, darkAccent = 0xFFF87171,
        lightAppBar = 0xFFF7EAEA, darkAppBar = 0xFF241012,
        lightDrawer = 0xFF4A1212, darkDrawer = 0xFF4A1212
    ),
    ColorScheme(
        name = "琥珀橙",
        lightBg = 0xFFF9F3EE, lightAccent = 0xFFEA580C,
        darkBg = 0xFF2B1A0F, darkAccent = 0xFFFB923C,
        lightAppBar = 0xFFF3E8DF, darkAppBar = 0xFF2B1A0F,
        lightDrawer = 0xFF4A2410, darkDrawer = 0xFF4A2410
    ),
    ColorScheme(
        name = "香槟金",
        lightBg = 0xFFFAF5EA, lightAccent = 0xFFB7791F,
        darkBg = 0xFF241D0E, darkAccent = 0xFFE7C05A,
        lightAppBar = 0xFFF5EEE0, darkAppBar = 0xFF241D0E,
        lightDrawer = 0xFF4A3208, darkDrawer = 0xFF4A3208
    ),
    ColorScheme(
        name = "青柠绿",
        lightBg = 0xFFF3F6ED, lightAccent = 0xFF65A30D,
        darkBg = 0xFF202A12, darkAccent = 0xFFA3E635,
        lightAppBar = 0xFFE9EFE1, darkAppBar = 0xFF202A12,
        lightDrawer = 0xFF2B3D0B, darkDrawer = 0xFF2B3D0B
    ),
    ColorScheme(
        name = "翡翠绿",
        lightBg = 0xFFEFF5F1, lightAccent = 0xFF059669,
        darkBg = 0xFF12291F, darkAccent = 0xFF34D399,
        lightAppBar = 0xFFE3EFE8, darkAppBar = 0xFF12291F,
        lightDrawer = 0xFF0A3D2C, darkDrawer = 0xFF0A3D2C
    ),
    ColorScheme(
        name = "湖青色",
        lightBg = 0xFFEFF6F8, lightAccent = 0xFF0891B2,
        darkBg = 0xFF10262E, darkAccent = 0xFF22D3EE,
        lightAppBar = 0xFFE4F0F3, darkAppBar = 0xFF10262E,
        lightDrawer = 0xFF083C4A, darkDrawer = 0xFF083C4A
    ),
    ColorScheme(
        name = "天空青",
        lightBg = 0xFFEFF6FB, lightAccent = 0xFF0EA5E9,
        darkBg = 0xFF0F2433, darkAccent = 0xFF38BDF8,
        lightAppBar = 0xFFE4F0F8, darkAppBar = 0xFF0F2433,
        lightDrawer = 0xFF0A3A57, darkDrawer = 0xFF0A3A57
    ),
    ColorScheme(
        name = "石墨灰",
        lightBg = 0xFFF4F5F7, lightAccent = 0xFF475569,
        darkBg = 0xFF14181D, darkAccent = 0xFF94A3B8,
        lightAppBar = 0xFFEDEFF2, darkAppBar = 0xFF14181D,
        lightDrawer = 0xFF1F2937, darkDrawer = 0xFF1F2937
    )
)

/** 默认配色方案（科技蓝）。 */
const val DEFAULT_COLOR_SCHEME = 0

/** 解析配色方案；序号越界时回落到默认方案。 */
fun resolveColorScheme(index: Int): ColorScheme =
    ColorSchemes.getOrElse(index) { ColorSchemes[DEFAULT_COLOR_SCHEME] }

/** 应用设置（存储在 DataStore 中）。 */
// 标记为 @Immutable：这个类里有 List / Set 字段，不加注解时 Compose 会把它判定为
// 「不稳定」，于是所有接收它的界面在父组件重组时都无法跳过重组。
// 加上之后，只要内容没变就能直接跳过，页面切换和滑动时的重组量显著下降。
@Immutable
data class TimetableSettings(
    val semesterStartDate: Long = 0L,   // 第 1 周周一的 00:00 时间戳（毫秒）
    val semesterEndDate: Long = 0L,     // 学期结束日期
    val periodCount: Int = 11,
    val periodStartTimes: List<String> = defaultPeriodStarts,
    val periodEndTimes: List<String> = defaultPeriodEnds,
    /** 是否开启「上课前提醒」开关。 */
    val remindEnabled: Boolean = true,
    /**
     * 提前提醒的分钟数列表，可配置多个；0 表示上课时提醒。
     * 例如 [0, 10] 表示上课时提醒 + 提前 10 分钟提醒。
     */
    val remindMinutesList: List<Int> = listOf(10),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 动画风格，默认刚性。 */
    val animationStyle: AnimationStyle = AnimationStyle.RIGID,
    /**
     * 动画效果档位（性能选项），默认标准。
     *
     * 只在本机有意义（取决于这台设备的性能），所以归在「本机状态」，不随账号同步。
     */
    val performanceMode: PerformanceMode = PerformanceMode.STANDARD,
    /**
     * 整个软件的刷新率（Hz），0 表示跟随系统。
     *
     * 只在这台设备上有意义（屏幕支持哪些档位因机而异），和 [performanceMode] 一样
     * 归在「本机状态」，不随账号同步。
     */
    val refreshRateHz: Int = 0,
    /**
     * 色彩矫正：0 关闭 / 1 绿色弱视（绿-红）/ 2 红色弱视（红-绿）/
     * 3 蓝色弱视（蓝-黄）/ 4 灰度。只在本机生效。
     */
    val colorCorrectionMode: Int = 0,
    /** 法定节假日是否自动隐藏课程（不上课）。 */
    val skipHolidays: Boolean = true,
    /**
     * 节假日当天的课程改「淡化」而不是隐藏（仅在 [skipHolidays] 为 true 时有意义）。
     *
     * 与 [skipHolidays] 一起拼出界面上「隐藏 / 淡化」二选一（见 [holidayMode]）：之所以用两个布尔、
     * 而不是直接换成枚举字段，是因为 [skipHolidays] 是原有字段，已有的本机设置与
     * 课表备份都按它存，保留它老数据才不会丢。
     */
    val dimHolidayCourses: Boolean = false,
    /**
     * 是否铺一层「测试版本 · 仅供参考」的水印（仅管理员可开）。
     *
     * 用来拍截图、发版本预告：让看图的人一眼知道这不是正式版。只在本机生效。
     */
    val betaWatermark: Boolean = false,
    /** 被用户关闭的节假日分组名称。 */
    val disabledHolidayGroups: Set<String> = emptySet(),
    /** 用户自己添加的假期日期（yyyy-MM-dd）。 */
    val customHolidays: Set<String> = emptySet(),
    /**
     * 用户**打开**的少数民族节日名称（见 [com.nexoraism.coursemeow.domain.EthnicFestivals]）。
     *
     * 注意与 [disabledHolidayGroups] 的默认值正好相反：法定节假日是默认生效、记「关掉的」，
     * 少数民族节日是**默认都不生效**、记「打开的」。
     *
     * 为什么默认不生效：少数民族习惯节日的放假权在地方人民政府，全国没有统一规定，
     * 同一个节日在不同地区、不同年份的实际安排都不一样（2026 年古尔邦节新疆放 5 天、
     * 宁夏只放 2 天）。如果默认全开，等于给所有人凭空加了四十多天假，课表直接就不能看了，
     * 所以只能由用户认领自己会放的那几个。
     */
    val enabledEthnicGroups: Set<String> = emptySet(),
    /**
     * 用户自己添加的补课日期：日期（yyyy-MM-dd）-> 补哪一天的课（周一 = 1 … 周日 = 7）。
     *
     * 补课日算「要上课」：会覆盖放假判定，并且按指定的那一天显示课程
     * （例如「周六补周三的课」，那天就显示周三的课程）。
     */
    val customMakeupDays: Map<String, Int> = emptyMap(),
    /** 配色方案索引（见 [ColorSchemes]）。 */
    val colorSchemeIndex: Int = DEFAULT_COLOR_SCHEME,
    /** 当前正在查看 / 编辑的课表 id，0 表示还没有任何课表。 */
    val activeTimetableId: Long = 0L,
    /** 开发者模式（在设置页连点创作者名字 8 次开启），开启后设置页会多出「开发者选项」。 */
    val developerMode: Boolean = false,

    /** 开发者模式：屏幕角落的实时帧率浮层。 */
    val showFps: Boolean = false,

    /** 开发者模式：后台自动记录性能数据。 */
    val perfRecord: Boolean = false,

    // ---------- 夏令时 / 冬令时 ----------

    /** 是否按日期自动切换夏令时 / 冬令时作息。默认关闭（始终使用夏令时作息）。 */
    val dstEnabled: Boolean = false,
    /** 夏令时开始日期，格式 "MM-dd"，每年循环。 */
    val dstSummerStart: String = "04-07",
    /** 冬令时开始日期，格式 "MM-dd"，每年循环。 */
    val dstWinterStart: String = "10-08",
    /** 夏令时作息（[periodStartTimes] / [periodEndTimes] 生效时即取自这里）。 */
    val summerPeriodStartTimes: List<String> = defaultSummerStarts,
    val summerPeriodEndTimes: List<String> = defaultSummerEnds,
    /** 冬令时作息。 */
    val winterPeriodStartTimes: List<String> = defaultWinterStarts,
    val winterPeriodEndTimes: List<String> = defaultWinterEnds,

    /**
     * 是否开启「按教学楼自定义节次时间」。
     *
     * 不同教学楼的上课时间可能不一样（同一所学校里，本部与某栋楼能差十分钟）。
     * 开启后：[periodStartTimes] / [periodEndTimes] 只作为「没单独设置的那些楼」的默认值，
     * 主页左侧那一栏也只显示节次、不再显示时间，时间挪进课程框里按各自的教学楼显示。
     */
    val buildingPeriodTimesEnabled: Boolean = false,

    /** 教学楼 -> 这栋楼自己的上下课时间（只记用户改过的那些楼）。 */
    val buildingPeriodTimes: Map<String, BuildingPeriodTimes> = emptyMap(),

    /** 周视图每节课的行高（dp）；0 表示自动撑满屏幕。 */
    val weekRowHeight: Int = 0,

    // ---------- 课表显示自定义 ----------

    /** 周视图左侧时间列的显示方式。 */
    val timeColumnStyle: TimeColumnStyle = TimeColumnStyle.FULL,
    /** 周视图是否显示周六。 */
    val showSaturday: Boolean = true,
    /** 周视图是否显示周日。 */
    val showSunday: Boolean = true,
    /** 课程块上是否显示上课地点。 */
    val showCourseLocation: Boolean = true,
    /** 课程块上是否显示教师（周视图格子较窄，默认不显示）。 */
    val showCourseTeacher: Boolean = false,
    /** 课程块上是否显示「单周 / 双周 / 自定义周」标记。 */
    val showWeekMark: Boolean = true,
    /** 已上完的课程是否淡化显示（今天按时间判断，过去的日期整页都算上完）。 */
    val dimPastCourses: Boolean = true,
    /** 主页是否显示「正在上 / 下一节」提示栏。 */
    val showNowBar: Boolean = false,

    // ---------- 课程块文字 ----------

    /** 周视图课程名字号（sp）。 */
    val courseNameFontSize: Int = 12,
    /** 周视图上课地点字号（sp）。 */
    val courseLocationFontSize: Int = 8,
    /** 周视图教师字号（sp）。 */
    val courseTeacherFontSize: Int = 8,
    /** 周视图课程名最多显示几行。 */
    val courseNameMaxLines: Int = 5,
    /** 周视图地点 / 教师最多显示几行。 */
    val courseMetaMaxLines: Int = 4,

    /**
     * 周视图点空白格新建课程时，点到「其他」格子的行为。
     *
     * - false（默认）：取消选中，那一格不会被选中，要再点一次才能选中它；
     * - true：把选中框移到刚点的那一格，再点一次就能新建。
     */
    val weekCellTapMove: Boolean = false,

    /**
     * 主页课表只读模式。
     *
     * 开启后主页的周视图 / 日视图都只能看：点空白格不会选中、不会新建课程，
     * 课程详情里也不再提供编辑与删除。适合「课表已经排好，只想看不想动」的场景。
     */
    val homeReadOnly: Boolean = false,

    /**
     * 「锁定课表」开关。
     *
     * 锁的是**新增**，不是编辑：点空白格不会出现加号、选不中，但点已有课程
     * 照样能打开详情并修改。适合「课表排好了，怕手滑点到空格子多出一节课」。
     *
     * 和 [homeReadOnly] 是单向联动的，规则集中放在 SettingsRepository 的两个
     * setter 里，不要在别处再写一遍：
     * - 开只读 → 自动跟着锁定（只读比锁定更严，锁不锁都点不了空白格）；
     * - 关锁定 → 连只读一起关（用户是想「能编辑了」，不该剩个只读卡着）；
     * - 关只读 → 不动锁定（从「全锁」退到「只禁新增」是很自然的中间档）；
     * - 开锁定 → 不动只读。
     */
    val homeLocked: Boolean = false,

    /**
     * 各类注释「新建时默认选哪边」的用户覆盖值：`NoteKind.id` -> 是否跟随课程。
     *
     * 只放被用户改过的类型；查不到的走 [NoteKind.permanentByDefault]。
     * 这里存的是**默认值**，不是某条注释自己的作用域 —— 改它不会把已经填好的
     * 注释搬家。
     */
    val noteDefaultsPermanent: Map<String, Boolean> = emptyMap(),

    /**
     * 上课提醒正文里要带哪些要素（片段清单里每一项的开关）。
     *
     * 取值是要素 id：`name`（课程名）/ `time` / `location` / `teacher`，以及各注释类型
     * （见 [NoteKind.id]：`device`、`textbook`…）。片段的**顺序**另见 [notifySegments]。
     * 没开 [remindEnabled] 时这项不起作用。
     */
    val notifyElementIds: Set<String> = DEFAULT_NOTIFY_ELEMENTS,

    /**
     * 通知正文的片段顺序（目前只有变量片段）。
     *
     * 缺省值 [DEFAULT_NOTIFY_SEGMENTS] 与引入本字段之前的输出**逐字一致**：老版本存下来的
     * 设置 JSON 里没有这个键，反序列化会回退到它，所以升级后观感不变。
     */
    val notifySegments: List<NotifySegment> = DEFAULT_NOTIFY_SEGMENTS,

    /**
     * 天气功能开关。
     *
     * 打开后：按每节课的开始时间自动取一次天气，写进当次课的备注，课表格子的左下角
     * 也会带一个小图标；要定位，所以会申请定位权限 —— **只在你打开它之后才申请**。
     *
     * 关掉后：不申请、也不读取任何系统权限，格子上的天气图标不再显示，已经写进去的
     * 天气备注会被清掉；提醒里那个「天气」要素同时变成不可勾选。
     */
    val weatherEnabled: Boolean = false,

    /**
     * 温度的显示单位。
     *
     * 默认 [TemperatureUnit.CELSIUS]：老设置里没有这个键，反序列化会回退到默认值，
     * 升级后的显示与以前完全一致；数据源本身也只给摄氏度（见 [WeatherInfo]）。
     */
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,

    /**
     * 主页新增课程时，默认是不是「只加这一次」。
     *
     * true（默认）时新建课程只落在当前这一周那一天；用户想排整学期，
     * 在新建界面切到「永久」即可。
     */
    val newCourseOneShotByDefault: Boolean = true,

    // ---------- 上课自动静音 ----------

    /**
     * 是否开启「上课自动静音」。
     *
     * 开启后按**每个节次**上下课，而不是把连堂课当一个整段：这样节与节之间的
     * 课间天然恢复成上课前的模式（下课调回、再上课再静音）。
     */
    val autoMuteEnabled: Boolean = false,

    /** 静音方式：true = 振动，false = 完全静音。 */
    val autoMuteVibrate: Boolean = true,

    /**
     * 连堂课的课间是否保持静音。
     *
     * 默认关闭：按用户要求，课间（如 01:50-02:00）算「下课」，应恢复铃声；
     * 打开后相邻、间隔不大的两节课会被并成一段，课间不恢复。
     */
    val autoMuteKeepDuringBreak: Boolean = false,

    // ---------- 晚自习（属于某一张课表，实际存在课表行的 periodConfig 里） ----------

    /**
     * 晚自习规则：0 关闭 / 1 次日有课（含补课）才算 / 2 按星期固定。
     *
     * 取值对应 [com.nexoraism.coursemeow.domain.StudyHallRule]。
     * 这里的值只是**生效值**：真正落盘的是 `Timetable.periodConfig` 里的
     * `StudyHallConfig`（见 `EffectiveSettings.withTimetable`）。
     */
    val studyHallRule: Int = 0,

    /** 晚自习时段的起始节次。 */
    val studyHallStartPeriod: Int = 9,

    /** 晚自习时段的结束节次。 */
    val studyHallEndPeriod: Int = 11,

    /** 生效星期（1 = 周一 … 7 = 周日，默认周一~周五）。 */
    val studyHallDays: Set<Int> = setOf(1, 2, 3, 4, 5),

    /** 晚自习块的颜色（旧色板槽位，渲染时映射成当前主题的课程色）。 */
    val studyHallColor: Int = 0xFF6D28D9.toInt(),

    /** 生效起始周（含）。 */
    val studyHallStartWeek: Int = 1,

    /** 生效结束周（含）；0 = 不限，到学期末。 */
    val studyHallEndWeek: Int = 0,

    /** 「次日有课」里，补课日算不算有课。 */
    val studyHallCountMakeupDays: Boolean = true,

    /** 强制上课的日期（yyyy-MM-dd）。 */
    val studyHallForceOnDates: Set<String> = emptySet(),

    /** 强制不上的日期（yyyy-MM-dd）。 */
    val studyHallForceOffDates: Set<String> = emptySet(),

    // ---------- 写入系统日历 ----------

    /**
     * 是否把课程写进系统日历。
     *
     * 写入的是本机自建的「拾课喵」日历，日历本身只存在于这台设备上，
     * 换设备要重新写，所以和自动静音一样只存本机、不上云。
     */
    val calendarSyncEnabled: Boolean = false,

    /** 写进日历的事件提前多少分钟提醒（0 表示事件开始时提醒）。 */
    val calendarRemindMinutes: Int = 10,

    // ---------- UI 主题 ----------

    /**
     * 当前生效的 UI 主题 id。
     *
     * [DEFAULT_UI_THEME_ID] 表示内置 UI：颜色与圆角全部按应用自带的规则推导。
     */
    val uiThemeId: String = DEFAULT_UI_THEME_ID,

    /** 用户导入的 UI 主题（可以放多个，切换时用 [uiThemeId] 指定）。 */
    val uiThemes: List<UiTheme> = emptyList(),

    /**
     * 顶栏内容的高度（dp）。
     *
     * 48dp = 原始高度（44dp）的 109%，默认就用这个值；所有用到顶栏的页面共用它。
     */
    val appBarHeightDp: Int = 48,

    /**
     * 顶栏标题的字号（sp）。
     *
     * 默认 18sp：这是「拾课喵 · 四个字的课表名」能完整放下的最大一档；
     * 想要更大的标题可以在外观设置里往上调，标题过长时会自动省略。
     */
    val appBarTitleSp: Int = 18,

    /**
     * UI 开发模式：打开后长按界面上的文案可以直接改，改完关掉开关就自动存成一份 UI 文件。
     *
     * 这是个**临时的工作状态**，不是一份主题：改动都记在 [uiDevDraft] 里，
     * 退出时并进一套主题、写出文件，然后清空。
     */
    val uiDevMode: Boolean = false,

    /**
     * UI 开发模式里改过的文案（原文 → 新文案）。
     *
     * 重启也留着：设计到一半退出去接个电话，回来接着改。
     */
    val uiDevDraft: Map<String, String> = emptyMap(),

    /** 界面语言：`auto` 为跟随系统，其余取值见 `AppLanguage`。 */
    val language: String = "auto"
) {
    /** 当前日期应当使用的作息。 */
    fun seasonOn(date: LocalDate = LocalDate.now()): DstSeason =
        if (!dstEnabled) DstSeason.SUMMER
        else resolveSeason(date, dstSummerStart, dstWinterStart)

    /** 某个季节对应的作息时间。 */
    fun timesOf(season: DstSeason): Pair<List<String>, List<String>> =
        if (season == DstSeason.WINTER) {
            winterPeriodStartTimes to winterPeriodEndTimes
        } else {
            summerPeriodStartTimes to summerPeriodEndTimes
        }

    /**
     * 某栋教学楼实际使用的上下课时间（按当前季节取）。
     *
     * 没开启「按教学楼自定义节次时间」、或这栋楼没单独设置过，一律用默认作息；
     * 设置过的那几节用它的，其余节次仍回退到默认 —— 所以用户只填「第 3 节」也是合法的。
     * 冬令时没单独设置过时沿用这栋楼的夏令时时间（见 [BuildingPeriodTimes.timesFor]）。
     */
    fun timesForBuilding(building: String): Pair<List<String>, List<String>> {
        val fallback = periodStartTimes to periodEndTimes
        if (!buildingPeriodTimesEnabled) return fallback
        val custom = buildingPeriodTimes[building] ?: return fallback
        val (rawStarts, rawEnds) = custom.timesFor(seasonOn() == DstSeason.WINTER)
        val starts = periodStartTimes.mapIndexed { i, base ->
            rawStarts.getOrNull(i)?.takeIf { it.isNotBlank() } ?: base
        }
        val ends = periodEndTimes.mapIndexed { i, base ->
            rawEnds.getOrNull(i)?.takeIf { it.isNotBlank() } ?: base
        }
        return starts to ends
    }

    /**
     * 某类注释在「新建时」默认是不是跟随课程。
     *
     * 系统自动写入的类型（天气）恒为「临时」：它只属于某一天的那一节课，老设置里
     * 万一存过 `weather=true` 也在这里被挡住，不去读那份偏好。
     */
    fun notePermanentByDefault(kind: NoteKind): Boolean =
        if (kind.systemWritten) false else noteDefaultsPermanent[kind.id] ?: kind.permanentByDefault

    /** 上课提醒正文里要不要带这个要素。 */
    fun notifyShows(elementId: String): Boolean = elementId in notifyElementIds

    companion object {
        /** 提醒正文默认带的三要素，与引入注释之前的观感保持一致。 */
        val DEFAULT_NOTIFY_ELEMENTS = setOf("time", "location", "teacher")

        /**
         * 通知正文片段的默认顺序。
         *
         * 直接取 [NotifyVariableIds] 的全量顺序：变量都在列表里，但默认只有
         * [DEFAULT_NOTIFY_ELEMENTS] 里的三要素是打开的，所以实际输出与以前一致。
         */
        val DEFAULT_NOTIFY_SEGMENTS: List<NotifySegment> =
            NotifyVariableIds.map { NotifySegment.Variable(it) }

        // 默认 11 节课的上下课时间（江苏大学夏令时作息，4 月 7 日起执行）
        val defaultSummerStarts = listOf(
            "08:00", "08:55", "10:20", "11:15", "14:00",
            "14:55", "16:00", "16:55", "19:00", "19:55", "20:50"
        )
        val defaultSummerEnds = listOf(
            "08:45", "09:40", "11:05", "12:00", "14:45",
            "15:40", "16:45", "17:40", "19:45", "20:40", "21:35"
        )

        // 江苏大学冬令时作息（10 月 8 日起执行）：上午不变，下午与晚上整体提前 30 分钟
        val defaultWinterStarts = listOf(
            "08:00", "08:55", "10:20", "11:15", "13:30",
            "14:25", "15:30", "16:25", "18:30", "19:25", "20:20"
        )
        val defaultWinterEnds = listOf(
            "08:45", "09:40", "11:05", "12:00", "14:15",
            "15:10", "16:15", "17:10", "19:15", "20:10", "21:05"
        )

        /** 兼容旧调用：不开启夏令时切换时，默认作息即夏令时作息。 */
        val defaultPeriodStarts = defaultSummerStarts
        val defaultPeriodEnds = defaultSummerEnds
    }
}

/** 作息季节。 */
enum class DstSeason { SUMMER, WINTER }

/**
 * 把 [TimetableSettings.skipHolidays] 与 [TimetableSettings.dimHolidayCourses] 两个布尔
 * 拼成界面上那一档：总开关关掉即「关闭」，否则再看是不是「淡化」。
 */
fun TimetableSettings.holidayMode(): HolidayMode = when {
    !skipHolidays -> HolidayMode.OFF
    dimHolidayCourses -> HolidayMode.DIMMED
    else -> HolidayMode.HIDDEN
}

/** 季节名称（界面展示用）。 */
fun seasonLabel(season: DstSeason): String =
    if (season == DstSeason.WINTER) "冬令时" else "夏令时"

/**
 * 判断 [date] 落在哪个季节。
 *
 * [summerStart] / [winterStart] 为 "MM-dd"。当夏令时开始早于冬令时时
 * （北半球常见情况），区间 [summerStart, winterStart) 为夏令时，其余为冬令时；
 * 反之则相反。日期解析失败时回退为夏令时。
 */
fun resolveSeason(date: LocalDate, summerStart: String, winterStart: String): DstSeason {
    val summer = parseMonthDay(summerStart, date.year) ?: return DstSeason.SUMMER
    val winter = parseMonthDay(winterStart, date.year) ?: return DstSeason.SUMMER
    return if (summer <= winter) {
        if (!date.isBefore(summer) && date.isBefore(winter)) DstSeason.SUMMER else DstSeason.WINTER
    } else {
        if (!date.isBefore(summer) || date.isBefore(winter)) DstSeason.SUMMER else DstSeason.WINTER
    }
}

private fun parseMonthDay(text: String, year: Int): LocalDate? = runCatching {
    val parts = text.trim().split('-', '/', '.')
    if (parts.size < 2) return@runCatching null
    val month = parts[0].toInt()
    val day = parts[1].toInt()
    LocalDate.of(year, month, day)
}.getOrNull()

