package gups.pluginapi.host

import gups.core.AttrValue
import gups.core.Label
import gups.core.Rng
import gups.core.ai.FactValue
import gups.core.ai.GoapAction
import gups.core.ai.GoapPlanner
import gups.core.ai.PlanResult
import gups.core.model.Actor
import gups.core.model.AffectScope
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.Effect
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Room
import gups.core.model.SkillProto
import gups.core.model.StandingHolder
import gups.core.model.StandingSubject
import gups.core.model.WorldSchema
import gups.core.model.didYouMean
import gups.core.rules.RulesContext
import gups.core.world.AffectSpec
import gups.core.world.BUILTIN_STANDING_EVENTS
import gups.core.world.Castable
import gups.core.world.Diagnostic
import gups.core.world.Feature
import gups.core.world.GMCP_PACKAGE_NAME
import gups.core.world.HeldAffect
import gups.core.world.STANDING_EVENT_NAME
import gups.core.world.StandingEvent
import gups.core.world.TaskSpec
import gups.core.world.World
import gups.core.world.act
import gups.core.world.actAdvance
import gups.core.world.actAttack
import gups.core.world.actCast
import gups.core.world.actMove
import gups.core.world.actorSlots
import gups.core.world.addItemTag
import gups.core.world.applyAffect
import gups.core.world.applyItemAffect
import gups.core.world.applyStanding
import gups.core.world.awardXp
import gups.core.world.carriedWeight
import gups.core.world.clearActive
import gups.core.world.clearAnchor
import gups.core.world.clearContributions
import gups.core.world.clearOwner
import gups.core.world.clearStanding
import gups.core.world.custodian
import gups.core.world.damage
import gups.core.world.destroy
import gups.core.world.disposition
import gups.core.world.echo
import gups.core.world.emote
import gups.core.world.factionsOf
import gups.core.world.fits
import gups.core.world.forget
import gups.core.world.gmcp
import gups.core.world.gmcpWithin
import gups.core.world.grantTrait
import gups.core.world.groupAdd
import gups.core.world.groupDisband
import gups.core.world.groupMembers
import gups.core.world.groupOf
import gups.core.world.groupRemove
import gups.core.world.groupSetLeader
import gups.core.world.heal
import gups.core.world.heldAffects
import gups.core.world.isDying
import gups.core.world.isInactive
import gups.core.world.isSimulated
import gups.core.world.leash
import gups.core.world.levelDown
import gups.core.world.levelUpAndNotify
import gups.core.world.mayDrop
import gups.core.world.mayEnter
import gups.core.world.mayGive
import gups.core.world.mayLearn
import gups.core.world.mayPut
import gups.core.world.mayRemove
import gups.core.world.mayWear
import gups.core.world.memoriesOf
import gups.core.world.moveItem
import gups.core.world.movePaceOf
import gups.core.world.opponentsOf
import gups.core.world.ownerOf
import gups.core.world.pathTo
import gups.core.world.pendingLevels
import gups.core.world.perceive
import gups.core.world.raiseStandingEvent
import gups.core.world.remember
import gups.core.world.removeAffect
import gups.core.world.removeItemAffect
import gups.core.world.removeItemTag
import gups.core.world.removeSkill
import gups.core.world.removeTrait
import gups.core.world.removeWornItem
import gups.core.world.reportSkillUse
import gups.core.world.resetLeash
import gups.core.world.say
import gups.core.world.send
import gups.core.world.setActive
import gups.core.world.setAnchor
import gups.core.world.setBaseMaxVital
import gups.core.world.setBaseResist
import gups.core.world.setBaseStat
import gups.core.world.setCharges
import gups.core.world.setChoices
import gups.core.world.setContainerState
import gups.core.world.setDoor
import gups.core.world.setItemName
import gups.core.world.setLeash
import gups.core.world.setOwner
import gups.core.world.setSkill
import gups.core.world.spawnItem
import gups.core.world.stack
import gups.core.world.standing
import gups.core.world.standingHistory
import gups.core.world.startCombat
import gups.core.world.startTask
import gups.core.world.stopCombat
import gups.core.world.stopTask
import gups.core.world.summon
import gups.core.world.takeXp
import gups.core.world.taskOf
import gups.core.world.teleport
import gups.core.world.traceRules
import gups.core.world.transformItem
import gups.core.world.wearItem
import gups.pluginapi.PluginActor
import gups.pluginapi.PluginDoor
import gups.pluginapi.PluginEntity
import gups.pluginapi.PluginExit
import gups.pluginapi.PluginGmcpCall
import gups.pluginapi.PluginGmcpWorld
import gups.pluginapi.PluginGroup
import gups.pluginapi.PluginItem
import gups.pluginapi.PluginItemAffect
import gups.pluginapi.PluginItemInfo
import gups.pluginapi.PluginItemLocation
import gups.pluginapi.PluginMemory
import gups.pluginapi.PluginNamed
import gups.pluginapi.PluginPlanAction
import gups.pluginapi.PluginRng
import gups.pluginapi.PluginRole
import gups.pluginapi.PluginRoom
import gups.pluginapi.PluginSchema
import gups.pluginapi.PluginSkill
import gups.pluginapi.PluginSkillRoll
import gups.pluginapi.PluginStandingTotals
import gups.pluginapi.PluginTask
import gups.pluginapi.PluginTaskCall
import gups.pluginapi.PluginTaskInterrupt
import gups.pluginapi.PluginTaskStop
import gups.pluginapi.PluginWorld
import gups.script.ActorView
import gups.script.ItemView
import gups.script.ROLL_EVENT
import gups.script.RoomView
import gups.script.scriptWorld
import java.util.WeakHashMap
import net.starlark.java.eval.StarlarkThread

// The facade implementations. Each verb calls what the `ctx` verb of
// the same name calls (script/src/main/kotlin/gups/script/Ctx.kt).

/** Conversions behind `PluginViews`, and the server log of each world with plugins installed. */
internal object Facades {
  private val logs = WeakHashMap<World, (String) -> Unit>()

  fun registerLog(world: World, log: (String) -> Unit) {
    synchronized(logs) { logs[world] = log }
  }

  fun logOf(world: World): (String) -> Unit = synchronized(logs) { logs[world] } ?: {}

  private val gmcp = WeakHashMap<World, Map<String, List<String>>>()

  /** Records each plugin's GMCP state and event packages, which its facades may send under. */
  fun registerGmcp(world: World, owned: Map<String, List<String>>) {
    synchronized(gmcp) { gmcp[world] = owned }
  }

  fun gmcpOf(world: World, pluginId: String?): List<String> =
    pluginId?.let { id -> synchronized(gmcp) { gmcp[world]?.get(id) } } ?: emptyList()

  private val rulesExports = WeakHashMap<World, Map<String, Map<String, String>>>()

  /**
   * Records the rules exports each plugin of [world] declared, by plugin id: name to "int" or
   * "bool".
   */
  fun registerRulesExports(world: World, declared: Map<String, Map<String, String>>) {
    synchronized(rulesExports) { rulesExports[world] = declared }
  }

  /** The rules exports plugin [pluginId] declared in [world]: name to "int" or "bool". */
  fun rulesExportsOf(world: World, pluginId: String?): Map<String, String> =
    synchronized(rulesExports) { rulesExports[world] }?.get(pluginId) ?: emptyMap()

  /** The facade for raw builtins, which belong to no plugin namespace. */
  fun world(thread: StarlarkThread): PluginWorld? =
    scriptWorld(thread)?.let { WorldFacade(it, null, logOf(it)) }

  fun room(value: Any?): PluginRoom? =
    when (value) {
      is RoomFacade -> value
      is RoomView -> RoomFacade(value.room, value.scope.world)
      else -> null
    }

  fun actor(value: Any?): PluginActor? =
    when (value) {
      is ActorFacade -> value
      is ActorView -> ActorFacade(value.actor, value.scope.world)
      else -> null
    }

  fun item(value: Any?): PluginItem? =
    when (value) {
      is ItemFacade -> value
      is ItemView -> ItemFacade(value.item, value.scope.world)
      else -> null
    }
}

internal abstract class EntityFacade(open val entity: Entity) : PluginEntity {
  override val id: Long
    get() = entity.id.v

  override val name: String
    get() = entity.name

  override fun equals(other: Any?): Boolean = other is EntityFacade && other.entity === entity

  override fun hashCode(): Int = System.identityHashCode(entity)

  override fun toString(): String = entity.toString()
}

