package gups.pluginapi

/**
 * What a plugin may do without changing the world: reads, messages and GMCP events. GMCP input
 * handlers get only this view, and act through [PluginGmcpCall.command].
 */
interface PluginGmcpWorld {
  /**
   * The features this world has: the engine features that are on (`combat`, `track`, `skills`,
   * `leveling`, `standing`) and the features loaded plugins provide.
   */
  val features: Set<String>
    get() = setOf("combat", "track", "skills", "leveling")

  fun send(target: PluginActor, text: String)

  /**
   * Sends the GMCP event [pkg] with [data] (plain values) to [actor]. [pkg] must be, or nest in, a
   * package this plugin declared with addGmcp or addGmcpEvent. Returns false, sending nothing, for
   * a mob, a link-dead player, or a client that doesn't support [pkg].
   */
  fun gmcp(actor: PluginActor, pkg: String, data: Any?): Boolean

  /** Reads an attribute; a key without `ns:` is in the plugin's own namespace (its id). */
  fun getAttr(entity: PluginEntity, key: String, default: Any? = null): Any?

  // ---- Reads ----

  /** The names the world declares, in declaration order. */
  val schema: PluginSchema
  val pulse: Long
  val seed: Long

  /** Every room, sorted by label. */
  fun rooms(): List<PluginRoom>

  fun room(label: String): PluginRoom?

  /** Every region tag a room carries, sorted. */
  fun regions(): List<String>

  // ---- Diagnostics ----

  /** Records a `"plugin"` diagnostic. */
  fun diagnose(message: String)

  /** Writes to the server log. */
  fun log(message: String)
}

/**
 * Safe facade interface exposing the running simulation world to plugins.
 *
 * ## Facade Verb Guarantees & Invariant Preservation
 * - Every verb (`send`, `echo`, `damage`, `heal`, `applyAffect`, `removeAffect`, `teleport`,
 *   `setChoices`, `grantTrait`, `setSkill`, `awardXp`, `takeXp`, `levelUp`, `levelDown`) directly
 *   delegates to the engine's internal implementation of the corresponding Starlark `ctx` verb.
 * - This guarantees that all game invariants, state checks, entity dirty flags, immediate
 *   checkpoint triggers, event cascades, and perception broadcasts behave identically whether
 *   invoked from Starlark scripts or Kotlin plugins.
 *
 * ## Live State Views
 * - Entity handles ([PluginRoom], [PluginActor], [PluginItem]) are lightweight views that re-read
 *   current live state on every property access, preventing stale reads across pulse boundaries.
 *
 * ## Attribute Namespacing
 * - Attribute operations ([getAttr], [setAttr], [delAttr]) automatically default to the plugin's
 *   own namespace prefix (`<pluginId>:<key>`), isolating plugin-specific state from core engine
 *   attributes and other plugins.
 */
interface PluginWorld : PluginGmcpWorld {
  // ---- Verbs ----

  fun echo(room: PluginRoom, text: String, exclude: List<PluginActor> = emptyList())

  /** Raw damage to one vital: no to-hit roll and no mitigation, as `ctx.damage`. */
  fun damage(
    target: PluginActor,
    amount: Int,
    type: String,
    vital: String,
    source: PluginActor? = null,
  )

  fun heal(target: PluginActor, amount: Int, vital: String)

  fun applyAffect(
    target: PluginActor,
    name: String,
    duration: Int,
    statDeltas: Map<String, Int> = emptyMap(),
    flags: Set<String> = emptySet(),
    maxVitalDeltas: Map<String, Int> = emptyMap(),
    resistDeltas: Map<String, Int> = emptyMap(),
  )

  fun removeAffect(target: PluginActor, name: String): Boolean

  fun applyItemAffect(
    item: PluginItem,
    name: String,
    duration: Int?,
    applies: String = "worn",
    statDeltas: Map<String, Int> = emptyMap(),
    flags: Set<String> = emptySet(),
    senseDeltas: Map<String, Int> = emptyMap(),
    maxVitalDeltas: Map<String, Int> = emptyMap(),
    resistDeltas: Map<String, Int> = emptyMap(),
  )

  fun removeItemAffect(item: PluginItem, name: String): Boolean

  fun addTag(item: PluginItem, tag: String): Boolean

  fun removeTag(item: PluginItem, tag: String): Boolean

  fun transform(item: PluginItem, label: String)

  /**
   * Sets [item]'s remaining uses, from 0 to its prototype's charges; 0 doesn't run its empty
   * action.
   */
  fun setCharges(item: PluginItem, charges: Int)

  fun teleport(actor: PluginActor, room: PluginRoom)

  /** Writes an attribute in the plugin's own namespace; `null` deletes it. */
  fun setAttr(entity: PluginEntity, key: String, value: Any?)

  fun delAttr(entity: PluginEntity, key: String)

