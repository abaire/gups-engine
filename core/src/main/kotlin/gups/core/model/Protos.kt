package gups.core.model

import gups.core.Label
import gups.core.THINK_DEFAULT_PULSES
import gups.core.world.Behavior
import gups.core.world.SkillResolver
import gups.core.world.ThinkFn
import gups.core.world.ZoneCommand

// Immutable prototypes (SPEC §3.4). The script host (M2) produces them by evaluating
// packages; until then they are built directly in Kotlin.

val STAT_NAMES: List<String> = listOf("str", "dex", "con", "int", "wis")
val DEFAULT_STATS: Map<String, Int> = STAT_NAMES.associateWith { 10 }

data class DoorProto(
    val state: DoorState = DoorState.CLOSED,
    val key: Label? = null,
    val openBlocks: Set<Channel> = emptySet(),
    val closedBlocks: Set<Channel> = setOf(Channel.SIGHT, Channel.SOUND),
)

data class ExitProto(
    val direction: Direction,
    val to: Label,
    /** Arrival index; `-1` means the back of the destination (`trackSize - 1`). */
    val entryIndex: Int = 0,
    val blocks: Set<Channel> = emptySet(),
    val door: DoorProto? = null,
    val oneWay: Boolean = false,
)

data class SpawnRule(val label: Label, val count: Int = 1, val respawnPulses: Int = 1200, val atIndex: Int = 0)

data class RoomProto(
    val label: Label,
    val name: String,
    val desc: String = "",
    val size: TrackSize = TrackSize.NORMAL,
    val regions: Set<String> = emptySet(),
    /** `always_active`, `dead_end_ok`. */
    val flags: Set<String> = emptySet(),
    val exits: List<ExitProto> = emptyList(),
    val spawns: List<SpawnRule> = emptyList(),
    /** Zone verbs, run in the tick lane. */
    val commands: Map<String, ZoneCommand> = emptyMap(),
    val version: Int = 1,
)

data class LootRule(val label: Label, val chance: Double = 1.0, val count: Int = 1)

data class MobProto(
    val label: Label,
    val name: String,
    val keywords: List<String>,
    val desc: String = "",
    val level: Int = 1,
    val stats: Map<String, Int> = DEFAULT_STATS,
    val hp: Int = 10,
    val mana: Int = 0,
    val move: Int = 100,
    val armor: Int = 0,
    val resists: Map<DamageType, Int> = emptyMap(),
    val skills: Map<Label, Int> = emptyMap(),
    val senses: Map<Sense, Int> = emptyMap(),
    val behaviors: List<Behavior> = emptyList(),
    val think: ThinkFn? = null,
    val thinkEvery: Int = THINK_DEFAULT_PULSES,
    val dormantRecovery: DormantRecovery = DormantRecovery.FULL,
    val loot: List<LootRule> = emptyList(),
    val version: Int = 1,
)

data class ContainerProto(
    val capacity: Int = 10,
    val maxWeight: Int = 100,
    val state: DoorState = DoorState.OPEN,
    val key: Label? = null,
)

data class ItemProto(
    val label: Label,
    val name: String,
    val keywords: List<String>,
    val desc: String = "",
    /** For example `melee`, `ranged`, `armor`, `consumable`. */
    val kind: String = "misc",
    /** Weapon reach. */
    val range: Band = Band.MELEE,
    val weight: Int = 1,
    /** Equipment slot this item goes in, for example `wield`. */
    val wear: String? = null,
    val dice: Dice? = null,
    val damageType: DamageType = DamageType.BLUDGEON,
    val statDeltas: Map<String, Int> = emptyMap(),
    val stackable: Boolean = false,
    val decayPulses: Int? = null,
    val container: ContainerProto? = null,
    val commands: Map<String, ZoneCommand> = emptyMap(),
    val version: Int = 1,
)

enum class SkillRange { MELEE, NEAR, FAR, SELF, ROOM }

enum class TargetKind { ENEMY, ALLY, SELF, ITEM, NONE }

/** When an in-progress cast is broken (SPEC §5.3). */
data class Interrupt(val damagePct: Int? = null, val stun: Boolean = true) {
    companion object {
        val NONE = Interrupt(damagePct = null, stun = false)
    }
}

data class SkillProto(
    val label: Label,
    val kind: String = "skill",
    /** Keys `hp`, `mana`, `move`. */
    val cost: Map<String, Int> = emptyMap(),
    val warmup: Int = 0,
    val lag: Int = 0,
    val range: SkillRange = SkillRange.MELEE,
    val target: TargetKind = TargetKind.ENEMY,
    val channels: Set<Channel> = emptySet(),
    val interrupt: Interrupt = Interrupt(),
    val attackWhileCasting: Boolean = false,
    val effect: Effect? = null,
    val resolve: SkillResolver? = null,
) {
    val name: String get() = label.name
}

/** The prototype table. Labels are unique per kind. */
class Prototypes(
    rooms: List<RoomProto> = emptyList(),
    mobs: List<MobProto> = emptyList(),
    items: List<ItemProto> = emptyList(),
    skills: List<SkillProto> = emptyList(),
) {
    val rooms: Map<Label, RoomProto> = unique("room", rooms) { it.label }
    val mobs: Map<Label, MobProto> = unique("mob", mobs) { it.label }
    val items: Map<Label, ItemProto> = unique("item", items + CORPSE) { it.label }
    val skills: Map<Label, SkillProto> = unique("skill", skills) { it.label }

    fun mob(label: Label): MobProto = mobs[label] ?: throw NoSuchElementException("No mob prototype $label")

    fun item(label: Label): ItemProto = items[label] ?: throw NoSuchElementException("No item prototype $label")

    fun skill(label: Label): SkillProto = skills[label] ?: throw NoSuchElementException("No skill $label")

    companion object {
        val PLAYER: Label = Label("//engine:player")

        val CORPSE = ItemProto(
            label = Label("//engine:corpse"),
            name = "a corpse",
            keywords = listOf("corpse"),
            kind = "corpse",
            weight = 100,
            container = ContainerProto(capacity = 100, maxWeight = 10_000),
        )

        private fun <T> unique(kind: String, protos: List<T>, label: (T) -> Label): Map<Label, T> {
            val out = LinkedHashMap<Label, T>()
            for (p in protos) require(out.put(label(p), p) == null) { "Duplicate $kind prototype ${label(p)}" }
            return out
        }
    }
}
