package gups.cli

import com.github.ajalt.clikt.testing.test
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `feature` tags, `world.html` and `gups docs --world` filtering. */
class DocsWorldTest {
  private val robot = System.getProperty("gups.robotWorld")

  private fun docs(vararg args: String): Map<String, String> {
    val out = Files.createTempDirectory("gups-docs")
    val r = gupsCommand().test(listOf("docs", "--out", out.toString()) + args)
    assertEquals(0, r.statusCode, r.output)
    return DocsRenderer.FILES.associateWith { out.resolve(it).readText() }
  }

  @Test
  fun `api json tags feature entries and leaves kernel entries alone`() {
    val json = docs().getValue("api.json")
    fun feature(symbol: String): String? =
      Regex("\"symbol\": \"${Regex.escape(symbol)}\",(?:\\s*\"feature\": \"(\\w+)\")?")
        .find(json)
        ?.let { it.groupValues[1].ifEmpty { null } }
    assertEquals("skills", feature("skill"))
    assertEquals("combat", feature("hook:before_attack"))
    assertEquals("track", feature("room.size"))
    assertEquals("leveling", feature("rules:xp_for_kill"))
    assertEquals("combat", feature("aggressive"))
    assertEquals(null, feature("hook:on_enter"))
    assertEquals(null, feature("room"))
    assertContains(json, "\"messages\": [")
    assertContains(json, "\"symbol\": \"message:cast.begin.known\"")
  }

  @Test
  fun `every page shows feature badges and world html lists the message catalog`() {
    val pages = docs()
    assertContains(pages.getValue("declarations.html"), "feature: skills")
    assertContains(pages.getValue("hooks.html"), "feature: combat")
    assertContains(pages.getValue("rules.html"), "feature: leveling")
    val world = pages.getValue("world.html")
    assertContains(world, "id=\"message-cast.begin.known\"")
    assertContains(world, "{actor} begins casting {skill}.")
    assertContains(world, "feature: skills")
    assertFalse("Schema" in world, "no schema without --world")
  }

  @Test
  fun `with a world the pages leave out the entries of features that are off`() {
    val all = docs()
    val robotDocs = docs("--world", robot)
    // The robot world has combat and skills only.
    for (gone in
      listOf(
        "exit_entry_index",
        "room.size",
        "xp_for_kill",
        "retreat_check",
        "aggressive_unless",
      )) {
      assertFalse(gone in robotDocs.getValue("declarations.html"))
    }
    assertContains(all.getValue("declarations.html"), "id=\"room.size\"")
    assertFalse("id=\"room.size\"" in robotDocs.getValue("declarations.html"))
    assertFalse("id=\"rules-xp_for_kill\"" in robotDocs.getValue("rules.html"))
    assertContains(all.getValue("rules.html"), "id=\"rules-xp_for_kill\"")
    assertContains(robotDocs.getValue("rules.html"), "id=\"rules-to_hit\"")
    for (lag in listOf("advance_lag", "retreat_lag", "retreat_fail_lag")) {
      assertFalse("id=\"rules-$lag\"" in robotDocs.getValue("rules.html"), lag)
      assertContains(all.getValue("rules.html"), "id=\"rules-$lag\"")
      assertFalse("id=\"modifier-$lag\"" in robotDocs.getValue("rules.html"), lag)
      assertContains(all.getValue("rules.html"), "id=\"modifier-$lag\"")
    }
    assertContains(robotDocs.getValue("rules.html"), "id=\"rules-flee_fail_lag\"")
    assertContains(robotDocs.getValue("rules.html"), "id=\"modifier-flee_fail_lag\"")
    assertContains(all.getValue("rules.html"), "id=\"modifier-flee_fail_lag\"")
    assertFalse("hook-on_combat_tick" !in robotDocs.getValue("hooks.html"))
    assertFalse("hook-on_level_change" in robotDocs.getValue("hooks.html"))
    assertContains(all.getValue("hooks.html"), "hook-on_level_change")
    for (field in listOf("old_level", "new_level")) {
      assertFalse("id=\"ctx.$field\"" in robotDocs.getValue("ctx.html"), field)
      assertContains(all.getValue("ctx.html"), "id=\"ctx.$field\"")
    }
    assertFalse("id=\"knockback\"" in robotDocs.getValue("effects.html"))
    assertContains(all.getValue("effects.html"), "id=\"knockback\"")
    assertFalse("id=\"message-leveling.gain\"" in robotDocs.getValue("world.html"))
    assertContains(robotDocs.getValue("world.html"), "id=\"message-cast.begin.known\"")
    // api.json is the whole model whatever the world.
    assertEquals(all.getValue("api.json"), robotDocs.getValue("api.json"))
  }

  @Test
  fun `world html shows the schema, features, renamed commands and overrides`() {
    val world = docs("--world", robot).getValue("world.html")
    assertContains(world, "Integrity")
    assertContains(world, "psychic")
    assertContains(world, "<code>combat</code>: on")
    assertContains(world, "<code>track</code>: off")
    assertContains(world, "<code>leveling</code>: off")
    assertContains(world, "id=\"command-invoke\"")
    assertContains(world, "<code>cast</code>")
    assertFalse("id=\"command-cast\"" in world)
    assertFalse("id=\"command-advance\"" in world)
    assertContains(world, "id=\"command-look\"")
    // The world's override sits beside the default text.
    assertContains(
      world,
      "{actor}&#39;s implant whines as it spins up {skill}.".replace("&#39;", "'"),
    )
    assertTrue("Your implant spins up {skill}." in world)
  }

  @Test
  fun `world html lists the gmcp packages the lib declares`() {
    val dir =
      tempWorld(
        mapOf(
          "lib/package.star" to "package(api_version = 1, kind = \"lib\")",
          "lib/a.star" to "gmcp_package(\"Shop.Stock\", doc = \"Items for sale\")",
          "zones/z/package.star" to "package(api_version = 1, kind = \"zone\")",
          "zones/z/rooms.star" to "room(\"start\")",
        )
      )
    val world = docs("--world", dir.toString()).getValue("world.html")
    assertContains(world, "<h2>GMCP packages")
    assertContains(world, "<code>Shop.Stock</code>")
    assertContains(world, "Items for sale")
    assertContains(world, "<code>lib</code>")
  }

  @Test
  fun `a MESSAGES gups with a problem stops docs --world`() {
    val copy = Files.createTempDirectory("gups-docs-world")
    java.io.File(robot).copyRecursively(copy.toFile())
    copy.resolve("MESSAGES.gups").toFile().writeText("messages({\"cast.begin.knwn\": \"x\"})\n")
    val r =
      gupsCommand()
        .test(listOf("docs", "--world", copy.toString(), "--out", copy.resolve("out").toString()))
    assertEquals(1, r.statusCode)
    assertContains(r.stderr, "unknown message 'cast.begin.knwn'")
  }
}
