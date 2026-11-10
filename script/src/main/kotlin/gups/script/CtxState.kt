package gups.script

import gups.core.AttrValue
import gups.core.model.Entity
import gups.core.world.HookContext
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread

/** Entity attributes and timers. */
abstract class CtxState
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxDoors(frame, owner, hc, extras) {
  /** The stored key for [key] as written by code in the caller's package. */
  internal fun attrKey(key: Any, write: Boolean, what: String, thread: StarlarkThread): String {
    val k = str(key, what)
    val caller = pkgOf(thread)
    val pkg = frame.host.scripts.packages[caller]
    val own = pkg?.namespace ?: caller.substringAfterLast('/')
    val (ns, short) = splitAttrKey(k, own)
    if (!isValidAttrKey(ns, short)) fail("$what: '$k' is not a valid attribute key")
    if (write && !mayWrite(ns, own, pkg?.sharedNamespaces.orEmpty())) {
      val declarer = frame.host.scripts.packages.values.firstOrNull { ns in it.sharedNamespaces }
      fail(
        "$what: code in //$caller can only write its own namespace '$own', not '$ns'" +
          (declarer?.let { " (use the helpers exported by //${it.path})" } ?: "")
      )
    }
    return "$ns:$short"
  }

  @StarlarkMethod(
    name = "set_attr",
    doc = "Stores a value (int, str, bool, list or dict) on an entity. None deletes the key.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(
          name = "key",
          named = true,
          doc = "Attribute key; unprefixed keys are in your package's namespace.",
        ),
        Param(
          name = "value",
          named = true,
          doc = "Value to store (int, str, bool, list or dict), or None to delete the key.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun setAttr(entity: Any, key: Any, value: Any, thread: StarlarkThread): Any =
    verb("set_attr", "$key") {
      val e = entityArg(entity, "set_attr(entity)")
      val k = attrKey(key, write = true, "set_attr(key)", thread)
      val stored = toAttr(value, "set_attr(value)", frame.host.limits)
      if (stored == null) {
        e.customAttributes.remove(k)
      } else {
        val next = LinkedHashMap<String, AttrValue>(e.customAttributes).also { it[k] = stored }
        val size = jsonSize(next)
        if (size > frame.host.limits.maxAttrBytes) {
          limitFail(
            "alloc_limit",
            "set_attr(): ${e.name}'s attributes would be $size bytes, over the ${frame.host.limits.maxAttrBytes / 1024} KB limit",
          )
        }
        e.customAttributes[k] = stored
      }
      e.dirty = true
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "get_attr",
    doc = "Reads a value; your own keys by short name, other packages' by 'namespace:key'.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(
          name = "key",
          named = true,
          doc = "Attribute key; your own by short name, another package's as 'namespace:key'.",
        ),
        Param(
          name = "default",
          defaultValue = "None",
          named = true,
          doc = "Value returned when the key is absent.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun getAttr(entity: Any, key: Any, default: Any, thread: StarlarkThread): Any =
    verb("get_attr", "$key") {
      val e = entityArg(entity, "get_attr(entity)")
      e.customAttributes[attrKey(key, write = false, "get_attr(key)", thread)]?.let {
        fromAttr(it, thread.mutability())
      } ?: default
    }

  @StarlarkMethod(
    name = "del_attr",
    doc = "Deletes an attribute key from an entity.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(
          name = "key",
          named = true,
          doc = "Attribute key; unprefixed keys are in your package's namespace.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun delAttr(entity: Any, key: Any, thread: StarlarkThread): Any =
    verb("del_attr", "$key") {
      val e = entityArg(entity, "del_attr(entity)")
      if (e.customAttributes.remove(attrKey(key, write = true, "del_attr(key)", thread)) != null)
        e.dirty = true
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "after",
    doc =
      "Calls fn(ctx, **args) on this hook's owner after some pulses. fn must be a top-level function; args must be storable.",
    parameters =
      [
        Param(name = "pulses", named = true, doc = "Pulses to wait, at least 1."),
        Param(name = "fn", named = true, doc = "Top-level function fn(ctx, **args) to call."),
      ],
    extraKeywords =
      Param(
        name = "args",
        doc =
          "Keyword arguments passed to fn; values must be storable (int, str, bool, list or dict).",
      ),
  )
  fun after(pulses: Any, fn: Any, args: Dict<String, Any>): Any =
    verb("after") {
      val n = int(pulses, "after(pulses)")
      if (n < 1) fail("after(pulses): must be at least 1")
      val ref = hookRef(fn, "after(fn)")
      val stored =
        args.entries.associate { (k, x) ->
          k to
            (toAttr(x, "after($k)", frame.host.limits)
              ?: fail("after($k): None can't be passed to a timer"))
        }
      sInt(world.after(owner, n, ref, stored))
    }

  @StarlarkMethod(
    name = "cancel",
    doc = "Cancels one of this owner's timers; True if it was pending.",
    parameters = [Param(name = "timer_id", named = true, doc = "Timer id returned by ctx.after.")],
  )
  fun cancel(timerId: Any): Any =
    verb("cancel") { world.cancel(owner, int(timerId, "cancel(timer_id)").toLong()) }
}

private val ATTR_NAME = Regex("[A-Za-z0-9_]+")
private val ATTR_NAMESPACE = Regex("[a-z0-9_]+")

/** Splits `ns:name` into its namespace and name; a bare name belongs to [own]. */
private fun splitAttrKey(key: String, own: String): Pair<String, String> =
  if (':' in key) key.substringBefore(':') to key.substringAfter(':') else own to key

private fun isValidAttrKey(namespace: String, name: String): Boolean =
  ATTR_NAME.matches(name) && ATTR_NAMESPACE.matches(namespace)

private fun mayWrite(namespace: String, own: String, shared: Collection<String>): Boolean =
  namespace == own || namespace in shared
