package gups.core.model

import gups.core.EntityId
import gups.core.Label

const val MEMORY_MAX_ENTRIES = 256

data class MemoryKey(
  val subject: EntityId,
  val event: String,
  val skill: Label?,
  val target: EntityId?,
)

class Memory(val key: MemoryKey, val firstPulse: Long) {
  var pulse: Long = firstPulse
    internal set

  var count: Int = 1
    internal set

  var amount: Long = 0
    internal set

  var channel: Channel? = null
    internal set
}
