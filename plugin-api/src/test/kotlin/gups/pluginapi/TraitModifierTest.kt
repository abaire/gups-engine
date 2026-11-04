package gups.pluginapi

import gups.core.model.Band
import gups.core.model.DamageType
import gups.core.model.Player
import gups.core.rules.RulesContext
import gups.core.world.World
import gups.core.world.advanceLagOf
import gups.core.world.regenOf
import gups.core.world.resolveDamage
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

  @Test
  fun `a defend_hit trait modifier sees the defender as actor and the attacker as other`() {
    var seenActor: String? = null
    var seenOther: String? = null
    w.traits =
      mapOf(
        "parry" to
          traitAdapter(
            "heritage",
            PluginTrait(
              "parry",
              modifiers =
                mapOf(
                  PluginModifierPoint.DEFEND_HIT to
                    PluginModifier { call, base ->
                      seenActor = call.actor.name
                      seenOther = call.other?.name
                      (base as Long) - 10L
                    }
                ),
            ),
          )
      )
    val p = w.spawnPlayer("Ann", gups.core.Label("//zones/z:a"))
    val wolf = w.spawnMob(gups.core.Label("//zones/z:wolf"), p.room)
    wolf.grantedTraits = setOf("parry")
    wolf.recomputeTraits(w)
    val base = w.rules.toHit(RulesContext(w, p, wolf))
    val r = w.toHitChain(RulesContext(w, p, wolf))
    assertEquals(wolf.name, seenActor, "the defender's trait sees the defender as actor")
    assertEquals(p.name, seenOther, "and the attacker as other")
    assertEquals(base - 10, r.value, "the modifier's result enters the chain")
  }

  @Test
  fun `a mitigate trait modifier gets and returns a vital map of Long`() {
    w.traits =
      mapOf(
        "siphon" to
          traitAdapter(
            "heritage",
            PluginTrait(
              "siphon",
              modifiers =
                mapOf(
                  PluginModifierPoint.MITIGATE to
                    PluginModifier { _, base ->
                      @Suppress("UNCHECKED_CAST") val m = (base as Map<String, Long>).toMutableMap()
                      m["hp"] = (m["hp"] ?: 0L) - 1L
                      m["mana"] = -1L
                      m
                    }
                ),
            ),
          )
      )
    val p = w.spawnPlayer("Ann", gups.core.Label("//zones/z:a"))
    val wolf = w.spawnMob(gups.core.Label("//zones/z:wolf"), p.room)
    wolf.grantedTraits = setOf("siphon")
    wolf.recomputeTraits(w)
    val hit = w.resolveDamage(RulesContext(w, p, wolf), 5, DamageType("slash"))
    assertEquals(mapOf("hp" to 4, "mana" to -1), hit.losses)
  }

  @Test
  fun `a mitigate trait modifier returning a bad map leaves the value and records a diagnostic`() {
    w.traits =
      mapOf(
        "bad_siphon" to
          traitAdapter(
            "heritage",
            PluginTrait(
              "bad_siphon",
              modifiers =
                mapOf(
                  PluginModifierPoint.MITIGATE to
                    PluginModifier { _, base ->
                      @Suppress("UNCHECKED_CAST") (base as Map<String, Long>).plus("zz" to 1L)
                    }
                ),
            ),
          )
      )
    val p = w.spawnPlayer("Ann", gups.core.Label("//zones/z:a"))
    val wolf = w.spawnMob(gups.core.Label("//zones/z:wolf"), p.room)
    wolf.grantedTraits = setOf("bad_siphon")
    wolf.recomputeTraits(w)
    val hit = w.resolveDamage(RulesContext(w, p, wolf), 5, DamageType("slash"))
    assertEquals(mapOf("hp" to 5), hit.losses, "the bad map is refused, the base survives")
    val plugin = w.diagnostics.filter { it.kind == "plugin" }.map { it.message }
    assertTrue(plugin.any { "trait 'bad_siphon'" in it }, "$plugin")
  }

  @Test
  fun `a to_hit trait modifier still sees the attacker as actor`() {
    var seenActor: String? = null
    var seenOther: String? = null
    val p =
      player(
        PluginTrait(
          "keen",
          modifiers =
            mapOf(
              PluginModifierPoint.TO_HIT to
                PluginModifier { call, base ->
                  seenActor = call.actor.name
                  seenOther = call.other?.name
                  base
                }
            ),
        )
      )
    val wolf = w.spawnMob(gups.core.Label("//zones/z:wolf"), p.room)
    w.toHitChain(RulesContext(w, p, wolf))
    assertEquals(p.name, seenActor, "an attacker point still runs on the attacker")
    assertEquals(wolf.name, seenOther)
  }
}
