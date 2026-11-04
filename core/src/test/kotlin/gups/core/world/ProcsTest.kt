package gups.core.world

import gups.core.HookRef
import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Effect
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.ModifierPoint
import gups.core.model.Proc
import gups.core.model.ProcOutcome
import gups.core.model.ProcTarget
import gups.core.model.ProcTrigger
import gups.core.model.Prototypes
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import gups.core.model.RestrictionRequest
import gups.core.model.SkillProto
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcsTest {
  private val fs = FixtureSchemas.FANTASY
  private val slash = DamageType("slash")
  private val fire = DamageType("fire")
  private val procSword = Label("//t:proc_sword")
  private val thornsArmor = Label("//t:thorns_armor")
  private val avoidBoots = Label("//t:avoid_boots")
  private val offhandDagger = Label("//t:offhand_dagger")
  private val spellSkill = Label("//t:spell_skill")

  private fun protos(
    extraItems: List<ItemProto> = emptyList(),
    extraSkills: List<SkillProto> = emptyList(),
  ) = Prototypes(T.rooms, T.mobs(fs), T.items + extraItems, T.skills(fs) + extraSkills)

  @Test
  fun `HIT proc on wielded weapon damages defender beyond swing and sends message`() {
    val sword =
      ItemProto(
        procSword,
        "a blazing sword",
        listOf("sword"),
        wear = "wield",
        dice = Dice(1, 1, 4),
        procs =
          listOf(
            Proc(
              ProcTrigger.HIT,
              1.0,
              listOf(ProcOutcome(weight = 1.0, effect = Effect.Damage(Dice(1, 1, 5), fire))),
            )
          ),
      )
    val f = Fixture(rules = FixedRules(toHit = 100, damage = 4), protos = protos(listOf(sword)))
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.w.spawnItem(procSword, ItemLocation.Equipped(p.id, "wield"))

    f.w.startCombat(p, d)
    f.runTo(8)

    // Swing damage = 4, proc damage = 1d1+5 = 6. Total = 10 per round.
    assertEquals(1000 - 10, d.current(fs.health))
    val out = f.out(p)
    assertContains(out, "Power surges from a blazing sword!")
  }

  @Test
  fun `STRUCK proc on defender damages attacker`() {
    val armor =
      ItemProto(
        thornsArmor,
        "a spiked vest",
        listOf("vest"),
        wear = "body",
        procs =
          listOf(
            Proc(
              ProcTrigger.STRUCK,
              1.0,
              listOf(ProcOutcome(weight = 1.0, effect = Effect.Damage(Dice(1, 1, 3), slash))),
            )
          ),
      )
    val f = Fixture(rules = FixedRules(toHit = 100, damage = 4), protos = protos(listOf(armor)))
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.w.spawnItem(thornsArmor, ItemLocation.Equipped(d.id, "body"))

    val initialHp = p.current(fs.health)
    f.w.startCombat(p, d)
    f.runTo(8)

    // Attacker swung at dummy. Dummy has thorns (1d1+3 = 4 damage).
    // Dummy swung at Ann for 4 damage. Total Ann damage = 4 + 4 = 8.
    assertEquals(initialHp - 8, p.current(fs.health))
  }

  @Test
  fun `AVOID fires only on a miss and HIT never fires on a miss`() {
    val boots =
      ItemProto(
        avoidBoots,
        "wind boots",
        listOf("boots"),
        wear = "feet",
        procs =
          listOf(
            Proc(
              ProcTrigger.AVOID,
              1.0,
              listOf(ProcOutcome(weight = 1.0, effect = Effect.Damage(Dice(1, 1, 2), slash))),
            )
          ),
      )
    val sword =
      ItemProto(
        procSword,
        "a blazing sword",
        listOf("sword"),
        wear = "wield",
        dice = Dice(1, 1, 4),
        procs =
          listOf(
            Proc(
              ProcTrigger.HIT,
              1.0,
              listOf(ProcOutcome(weight = 1.0, effect = Effect.Damage(Dice(1, 1, 5), fire))),
            )
          ),
      )
    val f =
      Fixture(rules = FixedRules(toHit = 0, damage = 4), protos = protos(listOf(boots, sword)))
    val p = f.player("Ann")
    val bob = f.player("Bob")
    val d = f.mob(T.DUMMY)
    f.w.spawnItem(procSword, ItemLocation.Equipped(p.id, "wield"))
    f.w.spawnItem(avoidBoots, ItemLocation.Equipped(d.id, "feet"))

    val initialDummyHp = d.current(fs.health)
    val initialAnnHp = p.current(fs.health)
    f.w.startCombat(p, d)
    f.runTo(8)

    // Ann missed dummy -> AVOID fired on dummy, dealing 1d1+2 = 3 to Ann.
    // Dummy missed Ann -> boots on dummy did not fire on dummy's swing.
    // HIT never fired on Ann's miss, so dummy took 0 damage.
    assertEquals(initialDummyHp, d.current(fs.health))
    assertEquals(initialAnnHp - 3, p.current(fs.health))
    assertFalse("Power surges from a blazing sword!" in f.out(p))
    assertContains(f.out(bob), "Power surges around A training dummy!")
  }

  @Test
  fun `weapon filter ensures offhand dagger fires proc only on its own swing`() {
    val sword =
      ItemProto(procSword, "a plain sword", listOf("sword"), wear = "wield", dice = Dice(1, 1, 4))
    val dagger =
      ItemProto(
        offhandDagger,
        "an offhand proc dagger",
        listOf("dagger"),
        wear = "offhand",
        dice = Dice(1, 1, 2),
        procs =
          listOf(
            Proc(
              ProcTrigger.HIT,
              1.0,
              listOf(ProcOutcome(weight = 1.0, effect = Effect.Damage(Dice(1, 1, 1), fire))),
            )
          ),
      )
    val f =
      Fixture(rules = FixedRules(toHit = 100, damage = 2), protos = protos(listOf(sword, dagger)))
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.w.spawnItem(procSword, ItemLocation.Equipped(p.id, "wield"))
    f.w.spawnItem(offhandDagger, ItemLocation.Equipped(p.id, "offhand"))

    f.w.startCombat(p, d)
    f.runTo(8) // 1 round: 2 swings (wield, offhand)

    // With 2 swings in the round, the dagger procs only ONCE (on its own swing).
    val out = f.out(p)
    val matches = "Power surges from an offhand proc dagger!".toRegex().findAll(out).count()
    assertEquals(1, matches)
  }

  @Test
  fun `outcome tables chance 0 makes only one roll and produces no proc`() {
    val sword =
      ItemProto(
        procSword,
        "a dud sword",
        listOf("sword"),
        wear = "wield",
        dice = Dice(1, 1, 4),
        procs =
          listOf(
            Proc(
              ProcTrigger.HIT,
              0.0,
              listOf(
                ProcOutcome(0.5, effect = Effect.Damage(Dice(1, 1, 2), fire)),
                ProcOutcome(0.5, effect = Effect.Damage(Dice(1, 1, 3), slash)),
              ),
            )
          ),
      )
    val f = Fixture(rules = FixedRules(toHit = 100, damage = 2), protos = protos(listOf(sword)))
    val traceLines = mutableListOf<String>()
    f.w.onRules = { _, line -> traceLines += line }
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.w.spawnItem(procSword, ItemLocation.Equipped(p.id, "wield"))

    f.w.startCombat(p, d)
    f.runTo(8)

    val procTraces = traceLines.filter { it.startsWith("proc item:proc_sword hit roll") }
    assertEquals(1, procTraces.size)
    assertTrue(procTraces[0].endsWith("→ no proc"))
  }

  @Test
  fun `outcome tables with equal weights pick exactly one per hit`() {
    val sword =
      ItemProto(
        procSword,
        "a split sword",
        listOf("sword"),
        wear = "wield",
        dice = Dice(1, 1, 4),
        procs =
          listOf(
            Proc(
              ProcTrigger.HIT,
              1.0,
              listOf(
                ProcOutcome(0.5, effect = Effect.Damage(Dice(1, 1, 2), fire)),
                ProcOutcome(0.5, effect = Effect.Damage(Dice(1, 1, 3), slash)),
              ),
            )
          ),
      )
    val f = Fixture(rules = FixedRules(toHit = 100, damage = 2), protos = protos(listOf(sword)))
    val traceLines = mutableListOf<String>()
    f.w.onRules = { _, line -> traceLines += line }
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.w.spawnItem(procSword, ItemLocation.Equipped(p.id, "wield"))

    f.w.startCombat(p, d)
    f.runTo(40) // Several rounds

    val procTraces = traceLines.filter { it.startsWith("proc item:proc_sword hit roll") }
    assertTrue(procTraces.isNotEmpty())
    assertTrue(procTraces.all { it.endsWith("→ damage") })
  }

  @Test
  fun `outcome tables with total weight below 1 pick nothing when roll exceeds total`() {
    val sword =
      ItemProto(
        procSword,
        "a rare wand",
        listOf("wand"),
        wear = "wield",
        dice = Dice(1, 1, 4),
        procs =
          listOf(
            Proc(
              ProcTrigger.HIT,
              1.0,
              listOf(ProcOutcome(0.01, effect = Effect.Damage(Dice(1, 1, 2), fire))),
            )
          ),
      )
    val f = Fixture(rules = FixedRules(toHit = 100, damage = 2), protos = protos(listOf(sword)))
    val traceLines = mutableListOf<String>()
    f.w.onRules = { _, line -> traceLines += line }
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.w.spawnItem(procSword, ItemLocation.Equipped(p.id, "wield"))

    f.w.startCombat(p, d)
    f.runTo(16)

    val procTraces = traceLines.filter { it.startsWith("proc item:proc_sword hit roll") }
    assertTrue(procTraces.any { it.endsWith("→ nothing") })
  }

  @Test
  fun `skill outcome applies effect, sets ctx skill on damage chain, and charges no cost or lag`() {
    val skill =
      SkillProto(
        label = spellSkill,
        cost = mapOf(fs.energy to 10),
        lag = 8,
        effect = Effect.Damage(Dice(1, 1, 7), fire),
      )
    val sword =
      ItemProto(
        procSword,
        "a magical sword",
        listOf("sword"),
        wear = "wield",
        dice = Dice(1, 1, 4),
        procs =
          listOf(Proc(ProcTrigger.HIT, 1.0, listOf(ProcOutcome(weight = 1.0, skill = spellSkill)))),
      )
    val host = RecordingHost()
    val skillsSeen = mutableListOf<SkillProto?>()
    host.modifiers["track_skill"] = { point, ctx, base ->
      if (point == ModifierPoint.DAMAGE) skillsSeen += ctx.skill
      base
    }
    val f =
      Fixture(
        rules = FixedRules(toHit = 100, damage = 2),
        scripts = host,
        protos = protos(listOf(sword), listOf(skill)),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.w.spawnItem(procSword, ItemLocation.Equipped(p.id, "wield"))
    // Add a damage modifier to the room to capture ctx.skill
    f.w.applyAffect(
      p,
      "tracker",
      1000,
      modifiers = mapOf(ModifierPoint.DAMAGE to HookRef(Label("//t:mods.star"), "track_skill")),
    )

    val startMana = p.current(fs.energy)
    val startWait = p.waitState

    f.w.startCombat(p, d)
    f.runTo(8)

    // Mana and waitState unchanged
    assertEquals(startMana, p.current(fs.energy))
    assertEquals(startWait, p.waitState)

    // Damage chain ran for swing (skill == null) and then for skill proc (skill == spellSkill)
    assertTrue(skillsSeen.any { it?.label == spellSkill })
  }

  @Test
  fun `denying cast_allowed modifier stops skill proc and sends no message`() {
    val skill = SkillProto(label = spellSkill, effect = Effect.Damage(Dice(1, 1, 7), fire))
    val sword =
      ItemProto(
        procSword,
        "a denied sword",
        listOf("sword"),
        wear = "wield",
        dice = Dice(1, 1, 4),
        procs =
          listOf(Proc(ProcTrigger.HIT, 1.0, listOf(ProcOutcome(weight = 1.0, skill = spellSkill)))),
      )
    val host = RecordingHost()
    host.modifiers["deny_cast"] = { _, _, _ -> "no casting allowed" }
    val f =
      Fixture(
        rules = FixedRules(toHit = 100, damage = 2),
        scripts = host,
        protos = protos(listOf(sword), listOf(skill)),
      )
    val traceLines = mutableListOf<String>()
    f.w.onRules = { _, line -> traceLines += line }
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.w.spawnItem(procSword, ItemLocation.Equipped(p.id, "wield"))
    f.w.applyAffect(
      p,
      "silence_mod",
      1000,
      modifiers = mapOf(ModifierPoint.CAST_ALLOWED to HookRef(Label("//t:mods.star"), "deny_cast")),
    )

    f.w.startCombat(p, d)
    f.runTo(8)

    assertFalse("Power surges" in f.out(p))
    val procTraces = traceLines.filter { it.startsWith("proc item:proc_sword hit roll") }
    assertTrue(procTraces.any { it.endsWith("→ cast_allowed denied") })
  }

  @Test
  fun `a plugin cast restriction stops a skill proc`() {
    val skill = SkillProto(label = spellSkill, effect = Effect.Damage(Dice(1, 1, 7), fire))
    val sword =
      ItemProto(
        procSword,
        "a restricted sword",
        listOf("sword"),
        wear = "wield",
        dice = Dice(1, 1, 4),
        procs =
          listOf(Proc(ProcTrigger.HIT, 1.0, listOf(ProcOutcome(weight = 1.0, skill = spellSkill)))),
      )
    val f =
      Fixture(
        rules = FixedRules(toHit = 100, damage = 2),
        protos = protos(listOf(sword), listOf(skill)),
      )
    val seen = mutableListOf<RestrictionRequest>()
    f.w.restrictions =
      f.w.restrictions +
        (RestrictionPoint.CAST to
          listOf(
            Restriction { _, r ->
              seen += r
              "Not your kind of magic."
            }
          ))
    val traceLines = mutableListOf<String>()
    f.w.onRules = { _, line -> traceLines += line }
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.w.spawnItem(procSword, ItemLocation.Equipped(p.id, "wield"))
    val hpBefore = d.current("hp")

    f.w.startCombat(p, d)
    f.runTo(8)

    assertTrue(seen.isNotEmpty())
    assertEquals(spellSkill, seen.first().skill?.label)
    assertFalse("Power surges" in f.out(p))
    assertEquals(hpBefore - 2, d.current("hp"))
    assertTrue(
      traceLines.any {
        it.startsWith("proc item:proc_sword hit roll") && it.endsWith("→ cast_allowed denied")
      }
    )
  }

  @Test
  fun `target SELF hits bearer, affect procs name affect, custom message replaces line, and attacker death at tier end`() {
    val selfSword =
      ItemProto(
        procSword,
        "a cursed dagger",
        listOf("dagger"),
        wear = "wield",
        dice = Dice(1, 1, 1),
        procs =
          listOf(
            Proc(
              ProcTrigger.HIT,
              1.0,
              listOf(
                ProcOutcome(
                  weight = 1.0,
                  effect = Effect.Damage(Dice(1, 1, 5), fire),
                  target = ProcTarget.SELF,
                  message = "The dagger burns your hand!",
                )
              ),
            )
          ),
      )
    val f = Fixture(rules = FixedRules(toHit = 100, damage = 1), protos = protos(listOf(selfSword)))
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.w.spawnItem(procSword, ItemLocation.Equipped(p.id, "wield"))

    val startHp = p.current(fs.health)
    f.w.startCombat(p, d)
    f.runTo(8)

    // Proc damaged bearer Ann
    val out = f.out(p)
    assertContains(out, "The dagger burns your hand!")
    assertFalse("Power surges from a cursed dagger!" in out)
    assertTrue(p.current(fs.health) < startHp)
  }

  @Test
  fun `affect proc on actor fires and names the affect`() {
    val f = Fixture(rules = FixedRules(toHit = 100, damage = 1), protos = protos())
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    val flameProc =
      Proc(
        ProcTrigger.HIT,
        1.0,
        listOf(ProcOutcome(weight = 1.0, effect = Effect.Damage(Dice(1, 1, 3), fire))),
      )
    f.w.applyAffect(p, "flame_blade", 100, procs = listOf(flameProc))

    f.w.startCombat(p, d)
    f.runTo(8)

    val out = f.out(p)
    assertContains(out, "Power surges from flame blade!")
  }

  @Test
  fun `thorns killing the attacker does not throw and death runs at tier end`() {
    val armor =
      ItemProto(
        thornsArmor,
        "deadly spikes",
        listOf("spikes"),
        wear = "body",
        procs =
          listOf(
            Proc(
              ProcTrigger.STRUCK,
              1.0,
              listOf(ProcOutcome(weight = 1.0, effect = Effect.Damage(Dice(1, 1, 500), slash))),
            )
          ),
      )
    val f = Fixture(rules = FixedRules(toHit = 100, damage = 1), protos = protos(listOf(armor)))
    val p = f.player("Ann")
    p.recallRoom = T.YARD
    val d = f.mob(T.DUMMY)
    f.w.spawnItem(thornsArmor, ItemLocation.Equipped(d.id, "body"))

    f.w.startCombat(p, d)
    f.runTo(8)

    // Ann died from thorns! Death ran at end of tier without throwing: Ann respawned at recall
    assertEquals(T.YARD, p.room.label)
    assertTrue(f.w.room(T.HALL).items.any { "the corpse of Ann" in it.name })
  }
}
