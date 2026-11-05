package gups.script

import com.google.common.collect.ImmutableCollection
import com.google.common.collect.ImmutableList
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.Band
import gups.core.model.Direction
import gups.core.model.Door
import gups.core.model.Entity
import gups.core.model.Exit
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Room
import gups.core.model.SkillProto
import gups.core.model.WorldSchema
import gups.core.model.didYouMean
import gups.core.world.HeldAffect
import gups.core.world.Proposal
import gups.core.world.World
import gups.core.world.actorSlots
import gups.core.world.heldAffects
import gups.core.world.isInactive
import gups.core.world.pendingLevels
import gups.core.world.roomOfEntity
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.Printer
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkList
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue
import net.starlark.java.eval.Structure

// Read-only views of live entities. Scripts never hold an entity directly: every
// mutation goes through a ctx verb. A view reads the entity's current state on each access.

/** Where views come from: a hook's ctx (valid only while the hook runs) or the harness. */
interface ViewScope {
  val world: World

  /** The package reading through these views; its own attribute keys appear by short name. */
  val pkg: String?

  /** Fails if the views may no longer be used. */
  fun check()
}

internal fun sInt(n: Int): StarlarkInt = StarlarkInt.of(n)

internal fun sInt(n: Long): StarlarkInt = StarlarkInt.of(n)

internal fun sList(items: List<Any>): StarlarkList<Any> = StarlarkList.immutableCopyOf(items)

internal fun sDict(items: Map<String, Any>): Dict<String, Any> = Dict.immutableCopyOf(items)

internal fun orNone(v: Any?): Any = v ?: Starlark.NONE

/** A view of [e], or None. */
fun view(scope: ViewScope, e: Entity?): Any =
  when (e) {
    null -> Starlark.NONE
    is Actor -> ActorView(scope, e)
    is Room -> RoomView(scope, e)
    is Item -> ItemView(scope, e)
  }

/**
 * Attributes as a reader in package [pkg] sees them: its own keys by short name, everyone else's by
 * full `namespace:key` name.
 */
internal fun readableAttrs(e: Entity, pkg: String?): Dict<String, Any> {
  val own = pkg?.substringAfterLast('/')
  return sDict(
    e.customAttributes.entries.associate { (k, v) ->
      val short = if (own != null && k.startsWith("$own:")) k.substring(own.length + 1) else k
      short to fromAttr(v, null)
    }
  )
}

/** Shared base: identity, equality by entity, and the validity check. */
abstract class EntityView internal constructor(val scope: ViewScope, val entity: Entity) :
  StarlarkValue {
  protected fun <T> read(block: () -> T): T {
    scope.check()
    return block()
  }

  /** Like [read] for a field or method of [view] that a feature owns, such as `actor.level`. */
  protected fun <T> read(view: String, block: () -> T): T {
    scope.check()
    FeatureOwnership.refused("view:$view", view, scope.world.features)?.let { fail(it) }
    return block()
  }

  @StarlarkMethod(name = "id", structField = true, doc = "The entity's id.")
  fun id(): Any = read { sInt(entity.id.v) }

  @StarlarkMethod(name = "label", structField = true, doc = "The prototype label.")
  fun label(): Any = read { entity.protoLabel.v }

  @StarlarkMethod(name = "name", structField = true, doc = "The entity's display name.")
  fun name(): Any = read { entity.name }

  @StarlarkMethod(
    name = "attrs",
    structField = true,
    doc = "Custom attributes; your package's own keys by short name.",
  )
  fun attrs(): Any = read { readableAttrs(entity, scope.pkg) }

  override fun equals(other: Any?): Boolean = other is EntityView && other.entity === entity

  override fun hashCode(): Int = entity.id.hashCode()

  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    printer.append("<${Starlark.type(this)} ${entity.name} ${entity.id}>")
  }
}

@StarlarkBuiltin(name = "actor", doc = "A read-only view of a player or mob.")
open class ActorView(scope: ViewScope, val actor: Actor) : EntityView(scope, actor), Structure {
  @StarlarkMethod(
    name = "is_player",
    structField = true,
    doc = "True for a player, False for a mob.",
  )
  fun isPlayer(): Any = read { actor.isPlayer }

  @StarlarkMethod(
    name = "in_world",
    structField = true,
    doc = "False once the actor has died (mobs) or quit.",
  )
  fun inWorld(): Any = read { actor.inWorld }

  @StarlarkMethod(
    name = "keywords",
    structField = true,
    doc = "Words that match this actor when targeting.",
  )
  fun keywords(): Any = read { sList(actor.keywords) }

  @StarlarkMethod(name = "level", structField = true, doc = "The actor's level.")
  fun level(): Any = read("actor.level") { sInt(actor.level) }

  @StarlarkMethod(name = "xp", structField = true, doc = "Current experience points.")
  fun xp(): Any = read("actor.xp") { sInt(actor.xp) }

  @StarlarkMethod(
    name = "pending_levels",
    structField = true,
    doc =
      "How many levels the actor has the XP for and has not been given. A read costs one" +
        " xp_to_level call per pending level, plus one.",
  )
  fun pendingLevels(): Any = read("actor.pending_levels") { sInt(scope.world.pendingLevels(actor)) }

