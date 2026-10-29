package gups.core.world

import gups.core.model.Actor
import gups.core.rules.RulesContext

object CombatMsg : MsgGroup(Feature.COMBAT) {
  val QUIT_REFUSED = msg("combat.quit_refused", "No way! You're fighting for your life!")
  val KILL_WHOM = msg("combat.kill_whom", "Kill whom?")
  val KILL_SELF = msg("combat.kill_self", "You can't attack yourself.")
  val KILL_ALREADY = msg("combat.kill_already", "You're already fighting {target}!", "target")
  val ATTACK_SELF = msg("combat.attack", "You attack {target}!", "target")
  val ATTACK_TARGET = msg("combat.attacked", "{actor} attacks you!", "actor")
  val FIGHTING = msg("combat.fighting", "{actor} and {target} are fighting!", "actor,target")
  val OUT_OF_REACH = msg("combat.out_of_reach", "{target} is out of reach.", "target")
  val MISS_SELF = msg("combat.miss.self", "You miss {target}.", "target")
  val MISS_TARGET = msg("combat.miss.target", "{actor} misses you.", "actor")
  val MISS_ROOM = msg("combat.miss.room", "{actor} misses {target}.", "actor,target")
  val HIT_SELF = msg("combat.hit.self", "You hit {target}.", "target")
  val HIT_TARGET = msg("combat.hit.target", "{actor} hits you.", "actor")
  val HIT_ROOM = msg("combat.hit.room", "{actor} hits {target}.", "actor,target")
  val FLEE_NOWHERE = msg("combat.flee.nowhere", "There's nowhere to flee!")
  val FLEE_FAILED = msg("combat.flee.failed", "You fail to escape!")
  val FLEE_SELF = msg("combat.flee.self", "You flee {direction}!", "direction")
  val FLEE_ROOM = msg("combat.flee.room", "{actor} flees {direction}.", "actor,direction")
}

/** The commands of the `combat` feature. */
internal object CombatCommands {
  fun all(): List<CommandDef> =
    listOf(
      TickCommand(
        "kill",
        category = CommandCategory.COMBAT,
        help = "Attack a target in the room.\nUsage: kill <target>",
      ) {
        planKill(it)
      },
      TickCommand(
        "flee",
        category = CommandCategory.COMBAT,
        help = "Flee from combat to an adjacent room.\nUsage: flee",
      ) {
        FleeAction
      },
    )

  private fun planKill(c: CommandCall): Action? {
    val w = c.world
    if (c.args.isEmpty()) return null.also { w.tell(c.actor, CombatMsg.KILL_WHOM) }
    val target =
      w.findActor(c.actor, c.args) ?: return null.also { w.tell(c.actor, KernelMsg.NOT_HERE) }
    return KillAction(target)
  }
}

/** `kill <target>`: engages and switches the primary target. */
internal class KillAction(private val target: Actor) : Action {
  override fun validate(w: World, actor: Actor): String? =
    when {
      target === actor -> w.msg(CombatMsg.KILL_SELF)
      !w.isPresent(target, w.roomOf(actor)) -> w.msg(KernelMsg.NOT_HERE)
      actor.primaryTarget == target.id -> w.msg(CombatMsg.KILL_ALREADY, "target" to target.name)
      else -> null
    }

  override fun resolve(w: World, actor: Actor): Int {
    w.startCombat(actor, target)
    actor.primaryTarget = target.id
    w.tell(actor, CombatMsg.ATTACK_SELF, "target" to target.name)
    w.tell(target, CombatMsg.ATTACK_TARGET, "actor" to actor.name.cap())
    return 0
  }
}

/**
 * Instant; rolls `flee_chance`, ignores pinning, and leaves through a random open exit. The exit's
 * guards and `before_exit`/`before_enter` hooks still apply, so combat can't be used to slip past
 * them; a denial counts as a failed flee.
 */
internal object FleeAction : Action {
  override fun validate(w: World, actor: Actor): String? =
    if (w.roomOf(actor).exits.values.none { it.passable }) w.msg(CombatMsg.FLEE_NOWHERE) else null

  override fun resolve(w: World, actor: Actor): Int {
    if (w.rng.int(1, 100) > w.rules.fleeChance(RulesContext(w, actor))) {
      w.tell(actor, CombatMsg.FLEE_FAILED)
      return w.rules.fleeFailLag(RulesContext(w, actor))
    }
    val exit = w.rng.choice(actor.room.exits.values.filter { it.passable })
    w.exitDenial(actor, exit.direction)?.let {
      w.send(actor, it)
      return w.rules.fleeFailLag(RulesContext(w, actor))
    }
    w.tell(actor, CombatMsg.FLEE_SELF, "direction" to exit.direction)
    w.deferRemoval(actor, Deferred.Kind.TRANSITION) {
      w.transition(actor, exit, CombatMsg.FLEE_ROOM)
    }
    return 0
  }
}
