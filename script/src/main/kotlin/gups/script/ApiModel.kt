package gups.script

import gups.core.EngineInfo
import gups.core.HookRef
import gups.core.model.ModifierPoint
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.HookName
import gups.core.world.Messages
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Paths
import java.util.jar.JarFile
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.Tuple
import net.starlark.java.spelling.SpellChecker

// Single machine-readable model of the Gups Starlark API.
// Drives declaration argument parsing, completeness checks, `gups docs --json` (`api.json`),
// and the HTML reference and changelog tools.

/** The `api_version` the whole API so far belongs to. Later additions give their own. */
const val FIRST_API_VERSION = 1

/**
 * The `api_version` that added a `@StarlarkBuiltin` type or `@StarlarkMethod`, for `api.json`.
 * Without it, the entry dates from [FIRST_API_VERSION].
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ApiSince(val version: Int)

/** Metadata and validation rules for one parameter of a declaration or settings builtin. */
data class DeclParam(
  val name: String,
  val type: String,
  val doc: String,
  val positional: Boolean = false,
  val keyword: Boolean = true,
  val required: Boolean = false,
  val default: String? = null,
  val allowedValues: List<String> = emptyList(),
  /** The `api_version` that added this parameter. */
  val since: Int = FIRST_API_VERSION,
)

/**
 * Specification of a declaration or settings builtin (`room`, `mob`, `item`, `skill`, `exit`,
 * `door`, `package`, `region`, `spawn`, `loot`, `container`, `global_command`, `struct`, `module`,
 * `server`). Both argument parsing and `api.json` read from this single spec.
 */
data class DeclSpec(
  val name: String,
  val doc: String,
  val params: List<DeclParam>,
  /** The kind of entity this declaration makes, if it accepts `on_*` / `before_*` hook kwargs. */
  val hookOwner: OwnerKind? = null,
  /** True if arbitrary keyword arguments are accepted (for `struct(**fields)`). */
  val extraKeywords: Boolean = false,
  /** Message used when the positional argument count is outside what [params] allows. */
  val positionalError: String? = null,
  val since: Int = FIRST_API_VERSION,
) {
  private val byName: Map<String, DeclParam> = params.associateBy { it.name }
  private val positionalParams: List<DeclParam> = params.filter { it.positional }
  private val minPositional: Int = positionalParams.count { it.required && !it.keyword }
  private val keywordNames: Set<String> =
    params.filter { it.keyword }.mapTo(LinkedHashSet()) { it.name }

  fun param(paramName: String): DeclParam =
    byName[paramName] ?: error("$name has no parameter '$paramName'")

  /**
   * Parses [args] and [kwargs] against this spec and runs [body] on the result. Afterwards, every
   * parameter of the spec must have been read, so a parameter the builtin forgets is an engine
   * error instead of an argument that is silently ignored.
   *
   * Parsing checks positional counts, binds positional and keyword arguments, runs
   * [beforeUnknownCheck] if given (so `package()` can check `api_version` before rejecting newer
   * arguments), validates hook kwargs against [HookCatalog], rejects unknown keyword arguments with
   * `SpellChecker.didYouMean`, and checks required parameters.
   */
  fun <T> bind(
    args: Tuple = Tuple.empty(),
    kwargs: Map<String, Any> = emptyMap(),
    beforeUnknownCheck: ((ParsedDecl) -> Unit)? = null,
    features: Features = Features.ALL,
    body: (ParsedDecl) -> T,
  ): T {
    val parsed = parse(args, kwargs, beforeUnknownCheck, features)
    val result = body(parsed)
    val unread = params.map { it.name }.filterNot(parsed::wasRead)
    check(unread.isEmpty()) {
      "$name() never reads ${unread.joinToString { "'$it'" }}, which its DeclSpec declares"
    }
    return result
  }

  private fun parse(
    args: Tuple,
    kwargs: Map<String, Any>,
    beforeUnknownCheck: ((ParsedDecl) -> Unit)?,
    features: Features,
  ): ParsedDecl {
    if (args.size !in minPositional..positionalParams.size) {
      fail(
        positionalError
          ?: "$name() takes ${positionalParams.size} positional argument(s), got ${args.size}"
      )
    }
    val values = LinkedHashMap<String, Any?>()
    for (i in args.indices) {
      val p = positionalParams[i]
      values[p.name] = args[i]
    }
    for ((k, v) in kwargs) {
      if (k in values) fail("$name(): got multiple values for argument '$k'")
      values[k] = v
    }
    val hooks = LinkedHashMap<HookName, HookRef>()
    val parsed = ParsedDecl(this, values, hooks)
    beforeUnknownCheck?.invoke(parsed)

    for ((key, value) in kwargs) {
      if (hookOwner != null && HookCatalog.looksLikeHook(key)) {
        val hook =
          HookCatalog.byName(key)
            ?: fail(
              "$name(): unknown hook '$key'${SpellChecker.didYouMean(key, HookCatalog.names)}"
            )
        if (hookOwner !in HookCatalog.attach.getValue(hook)) {
          fail("$name(): hook '$key' can't be attached to a ${hookOwner.word}")
        }
        FeatureOwnership.refused("hook:$key", "hook '$key'", features)?.let { fail("$name(): $it") }
        if (!isNone(value)) hooks[hook] = hookRef(value, "$name($key)")
        continue
      }
      if (!extraKeywords && key !in keywordNames) {
        fail("$name(): unexpected argument '$key'${SpellChecker.didYouMean(key, keywordNames)}")
      }
    }
    for (p in params) {
      if (!isNone(values[p.name])) {
        FeatureOwnership.refused("$name.${p.name}", "$name(${p.name})", features)?.let { fail(it) }
      }
      if (p.required && isNone(values[p.name])) {
        if (p.allowedValues.isNotEmpty())
          fail("$name(): ${p.name} is required (${orList(p.allowedValues)})")
        fail("$name(): '${p.name}' is required")
      }
    }
    return parsed
  }

  /** `a, b or c`. */
  private fun orList(words: List<String>): String =
    if (words.size < 2) words.joinToString()
    else words.dropLast(1).joinToString(", ") + " or " + words.last()
}

