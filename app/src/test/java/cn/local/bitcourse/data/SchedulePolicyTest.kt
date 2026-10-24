package cn.local.bitcourse.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SchedulePolicyTest {
    @Test fun calendarOriginMovesWithConfirmedWeekOffset() {
        assertEquals(LocalDate.of(2026, 9, 21), SchedulePolicy.resolveMonday(LocalDate.of(2026, 8, 31), 3, null))
    }

    @Test fun manualChoiceSurvivesCalendarFailureAndChangedOffset() {
        val manual = LocalDate.of(2026, 9, 14)
        assertEquals(manual, SchedulePolicy.resolveMonday(null, null, manual))
        assertEquals(manual, SchedulePolicy.resolveMonday(LocalDate.of(2026, 8, 31), 0, manual))
    }

    @Test fun ambiguousCalendarNeverInventsStartDate() {
        assertNull(SchedulePolicy.resolveMonday(LocalDate.of(2026, 8, 31), null, null))
        assertNull(SchedulePolicy.resolveMonday(null, 3, null))
    }

    @Test(expected = IllegalArgumentException::class) fun manualDateMustBeMonday() {
        SchedulePolicy.resolveMonday(null, null, LocalDate.of(2026, 9, 15))
    }

    @Test fun weekCalculationHonorsMondayBoundaryAndBeforeTerm() {
        val start = LocalDate.of(2026, 9, 14)
        assertEquals(1, SchedulePolicy.weekAt(start, start))
        assertEquals(1, SchedulePolicy.weekAt(start, start.plusDays(6)))
        assertEquals(2, SchedulePolicy.weekAt(start, start.plusDays(7)))
        assertEquals(0, SchedulePolicy.weekAt(start, start.minusDays(1)))
    }

    @Test fun weekRangesPreserveGapsAndRemoveDuplicates() {
        assertEquals("1–2、4–7 周", SchedulePolicy.weeksLabel(listOf(7, 4, 2, 1, 5, 6, 4)))
        assertEquals("9–16 周", SchedulePolicy.weeksLabel((9..16).toList()))
    }
}
