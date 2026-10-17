package com.nexoraism.coursemeow.domain.sheet

import com.nexoraism.coursemeow.data.parse.PdfTextExtractor
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * 「从课表文件导入」的唯一入口：**看文件本身**决定走哪条解析路，然后分派出去。
 *
 * 为什么看字节而不是扩展名：用户从教务系统／聊天软件／文件管理器里选来的文件，
 * 名字常常没有扩展名，或者扩展名是错的（`.xls` 其实是 xlsx、`.pdf` 其实是张图）。
 * 按内容判断才不会「明明是对的文件，却被一句格式不对挡在门外」。
 *
 * 认得出的三类：
 *   - `%PDF` 魔数 → 课表 PDF（班级 / 个人两种版面都能认），走本地规则解析（零依赖）；
 *   - 文本类（HTML / JSON）→ 教务系统的课表页面源码或课表接口返回，走 [ZfOnlineTimetableParser]；
 *   - 其余按 `.xls`（OLE2 复合文档）处理，走写死的学校适配器（学校导出的班级课表就是这种）。
 * 认不出的（xlsx 的 zip、其它二进制）明确说清楚「是哪种、该怎么办」，不猜。
 *
 * 目前不支持 csv / 纯文本：客户端没有对应的适配器（那条路只在云端），
 * 所以这里也不假装能读 —— 宁可给一句实话。
 */
object TimetableFileImporter {

    /** 单份个人课表 PDF 的上限。真实的个人课表 PDF 只有几十 KB，400 KB 是防呆。 */
    private const val MAX_PDF_BYTES = 400 * 1024

    /** 学校导出的 .xls 一般七十几 KB；16 MB 只是防呆，避免把超大文件读进内存。 */
    private const val MAX_SHEET_BYTES = 16 * 1024 * 1024

    /** 教务系统页面源码 / 接口返回的大小上限。真实的课表页面几百 KB 就到头了，8 MB 是防呆。 */
    private const val MAX_TEXT_BYTES = 8 * 1024 * 1024

    /**
     * 解析结果。
     *
     * [diagnostic] 是给**本地 debug 日志**看的一行诊断（文件名 / 命中的解析器 / 课程数，
     * PDF 失败时还带上原始文字），不上报、不进界面。
     */
    sealed interface Outcome {
        val diagnostic: String?

        data class Ok(
            val result: SheetImportResult,
            val route: String,
            /** 这份文件是不是**班级课表**（.xls 一定是；PDF 按 PDF 自身判断）。融合导入靠它决定谁当底。 */
            val isClassSheet: Boolean,
            override val diagnostic: String? = null
        ) : Outcome

        data class Bad(
            val message: String,
            override val diagnostic: String? = null
        ) : Outcome
    }

    fun import(bytes: ByteArray, fileName: String): Outcome {
        if (bytes.isEmpty()) return Outcome.Bad("这个文件是空的")
        if (isPdf(bytes)) return importPdf(bytes, fileName)
        if (bytes.size > MAX_SHEET_BYTES) return Outcome.Bad("文件太大了，读不动")
        if (isZip(bytes)) return Outcome.Bad("这是 .xlsx（新版 Excel），本机适配器只认 .xls")
        // 文本类：教务系统课表页面的源码，或课表接口返回的 JSON。
        // 放在 .xls 之前判断：xls 是二进制（含 NUL 字节），读不出文本，不会误判。
        if (bytes.size <= MAX_TEXT_BYTES) {
            val text = decodeText(bytes)
            if (text != null && looksLikeTimetableText(text)) return importOnline(text)
        }
        return importSheet(bytes)
    }

    /** 教务系统的课表数据长这样：接口返回的 JSON（`{` / `[`）或页面源码（`<`）。 */
    private fun looksLikeTimetableText(text: String): Boolean {
        val head = text.trimStart()
        return head.startsWith("{") || head.startsWith("[") || head.startsWith("<")
    }

    // ------------------------------------------------------------------ PDF

