package gups.script

import gups.core.AttrValue
import gups.core.HookRef
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.StandingHolder
import gups.core.model.StandingSubject
import gups.core.model.didYouMean
import gups.core.world.EntityRef
import net.starlark.java.eval.Dict
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Module
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkFloat
import net.starlark.java.eval.StarlarkFunction
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkList
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.Tuple
import net.starlark.java.spelling.SpellChecker

// Conversions between Starlark values and engine values, and the argument
// checks every builtin shares. Every failure is an EvalException, which the host turns into
// a script error.

internal fun fail(message: String): Nothing = throw EvalException(message)

/** Marks an error raised because a script exceeded a resource limit. */
internal class LimitExceeded(val kind: String, message: String) : RuntimeException(message)

internal fun limitFail(kind: String, message: String): Nothing =
  throw EvalException(message, LimitExceeded(kind, message))

internal fun typeName(v: Any?): String = if (v == null) "None" else Starlark.type(v)

internal fun isNone(v: Any?): Boolean = v == null || v === Starlark.NONE

internal fun str(v: Any?, what: String): String =
  v as? String ?: fail("$what: expected a string, got ${typeName(v)}")

internal fun strOrNull(v: Any?, what: String): String? = if (isNone(v)) null else str(v, what)

internal fun int(v: Any?, what: String): Int =
  when (v) {
    is StarlarkInt -> v.toInt(what)
    else -> fail("$what: expected an int, got ${typeName(v)}")
  }

internal fun intOrNull(v: Any?, what: String): Int? = if (isNone(v)) null else int(v, what)

internal fun long(v: Any?, what: String): Long =
  when (v) {
    is StarlarkInt -> v.toLong(what)
    else -> fail("$what: expected an int, got ${typeName(v)}")
  }

internal fun bool(v: Any?, what: String): Boolean =
  v as? Boolean ?: fail("$what: expected True or False, got ${typeName(v)}")

/** A whole percent from 0 to 100; a float is refused with a hint to write 25 for 0.25. */
internal fun percent(v: Any?, what: String): Int =
  when (v) {
    is StarlarkInt -> {
      val p = v.toInt(what)
      if (p !in 0..100) fail("$what: must be a whole percent from 0 to 100")
      p
    }
    is StarlarkFloat ->
      fail("$what: must be a whole percent from 0 to 100, not a fraction (write 25 for 0.25)")
    else -> fail("$what: must be a whole percent from 0 to 100")
  }

internal fun list(v: Any?, what: String): List<Any> =
  when (v) {
    is Sequence<*> -> v.map { it!! }
    else -> fail("$what: expected a list, got ${typeName(v)}")
  }

internal fun strList(v: Any?, what: String): List<String> =
  if (isNone(v)) emptyList() else list(v, what).map { str(it, "$what item") }

internal fun dict(v: Any?, what: String): Map<String, Any> =
  when (v) {
    is Dict<*, *> -> v.entries.associate { (k, value) -> str(k, "$what key") to value!! }
    else -> fail("$what: expected a dict, got ${typeName(v)}")
  }

internal fun intDict(v: Any?, what: String): Map<String, Int> =
  if (isNone(v)) emptyMap()
  else dict(v, what).mapValues { (k, value) -> int(value, "$what[\"$k\"]") }

/** One of [allowed], case-insensitively; suggests the nearest on a typo. */
internal fun choice(v: Any?, what: String, allowed: Collection<String>): String {
  val s = str(v, what).lowercase()
  if (s in allowed) return s
  fail(
    "$what: '$s' is not one of ${allowed.joinToString(", ")}${SpellChecker.didYouMean(s, allowed)}"
  )
}

/** The package the innermost running Starlark function was defined in. */
internal fun callerFile(thread: StarlarkThread): FileInfo? =
  Module.ofInnermostEnclosingStarlarkFunction(thread)?.clientData as? FileInfo

/** Parses [text] as a label, resolving `:name` against [pkg]. */
internal fun label(text: String, pkg: String?, what: String): Label =
  try {
    Label.parse(text, pkg)
  } catch (e: IllegalArgumentException) {
    fail("$what: ${e.message}")
  }

/**
 * A reference to a top-level function, as hooks, commands and timers need: they are stored as
 * `(file, name)` so they survive reloads and persistence.
 */
internal fun hookRef(v: Any?, what: String): HookRef {
  val fn = v as? StarlarkFunction ?: fail("$what: expected a function, got ${typeName(v)}")
  val file =
    fn.module.clientData as? FileInfo ?: fail("$what: ${fn.name} is not defined in a package file")
  if (file.test) {
    fail(
      "$what: ${fn.name} is defined in a test file; only functions in content files can be called later"
    )
  }
  if (fn.module.getGlobal(fn.name) !== fn) {
    fail(
      "$what: ${fn.name} must be a top-level function (def at the top of a file), not a nested function"
    )
  }
  return HookRef(file.label, fn.name)
}

