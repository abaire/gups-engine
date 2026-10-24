package gups.script

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldModuleTest {
    private fun read(text: String?) = WorldModule.read(WorldDir(tempWorld(if (text == null) emptyMap() else mapOf("MODULE.gups" to text))))

    @Test
    fun `checkpoint interval defaults to 30 minutes and can be set`() {
        val (none, noError) = read(null)
        assertNull(noError)
        assertEquals(30, none.checkpointMinutes)
        assertEquals(7200, none.checkpointPulses)
        val (set, _) = read("module(api_version = 1, engine = \"9.9.9\", checkpoint_minutes = 5)")
        assertEquals(1200, set.checkpointPulses)
        assertTrue(set.problems().isEmpty())
        assertContains(set.warnings().single(), "pins engine 9.9.9")
    }

    @Test
    fun `bad metadata is reported`() {
        assertContains(assertNotNull(read("module(chekpoint_minutes = 5)").second).message, "did you mean 'checkpoint_minutes'")
        assertContains(assertNotNull(read("module(checkpoint_minutes = 0)").second).message, "at least 1")
        assertContains(assertNotNull(read("x = 1").second).message, "must call module(")
        assertContains(read("module(api_version = 2)").first.problems().single(), "pins api_version 2")
    }
}
