package gups.core.world

import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.Condition
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.Exit
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Player
import gups.core.model.RestrictionPoint
import gups.core.model.RestrictionRequest
import gups.core.rules.RulesContext

/** The commands every world has. Their aliases come from the world. */
internal object KernelCommands {
  fun all(): List<CommandDef> =
    listOf(
      // Fast lane.
      FastCommand(
        "look",
        category = CommandCategory.INFORMATION,
        help = "Look at your surroundings, an actor, or an item.\nUsage: look [target]",
      ) {
        look(it)
      },
      FastCommand(
        "say",
        category = CommandCategory.COMMUNICATION,
        help = "Say something to everyone in the room.\nUsage: say <message> (or '<message>)",
      ) {
        spoken(it, ::say)
      },
      FastCommand(
        "tell",
        category = CommandCategory.COMMUNICATION,
        help = "Send a private message to a player or mob.\nUsage: tell <target> <message>",
      ) {
        spoken(it, ::tell)
      },
      FastCommand(
        "gossip",
        category = CommandCategory.COMMUNICATION,
        help = "Send a message over the world-wide gossip channel.\nUsage: gossip <message>",
      ) {
        spoken(it, ::gossip)
      },
      FastCommand(
        "shout",
        category = CommandCategory.COMMUNICATION,
        help = "Shout a message heard in this room and nearby rooms.\nUsage: shout <message>",
      ) {
        spoken(it, ::shout)
      },
      FastCommand(
        "whisper",
        category = CommandCategory.COMMUNICATION,
        help =
          "Whisper a message privately to someone in the room.\nUsage: whisper <target> <message>",
      ) {
        spoken(it, ::whisper)
      },
      FastCommand(
        "score",
        category = CommandCategory.INFORMATION,
        help = "View your character status, vitals, stats, resistances, and level.\nUsage: score",
      ) {
        score(it)
      },
      FastCommand(
        "inventory",
        category = CommandCategory.INFORMATION,
        help = "List items in your inventory.\nUsage: inventory",
      ) {
        inventory(it)
      },
      FastCommand(
        "equipment",
        category = CommandCategory.INFORMATION,
        help = "List items you are currently wearing or wielding.\nUsage: equipment",
      ) {
        equipment(it)
      },
      FastCommand(
        "keyring",
        category = CommandCategory.INFORMATION,
        help = "List keys on your keyring.\nUsage: keyring",
      ) {
        keyring(it)
      },
      FastCommand(
        "who",
        category = CommandCategory.INFORMATION,
        help = "List players currently online.\nUsage: who",
      ) {
        who(it)
      },
      FastCommand(
        "help",
        category = CommandCategory.INFORMATION,
        help =
          "Show available commands grouped by category, or detailed help on a specific command.\nUsage: help [command]",
      ) {
        help(it)
      },
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
      // Tick lane.
      TickCommand(
        "get",
        category = CommandCategory.ITEMS,
        help =
          "Pick up an item from the room or a container.\nUsage: get <item|count item|all [item]> [[from] <container>]",
      ) {
        planGet(it)
      },
      TickCommand(
        "drop",
        category = CommandCategory.ITEMS,
        help =
          "Drop an item from your inventory to the room.\nUsage: drop <item|count item|all [item]>",
      ) {
        planDrop(it)
      },
      TickCommand(
        "give",
        category = CommandCategory.ITEMS,
        help =
          "Give an item from your inventory to someone in the room.\nUsage: give <item|count item|all [item]> [to] <target>",
      ) {
        planGive(it)
      },
      TickCommand(
        "put",
        category = CommandCategory.ITEMS,
        help =
          "Put an item into a container.\nUsage: put <item|count item|all [item]> [in|into] <container>",
      ) {
        planPut(it)
      },
      TickCommand(
        "wear",
        category = CommandCategory.ITEMS,
        help = "Wear an item of equipment.\nUsage: wear <item>",
      ) {
        planWear(it, wield = false)
      },
      TickCommand(
        "wield",
        category = CommandCategory.ITEMS,
        help = "Wield a weapon in hand.\nUsage: wield <item>",
      ) {
        planWear(it, wield = true)
      },
      TickCommand(
        "remove",
        category = CommandCategory.ITEMS,
        help = "Remove worn equipment or unwield a weapon.\nUsage: remove <item>",
      ) {
        planRemove(it)
      },
      TickCommand(
        "use",
        category = CommandCategory.ITEMS,
        help = "Use an item you carry or wear, or one in the room.\nUsage: use <item> [target]",
      ) {
        planUse(it)
      },
      TickCommand(
        "open",
        category = CommandCategory.ITEMS,
        help = "Open a door or container.\nUsage: open <direction|item>",
      ) {
        planDoor(it, DoorVerb.OPEN)
      },
      TickCommand(
        "close",
        category = CommandCategory.ITEMS,
        help = "Close a door or container.\nUsage: close <direction|item>",
      ) {
        planDoor(it, DoorVerb.CLOSE)
      },
      TickCommand(
        "lock",
        category = CommandCategory.ITEMS,
        help = "Lock a door or container.\nUsage: lock <direction|item>",
      ) {
        planDoor(it, DoorVerb.LOCK)
      },
      TickCommand(
        "unlock",
        category = CommandCategory.ITEMS,
        help = "Unlock a door or container.\nUsage: unlock <direction|item>",
      ) {
        planDoor(it, DoorVerb.UNLOCK)
      },
    ) + Tools.all()

  fun move(dir: Direction): TickCommand {
    val dirAliases = Direction.ALIASES.filterValues { it == dir.name }.keys.sorted()
    return TickCommand(
      dir.name,
      aliases = dirAliases,
      category = CommandCategory.MOVEMENT,
      help = "Move ${dir.name} through an exit.\nUsage: ${dir.name}",
    ) {
      MoveAction(dir)
    }
  }

  // ---- Fast lane ---------------------------------------------------------------------------

