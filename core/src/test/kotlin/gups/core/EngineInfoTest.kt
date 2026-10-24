package gups.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EngineInfoTest {
    @Test
    fun `api version is 1`() {
        assertEquals(1, EngineInfo.API_VERSION)
    }

    @Test
    fun `engine version is stamped at build time`() {
        assertNotEquals("unknown", EngineInfo.version)
        assertNotEquals("\${engineVersion}", EngineInfo.version)
    }

    @Test
    fun `time constants match the pulse cadence`() {
        assertEquals(2_000L, COMBAT_ROUND_PULSES * PULSE_MS)
        assertEquals(30_000L, WORLD_TICK_PULSES * PULSE_MS)
        assertEquals(3 * 60_000L, LINKDEAD_PULSES * PULSE_MS)
        assertEquals(30 * 60_000L, IDLE_QUIT_PULSES * PULSE_MS)
    }
}