/** Validated arguments of one declaration call, as [DeclSpec.bind] hands them to the builtin. */
class ParsedDecl
internal constructor(
  val spec: DeclSpec,
  private val values: Map<String, Any?>,
  val hooks: Map<HookName, HookRef>,
) {
  private val read = HashSet<String>()

  val fn: String
    get() = spec.name

  fun what(param: String): String = "$fn($param)"

  internal fun wasRead(param: String): Boolean = param in read

  /** The value of [param], or null if absent or None. Reading marks the parameter as used. */
  operator fun get(param: String): Any? {
    spec.param(param)
    read += param
    return values[param]?.takeUnless(::isNone)
  }

  fun str(param: String): String? = get(param)?.let { gups.script.str(it, what(param)) }

  fun reqStr(param: String, whatStr: String = what(param)): String =
    get(param)?.let { gups.script.str(it, whatStr) } ?: fail("$fn(): '$param' is required")

  fun int(param: String): Int? = get(param)?.let { gups.script.int(it, what(param)) }

  fun bool(param: String): Boolean? = get(param)?.let { gups.script.bool(it, what(param)) }

  fun number(param: String): Double? = get(param)?.let { gups.script.number(it, what(param)) }

  fun choice(param: String): String? {
    val p = spec.param(param)
    return get(param)?.let { gups.script.choice(it, what(param), p.allowedValues) }
  }

  fun strList(param: String): List<String> = gups.script.strList(get(param), what(param))

  fun choiceList(param: String): List<String> {
    val p = spec.param(param)
    return strList(param).map { gups.script.choice(it, what(param), p.allowedValues) }
  }

  fun list(param: String): List<Any> =
    get(param)?.let { gups.script.list(it, what(param)) }.orEmpty()

  fun dict(param: String): Map<String, Any> =
    get(param)?.let { gups.script.dict(it, what(param)) }.orEmpty()

  fun intDict(param: String): Map<String, Int> = gups.script.intDict(get(param), what(param))
}

// ---- API model entries --------------------------------------------------------------------------

/**
 * A predeclared constant namespace in `PACKAGE_GLOBALS` (`DIR`, `MODIFIER`, `SIZE`, `SENSE`,
 * `DOOR`, `DAMAGE`).
 */
data class ConstantSpec(
  val name: String,
  val type: String,
  val value: String,
  val doc: String,
  val since: Int = FIRST_API_VERSION,
)

/** One hook in the hook catalog. */
data class HookSpec(
  val hook: HookName,
  val signature: String,
  val attachTo: List<OwnerKind>,
  /** The `ctx` fields the engine may set for this hook; the others are always None. */
  val ctxFields: List<String>,
  val doc: String,
  val since: Int = FIRST_API_VERSION,
) {
  val name: String
    get() = hook.scriptName

  val vetoable: Boolean
    get() = hook.vetoable
}