  // Choices (players only) and traits (players and mobs).
  // Each returns a refusal message, or null.
  fun setChoices(actor: PluginActor, changes: Map<String, String?>): String?

  fun grantTrait(actor: PluginActor, trait: String): String?

  fun removeTrait(actor: PluginActor, trait: String): String?

  // Skills (`skills` feature). setSkill runs the LEARN restrictions first.
  fun setSkill(actor: PluginActor, skill: String, proficiency: Int): String?

  fun removeSkill(actor: PluginActor, skill: String): Boolean

  /**
   * Reports a use of [skill] by [actor] to on_skill_use, with outcome "report", as
   * `ctx.report_skill_use` does. A skill only rules read advances this way. `target` may be the
   * actor itself. Needs the `skills` feature.
   */
  fun reportSkillUse(
    actor: PluginActor,
    skill: String,
    target: PluginActor? = null,
    item: PluginItem? = null,
  ): Unit = throw UnsupportedOperationException("reportSkillUse is not supported")

  fun setBaseStat(actor: PluginActor, stat: String, value: Int): String? =
    throw UnsupportedOperationException("setBaseStat is not supported")

  fun setBaseMaxVital(actor: PluginActor, vital: String, value: Int): String? =
    throw UnsupportedOperationException("setBaseMaxVital is not supported")

  fun setBaseResist(actor: PluginActor, type: String, value: Int): String? =
    throw UnsupportedOperationException("setBaseResist is not supported")

  // Leveling (`leveling` feature).

  /**
   * Awards [amount] experience points to [actor], applying pending levels in auto mode or sending
   * the ready notice in deferred mode.
   *
   * @throws IllegalStateException naming the leveling feature when leveling is disabled.
   * @throws IllegalArgumentException when [amount] is less than 1.
   */
  fun awardXp(actor: PluginActor, amount: Long)

  /**
   * Removes up to [amount] experience points from [actor], never going below 0. Never changes the
   * actor's level and sends no message.
   *
   * @return the amount of experience actually removed.
   * @throws IllegalStateException naming the leveling feature when leveling is disabled.
   * @throws IllegalArgumentException when [amount] is less than 1.
   */
  fun takeXp(actor: PluginActor, amount: Long): Long

  /**
   * Applies one pending level to [actor], in either level mode. In deferred mode, sends the ready
   * notice again if more levels are still pending.
   *
   * @return true if a level was gained, or false if no level was pending.
   * @throws IllegalStateException naming the leveling feature when leveling is disabled.
   */
  fun levelUp(actor: PluginActor): Boolean

  /**
   * Lowers [actor] by up to [count] levels, stopping at level 1.
   *
   * @return the number of levels actually lost.
   * @throws IllegalStateException naming the leveling feature when leveling is disabled.
   * @throws IllegalArgumentException when [count] is less than 1.
   */
  fun levelDown(actor: PluginActor, count: Int = 1): Int

  // Standing (`standing` feature).

  /**
   * Returns what [holder] thinks of [subject] (-100 to 100): the default sum of its parts or the
   * world's `disposition` rules export, then the disposition modifiers.
   */
  fun disposition(holder: PluginActor, subject: PluginActor): Int

  /**
   * Returns the amount of [holder]'s standing record about [subject], or 0 when there is none.
   * [holder] is a mob [PluginActor] or a faction label string, [subject] a [PluginActor] or a
   * faction label string. Declared faction opinions are not records.
   */
  fun standing(holder: Any, subject: Any): Int

  /** Returns the faction labels [actor] belongs to. */
  fun factionsOf(actor: PluginActor): List<String>

  /** Raises a custom standing event on [affected] by [actor]. */
  fun raiseStandingEvent(
    affected: PluginActor,
    actor: PluginActor,
    event: String,
    amount: Long = 0,
    item: PluginItem? = null,
  )

  /** Adjusts standing directly for [holder] and [subject] by [amount]. */
  fun adjustStanding(holder: Any, subject: Any, amount: Int)

  // Combat memory (`combat` feature).

  /**
   * Returns [mob]'s recorded memories, matching [subject] and/or [event] when specified, since
   * [since] pulse; a negative [since] counts as 0.
   */
  fun memories(
    mob: PluginActor,
    subject: PluginActor? = null,
    event: String? = null,
    since: Long = 0,
  ): List<PluginMemory>

  /** Records a memory entry for [mob]. */
  fun remember(
    mob: PluginActor,
    subject: PluginActor,
    event: String,
    skill: String? = null,
    amount: Long = 0,
    target: PluginActor? = null,
  )

  /**
   * Forgets memories matching [subject] and/or [event] on [mob]. Returns the count of removed
   * entries.
   */
  fun forget(mob: PluginActor, subject: PluginActor? = null, event: String? = null): Int

  /** The world's seeded RNG. Draws change the world's repro, so it stays off the read-only view. */
  val rng: PluginRng
}

