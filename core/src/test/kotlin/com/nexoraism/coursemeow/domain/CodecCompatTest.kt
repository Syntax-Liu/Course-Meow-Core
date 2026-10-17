package com.nexoraism.coursemeow.domain

import com.nexoraism.coursemeow.data.Course
import kotlin.io.encoding.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 新旧编解码实现的互解一致性测试。
 *
 * 目的：把 core 模块从 JVM 特有实现（java.util.Base64 / java.util.zip.GZIP）
 * 换成 KMP 兼容实现后，确保分享口令与云端备份格式零差异。
 */
class CodecCompatTest {

    // ---------- Base64 ----------

    private val javaB64Encode = java.util.Base64.getUrlEncoder()
    private val javaB64Decode = java.util.Base64.getUrlDecoder()

    // kotlin.io.encoding.Base64.UrlSafe（KMP 标准库自带）
    private val kotlinB64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT)

    @Test fun `base64 - java and kotlin encode same bytes identically`() {
        for (input in sampleBytes()) {
            val javaResult = javaB64Encode.encodeToString(input)
            val kotlinResult = kotlinB64.encode(input)
            assertEquals("input=${input.toHex()}", javaResult, kotlinResult)
        }
    }

    @Test fun `base64 - kotlin decodes java output`() {
        for (input in sampleBytes()) {
            val encoded = javaB64Encode.encodeToString(input)
            val decoded = kotlinB64.decode(encoded)
            assertArrayEquals("input=${input.toHex()}", input, decoded)
        }
    }

    @Test fun `base64 - java decodes kotlin output`() {
        for (input in sampleBytes()) {
            val encoded = kotlinB64.encode(input)
            val decoded = javaB64Decode.decode(encoded)
            assertArrayEquals("input=${input.toHex()}", input, decoded)
        }
    }

    @Test fun `base64 - decode with whitespace tolerated before decode`() {
        val input = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = javaB64Encode.encodeToString(input)
        val withWhitespace = encoded.chunked(2).joinToString("\n") + "  "
        val cleaned = withWhitespace.filterNot { it.isWhitespace() }
        val decoded = kotlinB64.decode(cleaned)
        assertArrayEquals(input, decoded)
    }

    // ---------- GZIP ----------

    private fun javaGzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun javaGunzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(data)).use { input ->
            val buf = ByteArray(4096)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                out.write(buf, 0, read)
            }
        }
        return out.toByteArray()
    }

    @Test fun `gzip - java roundtrip`() {
        for (input in sampleBytes()) {
            val compressed = javaGzip(input)
            val decompressed = javaGunzip(compressed)
            assertArrayEquals("input=${input.toHex()}", input, decompressed)
        }
    }

    @Test fun `gzip - output has GZIP magic bytes`() {
        val compressed = javaGzip("hello".toByteArray())
        assertEquals(0x1F.toByte(), compressed[0])
        assertEquals(0x8B.toByte(), compressed[1])
    }

    // ---------- CourseCodec 端到端 ----------

    private fun makeCourse(name: String, day: Int, start: Int, end: Int): Course = Course(
        name = name, teacher = "", building = "", room = "",
        dayOfWeek = day, startPeriod = start, endPeriod = end,
        startWeek = 1, endWeek = 16
    )

    @Test fun `courseCodec - encode then decode roundtrip`() {
        val courses = listOf(
            makeCourse("高等数学", 1, 1, 2),
            makeCourse("大学英语", 3, 3, 4).copy(note = "带耳机")
        )
        val encoded = CourseCodec.encode(courses, "")
        val decoded = CourseCodec.decode(encoded)
        assertEquals(courses.size, decoded.size)
        assertEquals(courses[0].name, decoded[0].name)
        assertEquals(courses[1].note, decoded[1].note)
    }

    @Test fun `courseCodec - CT4 format includes prefs`() {
        val courses = listOf(makeCourse("物理", 2, 1, 2))
        val prefs = """{"semesterStartDate":1726041600000}"""
        val encoded = CourseCodec.encode(courses, prefs)
        val (decodedCourses, decodedPrefs) = CourseCodec.decodeShare(encoded)
        assertEquals(1, decodedCourses.size)
        assertEquals(prefs, decodedPrefs)
    }

    @Test fun `courseCodec - CT3 and CT4 roundtrip produce same courses`() {
        val courses = listOf(
            makeCourse("化学", 4, 5, 6),
            makeCourse("体育", 5, 7, 8).copy(
                teacher = "王教练", building = "体育馆", room = "篮球馆"
            )
        )
        val ct3 = CourseCodec.encode(courses, "")
        val ct4 = CourseCodec.encode(courses, "{}")
        val ct3Decoded = CourseCodec.decode(ct3)
        val ct4Decoded = CourseCodec.decode(ct4)
        assertEquals(ct3Decoded.size, ct4Decoded.size)
        assertEquals(ct3Decoded[0].name, ct4Decoded[0].name)
        assertEquals(ct3Decoded[1].teacher, ct4Decoded[1].teacher)
    }

    @Test fun `courseCodec - new info fields survive roundtrip`() {
        val courses = listOf(
            makeCourse("高数", 1, 1, 2).copy(
                credits = "4.0", totalHours = "80", nature = "必修", assessment = "考试"
            )
        )
        val decoded = CourseCodec.decode(CourseCodec.encode(courses, ""))
        assertEquals("4.0", decoded[0].credits)
        assertEquals("80", decoded[0].totalHours)
        assertEquals("必修", decoded[0].nature)
        assertEquals("考试", decoded[0].assessment)
    }

    @Test fun `courseCodec - legacy code without new keys decodes them blank`() {
        // 旧口令（CT2 明文 JSON，没有 cr/th/cn/as 这些键）必须照旧能解，缺的键给空串
        val json = """[{"n":"旧课","d":1,"sp":1,"ep":2,"sw":1,"ew":16}]"""
        val code = "CT2|" + javaB64Encode.encodeToString(json.toByteArray(Charsets.UTF_8))
        val decoded = CourseCodec.decode(code)
        assertEquals(1, decoded.size)
        assertEquals("旧课", decoded[0].name)
        assertEquals("", decoded[0].credits)
        assertEquals("", decoded[0].totalHours)
        assertEquals("", decoded[0].nature)
        assertEquals("", decoded[0].assessment)
    }

    @Test fun `courseCodec - timetable fields survive roundtrip`() {
        val courses = listOf(makeCourse("物理", 2, 1, 2))
        val fields = CourseCodec.TimetableFields(
            semesterStart = 1726041600000L,
            semesterEnd = 1733760000000L,
            periodConfig = """{"periodStart":["08:00","08:55"]}"""
        )
        val encoded = CourseCodec.encode(courses, "", fields)
        val decoded = CourseCodec.decodeShare(encoded)
        assertEquals(1, decoded.courses.size)
        assertEquals(1726041600000L, decoded.semesterStart)
        assertEquals(1733760000000L, decoded.semesterEnd)
        assertEquals("""{"periodStart":["08:00","08:55"]}""", decoded.periodConfig)
    }

    @Test fun `courseCodec - timetable fields absent decode to zero and blank`() {
        // 老 CT4 口令只有 prefs、没有 ts/te/pc → 三键按 0 / 空串解出
        val courses = listOf(makeCourse("化学", 4, 5, 6))
        val encoded = CourseCodec.encode(courses, """{"semesterStartDate":1}""")
        val decoded = CourseCodec.decodeShare(encoded)
        assertEquals(0L, decoded.semesterStart)
        assertEquals(0L, decoded.semesterEnd)
        assertEquals("", decoded.periodConfig)
    }

    @Test fun `courseCodec - CT3 without fields stays CT3`() {
        // 既没有 prefs 也没有 fields 时仍走 CT3（数组），旧解析器照旧能解
        val courses = listOf(makeCourse("体育", 5, 7, 8))
        val encoded = CourseCodec.encode(courses)
        assertEquals(true, encoded.startsWith("CT3|"))
        assertEquals(courses.size, CourseCodec.decode(encoded).size)
    }

    // ---------- helpers ----------

    private fun sampleBytes(): List<ByteArray> = listOf(
        ByteArray(0),
        byteArrayOf(0),
        byteArrayOf(1),
        byteArrayOf(0x00, 0xFF.toByte()),
        byteArrayOf(1, 2, 3),
        byteArrayOf(1, 2, 3, 4),
        byteArrayOf(1, 2, 3, 4, 5),
        "hello world".toByteArray(),
        "CT4|abcdefghij".toByteArray(),
        ByteArray(100) { it.toByte() },
        ByteArray(1000) { (it * 7).toByte() },
        byteArrayOf(0x1F, 0x8B.toByte(), 0x00, 0x01)
    )

    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
