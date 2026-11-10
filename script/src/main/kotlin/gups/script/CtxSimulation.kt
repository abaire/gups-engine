package gups.script

import gups.core.model.Entity
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.world.HookContext
import gups.core.world.clearActive
import gups.core.world.clearAnchor
import gups.core.world.clearOwner
import gups.core.world.resetLeash
import gups.core.world.setActive
import gups.core.world.setAnchor
import gups.core.world.setLeash
import gups.core.world.setOwner
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkInt

/** Activation, ownership, leashes and anchors. */
abstract class CtxSimulation
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxState(frame, owner, hc, extras) {
  internal fun reason(
    entity: Any,
    reason: Any,
    name: String,
    change: (Entity, String) -> Boolean,
  ): Any =
    verb(name) {
      val e = entityArg(entity, "$name(entity)")
      val r = str(reason, "$name(reason)")
      if (r.isEmpty()) fail("$name(reason): must not be empty")
      change(e, r)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "set_active",
    doc =
      "Keeps an entity simulated (its queue, timers, AI and movement) even outside the bubble, until its last reason is cleared.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(
          name = "reason",
          named = true,
          doc = "Name for this reason; clear_active with the same name removes it.",
        ),
      ],
  )
  fun setActive(entity: Any, reason: Any): Any =
    reason(entity, reason, "set_active") { e, r -> world.setActive(e, r) }

  @StarlarkMethod(
    name = "clear_active",
    doc = "Removes a set_active reason from an entity.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(name = "reason", named = true, doc = "The reason given to set_active."),
      ],
  )
  fun clearActive(entity: Any, reason: Any): Any =
    reason(entity, reason, "clear_active") { e, r -> world.clearActive(e, r) }

  @StarlarkMethod(
    name = "set_owner",
    doc =
      "Makes a player the owner of a mob, replacing any owner it had. The mob then runs its owned behaviors, leaves its spawn rule and stops fighting its owner's side. Returns whether anything changed.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that becomes a pet."),
        Param(name = "player", named = true, doc = "The in-world player that owns it."),
      ],
  )
  fun setOwner(mob: Any, player: Any): Any =
    verb("set_owner") {
      val m = (mob as? ActorView)?.actor as? Mob ?: fail("set_owner(mob): must be a mob")
      val p =
        (player as? ActorView)?.actor?.takeIf { it is Player && it.inWorld } as? Player
          ?: fail("set_owner(player): must be a player in the world")
      world.setOwner(m, p)
    }

  @StarlarkMethod(
    name = "clear_owner",
    doc = "Removes a mob's owner. Returns whether it had one.",
    parameters = [Param(name = "mob", named = true, doc = "The mob to release.")],
  )
  fun clearOwner(mob: Any): Any =
    verb("clear_owner") {
      val m = (mob as? ActorView)?.actor as? Mob ?: fail("clear_owner(mob): must be a mob")
      world.clearOwner(m)
    }

  @StarlarkMethod(
    name = "set_leash",
    doc =
      "Overrides a mob's leash with an int of at least 0, or with None to unleash it. The override is saved with the mob and outlives reloads. Returns None.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob to leash."),
        Param(name = "leash", named = true, doc = "Most exits from home, or None to unleash."),
      ],
  )
  fun setLeash(mob: Any, leash: Any): Any =
    verb("set_leash") {
      val m = (mob as? ActorView)?.actor as? Mob ?: fail("set_leash(mob): must be a mob")
      val n =
        when (leash) {
          Starlark.NONE -> null
          is StarlarkInt -> {
            val v = leash.toInt("set_leash(leash)")
            if (v < 0) fail("set_leash(leash): must be an int of at least 0 or None")
            v
          }
          else -> fail("set_leash(leash): must be an int of at least 0 or None")
        }
      world.setLeash(m, n)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "reset_leash",
    doc =
      "Drops a mob's leash override, so its prototype leash applies again, including a value a later reload brings. Returns None.",
    parameters = [Param(name = "mob", named = true, doc = "The mob to reset.")],
  )
  fun resetLeash(mob: Any): Any =
    verb("reset_leash") {
      val m = (mob as? ActorView)?.actor as? Mob ?: fail("reset_leash(mob): must be a mob")
      world.resetLeash(m)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "set_anchor",
    doc =
      "Makes an entity project a bubble of its own, as players do, until its last reason is cleared.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(
          name = "reason",
          named = true,
          doc = "Name for this reason; clear_anchor with the same name removes it.",
        ),
      ],
  )
  fun setAnchor(entity: Any, reason: Any): Any =
    reason(entity, reason, "set_anchor") { e, r -> world.setAnchor(e, r) }

  @StarlarkMethod(
    name = "clear_anchor",
    doc = "Removes a set_anchor reason from an entity.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(name = "reason", named = true, doc = "The reason given to set_anchor."),
      ],
  )
  fun clearAnchor(entity: Any, reason: Any): Any =
    reason(entity, reason, "clear_anchor") { e, r -> world.clearAnchor(e, r) }
}
