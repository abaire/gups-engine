package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Effect
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.ModifierPoint
import gups.core.model.Prototypes
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.TargetKind
import gups.core.model.ZoneProto
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpellLandingTest {
  private val cloakLabel = Label("//t:cloak")
  private val enemySkillLabel = Label("//t:zap_enemy")
  private val roomSkillLabel = Label("//t:blast_room")
  private val allySkillLabel = Label("//t:zap_ally")

  private var resolverRan = false

  private fun enemySkill(fs: FixtureSchema) =
    SkillProto(
      enemySkillLabel,
      kind = "spell",
      cost = mapOf(fs.energy to 5),
      lag = 3,
      range = SkillRange.FAR,
      target = TargetKind.ENEMY,
      effect = Effect.Damage(Dice(1, 1, 10), DamageType(fs.magic)),
      resolve = SkillResolver { _, _, _, _, _ -> resolverRan = true },
    )

  private fun roomSkill(fs: FixtureSchema) =
    SkillProto(
      roomSkillLabel,
      kind = "spell",
      cost = mapOf(fs.energy to 5),
      lag = 3,
      range = SkillRange.ROOM,
      target = TargetKind.ENEMY,
      effect = Effect.Damage(Dice(1, 1, 10), DamageType(fs.magic)),
    )

  private fun allySkill(fs: FixtureSchema) =
    SkillProto(
      allySkillLabel,
      kind = "spell",
      cost = mapOf(fs.energy to 5),
      lag = 3,
      range = SkillRange.FAR,
      target = TargetKind.ALLY,
      effect = Effect.Damage(Dice(1, 1, 10), DamageType(fs.magic)),
    )

  private fun protos(fs: FixtureSchema, extraItems: List<ItemProto> = emptyList()) =
    Prototypes(
      T.rooms,
      T.mobs,
      T.items +
        listOf(
          ItemProto(
            cloakLabel,
            "a magic ward cloak",
            listOf("cloak"),
            wear = "body",
            modifiers = mapOf(ModifierPoint.DEFEND_HIT to mod("ward")),
          )
        ) +
        extraItems,
      T.skills + listOf(enemySkill(fs), roomSkill(fs), allySkill(fs)),
      zones = listOf(ZoneProto("t")),
    )

  @Test
  fun `default fixture enemy spell lands and damages with auto LAND and no roll`() {
    val f = Fixture(protos = protos(FixtureSchemas.current))
    val p = f.player("Caster")
    p.skills[enemySkillLabel] = 100
    val d = f.mob(T.DUMMY)
    val dummyStartHp = d.current(f.fs.health)

    val rulesTraces = mutableListOf<String>()
    f.w.onRules = { _, line -> rulesTraces += line }

    f.input(p, "cast zap_enemy dummy")
    f.run()

    assertTrue(d.current(f.fs.health) < dummyStartHp, "damage was applied")
    assertTrue(resolverRan, "skill resolve ran")
    assertTrue(
      rulesTraces.any { it.endsWith("→ auto LAND") },
      "trace shows auto LAND: $rulesTraces",
    )
  }

  @Test
  fun `victim with defend_hit modifier returning 0 resists spell`() {
    val host = RecordingHost()
    host.modifiers["ward"] = { _, _, _ -> 0 }
    var onCastCount = 0
    host.handlers[HookName.ON_CAST] = { _, owner, ctx ->
      if (owner == ctx.actor) onCastCount++
      null
    }

    val f = Fixture(scripts = host, protos = protos(FixtureSchemas.current))
    val caster = f.player("Caster")
    caster.skills[enemySkillLabel] = 100
    val victim = f.player("Victim")
    val bystander = f.player("Bystander")

    // Equip victim with ward cloak
    f.w.moveItem(
      f.w.spawnItem(cloakLabel, ItemLocation.Carried(victim.id)),
      ItemLocation.Equipped(victim.id, "body"),
    )

    val victimStartHp = victim.current(f.fs.health)
    val startEnergy = caster.current(f.fs.energy)

    val rulesTraces = mutableListOf<String>()
    f.w.onRules = { _, line -> rulesTraces += line }

    f.input(caster, "cast zap_enemy victim")
    f.run()

    assertEquals(victimStartHp, victim.current(f.fs.health), "victim took no damage")
    assertFalse(resolverRan, "skill resolve did not run")
    assertEquals(startEnergy - 5, caster.current(f.fs.energy), "cost was paid")
    assertTrue(caster.waitState >= 3, "lag was applied")
    assertTrue(victim.id in caster.fighting, "caster engaged in combat")
    assertTrue(caster.id in victim.fighting, "victim engaged in combat")

    assertContains(f.out(caster), "Victim resists your zap_enemy.")
    assertContains(f.out(victim), "You resist Caster's zap_enemy.")
    assertContains(f.out(bystander), "Victim resists Caster's zap_enemy.")
    assertEquals(1, onCastCount, "on_cast fired once")

    assertTrue(
      rulesTraces.any { "→ defender:defend_hit →" in it && it.endsWith("RESIST") },
      "trace shows defender:defend_hit and RESIST: $rulesTraces",
    )
  }

  @Test
  fun `to_hit modifier on caster sees c skill set and weapon and slot null`() {
    val host = RecordingHost()
    var seenSkill: SkillProto? = null
    var seenWeapon: Any? = "not-called"
    var seenSlot: Any? = "not-called"
    host.modifiers["boost"] = { _, c, base ->
      seenSkill = c.skill
      seenWeapon = c.weapon
      seenSlot = c.slot
      base
    }

    val ringLabel = Label("//t:ring")
    val ring =
      ItemProto(
        ringLabel,
        "a ring of focus",
        listOf("ring"),
        wear = "finger",
        modifiers = mapOf(ModifierPoint.TO_HIT to mod("boost")),
      )
    val f = Fixture(scripts = host, protos = protos(FixtureSchemas.current, listOf(ring)))
    val caster = f.player("Caster")
    caster.skills[enemySkillLabel] = 100
    val victim = f.mob(T.DUMMY)

    f.w.moveItem(
      f.w.spawnItem(ringLabel, ItemLocation.Carried(caster.id)),
      ItemLocation.Equipped(caster.id, "finger_l"),
    )

    f.input(caster, "cast zap_enemy dummy")
    f.run()

    assertNotNull(seenSkill)
    assertEquals(enemySkillLabel, seenSkill?.label)
    assertNull(seenWeapon)
    assertNull(seenSlot)
  }

  @Test
  fun `room range enemy skill against two victims rolls per victim`() {
    val host = RecordingHost()
    host.modifiers["ward"] = { _, _, _ -> 0 }

    val f = Fixture(scripts = host, protos = protos(FixtureSchemas.current))
    val caster = f.player("Caster")
    caster.skills[roomSkillLabel] = 100

    val victim1 = f.mob(T.DUMMY)
    val victim2 = f.mob(T.GOBLIN)

    // Only victim1 has ward cloak
    f.w.moveItem(
      f.w.spawnItem(cloakLabel, ItemLocation.Carried(victim1.id)),
      ItemLocation.Equipped(victim1.id, "body"),
    )

    val hp1 = victim1.current(f.fs.health)
    val hp2 = victim2.current(f.fs.health)

    f.input(caster, "cast blast_room dummy")
    f.run()

    assertEquals(hp1, victim1.current(f.fs.health), "victim1 resisted")
    assertTrue(victim2.current(f.fs.health) < hp2, "victim2 took damage")
  }

  @Test
  fun `ally skill with damage effect on another actor never rolls to hit`() {
    val host = RecordingHost()
    var toHitCalled = false
    host.modifiers["ward"] = { _, _, base ->
      toHitCalled = true
      base
    }

    val f = Fixture(scripts = host, protos = protos(FixtureSchemas.current))
    val caster = f.player("Caster")
    caster.skills[allySkillLabel] = 100
    val ally = f.player("Ally")

    f.w.moveItem(
      f.w.spawnItem(cloakLabel, ItemLocation.Carried(ally.id)),
      ItemLocation.Equipped(ally.id, "body"),
    )

    val allyStartHp = ally.current(f.fs.health)

    f.input(caster, "cast zap_ally ally")
    f.run()

    assertTrue(ally.current(f.fs.health) < allyStartHp, "the ally skill was cast and damaged")
    assertFalse(toHitCalled, "to_hit/defend_hit modifier was never called for ally skill")
  }
}