/** One parameter of a `@StarlarkMethod` function or verb. */
data class ParamSpec(
  val name: String,
  val positional: Boolean,
  val named: Boolean,
  val default: String? = null,
  val varargs: Boolean = false,
  val kwargs: Boolean = false,
  val doc: String = "",
)

/** A read-only attribute (`structField = true`) on a `@StarlarkBuiltin` type. */
data class FieldSpec(val name: String, val doc: String, val since: Int = FIRST_API_VERSION)

/** A callable method or builtin function annotated with `@StarlarkMethod`. */
data class MethodSpec(
  val name: String,
  val doc: String,
  val params: List<ParamSpec>,
  val since: Int = FIRST_API_VERSION,
)

/** A `@StarlarkBuiltin` object type (`ctx`, views, `rules_ctx`, and harness handles). */
data class TypeSpec(
  val name: String,
  val builtinType: String,
  val doc: String,
  val fields: List<FieldSpec>,
  val methods: List<MethodSpec>,
  val since: Int = FIRST_API_VERSION,
)

/** One function exported by the files of `//rules`. */
data class RulesExportSpec(
  val export: RulesExport,
  val signature: String,
  val returnType: String,
  /** The `rules_ctx` fields set for this call; empty when the function takes no ctx. */
  val ctxFields: List<String>,
  val doc: String,
  val since: Int = FIRST_API_VERSION,
) {
  val name: String
    get() = export.scriptName
}

/**
 * This model without the entries of features that [features] lacks: what a world with those
 * features can use. Used for `gups docs --world`.
 */
fun ScriptApiModel.forFeatures(features: Features): ScriptApiModel {
  fun on(symbol: String) =
    FeatureOwnership.of(symbol).let { it == null || it.satisfiedBy(features) }

  fun decl(d: DeclSpec) =
    if (!on(d.name)) null else d.copy(params = d.params.filter { on("${d.name}.${it.name}") })

  fun method(m: MethodSpec, path: String) =
    if (!on(path)) null else m.copy(params = m.params.filter { on("$path.${it.name}") })

  fun type(t: TypeSpec, path: String) =
    if (!on(path)) null
    else
      t.copy(
        fields = t.fields.filter { on("$path.${it.name}") },
        methods = t.methods.mapNotNull { method(it, "$path.${it.name}") },
      )

  return copy(
    declarations = declarations.mapNotNull(::decl),
    settings = settings.mapNotNull(::decl),
    constants = constants.filter { on(it.name) },
    hooks = hooks.filter { on("hook:${it.name}") },
    ctx = type(ctx, "ctx") ?: ctx,
    views = views.mapNotNull { type(it, "view:${it.name}") },
    effects = effects.mapNotNull { method(it, it.name) },
    behaviors = behaviors.mapNotNull { method(it, it.name) },
    rules =
      RulesApiSpec(
        ctx = type(rules.ctx, rules.ctx.name) ?: rules.ctx,
        exports = rules.exports.filter { on("rules:${it.name}") },
        modifierPoints = rules.modifierPoints.filter { on("modifier:${it.name}") },
      ),
    messages =
      messages.filter { m ->
        m.feature?.let { id -> Feature.byId(id)?.let { it in features } } ?: true
      },
  )
}

/** One entry of the engine message catalog. */
data class MessageSpec(
  val id: String,
  /** The feature that sends the message, or null for the kernel. */
  val feature: String?,
  val placeholders: List<String>,
  val required: List<String>,
  val default: String,
  val since: Int = FIRST_API_VERSION,
)

/** One modifier point in the rules modifier chain. */
data class ModifierPointSpec(
  val point: ModifierPoint,
  val signature: String,
  val valueType: String,
  val ctxFields: List<String>,
  val doc: String,
  val since: Int = FIRST_API_VERSION,
) {
  val name: String
    get() = point.scriptName
}

/** The rules section of the script API model. */
data class RulesApiSpec(
  val ctx: TypeSpec,
  val exports: List<RulesExportSpec>,
  val modifierPoints: List<ModifierPointSpec>,
)

/**
 * The complete, canonical description of the Gups Starlark API. Every list is sorted by `name` so
 * [toJson] is deterministic across runs and platforms.
 */
