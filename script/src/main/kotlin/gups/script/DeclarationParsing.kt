package gups.script

import gups.core.EngineInfo
import gups.core.HookRef
import gups.core.Label
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.Dice
import gups.core.model.DoorState
import gups.core.model.Effect
import gups.core.model.EquipAffect
import gups.core.model.ModifierPoint
import gups.core.model.Modifiers
import gups.core.model.Proc
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.TAG_REGEX
import gups.core.model.TargetKind
import gups.core.model.UNRECOGNIZED_KEYS
import gups.core.model.WorldSchema
import gups.core.model.didYouMean
import gups.core.model.keywordWords
import gups.core.world.ActorFilter
import gups.core.world.Features
import gups.core.world.ZoneCommand
import net.starlark.java.eval.Dict
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.spelling.SpellChecker

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

  fun factionRef(label: Label, thread: StarlarkThread) {
    val loc = thread.callerLocation
    pkg.factionRefs += FactionRef(label, loc?.file(), loc?.line(), loc?.column())
  }
}

// ---- Parsing helpers
// -----------------------------------------------------------------------------

internal fun loadCtx(thread: StarlarkThread, fn: String): LoadCtx {
  val ctx =
    thread.getThreadLocal(LoadCtx::class.java)
      ?: fail("$fn() can only be called while a package loads, not from a hook")
  ctx.tick()
  return ctx
}

internal fun standingChangeCtx(thread: StarlarkThread, fn: String): StandingChangeMarker =
  thread.getThreadLocal(StandingChangeMarker::class.java)
    ?: fail("$fn() can only be used in rules.standing_change")

/**
 * The package path label patterns in a behavior builtin resolve against: the loading package, or
 * the rules package while `owned_behaviors` runs.
 */
internal fun behaviorCtx(thread: StarlarkThread, fn: String): String {
  if (thread.getThreadLocal(LoadCtx::class.java) != null) return contentCtx(thread, fn).pkg.path
  val marker =
    thread.getThreadLocal(OwnedBehaviorsMarker::class.java)
      ?: fail("$fn() can only be used in a package's .star files or in rules.owned_behaviors")
  return marker.pkg
}

internal fun contentCtx(thread: StarlarkThread, fn: String): LoadCtx {
  val ctx = loadCtx(thread, fn)
  if (ctx.header) fail("$fn() belongs in a package's other .star files, not in package.star")
  if (ctx.file.test) fail("$fn() can't be used in a test file")
  return ctx
}