internal class RoomFacade(val room: Room, private val world: World) :
  EntityFacade(room), PluginRoom {
  override val label: String
    get() = room.label.v

  override val regions: List<String>
    get() = room.regions.sorted()

  override val flags: Set<String>
    get() = room.proto.flags.toSortedSet()

  override val tags: List<String>
    get() = room.proto.tags.sorted()

  override val size: String
    get() {
      needs(world, Feature.TRACK, "size")
      return room.proto.size.name.lowercase()
    }

  override val desc: String
    get() = room.proto.desc

  override val affects: List<String>
    get() = room.liveAffects(world.roomClock).map { it.name }

  override val exits: Map<String, String>
    get() = room.exits.values.associate { it.direction.name to it.to.v }

  override val actors: List<PluginActor>
    get() = room.actors.map { ActorFacade(it, world) }

  override val items: List<PluginItem>
    get() = room.items.map { ItemFacade(it, world) }

  override fun exit(dir: String): PluginExit? {
    val x = room.exits[Direction.parse(dir)] ?: return null
    return PluginExit(
      direction = x.direction.name,
      to = x.to.v,
      tags = x.tags.sorted(),
      door = x.door?.let { PluginDoor(it.state.name.lowercase(), it.key?.v, it.tags.sorted()) },
      key = x.door?.key?.v,
      passable = x.passable,
      oneWay = x.oneWay,
    )
  }
}

/** Fails with an `IllegalStateException` naming [feature] when [world] has it off. */
internal fun needs(world: World, feature: Feature, what: String) {
  check(feature in world.features) { "$what needs the ${feature.id} feature" }
}

/** [name] if it is one of [known], else an `IllegalArgumentException` with a suggestion. */
internal fun declared(
  name: String,
  known: List<String>,
  noun: String,
  what: String? = null,
): String {
  require(name in known) {
    "${what?.let { "$it: " }.orEmpty()}unknown $noun '$name'${didYouMean(name, known)}"
  }
  return name
}

internal class ActorFacade(val actor: Actor, private val world: World) :
  EntityFacade(actor), PluginActor {
  override val label: String?
    get() = if (actor is Player) null else actor.protoLabel.v

  override val isPlayer: Boolean
    get() = actor.isPlayer

  override val role: PluginRole?
    get() = (actor as? Player)?.let { PluginRole.valueOf(it.role.name) }

  override val room: PluginRoom
    get() = RoomFacade(actor.room, world)

  private val schema: WorldSchema
    get() = world.schema

  private fun statName(name: String): String = declared(name, schema.statNames, "stat")

  override fun stat(name: String): Int = actor.stat(statName(name))

  override fun baseStat(name: String): Int = actor.baseStats[statName(name)] ?: 0

  override fun vital(name: String): Int = actor.current(declared(name, schema.vitalNames, "vital"))

  override fun maxVital(name: String): Int = actor.max(declared(name, schema.vitalNames, "vital"))

  override fun baseMaxVital(name: String): Int =
    actor.baseMax(declared(name, schema.vitalNames, "vital"))

  override fun resist(type: String): Int =
    actor.resist(declared(type, schema.damageTypes, "damage type"))

  override fun baseResist(type: String): Int =
    actor.baseResist(declared(type, schema.damageTypes, "damage type"))

  override val level: Int
    get() {
      needs(world, Feature.LEVELING, "level")
      return actor.level
    }

  override val pendingLevels: Int
    get() {
      needs(world, Feature.LEVELING, "pendingLevels")
      return world.pendingLevels(actor)
    }

  override val affects: List<String>
    get() = actor.affects.map { it.name }

  override fun hasAffect(name: String): Boolean = actor.affects.any { it.name == name }

  override val flags: Set<String>
    get() = actor.flags.toSortedSet()

  override val choices: Map<String, String>
    get() =
      if (actor is Player) {
        val provider = world.creation
        if (provider == null) emptyMap()
        else
          actor.choices.filter { (cid, oid) ->
            provider.choices.any { c -> c.id == cid && c.options.any { o -> o.id == oid } }
          }
      } else emptyMap()

  override val traits: List<String>
    get() = actor.traits.map { it.name }.sorted()

  override val grantedTraits: Set<String>
    get() = actor.grantedTraits.toSortedSet()

  override fun hasTrait(name: String): Boolean = actor.hasTrait(name)

  override val skills: Map<String, Int>
    get() {
      needs(world, Feature.SKILLS, "skills")
      return actor.skills.mapKeys { it.key.v }
    }

  override val inventory: List<PluginItem>
    get() = actor.inventory.map { ItemFacade(it, world) }

  override val equipment: Map<String, PluginItem>
    get() = actor.equipment.mapValues { ItemFacade(it.value, world) }

  override val task: PluginTask?
    get() = world.taskOf(actor)?.let { TaskFacade(it, world) }

  override val isBusy: Boolean
    get() = actor.isBusy

  override val owner: PluginActor?
    get() = world.ownerOf(actor)?.let { ActorFacade(it, world) }

  override val homeRoom: PluginRoom?
    get() = (actor as? Mob)?.homeRoom?.let { world.roomOrNull(it) }?.let { RoomFacade(it, world) }

  override val leash: Int?
    get() = (actor as? Mob)?.leash

  override val leashOverridden: Boolean
    get() = (actor as? Mob)?.leashOverride != null

  override val fighting: List<PluginActor>
    get() {
      needs(world, Feature.COMBAT, "fighting")
      return actor.fighting.mapNotNull { world.actor(it) }.map { ActorFacade(it, world) }
    }

  override val primaryTarget: PluginActor?
    get() {
      needs(world, Feature.COMBAT, "primaryTarget")
      return actor.primaryTarget
        ?.takeIf { it in actor.fighting }
        ?.let { world.actor(it) }
        ?.let { ActorFacade(it, world) }
    }

  override val inWorld: Boolean
    get() = actor.inWorld

  override val keywords: List<String>
    get() = actor.keywords

  override val xp: Long
    get() {
      needs(world, Feature.LEVELING, "xp")
      return actor.xp
    }

  override val condition: String
    get() = actor.condition.word

  override val senses: Map<String, Int>
    get() = actor.effectiveSenses(world.schema.allChannels).mapKeys { it.key.name }

  override fun belowZeroSince(vital: String): Long? {
    val name = declared(vital, schema.vitalNames, "vital", "belowZeroSince(vital)")
    return actor.vitals.getValue(name).belowZeroSince
  }

  override val carriedWeight: Int
    get() = actor.carriedWeight()

  override val encumbranceLevel: Int
    get() = actor.encumbranceLevel

  override val isCasting: Boolean
    get() {
      needs(world, Feature.SKILLS, "isCasting")
      return actor.isCasting
    }

  override val casting: PluginSkill?
    get() {
      needs(world, Feature.SKILLS, "casting")
      return (actor.activity?.action as? Castable)?.skill?.let { SkillFacade(it) }
    }

  override fun hasFlag(flag: String): Boolean = actor.hasFlag(flag)

  override fun hasItem(label: String): Boolean {
    val l =
      runCatching { Label(label) }.getOrNull()
        ?: throw IllegalArgumentException("hasItem(label): '$label' is not an item")
    return (actor.inventory + actor.equipment.values).any { it.protoLabel == l }
  }

  override val slots: List<String>
    get() = world.actorSlots(actor)

  override val waitState: Int
    get() = actor.waitState

  override val movePace: Int
    get() = world.movePaceOf(RulesContext(world, actor))

  override val recallRoom: PluginRoom?
    get() =
      (actor as? Player)?.recallRoom?.let { world.roomOrNull(it) }?.let { RoomFacade(it, world) }
}

