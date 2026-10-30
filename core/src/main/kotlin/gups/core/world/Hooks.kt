package gups.core.world

import gups.core.AttrValue
import gups.core.CASCADE_MAX_DEPTH
import gups.core.HookRef
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.DamageType
import gups.core.model.Direction
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.ModifierPoint
import gups.core.model.Room
import gups.core.model.SkillProto
import gups.core.rules.RulesContext

/** The hook catalog. Only `before_*` hooks can veto. */
enum class HookName(val scriptName: String) {
  ON_LOAD("on_load"),
  ON_SPAWN("on_spawn"),
  ON_RESET("on_reset"),
  ON_WAKE("on_wake"),
  BEFORE_ENTER("before_enter"),
  ON_ENTER("on_enter"),
  BEFORE_EXIT("before_exit"),
  ON_EXIT("on_exit"),
  ON_SAY("on_say"),
  ON_TELL("on_tell"),
  BEFORE_GET("before_get"),
  ON_GET("on_get"),
  BEFORE_DROP("before_drop"),
  ON_DROP("on_drop"),
  BEFORE_USE("before_use"),
  ON_USE("on_use"),
  ON_GIVE("on_give"),
  BEFORE_ATTACK("before_attack"),
  BEFORE_CAST("before_cast"),
  ON_CAST_START("on_cast_start"),
  ON_CAST("on_cast"),
  ON_DAMAGE("on_damage"),
  ON_DEATH("on_death"),
  ON_COMBAT_TICK("on_combat_tick"),
  ON_TICK("on_tick"),
  ON_PERCEIVE("on_perceive"),
  BEFORE_OPEN("before_open"),
  ON_OPEN("on_open"),
  BEFORE_CLOSE("before_close"),
  ON_CLOSE("on_close"),
  BEFORE_LOCK("before_lock"),
  ON_LOCK("on_lock"),
  BEFORE_UNLOCK("before_unlock"),
  ON_UNLOCK("on_unlock");

  val vetoable: Boolean
    get() = scriptName.startsWith("before_")
}

/** Why an affect ended, for its `on_expire`. */
enum class AffectEndReason {
  /** It reached its expiry pulse. */
  EXPIRED,
  /** It was taken off early, by `remove_affect` or `dispel`. */
  REMOVED,
}

/** The event fields of one hook invocation. `ctx.self` is the owner. */
data class HookContext(
  val actor: Actor? = null,
  val target: Actor? = null,
  val item: Item? = null,
  val room: Room? = null,
  val direction: Direction? = null,
  val fromRoom: Room? = null,
  val toRoom: Room? = null,
  val text: String? = null,
  val args: Map<String, AttrValue> = emptyMap(),
  val skill: SkillProto? = null,
  val weapon: Item? = null,
  val amount: Int? = null,
  val type: DamageType? = null,
  /** For `on_damage`: the vital losses that make up [amount]. */
  val losses: Map<String, Int>? = null,
  val affect: Affect? = null,
  /** Why [affect] ended, for its `on_expire`. */
  val affectEndReason: AffectEndReason? = null,
  val elapsedPulses: Long? = null,
  /** The perception event type for `on_perceive`, for example `say` or `enter`. */
  val event: String? = null,
  /** The perceived event's channel name. */
  val channel: String? = null,
  /** The perceived event's initial intensity. */
  val intensity: Int? = null,
  /** The path attenuation between the event's room and the listener. */
  val cost: Int? = null,
  val sourceRoom: Room? = null,
  /** The listening mob's own exit leading toward the source; null for its own room. */
  val viaExit: Direction? = null,
  /** An item target, for `use <item> <item>` and item-target skills. */
  val targetItem: Item? = null,
)

/** How a reload went; [messages] are shown to builders and instructors in game. */
data class ReloadResult(val ok: Boolean, val messages: List<String>)

sealed interface HookOutcome {
  data object Allow : HookOutcome

  data class Deny(val message: String) : HookOutcome
}

/**
 * The script seam, implemented by `gups.script.StarlarkHost`. The engine decides dispatch order;
 * the host runs whatever hook the owner's prototype defines, if any.
 */
interface ScriptHost {
  fun invokeHook(world: World, owner: Entity, hook: HookName, ctx: HookContext): HookOutcome

  /** Runs a timer callback or an affect's `on_tick`/`on_expire`. */
  fun invokeRef(world: World, owner: Entity, ref: HookRef, ctx: HookContext)

  /** True while [owner]'s timer callback [ref] must not run, for example under quarantine. */
  fun isSuspended(owner: Entity, ref: HookRef): Boolean = false

  /** False if [owner] certainly has no [hook], so the engine can skip queuing work for it. */
  fun hasHook(owner: Entity, hook: HookName): Boolean = true

  /**
   * Runs one modifier `fn(ctx, base)` of the rules chain and returns its value: an `Int` for
   * numeric points, a vital map for `move_cost` and `regen`, and for `cast_allowed` null (allow) or
   * the denial message. A failing modifier returns [base]. [source] says where the modifier comes
   * from, for example `region:null_field`.
   */
  fun modify(
    world: World,
    point: ModifierPoint,
    ref: HookRef,
    source: String,
    c: RulesContext,
    base: Any?,
  ): Any? = base

