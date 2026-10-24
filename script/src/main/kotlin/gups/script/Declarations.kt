package gups.script

import gups.core.EngineInfo
import gups.core.HookRef
import gups.core.Label
import gups.core.PERCEPTION_MAX_RADIUS
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.ContainerProto
import gups.core.model.DEFAULT_STATS
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Direction
import gups.core.model.DoorProto
import gups.core.model.DoorState
import gups.core.model.DormantRecovery
import gups.core.model.Effect
import gups.core.model.ExitProto
import gups.core.model.Interrupt
import gups.core.model.ItemProto
import gups.core.model.LootRule
import gups.core.model.MobProto
import gups.core.model.ModifierPoint
import gups.core.model.Modifiers
import gups.core.model.RegionProto
import gups.core.model.RoomProto
import gups.core.model.STAT_NAMES
import gups.core.model.Sense
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.SpawnRule
import gups.core.model.TargetKind
import gups.core.model.TrackSize
import gups.core.world.ActorFilter
import gups.core.world.Aggressive
import gups.core.world.Assist
import gups.core.world.Behavior
import gups.core.world.Flee
import gups.core.world.Follow
import gups.core.world.Guard
import gups.core.world.HookName
import gups.core.world.Lane
import gups.core.world.Pursue
import gups.core.world.Scavenge
import gups.core.world.Sentinel
import gups.core.world.Wander
import gups.core.world.ZoneCommand
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Printer
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkList
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue
import net.starlark.java.eval.Structure
import com.google.common.collect.ImmutableCollection
import com.google.common.collect.ImmutableList
import net.starlark.java.eval.Tuple
import net.starlark.java.spelling.SpellChecker

// The content declarations of a package (SPEC §4.1–§4.3). They are only callable while a
// package's files evaluate; each one produces a frozen Kotlin prototype.

/** What the file being evaluated may do. Attached to the evaluating thread. */
internal class LoadCtx(
    val file: FileInfo,
    val pkg: ScriptPackage,
    val header: Boolean,
    private val onStep: () -> Unit,
) {
    var packageDeclared = false

    /** The wall-clock backstop for package evaluation, checked at each declaration. */
    fun tick() = onStep()

    fun ref(label: Label, kind: RefKind, thread: StarlarkThread) {
        val loc = thread.callerLocation
        pkg.refs += LabelRef(label, kind, loc?.file(), loc?.line(), loc?.column())
    }
}

/** Kinds of entity a hook can be attached to (SPEC §4.4 "Attach to"). */
internal enum class OwnerKind(val word: String) { ROOM("room"), MOB("mob"), ITEM("item") }

/** The hook catalog's attachment rules (SPEC §4.4). */
internal object HookCatalog {
    private val R = OwnerKind.ROOM
    private val M = OwnerKind.MOB
    private val I = OwnerKind.ITEM

    val attach: Map<HookName, Set<OwnerKind>> = mapOf(
        HookName.ON_LOAD to setOf(R, M, I),
        HookName.ON_SPAWN to setOf(R, M, I),
        HookName.ON_RESET to setOf(R),
        HookName.ON_WAKE to setOf(R, M, I),
        HookName.BEFORE_ENTER to setOf(R, M),
        HookName.ON_ENTER to setOf(R, M),
        HookName.BEFORE_EXIT to setOf(R, M),
        HookName.ON_EXIT to setOf(R, M),
        HookName.ON_SAY to setOf(M, R),
        HookName.ON_TELL to setOf(M),
        HookName.BEFORE_GET to setOf(I, M),
        HookName.ON_GET to setOf(I, M),
        HookName.BEFORE_DROP to setOf(I, M),
        HookName.ON_DROP to setOf(I, M),
        HookName.BEFORE_USE to setOf(I),
        HookName.ON_USE to setOf(I),
        HookName.ON_GIVE to setOf(M, I),
        HookName.BEFORE_ATTACK to setOf(M, I),
        HookName.BEFORE_CAST to setOf(M, I, R),
        HookName.ON_CAST_START to setOf(M, I, R),
        HookName.ON_CAST to setOf(M, I, R),
        HookName.ON_DAMAGE to setOf(M, I),
        HookName.ON_DEATH to setOf(M, R),
        HookName.ON_COMBAT_TICK to setOf(M, I),
        HookName.ON_TICK to setOf(R, M, I),
        HookName.ON_NEARBY to setOf(M),
    )

    val names: List<String> = HookName.entries.map { it.scriptName }

    fun byName(name: String): HookName? = HookName.entries.firstOrNull { it.scriptName == name }

    fun looksLikeHook(name: String): Boolean = name.startsWith("on_") || name.startsWith("before_")
}

/**
 * Keyword-argument reader for one declaration call. Every key read is "known"; [finish]
 * rejects the rest with a spelling suggestion.
 */
internal class Kw(private val fn: String, private val kwargs: Map<String, Any>) {
    private val known = LinkedHashSet<String>()

    /** The value of [name], or null if absent or None. */
    operator fun get(name: String): Any? {
        known += name
        return kwargs[name]?.takeUnless(::isNone)
    }

    fun what(name: String) = "$fn($name)"

    /** Hook kwargs (`on_*`, `before_*`) attachable to [kind]. */
    fun hooks(kind: OwnerKind): Map<HookName, HookRef> {
        val out = LinkedHashMap<HookName, HookRef>()
        for ((key, value) in kwargs) {
            if (!HookCatalog.looksLikeHook(key)) continue
            known += key
            val hook = HookCatalog.byName(key)
                ?: fail("$fn(): unknown hook '$key'${SpellChecker.didYouMean(key, HookCatalog.names)}")
            if (kind !in HookCatalog.attach.getValue(hook)) {
                fail("$fn(): hook '$key' can't be attached to a ${kind.word}")
            }
            if (isNone(value)) continue
            out[hook] = hookRef(value, "$fn($key)")
        }
        return out
    }

