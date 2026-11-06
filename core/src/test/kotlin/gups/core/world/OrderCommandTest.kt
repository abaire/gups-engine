package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Condition
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class OrderCommandTest(private val fs: FixtureSchema) {
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

  private val wolfProto =
    MobProto(
      Label("//t:wolf"),
      "a wolf",
      listOf("wolf"),
      level = 1,
      vitals = mapOf(fs.health to 50, fs.energy to 50),
    )

  @Test
  fun `order wolf kill goblin queues the kill, touches neither player queue nor waitState, engages owner on landing`() {
    val f = Fixture(protos = Prototypes(T.rooms, T.mobs(fs) + wolfProto, T.items, T.skills(fs)))
    val ann = f.player("Ann")
    val wolf = f.w.spawnMob(wolfProto.label, ann.room)
    f.w.setOwner(wolf, ann)
    val goblin = f.mob(T.GOBLIN)

    f.input(ann, "order wolf kill goblin")
    assertEquals(1, wolf.actionQueue.size)
    assertEquals(ann.id, wolf.actionQueue.first().orderedBy)
    assertTrue(ann.actionQueue.isEmpty())
    assertEquals(0, ann.waitState)
    assertContains(f.out(ann), "You order a wolf to 'kill goblin'.")

    f.run()
    assertTrue(wolf.isEngaged)
    assertTrue(ann.isEngaged, "owner is engaged when pet attack lands")
    assertTrue(goblin.id in ann.fighting)
  }

  @Test
  fun `order pets orders two pets in ascending id, skips pet in another room, refuses another player's pet`() {
    val f = Fixture(protos = Prototypes(T.rooms, T.mobs(fs) + wolfProto, T.items, T.skills(fs)))
    val ann = f.player("Ann", at = T.HALL)
    val bob = f.player("Bob", at = T.HALL)
    val dummy = f.mob(T.DUMMY, at = T.HALL)

    val wolf1 = f.w.spawnMob(wolfProto.label, ann.room, index = 1)
    f.w.setOwner(wolf1, ann)
    val wolf2 = f.w.spawnMob(wolfProto.label, ann.room, index = 2)
    f.w.setOwner(wolf2, ann)
    val wolf3 = f.w.spawnMob(wolfProto.label, f.w.room(T.YARD), index = 3)
    f.w.setOwner(wolf3, ann)
    val bobWolf = f.w.spawnMob(wolfProto.label, ann.room, index = 4)
    f.w.setOwner(bobWolf, bob)

    f.input(ann, "order pets kill dummy")

    assertEquals(1, wolf1.actionQueue.size)
    assertEquals(1, wolf2.actionQueue.size)
    assertEquals(0, wolf3.actionQueue.size, "pet in another room is not ordered")
    assertEquals(0, bobWolf.actionQueue.size, "another player's pet is not ordered")

    f.w.clearOwner(wolf1)
    f.w.clearOwner(wolf2)
    f.w.clearOwner(wolf3)
    f.input(ann, "order wolf kill dummy")
    assertContains(f.out(ann), "You have no pet here called wolf.")
    f.input(ann, "order pets kill dummy")
    assertContains(f.out(ann), "You have no pets here.")
  }

  @Test
  fun `before_order hook returning deny blocks the order`() {
    val host = RecordingHost()
    host.handlers[HookName.BEFORE_ORDER] = { _, _, _ -> HookOutcome.Deny("The wolf ignores you.") }
    val f =
      Fixture(
        scripts = host,
        protos = Prototypes(T.rooms, T.mobs(fs) + wolfProto, T.items, T.skills(fs)),
      )
    val ann = f.player("Ann")
    val wolf = f.w.spawnMob(wolfProto.label, ann.room)
    f.w.setOwner(wolf, ann)

    f.input(ann, "order wolf score")
    f.run()
    assertEquals(0, wolf.actionQueue.size)
    assertContains(f.out(ann), "The wolf ignores you.")
  }

  @Test
  fun `plugin ORDER restriction blocks the order`() {
    val f = Fixture(protos = Prototypes(T.rooms, T.mobs(fs) + wolfProto, T.items, T.skills(fs)))
    f.w.restrictions =
      mapOf(
        RestrictionPoint.ORDER to listOf(Restriction { _, _ -> "A magic seal prevents orders." })
      )
    val ann = f.player("Ann")
    val wolf = f.w.spawnMob(wolfProto.label, ann.room)
    f.w.setOwner(wolf, ann)

    f.input(ann, "order wolf score")
    f.run()
    assertEquals(0, wolf.actionQueue.size)
    assertContains(f.out(ann), "A magic seal prevents orders.")
  }

  @Test
  fun `an incapacitated pet gets ORDER_CANT`() {
    val f = Fixture(protos = Prototypes(T.rooms, T.mobs(fs) + wolfProto, T.items, T.skills(fs)))
    val ann = f.player("Ann")
    val wolf = f.w.spawnMob(wolfProto.label, ann.room)
    f.w.setOwner(wolf, ann)
    wolf.condition = Condition.INCAPACITATED

    f.input(ann, "order wolf score")
    f.run()
    assertEquals(0, wolf.actionQueue.size)
    assertContains(f.out(ann), "A wolf can't do that right now.")
  }

  @Test
  fun `exploit 1 - owner walks out before ordered kill runs, action is dropped`() {
    val f = Fixture(protos = Prototypes(T.rooms, T.mobs(fs) + wolfProto, T.items, T.skills(fs)))
    val ann = f.player("Ann", at = T.HALL)
    val wolf = f.w.spawnMob(wolfProto.label, ann.room)
    f.w.setOwner(wolf, ann)
    val goblin = f.mob(T.GOBLIN, at = T.HALL)

    wolf.waitState = 2
    f.input(ann, "order wolf kill goblin")
    f.run()
    assertEquals(1, wolf.actionQueue.size)

    f.w.teleport(ann, f.w.room(T.YARD))

    f.run(3)
    assertFalse(wolf.isEngaged, "wolf did not engage goblin")
    assertFalse(goblin.isEngaged)
    assertContains(f.out(ann), "A wolf hesitates without you nearby.")
  }

  @Test
  fun `exploit 2 - ordered move separates pet from owner, subsequent ordered attack is dropped`() {
    val f = Fixture(protos = Prototypes(T.rooms, T.mobs(fs) + wolfProto, T.items, T.skills(fs)))
    val ann = f.player("Ann", at = T.HALL)
    val wolf = f.w.spawnMob(wolfProto.label, ann.room)
    f.w.setOwner(wolf, ann)
    val goblin = f.mob(T.GOBLIN, at = T.YARD)

    f.input(ann, "order wolf north")
    f.input(ann, "order wolf kill goblin")

    assertEquals(2, wolf.actionQueue.size)

    // Pulse 1: wolf moves north to yard (ann is in hall)
    f.run()
    assertEquals(T.YARD, wolf.room.label)
    assertEquals(T.HALL, ann.room.label)

    // Pulse 2: wolf attempts to execute kill goblin, but ann is in hall, wolf is in yard
    f.run()
    assertFalse(wolf.isEngaged)
    assertFalse(goblin.isEngaged)
    assertContains(f.out(ann), "A wolf hesitates without you nearby.")
  }

  @Test
  fun `exploit 3 - owner leaves during spell warmup, cast is dropped`() {
    val f = Fixture(protos = Prototypes(T.rooms, T.mobs(fs) + wolfProto, T.items, T.skills(fs)))
    val ann = f.player("Ann", at = T.HALL)
    val wolf = f.w.spawnMob(wolfProto.label, ann.room)
    f.w.setOwner(wolf, ann)
    wolf.skills[T.BOLT] = 100
    val goblin = f.mob(T.GOBLIN, at = T.HALL)

    f.input(ann, "order wolf cast bolt goblin")
    f.run()
    assertTrue(wolf.isCasting)

    f.w.teleport(ann, f.w.room(T.YARD))

    f.run(5)
    assertFalse(wolf.isCasting)
    assertFalse(wolf.isEngaged, "wolf is not engaged")
    assertFalse(goblin.isEngaged, "goblin is not engaged")
    assertContains(f.out(ann), "A wolf hesitates without you nearby.")
  }

  @Test
  fun `pet own think-chosen action without orderedBy is unaffected by owner absence`() {
    val f = Fixture(protos = Prototypes(T.rooms, T.mobs(fs) + wolfProto, T.items, T.skills(fs)))
    val ann = f.player("Ann", at = T.YARD)
    val wolf = f.w.spawnMob(wolfProto.label, f.w.room(T.HALL))
    f.w.setOwner(wolf, ann)
    val goblin = f.mob(T.GOBLIN, at = T.HALL)

    f.w.act(wolf, "kill goblin")
    assertEquals(1, wolf.actionQueue.size)
    assertNull(wolf.actionQueue.first().orderedBy)

    f.run(5)
    assertTrue(wolf.isEngaged, "wolf engages goblin even though owner is in another room")
  }
}
