package gups.script

import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.Band
import gups.core.model.Direction
import gups.core.model.Entity
import gups.core.model.Exit
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Room
import gups.core.model.STAT_NAMES
import gups.core.model.SkillProto
import gups.core.world.Proposal
import gups.core.world.World
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

// Read-only views of live entities (SPEC §4.5). Scripts never hold an entity directly: every
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
 * full `namespace:key` name (SPEC §3.7).
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
open class ActorView(scope: ViewScope, val actor: Actor) : EntityView(scope, actor) {
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
  fun level(): Any = read { sInt(actor.level) }

  @StarlarkMethod(name = "xp", structField = true, doc = "Current experience points.")
  fun xp(): Any = read { sInt(actor.xp) }

  @StarlarkMethod(name = "hp", structField = true, doc = "Current hit points.")
  fun hp(): Any = read { sInt(actor.hp) }

  @StarlarkMethod(name = "max_hp", structField = true, doc = "Maximum hit points.")
  fun maxHp(): Any = read { sInt(actor.maxHp) }

  @StarlarkMethod(name = "mana", structField = true, doc = "Current mana.")
  fun mana(): Any = read { sInt(actor.mana) }

  @StarlarkMethod(name = "max_mana", structField = true, doc = "Maximum mana.")
  fun maxMana(): Any = read { sInt(actor.maxMana) }

  @StarlarkMethod(name = "move", structField = true, doc = "Current movement points.")
  fun move(): Any = read { sInt(actor.move) }

  @StarlarkMethod(name = "max_move", structField = true, doc = "Maximum movement points.")
  fun maxMove(): Any = read { sInt(actor.maxMove) }

  @StarlarkMethod(
    name = "stats",
    structField = true,
    doc = "Effective attributes, including affects and equipment.",
  )
  fun stats(): Any = read { sDict(STAT_NAMES.associateWith { sInt(actor.stat(it)) }) }

  @StarlarkMethod(name = "armor", structField = true, doc = "Armor rating.")
  fun armor(): Any = read { sInt(actor.armor) }

  @StarlarkMethod(
    name = "resists",
    structField = true,
    doc = "Damage resistance percentage by damage type.",
  )
  fun resists(): Any = read {
    sDict(actor.resists.entries.associate { it.key.name to sInt(it.value) })
  }

  @StarlarkMethod(name = "room", structField = true, doc = "The room the actor is currently in.")
  fun room(): Any = read { RoomView(scope, actor.room) }

  @StarlarkMethod(
    name = "track_index",
    structField = true,
    doc = "Position along the room's 1D track.",
  )
  fun trackIndex(): Any = read { sInt(actor.trackIndex) }

  @StarlarkMethod(
    name = "band_of",
    doc = "'melee', 'near' or 'far' to another actor or item in the same room; None elsewhere.",
    parameters = [Param(name = "other", named = true, doc = "Actor or item in the same room.")],
  )
  fun bandOf(other: Any): Any = read {
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

  @StarlarkMethod(name = "affects", structField = true, doc = "Active timed affects on this actor.")
  fun affects(): Any = read { sList(actor.affects.map { AffectView(scope, actor, it) }) }

  @StarlarkMethod(name = "skills", structField = true, doc = "Skill label to proficiency (0-100).")
  fun skills(): Any = read { sDict(actor.skills.entries.associate { it.key.v to sInt(it.value) }) }

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
  fun fighting(): Any = read {
    sList(actor.fighting.mapNotNull { scope.world.actor(it) }.map { ActorView(scope, it) })
  }

  @StarlarkMethod(
    name = "is_casting",
    structField = true,
    doc = "True while the actor is in casting warmup.",
  )
  fun isCasting(): Any = read { actor.isCasting }

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
    doc = "Flags from affects, including those worn items apply, sorted.",
  )
  fun flags(): Any = read { sList(actor.affects.flatMap { it.flags }.distinct().sorted()) }

  @StarlarkMethod(
    name = "has_flag",
    doc = "True if an affect or a worn item gives this flag, such as 'no_regen'.",
    parameters = [Param(name = "flag", named = true, doc = "Flag name, such as 'no_regen'.")],
  )
  fun hasFlag(flag: Any): Any = read { actor.hasFlag(str(flag, "has_flag(flag)")) }

  @StarlarkMethod(
    name = "carried_weight",
    structField = true,
    doc = "Weight of everything carried and worn.",
  )
  fun carriedWeight(): Any = read {
    sInt(actor.inventory.sumOf { it.weight } + actor.equipment.values.sumOf { it.weight })
  }

  @StarlarkMethod(name = "casting", structField = true, doc = "The skill being cast, or None.")
  fun casting(): Any = read {
    (actor.casting?.action as? gups.core.world.Castable)?.skill?.let(::SkillView) ?: Starlark.NONE
  }
}

@StarlarkBuiltin(name = "room", doc = "A read-only view of a room.")
class RoomView(scope: ViewScope, val room: Room) : EntityView(scope, room) {
  @StarlarkMethod(name = "desc", structField = true, doc = "The room's description.")
  fun desc(): Any = read { room.proto.desc }

