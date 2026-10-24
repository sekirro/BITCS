package cn.local.bitcourse.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SchoolParserTest {
    private val term = "2026-2027-1"

    @Test fun chineseWeeksTakePriorityAndSplitPracticeRowsMerge() {
        val spans = listOf("16周", "15周", "14周", "13周", "11-12周", "9-10周")
        val rows = listOf(2, 4).flatMap { day ->
            spans.map { span ->
                row(
                    name = "人工智能实践",
                    day = day,
                    start = 11,
                    end = 12,
                    chinese = span,
                    rawWeeks = SchoolParser.parseWeeks(span).map { it + 3 },
                )
            }
        }
        val parsed = SchoolParser.parseCourses(schedule(rows), term)
        assertEquals(2, parsed.courses.size)
        assertEquals(listOf(2, 4), parsed.courses.map { it.day })
        parsed.courses.forEach { assertEquals((9..16).toList(), it.weeks) }
        assertEquals(3, parsed.weekOffset)
        assertTrue(parsed.warnings.isEmpty())
    }

    @Test fun weekListsRangesAndParityAreExpandedAndSorted() {
        assertEquals(listOf(1, 2, 4, 5, 6, 7), SchoolParser.parseWeeks("1-2周,4-7周"))
        assertEquals(listOf(1, 3, 5, 7), SchoolParser.parseWeeks("第1—8周（单周）"))
        assertEquals(listOf(2, 4, 6, 8), SchoolParser.parseWeeks("1-8双周"))
        assertEquals(listOf(1, 3, 7, 9), SchoolParser.parseWeeks("1-4周、7-10周(单)"))
        assertEquals(listOf(1, 3, 6, 8), SchoolParser.parseWeeks("1-4单周,5-8双周"))
        assertEquals(listOf(1, 2, 3), SchoolParser.parseWeeks("第１～３周，２周"))
        assertEquals(emptyList<Int>(), SchoolParser.parseWeeks("  "))
    }

    @Test fun invalidWeekRangesDoNotSilentlyBecomeEmptyLists() {
        listOf("0周", "8-2周", "单周", "不详", "61周", "1-999999999999周", "1周,,2周").forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { SchoolParser.parseWeeks(text) }
        }
    }

    @Test fun duplicateRowsAreDeduplicatedAndIdsAreStable() {
        val a = row(chinese = "1-2周", rawWeeks = listOf(4, 5))
        val b = row(chinese = "2-3周", rawWeeks = listOf(5, 6))
        val first = SchoolParser.parseCourses(schedule(listOf(a, b, a)), term)
        val second = SchoolParser.parseCourses(schedule(listOf(b, a)), term)
        assertEquals(1, first.courses.size)
        assertEquals(listOf(1, 2, 3), first.courses.single().weeks)
        assertEquals(first.courses, second.courses)
    }

    @Test fun mergeKeyPreservesRoomTeacherCampusCourseAndPeriodDifferences() {
        val base = row()
        val variants = listOf(
            base,
            base.deepCopy().apply { addProperty("KCH", "ANON-002") },
            base.deepCopy().apply { addProperty("KCM", "另一门匿名课程") },
            base.deepCopy().apply { addProperty("SKXQ", 3) },
            base.deepCopy().apply { addProperty("KSJC", 2) },
            base.deepCopy().apply { addProperty("JSJC", 4) },
            base.deepCopy().apply { addProperty("JASMC", "示例教室B") },
            base.deepCopy().apply { addProperty("SKJS", "示例教师B") },
            base.deepCopy().apply { addProperty("XXXQMC", "示例校区B") },
        )
        val parsed = SchoolParser.parseCourses(schedule(variants), term)
        assertEquals(9, parsed.courses.size)
        assertEquals(9, parsed.courses.map { it.id }.toSet().size)
    }

    @Test fun validEmptyRowsAreSuccessfulButOffsetIsUnknown() {
        val parsed = SchoolParser.parseCourses(schedule(emptyList()), term)
        assertEquals(term, parsed.term)
        assertTrue(parsed.courses.isEmpty())
        assertNull(parsed.weekOffset)
    }

    @Test fun innerQueryFailureCannotBecomeAnEmptySuccessfulSchedule() {
        for (code in listOf("0", "-1", "500")) {
            val response = """{"code":0,"datas":{"cxxszhxqkb":{"extParams":{"code":$code,"msg":"匿名失败示例"},"rows":[]}}}"""
            assertThrows(IllegalArgumentException::class.java) { SchoolParser.parseCourses(response, term) }
        }
        val response = """{"code":0,"datas":{"cxxszhxqkb":{"extParams":{"code":1,"msg":"查询成功"},"totalSize":-1,"rows":[]}}}"""
        assertTrue(SchoolParser.parseCourses(response, term).courses.isEmpty())
    }

    @Test fun incompletePaginationIsRejectedButUnknownTotalIsAllowed() {
        val root = com.google.gson.JsonParser.parseString(schedule(listOf(row()))).asJsonObject
        val data = root.getAsJsonObject("datas").getAsJsonObject("cxxszhxqkb")
        data.addProperty("totalSize", 2)
        assertThrows(IllegalArgumentException::class.java) { SchoolParser.parseCourses(root.toString(), term) }
        for (total in listOf(-1, 1)) {
            data.addProperty("totalSize", total)
            assertEquals(1, SchoolParser.parseCourses(root.toString(), term).courses.size)
        }
    }

    @Test fun validWeekAndPeriodBoundariesMatchTheUiLimits() {
        val boundary = row(start = 30, end = 30, chinese = "60周", rawWeeks = listOf(60))
        val parsed = SchoolParser.parseCourses(schedule(listOf(boundary)), term)
        assertEquals(listOf(60), parsed.courses.single().weeks)
        assertEquals(30, parsed.courses.single().endPeriod)
        assertEquals(0, parsed.weekOffset)
        val tooLate = row(chinese = "61周", rawWeeks = listOf(61))
        assertThrows(IllegalArgumentException::class.java) { SchoolParser.parseCourses(schedule(listOf(tooLate)), term) }
    }

    @Test fun missingChineseWeeksFallBackToBitmapWithZeroOffset() {
        val parsed = SchoolParser.parseCourses(schedule(listOf(row(chinese = null, rawWeeks = listOf(4, 6)))), term)
        assertEquals(listOf(4, 6), parsed.courses.single().weeks)
        assertEquals(0, parsed.weekOffset)
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test fun invalidChineseWeeksCanFallBackButDoNotPretendToBeChineseWeeks() {
        val parsed = SchoolParser.parseCourses(schedule(listOf(row(chinese = "待定", rawWeeks = listOf(5, 7)))), term)
        assertEquals(listOf(5, 7), parsed.courses.single().weeks)
        assertEquals(0, parsed.weekOffset)
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test fun missingBitmapKeepsChineseWeeksButCannotInferOffset() {
        val record = row(chinese = "9-16周").apply { remove("SKZC") }
        val parsed = SchoolParser.parseCourses(schedule(listOf(record)), term)
        assertEquals((9..16).toList(), parsed.courses.single().weeks)
        assertNull(parsed.weekOffset)
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test fun malformedBitmapCannotOverrideValidChineseWeeks() {
        val record = row(chinese = "1-3周").apply { addProperty("SKZC", "00x111") }
        val parsed = SchoolParser.parseCourses(schedule(listOf(record)), term)
        assertEquals(listOf(1, 2, 3), parsed.courses.single().weeks)
        assertNull(parsed.weekOffset)
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test fun differentOffsetsBetweenRowsAreNotGuessed() {
        val parsed = SchoolParser.parseCourses(schedule(listOf(
            row(chinese = "1-2周", rawWeeks = listOf(4, 5)),
            row(chinese = "3-4周", rawWeeks = listOf(7, 8)),
        )), term)
        assertEquals(listOf(1, 2, 3, 4), parsed.courses.single().weeks)
        assertNull(parsed.weekOffset)
        assertTrue(parsed.warnings.any { it.contains("不一致") })
    }

    @Test fun bitmapFallbackAndShiftedChineseRowsHaveUnknownCombinedOffset() {
        val parsed = SchoolParser.parseCourses(schedule(listOf(
            row(chinese = "1周", rawWeeks = listOf(4)),
            row(chinese = null, rawWeeks = listOf(8)),
        )), term)
        assertEquals(listOf(1, 8), parsed.courses.single().weeks)
        assertNull(parsed.weekOffset)
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test fun oneRowWithInconsistentOrUnequalWeeksCannotInferOffset() {
        for (bitmap in listOf(listOf(4, 6), listOf(4))) {
            val parsed = SchoolParser.parseCourses(schedule(listOf(row(chinese = "1-2周", rawWeeks = bitmap))), term)
            assertNull(parsed.weekOffset)
            assertTrue(parsed.warnings.isNotEmpty())
        }
    }

    @Test fun malformedOrFailedResponsesNeverBecomeSuccessfulEmptySchedules() {
        val responses = listOf(
            "<!doctype html><html>login</html>",
            "{\"code\":403,\"datas\":{\"cxxszhxqkb\":{\"rows\":[]}}}",
            "{\"success\":false,\"datas\":{\"cxxszhxqkb\":{\"rows\":[]}}}",
            "{\"error\":\"expired\",\"datas\":{\"cxxszhxqkb\":{\"rows\":[]}}}",
            "{}",
            "{\"datas\":{\"cxxszhxqkb\":{}}}",
            "{\"datas\":{\"cxxszhxqkb\":{\"rows\":null}}}",
            "{\"datas\":{\"cxxszhxqkb\":{\"rows\":{}}}}",
            "{\"datas\":{\"cxxszhxqkb\":{\"rows\":[null]}}}",
            "{\"datas\":{\"cxxszhxqkb\":{\"rows\":[{}]}}}",
            "[]",
            "{not_json}",
            "{\"datas\":{\"cxxszhxqkb\":{\"rows\":[]}}} trailing",
        )
        responses.forEach { response ->
            assertThrows(IllegalArgumentException::class.java) { SchoolParser.parseCourses(response, term) }
        }
    }

    @Test fun invalidRowsCannotEraseTheExistingScheduleAsAnEmptySuccess() {
        val invalidRows = listOf(
            row().apply { remove("KCM") },
            row().apply { addProperty("SKXQ", 8) },
            row().apply { addProperty("SKXQ", 1.5) },
            row().apply { addProperty("KSJC", 0) },
            row().apply { addProperty("JSJC", 0) },
            row().apply { addProperty("KSJC", 31); addProperty("JSJC", 31) },
            row().apply { addProperty("JSJC", Int.MAX_VALUE) },
            row().apply { remove("ZCMC"); remove("SKZC") },
            row().apply { add("JASMC", JsonObject()) },
            row().apply { addProperty("XNXQDM", "2025-2026-1") },
        )
        invalidRows.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { SchoolParser.parseCourses(schedule(listOf(invalid)), term) }
        }
    }

    @Test fun currentTermAndTermValidationRequireConsecutiveYears() {
        assertEquals(term, SchoolParser.currentTerm("""{"code":0,"datas":{"dqxnxq":{"rows":[{"DM":"$term"}]}}}"""))
        assertEquals(term, SchoolParser.validateTerm(" $term "))
        assertTrue(SchoolParser.isValidTerm("2026-2027-3"))
        listOf("", "2026-2026-1", "2026-2028-1", "2026-2027-0", "2026-2027-4", "26-27-1", "0000-0001-1").forEach { invalid ->
            assertFalse(SchoolParser.isValidTerm(invalid))
            assertThrows(IllegalArgumentException::class.java) { SchoolParser.parseCourses(schedule(emptyList()), invalid) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SchoolParser.currentTerm("""{"datas":{"dqxnxq":{"rows":[]}}}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SchoolParser.currentTerm("""{"datas":{"dqxnxq":{"rows":[{"DM":"2026-2028-1"}]}}}""")
        }
    }

    @Test fun firstMondayMapsXqAndRqAndAcceptsIsoDateTimes() {
        val expected = LocalDate.of(2026, 9, 7)
        assertEquals(expected, SchoolParser.firstMonday("""{"code":0,"data":[{"XQ":2,"RQ":"2026-09-08"},{"XQ":1,"RQ":"2026-09-07"}]}"""))
        for (date in listOf("2026-09-07T00:00:00", "2026-09-07T00:00:00+08:00", "2026-09-07 00:00:00")) {
            assertEquals(expected, SchoolParser.firstMonday("""{"data":[{"XQ":"1","RQ":"$date"}]}"""))
        }
    }

    @Test fun missingMondayDateReturnsNull() {
        listOf("{}", """{"data":null}""", """{"data":[]}""", """{"data":[{"XQ":"1"}]}""", """{"data":[{"XQ":"2","RQ":"2026-09-08"}]}""").forEach { json ->
            assertNull(SchoolParser.firstMonday(json))
        }
    }

    @Test fun invalidCalendarDateStructureAndWeekdayAreRejected() {
        listOf(
            "<html>login</html>",
            """{"code":500,"data":[]}""",
            """{"data":{}}""",
            """{"data":[null]}""",
            """{"data":[{"XQ":"1","RQ":"2026-09-08"}]}""",
            """{"data":[{"XQ":"1","RQ":"2026-02-30"}]}""",
            """{"data":[{"XQ":"1","RQ":"2026-09-07garbage"}]}""",
        ).forEach { json ->
            assertThrows(IllegalArgumentException::class.java) { SchoolParser.firstMonday(json) }
        }
    }

    @Test fun idsIncludeTheTermButDoNotExposeRecordFields() {
        val one = SchoolParser.parseCourses(schedule(listOf(row())), term).courses.single()
        val otherTerm = "2027-2028-1"
        val otherRow = row().apply { addProperty("XNXQDM", otherTerm) }
        val two = SchoolParser.parseCourses(schedule(listOf(otherRow)), otherTerm).courses.single()
        assertNotEquals(one.id, two.id)
        assertTrue(one.id.matches(Regex("[0-9a-f]{64}")))
    }

    private fun row(
        name: String = "匿名示例课程",
        day: Int = 2,
        start: Int = 1,
        end: Int = 3,
        chinese: String? = "1-2周",
        rawWeeks: List<Int> = listOf(4, 5),
    ): JsonObject = JsonObject().apply {
        addProperty("XNXQDM", term)
        addProperty("KCH", "ANON-001")
        addProperty("KCM", name)
        addProperty("SKJS", "示例教师A")
        addProperty("JASMC", "示例教室A")
        addProperty("XXXQMC", "示例校区A")
        addProperty("SKXQ", day)
        addProperty("KSJC", start)
        addProperty("JSJC", end)
        if (chinese != null) addProperty("ZCMC", chinese)
        addProperty("SKZC", (1..(rawWeeks.maxOrNull() ?: 1)).joinToString("") { if (it in rawWeeks) "1" else "0" })
    }

    private fun schedule(rows: List<JsonObject>): String = JsonObject().apply {
        addProperty("code", 0)
        add("datas", JsonObject().apply {
            add("cxxszhxqkb", JsonObject().apply {
                add("rows", JsonArray().apply { rows.forEach { add(it) } })
            })
        })
    }.toString()
}
