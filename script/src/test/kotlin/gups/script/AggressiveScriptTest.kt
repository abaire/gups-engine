package gups.script

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AggressiveScriptTest {
  private fun zonePkg() = "package(title = \"Z\", api_version = 1, kind = \"zone\")"

  private fun load(code: String) =
    ScriptLoader(
        WorldDir(
          tempWorld(
            mapOf(
              "zones/z/package.star" to zonePkg(),
              "zones/z/content.star" to "room(\"start\")\n${code.trimIndent()}",
            )
          )
        ),
        ScriptLimits.HARNESS,
      )
      .load(listOf("zones/z"))

  @Test
  fun `aggressive rejects via as an unexpected keyword`() {
    val r = load("mob(\"m\", vitals = vitals(hp = 1), behaviors = [aggressive(via = \"sight\")])")
    assertFalse(r.ok)
    assertContains(r.report(), "unexpected keyword argument 'via'")
  }

  @Test
  fun `aggressive chance rejects a fraction with the percent hint`() {
    val r = load("mob(\"m\", vitals = vitals(hp = 1), behaviors = [aggressive(chance = 0.5)])")
    assertFalse(r.ok)
    assertContains(
      r.report(),
      "aggressive(chance): must be a whole percent from 0 to 100, not a fraction (write 25 for 0.25)",
    )
  }

  @Test
  fun `every behavior with a priority accepts priority and rejects a non-int`() {
    val ok =
      load(
        """
        mob("m", vitals = vitals(hp = 1), behaviors = [
            wander(priority = 1), aggressive(priority = 2), assist("m", priority = 3),
            flee(vitals(hp = 10), priority = 4), follow("players", priority = 5),
            pursue("players", priority = 6), scavenge(priority = 7), heel(priority = 8),
            defend(group = False, priority = 9)])
        """
      )
    assertTrue(ok.ok, ok.report())
    val bad = load("mob(\"m\", vitals = vitals(hp = 1), behaviors = [heel(priority = \"high\")])")
    assertFalse(bad.ok)
    assertContains(bad.report(), "heel(priority)")
  }
}
