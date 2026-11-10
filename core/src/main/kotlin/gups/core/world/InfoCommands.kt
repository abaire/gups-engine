package gups.core.world

import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Condition
import gups.core.model.DoorState
import gups.core.model.Item
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Room

/** The read-only commands that report on the room, the character and the world. */
internal object InfoCommands {
  fun look(c: CommandCall) {
    val w = c.world
    if (c.args.isEmpty()) return w.send(c.actor, w.describeRoom(c.actor))
    val actor = w.findActor(c.actor, c.args)
    if (actor != null) return lookAtActor(c, actor)
    val item =
      w.findItem(c.actor, c.args, inventory = true, ground = true)
        ?: return w.tell(c.actor, KernelMsg.DONT_SEE)
    lookAtItem(c, item)
  }

  private fun lookAtActor(c: CommandCall, actor: Actor) {
    val w = c.world
    val desc = (actor as? Mob)?.proto?.desc?.ifEmpty { null }
    w.send(c.actor, desc ?: w.msg(KernelMsg.LOOK_NOTHING_SPECIAL, "name" to actor.name))
    w.send(
      c.actor,
      w.msg(KernelMsg.LOOK_HEALTH, "name" to actor.name, "health" to w.healthWord(actor)),
    )
  }

  private fun lookAtItem(c: CommandCall, item: Item) {
    val w = c.world
    w.send(
      c.actor,
      item.proto.desc.ifEmpty { w.msg(KernelMsg.LOOK_NOTHING_SPECIAL, "name" to item.name) },
    )
    val box = item.container ?: return
    if (box.state != DoorState.OPEN) return w.tell(c.actor, KernelMsg.LOOK_CLOSED)
    val inside = item.contents
    if (inside.isEmpty()) w.tell(c.actor, KernelMsg.LOOK_EMPTY)
    else
      w.tell(
        c.actor,
        KernelMsg.LOOK_CONTAINS,
        "items" to inside.joinToString(", ") { it.describe() },
      )
  }

  /**
   * `score`: the name, the effective stats as `<label> <value>` and vitals as `<label> cur/max` in
   * schema order, and the effective resists. With `leveling`, the level and XP, followed on the
   * next line by the ready line while levels are pending in either level mode. While grouped, the
   * group leader and member count. Then each known creation choice in choice order and the carried
   * traits in name order.
   */
  fun score(c: CommandCall) {
    val a = c.actor
    val w = c.world
    val lines = mutableListOf(a.name)
    lines += attributeLines(w, a)
    if (Feature.LEVELING in w.features) lines += levelLines(w, a)
    if (a is Player) {
      lines += groupLines(w, a)
      lines += choiceLines(w, a)
    }
    if (a.traits.isNotEmpty()) {
      lines += w.msg(KernelMsg.SCORE_TRAITS, "traits" to a.traits.joinToString(", ") { it.label })
    }
    w.send(a, lines.joinToString("\n"))
  }

  private fun attributeLines(w: World, a: Actor): List<String> {
    val schema = w.schema
    val lines = mutableListOf<String>()
    if (schema.stats.isNotEmpty())
      lines += schema.stats.joinToString("  ") { "${it.label} ${a.stat(it.name)}" }
    if (schema.vitals.isNotEmpty())
      lines +=
        schema.vitals.joinToString("  ") { "${it.label} ${a.current(it.name)}/${a.max(it.name)}" }
    if (schema.damageTypes.isNotEmpty())
      lines +=
        w.msg(
          KernelMsg.SCORE_RESISTS,
          "resists" to schema.damageTypes.joinToString("  ") { "$it ${a.effectiveResist(it)}" },
        )
    return lines
  }

  private fun levelLines(w: World, a: Actor): List<String> {
    val lines = mutableListOf(w.msg(LevelingMsg.SCORE_LEVEL, "level" to a.level, "xp" to a.xp))
    if (w.pendingLevels(a) > 0) lines += w.msg(LevelingMsg.SCORE_READY)
    return lines
  }

