package gups.core.world

import gups.core.BUBBLE_RADIUS
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Direction
import gups.core.model.Item
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Room
import gups.core.rules.RulesContext

// The built-in behaviors (SPEC §4.7). Each proposes at most one action per think cycle; the
// think cycle sorts the proposals by priority, then by declaration order (SPEC §5.8). The
// priorities are fixed (D-121): fleeing beats fighting, which beats moving about.

const val PRIORITY_FLEE = 100
const val PRIORITY_AGGRESSIVE = 60
const val PRIORITY_ASSIST = 50
const val PRIORITY_PURSUE = 40
const val PRIORITY_FOLLOW = 30
const val PRIORITY_WANDER = 10
const val PRIORITY_SCAVENGE = 5

/**
 * Whom a behavior means (D-121): `players`, `mobs`, `all`, or label patterns such as
 * `//zones/caves:goblin_*` with `*` wildcards. A pattern without a package matches the label's
 * name in any package.
 */
class ActorFilter(val patterns: List<String>) {
    constructor(vararg patterns: String) : this(patterns.toList())

    fun matches(a: Actor): Boolean = patterns.any { p ->
        when (p) {
            "players" -> a is Player
            "mobs" -> a is Mob
            "all", "anyone" -> true
            else -> a is Mob && labelMatches(p, a.protoLabel)
        }
    }

    override fun toString(): String = patterns.joinToString(", ")

    companion object {
        fun labelMatches(pattern: String, label: Label): Boolean {
            val text = if (pattern.startsWith("//")) label.v else label.name
            val regex = pattern.split("*").joinToString(".*") { Regex.escape(it) }
            return Regex(regex).matches(text)
        }
    }
}

private fun World.others(mob: Mob): List<Actor> =
    mob.room.actorIndex.values.filter { it !== mob && it.inWorld && it.hp > 0 && isSimulated(it) }

/** `wander(radius, chance)`: with probability [chance] per think, steps through a random open exit that stays within [radius] exits of home. */
class Wander(val radius: Int, val chance: Double) : Behavior {
    override fun propose(world: World, mob: Mob): Proposal? {
        if (mob.isEngaged || world.rng.fraction() >= chance) return null
        val home = world.roomOrNull(mob.homeRoom) ?: mob.room
        val near = world.within(home, radius)
        val exits = mob.room.exits.values.filter { it.passable && world.roomOrNull(it.to) in near && it.to != mob.room.label }
        if (exits.isEmpty()) return null
        val exit = world.rng.choice(exits)
        return Proposal(exit.direction.name, PRIORITY_WANDER, "wander", MoveAction(exit.direction))
    }
}

/** Rooms within [radius] exits of [from] through open doors. */
internal fun World.within(from: Room, radius: Int): Set<Room> {
    val seen = linkedMapOf(from to 0)
    val queue = ArrayDeque(listOf(from))
    while (queue.isNotEmpty()) {
        val r = queue.removeFirst()
        val d = seen.getValue(r)
        if (d >= radius) continue
        for (e in r.exits.values) {
            if (!e.passable) continue
            val next = roomOrNull(e.to) ?: continue
            if (next !in seen) {
                seen[next] = d + 1
                queue.addLast(next)
            }
        }
    }
    return seen.keys
}

/** `aggressive(toward, unless_level_above)`: attacks the first matching actor in the room. */
class Aggressive(val toward: ActorFilter, val unlessLevelAbove: Int? = null) : Behavior {
    override fun propose(world: World, mob: Mob): Proposal? {
        if (mob.isEngaged) return null
        val target = world.others(mob).firstOrNull { toward.matches(it) && (unlessLevelAbove == null || it.level <= unlessLevelAbove) }
            ?: return null
        return Proposal("kill ${target.keywords.first()}", PRIORITY_AGGRESSIVE, "aggressive", KillAction(target))
    }
}

/** `assist(allies)`: joins a fight an ally in the room is in, against the ally's opponent. */
class Assist(val allies: ActorFilter) : Behavior {
    override fun propose(world: World, mob: Mob): Proposal? {
        if (mob.isEngaged) return null
        for (ally in world.others(mob)) {
            if (!allies.matches(ally) || !ally.isEngaged) continue
            val foes = world.opponentsOf(ally).filter { it !== mob && !allies.matches(it) }
            val foe = foes.firstOrNull { it.id == ally.primaryTarget } ?: foes.firstOrNull() ?: continue
            return Proposal("kill ${foe.keywords.first()}", PRIORITY_ASSIST, "assist", KillAction(foe))
        }
        return null
    }
}

