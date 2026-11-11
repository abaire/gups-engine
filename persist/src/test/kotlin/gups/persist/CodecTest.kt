package gups.persist

import gups.core.EntityId
import gups.core.Label
import gups.core.fixtures.FixtureRules
import gups.core.fixtures.FixtureSchemas
import gups.core.fixtures.tempDb
import gups.core.model.Affect
import gups.core.model.AffectScope
import gups.core.model.DamageType
import gups.core.model.Effect
import gups.core.model.EquipAffect
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Prototypes
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import gups.core.rules.RulesContext
import gups.core.world.AffectSpec
import gups.core.world.applyAffect
import gups.core.world.applyItemAffect
import gups.core.world.changeVital
import gups.core.world.quit
import gups.core.world.spawnItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString

class CodecTest {
  @Test
  fun `affect with maxVitalDeltas and resistDeltas round trips through affectJson`() {
    val fire = DamageType("fire")
    val affect =
      Affect(
        id = 42L,
        name = "blessing",
        source = EntityId(10),
        appliedAt = 100L,
        expiresAt = 200L,
        statDeltas = mapOf("str" to 3),
        flags = setOf("blessed"),
        maxVitalDeltas = mapOf("hp" to 15),
        resistDeltas = mapOf(fire to 5),
        applies = AffectScope.CARRIED,
      )
    val json = affectJson(affect)
    val restored = affectFromJson(json)
    assertEquals(affect.id, restored.id)
    assertEquals(affect.name, restored.name)
    assertEquals(affect.source, restored.source)
    assertEquals(affect.appliedAt, restored.appliedAt)
    assertEquals(affect.expiresAt, restored.expiresAt)
    assertEquals(affect.statDeltas, restored.statDeltas)
    assertEquals(affect.flags, restored.flags)
    assertEquals(affect.maxVitalDeltas, restored.maxVitalDeltas)
    assertEquals(affect.resistDeltas, restored.resistDeltas)
    assertEquals(affect.applies, restored.applies)
  }

  @Test
  fun `an apply_affect effect with a 64-bit duration round trips through the codec`() {
    val effect = Effect.ApplyAffect(name = "forever", duration = Long.MAX_VALUE)
    val json = JSON.encodeToString(EffectJson.serializer(), effect.json())
    val back = JSON.decodeFromString(EffectJson.serializer(), json).toEffect()
    val a = back as Effect.ApplyAffect
    assertEquals("forever", a.name)
    assertEquals<Long?>(Long.MAX_VALUE, a.duration)
  }

  @Test
  fun `affect with power 137 survives save and load, and missing power decodes to 100`() {
    val affect =
      Affect(
        id = 10L,
        name = "might",
        source = null,
        appliedAt = 50L,
        expiresAt = 150L,
        power = 137,
      )
    val json = affectJson(affect)
    val restored = affectFromJson(json)
    assertEquals(137, restored.power)

    val decodedDefault =
      JSON.decodeFromString(
        AffectJson.serializer(),
        """{"id":11,"name":"might","appliedAt":50,"expiresAt":150}""",
      )
    assertEquals(100, decodedDefault.power)
    val restoredDefault = affectFromJson(decodedDefault)
    assertEquals(100, restoredDefault.power)
  }