  @StarlarkMethod(
    name = "condition",
    structField = true,
    doc = "'ok', 'incapacitated' or 'dead', as the rules last decided.",
  )
  fun condition(): Any = read { actor.condition.word }

  @StarlarkMethod(
    name = "base",
    structField = true,
    doc = "Base stat values, without affects, equipment or encumbrance: base.dex.",
  )
  fun base(): Any = read { BaseView(actor, scope.world.schema) }

  @StarlarkMethod(
    name = "senses",
    structField = true,
    doc =
      "Effective sense score on every channel, with traits, affects and equipment applied: senses['sound']. 0 senses nothing on that channel.",
  )
  fun senses(): Any = read {
    sDict(
      actor.effectiveSenses(scope.world.schema.allChannels).entries.associate {
        it.key.name to sInt(it.value)
      }
    )
  }

  @StarlarkMethod(
    name = "resists",
    structField = true,
    doc = "Damage resistance percentage by declared damage type: resists.fire.",
  )
  fun resists(): Any = read {
    val types = scope.world.schema.damageTypes
    TypedMap(TypedKind.RESISTS, types.associateWith { actor.effectiveResist(it) }, types)
  }

  @StarlarkMethod(
    name = "below_zero_since",
    doc = "The pulse the vital last dropped below 0, or None while it is 0 or more.",
    parameters = [Param(name = "vital", named = true, doc = "A declared vital.")],
  )
  fun belowZeroSince(vital: Any): Any = read {
    val name =
      checkName(
        str(vital, "below_zero_since(vital)"),
        scope.world.schema.vitalNames,
        "vital",
        "below_zero_since(vital)",
      )
    actor.vitals.getValue(name).belowZeroSince?.let { sInt(it) } ?: Starlark.NONE
  }

  override fun getValue(name: String): Any? = read {
    val schema = scope.world.schema
    when {
      name in schema.statNames -> sInt(actor.stat(name))
      name in schema.vitalNames -> sInt(actor.current(name))
      name.startsWith("max_") && name.removePrefix("max_") in schema.vitalNames ->
        sInt(actor.max(name.removePrefix("max_")))
      else -> null
    }
  }

  override fun setField(field: String, value: Any) {
    throw Starlark.errorf(
      "actor fields are read-only: can't set '%s'; use a ctx verb such as damage or heal",
      field,
    )
  }

  override fun getFieldNames(): ImmutableCollection<String> {
    val schema = scope.world.schema
    return ImmutableList.copyOf(
      schema.statNames + schema.vitalNames + schema.vitalNames.map { "max_$it" }
    )
  }

  override fun getErrorMessageForUnknownField(field: String): String {
    val known =
      getFieldNames() + Starlark.getMethodAnnotations(ActorView::class.java).values.map { it.name }
    return "'actor' value has no field or method '$field'${didYouMean(field, known)}"
  }

  @StarlarkMethod(name = "room", structField = true, doc = "The room the actor is currently in.")
  fun room(): Any = read { RoomView(scope, actor.room) }

  @StarlarkMethod(
    name = "track_index",
    structField = true,
    doc = "Position along the room's 1D track.",
  )
  fun trackIndex(): Any = read("actor.track_index") { sInt(actor.trackIndex) }

  @StarlarkMethod(
    name = "band_of",
    doc = "'melee', 'near' or 'far' to another actor or item in the same room; None elsewhere.",
    parameters = [Param(name = "other", named = true, doc = "Actor or item in the same room.")],
  )
  fun bandOf(other: Any): Any =
    read("actor.band_of") {
      val index =
        when (other) {
          is ActorView ->
            other.actor.trackIndex.takeIf { other.actor.room === actor.room && other.actor.inWorld }
          is ItemView ->
            (other.item.location as? ItemLocation.InRoom)
              ?.takeIf { it.room == actor.room.id }
              ?.trackIndex
              ?: other.item
                .let { scope.world.roomOfEntity(it) }
                ?.takeIf { it === actor.room }
                ?.let { actor.trackIndex }
          else -> fail("band_of(): expected an actor or item, got ${typeName(other)}")
        }
      index?.let { Band.between(actor.trackIndex, it).word } ?: Starlark.NONE
    }

  @StarlarkMethod(
    name = "affects",
    structField = true,
    doc = "Active affects on this actor, including untimed ones from worn items.",
  )
  fun affects(): Any = read { sList(actor.affects.map { AffectView(scope, actor, it) }) }

  @StarlarkMethod(name = "skills", structField = true, doc = "Skill label to proficiency (0-100).")
  fun skills(): Any =
    read("actor.skills") { sDict(actor.skills.entries.associate { it.key.v to sInt(it.value) }) }

  @StarlarkMethod(
    name = "slots",
    structField = true,
    doc = "Equipment slots this actor currently has, a subset of equipment_slots().",
  )
  fun slots(): Any = read { sList(scope.world.actorSlots(actor)) }