    /** Arguments the spec defines that this engine build does not implement yet. */
    fun notYet(name: String, milestone: String) {
        known += name
        val v = kwargs[name]
        val empty = isNone(v) || (v is Dict<*, *> && v.isEmpty()) || (v is Sequence<*> && v.isEmpty())
        if (!empty) fail("$fn(): '$name' is not supported by this engine yet (planned for $milestone)")
    }

    fun finish() {
        for (key in kwargs.keys) {
            if (key !in known) fail("$fn(): unexpected argument '$key'${SpellChecker.didYouMean(key, known)}")
        }
    }
}

// ---- Small declaration values -------------------------------------------------------------------

/** Base for values such as `exit(...)` that only make sense inside another declaration. */
internal abstract class DeclValue(private val kind: String) : StarlarkValue {
    override fun repr(printer: Printer) {
        printer.append("<$kind>")
    }

    override fun isImmutable(): Boolean = true
}

internal class ExitDecl(val proto: ExitProto) : DeclValue("exit")

internal class DoorDecl(val proto: DoorProto) : DeclValue("door")

internal class SpawnDecl(val rule: SpawnRule) : DeclValue("spawn")

internal class LootDecl(val rule: LootRule) : DeclValue("loot")

internal class ContainerDecl(val proto: ContainerProto) : DeclValue("container")

internal class EffectDecl(val effect: Effect) : DeclValue("effect")

internal class BehaviorDecl(val behavior: Behavior) : DeclValue("behavior")

/** What `struct(**fields)` returns: read-only named fields (D-125). */
internal class StructValue(private val fields: Map<String, Any>) : Structure {
    override fun getValue(name: String): Any? = fields[name]

    override fun getFieldNames(): ImmutableCollection<String> = ImmutableList.copyOf(fields.keys)

    override fun getErrorMessageForUnknownField(field: String): String =
        "struct has no field '$field'${SpellChecker.didYouMean(field, fields.keys)}"

    override fun isImmutable(): Boolean = fields.values.all { Starlark.isImmutable(it) }

    override fun repr(printer: Printer) {
        printer.append("struct(")
        fields.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) printer.append(", ")
            printer.append(k).append(" = ").repr(v)
        }
        printer.append(")")
    }
}

// ---- Parsing helpers -----------------------------------------------------------------------------

private fun loadCtx(thread: StarlarkThread, fn: String): LoadCtx {
    val ctx = thread.getThreadLocal(LoadCtx::class.java)
        ?: fail("$fn() can only be called while a package loads, not from a hook")
    ctx.tick()
    return ctx
}

private fun contentCtx(thread: StarlarkThread, fn: String): LoadCtx {
    val ctx = loadCtx(thread, fn)
    if (ctx.header) fail("$fn() belongs in a package's other .star files, not in package.star")
    if (ctx.file.test) fail("$fn() can't be used in a test file")
    return ctx
}

/** The declaration's single positional argument: its name within the package. */
private fun declName(fn: String, args: Tuple, ctx: LoadCtx): String {
    if (args.size != 1) fail("$fn() takes the declaration name as its only positional argument")
    val name = str(args[0], "$fn() name")
    label(name.let { ":$it" }, ctx.pkg.path, "$fn() name")
    if (!ctx.pkg.names.add(name)) fail("$fn(): '$name' is already declared in //${ctx.pkg.path}")
    return name
}

private fun channels(v: Any?, what: String): Set<Channel> =
    strList(v, what).mapTo(LinkedHashSet()) { Channel.valueOf(choice(it, what, CHANNELS).uppercase()) }

private val CHANNELS = Channel.entries.map { it.name.lowercase() }

private fun dice(v: Any?, what: String): Dice = try {
    Dice.parse(str(v, what))
} catch (e: IllegalArgumentException) {
    fail("$what: ${e.message}")
}

private fun band(v: Any?, what: String): Band = Band.parse(choice(v, what, Band.entries.map { it.word }))

private fun doorState(v: Any?, what: String): DoorState =
    DoorState.valueOf(choice(v, what, DoorState.entries.map { it.name.lowercase() }).uppercase())

private fun stats(v: Any?, what: String): Map<String, Int> {
    val given = intDict(v, what)
    for (k in given.keys) if (k !in STAT_NAMES) fail("$what: unknown stat '$k'${SpellChecker.didYouMean(k, STAT_NAMES)}")
    return DEFAULT_STATS + given
}

private fun statDeltas(v: Any?, what: String): Map<String, Int> {
    val given = intDict(v, what)
    for (k in given.keys) if (k !in STAT_NAMES) fail("$what: unknown stat '$k'${SpellChecker.didYouMean(k, STAT_NAMES)}")
    return given
}

private fun displayName(id: String): String = id.replace('_', ' ')

private val ARTICLES = setOf("a", "an", "the", "some")

/** Keywords default to the words of the display name minus articles, or else the declaration name (D-070). */
private fun defaultKeywords(name: String, id: String): List<String> =
    name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in ARTICLES }.ifEmpty { listOf(id.lowercase()) }

private fun commands(v: Any?, what: String, pkg: String): Map<String, ZoneCommand> {
    if (isNone(v)) return emptyMap()
    return dict(v, what).entries.associate { (verb, fn) ->
        val word = verb.lowercase()
        if (!Regex("[a-z][a-z0-9_]*").matches(word)) fail("$what: '$verb' is not a valid verb")
        word to ScriptCommand(word, hookRef(fn, "$what[\"$verb\"]"), pkg)
    }
}

private fun version(kw: Kw, fn: String): Int {
    val v = kw["version"]?.let { int(it, kw.what("version")) } ?: 1
    if (v < 1) fail("$fn(version): must be at least 1")
    return v
}

private val POINTS = ModifierPoint.entries.map { it.scriptName }