data class ScriptApiModel(
  val apiVersion: Int,
  val engineVersion: String,
  val declarations: List<DeclSpec>,
  val settings: List<DeclSpec>,
  val constants: List<ConstantSpec>,
  val hooks: List<HookSpec>,
  val ctx: TypeSpec,
  val views: List<TypeSpec>,
  val effects: List<MethodSpec>,
  val behaviors: List<MethodSpec>,
  val rules: RulesApiSpec,
  val harness: List<TypeSpec>,
  /** The engine message catalog, sorted by id. */
  val messages: List<MessageSpec> = emptyList(),
) {
  /** Canonical, sorted, 2-space-indented JSON with `\n` line endings. */
  fun toJson(): String = JsonWriter().apply { writeModel(this@ScriptApiModel) }.toString()

  /** Every entry's `symbol`, the path the changelog uses to name it, in `api.json` order. */
  fun symbols(): List<String> = JsonWriter().apply { writeModel(this@ScriptApiModel) }.symbols
}

// ---- Reflection over @StarlarkBuiltin and @StarlarkMethod --------------------------------------

/**
 * Builds a [TypeSpec] by reflecting over `@StarlarkBuiltin` and `@StarlarkMethod` annotations on
 * [cls] and its superclasses. Fields and methods are sorted canonically by name.
 */
fun describeBuiltin(cls: Class<*>, name: String? = null): TypeSpec {
  val builtin =
    cls.getAnnotation(StarlarkBuiltin::class.java)
      ?: error("${cls.name} is missing @StarlarkBuiltin")
  val fields = LinkedHashMap<String, FieldSpec>()
  val methods = LinkedHashMap<String, MethodSpec>()
  for (m in starlarkMethodsOf(cls)) {
    val annot = m.getAnnotation(StarlarkMethod::class.java)
    if (annot.structField) {
      fields.putIfAbsent(
        annot.name,
        FieldSpec(name = annot.name, doc = annot.doc, since = sinceOf(m)),
      )
    } else {
      methods.putIfAbsent(annot.name, describeMethod(m))
    }
  }
  return TypeSpec(
    name = name ?: builtin.name,
    builtinType = builtin.name,
    doc = builtin.doc,
    fields = fields.values.sortedBy { it.name },
    methods = methods.values.sortedBy { it.name },
    since = cls.getAnnotation(ApiSince::class.java)?.version ?: FIRST_API_VERSION,
  )
}

/** Reads the `@StarlarkMethod`s in [names] from [cls], sorted by name. */
internal fun describeMethods(cls: Class<*>, names: Set<String>): List<MethodSpec> {
  val byName =
    starlarkMethodsOf(cls).associateBy { it.getAnnotation(StarlarkMethod::class.java).name }
  return names.sorted().map { name ->
    describeMethod(byName[name] ?: error("${cls.simpleName} has no @StarlarkMethod named '$name'"))
  }
}

/**
 * The `@StarlarkBuiltin` classes in [anchor]'s package (not its subpackages) that come from the
 * same directory or jar as [anchor], nested classes included. Completeness tests use it so that a
 * new builtin can't be left out of the model.
 */
fun findBuiltinClasses(anchor: Class<*>): List<Class<*>> {
  val pkg = anchor.packageName
  val dir = pkg.replace('.', '/')
  val location = Paths.get(anchor.protectionDomain.codeSource.location.toURI())
  val files =
    if (Files.isDirectory(location)) {
      location.resolve(dir).toFile().listFiles().orEmpty().map { it.name }
    } else {
      JarFile(location.toFile()).use { jar ->
        jar
          .entries()
          .toList()
          .map { it.name }
          .filter { it.startsWith("$dir/") && '/' !in it.removePrefix("$dir/") }
          .map { it.substringAfterLast('/') }
      }
    }
  return files
    .filter { it.endsWith(".class") }
    .sorted()
    .map { Class.forName("$pkg.${it.removeSuffix(".class")}", false, anchor.classLoader) }
    .filter { it.isAnnotationPresent(StarlarkBuiltin::class.java) }
}

private fun starlarkMethodsOf(cls: Class<*>): List<Method> =
  generateSequence(cls) { it.superclass }
    .flatMap { it.declaredMethods.asSequence() }
    .filter { it.isAnnotationPresent(StarlarkMethod::class.java) }
    .sortedBy { it.getAnnotation(StarlarkMethod::class.java).name }
    .toList()

private fun sinceOf(m: Method): Int =
  m.getAnnotation(ApiSince::class.java)?.version ?: FIRST_API_VERSION