  @StarlarkMethod(
    name = "equipped",
    doc = "The item in an equipment slot, or None.",
    parameters =
      [Param(name = "slot", named = true, doc = "Equipment slot name, such as 'wield' or 'body'.")],
  )
  fun equipped(slot: Any): Any = read { view(scope, actor.equipped(str(slot, "equipped(slot)"))) }

  @StarlarkMethod(name = "inventory", doc = "Carried items (not equipment).")
  fun inventory(): Any = read { sList(actor.inventory.map { ItemView(scope, it) }) }

  @StarlarkMethod(
    name = "has_item",
    doc = "Whether the actor carries or wears an item of this prototype.",
    parameters = [Param(name = "label", named = true, doc = "Item prototype label.")],
    useStarlarkThread = true,
  )
  fun hasItem(label: Any, thread: StarlarkThread): Any = read {
    val l =
      label(str(label, "has_item(label)"), callerFile(thread)?.pkg ?: scope.pkg, "has_item(label)")
    (actor.inventory + actor.equipment.values).any { it.protoLabel == l }
  }

  @StarlarkMethod(name = "fighting", doc = "Actors engaged with this one.")
  fun fighting(): Any =
    read("actor.fighting") {
      sList(actor.fighting.mapNotNull { scope.world.actor(it) }.map { ActorView(scope, it) })
    }

  @StarlarkMethod(
    name = "is_casting",
    structField = true,
    doc = "True while the actor is in casting warmup.",
  )
  fun isCasting(): Any = read("actor.is_casting") { actor.isCasting }

  @StarlarkMethod(name = "wait_state", structField = true, doc = "Recovery pulses left.")
  fun waitState(): Any = read { sInt(actor.waitState) }

  @StarlarkMethod(
    name = "home_room",
    structField = true,
    doc = "A mob's home room label; None for players.",
  )
  fun homeRoom(): Any = read { (actor as? Mob)?.homeRoom?.v ?: Starlark.NONE }

  @StarlarkMethod(
    name = "recall_room",
    structField = true,
    doc = "A player's recall room label; None for mobs.",
  )
  fun recallRoom(): Any = read {
    (actor as? gups.core.model.Player)?.recallRoom?.v ?: Starlark.NONE
  }

  @StarlarkMethod(
    name = "flags",
    structField = true,
    doc = "Flags from traits, affects and item affects, sorted.",
  )
  fun flags(): Any = read { sList(actor.flags.sorted()) }

  @StarlarkMethod(
    name = "has_flag",
    doc = "True if a trait, an affect or an item affect gives this flag, such as 'no_regen'.",
    parameters = [Param(name = "flag", named = true, doc = "Flag name, such as 'no_regen'.")],
  )
  fun hasFlag(flag: Any): Any = read { actor.hasFlag(str(flag, "has_flag(flag)")) }

  @StarlarkMethod(
    name = "choices",
    structField = true,
    doc = "Chosen character creation options by choice id: a dict, empty for mobs.",
  )
  fun choices(): Any = read {
    if (actor is Player) sDict(actor.choices.toMap()) else sDict(emptyMap<String, String>())
  }

  @StarlarkMethod(
    name = "traits",
    structField = true,
    doc = "Sorted names of the known traits the actor carries.",
  )
  fun traits(): Any = read { sList(actor.traits.map { it.name }) }

  @StarlarkMethod(
    name = "has_trait",
    doc = "True if the actor carries this trait.",
    parameters = [Param(name = "name", named = true, doc = "Trait name.")],
  )
  fun hasTrait(name: Any): Any = read {
    val tName = str(name, "has_trait(name)")
    actor.traits.any { it.name == tName }
  }

  @StarlarkMethod(
    name = "carried_weight",
    structField = true,
    doc = "Weight of everything carried and worn.",
  )
  fun carriedWeight(): Any = read {
    sInt(actor.inventory.sumOf { it.weight } + actor.equipment.values.sumOf { it.weight })
  }

  @StarlarkMethod(name = "casting", structField = true, doc = "The skill being cast, or None.")
  fun casting(): Any =
    read("actor.casting") {
      (actor.casting?.action as? gups.core.world.Castable)?.skill?.let {
        SkillView(it, scope.world.schema)
      } ?: Starlark.NONE
    }
}

@StarlarkBuiltin(name = "room", doc = "A read-only view of a room.")
class RoomView(scope: ViewScope, val room: Room) : EntityView(scope, room) {
  @StarlarkMethod(name = "desc", structField = true, doc = "The room's description.")
  fun desc(): Any = read { room.proto.desc }

  @StarlarkMethod(name = "size", structField = true, doc = "'cramped', 'normal' or 'large'.")
  fun size(): Any = read("room.size") { room.proto.size.name.lowercase() }

  @StarlarkMethod(name = "tags", structField = true, doc = "Tags carried by this room, sorted.")
  fun tags(): Any = read { sList(room.tags.sorted()) }

  @StarlarkMethod(
    name = "track_size",
    structField = true,
    doc = "Number of track slots in this room (1, 3 or 6).",
  )
  fun trackSize(): Any = read("room.track_size") { sInt(room.trackSize) }

