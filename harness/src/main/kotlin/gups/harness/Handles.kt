package gups.harness

import gups.core.CapturingSink
import gups.core.Label
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.DEFAULT_STATS
import gups.core.model.DamageType
import gups.core.model.Entity
import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.model.Room
import gups.core.world.Diagnostic
import gups.core.world.World
import gups.core.world.attach
import gups.core.world.damage
import gups.core.world.flushOutput
import gups.core.world.handle
import gups.core.world.spawnItem
import gups.core.world.submit
import gups.script.ActorView
import gups.script.EntityView
import gups.script.ItemView
import gups.script.RoomView
import gups.script.StarlarkHost
import gups.script.ViewScope
import gups.script.callerPackage
import gups.script.starlarkValue
import gups.script.storableValue
import gups.script.view
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Printer
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkList
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue

// The Starlark objects of the test harness API (SPEC §4.12): the world handle `w` and player
// handles `p`. `t` is in Assertions.kt.

internal fun evalFail(message: String): Nothing = throw EvalException(message)

internal fun typeName(v: Any?): String = if (v == null) "None" else Starlark.type(v)

internal fun isNone(v: Any?): Boolean = v == null || v === Starlark.NONE

internal fun str(v: Any?, what: String): String = v as? String ?: evalFail("$what: expected a string, got ${typeName(v)}")

internal fun int(v: Any?, what: String): Int = (v as? StarlarkInt)?.toInt(what) ?: evalFail("$what: expected an int, got ${typeName(v)}")

internal fun intOrNull(v: Any?, what: String): Int? = if (isNone(v)) null else int(v, what)

internal fun label(text: String, pkg: String?, what: String): Label = try {
    Label.parse(text, pkg)
} catch (e: IllegalArgumentException) {
    evalFail("$what: ${e.message}")
}

/** Commands run by `p.do()` must finish within this many pulses. */
const val DO_MAX_PULSES = 1000

/** Keeps at most this many trace lines per world. */
private const val TRACE_KEEP = 400