internal class ItemFacade(val item: Item, private val world: World) :
  EntityFacade(item), PluginItem {
  override val label: String
    get() = item.protoLabel.v

  override val keywords: List<String>
    get() = item.keywords

  override val kind: String
    get() = item.proto.kind

  override val wear: String?
    get() = item.proto.wear

  override val slotCost: Int
    get() = item.proto.slotCost

  override val weight: Int
    get() = item.proto.weight

  override val value: Int
    get() = item.proto.value

  override val maxVitalDeltas: Map<String, Int>
    get() = item.proto.maxVitalDeltas

  override val resistDeltas: Map<String, Int>
    get() = item.proto.resistDeltas.mapKeys { it.key.name }

  override val inactive: Boolean
    get() = world.isInactive(item)

  override val tags: List<String>
    get() = item.tags.sorted()

  override fun hasTag(tag: String): Boolean = tag in item.tags

  override val variants: Map<String, String>
    get() = item.proto.variants.mapValues { it.value.v }

  override val charges: Int?
    get() = item.charges

  override val maxCharges: Int?
    get() = item.proto.charges

  override val affects: List<PluginItemAffect>
    get() = item.heldAffects().map(::toPlugin)

  override val containerState: String?
    get() = item.container?.state?.name?.lowercase()

  override val contents: List<PluginItem>
    get() = item.contents.map { ItemFacade(it, world) }

  override val custodian: PluginActor?
    get() = world.custodian(item)?.let { ActorFacade(it, world) }

  override val plural: String?
    get() = item.proto.plural

  override val count: Int
    get() = item.count

  override val location: PluginItemLocation
    get() =
      when (val l = item.location) {
        is ItemLocation.InRoom ->
          PluginItemLocation.InRoom(RoomFacade(world.entity(l.room) as Room, world))
        is ItemLocation.Carried ->
          PluginItemLocation.Carried(ActorFacade(world.actor(l.actor)!!, world))
        is ItemLocation.Equipped ->
          PluginItemLocation.Equipped(ActorFacade(world.actor(l.actor)!!, world), l.slot)
        is ItemLocation.Contained ->
          PluginItemLocation.InContainer(ItemFacade(world.item(l.container)!!, world))
      }

  override val inWorld: Boolean
    get() = world.item(item.id) === item

  override val dice: String?
    get() = item.proto.dice?.toString()

  override val damageType: String
    get() = item.proto.damageType?.name ?: world.schema.unarmedDamageType

  override val statDeltas: Map<String, Int>
    get() = item.proto.statDeltas

  override val senseDeltas: Map<String, Int>
    get() = item.proto.senseDeltas.mapKeys { it.key.name }
}

internal fun toPlugin(ha: HeldAffect): PluginItemAffect =
  PluginItemAffect(
    name = ha.name,
    applies = ha.applies.name.lowercase(),
    flags = ha.flags.sorted(),
    statDeltas = ha.statDeltas,
    senseDeltas = ha.senseDeltas.mapKeys { it.key.name },
    origin = if (ha.instance == null) "proto" else "instance",
    expiresAt = ha.instance?.expiresAt?.takeIf { it != Long.MAX_VALUE },
    maxVitalDeltas = ha.maxVitalDeltas,
    resistDeltas = ha.resistDeltas.mapKeys { it.key.name },
  )

internal class SkillFacade(val skill: gups.core.model.SkillProto) : PluginSkill {
  override val label: String
    get() = skill.label.v

  override val name: String
    get() = skill.name

  override val kind: String
    get() = skill.kind

  override val tags: List<String>
    get() = skill.tags.sorted()
}

internal class RngFacade(private val rngSupplier: () -> Rng) : PluginRng {
  constructor(world: World) : this({ world.rng })

  constructor(rng: Rng) : this({ rng })

  override fun int(min: Int, max: Int): Int = rngSupplier().int(min, max)

  override fun chance(pct: Int): Boolean = rngSupplier().chance(pct)

  override fun <T> choice(items: List<T>): T {
    require(items.isNotEmpty()) { "choice(): the list is empty" }
    return rngSupplier().choice(items)
  }
}

/** The fields of [PluginWorld.rules] that become `ctx` fields of the same name. */
private val RULES_CTX_FIELDS = setOf("actor", "target", "source", "skill", "item")

/**
 * The [PluginWorld] of plugin [pluginId] over [world]. A null [pluginId] (raw builtins) has no
 * namespace of its own, so attribute keys must be qualified and can't be written.
 */
