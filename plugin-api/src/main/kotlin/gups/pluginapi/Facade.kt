package gups.pluginapi

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
interface PluginWorld {
  /** The engine features this world has on: `combat`, `track`, `skills`, `leveling`. */
  val features: Set<String>
    get() = setOf("combat", "track", "skills", "leveling")

  // ---- Verbs ----

  fun send(target: PluginActor, text: String)

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
  )

  fun removeAffect(target: PluginActor, name: String): Boolean

  fun teleport(actor: PluginActor, room: PluginRoom)

  /** Reads an attribute; a key without `ns:` is in the plugin's own namespace (its id). */
  fun getAttr(entity: PluginEntity, key: String, default: Any? = null): Any?

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

  // ---- Reads ----

  /** The names the world declares, in declaration order. */
  val schema: PluginSchema

  val pulse: Long
  val seed: Long
  val rng: PluginRng

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
  val weight: Int
  val tags: List<String>

  /** Display name for stacks of 2 or more, such as `gold coins`, or null to show `name (count)`. */
  val plural: String?
}

interface PluginSkill {
  val label: String
  val name: String
  val kind: String
  val tags: List<String>
}
