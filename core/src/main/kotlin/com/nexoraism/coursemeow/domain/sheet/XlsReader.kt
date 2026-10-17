package com.nexoraism.coursemeow.domain.sheet

import java.nio.charset.Charset

/**
 * 一张表格：按行列的文本 + 合并单元格范围。
 *
 * 只保留导入课表需要的东西 —— 值、位置、合并。样式、公式、日期系统一概不管：
 * 教务系统导出的课表里，我们只读得到的文字。
 */
class SheetGrid(
    /** 行 → 列 → 文本（空单元格为 ""）。行列都会补到最大范围，不会出现 null 洞。 */
    val rows: List<List<String>>,
    /** 合并范围，元素为 [起始行, 起始列, 结束行, 结束列]，均为 0 基。 */
    val merges: List<IntArray>
) {
    val rowCount: Int get() = rows.size

    fun cell(row: Int, col: Int): String =
        rows.getOrNull(row)?.getOrNull(col) ?: ""

    /**
     * 合并单元格的值只在左上角那个格子里存着，其余是空的。
     * 读「第 1 行的班级名」这种跨列合并的标题时必须看这个，否则会读成一串空。
     */
    fun mergedValue(row: Int, col: Int): String {
        val direct = cell(row, col)
        if (direct.isNotBlank()) return direct
        merges.forEach { m ->
            if (row in m[0]..m[2] && col in m[1]..m[3]) return cell(m[0], m[1])
        }
        return ""
    }
}

/**
 * 只读的 `.xls`（BIFF8）读取器。
 *
 * 为什么要自己写一个：教务系统导出的是老式的二进制 xls，安卓上没有现成可用的轻量库 ——
 * POI 太大、jxl 早就停更、把 SheetJS 塞进 WebView 又要多背一个浏览器引擎。
 * 而这份格式实际用到的记录极少（实测 76 个文件：单元格全是 LABELSST，没有 NUMBER /
 * RK / MULRK / FORMULA），所以只实现需要的那一子集：零依赖、包体积不涨、纯 Kotlin 最快。
 *
 * 结构跨两层：
 *   1. OLE2 复合文档容器 —— 找到里面的 `Workbook` 流；
 *   2. BIFF8 记录流 —— 按「2 字节类型 + 2 字节长度」逐条读。
 *
 * 解析不出来就抛 [NotAnXlsFile] / [UnsupportedXls]，**绝不猜**：导入课表这件事上，
 * 悄悄读错比读不出来糟糕得多。调用方接住异常后提示用户「另存为 .xlsx 或 .csv 再导入」。
 */
object XlsReader {

    class NotAnXlsFile(message: String) : Exception(message)
    class UnsupportedXls(message: String) : Exception(message)

    // 链表结束标记与空闲扇区。写成负数是因为 0xFFFFFFFE 在 Kotlin 里会推成 Long，
    // 而扇区号、FAT 项全都是 Int，混着比会编译不过。
    private const val ENDOFCHAIN = -2 // 0xFFFFFFFE
    private const val FREESECT = -1 // 0xFFFFFFFF

    private const val REC_BOF = 0x0809
    private const val REC_EOF = 0x000A
    private const val REC_BOUNDSHEET = 0x0085
    private const val REC_SST = 0x00FC
    private const val REC_CONTINUE = 0x003C
    private const val REC_LABELSST = 0x00FD
    private const val REC_LABEL = 0x0204
    private const val REC_NUMBER = 0x0203
    private const val REC_RK = 0x027E
    private const val REC_MULRK = 0x00BD
    private const val REC_FORMULA = 0x0006
    private const val REC_STRING = 0x0207
    private const val REC_MERGEDCELLS = 0x00E5

    private const val BIFF8 = 0x0600

    /** 压缩字符串用的单字节表：BIFF8 规定是 Latin-1。 */
    private val LATIN1: Charset = Charsets.ISO_8859_1

    fun read(bytes: ByteArray): SheetGrid {
        val workbook = readWorkbookStream(bytes)
        return readBiff(workbook)
    }

    // ------------------------------------------------------------ OLE2 容器

