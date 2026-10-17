package com.nexoraism.coursemeow.domain.sheet

import com.nexoraism.coursemeow.data.WeekRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 正方在线课表解析器测试。
 *
 * 样例都按真实接口 / 真实表格的字段形状手写（不含真实姓名与教室，用「张三」「三江楼」这类占位），
 * 这样测试不依赖外部数据、可离线跑。
 */
class ZfOnlineTimetableParserTest {

    // ---------- 周次（纯函数，直接单测） ----------

    @Test
    fun `weekRule - range is every week`() {
        assertEquals(
            ZfOnlineTimetableParser.WeekRuleSpec(1, 16, WeekRule.EVERY, ""),
            ZfOnlineTimetableParser.parseWeekRule("1-16周")
        )
    }

    @Test
    fun `weekRule - odd weeks`() {
        assertEquals(
            ZfOnlineTimetableParser.WeekRuleSpec(1, 16, WeekRule.ODD, ""),
            ZfOnlineTimetableParser.parseWeekRule("1-16周(单)")
        )
    }

    @Test
    fun `weekRule - even weeks`() {
        assertEquals(
            ZfOnlineTimetableParser.WeekRuleSpec(4, 18, WeekRule.EVEN, ""),
            ZfOnlineTimetableParser.parseWeekRule("4-18周(双)")
        )
    }

    @Test
    fun `weekRule - multi segment becomes custom`() {
        assertEquals(
            ZfOnlineTimetableParser.WeekRuleSpec(1, 9, WeekRule.CUSTOM, "1,2,3,4,5,7,8,9"),
            ZfOnlineTimetableParser.parseWeekRule("1-5,7-9周")
        )
    }

    @Test
    fun `weekRule - fullwidth brackets and single-word suffix tolerated`() {
        assertEquals(
            ZfOnlineTimetableParser.WeekRuleSpec(1, 16, WeekRule.ODD, ""),
            ZfOnlineTimetableParser.parseWeekRule("1-16周（单）")
        )
        assertEquals(
            ZfOnlineTimetableParser.WeekRuleSpec(1, 16, WeekRule.ODD, ""),
            ZfOnlineTimetableParser.parseWeekRule("1-16周单周")
        )
        assertEquals(
            ZfOnlineTimetableParser.WeekRuleSpec(1, 16, WeekRule.EVEN, ""),
            ZfOnlineTimetableParser.parseWeekRule("1-16周双周")
        )
    }

    @Test
    fun `weekRule - lone word without numbers is unknown`() {
        assertNull(ZfOnlineTimetableParser.parseWeekRule("单周"))
        assertNull(ZfOnlineTimetableParser.parseWeekRule(""))
    }

    // ---------- JSON 分支 ----------

    @Test
    fun `json - standard zf payload`() {
        val raw = """
            {"kbList":[{"kcmc":"大学英语(I)","xqj":"1","jcs":"1-2","zcd":"4-18周(双)","cdmc":"三江楼0302","xm":"张三"}],"xsxx":{}}
        """.trimIndent()

        val result = ZfOnlineTimetableParser.parse(raw, "我的课表")
        assertNotNull(result)
        result!!
        assertEquals("我的课表", result.timetableName)
        assertEquals(0L, result.semesterStartDate)
        assertEquals(0L, result.semesterEndDate)
        assertEquals(1, result.courses.size)

        val c = result.courses[0]
        assertEquals("大学英语(I)", c.name)
        assertEquals(1, c.dayOfWeek)
        assertEquals(1, c.startPeriod)
        assertEquals(2, c.endPeriod)
        assertEquals(4, c.startWeek)
        assertEquals(18, c.endWeek)
        assertEquals(WeekRule.EVEN, c.weekType)
        assertEquals("三江楼", c.building)
        assertEquals("0302", c.room)
        assertEquals("张三", c.teacher)
        assertTrue(result.incomplete.isEmpty())
        assertTrue(result.notes.any { it.contains("正方") })
    }

