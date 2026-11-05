package gups.pluginapi

import gups.core.CapturingSink
import gups.core.Label
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Band
import gups.core.model.DamageType
import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.model.SkillProto
import gups.core.model.TargetKind
import gups.core.rules.RulesContext
import gups.core.world.World
import gups.core.world.advanceLagOf
import gups.core.world.attach
import gups.core.world.handle
import gups.core.world.itemPowerChain
import gups.core.world.regenOf
import gups.core.world.resolveDamage
import gups.core.world.retreatLagOf
import gups.core.world.spawnItem
import gups.core.world.submit
import gups.core.world.toHitChain
import gups.pluginapi.host.traitAdapter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraitModifierTest {
  private val w: World = world()

  private fun player(vararg traits: PluginTrait): Player {
    w.traits = traits.associate { it.name to traitAdapter("heritage", it) }
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
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
    val other = w.spawnMob(Label("//zones/z:wolf"), p.room)
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
    val other = w.spawnMob(Label("//zones/z:wolf"), p.room)
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
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
    val wolf = w.spawnMob(Label("//zones/z:wolf"), p.room)
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
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
    val wolf = w.spawnMob(Label("//zones/z:wolf"), p.room)
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
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
    val wolf = w.spawnMob(Label("//zones/z:wolf"), p.room)
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
    val wolf = w.spawnMob(Label("//zones/z:wolf"), p.room)
    w.toHitChain(RulesContext(w, p, wolf))
    assertEquals(p.name, seenActor, "an attacker point still runs on the attacker")
    assertEquals(wolf.name, seenOther)
  }

  private val zap = SkillProto(Label("//zones/z:zap"), kind = "spell", target = TargetKind.ENEMY)

  @Test
  fun `a to_hit trait modifier sees the spell, and no skill for a swing`() {
    val seen = mutableListOf<PluginSkill?>()
    val p =
      player(
        PluginTrait(
          "focus",
          modifiers =
            mapOf(
              PluginModifierPoint.TO_HIT to
                PluginModifier { call, base ->
                  seen += call.skill
                  (base as Long) + 10L
                }
            ),
        )
      )
    val wolf = w.spawnMob(Label("//zones/z:wolf"), p.room)
    val r = w.toHitChain(RulesContext(w, p, wolf, skill = zap))
    w.toHitChain(RulesContext(w, p, wolf, slot = "wield"))
    assertEquals(2, seen.size)
    assertEquals("zap", assertNotNull(seen[0]).name)
    assertEquals("spell", seen[0]?.kind)
    assertNull(seen[1], "a swing has no skill")
    assertContains(r.text, "trait:focus")
  }

  @Test
  fun `an item_power trait modifier sees the item and event`() {
    var seenItem: PluginItem? = null
    var seenEvent: String? = null
    val p =
      player(
        PluginTrait(
          "artificer",
          modifiers =
            mapOf(
              PluginModifierPoint.ITEM_POWER to
                PluginModifier { call, base ->
                  seenItem = call.item
                  seenEvent = call.event
                  (base as Long) + 20L
                }
            ),
        )
      )
    val wand = w.spawnItem(Label("//zones/z:stick"), ItemLocation.Carried(p.id))
    val r = w.itemPowerChain(RulesContext(w, p, item = wand, event = "use"))
    assertEquals(120, r)
    assertEquals("a stick", seenItem?.name)
    assertEquals("use", seenEvent)
  }

  @Test
  fun `a defend_hit trait modifier that refuses spells makes its bearer resist a cast`() {
    val files =
      ZONE +
        ("zones/z/skills.star" to
          """
            skill("charm", kind = "spell", target = "enemy", effect = damage(dice = "1d4"))
          """)
    val w = world(files)
    val seen = mutableListOf<PluginSkill?>()
    w.traits =
      mapOf(
        "fey" to
          traitAdapter(
            "heritage",
            PluginTrait(
              "fey",
              modifiers =
                mapOf(
                  PluginModifierPoint.DEFEND_HIT to
                    PluginModifier { call, base ->
                      seen += call.skill
                      if (call.skill?.kind == "spell") 0L else base
                    }
                ),
            ),
          )
      )
    val ann = w.spawnPlayer("Ann", Label("//zones/z:a"))
    ann.skills[Label("//zones/z:charm")] = 100
    val wolf = w.spawnMob(Label("//zones/z:wolf"), ann.room)
    wolf.grantedTraits = setOf("fey")
    wolf.recomputeTraits(w)
    val hp = wolf.current("hp")
    val sink = CapturingSink()
    w.handle(WorldControlEvent.Connect(SessionId(1), sink))
    w.attach(SessionId(1), ann)
    val trace = mutableListOf<String>()
    w.onRules = { _, line -> trace += line }

    w.submit(ann, "cast charm wolf")
    w.runPulse()
    assertEquals(hp, wolf.current("hp"), "the charmed wolf took no damage")
    assertContains(sink.drain(), "A wolf resists your charm.")
    assertTrue(trace.any { "trait:fey 0" in it && it.endsWith("RESIST") }, "$trace")
    assertEquals("charm", assertNotNull(seen.single()).name)
  }
}
