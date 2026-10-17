package com.nexoraism.coursemeow.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一张课表自己的**作息配置**：上下课时间（夏令时 / 冬令时两套）与作息切换开关。
 *
 * 为什么要单独抽出来：这几项原先存在全应用的设置里，等于所有课表共用一套作息 ——
 * 改一张课表的节次时间，另一张也跟着变。现在它跟着课表走（存在 `timetables.periodConfig`
 * 这一列），每张课表各改各的互不影响。
 *
 * 与学期起止日期一样采用「没设过就回退」的语义：老数据里这一列是空的，含义是
 * 「这张课表还没设过作息」，此时读取全局那份默认值 —— 升级上来的用户看到的作息不会变。
 */
@Immutable
data class PeriodConfig(
    val dstEnabled: Boolean = false,
    val dstSummerStart: String = "04-07",
    val dstWinterStart: String = "10-08",
    val summerStarts: List<String> = TimetableSettings.defaultSummerStarts,
    val summerEnds: List<String> = TimetableSettings.defaultSummerEnds,
    val winterStarts: List<String> = TimetableSettings.defaultWinterStarts,
    val winterEnds: List<String> = TimetableSettings.defaultWinterEnds,
    /** 是否开启「按教学楼自定义节次时间」。 */
    val buildingTimesEnabled: Boolean = false,
    /** 教学楼 -> 这栋楼自己的上下课时间（只记用户改过的那些楼）。 */
    val buildingTimes: Map<String, BuildingPeriodTimes> = emptyMap(),
    /**
     * 这张课表自己的晚自习规则。
     *
     * 晚自习是**课表的一部分**（同一所学校不同学期、不同校区的规矩可能不一样），
     * 所以跟作息一起存在课表行里，而不是应用级设置。
     *
     * 为 null 表示「这张课表还没设过」：老数据（4.16.x 及更早）的 periodConfig 里
     * 没有这一块，此时回退到全局那份旧值，升级后用户看到的规则不会变。
     */
    val studyHall: StudyHallConfig? = null
) {
    companion object {
        /** 从一份（已合并的）设置里取出作息这一块，用于「改完再写回课表」。 */
        fun of(s: TimetableSettings): PeriodConfig = PeriodConfig(
            dstEnabled = s.dstEnabled,
            dstSummerStart = s.dstSummerStart,
            dstWinterStart = s.dstWinterStart,
            summerStarts = s.summerPeriodStartTimes,
            summerEnds = s.summerPeriodEndTimes,
            winterStarts = s.winterPeriodStartTimes,
            winterEnds = s.winterPeriodEndTimes,
            buildingTimesEnabled = s.buildingPeriodTimesEnabled,
            buildingTimes = s.buildingPeriodTimes,
            studyHall = StudyHallConfig(
                rule = s.studyHallRule,
                startPeriod = s.studyHallStartPeriod,
                endPeriod = s.studyHallEndPeriod,
                days = s.studyHallDays,
                color = s.studyHallColor,
                startWeek = s.studyHallStartWeek,
                endWeek = s.studyHallEndWeek,
                countMakeupDays = s.studyHallCountMakeupDays,
                forceOn = s.studyHallForceOnDates,
                forceOff = s.studyHallForceOffDates
            )
        )
    }
}

/**
 * 晚自习规则（存在 [PeriodConfig] 里，随课表走）。
 *
 * 判定顺序见 `StudyHall.synthesize`：生效周次 → 强制停 → 强制开 → 星期 → 今晚有课 → 次日有课。
 */
