package gups.core.world

import gups.core.ACTION_QUEUE_MAX
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Channel
import gups.core.model.ContainerState
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.QueuedAction
import gups.core.model.RestrictionPoint
import gups.core.model.RestrictionRequest
import gups.core.model.Room
import gups.core.model.SkillProto
import gups.core.model.TAG_REGEX

// Engine-validated verbs used by the script host's `ctx` that have no player
// command of their own. Each is individually atomic and invariant-preserving.

/** The `say` verb: the room hears it and `on_say` fires. */
fun World.say(speaker: Actor, text: String) {
  tell(speaker, KernelMsg.SAY_SELF, "text" to text)
  val heard =
    tellPerceivers(
      speaker.room,
      "say",
      speaker,
      mapOf(
        Channel.SOUND to msg(KernelMsg.SAY_ROOM, "actor" to speaker.name.cap(), "text" to text)
      ),
      eventText = text,
      exclude = arrayOf(speaker),
    )
  channel("say", speaker, text, heard.filterValues { it == Channel.SOUND }.keys + speaker)
  dispatch(HookName.ON_SAY, null, speaker.room, HookContext(actor = speaker, text = text))
  perceive("say", Channel.SOUND, speaker.room, speaker, text)
}

/** The `emote` verb: everyone in the room, the actor included, sees "Name text". */
fun World.emote(actor: Actor, text: String) {
  val line = msg(KernelMsg.EMOTE, "actor" to actor.name.cap(), "text" to text)
  send(actor, line)
  tellPerceivers(
    actor.room,
    "emote",
    actor,
    mapOf(Channel.SIGHT to line),
    eventText = text,
    exclude = arrayOf(actor),
  )
  perceive("emote", Channel.SIGHT, actor.room, actor, text)
}

/**
 * Sets the door on [room]'s exit [dir], and on the matching door on the other side, so both sides
 * always agree. Silent: callers do their own messaging.
 */
fun World.setDoor(room: Room, dir: Direction, state: DoorState) {
  val exit = requireNotNull(room.exits[dir]) { "${room.label} has no exit $dir" }
  val door = requireNotNull(exit.door) { "${room.label} exit $dir has no door" }
  door.state = state
  room.dirty = true
  reverseExit(room, exit)?.let { (other, back) ->
    back.door!!.state = state
    other.dirty = true
  }
  graphChanged()
}

/**
 * Shortest walk from [from] to [to] over passable exits, as exit directions; empty if they are the
 * same room, null if [to] can't be reached. Breadth-first over the whole room graph in exit
 * declaration order, so ties resolve the same way every time. Results are cached per `(from, to)`
 * until a door, exit or reload changes the graph.
 */
fun World.pathTo(from: Room, to: Room): List<Direction>? {
  if (from === to) return emptyList()
  val key = from.id to to.id
  if (key in pathCache) return pathCache[key]
  return searchPath(from, to).also { pathCache[key] = it }
}

private fun World.searchPath(from: Room, to: Room): List<Direction>? {
  val via = HashMap<Room, Pair<Room, Direction>>()
  val queue = ArrayDeque(listOf(from))
  while (queue.isNotEmpty()) {
    val room = queue.removeFirst()
    for (exit in room.exits.values) {
      if (!exit.passable) continue
      val next = room(exit.to)
      if (next === from || next in via) continue
      via[next] = room to exit.direction
      if (next === to) {
        val path = ArrayDeque<Direction>()
        var at = to
        while (at !== from) {
          val (prev, dir) = via.getValue(at)
          path.addFirst(dir)
          at = prev
        }
        return path.toList()
      }
      queue.addLast(next)
    }
  }
  return null
}

// ---- Mob actions -----------------------------------------------------------------------------
// Each enqueues a tick-lane action, exactly as if the mob had typed it, and returns false if
// the queue is full.

/** Enqueues a ready-made [action] for [actor], subject to the queue cap. */
fun World.enqueue(actor: Actor, verb: String, action: Action): Boolean {
  if (actor.actionQueue.size >= ACTION_QUEUE_MAX) {
    tell(actor, KernelMsg.QUEUE_FULL)
    return false
  }
  actor.actionQueue.addLast(QueuedAction(TickCommand(verb) { action }, verb, ""))
  return true
}

fun World.actMove(mob: Mob, dir: Direction): Boolean = enqueue(mob, dir.name, MoveAction(dir))

fun World.actAttack(mob: Mob, target: Actor): Boolean =
  enqueue(mob, commands.nameOf("kill"), KillAction(target))

/** Casts [skill], which the mob must know, at [target] (or the skill's default target). */
fun World.actCast(mob: Mob, skill: SkillProto, target: Actor?): Boolean {
  require(skill.label in mob.skills) { "${mob.name} doesn't know ${skill.label}" }
  return enqueue(mob, commands.nameOf("cast"), CastAction(skill, null, target))
}

fun World.actAdvance(mob: Mob, target: Actor): Boolean =
  enqueue(mob, commands.nameOf("advance"), AdvanceAction(target))

