package gups.pluginapi.host

import com.google.common.collect.ImmutableCollection
import com.google.common.collect.ImmutableList
import gups.pluginapi.PluginEntity
import gups.pluginapi.PluginParam
import gups.pluginapi.PluginWorld
import gups.script.pluginEffect
import gups.script.scriptWorld
import net.starlark.java.eval.Dict
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Printer
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkCallable
import net.starlark.java.eval.StarlarkFloat
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkList
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue
import net.starlark.java.eval.Structure
import net.starlark.java.eval.Tuple

// The script-facing values of plugins.

/** A plugin's builtins and effect primitives, as the fields of one global named by its id. */
class PluginNamespace(val pluginId: String, private val members: Map<String, Any>) :
  StarlarkValue, Structure {
  override fun getValue(name: String): Any? = members[name]

  override fun getFieldNames(): ImmutableCollection<String> =
    ImmutableList.copyOf(members.keys.sorted())

  override fun getErrorMessageForUnknownField(name: String): String =
    "plugin '$pluginId' has no member '$name'"

  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    printer.append("<plugin $pluginId>")
  }
}

/** Binds Starlark arguments to [params] in declared order. Values stay Starlark values. */
internal fun bind(
  what: String,
  params: List<PluginParam>,
  args: Tuple,
  kwargs: Dict<String, Any>,
): LinkedHashMap<String, Any?> {
  if (args.size > params.size)
    throw Starlark.errorf(
      "%s takes at most %d positional argument(s), got %d",
      what,
      params.size,
      args.size,
    )
  val values = LinkedHashMap<String, Any?>()
  for ((i, a) in args.withIndex()) values[params[i].name] = a
  val names = params.map { it.name }.toSet()
  for ((k, v) in kwargs.entries) {
    if (k !in names) throw Starlark.errorf("%s: unexpected argument '%s'", what, k)
    if (k in values) throw Starlark.errorf("%s: got multiple values for argument '%s'", what, k)
    values[k] = v
  }
  val bound = LinkedHashMap<String, Any?>()
  for (p in params) {
    if (p.name in values) {
      bound[p.name] = values[p.name]
    } else {
      if (p.required) throw Starlark.errorf("%s: '%s' is required", what, p.name)
      bound[p.name] = p.defaultValue
    }
  }
  return bound
}

/**
 * A Starlark value as a plain Kotlin value; script views become facades unless [entities] is false.
 */
internal fun toPlain(v: Any?, what: String, entities: Boolean = true): Any? =
  when (v) {
    null,
    Starlark.NONE -> null
    is String,
    is Boolean -> v
    is StarlarkInt -> v.toLong(what)
    is StarlarkFloat -> v.toDouble()
    is Int -> v.toLong()
    is Float -> v.toDouble()
    is Sequence<*> -> v.map { toPlain(it, what, entities) }
    is Dict<*, *> ->
      v.entries.associate { (k, x) ->
        (k as? String ?: throw Starlark.errorf("%s: dict keys must be strings", what)) to
          toPlain(x, what, entities)
      }
    else -> {
      val facade = Facades.room(v) ?: Facades.actor(v) ?: Facades.item(v)
      when {
        facade == null && v !is StarlarkValue -> v // a plain value from a default
        facade == null ->
          throw Starlark.errorf("%s: can't pass a %s to a plugin", what, Starlark.type(v))
        !entities ->
          throw Starlark.errorf(
            "%s: an effect is part of a frozen prototype, so it can't take a %s",
            what,
            Starlark.type(v),
          )
        else -> facade
      }
    }
  }

/** A plugin's return value as a Starlark value. */
internal fun fromPlain(v: Any?, what: String): Any =
  when (v) {
    null,
    Unit -> Starlark.NONE
    is String,
    is Boolean -> v
    is Int -> StarlarkInt.of(v)
    is Long -> StarlarkInt.of(v)
    is Double -> StarlarkFloat.of(v)
    is List<*> -> StarlarkList.immutableCopyOf(v.map { fromPlain(it, what) })
    is Map<*, *> ->
      Dict.immutableCopyOf(v.entries.associate { (k, x) -> k.toString() to fromPlain(x, what) })
    is PluginEntity ->
      throw Starlark.errorf(
        "%s: plugins can't return entities in this version; return the label",
        what,
      )
    is StarlarkValue -> v
    else -> throw Starlark.errorf("%s: can't return a %s", what, v::class.java.simpleName)
  }

/** A builtin added with the lambda overload of `addBuiltin`. */
internal class PluginBuiltin(
  private val pluginId: String,
  private val name: String,
  val params: List<PluginParam>,
  private val log: (gups.core.world.World) -> (String) -> Unit,
  private val fn: (PluginWorld, List<Any?>, Map<String, Any?>) -> Any?,
) : StarlarkCallable {
  override fun getName(): String = "$pluginId.$name"

  override fun isImmutable(): Boolean = true

  override fun call(thread: StarlarkThread, args: Tuple, kwargs: Dict<String, Any>): Any {
    val what = "$pluginId.$name()"
    val world =
      scriptWorld(thread)
        ?: throw Starlark.errorf(
          "%s can only be called while the world runs, from a hook or command",
          what,
        )
    val bound = bind(what, params, args, kwargs).mapValues { toPlain(it.value, what) }
    val result =
      try {
        fn(WorldFacade(world, pluginId, log(world)), bound.values.toList(), bound)
      } catch (e: EvalException) {
        throw e
      } catch (e: Exception) {
        throw Starlark.errorf("%s failed: %s", what, e.toString())
      }
    return fromPlain(result, what)
  }
}

/** An effect primitive: calling it builds an effect value; the world applies it later. */
internal class PluginEffectPrimitive(
  private val pluginId: String,
  private val name: String,
  val params: List<PluginParam>,
) : StarlarkCallable {
  override fun getName(): String = "$pluginId.$name"

  override fun isImmutable(): Boolean = true

  override fun call(thread: StarlarkThread, args: Tuple, kwargs: Dict<String, Any>): Any {
    val what = "$pluginId.$name()"
    val bound =
      bind(what, params, args, kwargs).mapValues { toPlain(it.value, what, entities = false) }
    return pluginEffect(pluginId, name, bound)
  }
}
