package gups.core.world

import gups.core.Label
import gups.core.model.AffectScope
import gups.core.model.Band
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.ItemAffect
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.ModifierPoint
import gups.core.model.Prototypes
import gups.core.model.RegionProto
import gups.core.model.ZoneProto
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModifierChainTest {
  private val cap = Label("//t:cap")
  private val blade = Label("//t:blade")

  private fun protos(hallModifiers: Map<ModifierPoint, gups.core.HookRef> = emptyMap()) =
    Prototypes(
      listOf(T.rooms[0].copy(regions = setOf("b_reg", "a_reg"), modifiers = hallModifiers)) +
        T.rooms.drop(1),
      T.mobs,
      T.items +
        listOf(
          ItemProto(
            cap,
            "a cap",
            listOf("cap"),
            wear = "head",
            modifiers = mapOf(ModifierPoint.TO_HIT to mod("cap")),
          ),
          ItemProto(
            blade,
            "a blade",
            listOf("blade"),
            wear = "wield",
            modifiers = mapOf(ModifierPoint.TO_HIT to mod("blade")),
          ),
        ),
      T.skills,
      zones = listOf(ZoneProto("t", modifiers = mapOf(ModifierPoint.TO_HIT to mod("zone")))),
      regions =
        listOf(
          RegionProto(
            "b_reg",
            "t",
            mapOf(ModifierPoint.TO_HIT to mod("rb"), ModifierPoint.DAMAGE to mod("halve")),
          ),
          RegionProto("a_reg", "t", mapOf(ModifierPoint.TO_HIT to mod("ra"))),
        ),
    )

  @Test
  fun `the chain runs zone, regions by name, room, affects by id, then worn items in slot order`() {
    val host = RecordingHost()
    for (n in listOf("zone", "ra", "rb", "room", "aff_a", "aff_b", "cap", "blade")) host.modifiers[
        n] =
      { _, _, base ->
        (base as Int) + 1
      }
    val f =
      Fixture(
        rules = FixedRules(toHit = 50),
        scripts = host,
        protos = protos(mapOf(ModifierPoint.TO_HIT to mod("room"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.w.applyAffect(p, "zeta", 100, modifiers = mapOf(ModifierPoint.TO_HIT to mod("aff_a")))
    f.w.applyAffect(p, "alpha", 100, modifiers = mapOf(ModifierPoint.TO_HIT to mod("aff_b")))
    f.w.moveItem(
      f.w.spawnItem(blade, ItemLocation.Carried(p.id)),
      ItemLocation.Equipped(p.id, "wield"),
    )
    f.w.moveItem(
      f.w.spawnItem(cap, ItemLocation.Carried(p.id)),
      ItemLocation.Equipped(p.id, "head"),
    )
    host.calls.clear()
    val r = f.w.toHitChain(RulesContext(f.w, p, d))
    assertEquals(58, r.value)
    assertEquals(
      listOf(
        "mod:zone:t:zone",
        "mod:region:a_reg:ra",
        "mod:region:b_reg:rb",
        "mod:room:hall:room",
        "mod:affect:zeta:aff_a",
        "mod:affect:alpha:aff_b",
        "mod:item:cap:cap",
        "mod:item:blade:blade",
      ),
      host.calls,
    )
    assertContains(r.text, "rules.to_hit base=50 → zone:t 51 → region:a_reg 52")
    assertContains(r.text, "→ item:blade 58")
  }

  @Test
  fun `modifiers change auto-attacks, and the rules trace shows the chain and the roll`() {
    val host = RecordingHost()
    host.modifiers["halve"] = { _, _, base -> (base as Int) / 2 }
    val f = Fixture(rules = FixedRules(damage = 10), scripts = host, protos = protos())
    val lines = mutableListOf<String>()
    f.w.onRules = { _, line -> lines += line }
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.w.startCombat(p, d)
    f.runTo(8)
    assertEquals(1000 - 5, d.current("hp"))
    assertTrue(
      lines.any { it.startsWith("rules.to_hit base=100") && it.endsWith("HIT") && "roll" in it },
      lines.toString(),
    )
    assertTrue(lines.any { "rules.damage base=10 → region:b_reg 5" in it }, lines.toString())
    assertTrue(lines.any { it.startsWith("initiative at pulse 8:") }, lines.toString())
  }

  @Test
  fun `cast_allowed stops at the first denial and the cast costs nothing`() {
    val host = RecordingHost()
    host.modifiers["fizzle"] = { _, _, _ -> "Your magic fizzles into nothing." }
    host.modifiers["never"] = { _, _, _ -> error("not reached") }
    val f =
      Fixture(
        scripts = host,
        protos =
          Prototypes(
            listOf(
              T.rooms[0].copy(modifiers = mapOf(ModifierPoint.CAST_ALLOWED to mod("fizzle")))
            ) + T.rooms.drop(1),
            T.mobs,
            T.items,
            T.skills,
            zones = listOf(ZoneProto("t")),
          ),
      )
    val p = f.player("Ann")
    p.skills[T.BOLT] = 100
    f.w.applyAffect(p, "late", 100, modifiers = mapOf(ModifierPoint.CAST_ALLOWED to mod("never")))
    f.mob(T.DUMMY)
    f.input(p, "cast bolt dummy")
    f.run()
    assertContains(f.out(p), "Your magic fizzles into nothing.")
    assertEquals(50, p.current("mana"))
    assertEquals(0, p.waitState)
    assertNull(p.casting)
  }

  @Test
  fun `regen and move cost go through the chain`() {
    val host = RecordingHost()
    host.modifiers["slow"] = { point, _, base ->
      when (point) {
        ModifierPoint.REGEN ->
          @Suppress("UNCHECKED_CAST") mapOf("hp" to (base as Map<String, Int>).getValue("hp") / 2)
        ModifierPoint.MOVE_COST ->
          @Suppress("UNCHECKED_CAST")
          mapOf("move" to (base as Map<String, Int>).getValue("move") + 4)
        else -> base
      }
    }
    val f = Fixture(scripts = host)
    val p = f.player("Ann", hp = 50)
    f.w.applyAffect(
      p,
      "weary",
      10_000,
      modifiers = mapOf(ModifierPoint.REGEN to mod("slow"), ModifierPoint.MOVE_COST to mod("slow")),
    )
    f.setVital(p, "hp", 10)
    val regen = f.w.regenOf(RulesContext(f.w, p))
    assertEquals(mapOf("hp" to (1 + 10 / 4 + 1 / 2) / 2), regen)
    f.input(p, "north")
    f.run()
    assertEquals(100 - 5, p.current("move"))
  }

  @Test
  fun `worn weapon modifiers apply to its own swing and spell damage but not other weapon swings, ring applies to all`() {
    val swordLabel = Label("//t:custom_sword")
    val daggerLabel = Label("//t:custom_dagger")
    val ringLabel = Label("//t:custom_ring")
    val host = RecordingHost()
    host.modifiers["sword_boost"] = { _, _, base -> (base as Int) + 10 }
    host.modifiers["ring_boost"] = { _, _, base -> (base as Int) + 1 }
    val f =
      Fixture(
        scripts = host,
        protos =
          Prototypes(
            T.rooms,
            T.mobs,
            T.items +
              listOf(
                ItemProto(
                  swordLabel,
                  "sword",
                  listOf("sword"),
                  wear = "wield",
                  dice = Dice(1, 6),
                  modifiers = mapOf(ModifierPoint.DAMAGE to mod("sword_boost")),
                ),
                ItemProto(
                  daggerLabel,
                  "dagger",
                  listOf("dagger"),
                  wear = "offhand",
                  dice = Dice(1, 4),
                ),
                ItemProto(
                  ringLabel,
                  "ring",
                  listOf("ring"),
                  wear = "finger",
                  modifiers = mapOf(ModifierPoint.DAMAGE to mod("ring_boost")),
                ),
              ),
            T.skills,
            zones = listOf(ZoneProto("t")),
          ),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    val sword = f.w.spawnItem(swordLabel, ItemLocation.Carried(p.id))
    f.w.moveItem(sword, ItemLocation.Equipped(p.id, "wield"))
    val dagger = f.w.spawnItem(daggerLabel, ItemLocation.Carried(p.id))
    f.w.moveItem(dagger, ItemLocation.Equipped(p.id, "offhand"))
    val ring = f.w.spawnItem(ringLabel, ItemLocation.Carried(p.id))
    f.w.moveItem(ring, ItemLocation.Equipped(p.id, "finger_l"))

    // 1. Sword's own swing (slot = "wield", weapon = sword) -> both sword and ring apply
    val swordSwingCtx = RulesContext(f.w, p, d, weapon = sword, slot = "wield")
    assertEquals(21, f.w.damageChain(swordSwingCtx, base = 10).value)

    // 2. Offhand dagger swing (slot = "offhand", weapon = dagger) -> sword skipped, ring applies
    val daggerSwingCtx = RulesContext(f.w, p, d, weapon = dagger, slot = "offhand")
    assertEquals(11, f.w.damageChain(daggerSwingCtx, base = 10).value)

    // 3. Spell damage (slot = null) -> outside swings, sword and ring both apply
    val spellCtx = RulesContext(f.w, p, d, skill = T.skills[0], damageType = DamageType(f.fs.magic))
    assertEquals(21, f.w.damageChain(spellCtx, base = 10).value)
  }

  @Test
  fun `a worn weapon's to_hit modifiers and item affect modifiers apply only to its own swing`() {
    val swordLabel = Label("//t:custom_sword")
    val daggerLabel = Label("//t:custom_dagger")
    val host = RecordingHost()
    host.modifiers["sword_aim"] = { _, _, base -> (base as Int) + 10 }
    host.modifiers["sword_glow"] = { _, _, base -> (base as Int) + 3 }
    val f =
      Fixture(
        scripts = host,
        protos =
          Prototypes(
            T.rooms,
            T.mobs,
            T.items +
              listOf(
                ItemProto(
                  swordLabel,
                  "sword",
                  listOf("sword"),
                  wear = "wield",
                  dice = Dice(1, 6),
                  modifiers = mapOf(ModifierPoint.TO_HIT to mod("sword_aim")),
                  affects =
                    listOf(
                      ItemAffect(
                        "glow",
                        AffectScope.CARRIED,
                        modifiers = mapOf(ModifierPoint.DAMAGE to mod("sword_glow")),
                      )
                    ),
                ),
                ItemProto(
                  daggerLabel,
                  "dagger",
                  listOf("dagger"),
                  wear = "offhand",
                  dice = Dice(1, 4),
                ),
              ),
            T.skills,
            zones = listOf(ZoneProto("t")),
          ),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    val sword = f.w.spawnItem(swordLabel, ItemLocation.Carried(p.id))
    f.w.moveItem(sword, ItemLocation.Equipped(p.id, "wield"))
    val dagger = f.w.spawnItem(daggerLabel, ItemLocation.Carried(p.id))
    f.w.moveItem(dagger, ItemLocation.Equipped(p.id, "offhand"))

    val swordSwing = RulesContext(f.w, p, d, weapon = sword, slot = "wield")
    assertEquals(f.w.rules.toHit(swordSwing) + 10, f.w.toHitChain(swordSwing).value)
    assertEquals(13, f.w.damageChain(swordSwing, base = 10).value)

    val daggerSwing = RulesContext(f.w, p, d, weapon = dagger, slot = "offhand")
    assertEquals(f.w.rules.toHit(daggerSwing), f.w.toHitChain(daggerSwing).value)
    assertEquals(10, f.w.damageChain(daggerSwing, base = 10).value)
  }

  @Test
  fun `lag chains run through the actor's modifiers and clamp at 0`() {
    val host = RecordingHost()
    var delta = -10
    host.modifiers["mod_lag"] = { _, _, base -> (base as Int) + delta }
    val f = Fixture(scripts = host)
    val p = f.player("Ann")
    val other = f.mob(T.DUMMY)
    val lines = mutableListOf<String>()
    f.w.onRules = { _, line -> lines += line }
    f.w.applyAffect(
      p,
      "slow_advance",
      10_000,
      modifiers = mapOf(ModifierPoint.ADVANCE_LAG to mod("mod_lag")),
    )
    val ctx = RulesContext(f.w, p, other, band = Band.NEAR)
    assertEquals(0, f.w.advanceLagOf(ctx))
    assertTrue(
      "rules.advance_lag base=2 → affect:slow_advance -8 → clamp 0" in lines,
      lines.toString(),
    )

    lines.clear()
    delta = 3
    assertEquals(5, f.w.advanceLagOf(ctx))
    assertTrue(lines.none { "clamp" in it }, lines.toString())
    assertTrue(
      lines.any { it.contains("rules.advance_lag base=2 → affect:slow_advance 5") },
      lines.toString(),
    )
  }
}