internal fun hookRefOrNull(v: Any?, what: String): HookRef? =
  if (isNone(v)) null else hookRef(v, what)

// ---- Script-compatible values -------------------------------------------------------------------

/**
 * Bounds one value's conversion: its nesting and its JSON size, charged before descending into each
 * part, so a value that shares one list many times, or contains itself, fails at the cap instead of
 * being expanded.
 */
private class ConversionBudget(
  private val what: String,
  private val limits: ScriptLimits,
  /** What the value is called in the size error, such as "payload". */
  private val noun: String,
  private val maxBytes: Int,
) {
  private var size = 0
  private var depth = 0

  fun charge(bytes: Int) {
    size += bytes
    if (size > maxBytes)
      limitFail(
        "alloc_limit",
        "$what: $noun of at least $size bytes is over the ${maxBytes / 1024} KB limit",
      )
  }

  /** Converts a list or dict of [n] elements with [block], one level deeper. */
  fun <T> collection(n: Int, block: () -> T): T {
    checkSize(n, what, limits)
    if (depth >= limits.maxNesting)
      limitFail("alloc_limit", "$what: lists and dicts may nest at most ${limits.maxNesting} deep")
    charge(2 + (n - 1).coerceAtLeast(0))
    depth++
    try {
      return block()
    } finally {
      depth--
    }
  }
}

/** Converts a script value for storage. `None` maps to null ("absent"). */
internal fun toAttr(v: Any?, what: String, limits: ScriptLimits): AttrValue? =
  toAttr(v, what, limits, ConversionBudget(what, limits, "value", limits.maxAttrBytes))

private fun toAttr(v: Any?, what: String, limits: ScriptLimits, b: ConversionBudget): AttrValue? =
  when (v) {
    null,
    Starlark.NONE -> null
    is StarlarkInt -> AttrValue.I(v.toLong(what)).also { b.charge(it.v.toString().length) }
    is String -> AttrValue.S(checkString(v, what, limits)).also { b.charge(jsonStringSize(v)) }
    is Boolean -> AttrValue.B(v).also { b.charge(if (v) 4 else 5) }
    is Sequence<*> ->
      b.collection(v.size) {
        AttrValue.L(v.map { toAttr(it, what, limits, b) ?: fail("$what: lists can't hold None") })
      }
    is Dict<*, *> ->
      b.collection(v.size) {
        AttrValue.M(
          v.entries.associate { (k, value) ->
            val key = k as? String ?: fail("$what: dict keys must be strings, got ${typeName(k)}")
            b.charge(jsonStringSize(key) + 1)
            key to (toAttr(value, what, limits, b) ?: fail("$what: dicts can't hold None"))
          }
        )
      }
    is StarlarkFloat -> fail("$what: floats can't be stored; use an int (for example, tenths)")
    else -> fail("$what: a ${typeName(v)} can't be stored; use an int, string, bool, list or dict")
  }

/** Largest GMCP payload a script may send, as compact UTF-8 JSON. */
internal const val MAX_GMCP_PAYLOAD_BYTES = 16_384

/**
 * [v] as a plain value (null, Long, String, Boolean, List, Map<String, Any?>). `None` is allowed
 * anywhere. With [views] on, an actor, item or room view becomes an [EntityRef]; otherwise a view
 * is a script error. Only the per-string, per-collection and nesting limits apply.
 */
internal fun toPlain(v: Any?, what: String, limits: ScriptLimits, views: Boolean = false): Any? =
  toPlain(v, what, limits, views, ConversionBudget(what, limits, "payload", Int.MAX_VALUE))

/**
 * [v] as a plain GMCP value (null, Long, String, Boolean, List, Map<String, Any?>). `None` is
 * allowed anywhere. Raises `alloc_limit` when the JSON form is over [MAX_GMCP_PAYLOAD_BYTES].
 */
internal fun toGmcp(v: Any?, what: String, limits: ScriptLimits): Any? =
  toPlain(
    v,
    what,
    limits,
    views = false,
    ConversionBudget(what, limits, "payload", MAX_GMCP_PAYLOAD_BYTES),
  )

