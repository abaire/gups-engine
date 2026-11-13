package gups.script

import gups.core.model.Entity
import gups.core.model.ItemLocation
import gups.core.world.HookContext
import gups.core.world.removeWornItem
import gups.core.world.wearItem
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark

/** Putting equipment on and taking it off, as the wear, wield and remove commands do. */
abstract class CtxEquipment
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxItems(frame, owner, hc, extras) {
  @StarlarkMethod(
    name = "wear",
    doc =
      "Puts on an item the actor carries as the wear command (or wield) would, asking the same" +
        " restrictions and taking off at most one worn item, which goes back to the inventory." +
        " Sends nothing. Returns the refusal message, or None once the item is worn.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who puts the item on."),
        Param(name = "item", named = true, doc = "An item the actor carries."),
        Param(
          name = "wield",
          named = true,
          defaultValue = "False",
          doc = "Wield it, as the wield command does.",
        ),
      ],
  )
  fun wear(actor: Any, item: Any, wield: Any): Any =
    verb("wear") {
      val a = actorArg(actor, "wear(actor)")
      val i = itemArg(item, "wear(item)")
      if (i.location != ItemLocation.Carried(a.id))
        fail("wear(item): ${a.name} doesn't carry ${i.name}")
      world.wearItem(a, i, bool(wield, "wear(wield)")) ?: Starlark.NONE
    }

  @StarlarkMethod(
    name = "remove_worn",
    doc =
      "Takes off an item the actor wears, back into the inventory, after asking the remove" +
        " restriction. Sends nothing. Returns the refusal message, or None.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who takes the item off."),
        Param(name = "item", named = true, doc = "An item the actor wears."),
      ],
  )
  fun removeWorn(actor: Any, item: Any): Any =
    verb("remove_worn") {
      val a = actorArg(actor, "remove_worn(actor)")
      val i = itemArg(item, "remove_worn(item)")
      val at = i.location
      if (at !is ItemLocation.Equipped || at.actor != a.id) {
        fail("remove_worn(item): ${a.name} doesn't wear ${i.name}")
      }
      world.removeWornItem(a, i) ?: Starlark.NONE
    }
}
