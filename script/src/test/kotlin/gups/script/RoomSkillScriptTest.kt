package gups.script

import gups.core.Label
import gups.core.rules.RulesContext
import gups.core.world.World
import gups.core.world.toHitChain
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Script-level `room` skill target: declaration checks and casting. */
class RoomSkillScriptTest {
  private fun fixture(content: String): ScriptFixture =
    ScriptFixture(
      mapOf(
        "rules/package.star" to gups.script.testing.TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to
          gups.script.testing.TestWorlds.rules("def to_hit(ctx):\n    return 50\n"),
        "zones/z/package.star" to zonePkg(),
        "zones/z/content.star" to content,
      ),
      load = listOf("zones/z", "rules"),
    )

  private fun load(content: String, wiring: WorldPluginWiring = WorldPluginWiring.NONE) =
    ScriptLoader(
        WorldDir(
          tempWorld(mapOf("zones/z/package.star" to zonePkg(), "zones/z/content.star" to content))
        ),
        ScriptLimits.HARNESS,
        plugins = wiring,
      )
      .load(listOf("zones/z"))

  @Test
  fun `a room skill loads and casts, enchanting the room with its modifier and tags`() {
    val f =
      fixture(
        """
        def _mod(ctx, base):
            return base + 100
        skill("consecrate", target = "room", effect = apply_affect("holy", 20, add_tags = ["consecrated"], modifiers = {"to_hit": _mod}))
        room("start", tags = ["stone"])
        room("next")
        mob("rat", vitals = vitals(hp = 100))
        """
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val start = f.w.room(Label("//zones/z:start"))
    val c = { RulesContext(f.w, p, rat) }
    assertEquals(50, f.w.toHitChain(c()).value, "the room is not enchanted yet")

    p.skills[Label("//zones/z:consecrate")] = 100
    f.input(p, "cast consecrate")
    f.run()

    assertEquals(1, start.affects.size, "the cast put an affect on the room")
    assertEquals("holy", start.affects[0].name)
    assertTrue("consecrated" in start.tagsAt(f.w.pulse), "the room gained the added tag")
    val inStart = f.w.toHitChain(c())
    assertEquals(150, inStart.value, "the room's modifier changes the chain")
    assertContains(inStart.text, "room-affect:holy")

    f.input(p, "skills")
    f.run()
    assertContains(f.out(p), "consecrate", message = "the room skill is listed")
  }

  @Test
  fun `an actor primitive on a room skill fails to load naming the primitive`() {
    val r =
      load(
        """
        skill("bad", target = "room", effect = damage("1d6"))
        room("start")
        """
      )
    assertFalse(r.ok)
    assertContains(
      r.report(),
      "skill(effect): damage acts on actors; a room skill may use apply_affect, dispel, sequence and plugin primitives",
    )
  }

  @Test
  fun `a plugin primitive on a room skill loads`() {
    val wiring =
      object : WorldPluginWiring {
        override val globals: Map<String, Any> =
          mapOf("banish" to pluginEffect("curse", "banish", emptyMap()))

        override fun install(world: World) = Unit

        override fun started(world: World) = Unit
      }
    val r =
      load(
        """
        skill("ward", target = "room", effect = banish)
        room("start")
        """,
        wiring,
      )
    assertTrue(r.ok, r.report())
  }

  @Test
  fun `actor-only fields on a room skill's apply_affect fail to load`() {
    for ((body, message) in
      listOf(
        "stat_deltas = stats(dex = 1)" to
          "apply_affect(stat_deltas): a room's affect can't change stats",
        "sense_deltas = {\"spell\": 3}" to
          "apply_affect(sense_deltas): a room's affect can't change senses",
        "max_vital_deltas = vitals(hp = 5)" to
          "apply_affect(max_vital_deltas): a room's affect can't change maximum vitals",
        "resist_deltas = resists(fire = 10)" to
          "apply_affect(resist_deltas): a room's affect can't change resists",
        "procs = [proc(on = \"hit\", effect = damage(\"1d1\"))]" to
          "apply_affect(procs): a room's affect can't have procs",
        "applies = \"worn\"" to "apply_affect(applies): only item affects take applies",
      )) {
      val r =
        load(
          "skill(\"bad\", target = \"room\", effect = apply_affect(\"h\", 5, $body))\nroom(\"start\")"
        )
      assertFalse(r.ok, "$body must fail")
      assertContains(r.report(), "skill(effect): $message")
    }
  }

  @Test
  fun `item use_target room fails to load`() {
    val r =
      load(
        """
        item("wand", use_effect = apply_affect("h", 5), use_target = "room")
        room("start")
        """
      )
    assertFalse(r.ok)
    assertContains(r.report(), "item(use_target): 'room' is not a valid use target")
  }

  @Test
  fun `a sequence of two room applies loads and casts both`() {
    val f =
      fixture(
        """
        skill("double", target = "room", effect = sequence(apply_affect("a", 20, add_tags = ["ta"]), apply_affect("b", 20, add_tags = ["tb"])))
        room("start")
        """
      )
    val p = f.player()
    p.skills[Label("//zones/z:double")] = 100
    f.input(p, "cast double")
    f.run()
    val start = f.w.room(Label("//zones/z:start"))
    assertEquals(2, start.affects.size, "both applies ran")
    assertTrue("ta" in start.tagsAt(f.w.pulse))
    assertTrue("tb" in start.tagsAt(f.w.pulse))
  }

  @Test
  fun `a non-room skill whose apply_affect adds tags fails to load`() {
    val r =
      load(
        """
        skill("bad", target = "self", effect = apply_affect("h", 5, add_tags = ["warded"]))
        room("start")
        """
      )
    assertFalse(r.ok)
    assertContains(r.report(), "skill(effect): apply_affect(add_tags) is only for room skills")
  }
}
