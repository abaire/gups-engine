package gups.script

import com.google.common.collect.ImmutableCollection
import com.google.common.collect.ImmutableList
import gups.core.EngineInfo
import gups.core.HookRef
import gups.core.Label
import gups.core.WORLD_TICK_PULSES
import gups.core.model.AffectScope
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.ContainerProto
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Direction
import gups.core.model.DoorProto
import gups.core.model.DoorState
import gups.core.model.DormantRecovery
import gups.core.model.Effect
import gups.core.model.EmptyAction
import gups.core.model.EquipAffect
import gups.core.model.ExitProto
import gups.core.model.Interrupt
import gups.core.model.ItemAffect
import gups.core.model.ItemProto
import gups.core.model.LootRule
import gups.core.model.MobProto
import gups.core.model.ModifierPoint
import gups.core.model.Modifiers
import gups.core.model.Proc
import gups.core.model.ProcOutcome
import gups.core.model.ProcTarget
import gups.core.model.ProcTrigger
import gups.core.model.RegionProto
import gups.core.model.RoomProto
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.SpawnRule
import gups.core.model.TAG_REGEX
import gups.core.model.TargetKind
import gups.core.model.TrackSize
import gups.core.model.WorldSchema
import gups.core.model.didYouMean
import gups.core.model.keywordWords
import gups.core.world.ActorFilter
import gups.core.world.Aggressive
import gups.core.world.Assist
import gups.core.world.Behavior
import gups.core.world.ENGINE_GMCP_PACKAGES
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.Flee
import gups.core.world.Follow
import gups.core.world.GMCP_PACKAGE_NAME
import gups.core.world.Guard
import gups.core.world.HookName
import gups.core.world.Lane
import gups.core.world.Pursue
import gups.core.world.Scavenge
import gups.core.world.Sentinel
import gups.core.world.Wander
import gups.core.world.ZoneCommand
import gups.core.world.gmcpOverlaps
import gups.core.world.gmcpWithin
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

// The content declarations of a package. They are only callable while a
// package's files evaluate; each one produces a frozen Kotlin prototype.

/** What the file being evaluated may do. Attached to the evaluating thread. */
internal class LoadCtx(
  val file: FileInfo,
  val pkg: ScriptPackage,
  val header: Boolean,
  /** Names an exit may not take: engine commands and the world's aliases for them. */
  val engineVerbs: Set<String>,
  private val onStep: () -> Unit,
) {
  var packageDeclared = false

  /** The wall-clock backstop for package evaluation, checked at each declaration. */
  fun tick() = onStep()

  fun ref(label: Label, kind: RefKind, thread: StarlarkThread) {
    val loc = thread.callerLocation
    pkg.refs += LabelRef(label, kind, loc?.file(), loc?.line(), loc?.column())
  }

  fun wearRef(item: Label, param: String, wear: String, thread: StarlarkThread) {
    val loc = thread.callerLocation
    pkg.wearRefs += WearRef(item, param, wear, loc?.file(), loc?.line(), loc?.column())
  }
}

/** Kinds of entity a hook can be attached to. */
enum class OwnerKind(val word: String) {
  ROOM("room"),
  MOB("mob"),
  ITEM("item"),
}

/** The hook catalog: attachment rules, signatures, ctx fields and docs. */
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
        ctxFields =
          listOf(
            "self",
            "actor",
            "target",
            "amount",
            "type",
            "losses",
            "parts",
            "room",
            "pulse",
            "rng",
          ),
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
        hook = HookName.ON_LEVEL_CHANGE,
        signature = "fn(ctx)",
        attachTo = listOf(M, R, I),
        ctxFields = listOf("self", "actor", "old_level", "new_level", "room", "pulse", "rng"),
        doc = "Fires once per level step when an actor's level changes up or down.",
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
        hook = HookName.ON_PERCEIVE,
        signature = "fn(ctx)",
        attachTo = listOf(M),
        ctxFields =
          listOf(
            "self",
            "event",
            "channel",
            "intensity",
            "cost",
            "actor",
            "source_room",
            "via_exit",
            "text",
            "room",
            "pulse",
            "rng",
          ),
        doc =
          "Fires on a mob when `rules.can_perceive` lets it notice an event on a channel, in its room or carried to it from farther away.",
      ),
    ) + manipulationHooks()

  /** The before and after hooks of `open`, `close`, `lock` and `unlock` on a door or container. */
  private fun manipulationHooks(): List<HookSpec> =
    listOf(
        HookName.BEFORE_OPEN to "opening",
        HookName.ON_OPEN to "opened",
        HookName.BEFORE_CLOSE to "closing",
        HookName.ON_CLOSE to "closed",
        HookName.BEFORE_LOCK to "locking",
        HookName.ON_LOCK to "locked",
        HookName.BEFORE_UNLOCK to "unlocking",
        HookName.ON_UNLOCK to "unlocked",
      )
      .map { (hook, word) ->
        HookSpec(
          hook = hook,
          signature = "fn(ctx)",
          attachTo = listOf(I, R, M),
          ctxFields = listOf("self", "actor", "direction", "item", "room", "pulse", "rng"),
          doc =
            if (hook.vetoable)
              "Fires before an actor starts $word a door or container; return ctx.deny(msg) to veto. For a door ctx.direction is set and ctx.item is None; for a container it is the other way around."
            else
              "Fires after a door or container is $word. For a door ctx.direction is set and ctx.item is None; for a container it is the other way around.",
        )
      }

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

internal class ProcDecl(val proc: Proc) : DeclValue("proc")

internal class OutcomeDecl(val outcome: ProcOutcome) : DeclValue("outcome")

internal class ContainerDecl(val proto: ContainerProto) : DeclValue("container")

internal class ItemAffectDecl(val affect: ItemAffect) : DeclValue("item_affect")

internal class EffectDecl(val effect: Effect) : DeclValue("effect")

/** An effect value for a plugin effect primitive, usable wherever an engine effect is. */
fun pluginEffect(pluginId: String, name: String, args: Map<String, Any?>): StarlarkValue =
  EffectDecl(Effect.Plugin(pluginId, name, args))

internal class BehaviorDecl(val behavior: Behavior) : DeclValue("behavior")

/** What `struct(**fields)` returns: read-only named fields. */
internal class StructValue(val fields: Map<String, Any>) : Structure {
  override fun getValue(name: String): Any? = fields[name]

  override fun getFieldNames(): ImmutableCollection<String> = ImmutableList.copyOf(fields.keys)