  @Test
  fun `item instance affects with maxVitalDeltas and resistDeltas survive a restart`() {
    val file = tempDb()
    val fire = DamageType("fire")
    val a = PersistWorld(file, P.protos())
    val ann = a.login("Ann")
    val gem = a.w.spawnItem(P.GEM, ItemLocation.Carried(ann.id))
    a.w.applyItemAffect(
      gem,
      AffectSpec(
        "fire_ward",
        100,
        maxVitalDeltas = mapOf("hp" to 10),
        resistDeltas = mapOf(fire to 8),
      ),
      applies = AffectScope.CARRIED,
    )
    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, P.protos())
    val ann2 = b.login("Ann")
    val gem2 = ann2.inventory.single()
    val ward = gem2.affects.single()
    assertEquals("fire_ward", ward.name)
    assertEquals(mapOf("hp" to 10), ward.maxVitalDeltas)
    assertEquals(mapOf(fire to 8), ward.resistDeltas)
    b.close()
  }

  @Test
  fun `actor affects with maxVitalDeltas and resistDeltas survive a restart`() {
    val file = tempDb()
    val fire = DamageType("fire")
    val a = PersistWorld(file, P.protos())
    val ann = a.login("Ann")
    a.w.applyAffect(
      ann,
      AffectSpec("vigor", 100, maxVitalDeltas = mapOf("hp" to 20), resistDeltas = mapOf(fire to 4)),
    )
    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, P.protos())
    val ann2 = b.login("Ann")
    val vigor = ann2.affects.single { it.name == "vigor" }
    assertEquals(mapOf("hp" to 20), vigor.maxVitalDeltas)
    assertEquals(mapOf(fire to 4), vigor.resistDeltas)
    b.close()
  }

  @Test
  fun `a player saved at hp above base max while wearing a +hp item restores with that hp intact`() {
    val file = tempDb()
    val vest = Label("//t:vest")
    val vestProto =
      ItemProto(vest, "a vest", listOf("vest"), wear = "body", maxVitalDeltas = mapOf("hp" to 20))
    val base = P.protos()
    val protos =
      Prototypes(
        rooms = base.rooms.values.toList(),
        mobs = base.mobs.values.toList(),
        items = base.items.values.filter { it.label != Prototypes.CORPSE.label } + vestProto,
        zones = base.zones.values.toList(),
        regions = base.regions.values.toList(),
      )
    val a = PersistWorld(file, protos)
    val ann = a.login("Ann")
    val baseMax = ann.baseMax("hp")
    a.w.spawnItem(vest, ItemLocation.Equipped(ann.id, "body"))
    assertEquals(baseMax + 20, ann.max("hp"))
    a.w.changeVital(ann, "hp", 20)
    assertEquals(baseMax + 20, ann.current("hp"))

    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, protos)
    val ann2 = b.login("Ann")
    assertEquals(baseMax + 20, ann2.max("hp"))
    assertEquals(
      baseMax + 20,
      ann2.current("hp"),
      "the boosted hp survived restore without being clamped",
    )
    b.close()
  }

  private fun vestProtos(vest: ItemProto): Prototypes {
    val base = P.protos()
    return Prototypes(
      rooms = base.rooms.values.toList(),
      mobs = base.mobs.values.toList(),
      items = base.items.values.filter { it.label != Prototypes.CORPSE.label } + vest,
      zones = base.zones.values.toList(),
      regions = base.regions.values.toList(),
    )
  }

  @Test
  fun `restore keeps boosted hp and the timed equip affect when the +hp item has equip affects`() {
    val file = tempDb()
    val vest = Label("//t:warm_vest")
    val protos =
      vestProtos(
        ItemProto(
          vest,
          "a warm vest",
          listOf("vest"),
          wear = "body",
          maxVitalDeltas = mapOf("hp" to 20),
          equipAffects = listOf(EquipAffect("warm"), EquipAffect("hasted", pulses = 1000)),
        )
      )
    val a = PersistWorld(file, protos)
    val ann = a.login("Ann")
    val baseMax = ann.baseMax("hp")
    a.w.spawnItem(vest, ItemLocation.Equipped(ann.id, "body"))
    a.w.changeVital(ann, "hp", 20)
    assertEquals(baseMax + 20, ann.current("hp"))
    val hastedUntil = ann.affects.single { "hasted" in it.flags }.expiresAt
    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, protos)
    val ann2 = b.login("Ann")
    assertEquals(baseMax + 20, ann2.current("hp"))
    assertTrue(ann2.hasFlag("warm"))
    assertEquals(hastedUntil, ann2.affects.single { "hasted" in it.flags }.expiresAt)
    b.close()
  }

  @Test
  fun `an item that no longer fits is inactive after restore, without telling the player`() {
    val file = tempDb()
    val vest = Label("//t:warm_vest")
    val protos =
      vestProtos(
        ItemProto(
          vest,
          "a warm vest",
          listOf("vest"),
          wear = "body",
          maxVitalDeltas = mapOf("hp" to 20),
          equipAffects = listOf(EquipAffect("warm")),
        )
      )
    val a = PersistWorld(file, protos)
    val ann = a.login("Ann")
    val baseMax = ann.baseMax("hp")
    a.w.spawnItem(vest, ItemLocation.Equipped(ann.id, "body"))
    a.w.quit(ann)
    a.save()
    a.close()

    val bodiless =
      object : FixtureRules(FixtureSchemas.current) {
        override fun actorSlots(c: RulesContext): List<String> = equipmentSlots() - "body"
      }
    val b = PersistWorld(file, protos, rules = bodiless)
    val ann2 = b.login("Ann")
    b.run(2)
    assertEquals(setOf("body"), ann2.inactiveSlots)
    assertEquals(vest, ann2.equipped("body")?.protoLabel)
    assertEquals(baseMax, ann2.max("hp"))
    assertFalse(ann2.hasFlag("warm"))
    val out = b.sinks.getValue("Ann").drain()
    assertFalse(out.contains("no longer fits"), out)
    b.close()
  }

  @Test
  fun `restore asks the wear restriction again, quietly`() {
    val file = tempDb()
    val vest = Label("//t:warm_vest")
    val protos =
      vestProtos(
        ItemProto(
          vest,
          "a warm vest",
          listOf("vest"),
          wear = "body",
          equipAffects = listOf(EquipAffect("warm")),
        )
      )
    val a = PersistWorld(file, protos)
    val ann = a.login("Ann")
    a.w.spawnItem(vest, ItemLocation.Equipped(ann.id, "body"))
    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, protos)
    b.w.restrictions =
      mapOf(RestrictionPoint.WEAR to listOf(Restriction { _, _ -> "Vests are banned." }))
    val ann2 = b.login("Ann")
    b.run(2)
    assertEquals(setOf("body"), ann2.inactiveSlots)
    assertFalse(ann2.hasFlag("warm"))
    val out = b.sinks.getValue("Ann").drain()
    assertFalse(out.contains("Vests are banned."), out)
    b.close()
  }
}