/** A `modifiers` dict: modifier point name to a top-level function `fn(ctx, base)` (SPEC §4.6). */
internal fun modifiers(v: Any?, what: String): Modifiers {
    if (isNone(v)) return emptyMap()
    return dict(v, what).entries.associate { (k, fn) ->
        val point = ModifierPoint.byName(k)
            ?: fail("$what: '$k' is not a modifier point${SpellChecker.didYouMean(k, POINTS)} (one of ${POINTS.joinToString(", ")})")
        point to hookRef(fn, "$what[\"$k\"]")
    }
}

/** Affect flags such as `no_regen`: lowercase words. */
private fun flags(v: Any?, what: String): Set<String> = strList(v, what).onEach {
    if (!Regex("[a-z][a-z0-9_]*").matches(it)) fail("$what: '$it' is not a valid flag")
}.toSet()

/**
 * Whom a behavior means: `players`, `mobs`, `all`, or label patterns with `*` such as
 * `goblin_*` (any package) or `:goblin_*` (this package). A string or a list of them.
 */
private fun actorFilter(v: Any?, what: String, pkg: String): ActorFilter {
    val items = if (v is String) listOf(v) else strList(v, what)
    if (items.isEmpty()) fail("$what: name at least one of players, mobs, all, or a label pattern")
    return ActorFilter(
        items.map { p ->
            when {
                p in setOf("players", "mobs", "all", "anyone") -> p
                p.startsWith(":") -> "//$pkg$p"
                else -> p
            }.also { q ->
                if (q !in setOf("players", "mobs", "all", "anyone") && !Regex("(//[a-z0-9_/]+:)?[A-Za-z0-9_.*\\-]+").matches(q)) {
                    fail("$what: '$p' is not players, mobs, all, or a label pattern such as goblin_*")
                }
            }
        },
    )
}

private fun effectValue(v: Any?, what: String): Effect =
    (v as? EffectDecl)?.effect ?: fail("$what: expected an effect such as damage(...), got ${typeName(v)}")

/**
 * The declaration builtins, plus the effect primitives (SPEC §4.3) and constants. Effect
 * primitives work anywhere; everything else only while a package loads.
 */
internal class DeclarationBuiltins {
    @StarlarkMethod(
        name = "package",
        doc = "Declares the package. Must be called exactly once, in package.star.",
        extraKeywords = Param(name = "kwargs"),
        useStarlarkThread = true,
    )
    fun pkg(kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
        val ctx = loadCtx(thread, "package")
        if (!ctx.header) fail("package() can only be called in package.star")
        if (ctx.packageDeclared) fail("package() is called more than once")
        ctx.packageDeclared = true
        val kw = Kw("package", kwargs)
        val p = ctx.pkg
        p.apiVersion = kw["api_version"]?.let { int(it, kw.what("api_version")) } ?: fail("package(): api_version is required")
        // Checked first: a package for another version may use arguments this engine doesn't know.
        if (p.apiVersion != EngineInfo.API_VERSION) ApiVersionMismatch(p.apiVersion).let { throw EvalException(it.message, it) }
        p.kind = kw["kind"]?.let { PackageKind.parse(choice(it, kw.what("kind"), PackageKind.entries.map { k -> k.word })) }
            ?: fail("package(): kind is required (zone, lib, rules or skills)")
        p.title = kw["title"]?.let { str(it, kw.what("title")) } ?: p.path
        p.resetPulses = kw["reset_pulses"]?.let { int(it, kw.what("reset_pulses")) }?.also {
            if (it < 1) fail("package(reset_pulses): must be at least 1")
        }
        p.sharedNamespaces = strList(kw["shared_namespaces"], kw.what("shared_namespaces"))
        if (p.sharedNamespaces.isNotEmpty() && !p.kind.privileged) {
            fail("package(shared_namespaces): only lib and rules packages may declare shared namespaces")
        }
        for (ns in p.sharedNamespaces) if (!Regex("[a-z0-9_]+").matches(ns)) fail("package(shared_namespaces): '$ns' is not a valid namespace")
        p.modifiers = modifiers(kw["modifiers"], kw.what("modifiers"))
        p.expectedTombstones = strList(kw["expected_tombstones"], kw.what("expected_tombstones")).map {
            label(it, p.path, kw.what("expected_tombstones"))
        }
        p.migrateNoop = strList(kw["migrate_noop"], kw.what("migrate_noop")).map {
            label(it, p.path, kw.what("migrate_noop"))
        }
        kw.finish()
        return Starlark.NONE
    }

