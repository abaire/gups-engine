# 07. Testing and Verifying Your Plugin

Automated testing is fundamental to GUPS development. Because plugins run as trusted code inside the host engine, having
high test coverage is essential.

In [`WeatherPluginTest.kt`](../../src/test/kotlin/gups/sample/weather/WeatherPluginTest.kt), testing is organized into
two complementary tiers:

1. **Tier 1: Fast Unit Tests** using mock facades (`FakeRegistry`, `FakeWorld`, `FakeRoom`, `FakeActor`) to test
   isolated logic in milliseconds.
2. **Tier 2: End-to-End Integration Tests** using `PluginLoader` and `TestRunner` to verify that the built `.jar` loads
   cleanly into a virtual world and interacts with real Starlark scripts.

---

## 1. Fast Unit Testing with Mock Facades

Because the plugin API consists entirely of interfaces, you can test your plugin's business logic without booting the
engine:

### Building Fakes of the Plugin API

In `WeatherPluginTest.kt`, lightweight test doubles implement the facade interfaces:

```kotlin
private class FakeRegistry : PluginRegistry {
    val builtins = mutableMapOf<String, (PluginWorld, List<Any?>, Map<String, Any?>) -> Any?>()
    val commands = mutableMapOf<String, (PluginCommandCall) -> Unit>()
    val effects = mutableMapOf<String, Pair<List<PluginParam>, PluginEffect>>()
    val gmcp = mutableMapOf<String, (PluginWorld, PluginActor) -> Any?>()
    val started = mutableListOf<(PluginWorld) -> Unit>()

    override fun addBuiltin(...) {
        builtins[name] = call
    }
    override fun addCommand(...) {
        commands[name] = run
    }
    override fun addEffectPrimitive(...) {
        effects[name] = params to apply
    }
    override fun addGmcp(...) {
        gmcp[pkg] = produce
    }
    override fun onWorldStarted(fn: (PluginWorld) -> Unit) {
        started += fn
    }
    // Unused methods can be left as TODO()
}

private class FakeWorld(
    override val seed: Long = 42,
    override var pulse: Long = 0,
    private val regionList: List<String> = emptyList(),
) : PluginWorld {
    val sent = mutableListOf<String>()
    val affects = mutableListOf<List<Any>>()

    override fun send(target: PluginActor, text: String) {
        sent += text
    }
    override fun applyAffect(...) {
        affects += listOf(target, name, duration, statDeltas)
    }
    override fun regions(): List<String> = regionList
    // ...
}
```

### Testing the Command

With fakes, testing command execution is straightforward and executes in less than 1 millisecond:

```kotlin
@Test
fun `the weather command`() {
    val w = FakeWorld()
    val run = registry.commands.getValue("weather")

    // Run command while standing indoors:
    run(PluginCommandCall(w, FakeActor(cabin), ""))
    // Run command while standing outdoors in the meadow:
    run(PluginCommandCall(w, FakeActor(meadow), ""))

    assertEquals(
        listOf(
            "You are indoors.",
            "The weather here is ${WeatherPlugin.stateFor(w, meadow)}."
        ),
        w.sent,
    )
}
```

### Testing Effect Primitives

Verify that your effect applies expected status conditions and handles missing targets:

```kotlin
@Test
fun `soak applies soaked, and does nothing without a target`() {
    val w = FakeWorld()
    val (params, soak) = registry.effects.getValue("soak")
    assertEquals(2L, params.single().defaultValue)

    val target = FakeActor(meadow)
    // Apply with target:
    soak.apply(PluginEffectCall(w, FakeActor(cabin), target, mapOf("pulses" to 3L)))
    // Apply without target (target = "none"):
    soak.apply(PluginEffectCall(w, FakeActor(cabin), null, mapOf("pulses" to 3L)))

    assertEquals(
        listOf(listOf<Any>(target, "soaked", 3, mapOf("dex" to -1))),
        w.affects
    )
}
```

---

## 2. End-to-End Integration Testing with `TestRunner`

While unit tests verify isolated logic, an integration test verifies the entire lifecycle:

1. Packing the `.jar` bundle.
2. Loading the jar via the engine's `PluginLoader` and verifying its SHA-256 checksum.
3. Injecting plugin globals into the Starlark execution environment.
4. Executing Starlark tests (`*_test.star`) that exercise builtins and commands.

### Setting Up a Test World

In `WeatherPluginTest.kt`, the `the built jar runs through TestRunner` test creates a temporary world directory:

```kotlin
@Test
fun `the built jar runs through TestRunner`() {
    val tmp = Files.createTempDirectory("gups-weather")

    // 1. Copy the compiled plugin jar:
    val jar = tmp.resolve("plugins/weather.jar").also { it.parent.createDirectories() }
    Path.of(System.getProperty("gups.sample.weatherJar")).copyTo(jar)

    // 2. Pin the jar in MODULE.gups with its SHA-256 hash:
    val checksum = PluginLoader.sha256(jar)
    write(
        tmp, "MODULE.gups", """
        module(
            api_version = 1,
            plugins = {"weather": "$checksum"},
        )
    """
    )

    // 3. Write Starlark zone code using the plugin:
    write(tmp, "zones/valley/package.star", """package(api_version = 1, kind = "zone")""")
    write(
        tmp, "zones/valley/rooms.star", """
        def _sky(ctx):
            ctx.send(ctx.actor, "The sky is " + weather.now(ctx.room) + ".")

        room("cabin", name = "Cabin", desc = "A snug cabin.", exits = [exit("north", ":field")])
        room("field", name = "Field", desc = "An open field.", regions = ["meadow"],
             exits = [exit("south", ":cabin")], on_enter = _sky)
        skill("rain_call", kind = "spell", target = "self", effect = weather.soak(pulses = 3))
    """
    )

    // 4. Write Starlark test suite:
    write(
        tmp, "zones/valley/valley_test.star", """
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
    """
    )

    // 5. Load plugins and execute tests via TestRunner:
    val dir = WorldDir(tmp)
    val (module, _) = WorldModule.read(dir)
    PluginLoader.load(dir, module).use { host ->
        val report = TestRunner(dir, plugins = host).run(listOf("//zones/valley"))
        assertEquals(emptyList(), report.problems)
        for (r in report.results) {
            assertEquals(Status.PASS, r.status, "${r.name}: ${r.message}")
        }
    }
}
```

---

## 3. Running Tests via Gradle

To run the entire test suite from your terminal:

```bash
./gradlew :samples:weather-plugin:test
```

### How Gradle Configures the Test Jar

In [`build.gradle.kts`](../../build.gradle.kts), the test task links the output of `tasks.jar` to a system property so
the integration test can locate the newly built jar:

```kotlin
tasks.test {
    val jar = tasks.jar.flatMap { it.archiveFile }
    inputs.file(jar)
    systemProperty("gups.sample.weatherJar", jar.get().asFile.absolutePath)

    val templateWorld = rootProject.layout.projectDirectory.dir("template-world")
    inputs.dir(templateWorld)
    systemProperty("gups.templateWorld", templateWorld.asFile.absolutePath)
}
```

---

## Next Steps

Now that your plugin is fully tested, proceed to the final guide:
**[08. Packaging, Pinning, and Deployment](08_packaging_and_installation.md)**.
