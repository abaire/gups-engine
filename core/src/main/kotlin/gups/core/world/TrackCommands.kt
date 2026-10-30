package gups.core.world

import gups.core.Role
import gups.core.model.Actor
import gups.core.model.Band
import gups.core.rules.RulesContext

object TrackMsg : MsgGroup(Feature.TRACK) {
  val PINNED = msg("track.pinned", "You're pinned! Retreat or flee first.")
  val ADVANCE_WHOM = msg("track.advance_whom", "Advance toward whom?")
  val ALREADY_BESIDE = msg("track.already_beside", "You're already beside {target}.", "target")
  val ADVANCE_SELF = msg("track.advance.self", "You advance toward {target}.", "target")
  val ADVANCE_ROOM = msg("track.advance.room", "{actor} advances toward {target}.", "actor,target")
  val RETREAT_NOTHING = msg("track.retreat_nothing", "You have nothing to retreat from.")
  val RETREAT_LIMIT = msg("track.retreat_limit", "You can't retreat any further.")
  val RETREAT_FAILED = msg("track.retreat_failed", "You fail to break away!")
  val RETREAT_SELF = msg("track.retreat.self", "You fall back from {target}.", "target")
  val RETREAT_ROOM = msg("track.retreat.room", "{actor} falls back from {target}.", "actor,target")
}

/** The commands of the `track` feature. */
internal object TrackCommands {
  fun all(): List<CommandDef> =
    listOf(
      TickCommand(
        "advance",
        category = CommandCategory.MOVEMENT,
        help = "Advance toward your opponent on the combat track.\nUsage: advance",
      ) {
        planAdvance(it)
      },
      TickCommand(
        "retreat",
        category = CommandCategory.MOVEMENT,
        help = "Retreat away from your opponent on the combat track.\nUsage: retreat",
      ) {
        planRetreat(it)
      },
      FastCommand(
        "@where",
        role = Role.BUILDER,
        category = CommandCategory.SPECIAL,
        help = "Show your track index and bands numerically.\nUsage: @where",
      ) {
        Tools.where(it)
      },
    )

  private fun planAdvance(c: CommandCall): Action? {
    val w = c.world
    val target =
      (if (c.args.isEmpty()) w.nearestOpponent(c.actor) else w.findActor(c.actor, c.args))
        ?: return null.also { w.tell(c.actor, TrackMsg.ADVANCE_WHOM) }
    return AdvanceAction(target)
  }

  private fun planRetreat(c: CommandCall): Action? {
    val words = c.args.removePrefix("from ").trim()
    if (words.isEmpty()) return RetreatAction(null)
    val target =
      c.world.findActor(c.actor, words)
        ?: return null.also { c.world.tell(c.actor, KernelMsg.NOT_HERE) }
    return RetreatAction(target)
  }
}

internal class AdvanceAction(private val target: Actor) : Action {
  override fun validate(w: World, actor: Actor): String? {
    if (!w.isPresent(target, w.roomOf(actor))) return w.msg(KernelMsg.NOT_HERE)
    if (w.indexOf(target) == w.indexOf(actor))
      return w.msg(TrackMsg.ALREADY_BESIDE, "target" to target.name)
    return null
  }

  override fun resolve(w: World, actor: Actor): Int {
    val step = Integer.signum(target.trackIndex - actor.trackIndex)
    if (step != 0) w.placeActor(actor, actor.room, actor.trackIndex + step)
    w.tell(actor, TrackMsg.ADVANCE_SELF, "target" to target.name)
    w.tellSight(
      actor.room,
      "advance",
      actor,
      w.msg(TrackMsg.ADVANCE_ROOM, "actor" to actor.name.cap(), "target" to target.name),
      actor,
    )
    // The live positions: indexOf would still report where the step began.
    val band = Band.between(actor.trackIndex, target.trackIndex)
    return w.rules.advanceLag(RulesContext(w, actor, target, band = band))
  }
}

/** Steps away from [from], or from the nearest opponent when null. */
internal class RetreatAction(private val from: Actor?) : Action {
  private fun threat(w: World, actor: Actor): Actor? = from ?: w.nearestOpponent(actor)

  override fun validate(w: World, actor: Actor): String? {
    val t = threat(w, actor) ?: return w.msg(TrackMsg.RETREAT_NOTHING)
    if (!w.isPresent(t, w.roomOf(actor))) return w.msg(KernelMsg.NOT_HERE)
    if (step(w.indexOf(actor), w.indexOf(t), w.roomOf(actor).trackSize) == null)
      return w.msg(TrackMsg.RETREAT_LIMIT)
    return null
  }

  override fun resolve(w: World, actor: Actor): Int {
    val t = threat(w, actor)!!
    val pinner = w.opponentsOf(actor).firstOrNull { it.trackIndex == actor.trackIndex }
    if (
      pinner != null && w.rng.int(1, 100) > w.rules.retreatCheck(RulesContext(w, actor, pinner))
    ) {
      w.tell(actor, TrackMsg.RETREAT_FAILED)
      return w.rules.retreatFailLag(RulesContext(w, actor, pinner, band = Band.MELEE))
    }
    val dir = step(actor.trackIndex, t.trackIndex, actor.room.trackSize) ?: return 0
    w.placeActor(actor, actor.room, actor.trackIndex + dir)
    w.tell(actor, TrackMsg.RETREAT_SELF, "target" to t.name)
    w.tellSight(
      actor.room,
      "retreat",
      actor,
      w.msg(TrackMsg.RETREAT_ROOM, "actor" to actor.name.cap(), "target" to t.name),
      actor,
    )
    val band = Band.between(actor.trackIndex, t.trackIndex)
    return w.rules.retreatLag(RulesContext(w, actor, t, band = band))
  }

  companion object {
    /** Direction away from [threat]; on a shared index, toward the side with more room. */
    fun step(mine: Int, threat: Int, size: Int): Int? {
      val dir =
        when {
          mine < threat -> -1
          mine > threat -> 1
          size - 1 - mine >= mine -> 1
          else -> -1
        }
      return if (mine + dir in 0 until size) dir else null
    }
  }
}
