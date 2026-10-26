package gups.pluginapi

// What plugins can touch (SPEC §4.15): a facade over the running world. Every verb
// calls what the `ctx` verb of the same name calls, so the engine's invariants hold. Views read
// current state on every access.

interface PluginWorld {
  // ---- Verbs ----

  fun send(target: PluginActor, text: String)

  fun echo(room: PluginRoom, text: String, exclude: List<PluginActor> = emptyList())

  fun damage(target: PluginActor, amount: Int, type: String, source: PluginActor? = null)

  fun heal(target: PluginActor, amount: Int)

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

  // ---- Reads ----

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

/** The world's seeded RNG. */
interface PluginRng {
  /** A uniform int in `min..max`, inclusive. */
  fun int(min: Int, max: Int): Int

  /** True with probability [pct] percent, as `ctx.rng.chance` (D-177). */
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
  val size: String

  /** Direction → destination room label. */
  val exits: Map<String, String>
  val actors: List<PluginActor>
  val items: List<PluginItem>
}

interface PluginActor : PluginEntity {
  val isPlayer: Boolean
  val room: PluginRoom
  val hp: Int
  val maxHp: Int
  val mana: Int
  val maxMana: Int
  val move: Int
  val maxMove: Int
  val level: Int

  /** Affect names. */
  val affects: List<String>

  fun hasAffect(name: String): Boolean
}

interface PluginItem : PluginEntity {
  val keywords: List<String>
}
