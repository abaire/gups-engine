package gups.core.world

import gups.core.Role
import gups.core.model.ModifierPoint
import gups.core.model.Prototypes
import gups.core.model.Trait
import gups.core.model.TraitModifier
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraitEffectsTest {
  private val f = Fixture()
  private val fs = f.fs

  private val strong =
    Trait(
      "strong",
      statDeltas = mapOf(fs.strength to 3),
      maxVitalDeltas = mapOf(fs.health to 10),
      resistDeltas = mapOf(fs.magic to 25),
    )

  init {
    f.w.traits =
      mapOf(
        "strong" to strong,
        "mute" to Trait("mute", flags = setOf("silenced")),
        "undead" to Trait("undead", flags = setOf("no_regen")),
      )
  }

  @Test
  fun `stat, max-vital and resist deltas show in effective values while base values stay`() {
    val p = f.player("Ann", hp = 50)
    val stat = p.stat(fs.strength)
    val resist = p.resist(fs.magic)
    assertNull(f.w.grantTrait(p, "strong"))
    assertEquals(stat + 3, p.stat(fs.strength))
    assertEquals(stat, p.baseStat(fs.strength))
    assertEquals(60, p.max(fs.health))
    assertEquals(50, p.baseMax(fs.health))
    assertEquals(resist + 25, p.resist(fs.magic))
    assertEquals(resist, p.baseResist(fs.magic))
    assertEquals(50, p.current(fs.health), "gaining a trait leaves current")
  }

  @Test
  fun `losing a trait caps current at the new maximum, and invariant 3 holds every pulse`() {
    val p = f.player("Ann", hp = 50)
    f.w.grantTrait(p, "strong")
    f.setVital(p, fs.health, 60)
    assertEquals(60, p.current(fs.health))
    repeat(5) {
      f.run()
      assertTrue(f.w.invariantViolations().isEmpty())
    }
    assertNull(f.w.removeTrait(p, "strong"))
    assertEquals(50, p.max(fs.health))
    assertEquals(50, p.current(fs.health))
    repeat(5) {
      f.run()
      assertTrue(f.w.invariantViolations().isEmpty())
    }
  }

  @Test
  fun `trait flags count for has_flag and silence casting`() {
    val p = f.player("Ann")
    p.skills[T.ZAP] = 100
    f.mob()
    f.w.grantTrait(p, "mute")
    assertTrue(p.hasFlag("silenced"))
    assertTrue("silenced" in p.flags)
    f.input(p, "cast zap goblin")
    f.run()
    assertContains(f.out(p), "You can't get the words out!")
    assertNull(p.activity)
  }

  @Test
  fun `a no_regen trait stops regeneration`() {
    val ann = f.player("Ann", hp = 50)
    val bob = f.player("Bob", hp = 50)
    f.w.grantTrait(bob, "undead")
    f.setVital(ann, fs.health, 10)
    f.setVital(bob, fs.health, 10)
    f.run(200)
    assertTrue(ann.current(fs.health) > 10)
    assertEquals(10, bob.current(fs.health))
  }

  @Test
  fun `trait modifiers run after room modifiers and before affects, in name order, as trait-name`() {
    val host = RecordingHost()
    for (n in listOf("room", "aff")) host.modifiers[n] = { _, _, base -> (base as Int) + 1 }
    val g =
      Fixture(
        rules = FixedRules(toHit = 50),
        scripts = host,
        protos =
          Prototypes(
            listOf(T.rooms[0].copy(modifiers = mapOf(ModifierPoint.TO_HIT to mod("room")))) +
              T.rooms.drop(1),
            T.mobs,
            T.items,
            T.skills,
          ),
      )
    val double = TraitModifier { _, base -> (base as Int) * 2 }
    val plusOne = TraitModifier { _, base -> (base as Int) + 1 }
    g.w.traits =
      mapOf(
        "zealous" to Trait("zealous", modifiers = mapOf(ModifierPoint.TO_HIT to double)),
        "able" to Trait("able", modifiers = mapOf(ModifierPoint.TO_HIT to plusOne)),
      )
    val p = g.player("Ann")
    g.w.grantTrait(p, "zealous")
    g.w.grantTrait(p, "able")
    g.w.applyAffect(p, AffectSpec("x", 100, modifiers = mapOf(ModifierPoint.TO_HIT to mod("aff"))))
    val r = g.w.toHitChain(RulesContext(g.w, p, g.mob(T.DUMMY)))
    assertEquals(
      "rules.to_hit base=50 → room:hall 51 → trait:able 52 → trait:zealous 104 → affect:x 105",
      r.text,
    )
    assertEquals(105, r.value)
  }

  @Test
  fun `a throwing trait modifier leaves the value and records a diagnostic`() {
    f.w.traits =
      mapOf(
        "cursed" to
          Trait(
            "cursed",
            modifiers = mapOf(ModifierPoint.TO_HIT to TraitModifier { _, _ -> error("hex") }),
          )
      )
    val p = f.player("Ann")
    f.w.grantTrait(p, "cursed")
    val r = f.w.toHitChain(RulesContext(f.w, p, f.mob(T.DUMMY)))
    assertEquals("rules.to_hit base=100 → trait:cursed 100", r.text)
    assertTrue(f.w.diagnostics.any { it.kind == "plugin" && "hex" in it.message })
  }

  @Test
  fun `a mob's prototype traits change its stats, maxima and resists, and refuse removal`() {
    val g =
      Fixture(
        protos =
          Prototypes(
            T.rooms,
            T.mobs(fs).map { if (it.label == T.GOBLIN) it.copy(traits = setOf("strong")) else it },
            T.items,
            T.skills(fs),
          )
      )
    g.w.traits = mapOf("strong" to strong)
    val goblin = g.mob()
    assertTrue(goblin.hasTrait("strong"))
    assertEquals(goblin.baseStat(fs.strength) + 3, goblin.stat(fs.strength))
    assertEquals(goblin.baseMax(fs.health) + 10, goblin.max(fs.health))
    assertEquals(goblin.baseResist(fs.magic) + 25, goblin.resist(fs.magic))
    assertEquals("Trait 'strong' was not granted directly", g.w.removeTrait(goblin, "strong"))
    assertTrue(goblin.hasTrait("strong"))
  }

  @Test
  fun `granting and removing traits refuse what doesn't apply and throw for unknown names`() {
    val p = f.player("Ann")
    assertNull(f.w.grantTrait(p, "strong"))
    assertEquals("Trait 'strong' already granted", f.w.grantTrait(p, "strong"))
    assertNull(f.w.removeTrait(p, "strong"))
    assertEquals("Trait 'strong' was not granted directly", f.w.removeTrait(p, "strong"))
    assertFalse(p.hasTrait("strong"))
    assertFailsWith<IllegalArgumentException> { f.w.grantTrait(p, "nope") }
    assertFailsWith<IllegalArgumentException> { f.w.removeTrait(p, "nope") }
  }

  @Test
  fun `an unknown granted trait is kept and ignored, and @stat marks it`() {
    val p = f.player("Ann")
    p.role = Role.INSTRUCTOR
    p.grantedTraits = setOf("strong", "ghostly")
    p.recomputeTraits(f.w)
    assertEquals(listOf("strong"), p.traits.map { it.name })
    assertEquals(setOf("strong", "ghostly"), p.grantedTraits)
    f.out(p)
    f.input(p, "@stat #${p.id.v}")
    val out = f.out(p)
    assertContains(out, "Granted traits: ghostly (unknown), strong")
    assertContains(out, "Traits: strong (granted)")
  }
}
