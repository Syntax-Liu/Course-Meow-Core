package com.nexoraism.coursemeow.domain

import com.nexoraism.coursemeow.data.Course
import com.nexoraism.coursemeow.data.TimeMode
import com.nexoraism.coursemeow.data.WeekRule
import kotlin.io.encoding.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 课程表与字符串之间的编解码。
 *
 * 用在「分享课表」这一条路上：把课程编成一段字符串存到云端，换回一个 4 位口令；
 * 别人用口令取回后在这里解回来。旧版本曾把这段字符串直接当「长暗号」手抄、也直接塞进二维码，
 * 2.11.0 起只走云端这一条 —— 见 `ExportDialog` 与 `ImportScreen`。
 *
 * 版本：CT1 / CT2 是早期格式（仍能解 —— 别人手里可能还留着旧口令，不该白给）；
 * CT3 把 JSON 做 GZIP 压缩后再 Base64，长度压到原来的四分之一左右；
 * CT4 在 CT3 之上多带一段「跟随课表的设置」（作息时间 / 学期起止 / 节假日与补课日）：
 * 课表本来就离不开作息，别人拿到这张课表时也该按同一套作息去看。
 */
object CourseCodec {

    private const val PREFIX_V1 = "CT1|"
    private const val PREFIX_V2 = "CT2|"
    private const val PREFIX_V3 = "CT3|"
    private const val PREFIX_V4 = "CT4|"

