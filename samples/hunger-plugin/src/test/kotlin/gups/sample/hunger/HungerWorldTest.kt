package gups.sample.hunger

import gups.harness.Status
import gups.harness.TestRunner
import gups.pluginapi.host.PluginLoader
import gups.script.WorldDir
import gups.script.WorldModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The built hunger jar, loaded into a copy of the template world and driven by the harness. */
class HungerWorldTest {
  private fun write(root: Path, relativePath: String, text: String) {
    val p = root.resolve(relativePath)
    p.parent.createDirectories()
    p.writeText(text.trimIndent() + "\n")
  }

  /** Copies the built jar into [tmp] and returns its sha256, pinning it under the `hunger` id. */
  private fun pinJar(tmp: Path): String {
    val jar = tmp.resolve("plugins/hunger.jar").also { it.parent.createDirectories() }
    Path.of(System.getProperty("gups.sample.hungerJar")).copyTo(jar)
    return PluginLoader.sha256(jar)
  }

  /** A copy of the template world's rules, with spells rewritten to always land. */
  private fun copyRules(tmp: Path) {
    val rules = Path.of(System.getProperty("gups.templateWorld")).resolve("rules")
    Files.list(rules)
      .use { s -> s.toList() }
      .forEach { it.copyTo(tmp.resolve("rules").createDirectories().resolve(it.fileName)) }
    gups.script.testing.TestWorlds.makeSpellsLand(tmp.resolve("rules"))
  }

  private val zone =
    """
    def _hcheck(ctx):
        ctx.send(ctx.actor, "hunger %s" % ctx.has_feature("hunger"))

    def _eat(ctx):
        ctx.send(ctx.actor, "ate %s / %s" % (hunger.eat(ctx.actor, 3), hunger.eat(ctx.actor, 100)))

    room("dining", name = "Dining", desc = "A dining hall.", flags = ["dead_end_ok"], exits = [exit("north", ":hall"), exit("east", ":kitchen")])
    room("hall", name = "Hall", desc = "A hall.", exits = [exit("south", ":dining")], on_enter = _hcheck)
    room("kitchen", name = "Kitchen", desc = "A kitchen.", exits = [exit("west", ":dining")], on_enter = _eat)
    """

  private val tests =
    """
    def test_has_feature(t):
        w = t.world(seed = 1)
        p = w.spawn_player("Ann", at = ":dining")
        t.contains(p.do("north"), "hunger True")

    def test_every_raises_hunger(t):
        w = t.world(seed = 1)
        p = w.spawn_player("Ann", at = ":dining")
        w.run(pulses = 600)
        t.eq(w.get_attr(p, "hunger:hunger", 0), 5)
        t.contains(p.output(), "You are getting hungry.")

    def test_death_clears_hunger(t):
        w = t.world(seed = 1)
        p = w.spawn_player("Ann", at = ":dining")
        w.set_attr(p, "hunger:hunger", 7)
        w.damage(p, "hp", amount = p.max_hp)
        w.run(pulses = 20)
        t.eq(w.get_attr(p, "hunger:hunger", -1), -1)

    def test_eat(t):
        w = t.world(seed = 1)
        p = w.spawn_player("Ann", at = ":dining")
        w.set_attr(p, "hunger:hunger", 7)
        t.contains(p.do("east"), "ate 4 / 0")
        t.eq(w.get_attr(p, "hunger:hunger", 0), 0)
    """

  @Test
  fun `the built hunger jar runs through TestRunner in the template world`() {
    val tmp = Files.createTempDirectory("gups-hunger")
    val sha = pinJar(tmp)
    val schema = Path.of(System.getProperty("gups.templateWorld")).resolve("MODULE.gups").readText()
    write(
      tmp,
      "MODULE.gups",
      schema.replaceFirst("module(", "module(\n    plugins = {\"hunger\": \"${sha}\"},"),
    )
    copyRules(tmp)
    write(tmp, "skills/package.star", """package(api_version = 1, kind = "skills")""")
    write(tmp, "zones/hungry/package.star", """package(api_version = 1, kind = "zone")""")
    write(tmp, "zones/hungry/content.star", zone)
    write(tmp, "zones/hungry/hunger_test.star", tests)

    val dir = WorldDir(tmp)
    val (module, error) = WorldModule.read(dir)
    assertNull(error)
    PluginLoader.load(dir, module).use { host ->
      assertEquals(emptyList(), host.warnings)
      val report = TestRunner(dir, plugins = host).run(listOf("//zones/hungry"))
      assertEquals(emptyList(), report.problems)
      assertEquals(4, report.results.size, report.results.joinToString("\n"))
      for (r in report.results) assertEquals(Status.PASS, r.status, "${r.name}: ${r.message}")
    }
  }

  @Test
  fun `a world without an hp vital skips the plugin with a refusal`() {
    val tmp = Files.createTempDirectory("gups-hunger-nohp")
    val sha = pinJar(tmp)
    write(
      tmp,
      "MODULE.gups",
      """
      module(
          api_version = 1,
          stats = ["str"],
          vitals = ["mana"],
          damage_types = ["fire"],
          unarmed_damage_type = "fire",
          plugins = {"hunger": "${sha}"},
      )
      """,
    )
    val dir = WorldDir(tmp)
    val (module, error) = WorldModule.read(dir)
    assertNull(error)
    PluginLoader.load(dir, module).use { host ->
      assertTrue(
        host.warnings.any { it.contains("refused: needs an 'hp' vital") },
        host.warnings.joinToString("\n"),
      )
    }
  }
}