  private fun look(c: CommandCall) {
    val w = c.world
    if (c.args.isEmpty()) return w.send(c.actor, w.describeRoom(c.actor))
    val actor = w.findActor(c.actor, c.args)
    if (actor != null) {
      val desc = (actor as? gups.core.model.Mob)?.proto?.desc?.ifEmpty { null }
      w.send(c.actor, desc ?: w.msg(KernelMsg.LOOK_NOTHING_SPECIAL, "name" to actor.name))
      return w.send(
        c.actor,
        w.msg(KernelMsg.LOOK_HEALTH, "name" to actor.name, "health" to w.healthWord(actor)),
      )
    }
    val item =
      w.findItem(c.actor, c.args, inventory = true, ground = true)
        ?: return w.tell(c.actor, KernelMsg.DONT_SEE)
    w.send(
      c.actor,
      item.proto.desc.ifEmpty { w.msg(KernelMsg.LOOK_NOTHING_SPECIAL, "name" to item.name) },
    )
    item.container?.let { box ->
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
  }

  /**
   * Runs a speech command unless the speaker is `silenced`, which stops all speech, and with it any
   * dialogue or quest step a mob would run on hearing it.
   */
  private fun spoken(c: CommandCall, speak: (CommandCall) -> Unit) {
    if (c.actor.hasFlag("silenced")) return c.world.tell(c.actor, KernelMsg.SILENCED)
    speak(c)
  }

  private fun say(c: CommandCall) {
    if (c.args.isEmpty()) return c.world.tell(c.actor, KernelMsg.SAY_WHAT)
    c.world.say(c.actor, c.args)
  }

  private fun tell(c: CommandCall) {
    val w = c.world
    val (name, text) = splitFirst(c.args)
    if (name.isEmpty() || text.isEmpty()) return w.tell(c.actor, KernelMsg.TELL_USAGE)
    val player = w.players.firstOrNull { it.name.equals(name, ignoreCase = true) }
    if (player != null) {
      w.tell(player, KernelMsg.TELL_TARGET, "actor" to c.actor.name.cap(), "text" to text)
      w.channel("tell", c.actor, text, setOf(c.actor, player))
      return w.tell(c.actor, KernelMsg.TELL_SELF, "target" to player.name, "text" to text)
    }
    val mob = w.findActor(c.actor, name) ?: return w.tell(c.actor, KernelMsg.TELL_NOBODY)
    w.tell(c.actor, KernelMsg.TELL_SELF, "target" to mob.name, "text" to text)
    w.invokeOn(mob, HookName.ON_TELL, HookContext(actor = c.actor, text = text))
  }

  private fun gossip(c: CommandCall) {
    if (c.args.isEmpty()) return c.world.tell(c.actor, KernelMsg.GOSSIP_WHAT)
    if (c.actor is Player && c.world.refuseRestricted(c.actor, "gossip")) return
    for (p in c.world.players) c.world.tell(
      p,
      KernelMsg.GOSSIP_LINE,
      "actor" to c.actor.name,
      "text" to c.args,
    )
    c.world.channel("gossip", c.actor, c.args, c.world.players)
  }

  /** Everyone else hears it, and gets its channel event, through `rules.perceive_message`. */
  private fun shout(c: CommandCall) {
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

  private fun whisper(c: CommandCall) {
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

  /**
   * `score`: the name, the effective stats as `<label> <value>` and vitals as `<label> cur/max` in
   * schema order, and the effective resists. With `leveling`, the level and XP, followed on the
   * next line by the ready line while levels are pending in either level mode. Then each known
   * creation choice in choice order and the carried traits in name order.
   */
  private fun score(c: CommandCall) {
    val a = c.actor
    val w = c.world
    val schema = w.schema
    val lines = mutableListOf(a.name)
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
    if (Feature.LEVELING in w.features) {
      lines += w.msg(LevelingMsg.SCORE_LEVEL, "level" to a.level, "xp" to a.xp)
      if (w.pendingLevels(a) > 0) {
        lines += w.msg(LevelingMsg.SCORE_READY)
      }
    }
    if (a is Player && w.creation != null) {
      for (choice in w.creation!!.choices) {
        val optId = a.choices[choice.id] ?: continue
        val opt = choice.options.firstOrNull { it.id == optId } ?: continue
        lines += w.msg(KernelMsg.SCORE_CHOICE, "title" to choice.title, "label" to opt.label)
      }
    }
    if (a.traits.isNotEmpty()) {
      lines += w.msg(KernelMsg.SCORE_TRAITS, "traits" to a.traits.joinToString(", ") { it.label })
    }
    w.send(a, lines.joinToString("\n"))
  }

  private fun inventory(c: CommandCall) {
    // Keys live on the keyring, so they don't clutter the inventory.
    val items = c.actor.inventory.filterNot { it.onKeyring }
    val w = c.world
    w.send(
      c.actor,
      if (items.isEmpty()) w.msg(KernelMsg.INVENTORY_EMPTY)
      else
        w.msg(
          KernelMsg.INVENTORY_LIST,
          "items" to
            items.joinToString("\n") { w.msg(KernelMsg.INVENTORY_ITEM, "item" to it.describe()) },
        ),
    )
  }

  private fun keyring(c: CommandCall) {
    val keys = c.actor.inventory.filter { it.onKeyring }
    val w = c.world
    w.send(
      c.actor,
      if (keys.isEmpty()) w.msg(KernelMsg.KEYRING_EMPTY)
      else
        w.msg(
          KernelMsg.KEYRING_LIST,
          "items" to
            keys.joinToString("\n") { w.msg(KernelMsg.INVENTORY_ITEM, "item" to it.describe()) },
        ),
    )
  }

  private fun equipment(c: CommandCall) {
    val eq = c.actor.equipment
    val w = c.world
    w.send(
      c.actor,
      if (eq.isEmpty()) w.msg(KernelMsg.EQUIPMENT_EMPTY)
      else
        w.msg(
          KernelMsg.EQUIPMENT_LIST,
          "items" to
            eq.entries.joinToString("\n") {
              w.msg(KernelMsg.EQUIPMENT_ITEM, "slot" to it.key, "item" to it.value.name)
            },
        ),
    )
  }

  private fun who(c: CommandCall) {
    val w = c.world
    val lines =
      w.players.map { p ->
        w.msg(
          if (p.session == null) KernelMsg.WHO_LINKDEAD else KernelMsg.WHO_PLAYER,
          "name" to p.name,
        )
      }
    w.tell(c.actor, KernelMsg.WHO_LIST, "players" to lines.joinToString("\n"))
  }

  private fun help(c: CommandCall) {
    val target = c.args.trim()
    if (target.isNotEmpty()) {
      showDetailedHelp(c, target)
      return
    }
    showHelpIndex(c)
  }

  private fun showHelpIndex(c: CommandCall) {
    val defs =
      (c.world.commands.engineCommands +
          c.world.commands.globalCommands +
          c.world.commands.pluginCommands +
          c.world.commands.toolCommands)
        .distinct()
        .filter { it.allows(c.actor) }

    val byCategory = defs.groupBy { c.world.commands.categoryOrImplicit(it.category) }
    val movementCat = c.world.commands.categoryOrImplicit(CommandCategory.MOVEMENT)
    val presentCategories =
      (byCategory.keys + (if (c.actor is Player) setOf(movementCat) else emptySet())).distinct()

    val categoryComparator =
      compareBy<CategoryDef>(
        { if (it.id.equals(CommandCategory.SPECIAL, ignoreCase = true)) 1 else 0 },
        { it.order ?: CommandCategory.DEFAULT_ORDER },
        { it.title.lowercase() },
      )

    val allCategories = presentCategories.sortedWith(categoryComparator)

    val lines = mutableListOf<String>()
    for (cat in allCategories) {
      val cmds = byCategory[cat] ?: emptyList()
      val names = cmds.map { it.name }.sorted()
      if (cat.id.equals(CommandCategory.MOVEMENT, ignoreCase = true)) {
        if (names.isNotEmpty()) {
          lines += "${cat.title}: ${names.joinToString(" ")}, and any exit name"
        } else {
          lines += "${cat.title}: and any exit name"
        }
      } else if (names.isNotEmpty()) {
        lines += "${cat.title}: ${names.joinToString(" ")}"
      }
    }
    c.world.send(c.actor, lines.joinToString("\n"))
  }

  private fun showDetailedHelp(c: CommandCall, target: String) {
    val word = target.substringBefore(' ').lowercase()
    val prefixCommand = if (word.length == 1) c.world.commands.aliases.prefixes[word[0]] else null
    val def =
      prefixCommand?.let { c.world.commands.resolve(c.actor, it) }
        ?: c.world.commands.resolve(c.actor, word)
        ?: (if (!word.startsWith("@")) c.world.commands.resolve(c.actor, "@$word") else null)

    if (def != null) {
      val worldAliases = c.world.commands.aliases.words.filterValues { it == def.name }.keys
      val worldPrefixes =
        c.world.commands.aliases.prefixes.filterValues { it == def.name }.keys.map { it.toString() }
      val allAliases = (def.aliases + worldAliases + worldPrefixes).distinct().sorted()
      val aliasStr = if (allAliases.isEmpty()) "none" else allAliases.joinToString(", ")

      val details = def.detailedHelp().trim()
      val text = buildString {
        appendLine("Command: ${def.name}")
        appendLine("Aliases: $aliasStr")
        if (details.isNotEmpty()) {
          appendLine()
          append(details)
        }
      }
      c.world.send(c.actor, text)
      return
    }

    val cat = c.world.commands.category(target) ?: c.world.commands.category(word)
    if (cat != null && !cat.help.isNullOrBlank()) {
      val allDefs =
        (c.world.commands.engineCommands +
            c.world.commands.globalCommands +
            c.world.commands.pluginCommands +
            c.world.commands.toolCommands)
          .distinct()
      val inCategory =
        allDefs.filter {
          it.category.equals(cat.id, ignoreCase = true) ||
            it.category.equals(cat.title, ignoreCase = true)
        }
      val allowed = inCategory.filter { it.allows(c.actor) }
      if (inCategory.isEmpty() || allowed.isNotEmpty()) {
        val names = allowed.map { it.name }.sorted()
        val text = buildString {
          appendLine("Category: ${cat.title}")
          if (names.isNotEmpty()) {
            appendLine("Commands: ${names.joinToString(" ")}")
          }
          appendLine()
          append(cat.help.trim())
        }
        c.world.send(c.actor, text)
        return
      }
    }

    val message = "No help available for '$target'."
    c.world.send(c.actor, message)
  }

  /** `stop`/`clear`: flush the queue and cancel a cast; combat and recovery stay. */
  private fun stop(c: CommandCall) {
    val w = c.world
    val queued = c.actor.actionQueue.size
    c.actor.actionQueue.clear()
    val cancelled = Feature.SKILLS in w.features && w.cancelCast(c.actor)
    w.send(
      c.actor,
      w.msg(
        when {
          cancelled -> SkillMsg.STOP_CAST
          queued > 0 -> KernelMsg.STOP_PLANS
          else -> KernelMsg.STOP_NOTHING
        }
      ),
    )
  }

  private fun quit(c: CommandCall) {
    val p = c.actor as? Player ?: return
    if (Feature.COMBAT in c.world.features && p.isEngaged)
      return c.world.tell(p, CombatMsg.QUIT_REFUSED)
    c.world.quit(p)
  }

  // ---- Tick lane ---------------------------------------------------------------------------

  private fun planGet(c: CommandCall): Action? {
    val w = c.world
    if (c.args.isBlank()) return null.also { w.tell(c.actor, KernelMsg.GET_WHAT) }
    return GetAction(c.args.trim())
  }

  private fun planDrop(c: CommandCall): Action? {
    val w = c.world
    val spec = parseItemSpec(c.args) ?: return null.also { w.tell(c.actor, KernelMsg.NOT_CARRYING) }
    return object : Action {
      override fun validate(w: World, actor: Actor): String? =
        if (matchItems(actor.inventory, spec).isEmpty()) w.msg(KernelMsg.NOT_CARRYING) else null

      override fun resolve(w: World, actor: Actor): Int {
        for ((item, wanted) in matchItems(actor.inventory, spec)) {
          if (item.location != ItemLocation.Carried(actor.id)) continue
          val refusal =
            w.restrictionRefusal(
              RestrictionPoint.DROP,
              RestrictionRequest(point = RestrictionPoint.DROP, actor = actor, item = item),
            )
          if (refusal != null) {
            w.send(actor, refusal)
            continue
          }
          val veto =
            w.dispatch(
              HookName.BEFORE_DROP,
              item,
              actor.room,
              HookContext(actor = actor, item = item),
            )
          if (veto != null) {
            w.send(actor, veto)
            continue
          }
          // The hook may have destroyed or moved the stack.
          if (item.id !in w.entities || item.location != ItemLocation.Carried(actor.id)) continue
          val n = minOf(wanted, item.count)
          val moved = w.moveItem(item, ItemLocation.InRoom(actor.room.id, actor.trackIndex), n)
          val desc = moved.describe()
          w.tell(actor, KernelMsg.DROP_SELF, "item" to desc)
          w.tellSight(
            actor.room,
            "drop",
            actor,
            w.msg(KernelMsg.DROP_ROOM, "actor" to actor.name.cap(), "item" to desc),
            actor,
          )
          w.dispatch(HookName.ON_DROP, moved, actor.room, HookContext(actor = actor, item = moved))
          if (moved.id in w.entities) w.stack(moved)
        }
        return 0
      }
    }
  }

  /**
   * `give <what> [to] <target>`: hands one item, a count, or all matching items over; fires
   * `on_give` once per stack moved. The items are resolved when the action runs, so several queued
   * gives each act on the state the previous left.
   */
  private fun planGive(c: CommandCall): Action? {
    val w = c.world
    val toMatch = Regex("\\s+to\\s+", RegexOption.IGNORE_CASE).findAll(c.args).lastOrNull()
    val whatStr: String
    val targetStr: String
    if (toMatch != null) {
      whatStr = c.args.substring(0, toMatch.range.first).trim()
      targetStr = c.args.substring(toMatch.range.last + 1).trim()
    } else {
      val words = c.args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
      if (words.size < 2) return null.also { w.tell(c.actor, KernelMsg.GIVE_USAGE) }
      targetStr = words.last()
      whatStr = words.dropLast(1).joinToString(" ")
    }
    val spec = parseItemSpec(whatStr) ?: return null.also { w.tell(c.actor, KernelMsg.GIVE_USAGE) }
    return GiveAction(spec, targetStr)
  }

  /**
   * `put <what> [in|into] <container>`: the container is carried or on the ground within reach, and
   * open. Moves one item, a count, or all matching items, never the container itself; the items are
   * resolved when the action runs, so several queued puts each act on the state the previous left.
   */
  private fun planPut(c: CommandCall): Action? {
    val w = c.world
    val inMatch = Regex("\\s+(in|into)\\s+", RegexOption.IGNORE_CASE).findAll(c.args).lastOrNull()
    val whatStr: String
    val boxStr: String
    if (inMatch != null) {
      whatStr = c.args.substring(0, inMatch.range.first).trim()
      boxStr = c.args.substring(inMatch.range.last + 1).trim()
    } else {
      val words = c.args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
      if (words.size < 2) return null.also { w.tell(c.actor, KernelMsg.PUT_USAGE) }
      boxStr = words.last()
      whatStr = words.dropLast(1).joinToString(" ")
    }
    val spec = parseItemSpec(whatStr) ?: return null.also { w.tell(c.actor, KernelMsg.PUT_USAGE) }
    return PutAction(spec, boxStr)
  }

  /**
   * `wear <item>` and `wield <item>`: equips a carried item in its slot, swapping out whatever was
   * there. An item's `wear` slot may name a family such as `finger`, which fits the first free
   * `finger_*` slot of the rules' `equipment_slots()`.
   */
  private fun planWear(c: CommandCall, wield: Boolean): Action? {
    val w = c.world
    val item =
      w.findItem(c.actor, c.args, inventory = true, ground = false)
        ?: return null.also { w.tell(c.actor, KernelMsg.NOT_CARRYING) }
    val wanted = item.proto.wear
    if (wanted == null || (wield && wanted != "wield"))
      return null.also {
        w.tell(
          c.actor,
          if (wield) KernelMsg.WEAR_CANT_WIELD else KernelMsg.WEAR_CANT_WEAR,
          "item" to item.name,
        )
      }
    return object : Action {
      override fun validate(w: World, actor: Actor): String? {
        if (item.location != ItemLocation.Carried(actor.id)) return w.msg(KernelMsg.NOT_CARRYING)
        val slot =
          w.slotFor(actor, wanted) ?: return w.msg(KernelMsg.WEAR_CANT_WEAR, "item" to item.name)
        val occupant = actor.equipped(slot)
        if (occupant != null) {
          val removeReq =
            RestrictionRequest(
              point = RestrictionPoint.REMOVE,
              actor = actor,
              item = occupant,
              slot = slot,
            )
          val refusal = w.restrictionRefusal(RestrictionPoint.REMOVE, removeReq)
          if (refusal != null) return refusal
        }
        val req =
          RestrictionRequest(point = RestrictionPoint.WEAR, actor = actor, item = item, slot = slot)
        val refusal = w.restrictionRefusal(RestrictionPoint.WEAR, req)
        if (refusal != null) return refusal
        return null
      }

      override fun resolve(w: World, actor: Actor): Int {
        val slot = w.slotFor(actor, wanted)!!
        actor.equipped(slot)?.let {
          w.moveItem(it, ItemLocation.Carried(actor.id))
          w.tell(actor, KernelMsg.WEAR_STOP, "item" to it.name)
        }
        w.moveItem(item, ItemLocation.Equipped(actor.id, slot))
        val wielded = slot == "wield"
        w.tell(
          actor,
          if (wielded) KernelMsg.WIELD_SELF else KernelMsg.WEAR_SELF,
          "item" to item.name,
        )
        w.tellSight(
          actor.room,
          if (wielded) "wield" else "wear",
          actor,
          w.msg(
            if (wielded) KernelMsg.WIELD_ROOM else KernelMsg.WEAR_ROOM,
            "actor" to actor.name.cap(),
            "item" to item.name,
          ),
          actor,
        )
        return 0
      }
    }
  }

  /**
   * `use <item> [target]`: an item worn, carried, or on the ground within reach whose `before_use`
   * doesn't veto; fires its `on_use`. The target is an actor in the room, or else an item carried
   * or on the ground.
   */
  private fun planUse(c: CommandCall): Action? {
    val w = c.world
    val words = c.args.split(Regex("\\s+"), limit = 2)
    if (words[0].isEmpty()) return null.also { w.tell(c.actor, KernelMsg.USE_WHAT) }
    val item =
      (c.actor.equipment.values.firstOrNull { it.matches(words[0]) }
        ?: w.findItem(c.actor, words[0], inventory = true, ground = true))
        ?: return null.also { w.tell(c.actor, KernelMsg.DONT_SEE) }
    val word = words.getOrNull(1)?.trim()?.removePrefix("on ")?.trim()?.ifEmpty { null }
    val target = word?.let { w.findActor(c.actor, it) }
    val targetItem =
      if (word != null && target == null) {
        w.findItem(c.actor, word, inventory = true, ground = true)
          ?: return null.also { w.tell(c.actor, KernelMsg.DONT_SEE) }
      } else {
        null
      }
    val ctx = { actor: Actor ->
      HookContext(actor = actor, item = item, target = target, targetItem = targetItem)
    }
    return object : Action {
      override fun validate(w: World, actor: Actor): String? {
        val held =
          item.location == ItemLocation.Carried(actor.id) ||
            (item.location as? ItemLocation.Equipped)?.actor == actor.id
        if (!held) {
          if ((item.location as? ItemLocation.InRoom)?.room != w.roomOf(actor).id)
            return w.msg(KernelMsg.DONT_SEE)
          w.reach(actor, item)?.let {
            return it
          }
        }
        if (target != null && !w.isPresent(target, w.roomOf(actor)))
          return w.msg(KernelMsg.NOT_HERE)
        if (!w.scripts.hasHook(item, HookName.ON_USE))
          return w.msg(KernelMsg.USE_CANT, "item" to item.name)
        return null
      }

      override fun veto(w: World, actor: Actor): String? =
        w.dispatch(HookName.BEFORE_USE, item, actor.room, ctx(actor))

      override fun resolve(w: World, actor: Actor): Int {
        w.dispatch(HookName.ON_USE, item, actor.room, ctx(actor))
        return 0
      }
    }
  }

  private fun planRemove(c: CommandCall): Action? {
    val w = c.world
    val item =
      c.actor.equipment.values.firstOrNull { it.matches(c.args) }
        ?: return null.also { w.tell(c.actor, KernelMsg.NOT_USING) }
    return object : Action {
      override fun validate(w: World, actor: Actor): String? {
        if ((item.location as? ItemLocation.Equipped)?.actor != actor.id)
          return w.msg(KernelMsg.NOT_USING)
        val req =
          RestrictionRequest(
            point = RestrictionPoint.REMOVE,
            actor = actor,
            item = item,
            slot = (item.location as ItemLocation.Equipped).slot,
          )
        val refusal = w.restrictionRefusal(RestrictionPoint.REMOVE, req)
        if (refusal != null) return refusal
        return null
      }

      override fun resolve(w: World, actor: Actor): Int {
        w.moveItem(item, ItemLocation.Carried(actor.id))
        w.tell(actor, KernelMsg.WEAR_STOP, "item" to item.name)
        return 0
      }
    }
  }

  private fun planDoor(c: CommandCall, verb: DoorVerb): Action? {
    if (c.args.isEmpty())
      return null.also { c.world.tell(c.actor, KernelMsg.DOOR_WHAT.getValue(verb)) }
    val dir = Direction.parse(c.args)
    // A word that isn't a door here may name a container.
    if (c.actor.room.exits[dir]?.door == null) {
      val box =
        c.world.findItem(c.actor, c.args, inventory = true, ground = true)?.takeIf {
          it.container != null
        }
      if (box != null) return planContainer(box, verb)
    }
    return object : Action {
      override fun validate(w: World, actor: Actor): String? {
        val door =
          w.roomOf(actor).exits[dir]?.door ?: return w.msg(KernelMsg.DOOR_NONE, "direction" to dir)
        if (door.state !in verb.from)
          return w.msg(
            KernelMsg.DOOR_STATE,
            "direction" to dir,
            "state" to KernelMsg.stateWord(door.state),
          )
        if (verb == DoorVerb.LOCK || verb == DoorVerb.UNLOCK) {
          val key = door.key ?: return w.msg(KernelMsg.DOOR_NO_LOCK, "direction" to dir)
          if (!actor.hasKey(key)) return w.msg(KernelMsg.NO_KEY)
        }
        return null
      }

      private fun hookCtx(actor: Actor) =
        HookContext(actor = actor, room = actor.room, direction = dir)

      override fun veto(w: World, actor: Actor): String? =
        w.dispatch(verb.before, null, actor.room, hookCtx(actor))

      override fun resolve(w: World, actor: Actor): Int {
        val exit = actor.room.exits.getValue(dir)
        w.setDoor(actor.room, dir, verb.to)
        w.send(actor, w.msg(KernelMsg.DOOR_SELF.getValue(verb), "direction" to dir))
        val door = exit.door!!
        val intensity = w.noiseOf(verb, actor, door.tags, exit = exit)
        val heard = w.msg(KernelMsg.DOOR_HEARD.getValue(verb)).takeIf { intensity > 0 }
        w.tellPerceivers(
          actor.room,
          verb.word,
          actor,
          renderings(
            w.msg(
              KernelMsg.DOOR_ROOM.getValue(verb),
              "actor" to actor.name.cap(),
              "direction" to dir,
            ),
            heard,
          ),
          intensity.coerceAtLeast(1),
          exclude = arrayOf(actor),
        )
        w.reverseExit(actor.room, exit)?.let { (other, back) ->
          w.tellPerceivers(
            other,
            verb.word,
            actor,
            renderings(
              w.msg(
                if (verb.to == DoorState.OPEN) KernelMsg.DOOR_OTHER_OPENS
                else KernelMsg.DOOR_OTHER_CLOSES,
                "direction" to back.direction,
              ),
              heard,
            ),
            intensity.coerceAtLeast(1),
          )
        }
        w.makeNoise(verb, actor, intensity, w.room(exit.to))
        w.dispatch(verb.after, null, actor.room, hookCtx(actor))
        return 0
      }
    }
  }

  /** Opening, closing, locking and unlocking a container item, with its key carried or worn. */
  private fun planContainer(box: Item, verb: DoorVerb): Action =
    object : Action {
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
        if (verb == DoorVerb.LOCK || verb == DoorVerb.UNLOCK) {
          val key = state.key ?: return w.msg(KernelMsg.BOX_NO_LOCK, "item" to box.name)
          if (!actor.hasKey(key)) return w.msg(KernelMsg.NO_KEY)
        }
        return null
      }

      private fun hookCtx(actor: Actor) = HookContext(actor = actor, room = actor.room, item = box)

      override fun veto(w: World, actor: Actor): String? =
        w.dispatch(verb.before, box, actor.room, hookCtx(actor))

      override fun resolve(w: World, actor: Actor): Int {
        box.container!!.state = verb.to
        box.dirty = true
        w.send(actor, w.msg(KernelMsg.BOX_SELF.getValue(verb), "item" to box.name))
        val intensity = w.noiseOf(verb, actor, box.tags, item = box)
        w.tellPerceivers(
          actor.room,
          verb.word,
          actor,
          renderings(
            w.msg(
              KernelMsg.BOX_ROOM.getValue(verb),
              "actor" to actor.name.cap(),
              "item" to box.name,
            ),
            w.msg(KernelMsg.BOX_HEARD.getValue(verb)).takeIf { intensity > 0 },
          ),
          intensity.coerceAtLeast(1),
          exclude = arrayOf(actor),
        )
        w.makeNoise(verb, actor, intensity, null)
        w.dispatch(verb.after, box, actor.room, hookCtx(actor))
        return 0
      }
    }

  // ---- Helpers -----------------------------------------------------------------------------

}

/** [s] split at its first whitespace: the first word and the trimmed rest. */
internal fun splitFirst(s: String): Pair<String, String> {
  val t = s.trim()
  val i = t.indexOfFirst { it.isWhitespace() }
  return if (i < 0) t to "" else t.substring(0, i) to t.substring(i + 1).trim()
}

/**
 * A parsed `<what>`: [all] for `all` and `all <keyword>`, [count] for an explicit unit count,
 * [ordinal] for `2.sword` (default 1), and [keyword] (null only for bare `all`).
 */
internal data class ItemSpec(
  val all: Boolean,
  val count: Int?,
  val ordinal: Int,
  val keyword: String?,
)

/** True if bare `all` may pick [this] up: not tagged `no_all` and not a keyring-only key. */
internal val Item.inBareAll: Boolean
  get() = "no_all" !in tags && !onKeyring

/** Parses `<what>` into an [ItemSpec], or null when it is not a valid item specifier. */
internal fun parseItemSpec(raw: String): ItemSpec? {
  val t = raw.trim()
  if (t.isEmpty()) return null
  if (t.equals("all", ignoreCase = true))
    return ItemSpec(all = true, count = null, ordinal = 1, keyword = null)
  val (first, rest) = splitFirst(t)
  if (first.equals("all", ignoreCase = true)) return ItemSpec(true, null, 1, rest.lowercase())
  // A count too large for an Int is capped like any other count larger than what is there.
  val count = first.toIntOrNull() ?: Int.MAX_VALUE.takeIf { first.all(Char::isDigit) }
  count?.let { n ->
    if (n < 1 || rest.isEmpty()) return null
    val (ord, word) = ordinal(rest)
    if (ord < 1 || word.isEmpty()) return null
    return ItemSpec(false, n, ord, word)
  }
  val (ord, word) = ordinal(t)
  if (ord < 1 || word.isEmpty()) return null
  return ItemSpec(false, null, ord, word)
}

/**
 * The source stacks [spec] names in [pool] and the units wanted from each: every matching stack for
 * `all`, the [ordinal]th for a bare keyword, or enough stacks to fill [count]. [exclude] still
 * counts toward the ordinal, so `put 2.chest in chest` names the second chest, not the third.
 */
internal fun matchItems(
  pool: List<Item>,
  spec: ItemSpec,
  exclude: Item? = null,
): List<Pair<Item, Int>> {
  val hits =
    pool
      .filter { if (spec.keyword == null) it.inBareAll else it.matches(spec.keyword) }
      .drop(spec.ordinal - 1)
      .filter { it !== exclude }
  if (spec.all) return hits.map { it to it.count }
  if (spec.count == null) return hits.take(1).map { it to it.count }
  var remaining = spec.count
  val out = mutableListOf<Pair<Item, Int>>()
  for (item in hits) {
    if (remaining <= 0) break
    val take = minOf(remaining, item.count)
    out += item to take
    remaining -= take
  }
  return out
}

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
  ),
}