/** `flee(at_hp_pct)`: flees once hit points are at or below the threshold, engaged or not (D-121). */
class Flee(val atHpPct: Int) : Behavior {
    override fun propose(world: World, mob: Mob): Proposal? {
        if (mob.hp.toLong() * 100 > atHpPct.toLong() * mob.maxHp) return null
        if (mob.room.exits.values.none { it.passable }) return null
        return Proposal("flee", PRIORITY_FLEE, "flee", FleeAction)
    }
}

/**
 * `guard(exit)`: denies [exit] to everyone but [allies] while the guard is in the room and
 * able to act. A built-in `before_exit` observer, not a proposal.
 */
class Guard(val exit: Direction, val allies: ActorFilter = ActorFilter("mobs")) : Behavior {
    override fun propose(world: World, mob: Mob): Proposal? = null

    fun denies(world: World, guard: Mob, actor: Actor, dir: Direction): Boolean =
        dir == exit && actor !== guard && !allies.matches(actor) && world.canAct(guard) && world.isSimulated(guard)
}

/** The first guard in [room] that bars [actor] from leaving by [dir], as a denial message. */
internal fun World.guardDenial(actor: Actor, room: Room, dir: Direction): String? {
    for (m in room.actorIndex.values) {
        if (m !is Mob || !m.inWorld || m === actor) continue
        if (m.proto.behaviors.any { it is Guard && it.denies(this, m, actor, dir) }) return "${m.name.cap()} blocks your way."
    }
    return null
}

/**
 * `follow(target)`: picks the first matching actor in its room as leader, then follows it
 * along the shortest path while it is within [BUBBLE_RADIUS] exits; farther away, it gives up.
 */
class Follow(val target: ActorFilter) : Behavior {
    override fun propose(world: World, mob: Mob): Proposal? {
        if (mob.isEngaged) return null
        val leader = mob.leader?.let(world::actor)?.takeIf { it.inWorld && target.matches(it) }
        if (leader == null) {
            mob.leader = world.others(mob).firstOrNull { target.matches(it) }?.id
            return null
        }
        if (leader.room === mob.room) return null
        val path = world.pathTo(mob.room, leader.room)
        if (path == null || path.size > BUBBLE_RADIUS) {
            mob.leader = null
            return null
        }
        return Proposal(path.first().name, PRIORITY_FOLLOW, "follow", MoveAction(path.first()))
    }
}

/** `pursue(target)`: heads for the nearest matching actor anywhere in the world by `path_to`. */
class Pursue(val target: ActorFilter) : Behavior {
    override fun propose(world: World, mob: Mob): Proposal? {
        if (mob.isEngaged) return null
        if (world.others(mob).any { target.matches(it) }) return null
        val best = world.actors.asSequence()
            .filter { it !== mob && it.inWorld && target.matches(it) && world.isSimulated(it) }
            .mapNotNull { t -> world.pathTo(mob.room, t.room)?.takeIf { it.isNotEmpty() }?.let { t to it } }
            .minWithOrNull(compareBy<Pair<Actor, List<Direction>>>({ it.second.size }, { it.first.id }))
            ?: return null
        val step = best.second.first()
        return Proposal(step.name, PRIORITY_PURSUE, "pursue", MoveAction(step))
    }
}

/** `sentinel()`: never leaves its room. Removes other behaviors' moves and flights. */
object Sentinel : Behavior {
    override fun propose(world: World, mob: Mob): Proposal? = null

    fun allows(p: Proposal): Boolean = p.action !is MoveAction && p.action !== FleeAction
}

/** `scavenge()`: picks up the first item on the ground it can reach and carry. Corpses are left alone. */
object Scavenge : Behavior {
    override fun propose(world: World, mob: Mob): Proposal? {
        if (mob.isEngaged) return null
        val item = mob.room.items.firstOrNull { world.canScavenge(mob, it) } ?: return null
        return Proposal("get ${item.keywords.first()}", PRIORITY_SCAVENGE, "scavenge", GetAction(item.keywords.first(), null, fixed = item))
    }
}

private fun World.canScavenge(mob: Mob, item: Item): Boolean {
    if (item.kind == "corpse") return false
    val band = Band.between(mob.trackIndex, item.groundIndex ?: return false)
    if (rules.getLag(RulesContext(this, mob, item = item, band = band), band) == null) return false
    return mob.carriedWeight() + item.weight <= rules.carryLimit(RulesContext(this, mob, item = item))
}
