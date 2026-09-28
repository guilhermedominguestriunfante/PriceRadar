package com.dedonervoso.core.online

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnlineServiceTest {
    @Test
    fun weeklyBoardsUseIsoWeeksInUtc() {
        assertEquals("2026-W40", OnlineService.isoWeek(Instant.parse("2026-09-28T00:00:00Z").toEpochMilli()))
        assertEquals("2026-W39", OnlineService.isoWeek(Instant.parse("2026-09-27T23:59:59Z").toEpochMilli()))
        assertEquals("2026-W53", OnlineService.isoWeek(Instant.parse("2026-12-31T10:00:00Z").toEpochMilli()))
        assertEquals("2026-W53", OnlineService.isoWeek(Instant.parse("2027-01-03T10:00:00Z").toEpochMilli()))
    }

    @Test
    fun friendCodesAreForgivingToType() {
        assertEquals("K7P3QX", OnlineService.normalizeCode(" k7p-3qx "))
        assertNull(OnlineService.normalizeCode("K7P3Q0"), "0 is not in the alphabet (looks like O)")
        assertNull(OnlineService.normalizeCode("K7P3Q"))
        assertEquals("K7P-3QX", OnlineService.formatCode("K7P3QX"))
    }
}