  @StarlarkMethod(
    name = "regions",
    structField = true,
    doc = "Region tags carried by this room, sorted.",
  )
  fun regions(): Any = read { sList(room.regions.sorted()) }

  @StarlarkMethod(name = "flags", structField = true, doc = "Room flags, sorted.")
  fun flags(): Any = read { sList(room.proto.flags.sorted()) }

  @StarlarkMethod(name = "exits", doc = "Exits leading out of this room.")
  fun exits(): Any = read { sList(room.exits.values.map { ExitView(scope, room, it) }) }

  @StarlarkMethod(
    name = "exit",
    doc = "The exit in a direction, or None.",
    parameters = [Param(name = "dir", named = true, doc = "Direction name.")],
  )
  fun exit(dir: Any): Any = read {
    room.exits[Direction.parse(str(dir, "exit(dir)"))]?.let { ExitView(scope, room, it) }
      ?: Starlark.NONE
  }

  @StarlarkMethod(name = "actors", doc = "Actors here, ascending id.")
  fun actors(): Any = read { sList(room.actors.map { ActorView(scope, it) }) }

  @StarlarkMethod(name = "items", doc = "Items on the ground here, ascending id.")
  fun items(): Any = read { sList(room.items.map { ItemView(scope, it) }) }
}

@StarlarkBuiltin(name = "door", doc = "A read-only view of an exit's door.")
class DoorView internal constructor(private val scope: ViewScope, private val door: Door) :
  StarlarkValue {
  private fun <T> read(block: () -> T): T {
    scope.check()
    return block()
  }

  @StarlarkMethod(name = "state", structField = true, doc = "'open', 'closed' or 'locked'.")
  fun state(): Any = read { door.state.name.lowercase() }

  @StarlarkMethod(name = "key", structField = true, doc = "The key's item label, or None.")
  fun key(): Any = read { door.key?.v ?: Starlark.NONE }

  @StarlarkMethod(
    name = "tags",
    structField = true,
    doc = "Tags declared on door(tags = [...]), sorted.",
  )
  fun tags(): Any = read { sList(door.tags.sorted()) }

  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    printer.append("<door ${door.state.name.lowercase()}>")
  }
}

@StarlarkBuiltin(name = "exit", doc = "A read-only view of an exit.")
class ExitView
internal constructor(private val scope: ViewScope, private val room: Room, private val exit: Exit) :
  StarlarkValue {
  private fun <T> read(block: () -> T): T {
    scope.check()
    return block()
  }

  private fun <T> read(view: String, block: () -> T): T {
    scope.check()
    FeatureOwnership.refused("view:$view", view, scope.world.features)?.let { fail(it) }
    return block()
  }

  @StarlarkMethod(name = "direction", structField = true, doc = "The exit's direction name.")
  fun direction(): Any = read { exit.direction.name }

  @StarlarkMethod(name = "to", structField = true, doc = "The destination room's label.")
  fun to(): Any = read { exit.to.v }

  @StarlarkMethod(
    name = "entry_index",
    structField = true,
    doc = "Destination track index where travellers arrive, or -1 for the back.",
  )
  fun entryIndex(): Any = read("exit.entry_index") { sInt(exit.entryIndex) }

  @StarlarkMethod(
    name = "tags",
    structField = true,
    doc = "Tags declared on exit(tags = [...]), sorted.",
  )
  fun tags(): Any = read { sList(exit.tags.sorted()) }

  @StarlarkMethod(name = "door", structField = true, doc = "The exit's door, or None without one.")
  fun door(): Any = read { exit.door?.let { DoorView(scope, it) } ?: Starlark.NONE }

  @StarlarkMethod(name = "key", structField = true, doc = "The door's key label, or None.")
  fun key(): Any = read { exit.door?.key?.v ?: Starlark.NONE }

  @StarlarkMethod(
    name = "passable",
    structField = true,
    doc = "False while a door is closed or locked.",
  )
  fun passable(): Any = read { exit.passable }

  @StarlarkMethod(
    name = "one_way",
    structField = true,
    doc = "True if this exit intentionally has no return exit.",
  )
  fun oneWay(): Any = read { exit.oneWay }

  override fun equals(other: Any?): Boolean = other is ExitView && other.exit === exit

  override fun hashCode(): Int = System.identityHashCode(exit)

  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    printer.append("<exit ${exit.direction} of ${room.label}>")
  }
}

@StarlarkBuiltin(name = "item", doc = "A read-only view of an item.")
class ItemView(scope: ViewScope, val item: Item) : EntityView(scope, item) {
  @StarlarkMethod(
    name = "plural",
    structField = true,
    allowReturnNones = true,
    doc = "Display name for stacks of 2 or more, or None.",
  )
  fun plural(): Any? = read { item.proto.plural }

  @StarlarkMethod(
    name = "kind",
    structField = true,
    doc = "Item category, such as 'misc', 'melee', 'ranged', 'armor' or 'key'.",
  )
  fun kind(): Any = read { item.kind }

