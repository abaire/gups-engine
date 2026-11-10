package gups.core.world

import gups.core.Label
import gups.core.Role
import gups.core.model.Entity
import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.model.Room

internal const val NOT_YOURS = "That isn't one of your packages."

/**
 * The builder and instructor `@` commands that need only the world. Builders are confined to their
 * packages; instructors may use them anywhere. `@trace` and `@eval` come from the script host, and
 * the account commands from `auth`.
 */
internal object Tools {
  fun all(): List<CommandDef> = BuilderTools.all() + InstructorTools.all()
}

/** A staff command: [help] carries the usage line. */
internal fun staffCommand(
  name: String,
  role: Role,
  help: String,
  run: (CommandCall) -> Unit,
): CommandDef =
  FastCommand(name, role = role, category = CommandCategory.SPECIAL, help = help, run = run)

/** `//pkg:name`, or `:name` in the package of [p]'s room. */
internal fun labelArg(text: String, p: Player): Label? =
  runCatching { Label.parse(text, p.room.label.pkg) }.getOrNull()

/**
 * The package a builder needs to own to inspect [e]: a room's own, a mob's or item's prototype
 * package, and for players and engine items (corpses) the package of their room.
 */
fun World.packageOf(e: Entity): String? =
  when {
    e is Room -> e.label.pkg
    e is Player || e.protoLabel.pkg == "engine" -> roomOfEntity(e)?.label?.pkg
    else -> e.protoLabel.pkg
  }

/** Where an item is, in words. */
internal fun World.describeLocation(loc: ItemLocation): String =
  when (loc) {
    is ItemLocation.InRoom ->
      "in ${(entity(loc.room) as? Room)?.label ?: "room #${loc.room.v}"} at ${loc.trackIndex}"
    is ItemLocation.Carried -> "carried by ${actor(loc.actor)?.name ?: "#${loc.actor.v}"}"
    is ItemLocation.Equipped ->
      "worn by ${actor(loc.actor)?.name ?: "#${loc.actor.v}"} (${loc.slot})"
    is ItemLocation.Contained -> "inside ${item(loc.container)?.name ?: "#${loc.container.v}"}"
  }
