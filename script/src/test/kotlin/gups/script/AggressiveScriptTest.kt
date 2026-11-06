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
  fun `aggressive via rejects an unknown channel with the senses wording`() {
    val r = load("mob(\"m\", vitals = vitals(hp = 1), behaviors = [aggressive(via = \"nope\")])")
    assertFalse(r.ok)
    assertContains(r.report(), "aggressive(via): 'nope' is not one of sight, sound, spell")
  }

  @Test
  fun `aggressive via rejects an empty list`() {
    val r = load("mob(\"m\", vitals = vitals(hp = 1), behaviors = [aggressive(via = [])])")
    assertFalse(r.ok)
    assertContains(r.report(), "aggressive(via): needs at least one channel")
  }

  @Test
  fun `aggressive via rejects a duplicated channel`() {
    val r =
      load(
        "mob(\"m\", vitals = vitals(hp = 1), behaviors = [aggressive(via = [\"sight\", \"sight\"])])"
      )
    assertFalse(r.ok)
    assertContains(r.report(), "aggressive(via): duplicate channel")
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
  fun `aggressive via accepts a declared channel and a list of built-ins`() {
    val r = load("mob(\"m\", vitals = vitals(hp = 1), behaviors = [aggressive(via = \"spell\")])")
    assertTrue(r.ok, r.report())
    val r2 =
      load(
        "mob(\"m\", vitals = vitals(hp = 1), behaviors = [aggressive(toward = \"players\", chance = 50, via = [\"sight\", \"sound\"])])"
      )
    assertTrue(r2.ok, r2.report())
  }
}
