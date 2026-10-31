package gups.script

import gups.core.AttrValue
import gups.core.HookRef
import gups.core.Label
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

/** An int or a float, for probabilities such as `chance = 0.25`. */
internal fun number(v: Any?, what: String): Double =
  when (v) {
    is StarlarkInt -> v.toDouble()
    is StarlarkFloat -> v.toDouble()
    else -> fail("$what: expected a number, got ${typeName(v)}")
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

/** Converts a script value for storage. `None` maps to null ("absent"). */
internal fun toAttr(v: Any?, what: String, limits: ScriptLimits): AttrValue? =
  when (v) {
    null,
    Starlark.NONE -> null
    is StarlarkInt -> AttrValue.I(v.toLong(what))
    is String -> AttrValue.S(checkString(v, what, limits))
    is Boolean -> AttrValue.B(v)
    is Sequence<*> -> {
      checkSize(v.size, what, limits)
      AttrValue.L(v.map { toAttr(it, what, limits) ?: fail("$what: lists can't hold None") })
    }
    is Dict<*, *> -> {
      checkSize(v.size, what, limits)
      AttrValue.M(
        v.entries.associate { (k, value) ->
          (k as? String ?: fail("$what: dict keys must be strings, got ${typeName(k)}")) to
            (toAttr(value, what, limits) ?: fail("$what: dicts can't hold None"))
        }
      )
    }
    is StarlarkFloat -> fail("$what: floats can't be stored; use an int (for example, tenths)")
    else -> fail("$what: a ${typeName(v)} can't be stored; use an int, string, bool, list or dict")
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