private fun describeMethod(m: Method): MethodSpec {
  val annot = m.getAnnotation(StarlarkMethod::class.java)
  val params = mutableListOf<ParamSpec>()
  for (p: Param in annot.parameters) {
    params +=
      ParamSpec(
        name = p.name,
        positional = p.positional,
        named = p.named,
        default = p.defaultValue.takeIf { it.isNotEmpty() },
        doc = p.doc,
      )
  }
  if (annot.extraPositionals.name.isNotEmpty()) {
    params +=
      ParamSpec(
        name = annot.extraPositionals.name,
        positional = true,
        named = false,
        varargs = true,
        doc = annot.extraPositionals.doc,
      )
  }
  if (annot.extraKeywords.name.isNotEmpty()) {
    params +=
      ParamSpec(
        name = annot.extraKeywords.name,
        positional = false,
        named = true,
        kwargs = true,
        doc = annot.extraKeywords.doc,
      )
  }
  return MethodSpec(name = annot.name, doc = annot.doc, params = params, since = sinceOf(m))
}

// ---- Assembling and validating the model --------------------------------------------------------

object ScriptApi {
  /** The `@StarlarkBuiltin` view classes in `:script`. */
  val VIEW_CLASSES: List<Class<*>> =
    listOf(
      ActorView::class.java,
      AffectView::class.java,
      ProtoAffectView::class.java,
      Denial::class.java,
      DoorView::class.java,
      ExitView::class.java,
      ItemView::class.java,
      LocationView::class.java,
      ProposalView::class.java,
      RngView::class.java,
      RoomView::class.java,
      SkillView::class.java,
    )

  /** Every `@StarlarkBuiltin` class in `:script` that the model describes. */
  val BUILTIN_CLASSES: List<Class<*>> = VIEW_CLASSES + listOf(Ctx::class.java, RulesCtx::class.java)

  /**
   * Builds the complete [ScriptApiModel]. [harness] describes the harness objects, which live in
   * `:harness` (`HarnessApi.specs()`); `:script` can't see them itself.
   */
  fun model(harness: List<TypeSpec>): ScriptApiModel =
    ScriptApiModel(
      apiVersion = EngineInfo.API_VERSION,
      engineVersion = EngineInfo.version,
      declarations = DeclarationBuiltins.DECLARATION_SPECS.sortedBy { it.name },
      settings =
        listOf(
            WorldModule.SPEC,
            WorldModule.STAT_SPEC,
            WorldModule.VITAL_SPEC,
            WorldMessages.SPEC,
            ServerConfig.SPEC,
          )
          .sortedBy { it.name },
      constants = DeclarationBuiltins.CONSTANT_SPECS.sortedBy { it.name },
      hooks = HookCatalog.specs.sortedBy { it.name },
      ctx = describeBuiltin(Ctx::class.java),
      views = VIEW_CLASSES.map { describeBuiltin(it) }.sortedBy { it.name },
      effects = describeMethods(DeclarationBuiltins::class.java, DeclarationBuiltins.EFFECT_NAMES),
      behaviors =
        describeMethods(DeclarationBuiltins::class.java, DeclarationBuiltins.BEHAVIOR_NAMES),
      rules =
        RulesApiSpec(
          ctx = describeBuiltin(RulesCtx::class.java),
          exports = RULES_EXPORTS.sortedBy { it.name },
          modifierPoints = MODIFIER_POINTS.sortedBy { it.name },
        ),
      harness = harness.sortedBy { it.name },
      messages =
        Messages.all
          .map { m ->
            MessageSpec(
              m.id,
              m.feature?.id,
              m.placeholders,
              m.placeholders.filter { it in m.required },
              m.default,
            )
          }
          .sortedBy { it.id },
    )

