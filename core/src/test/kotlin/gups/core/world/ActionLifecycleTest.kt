package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Effect
import gups.core.model.Prototypes
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class ActionLifecycleTest(private val fs: FixtureSchema) {
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

  private fun caster(): Triple<Fixture, gups.core.model.Player, gups.core.model.Mob> {
    val f = Fixture(rules = FixedRules(toHit = 0))
    val p = f.player("Ann")
    p.skills[T.BOLT] = 100
    val g = f.mob(T.DUMMY, index = 2)
    return Triple(f, p, g)
  }

  @Test
  fun `onlookers who know the spell recognize it at the warmup, others don't`() {
    val (f, p, _) = caster()
    val adept = f.player("Bea", index = 1)
    adept.skills[T.BOLT] = 50
    val novice = f.player("Cal", index = 1)
    f.input(p, "cast bolt dummy")
    f.run()
    assertContains(f.out(adept), "Ann begins casting bolt.")
    val seen = f.out(novice)
    assertContains(seen, "Ann begins casting.")
    assertFalse("begins casting bolt" in seen)
  }

  @Test
  fun `bystanders see a completed cast, including an instant one`() {
    val f = Fixture(rules = FixedRules(toHit = 0))
    val p = f.player("Ann")
    p.skills[T.ZAP] = 100
    val g = f.mob(T.DUMMY, index = 2)
    val onlooker = f.player("Bea", index = 1)
    f.input(p, "cast zap dummy")
    f.run()
    assertTrue(g.current(fs.health) < 1000, "the instant cast resolved")
    assertContains(f.out(onlooker), "Ann casts zap at ${g.name}.")
    assertFalse("Ann casts zap" in f.out(p), "the caster gets its own message only")
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
    assertEquals(1000, g.current(fs.health))
    f.run() // pulse 5: resolves
    assertFalse(p.isCasting)
    assertEquals(1000 - 8, g.current(fs.health))
    assertEquals(40, p.current(fs.energy))
    assertEquals(4, p.waitState)
    assertTrue(g.id in p.fighting, "a hostile skill landing starts combat")
    f.run(4)
    assertEquals(0, p.waitState)
  }

  @Test
  fun `an enemy skill with no harmful effect still starts combat`() {
    val hex =
      SkillProto(
        Label("//t:hex"),
        kind = "spell",
        range = SkillRange.FAR,
        effect = Effect.ApplyAffect("hexed", 10, flags = setOf("silenced")),
      )
    val f =
      Fixture(
        rules = FixedRules(toHit = 0),
        protos = Prototypes(T.rooms, T.mobs, T.items, T.skills + hex),
      )
    val p = f.player("Ann")
    p.skills[hex.label] = 100
    val g = f.mob(T.DUMMY, index = 2)
    f.input(p, "cast hex dummy")
    f.run()
    assertTrue(g.hasFlag("silenced"))
    assertTrue(g.id in p.fighting)
    assertTrue(p.id in g.fighting)
  }

  @Test
  fun `an ally skill landing on another actor does not start combat`() {
    val f = Fixture(rules = FixedRules(toHit = 0))
    val p = f.player("Ann")
    p.skills[T.MEND] = 100
    val g = f.mob(T.DUMMY, index = 2)
    f.input(p, "cast mend dummy")
    f.run()
    assertFalse(p.isEngaged)
    assertFalse(g.isEngaged)
  }

  @Test
  fun `stuns and displacement start combat, outside of any skill`() {
    for (effect in listOf(Effect.Stun(3), Effect.Knockback(1), Effect.Pull(1))) {
      val f = Fixture(rules = FixedRules(toHit = 0))
      val p = f.player("Ann")
      val g = f.mob(T.DUMMY, index = 2)
      f.w.applyEffect(effect, p, g)
      assertTrue(g.id in p.fighting, "$effect engages")
      assertTrue(p.id in g.fighting, "$effect engages both sides")
    }
  }

  @Test
  fun `a stun an actor puts on itself does not start combat`() {
    val f = Fixture(rules = FixedRules(toHit = 0))
    val p = f.player("Ann")
    f.w.applyEffect(Effect.Stun(3), p, p)
    assertFalse(p.isEngaged)
  }

  @Test
  fun `damage during the warmup interrupts with half lag and no cost`() {
    val (f, p, g) = caster()
    f.input(p, "cast bolt dummy")
    f.run(2)
    f.hurt(p, 4, g) // 8% of 50: below the threshold
    assertTrue(p.isCasting)
    f.hurt(p, 5, g) // 10%
    assertFalse(p.isCasting)
    assertEquals(2, p.waitState)
    assertEquals(50, p.current(fs.energy))
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
    assertEquals(0, p.waitState, "no recovery while stunned")
    f.run(2)
    assertTrue(p.isStunned)
    assertEquals(0, p.waitState)
    f.run() // the stun expires in the timer phase, then the action phase counts down one pulse
    assertFalse(p.isStunned)
    assertEquals(1, p.waitState, "the broken cast's half lag applies once the stun ends")
    assertEquals(1000, g.current(fs.health), "the interrupted cast never resolves")
    assertEquals(50, p.current(fs.energy))
  }

  @Test
  fun `a stun pauses the warmup of a cast it doesn't interrupt`() {
    val skills =
      T.skills.map {
        if (it.label == T.BOLT) it.copy(interrupt = gups.core.model.Interrupt(stun = false)) else it
      }
    val f =
      Fixture(
        rules = FixedRules(toHit = 0),
        protos = gups.core.model.Prototypes(T.rooms, T.mobs, T.items, skills),
      )
    val p = f.player("Ann")
    p.skills[T.BOLT] = 100
    val g = f.mob(T.DUMMY, index = 2)
    f.input(p, "cast bolt dummy")
    f.run(2) // warmup started at pulse 1, due at pulse 5
    f.w.applyEffect(gups.core.model.Effect.Stun(3), g, p)
    assertTrue(p.isCasting)
    f.run(3) // the stun ends at pulse 5, the cast's original end
    assertTrue(p.isCasting, "the warmup didn't run during the stun")
    assertEquals(1000, g.current(fs.health))
    f.run(3)
    assertFalse(p.isCasting)
    assertEquals(
      1000 - 8,
      g.current(fs.health),
      "the cast resolves once its paused pulses have passed",
    )
  }

  @Test
  fun `a stun holds recovery aside and restores it in full when it ends`() {
    val f = Fixture(rules = FixedRules(toHit = 0))
    val p = f.player("Ann")
    val g = f.mob(index = 2)
    p.waitState = 4
    f.w.applyAffect(p, "stun", 3, flags = setOf("stunned"))
    assertEquals(0, p.waitState, "the stun removes recovery")
    f.run(2)
    assertEquals(0, p.waitState)
    f.w.removeAffect(p, "stun")
    assertEquals(4, p.waitState, "recovery did not count down during the stun")
    f.input(p, "advance goblin")
    f.run(4)
    assertEquals(0, p.trackIndex, "the queued action still waits out the restored recovery")
    f.run()
    assertEquals(1, p.trackIndex)
    assertEquals(2, g.trackIndex)
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
    assertEquals(50, p.current(fs.energy))
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

  @Test
  fun `a silenced actor can't speak, so mobs never hear it`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_SAY] = { _, _, _ -> null }
    host.handlers[HookName.ON_TELL] = { _, _, _ -> null }
    val f = Fixture(scripts = host)
    val p = f.player("Ann")
    val bob = f.player("Bob")
    f.mob(T.DUMMY)
    f.w.applyAffect(p, "hexed", 10, flags = setOf("silenced"))
    f.w.flushOutput()
    f.out(bob)
    for (line in listOf("say hi", "tell dummy hi", "whisper bob hi", "shout hi", "gossip hi")) {
      f.input(p, line)
      f.w.flushOutput()
      assertTrue("You can't get the words out!" in f.out(p), line)
    }
    assertEquals("", f.out(bob), "nobody hears a word")
    assertEquals(emptyList(), host.calls, "no on_say or on_tell fires")
    f.w.removeAffect(p, "hexed")
    f.input(p, "say hi")
    f.input(p, "tell dummy hi")
    assertEquals(
      listOf(
        "on_say:The Hall",
        "on_say:Bob",
        "on_say:a training dummy",
        "on_tell:a training dummy",
      ),
      host.calls,
    )
  }

  @Test
  fun `re-applying an affect only replaces it when the new one lasts longer`() {
    val f = Fixture()
    val p = f.player("Ann")
    val long = f.w.applyAffect(p, "stun", 10, flags = setOf("stunned"))
    f.run(2)
    val short = f.w.applyAffect(p, "stun", 3, flags = setOf("stunned"))
    assertTrue(short === long, "a shorter stun leaves the longer one in place")
    assertEquals(f.w.pulse + 8, p.affects.single().expiresAt)
    val longer = f.w.applyAffect(p, "stun", 9, flags = setOf("stunned"))
    assertTrue(longer !== long, "a stun that outlasts the old one replaces it")
    assertEquals(f.w.pulse + 9, p.affects.single().expiresAt)
    f.w.applyEffect(Effect.Stun(1), p, p)
    assertEquals(f.w.pulse + 9, p.affects.single().expiresAt, "the stun primitive follows suit")
  }
}
