package gups.script

import gups.core.Label
import gups.script.testing.TestWorlds
import gups.script.testing.tempWorld
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FactionScriptTest {
  private fun load(
    files: Map<String, String>,
    vararg pkgs: String = arrayOf("zones/z"),
  ): LoadResult = ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(pkgs.toList())

  private fun only(r: LoadResult, pkg: String = "zones/z"): LoadError {
    val errs = r.errors.filter { it.pkg == pkg }
    return errs.firstOrNull { it.kind != "load" } ?: errs.first()
  }

  @Test
  fun `faction declarations load and resolve relative and cross-package keys`() {
    val files =
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to
          """
          room("start")
          faction("goblins", standing = {":orcs": -80, "//zones/x:goblin": 50})
          faction("orcs")
          mob("grunt", factions = [":goblins"])
          mob("loner", factions = [])
          mob("default_mob")
          """
            .trimIndent(),
        "zones/x/package.star" to zonePkg(),
        "zones/x/mobs.star" to "room(\"start\")\nmob(\"goblin\")\n",
      )
    val r = load(files, "zones/z", "zones/x")
    assertTrue(r.errors.isEmpty(), r.report())
    val protos = r.prototypes()

    val goblins = protos.factions[Label("//zones/z:goblins")]
    assertNotNull(goblins)
    assertEquals(-80, goblins.standing[Label("//zones/z:orcs")])
    assertEquals(50, goblins.standing[Label("//zones/x:goblin")])

    val orcs = protos.factions[Label("//zones/z:orcs")]
    assertNotNull(orcs)
    assertTrue(orcs.standing.isEmpty())

    val grunt = protos.mob(Label("//zones/z:grunt"))
    assertEquals(listOf(Label("//zones/z:goblins")), grunt.factions)

    val loner = protos.mob(Label("//zones/z:loner"))
    assertEquals(emptyList(), loner.factions)

    val defaultMob = protos.mob(Label("//zones/z:default_mob"))
    assertNull(defaultMob.factions)

    assertTrue(protos.isFaction(Label("//zones/z:goblins")))
    assertTrue(protos.isFaction(Label("//zones/z:orcs")))
    assertTrue(protos.isFaction(Label("//zones/x:goblin")))
    assertTrue(!protos.isFaction(Label("//zones/z:nonexistent")))
  }

  @Test
  fun `faction with same name as mob prototype in same package configures it`() {
    val files =
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to
          """
          room("start")
          mob("guard")
          faction("guard", standing = {":guard": 100})
          """
            .trimIndent(),
      )
    val r = load(files, "zones/z")
    assertTrue(r.errors.isEmpty(), r.report())
    val protos = r.prototypes()
    assertNotNull(protos.mob(Label("//zones/z:guard")))
    assertNotNull(protos.factions[Label("//zones/z:guard")])
  }

  @Test
  fun `unknown faction in standing key fails to load with did you mean suggestion`() {
    val files =
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to
          """
          room("start")
          faction("orcs")
          faction("goblins", standing = {":orc": -80})
          """
            .trimIndent(),
      )
    val r = load(files, "zones/z")
    val err = r.errors.singleOrNull { it.pkg == "zones/z" }
    assertNotNull(err, r.report())
    assertContains(err.message, "//zones/z:orc")
    assertContains(err.message, "did you mean '//zones/z:orcs'?")
  }

  @Test
  fun `unknown faction in mob factions fails to load with did you mean suggestion`() {
    val files =
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to
          """
          room("start")
          faction("townsfolk")
          mob("guard", factions = [":townsflok"])
          """
            .trimIndent(),
      )
    val r = load(files, "zones/z")
    val err = r.errors.singleOrNull { it.pkg == "zones/z" }
    assertNotNull(err, r.report())
    assertContains(err.message, "//zones/z:townsflok")
    assertContains(err.message, "did you mean '//zones/z:townsfolk'?")
  }

  @Test
  fun `out-of-range standing in faction fails to load`() {
    val files =
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to
          """
          room("start")
          faction("orcs")
          faction("goblins", standing = {":orcs": -150})
          """
            .trimIndent(),
      )
    val r = load(files, "zones/z")
    val err = only(r, "zones/z")
    assertNotNull(err, r.report())
    assertContains(err.message, "faction(): standing of //zones/z:orcs must be in -100..100")
  }

  @Test
  fun `faction and mob factions are refused when standing feature is off`() {
    val files =
      mapOf(
        "MODULE.gups" to TestWorlds.module("features = [\"combat\"]"),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rulesFor(listOf("combat")),
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to "room(\"start\")\nfaction(\"orcs\")\n",
      )
    val r = load(files, "zones/z", "rules")
    val err = only(r, "zones/z")
    assertNotNull(err, r.report())
    assertContains(err.message, "faction() needs the standing feature")

    val mobFiles =
      mapOf(
        "MODULE.gups" to TestWorlds.module("features = [\"combat\"]"),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rulesFor(listOf("combat")),
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to "room(\"start\")\nmob(\"orc\", factions = [])\n",
      )
    val mobR = load(mobFiles, "zones/z", "rules")
    val mobErr = only(mobR, "zones/z")
    assertNotNull(mobErr, mobR.report())
    assertContains(mobErr.message, "mob(factions) needs the standing feature")
  }
}