  /**
   * Re-evaluates [packages] and swaps them into [world]. On failure the old version stays live and
   * the result carries the errors.
   */
  fun reload(world: World, packages: List<String>): ReloadResult =
    ReloadResult(
      false,
      listOf("This world has no script host to reload ${packages.joinToString()} with."),
    )

  /**
   * Runs the package's `migrate(old_version, attrs)` for [entity], saved at prototype version
   * [fromVersion]. The host rewrites the package's own attributes in place.
   */
  fun migrate(world: World, entity: Entity, fromVersion: Int) = Unit

  /** Packages whose scripts are disabled, saved in `zone_states`. */
  val quarantinedPackages: Set<String>
    get() = emptySet()

  /**
   * Puts a package in quarantine: on errors, as restored from a checkpoint, or by `@quarantine`.
   */
  fun quarantine(world: World, pkg: String, why: String) = Unit

  /** Lifts a package's quarantine (`@unquarantine`). */
  fun unquarantine(world: World, pkg: String) = Unit

  /** Every loaded package path, for checking `@quarantine` and `@reload` arguments. */
  val packages: Set<String>
    get() = emptySet()

  /** Script steps used so far per package, ever increasing (`gups_script_steps_total`). */
  val stepsByPackage: Map<String, Long>
    get() = emptyMap()

  companion object {
    /** No scripts: every hook allows. */
    val NONE: ScriptHost =
      object : ScriptHost {
        override fun invokeHook(world: World, owner: Entity, hook: HookName, ctx: HookContext) =
          HookOutcome.Allow

        override fun invokeRef(world: World, owner: Entity, ref: HookRef, ctx: HookContext) = Unit
      }
  }
}

/**
 * Fires [hook] in dispatch order: [primary], the acting actor ([HookContext.actor]) for `before_*`
 * hooks, the room, other actors in the room by ascending id, the room's items, then the items
 * carried or equipped by [primary], the acting actor and the other actors in that order, ascending
 * id within each owner. The acting actor observes its own `on_*` events only when it is [primary],
 * but its items always do. Returns the first denial of a `before_*` hook, or null.
 */
internal fun World.dispatch(
  hook: HookName,
  primary: Entity?,
  room: Room?,
  ctx: HookContext,
): String? {
  // The actor may veto its own action, but doesn't hear its own on_* events, which would let a
  // mob answer itself. Its gear still observes them, so a wielded weapon sees its wielder's
  // actions.
  val actor = ctx.actor?.takeIf { hook.vetoable }
  val entities = LinkedHashSet<Entity>()
  val items = LinkedHashSet<Entity>()
  primary?.let(entities::add)
  actor?.let(entities::add)
  if (room != null) {
    entities += room
    val actors = room.actorIndex.values.filter { it !== primary && it !== ctx.actor }
    entities += actors
    items += room.itemIndex.values
    for (holder in listOfNotNull(primary as? Actor, ctx.actor).distinct() + actors) items +=
      (holder.inventoryIndex.values + holder.equipmentIndex.values).sortedBy { it.id }
  }
  val owners = entities + items
  return withCascadeGuard(hook.scriptName) {
    for (owner in owners) {
      val outcome =
        try {
          scripts.invokeHook(this, owner, hook, ctx)
        } catch (e: Exception) {
          // A crashing hook behaves as if it returned None.
          diagnose("script", "${hook.scriptName} on $owner failed: ${e.message}", e)
          HookOutcome.Allow
        }
      if (hook.vetoable && outcome is HookOutcome.Deny) return@withCascadeGuard outcome.message
    }
    null
  }
}

/** Fires [hook] on [owner] alone, for per-entity hooks such as `on_tick`. */
internal fun World.invokeOn(owner: Entity, hook: HookName, ctx: HookContext) {
  withCascadeGuard(hook.scriptName) {
    try {
      scripts.invokeHook(this, owner, hook, ctx)
    } catch (e: Exception) {
      diagnose("script", "${hook.scriptName} on $owner failed: ${e.message}", e)
    }
  }
}

/** Runs the timer or affect callback [ref] on [owner]. */
internal fun World.invokeRef(owner: Entity, ref: HookRef, ctx: HookContext) {
  withCascadeGuard("${ref.file}:${ref.name}") {
    try {
      scripts.invokeRef(this, owner, ref, ctx)
    } catch (e: Exception) {
      diagnose("script", "${ref.file}:${ref.name} on $owner failed: ${e.message}", e)
    }
  }
}

/**
 * Runs [block] one cascade level deeper, or drops it with a `cascade` diagnostic naming [what] and
 * returns null when the cascade is already [CASCADE_MAX_DEPTH] deep. Every script entry point goes
 * through here, so self-only hooks and callbacks can't recurse past the limit.
 */
private inline fun <T> World.withCascadeGuard(what: String, block: () -> T): T? {
  if (cascadeDepth >= CASCADE_MAX_DEPTH) {
    diagnose("cascade", "$what dropped: cascade deeper than $CASCADE_MAX_DEPTH")
    return null
  }
  cascadeDepth++
  try {
    return block()
  } finally {
    cascadeDepth--
  }
}
