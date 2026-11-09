package gups.core.world

import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Channel
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Effect
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.Trait
import gups.core.model.TraitEvent
import gups.core.model.TraitHandler
import gups.core.rules.RulesContext
import gups.core.rules.RulesEngine
import gups.core.rules.StartingStats
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CastRecognitionTest {
  /** Answers recognizes_skill with [answer], remembering who was asked. */
  private class AskedRules(base: RulesEngine, private val answer: Boolean?) : RulesEngine by base {
    val asked = mutableListOf<String>()

    override fun recognizesSkill(c: RulesContext): Boolean? {
      asked += c.requireActor().name
      return answer
    }
  }

  private val none = mapOf(Channel.SIGHT to 0, Channel.SOUND to 0)

  private fun rules(answer: Boolean? = null, spellToHit: Int = 100) =
    AskedRules(FixedRules(spellToHit = spellToHit), answer)

  private class Scene(val f: Fixture, val rules: AskedRules) {
    val ann = f.player("Ann")
    val dee = f.player("Dee", index = 2)
    val bea = f.player("Bea", index = 1)
  }

  private fun scene(answer: Boolean? = null, spellToHit: Int = 100): Scene {
    val r = rules(answer, spellToHit)
    return Scene(Fixture(rules = r), r).also { it.ann.skills[T.BOLT] = 100 }
  }

  private fun Scene.cast(skill: String = "bolt") {
    f.input(ann, "cast $skill dee")
    f.run(6)
  }

  @Test
  fun `a known skill is named at warmup and resolve with no rules call`() {
    val s = scene(answer = true)
    s.bea.skills[T.BOLT] = 50
    s.dee.skills[T.BOLT] = 50
    s.cast()
    val bea = s.f.out(s.bea)
    assertContains(bea, "Ann begins casting bolt.")
    assertContains(bea, "Ann casts bolt at Dee.")
    assertContains(s.f.out(s.dee), "Ann casts bolt at you!")
    assertTrue(s.rules.asked.isEmpty(), "asked ${s.rules.asked}")
  }

  @Test
  fun `an unknown skill with no export is anonymous throughout`() {
    val s = scene()
    s.cast()
    val bea = s.f.out(s.bea)
    assertContains(bea, "Ann begins casting.")
    assertContains(bea, "Ann's attack hits Dee.")
    assertFalse("bolt" in bea)
    val dee = s.f.out(s.dee)
    assertContains(dee, "Ann's attack hits you!")
    assertFalse("bolt" in dee)
  }

  @Test
  fun `the caster's own lines always name the skill`() {
    val s = scene()
    s.cast()
    val ann = s.f.out(s.ann)
    assertContains(ann, "You begin casting bolt.")
    assertContains(ann, "You cast bolt at Dee.")
  }

  @Test
  fun `a landed hit tells anonymous players before the damage lands`() {
    val s = scene()
    s.cast()
    val dee = s.f.out(s.dee)
    val hit = dee.indexOf("Ann's attack hits you!")
    assertTrue(hit >= 0)
    assertTrue(s.dee.current(s.f.fs.health) < 50)
    val after = dee.substring(hit + 1)
    assertTrue(after.isNotBlank(), "the damage report follows the hit line: $dee")
  }

  @Test
  fun `a resisted enemy skill tells anonymous players only the resisted lines`() {
    val s = scene(spellToHit = 0)
    s.cast()
    val dee = s.f.out(s.dee)
    assertContains(dee, "You resist Ann's attack.")
    assertFalse("hits" in dee, dee)
    val bea = s.f.out(s.bea)
    assertContains(bea, "Dee resists Ann's attack.")
    assertFalse("hits" in bea, bea)
  }

  @Test
  fun `a bystander who recognizes a resisted skill gets the named lines as before`() {
    val s = scene(answer = true, spellToHit = 0)
    s.cast()
    val bea = s.f.out(s.bea)
    assertTrue(
      bea.indexOf("Ann casts bolt at Dee.") in 0 until bea.indexOf("Dee resists Ann's bolt."),
      bea,
    )
  }

  @Test
  fun `a room skill gives anonymous bystanders the generic line`() {
    val quake =
      SkillProto(
        Label("//t:quake"),
        kind = "spell",
        range = SkillRange.ROOM,
        effect = Effect.Damage(Dice(1, 1, 4), DamageType("fire")),
      )
    val r = rules()
    val f =
      Fixture(
        rules = r,
        protos = gups.core.model.Prototypes(T.rooms, T.mobs, T.items, T.skills + quake),
      )
    val ann = f.player("Ann").also { it.skills[quake.label] = 100 }
    val bea = f.player("Bea", index = 1)
    f.input(ann, "cast quake bea")
    f.run(3)
    assertContains(f.out(bea), "Ann does something.")
  }

  @Test
  fun `the export is asked once per player across warmup and resolve`() {
    val s = scene(answer = true)
    s.cast()
    val bea = s.f.out(s.bea)
    assertContains(bea, "Ann begins casting bolt.")
    assertContains(bea, "Ann casts bolt at Dee.")
    assertContains(s.f.out(s.dee), "Ann casts bolt at you!")
    assertEquals(listOf("Bea", "Dee"), s.rules.asked.sorted())
  }

  @Test
  fun `a player who arrives mid-warmup is asked at resolve`() {
    val s = scene(answer = true)
    s.f.input(s.ann, "cast bolt dee")
    s.f.run(2)
    val late = s.f.player("Cal", index = 1)
    s.f.run(5)
    assertContains(s.f.out(late), "Ann casts bolt at Dee.")
    assertTrue("Cal" in s.rules.asked)
  }

  @Test
  fun `a blind bystander gets no warmup or room line but a blind target is still told`() {
    val s = scene()
    val blind =
      s.f.w.spawnPlayer("Cal", T.HALL, overrides = StartingStats(senses = none), index = 1).also {
        s.f.connect(it)
      }
    s.cast()
    assertEquals("", s.f.out(blind))
    assertContains(s.f.out(s.dee), "hits you")
    val s2 = scene()
    val eve =
      s2.f.w.spawnPlayer("Eve", T.HALL, overrides = StartingStats(senses = none), index = 3).also {
        s2.f.connect(it)
      }
    s2.f.input(s2.ann, "cast bolt eve")
    s2.f.run(6)
    assertContains(s2.f.out(eve), "hits you")
  }

  private class Call(val bearer: String, val fields: Map<String, Any?>)

  /** Registers a trait whose handler records each call into [calls] and then runs [also]. */
  private fun Scene.watch(
    calls: MutableList<Call>,
    name: String = "watcher",
    also: (Actor) -> Unit = {},
  ) {
    f.w.traits =
      f.w.traits +
        (name to
          Trait(
            name,
            handlers =
              mapOf(
                TraitEvent.SKILL_RECOGNIZED to
                  TraitHandler { _, bearer, event, fields ->
                    assertEquals(TraitEvent.SKILL_RECOGNIZED, event)
                    calls += Call(bearer.name, fields)
                    also(bearer)
                  }
              ),
          ))
  }

  @Test
  fun `a trait handler fires once with the cast's fields for a recognized unknown skill`() {
    val s = scene(answer = true)
    val calls = mutableListOf<Call>()
    s.watch(calls)
    s.f.w.grantTrait(s.bea, "watcher")
    s.cast()
    assertEquals(listOf("Bea"), calls.map { it.bearer })
    val fields = calls.single().fields
    assertEquals(s.ann, fields["source"])
    assertEquals(T.BOLT.v, fields["skill"])
    assertEquals("success", fields["outcome"])
    assertEquals(s.dee, fields["target"])
    assertTrue(fields["power"] is Int)
  }

  @Test
  fun `a resisted cast fires with outcome resist`() {
    val s = scene(answer = true, spellToHit = 0)
    val calls = mutableListOf<Call>()
    s.watch(calls)
    s.f.w.grantTrait(s.bea, "watcher")
    s.cast()
    assertEquals("resist", calls.single().fields["outcome"])
  }

  @Test
  fun `a trait handler doesn't fire for a known skill or a bystander who didn't recognize`() {
    val known = scene(answer = true)
    val knownCalls = mutableListOf<Call>()
    known.watch(knownCalls)
    known.bea.skills[T.BOLT] = 50
    known.f.w.grantTrait(known.bea, "watcher")
    known.cast()
    assertTrue(knownCalls.isEmpty())

    val unrecognized = scene(answer = false)
    val blindCalls = mutableListOf<Call>()
    unrecognized.watch(blindCalls)
    unrecognized.f.w.grantTrait(unrecognized.bea, "watcher")
    unrecognized.cast()
    assertTrue(blindCalls.isEmpty())
  }

  @Test
  fun `handlers fire in the order the players were asked, then by trait name`() {
    val s = scene(answer = true)
    val calls = mutableListOf<Call>()
    s.watch(calls, "b_trait")
    s.watch(calls, "a_trait")
    for (p in listOf(s.bea, s.dee)) {
      s.f.w.grantTrait(p, "b_trait")
      s.f.w.grantTrait(p, "a_trait")
    }
    s.cast()
    assertEquals(4, calls.size)
    val askedOrder = s.rules.asked
    assertEquals(askedOrder.flatMap { listOf(it, it) }, calls.map { it.bearer })
  }

  @Test
  fun `a throwing handler records a diagnostic and the next player's handler still fires`() {
    val s = scene(answer = true)
    val calls = mutableListOf<Call>()
    s.watch(calls) { if (it.name == s.rules.asked.first()) error("boom") }
    s.f.w.grantTrait(s.bea, "watcher")
    s.f.w.grantTrait(s.dee, "watcher")
    s.cast()
    assertEquals(2, calls.size)
    assertTrue(
      s.f.w.diagnostics.any {
        it.kind == "plugin" && "trait 'watcher' handler SKILL_RECOGNIZED threw: boom" in it.message
      },
      s.f.w.diagnostics.toString(),
    )
  }

  @Test
  fun `a handler that teaches the skill to a later player skips that player`() {
    val s = scene(answer = true)
    val calls = mutableListOf<Call>()
    s.watch(calls) { bearer ->
      if (bearer.name == s.rules.asked.first()) {
        val later = if (bearer === s.bea) s.dee else s.bea
        later.skills[T.BOLT] = 10
      }
    }
    s.f.w.grantTrait(s.bea, "watcher")
    s.f.w.grantTrait(s.dee, "watcher")
    s.cast()
    assertEquals(1, calls.size)
  }

  @Test
  fun `learnedBy holds those who recognized the skill without knowing it`() {
    val s = scene(answer = true)
    s.bea.skills[T.BOLT] = 50
    val rec = CastRecognition()
    val bolt = s.f.w.protos.skill(T.BOLT)
    assertTrue(s.f.w.recognizes(s.bea, s.ann, bolt, s.dee, rec))
    assertTrue(s.f.w.recognizes(s.dee, s.ann, bolt, s.dee, rec))
    assertTrue(s.f.w.recognizes(s.dee, s.ann, bolt, s.dee, rec))
    assertEquals(setOf(s.dee.id), rec.learnedBy)
    assertEquals(listOf("Dee"), s.rules.asked)
  }
}
