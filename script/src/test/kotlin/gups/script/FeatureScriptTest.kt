package gups.script

import gups.core.model.Band
import gups.core.rules.RulesContext
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.awardXp
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureScriptTest {
  private fun without(vararg off: Feature): Features =
    Features(
      Feature.entries.toSet() -
        off.toSet() -
        Feature.entries.filter { f -> f.requires.any { it in off } }.toSet()
    )

  private fun idsOf(features: Features): String = features.ids.joinToString(", ") { "\"$it\"" }

  /** Files of a world with [features], the template rules cut down to them, and [zone] files. */
  private fun world(features: Features, vararg zone: Pair<String, String>): Map<String, String> =
    mapOf(
      "MODULE.gups" to TestWorlds.module("features = [${idsOf(features)}]"),
      "rules/package.star" to TestWorlds.RULES_PACKAGE,
      "rules/combat.star" to TestWorlds.rulesFor(features.ids),
      "zones/z/package.star" to zonePkg(),
    ) + zone

  private fun load(files: Map<String, String>): LoadResult =
    ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(listOf("zones/z", "rules"))

  /**
   * Loading [source] (and the [more] files) in a world without [off] fails with "[what] needs the
   * feature", and loads with every feature on.
   */
  private fun refused(
    off: Feature,
    source: String,
    what: String,
    more: Map<String, String> = emptyMap(),
  ) {
    val files = mapOf("zones/z/a.star" to source) + more
    val r = load(world(without(off), *files.toList().toTypedArray()))
    assertTrue(
      r.errors.any { it.pkg == "zones/z" && "$what needs the ${off.id} feature" in it.message },
      "without ${off.id}: ${r.report()}",
    )
    val on = load(world(Features.ALL, *files.toList().toTypedArray()))
    assertTrue(on.errors.none { it.pkg == "zones/z" }, "with ${off.id}: ${on.report()}")
  }

  @Test
  fun `a skill declaration needs skills`() {
    refused(Feature.SKILLS, "skill(\"zap\")", "skill()")
  }

  @Test
  fun `the skills package kind needs skills`() {
    val files =
      world(without(Feature.SKILLS), "zones/z/a.star" to "room(\"start\")") +
        ("skills/package.star" to "package(api_version = 1, kind = \"skills\")")
    val r = ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(listOf("skills"))
    assertContains(r.errors.single().message, "package kind 'skills' needs the skills feature")
  }

  @Test
  fun `track declaration arguments need the track`() {
    refused(Feature.TRACK, "room(\"start\", size = SIZE.large)", "room(size)")
    refused(
      Feature.TRACK,
      "room(\"a\", exits = [exit(\"north\", \":b\", entry_index = 1)])\nroom(\"b\")",
      "exit(entry_index)",
    )
    refused(
      Feature.TRACK,
      "mob(\"m\")\nroom(\"start\", spawns = [spawn(\":m\", at_index = 1)])",
      "spawn(at_index)",
    )
    refused(Feature.TRACK, "item(\"bow\", range = \"far\")", "item(range)")
  }

  @Test
  fun `mob skills need skills and mob level needs leveling`() {
    refused(
      Feature.SKILLS,
      "mob(\"m\", skills = {\":zap\": 50})",
      "mob(skills)",
      mapOf("zones/z/b.star" to "skill(\"zap\")"),
    )
    refused(Feature.LEVELING, "mob(\"m\", level = 3)", "mob(level)")
  }

  @Test
  fun `hooks of a feature need it`() {
    refused(
      Feature.COMBAT,
      "def f(ctx):\n    pass\nmob(\"m\", before_attack = f)",
      "hook 'before_attack'",
    )
    refused(
      Feature.COMBAT,
      "def f(ctx):\n    pass\nmob(\"m\", on_combat_tick = f)",
      "hook 'on_combat_tick'",
    )
    for (hook in listOf("before_cast", "on_cast_start", "on_cast")) refused(
      Feature.SKILLS,
      "def f(ctx):\n    pass\nmob(\"m\", $hook = f)",
      "hook '$hook'",
    )
  }

  @Test
  fun `a mob, a room and an item can declare on_level_change, and another kind of entity can't`() {
    val r =
      load(
        world(
          Features.ALL,
          "zones/z/a.star" to
            """
            def f(ctx):
                pass

            room("start", on_level_change = f)
            mob("m", on_level_change = f)
            item("i", on_level_change = f)
            """,
        )
      )
    assertTrue(r.ok, "room, mob and item can declare on_level_change: ${r.report()}")

    val exitInvalid =
      load(
        world(
          Features.ALL,
          "zones/z/a.star" to
            """
            def f(ctx):
                pass

            room("start", exits = [exit("north", ":other", on_level_change = f)])
            room("other")
            """,
        )
      )
    assertFalse(exitInvalid.ok)
    assertTrue(
      exitInvalid.errors.any { "exit(): unexpected argument 'on_level_change'" in it.message }
    )

    val skillInvalid =
      load(
        world(
          Features.ALL,
          "zones/z/a.star" to
            """
            def f(ctx):
                pass

            skill("s", on_level_change = f)
            """,
        )
      )
    assertFalse(skillInvalid.ok)
    assertTrue(
      skillInvalid.errors.any { "skill(): unexpected argument 'on_level_change'" in it.message }
    )
  }

  @Test
  fun `declaring on_level_change without leveling fails to load naming the feature`() {
    for (decl in
      listOf(
        "mob(\"m\", on_level_change = f)",
        "room(\"start\", on_level_change = f)",
        "item(\"i\", on_level_change = f)",
      )) {
      refused(Feature.LEVELING, "def f(ctx):\n    pass\n$decl", "hook 'on_level_change'")
    }
  }

  @Test
  fun `behaviors of a feature need it`() {
    refused(Feature.COMBAT, "mob(\"m\", behaviors = [aggressive()])", "aggressive()")
    refused(Feature.COMBAT, "mob(\"m\", behaviors = [assist(\"m\")])", "assist()")
    refused(Feature.COMBAT, "mob(\"m\", behaviors = [flee(vitals(hp = 10))])", "flee()")
    refused(
      Feature.LEVELING,
      "mob(\"m\", behaviors = [aggressive(unless_level_above = 3)])",
      "aggressive(unless_level_above)",
    )
  }

  @Test
  fun `modifier points of a feature need it`() {
    refused(
      Feature.COMBAT,
      "def f(ctx, base):\n    return base\nitem(\"ring\", wear = \"finger\", modifiers = {\"to_hit\": f})",
      "item(modifiers)[\"to_hit\"]",
    )
    refused(
      Feature.SKILLS,
      "def f(ctx, base):\n    return base\nroom(\"start\", modifiers = {\"cast_allowed\": f})",
      "room(modifiers)[\"cast_allowed\"]",
    )
  }

  @Test
  fun `effect primitives need skills, and knockback and pull need the track`() {
    refused(Feature.SKILLS, "x = stun(3)", "stun()")
    refused(Feature.SKILLS, "x = sequence()", "sequence()")
    refused(Feature.TRACK, "x = knockback(1)", "knockback()")
    refused(Feature.TRACK, "x = pull(1)", "pull()")
  }

  @Test
  fun `melee near and far skill ranges need the track, the others don't`() {
    val noTrack = without(Feature.TRACK)
    for (range in listOf("melee", "near", "far")) {
      val r =
        load(
          world(
            noTrack,
            "zones/z/a.star" to "skill(\"s\", range = \"$range\", effect = damage(\"1d4\"))",
          )
        )
      assertContains(r.errors.first { it.pkg == "zones/z" }.message, "needs the track feature")
    }
    for (range in listOf("self", "room")) {
      val r =
        load(
          world(
            noTrack,
            "zones/z/a.star" to "skill(\"s\", range = \"$range\", effect = damage(\"1d4\"))",
          )
        )
      assertTrue(r.errors.none { it.pkg == "zones/z" }, r.report())
    }
    val omitted = load(world(noTrack, "zones/z/a.star" to "skill(\"s\", effect = damage(\"1d4\"))"))
    assertTrue(omitted.errors.none { it.pkg == "zones/z" }, omitted.report())
  }

  @Test
  fun `rules exports of a feature that is off are forbidden and required when on`() {
    val all = TestWorlds.rulesFor(Features.ALL.ids)
    for ((feature, exports) in
      mapOf(
        Feature.COMBAT to listOf("to_hit", "damage", "initiative", "flee_chance", "flee_fail_lag"),
        Feature.SKILLS to listOf("cast_allowed", "interrupt_check"),
        Feature.TRACK to
          listOf("retreat_check", "get_lag", "advance_lag", "retreat_lag", "retreat_fail_lag"),
        Feature.LEVELING to listOf("xp_for_kill", "xp_to_level", "on_level_up"),
      )) {
      val off = without(feature)
      // Defining the export with the feature off is a load error naming the feature.
      val over =
        world(off, "zones/z/a.star" to "room(\"start\")") +
          ("rules/combat.star" to TestWorlds.rulesFor(off.ids) + "\n" + exportsOf(all, exports))
      val r = ScriptLoader(WorldDir(tempWorld(over)), ScriptLimits.HARNESS).load(listOf("rules"))
      val messages = r.errors.map { it.message }
      for (e in exports) assertTrue(
        messages.any { "defines $e(...)" in it && "needs the ${feature.id} feature" in it },
        "$e without ${feature.id}: $messages",
      )
      // With the feature on, leaving one out is the missing-export error.
      val missing =
        world(Features.ALL, "zones/z/a.star" to "room(\"start\")") +
          ("rules/combat.star" to TestWorlds.rulesFor(off.ids))
      val m = ScriptLoader(WorldDir(tempWorld(missing)), ScriptLimits.HARNESS).load(listOf("rules"))
      for (e in exports) assertTrue(
        m.errors.any { "must define $e(...)" in it.message },
        "$e is required with ${feature.id}",
      )
    }
  }

  private fun exportsOf(source: String, names: List<String>): String =
    Regex("(?m)^def (\\w+)\\(.*(?:\\n(?![a-z]).*)*")
      .findAll(source)
      .filter { it.groupValues[1] in names }
      .joinToString("\n") { it.value }

  @Test
  fun `a world's rules without the exports of its off features load and dry-run`() {
    for (off in listOf(Feature.COMBAT, Feature.SKILLS, Feature.TRACK, Feature.LEVELING)) {
      val r = load(world(without(off), "zones/z/a.star" to "room(\"start\")"))
      assertTrue(r.ok, "without ${off.id}: ${r.report()}")
    }
    val r = load(world(Features(emptySet()), "zones/z/a.star" to "room(\"start\")"))
    assertTrue(r.ok, r.report())
  }

  @Test
  fun `xp_loss in on_player_death is a rules error without leveling`() {
    val bad =
      TestWorlds.rulesFor(without(Feature.LEVELING).ids)
        .replace("\"vitals\": vitals(hp = 1),", "\"vitals\": vitals(hp = 1), \"xp_loss\": 5,")
    val r =
      load(
        world(without(Feature.LEVELING), "zones/z/a.star" to "room(\"start\")") +
          ("rules/combat.star" to bad)
      )
    assertTrue(
      r.errors.any { "xp_loss" in it.message && "needs the leveling feature" in it.message },
      r.report(),
    )
  }

  @Test
  fun `level_loss without leveling is refused at load`() {
    val bad =
      TestWorlds.rulesFor(without(Feature.LEVELING).ids)
        .replace("\"vitals\": vitals(hp = 1),", "\"vitals\": vitals(hp = 1), \"level_loss\": 1,")
    val r =
      load(
        world(without(Feature.LEVELING), "zones/z/a.star" to "room(\"start\")") +
          ("rules/combat.star" to bad)
      )
    assertTrue(
      r.errors.any { "level_loss" in it.message && "needs the leveling feature" in it.message },
      r.report(),
    )
  }

  @Test
  fun `a world with leveling off that defines on_level_up fails to load naming the feature`() {
    val r =
      load(
        world(without(Feature.LEVELING), "zones/z/a.star" to "room(\"start\")") +
          ("rules/combat.star" to
            TestWorlds.rulesFor(without(Feature.LEVELING).ids) +
              "\ndef on_level_up(ctx):\n    return None\n")
      )
    assertTrue(
      r.errors.any {
        "defines on_level_up(...)" in it.message && "needs the leveling feature" in it.message
      },
      r.report(),
    )
  }

  // ---- ctx verbs and view fields -------------------------------------------------------------

  /** Runs [body] in a hook when a player enters, in a world without [off]; returns the error. */
  private fun runtimeError(off: Feature, body: String): String? {
    val f =
      ScriptFixture(
        world(
          without(off),
          "zones/z/a.star" to
            """
            def _enter(ctx):
                a = ctx.actor
                $body

            room("start", exits = [exit("north", ":hall")])
            room("hall", on_enter = _enter, exits = [exit("south", ":start")])
            mob("goblin")
            """,
        )
      )
    val p = f.player()
    f.mob("//zones/z:goblin")
    f.input(p, "north")
    f.run(2)
    return f.diagnostics("script_error").firstOrNull()?.message
  }

  @Test
  fun `view fields and ctx verbs of a feature that is off raise the feature error`() {
    val cases =
      listOf(
        Triple(Feature.LEVELING, "x = a.level", "actor.level needs the leveling feature"),
        Triple(Feature.LEVELING, "x = a.xp", "actor.xp needs the leveling feature"),
        Triple(
          Feature.LEVELING,
          "x = a.pending_levels",
          "actor.pending_levels needs the leveling feature",
        ),
        Triple(
          Feature.LEVELING,
          "ctx.award_xp(a, 10)",
          "ctx.award_xp() needs the leveling feature",
        ),
        Triple(Feature.LEVELING, "ctx.take_xp(a, 10)", "ctx.take_xp() needs the leveling feature"),
        Triple(Feature.LEVELING, "ctx.level_up(a)", "ctx.level_up() needs the leveling feature"),
        Triple(
          Feature.LEVELING,
          "ctx.level_down(a)",
          "ctx.level_down() needs the leveling feature",
        ),
        Triple(Feature.TRACK, "x = a.track_index", "actor.track_index needs the track feature"),
        Triple(Feature.TRACK, "x = a.band_of(a)", "actor.band_of needs the track feature"),
        Triple(Feature.TRACK, "x = ctx.room.size", "room.size needs the track feature"),
        Triple(Feature.SKILLS, "x = a.skills", "actor.skills needs the skills feature"),
        Triple(Feature.SKILLS, "x = a.casting", "actor.casting needs the skills feature"),
        Triple(Feature.SKILLS, "x = a.is_casting", "actor.is_casting needs the skills feature"),
        Triple(Feature.COMBAT, "x = a.fighting()", "actor.fighting needs the combat feature"),
        Triple(
          Feature.COMBAT,
          "ctx.start_combat(a, a)",
          "ctx.start_combat() needs the combat feature",
        ),
        Triple(Feature.COMBAT, "ctx.stop_combat(a)", "ctx.stop_combat() needs the combat feature"),
        Triple(
          Feature.COMBAT,
          "x = ctx.enemies_near(a, \"far\")",
          "ctx.enemies_near() needs the combat feature",
        ),
        Triple(Feature.COMBAT, "ctx.act_attack(a, a)", "ctx.act_attack() needs the combat feature"),
        Triple(
          Feature.SKILLS,
          "ctx.act_cast(a, \":x\")",
          "ctx.act_cast() needs the skills feature",
        ),
        Triple(Feature.TRACK, "ctx.act_advance(a, a)", "ctx.act_advance() needs the track feature"),
        Triple(Feature.TRACK, "ctx.act_retreat(a)", "ctx.act_retreat() needs the track feature"),
        Triple(
          Feature.TRACK,
          "ctx.teleport(a, \":start\", index = 1)",
          "ctx.teleport(index) needs the track feature",
        ),
      )
    for ((off, body, expected) in cases) {
      val message = runtimeError(off, body)
      assertTrue(message != null && expected in message, "$body without ${off.id}: $message")
    }
  }

  @Test
  fun `the rules ctx band is None without the track`() {
    val probe = "def regen(ctx):\n    return vitals(hp = 1 if ctx.band == None else 99)\n"
    fun hp(features: Features): Int {
      val f =
        ScriptFixture(
          world(features, "zones/z/a.star" to "room(\"start\")") +
            ("rules/combat.star" to TestWorlds.rulesFor(features.ids, probe))
        )
      val ctx = RulesContext(f.w, f.player(), band = Band.NEAR)
      return f.w.rules.regen(ctx).getValue("hp")
    }
    assertEquals(99, hp(Features.ALL))
    assertEquals(1, hp(without(Feature.TRACK)))
  }

  @Test
  fun `a view of a feature that is on works`() {
    val f =
      ScriptFixture(
        world(
          Features.ALL,
          "zones/z/a.star" to
            """
            def _enter(ctx):
                ctx.send(ctx.actor, "level " + str(ctx.actor.level) + " at " + str(ctx.actor.track_index))

            room("start", exits = [exit("north", ":hall")])
            room("hall", on_enter = _enter)
            """,
        )
      )
    val p = f.player()
    f.input(p, "north")
    f.run(2)
    assertContains(f.out(p), "level 1 at 0")
    assertFalse(f.diagnostics("script_error").isNotEmpty())
  }

  @Test
  fun `pending_levels counts the owed levels in deferred mode, and is 0 after an auto award`() {
    val zoneFiles =
      "zones/z/a.star" to
        """
        def _check(ctx):
            ctx.send(ctx.actor, "pending: " + str(ctx.actor.pending_levels))

        room("start", commands = {"check": _check})
        """

    val fDeferred =
      ScriptFixture(
        world(Features.ALL, zoneFiles) +
          mapOf("MODULE.gups" to TestWorlds.module("level_mode = \"deferred\""))
      )
    val pDef = fDeferred.player()
    fDeferred.input(pDef, "check")
    fDeferred.run(1)
    assertContains(fDeferred.out(pDef), "pending: 0")

    fDeferred.w.awardXp(pDef, 1000)
    fDeferred.input(pDef, "check")
    fDeferred.run(1)
    assertContains(fDeferred.out(pDef), "pending: 1")

    fDeferred.w.awardXp(pDef, 2000)
    fDeferred.input(pDef, "check")
    fDeferred.run(1)
    assertContains(fDeferred.out(pDef), "pending: 2")
    assertEquals(1, pDef.level)

    val fAuto = ScriptFixture(world(Features.ALL, zoneFiles))
    val pAuto = fAuto.player()
    fAuto.w.awardXp(pAuto, 3000)
    assertEquals(3, pAuto.level)
    fAuto.input(pAuto, "check")
    fAuto.run(1)
    assertContains(fAuto.out(pAuto), "pending: 0")
  }
}
