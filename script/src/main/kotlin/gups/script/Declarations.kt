package gups.script

import com.google.common.collect.ImmutableCollection
import com.google.common.collect.ImmutableList
import gups.core.EngineInfo
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
enum class OwnerKind(val word: String) {
  ROOM("room"),
  MOB("mob"),
  ITEM("item"),
}

/** The hook catalog: attachment rules, signatures, ctx fields and docs (SPEC §4.4, M7.1). */
internal object HookCatalog {
  private val R = OwnerKind.ROOM
  private val M = OwnerKind.MOB
  private val I = OwnerKind.ITEM

  val specs: List<HookSpec> =
    listOf(
      HookSpec(
        hook = HookName.ON_LOAD,
        signature = "fn(ctx)",
        attachTo = listOf(R, M, I),
        ctxFields = listOf("self", "room", "pulse", "rng"),
        doc =
          "Fires when a room is created on world load or reload, or when a mob or item is loaded.",
      ),
      HookSpec(
        hook = HookName.ON_SPAWN,
        signature = "fn(ctx)",
        attachTo = listOf(R, M, I),
        ctxFields = listOf("self", "item", "room", "pulse", "rng"),
        doc = "Fires when a mob or item is freshly spawned, or on a room when a spawn rule fires.",
      ),
      HookSpec(
        hook = HookName.ON_RESET,
        signature = "fn(ctx)",
        attachTo = listOf(R),
        ctxFields = listOf("self", "room", "pulse", "rng"),
        doc = "Fires on a room when its zone resets.",
      ),
      HookSpec(
        hook = HookName.ON_WAKE,
        signature = "fn(ctx, elapsed_pulses)",
        attachTo = listOf(R, M, I),
        ctxFields = listOf("self", "room", "elapsed_pulses", "pulse", "rng"),
        doc = "Fires when an entity wakes from dormancy as the reality bubble reaches its room.",
      ),
      HookSpec(
        hook = HookName.BEFORE_ENTER,
        signature = "fn(ctx)",
        attachTo = listOf(R, M),
        ctxFields = listOf("self", "actor", "direction", "from_room", "room", "pulse", "rng"),
        doc = "Fires before an actor enters the room; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.ON_ENTER,
        signature = "fn(ctx)",
        attachTo = listOf(R, M),
        ctxFields = listOf("self", "actor", "direction", "from_room", "room", "pulse", "rng"),
        doc = "Fires after an actor enters the room.",
      ),
      HookSpec(
        hook = HookName.BEFORE_EXIT,
        signature = "fn(ctx)",
        attachTo = listOf(R, M),
        ctxFields = listOf("self", "actor", "direction", "to_room", "room", "pulse", "rng"),
        doc = "Fires before an actor leaves the room through an exit; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.ON_EXIT,
        signature = "fn(ctx)",
        attachTo = listOf(R, M),
        ctxFields = listOf("self", "actor", "direction", "to_room", "room", "pulse", "rng"),
        doc = "Fires after an actor leaves the room through an exit.",
      ),
      HookSpec(
        hook = HookName.ON_SAY,
        signature = "fn(ctx)",
        attachTo = listOf(M, R),
        ctxFields = listOf("self", "actor", "text", "room", "pulse", "rng"),
        doc = "Fires when an actor says something aloud in the room.",
      ),
      HookSpec(
        hook = HookName.ON_TELL,
        signature = "fn(ctx)",
        attachTo = listOf(M),
        ctxFields = listOf("self", "actor", "text", "room", "pulse", "rng"),
        doc = "Fires on a mob when an actor sends it a tell.",
      ),
      HookSpec(
        hook = HookName.BEFORE_GET,
        signature = "fn(ctx)",
        attachTo = listOf(I, M),
        ctxFields = listOf("self", "actor", "item", "room", "pulse", "rng"),
        doc = "Fires before an actor picks up an item; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.ON_GET,
        signature = "fn(ctx)",
        attachTo = listOf(I, M),
        ctxFields = listOf("self", "actor", "item", "room", "pulse", "rng"),
        doc = "Fires after an actor picks up an item.",
      ),
      HookSpec(
        hook = HookName.BEFORE_DROP,
        signature = "fn(ctx)",
        attachTo = listOf(I, M),
        ctxFields = listOf("self", "actor", "item", "room", "pulse", "rng"),
        doc = "Fires before an actor drops an item; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.ON_DROP,
        signature = "fn(ctx)",
        attachTo = listOf(I, M),
        ctxFields = listOf("self", "actor", "item", "room", "pulse", "rng"),
        doc = "Fires after an actor drops an item.",
      ),
      HookSpec(
        hook = HookName.BEFORE_USE,
        signature = "fn(ctx)",
        attachTo = listOf(I),
        ctxFields = listOf("self", "actor", "item", "target", "room", "pulse", "rng"),
        doc = "Fires before an actor uses an item; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.ON_USE,
        signature = "fn(ctx)",
        attachTo = listOf(I),
        ctxFields = listOf("self", "actor", "item", "target", "room", "pulse", "rng"),
        doc = "Fires when an actor uses an item.",
      ),
      HookSpec(
        hook = HookName.ON_GIVE,
        signature = "fn(ctx)",
        attachTo = listOf(M, I),
        ctxFields = listOf("self", "actor", "item", "target", "room", "pulse", "rng"),
        doc = "Fires when an actor gives an item to another actor.",
      ),
      HookSpec(
        hook = HookName.BEFORE_ATTACK,
        signature = "fn(ctx)",
        attachTo = listOf(M, I),
        ctxFields = listOf("self", "actor", "target", "weapon", "room", "pulse", "rng"),
        doc = "Fires before an attack swing; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.BEFORE_CAST,
        signature = "fn(ctx)",
        attachTo = listOf(M, I, R),
        ctxFields = listOf("self", "actor", "skill", "target", "room", "pulse", "rng"),
        doc = "Fires before casting begins; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.ON_CAST_START,
        signature = "fn(ctx)",
        attachTo = listOf(M, I, R),
        ctxFields = listOf("self", "actor", "skill", "target", "room", "pulse", "rng"),
        doc = "Fires when a skill enters casting warmup.",
      ),
      HookSpec(
        hook = HookName.ON_CAST,
        signature = "fn(ctx)",
        attachTo = listOf(M, I, R),
        ctxFields = listOf("self", "actor", "skill", "target", "room", "pulse", "rng"),
        doc = "Fires after a skill resolves.",
      ),
      HookSpec(
        hook = HookName.ON_DAMAGE,
        signature = "fn(ctx)",
        attachTo = listOf(M, I),
        ctxFields = listOf("self", "actor", "target", "amount", "type", "room", "pulse", "rng"),
        doc = "Fires when an actor takes damage.",
      ),
      HookSpec(
        hook = HookName.ON_DEATH,
        signature = "fn(ctx)",
        attachTo = listOf(M, R),
        ctxFields = listOf("self", "actor", "target", "room", "pulse", "rng"),
        doc = "Fires when an actor dies.",
      ),
      HookSpec(
        hook = HookName.ON_COMBAT_TICK,
        signature = "fn(ctx)",
        attachTo = listOf(M, I),
        ctxFields = listOf("self", "actor", "targets", "room", "pulse", "rng"),
        doc = "Fires once per combat round on each fighting mob and its equipped items.",
      ),
      HookSpec(
        hook = HookName.ON_TICK,
        signature = "fn(ctx)",
        attachTo = listOf(R, M, I),
        ctxFields = listOf("self", "room", "pulse", "rng"),
        doc = "Fires on each world tick while simulated.",
      ),
      HookSpec(
        hook = HookName.ON_NEARBY,
        signature = "fn(ctx)",
        attachTo = listOf(M),
        ctxFields =
          listOf(
            "self",
            "event",
            "actor",
            "source_room",
            "via_exit",
            "text",
            "room",
            "pulse",
            "rng",
          ),
        doc = "Fires on a mob with senses when a perceptible event happens within its sense radius.",
      ),
    )

