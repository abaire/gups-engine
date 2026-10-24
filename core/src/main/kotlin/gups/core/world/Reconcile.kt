package gups.core.world

import gups.core.model.Actor
import gups.core.model.Exit
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Mob
import gups.core.model.MobProto
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.Room
import gups.core.model.RoomProto

/** What a prototype swap did to live instances, for the reload report. */
data class ReconcileReport(val created: List<Room>, val notes: List<String>)

/**
 * Swaps in [next] after a reload (SPEC §5.10 steps 3–4) and reconciles live instances
 * (SPEC §3.4) against it.
 *
 * - **Rooms** keep their identity and contents. Their static data comes from the new
 *   prototype. Door states and exit blocks the old prototype set are taken from the new one;
 *   ones changed in play are kept.
 * - **Mobs** keep their state, except for a field still equal to its old prototype value,
 *   which takes the new value. That is the instance delta of §3.4, computed on the spot
 *   (D-080).
 * - **Deleted prototypes:** mobs and items on the ground are despawned. Items in a player's
 *   custody become inert tombstones. Players in a deleted room move to their recall room, or
 *   else the first room.
 */
fun World.applyPrototypes(next: Prototypes): ReconcileReport {
    val old = protos
    protos = next
    val notes = mutableListOf<String>()
    val created = mutableListOf<Room>()

    for ((label, p) in next.rooms) {
        val room = roomsByLabel[label]
        if (room != null) rebind(room, p, old.rooms[label]) else created += Room(allocateId(), p).also { register(it); roomsByLabel[label] = it }
    }
    val fallback = roomsByLabel.values.first()
    for (gone in roomsByLabel.values.filter { it.label !in next.rooms }) {
        for (a in gone.actors) {
            when (a) {
                is Player -> {
                    val to = roomOrNull(a.recallRoom)?.takeIf { it.label in next.rooms } ?: fallback
                    if (a.recallRoom !in next.rooms) a.recallRoom = fallback.label
                    disengageAll(a)
                    placeActor(a, to, 0)
                    send(a, "The world shifts around you.")
                    send(a, describeRoom(a))
                }
                is Mob -> despawn(a)
            }
        }
        for (i in gone.items) destroy(i)
        roomsByLabel.remove(gone.label)
        entities.remove(gone.id)
        notes += "room ${gone.label} removed"
    }
    for (room in roomsByLabel.values) {
        for (a in room.actors) if (a.trackIndex >= room.trackSize) placeActor(a, room, room.trackSize - 1)
        for (i in room.items) i.groundIndex?.let { if (it >= room.trackSize) moveItem(i, ItemLocation.InRoom(room.id, room.trackSize - 1)) }
    }

    for (m in actorIndex.values.filterIsInstance<Mob>()) {
        val p = next.mobs[m.protoLabel]
        if (p == null) {
            notes += "mob ${m.protoLabel} ($m) despawned: its prototype is gone"
            despawn(m)
        } else {
            rebind(m, p, old.mobs[m.protoLabel] ?: m.proto)
        }
    }
    for (a in actorIndex.values) a.skills.keys.retainAll { it in next.skills }

    for (item in entities.values.filterIsInstance<Item>().sortedBy { it.id }) {
        if (item.id !in entities) continue
        val p = next.items[item.protoLabel]
        when {
            p != null -> {
                item.proto = p
                item.protoVersion = p.version
            }
            item.proto.kind == TOMBSTONE -> Unit
            custodian(item) is Player -> {
                item.proto = tombstone(item.proto)
                notes += "item ${item.protoLabel} ($item) became a tombstone"
            }
            else -> {
                notes += "item ${item.protoLabel} ($item) despawned: its prototype is gone"
                destroy(item)
            }
        }
        item.dirty = true
    }

    rebuildSpawns()
    for (room in created) for (s in spawnStates) if (s.room === room) fillSpawn(s)

    zoneResets.keys.retainAll(next.zones.keys)
    for (zone in next.zones.values) {
        val every = zone.resetPulses
        if (every == null) zoneResets.remove(zone.pkg) else zoneResets.putIfAbsent(zone.pkg, pulse + every)
    }
    return ReconcileReport(created, notes)
}

