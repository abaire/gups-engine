package gups.sample.hunger

import gups.pluginapi.GupsPlugin
import gups.pluginapi.PluginActor
import gups.pluginapi.PluginApi
import gups.pluginapi.PluginParam
import gups.pluginapi.PluginRegistry
import gups.pluginapi.PluginWorld

/**
 * The feature-sized sample plugin: a hunger system. It provides the `hunger` feature, refuses a
 * world that can't support it, raises each player's hunger once per world tick with `every`, warns
 * them as it grows, resets it from an `on_death` event listener, and offers `hunger.eat` for item
 * `on_use` hooks and `hunger.dine`, which eats over a timed task. A student plugin: every file is
 * commented to show one extension point.
 */
class HungerPlugin : GupsPlugin {
  override val id = "hunger"
  override val pluginApiVersion = PluginApi.VERSION
  /** Scripts test `ctx.has_feature("hunger")`; other plugins may name it in `requiredFeatures`. */
  override val providedFeatures: Set<String> = setOf("hunger")
  override val doc =
    "Hunger: a feature-sized plugin (provided feature, periodic work, event, builtin)."

  /** A player's hunger climbs from 0 (sated) to 10 (starving); starvation starts at 10. */
  private val STARVING = 10L
  private val HUNGRY = 5L

  override fun register(r: PluginRegistry) {
    // A world check: refuse a world that can't support hunger, rather than silently do nothing.
    if ("hp" !in r.schema.vitals.map { it.name }) r.refuse("needs an 'hp' vital")
    if (r.schema.damageTypes.isEmpty()) r.refuse("needs a damage type")
    val damageType = r.schema.damageTypes.first()

    // Periodic work: raise each simulated player's hunger once per world tick, and warn them as it
    // grows. The callback runs after the world tick's work, in plugin-id order.
    r.every(120, "Raises hunger once per world tick.") { call ->
      for (actor in call.actors) {
        if (!actor.isPlayer) continue
        val hunger = ((call.world.getAttr(actor, "hunger", 0L) as? Long) ?: 0L) + 1
        val next = hunger.coerceAtMost(STARVING)
        call.world.setAttr(actor, "hunger", next)
        when (next) {
          HUNGRY -> call.world.send(actor, "You are getting hungry.")
          STARVING -> {
            call.world.send(actor, "You are starving.")
            call.world.damage(actor, 1, damageType, "hp")
          }
        }
      }
    }

    // An engine event listener: death resets hunger. It runs after the scripts' global on_death
    // hooks; the victim is still in the world here, so clearing the attribute succeeds.
    r.onEvent("on_death", "Death resets hunger.") { call ->
      call.world.setAttr(call.actor, "hunger", null)
    }

    registerEat(r)
    registerDine(r)
  }

  private fun registerEat(r: PluginRegistry) {
    // A Starlark builtin: hunger.eat(actor, amount) lowers hunger and returns the new value. An
    // item's on_use hook calls it to feed the actor.
    r.addBuiltin(
      name = "eat",
      doc = "Lowers an actor's hunger by amount, not below 0; returns the new value.",
      params =
        listOf(
          PluginParam("actor", "actor", "The actor to feed."),
          PluginParam("amount", "int", "How much hunger to lower."),
        ),
      returnType = "int",
    ) { world, pos, _ ->
      val actor =
        pos[0] as? PluginActor
          ?: throw IllegalArgumentException("hunger.eat(actor, amount): expected an actor")
      val amount =
        (pos[1] as? Number)?.toInt()
          ?: throw IllegalArgumentException("hunger.eat(actor, amount): amount must be an int")
      feed(world, actor, amount)
    }
  }

  private fun registerDine(r: PluginRegistry) {
    // A builtin that takes time: hunger.dine(actor, amount, pulses) eats over a timed task, so the
    // actor is busy meanwhile and `stop` cancels the meal. Hunger drops only when the task
    // completes. It returns None once the meal starts, or the refusal ("You're busy.").
    r.addBuiltin(
      name = "dine",
      doc =
        "Eats over pulses as a timed task, then lowers hunger by amount; returns None once" +
          " started, or the refusal message.",
      params =
        listOf(
          PluginParam("actor", "actor", "The actor who eats."),
          PluginParam("amount", "int", "How much hunger the meal lowers."),
          PluginParam("pulses", "int", "How long the meal takes, at least 1."),
        ),
      returnType = "str | None",
    ) { world, pos, _ ->
      val actor =
        pos[0] as? PluginActor
          ?: throw IllegalArgumentException("hunger.dine(actor, amount, pulses): expected an actor")
      val amount = intArg(pos[1], "amount")
      // The engine names the task in its lines: "You stop eating."
      world.startTask(actor, intArg(pos[2], "pulses"), "eating") { done ->
        feed(done.world, done.actor, amount)
        done.world.send(done.actor, "You finish eating.")
      }
    }
  }

  private fun intArg(v: Any?, name: String): Int =
    (v as? Number)?.toInt()
      ?: throw IllegalArgumentException("hunger.dine(actor, amount, pulses): $name must be an int")

  /** Lowers [actor]'s hunger by [amount], not below 0, and returns the new value. */
  private fun feed(world: PluginWorld, actor: PluginActor, amount: Int): Long {
    val hunger = (world.getAttr(actor, "hunger", 0L) as? Long) ?: 0L
    val next = (hunger - amount).coerceAtLeast(0L)
    world.setAttr(actor, "hunger", next)
    return next
  }
}
