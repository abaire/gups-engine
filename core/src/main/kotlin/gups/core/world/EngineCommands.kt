package gups.core.world

import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.Exit
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Player
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.TargetKind
import gups.core.rules.RulesContext

/** What a `silenced` actor is told when it tries to speak or cast. */
private const val SILENCED = "You can't get the words out!"

/** The engine's player commands. Their aliases come from the world. */
internal object EngineCommands {
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
      TickCommand("kill") { planKill(it) },
      TickCommand("cast") { planCast(it) },
      TickCommand("advance") { planAdvance(it) },
      TickCommand("retreat") { planRetreat(it) },
      TickCommand("flee") { FleeAction },
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
      w.send(c.actor, desc ?: "You see nothing special about ${actor.name}.")
      return w.send(c.actor, "${actor.name.cap()} ${healthWord(actor)}.")
    }
    val item =
      w.findItem(c.actor, c.args, inventory = true, ground = true)
        ?: return w.send(c.actor, "You don't see that here.")
    w.send(c.actor, item.proto.desc.ifEmpty { "You see nothing special about ${item.name}." })
    item.container?.let { box ->
      if (box.state != DoorState.OPEN) return w.send(c.actor, "It is closed.")
      val inside = item.contents
      w.send(
        c.actor,
        if (inside.isEmpty()) "It is empty."
        else "It contains: " + inside.joinToString(", ") { it.name } + ".",
      )
    }
  }

  /**
   * Runs a speech command unless the speaker is `silenced`, which stops all speech, and with it any
   * dialogue or quest step a mob would run on hearing it.
   */
  private fun spoken(c: CommandCall, speak: (CommandCall) -> Unit) {
    if (c.actor.hasFlag("silenced")) return c.world.send(c.actor, SILENCED)
    speak(c)
  }

  private fun say(c: CommandCall) {
    if (c.args.isEmpty()) return c.world.send(c.actor, "Say what?")
    c.world.say(c.actor, c.args)
  }

  private fun tell(c: CommandCall) {
    val w = c.world
    val (name, text) = splitFirst(c.args)
    if (name.isEmpty() || text.isEmpty()) return w.send(c.actor, "Tell whom what?")
    val player = w.players.firstOrNull { it.name.equals(name, ignoreCase = true) }
    if (player != null) {
      w.send(player, "${c.actor.name.cap()} tells you, '$text'")
      w.channel("tell", c.actor, text, setOf(c.actor, player))
      return w.send(c.actor, "You tell ${player.name}, '$text'")
    }
    val mob = w.findActor(c.actor, name) ?: return w.send(c.actor, "Nobody by that name is around.")
    w.send(c.actor, "You tell ${mob.name}, '$text'")
    w.invokeOn(mob, HookName.ON_TELL, HookContext(actor = c.actor, text = text))
  }

  private fun gossip(c: CommandCall) {
    if (c.args.isEmpty()) return c.world.send(c.actor, "Gossip what?")
    if (c.actor is Player && c.world.refuseRestricted(c.actor, "gossip")) return
    for (p in c.world.players) c.world.send(p, "[gossip] ${c.actor.name}: ${c.args}")
    c.world.channel("gossip", c.actor, c.args, c.world.players)
  }

  /** Heard in the room and in every room whose exit toward it lets noise through. */
  private fun shout(c: CommandCall) {
    val w = c.world
    if (c.args.isEmpty()) return w.send(c.actor, "Shout what?")
    w.send(c.actor, "You shout, '${c.args}'")
    w.echo(c.actor.room, "${c.actor.name.cap()} shouts, '${c.args}'", c.actor)
    val near =
      w.inbound(c.actor.room)
        .filter { (_, exit) -> Channel.NOISE !in exit.effectiveSensesBlocked }
        .map { it.first }
        .distinct()
    for (room in near) w.echo(room, "Someone nearby shouts, '${c.args}'")
    w.channel("shout", c.actor, c.args, c.actor.room.actors + near.flatMap { it.actors })
    w.perceive("shout", Channel.NOISE, c.actor.room, c.actor, c.args)
  }

  private fun whisper(c: CommandCall) {
    val w = c.world
    val (name, text) = splitFirst(c.args)
    if (name.isEmpty() || text.isEmpty()) return w.send(c.actor, "Whisper what to whom?")
    val target = w.findActor(c.actor, name) ?: return w.send(c.actor, "They aren't here.")
    w.send(c.actor, "You whisper to ${target.name}, '$text'")
    w.send(target, "${c.actor.name.cap()} whispers to you, '$text'")
    w.channel("whisper", c.actor, text, setOf(c.actor, target))
    w.echo(
      c.actor.room,
      "${c.actor.name.cap()} whispers something to ${target.name}.",
      c.actor,
      target,
    )
  }

  private fun score(c: CommandCall) {
    val a = c.actor
    val stats = gups.core.model.STAT_NAMES.joinToString("  ") { "$it ${a.stat(it)}" }
    c.world.send(
      a,
      """
            |${a.name}, level ${a.level} (${a.xp} xp)
            |HP ${a.hp}/${a.maxHp}  Mana ${a.mana}/${a.maxMana}  Move ${a.move}/${a.maxMove}
            |$stats  armor ${a.armor}
            """
        .trimMargin(),
    )
  }

  private fun inventory(c: CommandCall) {
    // Keys live on the keyring, so they don't clutter the inventory.
    val items = c.actor.inventory.filterNot { it.proto.isKeyOnly }
    c.world.send(
      c.actor,
      if (items.isEmpty()) "You are carrying nothing."
      else "You are carrying:\n" + items.joinToString("\n") { "  ${it.name}" },
    )
  }

  private fun keyring(c: CommandCall) {
    val keys = c.actor.inventory.filter { it.proto.isKeyOnly }
    c.world.send(
      c.actor,
      if (keys.isEmpty()) "Your keyring is empty."
      else "Your keyring holds:\n" + keys.joinToString("\n") { "  ${it.name}" },
    )
  }

  private fun equipment(c: CommandCall) {
    val eq = c.actor.equipment
    c.world.send(
      c.actor,
      if (eq.isEmpty()) "You are using nothing."
      else "You are using:\n" + eq.entries.joinToString("\n") { "  <${it.key}> ${it.value.name}" },
    )
  }

  private fun who(c: CommandCall) {
    val lines =
      c.world.players.map { p -> "  ${p.name}" + if (p.session == null) " (link-dead)" else "" }
    c.world.send(c.actor, "Players:\n" + lines.joinToString("\n"))
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
    c.world.send(c.actor, "Instant: $fast\nActions: $tick, and any exit name")
  }

  /** `stop`/`clear`: flush the queue and cancel a cast; combat and recovery stay. */
  private fun stop(c: CommandCall) {
    val queued = c.actor.actionQueue.size
    c.actor.actionQueue.clear()
    val cancelled = c.world.cancelCast(c.actor)
    c.world.send(
      c.actor,
      when {
        cancelled -> "You stop casting."
        queued > 0 -> "You clear your plans."
        else -> "You have nothing to stop."
      },
    )
  }

  private fun quit(c: CommandCall) {
    val p = c.actor as? Player ?: return
    if (p.isEngaged) return c.world.send(p, "No way! You're fighting for your life!")
    c.world.quit(p)
  }

  // ---- Tick lane ---------------------------------------------------------------------------

  private fun planKill(c: CommandCall): Action? {
    val w = c.world
    if (c.args.isEmpty()) return null.also { w.send(c.actor, "Kill whom?") }
    val target =
      w.findActor(c.actor, c.args) ?: return null.also { w.send(c.actor, "They aren't here.") }
    return KillAction(target)
  }

  private fun planCast(c: CommandCall): Action? {
    val w = c.world
    val (skillWord, targetWord) = splitSkill(c.args)
    if (skillWord.isEmpty()) return null.also { w.send(c.actor, "Cast what?") }
    val skill =
      c.actor.skills.keys.map(w.protos::skill).firstOrNull {
        it.name.startsWith(skillWord.lowercase())
      } ?: return null.also { w.send(c.actor, "You don't know that skill.") }
    return CastAction(skill, targetWord.ifEmpty { null })
  }

  private fun planAdvance(c: CommandCall): Action? {
    val w = c.world
    val target =
      (if (c.args.isEmpty()) w.nearestOpponent(c.actor) else w.findActor(c.actor, c.args))
        ?: return null.also { w.send(c.actor, "Advance toward whom?") }
    return AdvanceAction(target)
  }

  private fun planRetreat(c: CommandCall): Action? {
    val words = c.args.removePrefix("from ").trim()
    if (words.isEmpty()) return RetreatAction(null)
    val target =
      c.world.findActor(c.actor, words)
        ?: return null.also { c.world.send(c.actor, "They aren't here.") }
    return RetreatAction(target)
  }

  private fun planGet(c: CommandCall): Action? {
    val w = c.world
    val (what, from) =
      c.args.split(Regex("\\s+from\\s+"), limit = 2).let { it[0].trim() to it.getOrNull(1)?.trim() }
    if (what.isEmpty()) return null.also { w.send(c.actor, "Get what?") }
    val container =
      from?.let {
        w.findItem(c.actor, it, inventory = true, ground = true)
          ?: return null.also { w.send(c.actor, "You don't see that here.") }
      }
    return GetAction(what, container)
  }

  private fun planDrop(c: CommandCall): Action? {
    val w = c.world
    val item =
      w.findItem(c.actor, c.args, inventory = true, ground = false)
        ?: return null.also { w.send(c.actor, "You aren't carrying that.") }
    return object : Action {
      override fun validate(w: World, actor: Actor): String? =
        if (item.location != ItemLocation.Carried(actor.id)) "You aren't carrying that." else null

      override fun veto(w: World, actor: Actor): String? =
        w.dispatch(HookName.BEFORE_DROP, item, actor.room, HookContext(actor = actor, item = item))

      override fun resolve(w: World, actor: Actor): Int {
        w.moveItem(item, ItemLocation.InRoom(actor.room.id, actor.trackIndex))
        w.send(actor, "You drop ${item.name}.")
        w.echo(actor.room, "${actor.name.cap()} drops ${item.name}.", actor)
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
    if (words.size < 2) return null.also { w.send(c.actor, "Give what to whom?") }
    val item =
      w.findItem(c.actor, words.dropLast(1).joinToString(" "), inventory = true, ground = false)
        ?: return null.also { w.send(c.actor, "You aren't carrying that.") }
    val target =
      w.findActor(c.actor, words.last())
        ?: return null.also { w.send(c.actor, "They aren't here.") }
    return object : Action {
      override fun validate(w: World, actor: Actor): String? =
        when {
          item.location != ItemLocation.Carried(actor.id) -> "You aren't carrying that."
          target === actor -> "You already have it."
          !w.isPresent(target, w.roomOf(actor)) -> "They aren't here."
          target.carriedWeight() + item.weight >
            w.rules.carryLimit(RulesContext(w, target, item = item)) ->
            "${target.name.cap()} can't carry that much."
          else -> null
        }

      override fun resolve(w: World, actor: Actor): Int {
        w.moveItem(item, ItemLocation.Carried(target.id))
        w.send(actor, "You give ${item.name} to ${target.name}.")
        w.send(target, "${actor.name.cap()} gives you ${item.name}.")
        w.echo(
          actor.room,
          "${actor.name.cap()} gives ${item.name} to ${target.name}.",
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
      return null.also { w.send(c.actor, "Put what in what?") }
    val item =
      w.findItem(c.actor, parts[0], inventory = true, ground = false)
        ?: return null.also { w.send(c.actor, "You aren't carrying that.") }
    val box =
      w.findItem(c.actor, parts[1], inventory = true, ground = true)
        ?: return null.also { w.send(c.actor, "You don't see that here.") }
    return object : Action {
      override fun validate(w: World, actor: Actor): String? {
        if (item.location != ItemLocation.Carried(actor.id)) return "You aren't carrying that."
        if (item === box) return "You can't put something inside itself."
        val state = box.container ?: return "${box.name.cap()} is not a container."
        if (!w.isPresent(box, w.roomOf(actor))) return "You don't see that here."
        w.reach(actor, box)?.let {
          return it
        }
        if (state.state != DoorState.OPEN) return "${box.name.cap()} is closed."
        if (box.contents.size >= state.capacity) return "${box.name.cap()} is full."
        if (box.contents.sumOf { it.weight } + item.weight > state.maxWeight)
          return "${item.name.cap()} won't fit in ${box.name}."
        return null
      }

      override fun resolve(w: World, actor: Actor): Int {
        w.moveItem(item, ItemLocation.Contained(box.id))
        w.send(actor, "You put ${item.name} in ${box.name}.")
        w.echo(actor.room, "${actor.name.cap()} puts ${item.name} in ${box.name}.", actor)
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
        ?: return null.also { w.send(c.actor, "You aren't carrying that.") }
    val wanted = item.proto.wear
    if (wanted == null || (wield && wanted != "wield"))
      return null.also {
        w.send(c.actor, "You can't ${if (wield) "wield" else "wear"} ${item.name}.")
      }
    return object : Action {
      override fun validate(w: World, actor: Actor): String? {
        if (item.location != ItemLocation.Carried(actor.id)) return "You aren't carrying that."
        if (w.slotFor(actor, wanted) == null) return "You can't wear ${item.name}."
        return null
      }

      override fun resolve(w: World, actor: Actor): Int {
        val slot = w.slotFor(actor, wanted)!!
        actor.equipped(slot)?.let {
          w.moveItem(it, ItemLocation.Carried(actor.id))
          w.send(actor, "You stop using ${it.name}.")
        }
        w.moveItem(item, ItemLocation.Equipped(actor.id, slot))
        val verb = if (slot == "wield") "wield" else "wear"
        w.send(actor, "You $verb ${item.name}.")
        w.echo(actor.room, "${actor.name.cap()} ${verb}s ${item.name}.", actor)
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
    if (words[0].isEmpty()) return null.also { w.send(c.actor, "Use what?") }
    val item =
      (c.actor.equipment.values.firstOrNull { it.matches(words[0]) }
        ?: w.findItem(c.actor, words[0], inventory = true, ground = false))
        ?: return null.also { w.send(c.actor, "You aren't carrying that.") }
    val word = words.getOrNull(1)?.trim()?.removePrefix("on ")?.trim()?.ifEmpty { null }
    val target = word?.let { w.findActor(c.actor, it) }
    val targetItem =
      if (word != null && target == null) {
        w.findItem(c.actor, word, inventory = true, ground = true)
          ?: return null.also { w.send(c.actor, "You don't see that here.") }
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
        if (!held) return "You aren't carrying that."
        if (target != null && !w.isPresent(target, w.roomOf(actor))) return "They aren't here."
        if (!w.scripts.hasHook(item, HookName.ON_USE)) return "You can't use ${item.name}."
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
        ?: return null.also { w.send(c.actor, "You aren't using that.") }
    return object : Action {
      override fun validate(w: World, actor: Actor): String? =
        if ((item.location as? ItemLocation.Equipped)?.actor != actor.id) "You aren't using that."
        else null

      override fun resolve(w: World, actor: Actor): Int {
        w.moveItem(item, ItemLocation.Carried(actor.id))
        w.send(actor, "You stop using ${item.name}.")
        return 0
      }
    }
  }

  private enum class DoorVerb(val word: String, val from: Set<DoorState>, val to: DoorState) {
    OPEN("open", setOf(DoorState.CLOSED), DoorState.OPEN),
    CLOSE("close", setOf(DoorState.OPEN), DoorState.CLOSED),
    LOCK("lock", setOf(DoorState.CLOSED), DoorState.LOCKED),
    UNLOCK("unlock", setOf(DoorState.LOCKED), DoorState.CLOSED),
  }

  private fun planDoor(c: CommandCall, verb: DoorVerb): Action? {
    if (c.args.isEmpty()) return null.also { c.world.send(c.actor, "${verb.word.cap()} what?") }
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
        val door = w.roomOf(actor).exits[dir]?.door ?: return "There is no door $dir."
        if (door.state !in verb.from) return "The door $dir is ${door.state.name.lowercase()}."
        if (verb == DoorVerb.LOCK || verb == DoorVerb.UNLOCK) {
          val key = door.key ?: return "The door $dir has no lock."
          if (!actor.hasKey(key)) return "You don't have the key."
        }
        return null
      }

      override fun resolve(w: World, actor: Actor): Int {
        val exit = actor.room.exits.getValue(dir)
        w.setDoor(actor.room, dir, verb.to)
        w.send(actor, "You ${verb.word} the door $dir.")
        w.echo(actor.room, "${actor.name.cap()} ${verb.word}s the door $dir.", actor)
        w.reverseExit(actor.room, exit)?.let { (other, back) ->
          w.echo(
            other,
            "The door ${back.direction} ${if (verb.to == DoorState.OPEN) "opens" else "closes"}.",
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
        val state = box.container ?: return "${box.name.cap()} is not a container."
        if (!w.isPresent(box, w.roomOf(actor))) return "You don't see that here."
        w.reach(actor, box)?.let {
          return it
        }
        if (state.state !in verb.from)
          return "${box.name.cap()} is ${state.state.name.lowercase()}."
        if (verb == DoorVerb.LOCK || verb == DoorVerb.UNLOCK) {
          val key = state.key ?: return "${box.name.cap()} has no lock."
          if (!actor.hasKey(key)) return "You don't have the key."
        }
        return null
      }

      override fun resolve(w: World, actor: Actor): Int {
        box.container!!.state = verb.to
        box.dirty = true
        w.send(actor, "You ${verb.word} ${box.name}.")
        w.echo(actor.room, "${actor.name.cap()} ${verb.word}s ${box.name}.", actor)
        return 0
      }
    }

  // ---- Helpers -----------------------------------------------------------------------------

  fun healthWord(a: Actor): String {
    val pct = a.hp * 100 / a.maxHp.coerceAtLeast(1)
    return when {
      pct >= 100 -> "is in excellent condition"
      pct >= 75 -> "has a few scratches"
      pct >= 50 -> "is wounded"
      pct >= 25 -> "is badly hurt"
      pct > 0 -> "is in awful condition"
      else -> "is mortally wounded"
    }
  }

  private fun splitFirst(s: String): Pair<String, String> {
    val t = s.trim()
    val i = t.indexOfFirst { it.isWhitespace() }
    return if (i < 0) t to "" else t.substring(0, i) to t.substring(i + 1).trim()
  }

  /** `cast fire goblin` or `cast 'magic missile' goblin`. */
  private fun splitSkill(s: String): Pair<String, String> {
    val t = s.trim()
    val q = t.firstOrNull()
    if (q == '\'' || q == '"') {
      val end = t.indexOf(q, 1)
      if (end > 0) return t.substring(1, end).replace(' ', '_') to t.substring(end + 1).trim()
    }
    return splitFirst(t)
  }
}

/** `kill <target>`: engages and switches the primary target. */
internal class KillAction(private val target: Actor) : Action {
  override fun validate(w: World, actor: Actor): String? =
    when {
      target === actor -> "You can't attack yourself."
      !w.isPresent(target, w.roomOf(actor)) -> "They aren't here."
      actor.primaryTarget == target.id -> "You're already fighting ${target.name}!"
      else -> null
    }

  override fun resolve(w: World, actor: Actor): Int {
    w.startCombat(actor, target)
    actor.primaryTarget = target.id
    w.send(actor, "You attack ${target.name}!")
    w.send(target, "${actor.name.cap()} attacks you!")
    return 0
  }
}

/**
 * A skill cast with the full action lifecycle. The target is found by [targetWord] at the first
 * validation, unless the script verb `act_cast` fixed it up front.
 */
internal class CastAction(
  override val skill: SkillProto,
  private val targetWord: String?,
  private val fixedTarget: Actor? = null,
) : Action, Castable {
  override val warmup: Int
    get() = skill.warmup

  override val lag: Int
    get() = skill.lag

  override val interrupt
    get() = skill.interrupt

  override val attackWhileCasting: Boolean
    get() = skill.attackWhileCasting

  /** Chosen on the first validation; re-validation checks the same target. */
  private var target: Actor? = null
  private var targetChosen = false

  /** The target of an `item` skill: carried, worn or on the ground. */
  private var targetItem: Item? = null

  private fun chooseTarget(w: World, caster: Actor): Result<Actor?> {
    if (targetChosen) return Result.success(target)
    val t =
      if (fixedTarget != null && skill.target in setOf(TargetKind.ENEMY, TargetKind.ALLY))
        fixedTarget
      else
        when (skill.target) {
          TargetKind.SELF -> caster
          TargetKind.NONE -> null
          TargetKind.ITEM -> {
            val word =
              targetWord
                ?: return Result.failure(IllegalArgumentException("Cast ${skill.name} on what?"))
            targetItem =
              caster.equipment.values.firstOrNull { it.matches(word) }
                ?: w.findItem(caster, word, inventory = true, ground = true)
                ?: return Result.failure(IllegalArgumentException("You don't see that here."))
            null
          }
          TargetKind.ALLY ->
            targetWord?.let { w.findActor(caster, it) ?: return notHere() } ?: caster
          TargetKind.ENEMY ->
            targetWord?.let { w.findActor(caster, it) ?: return notHere() }
              ?: caster.primaryTarget?.let(w::actor)
              ?: return Result.failure(IllegalArgumentException("Cast ${skill.name} at whom?"))
        }
    target = t
    targetChosen = true
    return Result.success(t)
  }

  private fun notHere(): Result<Actor?> =
    Result.failure(IllegalArgumentException("They aren't here."))

  override fun validate(w: World, actor: Actor): String? {
    if (actor.hasFlag("silenced")) return SILENCED
    val t =
      chooseTarget(w, actor).getOrElse {
        return it.message
      }
    targetItem?.let { i ->
      if (w.item(i.id) !== i || !w.isPresent(i, w.roomOf(actor)))
        return "${i.name.cap()} isn't here."
      w.reach(actor, i)?.let {
        return it
      }
    }
    if (t != null && t !== actor) {
      if (!w.isPresent(t, w.roomOf(actor))) return "${t.name.cap()} isn't here."
      val band = Band.between(w.indexOf(actor), w.indexOf(t))
      val reach =
        when (skill.range) {
          SkillRange.MELEE -> Band.MELEE
          SkillRange.NEAR -> Band.NEAR
          SkillRange.FAR,
          SkillRange.ROOM -> Band.FAR
          SkillRange.SELF -> return "You can only cast ${skill.name} on yourself."
        }
      if (!reach.reaches(band)) return "${t.name.cap()} is out of range."
    }
    for ((vital, need) in skill.cost) {
      val have =
        when (vital) {
          "hp" -> actor.hp
          "mana" -> actor.mana
          "move" -> actor.move
          else -> 0
        }
      if (have < need) return "You don't have enough $vital."
    }
    return null
  }

  override fun veto(w: World, actor: Actor): String? =
    w.castAllowed(RulesContext(w, actor, target, skill = skill))
      ?: w.dispatch(
        HookName.BEFORE_CAST,
        actor,
        actor.room,
        HookContext(actor = actor, target = target, skill = skill, targetItem = targetItem),
      )

  override fun begin(w: World, actor: Actor) {
    w.send(actor, "You begin casting ${skill.name}.")
    // Onlookers who know the spell recognize it; others only see that a spell is starting.
    for (viewer in actor.room.actorIndex.values) {
      if (viewer === actor) continue
      w.send(
        viewer,
        if (viewer.skills.containsKey(skill.label))
          "${actor.name.cap()} begins casting ${skill.name}."
        else "${actor.name.cap()} begins casting.",
      )
    }
    w.dispatch(
      HookName.ON_CAST_START,
      actor,
      actor.room,
      HookContext(actor = actor, target = target, skill = skill, targetItem = targetItem),
    )
  }

  override fun resolve(w: World, actor: Actor): Int {
    for ((vital, n) in skill.cost) {
      when (vital) {
        "hp" -> actor.hp -= n
        "mana" -> actor.mana -= n
        "move" -> actor.move -= n
      }
    }
    val t = target
    val onItem = targetItem
    w.send(
      actor,
      when {
        onItem != null -> "You cast ${skill.name} on ${onItem.name}."
        t == null || t === actor -> "You cast ${skill.name}."
        else -> "You cast ${skill.name} at ${t.name}."
      },
    )
    if (t != null && t !== actor) w.send(t, "${actor.name.cap()} casts ${skill.name} at you!")
    // Bystanders see every completed cast, so instant spells aren't invisible to the room.
    w.echo(
      actor.room,
      when {
        onItem != null -> "${actor.name.cap()} casts ${skill.name} on ${onItem.name}."
        t == null || t === actor -> "${actor.name.cap()} casts ${skill.name}."
        else -> "${actor.name.cap()} casts ${skill.name} at ${t.name}."
      },
      *listOfNotNull(actor, t).toTypedArray(),
    )
    val targets =
      if (skill.range == SkillRange.ROOM)
        actor.room.actors.filter { it !== actor && w.isSimulated(it) }
      else listOf(t)
    for (each in targets) {
      // An enemy skill engages whatever it lands on, whatever its effects do.
      if (skill.target == TargetKind.ENEMY && each != null && each !== actor)
        w.startCombat(actor, each)
      // Effect primitives act on actors; an item skill's work is its resolve.
      if (onItem == null) skill.effect?.let { w.applyEffect(it, actor, each, skill) }
      skill.resolve?.resolve(w, actor, each, onItem)
    }
    w.dispatch(
      HookName.ON_CAST,
      actor,
      actor.room,
      HookContext(actor = actor, target = t, skill = skill, targetItem = onItem),
    )
    return skill.lag
  }
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
          if (!w.isPresent(source, room)) return "You don't see that here."
          val box = source.container ?: return "${source.name.cap()} is not a container."
          if (box.state != DoorState.OPEN) return "${source.name.cap()} is closed."
          source.contents.firstOrNull { it.matches(what) }
        }
      } ?: return "You don't see that here."
    val ground = container ?: found
    val band = ground.groundIndex?.let { Band.between(w.indexOf(actor), it) } ?: Band.MELEE
    extraLag =
      w.rules.getLag(RulesContext(w, actor, item = found, band = band), band)
        ?: return "You can't reach ${found.name} from here."
    if (
      actor.carriedWeight() + found.weight >
        w.rules.carryLimit(RulesContext(w, actor, item = found))
    ) {
      return "You can't carry that much."
    }
    item = found
    return null
  }

  override fun veto(w: World, actor: Actor): String? =
    w.dispatch(HookName.BEFORE_GET, item, actor.room, HookContext(actor = actor, item = item))

  override fun resolve(w: World, actor: Actor): Int {
    val it = item!!
    w.moveItem(it, ItemLocation.Carried(actor.id))
    w.send(actor, "You get ${it.name}" + (container?.let { c -> " from ${c.name}" } ?: "") + ".")
    w.echo(actor.room, "${actor.name.cap()} gets ${it.name}.", actor)
    w.dispatch(HookName.ON_GET, it, actor.room, HookContext(actor = actor, item = it))
    if (it.id in w.entities) w.stack(it)
    return extraLag
  }
}

/**
 * Null if [actor] can reach [item] (carried, or on the ground within `get_lag` reach), else why
 * not.
 */
internal fun World.reach(actor: Actor, item: Item): String? {
  val index = item.groundIndex ?: return null
  val band = Band.between(indexOf(actor), index)
  return if (rules.getLag(RulesContext(this, actor, item = item, band = band), band) == null)
    "You can't reach ${item.name} from here."
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

/** `look`: the room, its exits, and actors and items grouped by band. */
fun World.describeRoom(viewer: Actor): String {
  val room = viewer.room
  val lines = mutableListOf(room.name)
  if (room.proto.desc.isNotEmpty()) lines += room.proto.desc
  val exits =
    room.exits.values.joinToString(", ") { e ->
      e.direction.name +
        (e.door?.takeIf { it.state != DoorState.OPEN }?.let { " (${it.state.name.lowercase()})" }
          ?: "")
    }
  lines += if (exits.isEmpty()) "Exits: none." else "Exits: $exits."
  val words = mapOf(Band.MELEE to "Beside you", Band.NEAR to "Nearby", Band.FAR to "Far off")
  val others =
    room.actors.filter { it !== viewer }.groupBy { Band.between(viewer.trackIndex, it.trackIndex) }
  for (band in Band.entries) {
    others[band]?.let { list ->
      lines += "${words[band]}: " + list.joinToString(", ") { it.name } + "."
    }
  }
  val items = room.items.groupBy { Band.between(viewer.trackIndex, it.groundIndex ?: 0) }
  for (band in Band.entries) {
    items[band]?.let { list ->
      lines +=
        "On the ground ${words[band]!!.lowercase()}: " + list.joinToString(", ") { it.name } + "."
    }
  }
  return lines.joinToString("\n")
}