  private fun groupLines(w: World, player: Player): List<String> {
    val group = w.groupOf(player) ?: return emptyList()
    return listOf(
      w.msg(
        KernelMsg.SCORE_GROUP,
        "leader" to group.leader.name,
        "count" to w.groupMembers(player).size,
      )
    )
  }

  private fun choiceLines(w: World, player: Player): List<String> {
    val creation = w.creation ?: return emptyList()
    return creation.choices.mapNotNull { choice ->
      val optId = player.choices[choice.id] ?: return@mapNotNull null
      val opt = choice.options.firstOrNull { it.id == optId } ?: return@mapNotNull null
      w.msg(KernelMsg.SCORE_CHOICE, "title" to choice.title, "label" to opt.label)
    }
  }

  fun inventory(c: CommandCall) {
    // Keys live on the keyring, so they don't clutter the inventory.
    val items = c.actor.inventory.filterNot { it.onKeyring }
    listItems(c, items, KernelMsg.INVENTORY_EMPTY, KernelMsg.INVENTORY_LIST)
  }

  fun keyring(c: CommandCall) {
    listItems(
      c,
      c.actor.inventory.filter { it.onKeyring },
      KernelMsg.KEYRING_EMPTY,
      KernelMsg.KEYRING_LIST,
    )
  }

  private fun listItems(c: CommandCall, items: List<Item>, empty: Msg, list: Msg) {
    val w = c.world
    w.send(
      c.actor,
      if (items.isEmpty()) w.msg(empty)
      else
        w.msg(
          list,
          "items" to
            items.joinToString("\n") { w.msg(KernelMsg.INVENTORY_ITEM, "item" to it.describe()) },
        ),
    )
  }

  fun equipment(c: CommandCall) {
    val w = c.world
    val actor = c.actor
    val entries = equipmentEntries(w, actor)
    w.send(
      actor,
      if (entries.isEmpty()) w.msg(KernelMsg.EQUIPMENT_EMPTY)
      else
        w.msg(
          KernelMsg.EQUIPMENT_LIST,
          "items" to
            entries.joinToString("\n") { (slot, item) ->
              val msg =
                if (actor.isActiveSlot(slot)) KernelMsg.EQUIPMENT_ITEM
                else KernelMsg.EQUIPMENT_INACTIVE
              w.msg(msg, "slot" to slot, "item" to item.name)
            },
        ),
    )
  }

  /** The actor's slots in order, then any other occupied slots by name. */
  private fun equipmentEntries(w: World, actor: Actor): List<Pair<String, Item>> {
    val slots = w.actorSlots(actor)
    val inSlotOrder = slots.mapNotNull { slot -> actor.equipped(slot)?.let { slot to it } }
    val others =
      actor.equipment
        .filterKeys { it !in slots }
        .entries
        .sortedBy { it.key }
        .map { it.key to it.value }
    return inSlotOrder + others
  }

  fun who(c: CommandCall) {
    val w = c.world
    val lines = w.players.map { whoLine(w, it) }
    w.tell(c.actor, KernelMsg.WHO_LIST, "players" to lines.joinToString("\n"))
  }

  private fun whoLine(w: World, p: Player): String {
    val level =
      if (Feature.LEVELING in w.features) w.msg(LevelingMsg.WHO_LEVEL, "level" to p.level) else ""
    val group = w.groupOf(p)?.let { w.msg(KernelMsg.WHO_GROUP, "leader" to it.leader.name) } ?: ""
    return w.msg(
      if (p.session == null) KernelMsg.WHO_LINKDEAD else KernelMsg.WHO_PLAYER,
      "level" to level,
      "name" to p.name,
      "group" to group,
    )
  }
}

/**
 * How [a] looks to others: by its condition, else by its lowest vital as a share of its maximum.
 */
