package gups.core.world

import gups.core.model.Actor
import gups.core.model.Direction
import gups.core.model.Exit
import gups.core.model.Player
import gups.core.model.Room
import gups.core.rules.RulesContext

// Room transitions and the tactical track (SPEC §5.5).

/** The arrival index for [exit] in [to]: `-1` maps to the back; clamped to the track. */
fun entryIndexFor(exit: Exit, to: Room): Int =
    (if (exit.entryIndex < 0) to.trackSize - 1 else exit.entryIndex).coerceIn(0, to.trackSize - 1)

/**
 * Moves [actor] through [exit]: ends its engagements, places it at the entry index, and fires
 * `on_exit`/`on_enter`. Callers inside an initiative tier go through [deferRemoval].
 */
internal fun World.transition(actor: Actor, exit: Exit, verb: String = "leaves") {
    if (!actor.inWorld) return
    val from = actor.room
    val to = room(exit.to)
    disengageAll(actor)
    echo(from, "${actor.name.cap()} $verb ${exit.direction}.", actor)
    placeActor(actor, to, entryIndexFor(exit, to))
    echo(to, "${actor.name.cap()} arrives.", actor)
    if (actor is Player) send(actor, describeRoom(actor))
    dispatch(HookName.ON_EXIT, null, from, HookContext(actor = actor, direction = exit.direction, toRoom = to))
    dispatch(HookName.ON_ENTER, null, to, HookContext(actor = actor, direction = exit.direction, fromRoom = from))
}

/** The `teleport` verb: engine-validated, fires the exit and enter hooks. */
fun World.teleport(actor: Actor, to: Room, index: Int? = null) {
    deferRemoval(actor, Deferred.Kind.TRANSITION) {
        if (!actor.inWorld) return@deferRemoval
        val from = actor.room
        disengageAll(actor)
        echo(from, "${actor.name.cap()} vanishes.", actor)
        placeActor(actor, to, index ?: 0)
        echo(to, "${actor.name.cap()} appears.", actor)
        if (actor is Player) send(actor, describeRoom(actor))
        dispatch(HookName.ON_EXIT, null, from, HookContext(actor = actor, toRoom = to))
        dispatch(HookName.ON_ENTER, null, to, HookContext(actor = actor, fromRoom = from))
    }
}

/** Walking through an exit: one action plus `move_cost` move points. */
internal class MoveAction(private val dir: Direction) : Action {
    override fun validate(w: World, actor: Actor): String? {
        val exit = w.roomOf(actor).exits[dir] ?: return "You can't go that way."
        if (!exit.passable) return "The way $dir is closed."
        if (w.isPinned(actor)) return "You're pinned! Retreat or flee first."
        if (actor.move < w.rules.moveCost(RulesContext(w, actor))) return "You are too exhausted."
        return null
    }

    override fun veto(w: World, actor: Actor): String? {
        val from = w.roomOf(actor)
        val exit = from.exits.getValue(dir)
        val to = w.room(exit.to)
        return w.dispatch(HookName.BEFORE_EXIT, null, from, HookContext(actor = actor, direction = dir, toRoom = to))
            ?: w.dispatch(HookName.BEFORE_ENTER, null, to, HookContext(actor = actor, direction = dir, fromRoom = from))
    }

    override fun resolve(w: World, actor: Actor): Int {
        val exit = w.roomOf(actor).exits.getValue(dir)
        actor.move -= w.rules.moveCost(RulesContext(w, actor))
        w.deferRemoval(actor, Deferred.Kind.TRANSITION) { w.transition(actor, exit) }
        return 0
    }
}

internal class AdvanceAction(private val target: Actor) : Action {
    override fun validate(w: World, actor: Actor): String? {
        if (!w.isPresent(target, w.roomOf(actor))) return "They aren't here."
        if (w.indexOf(target) == w.indexOf(actor)) return "You're already beside ${target.name}."
        return null
    }

    override fun resolve(w: World, actor: Actor): Int {
        val step = Integer.signum(target.trackIndex - actor.trackIndex)
        if (step != 0) w.placeActor(actor, actor.room, actor.trackIndex + step)
        w.send(actor, "You advance toward ${target.name}.")
        w.echo(actor.room, "${actor.name.cap()} advances toward ${target.name}.", actor)
        return w.rules.advanceLag(RulesContext(w, actor, target))
    }
}

/** Steps away from [from], or from the nearest opponent when null. */
internal class RetreatAction(private val from: Actor?) : Action {
    private fun threat(w: World, actor: Actor): Actor? = from ?: w.nearestOpponent(actor)

    override fun validate(w: World, actor: Actor): String? {
        val t = threat(w, actor) ?: return "You have nothing to retreat from."
        if (!w.isPresent(t, w.roomOf(actor))) return "They aren't here."
        if (step(w.indexOf(actor), w.indexOf(t), w.roomOf(actor).trackSize) == null) return "You can't retreat any further."
        return null
    }

    override fun resolve(w: World, actor: Actor): Int {
        val t = threat(w, actor)!!
        val pinner = w.opponentsOf(actor).firstOrNull { it.trackIndex == actor.trackIndex }
        if (pinner != null && w.rng.int(1, 100) > w.rules.retreatCheck(RulesContext(w, actor, pinner))) {
            w.send(actor, "You fail to break away!")
            return w.rules.retreatFailLag(RulesContext(w, actor, pinner))
        }
        val dir = step(actor.trackIndex, t.trackIndex, actor.room.trackSize) ?: return 0
        w.placeActor(actor, actor.room, actor.trackIndex + dir)
        w.send(actor, "You fall back from ${t.name}.")
        w.echo(actor.room, "${actor.name.cap()} falls back from ${t.name}.", actor)
        return w.rules.retreatLag(RulesContext(w, actor, t))
    }

    companion object {
        /** Direction away from [threat]; on a shared index, toward the side with more room. */
        fun step(mine: Int, threat: Int, size: Int): Int? {
            val dir = when {
                mine < threat -> -1
                mine > threat -> 1
                size - 1 - mine >= mine -> 1
                else -> -1
            }
            return if (mine + dir in 0 until size) dir else null
        }
    }
}

/** Instant; rolls `flee_chance`, ignores pinning, and leaves through a random open exit. */
internal object FleeAction : Action {
    override fun validate(w: World, actor: Actor): String? =
        if (w.roomOf(actor).exits.values.none { it.passable }) "There's nowhere to flee!" else null

    override fun resolve(w: World, actor: Actor): Int {
        if (w.rng.int(1, 100) > w.rules.fleeChance(RulesContext(w, actor))) {
            w.send(actor, "You fail to escape!")
            return w.rules.fleeFailLag(RulesContext(w, actor))
        }
        val exit = w.rng.choice(actor.room.exits.values.filter { it.passable })
        w.send(actor, "You flee ${exit.direction}!")
        w.deferRemoval(actor, Deferred.Kind.TRANSITION) { w.transition(actor, exit, "flees") }
        return 0
    }
}