  val attach: Map<HookName, Set<OwnerKind>> = specs.associate { it.hook to it.attachTo.toSet() }

  val names: List<String> = HookName.entries.map { it.scriptName }

  fun byName(name: String): HookName? = HookName.entries.firstOrNull { it.scriptName == name }

  fun looksLikeHook(name: String): Boolean = name.startsWith("on_") || name.startsWith("before_")
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

/**
 * An effect value for a plugin effect primitive, usable wherever an engine effect is (SPEC §4.15).
 */
fun pluginEffect(pluginId: String, name: String, args: Map<String, Any?>): StarlarkValue =
  EffectDecl(Effect.Plugin(pluginId, name, args))

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

// ---- Parsing helpers
// -----------------------------------------------------------------------------

private fun loadCtx(thread: StarlarkThread, fn: String): LoadCtx {
  val ctx =
    thread.getThreadLocal(LoadCtx::class.java)
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

/** Validates the declaration's single positional name within the package. */
private fun declName(fn: String, d: ParsedDecl, ctx: LoadCtx): String {
  val name = d.reqStr("id", "$fn() name")
  label(name.let { ":$it" }, ctx.pkg.path, "$fn() name")
  if (!ctx.pkg.names.add(name)) fail("$fn(): '$name' is already declared in //${ctx.pkg.path}")
  return name
}

private fun channels(v: Any?, what: String): Set<Channel> =
  strList(v, what).mapTo(LinkedHashSet()) {
    Channel.valueOf(choice(it, what, CHANNELS).uppercase())
  }

private val CHANNELS = Channel.entries.map { it.name.lowercase() }

private fun dice(v: Any?, what: String): Dice =
  try {
    Dice.parse(str(v, what))
  } catch (e: IllegalArgumentException) {
    fail("$what: ${e.message}")
  }

private fun band(v: Any?, what: String): Band =
  Band.parse(choice(v, what, Band.entries.map { it.word }))

private fun doorState(v: Any?, what: String): DoorState =
  DoorState.valueOf(choice(v, what, DoorState.entries.map { it.name.lowercase() }).uppercase())

private fun stats(v: Any?, what: String): Map<String, Int> {
  val given = intDict(v, what)
  for (k in given.keys) if (k !in STAT_NAMES)
    fail("$what: unknown stat '$k'${SpellChecker.didYouMean(k, STAT_NAMES)}")
  return DEFAULT_STATS + given
}

private fun statDeltas(v: Any?, what: String): Map<String, Int> {
  val given = intDict(v, what)
  for (k in given.keys) if (k !in STAT_NAMES)
    fail("$what: unknown stat '$k'${SpellChecker.didYouMean(k, STAT_NAMES)}")
  return given
}

private fun displayName(id: String): String = id.replace('_', ' ')

private val ARTICLES = setOf("a", "an", "the", "some")

/**
 * Keywords default to the words of the display name minus articles, or else the declaration name
 * (D-070).
 */
private fun defaultKeywords(name: String, id: String): List<String> =
  name
    .lowercase()
    .split(Regex("[^a-z0-9]+"))
    .filter { it.isNotEmpty() && it !in ARTICLES }
    .ifEmpty { listOf(id.lowercase()) }

private fun commands(v: Any?, what: String, pkg: String): Map<String, ZoneCommand> {
  if (isNone(v)) return emptyMap()
  return dict(v, what).entries.associate { (verb, fn) ->
    val word = verb.lowercase()
    if (!Regex("[a-z][a-z0-9_]*").matches(word)) fail("$what: '$verb' is not a valid verb")
    word to ScriptCommand(word, hookRef(fn, "$what[\"$verb\"]"), pkg)
  }
}

private fun version(d: ParsedDecl): Int {
  val v = d.int("version") ?: 1
  if (v < 1) fail("${d.fn}(version): must be at least 1")
  return v
}

private val POINTS = ModifierPoint.entries.map { it.scriptName }

/** A `modifiers` dict: modifier point name to a top-level function `fn(ctx, base)` (SPEC §4.6). */
internal fun modifiers(v: Any?, what: String): Modifiers {
  if (isNone(v)) return emptyMap()
  return dict(v, what).entries.associate { (k, fn) ->
    val point =
      ModifierPoint.byName(k)
        ?: fail(
          "$what: '$k' is not a modifier point${SpellChecker.didYouMean(k, POINTS)} (one of ${POINTS.joinToString(", ")})"
        )
    point to hookRef(fn, "$what[\"$k\"]")
  }
}

/** Affect flags such as `no_regen`: lowercase words. */
private fun flags(v: Any?, what: String): Set<String> =
  strList(v, what)
    .onEach { if (!Regex("[a-z][a-z0-9_]*").matches(it)) fail("$what: '$it' is not a valid flag") }
    .toSet()

/**
 * Whom a behavior means: `players`, `mobs`, `all`, or label patterns with `*` such as `goblin_*`
 * (any package) or `:goblin_*` (this package). A string or a list of them.
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
        if (
          q !in setOf("players", "mobs", "all", "anyone") &&
            !Regex("(//[a-z0-9_/]+:)?[A-Za-z0-9_.*\\-]+").matches(q)
        ) {
          fail("$what: '$p' is not players, mobs, all, or a label pattern such as goblin_*")
        }
      }
    }
  )
}

private fun effectValue(v: Any?, what: String): Effect =
  (v as? EffectDecl)?.effect
    ?: fail("$what: expected an effect such as damage(...), got ${typeName(v)}")

/**
 * The declaration builtins, plus the effect primitives (SPEC §4.3) and constants. Effect primitives
 * work anywhere; everything else only while a package loads.
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
    val p = ctx.pkg
    return PACKAGE_SPEC.bind(
      kwargs = kwargs,
      beforeUnknownCheck = { parsed ->
        p.apiVersion = parsed.int("api_version") ?: fail("package(): api_version is required")
        // Checked first: a package for another version may use arguments this engine doesn't know.
        if (p.apiVersion != EngineInfo.API_VERSION)
          ApiVersionMismatch(p.apiVersion).let { throw EvalException(it.message, it) }
      },
    ) { d ->
      p.kind =
        d.choice("kind")?.let(PackageKind::parse)
          ?: fail("package(): kind is required (zone, lib, rules or skills)")
      p.title = d.str("title") ?: p.path
      p.resetPulses =
        d.int("reset_pulses")?.also {
          if (it < 1) fail("package(reset_pulses): must be at least 1")
        }
      p.sharedNamespaces = d.strList("shared_namespaces")
      if (p.sharedNamespaces.isNotEmpty() && !p.kind.privileged) {
        fail(
          "package(shared_namespaces): only lib and rules packages may declare shared namespaces"
        )
      }
      for (ns in p.sharedNamespaces) if (!Regex("[a-z0-9_]+").matches(ns))
        fail("package(shared_namespaces): '$ns' is not a valid namespace")
      p.modifiers = modifiers(d["modifiers"], d.what("modifiers"))
      p.expectedTombstones =
        d.strList("expected_tombstones").map { label(it, p.path, d.what("expected_tombstones")) }
      p.migrateNoop = d.strList("migrate_noop").map { label(it, p.path, d.what("migrate_noop")) }
      Starlark.NONE
    }
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
    return ROOM_SPEC.bind(args, kwargs) { d ->
      val id = declName("room", d, ctx)
      val pkg = ctx.pkg.path
      val label = Label("//$pkg:$id")
      val size = d.choice("size")?.let { TrackSize.valueOf(it.uppercase()) } ?: TrackSize.NORMAL
      val flags = d.choiceList("flags").toSet()
      val exits =
        d.list("exits").map {
          (it as? ExitDecl)?.proto ?: fail("room(exits): expected exit(...), got ${typeName(it)}")
        }
      exits
        .groupBy { it.direction }
        .forEach { (dir, same) -> if (same.size > 1) fail("room(exits): two exits lead $dir") }
      for (e in exits) {
        ctx.ref(e.to, RefKind.ROOM, thread)
        e.door?.key?.let { ctx.ref(it, RefKind.ITEM, thread) }
      }
      val spawns =
        d.list("spawns").map {
          (it as? SpawnDecl)?.rule ?: fail("room(spawns): expected spawn(...), got ${typeName(it)}")
        }
      for (s in spawns) {
        ctx.ref(s.label, RefKind.MOB, thread)
        if (s.atIndex !in 0 until size.slots)
          fail("room(spawns): at_index ${s.atIndex} is outside this ${size.name.lowercase()} room")
      }
      val proto =
        RoomProto(
          label = label,
          name = d.str("name") ?: displayName(id),
          desc = d.str("desc") ?: "",
          size = size,
          regions = d.strList("regions").toSet(),
          flags = flags,
          exits = exits,
          spawns = spawns,
          commands = commands(d["commands"], d.what("commands"), pkg),
          modifiers = modifiers(d["modifiers"], d.what("modifiers")),
          version = version(d),
        )
      for (r in proto.regions) if (!Regex("[a-z0-9_]+").matches(r))
        fail("room(regions): '$r' is not a valid region name")
      ctx.pkg.roomList += proto
      if (d.hooks.isNotEmpty()) ctx.pkg.hookMap[label] = d.hooks
      Starlark.NONE
    }
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
    return EXIT_SPEC.bind(args, kwargs) { d ->
      val dir = Direction.parse(d.reqStr("direction", "exit() direction"))
      if (!Regex("[a-z][a-z0-9_]*").matches(dir.name))
        fail("exit(): '${dir.name}' is not a valid exit name")
      val to = label(d.reqStr("to", "exit() destination"), ctx.pkg.path, d.what("to"))
      val proto =
        ExitProto(
          direction = dir,
          to = to,
          entryIndex = d.int("entry_index") ?: 0,
          blocks = channels(d["blocks"], d.what("blocks")),
          door =
            d["door"]?.let {
              (it as? DoorDecl)?.proto
                ?: fail("exit(door): expected door(...), got ${typeName(it)}")
            },
          oneWay = d.bool("one_way") ?: false,
        )
      if (proto.entryIndex < -1) fail("exit(entry_index): must be -1 (the back) or a track index")
      ExitDecl(proto)
    }
  }

  @StarlarkMethod(
    name = "door",
    doc = "A door, for exit(door=...).",
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun door(kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "door")
    return DOOR_SPEC.bind(kwargs = kwargs) { d ->
      val def = DoorProto()
      val proto =
        DoorProto(
          state = d.choice("state")?.let { DoorState.valueOf(it.uppercase()) } ?: def.state,
          key = d.str("key")?.let { label(it, ctx.pkg.path, d.what("key")) },
          openBlocks =
            if (d["open_blocks"] != null) channels(d["open_blocks"], d.what("open_blocks"))
            else def.openBlocks,
          closedBlocks =
            if (d["closed_blocks"] != null) channels(d["closed_blocks"], d.what("closed_blocks"))
            else def.closedBlocks,
        )
      DoorDecl(proto)
    }
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
    return SPAWN_SPEC.bind(args, kwargs) { d ->
      val def = SpawnRule(Label("//engine:none"))
      val rule =
        SpawnRule(
          label = label(d.reqStr("label", "spawn() label"), ctx.pkg.path, "spawn() label"),
          count = d.int("count") ?: def.count,
          respawnPulses = d.int("respawn_pulses") ?: def.respawnPulses,
          atIndex = d.int("at_index") ?: def.atIndex,
        )
      if (rule.count < 1) fail("spawn(count): must be at least 1")
      if (rule.respawnPulses < 1) fail("spawn(respawn_pulses): must be at least 1")
      SpawnDecl(rule)
    }
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
    return LOOT_SPEC.bind(args, kwargs) { d ->
      val rule =
        LootRule(
          label = label(d.reqStr("label", "loot() label"), ctx.pkg.path, "loot() label"),
          chance = d.number("chance") ?: 1.0,
          count = d.int("count") ?: 1,
        )
      if (rule.chance !in 0.0..1.0) fail("loot(chance): must be between 0 and 1")
      if (rule.count < 1) fail("loot(count): must be at least 1")
      LootDecl(rule)
    }
  }

  @StarlarkMethod(
    name = "container",
    doc = "Container settings, for item(container=...).",
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun container(kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "container")
    return CONTAINER_SPEC.bind(kwargs = kwargs) { d ->
      val def = ContainerProto()
      val proto =
        ContainerProto(
          capacity = d.int("capacity") ?: def.capacity,
          maxWeight = d.int("max_weight") ?: def.maxWeight,
          state = d["state"]?.let { doorState(it, d.what("state")) } ?: def.state,
          key = d.str("key")?.let { label(it, ctx.pkg.path, d.what("key")) },
        )
      ContainerDecl(proto)
    }
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
    return MOB_SPEC.bind(args, kwargs) { d ->
      val id = declName("mob", d, ctx)
      val pkg = ctx.pkg.path
      val label = Label("//$pkg:$id")
      val name = d.str("name") ?: displayName(id)
      val skills =
        d.dict("skills").entries.associate { (k, v) ->
          val skill = label(k, pkg, d.what("skills"))
          ctx.ref(skill, RefKind.SKILL, thread)
          val prof = int(v, "mob(skills[\"$k\"])")
          if (prof !in 0..100) fail("mob(skills[\"$k\"]): proficiency must be 0..100")
          skill to prof
        }
      val senses =
        d.intDict("senses").entries.associate { (k, v) ->
          val sense =
            Sense.valueOf(
              choice(k, d.what("senses"), Sense.entries.map { it.name.lowercase() }).uppercase()
            )
          if (v !in 0..PERCEPTION_MAX_RADIUS)
            fail("mob(senses[\"$k\"]): radius must be 0..$PERCEPTION_MAX_RADIUS")
          sense to v
        }
      val loot =
        d.list("loot").map {
          (it as? LootDecl)?.rule ?: fail("mob(loot): expected loot(...), got ${typeName(it)}")
        }
      for (l in loot) ctx.ref(l.label, RefKind.ITEM, thread)
      val def = MobProto(label, name, emptyList())
      val proto =
        MobProto(
          label = label,
          name = name,
          keywords = d["keywords"]?.let { d.strList("keywords") } ?: defaultKeywords(name, id),
          desc = d.str("desc") ?: "",
          level = d.int("level") ?: def.level,
          stats = stats(d["stats"], d.what("stats")),
          hp = d.int("hp") ?: def.hp,
          mana = d.int("mana") ?: def.mana,
          move = d.int("move") ?: def.move,
          armor = d.int("armor") ?: def.armor,
          resists = d.intDict("resists").mapKeys { DamageType(it.key.lowercase()) },
          skills = skills,
          senses = senses,
          think = d["think"]?.let { ScriptThink(hookRef(it, d.what("think")), pkg) },
          thinkEvery = d.int("think_every") ?: def.thinkEvery,
          behaviors =
            d.list("behaviors").map {
              (it as? BehaviorDecl)?.behavior
                ?: fail(
                  "mob(behaviors): expected a behavior such as wander(...), got ${typeName(it)}"
                )
            },
          dormantRecovery =
            d.choice("dormant_recovery")?.let { DormantRecovery.valueOf(it.uppercase()) }
              ?: def.dormantRecovery,
          loot = loot,
          version = version(d),
        )
      if (proto.hp < 1) fail("mob(hp): must be at least 1")
      if (proto.thinkEvery < 1) fail("mob(think_every): must be at least 1")
      if (proto.keywords.isEmpty()) fail("mob(keywords): a mob needs at least one keyword")
      ctx.pkg.mobList += proto
      if (d.hooks.isNotEmpty()) ctx.pkg.hookMap[label] = d.hooks
      Starlark.NONE
    }
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
    return ITEM_SPEC.bind(args, kwargs) { d ->
      val id = declName("item", d, ctx)
      val pkg = ctx.pkg.path
      val label = Label("//$pkg:$id")
      val name = d.str("name") ?: displayName(id)
      val wear = d.str("wear")
      val wield = d.str("wield")
      if (wear != null && wield != null) fail("item(): give either wear or wield, not both")
      val container =
        d["container"]?.let {
          (it as? ContainerDecl)?.proto
            ?: fail("item(container): expected container(...), got ${typeName(it)}")
        }
      container?.key?.let { ctx.ref(it, RefKind.ITEM, thread) }
      val def = ItemProto(label, name, emptyList())
      val proto =
        ItemProto(
          label = label,
          name = name,
          keywords = d["keywords"]?.let { d.strList("keywords") } ?: defaultKeywords(name, id),
          desc = d.str("desc") ?: "",
          kind = d.str("kind")?.lowercase() ?: def.kind,
          range = d["range"]?.let { band(it, d.what("range")) } ?: def.range,
          weight = d.int("weight") ?: def.weight,
          wear = wear ?: wield,
          dice = d["dice"]?.let { dice(it, d.what("dice")) },
          damageType = d.str("damage_type")?.let { DamageType(it.lowercase()) } ?: def.damageType,
          statDeltas = statDeltas(d["stat_deltas"], d.what("stat_deltas")),
          stackable = d.bool("stackable") ?: false,
          decayPulses = d.int("decay_pulses"),
          container = container,
          commands = commands(d["commands"], d.what("commands"), pkg),
          equipAffects = flags(d["equip_affects"], d.what("equip_affects")),
          modifiers = modifiers(d["modifiers"], d.what("modifiers")),
          version = version(d),
        )
      if (proto.weight < 0) fail("item(weight): can't be negative")
      if (proto.keywords.isEmpty()) fail("item(keywords): an item needs at least one keyword")
      if (proto.wear == null && (proto.equipAffects.isNotEmpty() || proto.modifiers.isNotEmpty())) {
        fail(
          "item(): equip_affects and modifiers apply while the item is worn, so it needs a wear slot"
        )
      }
      ctx.pkg.itemList += proto
      if (d.hooks.isNotEmpty()) ctx.pkg.hookMap[label] = d.hooks
      Starlark.NONE
    }
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
    return SKILL_SPEC.bind(args, kwargs) { d ->
      val id = declName("skill", d, ctx)
      val pkg = ctx.pkg.path
      val label = Label("//$pkg:$id")
      val cost = d.intDict("cost")
      for (k in cost.keys) choice(k, d.what("cost"), listOf("hp", "mana", "move"))
      val interrupt =
        d["interrupt"]?.let { v ->
          val m = dict(v, d.what("interrupt"))
          for (k in m.keys) choice(k, d.what("interrupt"), listOf("damage_pct", "stun"))
          Interrupt(
            damagePct = m["damage_pct"]?.let { intOrNull(it, "skill(interrupt[\"damage_pct\"])") },
            stun = m["stun"]?.let { bool(it, "skill(interrupt[\"stun\"])") } ?: true,
          )
        } ?: Interrupt()
      val def = SkillProto(label)
      val proto =
        SkillProto(
          label = label,
          kind = d.str("kind") ?: def.kind,
          cost = cost,
          warmup = d.int("warmup") ?: 0,
          lag = d.int("lag") ?: 0,
          range = d.choice("range")?.let { SkillRange.valueOf(it.uppercase()) } ?: def.range,
          target = d.choice("target")?.let { TargetKind.valueOf(it.uppercase()) } ?: def.target,
          channels = channels(d["channels"], d.what("channels")),
          interrupt = interrupt,
          attackWhileCasting = d.bool("attack_while_casting") ?: false,
          effect = d["effect"]?.let { effectValue(it, d.what("effect")) },
          resolve = d["resolve"]?.let { ScriptResolve(hookRef(it, d.what("resolve")), pkg) },
        )
      if (proto.warmup < 0 || proto.lag < 0) fail("skill(): warmup and lag can't be negative")
      proto.effect?.let { effectRefs(it, ctx, thread) }
      ctx.pkg.skillList += proto
      Starlark.NONE
    }
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
    doc =
      "Declares the modifiers of a region, a tag rooms carry with room(regions=[...]). Region names are world-wide.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun region(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "region")
    return REGION_SPEC.bind(args, kwargs) { d ->
      val id = declName("region", d, ctx)
      if (!Regex("[a-z0-9_]+").matches(id)) fail("region(): '$id' is not a valid region name")
      val proto = RegionProto(id, ctx.pkg.path, modifiers(d["modifiers"], d.what("modifiers")))
      ctx.pkg.regionList += proto
      Starlark.NONE
    }
  }

  // ---- Behaviors (SPEC §4.7) --------------------------------------------------------------------

  @StarlarkMethod(
    name = "wander",
    doc =
      "Behavior: with probability chance per think, steps through a random open exit within radius exits of home.",
    parameters =
      [
        Param(
          name = "radius",
          named = true,
          defaultValue = "3",
          doc = "Maximum distance from home, in exits.",
        ),
        Param(
          name = "chance",
          named = true,
          defaultValue = "0.1",
          doc = "Probability of taking a step on each think, between 0 and 1.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun wander(radius: Any, chance: Any, thread: StarlarkThread): Any {
    contentCtx(thread, "wander")
    val r =
      int(radius, "wander(radius)").also { if (it < 0) fail("wander(radius): can't be negative") }
    val c =
      number(chance, "wander(chance)").also {
        if (it !in 0.0..1.0) fail("wander(chance): must be between 0 and 1")
      }
    return BehaviorDecl(Wander(r, c))
  }

  @StarlarkMethod(
    name = "aggressive",
    doc =
      "Behavior: attacks matching actors on sight. toward is 'players', 'mobs', 'all' or label patterns.",
    parameters =
      [
        Param(
          name = "toward",
          named = true,
          defaultValue = "'players'",
          doc = "Who to attack: 'players', 'mobs', 'all', or label patterns such as 'goblin_*'.",
        ),
        Param(
          name = "unless_level_above",
          named = true,
          defaultValue = "None",
          doc = "Spare actors above this level, or None to attack any level.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun aggressive(toward: Any, unlessLevelAbove: Any, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "aggressive")
    return BehaviorDecl(
      Aggressive(
        actorFilter(toward, "aggressive(toward)", ctx.pkg.path),
        intOrNull(unlessLevelAbove, "aggressive(unless_level_above)"),
      )
    )
  }

  @StarlarkMethod(
    name = "assist",
    doc = "Behavior: joins fights alongside allies, given as label patterns such as 'goblin_*'.",
    parameters =
      [
        Param(
          name = "allies",
          named = true,
          doc = "Allies to help, as label patterns such as 'goblin_*'.",
        )
      ],
    useStarlarkThread = true,
  )
  fun assist(allies: Any, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "assist")
    return BehaviorDecl(Assist(actorFilter(allies, "assist(allies)", ctx.pkg.path)))
  }

  @StarlarkMethod(
    name = "flee",
    doc = "Behavior: flees at or below this percentage of max hp.",
    parameters =
      [
        Param(
          name = "at_hp_pct",
          named = true,
          defaultValue = "20",
          doc = "Percentage of max hp (0..100) at or below which the mob flees.",
        )
      ],
    useStarlarkThread = true,
  )
  fun flee(atHpPct: Any, thread: StarlarkThread): Any {
    contentCtx(thread, "flee")
    val pct =
      int(atHpPct, "flee(at_hp_pct)").also {
        if (it !in 0..100) fail("flee(at_hp_pct): must be 0..100")
      }
    return BehaviorDecl(Flee(pct))
  }

  @StarlarkMethod(
    name = "guard",
    doc =
      "Behavior: denies an exit to everyone who isn't an ally (default: other mobs) while the guard is present.",
    parameters =
      [
        Param(name = "exit", named = true, doc = "Direction of the exit to guard."),
        Param(
          name = "allies",
          named = true,
          defaultValue = "'mobs'",
          doc = "Who may pass: 'players', 'mobs', 'all', or label patterns.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun guard(exit: Any, allies: Any, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "guard")
    return BehaviorDecl(
      Guard(
        Direction.parse(str(exit, "guard(exit)")),
        actorFilter(allies, "guard(allies)", ctx.pkg.path),
      )
    )
  }

  @StarlarkMethod(
    name = "follow",
    doc =
      "Behavior: follows the first matching actor it meets ('players', 'mobs' or label patterns).",
    parameters =
      [
        Param(
          name = "target",
          named = true,
          doc = "Who to follow: 'players', 'mobs', or label patterns.",
        )
      ],
    useStarlarkThread = true,
  )
  fun follow(target: Any, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "follow")
    return BehaviorDecl(Follow(actorFilter(target, "follow(target)", ctx.pkg.path)))
  }

  @StarlarkMethod(
    name = "pursue",
    doc = "Behavior: heads for the nearest matching actor anywhere in the world.",
    parameters =
      [
        Param(
          name = "target",
          named = true,
          doc = "Who to pursue: 'players', 'mobs', or label patterns.",
        )
      ],
    useStarlarkThread = true,
  )
  fun pursue(target: Any, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "pursue")
    return BehaviorDecl(Pursue(actorFilter(target, "pursue(target)", ctx.pkg.path)))
  }

  @StarlarkMethod(
    name = "sentinel",
    doc = "Behavior: never leaves its room.",
    useStarlarkThread = true,
  )
  fun sentinel(thread: StarlarkThread): Any {
    contentCtx(thread, "sentinel")
    return BehaviorDecl(Sentinel)
  }

  @StarlarkMethod(
    name = "scavenge",
    doc = "Behavior: picks up items lying within reach.",
    useStarlarkThread = true,
  )
  fun scavenge(thread: StarlarkThread): Any {
    contentCtx(thread, "scavenge")
    return BehaviorDecl(Scavenge)
  }

  @StarlarkMethod(
    name = "global_command",
    doc = "Registers a command available everywhere. lib and rules packages only.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun globalCommand(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "global_command")
    return GLOBAL_COMMAND_SPEC.bind(args, kwargs) { d ->
      if (!ctx.pkg.kind.privileged)
        fail("global_command(): only lib and rules packages may register global commands")
      val verb = d.reqStr("name", "global_command() name").lowercase()
      if (!Regex("[a-z][a-z0-9_]*").matches(verb))
        fail("global_command(): '$verb' is not a valid verb")
      val l = if ((d.choice("lane") ?: "fast") == "fast") Lane.FAST else Lane.TICK
      if (ctx.pkg.commandList.any { it.name == verb })
        fail("global_command(): '$verb' is already registered")
      ctx.pkg.commandList +=
        GlobalCommandDecl(verb, hookRef(d["fn"], "global_command(fn)"), l, ctx.pkg.path)
      Starlark.NONE
    }
  }

  @StarlarkMethod(
    name = "struct",
    doc =
      "An immutable record of named fields, for example a library's exported functions: struct(start = _start).",
    extraKeywords = Param(name = "kwargs"),
  )
  fun struct(kwargs: Dict<String, Any>): Any {
    return STRUCT_SPEC.bind(kwargs = kwargs) { StructValue(LinkedHashMap(kwargs)) }
  }

  // ---- Effect primitives (SPEC §4.3) -----------------------------------------------------------

  @StarlarkMethod(
    name = "damage",
    doc = "Deal damage.",
    parameters =
      [
        Param(name = "dice", named = true, doc = "Damage dice such as '2d6+1'."),
        Param(
          name = "type",
          defaultValue = "'bludgeon'",
          named = true,
          doc = "Damage type, such as 'slash', 'fire' or 'bludgeon'.",
        ),
      ],
  )
  fun damage(dice: Any, type: Any): Any =
    EffectDecl(
      Effect.Damage(dice(dice, "damage(dice)"), DamageType(str(type, "damage(type)").lowercase()))
    )

  @StarlarkMethod(
    name = "heal",
    doc = "Restore hit points.",
    parameters =
      [Param(name = "dice", named = true, doc = "Hit points restored, as dice such as '2d4'.")],
  )
  fun heal(dice: Any): Any = EffectDecl(Effect.Heal(dice(dice, "heal(dice)")))

  @StarlarkMethod(
    name = "apply_affect",
    doc = "Attach a timed buff or debuff.",
    parameters =
      [
        Param(
          name = "name",
          named = true,
          doc = "Affect name; applying the same name again replaces the existing affect.",
        ),
        Param(name = "duration", named = true, doc = "Duration in pulses."),
        Param(
          name = "stat_deltas",
          named = true,
          defaultValue = "{}",
          doc = "Stat adjustments while the affect lasts, such as {'dex': -1}.",
        ),
        Param(
          name = "flags",
          named = true,
          defaultValue = "[]",
          doc = "Flags the affect grants, such as 'no_regen'.",
        ),
        Param(
          name = "tick_every",
          named = true,
          defaultValue = "None",
          doc = "Pulses between on_tick calls, or None for no ticking.",
        ),
        Param(
          name = "on_tick",
          named = true,
          defaultValue = "None",
          doc = "Top-level function fn(ctx) called every tick_every pulses.",
        ),
        Param(
          name = "on_expire",
          named = true,
          defaultValue = "None",
          doc = "Top-level function fn(ctx) called when the affect ends.",
        ),
        Param(
          name = "expire_msg",
          named = true,
          defaultValue = "''",
          doc = "Text sent to the target when the affect ends.",
        ),
        Param(
          name = "modifiers",
          named = true,
          defaultValue = "{}",
          doc = "Rules modifiers active while the affect lasts, by modifier point name.",
        ),
      ],
  )
  fun applyAffect(
    name: Any,
    duration: Any,
    statDeltas: Any,
    flags: Any,
    tickEvery: Any,
    onTick: Any,
    onExpire: Any,
    expireMsg: Any,
    modifiers: Any,
  ): Any =
    EffectDecl(
      affectEffect(
        name,
        duration,
        statDeltas,
        flags,
        tickEvery,
        onTick,
        onExpire,
        expireMsg,
        modifiers,
        "apply_affect",
      )
    )

  @StarlarkMethod(
    name = "dispel",
    doc = "Remove matching affects; a trailing * matches a prefix.",
    parameters =
      [
        Param(
          name = "filter",
          named = true,
          doc = "Affect name to remove; a trailing * matches every name with that prefix.",
        )
      ],
  )
  fun dispel(filter: Any): Any = EffectDecl(Effect.Dispel(str(filter, "dispel(filter)")))

  @StarlarkMethod(
    name = "knockback",
    doc = "Push the target along the track.",
    parameters =
      [Param(name = "steps", named = true, doc = "Track positions to push the target, at least 1.")],
  )
  fun knockback(steps: Any): Any = EffectDecl(Effect.Knockback(positive(steps, "knockback(steps)")))

  @StarlarkMethod(
    name = "pull",
    doc = "Draw the target along the track.",
    parameters =
      [Param(name = "steps", named = true, doc = "Track positions to draw the target, at least 1.")],
  )
  fun pull(steps: Any): Any = EffectDecl(Effect.Pull(positive(steps, "pull(steps)")))

  @StarlarkMethod(
    name = "stun",
    doc = "Stun the target.",
    parameters =
      [Param(name = "pulses", named = true, doc = "Pulses the target is stunned, at least 1.")],
  )
  fun stun(pulses: Any): Any = EffectDecl(Effect.Stun(positive(pulses, "stun(pulses)")))

  @StarlarkMethod(
    name = "summon",
    doc = "Spawn mobs beside the caster.",
    parameters =
      [
        Param(name = "label", named = true, doc = "Mob prototype label to spawn."),
        Param(
          name = "count",
          named = true,
          defaultValue = "1",
          doc = "Number of mobs to spawn, at least 1.",
        ),
        Param(
          name = "duration",
          named = true,
          defaultValue = "None",
          doc = "Pulses before the summoned mobs vanish, or None to keep them.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun summon(label: Any, count: Any, duration: Any, thread: StarlarkThread): Any {
    val pkg = thread.getThreadLocal(LoadCtx::class.java)?.pkg?.path ?: callerFile(thread)?.pkg
    return EffectDecl(
      Effect.Summon(
        label(str(label, "summon(label)"), pkg, "summon(label)"),
        positive(count, "summon(count)"),
        intOrNull(duration, "summon(duration)")?.also {
          if (it < 1) fail("summon(duration): must be at least 1")
        },
      )
    )
  }

  @StarlarkMethod(
    name = "sequence",
    doc = "Combine several effects.",
    extraPositionals = Param(name = "effects", doc = "Effects applied in order."),
  )
  fun sequence(effects: Tuple): Any =
    EffectDecl(
      Effect.Sequence(effects.mapIndexed { i, e -> effectValue(e, "sequence() argument ${i + 1}") })
    )

  private fun positive(v: Any, what: String): Int =
    int(v, what).also { if (it < 1) fail("$what: must be at least 1") }

  companion object {
    private val ROOM_FLAGS = listOf("always_active", "dead_end_ok")
    private val TRACK_SIZES = TrackSize.entries.map { it.name.lowercase() }
    private val DOOR_STATES = DoorState.entries.map { it.name.lowercase() }
    private val BANDS = Band.entries.map { it.word }
    private val SENSES = Sense.entries.map { it.name.lowercase() }
    private val DORMANT_MODES = DormantRecovery.entries.map { it.name.lowercase() }
    private val SKILL_RANGES = SkillRange.entries.map { it.name.lowercase() }
    private val TARGET_KINDS = TargetKind.entries.map { it.name.lowercase() }

    /** Constants available to every package file. */
    val CONSTANTS: Map<String, Any> =
      mapOf(
        "CRAMPED" to "cramped",
        "NORMAL" to "normal",
        "LARGE" to "large",
        "ALL" to StarlarkList.immutableCopyOf(CHANNELS),
      )

    val CONSTANT_SPECS: List<ConstantSpec> =
      listOf(
        ConstantSpec(
          "CRAMPED",
          "str",
          Starlark.repr(CONSTANTS.getValue("CRAMPED")),
          "Track size constant for a 1-slot room.",
        ),
        ConstantSpec(
          "NORMAL",
          "str",
          Starlark.repr(CONSTANTS.getValue("NORMAL")),
          "Track size constant for a 3-slot room.",
        ),
        ConstantSpec(
          "LARGE",
          "str",
          Starlark.repr(CONSTANTS.getValue("LARGE")),
          "Track size constant for a 6-slot room.",
        ),
        ConstantSpec(
          "ALL",
          "list[str]",
          Starlark.repr(CONSTANTS.getValue("ALL")),
          "All perception and spell channels: sight, sound, noise, spell.",
        ),
      )

    val EFFECT_NAMES: Set<String> =
      setOf(
        "damage",
        "heal",
        "apply_affect",
        "dispel",
        "knockback",
        "pull",
        "stun",
        "summon",
        "sequence",
      )

    val BEHAVIOR_NAMES: Set<String> =
      setOf(
        "wander",
        "aggressive",
        "assist",
        "flee",
        "guard",
        "follow",
        "pursue",
        "sentinel",
        "scavenge",
      )

    val PACKAGE_SPEC =
      DeclSpec(
        name = "package",
        doc = "Declares the package. Must be called exactly once, in package.star.",
        params =
          listOf(
            DeclParam(
              "api_version",
              "int",
              "The script API version this package targets.",
              required = true,
            ),
            DeclParam(
              "kind",
              "str",
              "Package kind: 'zone', 'lib', 'rules' or 'skills'.",
              required = true,
              allowedValues = PackageKind.entries.map { it.word },
            ),
            DeclParam(
              "title",
              "str",
              "Human-readable title of the package.",
              default = "package path",
            ),
            DeclParam(
              "reset_pulses",
              "int",
              "Pulses between zone resets, or None for no periodic reset.",
              default = "None",
            ),
            DeclParam(
              "shared_namespaces",
              "list[str]",
              "Attribute namespaces this privileged (lib or rules) package may write across packages.",
              default = "[]",
            ),
            DeclParam(
              "modifiers",
              "dict[str, function]",
              "Zone-wide rules modifiers by modifier point name.",
              default = "{}",
              allowedValues = POINTS,
            ),
            DeclParam(
              "expected_tombstones",
              "list[label]",
              "Prototype labels intentionally removed in this version, for migration checks.",
              default = "[]",
            ),
            DeclParam(
              "migrate_noop",
              "list[label]",
              "Prototype labels whose version rose without needing a migrate() function.",
              default = "[]",
            ),
          ),
      )

    val ROOM_SPEC =
      DeclSpec(
        name = "room",
        doc = "Declares a room.",
        hookOwner = OwnerKind.ROOM,
        positionalError = "room() takes the declaration name as its only positional argument",
        params =
          listOf(
            DeclParam(
              "id",
              "str",
              "The room's declaration name within the package.",
              positional = true,
              keyword = false,
              required = true,
            ),
            DeclParam("name", "str", "Display name shown to players.", default = "id with spaces"),
            DeclParam("desc", "str", "Room description shown when looking.", default = "''"),
            DeclParam(
              "size",
              "str",
              "Track size: 'cramped' (1 slot), 'normal' (3 slots) or 'large' (6 slots).",
              default = "'normal'",
              allowedValues = TRACK_SIZES,
            ),
            DeclParam(
              "regions",
              "list[str]",
              "Region tags that apply their modifiers in this room.",
              default = "[]",
            ),
            DeclParam(
              "flags",
              "list[str]",
              "Room flags such as 'always_active' or 'dead_end_ok'.",
              default = "[]",
              allowedValues = ROOM_FLAGS,
            ),
            DeclParam(
              "exits",
              "list[exit]",
              "Exits leading out of this room, built with exit(...).",
              default = "[]",
            ),
            DeclParam(
              "spawns",
              "list[spawn]",
              "Mob spawn rules for this room, built with spawn(...).",
              default = "[]",
            ),
            DeclParam(
              "commands",
              "dict[str, function]",
              "Room-local commands mapping verb name to fn(ctx).",
              default = "{}",
            ),
            DeclParam(
              "modifiers",
              "dict[str, function]",
              "Room rules modifiers by modifier point name.",
              default = "{}",
              allowedValues = POINTS,
            ),
            DeclParam(
              "version",
              "int",
              "Prototype schema version for persisted state migration.",
              default = "1",
            ),
          ),
      )

    val EXIT_SPEC =
      DeclSpec(
        name = "exit",
        doc = "An exit, for room(exits=[...]).",
        positionalError = "exit() takes a direction and an optional destination",
        params =
          listOf(
            DeclParam(
              "direction",
              "str",
              "Exit direction name, such as 'north', 'up' or a custom word.",
              positional = true,
              keyword = false,
              required = true,
            ),
            DeclParam(
              "to",
              "label",
              "Destination room label.",
              positional = true,
              keyword = true,
              required = true,
            ),
            DeclParam(
              "entry_index",
              "int",
              "Track index in the destination room where travellers arrive, or -1 for the back.",
              default = "0",
            ),
            DeclParam(
              "blocks",
              "list[str]",
              "Perception and spell channels blocked by this exit.",
              default = "[]",
              allowedValues = CHANNELS,
            ),
            DeclParam(
              "door",
              "door",
              "Optional door on this exit, built with door(...).",
              default = "None",
            ),
            DeclParam(
              "one_way",
              "bool",
              "True if this exit intentionally has no return exit.",
              default = "False",
            ),
          ),
      )

    val DOOR_SPEC =
      DeclSpec(
        name = "door",
        doc = "A door, for exit(door=...).",
        params =
          listOf(
            DeclParam(
              "state",
              "str",
              "Initial door state: 'open', 'closed' or 'locked'.",
              default = "'closed'",
              allowedValues = DOOR_STATES,
            ),
            DeclParam(
              "key",
              "label",
              "Item prototype label required to lock or unlock the door.",
              default = "None",
            ),
            DeclParam(
              "open_blocks",
              "list[str]",
              "Channels blocked while the door is open.",
              default = "[]",
              allowedValues = CHANNELS,
            ),
            DeclParam(
              "closed_blocks",
              "list[str]",
              "Channels blocked while the door is closed or locked.",
              default = "['sight', 'sound']",
              allowedValues = CHANNELS,
            ),
          ),
      )

    val SPAWN_SPEC =
      DeclSpec(
        name = "spawn",
        doc = "A spawn rule, for room(spawns=[...]).",
        positionalError = "spawn() takes the mob's label as its only positional argument",
        params =
          listOf(
            DeclParam(
              "label",
              "label",
              "Mob prototype label to spawn.",
              positional = true,
              keyword = false,
              required = true,
            ),
            DeclParam(
              "count",
              "int",
              "Maximum number of instances maintained by this rule.",
              default = "1",
            ),
            DeclParam(
              "respawn_pulses",
              "int",
              "Pulses before a slain mob respawns.",
              default = "1200",
            ),
            DeclParam(
              "at_index",
              "int",
              "Track index in the room where the mob spawns.",
              default = "0",
            ),
          ),
      )

    val LOOT_SPEC =
      DeclSpec(
        name = "loot",
        doc = "A loot roll, for mob(loot=[...]). chance is a probability between 0 and 1.",
        positionalError = "loot() takes the item's label as its only positional argument",
        params =
          listOf(
            DeclParam(
              "label",
              "label",
              "Item prototype label dropped on death.",
              positional = true,
              keyword = false,
              required = true,
            ),
            DeclParam("chance", "float", "Drop probability between 0.0 and 1.0.", default = "1.0"),
            DeclParam(
              "count",
              "int",
              "Number of items dropped when the roll succeeds.",
              default = "1",
            ),
          ),
      )

    val CONTAINER_SPEC =
      DeclSpec(
        name = "container",
        doc = "Container settings, for item(container=...).",
        params =
          listOf(
            DeclParam(
              "capacity",
              "int",
              "Maximum number of items the container holds.",
              default = "10",
            ),
            DeclParam(
              "max_weight",
              "int",
              "Maximum total weight the container holds.",
              default = "100",
            ),
            DeclParam(
              "state",
              "str",
              "Initial container state: 'open', 'closed' or 'locked'.",
              default = "'open'",
              allowedValues = DOOR_STATES,
            ),
            DeclParam(
              "key",
              "label",
              "Item prototype label required to lock or unlock the container.",
              default = "None",
            ),
          ),
      )

    val MOB_SPEC =
      DeclSpec(
        name = "mob",
        doc = "Declares a mob prototype.",
        hookOwner = OwnerKind.MOB,
        positionalError = "mob() takes the declaration name as its only positional argument",
        params =
          listOf(
            DeclParam(
              "id",
              "str",
              "The mob's declaration name within the package.",
              positional = true,
              keyword = false,
              required = true,
            ),
            DeclParam("name", "str", "Display name shown to players.", default = "id with spaces"),
            DeclParam(
              "keywords",
              "list[str]",
              "Words players can use to target this mob.",
              default = "words of name",
            ),
            DeclParam("desc", "str", "Description shown when looking at the mob.", default = "''"),
            DeclParam("level", "int", "Mob level.", default = "1"),
            DeclParam(
              "stats",
              "dict[str, int]",
              "Base stats ('str', 'dex', 'con', 'int', 'wis').",
              default = "{'str': 10, 'dex': 10, 'con': 10, 'int': 10, 'wis': 10}",
              allowedValues = STAT_NAMES,
            ),
            DeclParam("hp", "int", "Maximum hit points.", default = "10"),
            DeclParam("mana", "int", "Maximum mana.", default = "0"),
            DeclParam("move", "int", "Maximum movement points.", default = "100"),
            DeclParam("armor", "int", "Base armor rating.", default = "0"),
            DeclParam(
              "resists",
              "dict[str, int]",
              "Damage resistance percentage by damage type.",
              default = "{}",
            ),
            DeclParam(
              "skills",
              "dict[label, int]",
              "Known skills mapping skill label to proficiency (0..100).",
              default = "{}",
            ),
            DeclParam(
              "senses",
              "dict[str, int]",
              "Perception radii in exits (0..1) by sense.",
              default = "{}",
              allowedValues = SENSES,
            ),
            DeclParam(
              "think",
              "function",
              "Custom AI function fn(ctx) run on each think cycle.",
              default = "None",
            ),
            DeclParam("think_every", "int", "Pulses between AI think cycles.", default = "4"),
            DeclParam(
              "behaviors",
              "list[behavior]",
              "Built-in behaviors such as wander(...) or aggressive(...).",
              default = "[]",
            ),
            DeclParam(
              "dormant_recovery",
              "str",
              "How the mob recovers when its room wakes from dormancy.",
              default = "'full'",
              allowedValues = DORMANT_MODES,
            ),
            DeclParam(
              "loot",
              "list[loot]",
              "Loot rules rolled when the mob dies, built with loot(...).",
              default = "[]",
            ),
            DeclParam(
              "version",
              "int",
              "Prototype schema version for persisted state migration.",
              default = "1",
            ),
          ),
      )

    val ITEM_SPEC =
      DeclSpec(
        name = "item",
        doc = "Declares an item prototype.",
        hookOwner = OwnerKind.ITEM,
        positionalError = "item() takes the declaration name as its only positional argument",
        params =
          listOf(
            DeclParam(
              "id",
              "str",
              "The item's declaration name within the package.",
              positional = true,
              keyword = false,
              required = true,
            ),
            DeclParam("name", "str", "Display name shown to players.", default = "id with spaces"),
            DeclParam(
              "keywords",
              "list[str]",
              "Words players can use to target this item.",
              default = "words of name",
            ),
            DeclParam("desc", "str", "Description shown when examining the item.", default = "''"),
            DeclParam(
              "kind",
              "str",
              "Item category, such as 'misc', 'melee', 'ranged', 'armor' or 'key'.",
              default = "'misc'",
            ),
            DeclParam(
              "range",
              "str",
              "Weapon reach band: 'melee', 'near' or 'far'.",
              default = "'melee'",
              allowedValues = BANDS,
            ),
            DeclParam("weight", "int", "Weight of one item.", default = "1"),
            DeclParam("wear", "str", "Equipment slot the item is worn in.", default = "None"),
            DeclParam("wield", "str", "Weapon slot alias for wear.", default = "None"),
            DeclParam("dice", "dice", "Weapon damage dice such as '1d6+1'.", default = "None"),
            DeclParam(
              "damage_type",
              "str",
              "Weapon damage type, such as 'slash', 'pierce' or 'bludgeon'.",
              default = "'bludgeon'",
            ),
            DeclParam(
              "stat_deltas",
              "dict[str, int]",
              "Stat adjustments applied while the item is worn.",
              default = "{}",
              allowedValues = STAT_NAMES,
            ),
            DeclParam(
              "stackable",
              "bool",
              "True if identical items merge into a stack.",
              default = "False",
            ),
            DeclParam(
              "decay_pulses",
              "int",
              "Pulses before the item decays, or None for permanent items.",
              default = "None",
            ),
            DeclParam(
              "container",
              "container",
              "Container settings, built with container(...).",
              default = "None",
            ),
            DeclParam(
              "commands",
              "dict[str, function]",
              "Item-local commands mapping verb name to fn(ctx).",
              default = "{}",
            ),
            DeclParam(
              "equip_affects",
              "list[str]",
              "Affect flags granted while the item is worn.",
              default = "[]",
            ),
            DeclParam(
              "modifiers",
              "dict[str, function]",
              "Rules modifiers active while the item is worn.",
              default = "{}",
              allowedValues = POINTS,
            ),
            DeclParam(
              "version",
              "int",
              "Prototype schema version for persisted state migration.",
              default = "1",
            ),
          ),
      )

    val SKILL_SPEC =
      DeclSpec(
        name = "skill",
        doc = "Declares a skill or spell.",
        positionalError = "skill() takes the declaration name as its only positional argument",
        params =
          listOf(
            DeclParam(
              "id",
              "str",
              "The skill's declaration name within the package.",
              positional = true,
              keyword = false,
              required = true,
            ),
            DeclParam(
              "kind",
              "str",
              "Skill category, such as 'spell' or 'skill'.",
              default = "'skill'",
            ),
            DeclParam(
              "cost",
              "dict[str, int]",
              "Vitals spent on cast ('hp', 'mana', 'move').",
              default = "{}",
              allowedValues = listOf("hp", "mana", "move"),
            ),
            DeclParam(
              "warmup",
              "int",
              "Pulses of casting warmup before the skill resolves.",
              default = "0",
            ),
            DeclParam(
              "lag",
              "int",
              "Recovery wait-state pulses after the skill resolves.",
              default = "0",
            ),
            DeclParam(
              "range",
              "str",
              "Maximum target range.",
              default = "'melee'",
              allowedValues = SKILL_RANGES,
            ),
            DeclParam(
              "target",
              "str",
              "Kind of target the skill accepts.",
              default = "'enemy'",
              allowedValues = TARGET_KINDS,
            ),
            DeclParam(
              "channels",
              "list[str]",
              "Channels required and emitted when casting.",
              default = "[]",
              allowedValues = CHANNELS,
            ),
            DeclParam(
              "interrupt",
              "dict[str, any]",
              "Warmup interruption rules ('damage_pct', 'stun').",
              default = "{'damage_pct': None, 'stun': True}",
              allowedValues = listOf("damage_pct", "stun"),
            ),
            DeclParam(
              "attack_while_casting",
              "bool",
              "True if auto-attacks continue during warmup.",
              default = "False",
            ),
            DeclParam(
              "effect",
              "effect",
              "Declarative effect primitive applied when the skill resolves.",
              default = "None",
            ),
            DeclParam(
              "resolve",
              "function",
              "Custom function fn(ctx) run when the skill resolves.",
              default = "None",
            ),
          ),
      )

    val REGION_SPEC =
      DeclSpec(
        name = "region",
        doc =
          "Declares the modifiers of a region, a tag rooms carry with room(regions=[...]). Region names are world-wide.",
        positionalError = "region() takes the declaration name as its only positional argument",
        params =
          listOf(
            DeclParam(
              "id",
              "str",
              "World-wide region tag name.",
              positional = true,
              keyword = false,
              required = true,
            ),
            DeclParam(
              "modifiers",
              "dict[str, function]",
              "Rules modifiers active in rooms carrying this region tag.",
              default = "{}",
              allowedValues = POINTS,
            ),
          ),
      )

    val GLOBAL_COMMAND_SPEC =
      DeclSpec(
        name = "global_command",
        doc = "Registers a command available everywhere. lib and rules packages only.",
        positionalError = "global_command() takes name, fn and an optional lane",
        params =
          listOf(
            DeclParam(
              "name",
              "str",
              "Command verb players type.",
              positional = true,
              keyword = true,
              required = true,
            ),
            DeclParam(
              "fn",
              "function",
              "Top-level handler function fn(ctx).",
              positional = true,
              keyword = true,
              required = true,
            ),
            DeclParam(
              "lane",
              "str",
              "Execution lane: 'fast' (immediate) or 'tick' (queued on the actor).",
              positional = true,
              keyword = true,
              default = "'fast'",
              allowedValues = listOf("fast", "tick"),
            ),
          ),
      )

    val STRUCT_SPEC =
      DeclSpec(
        name = "struct",
        doc =
          "An immutable record of named fields, for example a library's exported functions: struct(start = _start).",
        extraKeywords = true,
        params = emptyList(),
      )

    val DECLARATION_SPECS: List<DeclSpec> =
      listOf(
        PACKAGE_SPEC,
        ROOM_SPEC,
        EXIT_SPEC,
        DOOR_SPEC,
        SPAWN_SPEC,
        LOOT_SPEC,
        CONTAINER_SPEC,
        MOB_SPEC,
        ITEM_SPEC,
        SKILL_SPEC,
        REGION_SPEC,
        GLOBAL_COMMAND_SPEC,
        STRUCT_SPEC,
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
    statDeltas =
      intDict(statDeltas, "$fn(stat_deltas)").onEach { (k, _) ->
        if (k !in STAT_NAMES)
          fail("$fn(stat_deltas): unknown stat '$k'${SpellChecker.didYouMean(k, STAT_NAMES)}")
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
internal class ApiVersionMismatch(val declared: Int) :
  RuntimeException(
    "package declares api_version $declared, but this engine supports only api_version ${EngineInfo.API_VERSION} " +
      "(${if (declared > EngineInfo.API_VERSION) "the package is newer than the engine" else "the package is ${EngineInfo.API_VERSION - declared} version(s) behind"}). " +
      "See CHANGELOG.md#api_version-${maxOf(declared, EngineInfo.API_VERSION)} and MIGRATING.md."
  )
