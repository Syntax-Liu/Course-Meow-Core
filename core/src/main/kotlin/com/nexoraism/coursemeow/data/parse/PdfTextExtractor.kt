package com.nexoraism.coursemeow.data.parse

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * 从一份 PDF 里抽出「文字 + 位置」，纯 Kotlin、零依赖。
 *
 * 为什么自己写而不引库：APK 体积敏感（整包才 9.8MB），成熟的 PDF 库动辄涨 8-10MB。
 * 而教务系统导出的个人课表 PDF 是**固定的一套版面**（iText 生成、字体固定），
 * 只需要它用到的那一小撮特性：
 *   - 对象 + 一个 FlateDecode 内容流（`java.util.zip.Inflater` 直接解压）；
 *   - 字体是 `/Subtype/Type0` + `/Encoding/UniGB-UCS2-H` —— 内容流里的字符串
 *     就是 **UCS-2（UTF-16BE）**，两位一个字，所以**不需要解析字体、不需要 CMap**；
 *   - 文本定位只用 `BT/ET`、`Tf`、`Tm`、`Td`、`TD`、`T*`、`TL`、`Tj`、`TJ`、`'`、`"`。
 *
 * 读不出来就明确失败，**绝不猜**：字体不是 UniGB-UCS2-H（嵌入了字体、或根本没有文字层）、
 * 内容流压不开 —— 都抛异常让调用方给用户一句能看懂的话，而不是返回一份空课表。
 *
 * 坐标系不再做 /Rotate 变换：这两类导出文件在内容流里已经把文字画在最终阅读方向上
 * （起始有一条 `cm` 矩阵承担了旋转），所以直接用文本矩阵的平移量做「行 / 列」聚类即可。
 */
object PdfTextExtractor {

    /** 不是 PDF（缺少 %PDF 魔数）。 */
    class NotPdf(message: String) : Exception(message)

    /** 是 PDF，但结构坏掉了 / 用了本读取器不支持的压缩方式。 */
    class Broken(message: String) : Exception(message)

    /** 没有可读的文字层（很可能是扫描件）。 */
    class NoTextLayer(message: String) : Exception(message)

    /** 字体不是本机能解码的 UniGB-UCS2-H（例如嵌入了字体或用了 Identity-H）。 */
    class UnsupportedFonts(message: String) : Exception(message)

    /** 一段文字及其在页面坐标系里的起点（文本矩阵的平移量）。 */
    data class Item(val page: Int, val x: Float, val y: Float, val text: String)

    /** 抽出一份 PDF 里的全部文字，按页顺序返回。失败抛上面那些异常。 */
    fun extract(bytes: ByteArray): List<Item> {
        if (bytes.size < 5 ||
            bytes[0] != '%'.code.toByte() || bytes[1] != 'P'.code.toByte() ||
            bytes[2] != 'D'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            throw NotPdf("不是 PDF 文件")
        }

        // ISO-8859-1 是「一字节一字符」，所以字符串下标正好等于字节下标 —— 结构扫描用文本，
        // 取二进制流时再用同样的下标去切原始字节数组。
        val text = String(bytes, Charsets.ISO_8859_1)
        val objects = parseObjects(text, bytes)

        val pageIds = pageOrder(objects)
        if (pageIds.isEmpty()) throw Broken("找不到页面对象")

        // 校验字体编码：只有 UniGB-UCS2-H 才能按 UTF-16BE 直接解码。
        val referencedFonts = pageIds
            .flatMap { fontRefsOf(objects[it]?.dict.orEmpty()) }
            .distinct()
        val anyFont = objects.values.any { it.dict.contains("/Type/Font") }
        if (!anyFont) throw NoTextLayer("这份 PDF 里没有可读的文字层")
        val topFonts = if (referencedFonts.isNotEmpty()) {
            referencedFonts.mapNotNull { objects[it]?.dict }
        } else {
            objects.values.filter { it.dict.contains("/Subtype/Type0") }.map { it.dict }
        }
        if (topFonts.isEmpty() || topFonts.any { !isUniGb(it) }) {
            throw UnsupportedFonts("这份 PDF 用的不是本机能解码的字体")
        }

        val items = ArrayList<Item>()
        pageIds.forEachIndexed { pageIndex, id ->
            val page = objects[id] ?: return@forEachIndexed
            contentRefsOf(page.dict).forEach { contentId ->
                val streamObj = objects[contentId] ?: return@forEach
                val raw = streamObj.stream ?: return@forEach
                val data = when {
                    streamObj.dict.contains("FlateDecode") -> inflate(raw)
                    // 其它过滤器（LZW / ASCII85 / 图片）本读取器不认，明确失败而不是硬读
                    streamObj.dict.contains("/Filter") -> throw Broken("内容流用了不支持的压缩方式")
                    else -> raw
                }
                parseContent(data).forEach { cmd -> // 逐条文本操作符还原文字与位置
                    val bytesOfText = cmd.textBytes() ?: return@forEach
                    val decoded = decodeUcs2(bytesOfText)
                    if (decoded.isEmpty()) return@forEach
                    items.add(Item(pageIndex, cmd.x.toFloat(), cmd.y.toFloat(), decoded))
                }
            }
        }

        if (items.isEmpty()) throw NoTextLayer("这份 PDF 里没有读出任何文字")
        return items
    }

