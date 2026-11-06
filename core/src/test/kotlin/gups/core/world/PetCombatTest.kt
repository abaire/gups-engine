package gups.core.world

import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class PetCombatTest(private val fs: FixtureSchema) {
  init {
    FixtureSchemas.current = fs
  }

  @BeforeEach
  fun useSchema() {
    FixtureSchemas.current = fs
  }

  @AfterEach
  fun resetSchema() {
    FixtureSchemas.current = FixtureSchemas.FANTASY
  }

  private class DisengageRecorder {
    val host = RecordingHost()
    val events = mutableListOf<Pair<String, String>>()

    init {
      host.handlers[HookName.ON_DISENGAGE] = { _, owner, ctx ->
        if (owner === ctx.actor) events += ctx.actor.name to (ctx.event ?: "")
        null
      }
    }
  }

  @Test
  fun `a pet engaging a mob engages the owner in the same room, victim primary target stays pet`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val wolf = f.mob(T.GOBLIN)
    f.w.setOwner(wolf, ann)
    val dummy = f.mob(T.DUMMY)

    f.w.startCombat(wolf, dummy)

    assertTrue(ann.isEngaged, "the owner is engaged")
    assertEquals(setOf(dummy.id), ann.fighting)
    assertEquals(setOf(wolf.id, ann.id), dummy.fighting)
    assertEquals(setOf(dummy.id), wolf.fighting)
    assertEquals(wolf.id, dummy.primaryTarget, "victim's primary target stays the pet")
    assertEquals(dummy.id, ann.primaryTarget)
    assertEquals(dummy.id, wolf.primaryTarget)
  }

  @Test
  fun `an owner in another room is not engaged`() {
    val f = Fixture()
    val ann = f.player("Ann", at = T.YARD)
    val wolf = f.mob(T.GOBLIN, at = T.HALL)
    f.w.setOwner(wolf, ann)
    val dummy = f.mob(T.DUMMY, at = T.HALL)

    f.w.startCombat(wolf, dummy)

    assertTrue(wolf.isEngaged)
    assertTrue(dummy.isEngaged)
    assertFalse(ann.isEngaged, "the owner in another room is not engaged")
    assertTrue(ann.fighting.isEmpty())
  }

  @Test
  fun `a pet engaging a member of the owner's group engages the owner with that member`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val bob = f.player("Bob")
    f.w.groupAdd(bob, ann)
    val wolf = f.mob(T.GOBLIN)
    f.w.setOwner(wolf, ann)

    f.w.startCombat(wolf, bob)

    assertTrue(ann.isEngaged)
    assertTrue(bob.id in ann.fighting)
    assertTrue(ann.id in bob.fighting)
  }

  @Test
  fun `a pet attacking another player engages its owner with that player`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val bob = f.player("Bob")
    val wolf = f.mob(T.GOBLIN)
    f.w.setOwner(wolf, ann)

    f.w.startCombat(wolf, bob)

    assertTrue(ann.isEngaged)
    assertTrue(bob.id in ann.fighting)
    assertTrue(ann.id in bob.fighting)
  }

  @Test
  fun `player 1 pet fighting player 2 pet engages both owners with opposing pet and each other`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val bob = f.player("Bob")
    val wolfA = f.mob(T.GOBLIN)
    val wolfB = f.mob(T.GOBLIN)
    f.w.setOwner(wolfA, ann)
    f.w.setOwner(wolfB, bob)

    f.w.startCombat(wolfA, wolfB)

    assertEquals(setOf(wolfB.id, bob.id), ann.fighting)
    assertEquals(setOf(wolfA.id, ann.id), bob.fighting)
    assertEquals(setOf(wolfB.id, bob.id), wolfA.fighting)
    assertEquals(setOf(wolfA.id, ann.id), wolfB.fighting)
    assertTrue(bob.id in ann.fighting)
    assertTrue(ann.id in bob.fighting)
  }

  @Test
  fun `a pet attacking its own owner does not loop and does not engage the owner with itself`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val wolf = f.mob(T.GOBLIN)
    f.w.setOwner(wolf, ann)

    f.w.startCombat(wolf, ann)

    assertEquals(setOf(ann.id), wolf.fighting)
    assertEquals(setOf(wolf.id), ann.fighting)
    assertFalse(ann.id in ann.fighting, "owner is not engaged with itself")
  }

  @Test
  fun `exploit - pet and owner engaged with goblin, owner flees disengaging pet with flee`() {
    val r = DisengageRecorder()
    val f = Fixture(rules = FixedRules(flee = 100), scripts = r.host)
    val ann = f.player("Ann")
    val wolf = f.mob(T.GOBLIN)
    f.w.setOwner(wolf, ann)
    val dummy = f.mob(T.DUMMY)

    f.w.startCombat(wolf, dummy)
    assertTrue(ann.isEngaged)
    assertTrue(wolf.isEngaged)

    f.input(ann, "flee")
    f.run(2)

    assertFalse(wolf.isEngaged, "pet is disengaged after owner flees")
    assertFalse(dummy.isEngaged, "opponent is disengaged")
    assertTrue(r.events.contains(wolf.name to "flee"), "pet gets flee disengage event")
  }

  @Test
  fun `exploit - pet and owner engaged with goblin, owner stop_combat disengages pet with stop`() {
    val r = DisengageRecorder()
    val f = Fixture(scripts = r.host)
    val ann = f.player("Ann")
    val wolf = f.mob(T.GOBLIN)
    f.w.setOwner(wolf, ann)
    val dummy = f.mob(T.DUMMY)

    f.w.startCombat(wolf, dummy)
    f.w.stopCombat(ann, dummy)

    assertFalse(wolf.isEngaged, "pet is disengaged after owner stops combat")
    assertFalse(dummy.isEngaged, "opponent is disengaged")
    assertTrue(r.events.contains(wolf.name to "stop"), "pet gets stop disengage event")
  }

  @Test
  fun `exploit - pet and owner engaged with goblin, owner walks out disengages pet with stop`() {
    val r = DisengageRecorder()
    val f = Fixture(scripts = r.host)
    val ann = f.player("Ann")
    val wolf = f.mob(T.GOBLIN)
    f.w.setOwner(wolf, ann)
    val dummy = f.mob(T.DUMMY)

    f.w.startCombat(wolf, dummy)
    val exit = f.w.room(T.HALL).exits[Direction.NORTH]!!
    f.w.transition(ann, exit)

    assertFalse(wolf.isEngaged, "pet is disengaged after owner leaves room")
    assertFalse(dummy.isEngaged, "opponent is disengaged")
    assertTrue(r.events.contains(wolf.name to "stop"), "pet gets stop disengage event")
  }

  @Test
  fun `owner dies causes pets to disengage with death event`() {
    val r = DisengageRecorder()
    val f = Fixture(scripts = r.host)
    val ann = f.player("Ann")
    val wolf = f.mob(T.GOBLIN)
    f.w.setOwner(wolf, ann)
    val dummy = f.mob(T.DUMMY)

    f.w.startCombat(wolf, dummy)
    f.hurt(ann, ann.current(fs.health) + 1, dummy)

    assertFalse(wolf.isEngaged, "pet is disengaged after owner dies")
    assertTrue(r.events.contains(wolf.name to "death"), "pet gets death disengage event")
  }

  @Test
  fun `a pet attacked while its owner is in another room stays engaged and fights`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val ann = f.player("Ann", at = T.YARD)
    val wolf = f.mob(T.GOBLIN, at = T.HALL)
    f.w.setOwner(wolf, ann)
    val dummy = f.mob(T.DUMMY, at = T.HALL)

    f.w.startCombat(dummy, wolf)

    assertTrue(wolf.isEngaged)
    assertTrue(dummy.isEngaged)
    assertFalse(ann.isEngaged)
    assertEquals(setOf(dummy.id), wolf.fighting)
    assertEquals(setOf(wolf.id), dummy.fighting)

    val initialHp = dummy.current(fs.health)
    f.runTo(16)
    assertTrue(dummy.current(fs.health) < initialHp, "pet auto-attacks when engaged")
  }
}