internal fun World.healthWord(a: Actor): String {
  when (a.condition) {
    Condition.DEAD -> return msg(KernelMsg.HEALTH_DEAD)
    Condition.INCAPACITATED -> return msg(KernelMsg.HEALTH_INCAPACITATED)
    Condition.OK -> Unit
  }
  val pct =
    a.vitalMap.entries
      .filter { a.max(it.key) > 0 }
      .minOfOrNull { it.value.current * 100 / a.max(it.key) } ?: 100
  return msg(
    when {
      pct >= 100 -> KernelMsg.HEALTH_EXCELLENT
      pct >= 75 -> KernelMsg.HEALTH_SCRATCHED
      pct >= 50 -> KernelMsg.HEALTH_WOUNDED
      pct >= 25 -> KernelMsg.HEALTH_HURT
      else -> KernelMsg.HEALTH_AWFUL
    }
  )
}

/** `look`: the room, its exits, and actors and items grouped by band when the world has a track. */
fun World.describeRoom(viewer: Actor): String {
  val room = viewer.room
  val lines = mutableListOf(room.name)
  if (room.proto.desc.isNotEmpty()) lines += room.proto.desc
  lines += exitsLine(room)
  val others = room.actors.filter { it !== viewer }
  lines +=
    if (Feature.TRACK in features) bandedLines(viewer, room, others) else plainLines(room, others)
  return lines.joinToString("\n")
}

private fun World.exitsLine(room: Room): String {
  val exits =
    room.exits.values.joinToString(", ") { e ->
      e.door
        ?.takeIf { it.state != DoorState.OPEN }
        ?.let {
          msg(
            KernelMsg.LOOK_DOOR,
            "direction" to e.direction.name,
            "state" to KernelMsg.stateWord(it.state),
          )
        } ?: e.direction.name
    }
  return if (exits.isEmpty()) msg(KernelMsg.LOOK_NO_EXITS)
  else msg(KernelMsg.LOOK_EXITS, "exits" to exits)
}

/** Actors and items as two flat lists, for a world with no track. */
private fun World.plainLines(room: Room, others: List<Actor>): List<String> {
  val lines = mutableListOf<String>()
  if (others.isNotEmpty())
    lines += msg(KernelMsg.LOOK_HERE_ACTORS, "list" to others.joinToString(", ") { it.name })
  if (room.items.isNotEmpty())
    lines +=
      msg(KernelMsg.LOOK_HERE_ITEMS, "list" to room.items.joinToString(", ") { it.describe() })
  return lines
}

/** Actors and items grouped by their distance band from [viewer]. */
private fun World.bandedLines(viewer: Actor, room: Room, others: List<Actor>): List<String> {
  val words =
    mapOf(
      Band.MELEE to msg(KernelMsg.LOOK_WHERE_MELEE),
      Band.NEAR to msg(KernelMsg.LOOK_WHERE_NEAR),
      Band.FAR to msg(KernelMsg.LOOK_WHERE_FAR),
    )
  val ground =
    mapOf(
      Band.MELEE to msg(KernelMsg.LOOK_GROUND_MELEE),
      Band.NEAR to msg(KernelMsg.LOOK_GROUND_NEAR),
      Band.FAR to msg(KernelMsg.LOOK_GROUND_FAR),
    )
  val actorsByBand = others.groupBy { Band.between(viewer.trackIndex, it.trackIndex) }
  val itemsByBand = room.items.groupBy { Band.between(viewer.trackIndex, it.groundIndex ?: 0) }
  val lines = mutableListOf<String>()
  for (band in Band.entries) {
    actorsByBand[band]?.let { list ->
      lines +=
        msg(
          KernelMsg.LOOK_ACTORS,
          "where" to words.getValue(band),
          "list" to list.joinToString(", ") { it.name },
        )
    }
  }
  for (band in Band.entries) {
    itemsByBand[band]?.let { list ->
      lines +=
        msg(
          KernelMsg.LOOK_ITEMS,
          "where" to ground.getValue(band),
          "list" to list.joinToString(", ") { it.describe() },
        )
    }
  }
  return lines
}