internal class WorldFacade(
  val world: World,
  private val pluginId: String?,
  private val logSink: (String) -> Unit,
) : PluginWorld {
  // A stale view fails as it does in `Ctx`, rather than reaching `core`.
  private fun actorOf(a: PluginActor, what: String): Actor {
    val actor =
      (a as? ActorFacade)?.actor
        ?: throw IllegalArgumentException("$what: not an actor of this world")
    require(actor.inWorld) { "$what: ${actor.name} is no longer in the world" }
    return actor
  }

  private fun mobOf(a: PluginActor, what: String): Mob {
    val actor = actorOf(a, what)
    if (actor !is Mob) throw IllegalArgumentException("$what: must be a mob")
    return actor
  }

  private fun playerOf(a: PluginActor, what: String): Player {
    val actor = actorOf(a, what)
    if (actor !is Player) throw IllegalArgumentException("$what: must be a player")
    return actor
  }

  private fun preparedActorOf(a: PluginActor, what: String): Actor {
    val actor =
      (a as? ActorFacade)?.actor
        ?: throw IllegalArgumentException("$what: not an actor of this world")
    require(actor.inWorld || world.preparing === actor) {
      "$what: ${actor.name} is no longer in the world"
    }
    return actor
  }

  private fun roomOf(r: PluginRoom, what: String): Room =
    (r as? RoomFacade)?.room ?: throw IllegalArgumentException("$what: not a room of this world")

  private fun itemOf(i: PluginItem, what: String): Item {
    val item =
      (i as? ItemFacade)?.item ?: throw IllegalArgumentException("$what: not an item of this world")
    require(world.item(item.id) === item) { "$what: ${item.name} is no longer in the world" }
    return item
  }

  private fun entityOf(e: PluginEntity, what: String): Entity =
    when (e) {
      is ActorFacade -> actorOf(e, what)
      is ItemFacade -> itemOf(e, what)
      else ->
        (e as? EntityFacade)?.entity
          ?: throw IllegalArgumentException("$what: not an entity of this world")
    }

  private fun preparedEntityOf(e: PluginEntity, what: String): Entity =
    when (e) {
      is ActorFacade -> preparedActorOf(e, what)
      is ItemFacade -> itemOf(e, what)
      else ->
        (e as? EntityFacade)?.entity
          ?: throw IllegalArgumentException("$what: not an entity of this world")
    }

  override val features: Set<String>
    get() = world.features.ids.toSet() + world.pluginFeatures

  override fun send(target: PluginActor, text: String) =
    world.send(actorOf(target, "send(target)"), text)

  override fun gmcp(actor: PluginActor, pkg: String, data: Any?): Boolean {
    require(GMCP_PACKAGE_NAME.matches(pkg)) {
      "gmcp(pkg): '$pkg' needs at least two dot-separated parts"
    }
    require(Facades.gmcpOf(world, pluginId).any { gmcpWithin(pkg, it) }) {
      "gmcp: plugin '${pluginId ?: "?"}' didn't declare a GMCP package for '$pkg'"
    }
    checkPlain(data, "gmcp(data)")
    val a = actorOf(actor, "gmcp")
    if (a !is Player) return false
    return world.gmcp(a, pkg, data)
  }

  override fun echo(room: PluginRoom, text: String, exclude: List<PluginActor>) =
    world.echo(
      roomOf(room, "echo(room)"),
      text,
      *exclude.map { actorOf(it, "echo(exclude)") }.toTypedArray(),
    )

  override fun damage(
    target: PluginActor,
    amount: Int,
    type: String,
    vital: String,
    source: PluginActor?,
  ) {
    require(amount >= 0) { "damage(amount): can't be negative; use heal()" }
    val damageType =
      declared(type.lowercase(), world.schema.damageTypes, "damage type", "damage(type)")
    val name = declared(vital, world.schema.vitalNames, "vital", "damage(vital)")
    world.damage(
      actorOf(target, "damage(target)"),
      mapOf(name to amount),
      DamageType(damageType),
      source?.let { actorOf(it, "damage(source)") },
    )
  }

  override fun heal(target: PluginActor, amount: Int, vital: String, source: PluginActor?) {
    require(amount >= 0) { "heal(amount): can't be negative; use damage()" }
    val name = declared(vital, world.schema.vitalNames, "vital", "heal(vital)")
    world.heal(
      actorOf(target, "heal(target)"),
      name,
      amount,
      source?.let { actorOf(it, "heal(source)") },
    )
  }

  override fun applyAffect(
    target: PluginActor,
    name: String,
    duration: Int,
    statDeltas: Map<String, Int>,
    flags: Set<String>,
    maxVitalDeltas: Map<String, Int>,
    resistDeltas: Map<String, Int>,
  ) {
    require(name.isNotBlank()) { "apply_affect(name): must not be blank" }
    require(duration >= 1) { "apply_affect(duration): must be at least 1" }
    for (stat in statDeltas.keys) declared(
      stat,
      world.schema.statNames,
      "stat",
      "apply_affect(statDeltas)",
    )
    for (vital in maxVitalDeltas.keys) declared(
      vital,
      world.schema.vitalNames,
      "vital",
      "apply_affect(maxVitalDeltas)",
    )
    for (type in resistDeltas.keys) declared(
      type,
      world.schema.damageTypes,
      "damage type",
      "apply_affect(resistDeltas)",
    )
    world.applyAffect(
      actorOf(target, "apply_affect(target)"),
      AffectSpec(
        name,
        duration.toLong(),
        statDeltas = statDeltas,
        flags = flags,
        maxVitalDeltas = maxVitalDeltas,
        resistDeltas = resistDeltas.mapKeys { DamageType(it.key) },
      ),
    )
  }

  override fun removeAffect(target: PluginActor, name: String): Boolean =
    world.removeAffect(actorOf(target, "remove_affect(target)"), name)

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
  ) {
    val target = itemOf(item, "apply_item_affect(item)")
    require(name.isNotBlank()) { "apply_item_affect(name): must not be blank" }
    require(duration == null || duration >= 1) { "apply_item_affect(duration): must be at least 1" }
    require(applies == "worn" || applies == "carried") {
      "apply_item_affect(applies): must be 'worn' or 'carried', got '$applies'"
    }
    val scope = AffectScope.valueOf(applies.uppercase())
    for (stat in statDeltas.keys) declared(
      stat,
      world.schema.statNames,
      "stat",
      "apply_item_affect(statDeltas)",
    )
    val channelNames = world.schema.allChannels.map { it.name }
    for (channel in senseDeltas.keys) declared(
      channel,
      channelNames,
      "channel",
      "apply_item_affect(senseDeltas)",
    )
    for (vital in maxVitalDeltas.keys) declared(
      vital,
      world.schema.vitalNames,
      "vital",
      "apply_item_affect(maxVitalDeltas)",
    )
    for (type in resistDeltas.keys) declared(
      type,
      world.schema.damageTypes,
      "damage type",
      "apply_item_affect(resistDeltas)",
    )
    world.applyItemAffect(
      target,
      AffectSpec(
        name,
        duration?.toLong(),
        statDeltas = statDeltas,
        flags = flags,
        senseDeltas = senseDeltas.mapKeys { Channel(it.key) },
        maxVitalDeltas = maxVitalDeltas,
        resistDeltas = resistDeltas.mapKeys { DamageType(it.key) },
      ),
      scope,
    )
  }

  override fun removeItemAffect(item: PluginItem, name: String): Boolean =
    world.removeItemAffect(itemOf(item, "remove_item_affect(item)"), name)

  override fun addTag(item: PluginItem, tag: String): Boolean =
    world.addItemTag(itemOf(item, "add_tag(item)"), tag)

  override fun removeTag(item: PluginItem, tag: String): Boolean =
    world.removeItemTag(itemOf(item, "remove_tag(item)"), tag)

  override fun transform(item: PluginItem, label: String) {
    val target = itemOf(item, "transform(item)")
    val l =
      runCatching { Label(label) }.getOrNull()
        ?: throw IllegalArgumentException("transform(label): '$label' is not an item")
    require(world.protos.items.containsKey(l)) { "transform(label): '$label' is not an item" }
    world.transformItem(target, l)
  }

  override fun setCharges(item: PluginItem, charges: Int) {
    world.setCharges(itemOf(item, "set_charges(item)"), charges)
  }

  override fun teleport(actor: PluginActor, room: PluginRoom): String? =
    world.teleport(actorOf(actor, "teleport(actor)"), roomOf(room, "teleport(room)"))

  override fun spawnItem(label: String, to: PluginEntity, count: Int): List<PluginItem> {
    val l = itemLabel(label, "spawnItem(label)")
    require(count >= 1) { "spawnItem(count): must be at least 1" }
    val at = locationOf(to, "spawnItem(to)")
    val made =
      if (world.protos.item(l).stackable) listOf(world.spawnItem(l, at, count))
      else List(count) { world.spawnItem(l, at) }
    return made.map { ItemFacade(it, world) }
  }

  override fun moveItem(item: PluginItem, to: PluginEntity, count: Int?): PluginItem {
    val i = itemOf(item, "moveItem(item)")
    val n = stackCount(i, count, "moveItem(count)")
    return ItemFacade(world.moveItem(i, locationOf(to, "moveItem(to)"), n), world)
  }

  override fun destroy(item: PluginItem, count: Int?) {
    val i = itemOf(item, "destroy(item)")
    world.destroy(i, stackCount(i, count, "destroy(count)"))
  }

  override fun mergeStack(item: PluginItem): PluginItem =
    ItemFacade(world.stack(itemOf(item, "mergeStack(item)")), world)

  private fun stackCount(item: Item, count: Int?, what: String): Int {
    val n = count ?: item.count
    require(n in 1..item.count) { "$what: must be between 1 and ${item.count}" }
    return n
  }

  /** Where an item put at [to] lies: a room at track index 0, an inventory, or a container. */
  private fun locationOf(to: PluginEntity, what: String): ItemLocation =
    when (val target = entityOf(to, what)) {
      is Room -> ItemLocation.InRoom(target.id, 0)
      is Actor -> ItemLocation.Carried(target.id)
      is Item -> {
        require(target.container != null) { "$what: ${target.name} is not a container" }
        ItemLocation.Contained(target.id)
      }
    }

  override fun wear(actor: PluginActor, item: PluginItem, wield: Boolean): String? =
    world.wearItem(actorOf(actor, "wear(actor)"), itemOf(item, "wear(item)"), wield)

  override fun removeWorn(actor: PluginActor, item: PluginItem): String? =
    world.removeWornItem(actorOf(actor, "removeWorn(actor)"), itemOf(item, "removeWorn(item)"))

  override fun mayWear(actor: PluginActor, item: PluginItem, wield: Boolean): String? =
    world.mayWear(actorOf(actor, "mayWear(actor)"), itemOf(item, "mayWear(item)"), wield)

  override fun mayRemove(actor: PluginActor, item: PluginItem): String? =
    world.mayRemove(actorOf(actor, "mayRemove(actor)"), itemOf(item, "mayRemove(item)"))

  override fun mayDrop(actor: PluginActor, item: PluginItem): String? =
    world.mayDrop(actorOf(actor, "mayDrop(actor)"), itemOf(item, "mayDrop(item)"))

  override fun mayGive(actor: PluginActor, item: PluginItem, to: PluginActor): String? =
    world.mayGive(
      actorOf(actor, "mayGive(actor)"),
      itemOf(item, "mayGive(item)"),
      actorOf(to, "mayGive(to)"),
    )

  override fun mayPut(actor: PluginActor, item: PluginItem, container: PluginItem): String? =
    world.mayPut(
      actorOf(actor, "mayPut(actor)"),
      itemOf(item, "mayPut(item)"),
      itemOf(container, "mayPut(container)"),
    )

  override fun mayEnter(actor: PluginActor, room: PluginRoom): String? =
    world.mayEnter(actorOf(actor, "mayEnter(actor)"), roomOf(room, "mayEnter(room)"))

  override fun mayLearn(actor: PluginActor, skill: String, proficiency: Int): String? {
    needs(world, Feature.SKILLS, "mayLearn")
    val label = knownSkill(skill, "mayLearn").label
    require(proficiency in 0..100) {
      "mayLearn(proficiency): proficiency must be 0..100, got $proficiency"
    }
    return world.mayLearn(preparedActorOf(actor, "mayLearn(actor)"), label, proficiency)
  }

  override fun fits(item: PluginItem, to: PluginEntity, count: Int?): Int {
    val i = itemOf(item, "fits(item)")
    return world.fits(i, fitsTarget(to), count ?: i.count)
  }

  override fun fits(label: String, to: PluginEntity, count: Int?): Int =
    world.fits(itemLabel(label, "fits(label)"), fitsTarget(to), count ?: 1)

  private fun itemLabel(label: String, what: String): Label {
    val l = runCatching { Label(label) }.getOrNull()
    require(l != null && world.protos.items.containsKey(l)) { "$what: '$label' is not an item" }
    return l
  }

  private fun mobLabel(label: String, what: String): Label {
    val l = runCatching { Label(label) }.getOrNull()
    require(l != null && world.protos.mobs.containsKey(l)) { "$what: '$label' is not a mob" }
    return l
  }

  private fun fitsTarget(to: PluginEntity): Entity {
    val target = entityOf(to, "fits(to)")
    require(target !is Item || target.container != null) {
      "fits(to): ${target.name} is not a container"
    }
    return target
  }

  override fun setChoices(actor: PluginActor, changes: Map<String, String?>): String? {
    val a = actorOf(actor, "setChoices(actor)")
    require(a is Player) { "setChoices(actor): mob has no choices" }
    requireNotNull(world.creation) { "setChoices: no creation provider is registered" }
    return world.setChoices(a, changes)
  }

  override fun grantTrait(actor: PluginActor, trait: String): String? {
    require(trait in world.traits) { "grantTrait(trait): unknown trait '$trait'" }
    val a = preparedActorOf(actor, "grantTrait(actor)")
    return world.grantTrait(a, trait)
  }

  override fun removeTrait(actor: PluginActor, trait: String): String? {
    require(trait in world.traits) { "removeTrait(trait): unknown trait '$trait'" }
    val a = preparedActorOf(actor, "removeTrait(actor)")
    return world.removeTrait(a, trait)
  }

  override fun setSkill(actor: PluginActor, skill: String, proficiency: Int): String? {
    needs(world, Feature.SKILLS, "setSkill")
    val label = knownSkill(skill, "setSkill").label
    require(proficiency in 0..100) {
      "setSkill(proficiency): proficiency must be 0..100, got $proficiency"
    }
    val a = preparedActorOf(actor, "setSkill(actor)")
    return world.setSkill(a, label, proficiency)
  }

  override fun removeSkill(actor: PluginActor, skill: String): Boolean {
    needs(world, Feature.SKILLS, "removeSkill")
    val a = preparedActorOf(actor, "removeSkill(actor)")
    return world.removeSkill(a, skillLabel(skill, "removeSkill"))
  }

  override fun reportSkillUse(
    actor: PluginActor,
    skill: String,
    target: PluginActor?,
    item: PluginItem?,
  ) {
    needs(world, Feature.SKILLS, "reportSkillUse")
    val proto = knownSkill(skill, "reportSkillUse")
    val a = actorOf(actor, "reportSkillUse(actor)")
    val t = target?.let { actorOf(it, "reportSkillUse(target)") }
    world.reportSkillUse(a, proto, t, item?.let { itemOf(it, "reportSkillUse(item)") })
  }

  override fun skillRoll(
    actor: PluginActor,
    skill: String,
    item: PluginItem?,
    target: PluginActor?,
    event: String,
    adjust: Int,
  ): PluginSkillRoll {
    needs(world, Feature.SKILLS, "skillRoll")
    require(ROLL_EVENT.matches(event)) {
      "skillRoll(event): must match [a-z][a-z0-9_]*, got '$event'"
    }
    val c =
      RulesContext(
        world,
        actorOf(actor, "skillRoll(actor)"),
        target?.let { actorOf(it, "skillRoll(target)") },
        skill = knownSkill(skill, "skillRoll"),
        item = item?.let { itemOf(it, "skillRoll(item)") },
        event = event,
      )
    return pluginSkillRoll(world, c, adjust)
  }

  private fun skillLabel(skill: String, what: String): Label =
    runCatching { Label(skill) }.getOrNull()
      ?: throw IllegalArgumentException("$what(skill): invalid skill label '$skill'")

  private fun knownSkill(skill: String, what: String): SkillProto =
    world.protos.skills[skillLabel(skill, what)]
      ?: throw IllegalArgumentException("$what(skill): unknown skill '$skill'")

  override fun startTask(
    actor: PluginActor,
    pulses: Int,
    name: String,
    lag: Int,
    interrupt: PluginTaskInterrupt,
    onStop: ((PluginTaskStop) -> Unit)?,
    onDone: (PluginTaskCall) -> Unit,
  ): String? {
    val a = actorOf(actor, "startTask(actor)")
    val spec = TaskSpec(name, pulses, lag, interrupt.toCore())
    return world.startTask(a, spec, PluginTaskCallbacks(pluginId, onDone, onStop))
  }

  override fun stopTask(actor: PluginActor): Boolean =
    world.stopTask(actorOf(actor, "stopTask(actor)"))

  override fun setItemName(item: PluginItem, name: String?): Boolean =
    world.setItemName(itemOf(item, "setItemName(item)"), name)

  override fun setBaseStat(actor: PluginActor, stat: String, value: Int): String? {
    declared(stat, world.schema.statNames, "stat", "setBaseStat(stat)")
    val a = preparedActorOf(actor, "setBaseStat(actor)")
    world.setBaseStat(a, stat, value)
    return null
  }

  override fun setBaseMaxVital(actor: PluginActor, vital: String, value: Int): String? {
    declared(vital, world.schema.vitalNames, "vital", "setBaseMaxVital(vital)")
    val a = preparedActorOf(actor, "setBaseMaxVital(actor)")
    world.setBaseMaxVital(a, vital, value)
    return null
  }

  override fun setBaseResist(actor: PluginActor, type: String, value: Int): String? {
    val dt = declared(type, world.schema.damageTypes, "damage type", "setBaseResist(type)")
    val a = preparedActorOf(actor, "setBaseResist(actor)")
    world.setBaseResist(a, DamageType(dt), value)
    return null
  }

  override fun awardXp(actor: PluginActor, amount: Long) {
    needs(world, Feature.LEVELING, "awardXp")
    require(amount >= 1L) { "awardXp(amount): must be at least 1" }
    world.awardXp(actorOf(actor, "awardXp(actor)"), amount)
  }

  override fun takeXp(actor: PluginActor, amount: Long): Long {
    needs(world, Feature.LEVELING, "takeXp")
    require(amount >= 1L) { "takeXp(amount): must be at least 1" }
    return world.takeXp(actorOf(actor, "takeXp(actor)"), amount)
  }

  override fun levelUp(actor: PluginActor): Boolean {
    needs(world, Feature.LEVELING, "levelUp")
    return world.levelUpAndNotify(actorOf(actor, "levelUp(actor)"))
  }

  override fun levelDown(actor: PluginActor, count: Int): Int {
    needs(world, Feature.LEVELING, "levelDown")
    require(count >= 1) { "levelDown(count): must be at least 1" }
    return world.levelDown(actorOf(actor, "levelDown(actor)"), count)
  }

  // Doors and containers, as `ctx` sets them: no hook runs, no key is named, nothing is
  // printed; locking a door or container without a key fails.

  private fun door(room: PluginRoom, dir: String, verb: String, to: (DoorState) -> DoorState) {
    val r = roomOf(room, "$verb(room)")
    val d = Direction.parse(dir)
    val door =
      r.exits[d]?.door ?: throw IllegalArgumentException("$verb(): ${r.label} has no door $d")
    world.setDoor(r, d, to(door.state))
  }

  override fun openDoor(room: PluginRoom, dir: String) =
    door(room, dir, "openDoor") { DoorState.OPEN }

  override fun closeDoor(room: PluginRoom, dir: String) =
    door(room, dir, "closeDoor") { if (it == DoorState.LOCKED) it else DoorState.CLOSED }

  override fun lockDoor(room: PluginRoom, dir: String) =
    door(room, dir, "lockDoor") { DoorState.LOCKED }

  override fun unlockDoor(room: PluginRoom, dir: String) =
    door(room, dir, "unlockDoor") { if (it == DoorState.LOCKED) DoorState.CLOSED else it }

  private fun container(item: PluginItem, verb: String, to: (DoorState) -> DoorState) {
    val i = itemOf(item, "$verb(item)")
    val box =
      i.container ?: throw IllegalArgumentException("$verb(item): ${i.name} is not a container")
    world.setContainerState(i, to(box.state))
  }

  override fun openContainer(item: PluginItem) = container(item, "openContainer") { DoorState.OPEN }

  override fun closeContainer(item: PluginItem) =
    container(item, "closeContainer") { if (it == DoorState.LOCKED) it else DoorState.CLOSED }

  override fun lockContainer(item: PluginItem) =
    container(item, "lockContainer") { DoorState.LOCKED }

  override fun unlockContainer(item: PluginItem) =
    container(item, "unlockContainer") { if (it == DoorState.LOCKED) DoorState.CLOSED else it }

  override fun say(speaker: PluginActor, text: String) {
    world.say(actorOf(speaker, "say(speaker)"), text)
  }

  override fun emote(actor: PluginActor, text: String) {
    world.emote(actorOf(actor, "emote(actor)"), text)
  }

  override fun emit(
    room: PluginRoom,
    channel: String,
    event: String,
    intensity: Int,
    text: String?,
    actor: PluginActor?,
  ) {
    declared(channel, world.schema.allChannels.map { it.name }, "channel", "emit(channel)")
    require(event.isNotBlank()) { "emit(event): must not be empty" }
    val at = roomOf(room, "emit(room)")
    val cause = actor?.let { actorOf(it, "emit(actor)") }
    if (intensity > 0) world.perceive(event, Channel(channel), at, cause, text, intensity)
  }

  /** A non-empty simulation reason, as the `ctx` reason verbs require. */
  private fun simReason(reason: String, verb: String): String {
    require(reason.isNotEmpty()) { "$verb(reason): must not be empty" }
    return reason
  }

  override fun setActive(entity: PluginEntity, reason: String) {
    world.setActive(entityOf(entity, "setActive(entity)"), simReason(reason, "setActive"))
  }

  override fun clearActive(entity: PluginEntity, reason: String) {
    world.clearActive(entityOf(entity, "clearActive(entity)"), simReason(reason, "clearActive"))
  }

  override fun setAnchor(entity: PluginEntity, reason: String) {
    world.setAnchor(entityOf(entity, "setAnchor(entity)"), simReason(reason, "setAnchor"))
  }

  override fun clearAnchor(entity: PluginEntity, reason: String) {
    world.clearAnchor(entityOf(entity, "clearAnchor(entity)"), simReason(reason, "clearAnchor"))
  }

  override fun clearStanding(holder: Any, subject: Any?) {
    needs(world, Feature.STANDING, "clearStanding")
    val h = standingHolder(holder, "clearStanding")
    val sub = subject?.let { standingSubject(it, h, "clearStanding") }
    world.clearStanding(h, sub)
  }

  override fun standingHistory(
    holder: Any,
    subject: Any,
    since: Long,
    via: PluginActor?,
    event: String?,
  ): PluginStandingTotals {
    needs(world, Feature.STANDING, "standingHistory")
    val h = standingHolder(holder, "standingHistory")
    val sub = standingSubject(subject, h, "standingHistory")
    val viaId = via?.let { mobOf(it, "standingHistory(via)").id }
    val totals = world.standingHistory(h, sub, since.coerceAtLeast(0L), viaId, event)
    return PluginStandingTotals(totals.gained, totals.lost)
  }

  override fun roll(dice: String): Int {
    val d =
      try {
        Dice.parse(dice)
      } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("roll(): ${e.message}", e)
      }
    return d.roll(world.rng)
  }

  // Mobs: spawning, actions, summons, owners and leashes.

  override fun spawnMob(label: String, room: PluginRoom, count: Int): List<PluginActor> {
    val l = mobLabel(label, "spawnMob(label)")
    require(count >= 1) { "spawnMob(count): must be at least 1" }
    val r = roomOf(room, "spawnMob(room)")
    return List(count) { world.spawnMob(l, r, 0) }.map { ActorFacade(it, world) }
  }

  override fun act(mob: PluginActor, commandLine: String): Boolean =
    world.act(mobOf(mob, "act(mob)"), commandLine)

  override fun actMove(mob: PluginActor, dir: String): Boolean =
    world.actMove(mobOf(mob, "actMove(mob)"), Direction.parse(dir))

  override fun actAttack(mob: PluginActor, target: PluginActor): Boolean {
    needs(world, Feature.COMBAT, "actAttack")
    return world.actAttack(mobOf(mob, "actAttack(mob)"), actorOf(target, "actAttack(target)"))
  }

  override fun actCast(mob: PluginActor, skill: String, target: PluginActor?): Boolean {
    needs(world, Feature.SKILLS, "actCast")
    val proto =
      world.protos.skills[skillLabel(skill, "actCast")]
        ?: throw IllegalArgumentException("actCast(skill): there is no skill $skill")
    return world.actCast(
      mobOf(mob, "actCast(mob)"),
      proto,
      target?.let { actorOf(it, "actCast(target)") },
    )
  }

  override fun moveToward(mob: PluginActor, dir: String): Boolean =
    world.actMove(mobOf(mob, "moveToward(mob)"), Direction.parse(dir))

  override fun moveToward(mob: PluginActor, room: PluginRoom): Boolean {
    val m = mobOf(mob, "moveToward(mob)")
    val dest = roomOf(room, "moveToward(room)")
    val step = world.pathTo(m.room, dest)?.firstOrNull() ?: return false
    return world.actMove(m, step)
  }

  override fun moveToward(mob: PluginActor, target: PluginActor): Boolean {
    val m = mobOf(mob, "moveToward(mob)")
    val t = actorOf(target, "moveToward(target)")
    if (t.room === m.room) {
      return if (t.trackIndex == m.trackIndex) false else world.actAdvance(m, t)
    }
    val step = world.pathTo(m.room, t.room)?.firstOrNull() ?: return false
    return world.actMove(m, step)
  }

  override fun summon(
    caster: PluginActor,
    label: String,
    count: Int,
    duration: Int?,
  ): List<PluginActor> {
    val l = mobLabel(label, "summon(label)")
    require(count >= 1) { "summon(count): must be at least 1" }
    require(duration == null || duration >= 1) { "summon(duration): must be at least 1" }
    val c = actorOf(caster, "summon(caster)")
    return world.summon(c, Effect.Summon(l, count, duration)).map { ActorFacade(it, world) }
  }

  override fun setOwner(mob: PluginActor, player: PluginActor): Boolean =
    world.setOwner(mobOf(mob, "setOwner(mob)"), playerOf(player, "setOwner(player)"))

  override fun clearOwner(mob: PluginActor): Boolean =
    world.clearOwner(mobOf(mob, "clearOwner(mob)"))

  override fun setLeash(mob: PluginActor, leash: Int?) {
    val m = mobOf(mob, "setLeash(mob)")
    require(leash == null || leash >= 0) { "setLeash(leash): must be an int of at least 0 or null" }
    world.setLeash(m, leash)
  }

  override fun resetLeash(mob: PluginActor) {
    world.resetLeash(mobOf(mob, "resetLeash(mob)"))
  }

  // Combat and groups, as `ctx` engages and manages them.

  override fun startCombat(a: PluginActor, b: PluginActor) {
    needs(world, Feature.COMBAT, "startCombat")
    val x = actorOf(a, "startCombat(a)")
    val y = actorOf(b, "startCombat(b)")
    if (x === y) throw IllegalArgumentException("startCombat(): an actor can't fight itself")
    if (x.room !== y.room)
      throw IllegalArgumentException(
        "startCombat(): ${x.name} and ${y.name} are not in the same room"
      )
    for (d in listOf(x, y)) if (world.isDying(d))
      throw IllegalArgumentException("startCombat(): ${d.name} is dying")
    world.startCombat(x, y)
  }

  override fun stopCombat(a: PluginActor, b: PluginActor?) {
    needs(world, Feature.COMBAT, "stopCombat")
    world.stopCombat(actorOf(a, "stopCombat(a)"), b?.let { actorOf(it, "stopCombat(b)") })
  }

  override fun clearContributions(victim: PluginActor, contributor: PluginActor?) {
    world.clearContributions(
      actorOf(victim, "clearContributions(victim)"),
      contributor?.let { actorOf(it, "clearContributions(contributor)") },
    )
  }

  override fun groupAdd(member: PluginActor, to: PluginActor) {
    world.groupAdd(playerOf(member, "groupAdd(member)"), playerOf(to, "groupAdd(to)"))
  }

  override fun groupRemove(member: PluginActor) {
    world.groupRemove(playerOf(member, "groupRemove(member)"))
  }

  override fun groupSetLeader(member: PluginActor) {
    world.groupSetLeader(playerOf(member, "groupSetLeader(member)"))
  }

  override fun groupDisband(member: PluginActor) {
    world.groupDisband(playerOf(member, "groupDisband(member)"))
  }

  // World queries, as `ctx` answers them.

  private fun bandOf(band: String, what: String): Band =
    try {
      Band.parse(band)
    } catch (e: IllegalArgumentException) {
      throw IllegalArgumentException("$what(band): '$band' is not one of 'melee', 'near', 'far'", e)
    }

  /** Actors of [a]'s room within [reach] that are simulated, excluding [a] itself. */
  private fun nearby(a: Actor, reach: Band): List<Actor> =
    a.room.actors.filter {
      it !== a && world.isSimulated(it) && reach.reaches(Band.between(a.trackIndex, it.trackIndex))
    }

  override fun enemiesNear(actor: PluginActor, band: String): List<PluginActor> {
    needs(world, Feature.COMBAT, "enemiesNear")
    val a = actorOf(actor, "enemiesNear(actor)")
    val foes = world.opponentsOf(a).toSet()
    return nearby(a, bandOf(band, "enemiesNear"))
      .filter { it in foes }
      .map { ActorFacade(it, world) }
  }

  override fun alliesNear(actor: PluginActor, band: String): List<PluginActor> {
    needs(world, Feature.COMBAT, "alliesNear")
    val a = actorOf(actor, "alliesNear(actor)")
    val ga = world.groupOf(a)
    return nearby(a, bandOf(band, "alliesNear"))
      .filter { x ->
        x.id !in a.fighting && (x.isPlayer == a.isPlayer || (ga != null && world.groupOf(x) === ga))
      }
      .map { ActorFacade(it, world) }
  }

  override fun pathTo(from: PluginRoom, to: PluginRoom): List<String>? =
    world.pathTo(roomOf(from, "pathTo(from)"), roomOf(to, "pathTo(to)"))?.map { it.name }

  override fun plan(
    facts: Map<String, Any>,
    actions: List<PluginPlanAction>,
    goal: Map<String, Any>,
    maxExpansions: Int,
  ): List<String>? {
    val res =
      GoapPlanner.plan(
        planFactMap(facts, "facts"),
        actions.map {
          GoapAction(
            name = it.name,
            cost = it.cost,
            pre = planFactMap(it.pre, "pre of '${it.name}'"),
            effects = planFactMap(it.effects, "effects of '${it.name}'"),
          )
        },
        planFactMap(goal, "goal"),
        maxExpansions,
      )
    return when (res) {
      is PlanResult.Found -> res.steps
      is PlanResult.NotFound -> null
    }
  }

  /** A planning fact map for [GoapPlanner.plan]; values must be Boolean, Int, Long or String. */
  private fun planFactMap(v: Map<String, Any>, what: String): Map<String, FactValue> =
    v.mapValues { (k, value) ->
      when (value) {
        is Boolean -> FactValue.Bool(value)
        is Int -> FactValue.Num(value.toLong())
        is Long -> FactValue.Num(value)
        is String -> FactValue.Text(value)
        else ->
          throw IllegalArgumentException(
            "plan($what): fact '$k' must be a Boolean, Int, Long or String"
          )
      }
    }

  override fun groupOf(actor: PluginActor): PluginGroup? {
    val a = actorOf(actor, "groupOf(actor)")
    val g = world.groupOf(a) ?: return null
    return PluginGroup(
      g.id.toLong(),
      ActorFacade(g.leader, world),
      world.groupMembers(a).map { ActorFacade(it, world) },
    )
  }

  override fun groupMembers(actor: PluginActor): List<PluginActor> {
    val a = actorOf(actor, "groupMembers(actor)")
    return world.groupMembers(a).map { ActorFacade(it, world) }
  }

  override fun disposition(holder: PluginActor, subject: PluginActor): Int {
    needs(world, Feature.STANDING, "disposition")
    return world.disposition(
      actorOf(holder, "disposition(holder)"),
      actorOf(subject, "disposition(subject)"),
    )
  }

  override fun standing(holder: Any, subject: Any): Int {
    needs(world, Feature.STANDING, "standing")
    val h = standingHolder(holder, "standing")
    return world.standing(h, standingSubject(subject, h, "standing"))
  }

  override fun factionsOf(actor: PluginActor): List<String> {
    needs(world, Feature.STANDING, "factionsOf")
    val a = actorOf(actor, "factionsOf(actor)")
    return world.factionsOf(a).map { it.v }
  }

  override fun raiseStandingEvent(
    affected: PluginActor,
    actor: PluginActor,
    event: String,
    amount: Long,
    item: PluginItem?,
  ) {
    needs(world, Feature.STANDING, "raiseStandingEvent")
    val aff = actorOf(affected, "raiseStandingEvent(affected)")
    val act = actorOf(actor, "raiseStandingEvent(actor)")
    if (event in BUILTIN_STANDING_EVENTS) {
      throw IllegalArgumentException("raiseStandingEvent(): '$event' is a built-in event")
    }
    if (!STANDING_EVENT_NAME.matches(event)) {
      throw IllegalArgumentException(
        "raiseStandingEvent(): event name must match [a-z][a-z0-9_]*, got '$event'"
      )
    }
    if (amount < 0) {
      throw IllegalArgumentException(
        "raiseStandingEvent(amount): must be non-negative, got $amount"
      )
    }
    val itm = item?.let { itemOf(it, "raiseStandingEvent(item)") }
    world.raiseStandingEvent(
      StandingEvent(event = event, affected = aff, actor = act, amount = amount, item = itm)
    )
  }

  override fun adjustStanding(holder: Any, subject: Any, amount: Int) {
    needs(world, Feature.STANDING, "adjustStanding")
    val h = standingHolder(holder, "adjustStanding")
    world.applyStanding(
      h,
      standingSubject(subject, h, "adjustStanding"),
      amount,
      event = "adjust",
      via = null,
    )
  }

  override fun memories(
    mob: PluginActor,
    subject: PluginActor?,
    event: String?,
    since: Long,
  ): List<PluginMemory> {
    needs(world, Feature.COMBAT, "memories")
    val m = mobOf(mob, "memories(mob)")
    val sub = subject?.let { actorOf(it, "memories(subject)") }
    return world.memoriesOf(m, sub, event, since.coerceAtLeast(0L)).map { mem ->
      PluginMemory(
        subject =
          world.actor(mem.key.subject)?.takeIf { it.inWorld }?.let { ActorFacade(it, world) },
        event = mem.key.event,
        skill = mem.key.skill?.v,
        target =
          mem.key.target?.let { tid ->
            world.actor(tid)?.takeIf { it.inWorld }?.let { ActorFacade(it, world) }
          },
        firstPulse = mem.firstPulse,
        pulse = mem.pulse,
        count = mem.count,
        amount = mem.amount,
        channel = mem.channel?.name,
      )
    }
  }

  override fun remember(
    mob: PluginActor,
    subject: PluginActor,
    event: String,
    skill: String?,
    amount: Long,
    target: PluginActor?,
  ) {
    needs(world, Feature.COMBAT, "remember")
    val m = mobOf(mob, "remember(mob)")
    val sub = actorOf(subject, "remember(subject)")
    if (!STANDING_EVENT_NAME.matches(event)) {
      throw IllegalArgumentException("remember(event): must match [a-z][a-z0-9_]*, got '$event'")
    }
    val sk =
      if (skill == null) {
        null
      } else {
        val l =
          runCatching { Label(skill) }.getOrNull()
            ?: throw IllegalArgumentException("remember(skill): invalid skill label '$skill'")
        if (l !in world.protos.skills) {
          throw IllegalArgumentException("remember(skill): unknown skill '$skill'")
        }
        l
      }
    if (amount < 0L) {
      throw IllegalArgumentException("remember(amount): must be non-negative, got $amount")
    }
    val tgt = target?.let { actorOf(it, "remember(target)") }
    world.remember(m, sub, event, sk, amount, tgt)
  }

  override fun forget(mob: PluginActor, subject: PluginActor?, event: String?): Int {
    needs(world, Feature.COMBAT, "forget")
    val m = mobOf(mob, "forget(mob)")
    val subId = subject?.let { actorOf(it, "forget(subject)").id }
    return world.forget(m, subId, event)
  }

  private fun standingHolder(holder: Any, fn: String): StandingHolder =
    when (holder) {
      is PluginActor ->
        when (val a = actorOf(holder, "$fn(holder)")) {
          is Mob -> StandingHolder.Personal(a)
          is Player ->
            throw IllegalArgumentException(
              "$fn(): a player has no personal standing; name a faction"
            )
        }
      is String -> StandingHolder.Faction(resolveFactionLabel(holder, fn, "holder"))
      else ->
        throw IllegalArgumentException(
          "$fn(): holder must be a mob or faction label, got ${holder::class.simpleName}"
        )
    }

  private fun standingSubject(subject: Any, holder: StandingHolder, fn: String): StandingSubject =
    when (subject) {
      is PluginActor -> StandingSubject.Of(actorOf(subject, "$fn(subject)").id)
      is String -> {
        if (holder is StandingHolder.Personal) {
          throw IllegalArgumentException(
            "$fn(): a personal standing record cannot have a faction subject"
          )
        }
        StandingSubject.Faction(resolveFactionLabel(subject, fn, "subject"))
      }
      else ->
        throw IllegalArgumentException(
          "$fn(): subject must be an actor or faction label, got ${subject::class.simpleName}"
        )
    }

  private fun resolveFactionLabel(labelStr: String, fnName: String, paramName: String): Label {
    val l =
      runCatching { Label(labelStr) }.getOrNull()
        ?: throw IllegalArgumentException("$fnName($paramName): '$labelStr' is not a valid label")
    if (!world.protos.isFaction(l)) {
      val known = world.protos.factions.keys.map { it.v } + world.protos.mobs.keys.map { it.v }
      throw IllegalArgumentException(
        "$fnName(): unknown faction '$labelStr'${didYouMean(l.v, known)}"
      )
    }
    return l
  }

  private fun key(key: String, write: Boolean, what: String): String {
    val (ns, short) =
      when {
        ':' in key -> key.substringBefore(':') to key.substringAfter(':')
        pluginId != null -> pluginId to key
        else ->
          throw IllegalArgumentException(
            "$what: '$key' needs a namespace ('ns:key') outside a plugin's own builtins"
          )
      }
    require(Regex("[A-Za-z0-9_]+").matches(short) && Regex("[a-z0-9_]+").matches(ns)) {
      "$what: '$key' is not a valid attribute key"
    }
    if (write)
      require(ns == pluginId) {
        "$what: a plugin can only write its own namespace '${pluginId ?: "?"}', not '$ns'"
      }
    return "$ns:$short"
  }

  override fun getAttr(entity: PluginEntity, key: String, default: Any?): Any? =
    entityOf(entity, "get_attr(entity)")
      .customAttributes[key(key, write = false, "get_attr(key)")]
      ?.let(::fromAttr) ?: default

  override fun setAttr(entity: PluginEntity, key: String, value: Any?) {
    val e = preparedEntityOf(entity, "set_attr(entity)")
    val k = key(key, write = true, "set_attr(key)")
    if (value == null) e.customAttributes.remove(k)
    else e.customAttributes[k] = toAttr(value, "set_attr(value)")
    e.dirty = true
  }

  override fun delAttr(entity: PluginEntity, key: String) {
    val e = preparedEntityOf(entity, "del_attr(entity)")
    if (e.customAttributes.remove(key(key, write = true, "del_attr(key)")) != null) e.dirty = true
  }

  override val schema: PluginSchema
    get() =
      world.schema.let { s ->
        PluginSchema(
          s.stats.map { PluginNamed(it.name, it.label) },
          s.vitals.map { PluginNamed(it.name, it.label) },
          s.damageTypes,
        )
      }

  override val pulse: Long
    get() = world.pulse

  override val seed: Long
    get() = world.config.seed

  override val rng: PluginRng
    get() = RngFacade(world)

  override fun rooms(): List<PluginRoom> =
    world.rooms.sortedBy { it.label.v }.map { RoomFacade(it, world) }

  override fun room(label: String): PluginRoom? =
    runCatching { Label(label) }
      .getOrNull()
      ?.let { l -> world.rooms.firstOrNull { it.label == l } }
      ?.let { RoomFacade(it, world) }

  override fun regions(): List<String> = world.rooms.flatMap { it.regions }.toSortedSet().toList()

  override fun skill(label: String): PluginSkill? =
    runCatching { Label(label) }
      .getOrNull()
      ?.let { world.protos.skills[it] }
      ?.let { SkillFacade(it) }

  override fun itemInfo(label: String): PluginItemInfo? =
    runCatching { Label(label) }.getOrNull()?.let { world.protos.items[it] }?.let(::itemInfoOf)

  override fun rules(name: String, fields: Map<String, Any?>, default: Any): Any {
    val type = Facades.rulesExportsOf(world, pluginId)[name]
    require(type != null) { "rules(): '$name' is not declared by plugin '$pluginId'" }
    val int = type == "int"
    require(if (int) default is Long || default is Int else default is Boolean) {
      "rules(): default for '$name' must be ${if (int) "an int" else "a bool"}"
    }
    val fallback = if (default is Int) default.toLong() else default
    fun unwrap(v: Any?): Any? =
      when (v) {
        is ActorFacade -> actorOf(v, "rules($name)")
        is ItemFacade -> itemOf(v, "rules($name)")
        is List<*> -> v.map(::unwrap)
        is Map<*, *> -> v.entries.associate { (k, x) -> k as String to unwrap(x) }
        else -> v
      }
    val rest =
      fields.filterKeys { it !in RULES_CTX_FIELDS && it != "base" }.mapValues { unwrap(it.value) }
    val actor = unwrap(fields["actor"]) as? Actor
    val skill =
      when (val s = fields["skill"]) {
        is SkillFacade -> s.skill
        is String -> runCatching { Label(s) }.getOrNull()?.let { world.protos.skills[it] }
        else -> null
      }
    val c =
      RulesContext(
        world,
        actor = actor,
        target = unwrap(fields["target"]) as? Actor,
        source = unwrap(fields["source"]) as? Actor,
        skill = skill,
        item = unwrap(fields["item"]) as? Item,
        room = actor?.room,
        base = fields["base"],
        args = rest,
      )
    val result = world.rules.pluginExport(name, type, c) ?: fallback
    world.traceRules(actor, "rules.$name → $result")
    return result
  }

  override fun diagnose(message: String) =
    world.record(
      Diagnostic(
        world.pulse,
        "plugin",
        if (pluginId != null) "plugin '$pluginId': $message" else message,
      )
    )

  override fun log(message: String) =
    logSink(if (pluginId != null) "plugin '$pluginId': $message" else message)
}

