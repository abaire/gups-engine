package gups.core.world

import gups.core.model.Direction
import gups.core.model.Player

/** The commands every world has. Their aliases come from the world. */
internal object KernelCommands {
  fun all(): List<CommandDef> = fastCommands() + tickCommands() + Tools.all()

  private fun fastCommands(): List<CommandDef> =
    listOf(look()) + communicationCommands() + informationCommands() + gameCommands()

  private fun tickCommands(): List<CommandDef> = itemCommands() + doorCommands()

  fun move(dir: Direction): TickCommand {
    val dirAliases = Direction.ALIASES.filterValues { it == dir.name }.keys.sorted()
    return TickCommand(
      dir.name,
      aliases = dirAliases,
      category = CommandCategory.MOVEMENT,
      help = "Move ${dir.name} through an exit.\nUsage: ${dir.name}",
      moves = true,
    ) {
      MoveAction(dir)
    }
  }

  private fun communicationCommands(): List<CommandDef> =
    listOf(
      "say"
        .speech("Say something to everyone in the room.\nUsage: say <message> (or '<message>)") {
          SpeechCommands.say(it)
        },
      "tell"
        .speech("Send a private message to a player or mob.\nUsage: tell <target> <message>") {
          SpeechCommands.tell(it)
        },
      "gossip"
        .speech("Send a message over the world-wide gossip channel.\nUsage: gossip <message>") {
          SpeechCommands.gossip(it)
        },
      "shout"
        .speech("Shout a message heard in this room and nearby rooms.\nUsage: shout <message>") {
          SpeechCommands.shout(it)
        },
      "whisper"
        .speech(
          "Whisper a message privately to someone in the room.\nUsage: whisper <target> <message>"
        ) {
          SpeechCommands.whisper(it)
        },
      FastCommand(
        "order",
        category = CommandCategory.COMMUNICATION,
        help = "Give an order to your pet.\nUsage: order <pet|pets> <command>",
      ) {
        SpeechCommands.order(it)
      },
    )

  private fun look() =
    "look"
      .info("Look at your surroundings, an actor, or an item.\nUsage: look [target]") {
        InfoCommands.look(it)
      }

  private fun informationCommands(): List<CommandDef> =
    listOf(
      "score"
        .info("View your character status, vitals, stats, resistances, and level.\nUsage: score") {
          InfoCommands.score(it)
        },
      "inventory"
        .info("List items in your inventory.\nUsage: inventory") { InfoCommands.inventory(it) },
      "equipment"
        .info("List items you are currently wearing or wielding.\nUsage: equipment") {
          InfoCommands.equipment(it)
        },
      "keyring".info("List keys on your keyring.\nUsage: keyring") { InfoCommands.keyring(it) },
      "who".info("List players currently online.\nUsage: who") { InfoCommands.who(it) },
      "help"
        .info(
          "Show available commands grouped by category, or detailed help on a specific command.\n" +
            "Usage: help [command]"
        ) {
          HelpCommand.help(it)
        },
    )

  private fun gameCommands(): List<CommandDef> =
    listOf(
      FastCommand(
        "stop",
        category = CommandCategory.GAME,
        help = "Clear planned actions and stop casting.\nUsage: stop",
      ) {
        stop(it)
      },
      FastCommand(
        "clear",
        category = CommandCategory.GAME,
        help = "Clear planned actions and stop casting.\nUsage: clear",
      ) {
        stop(it)
      },
      FastCommand(
        "quit",
        category = CommandCategory.GAME,
        help = "Leave the game and save character progress.\nUsage: quit",
      ) {
        quit(it)
      },
    )

  private fun itemCommands(): List<CommandDef> =
    listOf(
      "get"
        .item(
          "Pick up an item from the room or a container.\n" +
            "Usage: get <item|count item|all [item]> [[from] <container>]"
        ) {
          planGet(it)
        },
      "drop"
        .item(
          "Drop an item from your inventory to the room.\nUsage: drop <item|count item|all [item]>"
        ) {
          planDrop(it)
        },
      "give"
        .item(
          "Give an item from your inventory to someone in the room.\n" +
            "Usage: give <item|count item|all [item]> [to] <target>"
        ) {
          planGive(it)
        },
      "put"
        .item(
          "Put an item into a container.\n" +
            "Usage: put <item|count item|all [item]> [in|into] <container>"
        ) {
          planPut(it)
        },
      "wear".item("Wear an item of equipment.\nUsage: wear <item>") { planWear(it, wield = false) },
      "wield".item("Wield a weapon in hand.\nUsage: wield <item>") { planWear(it, wield = true) },
      "remove"
        .item("Remove worn equipment or unwield a weapon.\nUsage: remove <item>") {
          planRemove(it)
        },
      "use"
        .item("Use an item you carry or wear, or one in the room.\nUsage: use <item> [target]") {
          planUse(it)
        },
    )

  private fun doorCommands(): List<CommandDef> =
    listOf(
      door(DoorVerb.OPEN, "Open a door or container."),
      door(DoorVerb.CLOSE, "Close a door or container."),
      door(DoorVerb.LOCK, "Lock a door or container."),
      door(DoorVerb.UNLOCK, "Unlock a door or container."),
    )

  private fun String.speech(help: String, run: (CommandCall) -> Unit) =
    FastCommand(this, category = CommandCategory.COMMUNICATION, help = help) {
      SpeechCommands.spoken(it, run)
    }

  private fun String.info(help: String, run: (CommandCall) -> Unit) =
    FastCommand(this, category = CommandCategory.INFORMATION, help = help, run = run)

  private fun String.item(help: String, plan: (CommandCall) -> Action?) =
    TickCommand(this, category = CommandCategory.ITEMS, help = help, plan = plan)

  private fun door(verb: DoorVerb, summary: String) =
    verb.word.item("$summary\nUsage: ${verb.word} <direction|item>") { planDoor(it, verb) }

  /** `stop`/`clear`: flush the queue and cancel the activity; combat and recovery stay. */
  private fun stop(c: CommandCall) {
    val w = c.world
    val queued = c.actor.actionQueue.size
    c.actor.actionQueue.clear()
    val task = w.taskOf(c.actor)
    val reply =
      when {
        task != null -> w.msg(KernelMsg.STOP_TASK, "task" to task.name)
        c.actor.isBusy -> w.msg(SkillMsg.STOP_CAST)
        queued > 0 -> w.msg(KernelMsg.STOP_PLANS)
        else -> w.msg(KernelMsg.STOP_NOTHING)
      }
    // The reply comes first so a task's own stop callback can follow it.
    w.send(c.actor, reply)
    w.cancelActivity(c.actor)
  }

  private fun quit(c: CommandCall) {
    val p = c.actor as? Player ?: return
    if (Feature.COMBAT in c.world.features && p.isEngaged)
      return c.world.tell(p, CombatMsg.QUIT_REFUSED)
    c.world.quit(p)
  }
}