/** What a player in the room is told of a door or container being worked: sight, and its sound. */
private fun renderings(seen: String, heard: String?): Map<Channel, String> = buildMap {
  put(Channel.SIGHT, seen)
  if (heard != null) put(Channel.SOUND, heard)
}

/** How loud `rules.door_intensity` says [actor] doing [verb] to a door or container is. */
private fun World.noiseOf(
  verb: DoorVerb,
  actor: Actor,
  tags: Set<String>,
  exit: Exit? = null,
  item: Item? = null,
): Int =
  rules.doorIntensity(
    RulesContext(
      this,
      actor = actor,
      room = actor.room,
      action = verb.word,
      tags = tags,
      exit = exit,
      item = item,
    )
  )

/**
 * The sound of [actor] doing [verb] to a door or container; a door is heard from both its sides.
 */
private fun World.makeNoise(
  verb: DoorVerb,
  actor: Actor,
  intensity: Int,
  otherSide: gups.core.model.Room?,
) {
  perceive(verb.word, Channel.SOUND, actor.room, actor, intensity = intensity, alsoAt = otherSide)
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

/**
 * `get <what> [[from] <container>]`, with lag by band from `rules.get_lag`. The item is resolved
 * when the action runs, so several queued gets each act on the state the previous left. A `fixed`
 * item (from a behavior) is picked up alone, in full, with no container.
 */
internal class GetAction(private val args: String, private val fixed: Item? = null) : Action {
  private var spec: ItemSpec? = null
  private var container: Item? = null
  private var carriedSource = false

  override fun validate(w: World, actor: Actor): String? {
    val room = w.roomOf(actor)
    if (fixed != null) {
      val loc = fixed.location
      if (fixed.id !in w.entities || loc !is ItemLocation.InRoom || loc.room != room.id)
        return w.msg(KernelMsg.DONT_SEE)
    } else {
      parseArgs(w, actor)?.let {
        return it
      }
    }
    carriedSource = container?.let { w.custodian(it) } === actor
    container?.let { box ->
      if (!w.isPresent(box, room)) return w.msg(KernelMsg.DONT_SEE)
      val state = box.container ?: return w.msg(KernelMsg.NOT_CONTAINER, "item" to box.name)
      if (state.state != DoorState.OPEN) return w.msg(KernelMsg.PUT_CLOSED, "container" to box.name)
      w.reach(actor, box)?.let {
        return it
      }
    }
    val matches = wanted(w, actor)
    if (matches.isEmpty()) return w.msg(KernelMsg.DONT_SEE)
    if (matches.all { lag(w, actor, it.first) == null })
      return w.msg(KernelMsg.CANT_REACH, "item" to matches.first().first.name)
    if (!carriedSource && matches.none { w.carryFit(actor, it.first, 1) > 0 })
      return w.msg(KernelMsg.CANT_CARRY)
    return null
  }

  /** Parses [args] into [spec] and [container]; returns the message to tell instead, or null. */
  private fun parseArgs(w: World, actor: Actor): String? {
    val fromMatch = Regex("\\s+from\\s+", RegexOption.IGNORE_CASE).findAll(args).lastOrNull()
    return if (fromMatch != null) {
      val whatStr = args.substring(0, fromMatch.range.first).trim()
      val fromStr = args.substring(fromMatch.range.last + 1).trim()
      val s = parseItemSpec(whatStr) ?: return w.msg(KernelMsg.GET_WHAT)
      val box =
        w.findItem(actor, fromStr, inventory = true, ground = true)
          ?: return w.msg(KernelMsg.DONT_SEE)
      spec = s
      container = box
      null
    } else if (args.count { it.isWhitespace() } == 0) {
      val s = parseItemSpec(args) ?: return w.msg(KernelMsg.GET_WHAT)
      spec = s
      container = null
      null
    } else {
      val words = args.trim().split(Regex("\\s+"))
      val lastWord = words.last()
      val beforeLast = words.dropLast(1).joinToString(" ")
      val box =
        w.findItem(actor, lastWord, inventory = true, ground = true)?.takeIf {
          it.container != null
        }
      val boxSpec = parseItemSpec(beforeLast)
      if (box == null || boxSpec == null) {
        val s = parseItemSpec(args) ?: return w.msg(KernelMsg.GET_WHAT)
        spec = s
        container = null
      } else if (
        boxSpec.all ||
          (box.container?.state == DoorState.OPEN && matchItems(box.contents, boxSpec).isNotEmpty())
      ) {
        // The container wins: `all <container>` loots it, or an open container holds a match. A
        // closed container wins too, and `validate` reports it closed.
        spec = boxSpec
        container = box
      } else {
        // The box finds nothing (closed, or no matching contents). The whole phrase is a ground
        // item if one matches, else the container still wins.
        val whole = parseItemSpec(args)
        if (whole != null && matchItems(w.roomOf(actor).items, whole).isNotEmpty()) {
          spec = whole
          container = null
        } else {
          spec = boxSpec
          container = box
        }
      }
      null
    }
  }

  /** The source stacks and the units wanted from each, recomputed from the live pool. */
  private fun wanted(w: World, actor: Actor): List<Pair<Item, Int>> {
    val f = fixed
    if (f != null) {
      val loc = f.location
      return if (f.id in w.entities && loc is ItemLocation.InRoom && loc.room == w.roomOf(actor).id)
        listOf(f to f.count)
      else emptyList()
    }
    val s = spec ?: return emptyList()
    val c = container
    val pool =
      if (c == null) w.roomOf(actor).items
      else if (c.id in w.entities && w.isPresent(c, w.roomOf(actor))) c.contents else emptyList()
    return matchItems(pool, s)
  }

  /** True if [item] is still in the container, or on the ground in the actor's room. */
  private fun atSource(w: World, actor: Actor, item: Item): Boolean {
    val loc = item.location
    val box = container
    return if (box != null) loc == ItemLocation.Contained(box.id)
    else loc is ItemLocation.InRoom && loc.room == w.roomOf(actor).id
  }

  private fun lag(w: World, actor: Actor, item: Item): Int? {
    if (Feature.TRACK !in w.features) return 0
    val band = bandOf(w, actor, item)
    return w.rules.getLag(RulesContext(w, actor, item = item, band = band), band)
  }

  private fun bandOf(w: World, actor: Actor, item: Item): Band {
    val ground = container ?: item
    return ground.groundIndex?.let { Band.between(w.indexOf(actor), it) } ?: Band.MELEE
  }

  override fun resolve(w: World, actor: Actor): Int {
    var maxLag = 0
    var capped = false
    val box = container
    for ((item, wanted) in wanted(w, actor)) {
      if (!atSource(w, actor, item)) continue
      val lag = lag(w, actor, item)
      if (lag == null) {
        w.send(actor, w.msg(KernelMsg.CANT_REACH, "item" to item.name))
        continue
      }
      val veto =
        w.dispatch(HookName.BEFORE_GET, item, actor.room, HookContext(actor = actor, item = item))
      if (veto != null) {
        w.send(actor, veto)
        continue
      }
      // The hook may have destroyed or moved the stack.
      if (item.id !in w.entities || !atSource(w, actor, item)) continue
      var n = minOf(wanted, item.count)
      if (!carriedSource) {
        val fit = w.carryFit(actor, item, n)
        if (fit < n) {
          capped = true
          n = fit
        }
        if (n <= 0) continue
      }
      maxLag = maxOf(maxLag, lag)
      val moved = w.moveItem(item, ItemLocation.Carried(actor.id), n)
      val desc = moved.describe()
      w.send(
        actor,
        if (box != null) w.msg(KernelMsg.GET_SELF_FROM, "item" to desc, "container" to box.name)
        else w.msg(KernelMsg.GET_SELF, "item" to desc),
      )
      w.tellSight(
        actor.room,
        "get",
        actor,
        w.msg(KernelMsg.GET_ROOM, "actor" to actor.name.cap(), "item" to desc),
        actor,
      )
      w.dispatch(HookName.ON_GET, moved, actor.room, HookContext(actor = actor, item = moved))
      if (moved.id in w.entities) w.stack(moved)
    }
    if (capped) w.tell(actor, KernelMsg.CANT_CARRY)
    return maxLag
  }
}

/**
 * `give <what> [to] <target>`: hands one item, a count, or all matching items from the inventory
 * over; fires `on_give` once per stack moved. The items are resolved when the action runs, so
 * several queued gives each act on the state the previous left.
 */
private class GiveAction(private val spec: ItemSpec, private val targetStr: String) : Action {
  override fun validate(w: World, actor: Actor): String? {
    val target = w.findActor(actor, targetStr) ?: return w.msg(KernelMsg.NOT_HERE)
    if (target === actor) return w.msg(KernelMsg.GIVE_ALREADY)
    val matches = matchItems(actor.inventory, spec)
    if (matches.isEmpty()) return w.msg(KernelMsg.NOT_CARRYING)
    if (matches.none { w.carryFit(target, it.first, 1) > 0 })
      return w.msg(KernelMsg.GIVE_FULL, "target" to target.name)
    return null
  }

  override fun resolve(w: World, actor: Actor): Int {
    val target = w.findActor(actor, targetStr) ?: return 0
    var capped = false
    for ((item, wanted) in matchItems(actor.inventory, spec)) {
      // An on_give hook for an earlier stack may have destroyed or moved this one.
      if (item.id !in w.entities || item.location != ItemLocation.Carried(actor.id)) continue
      val refusal =
        w.restrictionRefusal(
          RestrictionPoint.GIVE,
          RestrictionRequest(
            point = RestrictionPoint.GIVE,
            actor = actor,
            item = item,
            target = target,
          ),
        )
      if (refusal != null) {
        w.send(actor, refusal)
        continue
      }
      var n = minOf(wanted, item.count)
      val fit = w.carryFit(target, item, n)
      if (fit < n) {
        capped = true
        n = fit
      }
      if (n <= 0) continue
      val moved = w.moveItem(item, ItemLocation.Carried(target.id), n)
      val desc = moved.describe()
      w.tell(actor, KernelMsg.GIVE_SELF, "item" to desc, "target" to target.name)
      w.tell(target, KernelMsg.GIVE_TARGET, "actor" to actor.name.cap(), "item" to desc)
      w.tellSight(
        actor.room,
        "give",
        actor,
        w.msg(
          KernelMsg.GIVE_ROOM,
          "actor" to actor.name.cap(),
          "item" to desc,
          "target" to target.name,
        ),
        actor,
        target,
      )
      w.dispatch(
        HookName.ON_GIVE,
        moved,
        actor.room,
        HookContext(actor = actor, item = moved, target = target),
      )
      if (moved.id in w.entities) w.stack(moved)
    }
    if (capped) w.tell(actor, KernelMsg.GIVE_FULL, "target" to target.name)
    return 0
  }
}

/**
 * `put <what> [in|into] <container>`: the container is carried or on the ground within reach, and
 * open. Moves one item, a count, or all matching items, never the container itself; the items are
 * resolved when the action runs, so several queued puts each act on the state the previous left.
 */
private class PutAction(private val spec: ItemSpec, private val boxStr: String) : Action {
  override fun validate(w: World, actor: Actor): String? {
    val box =
      w.findItem(actor, boxStr, inventory = true, ground = true) ?: return w.msg(KernelMsg.DONT_SEE)
    val matches = matchItems(actor.inventory, spec, exclude = box)
    if (matches.isEmpty()) {
      if (
        !spec.all &&
          box.location == ItemLocation.Carried(actor.id) &&
          spec.keyword != null &&
          box.matches(spec.keyword)
      )
        return w.msg(KernelMsg.PUT_ITSELF)
      return w.msg(KernelMsg.NOT_CARRYING)
    }
    val state = box.container ?: return w.msg(KernelMsg.NOT_CONTAINER, "item" to box.name)
    if (!w.isPresent(box, w.roomOf(actor))) return w.msg(KernelMsg.DONT_SEE)
    w.reach(actor, box)?.let {
      return it
    }
    if (state.state != DoorState.OPEN) return w.msg(KernelMsg.PUT_CLOSED, "container" to box.name)
    if (matches.none { w.mergesInto(it.first, box) || box.contents.size < state.capacity })
      return w.msg(KernelMsg.PUT_FULL, "container" to box.name)
    val free = state.maxWeight - box.contents.sumOf { it.weight }
    if (matches.none { fitWithin(free, it.first, 1) > 0 })
      return w.msg(
        KernelMsg.PUT_WONT_FIT,
        "item" to matches.first().first.name,
        "container" to box.name,
      )
    return null
  }

  override fun resolve(w: World, actor: Actor): Int {
    val box = w.findItem(actor, boxStr, inventory = true, ground = true) ?: return 0
    val state = box.container ?: return 0
    var full = false
    var tooHeavy: String? = null
    for ((item, wanted) in matchItems(actor.inventory, spec, exclude = box)) {
      if (item.location != ItemLocation.Carried(actor.id)) continue
      val refusal =
        w.restrictionRefusal(
          RestrictionPoint.PUT,
          RestrictionRequest(point = RestrictionPoint.PUT, actor = actor, item = item, target = box),
        )
      if (refusal != null) {
        w.send(actor, refusal)
        continue
      }
      if (box.contents.size >= state.capacity && !w.mergesInto(item, box)) {
        full = true
        continue
      }
      var n = minOf(wanted, item.count)
      val free = state.maxWeight - box.contents.sumOf { it.weight }
      val fit = fitWithin(free, item, n)
      if (fit < n) {
        tooHeavy = tooHeavy ?: item.name
        n = fit
      }
      if (n <= 0) continue
      val moved = w.moveItem(item, ItemLocation.Contained(box.id), n)
      val desc = moved.describe()
      w.tell(actor, KernelMsg.PUT_SELF, "item" to desc, "container" to box.name)
      w.tellSight(
        actor.room,
        "put",
        actor,
        w.msg(
          KernelMsg.PUT_ROOM,
          "actor" to actor.name.cap(),
          "item" to desc,
          "container" to box.name,
        ),
        actor,
      )
      w.stack(moved)
    }
    if (tooHeavy != null)
      w.tell(actor, KernelMsg.PUT_WONT_FIT, "item" to tooHeavy, "container" to box.name)
    else if (full) w.tell(actor, KernelMsg.PUT_FULL, "container" to box.name)
    return 0
  }
}

/** How many of the [n] wanted units of [item] fit within [free] weight units. */
private fun fitWithin(free: Int, item: Item, n: Int): Int =
  if (item.proto.stackable) {
    if (item.proto.weight <= 0) n else (free / item.proto.weight).coerceAtLeast(0)
  } else {
    if (item.weight <= free) 1 else 0
  }

/** How many of the [n] wanted units of [item] fit within [actor]'s remaining carry limit. */
internal fun World.carryFit(actor: Actor, item: Item, n: Int): Int =
  fitWithin(
    rules.carryLimit(RulesContext(this, actor, item = item)) - actor.carriedWeight(),
    item,
    n,
  )

/** True if [item] would merge into a stack in [box], taking no new slot. */
private fun World.mergesInto(item: Item, box: Item): Boolean {
  if (!item.canStack()) return false
  return box.contents.any {
    it !== item &&
      it.protoLabel == item.protoLabel &&
      it.canStack() &&
      it.decayAt == item.decayAt &&
      it.customAttributes == item.customAttributes
  }
}

/**
 * Null if [actor] can reach [item] (carried, or on the ground within `get_lag` reach), else why
 * not. Without the track everything is within reach.
 */
internal fun World.reach(actor: Actor, item: Item): String? {
  if (Feature.TRACK !in features) return null
  val index = item.groundIndex ?: return null
  val band = Band.between(indexOf(actor), index)
  return if (rules.getLag(RulesContext(this, actor, item = item, band = band), band) == null)
    msg(KernelMsg.CANT_REACH, "item" to item.name)
  else null
}

/**
 * The equipment slot [wanted] fits for [actor]: the slot itself, or for a family such as `finger`,
 * the first free `finger_*` slot (else the first one, to swap). Null if the rules have no such
 * slot.
 */
internal fun World.slotFor(actor: Actor, wanted: String): String? {
  val slots = equipmentSlots()
  if (wanted in slots) return wanted
  val fam = rules.slotFamilies()[wanted]
  if (fam != null) {
    return fam.firstOrNull { actor.equipped(it) == null } ?: fam.firstOrNull()
  }
  val family = slots.filter { it.startsWith("${wanted}_") }
  return family.firstOrNull { actor.equipped(it) == null } ?: family.firstOrNull()
}

/**
 * True if this item does nothing but open locks: `kind = "key"` and no slot, container, commands,
 * dice, wear effects or affects. Such keys go on the keyring instead of the inventory listing.
 */
internal val ItemProto.isKeyOnly: Boolean
  get() =
    kind == "key" &&
      wear == null &&
      container == null &&
      commands.isEmpty() &&
      dice == null &&
      statDeltas.isEmpty() &&
      senseDeltas.isEmpty() &&
      equipAffects.isEmpty() &&
      affects.isEmpty() &&
      modifiers.isEmpty()

/**
 * True if [this] goes on the keyring: its prototype is key-only and this instance has gained no
 * affects or tags of its own.
 */
internal val Item.onKeyring: Boolean
  get() = proto.isKeyOnly && affects.isEmpty() && addedTags.isEmpty()

/** True if [this] carries or wears an item of prototype [key], keyring keys included. */
internal fun Actor.hasKey(key: Label): Boolean =
  inventory.any { it.protoLabel == key } || equipment.values.any { it.protoLabel == key }

/** The other side of [exit], if it leads back here with a door. */
internal fun World.reverseExit(
  room: gups.core.model.Room,
  exit: Exit,
): Pair<gups.core.model.Room, Exit>? {
  val other = room(exit.to)
  val back =
    other.exits.values.firstOrNull { it.to == room.label && it.door != null } ?: return null
  return other to back
}

// ---- Finding things and describing rooms ------------------------------------------------------

/** Finds an actor in [viewer]'s room by keyword; `2.goblin` picks the second match. */
fun World.findActor(viewer: Actor, keyword: String): Actor? {
  val (n, word) = ordinal(keyword)
  if (word == "self" || word == "me") return viewer
  val room = roomOf(viewer)
  // Dormant actors are inert to simulated ones.
  return room.actorIndex.values
    .filter { it !== viewer && isPresent(it, room) && it.matches(word) && isSimulated(it) }
    .getOrNull(n - 1)
}

fun World.findItem(viewer: Actor, keyword: String, inventory: Boolean, ground: Boolean): Item? {
  if (keyword.isBlank()) return null
  val (n, word) = ordinal(keyword)
  val pool = buildList {
    if (inventory) addAll(viewer.inventory)
    if (ground) addAll(roomOf(viewer).items)
  }
  return pool.filter { it.matches(word) }.getOrNull(n - 1)
}

private fun ordinal(keyword: String): Pair<Int, String> {
  val t = keyword.trim().lowercase()
  val dot = t.indexOf('.')
  if (dot > 0)
    t.substring(0, dot).toIntOrNull()?.let {
      return it to t.substring(dot + 1)
    }
  return 1 to t
}

/** `look`: the room, its exits, and actors and items grouped by band when the world has a track. */
fun World.describeRoom(viewer: Actor): String {
  val room = viewer.room
  val lines = mutableListOf(room.name)
  if (room.proto.desc.isNotEmpty()) lines += room.proto.desc
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
  lines +=
    if (exits.isEmpty()) msg(KernelMsg.LOOK_NO_EXITS)
    else msg(KernelMsg.LOOK_EXITS, "exits" to exits)
  val others = room.actors.filter { it !== viewer }
  if (Feature.TRACK !in features) {
    if (others.isNotEmpty())
      lines += msg(KernelMsg.LOOK_HERE_ACTORS, "list" to others.joinToString(", ") { it.name })
    if (room.items.isNotEmpty())
      lines +=
        msg(KernelMsg.LOOK_HERE_ITEMS, "list" to room.items.joinToString(", ") { it.describe() })
    return lines.joinToString("\n")
  }
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
  val byBand = others.groupBy { Band.between(viewer.trackIndex, it.trackIndex) }
  for (band in Band.entries) {
    byBand[band]?.let { list ->
      lines +=
        msg(
          KernelMsg.LOOK_ACTORS,
          "where" to words.getValue(band),
          "list" to list.joinToString(", ") { it.name },
        )
    }
  }
  val items = room.items.groupBy { Band.between(viewer.trackIndex, it.groundIndex ?: 0) }
  for (band in Band.entries) {
    items[band]?.let { list ->
      lines +=
        msg(
          KernelMsg.LOOK_ITEMS,
          "where" to ground.getValue(band),
          "list" to list.joinToString(", ") { it.describe() },
        )
    }
  }
  return lines.joinToString("\n")
}
