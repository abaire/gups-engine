package gups.core.world

import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Effect
import gups.core.model.SkillProto
import gups.core.model.TargetKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class ContributionTest(private val fs: FixtureSchema) {
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

  private fun entry(victim: gups.core.model.Actor, id: gups.core.EntityId?) =
    id?.let { victim.contributions[it] }

  @Test
  fun `damage records damage and stamps the pulse, but self and environmental damage do not`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val p = f.player("Ann")
    val g = f.mob(T.DUMMY)
    f.w.startCombat(p, g)
    val pulse = f.w.pulse
    f.hurt(g, 6, p)
    val e = entry(g, p.id)
    assertEquals(6, e?.damage)
    assertEquals(0, e?.healing)
    assertEquals(0, e?.support)
    assertEquals(pulse, e?.lastPulse)
    // No entry for the victim's own ledger from itself, and nothing for a sourceless hit.
    f.hurt(g, 4, null)
    assertEquals(6, entry(g, p.id)?.damage, "environmental damage must not record")
  }

  @Test
  fun `healing an engaged ally credits each opponent's ledger by the effective amount only`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val a = f.player("Ann")
    val b = f.player("Ben")
    val g = f.mob(T.DUMMY)
    f.w.startCombat(a, g)
    f.w.startCombat(b, g)
    f.hurt(a, 20, g)
    f.w.heal(a, fs.health, 50, source = b)
    assertEquals(20, entry(g, b.id)?.healing, "only the effective gap is counted")
    assertEquals(0, entry(g, a.id)?.healing ?: 0)
    f.w.heal(a, fs.health, 10, source = b)
    assertEquals(20, entry(g, b.id)?.healing, "a full heal earns nothing extra")
  }

  @Test
  fun `healing an actor with no opponents records nothing`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val a = f.player("Ann")
    f.hurt(a, 10, null)
    f.w.heal(a, fs.health, 5)
    assertTrue(f.w.actors.none { it.contributions.containsKey(a.id) }, "no opponents, no ledger")
  }

  @Test
  fun `a healer's entry lands on the victim's opponents, not the victim itself`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val s = f.mob(T.GOBLIN)
    val m = f.mob(T.DUMMY)
    val p1 = f.player("Ann")
    val p2 = f.player("Ben")
    f.w.startCombat(m, p1)
    f.w.startCombat(m, p2)
    f.hurt(m, 20, null)
    f.w.heal(m, fs.health, 30, source = s)
    assertEquals(20, entry(p1, s.id)?.healing, "only the effective gap is credited")
    assertEquals(20, entry(p2, s.id)?.healing)
    assertNull(entry(m, s.id), "the healer's entry never lands on the victim's own ledger")
  }

  @Test
  fun `a reapplied affect that does not take hold records no support`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val p = f.player("Ann")
    val g = f.mob(T.DUMMY)
    f.w.startCombat(p, g)
    f.w.applyAffect(g, "hex", 100, source = p, hostile = true)
    assertEquals(1, entry(g, p.id)?.support)
    f.w.applyAffect(g, "hex", 50, source = p, hostile = true)
    assertEquals(1, entry(g, p.id)?.support, "the shorter reapply records nothing")
  }

  @Test
  fun `an enemy skill's affect is a bane on the victim regardless of prior engagement`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val p = f.player("Ann")
    val g = f.mob(T.DUMMY)
    val skill = SkillProto(T.ZAP, kind = "spell", target = TargetKind.ENEMY)
    f.w.applyEffect(Effect.ApplyAffect("hex", 100), p, g, skill, power = 100)
    assertEquals(
      1,
      entry(g, p.id)?.support,
      "an enemy skill's affect is a bane even while not engaged",
    )
  }

  @Test
  fun `an ally skill's affect on an engaged ally lands on that ally's opponents`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val a = f.player("Ann")
    val b = f.player("Ben")
    val g = f.mob(T.DUMMY)
    val s = f.mob(T.GOBLIN)
    f.w.startCombat(a, g)
    val skill = SkillProto(T.MEND, kind = "spell", target = TargetKind.ALLY)
    f.w.applyEffect(Effect.ApplyAffect("buff", 100), s, a, skill, power = 100)
    assertEquals(1, entry(g, s.id)?.support, "help goes onto the ally's opponents' ledgers")
    assertNull(entry(a, s.id), "the healed ally's own ledger gains nothing")
  }

  @Test
  fun `the raw apply_affect verb infers the bane from engagement`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val a = f.player("Ann")
    val b = f.player("Ben")
    val g = f.mob(T.DUMMY)
    f.w.startCombat(a, g)
    f.w.startCombat(b, g)
    // Engaged applier: a bane on the victim's own ledger.
    f.w.applyAffect(g, "hex", 100, source = a, hostile = null)
    assertEquals(1, entry(g, a.id)?.support)
    // A not-engaged applier is help: it lands on the victim's opponents.
    val c = f.player("Cal")
    f.w.applyAffect(g, "buff", 100, source = c, hostile = null)
    assertNull(entry(g, c.id), "an unengaged applier is help, not a bane on the victim")
    assertEquals(1, entry(a, c.id)?.support, "help lands on the victim's opponents")
  }

  @Test
  fun `clear_contributions empties a ledger or removes one contributor`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val p = f.player("Ann")
    val b = f.player("Ben")
    val g = f.mob(T.DUMMY)
    f.w.startCombat(p, g)
    f.w.startCombat(b, g)
    f.hurt(g, 4, p)
    f.hurt(g, 2, b)
    assertEquals(4, entry(g, p.id)?.damage)
    assertEquals(2, entry(g, b.id)?.damage)
    f.w.clearContributions(g, b)
    assertNull(entry(g, b.id), "the named contributor is removed")
    assertEquals(4, entry(g, p.id)?.damage, "the other contributor is untouched")
    f.w.clearContributions(g)
    assertTrue(g.contributions.isEmpty(), "the whole ledger is cleared")
  }

  @Test
  fun `clear_contributions on an empty ledger is a no-op`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val g = f.mob(T.DUMMY)
    f.w.clearContributions(g)
    f.w.clearContributions(g, f.player("Ann"))
    assertTrue(g.contributions.isEmpty())
  }

  @Test
  fun `ownerOf resolves a pet to its in-world player owner`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val p = f.player("Ann")
    val s = f.player("Sam")
    val owned = f.mob(T.GOBLIN)
    f.w.setOwner(owned, p)
    val summoned = f.w.summon(p, T.DUMMY, 1, null).first()
    val follower = f.mob(T.DUMMY)
    follower.leader = p.id
    val lostOwner = f.mob(T.DUMMY)
    f.w.setOwner(lostOwner, s)
    f.w.removeActor(s)
    assertEquals(p, f.w.ownerOf(summoned))
    assertEquals(p, f.w.ownerOf(owned))
    assertNull(f.w.ownerOf(follower), "following a player does not make a mob its pet")
    assertNull(f.w.ownerOf(lostOwner), "an owner no longer in the world gives no owner")
    assertNull(f.w.ownerOf(p), "a player has no owner")
  }
}
