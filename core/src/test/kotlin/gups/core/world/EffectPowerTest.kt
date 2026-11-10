package gups.core.world

import gups.core.model.AffectScope
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Effect
import gups.core.model.ItemLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EffectPowerTest {
  @Test
  fun `scaled handles zero, negative, and rounding down with minimum of 1 for positive`() {
    assertEquals(0, scaled(0, 50))
    assertEquals(-3, scaled(-3, 200))
    assertEquals(1, scaled(1, 10))
    assertEquals(10, scaled(7, 150))
  }

  @Test
  fun `damage scales by power and at power 0 deals at least 1`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val goblin = f.mob()
    val hpBefore = goblin.vitals.getValue("hp").current

    // Dice 10d1 rolls exactly 10. At power 150, 10 * 150 / 100 = 15.
    f.w.applyEffect(
      Effect.Damage(Dice.parse("10d1"), DamageType("physical")),
      ann,
      goblin,
      EffectContext(power = 150),
    )
    val hpAfter1 = goblin.vitals.getValue("hp").current
    assertEquals(15, hpBefore - hpAfter1)

    // At power 0, scaled(10, 0) = 1 (positive never below 1 before mitigation).
    f.w.applyEffect(
      Effect.Damage(Dice.parse("10d1"), DamageType("physical")),
      ann,
      goblin,
      EffectContext(power = 0),
    )
    val hpAfter2 = goblin.vitals.getValue("hp").current
    assertEquals(1, hpAfter1 - hpAfter2)
  }

  @Test
  fun `heal scales by power and at power 0 heals at least 1`() {
    val f = Fixture()
    val ann = f.player("Ann")
    ann.vitals.getValue("hp").current = 10
    val maxHp = ann.vitals.getValue("hp").max
    assertTrue(maxHp >= 40)

    f.w.applyEffect(Effect.Heal(Dice.parse("10d1"), "hp"), ann, ann, EffectContext(power = 150))
    assertEquals(25, ann.vitals.getValue("hp").current)

    f.w.applyEffect(Effect.Heal(Dice.parse("10d1"), "hp"), ann, ann, EffectContext(power = 0))
    assertEquals(26, ann.vitals.getValue("hp").current)
  }

  @Test
  fun `stun at power 200 lasts twice as long`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val goblin = f.mob()

    f.w.applyEffect(Effect.Stun(5), ann, goblin, EffectContext(power = 200))
    val stun = goblin.affects.single { it.name == "stun" }
    assertEquals(f.w.pulse + 10, stun.expiresAt)
    assertEquals(200, stun.power)
  }

  @Test
  fun `apply_affect duration scales and power is stored`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val goblin = f.mob()

    f.w.applyEffect(
      Effect.ApplyAffect("haste", duration = 10),
      ann,
      goblin,
      EffectContext(power = 150),
    )
    val haste = goblin.affects.single { it.name == "haste" }
    assertEquals(f.w.pulse + 15, haste.expiresAt)
    assertEquals(150, haste.power)
  }

  @Test
  fun `summon with duration gives summoned affect scaled duration while count is unscaled`() {
    val f = Fixture()
    val ann = f.player("Ann")

    f.w.applyEffect(
      Effect.Summon(T.GOBLIN, count = 2, duration = 10),
      ann,
      null,
      EffectContext(power = 150),
    )
    val goblins = f.w.actors.filter { it.room == ann.room && it.name == "a goblin" }
    assertEquals(2, goblins.size)
    for (g in goblins) {
      val summoned = g.affects.single { it.name == "summoned" }
      assertEquals(f.w.pulse + 15, summoned.expiresAt)
      assertEquals(150, summoned.power)
    }
  }

  @Test
  fun `item apply_affect with null duration stays permanent at power 300`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Carried(ann.id))

    f.w.applyItemEffect(
      Effect.ApplyAffect("keen", duration = null, applies = AffectScope.CARRIED),
      ann,
      sword,
      power = 300,
    )
    val keen = sword.affects.single { it.name == "keen" }
    assertEquals(Long.MAX_VALUE, keen.expiresAt)
    assertEquals(300, keen.power)
  }

  @Test
  fun `re-applying same-named affect that lasts less keeps first affect and its power`() {
    val f = Fixture()
    val ann = f.player("Ann")
    val goblin = f.mob()

    // Duration 20 scaled at 150% is 30.
    f.w.applyEffect(
      Effect.ApplyAffect("shield", duration = 20),
      ann,
      goblin,
      EffectContext(power = 150),
    )
    val first = goblin.affects.single { it.name == "shield" }
    assertEquals(150, first.power)
    assertEquals(f.w.pulse + 30, first.expiresAt)

    // Reapply with shorter duration (5 scaled at 80% is 4) and different power
    f.w.applyEffect(
      Effect.ApplyAffect("shield", duration = 5),
      ann,
      goblin,
      EffectContext(power = 80),
    )
    val second = goblin.affects.single { it.name == "shield" }
    assertEquals(first.id, second.id)
    assertEquals(150, second.power)
    assertEquals(f.w.pulse + 30, second.expiresAt)
  }
}
