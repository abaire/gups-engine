package gups.core.world

import gups.core.ACTION_QUEUE_MAX
import gups.core.model.Actor
import gups.core.model.Channel
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.QueuedAction
import gups.core.model.Room
import gups.core.model.SkillProto

// Engine-validated verbs used by the script host's `ctx` (SPEC §4.5) that have no player
// command of their own. Each is individually atomic and invariant-preserving (SPEC §6.2).

/** The `say` verb: the room hears it and `on_say` fires. */
fun World.say(speaker: Actor, text: String) {
    send(speaker, "You say, '$text'")
    echo(speaker.room, "${speaker.name.cap()} says, '$text'", speaker)
    dispatch(HookName.ON_SAY, null, speaker.room, HookContext(actor = speaker, text = text))
    perceive("say", Channel.SOUND, speaker.room, speaker, text)
}

/** The `emote` verb: everyone in the room, the actor included, sees "Name text". */
fun World.emote(actor: Actor, text: String) {
    echo(actor.room, "${actor.name.cap()} $text")
    perceive("emote", Channel.SOUND, actor.room, actor, text)
}

/**
 * Sets the door on [room]'s exit [dir], and on the matching door on the other side (D-031).
 * Silent: callers do their own messaging.
 */
fun World.setDoor(room: Room, dir: Direction, state: DoorState) {
    val exit = requireNotNull(room.exits[dir]) { "${room.label} has no exit $dir" }
    val door = requireNotNull(exit.door) { "${room.label} exit $dir has no door" }
    door.state = state
    room.dirty = true
    reverseExit(room, exit)?.let { (other, back) ->
        back.door!!.state = state
        other.dirty = true
    }
    graphChanged()
}

/** The `set_exit_blocks` verb: replaces the exit's own `blocks`; the door's set still adds to it. */
fun World.setExitBlocks(room: Room, dir: Direction, blocks: Set<Channel>) {
    val exit = requireNotNull(room.exits[dir]) { "${room.label} has no exit $dir" }
    exit.blocks = blocks
    room.dirty = true
}

/** The `split` verb: moves [n] of a stack into a new item beside it (SPEC §3.8 inv. 8). */
fun World.splitItem(item: Item, n: Int): Item {
    require(item.proto.stackable) { "${item.name} is not stackable" }
    require(n in 1 until item.count) { "split: n must be between 1 and ${item.count - 1}" }
    val at = item.location
    require(at !is ItemLocation.Equipped) { "split: can't split an equipped item" }
    val part = spawnItem(item.protoLabel, at, n)
    part.customAttributes.putAll(item.customAttributes)
    part.decayAt = item.decayAt
    item.count -= n
    item.dirty = true
    return part
}

/**
 * Shortest walk from [from] to [to] over passable exits, as exit directions; empty if they
 * are the same room, null if [to] can't be reached. Breadth-first over the whole room graph in
 * exit declaration order, so ties resolve the same way every time (D-063). Results are cached
 * per `(from, to)` until a door, exit or reload changes the graph (SPEC §5.9).
 */
fun World.pathTo(from: Room, to: Room): List<Direction>? {
    if (from === to) return emptyList()
    val key = from.id to to.id
    if (key in pathCache) return pathCache[key]
    return searchPath(from, to).also { pathCache[key] = it }
}

private fun World.searchPath(from: Room, to: Room): List<Direction>? {
    val via = HashMap<Room, Pair<Room, Direction>>()
    val queue = ArrayDeque(listOf(from))
    while (queue.isNotEmpty()) {
        val room = queue.removeFirst()
        for (exit in room.exits.values) {
            if (!exit.passable) continue
            val next = room(exit.to)
            if (next === from || next in via) continue
            via[next] = room to exit.direction
            if (next === to) {
                val path = ArrayDeque<Direction>()
                var at = to
                while (at !== from) {
                    val (prev, dir) = via.getValue(at)
                    path.addFirst(dir)
                    at = prev
                }
                return path.toList()
            }
            queue.addLast(next)
        }
    }
    return null
}

// ---- Mob actions (SPEC §4.5, §5.8) -----------------------------------------------------------
// Each enqueues a tick-lane action, exactly as if the mob had typed it, and returns false if
// the queue is full.

/** Enqueues a ready-made [action] for [actor], subject to the queue cap (SPEC §5.2). */
fun World.enqueue(actor: Actor, verb: String, action: Action): Boolean {
    if (actor.actionQueue.size >= ACTION_QUEUE_MAX) {
        send(actor, "You can't plan that far ahead.")
        return false
    }
    actor.actionQueue.addLast(QueuedAction(TickCommand(verb) { action }, verb, ""))
    return true
}

fun World.actMove(mob: Mob, dir: Direction): Boolean = enqueue(mob, dir.name, MoveAction(dir))

fun World.actAttack(mob: Mob, target: Actor): Boolean = enqueue(mob, "kill", KillAction(target))

/** Casts [skill], which the mob must know, at [target] (or the skill's default target). */
fun World.actCast(mob: Mob, skill: SkillProto, target: Actor?): Boolean {
    require(skill.label in mob.skills) { "${mob.name} doesn't know ${skill.label}" }
    return enqueue(mob, "cast", CastAction(skill, null, target))
}

fun World.actAdvance(mob: Mob, target: Actor): Boolean = enqueue(mob, "advance", AdvanceAction(target))

fun World.actRetreat(mob: Mob, from: Actor?): Boolean = enqueue(mob, "retreat", RetreatAction(from))
