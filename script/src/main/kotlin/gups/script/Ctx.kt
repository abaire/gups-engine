package gups.script

import gups.core.AttrValue
import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Room
import gups.core.world.HookContext
import gups.core.world.InvariantViolation
import gups.core.world.Proposal
import gups.core.world.actAdvance
import gups.core.world.actAttack
import gups.core.world.actCast
import gups.core.world.actMove
import gups.core.world.actRetreat
import gups.core.world.act
import gups.core.world.applyAffect
import gups.core.world.damage
import gups.core.world.destroy
import gups.core.world.echo
import gups.core.world.emote
import gups.core.world.findActor
import gups.core.world.heal
import gups.core.world.moveItem
import gups.core.world.opponentsOf
import gups.core.world.pathTo
import gups.core.world.removeAffect
import gups.core.world.say
import gups.core.world.send
import gups.core.world.setDoor
import gups.core.world.setExitBlocks
import gups.core.world.spawnItem
import gups.core.world.splitItem
import gups.core.world.startCombat
import gups.core.world.stopCombat
import gups.core.world.teleport
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.Printer
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue

/** What `ctx.deny(msg)` returns; a `before_*` hook returns it to veto (SPEC §4.4). */
@StarlarkBuiltin(name = "denial", doc = "A veto, from ctx.deny(msg).")
class Denial internal constructor(val message: String) : StarlarkValue {
    override fun isImmutable(): Boolean = true

    override fun repr(printer: Printer) {
        printer.append("<deny ").repr(message).append(">")
    }
}

/** Per-invocation extras that are not in [HookContext]. */
internal class CtxExtras(
    val proposals: List<Proposal>? = null,
    /** A zone or global command's argument text. */
    val commandArgs: String? = null,
    val commandTarget: Actor? = null,
    val targets: List<Actor>? = null,
)

/**
 * The `ctx` handed to one hook invocation (SPEC §4.5). Created fresh per call and invalid once
 * the hook returns. Fields are read-only views; every mutation is a verb the engine validates
 * and applies at once, on the world thread.
 */
