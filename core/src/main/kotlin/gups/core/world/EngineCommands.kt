package gups.core.world

import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.Exit
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.TargetKind
import gups.core.rules.RulesContext

/** The engine's player commands (SPEC §4.9). Items `give`, `put`, `wear` and `use` arrive in M5. */
internal object EngineCommands {
    fun all(): List<CommandDef> = listOf(
        // Fast lane.
        FastCommand("look", listOf("l")) { look(it) },
        FastCommand("say") { say(it) },
        FastCommand("tell") { tell(it) },
        FastCommand("gossip") { gossip(it) },
        FastCommand("shout") { shout(it) },
        FastCommand("whisper") { whisper(it) },
        FastCommand("score") { score(it) },
        FastCommand("inventory", listOf("i", "inv")) { inventory(it) },
        FastCommand("equipment", listOf("eq")) { equipment(it) },
        FastCommand("who") { who(it) },
        FastCommand("help") { help(it) },
        FastCommand("stop") { stop(it) },
        FastCommand("clear") { stop(it) },
        FastCommand("quit") { quit(it) },
        // Tick lane.
        TickCommand("kill", listOf("k", "attack")) { planKill(it) },
        TickCommand("cast", listOf("c")) { planCast(it) },
        TickCommand("advance") { planAdvance(it) },
        TickCommand("retreat") { planRetreat(it) },
        TickCommand("flee") { FleeAction },
        TickCommand("get", listOf("take")) { planGet(it) },
        TickCommand("drop") { planDrop(it) },
        TickCommand("wield") { planWield(it) },
        TickCommand("remove") { planRemove(it) },
        TickCommand("open") { planDoor(it, DoorVerb.OPEN) },
        TickCommand("close") { planDoor(it, DoorVerb.CLOSE) },
        TickCommand("lock") { planDoor(it, DoorVerb.LOCK) },
        TickCommand("unlock") { planDoor(it, DoorVerb.UNLOCK) },
    )

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
        val item = w.findItem(c.actor, c.args, inventory = true, ground = true)
            ?: return w.send(c.actor, "You don't see that here.")
        w.send(c.actor, item.proto.desc.ifEmpty { "You see nothing special about ${item.name}." })
        item.container?.let { box ->
            if (box.state != DoorState.OPEN) return w.send(c.actor, "It is closed.")
            val inside = item.contents
            w.send(c.actor, if (inside.isEmpty()) "It is empty." else "It contains: " + inside.joinToString(", ") { it.name } + ".")
        }
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
            return w.send(c.actor, "You tell ${player.name}, '$text'")
        }
        val mob = w.findActor(c.actor, name) ?: return w.send(c.actor, "Nobody by that name is around.")
        w.send(c.actor, "You tell ${mob.name}, '$text'")
        w.invokeOn(mob, HookName.ON_TELL, HookContext(actor = c.actor, text = text))
    }

    private fun gossip(c: CommandCall) {
        if (c.args.isEmpty()) return c.world.send(c.actor, "Gossip what?")
        for (p in c.world.players) c.world.send(p, "[gossip] ${c.actor.name}: ${c.args}")
    }

    /** Heard in the room and every adjacent room. Perception-based carrying arrives in M5. */
    private fun shout(c: CommandCall) {
        val w = c.world
        if (c.args.isEmpty()) return w.send(c.actor, "Shout what?")
        w.send(c.actor, "You shout, '${c.args}'")
        w.echo(c.actor.room, "${c.actor.name.cap()} shouts, '${c.args}'", c.actor)
        val near = c.actor.room.exits.values.map { w.room(it.to) }.distinct().filter { it !== c.actor.room }
        for (room in near) w.echo(room, "Someone nearby shouts, '${c.args}'")
    }

    private fun whisper(c: CommandCall) {
        val w = c.world
        val (name, text) = splitFirst(c.args)
        if (name.isEmpty() || text.isEmpty()) return w.send(c.actor, "Whisper what to whom?")
        val target = w.findActor(c.actor, name) ?: return w.send(c.actor, "They aren't here.")
        w.send(c.actor, "You whisper to ${target.name}, '$text'")
        w.send(target, "${c.actor.name.cap()} whispers to you, '$text'")
        w.echo(c.actor.room, "${c.actor.name.cap()} whispers something to ${target.name}.", c.actor, target)
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
            """.trimMargin(),
        )
    }

    private fun inventory(c: CommandCall) {
        val items = c.actor.inventory
        c.world.send(c.actor, if (items.isEmpty()) "You are carrying nothing." else "You are carrying:\n" + items.joinToString("\n") { "  ${it.name}" })
    }

    private fun equipment(c: CommandCall) {
        val eq = c.actor.equipment
        c.world.send(c.actor, if (eq.isEmpty()) "You are using nothing." else "You are using:\n" + eq.entries.joinToString("\n") { "  <${it.key}> ${it.value.name}" })
    }

    private fun who(c: CommandCall) {
        val lines = c.world.players.map { p -> "  ${p.name}" + if (p.session == null) " (link-dead)" else "" }
        c.world.send(c.actor, "Players:\n" + lines.joinToString("\n"))
    }

    private fun help(c: CommandCall) {
        val defs = c.world.commands.engineCommands + c.world.commands.globalCommands
        val fast = defs.filter { it.lane == Lane.FAST }.joinToString(" ") { it.name }
        val tick = defs.filter { it.lane == Lane.TICK }.joinToString(" ") { it.name }
        c.world.send(c.actor, "Instant: $fast\nActions: $tick, and any exit name")
    }

    /** `stop`/`clear` (SPEC §5.2): flush the queue and cancel a cast; combat and recovery stay. */
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
        val target = w.findActor(c.actor, c.args) ?: return null.also { w.send(c.actor, "They aren't here.") }
        return KillAction(target)
    }

    private fun planCast(c: CommandCall): Action? {
        val w = c.world
        val (skillWord, targetWord) = splitSkill(c.args)
        if (skillWord.isEmpty()) return null.also { w.send(c.actor, "Cast what?") }
        val skill = c.actor.skills.keys.map(w.protos::skill).firstOrNull { it.name.startsWith(skillWord.lowercase()) }
            ?: return null.also { w.send(c.actor, "You don't know that skill.") }
        return CastAction(skill, targetWord.ifEmpty { null })
    }

    private fun planAdvance(c: CommandCall): Action? {
        val w = c.world
        val target = (if (c.args.isEmpty()) w.nearestOpponent(c.actor) else w.findActor(c.actor, c.args))
            ?: return null.also { w.send(c.actor, "Advance toward whom?") }
        return AdvanceAction(target)
    }

    private fun planRetreat(c: CommandCall): Action? {
        val words = c.args.removePrefix("from ").trim()
        if (words.isEmpty()) return RetreatAction(null)
        val target = c.world.findActor(c.actor, words) ?: return null.also { c.world.send(c.actor, "They aren't here.") }
        return RetreatAction(target)
    }

    private fun planGet(c: CommandCall): Action? {
        val w = c.world
        val (what, from) = c.args.split(Regex("\\s+from\\s+"), limit = 2).let { it[0].trim() to it.getOrNull(1)?.trim() }
        if (what.isEmpty()) return null.also { w.send(c.actor, "Get what?") }
        val container = from?.let {
            w.findItem(c.actor, it, inventory = true, ground = true) ?: return null.also { w.send(c.actor, "You don't see that here.") }
        }
        return GetAction(what, container)
    }

    private fun planDrop(c: CommandCall): Action? {
        val w = c.world
        val item = w.findItem(c.actor, c.args, inventory = true, ground = false)
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
                return 0
            }
        }
    }

    private fun planWield(c: CommandCall): Action? {
        val w = c.world
        val item = w.findItem(c.actor, c.args, inventory = true, ground = false)
            ?: return null.also { w.send(c.actor, "You aren't carrying that.") }
        if (item.proto.wear != "wield") return null.also { w.send(c.actor, "You can't wield ${item.name}.") }
        return object : Action {
            override fun validate(w: World, actor: Actor): String? =
                if (item.location != ItemLocation.Carried(actor.id)) "You aren't carrying that." else null

            override fun resolve(w: World, actor: Actor): Int {
                actor.equipped("wield")?.let { w.moveItem(it, ItemLocation.Carried(actor.id)) }
                w.moveItem(item, ItemLocation.Equipped(actor.id, "wield"))
                w.send(actor, "You wield ${item.name}.")
                w.echo(actor.room, "${actor.name.cap()} wields ${item.name}.", actor)
                return 0
            }
        }
    }

    private fun planRemove(c: CommandCall): Action? {
        val w = c.world
        val item = c.actor.equipment.values.firstOrNull { it.matches(c.args) }
            ?: return null.also { w.send(c.actor, "You aren't using that.") }
        return object : Action {
            override fun validate(w: World, actor: Actor): String? =
                if ((item.location as? ItemLocation.Equipped)?.actor != actor.id) "You aren't using that." else null

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
        if (c.args.isEmpty()) return null.also { c.world.send(c.actor, "${verb.word.cap()} which way?") }
        val dir = Direction.parse(c.args)
        return object : Action {
            override fun validate(w: World, actor: Actor): String? {
                val door = w.roomOf(actor).exits[dir]?.door ?: return "There is no door $dir."
                if (door.state !in verb.from) return "The door $dir is ${door.state.name.lowercase()}."
                if (verb == DoorVerb.LOCK || verb == DoorVerb.UNLOCK) {
                    val key = door.key ?: return "The door $dir has no lock."
                    if (actor.inventory.none { it.protoLabel == key }) return "You don't have the key."
                }
                return null
            }

            override fun resolve(w: World, actor: Actor): Int {
                val exit = actor.room.exits.getValue(dir)
                w.setDoor(actor.room, dir, verb.to)
                w.send(actor, "You ${verb.word} the door $dir.")
                w.echo(actor.room, "${actor.name.cap()} ${verb.word}s the door $dir.", actor)
                w.reverseExit(actor.room, exit)?.let { (other, back) ->
                    w.echo(other, "The door ${back.direction} ${if (verb.to == DoorState.OPEN) "opens" else "closes"}.")
                }
                return 0
            }
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

/** `kill <target>`: engages and switches the primary target (D-034). */
internal class KillAction(private val target: Actor) : Action {
    override fun validate(w: World, actor: Actor): String? = when {
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
 * A skill cast with the full action lifecycle (SPEC §4.3, §5.3). The target is found by
 * [targetWord] at the first validation, unless the script verb `act_cast` fixed it up front.
 */
internal class CastAction(
    val skill: SkillProto,
    private val targetWord: String?,
    private val fixedTarget: Actor? = null,
) : Action {
    override val warmup: Int get() = skill.warmup
    override val lag: Int get() = skill.lag
    override val interrupt get() = skill.interrupt
    override val attackWhileCasting: Boolean get() = skill.attackWhileCasting

    /** Chosen on the first validation; re-validation checks the same target. */
    private var target: Actor? = null
    private var targetChosen = false

    private fun chooseTarget(w: World, caster: Actor): Result<Actor?> {
        if (targetChosen) return Result.success(target)
        val t = if (fixedTarget != null && skill.target in setOf(TargetKind.ENEMY, TargetKind.ALLY)) fixedTarget else when (skill.target) {
            TargetKind.SELF -> caster
            TargetKind.NONE -> null
            TargetKind.ITEM -> return Result.failure(IllegalArgumentException("You can't cast that on items yet."))
            TargetKind.ALLY -> targetWord?.let { w.findActor(caster, it) ?: return notHere() } ?: caster
            TargetKind.ENEMY -> targetWord?.let { w.findActor(caster, it) ?: return notHere() }
                ?: caster.primaryTarget?.let(w::actor)
                ?: return Result.failure(IllegalArgumentException("Cast ${skill.name} at whom?"))
        }
        target = t
        targetChosen = true
        return Result.success(t)
    }

    private fun notHere(): Result<Actor?> = Result.failure(IllegalArgumentException("They aren't here."))

    override fun validate(w: World, actor: Actor): String? {
        if (actor.hasFlag("silenced")) return "You can't get the words out!"
        val t = chooseTarget(w, actor).getOrElse { return it.message }
        if (t != null && t !== actor) {
            if (!w.isPresent(t, w.roomOf(actor))) return "${t.name.cap()} isn't here."
            val band = Band.between(w.indexOf(actor), w.indexOf(t))
            val reach = when (skill.range) {
                SkillRange.MELEE -> Band.MELEE
                SkillRange.NEAR -> Band.NEAR
                SkillRange.FAR, SkillRange.ROOM -> Band.FAR
                SkillRange.SELF -> return "You can only cast ${skill.name} on yourself."
            }
            if (!reach.reaches(band)) return "${t.name.cap()} is out of range."
        }
        for ((vital, need) in skill.cost) {
            val have = when (vital) {
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
        w.rules.castAllowed(RulesContext(w, actor, target, skill = skill))
            ?: w.dispatch(HookName.BEFORE_CAST, actor, actor.room, HookContext(actor = actor, target = target, skill = skill))

    override fun begin(w: World, actor: Actor) {
        w.send(actor, "You begin casting ${skill.name}.")
        w.echo(actor.room, "${actor.name.cap()} begins casting.", actor)
        w.dispatch(HookName.ON_CAST_START, actor, actor.room, HookContext(actor = actor, target = target, skill = skill))
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
        w.send(actor, if (t == null || t === actor) "You cast ${skill.name}." else "You cast ${skill.name} at ${t.name}.")
        if (t != null && t !== actor) w.send(t, "${actor.name.cap()} casts ${skill.name} at you!")
        val targets = if (skill.range == SkillRange.ROOM) actor.room.actors.filter { it !== actor } else listOf(t)
        for (each in targets) {
            skill.effect?.let { w.applyEffect(it, actor, each, skill) }
            skill.resolve?.resolve(w, actor, each)
        }
        w.dispatch(HookName.ON_CAST, actor, actor.room, HookContext(actor = actor, target = t, skill = skill))
        return skill.lag
    }
}

/** `get <item> [from <container>]`, with lag by band from `rules.get_lag` (SPEC §5.5). */
internal class GetAction(private val what: String, private val container: Item?) : Action {
    private var item: Item? = null
    private var extraLag = 0

    override fun validate(w: World, actor: Actor): String? {
        val room = w.roomOf(actor)
        val source = container
        val found = if (source == null) {
            room.items.firstOrNull { it.matches(what) }
        } else {
            if (!w.isPresent(source, room)) return "You don't see that here."
            val box = source.container ?: return "${source.name.cap()} is not a container."
            if (box.state != DoorState.OPEN) return "${source.name.cap()} is closed."
            source.contents.firstOrNull { it.matches(what) }
        } ?: return "You don't see that here."
        val ground = container ?: found
        val band = ground.groundIndex?.let { Band.between(w.indexOf(actor), it) } ?: Band.MELEE
        extraLag = w.rules.getLag(RulesContext(w, actor, item = found, band = band), band)
            ?: return "You can't reach ${found.name} from here."
        if (actor.carriedWeight() + found.weight > w.rules.carryLimit(RulesContext(w, actor, item = found))) {
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
        return extraLag
    }
}

/** The other side of [exit], if it leads back here with a door. */
internal fun World.reverseExit(room: gups.core.model.Room, exit: Exit): Pair<gups.core.model.Room, Exit>? {
    val other = room(exit.to)
    val back = other.exits.values.firstOrNull { it.to == room.label && it.door != null } ?: return null
    return other to back
}

// ---- Finding things and describing rooms ------------------------------------------------------

/** Finds an actor in [viewer]'s room by keyword; `2.goblin` picks the second match. */
fun World.findActor(viewer: Actor, keyword: String): Actor? {
    val (n, word) = ordinal(keyword)
    if (word == "self" || word == "me") return viewer
    val room = roomOf(viewer)
    return room.actorIndex.values.filter { it !== viewer && isPresent(it, room) && it.matches(word) }.getOrNull(n - 1)
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
    if (dot > 0) t.substring(0, dot).toIntOrNull()?.let { return it to t.substring(dot + 1) }
    return 1 to t
}

/** `look`: the room, its exits, and actors and items grouped by band (SPEC §5.5). */
fun World.describeRoom(viewer: Actor): String {
    val room = viewer.room
    val lines = mutableListOf(room.name)
    if (room.proto.desc.isNotEmpty()) lines += room.proto.desc
    val exits = room.exits.values.joinToString(", ") { e ->
        e.direction.name + (e.door?.takeIf { it.state != DoorState.OPEN }?.let { " (${it.state.name.lowercase()})" } ?: "")
    }
    lines += if (exits.isEmpty()) "Exits: none." else "Exits: $exits."
    val words = mapOf(Band.MELEE to "Beside you", Band.NEAR to "Nearby", Band.FAR to "Far off")
    val others = room.actors.filter { it !== viewer }.groupBy { Band.between(viewer.trackIndex, it.trackIndex) }
    for (band in Band.entries) {
        others[band]?.let { list -> lines += "${words[band]}: " + list.joinToString(", ") { it.name } + "." }
    }
    val items = room.items.groupBy { Band.between(viewer.trackIndex, it.groundIndex ?: 0) }
    for (band in Band.entries) {
        items[band]?.let { list -> lines += "On the ground ${words[band]!!.lowercase()}: " + list.joinToString(", ") { it.name } + "." }
    }
    return lines.joinToString("\n")
}
