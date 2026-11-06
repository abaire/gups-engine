package gups.core.world

import gups.core.AttrValue
import gups.core.CASCADE_MAX_DEPTH
import gups.core.EntityId
import gups.core.HookRef
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.DamageType
import gups.core.model.Direction
import gups.core.model.Entity
import gups.core.model.GroupSnapshot
import gups.core.model.Item
import gups.core.model.ModifierPoint
import gups.core.model.Room
import gups.core.model.SkillProto
import gups.core.rules.RulesContext

/** Catalog of script lifecycle and action hooks. Only `before_*` hooks are vetoable. */
enum class HookName(
  /** Starlark script function name for this hook. */
  val scriptName: String
) {
  /** Invoked when an entity is loaded into the world or during world startup. */
  ON_LOAD("on_load"),

  /** Invoked when a mob is freshly spawned into a room. */
  ON_SPAWN("on_spawn"),

  /** Invoked when a zone reset is executed. */
  ON_RESET("on_reset"),

  /** Invoked when a dormant room wakes back into the active reality bubble. */
  ON_WAKE("on_wake"),

  /** Vetoable hook evaluated before an actor enters a room. */
  BEFORE_ENTER("before_enter"),

  /** Invoked after an actor successfully enters a room. */
  ON_ENTER("on_enter"),

  /** Vetoable hook evaluated before an actor exits a room. */
  BEFORE_EXIT("before_exit"),

  /** Invoked after an actor successfully leaves a room. */
  ON_EXIT("on_exit"),

  /** Invoked when an actor speaks via `say` in the room. */
  ON_SAY("on_say"),

  /** Invoked when an actor receives a direct communication via `tell`. */
  ON_TELL("on_tell"),

  /** Vetoable hook evaluated before an actor takes an item from a room or container. */
  BEFORE_GET("before_get"),

  /** Invoked after an actor takes an item. */
  ON_GET("on_get"),

  /** Vetoable hook evaluated before an actor drops an item onto the ground. */
  BEFORE_DROP("before_drop"),

  /** Invoked after an actor drops an item. */
  ON_DROP("on_drop"),

  /** Vetoable hook evaluated before an item is used. */
  BEFORE_USE("before_use"),

  /** Invoked after an item is used. */
  ON_USE("on_use"),

  /** Invoked after an item is transferred from one actor to another. */
  ON_GIVE("on_give"),

  /** Vetoable hook evaluated before an actor initiates combat attacks against a target. */
  BEFORE_ATTACK("before_attack"),

  /** Vetoable hook evaluated before an actor begins warming up a skill or spell. */
  BEFORE_CAST("before_cast"),

  /** Invoked when skill warmup successfully starts. */
  ON_CAST_START("on_cast_start"),

  /** Invoked when skill warmup completes and the skill resolves. */
  ON_CAST("on_cast"),

  /** Invoked when an actor takes vital damage. */
  ON_DAMAGE("on_damage"),

  /** Invoked when an actor dies. */
  ON_DEATH("on_death"),

  /**
   * Invoked when an actor's level changes up or down, once per level step.
   *
   * [HookContext.actor] is the actor whose level changed, [HookContext.oldLevel] is the level
   * before the change, and [HookContext.newLevel] is the level after the change. It fires after the
   * gains or their unwinding and the gain record, so it sees the new state. A level gained is
   * announced before its hook fires; a loss is announced once, after the last step's hook.
   */
  ON_LEVEL_CHANGE("on_level_change"),

  /**
   * Invoked, as a global hook only, once for each change of an actor's encumbrance level.
   *
   * [HookContext.actor] is the actor, [HookContext.oldEncumbranceLevel] and
   * [HookContext.newEncumbranceLevel] are the levels. It fires after the actor has been told and
   * its fit has been rechecked.
   */
  ON_ENCUMBRANCE_CHANGE("on_encumbrance_change"),

  /**
   * Invoked, as a global hook only, once for each change to a player group.
   *
   * [HookContext.actor] is the player the event names, [HookContext.event] is "join", "leave",
   * "leader" or "disband", and [HookContext.group] is the group after the change (the full group,
   * no longer including the leaver, on a "leave").
   */
  ON_GROUP_CHANGE("on_group_change"),

  /** Invoked on engaged combatants during each combat round tick. */
  ON_COMBAT_TICK("on_combat_tick"),

  /**
   * Invoked when an engaged actor has no opponent left, once for each such actor.
   *
   * [HookContext.actor] is the freed actor, and [HookContext.event] is "flee" after a successful
   * flee, "death" when it was freed by an opponent's death, and "stop" otherwise. A dying actor
   * itself does not receive the hook. The engine clears no ledger here; a world calls
   * `clear_contributions` from it.
   */
  ON_DISENGAGE("on_disengage"),

  /** Invoked periodically on simulated entities. */
  ON_TICK("on_tick"),

  /** Invoked when an actor perceives an environmental or sensory event. */
  ON_PERCEIVE("on_perceive"),

  /** Vetoable hook evaluated before a door or container is opened. */
  BEFORE_OPEN("before_open"),

  /** Invoked after a door or container is opened. */
  ON_OPEN("on_open"),

  /** Vetoable hook evaluated before a door or container is closed. */
  BEFORE_CLOSE("before_close"),

  /** Invoked after a door or container is closed. */
  ON_CLOSE("on_close"),

  /** Vetoable hook evaluated before a door or container is locked. */
  BEFORE_LOCK("before_lock"),

  /** Invoked after a door or container is locked. */
  ON_LOCK("on_lock"),

  /** Vetoable hook evaluated before a door or container is unlocked. */
  BEFORE_UNLOCK("before_unlock"),

  /** Invoked after a door or container is unlocked. */
  ON_UNLOCK("on_unlock");

  /** True if this hook begins with `before_` and can return a denial string to veto the action. */
  val vetoable: Boolean
    get() = scriptName.startsWith("before_")
}

