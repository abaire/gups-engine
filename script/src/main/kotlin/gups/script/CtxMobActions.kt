package gups.script

import gups.core.model.Entity
import gups.core.model.Mob
import gups.core.model.Room
import gups.core.world.HookContext
import gups.core.world.act
import gups.core.world.actAdvance
import gups.core.world.actAttack
import gups.core.world.actCast
import gups.core.world.actMove
import gups.core.world.actRetreat
import gups.core.world.pathTo
import gups.core.world.teleport
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread

/** Mob actions of the tick lane, and teleporting. */
abstract class CtxMobActions
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxMessaging(frame, owner, hc, extras) {
  internal fun acted(mob: Mob, ok: Boolean): Any {
    if (mob === owner) frame.acted = true
    return ok
  }

  @StarlarkMethod(
    name = "act",
    doc = "Queues a command line for a mob, exactly as if it typed it.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that acts."),
        Param(
          name = "command_line",
          named = true,
          doc = "The command line, as a player would type it.",
        ),
      ],
  )
  fun act(mob: Any, commandLine: Any): Any =
    verb("act") {
      val m = mobArg(mob, "act(mob)")
      acted(m, world.act(m, text(commandLine, "act(command_line)")))
    }

  @StarlarkMethod(
    name = "act_move",
    doc = "Queues a move through an exit for a mob.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that acts."),
        Param(name = "dir", named = true, doc = "Direction of the exit to take."),
      ],
  )
  fun actMove(mob: Any, dir: Any): Any =
    verb("act_move") {
      val m = mobArg(mob, "act_move(mob)")
      acted(m, world.actMove(m, dir(dir, "act_move(dir)")))
    }

  @StarlarkMethod(
    name = "act_attack",
    doc = "Queues an attack against target for a mob.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that acts."),
        Param(name = "target", named = true, doc = "Actor to attack."),
      ],
  )
  fun actAttack(mob: Any, target: Any): Any =
    verb("act_attack") {
      val m = mobArg(mob, "act_attack(mob)")
      acted(m, world.actAttack(m, actorArg(target, "act_attack(target)")))
    }

  @StarlarkMethod(
    name = "act_cast",
    doc = "Queues casting a skill for a mob.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that acts."),
        Param(name = "skill", named = true, doc = "Skill label to cast."),
        Param(
          name = "target",
          defaultValue = "None",
          named = true,
          doc = "Target actor or item, or None for the skill's default target.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun actCast(mob: Any, skill: Any, target: Any, thread: StarlarkThread): Any =
    verb("act_cast") {
      val m = mobArg(mob, "act_cast(mob)")
      val proto =
        when (skill) {
          is SkillView -> skill.skill
          else ->
            world.protos.skills[
                label(str(skill, "act_cast(skill)"), pkgOf(thread), "act_cast(skill)")]
              ?: fail("act_cast(skill): there is no skill $skill")
        }
      acted(
        m,
        world.actCast(m, proto, if (isNone(target)) null else actorArg(target, "act_cast(target)")),
      )
    }

  @StarlarkMethod(
    name = "act_advance",
    doc = "Queues one step along the room track toward target for a mob.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that acts."),
        Param(name = "target", named = true, doc = "Actor or item in the same room to step toward."),
      ],
  )
  fun actAdvance(mob: Any, target: Any): Any =
    verb("act_advance") {
      val m = mobArg(mob, "act_advance(mob)")
      acted(m, world.actAdvance(m, actorArg(target, "act_advance(target)")))
    }

  @StarlarkMethod(
    name = "act_retreat",
    doc =
      "Queues one step along the room track away from target (or toward the room back) for a mob.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that acts."),
        Param(
          name = "target",
          defaultValue = "None",
          named = true,
          doc = "Actor or item to step away from, or None to step toward the back of the room.",
        ),
      ],
  )
  fun actRetreat(mob: Any, target: Any): Any =
    verb("act_retreat") {
      val m = mobArg(mob, "act_retreat(mob)")
      acted(
        m,
        world.actRetreat(m, if (isNone(target)) null else actorArg(target, "act_retreat(target)")),
      )
    }

  @StarlarkMethod(
    name = "move_toward",
    doc =
      "Queues one step toward a direction, an actor (advancing within the room) or a room. False if there is no way.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that acts."),
        Param(
          name = "dir_or_target",
          named = true,
          doc = "Direction, actor, or room (view or label) to head for.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun moveToward(mob: Any, dirOrTarget: Any, thread: StarlarkThread): Any =
    verb("move_toward") {
      val m = mobArg(mob, "move_toward(mob)")
      val dest: Room =
        when (dirOrTarget) {
          is ActorView -> {
            val t = actorArg(dirOrTarget, "move_toward(target)")
            if (t.room === m.room) {
              return@verb if (t.trackIndex == m.trackIndex) false
              else acted(m, world.actAdvance(m, t))
            }
            t.room
          }
          is String ->
            if (dirOrTarget.startsWith("//") || dirOrTarget.startsWith(":")) {
              roomArg(dirOrTarget, "move_toward(target)", thread)
            } else {
              return@verb acted(m, world.actMove(m, dir(dirOrTarget, "move_toward(dir)")))
            }
          else -> roomArg(dirOrTarget, "move_toward(target)", thread)
        }
      val step = world.pathTo(m.room, dest)?.firstOrNull() ?: return@verb false
      acted(m, world.actMove(m, step))
    }

  @StarlarkMethod(
    name = "teleport",
    doc =
      "Moves an actor to a room; fires the exit and enter hooks. Asks the enter restriction and" +
        " before_enter first, with no direction; guards and before_exit don't run. Returns the" +
        " refusal or veto message and moves nothing, or None once the actor has moved.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor to move."),
        Param(name = "room", named = true, doc = "Destination room or room label."),
        Param(
          name = "index",
          defaultValue = "None",
          named = true,
          doc = "Track index on arrival, or None for the room's default.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun teleport(actor: Any, room: Any, index: Any, thread: StarlarkThread): Any =
    verb("teleport") {
      trackIndex("teleport", index)
      val r = roomArg(room, "teleport(room)", thread)
      val i = intOrNull(index, "teleport(index)")
      if (i != null && i !in 0 until r.trackSize) fail("teleport(index): $i is outside ${r.label}")
      world.teleport(actorArg(actor, "teleport(actor)"), r, i) ?: Starlark.NONE
    }
}