    // ------------------------------------------------------------------ 对象扫描

    private class PdfObj(val dict: String, val stream: ByteArray?)

    /** 顺序扫描 `N G obj ... endobj`，遇到流就按 `stream/endstream` 跳过，避免把二进制当结构。 */
    private fun parseObjects(text: String, bytes: ByteArray): Map<Int, PdfObj> {
        val out = HashMap<Int, PdfObj>()
        // 逐个对象独立处理：先找 obj 关键字，再在它的区间里找 stream / endobj。
        var i = 0
        val matcher = Regex("(\\d+)\\s+(\\d+)\\s+obj")
        while (i < text.length) {
            val m = matcher.find(text, i) ?: break
            val id = m.groupValues[1].toIntOrNull()
            val bodyStart = m.range.last + 1
            val endObjIdx = text.indexOf("endobj", bodyStart).let { if (it < 0) text.length else it }
            // 字典里不会出现 "stream" 这个词，所以第一个 "stream" 就是本对象的流关键字；
            // 若它出现在 endobj 之后，说明本对象没有流。
            val streamKw = text.indexOf("stream", bodyStart)
            if (id != null && streamKw in (bodyStart + 1) until endObjIdx) {
                var dataStart = streamKw + "stream".length
                if (dataStart < text.length && text[dataStart] == '\r') dataStart++
                if (dataStart < text.length && text[dataStart] == '\n') dataStart++
                val dataEnd = text.indexOf("endstream", dataStart).let { if (it < 0) endObjIdx else it }
                val dict = text.substring(bodyStart, streamKw)
                val streamEnd = minOf(dataEnd, bytes.size)
                out[id] = PdfObj(dict, if (dataStart in 0 until streamEnd) bytes.copyOfRange(dataStart, streamEnd) else ByteArray(0))
                val resume = text.indexOf("endobj", dataEnd)
                i = if (resume < 0) endObjIdx + 6 else resume + 6
            } else {
                if (id != null) out[id] = PdfObj(text.substring(bodyStart, endObjIdx), null)
                i = endObjIdx + 6
            }
        }
        return out
    }

    /** 页面顺序：优先按 /Pages 的 /Kids 顺序，取不到再退回按对象号。 */
    private fun pageOrder(objects: Map<Int, PdfObj>): List<Int> {
        val pages = objects.entries.firstOrNull { it.value.dict.contains("/Type/Pages") }
        if (pages != null) {
            val kids = Regex("/Kids\\s*\\[([^\\]]*)]").find(pages.value.dict)?.groupValues?.get(1)
            if (!kids.isNullOrBlank()) {
                val ids = Regex("(\\d+)\\s+\\d+\\s+R").findAll(kids).map { it.groupValues[1].toInt() }.toList()
                if (ids.isNotEmpty()) return ids
            }
        }
        return objects.entries
            .filter { pageType(it.value.dict) }
            .map { it.key }
            .sorted()
    }

