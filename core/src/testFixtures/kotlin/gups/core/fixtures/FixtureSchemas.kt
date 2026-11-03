package gups.core.fixtures

import gups.core.model.SchemaName
import gups.core.model.WorldSchema

/**
 * A test schema and the roles its names play for [FixtureRules], so a test can say "the health
 * vital" and run against schemas that call it different things.
 */
class FixtureSchema(
  val name: String,
  val schema: WorldSchema,
  /** The vital that death is decided by, and that damage lowers. */
  val health: String,
  /** The vital that spells cost and that [mind] regenerates. */
  val energy: String,
  /** The vital that walking spends. */
  val stamina: String,
  val agility: String,
  val strength: String,
  val toughness: String,
  /** Stats whose sum drives [energy] regeneration. */
  val mind: List<String>,
  /** A stat that soaks damage, if the schema has one. */
  val armor: String?,
  /** A damage type that is not the unarmed one. */
  val magic: String,
  /** The vital's maximum a new player gets, by vital; the others start at 0. */
  val playerMax: Map<String, Int>,
  /** The vital's maximum a mob gets when its prototype sets none, by vital. */
  val mobMax: Map<String, Int>,
  /** Vitals that start empty, by their maximum. */
  val startsEmpty: Map<String, Int> = emptyMap(),
) {
  val unarmed: String
    get() = schema.unarmedDamageType

  val vitals: List<String>
    get() = schema.vitalNames

  val stats: List<String>
    get() = schema.statNames

  override fun toString(): String = name
}

object FixtureSchemas {
  /** The template world's schema. */
  val FANTASY =
    FixtureSchema(
      name = "fantasy",
      schema =
        WorldSchema(
          stats =
            listOf(
              SchemaName("str", "Strength"),
              SchemaName("dex", "Dexterity"),
              SchemaName("con", "Constitution"),
              SchemaName("int", "Intelligence"),
              SchemaName("wis", "Wisdom"),
              SchemaName("cha", "Charisma"),
              SchemaName("armor", "Armor"),
            ),
          vitals =
            listOf(
              SchemaName("hp", "Hit points"),
              SchemaName("mana", "Mana"),
              SchemaName("move", "Movement"),
            ),
          damageTypes = listOf("bludgeon", "slash", "pierce", "fire"),
          unarmedDamageType = "bludgeon",
          channels = listOf("spell"),
          prompt = "<{hp}hp {mana}m {move}mv> ",
        ),
      health = "hp",
      energy = "mana",
      stamina = "move",
      agility = "dex",
      strength = "str",
      toughness = "con",
      mind = listOf("int", "wis"),
      armor = "armor",
      magic = "fire",
      playerMax = mapOf("hp" to 20, "mana" to 20, "move" to 100),
      mobMax = mapOf("hp" to 10, "mana" to 0, "move" to 100),
    )

  /** No `hp` or `mana`: the health vital is second of three, and one vital starts empty. */
  val ROBOT =
    FixtureSchema(
      name = "robot",
      schema =
        WorldSchema(
          stats =
            listOf(
              SchemaName("might", "Might"),
              SchemaName("agility", "Agility"),
              SchemaName("tech", "Tech"),
            ),
          vitals =
            listOf(
              SchemaName("rads", "Radiation"),
              SchemaName("integrity", "Integrity"),
              SchemaName("power", "Power"),
            ),
          damageTypes = listOf("kinetic", "energy"),
          unarmedDamageType = "kinetic",
          channels = listOf("psionic"),
        ),
      health = "integrity",
      energy = "power",
      stamina = "power",
      agility = "agility",
      strength = "might",
      toughness = "tech",
      mind = listOf("tech"),
      armor = null,
      magic = "energy",
      playerMax = mapOf("integrity" to 20, "power" to 120, "rads" to 100),
      mobMax = mapOf("integrity" to 10, "power" to 100, "rads" to 100),
      startsEmpty = mapOf("rads" to 100),
    )

  val ALL: List<FixtureSchema> = listOf(FANTASY, ROBOT)

  /** The schema that fixtures use unless a test names one; parameterized tests set it. */
  var current: FixtureSchema = FANTASY
}
