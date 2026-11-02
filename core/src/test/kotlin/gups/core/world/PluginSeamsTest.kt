package gups.core.world

import gups.core.Label
import gups.core.Role
import gups.core.model.Actor
import gups.core.model.Effect
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Prototypes
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.TargetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginSeamsTest {
  private data class SeenPluginEffect(
    val effect: Effect.Plugin,
    val caster: Actor,
    val target: Actor?,
    val targetItem: Item?,
  )

  @Test
  fun `Effect Plugin reaches pluginEffects, also inside a sequence and without a target`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val goblin = f.mob()
    val seen = mutableListOf<Triple<Effect.Plugin, Actor, Actor?>>()
    f.w.pluginEffects = PluginEffectRunner { w, e, caster, target, _ ->
      assertSame(f.w, w)
      seen += Triple(e, caster, target)
    }
    val soak = Effect.Plugin("weather", "soak", mapOf("pulses" to 3L))
    f.w.applyEffect(soak, ann, goblin)
    f.w.applyEffect(Effect.Sequence(soak), ann, null)
    assertEquals(
      listOf(Triple(soak, ann as Actor, goblin as Actor?), Triple(soak, ann, null)),
      seen,
    )
  }

  @Test
  fun `item skill with Effect Plugin passes targetItem and runs once even with room range`() {
    val polish = Effect.Plugin("sample", "polish", emptyMap())
    val skill =
      SkillProto(
        Label("//t:polish"),
        kind = "spell",
        target = TargetKind.ITEM,
        range = SkillRange.ROOM,
        effect = polish,
      )
    val f = Fixture(protos = Prototypes(T.rooms, T.mobs, T.items, T.skills + skill))
    val ann = f.player("Ann")
    f.mob(T.DUMMY, index = 2)
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Carried(ann.id))
    val seen = mutableListOf<SeenPluginEffect>()
    f.w.pluginEffects = PluginEffectRunner { _, e, caster, target, targetItem ->
      seen += SeenPluginEffect(e, caster, target, targetItem)
    }
    ann.skills[skill.label] = 100
    f.input(ann, "cast polish sword")
    f.run()
    assertEquals(listOf(SeenPluginEffect(polish, ann, null, sword)), seen)
  }

  @Test
  fun `plugin commands may not shadow engine commands, directions or each other`() {
    val r = CommandRegistry()
    for (name in listOf("look", "north", "n")) {
      assertFailsWith<IllegalArgumentException>(name) { r.registerPlugin(FastCommand(name) {}) }
    }
    r.registerPlugin(FastCommand("forecast") {})
    assertFailsWith<IllegalArgumentException> { r.registerPlugin(FastCommand("forecast") {}) }
    assertFailsWith<IllegalArgumentException> {
      r.registerPlugin(FastCommand("weather", aliases = listOf("forecast")) {})
    }
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

  @Test
  fun `help lists plugin commands the actor may use`() {
    val f = Fixture()
    val ann = f.player("Ann")
    f.w.commands.registerPlugin(FastCommand("forecast") {})
    f.w.commands.registerPlugin(TickCommand("rake") { null })
    f.w.commands.registerPlugin(FastCommand("storm", role = Role.BUILDER) {})
    f.out(ann)
    f.input(ann, "help")
    val out = f.out(ann)
    assertTrue("forecast" in out, out)
    assertTrue("rake" in out, out)
    assertTrue("storm" !in out, out)
  }
}
