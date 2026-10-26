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

/** The hook catalog (SPEC §4.4). Only `before_*` hooks can veto. */
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
  ON_NEARBY("on_nearby");

  val vetoable: Boolean
    get() = scriptName.startsWith("before_")
}

/** The event fields of one hook invocation (SPEC §4.5). `ctx.self` is the owner. */
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
  val affect: Affect? = null,
  val elapsedPulses: Long? = null,
  /** The perception event type for `on_nearby`, for example `say` or `enter` (SPEC §5.7). */
  val event: String? = null,
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
 * The script seam (SPEC §4.11), implemented by `gups.script.StarlarkHost`. The engine decides
 * dispatch order; the host runs whatever hook the owner's prototype defines, if any.
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
   * Runs one modifier `fn(ctx, base)` of the rules chain (SPEC §4.6) and returns its value: an
   * `Int` for numeric points, a [gups.core.rules.Regen] for `regen`, and for `cast_allowed` null
   * (allow) or the denial message. A failing modifier returns [base]. [source] says where the
   * modifier comes from, for example `region:null_field`.
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
   * Re-evaluates [packages] and swaps them into [world] (SPEC §5.10). On failure the old version
   * stays live and the result carries the errors.
   */
  fun reload(world: World, packages: List<String>): ReloadResult =
    ReloadResult(
      false,
      listOf("This world has no script host to reload ${packages.joinToString()} with."),
    )

  /**
   * Runs the package's `migrate(old_version, attrs)` for [entity], saved at prototype version
   * [fromVersion] (SPEC §3.4). The host rewrites the package's own attributes in place.
   */
  fun migrate(world: World, entity: Entity, fromVersion: Int) = Unit

  /** Packages whose scripts are disabled, saved in `zone_states` (SPEC §6.3). */
  val quarantinedPackages: Set<String>
    get() = emptySet()

  /**
   * Puts a package in quarantine: on errors, as restored from a checkpoint, or by `@quarantine`.
   */
  fun quarantine(world: World, pkg: String, why: String) = Unit

  /** Lifts a package's quarantine (`@unquarantine`, SPEC §4.9). */
  fun unquarantine(world: World, pkg: String) = Unit

  /** Every loaded package path, for checking `@quarantine` and `@reload` arguments. */
  val packages: Set<String>
    get() = emptySet()

  /**
   * Script steps used so far per package, ever increasing (SPEC §6.2, `gups_script_steps_total`).
   */
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
 * Fires [hook] in dispatch order (SPEC §5.6): [primary], the room, other actors in the room by
 * ascending id, the room's items, then items carried or equipped by those actors. The acting actor
 * ([HookContext.actor]) only observes its own events when it is [primary]. Returns the first denial
 * of a `before_*` hook, or null.
 */
internal fun World.dispatch(
  hook: HookName,
  primary: Entity?,
  room: Room?,
  ctx: HookContext,
): String? {
  if (cascadeDepth >= CASCADE_MAX_DEPTH) {
    diagnose("cascade", "${hook.scriptName} dropped: cascade deeper than $CASCADE_MAX_DEPTH")
    return null
  }
  val owners = LinkedHashSet<Entity>()
  primary?.let(owners::add)
  if (room != null) {
    owners += room
    val actors = room.actorIndex.values.filter { it !== primary && it !== ctx.actor }
    owners += actors
    owners += room.itemIndex.values
    val holders = listOfNotNull(primary as? Actor) + actors
    owners +=
      holders.flatMap { it.inventoryIndex.values + it.equipmentIndex.values }.sortedBy { it.id }
  }
  cascadeDepth++
  try {
    for (owner in owners) {
      val outcome =
        try {
          scripts.invokeHook(this, owner, hook, ctx)
        } catch (e: Exception) {
          // A crashing hook behaves as if it returned None (SPEC §6.2).
          diagnose("script", "${hook.scriptName} on $owner failed: ${e.message}", e)
          HookOutcome.Allow
        }
      if (hook.vetoable && outcome is HookOutcome.Deny) return outcome.message
    }
    return null
  } finally {
    cascadeDepth--
  }
}

/** Fires [hook] on [owner] alone, for per-entity hooks such as `on_tick`. */
internal fun World.invokeOn(owner: Entity, hook: HookName, ctx: HookContext) {
  cascadeDepth++
  try {
    scripts.invokeHook(this, owner, hook, ctx)
  } catch (e: Exception) {
    diagnose("script", "${hook.scriptName} on $owner failed: ${e.message}", e)
  } finally {
    cascadeDepth--
  }
}

internal fun World.invokeRef(owner: Entity, ref: HookRef, ctx: HookContext) {
  cascadeDepth++
  try {
    scripts.invokeRef(this, owner, ref, ctx)
  } catch (e: Exception) {
    diagnose("script", "${ref.file}:${ref.name} on $owner failed: ${e.message}", e)
  } finally {
    cascadeDepth--
  }
}
