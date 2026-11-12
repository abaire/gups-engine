package gups.pluginapi.host

import gups.core.AttrValue
import gups.core.Label
import gups.core.Rng
import gups.core.model.Actor
import gups.core.model.AffectScope
import gups.core.model.Channel
import gups.core.model.DamageType
import gups.core.model.Entity
import gups.core.model.Item
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
import gups.core.world.Diagnostic
import gups.core.world.Feature
import gups.core.world.GMCP_PACKAGE_NAME
import gups.core.world.HeldAffect
import gups.core.world.STANDING_EVENT_NAME
import gups.core.world.StandingEvent
import gups.core.world.TaskSpec
import gups.core.world.World
import gups.core.world.addItemTag
import gups.core.world.applyAffect
import gups.core.world.applyItemAffect
import gups.core.world.applyStanding
import gups.core.world.awardXp
import gups.core.world.custodian
import gups.core.world.damage
import gups.core.world.disposition
import gups.core.world.echo
import gups.core.world.factionsOf
import gups.core.world.forget
import gups.core.world.gmcp
import gups.core.world.gmcpWithin
import gups.core.world.grantTrait
import gups.core.world.heal
import gups.core.world.heldAffects
import gups.core.world.isInactive
import gups.core.world.levelDown
import gups.core.world.levelUpAndNotify
import gups.core.world.memoriesOf
import gups.core.world.pendingLevels
import gups.core.world.raiseStandingEvent
import gups.core.world.remember
import gups.core.world.removeAffect
import gups.core.world.removeItemAffect
import gups.core.world.removeItemTag
import gups.core.world.removeSkill
import gups.core.world.removeTrait
import gups.core.world.reportSkillUse
import gups.core.world.send
import gups.core.world.setBaseMaxVital
import gups.core.world.setBaseResist
import gups.core.world.setBaseStat
import gups.core.world.setCharges
import gups.core.world.setChoices
import gups.core.world.setItemName
import gups.core.world.setSkill
import gups.core.world.standing
import gups.core.world.startTask
import gups.core.world.stopTask
import gups.core.world.takeXp
import gups.core.world.taskOf
import gups.core.world.teleport
import gups.core.world.traceRules
import gups.core.world.transformItem
import gups.core.world.unwearItem
import gups.core.world.wearItem
import gups.pluginapi.PluginActor
import gups.pluginapi.PluginEntity
import gups.pluginapi.PluginGmcpCall
import gups.pluginapi.PluginGmcpWorld
import gups.pluginapi.PluginItem
import gups.pluginapi.PluginItemAffect
import gups.pluginapi.PluginItemInfo
import gups.pluginapi.PluginMemory
import gups.pluginapi.PluginNamed
import gups.pluginapi.PluginRng
import gups.pluginapi.PluginRole
import gups.pluginapi.PluginRoom
import gups.pluginapi.PluginSchema
import gups.pluginapi.PluginSkill
import gups.pluginapi.PluginSkillRoll
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

  override val exits: Map<String, String>
    get() = room.exits.values.associate { it.direction.name to it.to.v }

  override val actors: List<PluginActor>
    get() = room.actors.map { ActorFacade(it, world) }

  override val items: List<PluginItem>
    get() = room.items.map { ItemFacade(it, world) }
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

  override val task: PluginTask?
    get() = world.taskOf(actor)?.let { TaskFacade(it, world) }

  override val isBusy: Boolean
    get() = actor.isBusy
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

  override val contents: List<PluginItem>
    get() = item.contents.map { ItemFacade(it, world) }

  override val custodian: PluginActor?
    get() = world.custodian(item)?.let { ActorFacade(it, world) }

  override val plural: String?
    get() = item.proto.plural
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

  override fun heal(target: PluginActor, amount: Int, vital: String) {
    require(amount >= 0) { "heal(amount): can't be negative; use damage()" }
    val name = declared(vital, world.schema.vitalNames, "vital", "heal(vital)")
    world.heal(actorOf(target, "heal(target)"), name, amount)
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

  override fun teleport(actor: PluginActor, room: PluginRoom) =
    world.teleport(actorOf(actor, "teleport(actor)"), roomOf(room, "teleport(room)"))

  override fun wear(actor: PluginActor, item: PluginItem, wield: Boolean): String? =
    world.wearItem(actorOf(actor, "wear(actor)"), itemOf(item, "wear(item)"), wield)

  override fun unwear(actor: PluginActor, item: PluginItem): String? =
    world.unwearItem(actorOf(actor, "unwear(actor)"), itemOf(item, "unwear(item)"))

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

  private fun mobOf(a: PluginActor, what: String): Mob {
    val actor = actorOf(a, what)
    if (actor !is Mob) throw IllegalArgumentException("$what: ${actor.name} is not a mob")
    return actor
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
