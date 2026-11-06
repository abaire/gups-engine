package gups.script

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class PercentTest {
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
  fun `wander refuses a fraction chance with a hint`() {
    val r = load("mob(\"m\", vitals = vitals(hp = 1), behaviors = [wander(chance = 0.5)])")
    assertFalse(r.ok)
    assertContains(
      r.report(),
      "wander(chance): must be a whole percent from 0 to 100, not a fraction (write 25 for 0.25)",
    )
  }

  @Test
  fun `wander refuses an out-of-range or non-int chance`() {
    for (bad in listOf("-1", "101", "True", "\"5\"")) {
      val r = load("mob(\"m\", vitals = vitals(hp = 1), behaviors = [wander(chance = $bad)])")
      assertFalse(r.ok, "Expected error for chance = $bad")
      assertContains(r.report(), "wander(chance): must be a whole percent from 0 to 100")
    }
  }

  @Test
  fun `loot refuses a fraction chance with a hint`() {
    // 1.0 was the old "always drops" default; it must now be written as 100.
    val r =
      load("item(\"k\")\nmob(\"m\", vitals = vitals(hp = 1), loot = [loot(\":k\", chance = 1.0)])")
    assertFalse(r.ok)
    assertContains(
      r.report(),
      "loot(chance): must be a whole percent from 0 to 100, not a fraction (write 25 for 0.25)",
    )
  }

  @Test
  fun `loot refuses an out-of-range chance`() {
    for (bad in listOf("-1", "101")) {
      val r =
        load(
          "item(\"k\")\nmob(\"m\", vitals = vitals(hp = 1), loot = [loot(\":k\", chance = $bad)])"
        )
      assertFalse(r.ok, "Expected error for chance = $bad")
      assertContains(r.report(), "loot(chance): must be a whole percent from 0 to 100")
    }
  }

  @Test
  fun `proc refuses a fraction chance with a hint`() {
    val r =
      load(
        "item(\"w\", wear = \"wield\", procs = [proc(on = \"hit\", chance = 0.25, effect = damage(dice = \"1d1\", type = DAMAGE.fire))])"
      )
    assertFalse(r.ok)
    assertContains(
      r.report(),
      "proc(chance): must be a whole percent from 0 to 100, not a fraction (write 25 for 0.25)",
    )
  }

  @Test
  fun `outcome refuses a fraction weight with a hint`() {
    val r =
      load(
        "item(\"w\", wear = \"wield\", procs = [proc(on = \"hit\", outcomes = [outcome(1.0, effect = damage(dice = \"1d1\", type = DAMAGE.fire))])])"
      )
    assertFalse(r.ok)
    assertContains(
      r.report(),
      "outcome(weight): must be a whole percent from 0 to 100, not a fraction (write 25 for 0.25)",
    )
  }
}