/** Reason recorded when an affect terminates, passed to its `on_expire` callback. */
enum class AffectEndReason {
  /** The affect naturally reached its expiration pulse. */
  EXPIRED,

  /** The affect was removed prematurely by dispelling or script removal. */
  REMOVED,
}

/** Event context parameters passed to a hook invocation. */
data class HookContext(
  /** Acting actor initiating the event, or null. */
  val actor: Actor? = null,
  /** Target actor receiving the action, or null. */
  val target: Actor? = null,
  /** Item being manipulated or used, or null. */
  val item: Item? = null,
  /** Room where the event occurs, or null. */
  val room: Room? = null,
  /** Traversed movement direction, or null. */
  val direction: Direction? = null,
  /** Origin room for movement events, or null. */
  val fromRoom: Room? = null,
  /** Destination room for movement events, or null. */
  val toRoom: Room? = null,
  /** Text payload associated with speech or communication, or null. */
  val text: String? = null,
  /** Arguments passed to scheduled timers or custom verbs. */
  val args: Map<String, AttrValue> = emptyMap(),
  /** Skill prototype being cast or resolved, or null. */
  val skill: SkillProto? = null,
  /** Weapon item used in combat, or null. */
  val weapon: Item? = null,
  /** Numeric amount (e.g. damage dealt), or null. */
  val amount: Int? = null,
  /** Damage type associated with combat damage, or null. */
  val type: DamageType? = null,
  /** Vital losses contributing to damage for `on_damage`. */
  val losses: Map<String, Int>? = null,
  /** The damage event's parts, the hit's type first, for `on_damage`. */
  val parts: List<DamagePart>? = null,
  /** Affect instance triggering a tick or expiration callback. */
  val affect: Affect? = null,
  /** Reason why [affect] ended for its `on_expire` callback. */
  val affectEndReason: AffectEndReason? = null,
  /** Number of pulses elapsed since the last tick or event. */
  val elapsedPulses: Long? = null,
  /** Perception event type (e.g. `say`, `enter`) for `on_perceive`. */
  val event: String? = null,
  /** The group after the change, for `on_group_change`, or null. */
  val group: GroupSnapshot? = null,
  /** Channel name for sensory events. */
  val channel: String? = null,
  /** Sensory signal intensity at the origin. */
  val intensity: Int? = null,
  /** Sensory attenuation cost along the traversal path. */
  val cost: Int? = null,
  /** Room where the sensory event originated. */
  val sourceRoom: Room? = null,
  /** Outgoing exit direction pointing toward the sensory source. */
  val viaExit: Direction? = null,
  /** Secondary item target for multi-item interactions, or null. */
  val targetItem: Item? = null,
  /** Actor's level before a level change for `on_level_change`, or null. */
  val oldLevel: Int? = null,
  /** Actor's level after a level change for `on_level_change`, or null. */
  val newLevel: Int? = null,
  /** The actor's encumbrance level before the change, for `on_encumbrance_change`, or null. */
  val oldEncumbranceLevel: Int? = null,
  /** The actor's encumbrance level after the change, for `on_encumbrance_change`, or null. */
  val newEncumbranceLevel: Int? = null,
)

/** Outcome result of a package reload operation. */
data class ReloadResult(
  /** True if all packages reloaded and passed validation successfully. */
  val ok: Boolean,
  /** Status and diagnostic messages generated during the reload. */
  val messages: List<String>,
)

/** Result of evaluating a vetoable `before_*` hook. */
sealed interface HookOutcome {
  /** Action is permitted to proceed. */
  data object Allow : HookOutcome

  /** Action is denied with user-facing rejection [message]. */
  data class Deny(val message: String) : HookOutcome
}

/** A plain value standing for an entity in custom hook arguments. */
data class EntityRef(val id: EntityId)

/** One plugin handler for a custom hook: (entity, args, result so far) -> new result. */
fun interface CustomHookHandler {
  fun handle(world: World, entity: Entity, args: Map<String, Any?>, result: Any?): Any?
}