  /**
   * Checks that [model] covers every global in `PACKAGE_GLOBALS`, every `@StarlarkBuiltin` class in
   * [builtinClasses] and all of its `@StarlarkMethod`s, every [HookName], every [ModifierPoint] and
   * every [RulesExport], with non-blank doc strings throughout, including method parameters. Also
   * checks that the ctx fields listed for hooks and rules calls exist, and that symbols are unique.
   * Returns the problems (empty when complete).
   */
  fun validate(model: ScriptApiModel, builtinClasses: Collection<Class<*>>): List<String> {
    val problems = mutableListOf<String>()

    fun requireDoc(path: String, doc: String) {
      if (doc.isBlank()) problems += "$path has a blank doc string"
    }

    fun requireMethodDocs(path: String, m: MethodSpec) {
      requireDoc(path, m.doc)
      for (p in m.params) requireDoc("$path param '${p.name}'", p.doc)
    }

    fun requireFields(path: String, fields: List<String>, type: TypeSpec) {
      val known = type.fields.mapTo(HashSet()) { it.name }
      for (f in fields) if (f !in known)
        problems += "$path lists ctx field '$f', which ${type.builtinType} doesn't have"
    }

    // 1. Every global in PACKAGE_GLOBALS must appear in declarations, effects, behaviors, or
    // constants.
    val documentedGlobals = buildSet {
      model.declarations.forEach { add(it.name) }
      model.effects.forEach { add(it.name) }
      model.behaviors.forEach { add(it.name) }
      model.constants.forEach { add(it.name) }
    }
    for (g in PACKAGE_GLOBALS.keys.sorted()) {
      if (g !in documentedGlobals)
        problems += "PACKAGE_GLOBALS entry '$g' is missing from ScriptApiModel"
    }
    for (g in documentedGlobals.sorted()) {
      if (g !in PACKAGE_GLOBALS) problems += "ScriptApiModel global '$g' is not in PACKAGE_GLOBALS"
    }

    // 2. Declarations and settings specs must have non-blank docs on the declaration and every
    // parameter.
    for (d in model.declarations + model.settings) {
      requireDoc("declaration '${d.name}'", d.doc)
      if (!d.extraKeywords && d.params.isEmpty()) {
        problems += "declaration '${d.name}' has no parameters and extraKeywords=false"
      }
      for (p in d.params) requireDoc("declaration '${d.name}' param '${p.name}'", p.doc)
    }

    // 3. Constants.
    for (c in model.constants) {
      requireDoc("constant '${c.name}'", c.doc)
      val actual = DeclarationBuiltins.CONSTANTS[c.name]
      if (actual == null) {
        problems += "constant '${c.name}' is not in DeclarationBuiltins.CONSTANTS"
      } else if (Starlark.repr(actual) != c.value) {
        problems +=
          "constant '${c.name}' value in model is ${c.value}, but runtime value is ${Starlark.repr(actual)}"
      }
    }

    // 4. Every HookName must appear in model.hooks with a doc, owners, and ctx fields ctx has.
    val hooksByName = model.hooks.associateBy { it.hook }
    for (h in HookName.entries) {
      val spec = hooksByName[h]
      if (spec == null) {
        problems += "HookName.${h.name} ('${h.scriptName}') is missing from model.hooks"
      } else {
        requireDoc("hook '${spec.name}'", spec.doc)
        if (spec.attachTo.isEmpty()) problems += "hook '${spec.name}' has empty attachTo"
        if (spec.ctxFields.isEmpty()) problems += "hook '${spec.name}' has empty ctxFields"
        requireFields("hook '${spec.name}'", spec.ctxFields, model.ctx)
      }
    }

    // 5. Effect primitives and behaviors.
    for (e in model.effects) requireMethodDocs("effect '${e.name}'", e)
    for (b in model.behaviors) requireMethodDocs("behavior '${b.name}'", b)

    // 6. Every RulesExport and ModifierPoint, with ctx fields rules_ctx has.
    val exportsByName = model.rules.exports.associateBy { it.export }
    for (x in RulesExport.entries) {
      val spec = exportsByName[x]
      if (spec == null) {
        problems += "RulesExport.${x.name} ('${x.scriptName}') is missing from model.rules.exports"
      } else {
        requireDoc("rules export '${spec.name}'", spec.doc)
        if (spec.returnType.isBlank())
          problems += "rules export '${spec.name}' has blank returnType"
        requireFields("rules export '${spec.name}'", spec.ctxFields, model.rules.ctx)
      }
    }
    val pointsByName = model.rules.modifierPoints.associateBy { it.point }
    for (mp in ModifierPoint.entries) {
      val spec = pointsByName[mp]
      if (spec == null) {
        problems +=
          "ModifierPoint.${mp.name} ('${mp.scriptName}') is missing from model.rules.modifierPoints"
      } else {
        requireDoc("modifier point '${spec.name}'", spec.doc)
        requireFields("modifier point '${spec.name}'", spec.ctxFields, model.rules.ctx)
      }
    }

    // 7. All @StarlarkBuiltin types in the model and in builtinClasses.
    val allTypes = listOf(model.ctx, model.rules.ctx) + model.views + model.harness
    for (t in allTypes) {
      requireDoc("type '${t.name}' (${t.builtinType})", t.doc)
      for (f in t.fields) requireDoc("type '${t.name}' field '${f.name}'", f.doc)
      for (m in t.methods) requireMethodDocs("type '${t.name}' method '${m.name}'", m)
    }
    val byBuiltinType = allTypes.associateBy { it.builtinType }
    for (cls in builtinClasses) {
      val annot = cls.getAnnotation(StarlarkBuiltin::class.java)
      if (annot == null) {
        problems += "${cls.name} is not annotated with @StarlarkBuiltin"
        continue
      }
      val typeSpec = byBuiltinType[annot.name]
      if (typeSpec == null) {
        problems += "@StarlarkBuiltin '${annot.name}' (${cls.name}) is missing from ScriptApiModel"
        continue
      }
      val documentedMembers =
        (typeSpec.fields.map { it.name } + typeSpec.methods.map { it.name }).toSet()
      for (m in starlarkMethodsOf(cls)) {
        val ma = m.getAnnotation(StarlarkMethod::class.java)
        if (ma.name !in documentedMembers) {
          problems +=
            "@StarlarkMethod '${annot.name}.${ma.name}' on ${cls.simpleName} is missing from ScriptApiModel"
        }
      }
    }

    // 8. Symbols name one entry each.
    for ((symbol, n) in model.symbols().groupingBy { it }.eachCount()) {
      if (n > 1) problems += "symbol '$symbol' names $n entries"
    }

    return problems
  }
}

