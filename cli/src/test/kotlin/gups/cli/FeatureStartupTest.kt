package gups.cli

import gups.core.world.Feature
import gups.core.world.Features
import gups.persist.Database
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

/** `features`, `rename` and `MESSAGES.gups` at startup, in `migrate-check` and in deploys. */
class FeatureStartupTest {
  private fun ids(features: Features) = features.ids.joinToString(", ") { "\"$it\"" }

  /** A one-room world with [features] and the extra `module(...)` arguments. */
  private fun world(
    features: Features = Features.ALL,
    extra: String = "",
    messages: String? = null,
  ): Path {
    val root = Files.createTempDirectory("gups-features")
    fun file(path: String, text: String) {
      val p = root.resolve(path)
      p.parent.createDirectories()
      p.writeText(text)
    }
    file("MODULE.gups", TestWorlds.module("features = [${ids(features)}] $extra".trimEnd()))
    file("rules/package.star", TestWorlds.RULES_PACKAGE)
    file("rules/combat.star", TestWorlds.rulesFor(features.ids))
    file("zones/z/package.star", "package(api_version = 1, kind = \"zone\")\n")
    file("zones/z/rooms.star", "room(\"start\", name = \"Start\")\n")
    messages?.let { file("MESSAGES.gups", it) }
    return root
  }

  private fun boot(root: Path, db: Path? = null, wipe: Boolean = false): Pair<Boolean, String> {
    val logs = StringBuffer()
    val server =
      GupsServer(WorldDir(root), port = 0, db = db, wipe = wipe) { logs.append(it).append('\n') }
    return server.boot().also { if (it) server.stop() } to logs.toString()
  }

  private fun without(vararg off: Feature) =
    Features(
      Feature.entries.toSet() -
        off.toSet() -
        Feature.entries.filter { f -> f.requires.any { it in off } }.toSet()
    )

  @Test
  fun `a world with a feature subset starts`() {
    val (ok, log) = boot(world(without(Feature.SKILLS, Feature.LEVELING)))
    assertTrue(ok, log)
  }

  @Test
  fun `an unknown feature stops the server`() {
    val root = world()
    val module = root.resolve("MODULE.gups")
    module.writeText(module.toFile().readText().replace("\"leveling\"", "\"levelling\""))
    val (ok, log) = boot(root)
    assertFalse(ok)
    assertContains(log, "unknown feature 'levelling' (did you mean 'leveling'?)")
  }

  @Test
  fun `track without combat stops the server`() {
    val root = world(Features(setOf(Feature.TRACK, Feature.SKILLS)))
    val (ok, log) = boot(root)
    assertFalse(ok)
    assertContains(log, "feature 'track' needs the 'combat' feature")
  }

  @Test
  fun `a bad rename stops the server`() {
    val (ok, log) = boot(world(extra = ", rename = {\"cast\": \"north\"}"))
    assertFalse(ok)
    assertContains(log, "'north' is a direction")
  }

  @Test
  fun `a bad MESSAGES gups stops the server with a suggestion`() {
    val (ok, log) = boot(world(messages = "messages({\"cast.begin.knwn\": \"x\"})\n"))
    assertFalse(ok)
    assertContains(log, "unknown message 'cast.begin.knwn' (did you mean 'cast.begin.known'?)")
    val (ok2, log2) = boot(world(messages = "messages({\"say.self\": \"You say.\"})\n"))
    assertFalse(ok2)
    assertContains(log2, "missing required placeholder {text}")
  }

  @Test
  fun `an override of a message of an off feature warns but starts`() {
    val (ok, log) =
      boot(
        world(without(Feature.SKILLS), messages = "messages({\"cast.what\": \"Invoke what?\"})\n")
      )
    assertTrue(ok, log)
    assertContains(log, "warning: MESSAGES.gups: 'cast.what' belongs to the 'skills' feature")
  }

  private fun recorded(db: Path): String? = Database.open(db).use { it.meta()["world_features"] }

  @Test
  fun `the first start records the features and a restart with them runs`() {
    val features = without(Feature.LEVELING)
    val db = tempDb()
    assertTrue(boot(world(features), db).first)
    assertEquals("combat,track,skills,standing", recorded(db))
    assertTrue(boot(world(features), db).first)
  }

  @Test
  fun `a changed feature set refuses to start until wipe`() {
    val db = tempDb()
    assertTrue(boot(world(Features.ALL), db).first)
    val (ok, log) = boot(world(without(Feature.LEVELING)), db)
    assertFalse(ok)
    assertContains(log, "features: removed leveling")
    assertContains(log, "--wipe")
    val (wiped, wipeLog) = boot(world(without(Feature.LEVELING)), db, wipe = true)
    assertTrue(wiped, wipeLog)
    assertEquals("combat,track,skills,standing", recorded(db))
    assertTrue(boot(world(without(Feature.LEVELING)), db).first)
  }

  @Test
  fun `a database from before features is taken to have all four`() {
    val db = tempDb()
    assertTrue(boot(world(Features.ALL), db).first)
    Database.open(db).use { it.deleteMeta("world_features") }
    assertTrue(boot(world(Features.ALL), db).first)
    Database.open(db).use { it.deleteMeta("world_features") }
    val (ok, log) = boot(world(without(Feature.SKILLS)), db)
    assertFalse(ok)
    assertContains(log, "features: removed skills")
  }

  @Test
  fun `migrate-check fails when the snapshot's features differ`() {
    val snapshot = tempDb()
    assertTrue(boot(world(Features.ALL), snapshot).first)
    val outcome = MigrationChecker(WorldDir(world(without(Feature.TRACK))), snapshot).check()
    assertFalse(outcome.ok)
    assertContains(outcome.problems.single(), "this schema or feature change needs a server wipe")
    assertContains(outcome.problems.single(), "features: removed track")
    assertTrue(MigrationChecker(WorldDir(world(Features.ALL)), snapshot).check().ok)
  }
}
