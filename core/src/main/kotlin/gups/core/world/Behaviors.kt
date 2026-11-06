package gups.core.world

import gups.core.BUBBLE_RADIUS
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.Condition
import gups.core.model.Direction
import gups.core.model.Item
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Room
import gups.core.rules.RulesContext

// The built-in behaviors. Each proposes at most one action per think cycle; the
// think cycle sorts the proposals by priority, then by declaration order. The
// priorities default to the constants below, where fleeing beats fighting, which beats moving
// about, and each behavior takes its own.

const val PRIORITY_FLEE = 100
const val PRIORITY_DEFEND = 50
const val PRIORITY_HEEL = 30
const val PRIORITY_AGGRESSIVE = 60
const val PRIORITY_ASSIST = 50
const val PRIORITY_PURSUE = 40
const val PRIORITY_FOLLOW = 30
const val PRIORITY_WANDER = 10
const val PRIORITY_SCAVENGE = 5

/**
 * Whom a behavior means: `players`, `mobs`, `all`, or label patterns such as
 * `//zones/caves:goblin_*` with `*` wildcards. A pattern without a package matches the label's name
 * in any package.
 */
data class ActorFilter(val patterns: List<String>) {
  constructor(vararg patterns: String) : this(patterns.toList())

  fun matches(a: Actor): Boolean =
    patterns.any { p ->
      when (p) {
        "players" -> a is Player
        "mobs" -> a is Mob
        "all",
        "anyone" -> true
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
  mob.room.actorIndex.values.filter {
    it !== mob && it.inWorld && it.condition != Condition.DEAD && isSimulated(it)
  }

/**
 * `wander(radius, chance)`: with probability [chance] percent per think, steps through a random
 * open exit that stays within [radius] exits of home.
 */
data class Wander(val radius: Int, val chance: Int, val priority: Int = PRIORITY_WANDER) :
  Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (mob.isEngaged || !world.rng.passes(chance)) return null
    val home = world.roomOrNull(mob.homeRoom) ?: mob.room
    val near = world.within(home, radius)
    val exits =
      mob.room.exits.values.filter {
        it.passable && world.roomOrNull(it.to) in near && it.to != mob.room.label
      }
    if (exits.isEmpty()) return null
    val exit = world.rng.choice(exits)
    return Proposal(exit.direction.name, priority, "wander", MoveAction(exit.direction))
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

/**
 * `aggressive(toward, unless_level_above, unless_level_below, chance, via)`: on each think, with
 * probability [chance] percent, attacks the first matching actor in the room the mob notices on any
 * channel in [via], sparing actors outside the level band.
 */
data class Aggressive(
  val toward: ActorFilter,
  val unlessLevelAbove: Int? = null,
  val unlessLevelBelow: Int? = null,
  val chance: Int = 100,
  val via: List<Channel> = listOf(Channel.SIGHT),
  val priority: Int = PRIORITY_AGGRESSIVE,
) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (Feature.COMBAT !in world.features || mob.isEngaged) return null
    if (!world.rng.passes(chance)) return null
    val target =
      world.others(mob).firstOrNull {
        toward.matches(it) &&
          levelOk(it) &&
          !world.isOwnerSide(mob, it) &&
          world.notices(mob, it, via)
      } ?: return null
    return Proposal(
      "${world.commands.nameOf("kill")} ${target.keywords.first()}",
      priority,
      "aggressive",
      KillAction(target),
    )
  }

  private fun levelOk(a: Actor): Boolean =
    (unlessLevelAbove == null || a.level <= unlessLevelAbove) &&
      (unlessLevelBelow == null || a.level >= unlessLevelBelow)
}

/** Whether [mob] notices [who] in its own room on any channel in [via], as `aggressive` asks it. */
private fun World.notices(mob: Mob, who: Actor, via: List<Channel>): Boolean =
  via.any { ch ->
    rules.canPerceive(
      RulesContext(
        this,
        actor = mob,
        target = mob,
        source = who,
        room = mob.room,
        sourceRoom = mob.room,
        channel = ch,
        event = "notice",
        intensity = 1,
        cost = 0,
      )
    )
  }

/** `assist(allies)`: joins a fight an ally in the room is in, against the ally's opponent. */
data class Assist(val allies: ActorFilter, val priority: Int = PRIORITY_ASSIST) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (Feature.COMBAT !in world.features || mob.isEngaged) return null
    for (ally in world.others(mob)) {
      if (!allies.matches(ally) || !ally.isEngaged) continue
      val foes =
        world.opponentsOf(ally).filter {
          it !== mob && !allies.matches(it) && !world.isOwnerSide(mob, it)
        }
      val foe = foes.firstOrNull { it.id == ally.primaryTarget } ?: foes.firstOrNull() ?: continue
      return Proposal(
        "${world.commands.nameOf("kill")} ${foe.keywords.first()}",
        priority,
        "assist",
        KillAction(foe),
      )
    }
    return null
  }
}

/**
 * `flee(below)`: flees once any listed vital is at or below its percentage of its maximum, engaged
 * or not.
 */
data class Flee(val below: Map<String, Int>, val priority: Int = PRIORITY_FLEE) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (Feature.COMBAT !in world.features) return null
    val low =
      below.any { (vital, pct) ->
        val max = mob.max(vital)
        // A vital the mob has no capacity for is never low.
        max > 0 && mob.current(vital).toLong() * 100 <= pct.toLong() * max
      }
    if (!low) return null
    if (mob.room.exits.values.none { it.passable }) return null
    return Proposal(world.commands.nameOf("flee"), priority, "flee", FleeAction)
  }
}