    private fun pageType(dict: String): Boolean =
        Regex("/Type\\s*/Page(?!s)").containsMatchIn(dict)

    private fun fontRefsOf(dict: String): List<Int> {
        val fonts = Regex("/Font\\s*<<([^>]*)>>").find(dict)?.groupValues?.get(1) ?: return emptyList()
        return Regex("(\\d+)\\s+\\d+\\s+R").findAll(fonts).map { it.groupValues[1].toInt() }.toList()
    }

    private fun contentRefsOf(dict: String): List<Int> {
        Regex("/Contents\\s*\\[([^\\]]*)]").find(dict)?.let { array ->
            return Regex("(\\d+)\\s+\\d+\\s+R").findAll(array.groupValues[1]).map { it.groupValues[1].toInt() }.toList()
        }
        val single = Regex("/Contents\\s+(\\d+)\\s+\\d+\\s+R").find(dict) ?: return emptyList()
        return listOf(single.groupValues[1].toInt())
    }

    /** 字体是不是 UniGB-UCS2-H（编码名或 BaseFont 名里带 UniGB 都算）。 */
    private fun isUniGb(dict: String): Boolean {
        val enc = Regex("/Encoding\\s*/([A-Za-z0-9#\\-+_.]+)").find(dict)?.groupValues?.get(1)
        val base = Regex("/BaseFont\\s*/([A-Za-z0-9#\\-+_.]+)").find(dict)?.groupValues?.get(1)
        return (enc?.contains("UniGB", ignoreCase = true) == true) ||
            (base?.contains("UniGB", ignoreCase = true) == true)
    }

    private fun inflate(raw: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(raw)
            val out = ByteArrayOutputStream(raw.size * 4 + 64)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val written = try {
                    inflater.inflate(buffer)
                } catch (e: java.util.zip.DataFormatException) {
                    break
                }
                if (written == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buffer, 0, written)
                }
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun decodeUcs2(b: ByteArray): String {
        val len = b.size - (b.size % 2)
        return if (len <= 0) "" else String(b, 0, len, Charsets.UTF_16BE)
    }

    // ------------------------------------------------------------------ 内容流解析

    private sealed interface Value {
        class Num(val v: Double) : Value
        class Name(val s: String) : Value
        class Str(val bytes: ByteArray) : Value
        class Arr(val items: List<Value>) : Value
        class Dict : Value
    }

    /** 一条已解析的命令：操作符 + 它前面的操作数，并带上发出文字时的文本矩阵平移量。 */
    private class Cmd(val op: String, val args: List<Value>, val x: Double, val y: Double) {

        fun nums(): List<Double> = args.filterIsInstance<Value.Num>().map { it.v }

        /** 这条命令写出的文字字节（Tj / ' / " / TJ 合并后）；不是文字命令则返回 null。 */
        fun textBytes(): ByteArray? = when (op) {
            "Tj", "'", "\"" -> (args.lastOrNull() as? Value.Str)?.bytes
            "TJ" -> {
                val arr = (args.lastOrNull() as? Value.Arr)?.items ?: return null
                val out = ByteArrayOutputStream()
                arr.filterIsInstance<Value.Str>().forEach { out.write(it.bytes) }
                out.toByteArray()
            }
            else -> null
        }
    }

