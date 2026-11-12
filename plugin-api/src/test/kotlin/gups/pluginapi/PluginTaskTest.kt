package gups.pluginapi

import gups.core.CapturingSink
import gups.core.Label
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.world.AffectSpec
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.World
import gups.core.world.applyAffect
import gups.core.world.attach
import gups.core.world.damage
import gups.core.world.handle
import gups.core.world.spawnItem
import gups.core.world.submit
import gups.pluginapi.host.ActorFacade
import gups.pluginapi.host.ItemFacade
import gups.pluginapi.host.WorldFacade
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `PluginWorld.startTask`, `stopTask`, `skillRoll`, `setItemName` and `PluginActor.task`. */
class PluginTaskTest {
  private val files =
    ZONE +
      mapOf(
        "lib/package.star" to "package(api_version = 1, kind = \"lib\")",
        "lib/tasks.star" to
          """
            def _halt(ctx):
                t = ctx.actor.task
                ctx.send(ctx.actor, "HALT:%s:%s" % (t.name if t else None, ctx.stop_task(ctx.actor)))

            global_command("halt", _halt, lane = "fast")
          """,
        "zones/z/skills.star" to "skill(\"smith\", kind = \"craft\")",
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(),
      )

  private class Scene(val w: World, val ann: Player, val sink: CapturingSink) {
    val facade = WorldFacade(w, "test") {}
    val actor: PluginActor
      get() = ActorFacade(ann, w)
  }

  private fun scene(at: String = "//zones/z:a"): Scene {
    val w = world(files)
    val ann = w.spawnPlayer("Ann", Label(at))
    val sink = CapturingSink()
    w.handle(WorldControlEvent.Connect(SessionId(1), sink))
    w.attach(SessionId(1), ann)
    return Scene(w, ann, sink)
  }

  private val ends = mutableListOf<PluginTaskEnd>()
  private val dones = mutableListOf<PluginTaskCall>()

  private fun Scene.start(pulses: Int = 3, lag: Int = 2, onDone: (PluginTaskCall) -> Unit = {}) =
    facade.startTask(
      actor,
      pulses,
      "weaving",
      lag = lag,
      onStop = { ends += it.reason },
      onDone = {
        dones += it
        onDone(it)
      },
    )

  @Test
  fun `a task completes after its pulses, then its lag applies`() {
    val s = scene()
    assertNull(s.start())
    assertTrue(s.actor.isBusy)
    assertEquals("weaving", s.actor.task?.name)
    assertEquals(3L, s.actor.task?.remaining)
    repeat(2) { s.w.runPulse() }
    assertTrue(dones.isEmpty())
    s.w.runPulse()
    assertEquals(1, dones.size)
    assertEquals("Ann", dones.single().actor.name)
    assertEquals("weaving", dones.single().task.name)
    assertFalse(s.actor.isBusy)
    assertNull(s.actor.task)
    assertEquals(2, s.ann.waitState)
  }

  @Test
  fun `stop, a stun and death end it with their reasons`() {
    val s = scene()
    s.start()
    s.w.submit(s.ann, "stop")
    s.w.runPulse()
    assertContains(s.sink.drain(), "You stop weaving.")
    assertEquals(listOf(PluginTaskEnd.CANCELLED), ends)

    s.start()
    s.w.applyAffect(s.ann, AffectSpec("stun", 2, flags = setOf("stunned")))
    assertEquals(PluginTaskEnd.INTERRUPTED, ends.last())
    repeat(6) { s.w.runPulse() }

    s.start(lag = 0)
    s.w.damage(s.ann, mapOf("hp" to 10_000), gups.core.model.DamageType("slash"))
    s.w.runPulse()
    assertEquals(PluginTaskEnd.GONE, ends.last())
  }

  @Test
  fun `a busy actor is refused`() {
    val s = scene()
    s.start()
    assertEquals("You're busy.", s.start())
  }

  @Test
  fun `a throwing callback is a plugin diagnostic and the world goes on`() {
    val s = scene()
    s.start { error("boom") }
    repeat(3) { s.w.runPulse() }
    assertTrue(s.w.diagnostics.any { it.kind == "plugin" && "boom" in it.message })
    assertFalse(s.actor.isBusy)
  }

  @Test
  fun `a script stop_task cancels a plugin task, and the plugin's stopTask a script one`() {
    val s = scene()
    s.start()
    s.w.submit(s.ann, "halt")
    s.w.runPulse()
    assertContains(s.sink.drain(), "HALT:weaving:True")
    assertEquals(listOf(PluginTaskEnd.CANCELLED), ends)
    assertFalse(s.facade.stopTask(s.actor), "nothing left to stop")
  }

  @Test
  fun `bad arguments throw`() {
    val s = scene()
    assertFailsWith<IllegalArgumentException> { s.facade.startTask(s.actor, 0, "x") {} }
    assertFailsWith<IllegalArgumentException> { s.facade.startTask(s.actor, 1, "a\nb") {} }
    assertFailsWith<IllegalArgumentException> {
      s.facade.startTask(s.actor, 1, "x", interrupt = PluginTaskInterrupt(damagePct = 0)) {}
    }
  }

  @Test
  fun `skillRoll rolls and needs skills`() {
    val s = scene()
    val r = s.facade.skillRoll(s.actor, "//zones/z:smith", adjust = 5)
    // Test worlds pin skill_check and skill_power to 100.
    assertEquals(PluginSkillRoll(105, null, true, 100), r)
    val off = world(ZONE, features = Features(Feature.entries.toSet() - Feature.SKILLS))
    val bo = off.spawnPlayer("Bo", Label("//zones/z:a"))
    val e =
      assertFailsWith<IllegalStateException> {
        WorldFacade(off, "test") {}.skillRoll(ActorFacade(bo, off), "//zones/z:smith")
      }
    assertContains(e.message.orEmpty(), "skills")
  }

  @Test
  fun `setItemName renames an item`() {
    val s = scene()
    val stick = s.w.spawnItem(Label("//zones/z:stick"), ItemLocation.Carried(s.ann.id))
    assertTrue(s.facade.setItemName(ItemFacade(stick, s.w), "a carved stick"))
    assertEquals("a carved stick", stick.name)
    assertFailsWith<IllegalArgumentException> {
      s.facade.setItemName(ItemFacade(stick, s.w), "x".repeat(81))
    }
  }

  @Test
  fun `itemInfo reads a prototype by label`() {
    val s = scene()
    val info = s.facade.itemInfo("//zones/z:stick")!!
    assertEquals("a stick", info.name)
    assertEquals(listOf("stick", "wood"), info.keywords)
    assertEquals(false, info.stackable)
    assertNull(info.charges)
    assertNull(s.facade.itemInfo("//zones/z:nope"))
    assertNull(s.facade.itemInfo("not a label"))
  }
}