// ---- Deterministic JSON writer ------------------------------------------------------------------

/**
 * Writes `api.json`. Each entry carries a `symbol`, its path for the changelog: `room`,
 * `room.size`, `damage.dice`, `SENSE`, `hook:on_wake`, `ctx.act_move`, `ctx.apply_affect.name`,
 * `view:room.exits`, `rules:to_hit`, `modifier:to_hit`, `rules_ctx.weapon`, `t.eq`.
 */
private class JsonWriter {
  private val sb = StringBuilder()
  private var indent = 0

  /** The symbols written so far, in order. */
  val symbols = mutableListOf<String>()

  override fun toString(): String = sb.append('\n').toString()

  fun writeModel(m: ScriptApiModel) = obj {
    field("api_version", m.apiVersion)
    field("engine_version", m.engineVersion)
    arrayField("declarations", m.declarations) { writeDecl(it) }
    arrayField("settings", m.settings) { writeDecl(it) }
    arrayField("constants", m.constants) { c ->
      obj {
        symbol(c.name)
        field("name", c.name)
        field("type", c.type)
        field("value", c.value)
        field("doc", c.doc)
        field("since", c.since)
      }
    }
    arrayField("hooks", m.hooks) { h ->
      obj {
        symbol("hook:${h.name}")
        field("name", h.name)
        field("signature", h.signature)
        field("vetoable", h.vetoable)
        strListField("attach_to", h.attachTo.map { it.word })
        strListField("ctx_fields", h.ctxFields)
        field("doc", h.doc)
        field("since", h.since)
      }
    }
    nextField("ctx")
    writeType(m.ctx, "ctx")
    arrayField("views", m.views) { writeType(it, "view:${it.name}") }
    arrayField("effects", m.effects) { writeMethod(it, it.name) }
    arrayField("behaviors", m.behaviors) { writeMethod(it, it.name) }
    objField("rules") { writeRules(m.rules) }
    arrayField("harness", m.harness) { writeType(it, it.name) }
    arrayField("messages", m.messages) { msg ->
      obj {
        symbol("message:${msg.id}")
        field("id", msg.id)
        strListField("placeholders", msg.placeholders)
        strListField("required", msg.required)
        field("default", msg.default)
        field("since", msg.since)
      }
    }
  }

  private fun writeDecl(d: DeclSpec) = obj {
    symbol(d.name)
    field("name", d.name)
    field("doc", d.doc)
    nullableStrField("hook_owner", d.hookOwner?.word)
    field("extra_keywords", d.extraKeywords)
    field("since", d.since)
    arrayField("params", d.params) { p ->
      obj {
        symbol("${d.name}.${p.name}")
        field("name", p.name)
        field("type", p.type)
        field("positional", p.positional)
        field("keyword", p.keyword)
        field("required", p.required)
        nullableStrField("default", p.default)
        strListField("allowed_values", p.allowedValues)
        field("doc", p.doc)
        field("since", p.since)
      }
    }
  }