/** One headless world created by `t.world(...)`: virtual clock, seeded RNG, no network (SPEC §7.2). */
@StarlarkBuiltin(name = "world", doc = "A headless test world.")
class WorldHandle internal constructor(
    override val world: World,
    val host: StarlarkHost,
    /** The test file's package, for relative labels and attribute keys. */
    override val pkg: String,
) : StarlarkValue, ViewScope {
    private var nextSession = 1L
    internal val traceLines = ArrayDeque<String>()
    private var traceDropped = 0

    override fun check() = Unit

    private fun pkgOf(thread: StarlarkThread) = callerPackage(thread) ?: pkg

    private fun roomArg(v: Any?, what: String, thread: StarlarkThread): Room = when (v) {
        is RoomView -> v.room
        is String -> world.roomOrNull(label(v, pkgOf(thread), what)) ?: evalFail("$what: there is no room $v")
        else -> evalFail("$what: expected a room or room label, got ${typeName(v)}")
    }

    private fun entityArg(v: Any?, what: String): Entity = (v as? EntityView)?.entity ?: evalFail("$what: expected an entity, got ${typeName(v)}")

    @StarlarkMethod(
        name = "spawn_player",
        doc = "Adds a connected player and returns its handle.",
        parameters = [
            Param(name = "name", named = true),
            Param(name = "at", named = true),
            Param(name = "level", named = true, defaultValue = "1"),
            Param(name = "hp", named = true, defaultValue = "20"),
            Param(name = "mana", named = true, defaultValue = "20"),
            Param(name = "move", named = true, defaultValue = "100"),
            Param(name = "index", named = true, defaultValue = "0"),
            Param(name = "stats", named = true, defaultValue = "None"),
            Param(name = "skills", named = true, defaultValue = "None"),
        ],
        useStarlarkThread = true,
    )
    fun spawnPlayer(name: Any, at: Any, level: Any, hp: Any, mana: Any, move: Any, index: Any, stats: Any, skills: Any, thread: StarlarkThread): Any {
        val room = roomArg(at, "spawn_player(at)", thread)
        val baseStats = DEFAULT_STATS + if (isNone(stats)) emptyMap() else (stats as? Dict<*, *>
            ?: evalFail("spawn_player(stats): expected a dict")).entries.associate { (k, v) -> str(k, "spawn_player(stats)") to int(v, "spawn_player(stats)") }
        val p = guard("spawn_player") {
            world.spawnPlayer(
                str(name, "spawn_player(name)"), room.label, int(level, "spawn_player(level)"), int(hp, "spawn_player(hp)"),
                int(mana, "spawn_player(mana)"), int(move, "spawn_player(move)"), baseStats, int(index, "spawn_player(index)"),
            )
        }
        // The harness has no idle clock; tests may run for hours of game time.
        p.idleExempt = true
        if (!isNone(skills)) {
            for ((k, v) in (skills as? Dict<*, *> ?: evalFail("spawn_player(skills): expected a dict")).entries) {
                val skill = label(str(k, "spawn_player(skills)"), pkgOf(thread), "spawn_player(skills)")
                if (skill !in world.protos.skills) evalFail("spawn_player(skills): there is no skill $skill")
                p.skills[skill] = int(v, "spawn_player(skills)")
            }
        }
        val sink = CapturingSink()
        val id = SessionId(nextSession++)
        world.handle(WorldControlEvent.Connect(id, sink))
        world.attach(id, p)
        return PlayerHandle(this, p, sink)
    }

    @StarlarkMethod(
        name = "spawn",
        doc = "Spawns a mob or item and returns its view.",
        parameters = [
            Param(name = "label", named = true),
            Param(name = "at", named = true),
            Param(name = "index", named = true, defaultValue = "0"),
            Param(name = "count", named = true, defaultValue = "1"),
        ],
        useStarlarkThread = true,
    )
    fun spawn(label: Any, at: Any, index: Any, count: Any, thread: StarlarkThread): Any {
        val l = label(str(label, "spawn(label)"), pkgOf(thread), "spawn(label)")
        val i = int(index, "spawn(index)")
        return guard("spawn") {
            when {
                l in world.protos.mobs -> view(this, world.spawnMob(l, roomArg(at, "spawn(at)", thread), i))
                l in world.protos.items -> {
                    val loc = when (at) {
                        is ActorView -> ItemLocation.Carried(at.actor.id)
                        is ItemView -> ItemLocation.Contained(at.item.id)
                        else -> ItemLocation.InRoom(roomArg(at, "spawn(at)", thread).id, i)
                    }
                    view(this, world.spawnItem(l, loc, int(count, "spawn(count)")))
                }
                else -> evalFail("spawn(label): there is no mob or item $l")
            }
        }
    }

    @StarlarkMethod(name = "run", doc = "Advances game time.", parameters = [Param(name = "pulses", named = true, defaultValue = "1")])
    fun run(pulses: Any): Any {
        val n = int(pulses, "run(pulses)")
        if (n < 0) evalFail("run(pulses): can't be negative")
        repeat(n) { world.runPulse() }
        return Starlark.NONE
    }

    @StarlarkMethod(
        name = "damage",
        doc = "Damages an actor by amount, or by pct of its max hp. Fires on_damage; death is processed at once.",
        parameters = [
            Param(name = "target", named = true),
            Param(name = "amount", named = true, defaultValue = "None"),
            Param(name = "pct", named = true, defaultValue = "None"),
            Param(name = "type", named = true, defaultValue = "'bludgeon'"),
        ],
    )
    fun damage(target: Any, amount: Any, pct: Any, type: Any): Any {
        val a = (target as? ActorView)?.actor ?: evalFail("damage(target): expected an actor, got ${typeName(target)}")
        val n = intOrNull(amount, "damage(amount)") ?: intOrNull(pct, "damage(pct)")?.let { a.maxHp * it / 100 }
            ?: evalFail("damage(): give amount or pct")
        world.damage(a, n, DamageType(str(type, "damage(type)")))
        return Starlark.NONE
    }

    /** The stored key for [key]: the test package's namespace unless it names one. */
    private fun attrKey(key: Any, thread: StarlarkThread): String {
        val k = str(key, "attribute key")
        return if (':' in k) k else "${pkgOf(thread).substringAfterLast('/')}:$k"
    }

    @StarlarkMethod(
        name = "set_attr",
        doc = "Sets an attribute directly. Unprefixed keys are in the test package's namespace.",
        parameters = [Param(name = "entity", named = true), Param(name = "key", named = true), Param(name = "value", named = true)],
        useStarlarkThread = true,
    )
    fun setAttr(entity: Any, key: Any, value: Any, thread: StarlarkThread): Any {
        val e = entityArg(entity, "set_attr(entity)")
        val k = attrKey(key, thread)
        val v = storableValue(value, "set_attr(value)")
        if (v == null) e.customAttributes.remove(k) else e.customAttributes[k] = v
        e.dirty = true
        return Starlark.NONE
    }

    @StarlarkMethod(
        name = "get_attr",
        parameters = [Param(name = "entity", named = true), Param(name = "key", named = true), Param(name = "default", named = true, defaultValue = "None")],
        useStarlarkThread = true,
    )
    fun getAttr(entity: Any, key: Any, default: Any, thread: StarlarkThread): Any =
        entityArg(entity, "get_attr(entity)").customAttributes[attrKey(key, thread)]?.let(::starlarkValue) ?: default

    @StarlarkMethod(name = "room", doc = "A view of a room.", parameters = [Param(name = "label", named = true)], useStarlarkThread = true)
    fun room(label: Any, thread: StarlarkThread): Any = RoomView(this, roomArg(label, "room(label)", thread))

    @StarlarkMethod(name = "trace", doc = "Records every hook and verb call; shown when the test fails.", parameters = [Param(name = "on", named = true, defaultValue = "True")])
    fun trace(on: Any): Any {
        host.tracer = if (on == true) { e -> addTrace("[${e.pulse}] ${e.text}") } else null
        return Starlark.NONE
    }

    private fun addTrace(line: String) {
        traceLines.addLast(line)
        if (traceLines.size > TRACE_KEEP) {
            traceLines.removeFirst()
            traceDropped++
        }
    }

    internal fun traceText(): String? {
        if (traceLines.isEmpty()) return null
        val head = if (traceDropped > 0) "  ... $traceDropped earlier lines\n" else ""
        return head + traceLines.joinToString("\n") { "  $it" }
    }

    @StarlarkMethod(name = "diagnostics", doc = "Script and engine problems logged so far.")
    fun diagnostics(): Any = StarlarkList.immutableCopyOf(world.diagnostics.map(::DiagnosticView))

    @StarlarkMethod(name = "quarantined", doc = "Packages currently quarantined.")
    fun quarantined(): Any = StarlarkList.immutableCopyOf(host.quarantined.toList())

    @StarlarkMethod(name = "pulse", structField = true, doc = "Game time.")
    fun pulse(): Any = StarlarkInt.of(world.pulse)

    private fun <T> guard(what: String, block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        evalFail("$what(): ${e.message}")
    } catch (e: NoSuchElementException) {
        evalFail("$what(): ${e.message}")
    }
}

