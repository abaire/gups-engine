package gups.sample.weather

import gups.harness.Status
import gups.harness.TestRunner
import gups.pluginapi.PluginActor
import gups.pluginapi.PluginCommandCall
import gups.pluginapi.PluginEffect
import gups.pluginapi.PluginEffectCall
import gups.pluginapi.PluginEntity
import gups.pluginapi.PluginItem
import gups.pluginapi.PluginLane
import gups.pluginapi.PluginParam
import gups.pluginapi.PluginRegistry
import gups.pluginapi.PluginRng
import gups.pluginapi.PluginRole
import gups.pluginapi.PluginRoom
import gups.pluginapi.PluginWorld
import gups.pluginapi.host.PluginLoader
import gups.script.WorldDir
import gups.script.WorldModule
import net.starlark.java.eval.StarlarkValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherPluginTest {
    // ---- Fakes of the plugin API: unused members TODO() ---------------------------------------

    private class FakeRegistry : PluginRegistry {
        val builtins = mutableMapOf<String, (PluginWorld, List<Any?>, Map<String, Any?>) -> Any?>()
        val commands = mutableMapOf<String, (PluginCommandCall) -> Unit>()
        val effects = mutableMapOf<String, Pair<List<PluginParam>, PluginEffect>>()
        val gmcp = mutableMapOf<String, (PluginWorld, PluginActor) -> Any?>()
        val started = mutableListOf<(PluginWorld) -> Unit>()

        override fun addBuiltin(name: String, value: StarlarkValue, doc: String) = TODO()

        override fun addBuiltin(
            name: String,
            doc: String,
            params: List<PluginParam>,
            returnType: String,
            call: (world: PluginWorld, positional: List<Any?>, named: Map<String, Any?>) -> Any?,
        ) {
            builtins[name] = call
        }

        override fun addCommand(name: String, lane: PluginLane, role: PluginRole, doc: String, run: (PluginCommandCall) -> Unit) {
            commands[name] = run
        }

        override fun addEffectPrimitive(name: String, doc: String, params: List<PluginParam>, apply: PluginEffect) {
            effects[name] = params to apply
        }

        override fun addGmcp(pkg: String, doc: String, produce: (PluginWorld, PluginActor) -> Any?) {
            gmcp[pkg] = produce
        }

        override fun onWorldStarted(fn: (PluginWorld) -> Unit) {
            started += fn
        }
    }

    private class FakeRoom(override val regions: List<String>) : PluginRoom {
        override val id = 1L
        override val label = "//zones/valley:x"
        override val name = "X"
        override val flags: Set<String> get() = TODO()
        override val size: String get() = TODO()
        override val exits: Map<String, String> get() = TODO()
        override val actors: List<PluginActor> get() = TODO()
        override val items: List<PluginItem> get() = TODO()
    }

    private class FakeActor(override val room: PluginRoom) : PluginActor {
        override val id = 2L
        override val label: String? = null
        override val name = "Ann"
        override val isPlayer = true
        override val hp get() = TODO()
        override val maxHp get() = TODO()
        override val mana get() = TODO()
        override val maxMana get() = TODO()
        override val move get() = TODO()
        override val maxMove get() = TODO()
        override val level get() = TODO()
        override val affects: List<String> get() = TODO()

        override fun hasAffect(name: String): Boolean = TODO()
    }

    private class FakeWorld(override val seed: Long = 42, override var pulse: Long = 0, private val regionList: List<String> = emptyList()) : PluginWorld {
        val sent = mutableListOf<String>()
        val affects = mutableListOf<List<Any>>()
        val logs = mutableListOf<String>()

        override fun send(target: PluginActor, text: String) {
            sent += text
        }

        override fun applyAffect(target: PluginActor, name: String, duration: Int, statDeltas: Map<String, Int>, flags: Set<String>) {
            affects += listOf(target, name, duration, statDeltas)
        }

        override fun log(message: String) {
            logs += message
        }

        override fun regions(): List<String> = regionList

        override fun echo(room: PluginRoom, text: String, exclude: List<PluginActor>) = TODO()

        override fun damage(target: PluginActor, amount: Int, type: String, source: PluginActor?) = TODO()

        override fun heal(target: PluginActor, amount: Int) = TODO()

        override fun removeAffect(target: PluginActor, name: String): Boolean = TODO()

        override fun teleport(actor: PluginActor, room: PluginRoom) = TODO()

        override fun getAttr(entity: PluginEntity, key: String, default: Any?): Any? = TODO()

        override fun setAttr(entity: PluginEntity, key: String, value: Any?) = TODO()

        override fun delAttr(entity: PluginEntity, key: String) = TODO()

        override val rng: PluginRng get() = TODO()

        override fun rooms(): List<PluginRoom> = TODO()

        override fun room(label: String): PluginRoom? = TODO()

        override fun diagnose(message: String) = TODO()
    }

    private val registry = FakeRegistry().also { WeatherPlugin().register(it) }
    private val meadow = FakeRoom(listOf("meadow", "valley"))
    private val cabin = FakeRoom(emptyList())

    // ---- Unit tests ---------------------------------------------------------------------------

    @Test
    fun `stateFor is a pure function of seed, pulse window and region`() {
        val w = FakeWorld(seed = 42, pulse = 10)
        val state = WeatherPlugin.stateFor(w, meadow)
        assertTrue(state in listOf("clear", "rain", "storm"))
        assertEquals(state, WeatherPlugin.stateFor(FakeWorld(seed = 42, pulse = 239), meadow), "same one-minute window")
        assertEquals(state, WeatherPlugin.stateFor(w, FakeRoom(listOf("valley", "meadow"))), "the first tag in sorted order")
        assertEquals("clear", WeatherPlugin.stateFor(w, cabin))
        val seen = (0L until 50L).map { WeatherPlugin.stateFor(FakeWorld(seed = 42, pulse = it * 240), meadow) }.toSet()
        assertEquals(setOf("clear", "rain", "storm"), seen, "windows vary")
        assertEquals(state, registry.builtins.getValue("now")(w, listOf(meadow), mapOf("room" to meadow)))
    }

    @Test
    fun `the weather command`() {
        val w = FakeWorld()
        val run = registry.commands.getValue("weather")
        run(PluginCommandCall(w, FakeActor(cabin), ""))
        run(PluginCommandCall(w, FakeActor(meadow), ""))
        assertEquals(listOf("You are indoors.", "The weather here is ${WeatherPlugin.stateFor(w, meadow)}."), w.sent)
    }

    @Test
    fun `soak applies soaked, and does nothing without a target`() {
        val w = FakeWorld()
        val (params, soak) = registry.effects.getValue("soak")
        assertEquals(2L, params.single().defaultValue)
        val target = FakeActor(meadow)
        soak.apply(PluginEffectCall(w, FakeActor(cabin), target, mapOf("pulses" to 3L)))
        soak.apply(PluginEffectCall(w, FakeActor(cabin), null, mapOf("pulses" to 3L)))
        assertEquals(listOf(listOf<Any>(target, "soaked", 3, mapOf("dex" to -1))), w.affects)
    }

    @Test
    fun `gmcp and the start log`() {
        val w = FakeWorld(regionList = listOf("meadow", "valley"))
        val produce = registry.gmcp.getValue("Gups.Weather")
        assertEquals(mapOf("state" to WeatherPlugin.stateFor(w, meadow), "region" to "meadow"), produce(w, FakeActor(meadow)))
        assertEquals(mapOf("state" to "clear", "region" to null), produce(w, FakeActor(cabin)))
        registry.started.single()(w)
        assertEquals(listOf("weather: tracking 2 region(s): meadow, valley"), w.logs)
    }

    // ---- End to end: the built weather.jar through gups test's TestRunner -----------------------

    private fun write(root: Path, path: String, text: String) {
        val p = root.resolve(path)
        p.parent.createDirectories()
        p.writeText(text.trimIndent() + "\n")
    }

    @Test
    fun `the built jar runs through TestRunner`() {
        val tmp = Files.createTempDirectory("gups-weather")
        val jar = tmp.resolve("plugins/weather.jar").also { it.parent.createDirectories() }
        Path.of(System.getProperty("gups.sample.weatherJar")).copyTo(jar)
        write(tmp, "MODULE.gups", """module(api_version = 1, plugins = {"weather": "${PluginLoader.sha256(jar)}"})""")
        val rules = Path.of(System.getProperty("gups.templateWorld")).resolve("rules")
        Files.list(rules).use { s -> s.toList() }.forEach { it.copyTo(tmp.resolve("rules").createDirectories().resolve(it.fileName)) }
        write(tmp, "zones/valley/package.star", """package(api_version = 1, kind = "zone")""")
        write(
            tmp,
            "zones/valley/rooms.star",
            """
            def _sky(ctx):
                ctx.send(ctx.actor, "The sky is " + weather.now(ctx.room) + ".")

            room("cabin", name = "Cabin", desc = "A snug cabin.", exits = [exit("north", ":field")])
            room("field", name = "Field", desc = "An open field.", regions = ["meadow"], exits = [exit("south", ":cabin")], on_enter = _sky)
            skill("rain_call", kind = "spell", target = "self", effect = weather.soak(pulses = 3))
            """,
        )
        write(
            tmp,
            "zones/valley/valley_test.star",
            """
            def test_the_sky_shows_on_entering_the_field(t):
                w = t.world()
                p = w.spawn_player("Ann", at = ":cabin")
                t.contains(p.do("north"), "The sky is ")

            def test_the_weather_command(t):
                w = t.world()
                p = w.spawn_player("Ann", at = ":cabin")
                t.contains(p.do("weather"), "You are indoors.")
                p.do("north")
                t.contains(p.do("weather"), "The weather here is ")

            def test_rain_call_soaks_the_caster(t):
                w = t.world()
                p = w.spawn_player("Ann", at = ":field", skills = {":rain_call": 100})
                p.do("cast rain_call")
                w.run(pulses = 2)
                t.true("soaked" in [a.name for a in p.affects])
            """,
        )
        val logs = mutableListOf<String>()
        val dir = WorldDir(tmp)
        val (module, error) = WorldModule.read(dir)
        assertNull(error)
        PluginLoader.load(dir, module, log = { synchronized(logs) { logs += it } }).use { host ->
            assertEquals(emptyList(), host.warnings)
            val report = TestRunner(dir, plugins = host).run(listOf("//zones/valley"))
            assertEquals(emptyList(), report.problems)
            assertEquals(3, report.results.size, report.results.joinToString("\n"))
            for (r in report.results) assertEquals(Status.PASS, r.status, "${r.name}: ${r.message}")
        }
        assertContains(logs, "plugin 'weather': weather: tracking 1 region(s): meadow")
    }
}