/** Validates the declaration's single positional name within the package. */
internal fun declName(fn: String, d: ParsedDecl, ctx: LoadCtx): String {
  val name = d.reqStr("id", "$fn() name")
  label(name.let { ":$it" }, ctx.pkg.path, "$fn() name")
  val existing = ctx.pkg.declKinds.getOrPut(name) { mutableSetOf() }
  val conflict =
    if (fn == "faction") {
      "faction" in existing || existing.any { it != "mob" }
    } else if (fn == "mob") {
      "mob" in existing || existing.any { it != "faction" }
    } else {
      existing.isNotEmpty()
    }
  if (conflict) fail("$fn(): '$name' is already declared in //${ctx.pkg.path}")
  existing += fn
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

internal fun dice(v: Any?, what: String): Dice =
  try {
    Dice.parse(str(v, what))
  } catch (e: IllegalArgumentException) {
    fail("$what: ${e.message}")
  }

internal fun band(v: Any?, what: String): Band =
  Band.parse(choice(v, what, Band.entries.map { it.word }))

internal fun doorState(v: Any?, what: String): DoorState =
  DoorState.valueOf(choice(v, what, DoorState.entries.map { it.name.lowercase() }).uppercase())

internal fun displayName(id: String): String = id.replace('_', ' ')

/**
 * Keywords default to the words of the display name minus articles, or else the declaration name.
 */
internal fun defaultKeywords(name: String, id: String): List<String> =
  keywordWords(name).ifEmpty { listOf(id.lowercase()) }

internal fun commands(v: Any?, what: String, pkg: String): Map<String, ZoneCommand> {
  if (isNone(v)) return emptyMap()
  return dict(v, what).entries.associate { (verb, fn) ->
    val word = verb.lowercase()
    if (!Regex("[a-z][a-z0-9_]*").matches(word)) fail("$what: '$verb' is not a valid verb")
    word to ScriptCommand(word, hookRef(fn, "$what[\"$verb\"]"), pkg)
  }
}

/** A `hooks` dict: a custom hook name to a top-level function. */
internal fun customHooks(v: Any?, what: String): Map<String, HookRef> {
  if (isNone(v)) return emptyMap()
  val catalog = HookCatalog.specs.mapTo(HashSet()) { it.hook.scriptName }
  return dict(v, what).entries.associate { (k, fn) ->
    val name = k.lowercase()
    if (!TAG_REGEX.matches(name)) fail("$what: '$name' is not a valid hook name")
    if (name in catalog) fail("$what: '$name' is a catalog hook; pass it as $name=... instead")
    name to hookRef(fn, "$what[\"$k\"]")
  }
}

internal fun version(d: ParsedDecl): Int {
  val v = d.int("version") ?: 1
  if (v < 1) fail("${d.fn}(version): must be at least 1")
  return v
}

internal val POINTS = ModifierPoint.entries.map { it.scriptName }

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
internal fun flags(v: Any?, what: String): Set<String> =
  strList(v, what).onEach { flag(it, what) }.toSet()

internal fun flag(f: String, what: String): String =
  f.also { if (!TAG_REGEX.matches(it)) fail("$what: '$it' is not a valid flag") }

/** A `add_tags` or `remove_tags` list: tags a room gains or loses, validated like a room's tags. */
internal fun affectTagList(v: Any?, what: String): Set<String> =
  strList(v, what)
    .onEach { t -> if (!TAG_REGEX.matches(t)) fail("$what: '$t' is not a valid tag") }
    .toSet()

internal val PLACEHOLDER_REGEX = Regex("""(?<!\{)\{([a-z][a-z0-9_]*)\}""")

private val UNRECOGNIZED_PLACEHOLDERS = setOf("actor", "target")

internal fun unrecognizedLines(d: ParsedDecl): Map<String, String> {
  val v = d["unrecognized"] ?: return emptyMap()
  val what = d.what("unrecognized")
  val m = dict(v, what)
  for (k in m.keys) choice(k, what, UNRECOGNIZED_KEYS)
  return m.mapValues { (k, value) ->
    val at = "skill(unrecognized[\"$k\"])"
    checkUnrecognizedLine(k, str(value, at), at)
  }
}

private fun checkUnrecognizedLine(key: String, text: String, at: String): String {
  if (!isSingleLine(text)) fail("$at: must be a non-empty single line")
  for (name in PLACEHOLDER_REGEX.findAll(text).map { it.groupValues[1] }) {
    if (key == "noun") fail("$at: the noun takes no placeholders")
    if (name !in UNRECOGNIZED_PLACEHOLDERS) fail("$at: unknown placeholder {$name}")
  }
  return text
}

internal fun checkUnrecognizedTargets(proto: SkillProto) {
  val noTarget = proto.target in setOf(TargetKind.SELF, TargetKind.NONE, TargetKind.ROOM)
  for ((k, text) in proto.unrecognized) {
    if (!usesTargetPlaceholder(text)) continue
    val roomRange = k == "room" && proto.range == SkillRange.ROOM
    if (noTarget || roomRange) {
      fail("skill(unrecognized[\"$k\"]): {target} needs a skill with a target")
    }
  }
}

private fun isSingleLine(text: String): Boolean =
  text.isNotEmpty() && text.none { it == '\n' || it == '\r' }

private fun usesTargetPlaceholder(text: String): Boolean =
  PLACEHOLDER_REGEX.findAll(text).any { it.groupValues[1] == "target" }

internal fun parseTags(d: ParsedDecl): Set<String> {
  val tags = d.strList("tags")
  for (t in tags) {
    if (!TAG_REGEX.matches(t)) fail("${d.what("tags")}: '$t' is not a valid tag")
  }
  return tags.toSortedSet()
}

internal fun parseMobTraits(d: ParsedDecl, known: Set<String>): Set<String> {
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
internal fun equipAffects(v: Any?, what: String): List<EquipAffect> =
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
internal fun actorFilter(v: Any?, what: String, pkg: String): ActorFilter {
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

internal fun effectValue(v: Any?, what: String): Effect =
  (v as? EffectDecl)?.effect
    ?: fail("$what: expected an effect such as damage(...), got ${typeName(v)}")

/** Roots script packages may not send under: engine trees the bundled client subscribes to. */
internal val SCRIPT_RESERVED_GMCP_ROOTS = setOf("Core", "Char", "Room", "Comm", "Gups")

internal fun preferArg(v: Any?, what: String): gups.core.AttrValue? {
  if (v == null || isNone(v)) return null
  var count = 0
  fun convert(x: Any?, depth: Int): gups.core.AttrValue {
    count++
    if (count > 64) fail("$what: may contain at most 64 values")
    return when (x) {
      is StarlarkInt -> gups.core.AttrValue.I(x.toLong(what))
      is String -> gups.core.AttrValue.S(x)
      is Boolean -> gups.core.AttrValue.B(x)
      is Sequence<*> -> {
        if (depth >= 4) fail("$what: lists and dicts may nest at most 4 deep")
        gups.core.AttrValue.L(x.map { convert(it, depth + 1) })
      }
      is Dict<*, *> -> {
        if (depth >= 4) fail("$what: lists and dicts may nest at most 4 deep")
        gups.core.AttrValue.M(
          x.entries.associate { (k, item) ->
            val key = k as? String ?: fail("$what: dict keys must be strings, got ${typeName(k)}")
            key to convert(item, depth + 1)
          }
        )
      }
      else -> fail("$what: expected int, str, bool, list or dict, got ${typeName(x)}")
    }
  }
  return convert(v, 0)
}

internal fun procList(v: Any?, what: String): List<Proc> {
  if (v == null || isNone(v)) return emptyList()
  val list = v as? Sequence<*> ?: fail("$what: expected list, got ${typeName(v)}")
  return list.mapIndexed { i, elem ->
    (elem as? ProcDecl)?.proc ?: fail("$what: element ${i + 1} must be a proc(...) value")
  }
}

/** `package(api_version=...)` names a version this engine doesn't support. */
internal class ApiVersionMismatch(val declared: Int) :
  RuntimeException(
    "package declares api_version $declared, but this engine supports only api_version ${EngineInfo.API_VERSION} " +
      "(${if (declared > EngineInfo.API_VERSION) "the package is newer than the engine" else "the package is ${EngineInfo.API_VERSION - declared} version(s) behind"}). " +
      "See CHANGELOG.md#api_version-${maxOf(declared, EngineInfo.API_VERSION)}."
  )

/** Fails with "<name> needs the <feature> feature" if the world has [symbol]'s feature off. */
internal fun requireFeature(features: Features, symbol: String, name: String = symbol) {
  FeatureOwnership.refused(symbol, name, features)?.let { fail(it) }
}

/** Records a declared entity's hooks, the catalog ones and the custom ones, for the package. */
internal fun registerHooks(ctx: LoadCtx, label: Label, d: ParsedDecl) {
  if (d.hooks.isNotEmpty()) ctx.pkg.hookMap[label] = d.hooks
  val custom = customHooks(d["hooks"], d.what("hooks"))
  if (custom.isNotEmpty()) ctx.pkg.customHookMap[label] = custom
}

internal fun positive(v: Any, what: String): Int =
  int(v, what).also { if (it < 1) fail("$what: must be at least 1") }
