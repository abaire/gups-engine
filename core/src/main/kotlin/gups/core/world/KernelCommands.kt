package gups.core.world

import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.Exit
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Player
import gups.core.rules.RulesContext

/** The commands every world has. Their aliases come from the world. */
internal object KernelCommands {
  fun all(): List<CommandDef> =
    listOf(
      // Fast lane.
      FastCommand("look") { look(it) },
      FastCommand("say") { spoken(it, ::say) },
      FastCommand("tell") { spoken(it, ::tell) },
      FastCommand("gossip") { spoken(it, ::gossip) },
      FastCommand("shout") { spoken(it, ::shout) },
      FastCommand("whisper") { spoken(it, ::whisper) },
      FastCommand("score") { score(it) },
      FastCommand("inventory") { inventory(it) },
      FastCommand("equipment") { equipment(it) },
      FastCommand("keyring") { keyring(it) },
      FastCommand("who") { who(it) },
      FastCommand("help") { help(it) },
      FastCommand("stop") { stop(it) },
      FastCommand("clear") { stop(it) },
      FastCommand("quit") { quit(it) },
      // Tick lane.
      TickCommand("get") { planGet(it) },
      TickCommand("drop") { planDrop(it) },
      TickCommand("give") { planGive(it) },
      TickCommand("put") { planPut(it) },
      TickCommand("wear") { planWear(it, wield = false) },
      TickCommand("wield") { planWear(it, wield = true) },
      TickCommand("remove") { planRemove(it) },
      TickCommand("use") { planUse(it) },
      TickCommand("open") { planDoor(it, DoorVerb.OPEN) },
      TickCommand("close") { planDoor(it, DoorVerb.CLOSE) },
      TickCommand("lock") { planDoor(it, DoorVerb.LOCK) },
      TickCommand("unlock") { planDoor(it, DoorVerb.UNLOCK) },
    ) + Tools.all()

  fun move(dir: Direction): TickCommand = TickCommand(dir.name) { MoveAction(dir) }

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
        w.tell(c.actor, KernelMsg.LOOK_CONTAINS, "items" to inside.joinToString(", ") { it.name })
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

  /** Heard in the room and in every room whose exit toward it lets noise through. */
  private fun shout(c: CommandCall) {
    val w = c.world
    if (c.args.isEmpty()) return w.tell(c.actor, KernelMsg.SHOUT_WHAT)
    w.tell(c.actor, KernelMsg.SHOUT_SELF, "text" to c.args)
    w.echo(
      c.actor.room,
      w.msg(KernelMsg.SHOUT_ROOM, "actor" to c.actor.name.cap(), "text" to c.args),
      c.actor,
    )
    val near =
      w.inbound(c.actor.room)
        .filter { (_, exit) -> Channel.NOISE !in exit.effectiveSensesBlocked }
        .map { it.first }
        .distinct()
    for (room in near) w.echo(room, w.msg(KernelMsg.SHOUT_NEAR, "text" to c.args))
    w.channel("shout", c.actor, c.args, c.actor.room.actors + near.flatMap { it.actors })
    w.perceive("shout", Channel.NOISE, c.actor.room, c.actor, c.args)
  }

