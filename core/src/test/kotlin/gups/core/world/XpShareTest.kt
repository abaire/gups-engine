package gups.core.world

import gups.core.fixtures.FixtureRules
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Actor
import gups.core.rules.RulesContext
import kotlin.test.Test
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
class XpShareTest(private val fs: FixtureSchema) {
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

  private val defaultShare: (RulesContext) -> Map<Actor, Long> = { c ->
    c.actor?.let { mapOf(it to (c.xp ?: 0L)) } ?: emptyMap()
  }

  @Test
  fun `xp_share splits the pool between the killer and a healer who never damaged the victim`() {
    val rules = FixtureRules(fs)
    rules.xpForKillOf = { 1000L }
    var seen: RulesContext? = null
    rules.xpShareOf = { c ->
      seen = c
      defaultShare(c)
    }
    val f = Fixture(rules = rules)
    val killer = f.player("Ann")
    val healer = f.player("Ben")
    val g = f.mob(T.DUMMY)
    f.w.startCombat(killer, g)
    f.w.startCombat(healer, g)
    f.hurt(g, 4, killer)
    f.hurt(killer, 10, g)
    f.w.heal(killer, fs.health, 5, source = healer)
    f.hurt(g, g.current(fs.health) + 1, killer)

    val ctx = checkNotNull(seen) { "xp_share was not called" }
    assertEquals(killer, ctx.actor)
    assertEquals(g, ctx.target)
    assertEquals(1000L, ctx.xp)
    // The default pays only the killer; a world formula could pay the healer too. Assert both are
    // offered to the formula.
    assertEquals(
      setOf(killer, healer).map { it.name },
      ctx.contributors!!.map { it.actor.name }.sorted(),
    )
    assertEquals(1000L, killer.xp, "the default pays the killer the whole pool")
    assertEquals(0L, healer.xp)
  }

  @Test
  fun `contributor struct fields are the ledger values and the owner and room`() {
    val rules = FixtureRules(fs)
    rules.xpForKillOf = { 500L }
    var seen: RulesContext? = null
    rules.xpShareOf = { c ->
      seen = c
      defaultShare(c)
    }
    val f = Fixture(rules = rules)
    val killer = f.player("Ann")
    val healer = f.player("Ben")
    val g = f.mob(T.DUMMY)
    f.w.startCombat(killer, g)
    f.w.startCombat(healer, g)
    f.hurt(g, 4, killer)
    f.hurt(killer, 10, g)
    f.w.heal(killer, fs.health, 5, source = healer)
    f.hurt(g, g.current(fs.health) + 1, killer)

    val byName = checkNotNull(seen).contributors!!.associateBy { it.actor.name }
    assertTrue(byName.getValue("Ann").damage > 0, "the killer's damage is recorded")
    assertEquals(0, byName.getValue("Ben").damage)
    assertEquals(5, byName.getValue("Ben").healing)
    assertNull(byName.getValue("Ann").owner, "a player contributor has no owner")
    assertEquals(true, byName.getValue("Ann").inRoom)
  }

  @Test
  fun `a formula naming an actor outside the contributors pays it`() {
    val rules = FixtureRules(fs)
    rules.xpForKillOf = { 500L }
    val f = Fixture(rules = rules)
    val killer = f.player("Ann")
    val bystander = f.player("Bob")
    val g = f.mob(T.DUMMY)
    f.w.startCombat(killer, g)
    f.hurt(g, 4, killer)
    rules.xpShareOf = { c -> mapOf(c.actor!! to 200L, bystander to 300L) }
    f.hurt(g, g.current(fs.health) + 1, killer)
    assertEquals(200L, killer.xp)
    assertEquals(300L, bystander.xp, "an actor outside the contributors may be paid")
  }

  @Test
  fun `a removed recipient, a zero entry and the victim are skipped`() {
    val rules = FixtureRules(fs)
    rules.xpForKillOf = { 400L }
    val f = Fixture(rules = rules)
    val killer = f.player("Ann")
    val ghost = f.player("Ghost")
    val g = f.mob(T.DUMMY)
    f.w.startCombat(killer, g)
    f.hurt(g, 4, killer)
    f.w.removeActor(ghost)
    rules.xpShareOf = { c -> mapOf(ghost to 100L, killer to 0L, g to 50L, killer to 400L) }
    f.hurt(g, g.current(fs.health) + 1, killer)
    assertEquals(400L, killer.xp, "the valid entry is paid; removed, zero and victim are skipped")
  }

  @Test
  fun `a negative entry in the dict yields nothing for that actor`() {
    val rules = FixtureRules(fs)
    rules.xpForKillOf = { 300L }
    val f = Fixture(rules = rules)
    val killer = f.player("Ann")
    val g = f.mob(T.DUMMY)
    f.w.startCombat(killer, g)
    f.hurt(g, 4, killer)
    rules.xpShareOf = { c -> mapOf(c.actor!! to -1L) }
    f.hurt(g, g.current(fs.health) + 1, killer)
    assertEquals(0L, killer.xp, "a negative entry is not paid")
  }

  @Test
  fun `a contributor who has quit by death time is not listed`() {
    val rules = FixtureRules(fs)
    rules.xpForKillOf = { 200L }
    var seen: RulesContext? = null
    rules.xpShareOf = { c ->
      seen = c
      defaultShare(c)
    }
    val f = Fixture(rules = rules)
    val killer = f.player("Ann")
    val quitter = f.player("Quitter")
    val g = f.mob(T.DUMMY)
    f.w.startCombat(killer, g)
    f.w.startCombat(quitter, g)
    f.hurt(g, 4, quitter)
    f.hurt(g, 2, killer)
    f.w.removeActor(quitter)
    f.hurt(g, g.current(fs.health) + 1, killer)
    assertEquals(
      listOf(killer),
      checkNotNull(seen).contributors!!.map { it.actor },
      "the quitter is not listed",
    )
    assertEquals(200L, killer.xp, "the default pays the killer")
  }

  @Test
  fun `a summoned victim pays nothing and xp_share is not called`() {
    val rules = FixtureRules(fs)
    rules.xpForKillOf = { 1000L }
    var shareCalled = false
    rules.xpShareOf = { c ->
      shareCalled = true
      defaultShare(c)
    }
    val f = Fixture(rules = rules)
    val killer = f.player("Ann")
    val summoned = f.w.summon(killer, T.DUMMY, 1, null).first()
    f.w.startCombat(killer, summoned)
    f.hurt(summoned, summoned.current(fs.health) + 1, killer)
    assertFalse(summoned.inWorld)
    assertFalse(shareCalled, "xp_share is not called for a summon")
    assertEquals(0L, killer.xp)
  }

  @Test
  fun `the ledger of a dead player victim is empty after death`() {
    val rules = FixtureRules(fs)
    val f = Fixture(rules = rules)
    val killer = f.player("Ann")
    val victim = f.player("Bob")
    f.w.startCombat(killer, victim)
    f.hurt(victim, 4, killer)
    f.hurt(victim, victim.current(fs.health) + 1, killer)
    assertTrue(victim.inWorld, "the player victim has respawned")
    assertTrue(victim.contributions.isEmpty(), "the victim's ledger is cleared on death")
  }
}
