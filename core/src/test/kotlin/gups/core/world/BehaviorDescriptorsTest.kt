package gups.core.world

import gups.core.model.Channel
import gups.core.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BehaviorDescriptorsTest {
  private val f = Fixture()
  private val noCombatFixture = Fixture(features = Features(emptySet()))

  @Test
  fun `round trip for all eleven behaviors`() {
    val behaviors: List<Behavior> =
      listOf(
        Wander(radius = 3, chance = 50, priority = 15),
        Aggressive(
          toward = ActorFilter("players", "//zones/forest:wolf_*"),
          unlessLevelAbove = 10,
          unlessLevelBelow = 2,
          chance = 80,
          via = listOf(Channel.SIGHT, Channel.SOUND),
          priority = 65,
        ),
        Aggressive(toward = ActorFilter("players")),
        Assist(allies = ActorFilter("//zones/caves:goblin_*"), priority = 55),
        Flee(below = mapOf("hp" to 25, "mana" to 10), priority = 105),
        Guard(exit = Direction.NORTH, allies = ActorFilter("//zones/caves:goblin_*", "mobs")),
        Follow(target = ActorFilter("players"), priority = 35),
        Pursue(target = ActorFilter("//zones/caves:boss"), priority = 45),
        Sentinel,
        Scavenge(priority = 8),
        Heel(priority = 32),
        Defend(group = true, priority = 52),
        Defend(group = false, priority = 50),
      )

    for (b in behaviors) {
      val descriptor = b.descriptor()
      val restored = f.w.behaviorOf(descriptor)
      assertEquals(b, restored, "Round-trip mismatch for $b")
    }
  }

  @Test
  fun `rejection on missing or unknown kind`() {
    assertFailsWith<IllegalArgumentException> { f.w.behaviorOf(emptyMap()) }
    assertFailsWith<IllegalArgumentException> { f.w.behaviorOf(mapOf("kind" to "teleport")) }
  }

  @Test
  fun `rejection on unknown key`() {
    assertFailsWith<IllegalArgumentException> {
      f.w.behaviorOf(mapOf("kind" to "wander", "radius" to 2L, "chance" to 10L, "extra" to "bad"))
    }
    assertFailsWith<IllegalArgumentException> {
      f.w.behaviorOf(mapOf("kind" to "sentinel", "priority" to 10L))
    }
  }

  @Test
  fun `rejection on wrong types`() {
    assertFailsWith<IllegalArgumentException> {
      f.w.behaviorOf(mapOf("kind" to "wander", "radius" to "2", "chance" to 10L))
    }
    assertFailsWith<IllegalArgumentException> {
      f.w.behaviorOf(mapOf("kind" to "heel", "priority" to "high"))
    }
    assertFailsWith<IllegalArgumentException> {
      f.w.behaviorOf(mapOf("kind" to "defend", "group" to "yes"))
    }
    assertFailsWith<IllegalArgumentException> {
      f.w.behaviorOf(mapOf("kind" to "guard", "exit" to 1L))
    }
    assertFailsWith<IllegalArgumentException> {
      f.w.behaviorOf(mapOf("kind" to "follow", "target" to "players"))
    }
    assertFailsWith<IllegalArgumentException> {
      f.w.behaviorOf(mapOf("kind" to "assist", "allies" to listOf(123L)))
    }
    assertFailsWith<IllegalArgumentException> {
      f.w.behaviorOf(mapOf("kind" to "flee", "below" to "low"))
    }
    assertFailsWith<IllegalArgumentException> {
      f.w.behaviorOf(mapOf("kind" to "flee", "below" to mapOf("hp" to 25))) // Int instead of Long
    }
  }

  @Test
  fun `rejection on package-relative or malformed label pattern`() {
    assertFailsWith<IllegalArgumentException> {
      f.w.behaviorOf(mapOf("kind" to "assist", "allies" to listOf(":goblin")))
    }
    assertFailsWith<IllegalArgumentException> {
      f.w.behaviorOf(mapOf("kind" to "follow", "target" to listOf("//zones/caves")))
    }
    assertFailsWith<IllegalArgumentException> {
      f.w.behaviorOf(mapOf("kind" to "pursue", "target" to listOf("wolf pack")))
    }
  }

  @Test
  fun `bare name patterns are accepted and round trip`() {
    val behaviors: List<Behavior> =
      listOf(
        Assist(allies = ActorFilter("goblin_*")),
        Follow(target = ActorFilter("wolf")),
        Aggressive(toward = ActorFilter("players", "rat*")),
        Guard(exit = Direction.SOUTH, allies = ActorFilter("guard_*")),
      )
    for (b in behaviors) assertEquals(
      b,
      f.w.behaviorOf(b.descriptor()),
      "Round-trip mismatch for $b",
    )
  }

  @Test
  fun `combat feature requirement`() {
    for (kind in listOf("defend", "aggressive", "assist", "flee")) {
      val desc =
        when (kind) {
          "defend" -> mapOf("kind" to "defend")
          "aggressive" -> mapOf("kind" to "aggressive")
          "assist" -> mapOf("kind" to "assist", "allies" to listOf("players"))
          "flee" -> mapOf("kind" to "flee", "below" to mapOf("hp" to 20L))
          else -> error("unreachable")
        }
      assertFailsWith<IllegalArgumentException>("behavior '$kind' should require combat feature") {
        noCombatFixture.w.behaviorOf(desc)
      }
    }

    // Non-combat behaviors should work without combat feature
    assertEquals(Heel(), noCombatFixture.w.behaviorOf(mapOf("kind" to "heel")))
    assertEquals(Sentinel, noCombatFixture.w.behaviorOf(mapOf("kind" to "sentinel")))
  }

  @Test
  fun `refreshOwnedBehaviors runs ownedBehaviorAdjusters in order`() {
    val ann = f.player("Ann")
    val wolf = f.mob(T.GOBLIN)
    f.w.ownedBehaviorAdjusters =
      listOf(
        OwnedBehaviorAdjuster { _, _, _, list -> list + Scavenge(priority = 10) },
        OwnedBehaviorAdjuster { _, _, _, list ->
          list.map { if (it is Heel) Heel(priority = 80) else it }
        },
      )

    f.w.setOwner(wolf, ann)
    val owned = wolf.ownedBehaviors!!
    assertEquals(Heel(priority = 80), owned.filterIsInstance<Heel>().first())
    assertEquals(Scavenge(priority = 10), owned.filterIsInstance<Scavenge>().first())
  }
}