  @StarlarkMethod(
    name = "keywords",
    structField = true,
    doc = "Words that match this item when targeting.",
  )
  fun keywords(): Any = read { sList(item.keywords) }

  @StarlarkMethod(name = "count", structField = true, doc = "Stack count.")
  fun count(): Any = read { sInt(item.count) }

  @StarlarkMethod(
    name = "charges",
    structField = true,
    allowReturnNones = true,
    doc = "Uses left, or None when unlimited.",
  )
  fun charges(): Any? = read { item.charges?.let(::sInt) }

  @StarlarkMethod(
    name = "max_charges",
    structField = true,
    allowReturnNones = true,
    doc = "The prototype's charges, or None when unlimited.",
  )
  fun maxCharges(): Any? = read { item.proto.charges?.let(::sInt) }

  @StarlarkMethod(
    name = "weight",
    structField = true,
    doc = "Total weight, including the stack and any contents.",
  )
  fun weight(): Any = read { sInt(item.weight) }

  @StarlarkMethod(
    name = "value",
    structField = true,
    doc = "Base price of one item, from its prototype.",
  )
  fun value(): Any = read { sInt(item.proto.value) }

  @StarlarkMethod(
    name = "in_world",
    structField = true,
    doc = "False once the item has been destroyed or decayed.",
  )
  fun inWorld(): Any = read { scope.world.item(item.id) === item }

  @StarlarkMethod(name = "location", structField = true, doc = "Where the item currently is.")
  fun location(): Any = read { LocationView(scope, item.location) }

  @StarlarkMethod(
    name = "tags",
    structField = true,
    doc = "Effective tags: the prototype's plus those added, minus those removed, sorted.",
  )
  fun tags(): Any = read { sList(item.tags.sorted()) }

  @StarlarkMethod(
    name = "has_tag",
    doc = "True if this item has the effective tag.",
    parameters = [Param(name = "tag", named = true, doc = "Tag name to check.")],
  )
  fun hasTag(tag: Any): Any = read { str(tag, "has_tag(tag)") in item.tags }

  @StarlarkMethod(
    name = "variants",
    structField = true,
    doc = "Free-form variant names to related item prototype labels.",
  )
  fun variants(): Any = read {
    sDict(item.proto.variants.entries.associate { it.key to it.value.v })
  }

  @StarlarkMethod(
    name = "affects",
    structField = true,
    doc =
      "Affects active on this item: prototype affects, then instance affects. Empty for an item " +
        "whose prototype was removed.",
  )
  fun affects(): Any = read {
    sList(
      item.heldAffects().map { ha ->
        val inst = ha.instance
        if (inst == null) ProtoAffectView(scope, item, ha) else AffectView(scope, item, inst)
      }
    )
  }

  @StarlarkMethod(
    name = "container_state",
    structField = true,
    doc = "'open', 'closed' or 'locked'; None if not a container.",
  )
  fun containerState(): Any = read { item.container?.state?.name?.lowercase() ?: Starlark.NONE }

  @StarlarkMethod(name = "contents", doc = "Items inside this container, ascending id.")
  fun contents(): Any = read { sList(item.contents.map { ItemView(scope, it) }) }

  @StarlarkMethod(
    name = "dice",
    structField = true,
    doc = "Weapon damage dice such as '1d6', or None.",
  )
  fun dice(): Any = read { item.proto.dice?.toString() ?: Starlark.NONE }

  @StarlarkMethod(name = "damage_type", structField = true, doc = "Weapon damage type.")
  fun damageType(): Any = read {
    item.proto.damageType?.name ?: scope.world.schema.unarmedDamageType
  }

  @StarlarkMethod(
    name = "range",
    structField = true,
    doc = "A weapon's reach: 'melee', 'near' or 'far'.",
  )
  fun range(): Any = read("item.range") { item.range.word }

  @StarlarkMethod(
    name = "wear",
    structField = true,
    doc = "The equipment slot it goes in, or None.",
  )
  fun wear(): Any = read { item.proto.wear ?: Starlark.NONE }

  @StarlarkMethod(
    name = "slot_cost",
    structField = true,
    doc = "How many slots of its family this item takes up when worn; at least 1.",
  )
  fun slotCost(): Any = read { sInt(item.proto.slotCost) }

  @StarlarkMethod(name = "stat_deltas", structField = true, doc = "Stat changes while worn.")
  fun statDeltas(): Any = read {
    TypedMap(TypedKind.STATS, item.proto.statDeltas, scope.world.schema.statNames)
  }

  @StarlarkMethod(
    name = "sense_deltas",
    structField = true,
    doc = "Sense score changes by channel while worn.",
  )
  fun senseDeltas(): Any = read {
    sDict(item.proto.senseDeltas.entries.associate { it.key.name to sInt(it.value) })
  }

  @StarlarkMethod(
    name = "max_vital_deltas",
    structField = true,
    doc = "Vital maximum changes while worn.",
  )
  fun maxVitalDeltas(): Any = read {
    TypedMap(TypedKind.VITALS, item.proto.maxVitalDeltas, scope.world.schema.vitalNames)
  }