  override fun getErrorMessageForUnknownField(field: String): String =
    "struct has no field '$field'${didYouMean(field, fields.keys)}"

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

/** The channels named in [v], each built-in or declared by the world. */
internal fun channels(v: Any?, what: String, schema: WorldSchema): Set<Channel> {
  val known = schema.allChannels.map { it.name }
  return strList(v, what).mapTo(LinkedHashSet()) { Channel(choice(it, what, known)) }
}

/** Sense scores by channel in [v]: each channel built-in or declared, each value an int. */
internal fun senses(
  v: Any?,
  what: String,
  schema: WorldSchema,
  allowNegative: Boolean,
): Map<Channel, Int> {
  val known = schema.allChannels.map { it.name }
  return intDict(v, what).entries.associate { (k, n) ->
    if (!allowNegative && n < 0) fail("$what[\"$k\"]: must be 0 or more")
    Channel(choice(k, what, known)) to n
  }
}

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

private fun displayName(id: String): String = id.replace('_', ' ')

/**
 * Keywords default to the words of the display name minus articles, or else the declaration name.
 */
private fun defaultKeywords(name: String, id: String): List<String> =
  keywordWords(name).ifEmpty { listOf(id.lowercase()) }

private fun commands(v: Any?, what: String, pkg: String): Map<String, ZoneCommand> {
  if (isNone(v)) return emptyMap()
  return dict(v, what).entries.associate { (verb, fn) ->
    val word = verb.lowercase()
    if (!Regex("[a-z][a-z0-9_]*").matches(word)) fail("$what: '$verb' is not a valid verb")
    word to ScriptCommand(word, hookRef(fn, "$what[\"$verb\"]"), pkg)
  }
}

/** A `hooks` dict: a custom hook name to a top-level function. */
private fun customHooks(v: Any?, what: String): Map<String, HookRef> {
  if (isNone(v)) return emptyMap()
  val catalog = HookCatalog.specs.mapTo(HashSet()) { it.hook.scriptName }
  return dict(v, what).entries.associate { (k, fn) ->
    val name = k.lowercase()
    if (!TAG_REGEX.matches(name)) fail("$what: '$name' is not a valid hook name")
    if (name in catalog) fail("$what: '$name' is a catalog hook; pass it as $name=... instead")
    name to hookRef(fn, "$what[\"$k\"]")
  }
}

private fun version(d: ParsedDecl): Int {
  val v = d.int("version") ?: 1
  if (v < 1) fail("${d.fn}(version): must be at least 1")
  return v
}

private val POINTS = ModifierPoint.entries.map { it.scriptName }

/** A `modifiers` dict: modifier point name to a top-level function `fn(ctx, base)`. */
internal fun modifiers(v: Any?, what: String, features: Features = Features.ALL): Modifiers {
  if (isNone(v)) return emptyMap()
  return dict(v, what).entries.associate { (k, fn) ->
    val point =
      ModifierPoint.byName(k)
        ?: fail(
          "$what: '$k' is not a modifier point${SpellChecker.didYouMean(k, POINTS)} (one of ${POINTS.joinToString(", ")})"
        )
    FeatureOwnership.refused("modifier:$k", "$what[\"$k\"]", features)?.let { fail(it) }
    point to hookRef(fn, "$what[\"$k\"]")
  }
}

/** Affect flags such as `no_regen`: lowercase words. */
private fun flags(v: Any?, what: String): Set<String> =
  strList(v, what).onEach { flag(it, what) }.toSet()

private fun flag(f: String, what: String): String =
  f.also { if (!TAG_REGEX.matches(it)) fail("$what: '$it' is not a valid flag") }

private fun parseTags(d: ParsedDecl): Set<String> {
  val tags = d.strList("tags")
  for (t in tags) {
    if (!TAG_REGEX.matches(t)) fail("${d.what("tags")}: '$t' is not a valid tag")
  }
  return tags.toSortedSet()
}

private fun parseMobTraits(d: ParsedDecl, known: Set<String>): Set<String> {
  val traits = d.strList("traits")
  for (t in traits) {
    if (t !in known) {
      fail("${d.what("traits")}: unknown trait '$t'${didYouMean(t, known)}")
    }
  }
  return traits.toSortedSet()
}

/**
 * `equip_affects` entries: a flag name, held while worn, or `{"flag": ..., "pulses": n}`, applied
 * for `n` pulses on equip. A stun must be timed so an item can't stun its wearer for good.
 */
private fun equipAffects(v: Any?, what: String): List<EquipAffect> =
  (if (isNone(v)) emptyList() else list(v, what)).map { e ->
    val affect =
      if (e is String) {
        EquipAffect(flag(e, what))
      } else {
        val m = dict(e, what)
        for (k in m.keys) choice(k, what, listOf("flag", "pulses"))
        val flag = flag(str(m["flag"], "$what[\"flag\"]"), what)
        val pulses = int(m["pulses"], "$what[\"pulses\"]")
        if (pulses < 1) fail("$what: '$flag' needs \"pulses\" of at least 1")
        EquipAffect(flag, pulses)
      }
    if (affect.flag == "stunned" && affect.pulses == null) {
      fail("$what: 'stunned' needs a duration, as {\"flag\": \"stunned\", \"pulses\": n}")
    }
    affect
  }

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

/** Roots script packages may not send under: engine trees the bundled client subscribes to. */
internal val SCRIPT_RESERVED_GMCP_ROOTS = setOf("Core", "Char", "Room", "Comm", "Gups")

/**
 * The declaration builtins, plus the effect primitives and constants. Effect primitives work
 * anywhere; everything else only while a package loads.
 */
internal class DeclarationBuiltins(
  private val schema: WorldSchema = WorldSchema.EMPTY,
  private val features: Features = Features.ALL,
  private val knownTraits: () -> Set<String> = { emptySet() },
) {
  /** Fails with "<name> needs the <feature> feature" if the world has [symbol]'s feature off. */
  private fun need(symbol: String, name: String = symbol) {
    FeatureOwnership.refused(symbol, name, features)?.let { fail(it) }
  }

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
      features = features,
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
      if (p.kind == PackageKind.SKILLS && Feature.SKILLS !in features)
        fail(FeatureOwnership.refusal("package kind 'skills'", Feature.SKILLS))
      p.title = d.str("title") ?: p.path
      // Counted in world ticks so a reset always lands on a world-tick boundary.
      p.resetPulses =
        d.int("reset_world_ticks")?.let {
          if (it < 1) fail("package(reset_world_ticks): must be at least 1")
          if (it > Int.MAX_VALUE / WORLD_TICK_PULSES) fail("package(reset_world_ticks): too large")
          it * WORLD_TICK_PULSES
        }
      p.sharedNamespaces = d.strList("shared_namespaces")
      if (p.sharedNamespaces.isNotEmpty() && !p.kind.privileged) {
        fail(
          "package(shared_namespaces): only lib and rules packages may declare shared namespaces"
        )
      }
      for (ns in p.sharedNamespaces) if (!Regex("[a-z0-9_]+").matches(ns))
        fail("package(shared_namespaces): '$ns' is not a valid namespace")
      p.modifiers = modifiers(d["modifiers"], d.what("modifiers"), features)
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
    return ROOM_SPEC.bind(args, kwargs, features = features) { d ->
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
          tags = parseTags(d),
          flags = flags,
          exits = exits,
          spawns = spawns,
          commands = commands(d["commands"], d.what("commands"), pkg),
          modifiers = modifiers(d["modifiers"], d.what("modifiers"), features),
          version = version(d),
        )
      for (r in proto.regions) if (!Regex("[a-z0-9_]+").matches(r))
        fail("room(regions): '$r' is not a valid region name")
      ctx.pkg.roomList += proto
      if (d.hooks.isNotEmpty()) ctx.pkg.hookMap[label] = d.hooks
      val hooks = customHooks(d["hooks"], d.what("hooks"))
      if (hooks.isNotEmpty()) ctx.pkg.customHookMap[label] = hooks
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
    return EXIT_SPEC.bind(args, kwargs, features = features) { d ->
      val dir = Direction.parse(d.reqStr("direction", "exit() direction"))
      if (!Regex("[a-z][a-z0-9_]*").matches(dir.name))
        fail("exit(): '${dir.name}' is not a valid exit name")
      if (dir.name in ctx.engineVerbs)
        fail("exit(): '${dir.name}' is a reserved keyword: it is an engine command")
      val to = label(d.reqStr("to", "exit() destination"), ctx.pkg.path, d.what("to"))
      val proto =
        ExitProto(
          direction = dir,
          to = to,
          entryIndex = d.int("entry_index") ?: 0,
          tags = parseTags(d),
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
    return DOOR_SPEC.bind(kwargs = kwargs, features = features) { d ->
      val def = DoorProto()
      val proto =
        DoorProto(
          state = d.choice("state")?.let { DoorState.valueOf(it.uppercase()) } ?: def.state,
          key = d.str("key")?.let { label(it, ctx.pkg.path, d.what("key")) },
          tags = parseTags(d),
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
    return SPAWN_SPEC.bind(args, kwargs, features = features) { d ->
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
    return LOOT_SPEC.bind(args, kwargs, features = features) { d ->
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
    return CONTAINER_SPEC.bind(kwargs = kwargs, features = features) { d ->
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
    name = "item_affect",
    doc = "An affect an item carries, for item(affects=[...]).",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun itemAffect(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    contentCtx(thread, "item_affect")
    return ITEM_AFFECT_SPEC.bind(args, kwargs, features = features) { d ->
      val name = d.reqStr("name", "item_affect() name")
      if (name.isBlank()) fail("item_affect(name): can't be blank")
      val applies =
        d.choice("applies")?.let { AffectScope.valueOf(it.uppercase()) } ?: AffectScope.WORN
      val statDeltas = typed(d["stat_deltas"], TypedKind.STATS, d.what("stat_deltas"))
      val senseDeltas = senses(d["sense_deltas"], d.what("sense_deltas"), schema, true)
      val maxVitalDeltas =
        typed(d["max_vital_deltas"], TypedKind.VITALS, d.what("max_vital_deltas"))
      val resistDeltas =
        typed(d["resist_deltas"], TypedKind.RESISTS, d.what("resist_deltas")).mapKeys { (k, _) ->
          DamageType(k)
        }
      val modifiers = modifiers(d["modifiers"], d.what("modifiers"), features)
      val rawFlags = d.strList("flags").map { flag(it, d.what("flags")) }
      if ("stunned" in rawFlags)
        fail("item_affect(flags): 'stunned' isn't allowed; item affects can't stun")
      val procs = procList(d["procs"], "item_affect(procs)")
      ItemAffectDecl(
        ItemAffect(
          name = name,
          applies = applies,
          statDeltas = statDeltas,
          senseDeltas = senseDeltas,
          maxVitalDeltas = maxVitalDeltas,
          resistDeltas = resistDeltas,
          modifiers = modifiers,
          flags = rawFlags.toSortedSet(),
          procs = procs,
        )
      )
    }
  }

  @StarlarkMethod(
    name = "outcome",
    doc = "A weighted outcome in a proc's outcome table.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun outcome(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    need("outcome", "procs")
    val loadCtx = thread.getThreadLocal(LoadCtx::class.java)
    val pkg = loadCtx?.pkg?.path ?: callerFile(thread)?.pkg
    return OUTCOME_SPEC.bind(args, kwargs, features = features) { d ->
      val weight = d.number("weight") ?: 1.0
      if (weight !in 0.0..1.0) fail("outcome(weight): must be between 0 and 1")
      val skillRaw = d["skill"]
      val effectRaw = d["effect"]
      if (skillRaw != null && !isNone(skillRaw) && effectRaw != null && !isNone(effectRaw)) {
        fail("outcome(): give skill or effect, not both")
      }
      val skill =
        if (skillRaw != null && !isNone(skillRaw)) {
          if (Feature.SKILLS !in features)
            fail(FeatureOwnership.refusal("proc(skill)", Feature.SKILLS))
          val l = label(str(skillRaw, d.what("skill")), pkg, d.what("skill"))
          loadCtx?.ref(l, RefKind.SKILL, thread)
          l
        } else null
      val effect =
        if (effectRaw != null && !isNone(effectRaw)) {
          val e = effectValue(effectRaw, d.what("effect"))
          checkProcEffect(e, d.what("effect"), loadCtx, thread)
          e
        } else null
      val target = d.choice("target")?.let { ProcTarget.valueOf(it.uppercase()) }
      val message =
        d["message"]?.let {
          val s = str(it, d.what("message"))
          if (s.isBlank()) fail("outcome(message): must not be blank")
          s
        }
      OutcomeDecl(
        ProcOutcome(
          weight = weight,
          skill = skill,
          effect = effect,
          target = target,
          message = message,
        )
      )
    }
  }

  @StarlarkMethod(
    name = "proc",
    doc = "A combat proc that fires during auto-attack swings.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun proc(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    need("proc", "procs")
    val loadCtx = thread.getThreadLocal(LoadCtx::class.java)
    val pkg = loadCtx?.pkg?.path ?: callerFile(thread)?.pkg
    return PROC_SPEC.bind(args, kwargs, features = features) { d ->
      val onStr = choice(d["on"], "proc(on)", listOf("hit", "struck", "avoid"))
      val trigger = ProcTrigger.valueOf(onStr.uppercase())
      val chance = d.number("chance") ?: 1.0
      if (chance !in 0.0..1.0) fail("proc(chance): must be between 0 and 1")
      val outcomesRaw = d["outcomes"]
      val skillRaw = d["skill"]
      val effectRaw = d["effect"]
      val targetRaw = d["target"]
      val messageRaw = d["message"]
      val outcomes: List<ProcOutcome>
      if (outcomesRaw != null && !isNone(outcomesRaw)) {
        if (!isNone(skillRaw) || !isNone(effectRaw) || !isNone(targetRaw) || !isNone(messageRaw)) {
          fail("proc(outcomes): can't be combined with skill, effect, target or message")
        }
        val list =
          outcomesRaw as? Sequence<*>
            ?: fail("proc(outcomes): expected list, got ${typeName(outcomesRaw)}")
        if (list.isEmpty()) fail("proc(outcomes): must not be empty")
        val parsedOutcomes =
          list.mapIndexed { i, elem ->
            (elem as? OutcomeDecl)?.outcome
              ?: fail("proc(outcomes): element ${i + 1} must be an outcome(...) value")
          }
        var total = 0.0
        for (o in parsedOutcomes) {
          total += o.weight
        }
        if (total > 1.0000001) fail("proc(outcomes): weights add up to more than 1")
        outcomes = parsedOutcomes
      } else {
        if (isNone(skillRaw) && isNone(effectRaw)) {
          fail("proc(): give one of skill, effect or outcomes")
        }
        if (!isNone(skillRaw) && !isNone(effectRaw)) {
          fail("proc(): give one of skill, effect or outcomes")
        }
        val skill =
          if (!isNone(skillRaw)) {
            if (Feature.SKILLS !in features)
              fail(FeatureOwnership.refusal("proc(skill)", Feature.SKILLS))
            val l = label(str(skillRaw, d.what("skill")), pkg, d.what("skill"))
            loadCtx?.ref(l, RefKind.SKILL, thread)
            l
          } else null
        val effect =
          if (!isNone(effectRaw)) {
            val e = effectValue(effectRaw, d.what("effect"))
            checkProcEffect(e, d.what("effect"), loadCtx, thread)
            e
          } else null
        val target = d.choice("target")?.let { ProcTarget.valueOf(it.uppercase()) }
        val message =
          if (!isNone(messageRaw)) {
            val s = str(messageRaw, d.what("message"))
            if (s.isBlank()) fail("proc(message): must not be blank")
            s
          } else null
        outcomes =
          listOf(
            ProcOutcome(
              weight = 1.0,
              skill = skill,
              effect = effect,
              target = target,
              message = message,
            )
          )
      }
      ProcDecl(Proc(on = trigger, chance = chance, outcomes = outcomes))
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
    return MOB_SPEC.bind(args, kwargs, features = features) { d ->
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
      val senses = senses(d["senses"], d.what("senses"), schema, allowNegative = false)
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
          stats = typed(d["stats"], TypedKind.STATS, d.what("stats")),
          vitals = typed(d["vitals"], TypedKind.VITALS, d.what("vitals")),
          resists =
            typed(d["resists"], TypedKind.RESISTS, d.what("resists")).mapKeys {
              DamageType(it.key)
            },
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
          traits = parseMobTraits(d, knownTraits()),
          version = version(d),
        )
      if (proto.thinkEvery < 1) fail("mob(think_every): must be at least 1")
      if (proto.keywords.isEmpty()) fail("mob(keywords): a mob needs at least one keyword")
      ctx.pkg.mobList += proto
      if (d.hooks.isNotEmpty()) ctx.pkg.hookMap[label] = d.hooks
      val hooks = customHooks(d["hooks"], d.what("hooks"))
      if (hooks.isNotEmpty()) ctx.pkg.customHookMap[label] = hooks
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
    return ITEM_SPEC.bind(args, kwargs, features = features) { d ->
      val id = declName("item", d, ctx)
      val pkg = ctx.pkg.path
      val label = Label("//$pkg:$id")
      val name = d.str("name") ?: displayName(id)
      val plural = d.str("plural")
      if (plural != null && plural.isBlank()) fail("item(plural): can't be blank")
      val wear = d.str("wear")
      val wield = d.str("wield")
      if (wear != null && wield != null) fail("item(): give either wear or wield, not both")
      if (wear != null) ctx.wearRef(label, "wear", wear, thread)
      if (wield != null) ctx.wearRef(label, "wield", wield, thread)
      val slotCost = d.int("slot_cost") ?: 1
      if (slotCost < 1) fail("item(slot_cost): must be at least 1, got $slotCost")
      if (slotCost != 1 && wear == null && wield == null) {
        fail(
          "item(slot_cost): slot_cost = $slotCost needs wear or wield; an item that isn't worn takes up no slots"
        )
      }
      val container =
        d["container"]?.let {
          (it as? ContainerDecl)?.proto
            ?: fail("item(container): expected container(...), got ${typeName(it)}")
        }
      container?.key?.let { ctx.ref(it, RefKind.ITEM, thread) }
      val affects = run {
        val raw = d.list("affects")
        val seen = mutableSetOf<String>()
        raw.map { elem ->
          val decl =
            elem as? ItemAffectDecl
              ?: fail("item(affects): expected item_affect(...), got ${typeName(elem)}")
          val aff = decl.affect
          if (!seen.add(aff.name)) fail("item(affects): '${aff.name}' appears twice")
          aff
        }
      }
      val variants = run {
        val raw = d.dict("variants")
        val out = LinkedHashMap<String, Label>(raw.size)
        for ((k, v) in raw) {
          if (!TAG_REGEX.matches(k)) fail("item(variants): '$k' is not a valid variant name")
          val targetLabel = label(str(v, d.what("variants")), pkg, d.what("variants"))
          ctx.ref(targetLabel, RefKind.ITEM, thread)
          out[k] = targetLabel
        }
        out
      }
      val charges = d.int("charges")
      val empty = d.str("empty")
      if (charges != null && charges < 1) fail("item(charges): must be at least 1")
      if (empty != null && empty != "destroy" && charges == null) fail("item(empty): needs charges")
      val stackable = d.bool("stackable") ?: false
      if (stackable && charges != null && charges != 1) {
        fail("item(charges): a stackable item can only have 1 charge")
      }
      if (stackable && empty == "keep") {
        fail("item(empty): a stackable item can't keep an empty item")
      }
      if (empty != null && empty != "destroy" && empty != "keep" && empty !in variants) {
        fail("item(empty): '$empty' is not 'destroy', 'keep' or one of this item's variants")
      }
      val emptyAction =
        when (empty) {
          null,
          "destroy" -> EmptyAction.Destroy
          "keep" -> EmptyAction.Keep
          else -> EmptyAction.Variant(empty)
        }
      val useEffect = d["use_effect"]?.let { effectValue(it, d.what("use_effect")) }
      useEffect?.let { effectRefs(it, ctx, thread) }
      val useTarget = d.choice("use_target")?.let { TargetKind.valueOf(it.uppercase()) }
      val useLag = d.int("use_lag") ?: 0
      if (useTarget != null && useTarget != TargetKind.SELF && useEffect == null) {
        fail("item(use_target): needs use_effect")
      }
      if (useLag < 0) fail("item(use_lag): can't be negative")
      if (useEffect != null) {
        checkEffectTarget(
          useEffect,
          useTarget ?: TargetKind.SELF,
          "item(use_effect)",
          "an item use_effect",
          "for use_target = 'item'",
          "with use_target = 'item'",
        )
      }
      val procs = procList(d["procs"], "item(procs)")
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
          value = d.int("value") ?: def.value,
          wear = wear ?: wield,
          slotCost = slotCost,
          dice = d["dice"]?.let { dice(it, d.what("dice")) },
          damageType =
            d.str("damage_type")?.let {
              DamageType(
                checkName(it.lowercase(), schema.damageTypes, "damage type", d.what("damage_type"))
              )
            },
          statDeltas = typed(d["stat_deltas"], TypedKind.STATS, d.what("stat_deltas")),
          senseDeltas = senses(d["sense_deltas"], d.what("sense_deltas"), schema, true),
          maxVitalDeltas =
            typed(d["max_vital_deltas"], TypedKind.VITALS, d.what("max_vital_deltas")),
          resistDeltas =
            typed(d["resist_deltas"], TypedKind.RESISTS, d.what("resist_deltas")).mapKeys { (k, _)
              ->
              DamageType(k)
            },
          tags = parseTags(d),
          stackable = stackable,
          decayPulses = d.int("decay_pulses"),
          container = container,
          commands = commands(d["commands"], d.what("commands"), pkg),
          equipAffects = equipAffects(d["equip_affects"], d.what("equip_affects")),
          affects = affects,
          variants = variants,
          modifiers = modifiers(d["modifiers"], d.what("modifiers"), features),
          procs = procs,
          version = version(d),
          plural = plural,
          charges = charges,
          empty = emptyAction,
          useEffect = useEffect,
          useTarget = useTarget ?: TargetKind.SELF,
          useLag = useLag,
        )
      if (proto.weight < 0) fail("item(weight): can't be negative")
      if (proto.value < 0) fail("item(value): must be at least 0")
      if (proto.keywords.isEmpty()) fail("item(keywords): an item needs at least one keyword")
      if (
        proto.wear == null &&
          (proto.equipAffects.isNotEmpty() ||
            proto.modifiers.isNotEmpty() ||
            proto.maxVitalDeltas.isNotEmpty() ||
            proto.resistDeltas.isNotEmpty() ||
            proto.procs.isNotEmpty())
      ) {
        fail(
          "item(): equip_affects, modifiers, max_vital_deltas, resist_deltas and procs apply while the item is worn, so it needs a wear slot"
        )
      }
      if (
        proto.wear == null &&
          proto.affects.any { it.applies == AffectScope.WORN && it.modifiers.isNotEmpty() }
      ) {
        fail("item(): a worn item_affect with modifiers needs a wear slot")
      }
      if (
        proto.wear == null &&
          proto.affects.any { it.applies == AffectScope.WORN && it.procs.isNotEmpty() }
      ) {
        fail("item(): a worn item_affect with procs needs a wear slot")
      }
      ctx.pkg.itemList += proto
      if (d.hooks.isNotEmpty()) ctx.pkg.hookMap[label] = d.hooks
      val hooks = customHooks(d["hooks"], d.what("hooks"))
      if (hooks.isNotEmpty()) ctx.pkg.customHookMap[label] = hooks
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
    need("skill", "skill()")
    return SKILL_SPEC.bind(args, kwargs, features = features) { d ->
      val id = declName("skill", d, ctx)
      val pkg = ctx.pkg.path
      val label = Label("//$pkg:$id")
      val cost = typed(d["cost"], TypedKind.VITALS, d.what("cost"))
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
      d.choice("range")?.let { r ->
        if (Feature.TRACK !in features && r in setOf("melee", "near", "far"))
          fail(FeatureOwnership.refusal("skill(range) '$r'", Feature.TRACK))
      }
      val proto =
        SkillProto(
          label = label,
          kind = d.str("kind") ?: def.kind,
          cost = cost,
          warmup = d.int("warmup") ?: 0,
          lag = d.int("lag") ?: 0,
          range = d.choice("range")?.let { SkillRange.valueOf(it.uppercase()) } ?: def.range,
          target = d.choice("target")?.let { TargetKind.valueOf(it.uppercase()) } ?: def.target,
          channels = channels(d["channels"], d.what("channels"), schema),
          interrupt = interrupt,
          attackWhileCasting = d.bool("attack_while_casting") ?: false,
          effect = d["effect"]?.let { effectValue(it, d.what("effect")) },
          resolve = d["resolve"]?.let { ScriptResolve(hookRef(it, d.what("resolve")), pkg) },
          tags = parseTags(d),
        )
      if (proto.warmup < 0 || proto.lag < 0) fail("skill(): warmup and lag can't be negative")
      proto.effect?.let { effectRefs(it, ctx, thread) }
      proto.effect?.let { eff ->
        checkEffectTarget(
          eff,
          proto.target,
          "skill(effect)",
          "an item skill's effect",
          "for item skills",
          "on an item skill",
        )
      }
      ctx.pkg.skillList += proto
      Starlark.NONE
    }
  }

  /**
   * Fails the load if [effect]'s primitives don't fit [target]; [what] prefixes the message,
   * [subject] names the effect in the item-target message, and [appliesOnly] and [noneOnly] end the
   * messages refusing `apply_affect(applies)` and a `None` duration on an actor target.
   */
  private fun checkEffectTarget(
    effect: Effect,
    target: TargetKind,
    what: String,
    subject: String,
    appliesOnly: String,
    noneOnly: String,
  ) {
    val leaves = flattenLeaves(effect)
    if (target == TargetKind.ITEM) {
      for (leaf in leaves) {
        when (leaf) {
          is Effect.ApplyAffect -> {
            if ("stunned" in leaf.flags) fail("$what: item affects can't stun")
          }
          is Effect.Plugin -> Unit
          else ->
            fail(
              "$what: ${primitiveName(leaf)} acts on actors; $subject may use apply_affect and plugin primitives"
            )
        }
      }
    } else {
      for (leaf in leaves) {
        if (leaf is Effect.ApplyAffect) {
          if (leaf.applies != null) fail("$what: apply_affect(applies) is only $appliesOnly")
          if (leaf.duration == null)
            fail("$what: apply_affect(duration) can be None only $noneOnly")
        }
      }
    }
  }

  private fun primitiveName(e: Effect): String =
    when (e) {
      is Effect.Damage -> "damage"
      is Effect.Heal -> "heal"
      is Effect.ApplyAffect -> "apply_affect"
      is Effect.Dispel -> "dispel"
      is Effect.Knockback -> "knockback"
      is Effect.Pull -> "pull"
      is Effect.Stun -> "stun"
      is Effect.Summon -> "summon"
      is Effect.Sequence -> "sequence"
      is Effect.Plugin -> "${e.pluginId}.${e.name}"
    }

  private fun flattenLeaves(e: Effect): List<Effect> =
    when (e) {
      is Effect.Sequence -> e.effects.flatMap(::flattenLeaves)
      else -> listOf(e)
    }

  private fun effectRefs(e: Effect, ctx: LoadCtx, thread: StarlarkThread) {
    when (e) {
      is Effect.Summon -> ctx.ref(e.label, RefKind.MOB, thread)
      is Effect.Sequence -> e.effects.forEach { effectRefs(it, ctx, thread) }
      else -> Unit
    }
  }

  private fun checkProcEffect(e: Effect, what: String, ctx: LoadCtx?, thread: StarlarkThread) {
    for (leaf in flattenLeaves(e)) {
      if (leaf is Effect.Summon) {
        fail("$what: summon can't be used in a proc")
      }
      if (leaf is Effect.ApplyAffect) {
        if (leaf.duration == null || leaf.applies != null) {
          fail("$what: apply_affect in a proc needs a duration and no applies")
        }
      }
    }
    if (ctx != null) {
      effectRefs(e, ctx, thread)
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
    return REGION_SPEC.bind(args, kwargs, features = features) { d ->
      val id = declName("region", d, ctx)
      if (!Regex("[a-z0-9_]+").matches(id)) fail("region(): '$id' is not a valid region name")
      val proto =
        RegionProto(id, ctx.pkg.path, modifiers(d["modifiers"], d.what("modifiers"), features))
      ctx.pkg.regionList += proto
      Starlark.NONE
    }
  }

  // ---- Behaviors --------------------------------------------------------------------------------

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
    need("aggressive", "aggressive()")
    if (!isNone(unlessLevelAbove))
      need("aggressive.unless_level_above", "aggressive(unless_level_above)")
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
    need("assist", "assist()")
    return BehaviorDecl(Assist(actorFilter(allies, "assist(allies)", ctx.pkg.path)))
  }

  @StarlarkMethod(
    name = "flee",
    doc = "Behavior: flees when any listed vital is at or below its percentage of its maximum.",
    parameters =
      [
        Param(
          name = "below",
          named = true,
          doc = "Percentages (0..100) by vital, built with vitals(...).",
        )
      ],
    useStarlarkThread = true,
  )
  fun flee(below: Any, thread: StarlarkThread): Any {
    contentCtx(thread, "flee")
    need("flee", "flee()")
    val pcts = typed(below, TypedKind.VITALS, "flee(below)")
    if (pcts.isEmpty()) fail("flee(below): name at least one vital")
    for ((vital, pct) in pcts) if (pct !in 0..100) fail("flee(below): $vital must be 0..100")
    return BehaviorDecl(Flee(pcts))
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
    return GLOBAL_COMMAND_SPEC.bind(args, kwargs, features = features) { d ->
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
    name = "gmcp_package",
    doc = "Declares a GMCP package this package sends with ctx.gmcp. lib and rules packages only.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun gmcpPackage(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "gmcp_package")
    return GMCP_PACKAGE_SPEC.bind(args, kwargs, features = features) { d ->
      if (!ctx.pkg.kind.privileged)
        fail("gmcp_package(): only lib and rules packages may declare GMCP packages")
      val name = d.reqStr("name", "gmcp_package() name")
      if (!GMCP_PACKAGE_NAME.matches(name))
        fail("gmcp_package(): '$name' needs at least two dot-separated parts, such as Shop.Stock")
      SCRIPT_RESERVED_GMCP_ROOTS.firstOrNull { gmcpWithin(name, it) }
        ?.let { fail("gmcp_package(): '$name' uses the reserved root $it") }
      ENGINE_GMCP_PACKAGES.firstOrNull { gmcpOverlaps(name, it) }
        ?.let { fail("gmcp_package(): '$name' overlaps the engine's $it") }
      ctx.pkg.gmcpList
        .firstOrNull { gmcpOverlaps(name, it.name) }
        ?.let { fail("gmcp_package(): '$name' overlaps '${it.name}', already declared here") }
      ctx.pkg.gmcpList += GmcpPackageDecl(name, d.str("doc") ?: "", ctx.pkg.path)
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
    return STRUCT_SPEC.bind(kwargs = kwargs, features = features) {
      StructValue(LinkedHashMap(kwargs))
    }
  }

  // ---- Effect primitives ------------------------------------------------------------------------

  @StarlarkMethod(
    name = "damage",
    doc = "Deal damage.",
    parameters =
      [
        Param(name = "dice", named = true, doc = "Damage dice such as '2d6+1'."),
        Param(
          name = "type",
          defaultValue = "None",
          named = true,
          doc = "A declared damage type, such as DAMAGE.fire; the unarmed type if omitted.",
        ),
      ],
  )
  fun damage(dice: Any, type: Any): Any =
    effect(
      "damage",
      Effect.Damage(
        dice(dice, "damage(dice)"),
        DamageType(
          if (isNone(type)) schema.unarmedDamageType
          else
            checkName(
              str(type, "damage(type)").lowercase(),
              schema.damageTypes,
              "damage type",
              "damage(type)",
            )
        ),
      ),
    )

  @StarlarkMethod(
    name = "heal",
    doc = "Restore a vital, up to its maximum.",
    parameters =
      [
        Param(name = "dice", named = true, doc = "Amount restored, as dice such as '2d4'."),
        Param(name = "vital", named = true, doc = "The declared vital to restore."),
      ],
  )
  fun heal(dice: Any, vital: Any): Any =
    effect(
      "heal",
      Effect.Heal(
        dice(dice, "heal(dice)"),
        checkName(str(vital, "heal(vital)"), schema.vitalNames, "vital", "heal(vital)"),
      ),
    )

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
        Param(
          name = "duration",
          named = true,
          doc = "Duration in pulses; None makes an item affect permanent.",
        ),
        Param(
          name = "stat_deltas",
          named = true,
          defaultValue = "None",
          doc = "Stat adjustments while the affect lasts, built with stats(...).",
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
          doc = "Top-level function fn(ctx) called when the affect expires or is removed.",
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
        Param(
          name = "sense_deltas",
          named = true,
          defaultValue = "{}",
          doc = "Sense score changes by channel while the affect lasts.",
        ),
        Param(
          name = "max_vital_deltas",
          named = true,
          defaultValue = "None",
          doc = "Vital maximum adjustments while the affect lasts, built with vitals(...).",
        ),
        Param(
          name = "resist_deltas",
          named = true,
          defaultValue = "None",
          doc = "Damage resistance adjustments while the affect lasts, built with resists(...).",
        ),
        Param(
          name = "applies",
          named = true,
          defaultValue = "None",
          doc = "Item affects only: 'worn' or 'carried'.",
        ),
        Param(
          name = "procs",
          named = true,
          defaultValue = "[]",
          doc = "Combat procs that fire during auto-attack swings.",
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
    senseDeltas: Any,
    maxVitalDeltas: Any,
    resistDeltas: Any,
    applies: Any,
    procs: Any,
  ): Any {
    val e =
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
        senseDeltas,
        maxVitalDeltas,
        resistDeltas,
        "apply_affect",
        schema,
        features,
        allowPermanent = true,
        procs = procs,
      )
    val scope =
      if (isNone(applies)) null
      else {
        val s =
          choice(
            str(applies, "apply_affect(applies)"),
            "apply_affect(applies)",
            listOf("worn", "carried"),
          )
        AffectScope.valueOf(s.uppercase())
      }
    return effect("apply_affect", e.copy(applies = scope))
  }

  @StarlarkMethod(
    name = "dispel",
    doc =
      "Remove the target's affects that match every given criterion. Affects flagged " +
        "'intrinsic' are never removed.",
    parameters =
      [
        Param(
          name = "name",
          named = true,
          defaultValue = "None",
          doc = "Affect name to remove; a trailing * matches every name with that prefix.",
        ),
        Param(
          name = "flag",
          named = true,
          defaultValue = "None",
          doc = "A flag, or a list of flags; matches affects carrying any of them.",
        ),
        Param(
          name = "all",
          named = true,
          defaultValue = "False",
          doc = "True to match every affect.",
        ),
        Param(
          name = "count",
          named = true,
          defaultValue = "None",
          doc = "Most affects to remove, oldest first, or None to remove every match.",
        ),
      ],
  )
  fun dispel(name: Any, flag: Any, all: Any, count: Any): Any {
    val n = strOrNull(name, "dispel(name)")
    val flags = if (flag is String) setOf(flag) else strList(flag, "dispel(flag)").toSet()
    val everything = bool(all, "dispel(all)")
    if (n == null && flags.isEmpty() && !everything) fail("dispel: give name, flag or all = True")
    val most = if (isNone(count)) null else positive(count, "dispel(count)")
    return effect("dispel", Effect.Dispel(n, flags, everything, most))
  }

  @StarlarkMethod(
    name = "knockback",
    doc = "Push the target along the track.",
    parameters =
      [Param(name = "steps", named = true, doc = "Track positions to push the target, at least 1.")],
  )
  fun knockback(steps: Any): Any =
    effect("knockback", Effect.Knockback(positive(steps, "knockback(steps)")))

  @StarlarkMethod(
    name = "pull",
    doc = "Draw the target along the track.",
    parameters =
      [Param(name = "steps", named = true, doc = "Track positions to draw the target, at least 1.")],
  )
  fun pull(steps: Any): Any = effect("pull", Effect.Pull(positive(steps, "pull(steps)")))

  @StarlarkMethod(
    name = "stun",
    doc = "Stun the target.",
    parameters =
      [Param(name = "pulses", named = true, doc = "Pulses the target is stunned, at least 1.")],
  )
  fun stun(pulses: Any): Any = effect("stun", Effect.Stun(positive(pulses, "stun(pulses)")))

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
    return effect(
      "summon",
      Effect.Summon(
        label(str(label, "summon(label)"), pkg, "summon(label)"),
        positive(count, "summon(count)"),
        intOrNull(duration, "summon(duration)")?.also {
          if (it < 1) fail("summon(duration): must be at least 1")
        },
      ),
    )
  }

  @StarlarkMethod(
    name = "sequence",
    doc = "Combine several effects.",
    extraPositionals = Param(name = "effects", doc = "Effects applied in order."),
  )
  fun sequence(effects: Tuple): Any =
    effect(
      "sequence",
      Effect.Sequence(effects.mapIndexed { i, e -> effectValue(e, "sequence() argument ${i + 1}") }),
    )

  private fun effect(name: String, effect: Effect): EffectDecl {
    need(name, "$name()")
    return EffectDecl(effect)
  }

  private fun positive(v: Any, what: String): Int =
    int(v, what).also { if (it < 1) fail("$what: must be at least 1") }

  companion object {
    private val ROOM_FLAGS = listOf("always_active", "dead_end_ok")
    private val TRACK_SIZES = TrackSize.entries.map { it.name.lowercase() }
    private val DOOR_STATES = DoorState.entries.map { it.name.lowercase() }
    private val BANDS = Band.entries.map { it.word }
    private val DORMANT_MODES = DormantRecovery.entries.map { it.name.lowercase() }
    private val SKILL_RANGES = SkillRange.entries.map { it.name.lowercase() }
    private val TARGET_KINDS = TargetKind.entries.map { it.name.lowercase() }

    /** Constants available to every package file. */
    fun constants(schema: WorldSchema): Map<String, Any> {
      val dirs = StructValue(Direction.STANDARD.associate { it.name to it.name })
      val modifiers =
        StructValue(ModifierPoint.entries.associate { it.scriptName to it.scriptName })
      val sizes =
        StructValue(TrackSize.entries.associate { it.name.lowercase() to it.name.lowercase() })
      val senses =
        StructValue(
          schema.allChannels.associate { it.name to it.name } +
            mapOf("all" to StarlarkList.immutableCopyOf(schema.allChannels.map { it.name }))
        )
      val doorStates =
        StructValue(DoorState.entries.associate { it.name.lowercase() to it.name.lowercase() })
      val damageTypes = StructValue(schema.damageTypes.associateWith { it })

      return mapOf(
        "DIR" to dirs,
        "MODIFIER" to modifiers,
        "SIZE" to sizes,
        "SENSE" to senses,
        "DOOR" to doorStates,
        "DAMAGE" to damageTypes,
      )
    }

    val CONSTANTS: Map<String, Any> = constants(WorldSchema.EMPTY)

    val CONSTANT_SPECS: List<ConstantSpec> =
      listOf(
        ConstantSpec(
          "DIR",
          "struct",
          Starlark.repr(CONSTANTS.getValue("DIR")),
          "Standard exit directions, such as DIR.north and DIR.west.",
        ),
        ConstantSpec(
          "MODIFIER",
          "struct",
          Starlark.repr(CONSTANTS.getValue("MODIFIER")),
          "Rules modifier points, such as MODIFIER.damage and MODIFIER.to_hit.",
        ),
        ConstantSpec(
          "SIZE",
          "struct",
          Starlark.repr(CONSTANTS.getValue("SIZE")),
          "Track size constants for rooms: SIZE.cramped, SIZE.normal, SIZE.large.",
        ),
        ConstantSpec(
          "SENSE",
          "struct",
          Starlark.repr(CONSTANTS.getValue("SENSE")),
          "Perception senses (SENSE.sight, SENSE.sound, declared senses) and SENSE.all.",
        ),
        ConstantSpec(
          "DOOR",
          "struct",
          Starlark.repr(CONSTANTS.getValue("DOOR")),
          "Door states: DOOR.open, DOOR.closed, DOOR.locked.",
        ),
        ConstantSpec(
          "DAMAGE",
          "struct",
          Starlark.repr(CONSTANTS.getValue("DAMAGE")),
          "A struct of the world's declared damage types, such as DAMAGE.fire; a misspelling fails at that line.",
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
              "reset_world_ticks",
              "int",
              "World ticks (30 s each) between zone resets, or None for no periodic reset.",
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
              "tags",
              "list[str]",
              "Tags for plugin restrictions and scripts.",
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
              "hooks",
              "dict[str, function]",
              "Custom hooks, by name, run with ctx.call_hook.",
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
              "tags",
              "list[str]",
              "Tags the rules read, such as 'soundproof' or 'sealed', to price sensing across the exit.",
              default = "[]",
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
              "tags",
              "list[str]",
              "Tags the rules read, such as 'heavy', 'creaky' or 'transparent', to price sensing and muffling.",
              default = "[]",
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

    val ITEM_AFFECT_SPEC =
      DeclSpec(
        name = "item_affect",
        doc = "An affect an item carries, for item(affects=[...]).",
        positionalError = "item_affect() takes the affect name as its only positional argument",
        params =
          listOf(
            DeclParam(
              "name",
              "str",
              "The affect's name.",
              positional = true,
              keyword = false,
              required = true,
            ),
            DeclParam(
              "applies",
              "str",
              "Where the affect reaches its holder: 'worn' or 'carried'.",
              default = "'worn'",
              allowedValues = listOf("worn", "carried"),
            ),
            DeclParam(
              "stat_deltas",
              "stats",
              "Stat adjustments applied while the affect reaches the holder, built with stats(...).",
              default = "stats()",
            ),
            DeclParam(
              "sense_deltas",
              "dict[str, int]",
              "Sense score changes by channel while the affect reaches the holder.",
              default = "{}",
            ),
            DeclParam(
              "max_vital_deltas",
              "vitals",
              "Vital maximum adjustments applied while the affect reaches the holder, built with vitals(...).",
              default = "vitals()",
            ),
            DeclParam(
              "resist_deltas",
              "resists",
              "Damage resistance adjustments applied while the affect reaches the holder, built with resists(...).",
              default = "resists()",
            ),
            DeclParam(
              "modifiers",
              "dict[str, function]",
              "Rules modifiers active while the affect reaches the holder.",
              default = "{}",
              allowedValues = POINTS,
            ),
            DeclParam(
              "flags",
              "list[str]",
              "Status flags applied to the holder while the affect reaches it.",
              default = "[]",
            ),
            DeclParam(
              "procs",
              "list[proc]",
              "Combat procs that fire during auto-attack swings.",
              default = "[]",
            ),
          ),
      )

    val OUTCOME_SPEC =
      DeclSpec(
        name = "outcome",
        doc = "A weighted outcome in a proc's outcome table.",
        positionalError = "outcome() takes the weight as its only positional argument",
        params =
          listOf(
            DeclParam(
              "weight",
              "float",
              "Probability weight between 0.0 and 1.0.",
              positional = true,
              keyword = false,
              required = true,
            ),
            DeclParam(
              "skill",
              "label",
              "Skill or spell triggered on this outcome.",
              default = "None",
            ),
            DeclParam(
              "effect",
              "effect",
              "Effect primitive applied on this outcome.",
              default = "None",
            ),
            DeclParam(
              "target",
              "str",
              "Target for the outcome: 'enemy', 'self' or 'none'.",
              default = "None",
              allowedValues = listOf("enemy", "self", "none"),
            ),
            DeclParam(
              "message",
              "str",
              "Message sent to the bearer instead of combat.proc.self.",
              default = "None",
            ),
          ),
      )

    val PROC_SPEC =
      DeclSpec(
        name = "proc",
        doc = "A combat proc that fires during auto-attack swings.",
        positionalError = "proc() takes 'on' as its only positional argument",
        params =
          listOf(
            DeclParam(
              "on",
              "str",
              "When the proc fires: 'hit', 'struck' or 'avoid'.",
              positional = true,
              keyword = true,
              required = true,
              allowedValues = listOf("hit", "struck", "avoid"),
            ),
            DeclParam(
              "chance",
              "float",
              "Probability of firing between 0.0 and 1.0.",
              default = "1.0",
            ),
            DeclParam("skill", "label", "Skill or spell triggered by the proc.", default = "None"),
            DeclParam(
              "effect",
              "effect",
              "Effect primitive applied by the proc.",
              default = "None",
            ),
            DeclParam(
              "target",
              "str",
              "Target for the outcome: 'enemy', 'self' or 'none'.",
              default = "None",
              allowedValues = listOf("enemy", "self", "none"),
            ),
            DeclParam(
              "message",
              "str",
              "Message sent to the bearer instead of combat.proc.self.",
              default = "None",
            ),
            DeclParam("outcomes", "list[outcome]", "Weighted table of outcomes.", default = "None"),
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
              "stats",
              "Base stats the mob starts from, built with stats(...).",
              default = "stats()",
            ),
            DeclParam(
              "vitals",
              "vitals",
              "Maximum vitals the mob starts from, built with vitals(...).",
              default = "vitals()",
            ),
            DeclParam(
              "resists",
              "resists",
              "Damage resistance percentage by damage type, built with resists(...).",
              default = "resists()",
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
              "Base sense score by channel, merged over sight and sound at 1; 0 senses nothing on that channel.",
              default = "{}",
            ),
            DeclParam(
              "think",
              "function",
              "Custom AI function fn(ctx) run on each think cycle.",
              default = "None",
            ),
            DeclParam("think_every", "int", "Pulses between AI think cycles.", default = "4"),
            DeclParam(
              "hooks",
              "dict[str, function]",
              "Custom hooks, by name, run with ctx.call_hook.",
              default = "{}",
            ),
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
              "traits",
              "list[str]",
              "Traits defined by the world's plugins.",
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
              "plural",
              "str",
              "Display name for stacks of 2 or more, such as 'gold coins'.",
              default = "None",
            ),
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
            DeclParam("value", "int", "Base price of one item; 0 by default.", default = "0"),
            DeclParam(
              "wear",
              "str",
              "Equipment slot or slot family the item is worn in.",
              default = "None",
            ),
            DeclParam("wield", "str", "Alias for wear, for weapons.", default = "None"),
            DeclParam(
              "slot_cost",
              "int",
              "How many slots of its family the item takes up; at least 1.",
              default = "1",
            ),
            DeclParam("dice", "dice", "Weapon damage dice such as '1d6+1'.", default = "None"),
            DeclParam(
              "damage_type",
              "str",
              "Weapon damage type, a declared damage type such as DAMAGE.slash.",
              default = "unarmed_damage_type",
            ),
            DeclParam(
              "stat_deltas",
              "stats",
              "Stat adjustments applied while the item is worn, built with stats(...).",
              default = "stats()",
            ),
            DeclParam(
              "sense_deltas",
              "dict[str, int]",
              "Sense score changes by channel while the item is worn.",
              default = "{}",
            ),
            DeclParam(
              "max_vital_deltas",
              "vitals",
              "Vital maximum adjustments applied while the item is worn, built with vitals(...).",
              default = "vitals()",
            ),
            DeclParam(
              "resist_deltas",
              "resists",
              "Damage resistance adjustments applied while the item is worn, built with resists(...).",
              default = "resists()",
            ),
            DeclParam(
              "tags",
              "list[str]",
              "Tags for plugin restrictions and scripts.",
              default = "[]",
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
              "hooks",
              "dict[str, function]",
              "Custom hooks, by name, run with ctx.call_hook.",
              default = "{}",
            ),
            DeclParam(
              "equip_affects",
              "list[str | dict]",
              "Affects applied when the item is worn: a flag held while worn, or " +
                "{'flag': str, 'pulses': int} for a timed one. 'stunned' must be timed.",
              default = "[]",
            ),
            DeclParam(
              "affects",
              "list[item_affect]",
              "Affects every instance carries, built with item_affect(...).",
              default = "[]",
            ),
            DeclParam(
              "variants",
              "dict[str, str]",
              "Related item prototypes by free-form name, for transform.",
              default = "{}",
            ),
            DeclParam(
              "charges",
              "int",
              "Uses before the item is spent, at least 1; None for unlimited.",
              default = "None",
            ),
            DeclParam(
              "empty",
              "str",
              "After the last charge: 'destroy', 'keep', or the name of one of the item's variants.",
              default = "'destroy'",
            ),
            DeclParam(
              "use_effect",
              "effect",
              "Effect primitive that use applies, with the user as caster.",
              default = "None",
            ),
            DeclParam(
              "use_target",
              "str",
              "Whom use_effect acts on: 'enemy', 'ally', 'self', 'item' or 'none'.",
              default = "'self'",
              allowedValues = TARGET_KINDS,
            ),
            DeclParam(
              "use_lag",
              "int",
              "Recovery lag in pulses after a use; at least 0.",
              default = "0",
            ),
            DeclParam(
              "modifiers",
              "dict[str, function]",
              "Rules modifiers active while the item is worn.",
              default = "{}",
              allowedValues = POINTS,
            ),
            DeclParam(
              "procs",
              "list[proc]",
              "Combat procs that fire during auto-attack swings.",
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
              "vitals",
              "Vitals spent on cast, built with vitals(...).",
              default = "vitals()",
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
            DeclParam(
              "tags",
              "list[str]",
              "Tags for plugin restrictions and scripts.",
              default = "[]",
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

    val GMCP_PACKAGE_SPEC =
      DeclSpec(
        name = "gmcp_package",
        doc =
          "Declares a GMCP package this package sends with ctx.gmcp. lib and rules packages only.",
        positionalError = "gmcp_package() takes the package name as its only positional argument",
        params =
          listOf(
            DeclParam(
              "name",
              "str",
              "The GMCP package name, two or more dot-separated parts.",
              positional = true,
              keyword = false,
              required = true,
            ),
            DeclParam("doc", "str", "Shown in the docs.", default = "\"\""),
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
        ITEM_AFFECT_SPEC,
        PROC_SPEC,
        OUTCOME_SPEC,
        MOB_SPEC,
        ITEM_SPEC,
        SKILL_SPEC,
        REGION_SPEC,
        GMCP_PACKAGE_SPEC,
        GLOBAL_COMMAND_SPEC,
        STRUCT_SPEC,
      ) + SCHEMA_SPECS
  }
}

internal fun procList(v: Any?, what: String): List<Proc> {
  if (v == null || isNone(v)) return emptyList()
  val list = v as? Sequence<*> ?: fail("$what: expected list, got ${typeName(v)}")
  return list.mapIndexed { i, elem ->
    (elem as? ProcDecl)?.proc ?: fail("$what: element ${i + 1} must be a proc(...) value")
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
  senseDeltas: Any,
  maxVitalDeltas: Any,
  resistDeltas: Any,
  fn: String,
  schema: WorldSchema,
  features: Features = Features.ALL,
  allowPermanent: Boolean = false,
  procs: Any = Tuple.empty(),
  procsSymbol: String = "$fn.procs",
): Effect.ApplyAffect {
  val d =
    if (allowPermanent && isNone(duration)) null
    else {
      val v = int(duration, "$fn(duration)")
      if (v < 1) fail("$fn(duration): must be at least 1")
      v
    }
  val every = intOrNull(tickEvery, "$fn(tick_every)")
  if (every != null && every < 1) fail("$fn(tick_every): must be at least 1")
  val procList = procList(procs, "$fn(procs)")
  if (procList.isNotEmpty()) {
    FeatureOwnership.refused(procsSymbol, "$fn(procs)", features)?.let { fail(it) }
  }
  return Effect.ApplyAffect(
    name = str(name, "$fn(name)"),
    duration = d,
    statDeltas = typed(statDeltas, TypedKind.STATS, "$fn(stat_deltas)"),
    flags = strList(flags, "$fn(flags)").toSet(),
    tickEvery = every,
    onTick = hookRefOrNull(onTick, "$fn(on_tick)"),
    onExpire = hookRefOrNull(onExpire, "$fn(on_expire)"),
    expireMessage = str(expireMsg, "$fn(expire_msg)"),
    modifiers = modifiers(mods, "$fn(modifiers)", features),
    senseDeltas = senses(senseDeltas, "$fn(sense_deltas)", schema, allowNegative = true),
    maxVitalDeltas = typed(maxVitalDeltas, TypedKind.VITALS, "$fn(max_vital_deltas)"),
    resistDeltas =
      typed(resistDeltas, TypedKind.RESISTS, "$fn(resist_deltas)").mapKeys { (k, _) ->
        DamageType(k)
      },
    procs = procList,
  )
}

/** `package(api_version=...)` names a version this engine doesn't support. */
internal class ApiVersionMismatch(val declared: Int) :
  RuntimeException(
    "package declares api_version $declared, but this engine supports only api_version ${EngineInfo.API_VERSION} " +
      "(${if (declared > EngineInfo.API_VERSION) "the package is newer than the engine" else "the package is ${EngineInfo.API_VERSION - declared} version(s) behind"}). " +
      "See CHANGELOG.md#api_version-${maxOf(declared, EngineInfo.API_VERSION)}."
  )
