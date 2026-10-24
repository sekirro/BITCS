package cn.local.bitcourse.ui

import cn.local.bitcourse.model.Course
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableLayoutTest {
    private fun course(id: String, start: Int, end: Int, day: Int = 1, weeks: List<Int> = listOf(1)) =
        Course(id = id, name = "课程$id", day = day, startPeriod = start, endPeriod = end, weeks = weeks)

    @Test fun consecutivePeriodsRemainSeparateButSharedPeriodGroupsTogether() {
        val blocks = TimetableLayout.blocksForDay(listOf(
            course("a", 1, 2), course("b", 3, 4), course("c", 4, 5),
        ), week = 1, day = 1)

        assertEquals(listOf(1 to 2, 3 to 5), blocks.map { it.startPeriod to it.endPeriod })
        assertEquals(listOf(listOf("a"), listOf("b", "c")), blocks.map { it.courses.map(Course::id) })
    }

    @Test fun transitiveOverlapCreatesOneBlockWithoutDroppingCourses() {
        val courses = listOf(course("c", 5, 6), course("a", 1, 3), course("b", 3, 5))
        val block = TimetableLayout.blocksForDay(courses, week = 1, day = 1).single()

        assertEquals(1, block.startPeriod)
        assertEquals(6, block.endPeriod)
        assertEquals(listOf("a", "b", "c"), block.courses.map(Course::id))
    }

    @Test fun nestedAndIdenticalIntervalsRemainAvailableInChooser() {
        val courses = listOf(course("a", 1, 8), course("b", 2, 3), course("c", 2, 3), course("d", 6, 7))
        val block = TimetableLayout.blocksForDay(courses, week = 1, day = 1).single()

        assertEquals(1 to 8, block.startPeriod to block.endPeriod)
        assertEquals(courses.map(Course::id).toSet(), block.courses.map(Course::id).toSet())
        assertEquals(courses.size, block.courses.size)
    }

    @Test fun filtersWeekAndDayBeforeGroupingAndKeepsSunday() {
        val courses = listOf(course("monday", 1, 2), course("sunday", 1, 2, day = 7),
            course("nextWeek", 1, 2, day = 7, weeks = listOf(2)))

        val sunday = TimetableLayout.blocksForDay(courses, week = 1, day = 7)
        assertEquals(listOf("sunday"), sunday.flatMap { it.courses }.map(Course::id))
        assertTrue(TimetableLayout.blocksForDay(courses, week = 1, day = 2).isEmpty())
        assertTrue(TimetableLayout.blocksForDay(emptyList(), week = 1, day = 1).isEmpty())
    }

    @Test fun disjointGroupsHaveStableOrderAndRetainEveryVisibleCourseExactlyOnce() {
        val courses = listOf(course("d", 12, 13), course("c", 7, 8), course("b", 2, 4), course("a", 1, 2))
        val blocks = TimetableLayout.blocksForDay(courses, week = 1, day = 1)

        assertEquals(listOf(1 to 4, 7 to 8, 12 to 13), blocks.map { it.startPeriod to it.endPeriod })
        assertEquals(listOf("a", "b", "c", "d"), blocks.flatMap { it.courses }.map(Course::id))
        assertEquals(blocks, TimetableLayout.blocksForDay(courses.reversed(), week = 1, day = 1))
    }
}
