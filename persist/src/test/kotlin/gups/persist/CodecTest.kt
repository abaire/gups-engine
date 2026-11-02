package gups.persist

import gups.core.EntityId
import gups.core.Label
import gups.core.model.Affect
import gups.core.model.AffectScope
import gups.core.model.DamageType
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Prototypes
import gups.core.world.applyAffect
import gups.core.world.applyItemAffect
import gups.core.world.changeVital
import gups.core.world.quit
import gups.core.world.spawnItem
import kotlin.test.Test
import kotlin.test.assertEquals

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
  fun `item instance affects with maxVitalDeltas and resistDeltas survive a restart`() {
    val file = tempDb()
    val fire = DamageType("fire")
    val a = PersistWorld(file, P.protos())
    val ann = a.login("Ann")
    val gem = a.w.spawnItem(P.GEM, ItemLocation.Carried(ann.id))
    a.w.applyItemAffect(
      gem,
      "fire_ward",
      100,
      applies = AffectScope.CARRIED,
      maxVitalDeltas = mapOf("hp" to 10),
      resistDeltas = mapOf(fire to 8),
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
      "vigor",
      100,
      maxVitalDeltas = mapOf("hp" to 20),
      resistDeltas = mapOf(fire to 4),
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
}
