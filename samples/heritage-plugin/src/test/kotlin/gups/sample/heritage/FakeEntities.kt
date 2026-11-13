package gups.sample.heritage

import gups.core.OutputSink
import gups.pluginapi.PluginActor
import gups.pluginapi.PluginItem
import gups.pluginapi.PluginItemLocation
import gups.pluginapi.PluginRng
import gups.pluginapi.PluginRole
import gups.pluginapi.PluginRoom
import gups.pluginapi.PluginSkill

class FakeActor(
  override val name: String = "Tester",
  override val isPlayer: Boolean = true,
  override val role: PluginRole = PluginRole.PLAYER,
  val traitSet: Set<String> = emptySet(),
  override val choices: Map<String, String> = emptyMap(),
  override val room: PluginRoom = FakeRoom(),
  val statValue: Int = 10,
  override val skills: Map<String, Int> = emptyMap(),
) : PluginActor {
  override val inventory: List<PluginItem> = emptyList()
  override val equipment: Map<String, PluginItem> = emptyMap()
  override val id = 100L
  override val label: String? = null
  override val flags: Set<String> = emptySet()
  override val traits: List<String> = traitSet.sorted()
  override val grantedTraits: Set<String> = emptySet()

  override fun hasTrait(name: String) = name in traitSet

  override fun stat(name: String) = statValue

  override fun baseStat(name: String) = 10

  override fun vital(name: String) = 20

  override fun maxVital(name: String) = 20

  override fun baseMaxVital(name: String) = 20

  override fun resist(type: String) = 0

  override fun baseResist(type: String) = 0

  override val level = 1
  override val pendingLevels = 0
  override val affects = emptyList<String>()

  override fun hasAffect(name: String) = false
}

class FakeRoom(override val tags: List<String> = emptyList()) : PluginRoom {
  override val id = 1L
  override val label = "//zones/test:room"
  override val name = "Room"
  override val regions = emptyList<String>()
  override val flags = emptySet<String>()
  override val size = "normal"
  override val exits = emptyMap<String, String>()
  override val actors = emptyList<PluginActor>()
  override val items = emptyList<PluginItem>()
}

class FakeAttempt(
  override val name: String = "Tester",
  override val answers: Map<String, String> = emptyMap(),
  override val seed: Long = 12345L,
  override val state: MutableMap<String, Any?> = mutableMapOf(),
  override val preview: PluginActor = FakeActor(),
) : gups.pluginapi.PluginCreationAttempt {
  override fun rng(stream: Int): PluginRng = TODO()
}

class FakeItem(override val tags: List<String> = emptyList()) : PluginItem {
  override val id = 50L
  override val label = "//zones/test:item"
  override val name = "Item"
  override val keywords = listOf("item")
  override val kind = "armor"
  override val wear = "body"
  override val slotCost = 1
  override val weight = 10
  override val value = 0
  override val maxVitalDeltas = emptyMap<String, Int>()
  override val resistDeltas = emptyMap<String, Int>()
  override val inactive = false
  override val plural: String? = null
  override val count = 1
  override val location: PluginItemLocation
    get() = TODO()

  override val charges: Int? = null
  override val maxCharges: Int? = null

  override fun hasTag(tag: String): Boolean = tag in tags

  override val variants: Map<String, String> = emptyMap()
  override val affects: List<gups.pluginapi.PluginItemAffect> = emptyList()
  override val contents: List<PluginItem> = emptyList()
  override val custodian: PluginActor? = null
}

class FakeSkill(override val tags: List<String> = emptyList()) : PluginSkill {
  override val label = "//skills:test"
  override val name = "Test"
  override val kind = "spell"
}

class PromptSink : OutputSink {
  val all = StringBuilder()
  var closed: String? = null

  override fun send(text: String) {
    all.append(text).append("\n")
  }

  override fun prompt(text: String) {
    all.append("[$text]")
  }

  override fun close(reason: String) {
    closed = reason
  }
}
