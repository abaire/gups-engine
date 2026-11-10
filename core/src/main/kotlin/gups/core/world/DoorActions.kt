package gups.core.world

import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Channel
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.Exit
import gups.core.model.Item
import gups.core.model.Room
import gups.core.rules.RulesContext

internal enum class DoorVerb(
  val word: String,
  val heardAs: String,
  val from: Set<DoorState>,
  val to: DoorState,
  val before: HookName,
  val after: HookName,
) {
  OPEN(
    "open",
    "opening",
    setOf(DoorState.CLOSED),
    DoorState.OPEN,
    HookName.BEFORE_OPEN,
    HookName.ON_OPEN,
  ),
  CLOSE(
    "close",
    "closing",
    setOf(DoorState.OPEN),
    DoorState.CLOSED,
    HookName.BEFORE_CLOSE,
    HookName.ON_CLOSE,
  ),
  LOCK(
    "lock",
    "locking",
    setOf(DoorState.CLOSED),
    DoorState.LOCKED,
    HookName.BEFORE_LOCK,
    HookName.ON_LOCK,
  ),
  UNLOCK(
    "unlock",
    "unlocking",
    setOf(DoorState.LOCKED),
    DoorState.CLOSED,
    HookName.BEFORE_UNLOCK,
    HookName.ON_UNLOCK,
  );

  /** True for the verbs that need the key to the lock. */
  val needsKey: Boolean
    get() = this == LOCK || this == UNLOCK
}

internal fun planDoor(c: CommandCall, verb: DoorVerb): Action? {
  if (c.args.isEmpty())
    return null.also { c.world.tell(c.actor, KernelMsg.DOOR_WHAT.getValue(verb)) }
  val dir = Direction.parse(c.args)
  // A word that isn't a door here may name a container.
  if (c.actor.room.exits[dir]?.door == null) {
    val box =
      c.world.findItem(c.actor, c.args, inventory = true, ground = true)?.takeIf {
        it.container != null
      }
    if (box != null) return ContainerAction(box, verb)
  }
  return DoorAction(dir, verb)
}

/** Null if [actor] may work a lock opened by [key], else the message [noLock] or [NO_KEY]. */
private fun World.keyRefusal(verb: DoorVerb, actor: Actor, key: Label?, noLock: () -> String) =
  when {
    !verb.needsKey -> null
    key == null -> noLock()
    !actor.hasKey(key) -> msg(KernelMsg.NO_KEY)
    else -> null
  }

private class DoorAction(private val dir: Direction, private val verb: DoorVerb) : Action {
  override fun validate(w: World, actor: Actor): String? {
    val door =
      w.roomOf(actor).exits[dir]?.door ?: return w.msg(KernelMsg.DOOR_NONE, "direction" to dir)
    if (door.state !in verb.from)
      return w.msg(
        KernelMsg.DOOR_STATE,
        "direction" to dir,
        "state" to KernelMsg.stateWord(door.state),
      )
    return w.keyRefusal(verb, actor, door.key) { w.msg(KernelMsg.DOOR_NO_LOCK, "direction" to dir) }
  }

  private fun hookContext(actor: Actor) =
    HookContext(actor = actor, room = actor.room, direction = dir)

  override fun veto(w: World, actor: Actor): String? =
    w.dispatch(verb.before, null, actor.room, hookContext(actor))

  override fun resolve(w: World, actor: Actor): Int {
    val exit = actor.room.exits.getValue(dir)
    w.setDoor(actor.room, dir, verb.to)
    w.send(actor, w.msg(KernelMsg.DOOR_SELF.getValue(verb), "direction" to dir))
    val intensity = w.doorNoise(verb, actor, exit)
    val sound =
      Sound(intensity, w.msg(KernelMsg.DOOR_HEARD.getValue(verb)).takeIf { intensity > 0 })
    announceHere(w, actor, sound)
    w.reverseExit(actor.room, exit)?.let { announceOtherSide(w, actor, it, sound) }
    w.makeNoise(verb, actor, intensity, w.room(exit.to))
    w.dispatch(verb.after, null, actor.room, hookContext(actor))
    return 0
  }

