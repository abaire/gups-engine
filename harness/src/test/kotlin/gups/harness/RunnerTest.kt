package gups.harness

import gups.script.WorldDir
import gups.script.testing.TestWorlds
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunnerTest {
  private fun world(files: Map<String, String>, defaults: Boolean = true): WorldDir {
    val root: Path = Files.createTempDirectory("gups-runner")
    for ((path, text) in if (defaults) TestWorlds.withDefaults(files) else files) {
      root.resolve(path).also { it.parent.createDirectories() }.writeText(text.trimIndent() + "\n")
    }
    return WorldDir(root)
  }

  private val zone =
    mapOf(
      "zones/a/package.star" to "package(api_version = 1, kind = \"zone\")",
      "zones/a/rooms.star" to
        """
            def _fight(ctx):
                ctx.send(ctx.actor, "The bell rings!")

            room("start", name = "Start", exits = [exit("north", ":hall")], commands = {"ring": _fight})
            room("hall", name = "Hall", exits = [exit("south", ":start")])
            mob("rat", vitals = vitals(hp = 8))
        """,
    )

  private fun run(
    files: Map<String, String>,
    vararg patterns: String,
    defaults: Boolean = true,
  ): TestReport = TestRunner(world(files, defaults)).run(patterns.toList())

  @Test
  fun `passing, failing and erroring tests are told apart`() {
    val r =
      run(
        zone +
          ("zones/a/a_test.star" to
            """
                def test_walks(t):
                    w = t.world(seed = 3)
                    p = w.spawn_player("Pat", at = ":start")
                    out = p.do("north")
                    t.contains(out, "Hall")
                    t.eq(p.room, ":hall")
                    t.eq(p.room, "//zones/a:hall")
                    t.ne(p.room, ":start")

                def test_fails(t):
                    w = t.world()
                    p = w.spawn_player("Pat", at = ":start")
                    t.eq(p.do("ring"), "The bell tolls!\n")

                def test_errors(t):
                    x = {}["nope"]

                def helper_not_a_test(t):
                    t.fail("never runs")
            """),
        "//zones/a",
      )
    assertEquals(
      listOf(
        "test_walks" to Status.PASS,
        "test_fails" to Status.FAIL,
        "test_errors" to Status.ERROR,
      ),
      r.results.map { it.name to it.status },
    )
    val fail = r.results[1]
    assertEquals("//zones/a:a_test.star:13", fail.location)
    assertContains(fail.message!!, "- The bell tolls!")
    assertContains(fail.message, "+ The bell rings!")
    assertContains(r.results[2].message!!, "nope")
    assertFalse(r.ok)
    assertEquals("1 passed, 1 failed, 1 errors", r.summary())
  }

  @Test
  fun `patterns select packages, files and single tests`() {
    val files =
      zone +
        mapOf(
          "zones/a/a_test.star" to "def test_one(t):\n    pass\ndef test_two(t):\n    pass",
          "zones/a/b_test.star" to "def test_three(t):\n    pass",
          "zones/b/package.star" to "package(api_version = 1, kind = \"zone\")",
          "zones/b/b_test.star" to "def test_four(t):\n    pass",
        )
    assertEquals(4, run(files, "//...").passed)
    assertEquals(4, run(files, "//zones/...").passed)
    assertEquals(3, run(files, "//zones/a").passed)
    assertEquals(1, run(files, "//zones/a:b_test.star").passed)
    assertEquals(listOf("test_two"), run(files, "//zones/a:test_two").results.map { it.name })
    assertEquals(listOf("no package matches //zones/c"), run(files, "//zones/c").problems)
    assertEquals(
      listOf("'zones/a' is not a label pattern; patterns start with //"),
      run(files, "zones/a").problems,
    )
  }

  @Test
  fun `load failures surface as errors`() {
    val bad =
      run(zone + ("zones/a/a_test.star" to "def test_x(t):\n    t.world()"), "//zones/a")
        .results
        .single()
    assertEquals(Status.PASS, bad.status)
    val broken =
      mapOf(
        "zones/a/package.star" to "package(api_version = 1, kind = \"zone\")",
        "zones/a/rooms.star" to "room(\"start\", exits = [exit(\"north\", \":nowhere\")])",
        "zones/a/a_test.star" to "def test_x(t):\n    t.world()",
      )
    val r = run(broken, "//zones/a").results.single()
    assertEquals(Status.ERROR, r.status)
    assertContains(r.message!!, "//zones/a:nowhere doesn't resolve")
    val syntax =
      run(zone + ("zones/a/a_test.star" to "def test_x(t)\n    pass"), "//zones/a").results.single()
    assertEquals("(load)", syntax.name)
    assertEquals(Status.ERROR, syntax.status)
  }

  @Test
  fun `script errors in hooks show as warnings with a trace on failure`() {
    val files =
      mapOf(
        "zones/a/package.star" to "package(api_version = 1, kind = \"zone\")",
        "zones/a/rooms.star" to
          """
                def _boom(ctx):
                    fail("kaboom")

                room("start", on_enter = _boom, exits = [exit("north", ":start")])
            """,
        "zones/a/a_test.star" to
          """
                def test_x(t):
                    w = t.world()
                    w.trace()
                    p = w.spawn_player("Pat", at = ":start")
                    p.do("north")
                    t.eq(len(w.diagnostics()), 1)
                    t.eq(w.diagnostics()[0].hook, "on_enter")
                    t.fail("show the trace")
            """,
      )
    val r = run(files, "//zones/a").results.single()
    assertEquals(Status.FAIL, r.status)
    assertContains(r.warnings.single(), "//zones/a:rooms.star:2: [script_error] on_enter: kaboom")
    assertContains(r.trace!!, "on_enter on //zones/a:start")
    assertContains(r.render(), "warning:")
  }

  @Test
  fun `the same seed gives the same fight`() {
    val files =
      zone +
        ("zones/a/a_test.star" to
          """
            def _fight(t, seed):
                w = t.world(seed = seed)
                p = w.spawn_player("Pat", at = ":start", vitals = vitals(hp = 30))
                rat = w.spawn(":rat", at = ":start")
                p.do("kill rat")
                w.run(pulses = 40)
                return p.output() + "hp=%d rat=%s" % (p.hp, rat.in_world)

            def test_replay(t):
                t.eq(_fight(t, 9), _fight(t, 9))
                t.contains(_fight(t, 9), "You hit rat.")
        """)
    val r = run(files, "//zones/a")
    assertTrue(r.ok, r.results.joinToString("\n") { it.render() })
  }

  @Test
  fun `force_rng queues the results of later rolls`() {
    val files =
      mapOf(
        "zones/a/package.star" to "package(api_version = 1, kind = \"zone\")",
        "zones/a/rooms.star" to
          """
            def _roll(ctx):
                ctx.send(ctx.actor, "rolled %d" % ctx.rng.int(1, 6))

            room("start", name = "Start", commands = {"roll": _roll})
          """,
        "zones/a/a_test.star" to
          """
            def test_forced(t):
                w = t.world(seed = 1)
                p = w.spawn_player("Pat", at = ":start")
                w.force_rng(ints = [4, 6])
                t.contains(p.do("roll"), "rolled 4")
                t.contains(p.do("roll"), "rolled 6")

            def test_out_of_range(t):
                w = t.world(seed = 1)
                p = w.spawn_player("Pat", at = ":start")
                w.force_rng(ints = [9])
                p.do("roll")
                t.contains(w.diagnostics()[0].message, "the forced value 9 is out of range")

            def test_bad_fraction(t):
                t.world().force_rng(fractions = [1.0])
          """,
      )
    val r = run(files, "//zones/a").results
    assertEquals(Status.PASS, r[0].status, r[0].render())
    assertEquals(Status.PASS, r[1].status, r[1].render())
    assertEquals(Status.ERROR, r[2].status)
    assertContains(r[2].message!!, "must be in [0, 1)")
  }

  @Test
  fun `world rejects package misuse and a rules package that isn't there`() {
    val files =
      zone +
        ("zones/a/a_test.star" to
          """
            def test_rules(t):
                t.world(rules = "//rules")

            def test_packages(t):
                t.world(packages = ["zones/a"])
        """)
    val r = run(files + ("MODULE.gups" to TestWorlds.MODULE), "//zones/a", defaults = false).results
    assertContains(r[0].message!!, "no package //rules")
    assertContains(r[1].message!!, "should be a package label")
  }

  @Test
  fun `the rules package is loaded implicitly, and rules picks another one`() {
    val files =
      zone +
        mapOf(
          "rules/package.star" to "package(api_version = 1, kind = \"rules\")",
          "rules/combat.star" to TestWorlds.rules("def xp_to_level(ctx, level):\n    return level"),
          "house/package.star" to "package(api_version = 1, kind = \"rules\")",
          "house/combat.star" to
            TestWorlds.rules("def xp_to_level(ctx, level):\n    return 2 * level"),
          "zones/a/a_test.star" to
            """
                def test_default_rules(t):
                    w = t.world()
                    p = w.spawn_player("Pat", at = ":start")
                    p.do("say hi")

                def test_house_rules(t):
                    w = t.world(rules = "//house")
                    t.true(w != None)

                def test_not_rules(t):
                    t.world(rules = "//zones/a")
            """,
        )
    val r = run(files, "//zones/a").results.associateBy { it.name }
    assertEquals(Status.PASS, r.getValue("test_default_rules").status)
    assertEquals(Status.PASS, r.getValue("test_house_rules").status)
    assertContains(r.getValue("test_not_rules").message!!, "//zones/a is not a rules package")
  }

  @Test
  fun `a world with a bubble only simulates rooms near players`() {
    val files =
      zone +
        ("zones/a/a_test.star" to
          """
            def test_bubble(t):
                w = t.world(bubble = True)
                t.eq(len([r for r in [w.room(":start"), w.room(":hall")]]), 2)
                w.run(pulses = 5)
                w.spawn_player("Pat", at = ":start")
                w.run(pulses = 1)
        """)
    assertTrue(run(files, "//zones/a").ok)
  }

  @Test
  fun `w act queues a command for a mob, and refuses players`() {
    val files =
      zone +
        mapOf(
          "zones/a/items.star" to
            """
              item("amulet", name = "an amulet", wear = "neck", max_vital_deltas = vitals(hp = 5))
            """,
          "zones/a/act_test.star" to
            """
              def test_mob_wears(t):
                  w = t.world(seed = 1)
                  rat = w.spawn(":rat", at = ":start")
                  w.spawn(":amulet", at = rat)
                  t.eq(rat.max_hp, 8)
                  t.true(w.act(rat, "wear amulet"))
                  w.run(pulses = 4)
                  t.eq(rat.equipped("neck").label, "//zones/a:amulet")
                  t.eq(rat.max_hp, 13)

              def test_player_refused(t):
                  w = t.world()
                  p = w.spawn_player("Pat", at = ":start")
                  w.act(p, "north")
            """,
        )
    val r = run(files, "//zones/a").results.associateBy { it.name }
    assertEquals(
      Status.PASS,
      r.getValue("test_mob_wears").status,
      r.getValue("test_mob_wears").render(),
    )
    assertEquals(Status.ERROR, r.getValue("test_player_refused").status)
    assertContains(r.getValue("test_player_refused").message!!, "act(mob): expected a mob")
  }

  @Test
  fun `a harness test drives each of the four w verbs and checks the level and XP`() {
    val autoFiles =
      zone +
        mapOf(
          "zones/a/auto_test.star" to
            """
              def test_auto_verbs(t):
                  w = t.world(seed = 1)
                  p = w.spawn_player("Pat", at = ":start", level = 1)
                  t.eq(p.level, 1)
                  t.eq(p.xp, 0)

                  # 1. award_xp raises level immediately in auto mode
                  w.award_xp(p, 2500)
                  t.eq(p.level, 2)
                  t.eq(p.xp, 2500)

                  # 2. take_xp removes experience without changing level
                  taken = w.take_xp(p, 400)
                  t.eq(taken, 400)
                  t.eq(p.xp, 2100)
                  t.eq(p.level, 2)

                  # 3. level_down lowers level and clamps XP below the lost level's threshold
                  lost = w.level_down(p)
                  t.eq(lost, 1)
                  t.eq(p.level, 1)
                  t.eq(p.xp, 999)

                  # 4. level_up when nothing pending returns False
                  t.false(w.level_up(p))
                  t.eq(p.level, 1)

                  # Mob handling with award_xp and level_down(count=2)
                  mob = w.spawn(":rat", at = ":start")
                  t.eq(mob.level, 1)
                  w.award_xp(mob, 6000)
                  t.eq(mob.level, 4)
                  t.eq(mob.xp, 6000)
                  lost_mob = w.level_down(mob, count = 2)
                  t.eq(lost_mob, 2)
                  t.eq(mob.level, 2)
                  t.eq(mob.xp, 2999)
            """
        )
    val autoReport = run(autoFiles, "//zones/a")
    assertTrue(autoReport.ok, autoReport.results.joinToString("\n") { it.render() })

    val deferredFiles =
      zone +
        mapOf(
          "MODULE.gups" to TestWorlds.module("level_mode = \"deferred\""),
          "zones/a/deferred_test.star" to
            """
              def test_deferred_verbs(t):
                  w = t.world(seed = 2)
                  p = w.spawn_player("Pat", at = ":start", level = 1)
                  t.eq(p.level, 1)
                  t.eq(p.xp, 0)
                  t.eq(p.pending_levels, 0)

                  # award_xp leaves level pending
                  w.award_xp(p, 3000)
                  t.eq(p.xp, 3000)
                  t.eq(p.level, 1)
                  t.eq(p.pending_levels, 2)

                  # level_up steps one level
                  gained = w.level_up(p)
                  t.true(gained)
                  t.eq(p.level, 2)
                  t.eq(p.pending_levels, 1)

                  # second level_up steps the remaining level
                  gained2 = w.level_up(p)
                  t.true(gained2)
                  t.eq(p.level, 3)
                  t.eq(p.pending_levels, 0)

                  # level_up when nothing pending returns False
                  t.false(w.level_up(p))
            """,
        )
    val deferredReport = run(deferredFiles, "//zones/a")
    assertTrue(deferredReport.ok, deferredReport.results.joinToString("\n") { it.render() })

    val badAmount =
      run(
        zone +
          mapOf(
            "zones/a/bad_test.star" to
              """
                def test_bad(t):
                    w = t.world()
                    p = w.spawn_player("Pat", at = ":start")
                    w.award_xp(p, 0)
              """
          ),
        "//zones/a",
      )
    assertEquals(Status.ERROR, badAmount.results.single().status)
    assertContains(badAmount.results.single().message!!, "award_xp(amount): must be at least 1")

    val badCount =
      run(
        zone +
          mapOf(
            "zones/a/bad_test.star" to
              """
                def test_bad(t):
                    w = t.world()
                    p = w.spawn_player("Pat", at = ":start")
                    w.level_down(p, count = 0)
              """
          ),
        "//zones/a",
      )
    assertEquals(Status.ERROR, badCount.results.single().status)
    assertContains(badCount.results.single().message!!, "level_down(count): must be at least 1")
  }

  @Test
  fun `the four w leveling verbs fail with the feature refusal when leveling is off`() {
    val off = listOf("combat", "track", "skills")
    for (call in listOf("award_xp(p, 10)", "take_xp(p, 10)", "level_up(p)", "level_down(p)")) {
      val report =
        run(
          zone +
            mapOf(
              "MODULE.gups" to TestWorlds.module("features = [${off.joinToString { "\"$it\"" }}]"),
              "rules/package.star" to TestWorlds.RULES_PACKAGE,
              "rules/combat.star" to TestWorlds.rulesFor(off),
              "zones/a/off_test.star" to
                """
                  def test_off(t):
                      w = t.world()
                      p = w.spawn_player("Pat", at = ":start")
                      w.$call
                """,
            ),
          "//zones/a",
        )
      val result = report.results.single()
      assertEquals(Status.ERROR, result.status, call)
      val name = call.substringBefore('(')
      assertContains(result.message!!, "w.$name() needs the leveling feature")
    }
  }

  @Test
  fun `a test spawns a fixture item, and a fixture's test functions never run`() {
    val r =
      run(
        zone +
          mapOf(
            "zones/a/probe_testdata.star" to
              """
                  item("probe", name = "a probe")

                  def test_in_fixture(t):
                      t.fail("fixtures hold no tests")
              """,
            "zones/a/a_test.star" to
              """
                  def test_probe(t):
                      w = t.world()
                      p = w.spawn_player("Pat", at = ":start")
                      w.spawn(":probe", at = p)
                      t.true(p.has_item(":probe"))
              """,
          ),
        "//zones/a",
      )
    assertEquals(
      listOf("test_probe" to Status.PASS),
      r.results.map { it.name to it.status },
      r.results.joinToString("\n") { "${it.name}: ${it.message}" },
    )
  }
}