private fun toPlain(
  v: Any?,
  what: String,
  limits: ScriptLimits,
  views: Boolean,
  b: ConversionBudget,
): Any? =
  when (v) {
    null,
    Starlark.NONE -> null.also { b.charge(4) }
    is StarlarkInt -> v.toLong(what).also { b.charge(it.toString().length) }
    is String -> checkString(v, what, limits).also { b.charge(gmcpStringSize(it)) }
    is Boolean -> v.also { b.charge(if (it) 4 else 5) }
    is Sequence<*> -> b.collection(v.size) { v.map { toPlain(it, what, limits, views, b) } }
    is Dict<*, *> ->
      b.collection(v.size) {
        v.entries.associate { (k, value) ->
          val key = k as? String ?: fail("$what: dict keys must be strings, got ${typeName(k)}")
          b.charge(gmcpStringSize(key) + 1)
          key to toPlain(value, what, limits, views, b)
        }
      }
    is StarlarkFloat -> fail("$what: floats can't be sent; use an int (for example, tenths)")
    is EntityView ->
      if (views) EntityRef(v.entity.id)
      else
        fail("$what: a ${typeName(v)} can't be sent; use an int, string, bool, list, dict or None")
    else ->
      fail("$what: a ${typeName(v)} can't be sent; use an int, string, bool, list, dict or None")
  }

/** The UTF-8 size of [v]'s compact JSON form. */
internal fun gmcpJsonSize(v: Any?): Int =
  when (v) {
    null -> 4
    is Boolean -> if (v) 4 else 5
    is Long -> v.toString().length
    is String -> gmcpStringSize(v)
    is List<*> -> 2 + v.sumOf { gmcpJsonSize(it) } + (v.size - 1).coerceAtLeast(0)
    is Map<*, *> ->
      2 +
        v.entries.sumOf { gmcpStringSize(it.key as String) + 1 + gmcpJsonSize(it.value) } +
        (v.size - 1).coerceAtLeast(0)
    else -> error("gmcpJsonSize: unexpected value ${typeName(v)}")
  }

/** A string's JSON size, quotes and escapes included. */
private fun gmcpStringSize(s: String): Int =
  2 +
    s.sumOf { c ->
      when {
        c == '"' || c == '\\' -> 2
        c == '\n' || c == '\t' || c == '\r' || c == '\b' || c.code == 0x0C -> 2
        c.code < 0x20 -> 6
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        c.isSurrogate() -> 2
        else -> 3
      }
    }

/** A fresh, mutable Starlark copy of [v] owned by [mu]. */
internal fun fromAttr(v: AttrValue, mu: Mutability?): Any =
  when (v) {
    is AttrValue.I -> StarlarkInt.of(v.v)
    is AttrValue.S -> v.v
    is AttrValue.B -> v.v
    is AttrValue.L ->
      v.v
        .map { fromAttr(it, mu) }
        .let { if (mu == null) StarlarkList.immutableCopyOf(it) else StarlarkList.copyOf(mu, it) }
    is AttrValue.M ->
      v.v
        .mapValues { fromAttr(it.value, mu) }
        .let { if (mu == null) Dict.immutableCopyOf(it) else Dict.copyOf(mu, it) }
  }

/**
 * A fresh Starlark copy of a plain custom hook value, owned by [mu]: null is `None`, an [EntityRef]
 * is the entity's view, or `None` if the entity left the world.
 */
internal fun fromPlain(v: Any?, mu: Mutability?, frame: Frame?): Any =
  when (v) {
    null -> Starlark.NONE
    is Long -> StarlarkInt.of(v)
    is String -> v
    is Boolean -> v
    is List<*> ->
      v.map { fromPlain(it, mu, frame) }
        .let { if (mu == null) StarlarkList.immutableCopyOf(it) else StarlarkList.copyOf(mu, it) }
    is Map<*, *> ->
      v.entries
        .associate { (k, value) ->
          (k as? String ?: error("fromPlain: dict keys must be strings, got ${typeName(k)}")) to
            fromPlain(value, mu, frame)
        }
        .let { if (mu == null) Dict.immutableCopyOf(it) else Dict.copyOf(mu, it) }
    is EntityRef -> if (frame == null) Starlark.NONE else view(frame, frame.world.entity(v.id))
    else -> error("fromPlain: unexpected value ${typeName(v)}")
  }

internal fun checkString(s: String, what: String, limits: ScriptLimits): String {
  if (s.length > limits.maxStringChars)
    limitFail("alloc_limit", "$what: string of ${s.length} chars is over the 1 MB limit")
  return s
}

internal fun checkSize(n: Int, what: String, limits: ScriptLimits) {
  if (n > limits.maxElements) limitFail("alloc_limit", "$what: $n elements is over the 1 MB limit")
}

/** Checks a value handed back to the engine, such as a hook's return value. */
internal fun checkValue(v: Any?, what: String, limits: ScriptLimits) {
  when (v) {
    is String -> checkString(v, what, limits)
    is Sequence<*> -> checkSize(v.size, what, limits)
    is Dict<*, *> -> checkSize(v.size, what, limits)
  }
}

/** Size of the attributes as compact JSON, the way they are persisted. */
internal fun jsonSize(attrs: Map<String, AttrValue>): Int = jsonSize(AttrValue.M(attrs))

