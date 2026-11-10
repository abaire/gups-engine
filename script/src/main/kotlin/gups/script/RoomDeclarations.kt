package gups.script

import gups.core.Label
import gups.core.model.ExitProto
import gups.core.model.RoomProto
import gups.core.model.SpawnRule
import gups.core.model.TrackSize
import gups.core.world.Features
import net.starlark.java.eval.StarlarkThread

private val REGION_NAME_REGEX = Regex("[a-z0-9_]+")

/** Turns the validated arguments of a `room(...)` call into a [RoomProto]. */
internal fun parseRoom(
  d: ParsedDecl,
  ctx: LoadCtx,
  thread: StarlarkThread,
  features: Features,
): RoomProto {
  val id = declName("room", d, ctx)
  val pkg = ctx.pkg.path
  val size = d.choice("size")?.let { TrackSize.valueOf(it.uppercase()) } ?: TrackSize.NORMAL
  val flags = d.choiceList("flags").toSet()
  val exits = roomExits(d, ctx, thread)
  val spawns = roomSpawns(d, ctx, thread, size)
  val proto =
    RoomProto(
      label = Label("//$pkg:$id"),
      name = d.str("name") ?: displayName(id),
      desc = d.str("desc") ?: "",
      size = size,
      regions = d.strList("regions").toSet(),
      tags = parseTags(d),
      flags = flags,
      exits = exits,
      spawns = spawns,
      commands = commands(d["commands"], d.what("commands"), pkg),
      modifiers = modifiers(d["modifiers"], d.what("modifiers"), features),
      version = version(d),
    )
  for (r in proto.regions) {
    if (!REGION_NAME_REGEX.matches(r)) fail("room(regions): '$r' is not a valid region name")
  }
  return proto
}

private fun roomExits(d: ParsedDecl, ctx: LoadCtx, thread: StarlarkThread): List<ExitProto> {
  val exits =
    d.list("exits").map {
      (it as? ExitDecl)?.proto ?: fail("room(exits): expected exit(...), got ${typeName(it)}")
    }
  exits
    .groupBy { it.direction }
    .forEach { (dir, same) -> if (same.size > 1) fail("room(exits): two exits lead $dir") }
  for (e in exits) {
    ctx.ref(e.to, RefKind.ROOM, thread)
    e.door?.key?.let { ctx.ref(it, RefKind.ITEM, thread) }
  }
  return exits
}

private fun roomSpawns(
  d: ParsedDecl,
  ctx: LoadCtx,
  thread: StarlarkThread,
  size: TrackSize,
): List<SpawnRule> {
  val spawns =
    d.list("spawns").map {
      (it as? SpawnDecl)?.rule ?: fail("room(spawns): expected spawn(...), got ${typeName(it)}")
    }
  for (s in spawns) {
    ctx.ref(s.label, RefKind.MOB, thread)
    if (s.atIndex !in 0 until size.slots) {
      fail("room(spawns): at_index ${s.atIndex} is outside this ${size.name.lowercase()} room")
    }
  }
  return spawns
}
