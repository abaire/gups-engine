package gups.script

import gups.core.model.Entity
import gups.core.model.ItemLocation
import gups.core.world.HookContext
import gups.core.world.ITEM_NAME_MAX
import gups.core.world.addItemTag
import gups.core.world.destroy
import gups.core.world.isShortLine
import gups.core.world.moveItem
import gups.core.world.removeItemTag
import gups.core.world.setCharges
import gups.core.world.setItemName
import gups.core.world.spawnItem
import gups.core.world.stack
import gups.core.world.transformItem
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread

/** Spawning and moving items. */
abstract class CtxItems
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxAffects(frame, owner, hc, extras) {
  @StarlarkMethod(
    name = "spawn",
    doc =
      "Spawns a mob (in a room) or an item (in a room, on an actor, or in a container). Returns its view, or a list when count > 1 makes several.",
    parameters =
      [
        Param(name = "label", named = true, doc = "Mob or item prototype label."),
        Param(name = "at", named = true, doc = "Room or room label, actor, or container item."),
        Param(
          name = "index",
          named = true,
          defaultValue = "0",
          doc = "Track index when spawning into a room.",
        ),
        Param(
          name = "count",
          named = true,
          defaultValue = "1",
          doc = "Number to spawn; more than 1 returns a list.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun spawn(label: Any, at: Any, index: Any, count: Any, thread: StarlarkThread): Any =
    verb("spawn", "$label") {
      trackIndex("spawn", index)
      val l = label(str(label, "spawn(label)"), pkgOf(thread), "spawn(label)")
      val n = int(count, "spawn(count)")
      if (n < 1) fail("spawn(count): must be at least 1")
      val made =
        when {
          l in world.protos.mobs -> {
            val room = roomArg(at, "spawn(at)", thread)
            val i = int(index, "spawn(index)")
            if (i !in 0 until room.trackSize) fail("spawn(index): $i is outside ${room.label}")
            List(n) { world.spawnMob(l, room, i) }
          }
          l in world.protos.items -> {
            val loc = location(at, index, "spawn(at)", thread)
            if (world.protos.item(l).stackable) listOf(world.spawnItem(l, loc, n))
            else List(n) { world.spawnItem(l, loc) }
          }
          else -> fail("spawn(label): there is no mob or item $l")
        }
      if (made.size == 1) v(made[0]) else sList(made.map { v(it) })
    }

  @StarlarkMethod(
    name = "give",
    doc = "Puts an item (a view, or a label to spawn) into an actor's inventory.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who receives the item."),
        Param(
          name = "label_or_item",
          named = true,
          doc = "Item view to move, or item prototype label to spawn.",
        ),
        Param(
          name = "count",
          named = true,
          defaultValue = "None",
          doc =
            "Number of items to spawn or move; defaults to 1 for a label, or the whole stack for an item.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun give(actor: Any, labelOrItem: Any, count: Any, thread: StarlarkThread): Any =
    verb("give") {
      val a = actorArg(actor, "give(actor)")
      val to = ItemLocation.Carried(a.id)
      if (labelOrItem is ItemView) {
        val i = itemArg(labelOrItem, "give(item)")
        val n = intOrNull(count, "give(count)") ?: i.count
        if (n !in 1..i.count) fail("give(count): must be between 1 and ${i.count}")
        return@verb v(world.moveItem(i, to, n))
      }
      val l = label(str(labelOrItem, "give(label_or_item)"), pkgOf(thread), "give(label)")
      if (l !in world.protos.items) fail("give(label): there is no item $l")
      val n = intOrNull(count, "give(count)") ?: 1
      if (n < 1) fail("give(count): must be at least 1")
      val made =
        if (world.protos.item(l).stackable) listOf(world.spawnItem(l, to, n))
        else List(n) { world.spawnItem(l, to) }
      if (made.size == 1) v(made[0]) else sList(made.map { v(it) })
    }

  @StarlarkMethod(
    name = "move_item",
    doc =
      "Moves an item, or count of a stack, to a room (at index), an actor's inventory, or a container. Returns the moved item.",
    parameters =
      [
        Param(name = "item", named = true, doc = "Item to move."),
        Param(name = "to", named = true, doc = "Room or room label, actor, or container item."),
        Param(
          name = "index",
          named = true,
          defaultValue = "0",
          doc = "Track index when moving to a room.",
        ),
        Param(
          name = "count",
          named = true,
          defaultValue = "None",
          doc = "Number of items of the stack to move, or None for the whole stack.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun moveItem(item: Any, to: Any, index: Any, count: Any, thread: StarlarkThread): Any =
    verb("move_item") {
      trackIndex("move_item", index)
      val i = itemArg(item, "move_item(item)")
      val n = intOrNull(count, "move_item(count)") ?: i.count
      if (n !in 1..i.count) fail("move_item(count): must be between 1 and ${i.count}")
      v(world.moveItem(i, location(to, index, "move_item(to)", thread), n))
    }

  @StarlarkMethod(
    name = "destroy",
    doc = "Removes an item and everything inside it, or count of a stack.",
    parameters =
      [
        Param(name = "item", named = true, doc = "Item to remove from the world."),
        Param(
          name = "count",
          named = true,
          defaultValue = "None",
          doc = "Number of items of the stack to remove, or None for the whole stack.",
        ),
      ],
  )
  fun destroy(item: Any, count: Any): Any =
    verb("destroy") {
      val i = itemArg(item, "destroy(item)")
      val n = intOrNull(count, "destroy(count)") ?: i.count
      if (n !in 1..i.count) fail("destroy(count): must be between 1 and ${i.count}")
      world.destroy(i, n)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "merge_stack",
    doc =
      "Merges an item into an identical stack in the same place; returns the stack that holds it.",
    parameters = [Param(name = "item", named = true, doc = "Item to merge.")],
  )
  fun mergeStack(item: Any): Any =
    verb("merge_stack") { v(world.stack(itemArg(item, "merge_stack(item)"))) }

  @StarlarkMethod(
    name = "set_charges",
    doc = "Sets an item's remaining uses, from 0 to its prototype's charges.",
    parameters =
      [
        Param(name = "item", named = true, doc = "Item with charges."),
        Param(name = "charges", named = true, doc = "Uses left; 0 doesn't run the empty action."),
      ],
  )
  fun setCharges(item: Any, charges: Any): Any =
    verb("set_charges") {
      val i = itemArg(item, "set_charges(item)")
      val n = int(charges, "set_charges(charges)")
      val max = i.proto.charges ?: fail("set_charges: ${i.name} has unlimited uses")
      if (i.proto.stackable) fail("set_charges: ${i.name} is stackable")
      if (n !in 0..max) fail("set_charges: charges must be between 0 and $max, got $n")
      world.setCharges(i, n)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "set_item_name",
    doc =
      "Renames an item: name, a single line of 1 to 80 characters, replaces its prototype's name," +
        " or None restores it. Keywords don't change. Returns whether the name changed.",
    parameters =
      [
        Param(name = "item", named = true, doc = "Item to rename."),
        Param(name = "name", named = true, doc = "The new name, or None for the prototype's."),
      ],
  )
  fun setItemName(item: Any, name: Any): Any =
    verb("set_item_name") {
      world.setItemName(itemArg(item, "set_item_name(item)"), itemName(name))
    }

  private fun itemName(name: Any): String? {
    if (isNone(name)) return null
    val n = str(name, "set_item_name(name)")
    if (!isShortLine(n, ITEM_NAME_MAX))
      fail(
        "set_item_name(name): must be a non-empty single line of at most $ITEM_NAME_MAX characters"
      )
    return n
  }

  @StarlarkMethod(
    name = "add_tag",
    doc = "Adds a tag to an item's effective tags; True if it was not already present.",
    parameters =
      [
        Param(name = "item", named = true, doc = "Item to tag."),
        Param(name = "tag", named = true, doc = "Tag to add."),
      ],
  )
  fun addTag(item: Any, tag: Any): Any =
    verb("add_tag") {
      val i = itemArg(item, "add_tag(item)")
      val t = str(tag, "add_tag(tag)")
      world.addItemTag(i, t)
    }

  @StarlarkMethod(
    name = "remove_tag",
    doc = "Removes a tag from an item's effective tags; True if it was present.",
    parameters =
      [
        Param(name = "item", named = true, doc = "Item to untag."),
        Param(name = "tag", named = true, doc = "Tag to remove."),
      ],
  )
  fun removeTag(item: Any, tag: Any): Any =
    verb("remove_tag") {
      val i = itemArg(item, "remove_tag(item)")
      val t = str(tag, "remove_tag(tag)")
      world.removeItemTag(i, t)
    }

  @StarlarkMethod(
    name = "transform",
    doc = "Swaps an item's prototype in place, keeping instance attributes, affects and tags.",
    parameters =
      [
        Param(name = "item", named = true, doc = "Item to transform."),
        Param(name = "label", named = true, doc = "Target item prototype label."),
      ],
    useStarlarkThread = true,
  )
  fun transform(item: Any, label: Any, thread: StarlarkThread): Any =
    verb("transform") {
      val i = itemArg(item, "transform(item)")
      val l = label(str(label, "transform(label)"), pkgOf(thread), "transform(label)")
      if (l !in world.protos.items) fail("transform(label): there is no item $l")
      world.transformItem(i, l)
      Starlark.NONE
    }
}
