package gups.core.world

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ActionLifecycleTest {
  private fun caster(): Triple<Fixture, gups.core.model.Player, gups.core.model.Mob> {
    val f = Fixture(rules = FixedRules(toHit = 0))
    val p = f.player("Ann")
    p.skills[T.BOLT] = 100
    val g = f.mob(T.DUMMY, index = 2)
    return Triple(f, p, g)
  }

  @Test
  fun `a warmup casts for its pulses, then resolves, pays, and lags`() {
    val (f, p, g) = caster()
    f.input(p, "cast bolt dummy")
    f.run() // pulse 1: popped, warmup starts
    val cast = assertNotNull(p.casting)
    assertEquals(1, cast.startedAt)
    assertEquals(5, cast.endsAt)
    f.run(3)
    assertTrue(p.isCasting)
    assertEquals(1000, g.hp)
    f.run() // pulse 5: resolves
    assertFalse(p.isCasting)
    assertEquals(1000 - 8, g.hp)
    assertEquals(40, p.mana)
    assertEquals(4, p.waitState)
    assertTrue(g.id in p.fighting, "a hostile skill landing starts combat")
    f.run(4)
    assertEquals(0, p.waitState)
  }

  @Test
  fun `damage during the warmup interrupts with half lag and no cost`() {
    val (f, p, g) = caster()
    f.input(p, "cast bolt dummy")
    f.run(2)
    f.w.damage(p, 4, gups.core.model.DamageType.SLASH, g) // 8% of 50: below the threshold
    assertTrue(p.isCasting)
    f.w.damage(p, 5, gups.core.model.DamageType.SLASH, g) // 10%
    assertFalse(p.isCasting)
    assertEquals(2, p.waitState)
    assertEquals(50, p.mana)
    f.w.flushOutput()
    assertContains(f.out(p), "concentration is broken")
  }

  @Test
  fun `a stun interrupts when the skill says so`() {
    val (f, p, g) = caster()
    f.input(p, "cast bolt dummy")
    f.run(2)
    f.w.applyEffect(gups.core.model.Effect.Stun(3), g, p)
    assertFalse(p.isCasting)
    assertEquals(2, p.waitState)
  }

  @Test
  fun `failed re-validation after the warmup pays nothing and applies half lag`() {
    val (f, p, g) = caster()
    f.input(p, "cast bolt dummy")
    f.run(2)
    f.w.teleport(g, f.w.room(T.YARD))
    f.out(p)
    f.run(3)
    assertFalse(p.isCasting)
    assertContains(f.out(p), "A training dummy isn't here.")
    assertEquals(2, p.waitState)
    assertEquals(50, p.mana)
  }

  @Test
  fun `validation failures and vetoes apply no lag`() {
    val host = RecordingHost()
    host.handlers[HookName.BEFORE_EXIT] = { _, owner, _ ->
      if (owner.name == "The Hall") HookOutcome.Deny("A force holds you back.") else null
    }
    val f = Fixture(scripts = host)
    val p = f.player("Ann")
    f.input(p, "west")
    f.run()
    assertEquals("You can't go that way.\n", f.out(p))
    assertEquals(0, p.waitState)
    f.input(p, "north")
    f.run()
    assertEquals("A force holds you back.\n", f.out(p))
    assertEquals(T.HALL, p.room.label)
    assertEquals(0, p.waitState)
  }

  @Test
  fun `vetoes run when the action is popped, not when it is queued`() {
    val host = RecordingHost()
    var deny = false
    host.handlers[HookName.BEFORE_EXIT] = { _, _, _ -> if (deny) HookOutcome.Deny("No.") else null }
    val f = Fixture(scripts = host)
    val p = f.player("Ann")
    f.input(p, "north")
    deny = true
    f.run()
    assertEquals(T.HALL, p.room.label)
  }

  @Test
  fun `recovery delays the next queued action`() {
    val f = Fixture(rules = FixedRules(toHit = 0))
    val p = f.player("Ann")
    val g = f.mob(index = 2)
    f.input(p, "advance goblin") // lag 2
    f.input(p, "advance goblin")
    f.run()
    assertEquals(1, p.trackIndex)
    assertEquals(2, p.waitState)
    f.run(2)
    assertEquals(1, p.trackIndex)
    f.run()
    assertEquals(2, p.trackIndex)
    assertEquals(g.trackIndex, p.trackIndex)
  }
}