    @Test
    fun `json - odd single period and comma list week`() {
        val raw = """
            {"kbList":[
              {"kcmc":"高等数学","xqj":"3","jcs":"3","zcd":"1-16周(单)","cdmc":"三江楼附楼 203","xm":"李四"},
              {"kcmc":"程序设计","xqj":"5","jcs":"5-6","zcd":"1-5,7-9周","cdmc":"计算机楼113","xm":["王五","赵六"]}
            ]}
        """.trimIndent()

        val result = ZfOnlineTimetableParser.parse(raw)!!
        assertEquals(2, result.courses.size)

        val math = result.courses[0]
        assertEquals(3, math.dayOfWeek)
        assertEquals(3, math.startPeriod)
        assertEquals(3, math.endPeriod)
        assertEquals(WeekRule.ODD, math.weekType)
        assertEquals(1, math.startWeek)
        assertEquals(16, math.endWeek)
        // 「附楼」跟着教室号走：楼名是「三江楼」，教室是「附楼203」（与 PDF 解析那条路一致）。
        assertEquals("三江楼", math.building)
        assertEquals("附楼203", math.room)

        val code = result.courses[1]
        assertEquals(WeekRule.CUSTOM, code.weekType)
        assertEquals("1,2,3,4,5,7,8,9", code.customWeeks)
        assertEquals(9, code.endWeek)
        // xm 是数组，多位教师用顿号连起来
        assertEquals("王五、赵六", code.teacher)
    }

    @Test
    fun `json - missing fields tolerated`() {
        val raw = """
            {"kbList":[
              {"kcmc":"没有周次也没有地点","xqj":"2","jcs":"7-8"},
              {"xqj":"2","jcs":"1-2","zcd":"1-16周","cdmc":"三江楼0101","xm":"张三"},
              {"kcmc":"星期非法","xqj":"9","jcs":"1-2","zcd":"1-16周","cdmc":"三江楼0101"}
            ]}
        """.trimIndent()

        val result = ZfOnlineTimetableParser.parse(raw)!!
        // 只有第一条能定位（缺课名的第二条、星期非法的第三条被跳过）
        assertEquals(1, result.courses.size)
        assertEquals("没有周次也没有地点", result.courses[0].name)
        // 缺周次 → 按整学期算，并记入 incomplete
        assertEquals(1, result.courses[0].startWeek)
        assertEquals(20, result.courses[0].endWeek)
        assertEquals(WeekRule.EVERY, result.courses[0].weekType)
        assertTrue(result.incomplete.contains("没有周次也没有地点"))
    }

    @Test
    fun `json - alternate outer key is found`() {
        val raw = """{"data":{"rows":[{"kcmc":"体育","xqj":"6","jcs":"3-4","zcd":"1-16周","cdmc":"体育馆"}]}}"""
        val result = ZfOnlineTimetableParser.parse(raw)!!
        assertEquals(1, result.courses.size)
        assertEquals("体育", result.courses[0].name)
        assertEquals(6, result.courses[0].dayOfWeek)
    }

    // ---------- HTML 分支 ----------