@Immutable
data class StudyHallConfig(
    /** 0 关闭 / 1 次日有课才上 / 2 按星期固定（天数由 [days] 决定）。 */
    val rule: Int = 0,
    val startPeriod: Int = 9,
    val endPeriod: Int = 11,
    /** 生效星期（1 = 周一 … 7 = 周日）。 */
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    val color: Int = 0xFF7E22CE.toInt(),
    /** 生效起始周（含）。 */
    val startWeek: Int = 1,
    /** 生效结束周（含）；0 = 不限，到学期末。 */
    val endWeek: Int = 0,
    /** 「次日有课」里，补课日算不算有课。 */
    val countMakeupDays: Boolean = true,
    /** 强制上课的日期（yyyy-MM-dd），校运会补自习之类。 */
    val forceOn: Set<String> = emptySet(),
    /** 强制不上的日期（yyyy-MM-dd），临时取消。 */
    val forceOff: Set<String> = emptySet()
)

/**
 * 一栋教学楼自己的一套上下课时间。
 *
 * 夏令时与冬令时各存一套：和统一作息一样按日期切换，两个功能不冲突。
 * 只记用户真正改过的节次：某个位置是空串（或没有这一项），那一节就沿用默认作息。
 * 这样学校作息表里「只有三江楼第 3 节晚 10 分钟」这种情况，只填一节也表达得出来；
 * 以后默认作息加减节次，这里也不会因为长度对不上而错位。
 *
 * 老数据只有 [starts] / [ends]（那时还没有分季节）：冬令时为空时按夏令时那份用。
 */
@Immutable
data class BuildingPeriodTimes(
    val starts: List<String> = emptyList(),
    val ends: List<String> = emptyList(),
    /** 冬令时时间；为空表示沿用 [starts] / [ends]。 */
    val winterStarts: List<String> = emptyList(),
    val winterEnds: List<String> = emptyList()
) {
    /** 取某一季的时间；冬令时没单独设过就退回夏令时那份。 */
    fun timesFor(winter: Boolean): Pair<List<String>, List<String>> =
        if (winter) {
            winterStarts.ifEmpty { starts } to winterEnds.ifEmpty { ends }
        } else {
            starts to ends
        }
}

/**
 * [PeriodConfig] 的存储编码。
 *
 * 存成一列 JSON 而不是拆成七八列：作息是一整块、只会整体读写，拆成列之后
 * 迁移要动整张表，以后想加一项（比如每节的小课间）还得再迁移一次。
 * 键名取短，是因为这一列在每一行里都要写一遍。
 */
object PeriodConfigCodec {

    fun encode(config: PeriodConfig): String {
        val o = JSONObject()
            .put("d", config.dstEnabled)
            .put("ss", config.dstSummerStart)
            .put("ws", config.dstWinterStart)
            .put("s", JSONArray(config.summerStarts))
            .put("se", JSONArray(config.summerEnds))
            .put("w", JSONArray(config.winterStarts))
            .put("we", JSONArray(config.winterEnds))
            .put("bte", config.buildingTimesEnabled)
            .put("bt", encodeBuildingTimes(config.buildingTimes))
        // 晚自习：没设过就不写这个键，读的时候才知道要回退到全局旧值
        config.studyHall?.let { o.put("sh", encodeStudyHall(it)) }
        return o.toString()
    }

    /** 解不出内容（空串、半截 JSON、老格式）一律返回 null，让调用方回退到全局值。 */
    fun decode(text: String): PeriodConfig? {
        if (text.isBlank()) return null
        return runCatching {
            val o = JSONObject(text)
            PeriodConfig(
                dstEnabled = o.optBoolean("d", false),
                dstSummerStart = o.optString("ss").ifBlank { "04-07" },
                dstWinterStart = o.optString("ws").ifBlank { "10-08" },
                summerStarts = readTimes(o.optJSONArray("s"), TimetableSettings.defaultSummerStarts),
                summerEnds = readTimes(o.optJSONArray("se"), TimetableSettings.defaultSummerEnds),
                winterStarts = readTimes(o.optJSONArray("w"), TimetableSettings.defaultWinterStarts),
                winterEnds = readTimes(o.optJSONArray("we"), TimetableSettings.defaultWinterEnds),
                buildingTimesEnabled = o.optBoolean("bte", false),
                buildingTimes = decodeBuildingTimes(o.optJSONObject("bt")),
                studyHall = o.optJSONObject("sh")?.let { decodeStudyHall(it) }
            )
        }.getOrNull()
    }