/** Kind of the inert items left when a carried item's prototype is deleted (SPEC §3.4). */
const val TOMBSTONE = "tombstone"

private fun tombstone(was: ItemProto) = ItemProto(
    label = was.label,
    name = was.name,
    keywords = was.keywords,
    desc = "A lifeless husk of something that no longer exists. You can drop it.",
    kind = TOMBSTONE,
    weight = was.weight,
    stackable = was.stackable,
    container = was.container,
)

/** The actor whose custody [item] ends in, if any. */
private fun World.custodian(item: Item): Actor? = when (val loc = item.location) {
    is ItemLocation.Carried -> actor(loc.actor)
    is ItemLocation.Equipped -> actor(loc.actor)
    is ItemLocation.Contained -> item(loc.container)?.let { custodian(it) }
    is ItemLocation.InRoom -> null
}

private fun World.despawn(m: Mob) {
    disengageAll(m)
    m.casting = null
    m.actionQueue.clear()
    removeActor(m)
}

private fun World.rebind(room: Room, p: RoomProto, was: RoomProto?) {
    val oldExits = room.exits
    room.proto = p
    room.protoVersion = p.version
    room.exits = p.exits.associateTo(LinkedHashMap()) { ep ->
        val exit = Exit(ep)
        val live = oldExits[ep.direction]
        val wasProto = was?.exits?.firstOrNull { it.direction == ep.direction }
        if (live != null && wasProto != null) {
            if (live.blocks != wasProto.blocks) exit.blocks = live.blocks
            val door = exit.door
            val liveDoor = live.door
            if (door != null && liveDoor != null && liveDoor.state != wasProto.door?.state) door.state = liveDoor.state
        }
        ep.direction to exit
    }
    room.dirty = true
}

/** A field the instance hasn't changed from its old prototype follows the new one. */
private fun <T> follow(live: T, was: T, now: T): T = if (live == was) now else live

private fun World.rebind(m: Mob, p: MobProto, was: MobProto) {
    m.proto = p
    m.protoVersion = p.version
    m.level = follow(m.level, was.level, p.level)
    val full = m.hp == m.maxHp
    m.maxHp = follow(m.maxHp, was.hp, p.hp)
    if (full || m.hp > m.maxHp) m.hp = m.maxHp
    m.maxMana = follow(m.maxMana, was.mana, p.mana)
    m.mana = m.mana.coerceAtMost(m.maxMana)
    m.maxMove = follow(m.maxMove, was.move, p.move)
    m.move = m.move.coerceAtMost(m.maxMove)
    m.armor = follow(m.armor, was.armor, p.armor)
    for (stat in p.stats.keys + was.stats.keys) {
        m.baseStats[stat] = follow(m.baseStats[stat], was.stats[stat], p.stats[stat]) ?: 0
    }
    if (m.resists == was.resists) {
        m.resists.clear()
        m.resists.putAll(p.resists)
    }
    if (m.skills == was.skills) {
        m.skills.clear()
        m.skills.putAll(p.skills)
    }
    m.thinkEvery = follow(m.thinkEvery, was.thinkEvery, p.thinkEvery)
    m.dormantRecovery = follow(m.dormantRecovery, was.dormantRecovery, p.dormantRecovery)
    m.dirty = true
}

/** New spawn states from the rooms' current rules; live mobs carry over by label, in order. */
private fun World.rebuildSpawns() {
    val old = spawnStates.toList()
    spawnStates.clear()
    val claimed = HashSet<SpawnState>()
    for (room in roomsByLabel.values) {
        for (rule in room.proto.spawns) {
            val s = SpawnState(room, rule)
            val prev = old.firstOrNull { it.room === room && it.rule.label == rule.label && it !in claimed }
            if (prev != null) {
                claimed += prev
                for (id in prev.live) (actor(id) as? Mob)?.let { m -> s.live += id; m.spawnedBy = s }
                s.respawnAt = prev.respawnAt ?: if (s.live.size < rule.count) pulse + rule.respawnPulses else null
            }
            spawnStates += s
        }
    }
    for (s in old) if (s !in claimed) for (id in s.live) (actor(id) as? Mob)?.spawnedBy = null
}