/** A handler's view of the world, so it can't be cast to [PluginWorld] and change anything. */
internal class GmcpWorldFacade(inner: WorldFacade) : PluginGmcpWorld by inner

/** One inbound GMCP message, as [PluginGmcpCall], with the once-per-message [command]. */
internal class GmcpInputCall(
  override val world: PluginGmcpWorld,
  override val actor: PluginActor,
  override val pkg: String,
  override val data: Any?,
  private val send: (String) -> Boolean,
) : PluginGmcpCall {
  private var used = false

  override fun command(line: String): Boolean {
    check(!used) { "command() runs at most one line per GMCP message" }
    used = true
    return send(line)
  }
}

/**
 * [v] must be null, String, Boolean, Int, Long, Double, an iterable or a Map with String keys. A
 * GMCP payload or a custom hook handler's result.
 */
internal fun checkPlain(v: Any?, what: String) {
  when (v) {
    null,
    is String,
    is Boolean,
    is Int,
    is Long,
    is Double -> Unit
    is Iterable<*> -> v.forEach { checkPlain(it, what) }
    is Map<*, *> -> {
      require(v.keys.all { it is String }) { "$what: map keys must be strings" }
      v.values.forEach { checkPlain(it, what) }
    }
    else -> throw IllegalArgumentException("$what: ${v::class.java.name} is not a plain value")
  }
}