  private fun announceHere(w: World, actor: Actor, sound: Sound) {
    val seen =
      w.msg(KernelMsg.DOOR_ROOM.getValue(verb), "actor" to actor.name.cap(), "direction" to dir)
    w.tellPerceivers(
      actor.room,
      verb.word,
      actor,
      renderings(seen, sound.heard),
      sound.intensity.coerceAtLeast(1),
      exclude = arrayOf(actor),
    )
  }

  private fun announceOtherSide(w: World, actor: Actor, side: Pair<Room, Exit>, sound: Sound) {
    val (other, back) = side
    val seen =
      w.msg(
        if (verb.to == DoorState.OPEN) KernelMsg.DOOR_OTHER_OPENS else KernelMsg.DOOR_OTHER_CLOSES,
        "direction" to back.direction,
      )
    w.tellPerceivers(
      other,
      verb.word,
      actor,
      renderings(seen, sound.heard),
      sound.intensity.coerceAtLeast(1),
    )
  }
}

/** Opening, closing, locking and unlocking a container item, with its key carried or worn. */
private class ContainerAction(private val box: Item, private val verb: DoorVerb) : Action {
  override fun validate(w: World, actor: Actor): String? {
    val state = box.container ?: return w.msg(KernelMsg.NOT_CONTAINER, "item" to box.name)
    if (!w.isPresent(box, w.roomOf(actor))) return w.msg(KernelMsg.DONT_SEE)
    w.reach(actor, box)?.let {
      return it
    }
    if (state.state !in verb.from)
      return w.msg(
        KernelMsg.BOX_STATE,
        "item" to box.name,
        "state" to KernelMsg.stateWord(state.state),
      )
    return w.keyRefusal(verb, actor, state.key) { w.msg(KernelMsg.BOX_NO_LOCK, "item" to box.name) }
  }

  private fun hookContext(actor: Actor) = HookContext(actor = actor, room = actor.room, item = box)

  override fun veto(w: World, actor: Actor): String? =
    w.dispatch(verb.before, box, actor.room, hookContext(actor))

  override fun resolve(w: World, actor: Actor): Int {
    box.container!!.state = verb.to
    box.dirty = true
    w.send(actor, w.msg(KernelMsg.BOX_SELF.getValue(verb), "item" to box.name))
    val intensity = w.boxNoise(verb, actor, box)
    val seen =
      w.msg(KernelMsg.BOX_ROOM.getValue(verb), "actor" to actor.name.cap(), "item" to box.name)
    val heard = w.msg(KernelMsg.BOX_HEARD.getValue(verb)).takeIf { intensity > 0 }
    w.tellPerceivers(
      actor.room,
      verb.word,
      actor,
      renderings(seen, heard),
      intensity.coerceAtLeast(1),
      exclude = arrayOf(actor),
    )
    w.makeNoise(verb, actor, intensity, null)
    w.dispatch(verb.after, box, actor.room, hookContext(actor))
    return 0
  }
}

/** What a player in the room is told of a door or container being worked: sight, and its sound. */
private fun renderings(seen: String, heard: String?): Map<Channel, String> = buildMap {
  put(Channel.SIGHT, seen)
  if (heard != null) put(Channel.SOUND, heard)
}

/** How loud, and what is heard of, a door or container being worked. */
private class Sound(val intensity: Int, val heard: String?)

/** How loud `rules.door_intensity` says [actor] doing [verb] to [exit]'s door is. */
private fun World.doorNoise(verb: DoorVerb, actor: Actor, exit: Exit): Int =
  rules.doorIntensity(
    RulesContext(
      this,
      actor = actor,
      room = actor.room,
      action = verb.word,
      tags = exit.door!!.tags,
      exit = exit,
    )
  )

/** How loud `rules.door_intensity` says [actor] doing [verb] to [box] is. */
private fun World.boxNoise(verb: DoorVerb, actor: Actor, box: Item): Int =
  rules.doorIntensity(
    RulesContext(
      this,
      actor = actor,
      room = actor.room,
      action = verb.word,
      tags = box.tags,
      item = box,
    )
  )

/**
 * The sound of [actor] doing [verb] to a door or container; a door is heard from both its sides.
 */
private fun World.makeNoise(verb: DoorVerb, actor: Actor, intensity: Int, otherSide: Room?) {
  perceive(verb.word, Channel.SOUND, actor.room, actor, intensity = intensity, alsoAt = otherSide)
}