fun World.actRetreat(mob: Mob, from: Actor?): Boolean =
  enqueue(mob, commands.nameOf("retreat"), RetreatAction(from))

fun World.restrictionRefusal(point: RestrictionPoint, r: RestrictionRequest): String? {
  val list = restrictions[point] ?: return null
  for (check in list) {
    val refusal =
      try {
        check.check(this, r)
      } catch (e: Exception) {
        diagnose("plugin", "restriction check failed: ${e.message}", e)
        null
      }
    if (refusal != null) return refusal
  }
  return null
}

/**
 * Why [changes] can't be applied to [player]'s answers, checked as creation checks them, or null.
 * Unknown choice or option ids are a programming error and throw.
 */
fun World.choicesRefusal(player: Player, changes: Map<String, String?>): String? {
  val provider =
    creation
      ?: if (changes.isEmpty()) return null
      else throw IllegalArgumentException("World has no creation provider")
  for ((choiceId, optId) in changes) {
    val choice =
      provider.choices.firstOrNull { it.id == choiceId }
        ?: throw IllegalArgumentException("Unknown choice '$choiceId'")
    if (optId != null && choice.options.none { it.id == optId }) {
      throw IllegalArgumentException("Unknown option '$optId' for choice '$choiceId'")
    }
  }
  val newAnswers = LinkedHashMap(player.choices)
  for ((cId, optId) in changes) {
    if (optId == null) newAnswers.remove(cId) else newAnswers[cId] = optId
  }
  val answersSoFar = LinkedHashMap<String, String>()
  for (c in provider.choices) {
    val ans = newAnswers[c.id] ?: continue
    val avail =
      try {
        provider.available(this, c.id, answersSoFar)
      } catch (e: Exception) {
        diagnose("plugin", "creation provider available() failed: ${e.message}", e)
        return msg(LoginMsg.CREATION_PROVIDER_FAILED)
      }
    if (ans !in avail) {
      return "Option '$ans' is not available for choice '${c.id}'"
    }
    answersSoFar[c.id] = ans
  }
  val valRefusal =
    try {
      provider.validate(this, newAnswers)
    } catch (e: Exception) {
      diagnose("plugin", "creation provider validate() failed: ${e.message}", e)
      return msg(LoginMsg.CREATION_PROVIDER_FAILED)
    }
  return valRefusal
}

/**
 * The change function: applies [changes] (an option id, or null to remove the answer) to [player]'s
 * answers as one change, or returns why it can't. Never reruns `starting_stats`.
 */
fun World.setChoices(player: Player, changes: Map<String, String?>): String? {
  choicesRefusal(player, changes)?.let {
    return it
  }
  if (changes.isEmpty()) return null
  val newAnswers = LinkedHashMap(player.choices)
  for ((cId, optId) in changes) {
    if (optId == null) newAnswers.remove(cId) else newAnswers[cId] = optId
  }

  player.choices.clear()
  player.choices.putAll(newAnswers)
  player.recomputeTraits(this)

  recheckWornItems(player)
  requestImmediate("choices")
  return null
}

private fun World.recheckWornItems(player: Player) {
  for ((slot, item) in player.equipmentIndex.toList()) {
    val req =
      RestrictionRequest(point = RestrictionPoint.WEAR, actor = player, item = item, slot = slot)
    val refused = restrictionRefusal(RestrictionPoint.WEAR, req)
    if (refused != null) {
      moveItem(item, ItemLocation.Carried(player.id))
      send(player, refused)
    }
  }
}

fun World.grantTrait(actor: Actor, traitName: String): String? {
  if (traitName !in traits) throw IllegalArgumentException("Unknown trait '$traitName'")
  if (traitName in actor.grantedTraits) return "Trait '$traitName' already granted"
  actor.grantedTraits = actor.grantedTraits + traitName
  actor.recomputeTraits(this)
  if (actor is Player) {
    recheckWornItems(actor)
    requestImmediate("choices")
  } else {
    actor.dirty = true
  }
  return null
}

fun World.removeTrait(actor: Actor, traitName: String): String? {
  if (traitName !in traits) throw IllegalArgumentException("Unknown trait '$traitName'")
  if (traitName !in actor.grantedTraits) return "Trait '$traitName' was not granted directly"
  actor.grantedTraits = actor.grantedTraits - traitName
  actor.recomputeTraits(this)
  if (actor is Player) {
    recheckWornItems(actor)
    requestImmediate("choices")
  } else {
    actor.dirty = true
  }
  return null
}

fun World.setSkill(actor: Actor, skillLabel: Label, proficiency: Int): String? {
  check(Feature.SKILLS in features) { "Skills feature is disabled" }
  val proto =
    protos.skills[skillLabel] ?: throw IllegalArgumentException("Unknown skill '$skillLabel'")
  require(proficiency in 0..100) { "Proficiency must be 0-100, got $proficiency" }
  val req =
    RestrictionRequest(
      point = RestrictionPoint.LEARN,
      actor = actor,
      skill = proto,
      proficiency = proficiency,
    )
  val refused = restrictionRefusal(RestrictionPoint.LEARN, req)
  if (refused != null) return refused
  actor.skills[skillLabel] = proficiency
  actor.dirty = true
  return null
}