    /**
     * 把内容流解析成命令序列：一边做词法切分（名字 / 字符串 / 数组 / 数字 / 操作符），
     * 一边维护文本矩阵，把每条 Tj/TJ 的位置算出来。
     */
    private fun parseContent(content: ByteArray): List<Cmd> {
        val raw = ContentLexer(content).parse()
        val out = ArrayList<Cmd>(raw.size)
        val tm = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        val tlm = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        var leading = 0.0

        fun translate(tx: Double, ty: Double) {
            // tlm = translate(tx,ty) × tlm：新平移量由旧行矩阵与位移合成
            val e = tlm[0] * tx + tlm[2] * ty + tlm[4]
            val f = tlm[1] * tx + tlm[3] * ty + tlm[5]
            tlm[4] = e
            tlm[5] = f
            System.arraycopy(tlm, 0, tm, 0, 6)
        }

        // 取这一条命令里的数字参数：词法器把数字都收进 args（统一是 Value.Num），
        // 这里只挑 Num 出来取它的值 —— 名字 / 字符串 / 数组 / 字典都不是数字，要跳过。
        // 注意 raw 里是词法器产出的 RawCmd（还没有 x/y），所以参数类型是 RawCmd 而非 Cmd。
        fun numsOf(c: RawCmd): List<Double> = c.args.filterIsInstance<Value.Num>().map { it.v }

        raw.forEach { c ->
            when (c.op) {
                "BT" -> {
                    for (k in 0..5) {
                        tm[k] = if (k == 0 || k == 3) 1.0 else 0.0
                        tlm[k] = tm[k]
                    }
                }
                "Tm" -> {
                    val n = numsOf(c)
                    if (n.size >= 6) {
                        val src = n.subList(n.size - 6, n.size)
                        for (k in 0..5) { tm[k] = src[k]; tlm[k] = src[k] }
                    }
                }
                "Td" -> {
                    val n = numsOf(c)
                    if (n.size >= 2) translate(n[n.size - 2], n[n.size - 1])
                }
                "TD" -> {
                    val n = numsOf(c)
                    if (n.size >= 2) { leading = -n[n.size - 1]; translate(n[n.size - 2], n[n.size - 1]) }
                }
                "T*" -> translate(0.0, -leading)
                "TL" -> numsOf(c).lastOrNull()?.let { leading = it }
                "Tj", "TJ" -> out.add(Cmd(c.op, c.args, tm[4], tm[5]))
                "'" -> { translate(0.0, -leading); out.add(Cmd(c.op, c.args, tm[4], tm[5])) }
                "\"" -> { translate(0.0, -leading); out.add(Cmd(c.op, c.args, tm[4], tm[5])) }
            }
        }
        return out
    }

    private fun setIdentity(m: DoubleArray) {
        m[0] = 1.0; m[1] = 0.0; m[2] = 0.0; m[3] = 1.0; m[4] = 0.0; m[5] = 0.0
    }

    /** 内容流词法：只做「值 / 操作符」的切分，不做语义。 */
    private class ContentLexer(private val b: ByteArray) {

        private var i = 0
        private val n = b.size

        fun parse(): List<RawCmd> {
            val cmds = ArrayList<RawCmd>()
            val args = ArrayList<Value>()
            while (true) {
                skipWs()
                if (i >= n) break
                val c = at(i)
                when {
                    c == 0x2F -> args.add(Value.Name(readName()))
                    c == 0x28 -> args.add(Value.Str(readLiteral()))
                    c == 0x3C -> {
                        if (at(i + 1) == 0x3C) args.add(readDict()) else args.add(Value.Str(readHex()))
                    }
                    c == 0x5B -> args.add(Value.Arr(readArray()))
                    c == 0x5D || c == 0x7B || c == 0x7D || c == 0x3E -> i++
                    else -> {
                        val tok = readBare()
                        if (tok.isEmpty()) { i++; continue }
                        val num = tok.toDoubleOrNull()
                        if (num != null) args.add(Value.Num(num))
                        else {
                            cmds.add(RawCmd(tok, ArrayList(args)))
                            args.clear()
                        }
                    }
                }
            }
            return cmds
        }

        private fun at(k: Int): Int = if (k in 0 until n) b[k].toInt() and 0xFF else -1

        private fun isWs(c: Int): Boolean =
            c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32

        private fun isDelim(c: Int): Boolean =
            isWs(c) || c == 0x28 || c == 0x29 || c == 0x3C || c == 0x3E || c == 0x5B ||
                c == 0x5D || c == 0x7B || c == 0x7D || c == 0x2F || c == 0x25

        private fun skipWs() {
            while (i < n) {
                val c = at(i)
                if (isWs(c)) i++
                else if (c == 0x25) { // '%' 注释到行尾
                    while (i < n && at(i) != 10 && at(i) != 13) i++
                } else break
            }
        }

