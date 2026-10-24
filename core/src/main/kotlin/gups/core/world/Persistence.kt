package gups.core.world

import gups.core.EntityId
import gups.core.Label
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Player

/**
 * The persistence seam (SPEC §2.2, §5.11), implemented by the `persist` module. Every call
 * comes from the world thread and must not block it: capture serializes, a writer elsewhere
 * commits.
 */
interface Persistence {
    /**
     * Captures dirty state. A routine capture is written at once. An [immediate] one (an
     * ownership change, a level-up, character creation, quit) is held and written with the
     * next routine one, so both sides of a transfer always land together (SPEC §5.11).
     */
    fun checkpoint(world: World, cause: String, immediate: Boolean)

    /** Restores the saved character [name] into [world] at login, or returns null if there is none. */
    fun loadCharacter(world: World, name: String): Player? = null

    /** Records a diagnostic in the diagnostics table, with a repro bundle for script errors. */
    fun diagnostic(world: World, d: Diagnostic) = Unit

    /** Non-null while new logins must be refused, for example while the database is unwritable (SPEC §6.4). */
    val maintenance: String? get() = null

    companion object {
        val NONE: Persistence = object : Persistence {
            override fun checkpoint(world: World, cause: String, immediate: Boolean) = Unit
        }
    }
}

/** One custody change of an item involving a player, for the `item_events` audit (SPEC §3.6). */
data class ItemEvent(val item: EntityId, val pulse: Long, val from: ItemLocation?, val to: ItemLocation?, val cause: String)

/** A tick-lane command or `say` since the last checkpoint, for repro bundles (SPEC §6.2). */
data class InputRecord(val pulse: Long, val actor: EntityId, val actorName: String, val room: Label, val line: String)

/** Ids of entities gone from the world since the last call: their rows can be deleted. */
fun World.drainRemoved(): Set<EntityId> = LinkedHashSet(removed).also { removed.clear() }

fun World.drainItemEvents(): List<ItemEvent> = itemEvents.toList().also { itemEvents.clear() }

/** Inputs recorded since the last periodic checkpoint. */
val World.recentInputs: List<InputRecord> get() = inputs

/** Starts a new input window, as each periodic checkpoint does. */
fun World.clearInputs() = inputs.clear()

/** Marks [item] and everything inside it as gone for good. */
internal fun World.markRemoved(item: Item) {
    removed += item.id
    for (inner in item.contentsIndex.values) markRemoved(inner)
}