    @Test
    fun `html - kbtable fragment parses`() {
        val html = """
            <html><body>
            <table class="table" id="kbtable">
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr>
                <td>第1-2节</td>
                <td>大学物理<br>李四<br>1-16周<br>三江楼附楼 203<br>学分:2.0<br>课程性质简称:必修<br>考核方式:考查</td>
                <td></td>
                <td>高等数学<br>1-5,7-9周<br>计算机楼113</td>
              </tr>
              <tr>
                <td>第3-4节</td>
                <td></td>
                <td>程序设计<br>王五<br>1-16周(单)<br>三江楼0302</td>
                <td></td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()

        val result = ZfOnlineTimetableParser.parse(html)
        assertNotNull(result)
        result!!
        assertEquals(3, result.courses.size)

        val physics = result.courses.first { it.name == "大学物理" }
        assertEquals(1, physics.dayOfWeek)
        assertEquals(1, physics.startPeriod)
        assertEquals(2, physics.endPeriod)
        assertEquals(1, physics.startWeek)
        assertEquals(16, physics.endWeek)
        assertEquals(WeekRule.EVERY, physics.weekType)
        assertEquals("李四", physics.teacher)
        // 「附楼」跟着教室号走（同 json 那条用例）。
        assertEquals("三江楼", physics.building)
        assertEquals("附楼203", physics.room)
        // 格子里的元信息行（学分 / 课程性质 / 考核方式）被单独取出来，不污染教师与地点
        assertEquals("2.0", physics.credits)
        assertEquals("必修", physics.nature)
        assertEquals("考查", physics.assessment)

        val math = result.courses.first { it.name == "高等数学" }
        assertEquals(3, math.dayOfWeek)
        assertEquals(WeekRule.CUSTOM, math.weekType)
        // 课名列下一行直接是周次 → 没有教师
        assertEquals("", math.teacher)
        assertEquals("计算机楼", math.building)
        assertEquals("113", math.room)

        val code = result.courses.first { it.name == "程序设计" }
        assertEquals(2, code.dayOfWeek)
        assertEquals(3, code.startPeriod)
        assertEquals(4, code.endPeriod)
        assertEquals(WeekRule.ODD, code.weekType)
    }

    @Test
    fun `html - without day header returns null instead of guessing`() {
        val html = """
            <table id="kbtable">
              <tr><td>大学物理<br>李四<br>1-16周<br>三江楼203</td></tr>
            </table>
        """.trimIndent()
        assertNull(ZfOnlineTimetableParser.parse(html))
    }

    @Test
    fun `html - cell without week returns null instead of guessing`() {
        val html = """
            <table id="kbtable">
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr><td>第1-2节</td><td>大学物理<br>李四</td><td></td><td></td></tr>
            </table>
        """.trimIndent()
        assertNull(ZfOnlineTimetableParser.parse(html))
    }

    // ---------- 兜底 ----------

    @Test
    fun `garbage input returns null`() {
        assertNull(ZfOnlineTimetableParser.parse("这不是课表，只是一段随便的文字"))
        assertNull(ZfOnlineTimetableParser.parse(""))
        assertNull(ZfOnlineTimetableParser.parse("{}"))
    }

    // ---------- 真实回包结构 ----------

    @Test
    fun `real zf payload with xsxx and practice list is parsed`() {
        // 取自新正方「学生课表查询」的真实回包结构：顶层含 kbList，
        // 另有干扰项 sjkList（实践课，没有星期与节次）与 xsxx 元信息；
        // 多教师用英文逗号分隔（正方的实际写法）。
        val json = """
            {"qsxqj":"1","xsxx":{"XNMC":"2026-2027","XQMMC":"1","XM":"刘俊霖"},
             "sjkList":[{"kcmc":"军事技能","zcd":"2-3周","jsxm":"人武部"}],
             "kbList":[
               {"kcmc":"程序设计基础A","xqj":"1","jcs":"3-4","zcd":"4-19周","cdmc":"三江楼0811","lh":"三江楼","xm":"耿霞"},
               {"kcmc":"高等数学A(I)","xqj":"4","jcs":"3-4","zcd":"4-18周(双)","cdmc":"三山楼510","lh":"三山楼","xm":"张海峰,沈文强"},
               {"kcmc":"思想道德与法治","xqj":"5","jcs":"3-4","zcd":"5-19周(单)","cdmc":"三江楼0604","lh":"三江楼","xm":"周海燕,崔金贵"}
             ]}
        """.trimIndent()

        val result = ZfOnlineTimetableParser.parse(json)
        assertNotNull(result)
        result!!
        // 只认 kbList：sjkList 里的实践课没有星期/节次，不该混进课表
        assertEquals(3, result.courses.size)

        val math = result.courses.first { it.name == "高等数学A(I)" }
        assertEquals(4, math.dayOfWeek)
        assertEquals(3, math.startPeriod)
        assertEquals(4, math.endPeriod)
        assertEquals(WeekRule.EVEN, math.weekType)
        assertEquals("三山楼", math.building)
        assertEquals("510", math.room)
        // 正方的多教师是英文逗号，进到应用里要统一成顿号
        assertEquals("张海峰、沈文强", math.teacher)

        val ethics = result.courses.first { it.name == "思想道德与法治" }
        assertEquals(WeekRule.ODD, ethics.weekType)
        assertEquals(5, ethics.startWeek)
        assertEquals(19, ethics.endWeek)
    }

    // ---------- 真实抓取的完整回包 ----------

    @Test
    fun `real captured zf payload parses end to end`() {
        // core/src/test/resources/zf_real_payload.json 是**真实抓下来**的一份完整回包。
        // 上面那些样例都是照它的字段形状缩写的，这一条才是「能不能解析真实教务系统」的判据。
        val json = javaClass.getResourceAsStream("/zf_real_payload.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("测试资源 zf_real_payload.json 缺失")

        val result = ZfOnlineTimetableParser.parse(json, timetableName = "2026-2027 第一学期")
        assertNotNull(result)
        result!!
        // 回包里 kbList 共 16 条（同一门课的不同时间段各算一条）
        assertEquals(16, result.courses.size)

        val names = result.courses.map { it.name }.toSet()
        assertTrue(names.contains("程序设计基础A"))
        assertTrue(names.contains("高等数学A(I)"))
        assertTrue(names.contains("大学英语(I)"))
        assertTrue(names.contains("大数据应用"))
        // sjkList 里的实践课（军事技能等）没有星期与节次，不该混进课表
        assertFalse(names.contains("军事技能"))

        val english = result.courses.first { it.name == "大学英语(I)" }
        assertEquals(3, english.dayOfWeek)        // 周三
        assertEquals(1, english.startPeriod)
        assertEquals(2, english.endPeriod)
        assertEquals(WeekRule.EVERY, english.weekType)
        assertEquals(4, english.startWeek)
        assertEquals(19, english.endWeek)
        assertEquals("三江楼", english.building)
        assertEquals("0302", english.room)
        assertEquals("范林芳", english.teacher)
        // 学分 / 总学时 / 课程性质来自回包字段：xf="2" / zxs(或 kczxs)="32" / kcxz="必修"
        assertEquals("2", english.credits)
        assertEquals("32", english.totalHours)
        assertEquals("必修", english.nature)

        val bigData = result.courses.first { it.name == "大数据应用" }
        assertEquals(6, bigData.dayOfWeek)        // 周六
        assertEquals(5, bigData.startPeriod)
        assertEquals(8, bigData.endPeriod)
        // 选修课：xf="3.0" / kczxs="64" / kcxz="选修"
        assertEquals("3.0", bigData.credits)
        assertEquals("64", bigData.totalHours)
        assertEquals("选修", bigData.nature)

        // 一门课排在不同时段 → 各自独立成条（高等数学在一周里排了三次）
        assertEquals(3, result.courses.count { it.name == "高等数学A(I)" })

        // 单周 / 双周要认出来
        assertEquals(WeekRule.ODD, result.courses.first { it.name == "思想道德与法治" && it.dayOfWeek == 5 }.weekType)
        assertEquals(WeekRule.EVEN, result.courses.first { it.name == "高等数学A(I)" && it.dayOfWeek == 4 }.weekType)
    }

    @Test
    fun `real class payload gives semester start and expanded weeks`() {
        // core/src/test/resources/zf_class_payload.json 是「班级课表」接口
        // （bjkbdy_cxBjKb.html?gnmkdm=N214505）的真实回包。它比个人课表多了两样关键东西：
        //   1. weekNum —— 每一周的起止日期，学期开始日期就藏在这里（个人课表没有）；
        //   2. zcds   —— 展开后的周次列表，单双周不用再去解析文本。
        val json = javaClass.getResourceAsStream("/zf_class_payload.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("测试资源 zf_class_payload.json 缺失")

        val result = ZfOnlineTimetableParser.parse(json, timetableName = "信息安全2601")
        assertNotNull(result)
        result!!

        // 第 1 周 = 2026-08-31（weekNum 第一条 rq: "2026-08-31/2026-09-06"）
        val startDay = Instant.ofEpochMilli(result.semesterStartDate)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        assertEquals(LocalDate.of(2026, 8, 31), startDay)
        assertTrue(result.semesterEndDate > result.semesterStartDate)

        // zcds "4,6,8,…,18" → 双周（周四那节高等数学）
        assertEquals(
            WeekRule.EVEN,
            result.courses.first { it.name == "高等数学A(I)" && it.dayOfWeek == 4 }.weekType
        )

        // 「未排地点」是占位说法，不该当成地点；这类课会被记进 incomplete
        val pe = result.courses.firstOrNull { it.name.contains("大学体育") }
        assertNotNull(pe)
        assertEquals("", pe!!.building)
        assertEquals("", pe.room)
        assertTrue(result.incomplete.any { it.contains("大学体育") })
    }
}