private fun jsonSize(v: AttrValue): Int =
  when (v) {
    is AttrValue.I -> v.v.toString().length
    is AttrValue.S -> jsonStringSize(v.v)
    is AttrValue.B -> if (v.v) 4 else 5
    is AttrValue.L -> 2 + v.v.sumOf { jsonSize(it) } + (v.v.size - 1).coerceAtLeast(0)
    is AttrValue.M ->
      2 +
        v.v.entries.sumOf { jsonStringSize(it.key) + 1 + jsonSize(it.value) } +
        (v.v.size - 1).coerceAtLeast(0)
  }

private fun jsonStringSize(s: String): Int =
  2 +
    s.sumOf { c ->
      when {
        c == '"' || c == '\\' -> 2
        c < ' ' -> 6
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        c.isSurrogate() -> 2
        else -> 3
      }
    }

internal fun tupleOf(items: List<Any>): Tuple = Tuple.copyOf(items)

// ---- For the harness ----------------------------------------------------------------------------

/** The package of the innermost running Starlark function, for resolving relative labels. */
fun callerPackage(thread: StarlarkThread): String? = callerFile(thread)?.pkg

/** The package a module belongs to, if it was loaded from a world. */
fun modulePackage(module: Module): String? = (module.clientData as? FileInfo)?.pkg

/** Converts a script value for storage, as `set_attr` does; None is null. */
fun storableValue(v: Any?, what: String): AttrValue? = toAttr(v, what, ScriptLimits())

/** An immutable Starlark value for a stored attribute. */
fun starlarkValue(v: AttrValue): Any = fromAttr(v, null)

/**
 * An immutable Starlark copy of a plain GMCP payload, as the listener would encode it: integral
 * numbers become ints, other numbers floats, collections lists, maps dicts with string keys, and
 * anything else its string form.
 */
fun gmcpValue(v: Any?): Any =
  when (v) {
    null -> Starlark.NONE
    is String -> v
    is Boolean -> v
    is Long -> StarlarkInt.of(v)
    is Int,
    is Short,
    is Byte -> StarlarkInt.of((v as Number).toLong())
    is Number -> StarlarkFloat.of(v.toDouble())
    is Map<*, *> ->
      Dict.immutableCopyOf(v.entries.associate { (k, value) -> k.toString() to gmcpValue(value) })
    is Iterable<*> -> StarlarkList.immutableCopyOf(v.map(::gmcpValue))
    is Array<*> -> StarlarkList.immutableCopyOf(v.map(::gmcpValue))
    else -> v.toString()
  }

internal fun resolveFactionLabel(
  labelStr: String,
  pkg: String?,
  fnName: String,
  paramName: String,
  protos: Prototypes,
): Label {
  val l = label(labelStr, pkg, "$fnName($paramName)")
  if (!protos.isFaction(l)) {
    val known = protos.factions.keys.map { it.v } + protos.mobs.keys.map { it.v }
    fail("$fnName(): unknown faction '$labelStr'${didYouMean(l.v, known)}")
  }
  return l
}

/**
 * A standing holder from a script value: a mob view (its personal opinion) or a faction label.
 * [actorOf] turns a view into its actor, so a caller can refuse actors that left the world.
 */
internal fun toStandingHolder(
  holder: Any,
  pkg: String?,
  fnName: String,
  protos: Prototypes,
  actorOf: (ActorView) -> Actor = { it.actor },
): StandingHolder =
  when (holder) {
    is ActorView -> {
      when (val a = actorOf(holder)) {
        is Mob -> StandingHolder.Personal(a)
        is Player -> fail("$fnName(): a player has no personal standing; name a faction")
      }
    }
    is String -> {
      val l = resolveFactionLabel(holder, pkg, fnName, "holder", protos)
      StandingHolder.Faction(l)
    }
    else -> fail("$fnName(): holder must be a mob or faction label, got ${typeName(holder)}")
  }

/** A standing subject from a script value: an actor view or a faction label. */
internal fun toStandingSubject(
  subject: Any,
  pkg: String?,
  fnName: String,
  protos: Prototypes,
  holder: StandingHolder? = null,
  actorOf: (ActorView) -> Actor = { it.actor },
): StandingSubject =
  when (subject) {
    is ActorView -> StandingSubject.Of(actorOf(subject).id)
    is String -> {
      if (holder is StandingHolder.Personal) {
        fail("$fnName(): a personal standing record cannot have a faction subject")
      }
      val l = resolveFactionLabel(subject, pkg, fnName, "subject", protos)
      StandingSubject.Faction(l)
    }
    else -> fail("$fnName(): subject must be an actor or faction label, got ${typeName(subject)}")
  }
