package gups.script

import gups.core.model.ItemProto
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import gups.core.model.TrackSize
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinterTest {

  @Test
  fun `dead-end room without dead_end_ok reports an error`() {
    val root =
      tempWorld(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
            room("trap", name = "Trap", exits = [])
            """
              .trimIndent(),
        )
      )
    val report = Linter(WorldDir(root)).lint()
    assertFalse(report.ok)
    assertEquals(1, report.errors.size)
    assertContains(report.errors[0].message, "dead-end room (0 exits) without 'dead_end_ok' flag")
  }

  @Test
  fun `room with one exit without dead_end_ok passes`() {
    val root =
      tempWorld(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
            room("start", name = "Start", exits = [exit("north", to = ":tunnel")])
            room("tunnel", name = "Tunnel", exits = [exit("south", to = ":start")])
            """
              .trimIndent(),
        )
      )
    val report = Linter(WorldDir(root)).lint()
    assertTrue(report.ok, report.findings.joinToString { it.message })
  }

  @Test
  fun `dead-end room with dead_end_ok passes`() {
    val root =
      tempWorld(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
            room("trap", name = "Trap", flags = ["dead_end_ok"], exits = [])
            """
              .trimIndent(),
        )
      )
    val report = Linter(WorldDir(root)).lint()
    assertTrue(report.ok, report.findings.joinToString { it.message })
  }

  @Test
  fun `missing reverse exit reports an error`() {
    val root =
      tempWorld(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
            room("a", name = "A", flags = ["dead_end_ok"], exits = [exit("north", to = ":b")])
            room("b", name = "B", flags = ["dead_end_ok"], exits = [])
            """
              .trimIndent(),
        )
      )
    val report = Linter(WorldDir(root)).lint()
    assertFalse(report.ok)
    val err = report.errors.first { it.message.contains("no reverse exit") }
    assertContains(
      err.message,
      "has no reverse exit in //zones/z:b leading 'south' back to //zones/z:a",
    )
  }

  @Test
  fun `one_way exit does not require a reverse exit`() {
    val root =
      tempWorld(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
            room("slide", name = "Slide", flags = ["dead_end_ok"], exits = [exit("down", to = ":pit", one_way = True)])
            room("pit", name = "Pit", flags = ["dead_end_ok"], exits = [])
            """
              .trimIndent(),
        )
      )
    val report = Linter(WorldDir(root)).lint()
    assertTrue(report.ok, report.findings.joinToString { it.message })
  }

  @Test
  fun `mismatched doors across exits report an error`() {
    val root =
      tempWorld(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
            item("key_a", name = "Key A")
            room("a", name = "A", flags = ["dead_end_ok"], exits = [exit("north", to = ":b", door = door(state = "locked", key = ":key_a"))])
            room("b", name = "B", flags = ["dead_end_ok"], exits = [exit("south", to = ":a", door = door(state = "closed"))])
            """
              .trimIndent(),
        )
      )
    val report = Linter(WorldDir(root)).lint()
    assertFalse(report.ok)
    assertContains(report.errors.first().message, "doors do not match")
  }

  @Test
  fun `prototype declaration changed since base requires version bump`() {
    val root =
      tempWorld(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            room("r", name = "R", size = SIZE.normal, flags = ["dead_end_ok"], exits = [])
            mob("m", name = "Goblin", vitals = vitals(hp = 50), version = 1)
            item("i", name = "Sword", weight = 5, version = 1)
            """
              .trimIndent(),
        )
      )

    val loader = ScriptLoader(WorldDir(root), ScriptLimits.HARNESS)
    val currentProtos = loader.load(listOf("zones/z")).prototypes()

    // Base with different stats/weights/sizes but current versions are not bumped
    val baseProtos =
      Prototypes(
        rooms =
          listOf(
            RoomProto(
              gups.core.Label("//zones/z:r"),
              "R",
              size = TrackSize.LARGE,
              flags = setOf("dead_end_ok"),
              version = 1,
            )
          ),
        mobs =
          listOf(
            MobProto(
              gups.core.Label("//zones/z:m"),
              "Goblin",
              emptyList(),
              vitals = mapOf("hp" to 30),
              version = 1,
            )
          ),
        items =
          listOf(
            ItemProto(gups.core.Label("//zones/z:i"), "Sword", emptyList(), weight = 2, version = 1)
          ),
      )

    val report = Linter(WorldDir(root), basePrototypes = baseProtos).lint()
    assertFalse(report.ok)
    assertEquals(3, report.errors.size)
    assertTrue(report.errors.any { "room prototype declaration changed" in it.message })
    assertTrue(report.errors.any { "mob prototype declaration changed" in it.message })
    assertTrue(report.errors.any { "item prototype declaration changed" in it.message })
  }

  @Test
  fun `presentation and code changes do not require version bump`() {
    val root =
      tempWorld(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            def _think(ctx): pass
            room("r", name = "New Room Name", desc = "New Desc", flags = ["dead_end_ok"], exits = [])
            mob("m", name = "New Mob Name", desc = "New Mob Desc", keywords = ["new"], think = _think, version = 1)
            item("i", name = "New Sword Name", desc = "New Item Desc", keywords = ["blade"], version = 1)
            """
              .trimIndent(),
        )
      )

    val baseProtos =
      Prototypes(
        rooms =
          listOf(
            RoomProto(
              gups.core.Label("//zones/z:r"),
              "Old Room",
              desc = "Old Desc",
              flags = setOf("dead_end_ok"),
              version = 1,
            )
          ),
        mobs =
          listOf(
            MobProto(
              gups.core.Label("//zones/z:m"),
              "Old Mob",
              listOf("old"),
              desc = "Old Mob Desc",
              version = 1,
            )
          ),
        items =
          listOf(
            ItemProto(
              gups.core.Label("//zones/z:i"),
              "Old Sword",
              listOf("old"),
              desc = "Old Item Desc",
              version = 1,
            )
          ),
      )

    val report = Linter(WorldDir(root), basePrototypes = baseProtos).lint()
    assertTrue(report.ok, report.errors.joinToString { it.message })
  }

  @Test
  fun `unchanged world with behaviors and modifiers lints cleanly against itself`() {
    val content =
      """
      def _mod(ctx, base): return base
      def _cmd(ctx): pass
      def _think(ctx): pass

      room("r", name = "R", flags = ["dead_end_ok"], exits = [], commands = {"push": _cmd}, modifiers = {"to_hit": _mod})
      mob(
          "rat",
          name = "Rat",
          behaviors = [
              wander(radius = 3, chance = 20),
              aggressive(toward = "players", unless_level_above = 5),
              assist(allies = ":rat"),
              flee(below = vitals(hp = 20)),
              guard(exit = "north", allies = "mobs"),
              follow(target = "players"),
              pursue(target = "players"),
              sentinel(),
              scavenge(),
          ],
          think = _think,
          version = 1,
      )
      item("ring", name = "Ring", wear = "ring", modifiers = {"damage": _mod}, commands = {"rub": _cmd}, version = 1)
      """
        .trimIndent()

    val root =
      tempWorld(mapOf("zones/z/package.star" to zonePkg(), "zones/z/content.star" to content))
    // Load base prototypes via a completely separate ScriptLoader evaluation (new instances)
    val baseDir = WorldDir(root)
    val baseProtos =
      ScriptLoader(baseDir, ScriptLimits.HARNESS).load(baseDir.packages()).prototypes()

    val report = Linter(WorldDir(root), basePrototypes = baseProtos).lint()
    assertTrue(report.ok, report.errors.joinToString { it.message })

    // Changing a behavior parameter without bumping version must fail
    val changedRoot =
      tempWorld(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            content.replace("wander(radius = 3, chance = 20)", "wander(radius = 4, chance = 20)"),
        )
      )
    val changedReport = Linter(WorldDir(changedRoot), basePrototypes = baseProtos).lint()
    assertFalse(changedReport.ok)
    assertTrue(
      changedReport.errors.any {
        "mob prototype declaration changed since base without a version bump" in it.message
      }
    )
  }

  @Test
  fun `changing an aggressive chance or senses requires a version bump`() {
    fun content(chance: Int, senses: String) =
      """
      mob(
          "rat",
          name = "Rat",
          senses = $senses,
          behaviors = [aggressive(toward = "players", chance = $chance)],
          version = 1,
      )
      """
        .trimIndent()

    val root =
      tempWorld(
        mapOf("zones/z/package.star" to zonePkg(), "zones/z/content.star" to content(100, "{}"))
      )
    val baseProtos =
      ScriptLoader(WorldDir(root), ScriptLimits.HARNESS)
        .load(WorldDir(root).packages())
        .prototypes()

    val chanceChanged =
      tempWorld(
        mapOf("zones/z/package.star" to zonePkg(), "zones/z/content.star" to content(50, "{}"))
      )
    val chanceReport = Linter(WorldDir(chanceChanged), basePrototypes = baseProtos).lint()
    assertFalse(chanceReport.ok)
    assertTrue(
      chanceReport.errors.any {
        "mob prototype declaration changed since base without a version bump" in it.message
      }
    )

    val sensesChanged =
      tempWorld(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to content(100, "{\"sight\": 0}"),
        )
      )
    val sensesReport = Linter(WorldDir(sensesChanged), basePrototypes = baseProtos).lint()
    assertFalse(sensesReport.ok)
    assertTrue(
      sensesReport.errors.any {
        "mob prototype declaration changed since base without a version bump" in it.message
      }
    )
  }

  @Test
  fun `attribute key written but never read emits a warning`() {
    val root =
      tempWorld(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/hooks.star" to
            """
            def _hook(ctx):
                ctx.set_attr(ctx.actor, "written_only", True)
                ctx.set_attr(ctx.actor, "written_and_read", 42)
                ctx.get_attr(ctx.actor, "written_and_read")
            room("r", name = "R", flags = ["dead_end_ok"], exits = [], on_enter = _hook)
            """
              .trimIndent(),
        )
      )

    val report = Linter(WorldDir(root)).lint()
    assertTrue(report.ok) // warnings do not fail lint
    assertEquals(1, report.warnings.size)
    assertContains(
      report.warnings.single().message,
      "attribute key 'z:written_only' is written but never read",
    )
  }

  @Test
  fun `region consistency warns on undeclared or unapplied regions`() {
    val root =
      tempWorld(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            region("unused_region")
            room("r", name = "R", flags = ["dead_end_ok"], regions = ["undeclared_region"], exits = [])
            """
              .trimIndent(),
        )
      )

    val report = Linter(WorldDir(root)).lint()
    assertTrue(report.ok)
    assertEquals(2, report.warnings.size)
    assertTrue(
      report.warnings.any { "region 'undeclared_region' applied to room(s)" in it.message }
    )
    assertTrue(
      report.warnings.any {
        "region 'unused_region' is declared in //zones/z but never applied" in it.message
      }
    )
  }

  @Test
  fun `github format renders repo paths, includes labels, and escapes special characters`() {
    val f1 =
      Linter.Finding(
        severity = Linter.Finding.Severity.ERROR,
        message = "dead-end room (0 exits) without 'dead_end_ok' flag",
        file = "//zones/tutorial:rooms.star",
        line = 12,
        label = gups.core.Label("//zones/tutorial:gate"),
      )
    assertEquals(
      "::error file=zones/tutorial/rooms.star,line=12,title=gups lint:://zones/tutorial:gate: dead-end room (0 exits) without 'dead_end_ok' flag",
      f1.renderGithub(),
    )

    val f2 =
      Linter.Finding(
        severity = Linter.Finding.Severity.WARNING,
        message = "attribute 100% key\nwith newline\rand return",
        file = null,
        line = null,
        label = gups.core.Label("//zones/forest:cave"),
      )
    assertEquals(
      "::warning file=zones/forest/package.star,title=gups lint:://zones/forest:cave: attribute 100%25 key%0Awith newline%0Dand return",
      f2.renderGithub(),
    )
  }

  @Test
  fun `markdown summary renders as expected`() {
    val finding =
      Linter.Finding(
        Linter.Finding.Severity.ERROR,
        "test error message",
        file = "zones/z/rooms.star",
        line = 10,
      )
    val report = Linter.Report(listOf(finding))
    assertContains(report.renderMarkdown(), "## `gups lint` summary")
    assertContains(report.renderMarkdown(), "❌ Error")
    assertContains(report.renderMarkdown(), "1 error, 0 warnings")
  }
}
