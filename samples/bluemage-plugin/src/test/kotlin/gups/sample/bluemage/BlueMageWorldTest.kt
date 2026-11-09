package gups.sample.bluemage

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

/** The built bluemage jar, loaded into a copy of the template world and driven by the harness. */
class BlueMageWorldTest {
  private fun write(root: Path, relativePath: String, text: String) {
    val p = root.resolve(relativePath)
    p.parent.createDirectories()
    p.writeText(text.trimIndent() + "\n")
  }

  private val templateWorld = Path.of(System.getProperty("gups.templateWorld"))

  /** A world dir whose MODULE.gups pins the jar when [pinned], with the template's rules. */
  private fun newWorld(pinned: Boolean, learnChanceRules: Boolean): Path {
    val tmp = Files.createTempDirectory("gups-bluemage")
    val schema = templateWorld.resolve("MODULE.gups").readText()
    if (pinned) {
      val jar = tmp.resolve("plugins/bluemage.jar").also { it.parent.createDirectories() }
      Path.of(System.getProperty("gups.sample.bluemageJar")).copyTo(jar)
      write(
        tmp,
        "MODULE.gups",
        schema.replaceFirst(
          "module(",
          "module(\n    plugins = {\"bluemage\": \"${PluginLoader.sha256(jar)}\"},",
        ),
      )
    } else {
      write(tmp, "MODULE.gups", schema)
    }
    Files.list(templateWorld.resolve("rules"))
      .use { s -> s.toList() }
      .forEach { it.copyTo(tmp.resolve("rules").createDirectories().resolve(it.fileName)) }
    gups.script.testing.TestWorlds.makeSpellsLand(tmp.resolve("rules"))

    // The template's recognizes_skill is a dice roll; the test needs every onlooker to recognize.
    // Defining it a second time in another file would be a load error, so the copy is edited.
    val perception = tmp.resolve("rules/perception.star")
    val text = perception.readText()
    val header = "def recognizes_skill(ctx):"
    assertTrue(header in text, "the template's perception.star defines recognizes_skill")
    val always =
      text.replaceFirst(header, "$header\n    return True\n\ndef _template_recognizes_skill(ctx):")
    val tuned =
      if (learnChanceRules) {
        // Past the plugin's cap, so every recognition is learned with the capped chance.
        always + "\ndef bluemage_learn_chance(ctx):\n    return ctx.base + 100\n"
      } else {
        always
      }
    perception.writeText(tuned)
    return tmp
  }

  private fun content(tmp: Path) {
    write(tmp, "skills/package.star", """package(api_version = 1, kind = "skills")""")
    write(
      tmp,
      "skills/skills.star",
      """
      skill("breath",
            kind = "spell",
            warmup = 0,
            lag = 1,
            range = "near",
            target = "enemy",
            tags = ["blue"],
            effect = damage(dice = "1d1", type = DAMAGE.fire))
      """,
    )
    write(tmp, "zones/lair/package.star", """package(api_version = 1, kind = "zone")""")
    write(
      tmp,
      "zones/lair/content.star",
      """
      def _awaken(ctx):
          ctx.send(ctx.actor, "awaken: %s" % bluemage.awaken(ctx.actor))

      room("hall", name = "Hall", desc = "A hall.", flags = ["dead_end_ok"], exits = [exit("north", ":lair"), exit("east", ":shrine")])
      room("lair", name = "Lair", desc = "A lair.", flags = ["dead_end_ok"], exits = [exit("south", ":hall")])
      room("shrine", name = "Shrine", desc = "A shrine.", flags = ["dead_end_ok"], exits = [exit("west", ":hall")], on_enter = _awaken)

      mob("drake", name = "a drake", desc = "A drake.", keywords = ["drake"], skills = {"//skills:breath": 100})
      """,
    )
    write(
      tmp,
      "zones/lair/bluemage_test.star",
      """
      def _breathe(w, drake, p, casts):
          out = ""
          for _ in range(casts):
              w.act(drake, "cast breath Ann")
              w.run(pulses = 8)
              out += p.output()
          return out

      def _scene(t, seed):
          w = t.world(seed = seed, packages = ["//skills", "//zones/lair"])
          p = w.spawn_player("Ann", at = ":hall", vitals = vitals(hp = 900))
          drake = w.spawn(":drake", at = ":lair")
          return (w, p, drake)

      def test_awaken_grants_the_trait(t):
          w = t.world(seed = 1, packages = ["//skills", "//zones/lair"])
          p = w.spawn_player("Ann", at = ":hall")
          t.contains(p.do("bluemagic"), "You are not a blue mage.")
          t.contains(p.do("east"), "awaken: None")
          t.contains(p.do("bluemagic"), "You haven't learned any blue magic.")

      def test_a_blue_mage_learns_by_watching(t):
          (w, p, drake) = _scene(t, 1)
          p.do("east")
          p.do("west")
          p.do("north")
          out = _breathe(w, drake, p, 30)
          t.contains(out, "You grasp the secret of breath!")
          t.eq(p.skills["//skills:breath"], 10)
          t.contains(p.do("bluemagic"), "breath")

      def test_a_player_without_the_trait_never_learns(t):
          (w, p, drake) = _scene(t, 1)
          p.do("north")
          out = _breathe(w, drake, p, 30)
          t.false("You grasp" in out)
          t.eq(p.skills, {})
      """,
    )
    return
  }

  @Test
  fun `the built bluemage jar runs through TestRunner in the template world`() {
    val tmp = newWorld(pinned = true, learnChanceRules = true)
    content(tmp)
    val dir = WorldDir(tmp)
    val (module, error) = WorldModule.read(dir)
    assertNull(error)
    PluginLoader.load(dir, module).use { host ->
      assertEquals(emptyList(), host.warnings)
      val report = TestRunner(dir, plugins = host).run(listOf("//zones/lair"))
      assertEquals(emptyList(), report.problems)
      assertEquals(3, report.results.size, report.results.joinToString("\n"))
      for (r in report.results) assertEquals(Status.PASS, r.status, "${r.name}: ${r.message}")
    }
  }

  @Test
  fun `a world that defines the learn chance without pinning the plugin fails to load`() {
    val tmp = newWorld(pinned = false, learnChanceRules = true)
    write(tmp, "zones/lair/package.star", """package(api_version = 1, kind = "zone")""")
    write(
      tmp,
      "zones/lair/content.star",
      """
      room("hall", name = "Hall", desc = "A hall.", flags = ["dead_end_ok"], exits = [])
      """,
    )
    write(
      tmp,
      "zones/lair/loads_test.star",
      """
      def test_loads(t):
          t.world(packages = ["//zones/lair"])
      """,
    )
    val dir = WorldDir(tmp)
    val (module, moduleError) = WorldModule.read(dir)
    assertNull(moduleError)
    PluginLoader.load(dir, module).use { host ->
      val report = TestRunner(dir, plugins = host).run(listOf("//zones/lair"))
      val text = (report.problems + report.results.mapNotNull { it.message }).joinToString("\n")
      assertTrue(
        "bluemage_learn_chance" in text && "not a rules export" in text,
        "expected a load error naming the export, got: $text",
      )
    }
  }
}