@StarlarkBuiltin(name = "ctx", doc = "The context of one hook invocation.")
class Ctx internal constructor(
    private val frame: Frame,
    private val owner: Entity,
    private val hc: HookContext,
    private val extras: CtxExtras,
) : StarlarkValue {
    private val world get() = frame.world

    private fun <T> field(block: () -> T): T {
        frame.check()
        return block()
    }

    /** Every verb: validity, the wall-clock backstop, tracing, and engine errors as script errors. */
    private fun <T> verb(name: String, detail: String = "", block: () -> T): T {
        frame.check()
        frame.checkClock()
        frame.trace("  $name($detail)")
        try {
            return block()
        } catch (e: InvariantViolation) {
            throw e
        } catch (e: IllegalArgumentException) {
            fail("ctx.$name(): ${e.message}")
        } catch (e: IllegalStateException) {
            fail("ctx.$name(): ${e.message}")
        } catch (e: NoSuchElementException) {
            fail("ctx.$name(): ${e.message}")
        }
    }

    private fun v(e: Entity?): Any = view(frame, e)

    private fun pkgOf(thread: StarlarkThread): String = callerFile(thread)?.pkg ?: frame.pkg

    // ---- Fields --------------------------------------------------------------------------------

    @StarlarkMethod(name = "self", structField = true, doc = "The hook's owner.")
    fun self(): Any = field { v(owner) }

    @StarlarkMethod(name = "actor", structField = true)
    fun actor(): Any = field { v(hc.actor) }

    @StarlarkMethod(name = "target", structField = true)
    fun target(): Any = field { v(hc.target ?: extras.commandTarget) }

    @StarlarkMethod(name = "targets", structField = true, doc = "Opponents, for on_combat_tick.")
    fun targets(): Any = field { extras.targets?.let { t -> sList(t.map { v(it) }) } ?: Starlark.NONE }

    @StarlarkMethod(name = "item", structField = true)
    fun item(): Any = field { v(hc.item) }

    @StarlarkMethod(name = "room", structField = true, doc = "The room the event happens in.")
    fun room(): Any = field { v(hc.room ?: world.roomOfEntity(owner)) }

    @StarlarkMethod(name = "direction", structField = true)
    fun direction(): Any = field { hc.direction?.name ?: Starlark.NONE }

    @StarlarkMethod(name = "from_room", structField = true)
    fun fromRoom(): Any = field { v(hc.fromRoom) }

    @StarlarkMethod(name = "to_room", structField = true)
    fun toRoom(): Any = field { v(hc.toRoom) }

    @StarlarkMethod(name = "text", structField = true)
    fun text(): Any = field { hc.text ?: Starlark.NONE }

    @StarlarkMethod(name = "args", structField = true, doc = "A command's argument text, or a timer's keyword arguments as a dict.")
    fun args(): Any = field {
        extras.commandArgs ?: if (hc.args.isEmpty()) Starlark.NONE else sDict(hc.args.mapValues { fromAttr(it.value, null) })
    }

    @StarlarkMethod(name = "event", structField = true, doc = "Perception event type, for on_nearby (M5).")
    fun event(): Any = field { Starlark.NONE }

    @StarlarkMethod(name = "source_room", structField = true, doc = "For on_nearby (M5).")
    fun sourceRoom(): Any = field { Starlark.NONE }

    @StarlarkMethod(name = "via_exit", structField = true, doc = "For on_nearby (M5).")
    fun viaExit(): Any = field { Starlark.NONE }

    @StarlarkMethod(name = "skill", structField = true)
    fun skill(): Any = field { hc.skill?.let(::SkillView) ?: Starlark.NONE }

    @StarlarkMethod(name = "weapon", structField = true)
    fun weapon(): Any = field { v(hc.weapon) }

    @StarlarkMethod(name = "amount", structField = true)
    fun amount(): Any = field { hc.amount?.let(::sInt) ?: Starlark.NONE }

    @StarlarkMethod(name = "type", structField = true, doc = "The damage type, for on_damage.")
    fun type(): Any = field { hc.type?.name ?: Starlark.NONE }

    @StarlarkMethod(name = "affect", structField = true, doc = "The affect, for affect on_tick and on_expire.")
    fun affect(): Any = field {
        val a = hc.affect ?: return@field Starlark.NONE
        AffectView(frame, owner as? Actor ?: return@field Starlark.NONE, a)
    }

    @StarlarkMethod(name = "proposals", structField = true, doc = "For think: behavior proposals, best first.")
    fun proposals(): Any = field { extras.proposals?.let { p -> sList(p.map(::ProposalView)) } ?: Starlark.NONE }

    @StarlarkMethod(name = "elapsed_pulses", structField = true)
    fun elapsedPulses(): Any = field { hc.elapsedPulses?.let(::sInt) ?: Starlark.NONE }

    @StarlarkMethod(name = "pulse", structField = true, doc = "Game time.")
    fun pulse(): Any = field { sInt(world.pulse) }

    @StarlarkMethod(name = "rng", structField = true)
    fun rng(): Any = field { RngView(frame) }

    // ---- Argument conversion -------------------------------------------------------------------

    private fun actorArg(x: Any?, what: String): Actor {
        val a = (x as? ActorView)?.actor ?: fail("$what: expected an actor, got ${typeName(x)}")
        if (!a.inWorld) fail("$what: ${a.name} is no longer in the world")
        return a
    }

    private fun mobArg(x: Any?, what: String): Mob =
        actorArg(x, what) as? Mob ?: fail("$what: mob actions apply only to mobs, never players")

    private fun itemArg(x: Any?, what: String): Item {
        val i = (x as? ItemView)?.item ?: fail("$what: expected an item, got ${typeName(x)}")
        if (world.item(i.id) !== i) fail("$what: ${i.name} is no longer in the world")
        return i
    }

    private fun roomArg(x: Any?, what: String, thread: StarlarkThread): Room = when (x) {
        is RoomView -> x.room
        is String -> world.roomOrNull(label(x, pkgOf(thread), what)) ?: fail("$what: there is no room $x")
        else -> fail("$what: expected a room or a room label, got ${typeName(x)}")
    }

    private fun entityArg(x: Any?, what: String): Entity = when (x) {
        is ActorView -> actorArg(x, what)
        is ItemView -> itemArg(x, what)
        is RoomView -> x.room
        else -> fail("$what: expected an actor, room or item, got ${typeName(x)}")
    }

    private fun text(x: Any?, what: String): String = checkString(str(x, what), what, frame.host.limits)

    private fun dir(x: Any?, what: String): Direction = Direction.parse(str(x, what))

    /** A destination: a room (ground, at [index]), an actor (carried) or a container. */
    private fun location(at: Any?, index: Any?, what: String, thread: StarlarkThread): ItemLocation = when (at) {
        is ActorView -> ItemLocation.Carried(actorArg(at, what).id)
        is ItemView -> ItemLocation.Contained(itemArg(at, what).id)
        else -> ItemLocation.InRoom(roomArg(at, what, thread).id, intOrNull(index, "$what index") ?: 0)
    }

    // ---- Messaging -----------------------------------------------------------------------------

    @StarlarkMethod(name = "say", doc = "speaker says text aloud; fires on_say.", parameters = [Param(name = "speaker", named = true), Param(name = "text", named = true)])
    fun say(speaker: Any, text: Any): Any = verb("say") { world.say(actorArg(speaker, "say(speaker)"), text(text, "say(text)")); Starlark.NONE }

    @StarlarkMethod(name = "emote", doc = "The room sees '<actor> text'.", parameters = [Param(name = "actor", named = true), Param(name = "text", named = true)])
    fun emote(actor: Any, text: Any): Any = verb("emote") { world.emote(actorArg(actor, "emote(actor)"), text(text, "emote(text)")); Starlark.NONE }

    @StarlarkMethod(name = "send", doc = "Sends text to one actor.", parameters = [Param(name = "target", named = true), Param(name = "text", named = true)])
    fun send(target: Any, text: Any): Any = verb("send") { world.send(actorArg(target, "send(target)"), text(text, "send(text)")); Starlark.NONE }

    @StarlarkMethod(
        name = "echo",
        doc = "Sends text to everyone in a room, except the actors in exclude.",
        parameters = [Param(name = "room", named = true), Param(name = "text", named = true), Param(name = "exclude", named = true, defaultValue = "[]")],
        useStarlarkThread = true,
    )
    fun echo(room: Any, text: Any, exclude: Any, thread: StarlarkThread): Any = verb("echo") {
        val skip = list(exclude, "echo(exclude)").map { actorArg(it, "echo(exclude)") }
        world.echo(roomArg(room, "echo(room)", thread), text(text, "echo(text)"), *skip.toTypedArray())
        Starlark.NONE
    }

    // ---- Veto ----------------------------------------------------------------------------------

    @StarlarkMethod(name = "deny", doc = "Vetoes the action; return it from a before_* hook.", parameters = [Param(name = "msg", named = true)])
    fun deny(msg: Any): Any = verb("deny") {
        if (!frame.site.vetoable) fail("deny() is only valid as the return value of a before_* hook (this is ${frame.site.hook})")
        Denial(text(msg, "deny(msg)"))
    }

    // ---- Mob actions (tick lane) ---------------------------------------------------------------

    private fun acted(mob: Mob, ok: Boolean): Any {
        if (mob === owner) frame.acted = true
        return ok
    }

    @StarlarkMethod(name = "act", doc = "Queues a command line for a mob, exactly as if it typed it.", parameters = [Param(name = "mob", named = true), Param(name = "command_line", named = true)])
    fun act(mob: Any, commandLine: Any): Any = verb("act") {
        val m = mobArg(mob, "act(mob)")
        acted(m, world.act(m, text(commandLine, "act(command_line)")))
    }

    @StarlarkMethod(name = "act_move", parameters = [Param(name = "mob", named = true), Param(name = "dir", named = true)])
    fun actMove(mob: Any, dir: Any): Any = verb("act_move") {
        val m = mobArg(mob, "act_move(mob)")
        acted(m, world.actMove(m, dir(dir, "act_move(dir)")))
    }

    @StarlarkMethod(name = "act_attack", parameters = [Param(name = "mob", named = true), Param(name = "target", named = true)])
    fun actAttack(mob: Any, target: Any): Any = verb("act_attack") {
        val m = mobArg(mob, "act_attack(mob)")
        acted(m, world.actAttack(m, actorArg(target, "act_attack(target)")))
    }

    @StarlarkMethod(
        name = "act_cast",
        parameters = [Param(name = "mob", named = true), Param(name = "skill", named = true), Param(name = "target", defaultValue = "None", named = true)],
        useStarlarkThread = true,
    )
    fun actCast(mob: Any, skill: Any, target: Any, thread: StarlarkThread): Any = verb("act_cast") {
        val m = mobArg(mob, "act_cast(mob)")
        val proto = when (skill) {
            is SkillView -> skill.skill
            else -> world.protos.skills[label(str(skill, "act_cast(skill)"), pkgOf(thread), "act_cast(skill)")]
                ?: fail("act_cast(skill): there is no skill $skill")
        }
        acted(m, world.actCast(m, proto, if (isNone(target)) null else actorArg(target, "act_cast(target)")))
    }

    @StarlarkMethod(name = "act_advance", parameters = [Param(name = "mob", named = true), Param(name = "target", named = true)])
    fun actAdvance(mob: Any, target: Any): Any = verb("act_advance") {
        val m = mobArg(mob, "act_advance(mob)")
        acted(m, world.actAdvance(m, actorArg(target, "act_advance(target)")))
    }

    @StarlarkMethod(name = "act_retreat", parameters = [Param(name = "mob", named = true), Param(name = "target", defaultValue = "None", named = true)])
    fun actRetreat(mob: Any, target: Any): Any = verb("act_retreat") {
        val m = mobArg(mob, "act_retreat(mob)")
        acted(m, world.actRetreat(m, if (isNone(target)) null else actorArg(target, "act_retreat(target)")))
    }

    @StarlarkMethod(
        name = "move_toward",
        doc = "Queues one step toward a direction, an actor (advancing within the room) or a room. False if there is no way.",
        parameters = [Param(name = "mob", named = true), Param(name = "dir_or_target", named = true)],
        useStarlarkThread = true,
    )
    fun moveToward(mob: Any, dirOrTarget: Any, thread: StarlarkThread): Any = verb("move_toward") {
        val m = mobArg(mob, "move_toward(mob)")
        val dest: Room = when (dirOrTarget) {
            is ActorView -> {
                val t = actorArg(dirOrTarget, "move_toward(target)")
                if (t.room === m.room) {
                    return@verb if (t.trackIndex == m.trackIndex) false else acted(m, world.actAdvance(m, t))
                }
                t.room
            }
            is String -> if (dirOrTarget.startsWith("//") || dirOrTarget.startsWith(":")) {
                roomArg(dirOrTarget, "move_toward(target)", thread)
            } else {
                return@verb acted(m, world.actMove(m, dir(dirOrTarget, "move_toward(dir)")))
            }
            else -> roomArg(dirOrTarget, "move_toward(target)", thread)
        }
        val step = world.pathTo(m.room, dest)?.firstOrNull() ?: return@verb false
        acted(m, world.actMove(m, step))
    }

    // ---- Movement ------------------------------------------------------------------------------

    @StarlarkMethod(
        name = "teleport",
        doc = "Moves an actor to a room; fires the exit and enter hooks.",
        parameters = [Param(name = "actor", named = true), Param(name = "room", named = true), Param(name = "index", defaultValue = "None", named = true)],
        useStarlarkThread = true,
    )
    fun teleport(actor: Any, room: Any, index: Any, thread: StarlarkThread): Any = verb("teleport") {
        val r = roomArg(room, "teleport(room)", thread)
        val i = intOrNull(index, "teleport(index)")
        if (i != null && i !in 0 until r.trackSize) fail("teleport(index): $i is outside ${r.label}")
        world.teleport(actorArg(actor, "teleport(actor)"), r, i)
        Starlark.NONE
    }

    // ---- Combat --------------------------------------------------------------------------------

    @StarlarkMethod(
        name = "damage",
        doc = "Deals damage directly (no to-hit roll, no mitigation); fires on_damage.",
        parameters = [Param(name = "target", named = true), Param(name = "amount", named = true), Param(name = "type", named = true), Param(name = "source", defaultValue = "None", named = true)],
    )
    fun damage(target: Any, amount: Any, type: Any, source: Any): Any = verb("damage") {
        val t = actorArg(target, "damage(target)")
        val n = int(amount, "damage(amount)")
        if (n < 0) fail("damage(amount): can't be negative; use heal()")
        world.damage(t, n, DamageType(str(type, "damage(type)").lowercase()), if (isNone(source)) null else actorArg(source, "damage(source)"))
        Starlark.NONE
    }

    @StarlarkMethod(name = "heal", parameters = [Param(name = "target", named = true), Param(name = "amount", named = true)])
    fun heal(target: Any, amount: Any): Any = verb("heal") {
        val n = int(amount, "heal(amount)")
        if (n < 0) fail("heal(amount): can't be negative; use damage()")
        world.heal(actorArg(target, "heal(target)"), n)
        Starlark.NONE
    }

    @StarlarkMethod(name = "start_combat", parameters = [Param(name = "a", named = true), Param(name = "b", named = true)])
    fun startCombat(a: Any, b: Any): Any = verb("start_combat") {
        val x = actorArg(a, "start_combat(a)")
        val y = actorArg(b, "start_combat(b)")
        if (x === y) fail("start_combat(): an actor can't fight itself")
        if (x.room !== y.room) fail("start_combat(): ${x.name} and ${y.name} are not in the same room")
        world.startCombat(x, y)
        Starlark.NONE
    }

    @StarlarkMethod(name = "stop_combat", parameters = [Param(name = "a", named = true), Param(name = "b", defaultValue = "None", named = true)])
    fun stopCombat(a: Any, b: Any): Any = verb("stop_combat") {
        world.stopCombat(actorArg(a, "stop_combat(a)"), if (isNone(b)) null else actorArg(b, "stop_combat(b)"))
        Starlark.NONE
    }

    @StarlarkMethod(
        name = "apply_affect",
        doc = "Attaches a timed buff or debuff; re-applying a name replaces it.",
        parameters = [
            Param(name = "target", named = true),
            Param(name = "name", named = true),
            Param(name = "duration", named = true),
            Param(name = "stat_deltas", named = true, defaultValue = "{}"),
            Param(name = "flags", named = true, defaultValue = "[]"),
            Param(name = "tick_every", named = true, defaultValue = "None"),
            Param(name = "on_tick", named = true, defaultValue = "None"),
            Param(name = "on_expire", named = true, defaultValue = "None"),
            Param(name = "expire_msg", named = true, defaultValue = "''"),
        ],
    )
    fun applyAffect(
        target: Any,
        name: Any,
        duration: Any,
        statDeltas: Any,
        flags: Any,
        tickEvery: Any,
        onTick: Any,
        onExpire: Any,
        expireMsg: Any,
    ): Any = verb("apply_affect") {
        val t = actorArg(target, "apply_affect(target)")
        val e = affectEffect(name, duration, statDeltas, flags, tickEvery, onTick, onExpire, expireMsg, "apply_affect")
        val a = world.applyAffect(t, e.name, e.duration, e.statDeltas, e.flags, e.tickEvery, e.onTick, e.onExpire, e.expireMessage, owner as? Actor)
        a?.let { AffectView(frame, t, it) } ?: Starlark.NONE
    }

    @StarlarkMethod(name = "remove_affect", doc = "Removes an affect by name; True if there was one.", parameters = [Param(name = "target", named = true), Param(name = "name", named = true)])
    fun removeAffect(target: Any, name: Any): Any = verb("remove_affect") {
        world.removeAffect(actorArg(target, "remove_affect(target)"), str(name, "remove_affect(name)"))
    }

    // ---- Items ---------------------------------------------------------------------------------

    @StarlarkMethod(
        name = "spawn",
        doc = "Spawns a mob (in a room) or an item (in a room, on an actor, or in a container). Returns its view, or a list when count > 1 makes several.",
        parameters = [
            Param(name = "label", named = true),
            Param(name = "at", named = true),
            Param(name = "index", named = true, defaultValue = "0"),
            Param(name = "count", named = true, defaultValue = "1"),
        ],
        useStarlarkThread = true,
    )
    fun spawn(label: Any, at: Any, index: Any, count: Any, thread: StarlarkThread): Any = verb("spawn", "$label") {
        val l = label(str(label, "spawn(label)"), pkgOf(thread), "spawn(label)")
        val n = int(count, "spawn(count)")
        if (n < 1) fail("spawn(count): must be at least 1")
        val made = when {
            l in world.protos.mobs -> {
                val room = roomArg(at, "spawn(at)", thread)
                val i = int(index, "spawn(index)")
                if (i !in 0 until room.trackSize) fail("spawn(index): $i is outside ${room.label}")
                List(n) { world.spawnMob(l, room, i) }
            }
            l in world.protos.items -> {
                val loc = location(at, index, "spawn(at)", thread)
                if (world.protos.item(l).stackable) listOf(world.spawnItem(l, loc, n)) else List(n) { world.spawnItem(l, loc) }
            }
            else -> fail("spawn(label): there is no mob or item $l")
        }
        if (made.size == 1) v(made[0]) else sList(made.map { v(it) })
    }

    @StarlarkMethod(
        name = "give",
        doc = "Puts an item (a view, or a label to spawn) into an actor's inventory.",
        parameters = [Param(name = "actor", named = true), Param(name = "label_or_item", named = true), Param(name = "count", named = true, defaultValue = "1")],
        useStarlarkThread = true,
    )
    fun give(actor: Any, labelOrItem: Any, count: Any, thread: StarlarkThread): Any = verb("give") {
        val a = actorArg(actor, "give(actor)")
        val to = ItemLocation.Carried(a.id)
        if (labelOrItem is ItemView) {
            val i = itemArg(labelOrItem, "give(item)")
            world.moveItem(i, to)
            return@verb v(i)
        }
        val l = label(str(labelOrItem, "give(label_or_item)"), pkgOf(thread), "give(label)")
        if (l !in world.protos.items) fail("give(label): there is no item $l")
        val n = int(count, "give(count)")
        if (n < 1) fail("give(count): must be at least 1")
        val made = if (world.protos.item(l).stackable) listOf(world.spawnItem(l, to, n)) else List(n) { world.spawnItem(l, to) }
        if (made.size == 1) v(made[0]) else sList(made.map { v(it) })
    }

    @StarlarkMethod(
        name = "move_item",
        doc = "Moves an item to a room (at index), an actor's inventory, or a container.",
        parameters = [Param(name = "item", named = true), Param(name = "to", named = true), Param(name = "index", named = true, defaultValue = "0")],
        useStarlarkThread = true,
    )
    fun moveItem(item: Any, to: Any, index: Any, thread: StarlarkThread): Any = verb("move_item") {
        world.moveItem(itemArg(item, "move_item(item)"), location(to, index, "move_item(to)", thread))
        Starlark.NONE
    }

    @StarlarkMethod(name = "destroy", doc = "Removes an item and everything inside it.", parameters = [Param(name = "item", named = true)])
    fun destroy(item: Any): Any = verb("destroy") {
        world.destroy(itemArg(item, "destroy(item)"))
        Starlark.NONE
    }

    @StarlarkMethod(name = "split", doc = "Splits n off a stack into a new item beside it.", parameters = [Param(name = "item", named = true), Param(name = "n", named = true)])
    fun split(item: Any, n: Any): Any = verb("split") { v(world.splitItem(itemArg(item, "split(item)"), int(n, "split(n)"))) }

    // ---- Doors and exits -----------------------------------------------------------------------

    private fun door(room: Any, dir: Any, thread: StarlarkThread, name: String, to: (DoorState) -> DoorState): Any = verb(name) {
        val r = roomArg(room, "$name(room)", thread)
        val d = dir(dir, "$name(dir)")
        val door = r.exits[d]?.door ?: fail("$name(): ${r.label} has no door $d")
        world.setDoor(r, d, to(door.state))
        Starlark.NONE
    }

    @StarlarkMethod(name = "open_door", parameters = [Param(name = "room", named = true), Param(name = "dir", named = true)], useStarlarkThread = true)
    fun openDoor(room: Any, dir: Any, thread: StarlarkThread): Any = door(room, dir, thread, "open_door") { DoorState.OPEN }

    @StarlarkMethod(name = "close_door", parameters = [Param(name = "room", named = true), Param(name = "dir", named = true)], useStarlarkThread = true)
    fun closeDoor(room: Any, dir: Any, thread: StarlarkThread): Any =
        door(room, dir, thread, "close_door") { if (it == DoorState.LOCKED) it else DoorState.CLOSED }

    @StarlarkMethod(name = "lock_door", doc = "Closes and locks the door.", parameters = [Param(name = "room", named = true), Param(name = "dir", named = true)], useStarlarkThread = true)
    fun lockDoor(room: Any, dir: Any, thread: StarlarkThread): Any = door(room, dir, thread, "lock_door") { DoorState.LOCKED }

    @StarlarkMethod(name = "unlock_door", doc = "Unlocks the door, leaving it closed.", parameters = [Param(name = "room", named = true), Param(name = "dir", named = true)], useStarlarkThread = true)
    fun unlockDoor(room: Any, dir: Any, thread: StarlarkThread): Any =
        door(room, dir, thread, "unlock_door") { if (it == DoorState.LOCKED) DoorState.CLOSED else it }

    @StarlarkMethod(
        name = "set_exit_blocks",
        doc = "Replaces the channels an exit blocks: any of 'sight', 'sound', 'noise', 'spell'.",
        parameters = [Param(name = "room", named = true), Param(name = "dir", named = true), Param(name = "channels", named = true)],
        useStarlarkThread = true,
    )
    fun setExitBlocks(room: Any, dir: Any, channels: Any, thread: StarlarkThread): Any = verb("set_exit_blocks") {
        val names = Channel.entries.map { it.name.lowercase() }
        val set = strList(channels, "set_exit_blocks(channels)").mapTo(LinkedHashSet()) {
            Channel.valueOf(choice(it, "set_exit_blocks(channels)", names).uppercase())
        }
        world.setExitBlocks(roomArg(room, "set_exit_blocks(room)", thread), dir(dir, "set_exit_blocks(dir)"), set)
        Starlark.NONE
    }

    // ---- State (SPEC §3.7) ---------------------------------------------------------------------

    /** The stored key for [key] as written by code in the caller's package. */
    private fun attrKey(key: Any, write: Boolean, what: String, thread: StarlarkThread): String {
        val k = str(key, what)
        val caller = pkgOf(thread)
        val pkg = frame.host.scripts.packages[caller]
        val own = pkg?.namespace ?: caller.substringAfterLast('/')
        val (ns, short) = if (':' in k) k.substringBefore(':') to k.substringAfter(':') else own to k
        if (!Regex("[A-Za-z0-9_]+").matches(short) || !Regex("[a-z0-9_]+").matches(ns)) fail("$what: '$k' is not a valid attribute key")
        if (write && ns != own && ns !in pkg?.sharedNamespaces.orEmpty()) {
            val declarer = frame.host.scripts.packages.values.firstOrNull { ns in it.sharedNamespaces }
            fail(
                "$what: code in //$caller can only write its own namespace '$own', not '$ns'" +
                    (declarer?.let { " (use the helpers exported by //${it.path})" } ?: ""),
            )
        }
        return "$ns:$short"
    }

    @StarlarkMethod(
        name = "set_attr",
        doc = "Stores a value (int, str, bool, list or dict) on an entity. None deletes the key.",
        parameters = [Param(name = "entity", named = true), Param(name = "key", named = true), Param(name = "value", named = true)],
        useStarlarkThread = true,
    )
    fun setAttr(entity: Any, key: Any, value: Any, thread: StarlarkThread): Any = verb("set_attr", "$key") {
        val e = entityArg(entity, "set_attr(entity)")
        val k = attrKey(key, write = true, "set_attr(key)", thread)
        val stored = toAttr(value, "set_attr(value)", frame.host.limits)
        if (stored == null) {
            e.customAttributes.remove(k)
        } else {
            val next = LinkedHashMap<String, AttrValue>(e.customAttributes).also { it[k] = stored }
            val size = jsonSize(next)
            if (size > frame.host.limits.maxAttrBytes) {
                limitFail("alloc_limit", "set_attr(): ${e.name}'s attributes would be $size bytes, over the ${frame.host.limits.maxAttrBytes / 1024} KB limit")
            }
            e.customAttributes[k] = stored
        }
        e.dirty = true
        Starlark.NONE
    }

    @StarlarkMethod(
        name = "get_attr",
        doc = "Reads a value; your own keys by short name, other packages' by 'namespace:key'.",
        parameters = [Param(name = "entity", named = true), Param(name = "key", named = true), Param(name = "default", defaultValue = "None", named = true)],
        useStarlarkThread = true,
    )
    fun getAttr(entity: Any, key: Any, default: Any, thread: StarlarkThread): Any = verb("get_attr", "$key") {
        val e = entityArg(entity, "get_attr(entity)")
        e.customAttributes[attrKey(key, write = false, "get_attr(key)", thread)]?.let { fromAttr(it, thread.mutability()) } ?: default
    }

    @StarlarkMethod(name = "del_attr", parameters = [Param(name = "entity", named = true), Param(name = "key", named = true)], useStarlarkThread = true)
    fun delAttr(entity: Any, key: Any, thread: StarlarkThread): Any = verb("del_attr", "$key") {
        val e = entityArg(entity, "del_attr(entity)")
        if (e.customAttributes.remove(attrKey(key, write = true, "del_attr(key)", thread)) != null) e.dirty = true
        Starlark.NONE
    }

    // ---- Timers --------------------------------------------------------------------------------

    @StarlarkMethod(
        name = "after",
        doc = "Calls fn(ctx, **args) on this hook's owner after some pulses. fn must be a top-level function; args must be storable.",
        parameters = [Param(name = "pulses", named = true), Param(name = "fn", named = true)],
        extraKeywords = Param(name = "args"),
    )
    fun after(pulses: Any, fn: Any, args: Dict<String, Any>): Any = verb("after") {
        val n = int(pulses, "after(pulses)")
        if (n < 1) fail("after(pulses): must be at least 1")
        val ref = hookRef(fn, "after(fn)")
        val stored = args.entries.associate { (k, x) -> k to (toAttr(x, "after($k)", frame.host.limits) ?: fail("after($k): None can't be passed to a timer")) }
        sInt(world.after(owner, n, ref, stored))
    }

    @StarlarkMethod(name = "cancel", doc = "Cancels one of this owner's timers; True if it was pending.", parameters = [Param(name = "timer_id", named = true)])
    fun cancel(timerId: Any): Any = verb("cancel") { world.cancel(owner, int(timerId, "cancel(timer_id)").toLong()) }

    // ---- Simulation (SPEC §5.9; the bubble itself arrives in M5) -------------------------------

    private fun reason(entity: Any, reason: Any, name: String, set: (Entity) -> MutableSet<String>, add: Boolean): Any = verb(name) {
        val e = entityArg(entity, "$name(entity)")
        val r = str(reason, "$name(reason)")
        if (r.isEmpty()) fail("$name(reason): must not be empty")
        val changed = if (add) set(e).add(r) else set(e).remove(r)
        if (changed) e.dirty = true
        Starlark.NONE
    }

    @StarlarkMethod(name = "set_active", parameters = [Param(name = "entity", named = true), Param(name = "reason", named = true)])
    fun setActive(entity: Any, reason: Any): Any = reason(entity, reason, "set_active", { it.activeReasons }, add = true)

    @StarlarkMethod(name = "clear_active", parameters = [Param(name = "entity", named = true), Param(name = "reason", named = true)])
    fun clearActive(entity: Any, reason: Any): Any = reason(entity, reason, "clear_active", { it.activeReasons }, add = false)

    @StarlarkMethod(name = "set_anchor", parameters = [Param(name = "entity", named = true), Param(name = "reason", named = true)])
    fun setAnchor(entity: Any, reason: Any): Any = reason(entity, reason, "set_anchor", { it.anchorReasons }, add = true)

    @StarlarkMethod(name = "clear_anchor", parameters = [Param(name = "entity", named = true), Param(name = "reason", named = true)])
    fun clearAnchor(entity: Any, reason: Any): Any = reason(entity, reason, "clear_anchor", { it.anchorReasons }, add = false)

    // ---- Queries -------------------------------------------------------------------------------

    private fun ownerRoom(): Room = world.roomOfEntity(owner) ?: fail("${owner.name} is not in a room")

    @StarlarkMethod(name = "here", doc = "The actor or ground item in the owner's room matching a keyword, or None.", parameters = [Param(name = "keyword", named = true)])
    fun here(keyword: Any): Any = verb("here") {
        val word = str(keyword, "here(keyword)")
        val room = ownerRoom()
        val a = (owner as? Actor)?.let { world.findActor(it, word) } ?: room.actors.firstOrNull { it !== owner && it.matches(word) }
        v(a ?: room.items.firstOrNull { it.matches(word) })
    }

    @StarlarkMethod(
        name = "find",
        doc = "Entities of a prototype, ascending id. scope is 'room' (the owner's room), 'zone' (its package) or 'world'.",
        parameters = [Param(name = "label", named = true), Param(name = "scope", named = true, defaultValue = "'room'")],
        useStarlarkThread = true,
    )
    fun find(label: Any, scope: Any, thread: StarlarkThread): Any = verb("find", "$label") {
        val l = label(str(label, "find(label)"), pkgOf(thread), "find(label)")
        val where = choice(scope, "find(scope)", listOf("room", "zone", "world"))
        val room = if (where == "world") null else ownerRoom()
        val found = world.allEntities.filter { it.protoLabel == l }.filter { e ->
            when (where) {
                "room" -> world.roomOfEntity(e) === room
                "zone" -> world.roomOfEntity(e)?.label?.pkg == room!!.label.pkg
                else -> true
            }
        }.sortedBy { it.id }
        sList(found.map { v(it) })
    }

    private fun near(actor: Any, band: Any, what: String, pick: (Actor, List<Actor>) -> List<Actor>): Any = verb(what) {
        val a = actorArg(actor, "$what(actor)")
        val reach = Band.parse(choice(band, "$what(band)", Band.entries.map { it.word }))
        val within = a.room.actors.filter { it !== a && reach.reaches(Band.between(a.trackIndex, it.trackIndex)) }
        sList(pick(a, within).map { v(it) })
    }

    @StarlarkMethod(name = "enemies_near", doc = "Opponents of actor within a band ('melee', 'near', 'far').", parameters = [Param(name = "actor", named = true), Param(name = "band", named = true)])
    fun enemiesNear(actor: Any, band: Any): Any = near(actor, band, "enemies_near") { a, within ->
        val foes = world.opponentsOf(a).toSet()
        within.filter { it in foes }
    }

    @StarlarkMethod(
        name = "allies_near",
        doc = "Actors of the same side (players with players, mobs with mobs) within a band who are not fighting actor.",
        parameters = [Param(name = "actor", named = true), Param(name = "band", named = true)],
    )
    fun alliesNear(actor: Any, band: Any): Any = near(actor, band, "allies_near") { a, within ->
        within.filter { it.isPlayer == a.isPlayer && it.id !in a.fighting }
    }

    @StarlarkMethod(
        name = "path_to",
        doc = "Directions from the owner's room to a room or actor through open exits; [] if already there, None if unreachable.",
        parameters = [Param(name = "target", named = true)],
        useStarlarkThread = true,
    )
    fun pathTo(target: Any, thread: StarlarkThread): Any = verb("path_to") {
        val dest = when (target) {
            is ActorView -> actorArg(target, "path_to(target)").room
            is ItemView -> world.roomOfEntity(itemArg(target, "path_to(target)")) ?: fail("path_to(): the item is nowhere")
            else -> roomArg(target, "path_to(target)", thread)
        }
        world.pathTo(ownerRoom(), dest)?.let { p -> sList(p.map { it.name }) } ?: Starlark.NONE
    }

    @StarlarkMethod(name = "roll", doc = "Rolls dice such as '2d6+1'.", parameters = [Param(name = "dice", named = true)])
    fun roll(dice: Any): Any = verb("roll", "$dice") {
        val d = try {
            Dice.parse(str(dice, "roll(dice)"))
        } catch (e: IllegalArgumentException) {
            fail("roll(): ${e.message}")
        }
        sInt(d.roll(world.rng))
    }

    override fun repr(printer: Printer) {
        printer.append("<ctx ${frame.site.hook} of ${owner.name}>")
    }

    /** One line for diagnostics (SPEC §6.2). */
    internal fun summary(): String = describeCtx(owner, hc)
}

internal fun describeCtx(owner: Entity, hc: HookContext): String = buildList {
    add("self=$owner")
    hc.actor?.let { add("actor=$it") }
    hc.target?.let { add("target=$it") }
    hc.item?.let { add("item=$it") }
    hc.room?.let { add("room=${it.label}") }
    hc.direction?.let { add("direction=$it") }
    hc.text?.let { add("text=${it.take(80)}") }
    hc.skill?.let { add("skill=${it.label}") }
    hc.amount?.let { add("amount=$it") }
}.joinToString(" ")