/**
 * Script execution engine seam connecting the world loop to Starlark script environments.
 *
 * The engine manages hook dispatch order; the script host evaluates whatever callbacks the
 * prototype declares, enforcing resource step budgets and wall-clock time limits.
 */
interface ScriptHost {
  /**
   * Invokes [hook] on [owner] within event [ctx], returning [HookOutcome.Allow] or
   * [HookOutcome.Deny].
   */
  fun invokeHook(world: World, owner: Entity, hook: HookName, ctx: HookContext): HookOutcome

  /**
   * Runs [owner]'s custom hook [name] with [args] (plain values and [EntityRef]s) and returns its
   * result as plain values, or null when it has none, is quarantined or fails.
   */
  fun invokeCustom(world: World, owner: Entity, name: String, args: Map<String, Any?>): Any? = null

  /** Runs a scheduled timer callback or affect tick/expiration [ref] on [owner]. */
  fun invokeRef(world: World, owner: Entity, ref: HookRef, ctx: HookContext)

  /** True if scheduled timer callback [ref] is suspended (e.g. during package quarantine). */
  fun isSuspended(owner: Entity, ref: HookRef): Boolean = false

  /**
   * Returns false if [owner] definitely declares no implementation of [hook], enabling dispatch
   * optimizations.
   */
  fun hasHook(owner: Entity, hook: HookName): Boolean = true

  /** Runs every global [hook] registered by loaded packages, with [ctx]. */
  fun invokeGlobal(world: World, hook: HookName, ctx: HookContext) = Unit

  /** Evaluates rules modifier [ref] for [point], returning modified value or [base] on error. */
  fun modify(
    world: World,
    point: ModifierPoint,
    ref: HookRef,
    source: String,
    c: RulesContext,
    base: Any?,
  ): Any? = base

  /**
   * Re-evaluates content [packages] and hot-swaps them into [world]. On failure, existing packages
   * remain active and error diagnostics are returned.
   */
  fun reload(world: World, packages: List<String>): ReloadResult =
    ReloadResult(
      false,
      listOf("This world has no script host to reload ${packages.joinToString()} with."),
    )

  /**
   * Runs package migration logic `migrate(old_version, attrs)` for an entity restored from
   * persistence.
   */
  fun migrate(world: World, entity: Entity, fromVersion: Int) = Unit

  /** Set of package paths currently placed under administrative or error quarantine. */
  val quarantinedPackages: Set<String>
    get() = emptySet()

  /** Places [pkg] into quarantine, suppressing its script hooks and timer callbacks. */
  fun quarantine(world: World, pkg: String, why: String) = Unit

  /** Lifts quarantine on [pkg], resuming script execution. */
  fun unquarantine(world: World, pkg: String) = Unit

  /** Set of all loaded package paths currently known to the script host. */
  val packages: Set<String>
    get() = emptySet()

  /** Cumulative Starlark execution steps consumed per package path. */
  val stepsByPackage: Map<String, Long>
    get() = emptyMap()

  companion object {
    /** No-op script host implementation that permits all hooks and executes no scripts. */
    val NONE: ScriptHost =
      object : ScriptHost {
        override fun invokeHook(world: World, owner: Entity, hook: HookName, ctx: HookContext) =
          HookOutcome.Allow

        override fun invokeRef(world: World, owner: Entity, ref: HookRef, ctx: HookContext) = Unit
      }
  }
}

/**
 * Fires [hook] across relevant entities in deterministic dispatch order: primary target, acting
 * actor (for `before_*` hooks), room, other actors in the room by ascending ID, room ground items,
 * then items held or equipped by primary, actor, and other actors in order. Returns the first veto
 * denial message, or null if all allow.
 */
internal fun World.dispatch(
  hook: HookName,
  primary: Entity?,
  room: Room?,
  ctx: HookContext,
): String? {
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
          diagnose("script", "${hook.scriptName} on $owner failed: ${e.message}", e)
          HookOutcome.Allow
        }
      if (hook.vetoable && outcome is HookOutcome.Deny) return@withCascadeGuard outcome.message
    }
    null
  }
}

/** Fires [hook] on [owner] alone, for entity-specific events such as `on_tick`. */
internal fun World.invokeOn(owner: Entity, hook: HookName, ctx: HookContext) {
  withCascadeGuard(hook.scriptName) {
    try {
      scripts.invokeHook(this, owner, hook, ctx)
    } catch (e: Exception) {
      diagnose("script", "${hook.scriptName} on $owner failed: ${e.message}", e)
    }
  }
}

/** Runs every global [hook] registered by loaded packages, with [ctx]. */
internal fun World.dispatchGlobal(hook: HookName, ctx: HookContext) {
  withCascadeGuard(hook.scriptName) {
    try {
      scripts.invokeGlobal(this, hook, ctx)
    } catch (e: Exception) {
      diagnose("script", "${hook.scriptName} failed: ${e.message}", e)
    }
  }
}

/** Runs scheduled timer or affect callback [ref] on [owner]. */
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
 * Executes [block] guarded against infinite recursive hook cascades exceeding [CASCADE_MAX_DEPTH].
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
