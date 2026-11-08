package gups.script

import gups.core.Label
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryDeclTest {
  @Test
  fun `module and mob memory_pulses reject negative values`() {
    val badModDir = tempWorld(mapOf("MODULE.gups" to TestWorlds.module("memory_pulses = -1")))
    val (_, modErr) = WorldModule.read(WorldDir(badModDir))
    assertNotNull(modErr)
    assertContains(modErr.message, "module(memory_pulses): must be at least 0")

    val badMobRes =
      ScriptLoader(
          WorldDir(
            tempWorld(
              mapOf(
                "zones/z/package.star" to zonePkg(),
                "zones/z/content.star" to
                  """
                  room("start")
                  mob("orc", memory_pulses = -1)
                  """
                    .trimIndent(),
              )
            )
          ),
          ScriptLimits.HARNESS,
        )
        .load(listOf("zones/z"))
    assertFalse(badMobRes.ok)
    assertContains(badMobRes.report(), "mob(memory_pulses): must be at least 0")
  }

  @Test
  fun `module and mob memory_pulses require combat feature`() {
    val noCombatModDir =
      tempWorld(
        mapOf(
          "MODULE.gups" to
            TestWorlds.module(
              "features = [\"skills\", \"leveling\", \"standing\"], memory_pulses = 100"
            )
        )
      )
    val (_, modErr) = WorldModule.read(WorldDir(noCombatModDir))
    assertNotNull(modErr)
    assertContains(modErr.message, "module(memory_pulses) needs the combat feature")

    val noCombatMobDir =
      tempWorld(
        mapOf(
          "MODULE.gups" to TestWorlds.module("features = [\"skills\", \"leveling\", \"standing\"]"),
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            room("start")
            mob("orc", memory_pulses = 50)
            """
              .trimIndent(),
        )
      )
    val noCombatMobRes =
      ScriptLoader(WorldDir(noCombatMobDir), ScriptLimits.HARNESS).load(listOf("zones/z"))
    assertFalse(noCombatMobRes.ok)
    assertContains(noCombatMobRes.report(), "mob(memory_pulses) needs the combat feature")
  }

  @Test
  fun `valid module and mob memory_pulses populate world and prototypes`() {
    val f =
      ScriptFixture(
        mapOf(
          "MODULE.gups" to TestWorlds.module("memory_pulses = 1200"),
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            mob("orc_default", name = "an orc")
            mob("orc_custom", name = "a custom orc", memory_pulses = 60)
            mob("orc_zero", name = "a forgetful orc", memory_pulses = 0)
            room("start", name = "Start", desc = "Start.", flags = ["dead_end_ok"])
            """
              .trimIndent(),
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rules(),
        )
      )
    assertTrue(f.result.ok)
    assertEquals(1200L, f.loader.module.memoryPulses)
    assertEquals(1200L, f.w.memoryPulses)
    val pDef = f.w.protos.mob(Label("//zones/z:orc_default"))
    val pCus = f.w.protos.mob(Label("//zones/z:orc_custom"))
    val pZero = f.w.protos.mob(Label("//zones/z:orc_zero"))
    assertNull(pDef.memoryPulses)
    assertEquals(60L, pCus.memoryPulses)
    assertEquals(0L, pZero.memoryPulses)
    assertNotEquals(pDef.copy(label = pCus.label, name = pCus.name), pCus)
  }
}
