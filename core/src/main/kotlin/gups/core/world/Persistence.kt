package gups.core.world

import gups.core.AccountStore
import gups.core.EntityId
import gups.core.Label
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Player

/**
 * Persistence layer seam connecting the world loop to durable SQLite storage.
 *
 * Threading and durability contract:
 * - Capture occurs synchronously on the world simulation thread to serialize a point-in-time
 *   snapshot of dirty state.
 * - Captured checkpoint batches are committed to disk by a dedicated writer thread, never blocking
 *   the world loop.
 * - Routine captures are committed periodically.
 * - Immediate captures (transfers, character creation, level-up, quit) are queued so both sides of
 *   state transfers persist together.
 */
interface Persistence {
  /**
   * Captures dirty state for checkpoint persistence. [immediate] indicates critical mutations
   * (ownership transfers, level-up, character creation, quit) that require immediate persistence
   * flushing.
   */
  fun checkpoint(world: World, cause: String, immediate: Boolean)

  /**
   * Restores a saved character named [name] from storage into [world] during login, or returns null
   * if not found.
   */
  fun loadCharacter(world: World, name: String): Player? = null

  /** Records an operational or script diagnostic into durable diagnostic storage. */
  fun diagnostic(world: World, d: Diagnostic) = Unit

  /**
   * Narrative maintenance message refusing new player logins (e.g. during database maintenance or
   * I/O failure), or null.
   */
  val maintenance: String?
    get() = null

  /**
   * Returns saved character names owned by [accountId], ordered oldest first. Must query in-memory
   * lookup cache only and never block the world thread.
   */
  fun characters(accountId: Long): List<String> = emptyList()

  /**
   * Returns saved character record matching case-insensitive [name], or null. Must query in-memory
   * lookup cache only and never block the world thread.
   */
  fun savedCharacter(name: String): SavedCharacter? = null

  /**
   * True if [id] is an actor saved in persistence: a character, or a mob such as a pet held out of
   * the world while its owner is offline.
   */
  fun isSavedActor(id: EntityId): Boolean = false

  /**
   * Account storage interface, or null if this persistence configuration does not manage accounts.
   */
  val accounts: AccountStore?
    get() = null

  /** Number of pending checkpoint batches queued for the database writer thread. */
  val backlog: Int
    get() = 0

  /** Generates an immediate manual backup snapshot and returns the destination file path. */
  fun backup(): String =
    throw UnsupportedOperationException("this world has no database to back up")

  /** Returns historical custody audit records for an item ID, ordered oldest first. */
  fun itemHistory(itemId: Long): List<ItemHistory> = emptyList()

  companion object {
    /** No-op persistence implementation used for ephemeral in-memory tests. */
    val NONE: Persistence =
      object : Persistence {
        override fun checkpoint(world: World, cause: String, immediate: Boolean) = Unit
      }
  }
}

/** Committed historical custody transfer record for an item. */
data class ItemHistory(
  /** Simulation pulse when the custody transfer occurred. */
  val pulse: Long,
  /** Wall-clock millisecond timestamp when committed. */
  val wallTime: Long,
  /** Serialized source location JSON, or null if created. */
  val from: String?,
  /** Serialized destination location JSON, or null if deleted. */
  val to: String?,
  /** Action cause that triggered the transfer. */
  val cause: String,
)

/** Minimal in-memory index record of a persisted character. */
data class SavedCharacter(
  /** Canonical character display name. */
  val name: String,
  /** Owning account ID, or null for unlinked characters. */
  val accountId: Long?,
)

/** Live item custody transfer event involving a player character, tracked for audit logging. */
data class ItemEvent(
  /** Identifier of the transferred item entity. */
  val item: EntityId,
  /** Simulation pulse when the event occurred. */
  val pulse: Long,
  /** Previous item location, or null if created. */
  val from: ItemLocation?,
  /** New item location, or null if destroyed. */
  val to: ItemLocation?,
  /** Narrative cause of the transfer. */
  val cause: String,
)

/**
 * Historical record of a command or speech input line processed in the tick lane, recorded for
 * repro bundles.
 */
data class InputRecord(
  /** Simulation pulse when input was processed. */
  val pulse: Long,
  /** Entity ID of the acting character. */
  val actor: EntityId,
  /** Name of the acting character. */
  val actorName: String,
  /** Prototype label of the room where the action occurred. */
  val room: Label,
  /** Text of the command or chat input line. */
  val line: String,
)

/** Drains and returns the set of entity IDs removed from the world since the last checkpoint. */
fun World.drainRemoved(): Set<EntityId> = LinkedHashSet(removed).also { removed.clear() }

/** Drains and returns all item custody transfer events recorded since the last checkpoint. */
fun World.drainItemEvents(): List<ItemEvent> = itemEvents.toList().also { itemEvents.clear() }

/** Recent command input records captured during the current checkpoint window. */
val World.recentInputs: List<InputRecord>
  get() = inputs

/** Clears recorded inputs at the start of a new checkpoint window. */
fun World.clearInputs() = inputs.clear()

/** Recursively marks [item] and all contained items as removed from the world simulation. */
internal fun World.markRemoved(item: Item) {
  removed += item.id
  for (inner in item.contentsIndex.values) markRemoved(inner)
}
