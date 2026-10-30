package gups.core.world

import gups.core.ACTION_QUEUE_MAX
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Channel
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

/** The `split` verb: moves [n] of a stack into a new item beside it. */
fun World.splitItem(item: Item, n: Int): Item {
  require(item.proto.stackable) { "${item.name} is not stackable" }
  require(n in 1 until item.count) { "split: n must be between 1 and ${item.count - 1}" }
  val at = item.location
  require(at !is ItemLocation.Equipped) { "split: can't split an equipped item" }
  val part = spawnItem(item.protoLabel, at, n)
  part.customAttributes.putAll(item.customAttributes)
  part.decayAt = item.decayAt
  item.count -= n
  item.dirty = true
  return part
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
