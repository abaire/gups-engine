package gups.pluginapi

import gups.core.model.Band
import gups.core.model.Player
import gups.core.rules.RulesContext
import gups.core.world.World
import gups.core.world.advanceLagOf
import gups.core.world.regenOf
import gups.core.world.retreatLagOf
import gups.core.world.toHitChain
import gups.pluginapi.host.traitAdapter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraitModifierTest {
  private val w: World = world()

  private fun player(vararg traits: PluginTrait): Player {
    w.traits = traits.associate { it.name to traitAdapter("heritage", it) }
    val p = w.spawnPlayer("Ann", gups.core.Label("//zones/z:a"))
    p.grantedTraits = traits.map { it.name }.toSet()
    p.recomputeTraits(w)
    return p
  }

  @Test
  fun `a trait modifier sees and returns Long, and the chain stays Int`() {
    var seen: Any? = null
    val p =
      player(
        PluginTrait(
          "keen",
          modifiers =
            mapOf(
              PluginModifierPoint.TO_HIT to
                PluginModifier { _, base ->
                  seen = base
                  (base as Long) + 5L
                }
            ),
        )
      )
    val r = w.toHitChain(RulesContext(w, p, p))
    val chained: Int = r.value
    val (base, after) = Regex("base=(-?\\d+) → trait:keen (-?\\d+)").find(r.text)!!.destructured
    assertTrue(seen is Long)
    assertEquals(base.toInt() + 5, after.toInt())
    assertEquals(after.toInt(), chained)
  }

  @Test
  fun `a regen trait modifier gets and returns a vital map of Long`() {
    val p =
      player(
        PluginTrait(
          "devout",
          modifiers =
            mapOf(
              PluginModifierPoint.REGEN to
                PluginModifier { _, base ->
                  @Suppress("UNCHECKED_CAST") val m = (base as Map<String, Long>).toMutableMap()
                  m["mana"] = (m["mana"] ?: 0L) + 1L
                  m
                }
            ),
        )
      )
    val lines = mutableListOf<String>()
    w.onRules = { _, line -> lines += line }
    val regen: Map<String, Int> = w.regenOf(RulesContext(w, p))
    val mana = regen.getValue("mana")
    assertContains(lines.single(), Regex("mana ${mana - 1}\\b.*→ trait:devout .*mana $mana\\b"))
  }

  @Test
  fun `a wrong-typed or throwing trait modifier leaves the value and records a diagnostic`() {
    val p =
      player(
        PluginTrait(
          "odd",
          modifiers = mapOf(PluginModifierPoint.TO_HIT to PluginModifier { _, _ -> "fast" }),
        ),
        PluginTrait(
          "broken",
          modifiers = mapOf(PluginModifierPoint.REGEN to PluginModifier { _, _ -> error("boom") }),
        ),
      )
    val r = w.toHitChain(RulesContext(w, p, p))
    assertContains(r.text, Regex("base=${r.value} → trait:odd ${r.value}$"))
    val lines = mutableListOf<String>()
    w.onRules = { _, line -> lines += line }
    val regen = w.regenOf(RulesContext(w, p))
    assertContains(lines.single(), Regex("base=(\\{.*\\}) → trait:broken \\1$"))
    assertTrue(regen.isNotEmpty())
    val plugin = w.diagnostics.filter { it.kind == "plugin" }.map { it.message }
    assertTrue(plugin.any { "trait 'odd'" in it }, "$plugin")
    assertTrue(plugin.any { "trait 'broken'" in it }, "$plugin")
  }

  @Test
  fun `an advance_lag trait modifier sees and returns Long, and wrong return is refused`() {
    val p =
      player(
        PluginTrait(
          "quick",
          modifiers =
            mapOf(
              PluginModifierPoint.ADVANCE_LAG to PluginModifier { _, base -> (base as Long) + 3L }
            ),
        ),
        PluginTrait(
          "bad_lag",
          modifiers =
            mapOf(PluginModifierPoint.RETREAT_LAG to PluginModifier { _, _ -> "not_a_long" }),
        ),
      )
    val other = w.spawnMob(gups.core.Label("//zones/z:wolf"), p.room)
    val ctx = RulesContext(w, p, other, band = Band.NEAR)
    val baseAdvance = w.rules.advanceLag(ctx)
    val lag = w.advanceLagOf(ctx)
    assertEquals(baseAdvance + 3, lag)

    val baseRetreat = w.rules.retreatLag(ctx)
    val retreatLag = w.retreatLagOf(ctx)
    assertEquals(baseRetreat, retreatLag)
    val plugin = w.diagnostics.filter { it.kind == "plugin" }.map { it.message }
    assertTrue(
      plugin.any { "trait 'bad_lag'" in it && "returned String, expected Long" in it },
      "$plugin",
    )
  }

  @Test
  fun `a negative lag from a trait modifier is clamped to 0 by the chain`() {
    val p =
      player(
        PluginTrait(
          "featherfoot",
          modifiers =
            mapOf(
              PluginModifierPoint.ADVANCE_LAG to PluginModifier { _, base -> (base as Long) - 100L }
            ),
        )
      )
    val other = w.spawnMob(gups.core.Label("//zones/z:wolf"), p.room)
    val lines = mutableListOf<String>()
    w.onRules = { _, line -> lines += line }
    assertEquals(0, w.advanceLagOf(RulesContext(w, p, other, band = Band.NEAR)))
    assertTrue(lines.single().endsWith("→ clamp 0"), "$lines")
  }
}