  @StarlarkMethod(
    name = "resist_deltas",
    structField = true,
    doc = "Resistance changes while worn.",
  )
  fun resistDeltas(): Any = read {
    TypedMap(
      TypedKind.RESISTS,
      item.proto.resistDeltas.mapKeys { it.key.name },
      scope.world.schema.damageTypes,
    )
  }

  @StarlarkMethod(
    name = "inactive",
    structField = true,
    doc = "True if equipped in a slot that does not currently fit its wearer.",
  )
  fun inactive(): Any = read { scope.world.isInactive(item) }
}

/** Where an item is. */
@StarlarkBuiltin(
  name = "location",
  doc = "Where an item is: kind is 'room', 'carried', 'equipped' or 'contained'.",
)
class LocationView
internal constructor(private val scope: ViewScope, private val loc: ItemLocation) : StarlarkValue {
  @StarlarkMethod(
    name = "kind",
    structField = true,
    doc = "'room', 'carried', 'equipped' or 'contained'.",
  )
  fun kind(): Any =
    when (loc) {
      is ItemLocation.InRoom -> "room"
      is ItemLocation.Carried -> "carried"
      is ItemLocation.Equipped -> "equipped"
      is ItemLocation.Contained -> "contained"
    }

  @StarlarkMethod(name = "room", structField = true, doc = "The room, for items on the ground.")
  fun room(): Any = view(scope, (loc as? ItemLocation.InRoom)?.let { scope.world.entity(it.room) })

  @StarlarkMethod(name = "index", structField = true, doc = "Track index, for items on the ground.")
  fun index(): Any {
    FeatureOwnership.refused("view:location.index", "location.index", scope.world.features)?.let {
      fail(it)
    }
    return (loc as? ItemLocation.InRoom)?.let { sInt(it.trackIndex) } ?: Starlark.NONE
  }

  @StarlarkMethod(name = "actor", structField = true, doc = "The carrier or wearer.")
  fun actor(): Any =
    view(
      scope,
      when (loc) {
        is ItemLocation.Carried -> scope.world.actor(loc.actor)
        is ItemLocation.Equipped -> scope.world.actor(loc.actor)
        else -> null
      },
    )

  @StarlarkMethod(name = "slot", structField = true, doc = "The equipment slot, for worn items.")
  fun slot(): Any = (loc as? ItemLocation.Equipped)?.slot ?: Starlark.NONE

  @StarlarkMethod(name = "container", structField = true, doc = "The containing item.")
  fun container(): Any =
    view(scope, (loc as? ItemLocation.Contained)?.let { scope.world.item(it.container) })

  override fun isImmutable(): Boolean = true
}

@StarlarkBuiltin(name = "affect", doc = "A read-only view of an affect.")
class AffectView
internal constructor(private val scope: ViewScope, private val host: Entity, val affect: Affect) :
  StarlarkValue {
  @StarlarkMethod(name = "id", structField = true, doc = "The affect's id on its host.")
  fun id(): Any = sInt(affect.id)

  @StarlarkMethod(name = "name", structField = true, doc = "The affect's name.")
  fun name(): Any = affect.name

  @StarlarkMethod(
    name = "expires_at",
    structField = true,
    allowReturnNones = true,
    doc = "The pulse it expires at.",
  )
  fun expiresAt(): Any =
    if (affect.expiresAt == Long.MAX_VALUE) Starlark.NONE else sInt(affect.expiresAt)

  @StarlarkMethod(
    name = "remaining",
    structField = true,
    allowReturnNones = true,
    doc = "Pulses until it expires.",
  )
  fun remaining(): Any =
    if (affect.expiresAt == Long.MAX_VALUE) Starlark.NONE
    else sInt((affect.expiresAt - scope.world.pulse).coerceAtLeast(0))

  @StarlarkMethod(name = "flags", structField = true, doc = "Affect flags, sorted.")
  fun flags(): Any = sList(affect.flags.sorted())

  @StarlarkMethod(
    name = "stat_deltas",
    structField = true,
    doc = "Stat adjustments granted by this affect.",
  )
  fun statDeltas(): Any = TypedMap(TypedKind.STATS, affect.statDeltas, scope.world.schema.statNames)

  @StarlarkMethod(
    name = "sense_deltas",
    structField = true,
    doc = "Sense score changes by channel granted by this affect.",
  )
  fun senseDeltas(): Any =
    sDict(affect.senseDeltas.entries.associate { it.key.name to sInt(it.value) })

  @StarlarkMethod(
    name = "max_vital_deltas",
    structField = true,
    doc = "Vital maximum adjustments granted by this affect.",
  )
  fun maxVitalDeltas(): Any =
    TypedMap(TypedKind.VITALS, affect.maxVitalDeltas, scope.world.schema.vitalNames)

  @StarlarkMethod(
    name = "resist_deltas",
    structField = true,
    doc = "Damage resistance adjustments granted by this affect.",
  )
  fun resistDeltas(): Any =
    TypedMap(
      TypedKind.RESISTS,
      affect.resistDeltas.mapKeys { it.key.name },
      scope.world.schema.damageTypes,
    )

  @StarlarkMethod(
    name = "source",
    structField = true,
    allowReturnNones = true,
    doc = "Who applied it, if still in the world.",
  )
  fun source(): Any = view(scope, affect.source?.let { scope.world.actor(it) })

  @StarlarkMethod(name = "host", structField = true, doc = "The actor or item carrying the affect.")
  fun hostView(): Any = view(scope, host)

  @StarlarkMethod(
    name = "applies",
    structField = true,
    allowReturnNones = true,
    doc = "Scope for item affects ('worn' or 'carried'), or None for actor affects.",
  )
  fun applies(): Any = affect.applies?.name?.lowercase() ?: Starlark.NONE

  @StarlarkMethod(
    name = "power",
    structField = true,
    doc = "Percent strength the affect was applied at; 100 when nothing scaled it.",
  )
  fun power(): Any = sInt(affect.power)

  @StarlarkMethod(
    name = "origin",
    structField = true,
    doc = "Always 'instance'; a prototype's affects are proto_affect views.",
  )
  fun origin(): Any = "instance"

  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    printer.append("<affect ${affect.name}>")
  }
}