/**
 * `guard(exit)`: denies [exit] to everyone but [allies] while the guard is in the room and able to
 * act. A built-in `before_exit` observer, not a proposal.
 */
data class Guard(val exit: Direction, val allies: ActorFilter = ActorFilter("mobs")) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? = null

  fun denies(world: World, guard: Mob, actor: Actor, dir: Direction): Boolean =
    dir == exit &&
      actor !== guard &&
      !allies.matches(actor) &&
      world.canAct(guard) &&
      world.isSimulated(guard)
}

/** The first guard in [room] that bars [actor] from leaving by [dir], as a denial message. */
internal fun World.guardDenial(actor: Actor, room: Room, dir: Direction): String? {
  for (m in room.actorIndex.values) {
    if (m !is Mob || !m.inWorld || m === actor) continue
    if (behaviorsOf(m).any { it is Guard && it.denies(this, m, actor, dir) })
      return msg(KernelMsg.GUARD_BLOCKS, "actor" to m.name.cap())
  }
  return null
}

/**
 * `follow(target)`: picks the first matching actor in its room as leader, then follows it along the
 * shortest path while it is within [BUBBLE_RADIUS] exits; farther away, it gives up.
 */
data class Follow(val target: ActorFilter, val priority: Int = PRIORITY_FOLLOW) : Behavior {
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
    return Proposal(path.first().name, priority, "follow", MoveAction(path.first()))
  }
}

/** `pursue(target)`: heads for the nearest matching actor anywhere in the world by `path_to`. */
data class Pursue(val target: ActorFilter, val priority: Int = PRIORITY_PURSUE) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (mob.isEngaged) return null
    if (world.others(mob).any { target.matches(it) }) return null
    val best =
      world.actors
        .asSequence()
        .filter { it !== mob && it.inWorld && target.matches(it) && world.isSimulated(it) }
        .mapNotNull { t ->
          world.pathTo(mob.room, t.room)?.takeIf { it.isNotEmpty() }?.let { t to it }
        }
        .minWithOrNull(compareBy<Pair<Actor, List<Direction>>>({ it.second.size }, { it.first.id }))
        ?: return null
    val step = best.second.first()
    return Proposal(step.name, priority, "pursue", MoveAction(step))
  }
}

/** `heel()`: follows the mob's owner by the shortest path, however far. */
data class Heel(val priority: Int = PRIORITY_HEEL) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (mob.isEngaged) return null
    val owner = world.ownerOf(mob) ?: return null
    if (owner.room === mob.room) return null
    val path = world.pathTo(mob.room, owner.room)?.takeIf { it.isNotEmpty() } ?: return null
    return Proposal(path.first().name, priority, "heel", MoveAction(path.first()))
  }
}

/**
 * `defend(group)`: joins the fights of the mob's owner, and with [group] its in-room group members.
 */
data class Defend(val group: Boolean = true, val priority: Int = PRIORITY_DEFEND) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (Feature.COMBAT !in world.features || mob.isEngaged) return null
    val owner = world.ownerOf(mob) ?: return null
    val candidates = buildList {
      if (owner.room === mob.room) add(owner)
      if (group) {
        addAll(
          world
            .groupMembers(owner)
            .filter { it !== owner && it.room === mob.room }
            .sortedBy { it.id }
        )
      }
    }
    for (c in candidates) {
      if (!c.isEngaged) continue
      val foes = world.opponentsOf(c).filter { it !== mob && !world.isOwnerSide(mob, it) }
      val foe = foes.firstOrNull { it.id == c.primaryTarget } ?: foes.firstOrNull() ?: continue
      return Proposal(
        "${world.commands.nameOf("kill")} ${foe.keywords.first()}",
        priority,
        "defend",
        KillAction(foe),
      )
    }
    return null
  }
}

/** The behaviors [mob] runs now: its owned list while it has an owner, else its prototype's. */
fun World.behaviorsOf(mob: Mob): List<Behavior> =
  if (mob.owner != null) mob.ownedBehaviors ?: defaultOwnedBehaviors(mob) else mob.proto.behaviors

/** `sentinel()`: never leaves its room. Removes other behaviors' moves and flights. */
data object Sentinel : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? = null

  fun allows(p: Proposal): Boolean = p.action !is MoveAction && p.action !== FleeAction
}

/**
 * `scavenge()`: picks up the first item on the ground it can reach and carry. Corpses are left
 * alone.
 */
data class Scavenge(val priority: Int = PRIORITY_SCAVENGE) : Behavior {
  override fun propose(world: World, mob: Mob): Proposal? {
    if (mob.isEngaged) return null
    val item = mob.room.items.firstOrNull { world.canScavenge(mob, it) } ?: return null
    return Proposal(
      "${world.commands.nameOf("get")} ${item.keywords.first()}",
      priority,
      "scavenge",
      GetAction("", fixed = item),
    )
  }
}

private fun World.canScavenge(mob: Mob, item: Item): Boolean {
  if (item.kind == "corpse") return false
  if (Feature.TRACK in features) {
    val band = Band.between(mob.trackIndex, item.groundIndex ?: return false)
    if (rules.getLag(RulesContext(this, mob, item = item, band = band), band) == null) return false
  }
  return mob.carriedWeight() + item.weight <= rules.carryLimit(RulesContext(this, mob, item = item))
}