    @StarlarkMethod(
        name = "room",
        doc = "Declares a room.",
        extraPositionals = Param(name = "args"),
        extraKeywords = Param(name = "kwargs"),
        useStarlarkThread = true,
    )
    fun room(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "room")
        val id = declName("room", args, ctx)
        val pkg = ctx.pkg.path
        val label = Label("//$pkg:$id")
        val kw = Kw("room", kwargs)
        val size = kw["size"]?.let { TrackSize.valueOf(choice(it, kw.what("size"), TrackSize.entries.map { s -> s.name.lowercase() }).uppercase()) }
            ?: TrackSize.NORMAL
        val flags = strList(kw["flags"], kw.what("flags")).map { choice(it, kw.what("flags"), ROOM_FLAGS) }.toSet()
        val exits = kw["exits"]?.let { list(it, kw.what("exits")) }.orEmpty().map {
            (it as? ExitDecl)?.proto ?: fail("room(exits): expected exit(...), got ${typeName(it)}")
        }
        exits.groupBy { it.direction }.forEach { (dir, same) -> if (same.size > 1) fail("room(exits): two exits lead $dir") }
        for (e in exits) {
            ctx.ref(e.to, RefKind.ROOM, thread)
            e.door?.key?.let { ctx.ref(it, RefKind.ITEM, thread) }
        }
        val spawns = kw["spawns"]?.let { list(it, kw.what("spawns")) }.orEmpty().map {
            (it as? SpawnDecl)?.rule ?: fail("room(spawns): expected spawn(...), got ${typeName(it)}")
        }
        for (s in spawns) {
            ctx.ref(s.label, RefKind.MOB, thread)
            if (s.atIndex !in 0 until size.slots) fail("room(spawns): at_index ${s.atIndex} is outside this ${size.name.lowercase()} room")
        }
        val proto = RoomProto(
            label = label,
            name = kw["name"]?.let { str(it, kw.what("name")) } ?: displayName(id),
            desc = kw["desc"]?.let { str(it, kw.what("desc")) } ?: "",
            size = size,
            regions = strList(kw["regions"], kw.what("regions")).toSet(),
            flags = flags,
            exits = exits,
            spawns = spawns,
            commands = commands(kw["commands"], kw.what("commands"), pkg),
            modifiers = modifiers(kw["modifiers"], kw.what("modifiers")),
            version = version(kw, "room"),
        )
        for (r in proto.regions) if (!Regex("[a-z0-9_]+").matches(r)) fail("room(regions): '$r' is not a valid region name")
        val hooks = kw.hooks(OwnerKind.ROOM)
        kw.finish()
        ctx.pkg.roomList += proto
        if (hooks.isNotEmpty()) ctx.pkg.hookMap[label] = hooks
        return Starlark.NONE
    }

    @StarlarkMethod(
        name = "exit",
        doc = "An exit, for room(exits=[...]).",
        extraPositionals = Param(name = "args"),
        extraKeywords = Param(name = "kwargs"),
        useStarlarkThread = true,
    )
    fun exit(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "exit")
        if (args.isEmpty() || args.size > 2) fail("exit() takes a direction and an optional destination")
        val dir = Direction.parse(str(args[0], "exit() direction"))
        if (!Regex("[a-z][a-z0-9_]*").matches(dir.name)) fail("exit(): '${dir.name}' is not a valid exit name")
        val kw = Kw("exit", kwargs)
        val toText = if (args.size == 2) str(args[1], "exit() destination") else kw["to"]?.let { str(it, kw.what("to")) }
        val to = label(toText ?: fail("exit(): 'to' is required"), ctx.pkg.path, kw.what("to"))
        val proto = ExitProto(
            direction = dir,
            to = to,
            entryIndex = kw["entry_index"]?.let { int(it, kw.what("entry_index")) } ?: 0,
            blocks = channels(kw["blocks"], kw.what("blocks")),
            door = kw["door"]?.let { (it as? DoorDecl)?.proto ?: fail("exit(door): expected door(...), got ${typeName(it)}") },
            oneWay = kw["one_way"]?.let { bool(it, kw.what("one_way")) } ?: false,
        )
        if (proto.entryIndex < -1) fail("exit(entry_index): must be -1 (the back) or a track index")
        kw.finish()
        return ExitDecl(proto)
    }

    @StarlarkMethod(name = "door", doc = "A door, for exit(door=...).", extraKeywords = Param(name = "kwargs"), useStarlarkThread = true)
    fun door(kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "door")
        val kw = Kw("door", kwargs)
        val d = DoorProto()
        val proto = DoorProto(
            state = kw["state"]?.let { doorState(it, kw.what("state")) } ?: d.state,
            key = kw["key"]?.let { label(str(it, kw.what("key")), ctx.pkg.path, kw.what("key")) },
            openBlocks = if (kw["open_blocks"] != null) channels(kwargs["open_blocks"], kw.what("open_blocks")) else d.openBlocks,
            closedBlocks = if (kw["closed_blocks"] != null) channels(kwargs["closed_blocks"], kw.what("closed_blocks")) else d.closedBlocks,
        )
        kw.finish()
        return DoorDecl(proto)
    }

    @StarlarkMethod(
        name = "spawn",
        doc = "A spawn rule, for room(spawns=[...]).",
        extraPositionals = Param(name = "args"),
        extraKeywords = Param(name = "kwargs"),
        useStarlarkThread = true,
    )
    fun spawn(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "spawn")
        if (args.size != 1) fail("spawn() takes the mob's label as its only positional argument")
        val kw = Kw("spawn", kwargs)
        val d = SpawnRule(Label("//engine:none"))
        val rule = SpawnRule(
            label = label(str(args[0], "spawn() label"), ctx.pkg.path, "spawn() label"),
            count = kw["count"]?.let { int(it, kw.what("count")) } ?: d.count,
            respawnPulses = kw["respawn_pulses"]?.let { int(it, kw.what("respawn_pulses")) } ?: d.respawnPulses,
            atIndex = kw["at_index"]?.let { int(it, kw.what("at_index")) } ?: d.atIndex,
        )
        if (rule.count < 1) fail("spawn(count): must be at least 1")
        if (rule.respawnPulses < 1) fail("spawn(respawn_pulses): must be at least 1")
        kw.finish()
        return SpawnDecl(rule)
    }

    @StarlarkMethod(
        name = "loot",
        doc = "A loot roll, for mob(loot=[...]). chance is a probability between 0 and 1.",
        extraPositionals = Param(name = "args"),
        extraKeywords = Param(name = "kwargs"),
        useStarlarkThread = true,
    )
    fun loot(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "loot")
        if (args.size != 1) fail("loot() takes the item's label as its only positional argument")
        val kw = Kw("loot", kwargs)
        val rule = LootRule(
            label = label(str(args[0], "loot() label"), ctx.pkg.path, "loot() label"),
            chance = kw["chance"]?.let { number(it, kw.what("chance")) } ?: 1.0,
            count = kw["count"]?.let { int(it, kw.what("count")) } ?: 1,
        )
        if (rule.chance !in 0.0..1.0) fail("loot(chance): must be between 0 and 1")
        if (rule.count < 1) fail("loot(count): must be at least 1")
        kw.finish()
        return LootDecl(rule)
    }

    @StarlarkMethod(name = "container", doc = "Container settings, for item(container=...).", extraKeywords = Param(name = "kwargs"), useStarlarkThread = true)
    fun container(kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "container")
        val kw = Kw("container", kwargs)
        val d = ContainerProto()
        val proto = ContainerProto(
            capacity = kw["capacity"]?.let { int(it, kw.what("capacity")) } ?: d.capacity,
            maxWeight = kw["max_weight"]?.let { int(it, kw.what("max_weight")) } ?: d.maxWeight,
            state = kw["state"]?.let { doorState(it, kw.what("state")) } ?: d.state,
            key = kw["key"]?.let { label(str(it, kw.what("key")), ctx.pkg.path, kw.what("key")) },
        )
        kw.finish()
        return ContainerDecl(proto)
    }

    @StarlarkMethod(
        name = "mob",
        doc = "Declares a mob prototype.",
        extraPositionals = Param(name = "args"),
        extraKeywords = Param(name = "kwargs"),
        useStarlarkThread = true,
    )
    fun mob(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "mob")
        val id = declName("mob", args, ctx)
        val pkg = ctx.pkg.path
        val label = Label("//$pkg:$id")
        val kw = Kw("mob", kwargs)
        val name = kw["name"]?.let { str(it, kw.what("name")) } ?: displayName(id)
        val skills = kw["skills"]?.let { dict(it, kw.what("skills")) }.orEmpty().entries.associate { (k, v) ->
            val skill = label(k, pkg, kw.what("skills"))
            ctx.ref(skill, RefKind.SKILL, thread)
            val prof = int(v, "mob(skills[\"$k\"])")
            if (prof !in 0..100) fail("mob(skills[\"$k\"]): proficiency must be 0..100")
            skill to prof
        }
        val senses = intDict(kw["senses"], kw.what("senses")).entries.associate { (k, v) ->
            val sense = Sense.valueOf(choice(k, kw.what("senses"), Sense.entries.map { it.name.lowercase() }).uppercase())
            if (v !in 0..PERCEPTION_MAX_RADIUS) fail("mob(senses[\"$k\"]): radius must be 0..$PERCEPTION_MAX_RADIUS")
            sense to v
        }
        val loot = kw["loot"]?.let { list(it, kw.what("loot")) }.orEmpty().map {
            (it as? LootDecl)?.rule ?: fail("mob(loot): expected loot(...), got ${typeName(it)}")
        }
        for (l in loot) ctx.ref(l.label, RefKind.ITEM, thread)
        val d = MobProto(label, name, emptyList())
        val proto = MobProto(
            label = label,
            name = name,
            keywords = kw["keywords"]?.let { strList(it, kw.what("keywords")) } ?: defaultKeywords(name, id),
            desc = kw["desc"]?.let { str(it, kw.what("desc")) } ?: "",
            level = kw["level"]?.let { int(it, kw.what("level")) } ?: d.level,
            stats = stats(kw["stats"], kw.what("stats")),
            hp = kw["hp"]?.let { int(it, kw.what("hp")) } ?: d.hp,
            mana = kw["mana"]?.let { int(it, kw.what("mana")) } ?: d.mana,
            move = kw["move"]?.let { int(it, kw.what("move")) } ?: d.move,
            armor = kw["armor"]?.let { int(it, kw.what("armor")) } ?: d.armor,
            resists = intDict(kw["resists"], kw.what("resists")).mapKeys { DamageType(it.key.lowercase()) },
            skills = skills,
            senses = senses,
            think = kw["think"]?.let { ScriptThink(hookRef(it, kw.what("think")), pkg) },
            thinkEvery = kw["think_every"]?.let { int(it, kw.what("think_every")) } ?: d.thinkEvery,
            behaviors = kw["behaviors"]?.let { list(it, kw.what("behaviors")) }.orEmpty().map {
                (it as? BehaviorDecl)?.behavior ?: fail("mob(behaviors): expected a behavior such as wander(...), got ${typeName(it)}")
            },
            dormantRecovery = kw["dormant_recovery"]?.let {
                DormantRecovery.valueOf(choice(it, kw.what("dormant_recovery"), DormantRecovery.entries.map { r -> r.name.lowercase() }).uppercase())
            } ?: d.dormantRecovery,
            loot = loot,
            version = version(kw, "mob"),
        )
        if (proto.hp < 1) fail("mob(hp): must be at least 1")
        if (proto.thinkEvery < 1) fail("mob(think_every): must be at least 1")
        if (proto.keywords.isEmpty()) fail("mob(keywords): a mob needs at least one keyword")
        val hooks = kw.hooks(OwnerKind.MOB)
        kw.finish()
        ctx.pkg.mobList += proto
        if (hooks.isNotEmpty()) ctx.pkg.hookMap[label] = hooks
        return Starlark.NONE
    }

    @StarlarkMethod(
        name = "item",
        doc = "Declares an item prototype.",
        extraPositionals = Param(name = "args"),
        extraKeywords = Param(name = "kwargs"),
        useStarlarkThread = true,
    )
    fun item(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "item")
        val id = declName("item", args, ctx)
        val pkg = ctx.pkg.path
        val label = Label("//$pkg:$id")
        val kw = Kw("item", kwargs)
        val name = kw["name"]?.let { str(it, kw.what("name")) } ?: displayName(id)
        val wear = kw["wear"]?.let { str(it, kw.what("wear")) }
        val wield = kw["wield"]?.let { str(it, kw.what("wield")) }
        if (wear != null && wield != null) fail("item(): give either wear or wield, not both")
        val container = kw["container"]?.let { (it as? ContainerDecl)?.proto ?: fail("item(container): expected container(...), got ${typeName(it)}") }
        container?.key?.let { ctx.ref(it, RefKind.ITEM, thread) }
        val d = ItemProto(label, name, emptyList())
        val proto = ItemProto(
            label = label,
            name = name,
            keywords = kw["keywords"]?.let { strList(it, kw.what("keywords")) } ?: defaultKeywords(name, id),
            desc = kw["desc"]?.let { str(it, kw.what("desc")) } ?: "",
            kind = kw["kind"]?.let { str(it, kw.what("kind")).lowercase() } ?: d.kind,
            range = kw["range"]?.let { band(it, kw.what("range")) } ?: d.range,
            weight = kw["weight"]?.let { int(it, kw.what("weight")) } ?: d.weight,
            wear = wear ?: wield,
            dice = kw["dice"]?.let { dice(it, kw.what("dice")) },
            damageType = kw["damage_type"]?.let { DamageType(str(it, kw.what("damage_type")).lowercase()) } ?: d.damageType,
            statDeltas = statDeltas(kw["stat_deltas"], kw.what("stat_deltas")),
            stackable = kw["stackable"]?.let { bool(it, kw.what("stackable")) } ?: false,
            decayPulses = kw["decay_pulses"]?.let { int(it, kw.what("decay_pulses")) },
            container = container,
            commands = commands(kw["commands"], kw.what("commands"), pkg),
            equipAffects = flags(kw["equip_affects"], kw.what("equip_affects")),
            modifiers = modifiers(kw["modifiers"], kw.what("modifiers")),
            version = version(kw, "item"),
        )
        if (proto.weight < 0) fail("item(weight): can't be negative")
        if (proto.keywords.isEmpty()) fail("item(keywords): an item needs at least one keyword")
        if (proto.wear == null && (proto.equipAffects.isNotEmpty() || proto.modifiers.isNotEmpty())) {
            fail("item(): equip_affects and modifiers apply while the item is worn, so it needs a wear slot")
        }
        val hooks = kw.hooks(OwnerKind.ITEM)
        kw.finish()
        ctx.pkg.itemList += proto
        if (hooks.isNotEmpty()) ctx.pkg.hookMap[label] = hooks
        return Starlark.NONE
    }

    @StarlarkMethod(
        name = "skill",
        doc = "Declares a skill or spell.",
        extraPositionals = Param(name = "args"),
        extraKeywords = Param(name = "kwargs"),
        useStarlarkThread = true,
    )
    fun skill(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "skill")
        val id = declName("skill", args, ctx)
        val pkg = ctx.pkg.path
        val label = Label("//$pkg:$id")
        val kw = Kw("skill", kwargs)
        val cost = intDict(kw["cost"], kw.what("cost"))
        for (k in cost.keys) choice(k, kw.what("cost"), listOf("hp", "mana", "move"))
        val interrupt = kw["interrupt"]?.let { v ->
            val m = dict(v, kw.what("interrupt"))
            for (k in m.keys) choice(k, kw.what("interrupt"), listOf("damage_pct", "stun"))
            Interrupt(
                damagePct = m["damage_pct"]?.let { intOrNull(it, "skill(interrupt[\"damage_pct\"])") },
                stun = m["stun"]?.let { bool(it, "skill(interrupt[\"stun\"])") } ?: true,
            )
        } ?: Interrupt()
        val d = SkillProto(label)
        val proto = SkillProto(
            label = label,
            kind = kw["kind"]?.let { str(it, kw.what("kind")) } ?: d.kind,
            cost = cost,
            warmup = kw["warmup"]?.let { int(it, kw.what("warmup")) } ?: 0,
            lag = kw["lag"]?.let { int(it, kw.what("lag")) } ?: 0,
            range = kw["range"]?.let { SkillRange.valueOf(choice(it, kw.what("range"), SkillRange.entries.map { r -> r.name.lowercase() }).uppercase()) }
                ?: d.range,
            target = kw["target"]?.let { TargetKind.valueOf(choice(it, kw.what("target"), TargetKind.entries.map { t -> t.name.lowercase() }).uppercase()) }
                ?: d.target,
            channels = channels(kw["channels"], kw.what("channels")),
            interrupt = interrupt,
            attackWhileCasting = kw["attack_while_casting"]?.let { bool(it, kw.what("attack_while_casting")) } ?: false,
            effect = kw["effect"]?.let { effectValue(it, kw.what("effect")) },
            resolve = kw["resolve"]?.let { ScriptResolve(hookRef(it, kw.what("resolve")), pkg) },
        )
        if (proto.warmup < 0 || proto.lag < 0) fail("skill(): warmup and lag can't be negative")
        proto.effect?.let { effectRefs(it, ctx, thread) }
        kw.finish()
        ctx.pkg.skillList += proto
        return Starlark.NONE
    }

    private fun effectRefs(e: Effect, ctx: LoadCtx, thread: StarlarkThread) {
        when (e) {
            is Effect.Summon -> ctx.ref(e.label, RefKind.MOB, thread)
            is Effect.Sequence -> e.effects.forEach { effectRefs(it, ctx, thread) }
            else -> Unit
        }
    }

    @StarlarkMethod(
        name = "region",
        doc = "Declares the modifiers of a region, a tag rooms carry with room(regions=[...]). Region names are world-wide.",
        extraPositionals = Param(name = "args"),
        extraKeywords = Param(name = "kwargs"),
        useStarlarkThread = true,
    )
    fun region(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "region")
        val id = declName("region", args, ctx)
        if (!Regex("[a-z0-9_]+").matches(id)) fail("region(): '$id' is not a valid region name")
        val kw = Kw("region", kwargs)
        val proto = RegionProto(id, ctx.pkg.path, modifiers(kw["modifiers"], kw.what("modifiers")))
        kw.finish()
        ctx.pkg.regionList += proto
        return Starlark.NONE
    }

    // ---- Behaviors (SPEC §4.7) --------------------------------------------------------------------

    @StarlarkMethod(
        name = "wander",
        doc = "Behavior: with probability chance per think, steps through a random open exit within radius exits of home.",
        parameters = [Param(name = "radius", named = true, defaultValue = "3"), Param(name = "chance", named = true, defaultValue = "0.1")],
        useStarlarkThread = true,
    )
    fun wander(radius: Any, chance: Any, thread: StarlarkThread): Any {
        contentCtx(thread, "wander")
        val r = int(radius, "wander(radius)").also { if (it < 0) fail("wander(radius): can't be negative") }
        val c = number(chance, "wander(chance)").also { if (it !in 0.0..1.0) fail("wander(chance): must be between 0 and 1") }
        return BehaviorDecl(Wander(r, c))
    }

    @StarlarkMethod(
        name = "aggressive",
        doc = "Behavior: attacks matching actors on sight. toward is 'players', 'mobs', 'all' or label patterns.",
        parameters = [Param(name = "toward", named = true, defaultValue = "'players'"), Param(name = "unless_level_above", named = true, defaultValue = "None")],
        useStarlarkThread = true,
    )
    fun aggressive(toward: Any, unlessLevelAbove: Any, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "aggressive")
        return BehaviorDecl(Aggressive(actorFilter(toward, "aggressive(toward)", ctx.pkg.path), intOrNull(unlessLevelAbove, "aggressive(unless_level_above)")))
    }

    @StarlarkMethod(
        name = "assist",
        doc = "Behavior: joins fights alongside allies, given as label patterns such as 'goblin_*'.",
        parameters = [Param(name = "allies", named = true)],
        useStarlarkThread = true,
    )
    fun assist(allies: Any, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "assist")
        return BehaviorDecl(Assist(actorFilter(allies, "assist(allies)", ctx.pkg.path)))
    }

    @StarlarkMethod(name = "flee", doc = "Behavior: flees at or below this percentage of max hp.", parameters = [Param(name = "at_hp_pct", named = true, defaultValue = "20")], useStarlarkThread = true)
    fun flee(atHpPct: Any, thread: StarlarkThread): Any {
        contentCtx(thread, "flee")
        val pct = int(atHpPct, "flee(at_hp_pct)").also { if (it !in 0..100) fail("flee(at_hp_pct): must be 0..100") }
        return BehaviorDecl(Flee(pct))
    }

    @StarlarkMethod(
        name = "guard",
        doc = "Behavior: denies an exit to everyone who isn't an ally (default: other mobs) while the guard is present.",
        parameters = [Param(name = "exit", named = true), Param(name = "allies", named = true, defaultValue = "'mobs'")],
        useStarlarkThread = true,
    )
    fun guard(exit: Any, allies: Any, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "guard")
        return BehaviorDecl(Guard(Direction.parse(str(exit, "guard(exit)")), actorFilter(allies, "guard(allies)", ctx.pkg.path)))
    }

    @StarlarkMethod(
        name = "follow",
        doc = "Behavior: follows the first matching actor it meets ('players', 'mobs' or label patterns).",
        parameters = [Param(name = "target", named = true)],
        useStarlarkThread = true,
    )
    fun follow(target: Any, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "follow")
        return BehaviorDecl(Follow(actorFilter(target, "follow(target)", ctx.pkg.path)))
    }

    @StarlarkMethod(
        name = "pursue",
        doc = "Behavior: heads for the nearest matching actor anywhere in the world.",
        parameters = [Param(name = "target", named = true)],
        useStarlarkThread = true,
    )
    fun pursue(target: Any, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "pursue")
        return BehaviorDecl(Pursue(actorFilter(target, "pursue(target)", ctx.pkg.path)))
    }

    @StarlarkMethod(name = "sentinel", doc = "Behavior: never leaves its room.", useStarlarkThread = true)
    fun sentinel(thread: StarlarkThread): Any {
        contentCtx(thread, "sentinel")
        return BehaviorDecl(Sentinel)
    }

    @StarlarkMethod(name = "scavenge", doc = "Behavior: picks up items lying within reach.", useStarlarkThread = true)
    fun scavenge(thread: StarlarkThread): Any {
        contentCtx(thread, "scavenge")
        return BehaviorDecl(Scavenge)
    }

    @StarlarkMethod(
        name = "global_command",
        doc = "Registers a command available everywhere. lib and rules packages only.",
        parameters = [
            Param(name = "name", named = true),
            Param(name = "fn", named = true),
            Param(name = "lane", named = true, defaultValue = "'fast'"),
        ],
        useStarlarkThread = true,
    )
    fun globalCommand(name: Any, fn: Any, lane: Any, thread: StarlarkThread): Any {
        val ctx = contentCtx(thread, "global_command")
        if (!ctx.pkg.kind.privileged) fail("global_command(): only lib and rules packages may register global commands")
        val verb = str(name, "global_command() name").lowercase()
        if (!Regex("[a-z][a-z0-9_]*").matches(verb)) fail("global_command(): '$verb' is not a valid verb")
        val l = if (choice(lane, "global_command(lane)", listOf("fast", "tick")) == "fast") Lane.FAST else Lane.TICK
        if (ctx.pkg.commandList.any { it.name == verb }) fail("global_command(): '$verb' is already registered")
        ctx.pkg.commandList += GlobalCommandDecl(verb, hookRef(fn, "global_command(fn)"), l, ctx.pkg.path)
        return Starlark.NONE
    }

    @StarlarkMethod(
        name = "struct",
        doc = "An immutable record of named fields, for example a library's exported functions: struct(start = _start).",
        extraKeywords = Param(name = "kwargs"),
    )
    fun struct(kwargs: Dict<String, Any>): Any = StructValue(LinkedHashMap(kwargs))

    // ---- Effect primitives (SPEC §4.3) -----------------------------------------------------------

    @StarlarkMethod(name = "damage", doc = "Deal damage.", parameters = [Param(name = "dice", named = true), Param(name = "type", defaultValue = "'bludgeon'", named = true)])
    fun damage(dice: Any, type: Any): Any = EffectDecl(Effect.Damage(dice(dice, "damage(dice)"), DamageType(str(type, "damage(type)").lowercase())))

    @StarlarkMethod(name = "heal", doc = "Restore hit points.", parameters = [Param(name = "dice", named = true)])
    fun heal(dice: Any): Any = EffectDecl(Effect.Heal(dice(dice, "heal(dice)")))

    @StarlarkMethod(
        name = "apply_affect",
        doc = "Attach a timed buff or debuff.",
        parameters = [
            Param(name = "name", named = true),
            Param(name = "duration", named = true),
            Param(name = "stat_deltas", named = true, defaultValue = "{}"),
            Param(name = "flags", named = true, defaultValue = "[]"),
            Param(name = "tick_every", named = true, defaultValue = "None"),
            Param(name = "on_tick", named = true, defaultValue = "None"),
            Param(name = "on_expire", named = true, defaultValue = "None"),
            Param(name = "expire_msg", named = true, defaultValue = "''"),
            Param(name = "modifiers", named = true, defaultValue = "{}"),
        ],
    )
    fun applyAffect(name: Any, duration: Any, statDeltas: Any, flags: Any, tickEvery: Any, onTick: Any, onExpire: Any, expireMsg: Any, modifiers: Any): Any =
        EffectDecl(affectEffect(name, duration, statDeltas, flags, tickEvery, onTick, onExpire, expireMsg, modifiers, "apply_affect"))

    @StarlarkMethod(name = "dispel", doc = "Remove matching affects; a trailing * matches a prefix.", parameters = [Param(name = "filter", named = true)])
    fun dispel(filter: Any): Any = EffectDecl(Effect.Dispel(str(filter, "dispel(filter)")))

    @StarlarkMethod(name = "knockback", doc = "Push the target along the track.", parameters = [Param(name = "steps", named = true)])
    fun knockback(steps: Any): Any = EffectDecl(Effect.Knockback(positive(steps, "knockback(steps)")))

    @StarlarkMethod(name = "pull", doc = "Draw the target along the track.", parameters = [Param(name = "steps", named = true)])
    fun pull(steps: Any): Any = EffectDecl(Effect.Pull(positive(steps, "pull(steps)")))

    @StarlarkMethod(name = "stun", doc = "Stun the target.", parameters = [Param(name = "pulses", named = true)])
    fun stun(pulses: Any): Any = EffectDecl(Effect.Stun(positive(pulses, "stun(pulses)")))

    @StarlarkMethod(
        name = "summon",
        doc = "Spawn mobs beside the caster.",
        parameters = [Param(name = "label", named = true), Param(name = "count", named = true, defaultValue = "1"), Param(name = "duration", named = true, defaultValue = "None")],
        useStarlarkThread = true,
    )
    fun summon(label: Any, count: Any, duration: Any, thread: StarlarkThread): Any {
        val pkg = thread.getThreadLocal(LoadCtx::class.java)?.pkg?.path ?: callerFile(thread)?.pkg
        return EffectDecl(
            Effect.Summon(
                label(str(label, "summon(label)"), pkg, "summon(label)"),
                positive(count, "summon(count)"),
                intOrNull(duration, "summon(duration)")?.also { if (it < 1) fail("summon(duration): must be at least 1") },
            ),
        )
    }

    @StarlarkMethod(name = "sequence", doc = "Combine several effects.", extraPositionals = Param(name = "effects"))
    fun sequence(effects: Tuple): Any = EffectDecl(Effect.Sequence(effects.mapIndexed { i, e -> effectValue(e, "sequence() argument ${i + 1}") }))

    private fun positive(v: Any, what: String): Int = int(v, what).also { if (it < 1) fail("$what: must be at least 1") }

    companion object {
        private val ROOM_FLAGS = listOf("always_active", "dead_end_ok")

        /** Constants available to every package file. */
        val CONSTANTS: Map<String, Any> = mapOf(
            "CRAMPED" to "cramped",
            "NORMAL" to "normal",
            "LARGE" to "large",
            "ALL" to StarlarkList.immutableCopyOf(CHANNELS),
        )
    }
}

