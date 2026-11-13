package gups.script

import gups.core.model.Entity
import gups.core.model.ItemLocation
import gups.core.world.HookContext
import gups.core.world.mayDrop
import gups.core.world.mayEnter
import gups.core.world.mayGive
import gups.core.world.mayPut
import gups.core.world.mayRemove
import gups.core.world.mayWear
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread

/**
 * Asking whether an actor may do something, as the command or verb would refuse it. Each query
 * returns the refusal message or None and changes nothing, so fast-lane commands may call them.
 */
abstract class CtxRestrictions
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxEquipment(frame, owner, hc, extras) {
  private fun carried(
    actor: Any,
    item: Any,
    verb: String,
  ): Pair<gups.core.model.Actor, gups.core.model.Item> {
    val a = actorArg(actor, "$verb(actor)")
    val i = itemArg(item, "$verb(item)")
    if (i.location != ItemLocation.Carried(a.id))
      fail("$verb(item): ${a.name} doesn't carry ${i.name}")
    return a to i
  }

  @StarlarkMethod(
    name = "may_wear",
    doc =
      "Asks whether the actor may put on an item they carry, as wear (or wield) would: the slot" +
        " choice, room, the wear restriction, and the remove restriction on the one item it would" +
        " take off. Changes nothing. Returns the refusal message, or None.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who would put the item on."),
        Param(name = "item", named = true, doc = "An item the actor carries."),
        Param(
          name = "wield",
          named = true,
          defaultValue = "False",
          doc = "Ask about wielding it, as the wield command would.",
        ),
      ],
  )
  fun mayWear(actor: Any, item: Any, wield: Any): Any =
    verb("may_wear") {
      val (a, i) = carried(actor, item, "may_wear")
      world.mayWear(a, i, bool(wield, "may_wear(wield)")) ?: Starlark.NONE
    }

  @StarlarkMethod(
    name = "may_remove",
    doc =
      "Asks whether the actor may take off an item they wear: the remove restriction. Changes" +
        " nothing. Returns the refusal message, or None.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who would take the item off."),
        Param(name = "item", named = true, doc = "An item the actor wears."),
      ],
  )
  fun mayRemove(actor: Any, item: Any): Any =
    verb("may_remove") {
      val a = actorArg(actor, "may_remove(actor)")
      val i = itemArg(item, "may_remove(item)")
      val at = i.location
      if (at !is ItemLocation.Equipped || at.actor != a.id) {
        fail("may_remove(item): ${a.name} doesn't wear ${i.name}")
      }
      world.mayRemove(a, i) ?: Starlark.NONE
    }

  @StarlarkMethod(
    name = "may_drop",
    doc =
      "Asks whether the actor may drop an item they carry: the drop restriction. Changes nothing" +
        " and runs no hook. Returns the refusal message, or None.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who would drop the item."),
        Param(name = "item", named = true, doc = "An item the actor carries."),
      ],
  )
  fun mayDrop(actor: Any, item: Any): Any =
    verb("may_drop") {
      val (a, i) = carried(actor, item, "may_drop")
      world.mayDrop(a, i) ?: Starlark.NONE
    }

  @StarlarkMethod(
    name = "may_give",
    doc =
      "Asks whether the actor may give an item they carry to another actor, as the give command" +
        " would: the receiver must be in the actor's room and not the actor, and the give" +
        " restriction. Says nothing about what the receiver can carry. Changes nothing and runs" +
        " no hook. Returns the refusal message, or None.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who would hand the item over."),
        Param(name = "item", named = true, doc = "An item the actor carries."),
        Param(name = "to", named = true, doc = "Actor who would receive it."),
      ],
  )
  fun mayGive(actor: Any, item: Any, to: Any): Any =
    verb("may_give") {
      val (a, i) = carried(actor, item, "may_give")
      world.mayGive(a, i, actorArg(to, "may_give(to)")) ?: Starlark.NONE
    }

  @StarlarkMethod(
    name = "may_put",
    doc =
      "Asks whether the actor may put an item they carry into a container, as the put command" +
        " would: not the container itself, which must be open, and the put restriction. Says" +
        " nothing about whether it fits. Changes nothing and runs no hook. Returns the refusal" +
        " message, or None.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who would put the item away."),
        Param(name = "item", named = true, doc = "An item the actor carries."),
        Param(name = "container", named = true, doc = "A container item."),
      ],
  )
  fun mayPut(actor: Any, item: Any, container: Any): Any =
    verb("may_put") {
      val (a, i) = carried(actor, item, "may_put")
      val box = itemArg(container, "may_put(container)")
      if (box.container == null) fail("may_put(container): ${box.name} is not a container")
      world.mayPut(a, i, box) ?: Starlark.NONE
    }

  @StarlarkMethod(
    name = "may_enter",
    doc =
      "Asks whether the actor may go to a room: the enter restriction, with no direction, from" +
        " the room the actor is in. Hooks such as before_enter can't be asked, so teleport can" +
        " still be vetoed. Changes nothing. Returns the refusal message, or None.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who would go."),
        Param(name = "room", named = true, doc = "Destination room or room label."),
      ],
    useStarlarkThread = true,
  )
  fun mayEnter(actor: Any, room: Any, thread: StarlarkThread): Any =
    verb("may_enter") {
      val a = actorArg(actor, "may_enter(actor)")
      world.mayEnter(a, roomArg(room, "may_enter(room)", thread)) ?: Starlark.NONE
    }
}
