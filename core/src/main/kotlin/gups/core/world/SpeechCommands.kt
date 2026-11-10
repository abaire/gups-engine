package gups.core.world

import gups.core.model.Channel
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.RestrictionPoint
import gups.core.model.RestrictionRequest

/** The commands for talking: to the room, to one listener, or over a channel. */
internal object SpeechCommands {
  /**
   * Runs a speech command unless the speaker is `silenced`, which stops all speech, and with it any
   * dialogue or quest step a mob would run on hearing it.
   */
  fun spoken(c: CommandCall, speak: (CommandCall) -> Unit) {
    if (c.actor.hasFlag("silenced")) return c.world.tell(c.actor, KernelMsg.SILENCED)
    speak(c)
  }

  fun say(c: CommandCall) {
    if (c.args.isEmpty()) return c.world.tell(c.actor, KernelMsg.SAY_WHAT)
    c.world.say(c.actor, c.args)
  }

  fun tell(c: CommandCall) {
    val w = c.world
    val (name, text) = splitFirst(c.args)
    if (name.isEmpty() || text.isEmpty()) return w.tell(c.actor, KernelMsg.TELL_USAGE)
    val player = w.players.firstOrNull { it.name.equals(name, ignoreCase = true) }
    if (player != null) return tellPlayer(c, player, text)
    val mob = w.findActor(c.actor, name) ?: return w.tell(c.actor, KernelMsg.TELL_NOBODY)
    w.tell(c.actor, KernelMsg.TELL_SELF, "target" to mob.name, "text" to text)
    w.invokeOn(mob, HookName.ON_TELL, HookContext(actor = c.actor, text = text))
  }

  private fun tellPlayer(c: CommandCall, player: Player, text: String) {
    val w = c.world
    w.tell(player, KernelMsg.TELL_TARGET, "actor" to c.actor.name.cap(), "text" to text)
    w.channel("tell", c.actor, text, setOf(c.actor, player))
    w.tell(c.actor, KernelMsg.TELL_SELF, "target" to player.name, "text" to text)
  }

  fun gossip(c: CommandCall) {
    val w = c.world
    if (c.args.isEmpty()) return w.tell(c.actor, KernelMsg.GOSSIP_WHAT)
    if (c.actor is Player && w.refuseRestricted(c.actor, "gossip")) return
    for (p in w.players) {
      w.tell(p, KernelMsg.GOSSIP_LINE, "actor" to c.actor.name, "text" to c.args)
    }
    w.channel("gossip", c.actor, c.args, w.players)
  }

  /** Everyone else hears it, and gets its channel event, through `rules.perceive_message`. */
  fun shout(c: CommandCall) {
    val w = c.world
    if (c.args.isEmpty()) return w.tell(c.actor, KernelMsg.SHOUT_WHAT)
    w.tell(c.actor, KernelMsg.SHOUT_SELF, "text" to c.args)
    w.channel("shout", c.actor, c.args, setOf(c.actor))
    w.perceive(
      "shout",
      Channel.SOUND,
      c.actor.room,
      c.actor,
      c.args,
      SHOUT_INTENSITY,
      tellRoom = true,
    )
  }

  fun whisper(c: CommandCall) {
    val w = c.world
    val (name, text) = splitFirst(c.args)
    if (name.isEmpty() || text.isEmpty()) return w.tell(c.actor, KernelMsg.WHISPER_USAGE)
    val target = w.findActor(c.actor, name) ?: return w.tell(c.actor, KernelMsg.NOT_HERE)
    w.tell(c.actor, KernelMsg.WHISPER_SELF, "target" to target.name, "text" to text)
    w.tell(target, KernelMsg.WHISPER_TARGET, "actor" to c.actor.name.cap(), "text" to text)
    w.channel("whisper", c.actor, text, setOf(c.actor, target))
    w.tellSight(
      c.actor.room,
      "whisper",
      c.actor,
      w.msg(KernelMsg.WHISPER_ROOM, "actor" to c.actor.name.cap(), "target" to target.name),
      c.actor,
      target,
    )
  }

  /** `order <pet|pets> <command>`: has one of the player's pets, or all of them, act. */
  fun order(c: CommandCall) {
    val player = c.actor as? Player ?: return
    val w = c.world
    val (petWord, command) = splitFirst(c.args)
    if (petWord.isEmpty() || command.isEmpty()) return w.tell(player, KernelMsg.ORDER_USAGE)
    val targets = orderTargets(w, player, petWord) ?: return
    for (pet in targets) orderPet(w, player, pet, command)
  }

  /** The pets [petWord] names, or null after telling [player] why it names none. */
  private fun orderTargets(w: World, player: Player, petWord: String): List<Mob>? {
    val pets =
      player.room.actorIndex.values
        .filterIsInstance<Mob>()
        .filter { it.owner == player.id && it.inWorld }
        .sortedBy { it.id }
    if (petWord.equals("pets", ignoreCase = true)) {
      if (pets.isEmpty()) w.tell(player, KernelMsg.ORDER_NO_PETS)
      return pets.takeIf { it.isNotEmpty() }
    }
    val (n, word) = petWord.ordinal()
    val pet = pets.filter { it.matches(word) }.getOrNull(n - 1)
    if (pet == null) w.tell(player, KernelMsg.ORDER_NO_PET, "name" to petWord)
    return pet?.let(::listOf)
  }

  private fun orderPet(w: World, player: Player, pet: Mob, command: String) {
    if (!w.canAct(pet) || !w.isSimulated(pet)) {
      return w.tell(player, KernelMsg.ORDER_CANT, "pet" to pet.name)
    }
    val denial = orderDenial(w, player, pet, command)
    if (denial != null) return w.send(player, denial)
    val before = pet.actionQueue.size
    if (!w.act(pet, command)) return w.tell(player, KernelMsg.ORDER_CANT, "pet" to pet.name)
    for (i in before until pet.actionQueue.size) pet.actionQueue[i].orderedBy = player.id
    w.tell(player, KernelMsg.ORDER_GIVEN, "pet" to pet.name, "command" to command)
  }

  /** Why a restriction or the pet's `before_order` hook refuses [command], or null. */
  private fun orderDenial(w: World, player: Player, pet: Mob, command: String): String? =
    w.refusalFor(RestrictionRequest(point = RestrictionPoint.ORDER, actor = player, target = pet))
      ?: w.dispatch(
        HookName.BEFORE_ORDER,
        pet,
        pet.room,
        HookContext(actor = player, target = pet, text = command),
      )
}
