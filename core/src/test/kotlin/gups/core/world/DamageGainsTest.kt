package gups.core.world

import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.DamageType
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
class DamageGainsTest(private val fs: FixtureSchema) {
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

  private val type = DamageType(fs.unarmed)

  @Test
  fun `a loss and a gain in one event lower the one and raise the other`() {
    val host = RecordingHost()
    var amount: Int? = null
    var losses: Map<String, Int>? = null
    var times = 0
    host.handlers[HookName.ON_DAMAGE] = { _, owner, ctx ->
      if (owner === ctx.target) {
        times++
        amount = ctx.amount
        losses = ctx.losses
      }
      null
    }
    val f = Fixture(scripts = host)
    val p = f.player("Ann")
    f.setVital(p, fs.energy, p.max(fs.energy) - 5) // one below the maximum
    f.w.damage(p, mapOf(fs.health to 3, fs.energy to -2), type)
    assertEquals(p.max(fs.health) - 3, p.current(fs.health))
    assertEquals(p.max(fs.energy) - 5 + 2, p.current(fs.energy))
    assertEquals(1, times, "on_damage fires once")
    assertEquals(3, amount)
    assertEquals(mapOf(fs.health to 3), losses, "the gain is not in losses")
  }

  @Test
  fun `a gain is capped at the effective maximum and never lowers a full vital`() {
    val f = Fixture()
    val p = f.player("Ann")
    // The energy vital is at its maximum; a gain must leave it there.
    f.w.damage(p, mapOf(fs.energy to -5), type)
    assertEquals(p.max(fs.energy), p.current(fs.energy), "a gain can't raise past the maximum")
    // One below; a gain of 5 reaches the maximum but no further.
    f.w.damage(p, mapOf(fs.energy to 1), type)
    f.w.damage(p, mapOf(fs.energy to -5), type)
    assertEquals(p.max(fs.energy), p.current(fs.energy))
    // A gain to a vital at its maximum never lowers it.
    f.setVital(p, fs.health, p.max(fs.health))
    f.w.damage(p, mapOf(fs.health to -100), type)
    assertEquals(p.max(fs.health), p.current(fs.health), "a gain never lowers a full vital")
  }

  @Test
  fun `an all-gain event raises the vital, fires no on_damage and sets no last attacker`() {
    val host = RecordingHost()
    var fired = false
    host.handlers[HookName.ON_DAMAGE] = { _, owner, ctx ->
      if (owner === ctx.target) fired = true
      null
    }
    val f = Fixture(scripts = host)
    val p = f.player("Ann")
    f.setVital(p, fs.energy, p.max(fs.energy) - 3)
    assertNull(p.lastAttacker)
    f.w.damage(p, mapOf(fs.energy to -2), type)
    assertEquals(p.max(fs.energy) - 1, p.current(fs.energy))
    assertFalse(fired, "no on_damage with no positive loss")
    assertNull(p.lastAttacker, "a gain never names an attacker")
  }

  @Test
  fun `a casting target hit with only gains is not interrupted`() {
    val f = Fixture(rules = FixedRules(toHit = 0))
    val p = f.player("Ann")
    p.skills[T.BOLT] = 100
    val g = f.mob(T.DUMMY, index = 2)
    f.input(p, "cast bolt dummy")
    f.run(2) // the warmup has started
    f.setVital(p, fs.energy, p.max(fs.energy) - 1)
    f.w.damage(p, mapOf(fs.energy to -5), type, g) // a gain, no loss
    assertTrue(p.isCasting, "a gain-only blow must not break concentration")
    assertEquals(p.max(fs.energy), p.current(fs.energy))
  }

  @Test
  fun `an all-zero event changes nothing and fires nothing`() {
    val host = RecordingHost()
    var fired = false
    host.handlers[HookName.ON_DAMAGE] = { _, _, _ ->
      fired = true
      null
    }
    val f = Fixture(scripts = host)
    val g = f.mob(T.DUMMY)
    val hp = g.current(fs.health)
    val mana = g.current(fs.energy)
    f.w.damage(g, mapOf(fs.health to 0, fs.energy to 0), type)
    assertEquals(hp, g.current(fs.health))
    assertEquals(mana, g.current(fs.energy))
    assertFalse(fired, "an all-zero event fires no on_damage")
  }
}