    /** 从复合文档里取出 `Workbook` 流的内容。 */
    private fun readWorkbookStream(file: ByteArray): ByteArray {
        if (file.size < 512) throw NotAnXlsFile("文件太小（${file.size} 字节）")
        // 魔数 D0 CF 11 E0 A1 B1 1A E1
        val magic = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
        )
        for (i in magic.indices) {
            if (file[i] != magic[i]) {
                throw NotAnXlsFile("不是 .xls（OLE2）文件；若是 .xlsx，请走 xlsx 那条路")
            }
        }

        val sectorSize = 1 shl u16(file, 30)
        val miniSectorSize = 1 shl u16(file, 32)
        val miniCutoff = u32(file, 56)
        val fatCount = u32(file, 44)
        val dirStart = u32(file, 48)
        val miniFatStart = u32(file, 60)
        val miniFatCount = u32(file, 64)
        var difStart = u32(file, 68)
        val difCount = u32(file, 72)
        if (sectorSize < 128 || sectorSize > 65536) throw UnsupportedXls("扇区大小异常：$sectorSize")

        // --- FAT：先取头里的 109 项，再顺着 DIFAT 链继续取 ---
        val fatSectors = ArrayList<Int>()
        for (i in 0 until 109) {
            if (fatSectors.size >= fatCount) break
            val s = u32(file, 76 + i * 4)
            if (s != FREESECT) fatSectors.add(s)
        }
        var guard = 0
        while (difStart != ENDOFCHAIN && difStart != FREESECT && fatSectors.size < fatCount && guard++ < 4096) {
            val base = (difStart + 1) * sectorSize
            val per = sectorSize / 4 - 1
            for (k in 0 until per) {
                if (fatSectors.size >= fatCount) break
                val s = i32(file, base + k * 4)
                if (s != FREESECT) fatSectors.add(s)
            }
            difStart = i32(file, base + per * 4)
        }
        val fat = IntArray(fatSectors.size * (sectorSize / 4))
        fatSectors.forEachIndexed { idx, sector ->
            val base = (sector + 1) * sectorSize
            for (i in 0 until sectorSize / 4) {
                fat[idx * (sectorSize / 4) + i] = i32(file, base + i * 4)
            }
        }

        fun chain(start: Int): IntArray {
            val out = ArrayList<Int>()
            var cur = start
            var g = 0
            while (cur != ENDOFCHAIN && cur != FREESECT && cur in fat.indices && g++ < 1 shl 20) {
                out.add(cur)
                cur = fat[cur]
            }
            return out.toIntArray()
        }

        fun readChain(start: Int): ByteArray {
            val sectors = chain(start)
            val out = ByteArray(sectors.size * sectorSize)
            sectors.forEachIndexed { i, s ->
                val base = (s + 1) * sectorSize
                System.arraycopy(file, base, out, i * sectorSize, minOf(sectorSize, file.size - base))
            }
            return out
        }

        // --- 目录：找 Workbook 流与根条目 ---
        val dir = readChain(dirStart)
        var root: Entry? = null
        val entries = ArrayList<Entry>()
        var i = 0
        while (i + 128 <= dir.size) {
            val base = i
            val nameLen = u16(dir, base + 64)
            if (nameLen >= 2) {
                val name = String(dir, base, nameLen - 2, Charsets.UTF_16LE)
                val type = dir[base + 66].toInt()
                val start = i32(dir, base + 116)
                val size = i32(dir, base + 120)
                val e = Entry(name, type, start, size)
                entries.add(e)
                if (type == 5) root = e
            }
            i += 128
        }
        val entry = entries.firstOrNull { it.type == 2 && (it.name == "Workbook" || it.name == "Book") }
            ?: throw UnsupportedXls("复合文档里没有 Workbook 流")

        // 小流走 miniFAT，大流走 FAT（阈值由文件头指定）
        if (entry.size >= miniCutoff || root == null) return readChain(entry.start).copyOf(entry.size)