/** A connected player in a test world: an actor view plus `do` and `output` (SPEC §4.12). */
@StarlarkBuiltin(name = "player", doc = "A test player: its view fields plus do(line) and output().")
class PlayerHandle internal constructor(private val w: WorldHandle, val player: Player, private val sink: CapturingSink) :
    ActorView(w, player) {
    @StarlarkMethod(
        name = "do",
        doc = "Enters a command line, runs pulses until the player's queued actions and casts are done, and returns the output since the last do() or output().",
        parameters = [Param(name = "line", named = true)],
    )
    fun doLine(line: Any): Any {
        val world = w.world
        if (!player.inWorld) evalFail("do(): ${player.name} is no longer in the world")
        world.submit(player, str(line, "do(line)"))
        var pulses = 0
        while (player.inWorld && (player.actionQueue.isNotEmpty() || player.casting != null)) {
            if (pulses++ >= DO_MAX_PULSES) evalFail("do(\"$line\"): still busy after $DO_MAX_PULSES pulses")
            world.runPulse()
        }
        return output()
    }

    @StarlarkMethod(name = "output", doc = "Output received since the last do() or output().")
    fun output(): Any {
        w.world.flushOutput()
        return sink.drain()
    }
}

/** A read-only diagnostic for `w.diagnostics()`. */
@StarlarkBuiltin(name = "diagnostic")
class DiagnosticView internal constructor(private val d: Diagnostic) : StarlarkValue {
    @StarlarkMethod(name = "kind", structField = true)
    fun kind(): Any = d.kind

    @StarlarkMethod(name = "message", structField = true)
    fun message(): Any = d.message

    @StarlarkMethod(name = "package", structField = true)
    fun pkg(): Any = d.pkg ?: Starlark.NONE

    @StarlarkMethod(name = "label", structField = true)
    fun label(): Any = d.label?.v ?: Starlark.NONE

    @StarlarkMethod(name = "hook", structField = true)
    fun hook(): Any = d.hook ?: Starlark.NONE

    @StarlarkMethod(name = "file", structField = true)
    fun file(): Any = d.file ?: Starlark.NONE

    @StarlarkMethod(name = "line", structField = true)
    fun line(): Any = d.line?.let { StarlarkInt.of(it) } ?: Starlark.NONE

    @StarlarkMethod(name = "pulse", structField = true)
    fun pulse(): Any = StarlarkInt.of(d.pulse)

    override fun isImmutable(): Boolean = true

    override fun repr(printer: Printer) {
        printer.append("<diagnostic ${d.kind}: ${d.message}>")
    }
}

/** The label an entity view stands for when compared with a label string. */
internal fun labelOf(v: Any?): Label? = when (v) {
    is ActorView -> if (v.actor is Player) null else v.actor.protoLabel
    is EntityView -> v.entity.protoLabel
    else -> null
}
