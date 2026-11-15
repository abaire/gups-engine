package gups.pluginapi

import gups.script.ActorView
import gups.script.Ctx
import gups.script.DoorView
import gups.script.ExitView
import gups.script.GroupView
import gups.script.ItemView
import gups.script.RngView
import gups.script.RoomView
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertTrue
import net.starlark.java.annot.StarlarkMethod

/**
 * Parity between the `ctx` verbs and view fields and the plugin facade. A ctx verb or view field
 * that has no facade counterpart must appear in [EXCEPTIONS] (a principled reason why there is
 * none). A change that adds a ctx verb or view field must add its facade counterpart or record an
 * entry there, or these tests fail.
 */
class CtxParityTest {
  /**
   * A ctx-side Starlark class, the facade types that must cover it, and the key prefix for
   * exceptions.
   */
  private data class Coverage(val prefix: String, val ctx: Class<*>, val facade: List<Class<*>>)

  private val pairs: List<Coverage> =
    listOf(
      Coverage(
        "ctx",
        Ctx::class.java,
        listOf(PluginWorld::class.java, PluginGmcpWorld::class.java),
      ),
      Coverage("actor", ActorView::class.java, listOf(PluginActor::class.java)),
      Coverage("room", RoomView::class.java, listOf(PluginRoom::class.java)),
      Coverage("item", ItemView::class.java, listOf(PluginItem::class.java)),
      Coverage("rng", RngView::class.java, listOf(PluginRng::class.java)),
      Coverage("exit", ExitView::class.java, listOf(PluginExit::class.java)),
      Coverage("door", DoorView::class.java, listOf(PluginDoor::class.java)),
      Coverage("group", GroupView::class.java, listOf(PluginGroup::class.java)),
    )

  /** Permanent exceptions: "<prefix>.<starlark name>" to the reason. */
  private val EXCEPTIONS: Map<String, String> =
    mapOf(
      "ctx.deny" to "only valid as a before_* hook's return value",
      "ctx.here" to "relative to the hook's owner; plugins read room.actors, items and rooms",
      "ctx.find" to "relative to the hook's owner; plugins read room.actors, items and rooms",
      "ctx.grant_item" to "by design: spawnItem and moveItem with an actor destination",
      "ctx.spawn" to "spelled spawnItem and spawnMob",
      "ctx.call_hook" to "ICEBOX.md 'Plugins calling custom hooks'",
      "ctx.after" to "DESIGN_NEEDED 'Plugin timers'",
      "ctx.cancel" to "DESIGN_NEEDED 'Plugin timers'",
      "ctx.act_advance" to "DESIGN_NEEDED 'Track positions on the plugin facade'",
      "ctx.act_retreat" to "DESIGN_NEEDED 'Track positions on the plugin facade'",
      "actor.track_index" to "DESIGN_NEEDED 'Track positions on the plugin facade'",
      "actor.band_of" to "DESIGN_NEEDED 'Track positions on the plugin facade'",
      "room.track_size" to "DESIGN_NEEDED 'Track positions on the plugin facade'",
      "exit.entry_index" to "DESIGN_NEEDED 'Track positions on the plugin facade'",
      "item.range" to "DESIGN_NEEDED 'Track positions on the plugin facade'",
      "ctx.has_feature" to "spelled features",
      "ctx.skill_info" to "spelled skill(label)",
      "ctx.standing_event" to "spelled raiseStandingEvent",
      "actor.attrs" to "spelled getAttr",
      "room.attrs" to "spelled getAttr",
      "item.attrs" to "spelled getAttr",
      "actor.base" to "spelled baseStat, baseMaxVital, baseResist",
      "actor.resists" to "spelled resist(type)",
      "actor.equipped" to "spelled equipment[slot]",
    )

  /**
   * The hook's context fields live here; they are not ctx verbs, so their Starlark methods are
   * dropped.
   */
  private val ctxFields: Class<*> by lazy { Class.forName("gups.script.CtxFields") }

  /**
   * The Starlark names of every method [k] and its superclasses declare, reading each method's
   * StarlarkMethod name; the hook's context fields are dropped.
   */
  private fun starlarkNames(k: Class<*>): Set<String> {
    val names = mutableSetOf<String>()
    var c: Class<*>? = k
    while (c != null && c != Any::class.java) {
      for (m in c.declaredMethods) {
        if (m.declaringClass === ctxFields) continue
        val ann = m.getAnnotation(StarlarkMethod::class.java) ?: continue
        names.add(ann.name)
      }
      c = c.superclass
    }
    return names
  }

  /**
   * The public method names of the facade interfaces, property getters normalised getFoo to foo.
   */
  private fun facadeNames(vararg ifaces: Class<*>): Set<String> {
    val names = mutableSetOf<String>()
    for (i in ifaces) for (m in i.methods) nameOf(m)?.let(names::add)
    return names
  }

  /**
   * A public method's facade name: a property getter getFoo becomes foo, $default bridges are
   * dropped.
   */
  private fun nameOf(m: Method): String? {
    val n = m.name
    if (n.contains('$')) return null
    val getter = m.parameterCount == 0 && n.length > 3 && n.startsWith("get") && n[3].isUpperCase()
    return if (getter) n.substring(3).replaceFirstChar { it.lowercaseChar() } else n
  }

  /** open_door becomes openDoor; a single word is unchanged. */
  private fun camelCase(snak: String): String {
    val parts = snak.split('_')
    return parts.first() +
      parts.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
  }

  @Test
  fun `every ctx verb and view field has a facade counterpart or a recorded exception`() {
    val missing = mutableListOf<String>()
    for (cov in pairs) {
      val facade = facadeNames(*cov.facade.toTypedArray())
      for (name in starlarkNames(cov.ctx)) {
        if (camelCase(name) in facade) continue
        val key = "${cov.prefix}.$name"
        if (key in EXCEPTIONS) continue
        missing += key
      }
    }
    assertTrue(
      missing.isEmpty(),
      "ctx members with no facade counterpart and no recorded exception:\n" +
        missing.sorted().joinToString("\n") +
        "\n(add a facade counterpart or an entry in EXCEPTIONS with a reason)",
    )
  }

  @Test
  fun `no exception names a field that has a counterpart or no longer exists`() {
    val prefixes = pairs.map { it.prefix }.toSet()
    val errors =
      EXCEPTIONS.keys
        .filter { it.substringBefore('.') !in prefixes }
        .mapTo(mutableListOf()) { "$it names no covered ctx class" }
    for (cov in pairs) {
      val facade = facadeNames(*cov.facade.toTypedArray())
      val ctxNames = starlarkNames(cov.ctx)
      for (key in EXCEPTIONS.keys.filter { it.startsWith(cov.prefix + ".") }) {
        val name = key.substring(cov.prefix.length + 1)
        when {
          name !in ctxNames -> errors += "$key no longer exists on the ctx class"
          camelCase(name) in facade -> errors += "$key now has a facade counterpart"
        }
      }
    }
    assertTrue(errors.isEmpty(), errors.joinToString("\n"))
  }
}
