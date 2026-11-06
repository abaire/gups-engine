package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Effect
import gups.core.model.Prototypes
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.TargetKind
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

/** A skill whose target is the caster's room: it enchants only that room. */
@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class RoomSkillTest(private val fs: FixtureSchema) {
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

  private fun fixture(
    rules: FixedRules = FixedRules(toHit = 0),
    extra: List<SkillProto> = emptyList(),
  ) = Fixture(rules = rules, protos = Prototypes(T.rooms, T.mobs, T.items, T.skills + extra))

  private fun consecrate(expireMessage: String = "The light fades.") =
    SkillProto(
      Label("//t:consecrate"),
      kind = "spell",
      target = TargetKind.ROOM,
      cost = mapOf(fs.energy to 10),
      lag = 4,
      effect =
        Effect.ApplyAffect(
          "holy",
          10,
          flags = setOf("holy"),
          expireMessage = expireMessage,
          addTags = setOf("consecrated"),
        ),
    )

  private fun dispelSkill() =
    SkillProto(
      Label("//t:unholy"),
      kind = "spell",
      target = TargetKind.ROOM,
      effect = Effect.Dispel(name = "holy"),
    )

  private fun cleanseSkill() =
    SkillProto(
      Label("//t:cleanse"),
      kind = "spell",
      target = TargetKind.ROOM,
      effect = Effect.Dispel(all = true, count = 1),
    )

  @Test
  fun `a room skill enchants the caster's room only, and pays its cost and lag`() {
    val f = fixture(extra = listOf(consecrate()))
    val p = f.player("Ann")
    p.skills[Label("//t:consecrate")] = 100
    f.player("Bea", at = T.YARD)
    f.input(p, "cast consecrate")
    f.run()
    val hall = f.w.room(T.HALL)
    val yard = f.w.room(T.YARD)
    assertEquals(1, hall.affects.size)
    assertEquals("holy", hall.affects[0].name)
    assertTrue("consecrated" in hall.tagsAt(f.w.pulse), "the room gains the added tag")
    assertTrue(yard.affects.isEmpty(), "a player in another room sees nothing")
    assertEquals(40, p.current(fs.energy), "the cast pays its cost")
    assertEquals(4, p.waitState, "the cast lags")
    assertContains(f.out(p), "You cast consecrate.")
  }

  @Test
  fun `a room skill's duration and power scale with cast power`() {
    class PowerRules(fs: FixtureSchema, private val power: Int) : FixedRules(fs) {
      override fun skillPower(c: RulesContext): Int = power
    }
    val f = fixture(rules = PowerRules(fs, 150), extra = listOf(consecrate()))
    val p = f.player("Ann")
    p.skills[Label("//t:consecrate")] = 100
    f.input(p, "cast consecrate")
    f.run()
    val hall = f.w.room(T.HALL)
    val affect = hall.affects[0]
    assertEquals(
      15,
      affect.expiresAt - affect.appliedAt,
      "a 10-pulse duration at 150 power lasts 15",
    )
    assertEquals(150, affect.power, "the affect remembers its power")
  }

  @Test
  fun `a failed skill check leaves the room unchanged`() {
    class FailRules(fs: FixtureSchema) : FixedRules(fs) {
      override fun skillCheck(c: RulesContext): Int = 0
    }
    val f = fixture(rules = FailRules(fs), extra = listOf(consecrate()))
    val p = f.player("Ann")
    p.skills[Label("//t:consecrate")] = 100
    f.input(p, "cast consecrate")
    f.run()
    assertTrue(f.w.room(T.HALL).affects.isEmpty(), "the room is unchanged")
    assertContains(f.out(p), "You fail to cast consecrate.")
  }

  @Test
  fun `dispel on a room removes the matching affect and tells the room its message`() {
    val f = fixture(extra = listOf(consecrate(), dispelSkill()))
    val p = f.player("Ann")
    p.skills[Label("//t:consecrate")] = 100
    p.skills[Label("//t:unholy")] = 100
    val bystander = f.player("Bea", index = 1)
    f.input(p, "cast consecrate")
    f.run()
    assertEquals(1, f.w.room(T.HALL).affects.size)
    f.run(4) // clear the consecrate cast's lag so the dispel can be cast
    f.input(p, "cast unholy")
    f.run()
    val out = f.out(p)
    assertTrue(f.w.room(T.HALL).affects.isEmpty(), "the dispelled affect is gone, out=$out")
    assertContains(out, "The light fades.")
    assertContains(f.out(bystander), "The light fades.")
  }

  @Test
  fun `dispel all with a count removes only the oldest affect`() {
    val f = fixture(extra = listOf(consecrate(), cleanseSkill()))
    val p = f.player("Ann")
    p.skills[Label("//t:consecrate")] = 100
    p.skills[Label("//t:cleanse")] = 100
    f.input(p, "cast consecrate")
    f.run()
    // A second, later affect: a full dispel takes the oldest, leaving this one.
    f.w.applyRoomAffect(f.w.room(T.HALL), "later", 10)
    assertEquals(2, f.w.room(T.HALL).affects.size)
    f.run(4) // clear the consecrate cast's lag so the dispel can be cast
    f.input(p, "cast cleanse")
    f.run()
    val remaining = f.w.room(T.HALL).affects
    assertEquals(1, remaining.size)
    assertEquals("later", remaining[0].name, "the older 'holy' affect was removed")
  }

  @Test
  fun `a room skill ignores range room and resolves once with no target`() {
    for (bystanders in listOf(0, 2)) {
      val seen = mutableListOf<String?>()
      val skill =
        consecrate()
          .copy(
            range = SkillRange.ROOM,
            resolve = SkillResolver { _, _, target, _, _ -> seen += target?.name },
          )
      val f = fixture(extra = listOf(skill))
      val p = f.player("Ann")
      p.skills[Label("//t:consecrate")] = 100
      repeat(bystanders) { f.player("Bystander$it", index = 1) }
      f.input(p, "cast consecrate")
      f.run()
      assertEquals(listOf<String?>(null), seen, "with $bystanders bystanders")
      assertEquals(1, f.w.room(T.HALL).affects.size)
      assertTrue(f.w.room(T.HALL).actors.none { it.affects.any { a -> a.name == "holy" } })
    }
  }

  @Test
  fun `a room skill fails if the caster left the room during warmup`() {
    val f = fixture(extra = listOf(consecrate().copy(warmup = 4)))
    val p = f.player("Ann")
    p.skills[Label("//t:consecrate")] = 100
    f.input(p, "cast consecrate")
    f.run()
    f.w.placeActor(p, f.w.room(T.YARD), 0)
    f.run(6)
    assertTrue(f.w.room(T.HALL).affects.isEmpty(), "the room the cast began in is unchanged")
    assertTrue(f.w.room(T.YARD).affects.isEmpty(), "the room the caster is in now is unchanged")
    assertContains(f.out(p), "You are no longer where you began casting consecrate.")
    assertEquals(50, p.current(fs.energy), "the failed cast pays no cost")
  }
}