/** Builds an `apply_affect` effect; shared by the primitive and the `ctx.apply_affect` verb. */
internal fun affectEffect(
    name: Any,
    duration: Any,
    statDeltas: Any,
    flags: Any,
    tickEvery: Any,
    onTick: Any,
    onExpire: Any,
    expireMsg: Any,
    mods: Any,
    fn: String,
): Effect.ApplyAffect {
    val d = int(duration, "$fn(duration)")
    if (d < 1) fail("$fn(duration): must be at least 1")
    val every = intOrNull(tickEvery, "$fn(tick_every)")
    if (every != null && every < 1) fail("$fn(tick_every): must be at least 1")
    return Effect.ApplyAffect(
        name = str(name, "$fn(name)"),
        duration = d,
        statDeltas = intDict(statDeltas, "$fn(stat_deltas)").onEach { (k, _) ->
            if (k !in STAT_NAMES) fail("$fn(stat_deltas): unknown stat '$k'${SpellChecker.didYouMean(k, STAT_NAMES)}")
        },
        flags = strList(flags, "$fn(flags)").toSet(),
        tickEvery = every,
        onTick = hookRefOrNull(onTick, "$fn(on_tick)"),
        onExpire = hookRefOrNull(onExpire, "$fn(on_expire)"),
        expireMessage = str(expireMsg, "$fn(expire_msg)"),
        modifiers = modifiers(mods, "$fn(modifiers)"),
    )
}

/** `package(api_version=...)` names a version this engine doesn't support (SPEC §1.3). */
internal class ApiVersionMismatch(val declared: Int) : RuntimeException(
    "package declares api_version $declared, but this engine supports only api_version ${EngineInfo.API_VERSION} " +
        "(${if (declared > EngineInfo.API_VERSION) "the package is newer than the engine" else "the package is ${EngineInfo.API_VERSION - declared} version(s) behind"}). " +
        "See CHANGELOG.md#api_version-${maxOf(declared, EngineInfo.API_VERSION)} and MIGRATING.md.",
)
