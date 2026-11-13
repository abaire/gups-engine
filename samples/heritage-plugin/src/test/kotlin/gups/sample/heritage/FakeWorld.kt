package gups.sample.heritage

import gups.pluginapi.PluginActor
import gups.pluginapi.PluginEntity
import gups.pluginapi.PluginItem
import gups.pluginapi.PluginRng
import gups.pluginapi.PluginRoom
import gups.pluginapi.PluginSchema
import gups.pluginapi.PluginWorld

class FakeWorld : PluginWorld {
  override val pulse = 0L
  override val seed = 42L
  override val rng: PluginRng
    get() = TODO()

  override val schema: PluginSchema
    get() = TODO()

  val sent = mutableListOf<String>()
  val setChoicesCalls = mutableListOf<Pair<PluginActor, Map<String, String?>>>()
  val setBaseStatCalls = mutableListOf<Triple<PluginActor, String, Int>>()
  val setSkillCalls = mutableListOf<Pair<String, Int>>()

  /** What setSkill answers, by skill label: a refusal, or "undefined" to throw as the host does. */
  val skillAnswers = mutableMapOf<String, String>()

  override fun send(target: PluginActor, text: String) {
    sent += text
  }

  override fun gmcp(actor: PluginActor, pkg: String, data: Any?): Boolean = TODO()

  override fun setChoices(actor: PluginActor, changes: Map<String, String?>): String? {
    setChoicesCalls += actor to changes
    return null
  }

  override fun setBaseStat(actor: PluginActor, stat: String, value: Int): String? {
    setBaseStatCalls += Triple(actor, stat, value)
    return null
  }

  override fun echo(room: PluginRoom, text: String, exclude: List<PluginActor>) = TODO()

  override fun damage(
    target: PluginActor,
    amount: Int,
    type: String,
    vital: String,
    source: PluginActor?,
  ) = TODO()

  override fun heal(target: PluginActor, amount: Int, vital: String, source: PluginActor?) = TODO()

  override fun applyAffect(
    target: PluginActor,
    name: String,
    duration: Int,
    statDeltas: Map<String, Int>,
    flags: Set<String>,
    maxVitalDeltas: Map<String, Int>,
    resistDeltas: Map<String, Int>,
  ) = TODO()

  override fun removeAffect(target: PluginActor, name: String) = TODO()

  override fun applyItemAffect(
    item: PluginItem,
    name: String,
    duration: Int?,
    applies: String,
    statDeltas: Map<String, Int>,
    flags: Set<String>,
    senseDeltas: Map<String, Int>,
    maxVitalDeltas: Map<String, Int>,
    resistDeltas: Map<String, Int>,
  ): Unit = TODO("not used")

  override fun removeItemAffect(item: PluginItem, name: String): Boolean = TODO("not used")

  override fun addTag(item: PluginItem, tag: String): Boolean = TODO("not used")

  override fun removeTag(item: PluginItem, tag: String): Boolean = TODO("not used")

  override fun transform(item: PluginItem, label: String): Unit = TODO("not used")

  override fun setCharges(item: PluginItem, charges: Int): Unit = TODO("not used")

  override fun teleport(actor: PluginActor, room: PluginRoom): String? = TODO()

  override fun wear(actor: PluginActor, item: PluginItem, wield: Boolean): String? = TODO()

  override fun removeWorn(actor: PluginActor, item: PluginItem): String? = TODO()

  override fun mayWear(actor: PluginActor, item: PluginItem, wield: Boolean): String? = TODO()

  override fun mayRemove(actor: PluginActor, item: PluginItem): String? = TODO()

  override fun mayDrop(actor: PluginActor, item: PluginItem): String? = TODO()

  override fun mayGive(actor: PluginActor, item: PluginItem, to: PluginActor): String? = TODO()

  override fun mayPut(actor: PluginActor, item: PluginItem, container: PluginItem): String? = TODO()

  override fun mayEnter(actor: PluginActor, room: PluginRoom): String? = TODO()

  override fun mayLearn(actor: PluginActor, skill: String, proficiency: Int): String? = TODO()

  override fun fits(item: PluginItem, to: PluginEntity, count: Int?): Int = TODO()

  override fun fits(label: String, to: PluginEntity, count: Int?): Int = TODO()

  override fun spawnItem(label: String, to: PluginEntity, count: Int): List<PluginItem> = TODO()

  override fun moveItem(item: PluginItem, to: PluginEntity, count: Int?): PluginItem = TODO()

  override fun destroy(item: PluginItem, count: Int?) = TODO()

  override fun mergeStack(item: PluginItem): PluginItem = TODO()

  override fun getAttr(entity: PluginEntity, key: String, default: Any?) = TODO()

  override fun setAttr(entity: PluginEntity, key: String, value: Any?) = TODO()

  override fun delAttr(entity: PluginEntity, key: String) = TODO()

  override fun grantTrait(actor: PluginActor, trait: String): String? = TODO()

  override fun removeTrait(actor: PluginActor, trait: String): String? = TODO()

  override fun setSkill(actor: PluginActor, skill: String, proficiency: Int): String? {
    val answer = skillAnswers[skill]
    if (answer == "undefined") throw IllegalArgumentException("unknown skill $skill")
    setSkillCalls += skill to proficiency
    return answer
  }

  override fun removeSkill(actor: PluginActor, skill: String) = TODO()

  override fun awardXp(actor: PluginActor, amount: Long) = TODO()

  override fun takeXp(actor: PluginActor, amount: Long): Long = TODO()

  override fun levelUp(actor: PluginActor): Boolean = TODO()

  override fun levelDown(actor: PluginActor, count: Int): Int = TODO()

  override fun disposition(holder: PluginActor, subject: PluginActor): Int = TODO()

  override fun standing(holder: Any, subject: Any): Int = TODO()

  override fun factionsOf(actor: PluginActor): List<String> = TODO()

  override fun raiseStandingEvent(
    affected: PluginActor,
    actor: PluginActor,
    event: String,
    amount: Long,
    item: PluginItem?,
  ) = TODO()

  override fun adjustStanding(holder: Any, subject: Any, amount: Int) = TODO()

  override fun memories(
    mob: PluginActor,
    subject: PluginActor?,
    event: String?,
    since: Long,
  ): List<gups.pluginapi.PluginMemory> = TODO()

  override fun remember(
    mob: PluginActor,
    subject: PluginActor,
    event: String,
    skill: String?,
    amount: Long,
    target: PluginActor?,
  ) = TODO()

  override fun forget(mob: PluginActor, subject: PluginActor?, event: String?): Int = TODO()

  override fun rooms() = emptyList<PluginRoom>()

  override fun room(label: String): PluginRoom? = null

  override fun regions() = emptyList<String>()

  override fun diagnose(message: String) {}

  override fun log(message: String) {}
}
