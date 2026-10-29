package gups.pluginapi

// What plugins can touch: a facade over the running world. Every verb
// calls what the `ctx` verb of the same name calls, so the engine's invariants hold. Views read
// current state on every access.

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
}

interface PluginSkill {
  val label: String
  val name: String
  val kind: String
  val tags: List<String>
}