  @StarlarkMethod(name = "size", structField = true, doc = "'cramped', 'normal' or 'large'.")
  fun size(): Any = read { room.proto.size.name.lowercase() }

  @StarlarkMethod(
    name = "track_size",
    structField = true,
    doc = "Number of track slots in this room (1, 3 or 6).",
  )
  fun trackSize(): Any = read { sInt(room.trackSize) }

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

@StarlarkBuiltin(name = "exit", doc = "A read-only view of an exit.")
class ExitView
internal constructor(private val scope: ViewScope, private val room: Room, private val exit: Exit) :
  StarlarkValue {
  private fun <T> read(block: () -> T): T {
    scope.check()
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
  fun entryIndex(): Any = read { sInt(exit.entryIndex) }

  @StarlarkMethod(
    name = "senses_blocked",
    structField = true,
    doc = "Effective blocked channels: the exit's own plus its door's.",
  )
  fun sensesBlocked(): Any = read {
    sList(exit.effectiveSensesBlocked.map { it.name.lowercase() }.sorted())
  }

  @StarlarkMethod(
    name = "door",
    structField = true,
    doc = "'open', 'closed' or 'locked'; None without a door.",
  )
  fun door(): Any = read { exit.door?.state?.name?.lowercase() ?: Starlark.NONE }

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
    name = "weight",
    structField = true,
    doc = "Total weight, including the stack and any contents.",
  )
  fun weight(): Any = read { sInt(item.weight) }

  @StarlarkMethod(
    name = "in_world",
    structField = true,
    doc = "False once the item has been destroyed or decayed.",
  )
  fun inWorld(): Any = read { scope.world.item(item.id) === item }

  @StarlarkMethod(name = "location", structField = true, doc = "Where the item currently is.")
  fun location(): Any = read { LocationView(scope, item.location) }

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
  fun damageType(): Any = read { item.proto.damageType.name }

  @StarlarkMethod(
    name = "range",
    structField = true,
    doc = "A weapon's reach: 'melee', 'near' or 'far'.",
  )
  fun range(): Any = read { item.range.word }

  @StarlarkMethod(
    name = "wear",
    structField = true,
    doc = "The equipment slot it goes in, or None.",
  )
  fun wear(): Any = read { item.proto.wear ?: Starlark.NONE }

  @StarlarkMethod(name = "stat_deltas", structField = true, doc = "Stat changes while worn.")
  fun statDeltas(): Any = read { sDict(item.proto.statDeltas.mapValues { sInt(it.value) }) }
}

/** Where an item is (SPEC §3.3). */
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
  fun index(): Any = (loc as? ItemLocation.InRoom)?.let { sInt(it.trackIndex) } ?: Starlark.NONE

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
internal constructor(private val scope: ViewScope, private val host: Actor, val affect: Affect) :
  StarlarkValue {
  @StarlarkMethod(name = "id", structField = true, doc = "The affect's id on its host.")
  fun id(): Any = sInt(affect.id)

  @StarlarkMethod(name = "name", structField = true, doc = "The affect's name.")
  fun name(): Any = affect.name

  @StarlarkMethod(name = "expires_at", structField = true, doc = "The pulse it expires at.")
  fun expiresAt(): Any = sInt(affect.expiresAt)

  @StarlarkMethod(name = "remaining", structField = true, doc = "Pulses until it expires.")
  fun remaining(): Any = sInt((affect.expiresAt - scope.world.pulse).coerceAtLeast(0))

  @StarlarkMethod(name = "flags", structField = true, doc = "Affect flags, sorted.")
  fun flags(): Any = sList(affect.flags.sorted())

  @StarlarkMethod(
    name = "stat_deltas",
    structField = true,
    doc = "Stat adjustments granted by this affect.",
  )
  fun statDeltas(): Any = sDict(affect.statDeltas.mapValues { sInt(it.value) })

  @StarlarkMethod(
    name = "source",
    structField = true,
    doc = "Who applied it, if still in the world.",
  )
  fun source(): Any = view(scope, affect.source?.let { scope.world.actor(it) })

  @StarlarkMethod(name = "host", structField = true, doc = "The actor carrying the affect.")
  fun hostView(): Any = ActorView(scope, host)

  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    printer.append("<affect ${affect.name}>")
  }
}

@StarlarkBuiltin(name = "skill", doc = "A read-only view of a skill.")
class SkillView internal constructor(val skill: SkillProto) : StarlarkValue {
  @StarlarkMethod(name = "label", structField = true, doc = "The skill's prototype label.")
  fun label(): Any = skill.label.v

  @StarlarkMethod(name = "name", structField = true, doc = "The skill's name.")
  fun name(): Any = skill.name

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
    doc = "Vitals spent: keys 'hp', 'mana', 'move'.",
  )
  fun cost(): Any = sDict(skill.cost.mapValues { sInt(it.value) })

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

/** The seeded world RNG (SPEC §4.5). There is no other source of randomness. */
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