  private fun whisper(c: CommandCall) {
    val w = c.world
    val (name, text) = splitFirst(c.args)
    if (name.isEmpty() || text.isEmpty()) return w.tell(c.actor, KernelMsg.WHISPER_USAGE)
    val target = w.findActor(c.actor, name) ?: return w.tell(c.actor, KernelMsg.NOT_HERE)
    w.tell(c.actor, KernelMsg.WHISPER_SELF, "target" to target.name, "text" to text)
    w.tell(target, KernelMsg.WHISPER_TARGET, "actor" to c.actor.name.cap(), "text" to text)
    w.channel("whisper", c.actor, text, setOf(c.actor, target))
    w.echo(
      c.actor.room,
      w.msg(KernelMsg.WHISPER_ROOM, "actor" to c.actor.name.cap(), "target" to target.name),
      c.actor,
      target,
    )
  }

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
          "resists" to
            schema.damageTypes.joinToString("  ") { "$it ${a.resists[DamageType(it)] ?: 0}" },
        )
    if (Feature.LEVELING in w.features)
      lines += w.msg(LevelingMsg.SCORE_LEVEL, "level" to a.level, "xp" to a.xp)
    w.send(a, lines.joinToString("\n"))
  }

  private fun inventory(c: CommandCall) {
    // Keys live on the keyring, so they don't clutter the inventory.
    val items = c.actor.inventory.filterNot { it.proto.isKeyOnly }
    val w = c.world
    w.send(
      c.actor,
      if (items.isEmpty()) w.msg(KernelMsg.INVENTORY_EMPTY)
      else
        w.msg(
          KernelMsg.INVENTORY_LIST,
          "items" to items.joinToString("\n") { w.msg(KernelMsg.INVENTORY_ITEM, "item" to it.name) },
        ),
    )
  }

  private fun keyring(c: CommandCall) {
    val keys = c.actor.inventory.filter { it.proto.isKeyOnly }
    val w = c.world
    w.send(
      c.actor,
      if (keys.isEmpty()) w.msg(KernelMsg.KEYRING_EMPTY)
      else
        w.msg(
          KernelMsg.KEYRING_LIST,
          "items" to keys.joinToString("\n") { w.msg(KernelMsg.INVENTORY_ITEM, "item" to it.name) },
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
    val defs =
      (c.world.commands.engineCommands +
          c.world.commands.globalCommands +
          c.world.commands.pluginCommands +
          c.world.commands.toolCommands)
        .distinct()
        .filter { it.allows(c.actor) }
    val fast = defs.filter { it.lane == Lane.FAST }.joinToString(" ") { it.name }
    val tick = defs.filter { it.lane == Lane.TICK }.joinToString(" ") { it.name }
    c.world.tell(c.actor, KernelMsg.HELP_LIST, "instant" to fast, "actions" to tick)
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
    val (what, from) =
      c.args.split(Regex("\\s+from\\s+"), limit = 2).let { it[0].trim() to it.getOrNull(1)?.trim() }
    if (what.isEmpty()) return null.also { w.tell(c.actor, KernelMsg.GET_WHAT) }
    val container =
      from?.let {
        w.findItem(c.actor, it, inventory = true, ground = true)
          ?: return null.also { w.tell(c.actor, KernelMsg.DONT_SEE) }
      }
    return GetAction(what, container)
  }

  private fun planDrop(c: CommandCall): Action? {
    val w = c.world
    val item =
      w.findItem(c.actor, c.args, inventory = true, ground = false)
        ?: return null.also { w.tell(c.actor, KernelMsg.NOT_CARRYING) }
    return object : Action {
      override fun validate(w: World, actor: Actor): String? =
        if (item.location != ItemLocation.Carried(actor.id)) w.msg(KernelMsg.NOT_CARRYING) else null

      override fun veto(w: World, actor: Actor): String? =
        w.dispatch(HookName.BEFORE_DROP, item, actor.room, HookContext(actor = actor, item = item))

      override fun resolve(w: World, actor: Actor): Int {
        w.moveItem(item, ItemLocation.InRoom(actor.room.id, actor.trackIndex))
        w.tell(actor, KernelMsg.DROP_SELF, "item" to item.name)
        w.echo(
          actor.room,
          w.msg(KernelMsg.DROP_ROOM, "actor" to actor.name.cap(), "item" to item.name),
          actor,
        )
        w.dispatch(HookName.ON_DROP, item, actor.room, HookContext(actor = actor, item = item))
        if (item.id in w.entities) w.stack(item)
        return 0
      }
    }
  }

  /**
   * `give <item> <actor>` (also `give <item> to <actor>`): hands a carried item over; fires
   * `on_give`.
   */
  private fun planGive(c: CommandCall): Action? {
    val w = c.world
    val words = c.args.split(Regex("\\s+")).filter { it.isNotEmpty() && it != "to" }
    if (words.size < 2) return null.also { w.tell(c.actor, KernelMsg.GIVE_USAGE) }
    val item =
      w.findItem(c.actor, words.dropLast(1).joinToString(" "), inventory = true, ground = false)
        ?: return null.also { w.tell(c.actor, KernelMsg.NOT_CARRYING) }
    val target =
      w.findActor(c.actor, words.last()) ?: return null.also { w.tell(c.actor, KernelMsg.NOT_HERE) }
    return object : Action {
      override fun validate(w: World, actor: Actor): String? =
        when {
          item.location != ItemLocation.Carried(actor.id) -> w.msg(KernelMsg.NOT_CARRYING)
          target === actor -> w.msg(KernelMsg.GIVE_ALREADY)
          !w.isPresent(target, w.roomOf(actor)) -> w.msg(KernelMsg.NOT_HERE)
          target.carriedWeight() + item.weight >
            w.rules.carryLimit(RulesContext(w, target, item = item)) ->
            w.msg(KernelMsg.GIVE_FULL, "target" to target.name)
          else -> null
        }

      override fun resolve(w: World, actor: Actor): Int {
        w.moveItem(item, ItemLocation.Carried(target.id))
        w.tell(actor, KernelMsg.GIVE_SELF, "item" to item.name, "target" to target.name)
        w.tell(target, KernelMsg.GIVE_TARGET, "actor" to actor.name.cap(), "item" to item.name)
        w.echo(
          actor.room,
          w.msg(
            KernelMsg.GIVE_ROOM,
            "actor" to actor.name.cap(),
            "item" to item.name,
            "target" to target.name,
          ),
          actor,
          target,
        )
        w.dispatch(
          HookName.ON_GIVE,
          item,
          actor.room,
          HookContext(actor = actor, item = item, target = target),
        )
        if (item.id in w.entities) w.stack(item)
        return 0
      }
    }
  }

  /**
   * `put <item> in <container>`: the container is carried or within reach on the ground, and open.
   */
  private fun planPut(c: CommandCall): Action? {
    val w = c.world
    val parts = c.args.split(Regex("\\s+(in|into)\\s+"), limit = 2)
    if (parts.size < 2 || parts[0].isBlank())
      return null.also { w.tell(c.actor, KernelMsg.PUT_USAGE) }
    val item =
      w.findItem(c.actor, parts[0], inventory = true, ground = false)
        ?: return null.also { w.tell(c.actor, KernelMsg.NOT_CARRYING) }
    val box =
      w.findItem(c.actor, parts[1], inventory = true, ground = true)
        ?: return null.also { w.tell(c.actor, KernelMsg.DONT_SEE) }
    return object : Action {
      override fun validate(w: World, actor: Actor): String? {
        if (item.location != ItemLocation.Carried(actor.id)) return w.msg(KernelMsg.NOT_CARRYING)
        if (item === box) return w.msg(KernelMsg.PUT_ITSELF)
        val state = box.container ?: return w.msg(KernelMsg.NOT_CONTAINER, "item" to box.name)
        if (!w.isPresent(box, w.roomOf(actor))) return w.msg(KernelMsg.DONT_SEE)
        w.reach(actor, box)?.let {
          return it
        }
        if (state.state != DoorState.OPEN)
          return w.msg(KernelMsg.PUT_CLOSED, "container" to box.name)
        if (box.contents.size >= state.capacity)
          return w.msg(KernelMsg.PUT_FULL, "container" to box.name)
        if (box.contents.sumOf { it.weight } + item.weight > state.maxWeight)
          return w.msg(KernelMsg.PUT_WONT_FIT, "item" to item.name, "container" to box.name)
        return null
      }

      override fun resolve(w: World, actor: Actor): Int {
        w.moveItem(item, ItemLocation.Contained(box.id))
        w.tell(actor, KernelMsg.PUT_SELF, "item" to item.name, "container" to box.name)
        w.echo(
          actor.room,
          w.msg(
            KernelMsg.PUT_ROOM,
            "actor" to actor.name.cap(),
            "item" to item.name,
            "container" to box.name,
          ),
          actor,
        )
        w.stack(item)
        return 0
      }
    }
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
        if (w.slotFor(actor, wanted) == null)
          return w.msg(KernelMsg.WEAR_CANT_WEAR, "item" to item.name)
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
        w.echo(
          actor.room,
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
   * `use <item> [target]`: an item carried or worn whose `before_use` doesn't veto; fires its
   * `on_use`. The target is an actor in the room, or else an item carried or on the ground.
   */
  private fun planUse(c: CommandCall): Action? {
    val w = c.world
    val words = c.args.split(Regex("\\s+"), limit = 2)
    if (words[0].isEmpty()) return null.also { w.tell(c.actor, KernelMsg.USE_WHAT) }
    val item =
      (c.actor.equipment.values.firstOrNull { it.matches(words[0]) }
        ?: w.findItem(c.actor, words[0], inventory = true, ground = false))
        ?: return null.also { w.tell(c.actor, KernelMsg.NOT_CARRYING) }
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
        if (!held) return w.msg(KernelMsg.NOT_CARRYING)
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
      override fun validate(w: World, actor: Actor): String? =
        if ((item.location as? ItemLocation.Equipped)?.actor != actor.id) w.msg(KernelMsg.NOT_USING)
        else null

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

      override fun resolve(w: World, actor: Actor): Int {
        val exit = actor.room.exits.getValue(dir)
        w.setDoor(actor.room, dir, verb.to)
        w.send(actor, w.msg(KernelMsg.DOOR_SELF.getValue(verb), "direction" to dir))
        w.echo(
          actor.room,
          w.msg(
            KernelMsg.DOOR_ROOM.getValue(verb),
            "actor" to actor.name.cap(),
            "direction" to dir,
          ),
          actor,
        )
        w.reverseExit(actor.room, exit)?.let { (other, back) ->
          w.echo(
            other,
            w.msg(
              if (verb.to == DoorState.OPEN) KernelMsg.DOOR_OTHER_OPENS
              else KernelMsg.DOOR_OTHER_CLOSES,
              "direction" to back.direction,
            ),
          )
        }
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

      override fun resolve(w: World, actor: Actor): Int {
        box.container!!.state = verb.to
        box.dirty = true
        w.send(actor, w.msg(KernelMsg.BOX_SELF.getValue(verb), "item" to box.name))
        w.echo(
          actor.room,
          w.msg(KernelMsg.BOX_ROOM.getValue(verb), "actor" to actor.name.cap(), "item" to box.name),
          actor,
        )
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

internal enum class DoorVerb(val word: String, val from: Set<DoorState>, val to: DoorState) {
  OPEN("open", setOf(DoorState.CLOSED), DoorState.OPEN),
  CLOSE("close", setOf(DoorState.OPEN), DoorState.CLOSED),
  LOCK("lock", setOf(DoorState.CLOSED), DoorState.LOCKED),
  UNLOCK("unlock", setOf(DoorState.LOCKED), DoorState.CLOSED),
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
  val pct = a.vitalMap.values.filter { it.max > 0 }.minOfOrNull { it.current * 100 / it.max } ?: 100
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

/** `get <item> [from <container>]`, with lag by band from `rules.get_lag`. */
internal class GetAction(
  private val what: String,
  private val container: Item?,
  private val fixed: Item? = null,
) : Action {
  private var item: Item? = null
  private var extraLag = 0

  override fun validate(w: World, actor: Actor): String? {
    val room = w.roomOf(actor)
    val source = container
    val found: Item =
      when {
        fixed != null ->
          fixed.takeIf { it.location is ItemLocation.InRoom && w.isPresent(it, room) }
        source == null -> room.items.firstOrNull { it.matches(what) }
        else -> {
          if (!w.isPresent(source, room)) return w.msg(KernelMsg.DONT_SEE)
          val box = source.container ?: return w.msg(KernelMsg.NOT_CONTAINER, "item" to source.name)
          if (box.state != DoorState.OPEN)
            return w.msg(KernelMsg.PUT_CLOSED, "container" to source.name)
          source.contents.firstOrNull { it.matches(what) }
        }
      } ?: return w.msg(KernelMsg.DONT_SEE)
    if (Feature.TRACK in w.features) {
      val ground = container ?: found
      val band = ground.groundIndex?.let { Band.between(w.indexOf(actor), it) } ?: Band.MELEE
      extraLag =
        w.rules.getLag(RulesContext(w, actor, item = found, band = band), band)
          ?: return w.msg(KernelMsg.CANT_REACH, "item" to found.name)
    }
    if (
      actor.carriedWeight() + found.weight >
        w.rules.carryLimit(RulesContext(w, actor, item = found))
    ) {
      return w.msg(KernelMsg.CANT_CARRY)
    }
    item = found
    return null
  }

  override fun veto(w: World, actor: Actor): String? =
    w.dispatch(HookName.BEFORE_GET, item, actor.room, HookContext(actor = actor, item = item))

  override fun resolve(w: World, actor: Actor): Int {
    val it = item!!
    w.moveItem(it, ItemLocation.Carried(actor.id))
    w.send(
      actor,
      if (container != null)
        w.msg(KernelMsg.GET_SELF_FROM, "item" to it.name, "container" to container.name)
      else w.msg(KernelMsg.GET_SELF, "item" to it.name),
    )
    w.echo(
      actor.room,
      w.msg(KernelMsg.GET_ROOM, "actor" to actor.name.cap(), "item" to it.name),
      actor,
    )
    w.dispatch(HookName.ON_GET, it, actor.room, HookContext(actor = actor, item = it))
    if (it.id in w.entities) w.stack(it)
    return extraLag
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
  val family = slots.filter { it.startsWith("${wanted}_") }
  return family.firstOrNull { actor.equipped(it) == null } ?: family.firstOrNull()
}

/**
 * True if this item does nothing but open locks: `kind = "key"` and no slot, container, commands,
 * dice or wear effects. Such keys go on the keyring instead of the inventory listing.
 */
internal val ItemProto.isKeyOnly: Boolean
  get() =
    kind == "key" &&
      wear == null &&
      container == null &&
      commands.isEmpty() &&
      dice == null &&
      statDeltas.isEmpty() &&
      equipAffects.isEmpty() &&
      modifiers.isEmpty()

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
      lines += msg(KernelMsg.LOOK_HERE_ITEMS, "list" to room.items.joinToString(", ") { it.name })
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
          "list" to list.joinToString(", ") { it.name },
        )
    }
  }
  return lines.joinToString("\n")
}