/** A declared stat or vital: the name scripts use and the label players see. */
data class PluginNamed(val name: String, val label: String)

/** What the world's `MODULE.gups` declares. */
data class PluginSchema(
  val stats: List<PluginNamed>,
  val vitals: List<PluginNamed>,
  val damageTypes: List<String>,
)

/** The world's seeded RNG. */
interface PluginRng {
  /** A uniform int in `min..max`, inclusive. */
  fun int(min: Int, max: Int): Int

  /** True with probability [pct] percent, as `ctx.rng.chance`. */
  fun chance(pct: Int): Boolean
}

interface PluginEntity {
  val id: Long

  /** The room's label, or a mob's or item's prototype label; null for players. */
  val label: String?
  val name: String
}

interface PluginRoom : PluginEntity {
  val regions: List<String>
  val flags: Set<String>
  val tags: List<String>
  val size: String

  /** Direction → destination room label. */
  val exits: Map<String, String>
  val actors: List<PluginActor>
  val items: List<PluginItem>
}

interface PluginActor : PluginEntity {
  val isPlayer: Boolean
  val role: PluginRole?
  val room: PluginRoom
  val level: Int

  /**
   * The number of levels the actor is currently eligible to advance.
   *
   * @throws IllegalStateException naming the leveling feature when leveling is disabled.
   */
  val pendingLevels: Int

  /** The effective value of a declared stat. An undeclared name throws, with a suggestion. */
  fun stat(name: String): Int

  /** The base value of a declared stat, without affects, equipment or encumbrance. */
  fun baseStat(name: String): Int

  /** The current value of a declared vital. */
  fun vital(name: String): Int

  /** The effective maximum of a declared vital. */
  fun maxVital(name: String): Int

  /** The base maximum of a declared vital. */
  fun baseMaxVital(name: String): Int

  /** The effective resistance percentage to a declared damage type. */
  fun resist(type: String): Int

  /** The base resistance percentage to a declared damage type. */
  fun baseResist(type: String): Int

  /** Affect names. */
  val affects: List<String>

  fun hasAffect(name: String): Boolean

  val flags: Set<String>

  /** Known choices only. */
  val choices: Map<String, String>

  /** Known traits only, sorted. */
  val traits: List<String>

  /** Granted traits as saved, sorted. */
  val grantedTraits: Set<String>

  fun hasTrait(name: String): Boolean

  /** Skill label to proficiency (needs the skills feature). */
  val skills: Map<String, Int>
}

interface PluginItem : PluginEntity {
  val keywords: List<String>
  val kind: String
  val wear: String?

  /** How many slots of its family this item occupies when equipped, at least 1. */
  val slotCost: Int
  val weight: Int

  /** Base price of one item, from its prototype. */
  val value: Int

  /** Vital maximum adjustments while equipped, by vital name. */
  val maxVitalDeltas: Map<String, Int>

  /** Damage resistance percentage adjustments while equipped, by damage type. */
  val resistDeltas: Map<String, Int>

  /** True if equipped in a slot that does not currently fit its wearer. */
  val inactive: Boolean

  /** Effective tags, sorted: the prototype's plus those added, minus those removed. */
  val tags: List<String>

  /** True if [tag] is among the effective [tags]. */
  fun hasTag(tag: String): Boolean

  /** Variant names to item labels. */
  val variants: Map<String, String>

  /** Uses left, or null when the item has unlimited uses. */
  val charges: Int?

  /** The prototype's charges, or null when unlimited. */
  val maxCharges: Int?

  /**
   * This item's affects: its prototype's in declaration order, then its own instance affects by id;
   * empty for an item whose prototype was removed (a tombstone).
   */
  val affects: List<PluginItemAffect>

  /** Items inside this container, ascending id. */
  val contents: List<PluginItem>

  /** The actor whose custody this item ends in, following containers; null on the ground. */
  val custodian: PluginActor?

  /** Display name for stacks of 2 or more, such as `gold coins`, or null to show `name (count)`. */
  val plural: String?
}

/** An affect on an item, as a plugin sees it: declared by its prototype or put on the instance. */
data class PluginItemAffect(
  val name: String,
  /** "worn" or "carried". */
  val applies: String,
  val flags: List<String>,
  val statDeltas: Map<String, Int>,
  val senseDeltas: Map<String, Int>,
  /** "proto" or "instance". */
  val origin: String,
  /** Absolute pulse, or null when permanent or from the prototype. */
  val expiresAt: Long?,
  /** Vital maximum adjustments while the affect reaches the holder, by vital name. */
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  /** Damage resistance adjustments while the affect reaches the holder, by damage type. */
  val resistDeltas: Map<String, Int> = emptyMap(),
)

interface PluginSkill {
  val label: String
  val name: String
  val kind: String
  val tags: List<String>
}
