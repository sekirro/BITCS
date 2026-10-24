package cn.local.bitcourse.data

import cn.local.bitcourse.model.Course
import cn.local.bitcourse.ui.TimetableLayout
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseDisplayTest {
    private fun course(id: String, start: Int, end: Int, weeks: List<Int> = listOf(1)) = Course(
        id = id, name = "匿名课程", teacher = "示例教师", room = "示例教室", campus = "示例校区",
        day = 1, startPeriod = start, endPeriod = end, weeks = weeks,
    )

    @Test fun splitThreePeriodExamplesKeepOnlyTwoPeriodsInWeekEleven() {
        val raw = listOf(3, 8, 11).flatMapIndexed { index, start ->
            listOf(
                course("first-$start", start, start + 1, (1..11).toList()).copy(name = "匿名课程$index", day = index + 1),
                course("last-$start", start + 2, start + 2, (1..10).toList()).copy(name = "匿名课程$index", day = index + 1),
            )
        }
        val before = raw.map { it.copy(weeks = it.weeks.toList()) }
        val display = CourseDisplay.mergePeriods(raw)
        assertEquals(6, display.size)
        listOf(3, 8, 11).forEachIndexed { index, start ->
            val weekFive = TimetableLayout.blocksForDay(display, 5, index + 1).single()
            assertEquals(start to start + 2, weekFive.startPeriod to weekFive.endPeriod)
            assertEquals((1..10).toList(), weekFive.courses.single().weeks)
            assertEquals(weekFive.courses, SchedulePolicy.coursesOn(display, 5, index + 1))
            val weekEleven = TimetableLayout.blocksForDay(display, 11, index + 1).single()
            assertEquals(start to start + 1, weekEleven.startPeriod to weekEleven.endPeriod)
            assertEquals(listOf(11), weekEleven.courses.single().weeks)
            assertEquals(weekEleven.courses, SchedulePolicy.coursesOn(display, 11, index + 1))
        }
        assertEquals(before, raw)
    }

    @Test fun existingCacheNeedsNoMigrationAndKeepsRawFragments() {
        val saved = SavedSchedule("2026-2027-1", listOf(
            course("a", 3, 4, (1..11).toList()), course("b", 5, 5, (1..10).toList()),
        ), 1L, null, "unknown", emptyList())
        val gson = Gson()
        val json = gson.toJson(saved)
        val loaded = gson.fromJson(json, SavedSchedule::class.java)

        assertEquals(CourseDisplay.mergePeriods(saved.courses), CourseDisplay.mergePeriods(loaded.courses))
        assertEquals(json, gson.toJson(loaded))
        assertEquals(listOf(3 to 4, 5 to 5), loaded.courses.map { it.startPeriod to it.endPeriod })
    }

    @Test fun joinsOnlyWeeksWhereTheAdjacentFragmentExists() {
        val display = CourseDisplay.mergePeriods(listOf(
            course("a", 1, 2, listOf(1, 3)), course("b", 3, 3, listOf(2, 3)),
        ))
        assertEquals(listOf(1 to 2), SchedulePolicy.coursesOn(display, 1, 1).map { it.startPeriod to it.endPeriod })
        assertEquals(listOf(3 to 3), SchedulePolicy.coursesOn(display, 2, 1).map { it.startPeriod to it.endPeriod })
        assertEquals(listOf(1 to 3), SchedulePolicy.coursesOn(display, 3, 1).map { it.startPeriod to it.endPeriod })
    }

    @Test fun everyVisibleMetadataDifferencePreventsMerging() {
        val first = course("a", 1, 2)
        val next = course("a", 3, 3)
        listOf(
            next.copy(name = "另一课程"), next.copy(teacher = "另一教师"), next.copy(room = "另一教室"),
            next.copy(campus = "另一校区"), next.copy(day = 2), next.copy(name = next.name + " "),
        ).forEach { changed ->
            assertEquals(2, CourseDisplay.mergePeriods(listOf(first, changed)).size)
        }
    }

    @Test fun gapsRemainEmptyAndAdjacentChainsMergeTransitively() {
        val display = CourseDisplay.mergePeriods(listOf(
            course("a", 1, 2), course("b", 3, 3), course("c", 4, 5), course("d", 7, 8),
        ))
        assertEquals(listOf(1 to 5, 7 to 8), display.map { it.startPeriod to it.endPeriod })
    }

    @Test fun distinctSourceIdsWithIdenticalOrOverlappingPeriodsRemainSelectable() {
        for (second in listOf(course("other-code", 1, 2), course("other-code", 2, 3))) {
            val original = listOf(course("first-code", 1, 2), second)
            val display = CourseDisplay.mergePeriods(original)
            assertEquals(original.toSet(), display.toSet())
            assertEquals(2, TimetableLayout.blocksForDay(display, 1, 1).single().courses.size)
        }
    }

    @Test fun ambiguityPreservesTheWholeConnectedGroupOnlyInAffectedWeeks() {
        val first = course("a", 1, 2, listOf(1, 2))
        val adjacent = course("b", 3, 3, listOf(1, 2))
        val overlapping = course("other-code", 2, 2, listOf(2))
        val display = CourseDisplay.mergePeriods(listOf(first, adjacent, overlapping))

        assertEquals(listOf(1 to 3), SchedulePolicy.coursesOn(display, 1, 1).map { it.startPeriod to it.endPeriod })
        val weekTwo = SchedulePolicy.coursesOn(display, 2, 1)
        assertEquals(setOf("a", "b", "other-code"), weekTwo.map { it.id }.toSet())
        assertEquals(setOf(1 to 2, 3 to 3, 2 to 2), weekTwo.map { it.startPeriod to it.endPeriod }.toSet())
        assertEquals(3, TimetableLayout.blocksForDay(display, 2, 1).sumOf { it.courses.size })
    }

    @Test fun exactDuplicatesCollapseButDoNotHideDistinctSourceConflicts() {
        val first = course("a", 1, 2)
        val second = course("b", 1, 2)
        assertEquals(1, CourseDisplay.mergePeriods(listOf(first, first)).size)
        assertEquals(setOf(first, second), CourseDisplay.mergePeriods(listOf(first, first, second)).toSet())
    }

    @Test fun equalMergedRangesCollectWeeksEvenWhenRawFragmentationChanges() {
        val display = CourseDisplay.mergePeriods(listOf(
            course("whole", 1, 3, listOf(1)),
            course("part-a", 1, 2, listOf(2)), course("part-b", 3, 3, listOf(2)),
        ))
        assertEquals(listOf(1, 2), display.single().weeks)
        assertEquals(1 to 3, display.single().startPeriod to display.single().endPeriod)
    }

    @Test fun orderingAndIdsAreStableAndNormalizationIsIdempotent() {
        val raw = listOf(course("a", 1, 2, listOf(1, 2)), course("b", 3, 3, listOf(1, 2)),
            course("conflict", 2, 2, listOf(2)), course("later", 8, 9))
        val display = CourseDisplay.mergePeriods(raw)
        assertEquals(display, CourseDisplay.mergePeriods(raw.reversed()))
        assertEquals(display, CourseDisplay.mergePeriods(display))
        assertTrue(CourseDisplay.mergePeriods(emptyList()).isEmpty())
    }
}
