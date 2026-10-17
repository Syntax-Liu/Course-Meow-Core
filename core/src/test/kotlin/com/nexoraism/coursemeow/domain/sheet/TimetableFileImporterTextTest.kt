package com.nexoraism.coursemeow.domain.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「文本类课表」分派的冒烟测试。
 *
 * 重点在编码：教务系统的页面常见 GBK / GB2312，导入器必须先能把它正确读成文本，
 * 后面那套解析才谈得上。这几条用例守住的就是「读得出」和「不误判」两件事。
 */
class TimetableFileImporterTextTest {

    private val html = """
        <html><body>
        <table id="kbtable">
          <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
          <tr>
            <td>第1-2节</td>
            <td>大学物理<br>李四<br>1-16周<br>三江楼203</td>
            <td></td>
            <td></td>
          </tr>
        </table>
        </body></html>
    """.trimIndent()

    @Test
    fun `gbk encoded html is decoded then routed to zf online`() {
        val bytes = html.toByteArray(charset("GBK"))
        val outcome = TimetableFileImporter.import(bytes, "课表.html")
        assertTrue(outcome is TimetableFileImporter.Outcome.Ok)
        val ok = outcome as TimetableFileImporter.Outcome.Ok
        assertEquals("zf-online", ok.route)
        assertEquals(1, ok.result.courses.size)
        assertEquals("大学物理", ok.result.courses.first().name)
    }

    @Test
    fun `utf8 json is routed to zf online`() {
        val json = """{"kbList":[{"kcmc":"大学英语","xqj":"1","jcs":"1-2","zcd":"1-16周","cdmc":"三江楼0302"}]}"""
        val outcome = TimetableFileImporter.import(json.toByteArray(Charsets.UTF_8), "kb.json")
        assertTrue(outcome is TimetableFileImporter.Outcome.Ok)
        val ok = outcome as TimetableFileImporter.Outcome.Ok
        assertEquals("zf-online", ok.route)
        assertEquals(1, ok.result.courses.size)
    }

    @Test
    fun `binary payload is not mistaken for text`() {
        // OLE2（.xls）的文件头，后面跟一段 NUL —— 必须被当成二进制，不能进文本分支
        val bytes = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()) + ByteArray(64)
        val outcome = TimetableFileImporter.import(bytes, "x.xls")
        val route = (outcome as? TimetableFileImporter.Outcome.Ok)?.route
        assertNotEquals("zf-online", route)
    }
}
