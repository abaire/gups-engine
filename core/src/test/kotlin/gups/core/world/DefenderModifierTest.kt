package gups.core.world

import gups.core.Label
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Effect
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.ModifierPoint
import gups.core.model.Prototypes
import gups.core.model.ZoneProto
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefenderModifierTest {
  private val cloak = Label("//t:cloak")
  private val parryDagger = Label("//t:parry_dagger")

  /** A room with a `defend_hit` modifier, plus a wearable that guards and a parry dagger. */
  private fun protos(hallModifiers: Map<ModifierPoint, gups.core.HookRef> = emptyMap()) =
    Prototypes(
      listOf(T.rooms[0].copy(modifiers = hallModifiers)) + T.rooms.drop(1),
      T.mobs,
      T.items +
        listOf(
          ItemProto(
            cloak,
            "a guard cloak",
            listOf("cloak"),
            wear = "body",
            modifiers = mapOf(ModifierPoint.DEFEND_HIT to mod("cloak")),
          ),
          ItemProto(
            parryDagger,
            "a parry dagger",
            listOf("dagger", "parry"),
            wear = "wield",
            dice = Dice(1, 1, 2),
            modifiers = mapOf(ModifierPoint.DEFEND_HIT to mod("parry")),
          ),
        ),
      T.skills,
      zones = listOf(ZoneProto("t")),
    )

  @Test
  fun `the defender's defend_hit steps run after the attacker's to_hit steps, in full order`() {
    val host = RecordingHost()
    for (n in listOf("atk", "room", "aff", "cloak", "parry")) host.modifiers[n] = { _, _, base ->
      (base as Int) + 1
    }
    val f =
      Fixture(
        rules = FixedRules(toHit = 50),
        scripts = host,
        protos = protos(mapOf(ModifierPoint.DEFEND_HIT to mod("room"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    // The defender carries its own chain: a room modifier, an affect and two worn items.
    f.w.applyAffect(
      d,
      AffectSpec("wary", 100, modifiers = mapOf(ModifierPoint.DEFEND_HIT to mod("aff"))),
    )
    f.w.moveItem(
      f.w.spawnItem(cloak, ItemLocation.Carried(d.id)),
      ItemLocation.Equipped(d.id, "body"),
    )
    f.w.moveItem(
      f.w.spawnItem(parryDagger, ItemLocation.Carried(d.id)),
      ItemLocation.Equipped(d.id, "wield"),
    )
    // The attacker has a to_hit of its own so its chain is non-empty and runs first.
    f.w.applyAffect(
      p,
      AffectSpec("keen", 100, modifiers = mapOf(ModifierPoint.TO_HIT to mod("atk"))),
    )
    val ctx = RulesContext(f.w, p, d)
    host.calls.clear()
    val r = f.w.toHitChain(ctx)
    assertEquals(55, r.value, "50 + atk + room + aff + cloak + parry")
    assertEquals(
      listOf(
        "mod:affect:keen:atk",
        "mod:room:hall:room",
        "mod:affect:wary:aff",
        "mod:item:cloak:cloak",
        "mod:item:parry_dagger:parry",
      ),
      host.calls,
    )
    assertContains(
      r.text,
      "rules.to_hit base=50 → affect:keen 51 → defender:defend_hit → room:hall 52",
    )
    assertContains(r.text, " → item:parry_dagger 55")
  }

  @Test
  fun `a defender's worn weapon contributes to defend_hit during a swing`() {
    val host = RecordingHost()
    host.modifiers["parry"] = { _, _, base -> (base as Int) + 7 }
    val f = Fixture(rules = FixedRules(toHit = 50), scripts = host, protos = protos())
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    val dagger = f.w.spawnItem(parryDagger, ItemLocation.Carried(d.id))
    f.w.moveItem(dagger, ItemLocation.Equipped(d.id, "wield"))
    // The attacker swings an unarmed swing ("wield", weapon = null); the defender's parry dagger
    // has dice but is not the swinging weapon, so a weapon filter would skip it. It must not.
    host.calls.clear()
    val r = f.w.toHitChain(RulesContext(f.w, p, d, slot = "wield"))
    assertEquals(57, r.value, "the defender's worn weapon contributes on defend_hit")
    assertEquals(listOf("mod:item:parry_dagger:parry"), host.calls)
  }

  @Test
  fun `a defender without a defend_hit modifier leaves the trace as today`() {
    val host = RecordingHost()
    host.modifiers["room"] = { _, _, base -> (base as Int) + 1 }
    val f =
      Fixture(
        rules = FixedRules(toHit = 50),
        scripts = host,
        protos = protos(mapOf(ModifierPoint.TO_HIT to mod("room"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    val r = f.w.toHitChain(RulesContext(f.w, p, d))
    assertEquals(51, r.value)
    assertEquals("rules.to_hit base=50 → room:hall 51", r.text)
  }

  @Test
  fun `each side reads only its own point and an attacker's defend_hit and a defender's to_hit are ignored`() {
    val host = RecordingHost()
    // The attacker's defend_hit and the defender's to_hit must both be ignored.
    host.modifiers["atk_defend"] = { _, _, base -> (base as Int) + 100 }
    host.modifiers["def_to_hit"] = { _, _, base -> (base as Int) + 100 }
    val f = Fixture(rules = FixedRules(toHit = 50), scripts = host, protos = protos())
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    // Give the attacker a defend_hit and the defender a to_hit: neither may be consulted.
    f.w.applyAffect(
      p,
      AffectSpec("rogue", 100, modifiers = mapOf(ModifierPoint.DEFEND_HIT to mod("atk_defend"))),
    )
    f.w.applyAffect(
      d,
      AffectSpec("sly", 100, modifiers = mapOf(ModifierPoint.TO_HIT to mod("def_to_hit"))),
    )
    host.calls.clear()
    val r = f.w.toHitChain(RulesContext(f.w, p, d))
    assertEquals(50, r.value)
    assertTrue(
      host.calls.isEmpty(),
      "no modifier from either side's foreign point ran: ${host.calls}",
    )
  }

  @Test
  fun `a defender defend_hit of 0 makes every swing miss`() {
    val host = RecordingHost()
    host.modifiers["cloak"] = { _, _, _ -> 0 }
    val f = Fixture(rules = FixedRules(toHit = 100), scripts = host, protos = protos())
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.w.moveItem(
      f.w.spawnItem(cloak, ItemLocation.Carried(d.id)),
      ItemLocation.Equipped(d.id, "body"),
    )
    f.w.startCombat(p, d)
    f.runTo(16)
    assertEquals(1000, d.current("hp"), "every swing missed")
    // The attacker still swings; its to_hit chain resolves to 0 via the defender's cloak.
    assertTrue(host.calls.any { it.endsWith(":cloak") }, "the defender's cloak ran on each swing")
  }

  // ---- receive_damage and mitigate ---------------------------------------------------------

  private val slash = DamageType("slash")

  /** A 5-point hit through a defender receive_damage that subtracts 3 lands for 2. */
  @Test
  fun `a receive_damage modifier subtracts from the raw damage of a swing`() {
    val host = RecordingHost()
    host.modifiers["ward"] = { _, _, base -> (base as Int) - 3 }
    val f =
      Fixture(
        rules = FixedRules(damage = 5),
        scripts = host,
        protos = protos(mapOf(ModifierPoint.RECEIVE_DAMAGE to mod("ward"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    val hit = f.w.resolveDamage(RulesContext(f.w, p, d), 5, slash)
    assertEquals(mapOf("hp" to 2), hit.losses, "5 - 3 = 2")
  }

  @Test
  fun `a receive_damage below 0 counts as 0 and fires no on_damage`() {
    val host = RecordingHost()
    var fired = false
    host.handlers[HookName.ON_DAMAGE] = { _, _, _ ->
      fired = true
      null
    }
    host.modifiers["nullify"] = { _, _, _ -> -10 }
    val f =
      Fixture(
        rules = FixedRules(damage = 5),
        scripts = host,
        protos = protos(mapOf(ModifierPoint.RECEIVE_DAMAGE to mod("nullify"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    val before = d.current("hp")
    f.w.applyEffect(Effect.Damage(Dice(1, 1, 5), slash), p, d)
    assertEquals(before, d.current("hp"), "nothing landed")
    assertFalse(fired, "no on_damage with no positive loss")
  }

  @Test
  fun `receive_damage applies to a damage effect too`() {
    val host = RecordingHost()
    host.modifiers["ward"] = { _, _, base -> (base as Int) - 3 }
    val f =
      Fixture(
        rules = FixedRules(damage = 5),
        scripts = host,
        protos = protos(mapOf(ModifierPoint.RECEIVE_DAMAGE to mod("ward"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    val before = d.current("hp")
    f.w.applyEffect(Effect.Damage(Dice(1, 1, 4), slash), p, d)
    assertEquals(before - 2, d.current("hp"), "5 - 3 = 2 through the effect")
  }

  @Test
  fun `a mitigate modifier routes part of an hp loss into a mana gain`() {
    val host = RecordingHost()
    host.modifiers["siphon"] = { _, _, base ->
      @Suppress("UNCHECKED_CAST") val m = base as Map<String, Int>
      mapOf("hp" to (m.getValue("hp") - 1), "mana" to -1)
    }
    val f = Fixture(rules = FixedRules(damage = 5), scripts = host, protos = protos())
    val p = f.player("Ann")
    val d = f.player("Bea", index = 1) // a player has a mana vital
    f.setVital(d, "mana", d.max("mana") - 1) // room to gain
    f.w.applyAffect(
      d,
      AffectSpec("siphon", 100, modifiers = mapOf(ModifierPoint.MITIGATE to mod("siphon"))),
    )
    val beforeHp = d.current("hp")
    val beforeMana = d.current("mana")
    val hit = f.w.resolveDamage(RulesContext(f.w, p, d), 5, slash)
    f.w.damage(d, hit.losses, slash, p)
    assertEquals(beforeHp - 4, d.current("hp"), "hp loss reduced by 1")
    assertEquals(beforeMana + 1, d.current("mana"), "routed into a mana gain")
  }

  @Test
  fun `the attacker's damage steps run before the defender's receive_damage and mitigate steps`() {
    val host = RecordingHost()
    host.modifiers["atk_dmg"] = { _, _, base -> (base as Int) + 1 }
    host.modifiers["ward"] = { _, _, base -> (base as Int) + 1 }
    host.modifiers["siphon"] = { _, _, base ->
      @Suppress("UNCHECKED_CAST") val m = base as Map<String, Int>
      mapOf("hp" to (m.getValue("hp") + 1))
    }
    val f =
      Fixture(
        rules = FixedRules(damage = 5),
        scripts = host,
        protos = protos(mapOf(ModifierPoint.RECEIVE_DAMAGE to mod("ward"))),
      )
    val p = f.player("Ann")
    val d = f.player("Bea", index = 1)
    f.w.applyAffect(
      p,
      AffectSpec("strike", 100, modifiers = mapOf(ModifierPoint.DAMAGE to mod("atk_dmg"))),
    )
    f.w.applyAffect(
      d,
      AffectSpec("siphon", 100, modifiers = mapOf(ModifierPoint.MITIGATE to mod("siphon"))),
    )
    host.calls.clear()
    f.w.resolveDamage(RulesContext(f.w, p, d), 5, slash)
    assertEquals(
      listOf(
        "mod:affect:strike:atk_dmg", // attacker's damage chain
        "mod:room:hall:ward", // defender's receive_damage
        "mod:affect:siphon:siphon", // defender's mitigate, after rules.mitigate
      ),
      host.calls,
    )
  }

  @Test
  fun `the trace shows the defender's receive_damage and mitigate steps only when they exist`() {
    val host = RecordingHost()
    host.modifiers["ward"] = { _, _, base -> (base as Int) - 3 }
    val f =
      Fixture(
        rules = FixedRules(damage = 5),
        scripts = host,
        protos = protos(mapOf(ModifierPoint.RECEIVE_DAMAGE to mod("ward"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    val hit = f.w.resolveDamage(RulesContext(f.w, p, d), 5, slash)
    assertContains(hit.text, " → defender:receive_damage → room:hall 2")
    assertFalse("defender:mitigate" in hit.text, "no mitigate step without a modifier")
  }

  @Test
  fun `a mitigate step with no losses traces as 0`() {
    val f = Fixture(rules = FixedRules().apply { mitigation = { _, _, _ -> emptyMap() } })
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    val hit = f.w.resolveDamage(RulesContext(f.w, p, d), 5, slash)
    assertEquals("rules.damage base=5 → mitigate 0 slash", hit.text)
  }

  @Test
  fun `the raw damage verb runs no chain`() {
    val host = RecordingHost()
    host.modifiers["ward"] = { _, _, base -> (base as Int) - 3 }
    val f =
      Fixture(
        rules = FixedRules(damage = 5),
        scripts = host,
        protos = protos(mapOf(ModifierPoint.RECEIVE_DAMAGE to mod("ward"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    host.calls.clear()
    val before = d.current("hp")
    f.w.damage(d, mapOf("hp" to 5), slash, p)
    assertEquals(before - 5, d.current("hp"), "the raw verb applies the loss verbatim")
    assertTrue(host.calls.isEmpty(), "no modifier chain ran for the raw verb: ${host.calls}")
  }
}
