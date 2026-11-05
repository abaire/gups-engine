package gups.script

import gups.script.testing.TestWorlds
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class TestWorldsTest {
  @TempDir lateinit var tmp: Path

  @Test
  fun `copying a world leaves out its local server data`() {
    val from = tmp.resolve("template")
    from.resolve("zones/z").createDirectories()
    from.resolve("MODULE.gups").writeText("module()\n")
    from.resolve("zones/z/zone.gups").writeText("zone()\n")
    from.resolve(".gups/backups").createDirectories()
    from.resolve(".gups/world.db").writeText("stale")

    val to = TestWorlds.copyWorld(from, tmp.resolve("copy"))

    assertEquals("module()\n", to.resolve("MODULE.gups").readText())
    assertEquals("zone()\n", to.resolve("zones/z/zone.gups").readText())
    assertFalse(to.resolve(".gups").exists())
    assertEquals(
      listOf("MODULE.gups", "zones"),
      Files.list(to).use { s -> s.map { it.fileName.toString() }.sorted().toList() },
    )
  }

  @Test
  fun `an override's preamble keeps the template's`() {
    val rules =
      TestWorlds.rules(
        """
        # An override's own comment, before its first def.
        _BONUS = 1

        def damage(ctx):
            return 5 + _BONUS
        """
      )
    assertTrue("_PHYSICAL = " in rules, "the template's constants stay: $rules")
    assertTrue("_BONUS = 1" in rules, "the override's constants are added: $rules")
  }

  @Test
  fun `rules contains spell_hit override returning 100`() {
    assertTrue("def _spell_hit(ctx):\n    return 100" in TestWorlds.rules())
  }

  @Test
  fun `copyWorld rewrites spell_hit by default and preserves it when false`() {
    val from = tmp.resolve("template_with_rules")
    from.resolve("rules").createDirectories()
    from
      .resolve("rules/combat.star")
      .writeText(
        """
        def _spell_hit(ctx):
            return 70 + (ctx.attacker.int - ctx.defender.wis) * 2

        def to_hit(ctx):
            return 50
        """
          .trimIndent()
      )

    val to1 = TestWorlds.copyWorld(from, tmp.resolve("copy1"))
    val combatStar1 = to1.resolve("rules/combat.star").readText()
    assertTrue("def _spell_hit(ctx):\n    return 100" in combatStar1)
    assertFalse("ctx.attacker.int - ctx.defender.wis" in combatStar1)

    val to2 = TestWorlds.copyWorld(from, tmp.resolve("copy2"), spellsAlwaysLand = false)
    val combatStar2 = to2.resolve("rules/combat.star").readText()
    assertTrue("ctx.attacker.int - ctx.defender.wis" in combatStar2)
  }
}
