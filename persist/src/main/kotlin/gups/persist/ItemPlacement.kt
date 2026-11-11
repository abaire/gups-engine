package gups.persist

import gups.core.EntityId
import gups.core.Label
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.world.World
import gups.core.world.custodian
import gups.core.world.restoreItem
import gups.core.world.settleRestoredHoldings
import gups.core.world.tombstone
import gups.core.world.upgrade

/**
 * Restores item rows whose holders exist, containers before contents. Rows that can't be placed, or
 * whose prototype is gone (unless [tombstones]), are deleted.
 */
internal fun placeItems(
  world: World,
  rows: List<EntityRow>,
  tombstones: Boolean,
  deleted: MutableSet<Long>,
  notes: MutableList<String>,
): List<Item> = ItemPlacer(world, tombstones, deleted, notes).place(rows)

private val ARTICLES = setOf("a", "an", "the")

private enum class Outcome {
  PLACED,
  DROPPED,
  WAITING,
}

private class ItemPlacer(
  private val world: World,
  private val tombstones: Boolean,
  private val deleted: MutableSet<Long>,
  private val notes: MutableList<String>,
) {
  private val placed = mutableListOf<Item>()

  fun place(rows: List<EntityRow>): List<Item> {
    val waiting = rows.sortedBy { it.id }.toMutableList()
    val ids = rows.mapTo(HashSet()) { it.id }
    var progress = true
    while (progress && waiting.isNotEmpty()) {
      progress = false
      val it = waiting.iterator()
      while (it.hasNext()) {
        if (tryPlace(it.next(), waiting, ids) == Outcome.WAITING) continue
        it.remove()
        progress = true
      }
    }
    for (row in waiting) drop(row, "its container is gone", "dropped")
    for (holder in placed.mapNotNull { world.custodian(it) }.distinct()) {
      world.settleRestoredHoldings(holder)
    }
    return placed
  }

  private fun tryPlace(row: EntityRow, waiting: List<EntityRow>, ids: Set<Long>): Outcome {
    val loc = JSON.decodeFromString(LocationJson.serializer(), row.location ?: "{}")
    val at = locate(loc)
    if (at == null) {
      if (awaitsContainer(loc, waiting, ids)) return Outcome.WAITING
      return drop(row, "where it was is gone", "dropped")
    }
    val payload = JSON.decodeFromString(ItemPayload.serializer(), row.payload)
    val known = world.protos.items[Label(row.protoLabel)]
    val proto = protoFor(row, payload, known)
    if (proto == null) return drop(row, "its prototype is gone", "despawned")
    restore(row, payload, proto, known, at)
    return Outcome.PLACED
  }

  private fun drop(row: EntityRow, why: String, verb: String): Outcome {
    deleted += row.id
    notes += "item ${row.protoLabel} #${row.id} $verb: $why"
    return Outcome.DROPPED
  }

  private fun locate(loc: LocationJson): ItemLocation? =
    when {
      loc.room != null ->
        world.roomOrNull(Label(loc.room))?.let { r ->
          ItemLocation.InRoom(r.id, (loc.index ?: 0).coerceIn(0, r.trackSize - 1))
        }
      loc.carried != null ->
        world.actor(EntityId(loc.carried))?.let { a -> ItemLocation.Carried(a.id) }
      loc.equipped != null ->
        world.actor(EntityId(loc.equipped))?.let { a ->
          ItemLocation.Equipped(a.id, loc.slot ?: "held")
        }
      loc.contained != null ->
        world.item(EntityId(loc.contained))?.let { c -> ItemLocation.Contained(c.id) }
      else -> null
    }

  /** Whether the row's container is still to be placed, so the row should be retried. */
  private fun awaitsContainer(loc: LocationJson, waiting: List<EntityRow>, ids: Set<Long>) =
    loc.contained != null && loc.contained in ids && waiting.any { w -> w.id == loc.contained }

  /** The prototype to restore with: the live one, a tombstone stand-in, or null if neither fits. */
  private fun protoFor(row: EntityRow, payload: ItemPayload, known: ItemProto?): ItemProto? =
    when {
      known != null && payload.tombstone == null -> known
      tombstones -> tombstone(standIn(row, payload))
      else -> null
    }

  private fun standIn(row: EntityRow, payload: ItemPayload): ItemProto =
    ItemProto(Label(row.protoLabel), payload.name, emptyList())
      .copy(
        keywords =
          payload.name.lowercase().split(' ').filter { w -> w.isNotEmpty() && w !in ARTICLES },
        weight = payload.weight,
        stackable = payload.stackable,
        container = payload.container?.let(::containerFromJson),
        plural = payload.plural,
      )

  private fun restore(
    row: EntityRow,
    payload: ItemPayload,
    proto: ItemProto,
    known: ItemProto?,
    at: ItemLocation,
  ) {
    val item = world.restoreItem(EntityId(row.id), proto, at, payload.count)
    applyItem(item, payload)
    if (known == null || payload.tombstone != null) {
      item.savedPayload = payload.tombstone ?: row.payload
      if (payload.tombstone == null) notes += "item ${row.protoLabel} #${row.id} is now a tombstone"
    } else {
      item.protoVersion = row.protoVersion
      world.upgrade(item, known.version)
    }
    item.dirty = known == null || item.protoVersion != row.protoVersion
    placed += item
  }
}