/**
 * A custom hook handler's result in the form scripts read: null, Long, String, Boolean, List or a
 * Map with String keys. Ints widen to Long and any iterable becomes a list; floats and anything
 * else are refused, since scripts can't receive them.
 */
internal fun toHookResult(v: Any?, what: String): Any? =
  when (v) {
    null,
    is String,
    is Boolean,
    is Long -> v
    is Int -> v.toLong()
    is Iterable<*> -> v.map { toHookResult(it, what) }
    is Map<*, *> ->
      v.entries.associate { (k, x) ->
        require(k is String) { "$what: map keys must be strings" }
        k to toHookResult(x, what)
      }
    else -> throw IllegalArgumentException("$what: ${v::class.java.name} is not a plain value")
  }

/** A plain value as an attribute: Int/Long, String, Boolean, List and Map with String keys. */
internal fun toAttr(v: Any, what: String): AttrValue =
  when (v) {
    is Int -> AttrValue.I(v.toLong())
    is Long -> AttrValue.I(v)
    is String -> AttrValue.S(v)
    is Boolean -> AttrValue.B(v)
    is List<*> ->
      AttrValue.L(
        v.map { toAttr(it ?: throw IllegalArgumentException("$what: lists can't hold null"), what) }
      )
    is Map<*, *> ->
      AttrValue.M(
        v.entries.associate { (k, x) ->
          (k as? String ?: throw IllegalArgumentException("$what: map keys must be strings")) to
            toAttr(x ?: throw IllegalArgumentException("$what: maps can't hold null"), what)
        }
      )
    else ->
      throw IllegalArgumentException(
        "$what: ${v::class.simpleName} can't be stored (use Long, String, Boolean, List or Map)"
      )
  }

internal fun fromAttr(a: AttrValue): Any =
  when (a) {
    is AttrValue.I -> a.v
    is AttrValue.S -> a.v
    is AttrValue.B -> a.v
    is AttrValue.L -> a.v.map(::fromAttr)
    is AttrValue.M -> a.v.mapValues { fromAttr(it.value) }
  }