        private fun readName(): String {
            i++ // 跳过 '/'
            val start = i
            while (i < n && !isDelim(at(i))) i++
            return String(b, start, i - start, Charsets.ISO_8859_1)
        }

        private fun readBare(): String {
            val start = i
            while (i < n && !isDelim(at(i))) i++
            return String(b, start, i - start, Charsets.ISO_8859_1)
        }

        /** 字面字符串 `(...)`：处理 `\` 转义与括号嵌套，返回原始字节（已解转义）。 */
        private fun readLiteral(): ByteArray {
            i++ // '('
            val out = ByteArrayOutputStream()
            var depth = 1
            while (i < n && depth > 0) {
                val c = at(i)
                when {
                    c == 0x5C -> { // '\'
                        i++
                        if (i >= n) break
                        when (val e = at(i)) {
                            0x6E -> { out.write(0x0A); i++ }
                            0x72 -> { out.write(0x0D); i++ }
                            0x74 -> { out.write(0x09); i++ }
                            0x62 -> { out.write(0x08); i++ }
                            0x66 -> { out.write(0x0C); i++ }
                            0x28, 0x29, 0x5C -> { out.write(e); i++ }
                            0x0A, 0x0D -> i++ // 行续接
                            in 0x30..0x37 -> { // 八进制 \ddd
                                var v = 0
                                var k = 0
                                while (k < 3 && i < n && at(i) in 0x30..0x37) { v = v * 8 + (at(i) - 0x30); i++; k++ }
                                out.write(v and 0xFF)
                            }
                            else -> { out.write(e); i++ }
                        }
                    }
                    c == 0x28 -> { depth++; out.write(c); i++ }
                    c == 0x29 -> { depth--; if (depth > 0) out.write(c); i++ }
                    else -> { out.write(c); i++ }
                }
            }
            return out.toByteArray()
        }

        /** 十六进制字符串 `<...>`。 */
        private fun readHex(): ByteArray {
            i++ // '<'
            val out = ByteArrayOutputStream()
            var high = -1
            while (i < n && at(i) != 0x3E) {
                val d = hexDigit(at(i))
                if (d >= 0) {
                    if (high < 0) high = d else { out.write(high * 16 + d); high = -1 }
                }
                i++
            }
            if (i < n) i++ // '>'
            if (high >= 0) out.write(high * 16)
            return out.toByteArray()
        }

        private fun hexDigit(c: Int): Int = when (c) {
            in 0x30..0x39 -> c - 0x30
            in 0x41..0x46 -> c - 0x41 + 10
            in 0x61..0x66 -> c - 0x61 + 10
            else -> -1
        }

        private fun readArray(): List<Value> {
            i++ // '['
            val items = ArrayList<Value>()
            while (i < n) {
                skipWs()
                if (i >= n) break
                val c = at(i)
                when {
                    c == 0x5D -> { i++; break }
                    c == 0x2F -> items.add(Value.Name(readName()))
                    c == 0x28 -> items.add(Value.Str(readLiteral()))
                    c == 0x3C -> if (at(i + 1) == 0x3C) items.add(readDict()) else items.add(Value.Str(readHex()))
                    c == 0x5B -> items.add(Value.Arr(readArray()))
                    else -> {
                        val tok = readBare()
                        if (tok.isEmpty()) { i++; continue }
                        tok.toDoubleOrNull()?.let { items.add(Value.Num(it)) }
                    }
                }
            }
            return items
        }

        private fun readDict(): Value {
            i += 2 // '<<'
            var depth = 1
            while (i < n && depth > 0) {
                val c = at(i)
                when {
                    c == 0x3C && at(i + 1) == 0x3C -> { depth++; i += 2 }
                    c == 0x3E && at(i + 1) == 0x3E -> { depth--; i += 2 }
                    c == 0x28 -> readLiteral()
                    else -> i++
                }
            }
            return Value.Dict()
        }
    }

    /** 词法结果：操作符名 + 操作数（位置在第二遍遍历时算）。 */
    private class RawCmd(val op: String, val args: List<Value>)
}