@StarlarkBuiltin(
  name = "proto_affect",
  doc = "A read-only view of an affect an item's prototype declares.",
)
class ProtoAffectView
internal constructor(private val scope: ViewScope, private val host: Item, val affect: HeldAffect) :
  StarlarkValue {
  @StarlarkMethod(
    name = "id",
    structField = true,
    allowReturnNones = true,
    doc = "Always None: prototype affects have no id.",
  )
  fun id(): Any = Starlark.NONE

  @StarlarkMethod(name = "name", structField = true, doc = "The affect's name.")
  fun name(): Any = affect.name

  @StarlarkMethod(
    name = "expires_at",
    structField = true,
    allowReturnNones = true,
    doc = "Always None: prototype affects never expire.",
  )
  fun expiresAt(): Any = Starlark.NONE

  @StarlarkMethod(
    name = "remaining",
    structField = true,
    allowReturnNones = true,
    doc = "Always None: prototype affects never expire.",
  )
  fun remaining(): Any = Starlark.NONE

  @StarlarkMethod(name = "flags", structField = true, doc = "Affect flags, sorted.")
  fun flags(): Any = sList(affect.flags.sorted())

  @StarlarkMethod(
    name = "stat_deltas",
    structField = true,
    doc = "Stat adjustments granted by this affect.",
  )
  fun statDeltas(): Any = TypedMap(TypedKind.STATS, affect.statDeltas, scope.world.schema.statNames)

  @StarlarkMethod(
    name = "sense_deltas",
    structField = true,
    doc = "Sense score changes by channel granted by this affect.",
  )
  fun senseDeltas(): Any =
    sDict(affect.senseDeltas.entries.associate { it.key.name to sInt(it.value) })

  @StarlarkMethod(
    name = "max_vital_deltas",
    structField = true,
    doc = "Vital maximum adjustments granted by this affect.",
  )
  fun maxVitalDeltas(): Any =
    TypedMap(TypedKind.VITALS, affect.maxVitalDeltas, scope.world.schema.vitalNames)

  @StarlarkMethod(
    name = "resist_deltas",
    structField = true,
    doc = "Damage resistance adjustments granted by this affect.",
  )
  fun resistDeltas(): Any =
    TypedMap(
      TypedKind.RESISTS,
      affect.resistDeltas.mapKeys { it.key.name },
      scope.world.schema.damageTypes,
    )

  @StarlarkMethod(
    name = "source",
    structField = true,
    allowReturnNones = true,
    doc = "Always None: the prototype declares it.",
  )
  fun source(): Any = Starlark.NONE

  @StarlarkMethod(name = "host", structField = true, doc = "The item whose prototype declares it.")
  fun hostView(): Any = ItemView(scope, host)

  @StarlarkMethod(
    name = "applies",
    structField = true,
    doc = "Where it reaches the holder from: 'worn' or 'carried'.",
  )
  fun applies(): Any = affect.applies.name.lowercase()

  @StarlarkMethod(name = "origin", structField = true, doc = "Always 'proto'.")
  fun origin(): Any = "proto"

  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    printer.append("<proto_affect ${affect.name}>")
  }
}

