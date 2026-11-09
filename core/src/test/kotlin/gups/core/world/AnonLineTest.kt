package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Actor
import gups.core.model.Channel
import gups.core.model.DamageType
import gups.core.model.DamageTypeInfo
import gups.core.model.Dice
import gups.core.model.Effect
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.TargetKind
import gups.core.rules.RulesContext
import gups.core.rules.RulesEngine
import gups.core.rules.StartingStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnonLineTest {
  private val fs: FixtureSchema =
    FixtureSchemas.FANTASY.let {
      FixtureSchema(
        it.name,
        it.schema.copy(damageTypeInfo = mapOf("fire" to DamageTypeInfo("fire", "Fire", "fiery"))),
        it.health,
        it.energy,
        it.stamina,
        it.agility,
        it.strength,
        it.toughness,
        it.mind,
        it.armor,
        it.speed,
        it.magic,
        it.playerMax,
        it.mobMax,
        it.startsEmpty,
      )
    }

  private val fire = Effect.Damage(Dice(1, 1, 4), DamageType("fire"))
  private val bludgeon = Effect.Damage(Dice(1, 1, 4), DamageType("bludgeon"))

  private fun skill(
    target: TargetKind = TargetKind.ENEMY,
    effect: Effect? = fire,
    unrecognized: Map<String, String> = emptyMap(),
    range: SkillRange = SkillRange.FAR,
  ) =
    SkillProto(
      Label("//t:anon"),
      kind = "spell",
      range = range,
      target = target,
      effect = effect,
      unrecognized = unrecognized,
    )

  private class Setup(val f: Fixture, val mage: Actor)

  private fun setup(): Setup {
    val f = Fixture(fs = fs)
    return Setup(f, f.player("Mage"))
  }

  @Test
  fun `a skill's own room line names the caster when there is no target`() {
    val s = skill(unrecognized = mapOf("room" to "{actor} blesses {target}."))
    assertEquals("Mage blesses Mage.", line(s, "room", null))
  }

  private fun line(s: SkillProto, key: String, target: String? = "Tess"): String {
    val (f, mage) = setup().let { it.f to it.mage }
    return f.w.anonLine(s, key, mage, target)
  }

  @Test
  fun `tier one uses the skill's own line with actor and target filled`() {
    val own =
      mapOf(
        "room" to "{actor} points at {target}.",
        "victim" to "{actor} points at you.",
        "resisted_room" to "{target} shrugs off {actor}.",
        "resisted_victim" to "You shrug off {actor}.",
      )
    val s = skill(unrecognized = own)
    assertEquals("Mage points at Tess.", line(s, "room"))
    assertEquals("Mage points at you.", line(s, "victim", null))
    assertEquals("Tess shrugs off Mage.", line(s, "resisted_room"))
    assertEquals("You shrug off Mage.", line(s, "resisted_victim", null))
  }

  @Test
  fun `tier two names the damage`() {
    assertEquals("Mage's fiery attack hits Tess.", line(skill(), "room"))
    assertEquals("Mage's fiery attack hits you!", line(skill(), "victim", null))
  }

  @Test
  fun `the noun and a type without an adjective`() {
    val bolt = skill(unrecognized = mapOf("noun" to "bolt"))
    assertEquals("Mage's fiery bolt hits you!", line(bolt, "victim", null))
    assertEquals("Mage's attack hits you!", line(skill(effect = bludgeon), "victim", null))
  }

  @Test
  fun `damage inside a sequence is found`() {
    val s = skill(effect = Effect.Sequence(Effect.Stun(1), fire))
    assertEquals("Mage's fiery attack hits you!", line(s, "victim", null))
    assertEquals(fire, firstDamage(s.effect))
    assertNull(firstDamage(Effect.Stun(1)))
    assertNull(firstDamage(null))
  }

  @Test
  fun `tier three is harm for an enemy and aid for an ally`() {
    val stun = skill(effect = Effect.Stun(1))
    assertEquals("Mage's attack makes you feel worse.", line(stun, "victim", null))
    assertEquals("Mage's attack strikes Tess.", line(stun, "room"))
    val heal = skill(TargetKind.ALLY, Effect.Heal(Dice(1, 1, 4), fs.health))
    assertEquals("Mage's aid makes you feel better.", line(heal, "victim", null))
    assertEquals("Mage's aid washes over Tess.", line(heal, "room"))
  }

  @Test
  fun `resisted lines use what`() {
    val bolt = skill(unrecognized = mapOf("noun" to "bolt"))
    assertEquals("You resist Mage's fiery bolt.", line(bolt, "resisted_victim", null))
    assertEquals("Tess resists Mage's fiery bolt.", line(bolt, "resisted_room"))
  }

  @Test
  fun `tier four is generic`() {
    val item = skill(TargetKind.ITEM, Effect.Stun(1))
    assertEquals("Mage does something to a lamp.", line(item, "room", "a lamp"))
    assertEquals("Mage does something to you.", line(item, "victim", null))
    assertEquals("Mage does something.", line(skill(), "room", null))
    assertEquals("Mage does something.", line(skill(TargetKind.NONE, null), "room", null))
  }

  @Test
  fun `a room skill with no single target is generic whatever its damage`() {
    assertEquals("Mage does something.", line(skill(range = SkillRange.ROOM), "room", null))
  }

  private class SpellRules(private val base: RulesEngine, private val channel: Channel) :
    RulesEngine by base {
    override fun perceiveMessage(c: RulesContext): String? =
      if (c.channel == channel && c.event == "cast") "You sense a working." else null
  }

  @Test
  fun `perceiversOf lists who would be told and how, without telling them`() {
    val spell = Channel("spell")
    val f = Fixture(fs = fs, rules = SpellRules(FixedRules(fs), spell))
    val none = mapOf(Channel.SIGHT to 0, Channel.SOUND to 0)
    val caster = f.player("Mage")
    val sighted = f.player("Bea")
    val blind =
      f.w.spawnPlayer("Cal", T.HALL, overrides = StartingStats(senses = none)).also {
        f.connect(it)
      }
    val attuned =
      f.w.spawnPlayer("Dee", T.HALL, overrides = StartingStats(senses = none + (spell to 1))).also {
        f.connect(it)
      }
    val seen =
      f.w.perceiversOf(caster.room, "cast", caster, setOf(Channel.SIGHT), exclude = arrayOf(caster))
    assertEquals(Channel.SIGHT, seen[sighted]?.channel)
    assertNull(seen[sighted]?.text)
    assertFalse(blind in seen)
    assertFalse(caster in seen)
    assertEquals(Perceived(spell, "You sense a working."), seen[attuned])
    assertTrue(listOf(sighted, attuned).all { f.out(it).isEmpty() }, "nothing is sent")
  }
}
