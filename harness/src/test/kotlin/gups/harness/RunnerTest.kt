package gups.harness

import gups.script.WorldDir
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
    private fun world(files: Map<String, String>): WorldDir {
        val root: Path = Files.createTempDirectory("gups-runner")
        for ((path, text) in files) {
            root.resolve(path).also { it.parent.createDirectories() }.writeText(text.trimIndent() + "\n")
        }
        return WorldDir(root)
    }

    private val zone = mapOf(
        "zones/a/package.star" to "package(api_version = 1, kind = \"zone\")",
        "zones/a/rooms.star" to """
            def _fight(ctx):
                ctx.send(ctx.actor, "The bell rings!")

            room("start", name = "Start", exits = [exit("north", ":hall")], commands = {"ring": _fight})
            room("hall", name = "Hall", exits = [exit("south", ":start")])
            mob("rat", hp = 8)
        """,
    )

    private fun run(files: Map<String, String>, vararg patterns: String): TestReport =
        TestRunner(world(files)).run(patterns.toList())

    @Test
    fun `passing, failing and erroring tests are told apart`() {
        val r = run(
            zone + ("zones/a/a_test.star" to """
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
        assertEquals(listOf("test_walks" to Status.PASS, "test_fails" to Status.FAIL, "test_errors" to Status.ERROR), r.results.map { it.name to it.status })
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
        val files = zone + mapOf(
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
        assertEquals(listOf("'zones/a' is not a label pattern; patterns start with //"), run(files, "zones/a").problems)
    }

    @Test
    fun `load failures surface as errors`() {
        val bad = run(zone + ("zones/a/a_test.star" to "def test_x(t):\n    t.world()"), "//zones/a").results.single()
        assertEquals(Status.PASS, bad.status)
        val broken = mapOf(
            "zones/a/package.star" to "package(api_version = 1, kind = \"zone\")",
            "zones/a/rooms.star" to "room(\"start\", exits = [exit(\"north\", \":nowhere\")])",
            "zones/a/a_test.star" to "def test_x(t):\n    t.world()",
        )
        val r = run(broken, "//zones/a").results.single()
        assertEquals(Status.ERROR, r.status)
        assertContains(r.message!!, "//zones/a:nowhere doesn't resolve")
        val syntax = run(zone + ("zones/a/a_test.star" to "def test_x(t)\n    pass"), "//zones/a").results.single()
        assertEquals("(load)", syntax.name)
        assertEquals(Status.ERROR, syntax.status)
    }

    @Test
    fun `script errors in hooks show as warnings with a trace on failure`() {
        val files = mapOf(
            "zones/a/package.star" to "package(api_version = 1, kind = \"zone\")",
            "zones/a/rooms.star" to """
                def _boom(ctx):
                    fail("kaboom")

                room("start", on_enter = _boom, exits = [exit("north", ":start")])
            """,
            "zones/a/a_test.star" to """
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
        val files = zone + ("zones/a/a_test.star" to """
            def _fight(t, seed):
                w = t.world(seed = seed)
                p = w.spawn_player("Pat", at = ":start", hp = 30)
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
    fun `world rejects package misuse and a rules package that isn't there`() {
        val files = zone + ("zones/a/a_test.star" to """
            def test_rules(t):
                t.world(rules = "//rules")

            def test_packages(t):
                t.world(packages = ["zones/a"])
        """)
        val r = run(files, "//zones/a").results
        assertContains(r[0].message!!, "no package //rules")
        assertContains(r[1].message!!, "should be a package label")
    }

    @Test
    fun `the rules package is loaded implicitly, and rules picks another one`() {
        val files = zone + mapOf(
            "rules/package.star" to "package(api_version = 1, kind = \"rules\")",
            "rules/combat.star" to "def xp_to_level(level):\n    return 1",
            "house/package.star" to "package(api_version = 1, kind = \"rules\")",
            "house/combat.star" to "def xp_to_level(level):\n    return 2",
            "zones/a/a_test.star" to """
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
        val files = zone + ("zones/a/a_test.star" to """
            def test_bubble(t):
                w = t.world(bubble = True)
                t.eq(len([r for r in [w.room(":start"), w.room(":hall")]]), 2)
                w.run(pulses = 5)
                w.spawn_player("Pat", at = ":start")
                w.run(pulses = 1)
        """)
        assertTrue(run(files, "//zones/a").ok)
    }
}