  private fun writeType(t: TypeSpec, path: String) = obj {
    symbol(path)
    field("name", t.name)
    field("builtin_type", t.builtinType)
    field("doc", t.doc)
    field("since", t.since)
    arrayField("fields", t.fields) { f ->
      obj {
        symbol("$path.${f.name}")
        field("name", f.name)
        field("doc", f.doc)
        field("since", f.since)
      }
    }
    arrayField("methods", t.methods) { writeMethod(it, "$path.${it.name}") }
  }

  private fun writeMethod(m: MethodSpec, path: String) = obj {
    symbol(path)
    field("name", m.name)
    field("doc", m.doc)
    field("since", m.since)
    arrayField("params", m.params) { p ->
      obj {
        symbol("$path.${p.name}")
        field("name", p.name)
        field("positional", p.positional)
        field("named", p.named)
        nullableStrField("default", p.default)
        field("varargs", p.varargs)
        field("kwargs", p.kwargs)
        field("doc", p.doc)
      }
    }
  }

  private fun writeRules(r: RulesApiSpec) {
    nextField("ctx")
    writeType(r.ctx, r.ctx.name)
    arrayField("exports", r.exports) { e ->
      obj {
        symbol("rules:${e.name}")
        field("name", e.name)
        field("signature", e.signature)
        field("return_type", e.returnType)
        strListField("ctx_fields", e.ctxFields)
        field("doc", e.doc)
        field("since", e.since)
      }
    }
    arrayField("modifier_points", r.modifierPoints) { mp ->
      obj {
        symbol("modifier:${mp.name}")
        field("name", mp.name)
        field("signature", mp.signature)
        field("value_type", mp.valueType)
        strListField("ctx_fields", mp.ctxFields)
        field("doc", mp.doc)
        field("since", mp.since)
      }
    }
  }

  private fun symbol(path: String) {
    symbols += path
    field("symbol", path)
    if (path.startsWith("message:")) {
      Messages.find(path.removePrefix("message:"))?.feature?.let { field("feature", it.id) }
    } else {
      FeatureOwnership.of(path)?.let { req ->
        if (req.features.size == 1) {
          field("feature", req.ids.first())
        } else {
          strListField("features", req.ids)
          field("features_mode", if (req.all) "all" else "any")
        }
      }
    }
  }

  private var firstInObjStack = ArrayDeque<Boolean>()

  private fun obj(block: () -> Unit) {
    sb.append("{\n")
    indent += 2
    firstInObjStack.addLast(true)
    block()
    firstInObjStack.removeLast()
    sb.append('\n')
    indent -= 2
    pad()
    sb.append('}')
  }

  private fun nextField(key: String) {
    if (!firstInObjStack.last()) sb.append(",\n")
    firstInObjStack[firstInObjStack.lastIndex] = false
    pad()
    quote(key)
    sb.append(": ")
  }

  private fun field(key: String, value: String) {
    nextField(key)
    quote(value)
  }

  private fun nullableStrField(key: String, value: String?) {
    nextField(key)
    if (value == null) sb.append("null") else quote(value)
  }

  private fun field(key: String, value: Int) {
    nextField(key)
    sb.append(value)
  }

  private fun field(key: String, value: Boolean) {
    nextField(key)
    sb.append(value)
  }

  private fun objField(key: String, block: () -> Unit) {
    nextField(key)
    obj(block)
  }

  private fun strListField(key: String, items: List<String>) {
    nextField(key)
    if (items.isEmpty()) {
      sb.append("[]")
      return
    }
    sb.append('[')
    items.forEachIndexed { i, s ->
      if (i > 0) sb.append(", ")
      quote(s)
    }
    sb.append(']')
  }

  private fun <T> arrayField(key: String, items: List<T>, writeItem: (T) -> Unit) {
    nextField(key)
    if (items.isEmpty()) {
      sb.append("[]")
      return
    }
    sb.append("[\n")
    indent += 2
    items.forEachIndexed { i, item ->
      if (i > 0) sb.append(",\n")
      pad()
      writeItem(item)
    }
    sb.append('\n')
    indent -= 2
    pad()
    sb.append(']')
  }

  private fun pad() {
    repeat(indent) { sb.append(' ') }
  }

  private fun quote(s: String) {
    sb.append('"')
    for (c in s) {
      when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
      }
    }
    sb.append('"')
  }
}