    private fun importPdf(bytes: ByteArray, fileName: String): Outcome {
        if (bytes.size > MAX_PDF_BYTES) {
            return Outcome.Bad("这份 PDF 太大了（超过 400 KB），换一份小一点的")
        }
        val parsed = try {
            PdfTimetableParser.parse(bytes, fileName)
        } catch (e: PdfTextExtractor.NotPdf) {
            return Outcome.Bad("这个文件不是有效的 PDF，或者已经损坏")
        } catch (e: PdfTextExtractor.NoTextLayer) {
            return Outcome.Bad("这份 PDF 是扫描件，没有可识别的文字（读不出来）")
        } catch (e: PdfTextExtractor.UnsupportedFonts) {
            return Outcome.Bad("这份 PDF 用的不是本机能解码的字体（可能嵌入了字体，或没有文字层）")
        } catch (e: PdfTextExtractor.Broken) {
            return Outcome.Bad("这个 PDF 打不开，或者文件已经损坏")
        } catch (e: PdfTimetableParser.UnknownLayout) {
            return Outcome.Bad("这一份解析不出，可能是新版面", e.textLog)
        } catch (e: PdfTimetableParser.NoCourses) {
            return Outcome.Bad("没能从这份 PDF 里认出课程，确认一下是不是教务系统导出的课表", e.textLog)
        } catch (e: Exception) {
            return Outcome.Bad("这份 PDF 解不出来，换一个文件试试")
        }
        val isClass = parsed.kind == PdfTimetableParser.Kind.CLASS
        return Outcome.Ok(
            result = parsed.result,
            route = "pdf:${parsed.kind.routeId}:${parsed.layout}",
            isClassSheet = isClass,
            // 成功时诊断日志只记一行摘要；原始文字日志留到失败（认不出）时才写，
            // 免得每次导入都往日志里灌一大段课表原文。
            diagnostic = if (isClass) {
                "班级课表 PDF · 版面 ${parsed.layout} · ${parsed.result.courses.size} 门课程"
            } else {
                "个人课表 PDF · 版面 ${parsed.layout} · ${parsed.result.courses.size} 门课程"
            }
        )
    }

    // ------------------------------------------------------------------ 学校表格

    private fun importSheet(bytes: ByteArray): Outcome {
        val grid = try {
            XlsReader.read(bytes)
        } catch (e: XlsReader.NotAnXlsFile) {
            return Outcome.Bad("这不是 .xls 文件，或者文件已经损坏")
        } catch (e: Exception) {
            return Outcome.Bad("这个文件读不出来，换一个试试")
        }

        val adapter = SchoolSheetAdapters.detect(grid)
            ?: return Outcome.Bad("还不认这所学校的课表格式")

        val result = try {
            adapter.parse(grid)
        } catch (e: XlsReader.UnsupportedXls) {
            return Outcome.Bad("这份表里没找到课程")
        } catch (e: Exception) {
            return Outcome.Bad("这份表解不出来，换一个文件试试")
        }

        return Outcome.Ok(
            result = result,
            route = "sheet:${adapter.id}",
            // 学校导出的 .xls 就是班级课表（个人课表是另一个系统给的 PDF）。
            isClassSheet = true,
            diagnostic = "课表解析：${adapter.id} · ${result.courses.size} 门课程 · 信息不全 ${result.incomplete.size} 门"
        )
    }

    // ------------------------------------------------------------------ 教务系统页面 / 接口

    private fun importOnline(text: String): Outcome {
        val parsed = ZfOnlineTimetableParser.parse(text)
            ?: return Outcome.Bad("没能从这份数据里认出课程，确认一下是不是教务系统的课表页面或接口返回")
        return Outcome.Ok(
            result = parsed,
            route = "zf-online",
            // 这是学生自己的在线课表，不是班级课表
            isClassSheet = false,
            diagnostic = "正方在线课表 · ${parsed.courses.size} 门课程"
        )
    }

    // ------------------------------------------------------------------ 文本解码

    /**
     * 把一段字节还原成文本；不是文本（二进制）时返回 null。
     *
     * 教务系统的页面常见 GBK / GB2312（老系统尤其），所以不能只按 UTF-8 读：
     * 先严格按 UTF-8 解码，遇到非法字节序列再退回 GBK。
     * 二进制文件（如 .xls 的 OLE2）会先被 NUL 字节筛掉，不会走到解码。
     */
    private fun decodeText(bytes: ByteArray): String? {
        if (bytes.take(512).any { it == 0.toByte() }) return null
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            runCatching { String(bytes, charset("GBK")) }.getOrNull()
        }
    }

    // ------------------------------------------------------------------ 魔数

    private fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()

    /** xlsx / 通用 zip 的头两字节是 `PK`；xls 是 OLE2（`D0 CF 11 E0`），两者靠这个分清。 */
    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
}