    private fun encodeStudyHall(sh: StudyHallConfig): JSONObject = JSONObject()
        .put("r", sh.rule)
        .put("sp", sh.startPeriod)
        .put("ep", sh.endPeriod)
        .put("d", JSONArray(sh.days.sorted()))
        .put("c", sh.color)
        .put("sw", sh.startWeek)
        .put("ew", sh.endWeek)
        .put("mk", sh.countMakeupDays)
        .put("fo", JSONArray(sh.forceOn.sorted()))
        .put("ff", JSONArray(sh.forceOff.sorted()))

    private fun decodeStudyHall(o: JSONObject): StudyHallConfig {
        val def = StudyHallConfig()
        val days = readDays(o.optJSONArray("d"), def.days)
        return StudyHallConfig(
            rule = o.optInt("r", def.rule),
            startPeriod = o.optInt("sp", def.startPeriod),
            endPeriod = o.optInt("ep", def.endPeriod),
            days = days,
            color = o.optInt("c", def.color),
            startWeek = o.optInt("sw", def.startWeek),
            endWeek = o.optInt("ew", def.endWeek),
            countMakeupDays = o.optBoolean("mk", def.countMakeupDays),
            forceOn = readDateSet(o.optJSONArray("fo")),
            forceOff = readDateSet(o.optJSONArray("ff"))
        )
    }

    /** 星期集合：空数组当作「没设过」，回默认值（全不选会让晚自习永远不出现，不是好默认）。 */
    private fun readDays(array: JSONArray?, fallback: Set<Int>): Set<Int> {
        if (array == null || array.length() == 0) return fallback
        val out = LinkedHashSet<Int>(array.length())
        for (i in 0 until array.length()) {
            val d = array.optInt(i, 0)
            if (d in 1..7) out.add(d)
        }
        return if (out.isEmpty()) fallback else out
    }

    private fun readDateSet(array: JSONArray?): Set<String> {
        if (array == null || array.length() == 0) return emptySet()
        val out = LinkedHashSet<String>(array.length())
        for (i in 0 until array.length()) {
            val v = array.optString(i).trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }

    private fun readTimes(array: JSONArray?, fallback: List<String>): List<String> {
        if (array == null || array.length() == 0) return fallback
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val v = array.optString(i).trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return if (out.isEmpty()) fallback else out
    }

    private fun encodeBuildingTimes(map: Map<String, BuildingPeriodTimes>): JSONObject {
        val o = JSONObject()
        map.forEach { (building, times) ->
            if (building.isBlank()) return@forEach
            o.put(
                building,
                JSONObject()
                    .put("s", JSONArray(times.starts))
                    .put("e", JSONArray(times.ends))
                    .put("ws", JSONArray(times.winterStarts))
                    .put("we", JSONArray(times.winterEnds))
            )
        }
        return o
    }

    private fun decodeBuildingTimes(obj: JSONObject?): Map<String, BuildingPeriodTimes> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, BuildingPeriodTimes>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            if (name.isBlank()) continue
            val item = obj.optJSONObject(name) ?: continue
            val starts = readTimeList(item.optJSONArray("s"))
            val ends = readTimeList(item.optJSONArray("e"))
            val winterStarts = readTimeList(item.optJSONArray("ws"))
            val winterEnds = readTimeList(item.optJSONArray("we"))
            if (starts.isEmpty() && ends.isEmpty() &&
                winterStarts.isEmpty() && winterEnds.isEmpty()
            ) {
                continue
            }
            out[name] = BuildingPeriodTimes(starts, ends, winterStarts, winterEnds)
        }
        return out
    }

    /**
     * 按教学楼的作息保留空位：空串代表「这一节沿用默认作息」，是有意义的值，
     * 不能像 [readTimes] 那样过滤掉，否则位置会整体前移、节次对不上。
     */
    private fun readTimeList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) out.add(array.optString(i).trim())
        return out
    }
}
