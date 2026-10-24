package gups.script

import gups.core.Label
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.TrackSize
import gups.core.world.HookName
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoaderTest {
    private fun load(files: Map<String, String>, vararg pkgs: String = arrayOf("zones/z")): LoadResult =
        ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(pkgs.toList())

    private fun only(r: LoadResult, pkg: String = "zones/z"): LoadError {
        val errs = r.errors.filter { it.pkg == pkg }
        assertTrue(errs.isNotEmpty(), "expected an error for $pkg, got: ${r.report()}")
        return errs.first()
    }

    @Test
    fun `declarations become frozen prototypes with resolved labels`() {
        val r = load(
            mapOf(
                "zones/z/package.star" to zonePkg(", reset_pulses = 600"),
                "zones/z/rooms.star" to """
                    room("start", name = "Start", size = LARGE,
                        exits = [exit("north", to = ":hall", entry_index = -1,
                                      door = door(state = "locked", key = ":key"))],
                        spawns = [spawn(":rat", count = 2, at_index = 4)])
                    room("hall", exits = [exit("south", ":start")])
                """,
                "zones/z/things.star" to """
                    mob("rat", name = "a fat rat", hp = 5, loot = [loot(":key", chance = 0.5)])
                    item("key", name = "a brass key")
                    skill("bite", range = "melee", effect = sequence(damage("1d4", type = "pierce"), stun(2)))
                """,
            ),
        )
        assertTrue(r.ok, r.report())
        val p = r.prototypes()
        val start = p.rooms.getValue(Label("//zones/z:start"))
        assertEquals(TrackSize.LARGE, start.size)
        val north = start.exits.single()
        assertEquals(Direction.NORTH, north.direction)
        assertEquals(Label("//zones/z:hall"), north.to)
        assertEquals(-1, north.entryIndex)
        assertEquals(DoorState.LOCKED, north.door!!.state)
        assertEquals(Label("//zones/z:key"), north.door!!.key)
        assertEquals(2, start.spawns.single().count)
        assertEquals("hall", p.rooms.getValue(Label("//zones/z:hall")).name)
        assertEquals(listOf("fat", "rat"), p.mob(Label("//zones/z:rat")).keywords)
        assertEquals(listOf("brass", "key"), p.item(Label("//zones/z:key")).keywords)
        assertEquals(600, p.zones.getValue("zones/z").resetPulses)
    }

    @Test
    fun `syntax errors point at the file and line with a caret`() {
        val r = load(mapOf("zones/z/package.star" to zonePkg(), "zones/z/rooms.star" to "room(\"start\",\n    name = \"x\"\n    desc = 1)"))
        val e = only(r)
        assertEquals("syntax", e.kind)
        assertEquals("//zones/z:rooms.star", e.file)
        assertEquals(3, e.line)
        assertContains(e.render(), "^")
        assertTrue(r.packages.isEmpty())
    }

    @Test
    fun `lambdas are not supported by this Starlark`() {
        val r = load(mapOf("zones/z/package.star" to zonePkg(), "zones/z/a.star" to "f = lambda x: x"))
        assertContains(only(r).message, "lambda")
    }

    @Test
    fun `a different api_version names the gap and the changelog`() {
        val r = load(mapOf("zones/z/package.star" to "package(api_version = 2, kind = \"zone\", brand_new_arg = 1)"))
        val e = only(r)
        assertEquals("api_version", e.kind)
        assertContains(e.message, "api_version 2")
        assertContains(e.message, "only api_version 1")
        assertContains(e.message, "CHANGELOG.md")
    }

    @Test
    fun `unknown arguments and hook typos get suggestions`() {
        val typo = load(mapOf("zones/z/package.star" to zonePkg(), "zones/z/a.star" to "room(\"start\", nme = \"x\")"))
        assertContains(only(typo).message, "unexpected argument 'nme' (did you mean 'name'?)")
        val hook = load(mapOf("zones/z/package.star" to zonePkg(), "zones/z/a.star" to "def f(ctx):\n    pass\nroom(\"start\", on_entr = f)"))
        assertContains(only(hook).message, "unknown hook 'on_entr' (did you mean 'on_enter'?)")
        val kind = load(mapOf("zones/z/package.star" to zonePkg(), "zones/z/a.star" to "def f(ctx):\n    pass\nroom(\"start\", on_tell = f)"))
        assertContains(only(kind).message, "hook 'on_tell' can't be attached to a room")
    }

    @Test
    fun `hooks must be top-level functions`() {
        val r = load(
            mapOf(
                "zones/z/package.star" to zonePkg(),
                "zones/z/a.star" to """
                    def make():
                        def inner(ctx):
                            pass
                        return inner
                    room("start", on_enter = make())
                """,
            ),
        )
        // Starlark itself refuses nested functions; lambdas are refused too (see above).
        assertContains(only(r).message, "nested functions are not allowed")
        val ok = load(mapOf("zones/z/package.star" to zonePkg(), "zones/z/a.star" to "def _greet(ctx):\n    pass\nroom(\"start\", on_enter = _greet)"))
        assertTrue(ok.ok, ok.report())
        assertEquals("_greet", ok.hooks(Label("//zones/z:start")).getValue(HookName.ON_ENTER).name)
    }

    @Test
    fun `declaration names are unique across kinds`() {
        val r = load(mapOf("zones/z/package.star" to zonePkg(), "zones/z/a.star" to "room(\"thing\")\nitem(\"thing\")"))
        assertContains(only(r).message, "'thing' is already declared")
    }

    @Test
    fun `unresolved labels fail the package and its dependents`() {
        val r = load(
            mapOf(
                "zones/a/package.star" to zonePkg(),
                "zones/a/a.star" to "room(\"start\", exits = [exit(\"east\", \"//zones/b:start\")])",
                "zones/b/package.star" to zonePkg(),
                "zones/b/b.star" to "room(\"start\", exits = [exit(\"west\", \"//zones/a:start\"), exit(\"north\", \":nowhere\")])",
            ),
            "zones/a",
        )
        val b = only(r, "zones/b")
        assertEquals("label", b.kind)
        assertContains(b.message, "//zones/b:nowhere doesn't resolve")
        assertEquals("//zones/b:b.star", b.file)
        assertEquals("dependency", only(r, "zones/a").kind)
    }

    @Test
    fun `referenced packages load automatically and kinds are checked`() {
        val r = load(
            mapOf(
                "zones/a/package.star" to zonePkg(),
                "zones/a/a.star" to "room(\"start\", exits = [exit(\"east\", \"//zones/b:rat\")])",
                "zones/b/package.star" to zonePkg(),
                "zones/b/b.star" to "mob(\"rat\")",
            ),
            "zones/a",
        )
        assertContains(only(r, "zones/a").message, "//zones/b:rat is a mob, not a room")
        assertTrue("zones/b" in r.packages)
    }

    @Test
    fun `load pulls in library files and detects cycles`() {
        val r = load(
            mapOf(
                "zones/z/package.star" to zonePkg(),
                "zones/z/a.star" to "load(\"//lib:names.star\", \"title\")\nroom(\"start\", name = title(\"hall\"))",
                "lib/package.star" to libPkg(),
                "lib/names.star" to "def title(s):\n    return s.upper()",
            ),
        )
        assertTrue(r.ok, r.report())
        assertEquals("HALL", r.prototypes().rooms.getValue(Label("//zones/z:start")).name)
        assertTrue("lib" in r.packages)

        val cycle = load(
            mapOf(
                "zones/z/package.star" to zonePkg(),
                "zones/z/a.star" to "load(\":b.star\", \"y\")\nx = 1",
                "zones/z/b.star" to "load(\":a.star\", \"x\")\ny = 1",
            ),
        )
        assertTrue(cycle.errors.any { "load cycle" in it.message }, cycle.report())
    }

    @Test
    fun `privileges and namespaces are enforced`() {
        val zoneCmd = load(mapOf("zones/z/package.star" to zonePkg(), "zones/z/a.star" to "def f(ctx):\n    pass\nglobal_command(\"dance\", f)"))
        assertContains(only(zoneCmd).message, "only lib and rules packages")
        val shared = load(mapOf("zones/z/package.star" to zonePkg(", shared_namespaces = [\"faction\"]")))
        assertContains(only(shared).message, "only lib and rules packages may declare shared namespaces")
        val clash = load(
            mapOf(
                "zones/z/package.star" to zonePkg(),
                "zones/z/a.star" to "room(\"start\", exits = [exit(\"east\", \"//other/z:start\")])",
                "other/z/package.star" to zonePkg(),
                "other/z/a.star" to "room(\"start\")",
            ),
        )
        assertContains(only(clash).message, "attribute namespace 'z' is used by more than one package")
        val shadow = load(mapOf("lib/package.star" to libPkg(), "lib/a.star" to "def f(ctx):\n    pass\nglobal_command(\"look\", f)"), "lib")
        assertContains(only(shadow, "lib").message, "clashes with the engine command")
    }

    @Test
    fun `package star holds only package() and must call it`() {
        val missing = load(mapOf("zones/z/package.star" to "x = 1"))
        assertContains(only(missing).message, "must call package(")
        val room = load(mapOf("zones/z/package.star" to zonePkg() + "room(\"start\")"))
        assertContains(only(room).message, "not in package.star")
        val nopkg = load(mapOf("zones/y/package.star" to zonePkg()), "zones/nope")
        assertEquals("missing", only(nopkg, "zones/nope").kind)
    }

    @Test
    fun `top-level evaluation has a step limit`() {
        val r = ScriptLoader(WorldDir(tempWorld(mapOf("zones/z/package.star" to zonePkg(), "zones/z/a.star" to "def spin():\n    for i in range(100000000):\n        pass\nspin()"))), ScriptLimits.HARNESS.copy(loadSteps = 10_000))
            .load(listOf("zones/z"))
        assertEquals("step_limit", only(r).kind)
    }

    @Test
    fun `mob behaviors must be behavior values`() {
        val r = load(mapOf("zones/z/package.star" to zonePkg(), "zones/z/a.star" to "mob(\"rat\", behaviors = [1])"))
        assertContains(only(r).message, "mob(behaviors): expected a behavior such as wander(...), got int")
        val empty = load(mapOf("zones/z/package.star" to zonePkg(), "zones/z/a.star" to "mob(\"rat\", behaviors = [])"))
        assertTrue(empty.ok, empty.report())
        assertNull(empty.prototypes().mob(Label("//zones/z:rat")).think)
        assertNotNull(empty.packages["zones/z"])
    }
}