fun World.removeSkill(actor: Actor, skillLabel: Label): Boolean {
  check(Feature.SKILLS in features) { "Skills feature is disabled" }
  require(skillLabel in protos.skills) { "Unknown skill '$skillLabel'" }
  val removed = actor.skills.remove(skillLabel) != null
  if (removed) actor.dirty = true
  return removed
}

/** Adds [tag] to [item]'s effective tags; false if it already had it. */
fun World.addItemTag(item: Item, tag: String): Boolean {
  require(item.id in entities) { "$item is not in the world" }
  require(TAG_REGEX.matches(tag)) { "'$tag' is not a valid tag" }
  if (tag in item.tags) return false
  if (tag in item.removedTags) item.removedTags.remove(tag) else item.addedTags.add(tag)
  item.dirty = true
  custodian(item)?.let { refreshHoldings(it) }
  return true
}

/** Removes [tag] from [item]'s effective tags; false if it didn't have it. */
fun World.removeItemTag(item: Item, tag: String): Boolean {
  require(item.id in entities) { "$item is not in the world" }
  require(TAG_REGEX.matches(tag)) { "'$tag' is not a valid tag" }
  if (tag !in item.tags) return false
  if (tag in item.addedTags) item.addedTags.remove(tag) else item.removedTags.add(tag)
  item.dirty = true
  custodian(item)?.let { refreshHoldings(it) }
  return true
}

/**
 * Swaps [item]'s prototype for [label] in place, keeping everything the instance owns. A stack
 * turning into a non-stackable item keeps only one of its count. A container turning into a
 * non-container drops its contents on the ground of the room it is in. A lock the new prototype has
 * no key for is undone, leaving the container closed.
 */
fun World.transformItem(item: Item, label: Label) {
  require(item.id in entities) { "$item is not in the world" }
  val newProto = protos.items[label]
  requireNotNull(newProto) { "transform: $label is not an item" }
  val box = newProto.container
  val ground =
    if (box == null && item.contentsIndex.isNotEmpty())
      requireNotNull(groundOf(item)) { "transform: $item has nowhere to drop its contents" }
    else null
  if (box != null) {
    val count = item.contentsIndex.size
    val weight = item.contentsIndex.values.sumOf { it.weight }
    require(count <= box.capacity && weight <= box.maxWeight) {
      "transform: $item can't become $label: it holds $count items weighing $weight, but $label " +
        "holds at most ${box.capacity} items weighing ${box.maxWeight}"
    }
  }
  val loc = item.location as? ItemLocation.Equipped
  val wearer = loc?.let { actor(it.actor) }
  if (wearer != null) {
    val wear = newProto.wear
    val fits = wear != null && (loc.slot == wear || loc.slot.startsWith("${wear}_"))
    if (!fits) moveItem(item, ItemLocation.Carried(wearer.id)) else removeEquipAffects(wearer, item)
  }
  if (ground != null) for (inner in item.contentsIndex.values.toList()) moveItem(inner, ground)
  item.proto = newProto
  item.protoVersion = newProto.version
  if (!newProto.stackable) item.count = 1
  val oldState = item.container?.state
  item.container = box?.let { ContainerState(it) }
  item.container?.let { c ->
    if (oldState != null)
      c.state = if (oldState == DoorState.LOCKED && c.key == null) DoorState.CLOSED else oldState
  }
  if (wearer != null && item.location is ItemLocation.Equipped) applyEquipAffects(wearer, item)
  item.dirty = true
  if (custodian(item) is Player && !item.debug)
    itemEvents += ItemEvent(item.id, pulse, item.location, item.location, "transform")
  custodian(item)?.let { refreshHoldings(it) }
}

/** The ground under [item]: where it lies, or where whoever ultimately holds it stands. */
private fun World.groundOf(item: Item): ItemLocation.InRoom? =
  when (val loc = item.location) {
    is ItemLocation.InRoom -> loc
    is ItemLocation.Carried ->
      actor(loc.actor)?.let { ItemLocation.InRoom(it.room.id, it.trackIndex) }
    is ItemLocation.Equipped ->
      actor(loc.actor)?.let { ItemLocation.InRoom(it.room.id, it.trackIndex) }
    is ItemLocation.Contained -> item(loc.container)?.let { groundOf(it) }
  }

/**
 * Sets [item]'s container state. Silent: callers do their own messaging. Only a container whose
 * prototype names a key can be locked.
 */
fun World.setContainerState(item: Item, state: DoorState) {
  require(item.id in entities) { "$item is not in the world" }
  val c = requireNotNull(item.container) { "$item is not a container" }
  require(state != DoorState.LOCKED || c.key != null) { "$item has no lock" }
  c.state = state
  item.dirty = true
}