        val miniFatRaw = readChain(miniFatStart)
        val miniFat = IntArray(miniFatCount)
        for (k in 0 until miniFatCount) {
            miniFat[k] = if ((k + 1) * 4 <= miniFatRaw.size) i32(miniFatRaw, k * 4) else ENDOFCHAIN
        }
        val miniStream = readChain(root.start)
        val out = java.io.ByteArrayOutputStream(entry.size)
        var cur = entry.start
        var g = 0
        while (cur != ENDOFCHAIN && cur != FREESECT && cur in miniFat.indices && g++ < 1 shl 20) {
            val from = cur * miniSectorSize
            out.write(miniStream, from, minOf(miniSectorSize, miniStream.size - from))
            cur = miniFat[cur]
        }
        return out.toByteArray().copyOf(entry.size)
    }

    private class Entry(val name: String, val type: Int, val start: Int, val size: Int)

    // ------------------------------------------------------------ BIFF8 记录

    private fun readBiff(workbook: ByteArray): SheetGrid {
        // 第一遍：SST（字符串表）可能跨记录，先把 SST 与它后面的 CONTINUE 拼起来解析。
        // 一条 Workbook 流里通常只有一张工作表（教务系统导出就是），但照样按
        // 「找到第一张工作表的 BOF 之后再读单元格」来做，遇到多张也能取到第一张。
        val cells = HashMap<Long, String>()
        val merges = ArrayList<IntArray>()
        var maxRow = -1
        var maxCol = -1
        var sst: List<String> = emptyList()

        var pos = 0
        var inFirstSheet = false
        var sheetSeen = false
        var pendingStringRecord = false

        while (pos + 4 <= workbook.size) {
            val type = u16(workbook, pos)
            val len = u16(workbook, pos + 2)
            val body = pos + 4
            if (body + len > workbook.size) break

            when (type) {
                REC_BOF -> {
                    // 第一个 BOF 是工作簿全局流，之后的第一个 BOF 才是第一张工作表
                    val dt = if (len >= 8) u16(workbook, body + 2) else -1
                    if (dt == 0x0010 && !sheetSeen) {
                        inFirstSheet = true
                        sheetSeen = true
                    }
                }

                REC_EOF -> {
                    if (inFirstSheet) return build(cells, merges, maxRow, maxCol, workbook, sheetSeen)
                }

                REC_SST -> {
                    val chunks = ArrayList<Pair<Int, Int>>()
                    chunks.add(body to len)
                    var next = pos + 4 + len
                    while (next + 4 <= workbook.size && u16(workbook, next) == REC_CONTINUE) {
                        val cl = u16(workbook, next + 2)
                        chunks.add((next + 4) to cl)
                        next += 4 + cl
                    }
                    sst = readSst(workbook, chunks)
                }

                REC_LABELSST -> {
                    if (inFirstSheet && len >= 10) {
                        val row = u16(workbook, body)
                        val col = u16(workbook, body + 2)
                        val idx = i32(workbook, body + 6)
                        val text = sst.getOrNull(idx) ?: ""
                        put(cells, row, col, text)
                        maxRow = maxOf(maxRow, row); maxCol = maxOf(maxCol, col)
                    }
                }

                REC_LABEL -> {
                    if (inFirstSheet && len >= 8) {
                        val row = u16(workbook, body)
                        val col = u16(workbook, body + 2)
                        val text = readUnicodeString(workbook, body + 6, body + len)
                        put(cells, row, col, text)
                        maxRow = maxOf(maxRow, row); maxCol = maxOf(maxCol, col)
                    }
                }

                REC_NUMBER -> {
                    if (inFirstSheet && len >= 14) {
                        val row = u16(workbook, body)
                        val col = u16(workbook, body + 2)
                        val d = java.lang.Double.longBitsToDouble(i64(workbook, body + 6))
                        put(cells, row, col, formatNumber(d))
                        maxRow = maxOf(maxRow, row); maxCol = maxOf(maxCol, col)
                    }
                }

                REC_RK -> {
                    if (inFirstSheet && len >= 10) {
                        val row = u16(workbook, body)
                        val col = u16(workbook, body + 2)
                        put(cells, row, col, formatNumber(rkValue(i32(workbook, body + 6))))
                        maxRow = maxOf(maxRow, row); maxCol = maxOf(maxCol, col)
                    }
                }

                REC_MULRK -> {
                    if (inFirstSheet && len >= 6) {
                        val row = u16(workbook, body)
                        val firstCol = u16(workbook, body + 2)
                        // 结构：行(2) 首列(2) + n×(XF(2) RK(4)) + 末列(2)
                        val n = (len - 6) / 6
                        for (k in 0 until n) {
                            val col = firstCol + k
                            val rk = i32(workbook, body + 4 + k * 6 + 2)
                            put(cells, row, col, formatNumber(rkValue(rk)))
                            maxRow = maxOf(maxRow, row); maxCol = maxOf(maxCol, col)
                        }
                    }
                }

                REC_FORMULA -> {
                    if (inFirstSheet && len >= 14) {
                        val row = u16(workbook, body)
                        val col = u16(workbook, body + 2)
                        val raw = i64(workbook, body + 6)
                        // 结果是不是字符串，看 6..7 字节；是则紧跟一条 STRING 记录
                        val isString = (raw and 0xFF) == 0xFF.toLong()
                        if (isString) {
                            pendingStringRecord = true
                            stringFormulaCell = row to col
                        } else {
                            put(cells, row, col, formatNumber(java.lang.Double.longBitsToDouble(raw)))
                            maxRow = maxOf(maxRow, row); maxCol = maxOf(maxCol, col)
                        }
                    }
                }

                REC_STRING -> {
                    if (inFirstSheet && pendingStringRecord) {
                        val text = readUnicodeString(workbook, body, body + len)
                        stringFormulaCell?.let { (r, c) ->
                            put(cells, r, c, text)
                            maxRow = maxOf(maxRow, r); maxCol = maxOf(maxCol, c)
                        }
                        pendingStringRecord = false
                        stringFormulaCell = null
                    }
                }

                REC_MERGEDCELLS -> {
                    if (inFirstSheet && len >= 2) {
                        val count = u16(workbook, body)
                        for (k in 0 until count) {
                            val off = body + 2 + k * 8
                            if (off + 8 > body + len) break
                            val r1 = u16(workbook, off)
                            val r2 = u16(workbook, off + 2)
                            val c1 = u16(workbook, off + 4)
                            val c2 = u16(workbook, off + 6)
                            merges.add(intArrayOf(r1, c1, r2, c2))
                            maxRow = maxOf(maxRow, r2); maxCol = maxOf(maxCol, c2)
                        }
                    }
                }
            }
            pos = body + len
        }

        // 没有 EOF（少见）时也别白读一场
        return build(cells, merges, maxRow, maxCol, workbook, sheetSeen)
    }

    private var stringFormulaCell: Pair<Int, Int>? = null

    private fun build(
        cells: Map<Long, String>,
        merges: List<IntArray>,
        maxRow: Int,
        maxCol: Int,
        workbook: ByteArray,
        sheetSeen: Boolean
    ): SheetGrid {
        stringFormulaCell = null
        if (!sheetSeen) throw UnsupportedXls("没找到工作表")
        if (maxRow < 0 || maxCol < 0) return SheetGrid(emptyList(), merges)
        val rows = ArrayList<List<String>>(maxRow + 1)
        for (r in 0..maxRow) {
            val row = ArrayList<String>(maxCol + 1)
            for (c in 0..maxCol) row.add(cells[key(r, c)] ?: "")
            rows.add(row)
        }
        return SheetGrid(rows, merges)
    }

    private fun key(row: Int, col: Int): Long = (row.toLong() shl 16) or col.toLong()

    private fun put(cells: HashMap<Long, String>, row: Int, col: Int, text: String) {
        if (text.isNotEmpty()) cells[key(row, col)] = text
    }

    // ------------------------------------------------------------ SST 字符串表

    /**
     * 解析 SST（+ 它后面的若干 CONTINUE）。
     *
     * 这里是整个读取器最容易写错的地方：一条字符串的字符可以**跨到 CONTINUE 记录**里继续，
     * 而每段 CONTINUE 开头有一个新的「压缩标志」字节，说明接下来的字符是单字节还是双字节。
     * 所以不能把各段简单拼起来当连续数据读，得在跨段处重新取标志。
     */
    private fun readSst(data: ByteArray, chunks: List<Pair<Int, Int>>): List<String> {
        val segs = chunks.map { (off, len) -> intArrayOf(off, len) }
        var si = 0
        var off = segs[0][0]
        var end = off + segs[0][1]

        fun ensure(): Boolean {
            while (off >= end && si < segs.size - 1) {
                si++
                off = segs[si][0]
                end = off + segs[si][1]
            }
            return off < end
        }

        fun need(n: Int): Boolean {
            var remain = n
            while (remain > 0) {
                if (!ensure()) return false
                remain -= minOf(remain, end - off)
                if (remain > 0) {
                    if (si >= segs.size - 1) return false
                    si++
                    off = segs[si][0]
                    end = off + segs[si][1]
                }
            }
            return true
        }

        if (segs[0][1] < 8) return emptyList()
        val total = i32(data, off + 4)
        off += 8

        val out = ArrayList<String>(minOf(total, 4096))
        for (n in 0 until total) {
            if (!ensure()) break
            if (off + 3 > end) {
                if (si >= segs.size - 1) break
                si++
                off = segs[si][0]
                end = off + segs[si][1]
                continue
            }
            val charCount = u16(data, off)
            val flags = data[off + 2].toInt()
            off += 3
            val rich = (flags and 0x08) != 0
            val ext = (flags and 0x04) != 0
            var richRuns = 0
            if (rich) {
                if (!need(2)) break
                richRuns = u16(data, off); off += 2
            }
            var extSize = 0
            if (ext) {
                if (!need(4)) break
                extSize = i32(data, off); off += 4
            }

            val sb = StringBuilder(charCount)
            var wide = (flags and 0x01) != 0
            var left = charCount
            while (left > 0) {
                if (!ensure()) break
                val avail = end - off
                if (wide) {
                    val canTake = minOf(left, avail / 2)
                    for (k in 0 until canTake) {
                        sb.append(u16(data, off + k * 2).toChar())
                    }
                    off += canTake * 2
                    left -= canTake
                } else {
                    val canTake = minOf(left, avail)
                    if (canTake > 0) {
                        sb.append(String(data, off, canTake, LATIN1))
                        off += canTake
                        left -= canTake
                    }
                }
                if (left > 0) {
                    // 跨段：下一段开头是新的压缩标志
                    if (si >= segs.size - 1) break
                    si++
                    off = segs[si][0]
                    end = off + segs[si][1]
                    if (off >= end) break
                    wide = (data[off].toInt() and 0x01) != 0
                    off += 1
                }
            }
            // 富文本格式与扩展数据跟在字符后面，跳过
            if (richRuns > 0) off += richRuns * 4
            if (extSize > 0) off += extSize
            out.add(sb.toString())
        }
        return out
    }

    /** 读一条内联的 BIFF8 Unicode 字符串（LABEL / STRING 记录里用）。 */
    private fun readUnicodeString(data: ByteArray, start: Int, limit: Int): String {
        if (start + 3 > limit) return ""
        val charCount = u16(data, start)
        val flags = data[start + 2].toInt()
        var off = start + 3
        val rich = (flags and 0x08) != 0
        val ext = (flags and 0x04) != 0
        var richRuns = 0
        if (rich && off + 2 <= limit) {
            richRuns = u16(data, off)
            off += 2
        }
        if (ext && off + 4 <= limit) {
            off += 4
        }
        val wide = (flags and 0x01) != 0
        val sb = StringBuilder(charCount)
        if (wide) {
            for (k in 0 until charCount) {
                val p = off + k * 2
                if (p + 2 > limit) break
                sb.append(u16(data, p).toChar())
            }
        } else {
            for (k in 0 until charCount) {
                val p = off + k
                if (p + 1 > limit) break
                sb.append(String(data, p, 1, LATIN1))
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------ 数值与字节

    /** RK 是压缩过的数字：最低位表示 ×100，次低位表示整数。 */
    private fun rkValue(raw: Int): Double {
        val isInt = (raw and 0x02) != 0
        val div100 = (raw and 0x01) != 0
        val value = if (isInt) {
            (raw shr 2).toDouble()
        } else {
            java.lang.Double.longBitsToDouble((raw.toLong() and 0xFFFFFFFCL) shl 32)
        }
        return if (div100) value / 100.0 else value
    }

    /** 数字转文本：整数就去掉小数点，教务系统里的节次/周次都是整数。 */
    private fun formatNumber(d: Double): String {
        return if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
            d.toLong().toString()
        } else {
            d.toString()
        }
    }

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun i32(b: ByteArray, at: Int): Int =
        u16(b, at) or (u16(b, at + 2) shl 16)

    private fun u32(b: ByteArray, at: Int): Int = i32(b, at)

    private fun i64(b: ByteArray, at: Int): Long =
        (i32(b, at).toLong() and 0xFFFFFFFFL) or (i32(b, at + 4).toLong() shl 32)
}
