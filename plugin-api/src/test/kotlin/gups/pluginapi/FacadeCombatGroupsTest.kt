package gups.pluginapi

import gups.core.Label
import gups.core.model.Player
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.World
import gups.pluginapi.host.ActorFacade
import gups.pluginapi.host.WorldFacade
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FacadeCombatGroupsTest {
  private val w: World = world(files = COMBAT_FILES)
  private val f = WorldFacade(w, "weather") {}
  private val playerEntity: Player = w.spawnPlayer("Ann", Label("//zones/z:a"))
  private val player = ActorFacade(playerEntity, w)

  private val roomA: PluginRoom
    get() = f.room("//zones/z:a")!!

  private val roomB: PluginRoom
    get() = f.room("//zones/z:b")!!

  private val roomC: PluginRoom
    get() = f.room("//zones/z:c")!!

  private fun wolf(): PluginActor =
    ActorFacade(w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a"))), w)

  /** Two players, and a wolf bob has dealt 5 points of sourced damage to. */
  private class WoundedWolf(
    val facade: WorldFacade,
    val wolf: PluginActor,
    val ann: PluginActor,
    val bob: PluginActor,
  )

  /** A world with two players and a wolf, in which bob has wounded it for 5 points. */
  private fun woundedWolf(): WoundedWolf {
    val w2 = world(files = COMBAT_FILES)
    val f2 = WorldFacade(w2, "weather") {}
    val ann = ActorFacade(w2.spawnPlayer("Ann", Label("//zones/z:a")), w2)
    val bob = ActorFacade(w2.spawnPlayer("Bob", Label("//zones/z:a")), w2)
    val wolf = ActorFacade(w2.spawnMob(Label("//zones/z:wolf"), w2.room(Label("//zones/z:a"))), w2)
    f2.damage(wolf, 5, "fire", "hp", source = bob)
    return WoundedWolf(f2, wolf, ann, bob)
  }

  /** Kills the wolf with a sourced hit from ann; returns the XP bob and ann each earned. */
  private fun splitXp(w: WoundedWolf): Pair<Long, Long> {
    w.facade.damage(w.wolf, 100, "fire", "hp", source = w.ann)
    return (w.bob as ActorFacade).actor.xp to (w.ann as ActorFacade).actor.xp
  }

  @Test
  fun `startCombat makes each actor the other's fighting and primary target`() {
    val wolf = wolf()
    f.startCombat(player, wolf)
    assertEquals(listOf(wolf), player.fighting)
    assertEquals(listOf(player), wolf.fighting)
    assertEquals(wolf, player.primaryTarget)
    assertEquals(player, wolf.primaryTarget)
  }

  @Test
  fun `enemiesNear finds the opponent within the band, and a bad band throws`() {
    val wolf = wolf()
    f.startCombat(player, wolf)
    assertTrue(f.enemiesNear(player, "far").any { it.id == wolf.id })
    assertTrue(f.alliesNear(player, "far").none { it.id == wolf.id })
    val e = assertFailsWith<IllegalArgumentException> { f.enemiesNear(player, "close") }
    assertContains(e.message!!, "melee")
    assertContains(e.message!!, "near")
    assertContains(e.message!!, "far")
  }

  @Test
  fun `stopCombat clears both actors' fights`() {
    val wolf = wolf()
    f.startCombat(player, wolf)
    f.stopCombat(player)
    assertEquals(emptyList(), player.fighting)
    assertEquals(emptyList(), wolf.fighting)
    assertNull(player.primaryTarget)
    assertNull(wolf.primaryTarget)
  }

  @Test
  fun `startCombat refuses to fight itself or across rooms`() {
    val e = assertFailsWith<IllegalArgumentException> { f.startCombat(player, player) }
    assertContains(e.message!!, "can't fight itself")
    val wolfB = f.spawnMob("//zones/z:wolf", roomB).single()
    val e2 = assertFailsWith<IllegalArgumentException> { f.startCombat(player, wolfB) }
    assertContains(e2.message!!, "not in the same room")
  }

  @Test
  fun `the combat verbs and reads throw IllegalStateException with the combat feature off`() {
    val w2 =
      world(files = COMBAT_FILES, features = Features(Feature.entries.toSet() - Feature.COMBAT))
    val f2 = WorldFacade(w2, "weather") {}
    val p = ActorFacade(w2.spawnPlayer("Ann", Label("//zones/z:a")), w2)
    val wolf = ActorFacade(w2.spawnMob(Label("//zones/z:wolf"), w2.room(Label("//zones/z:a"))), w2)
    assertFailsWith<IllegalStateException> { f2.startCombat(p, wolf) }
    assertFailsWith<IllegalStateException> { f2.stopCombat(p, wolf) }
    assertFailsWith<IllegalStateException> { f2.enemiesNear(p, "far") }
    assertFailsWith<IllegalStateException> { f2.alliesNear(p, "far") }
    assertFailsWith<IllegalStateException> { p.fighting }
  }

  @Test
  fun `a death's experience split credits a sourced damage`() {
    val (bobXp, annXp) = splitXp(woundedWolf())
    assertEquals(2L, bobXp)
    assertEquals(48L, annXp)
  }

  @Test
  fun `clearContributions removes a contributor's entry before the kill`() {
    val w = woundedWolf()
    w.facade.clearContributions(w.wolf, w.bob)
    val (bobXp, annXp) = splitXp(w)
    assertEquals(0L, bobXp)
    assertEquals(50L, annXp)
  }

  @Test
  fun `clearContributions without a contributor empties the whole record`() {
    val w = woundedWolf()
    w.facade.clearContributions(w.wolf)
    val (bobXp, annXp) = splitXp(w)
    assertEquals(0L, bobXp)
    assertEquals(50L, annXp)
  }

  @Test
  fun `pathTo gives directions of the shortest walk, empty when already there and null when unreachable`() {
    assertEquals(listOf("north"), f.pathTo(roomA, roomB))
    assertEquals(emptyList<String>(), f.pathTo(roomA, roomA))
    assertNull(f.pathTo(roomA, roomC))
  }

  @Test
  fun `plan finds the cheapest action sequence that reaches the goal`() {
    val plan =
      f.plan(
        facts = mapOf("has_key" to false),
        actions =
          listOf(
            PluginPlanAction(
              name = "pickup_key",
              pre = mapOf("has_key" to false),
              effects = mapOf("has_key" to true),
            ),
            PluginPlanAction(
              name = "unlock_door",
              pre = mapOf("has_key" to true),
              effects = mapOf("door_open" to true),
            ),
          ),
        goal = mapOf("door_open" to true),
      )
    assertEquals(listOf("pickup_key", "unlock_door"), plan)
  }

  @Test
  fun `plan returns empty when the goal already holds and null when it is unreachable`() {
    assertEquals(
      emptyList<String>(),
      f.plan(mapOf("door_open" to true), emptyList(), mapOf("door_open" to true)),
    )
    assertNull(
      f.plan(
        mapOf("has_key" to false),
        listOf(PluginPlanAction(name = "pickup_key", effects = mapOf("has_key" to true))),
        mapOf("door_open" to true),
      )
    )
  }

  @Test
  fun `plan rejects a fact that is not a Boolean, Int, Long or String`() {
    val e =
      assertFailsWith<IllegalArgumentException> {
        f.plan(mapOf("x" to 1.5), emptyList(), mapOf("x" to true))
      }
    assertContains(e.message!!, "plan(facts): fact 'x' must be a Boolean, Int, Long or String")
  }

  @Test
  fun `plan lets the planner's own limit errors propagate`() {
    val e =
      assertFailsWith<IllegalArgumentException> {
        f.plan(mapOf("x" to true), emptyList(), mapOf("x" to false), maxExpansions = 0)
      }
    assertContains(e.message!!, "max_expansions")
  }

  @Test
  fun `group verbs manage a group of two players`() {
    val bob = ActorFacade(w.spawnPlayer("Bob", Label("//zones/z:a")), w)
    f.groupAdd(bob, player)
    val g = f.groupOf(player)!!
    assertEquals(1L, g.id)
    assertEquals(player, g.leader)
    assertEquals(listOf(player, bob), g.members)
    assertEquals(listOf(player, bob), f.groupMembers(bob))
    f.groupSetLeader(bob)
    assertEquals(bob, f.groupOf(player)!!.leader)
    assertTrue(f.alliesNear(player, "far").any { it.id == bob.id })
    f.groupRemove(player)
    assertNull(f.groupOf(player))
    assertNull(f.groupOf(bob))
  }

  @Test
  fun `groupDisband dissolves the group at once`() {
    val bob = ActorFacade(w.spawnPlayer("Bob", Label("//zones/z:a")), w)
    f.groupAdd(bob, player)
    f.groupDisband(player)
    assertNull(f.groupOf(player))
    assertNull(f.groupOf(bob))
  }

  @Test
  fun `groupAdd refuses a mob member`() {
    val wolf = wolf()
    val e = assertFailsWith<IllegalArgumentException> { f.groupAdd(wolf, player) }
    assertContains(e.message!!, "must be a player")
  }

  companion object {
    /** The fixture zone, plus a room with no exits so a walk to it is unreachable. */
    val COMBAT_FILES =
      ZONE +
        mapOf(
          "zones/z/rooms.star" to
            """
            room("a", name = "Glade", desc = "A glade.", exits = [exit(DIR.north, ":b")])
            room("b", name = "Cave", desc = "A cave.", exits = [exit("south", ":a")])
            room("c", name = "Isle", desc = "An isle.", flags = ["dead_end_ok"])
            mob("wolf", name = "a wolf", desc = "A wolf.", keywords = ["wolf"])
            item("stick", name = "a stick", desc = "A stick.", keywords = ["stick"])
            """
        )
  }
}
