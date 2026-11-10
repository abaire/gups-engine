package gups.script

import gups.core.model.DamageType
import gups.core.model.Entity
import gups.core.world.HookContext
import gups.core.world.awardXp
import gups.core.world.clearContributions
import gups.core.world.damage
import gups.core.world.heal
import gups.core.world.isDying
import gups.core.world.levelDown
import gups.core.world.levelUpAndNotify
import gups.core.world.startCombat
import gups.core.world.stopCombat
import gups.core.world.takeXp
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark

/** Damage, healing, experience and combat engagement. */
abstract class CtxCombat
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxMobActions(frame, owner, hc, extras) {
  @StarlarkMethod(
    name = "damage",
    doc =
      "Deals damage directly to one vital (no to-hit roll, no mitigation); fires on_damage with just that loss.",
    parameters =
      [
        Param(name = "target", named = true, doc = "Actor to damage."),
        Param(name = "amount", named = true, doc = "Points of damage."),
        Param(name = "type", named = true, doc = "A declared damage type, such as DAMAGE.fire."),
        Param(name = "vital", named = true, doc = "The declared vital that loses the points."),
        Param(
          name = "source",
          defaultValue = "None",
          named = true,
          doc =
            "Actor credited with the damage; an omitted or None source is the applier of the affect whose hook is running.",
        ),
      ],
  )
  fun damage(target: Any, amount: Any, type: Any, vital: Any, source: Any): Any =
    verb("damage") {
      val t = actorArg(target, "damage(target)")
      val n = int(amount, "damage(amount)")
      if (n < 0) fail("damage(amount): can't be negative; use heal()")
      val schema = world.schema
      val damageType =
        checkName(
          str(type, "damage(type)").lowercase(),
          schema.damageTypes,
          "damage type",
          "damage(type)",
        )
      val name = checkName(str(vital, "damage(vital)"), schema.vitalNames, "vital", "damage(vital)")
      world.damage(
        t,
        mapOf(name to n),
        DamageType(damageType),
        if (isNone(source)) affectApplier() else actorArg(source, "damage(source)"),
      )
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "heal",
    doc = "Restores a vital of target, up to its maximum.",
    parameters =
      [
        Param(name = "target", named = true, doc = "Actor to heal."),
        Param(name = "amount", named = true, doc = "Points to restore."),
        Param(name = "vital", named = true, doc = "The declared vital to restore."),
        Param(
          name = "source",
          defaultValue = "None",
          named = true,
          doc =
            "Actor credited with the healing; an omitted or None source is the applier of the affect whose hook is running.",
        ),
      ],
  )
  fun heal(target: Any, amount: Any, vital: Any, source: Any): Any =
    verb("heal") {
      val n = int(amount, "heal(amount)")
      if (n < 0) fail("heal(amount): can't be negative; use damage()")
      val name =
        checkName(str(vital, "heal(vital)"), world.schema.vitalNames, "vital", "heal(vital)")
      world.heal(
        actorArg(target, "heal(target)"),
        name,
        n,
        source = if (isNone(source)) affectApplier() else actorArg(source, "heal(source)"),
      )
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "award_xp",
    doc =
      "Awards experience points to actor, applying pending levels in auto mode or sending" +
        " the ready notice in deferred mode. Returns None. Needs the leveling feature.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor to receive experience."),
        Param(name = "amount", named = true, doc = "Points to award; must be at least 1."),
      ],
  )
  fun awardXp(actor: Any, amount: Any): Any =
    verb("award_xp") {
      val a = actorArg(actor, "award_xp(actor)")
      val n = long(amount, "award_xp(amount)")
      if (n < 1L) fail("award_xp(amount): must be at least 1")
      world.awardXp(a, n)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "take_xp",
    doc =
      "Removes up to amount experience points from actor, never going below 0. Never changes" +
        " level and sends no message. Returns the amount actually removed. Needs the leveling" +
        " feature.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor to remove experience from."),
        Param(name = "amount", named = true, doc = "Points to remove; must be at least 1."),
      ],
  )
  fun takeXp(actor: Any, amount: Any): Any =
    verb("take_xp") {
      val a = actorArg(actor, "take_xp(actor)")
      val n = long(amount, "take_xp(amount)")
      if (n < 1L) fail("take_xp(amount): must be at least 1")
      sInt(world.takeXp(a, n))
    }

  @StarlarkMethod(
    name = "level_up",
    doc =
      "Applies one pending level to actor, in either level mode. In deferred mode, sends the" +
        " ready notice again if more levels are still pending. Returns True if a level was" +
        " gained, or False, changing nothing, if none was pending. Needs the leveling feature.",
    parameters = [Param(name = "actor", named = true, doc = "Actor to advance.")],
  )
  fun levelUp(actor: Any): Any =
    verb("level_up") {
      val a = actorArg(actor, "level_up(actor)")
      world.levelUpAndNotify(a)
    }

  @StarlarkMethod(
    name = "level_down",
    doc =
      "Lowers actor by up to count levels, stopping at level 1. Returns the number of levels" +
        " actually lost. Needs the leveling feature.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor to lower."),
        Param(
          name = "count",
          named = true,
          defaultValue = "1",
          doc = "Number of level steps to unwind; must be at least 1.",
        ),
      ],
  )
  fun levelDown(actor: Any, count: Any): Any =
    verb("level_down") {
      val a = actorArg(actor, "level_down(actor)")
      val c = int(count, "level_down(count)")
      if (c < 1) fail("level_down(count): must be at least 1")
      sInt(world.levelDown(a, c))
    }

  @StarlarkMethod(
    name = "start_combat",
    doc = "Engages two actors in the same room in combat.",
    parameters =
      [
        Param(name = "a", named = true, doc = "First actor."),
        Param(name = "b", named = true, doc = "Second actor, in the same room."),
      ],
  )
  fun startCombat(a: Any, b: Any): Any =
    verb("start_combat") {
      val x = actorArg(a, "start_combat(a)")
      val y = actorArg(b, "start_combat(b)")
      if (x === y) fail("start_combat(): an actor can't fight itself")
      if (x.room !== y.room)
        fail("start_combat(): ${x.name} and ${y.name} are not in the same room")
      for (d in listOf(x, y)) if (world.isDying(d)) fail("start_combat(): ${d.name} is dying")
      world.startCombat(x, y)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "stop_combat",
    doc = "Ends combat between a and b, or all of a's fights when b is None.",
    parameters =
      [
        Param(name = "a", named = true, doc = "Actor whose fighting stops."),
        Param(
          name = "b",
          defaultValue = "None",
          named = true,
          doc = "Opponent, or None for all of a's fights.",
        ),
      ],
  )
  fun stopCombat(a: Any, b: Any): Any =
    verb("stop_combat") {
      world.stopCombat(
        actorArg(a, "stop_combat(a)"),
        if (isNone(b)) null else actorArg(b, "stop_combat(b)"),
      )
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "clear_contributions",
    doc = "Empties victim's contribution record, or removes one contributor's entry.",
    parameters =
      [
        Param(name = "victim", named = true, doc = "Actor whose record is cleared."),
        Param(
          name = "contributor",
          defaultValue = "None",
          named = true,
          doc = "Contributor to remove, or None for the whole record.",
        ),
      ],
  )
  fun clearContributions(victim: Any, contributor: Any): Any =
    verb("clear_contributions") {
      val v = actorArg(victim, "clear_contributions(victim)")
      world.clearContributions(
        v,
        if (isNone(contributor)) null else actorArg(contributor, "clear_contributions(contributor)"),
      )
      Starlark.NONE
    }
}
