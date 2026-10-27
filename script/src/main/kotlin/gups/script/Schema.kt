package gups.script

import com.google.common.collect.ImmutableCollection
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import gups.core.model.WorldSchema
import gups.core.model.didYouMean
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.Printer
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.Structure

// The typed values of a world's schema: numbers keyed by declared vitals, stats or damage types.
// A constructor checks its keywords against the schema, and every API that takes such numbers
// accepts only the constructor's value.

/** What a [TypedMap] is keyed by. */
internal enum class TypedKind(val constructor: String, val noun: String) {
  VITALS("vitals", "vital"),
  STATS("stats", "stat"),
  RESISTS("resists", "damage type");

  fun known(schema: WorldSchema): List<String> =
    when (this) {
      VITALS -> schema.vitalNames
      STATS -> schema.statNames
      RESISTS -> schema.damageTypes
    }
}

/** An immutable record of ints keyed by schema names; reads back like a struct (`cost.mana`). */
internal class TypedMap(
  val kind: TypedKind,
  val values: Map<String, Int>,
  private val known: List<String>,
) : Structure {
  override fun getValue(name: String): Any? =
    when {
      name in values -> sInt(values.getValue(name))
      name in known -> sInt(0)
      else -> null
    }

  override fun getFieldNames(): ImmutableCollection<String> = ImmutableList.copyOf(known)

  override fun getErrorMessageForUnknownField(field: String): String =
    "${kind.constructor} value has no ${kind.noun} '$field'${didYouMean(field, known)}"

  override fun isImmutable(): Boolean = true

  override fun equals(other: Any?): Boolean =
    other is TypedMap && other.kind == kind && other.values == values

  override fun hashCode(): Int = 31 * kind.hashCode() + values.hashCode()

  override fun repr(printer: Printer) {
    printer.append(kind.constructor).append("(")
    values.entries.forEachIndexed { i, (k, v) ->
      if (i > 0) printer.append(", ")
      printer.append(k).append(" = ").append(v.toString())
    }
    printer.append(")")
  }
}

/** The numbers of [v], which must be a [kind] value; a plain dict is refused by name. */
internal fun typed(v: Any?, kind: TypedKind, what: String): Map<String, Int> =
  when {
    isNone(v) -> emptyMap()
    v is TypedMap && v.kind == kind -> v.values
    v is TypedMap ->
      fail("$what: expected ${kind.constructor}(...), got ${v.kind.constructor}(...)")
    v is Dict<*, *> -> fail("$what: use ${kind.constructor}(...) instead of a dict")
    else -> fail("$what: expected ${kind.constructor}(...), got ${typeName(v)}")
  }

/** [name] as one of [known] of [what], or a failure with a suggestion. */
internal fun checkName(
  name: String,
  known: Collection<String>,
  noun: String,
  what: String,
): String {
  if (name !in known) fail("$what: unknown $noun '$name'${didYouMean(name, known)}")
  return name
}

/** Checks that every key of [values] is declared, with a suggestion for a misspelling. */
internal fun checkKeys(
  values: Map<String, Int>,
  kind: TypedKind,
  schema: WorldSchema,
  what: String,
) {
  val known = kind.known(schema)
  for (k in values.keys) checkName(k, known, kind.noun, what)
}

/** `vitals(...)`, `stats(...)`, `resists(...)` and `DAMAGE`, predeclared in every file. */
internal class SchemaBuiltins(private val schema: WorldSchema) {
  private fun build(kind: TypedKind, kwargs: Dict<String, Any>): Any {
    val known = kind.known(schema)
    val out = LinkedHashMap<String, Int>()
    for ((k, v) in kwargs.entries) {
      checkName(k, known, kind.noun, "${kind.constructor}()")
      out[k] = int(v, "${kind.constructor}($k)")
    }
    return TypedMap(kind, out, known)
  }

  @StarlarkMethod(
    name = "vitals",
    doc = "Numbers keyed by the world's declared vitals, such as vitals(hp = 20).",
    extraKeywords = Param(name = "kwargs"),
  )
  fun vitals(kwargs: Dict<String, Any>): Any = build(TypedKind.VITALS, kwargs)

  @StarlarkMethod(
    name = "stats",
    doc = "Numbers keyed by the world's declared stats, such as stats(str = 12).",
    extraKeywords = Param(name = "kwargs"),
  )
  fun stats(kwargs: Dict<String, Any>): Any = build(TypedKind.STATS, kwargs)

  @StarlarkMethod(
    name = "resists",
    doc = "Percentages keyed by the world's declared damage types, such as resists(fire = 25).",
    extraKeywords = Param(name = "kwargs"),
  )
  fun resists(kwargs: Dict<String, Any>): Any = build(TypedKind.RESISTS, kwargs)
}

/** The schema-dependent builtins of every package, test and `@eval` file. */
internal fun schemaGlobals(schema: WorldSchema): Map<String, Any> {
  val b = ImmutableMap.builder<String, Any>()
  Starlark.addMethods(b, SchemaBuiltins(schema))
  return b.build()
}

private fun constructorSpec(kind: TypedKind, doc: String) =
  DeclSpec(name = kind.constructor, doc = doc, params = emptyList(), extraKeywords = true)

/** The documented constructors, which take one keyword per declared name. */
internal val SCHEMA_SPECS: List<DeclSpec> =
  listOf(
    constructorSpec(
      TypedKind.VITALS,
      "Numbers keyed by the world's declared vitals, such as vitals(hp = 20). Used for skill cost, mob vitals, flee(below), and the results of mitigate, move_cost, regen and starting_stats.",
    ),
    constructorSpec(
      TypedKind.STATS,
      "Numbers keyed by the world's declared stats, such as stats(str = 12). Used for stat_deltas, mob stats, and the results of encumbrance and starting_stats.",
    ),
    constructorSpec(
      TypedKind.RESISTS,
      "Percentages keyed by the world's declared damage types, such as resists(fire = 25). Used for mob resists.",
    ),
  )

/** The numbers of a `vitals(...)` value, for callers outside the script module. */
fun vitalsValue(v: Any?, what: String): Map<String, Int> = typed(v, TypedKind.VITALS, what)

/** The numbers of a `stats(...)` value, for callers outside the script module. */
fun statsValue(v: Any?, what: String): Map<String, Int> = typed(v, TypedKind.STATS, what)

/** [name] as a declared vital of [schema], or a failure with a suggestion. */
fun vitalName(name: String, schema: WorldSchema, what: String): String =
  checkName(name, schema.vitalNames, "vital", what)

/** [name] as a declared damage type of [schema], or a failure with a suggestion. */
fun damageTypeName(name: String, schema: WorldSchema, what: String): String =
  checkName(name, schema.damageTypes, "damage type", what)
