package gups.core.world

import gups.core.Label
import gups.core.model.Direction
import gups.core.model.MobProto
import gups.core.model.StandingHolder
import gups.core.model.StandingSubject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BehaviorDispositionTest {
  private fun mob(name: String, vararg behaviors: Behavior, hp: Int = 40) =
    MobProto(
      Label("//t:$name"),
      name,
      listOf(name),
      vitals = mapOf("hp" to hp),
      behaviors = behaviors.toList(),
    )

  private fun fixture(vararg mobs: MobProto, rules: FixedRules = FixedRules(flee = 100)) =
    Fixture(rules = rules, protos = T.protos(extraMobs = mobs.toList()))

  @Test
  fun `value equality distinguishes standing thresholds`() {
    assertNotEquals(
      Aggressive(ActorFilter("players"), dispositionBelow = -40),
      Aggressive(ActorFilter("players"), dispositionBelow = -50),
    )
    assertNotEquals(
      Assist(ActorFilter("mobs"), dispositionAbove = 10),
      Assist(ActorFilter("mobs"), dispositionAbove = 20),
    )
    assertNotEquals(
      Guard(Direction.NORTH, dispositionAbove = 10),
      Guard(Direction.NORTH, dispositionAbove = 20),
    )
    assertNotEquals(
      Follow(ActorFilter("players"), dispositionAbove = 10),
      Follow(ActorFilter("players"), dispositionAbove = 20),
    )
    assertNotEquals(
      Pursue(ActorFilter("players"), dispositionBelow = -10),
      Pursue(ActorFilter("players"), dispositionBelow = -20),
    )
  }

  @Test
  fun `aggressive with disposition_below attacks only candidates strictly below threshold`() {
    val f = fixture(mob("orc", Aggressive(ActorFilter("players"), dispositionBelow = -40)))
    val orc = f.mob(Label("//t:orc"))
    val neutral = f.player("Ann")
    f.runTo(3)
    assertFalse(orc.isEngaged, "neutral player (standing 0) is spared")

    val enemy = f.player("Bob")
    f.w.applyStanding(StandingHolder.Personal(orc), StandingSubject.Of(enemy.id), -50, "hate")
    f.runTo(6)
    assertTrue(orc.isEngaged)
    assertEquals(enemy.id, orc.primaryTarget)
  }

  @Test
  fun `assist with disposition_above requires ally above threshold and foe not above threshold`() {
    val f = fixture(mob("guard", Assist(ActorFilter("mobs"), dispositionAbove = 20)))
    val guard = f.mob(Label("//t:guard"))
    val ally = f.mob(Label("//t:guard"))
    val enemy = f.player("Bob")

    // Ally engages Bob
    f.w.startCombat(enemy, ally)
    f.runTo(3)
    assertFalse(guard.isEngaged, "ally has standing 0 <= 20, guard does not assist")

    // Ally gets high standing from guard
    f.w.applyStanding(StandingHolder.Personal(guard), StandingSubject.Of(ally.id), 50, "ally")
    // But enemy is also liked (standing 30 > 20)
    f.w.applyStanding(StandingHolder.Personal(guard), StandingSubject.Of(enemy.id), 30, "friend")
    f.runTo(6)
    assertFalse(guard.isEngaged, "foe is liked above threshold, guard will not attack friend")

    // Clear opinion of enemy so standing is 0 <= 20
    f.w.clearStanding(StandingHolder.Personal(guard), StandingSubject.Of(enemy.id))
    f.runTo(12)
    assertTrue(guard.isEngaged, "guard assists ally against foe")
    assertEquals(enemy.id, guard.primaryTarget)
  }

  @Test
  fun `guard with disposition_above allows well-liked actor through`() {
    val f =
      fixture(
        mob("bouncer", Guard(Direction.NORTH, allies = ActorFilter("mobs"), dispositionAbove = 50))
      )
    val bouncer = f.mob(Label("//t:bouncer"))
    val player = f.player("Alice")

    // Initially neutral (standing 0), player is blocked
    val denial1 = f.w.guardDenial(player, bouncer.room, Direction.NORTH)
    assertTrue(denial1 != null && "Bouncer" in denial1)

    // Player standing is raised to 60 > 50
    f.w.applyStanding(
      StandingHolder.Personal(bouncer),
      StandingSubject.Of(player.id),
      60,
      "favored",
    )
    val denial2 = f.w.guardDenial(player, bouncer.room, Direction.NORTH)
    assertNull(denial2, "player above standing threshold is allowed through")
  }

  @Test
  fun `follow with disposition_above picks and drops leader based on threshold`() {
    val f = fixture(mob("dog", Follow(ActorFilter("players"), dispositionAbove = 25)))
    val dog = f.mob(Label("//t:dog"))
    val player = f.player("Alice")

    f.runTo(3)
    assertNull(dog.leader, "player has standing 0 <= 25, not followed")

    // Raise standing to 40 > 25
    f.w.applyStanding(StandingHolder.Personal(dog), StandingSubject.Of(player.id), 40, "like")
    f.runTo(6)
    assertEquals(player.id, dog.leader, "player with standing > 25 is chosen as leader")

    // Drop standing to 10 <= 25
    f.w.applyStanding(StandingHolder.Personal(dog), StandingSubject.Of(player.id), -30, "dislike")
    f.runTo(9)
    assertNull(dog.leader, "leader dropped when standing drops below threshold")
  }

  @Test
  fun `pursue with disposition_below heads for target strictly below threshold`() {
    val f = fixture(mob("hunter", Pursue(ActorFilter("players"), dispositionBelow = -20)))
    val hunter = f.mob(Label("//t:hunter"))
    // Move hunter to yard
    f.w.teleport(hunter, f.w.room(T.YARD))

    val player = f.player("Alice")
    f.runTo(3)
    assertEquals(T.YARD, hunter.room.label, "neutral player not pursued")

    // Make hunter hate player
    f.w.applyStanding(StandingHolder.Personal(hunter), StandingSubject.Of(player.id), -50, "hate")
    f.runTo(6)
    assertEquals(T.HALL, hunter.room.label, "hunter pursues hated player")
  }
}