    /**
     * Base64（URL_SAFE + 带填充 + 不换行）。
     *
     * 与原来的 `android.util.Base64.NO_WRAP or URL_SAFE` 以及 `java.util.Base64.getUrlEncoder()`
     * 逐字节等价 —— 字母表 `- _`、补 `=`、不换行，三者输出必须完全一致。
     *
     * 用 `kotlin.io.encoding.Base64.UrlSafe` 是因为它在 KMP commonMain 里也能用，
     * 这是将来换 KMP 时零成本的一步。
     */
    private val B64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT)

    /**
     * 解码一段 Base64。先去掉空白字符：旧实现（安卓的 Base64）会忽略口令里的换行与空格
     * （粘贴时经常带上），必须先清掉 —— 否则老口令会解不开。
     */
    private fun decodeBase64(text: String): ByteArray =
        B64.decode(text.filterNot { it.isWhitespace() })

    /** 旧色板槽位 0（深蓝）；渲染时会映射成当前主题的课程色。 */
    private const val DEFAULT_COLOR = 0xFF1E3A8A.toInt()

    /**
     * 课表行自身的学期信息（开学 / 结束 / 作息），与 [Decoded.prefs] 那份整表设置相互补充。
     *
     * 这些字段存在 `Timetable` 行上，不在整表 DataStore 里，因此过去的口令只带了
     * 全局 DataStore 设置、漏掉了它们 —— 例如「开学时间」。现在一并打进 CT4。
     */
    data class TimetableFields(
        val semesterStart: Long = 0L,
        val semesterEnd: Long = 0L,
        val periodConfig: String = ""
    )

    /** 一次解码的结果：[prefs] 是随码一起带来的「跟随课表」设置，可能为空串。 */
    data class Decoded(
        val courses: List<Course>,
        val prefs: String = "",
        /** 课表行的开学时间（毫秒时间戳），0 表示口令里没带。 */
        val semesterStart: Long = 0L,
        /** 课表行的结束时间（毫秒时间戳），0 表示口令里没带。 */
        val semesterEnd: Long = 0L,
        /** 课表行的作息配置 JSON，空串表示口令里没带。 */
        val periodConfig: String = ""
    )

    /**
     * 把课程列表编码成一段便于交换的字符串（存到云端换一个口令用）。
     *
     * [timetablePrefs] 非空时用 CT4 把它一起带上；[timetableFields] 非空时也走 CT4，
     * 把课表行的学期起止与作息一并带上。两者都空时退回 CT3（只有课程），
     * 留给没有设置可带的场合。
     */
    fun encode(
        courses: List<Course>,
        timetablePrefs: String = "",
        timetableFields: TimetableFields? = null
    ): String {
        val arr = JSONArray()
        for (c in courses) {
            val o = JSONObject()
                .put("n", c.name)
                .put("d", c.dayOfWeek)
                .put("sp", c.startPeriod)
                .put("ep", c.endPeriod)
                .put("sw", c.startWeek)
                .put("ew", c.endWeek)
            // 只写入非默认值，减小体积；教师统一按顿号连接后再写，兼容旧的手打分隔符
            if (c.teacherText.isNotBlank()) o.put("t", c.teacherText)
            if (c.building.isNotBlank()) o.put("b", c.building)
            if (c.room.isNotBlank()) o.put("r", c.room)
            if (c.weekType != WeekRule.EVERY) o.put("wt", c.weekType)
            if (c.customWeeks.isNotBlank()) o.put("cw", c.customWeeks)
            if (c.color != DEFAULT_COLOR) o.put("c", c.color)
            if (c.note.isNotBlank()) o.put("nt", c.note)
            // 课表文件带来的四项信息：有值才写，老口令里没有这几项，读的时候缺键按空串处理
            if (c.credits.isNotBlank()) o.put("cr", c.credits)
            if (c.totalHours.isNotBlank()) o.put("th", c.totalHours)
            if (c.nature.isNotBlank()) o.put("cn", c.nature)
            if (c.assessment.isNotBlank()) o.put("as", c.assessment)
            if (c.timeMode != TimeMode.FIXED) {
                o.put("tm", c.timeMode)
                o.put("fs", c.freeStartTime)
                o.put("fe", c.freeEndTime)
            }
            arr.put(o)
        }
        // 带设置（整表设置 或 课表行学期信息）任一存在时都走 CT4 对象；
        // 对象里只写有值的键，旧解析器忽略未知键，因此不必升 CT 版本号。
        val fields = timetableFields
        val useObject = timetablePrefs.isNotBlank() || fields != null
        val json = if (useObject) {
            val root = JSONObject().put("courses", arr)
            if (timetablePrefs.isNotBlank()) root.put("prefs", timetablePrefs)
            if (fields != null) {
                if (fields.semesterStart != 0L) root.put("ts", fields.semesterStart)
                if (fields.semesterEnd != 0L) root.put("te", fields.semesterEnd)
                if (fields.periodConfig.isNotBlank()) root.put("pc", fields.periodConfig)
            }
            root.toString()
        } else {
            arr.toString()
        }
        val packed = gzip(json.toByteArray(Charsets.UTF_8))
        return (if (useObject) PREFIX_V4 else PREFIX_V3) + B64.encode(packed)
    }

    /** 解码「口令」为课程列表（不关心随码带来的设置时用这个）。失败返回空列表。 */
    fun decode(code: String): List<Course> = decodeShare(code).courses

    /** 解码「口令」：课程 + 随码带来的「跟随课表」设置。失败返回空结果。 */
    fun decodeShare(code: String): Decoded = runCatching {
        val body = code.trim()
        val json = when {
            body.startsWith(PREFIX_V4) -> {
                val raw = decodeBase64(body.removePrefix(PREFIX_V4))
                String(gunzip(raw), Charsets.UTF_8)
            }

            body.startsWith(PREFIX_V3) -> {
                val raw = decodeBase64(body.removePrefix(PREFIX_V3))
                String(gunzip(raw), Charsets.UTF_8)
            }

            else -> {
                val legacy = body.removePrefix(PREFIX_V2).removePrefix(PREFIX_V1)
                String(decodeBase64(legacy), Charsets.UTF_8)
            }
        }
        val text = json.trim()
        // CT4 的正文是个对象（课程 + 设置），CT1~CT3 直接就是课程数组
        if (text.startsWith("{")) {
            val root = JSONObject(text)
            Decoded(
                courses = parseCourses(root.optJSONArray("courses") ?: JSONArray()),
                prefs = root.optString("prefs"),
                // 旧口令没有 ts/te/pc → optLong 为 0、optString 为空串，照旧能解
                semesterStart = root.optLong("ts", 0L),
                semesterEnd = root.optLong("te", 0L),
                periodConfig = root.optString("pc")
            )
        } else {
            Decoded(parseCourses(JSONArray(text)))
        }
    }.getOrDefault(Decoded(emptyList()))

    private fun parseCourses(arr: JSONArray): List<Course> {
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Course(
                name = pick(o, "n", "name"),
                teacher = pick(o, "t", "teacher"),
                building = pick(o, "b", "building"),
                room = pick(o, "r", "room"),
                dayOfWeek = o.optInt("d", o.optInt("day", 1)),
                startPeriod = o.optInt("sp", 1),
                endPeriod = o.optInt("ep", 1),
                startWeek = o.optInt("sw", 1),
                endWeek = o.optInt("ew", 16),
                weekType = o.optInt("wt", WeekRule.EVERY),
                customWeeks = pick(o, "cw", "customWeeks"),
                color = o.optInt("c", o.optInt("color", DEFAULT_COLOR)),
                note = pick(o, "nt", "note"),
                timeMode = o.optInt("tm", TimeMode.FIXED),
                freeStartTime = pick(o, "fs", "freeStartTime"),
                freeEndTime = pick(o, "fe", "freeEndTime"),
                // 旧口令没有这四个键 → pick 返回空串，照旧能解
                credits = pick(o, "cr", "credits"),
                totalHours = pick(o, "th", "totalHours"),
                nature = pick(o, "cn", "nature"),
                assessment = pick(o, "as", "assessment")
            )
        }
    }

    private fun pick(o: JSONObject, shortKey: String, longKey: String): String {
        val v = o.optString(shortKey)
        if (v.isNotBlank()) return v
        return o.optString(longKey)
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }
}
