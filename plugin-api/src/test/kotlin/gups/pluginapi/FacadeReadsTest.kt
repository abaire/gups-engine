package gups.pluginapi

import gups.core.Label
import gups.core.model.Player
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.World
import gups.core.world.submit
import gups.pluginapi.host.ActorFacade
import gups.pluginapi.host.WorldFacade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The remaining actor and item reads: each returns what the `ctx` actor and item views return in
 * the same world, read through a scripted command that stores the `ctx` values in attributes.
 */
class FacadeReadsTest {
  private val w: World = world(files = READS_FILES)
  private val f = WorldFacade(w, "weather") {}
  private val playerEntity: Player = w.spawnPlayer("Ann", Label("//zones/z:a"))
  private val player = ActorFacade(playerEntity, w)
  private val wolf =
    ActorFacade(w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a"))), w)
  private val roomA: PluginRoom
    get() = f.room("//zones/z:a")!!

  /**
   * Gives [a] a sword and a no_regen flag, runs the `record` command as [a], and returns the `ctx`
   * values the command stored for it.
   */
  private fun recordReads(a: PluginActor): Map<String, Any?> {
    f.spawnItem("//zones/z:sword", a)
    f.applyAffect(a, "cursed", 100, flags = setOf("no_regen"))
    w.submit((a as ActorFacade).actor, "record")
    w.runPulse()
    return READ_KEYS.associateWith { f.getAttr(a, "lib:$it") }
  }

  /** Asserts every actor read matches the `ctx` value [ctx] stored for [a]. */
  private fun assertReads(a: PluginActor, ctx: Map<String, Any?>) {
    assertEquals(a.condition, ctx["condition"])
    assertEquals(a.senses.mapValues { it.value.toLong() }, ctx["senses"])
    assertEquals(a.slots, ctx["slots"])
    assertEquals(a.carriedWeight.toLong(), ctx["carried_weight"])
    assertEquals(a.hasItem("//zones/z:sword"), ctx["has_item"])
    assertEquals(a.hasFlag("no_regen"), ctx["has_flag"])
    assertEquals(a.waitState.toLong(), ctx["wait_state"])
    assertEquals(a.movePace.toLong(), ctx["move_pace"])
    assertEquals(a.recallRoom?.label, ctx["recall_room"])
    assertEquals(a.xp, ctx["xp"])
    assertEquals(a.inWorld, ctx["in_world"])
  }

  @Test
  fun `reads match ctx for a player`() {
    assertReads(player, recordReads(player))
    assertEquals("//zones/z:a", player.recallRoom?.label)
  }

  @Test
  fun `reads match ctx for a mob, whose recall room is none`() {
    assertReads(wolf, recordReads(wolf))
    assertNull(wolf.recallRoom)
  }

  @Test
  fun `inWorld is false after the mob dies`() {
    val m = ActorFacade(w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a"))), w)
    assertTrue(m.inWorld)
    f.damage(m, 1000, "fire", "hp")
    w.runPulse()
    assertFalse(m.inWorld)
  }

  @Test
  fun `xp throws IllegalStateException with leveling off`() {
    val w2 =
      world(files = READS_FILES, features = Features(Feature.entries.toSet() - Feature.LEVELING))
    val f2 = WorldFacade(w2, "weather") {}
    val p = ActorFacade(w2.spawnPlayer("Ann", Label("//zones/z:a")), w2)
    assertFailsWith<IllegalStateException> { p.xp }
  }

  @Test
  fun `item reads return the prototype values`() {
    val sword = f.spawnItem("//zones/z:sword", roomA).single()
    assertEquals("1d6", sword.dice)
    assertEquals("slash", sword.damageType)
    val cloak = f.spawnItem("//zones/z:cloak", roomA).single()
    assertEquals(mapOf("armor" to 2), cloak.statDeltas)
    assertEquals(mapOf("sight" to 1), cloak.senseDeltas)
  }

  companion object {
    val READ_KEYS =
      listOf(
        "condition",
        "senses",
        "slots",
        "carried_weight",
        "has_item",
        "has_flag",
        "wait_state",
        "move_pace",
        "recall_room",
        "xp",
        "in_world",
      )

    val READS_FILES =
      mapOf(
        "zones/z/package.star" to "package(api_version = 1, kind = \"zone\", title = \"Z\")",
        "zones/z/rooms.star" to
          """
          room("a", name = "Glade", desc = "A glade.", exits = [exit(DIR.north, ":b")])
          room("b", name = "Cave", desc = "A cave.", flags = ["dead_end_ok"], exits = [exit("south", ":a")])
          mob("wolf", name = "a wolf", keywords = ["wolf"])
          item("stick", name = "a stick", keywords = ["stick"])
          item("sword", name = "a sword", keywords = ["sword"], kind = "melee", wear = "wield", dice = "1d6", damage_type = "slash")
          item("cloak", name = "a cloak", keywords = ["cloak"], wear = "body", stat_deltas = stats(armor = 2), sense_deltas = {"sight": 1})
          """,
        "lib/package.star" to "package(api_version = 1, kind = \"lib\")",
        "lib/cmds.star" to
          """
          def _record(ctx):
              a = ctx.actor
              ctx.set_attr(a, "condition", a.condition)
              ctx.set_attr(a, "senses", a.senses)
              ctx.set_attr(a, "slots", a.slots)
              ctx.set_attr(a, "carried_weight", a.carried_weight)
              ctx.set_attr(a, "has_item", a.has_item("//zones/z:sword"))
              ctx.set_attr(a, "has_flag", a.has_flag("no_regen"))
              ctx.set_attr(a, "wait_state", a.wait_state)
              ctx.set_attr(a, "move_pace", a.move_pace)
              ctx.set_attr(a, "recall_room", a.recall_room)
              ctx.set_attr(a, "xp", a.xp)
              ctx.set_attr(a, "in_world", a.in_world)

          global_command("record", _record, lane = "tick")
          """,
      )
  }
}
