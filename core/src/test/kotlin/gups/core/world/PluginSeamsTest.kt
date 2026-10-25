package gups.core.world

import gups.core.model.Actor
import gups.core.model.Effect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginSeamsTest {
    @Test
    fun `Effect Plugin reaches pluginEffects, also inside a sequence and without a target`() {
        val f = Fixture()
        val ann = f.player("Ann")
        val goblin = f.mob()
        val seen = mutableListOf<Triple<Effect.Plugin, Actor, Actor?>>()
        f.w.pluginEffects = PluginEffectRunner { w, e, caster, target ->
            assertSame(f.w, w)
            seen += Triple(e, caster, target)
        }
        val soak = Effect.Plugin("weather", "soak", mapOf("pulses" to 3L))
        f.w.applyEffect(soak, ann, goblin)
        f.w.applyEffect(Effect.Sequence(soak), ann, null)
        assertEquals(listOf(Triple(soak, ann as Actor, goblin as Actor?), Triple(soak, ann, null)), seen)
    }

    @Test
    fun `plugin commands may not shadow engine commands, directions or each other`() {
        val r = CommandRegistry()
        for (name in listOf("look", "north", "n")) {
            assertFailsWith<IllegalArgumentException>(name) { r.registerPlugin(FastCommand(name) {}) }
        }
        r.registerPlugin(FastCommand("forecast") {})
        assertFailsWith<IllegalArgumentException> { r.registerPlugin(FastCommand("forecast") {}) }
        assertFailsWith<IllegalArgumentException> { r.registerPlugin(FastCommand("weather", aliases = listOf("forecast")) {}) }
        assertFailsWith<IllegalArgumentException> { r.registerGlobal(FastCommand("forecast") {}) }
        assertTrue(r.isPluginCommand("forecast"))
    }

    @Test
    fun `clearGlobals keeps plugin commands, and they resolve`() {
        val f = Fixture()
        val ann = f.player("Ann")
        f.w.commands.registerPlugin(FastCommand("forecast") { it.world.send(it.actor, "Rain.") })
        f.w.commands.registerGlobal(FastCommand("quests") {})
        f.w.commands.clearGlobals()
        assertEquals(listOf("forecast"), f.w.commands.pluginCommands.map { it.name })
        assertEquals("forecast", f.w.commands.resolve(ann, "forecast")?.name)
        assertEquals("forecast", f.w.commands.resolve(ann, "forec")?.name)
    }
}
