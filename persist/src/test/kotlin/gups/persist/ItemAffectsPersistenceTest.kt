package gups.persist

import gups.core.model.AffectScope
import gups.core.model.ItemLocation
import gups.core.model.Prototypes
import gups.core.world.TOMBSTONE
import gups.core.world.addItemTag
import gups.core.world.applyItemAffect
import gups.core.world.quit
import gups.core.world.removeItemTag
import gups.core.world.spawnItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ItemAffectsPersistenceTest {
  @Test
  fun `a timed and a permanent carried affect survive a restart`() {
    val file = tempDb()
    val a = PersistWorld(file, P.protos())
    val ann = a.login("Ann")
    val gem = a.w.spawnItem(P.GEM, ItemLocation.Carried(ann.id))
    val baseStr = ann.stat("str")
    val baseDex = ann.stat("dex")
    a.w.applyItemAffect(
      gem,
      "glow",
      100,
      applies = AffectScope.CARRIED,
      statDeltas = mapOf("str" to 5),
    )
    a.w.applyItemAffect(
      gem,
      "ward",
      null,
      applies = AffectScope.CARRIED,
      statDeltas = mapOf("dex" to 3),
    )
    // The carried affects count for the holder while the gem is carried.
    assertEquals(baseStr + 5, ann.stat("str"))
    assertEquals(baseDex + 3, ann.stat("dex"))
    val glow = gem.affects.single { it.name == "glow" }
    val ward = gem.affects.single { it.name == "ward" }
    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, P.protos())
    val ann2 = b.login("Ann")
    val gem2 = ann2.inventory.single()
    val glow2 = gem2.affects.single { it.name == "glow" }
    val ward2 = gem2.affects.single { it.name == "ward" }
    assertEquals(glow.id, glow2.id, "the affect keeps its id")
    assertEquals(glow.expiresAt, glow2.expiresAt, "the timed affect keeps its deadline")
    assertEquals(AffectScope.CARRIED, glow2.applies)
    assertEquals(mapOf("str" to 5), glow2.statDeltas)
    assertEquals(Long.MAX_VALUE, ward2.expiresAt, "a permanent affect round-trips as MAX_VALUE")
    assertEquals(AffectScope.CARRIED, ward2.applies)
    assertEquals(mapOf("dex" to 3), ward2.statDeltas)
    // The restored player's stat includes the carried affects without running a pulse.
    assertEquals(baseStr + 5, ann2.stat("str"))
    assertEquals(baseDex + 3, ann2.stat("dex"))
    b.close()
  }

  @Test
  fun `added and removed tags survive a restart`() {
    // P's gem has no prototype tags, so rebuild the prototypes giving it one to remove.
    val base = P.protos()
    val items =
      base.items.values
        .filter { it.label != Prototypes.CORPSE.label }
        .map { if (it.label == P.GEM) it.copy(tags = setOf("jewel")) else it }
        .toList()
    val protos =
      Prototypes(
        base.rooms.values.toList(),
        base.mobs.values.toList(),
        items,
        base.skills.values.toList(),
        base.zones.values.toList(),
      )
    val file = tempDb()
    val a = PersistWorld(file, protos)
    val ann = a.login("Ann")
    val gem = a.w.spawnItem(P.GEM, ItemLocation.Carried(ann.id))
    assertTrue(a.w.addItemTag(gem, "cursed"))
    assertTrue(a.w.removeItemTag(gem, "jewel"))
    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, protos)
    val ann2 = b.login("Ann")
    val gem2 = ann2.inventory.single()
    assertEquals(setOf("cursed"), gem2.addedTags)
    assertEquals(setOf("jewel"), gem2.removedTags)
    assertEquals(setOf("cursed"), gem2.tags, "effective tags")
    b.close()
  }

  @Test
  fun `a tombstone keeps its instance affects and tag changes across restarts`() {
    val file = tempDb()
    val a = PersistWorld(file, P.protos())
    val ann = a.login("Ann")
    val gem = a.w.spawnItem(P.GEM, ItemLocation.Carried(ann.id))
    val baseStr = ann.stat("str")
    a.w.applyItemAffect(
      gem,
      "glow",
      null,
      applies = AffectScope.CARRIED,
      statDeltas = mapOf("str" to 5),
    )
    assertTrue(a.w.addItemTag(gem, "cursed"))
    a.w.quit(ann)
    a.save()
    a.close()

    // The gem's prototype is gone, so it comes back as a tombstone.
    val b = PersistWorld(file, P.protos(withGem = false))
    val ann2 = b.login("Ann")
    val stone = ann2.inventory.single()
    assertEquals(TOMBSTONE, stone.kind)
    assertEquals(listOf("glow"), stone.affects.map { it.name })
    assertEquals(setOf("cursed"), stone.addedTags)
    assertEquals(baseStr, ann2.stat("str"), "a tombstone contributes nothing")
    b.w.quit(ann2)
    b.save()
    b.close()

    // Saved again as a tombstone, it still keeps them.
    val c = PersistWorld(file, P.protos(withGem = false))
    val stone2 = c.login("Ann").inventory.single()
    assertEquals(TOMBSTONE, stone2.kind)
    assertEquals(listOf("glow"), stone2.affects.map { it.name })
    assertEquals(setOf("cursed"), stone2.addedTags)
    c.close()
  }

  @Test
  fun `an item payload without the new fields decodes with empty affects and tags`() {
    val p = JSON.decodeFromString(ItemPayload.serializer(), """{"name":"a gem","count":1}""")
    assertTrue(p.affects.isEmpty())
    assertTrue(p.addedTags.isEmpty())
    assertTrue(p.removedTags.isEmpty())
  }
}