@StarlarkBuiltin(name = "skill", doc = "A read-only view of a skill.")
class SkillView internal constructor(val skill: SkillProto, private val schema: WorldSchema) :
  StarlarkValue {
  @StarlarkMethod(name = "label", structField = true, doc = "The skill's prototype label.")
  fun label(): Any = skill.label.v

  @StarlarkMethod(name = "name", structField = true, doc = "The skill's name.")
  fun name(): Any = skill.name

  @StarlarkMethod(name = "tags", structField = true, doc = "Tags carried by this skill, sorted.")
  fun tags(): Any = sList(skill.tags.sorted())

  @StarlarkMethod(
    name = "kind",
    structField = true,
    doc = "Skill category, such as 'spell' or 'skill'.",
  )
  fun kind(): Any = skill.kind

  @StarlarkMethod(name = "range", structField = true, doc = "Maximum target range.")
  fun range(): Any = skill.range.name.lowercase()

  @StarlarkMethod(name = "target", structField = true, doc = "Kind of target the skill accepts.")
  fun target(): Any = skill.target.name.lowercase()

  @StarlarkMethod(name = "warmup", structField = true, doc = "Casting warmup pulses.")
  fun warmup(): Any = sInt(skill.warmup)

  @StarlarkMethod(name = "lag", structField = true, doc = "Post-resolution recovery pulses.")
  fun lag(): Any = sInt(skill.lag)

  @StarlarkMethod(
    name = "cost",
    structField = true,
    doc = "The vitals spent, as vitals(...): cost.mana.",
  )
  fun cost(): Any = TypedMap(TypedKind.VITALS, skill.cost, schema.vitalNames)

  @StarlarkMethod(
    name = "channels",
    structField = true,
    doc = "Channels required and emitted when casting, sorted.",
  )
  fun channels(): Any = sList(skill.channels.map { it.name.lowercase() }.sorted())

  @StarlarkMethod(
    name = "interrupt",
    structField = true,
    doc = "{'damage_pct': int or None, 'stun': bool}.",
  )
  fun interrupt(): Any =
    sDict(
      mapOf(
        "damage_pct" to (skill.interrupt.damagePct?.let(::sInt) ?: Starlark.NONE),
        "stun" to skill.interrupt.stun,
      )
    )

  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    printer.append("<skill ${skill.label}>")
  }
}

@StarlarkBuiltin(name = "proposal", doc = "A behavior's suggested action for this think cycle.")
class ProposalView internal constructor(val proposal: Proposal) : StarlarkValue {
  @StarlarkMethod(name = "command", structField = true, doc = "The proposed command line.")
  fun command(): Any = proposal.commandLine

  @StarlarkMethod(
    name = "priority",
    structField = true,
    doc = "Proposal priority; higher ranks first.",
  )
  fun priority(): Any = sInt(proposal.priority)

  @StarlarkMethod(
    name = "source",
    structField = true,
    doc = "The behavior that produced this proposal.",
  )
  fun source(): Any = proposal.source

  override fun isImmutable(): Boolean = true
}

/** The seeded world RNG. There is no other source of randomness. */
@StarlarkBuiltin(name = "rng", doc = "The seeded, deterministic random number generator.")
class RngView internal constructor(private val scope: ViewScope) : StarlarkValue {
  @StarlarkMethod(
    name = "int",
    doc = "A uniform int in lo..hi, inclusive.",
    parameters =
      [
        Param(name = "lo", named = true, doc = "Smallest value."),
        Param(name = "hi", named = true, doc = "Largest value."),
      ],
  )
  fun int(lo: Any, hi: Any): Any {
    scope.check()
    val a = gups.script.int(lo, "rng.int(lo)")
    val b = gups.script.int(hi, "rng.int(hi)")
    if (a > b) fail("rng.int($a, $b): lo must not exceed hi")
    return sInt(scope.world.rng.int(a, b))
  }

  @StarlarkMethod(
    name = "chance",
    doc = "True with probability pct percent.",
    parameters = [Param(name = "pct", named = true, doc = "Probability in percent (0..100).")],
  )
  fun chance(pct: Any): Any {
    scope.check()
    return scope.world.rng.chance(gups.script.int(pct, "rng.chance(pct)"))
  }

  @StarlarkMethod(
    name = "choice",
    doc = "A uniformly chosen element of a non-empty list.",
    parameters = [Param(name = "items", named = true, doc = "Non-empty list to choose from.")],
  )
  fun choice(items: Any): Any {
    scope.check()
    val l = list(items, "rng.choice(items)")
    if (l.isEmpty()) fail("rng.choice(): the list is empty")
    return scope.world.rng.choice(l)
  }

  override fun isImmutable(): Boolean = true
}

class BaseView internal constructor(private val actor: Actor, private val schema: WorldSchema) :
  Structure {
  override fun getValue(name: String): Any? =
    when {
      name in schema.statNames -> sInt(actor.baseStat(name))
      name.startsWith("max_") && name.removePrefix("max_") in schema.vitalNames ->
        sInt(actor.baseMax(name.removePrefix("max_")))
      name == "senses" ->
        sDict(schema.allChannels.associate { it.name to sInt(actor.baseSenses[it] ?: 0) })
      name == "resists" ->
        TypedMap(
          TypedKind.RESISTS,
          schema.damageTypes.associateWith { actor.baseResist(it) },
          schema.damageTypes,
        )
      else -> null
    }

  override fun getFieldNames(): ImmutableCollection<String> =
    ImmutableList.copyOf(
      schema.statNames + schema.vitalNames.map { "max_$it" } + listOf("senses", "resists")
    )

  override fun getErrorMessageForUnknownField(field: String): String =
    "'base' value has no field '$field'${didYouMean(field, getFieldNames())}"

  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    printer.append("<base of ${actor.name}>")
  }
}
