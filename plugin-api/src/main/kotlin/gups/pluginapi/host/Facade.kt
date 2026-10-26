package gups.pluginapi.host

import gups.core.AttrValue
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.DamageType
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.Player
import gups.core.model.Room
import gups.core.world.Diagnostic
import gups.core.world.World
import gups.core.world.applyAffect
import gups.core.world.damage
import gups.core.world.echo
import gups.core.world.heal
import gups.core.world.removeAffect
import gups.core.world.send
import gups.core.world.teleport
import gups.pluginapi.PluginActor
import gups.pluginapi.PluginEntity
import gups.pluginapi.PluginItem
import gups.pluginapi.PluginRng
import gups.pluginapi.PluginRoom
import gups.pluginapi.PluginWorld
import gups.script.ActorView
import gups.script.ItemView
import gups.script.RoomView
import gups.script.scriptWorld
import java.util.WeakHashMap
import net.starlark.java.eval.StarlarkThread

// The facade implementations (SPEC §4.15). Each verb calls what the `ctx` verb of
// the same name calls (script/src/main/kotlin/gups/script/Ctx.kt).

/** Conversions behind `PluginViews`, and the server log of each world with plugins installed. */
internal object Facades {
  private val logs = WeakHashMap<World, (String) -> Unit>()

  fun registerLog(world: World, log: (String) -> Unit) {
    synchronized(logs) { logs[world] = log }
  }

  fun logOf(world: World): (String) -> Unit = synchronized(logs) { logs[world] } ?: {}

  /** The facade for raw builtins, which belong to no plugin namespace (D-177). */
  fun world(thread: StarlarkThread): PluginWorld? =
    scriptWorld(thread)?.let { WorldFacade(it, null, logOf(it)) }

  fun room(value: Any?): PluginRoom? =
    when (value) {
      is RoomFacade -> value
      is RoomView -> RoomFacade(value.room)
      else -> null
    }

  fun actor(value: Any?): PluginActor? =
    when (value) {
      is ActorFacade -> value
      is ActorView -> ActorFacade(value.actor)
      else -> null
    }

  fun item(value: Any?): PluginItem? =
    when (value) {
      is ItemFacade -> value
      is ItemView -> ItemFacade(value.item)
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

internal class RoomFacade(val room: Room) : EntityFacade(room), PluginRoom {
  override val label: String
    get() = room.label.v

  override val regions: List<String>
    get() = room.regions.sorted()

  override val flags: Set<String>
    get() = room.proto.flags.toSortedSet()

  override val size: String
    get() = room.proto.size.name.lowercase()

  override val exits: Map<String, String>
    get() = room.exits.values.associate { it.direction.name to it.to.v }

  override val actors: List<PluginActor>
    get() = room.actors.map(::ActorFacade)

  override val items: List<PluginItem>
    get() = room.items.map(::ItemFacade)
}

internal class ActorFacade(val actor: Actor) : EntityFacade(actor), PluginActor {
  override val label: String?
    get() = if (actor is Player) null else actor.protoLabel.v

  override val isPlayer: Boolean
    get() = actor.isPlayer

  override val room: PluginRoom
    get() = RoomFacade(actor.room)

  override val hp: Int
    get() = actor.hp

  override val maxHp: Int
    get() = actor.maxHp

  override val mana: Int
    get() = actor.mana

  override val maxMana: Int
    get() = actor.maxMana

  override val move: Int
    get() = actor.move

  override val maxMove: Int
    get() = actor.maxMove

  override val level: Int
    get() = actor.level

  override val affects: List<String>
    get() = actor.affects.map { it.name }

  override fun hasAffect(name: String): Boolean = actor.affects.any { it.name == name }
}

internal class ItemFacade(val item: Item) : EntityFacade(item), PluginItem {
  override val label: String
    get() = item.protoLabel.v

  override val keywords: List<String>
    get() = item.keywords
}

internal class RngFacade(private val world: World) : PluginRng {
  override fun int(min: Int, max: Int): Int = world.rng.int(min, max)

  override fun chance(pct: Int): Boolean = world.rng.chance(pct)
}

/**
 * The [PluginWorld] of plugin [pluginId] over [world]. A null [pluginId] (raw builtins) has no
 * namespace of its own, so attribute keys must be qualified and can't be written (D-177).
 */
internal class WorldFacade(
  val world: World,
  private val pluginId: String?,
  private val logSink: (String) -> Unit,
) : PluginWorld {
  // A stale view fails as it does in `Ctx`, rather than reaching `core` (D-177).
  private fun actorOf(a: PluginActor, what: String): Actor {
    val actor =
      (a as? ActorFacade)?.actor
        ?: throw IllegalArgumentException("$what: not an actor of this world")
    require(actor.inWorld) { "$what: ${actor.name} is no longer in the world" }
    return actor
  }

  private fun roomOf(r: PluginRoom, what: String): Room =
    (r as? RoomFacade)?.room ?: throw IllegalArgumentException("$what: not a room of this world")

  private fun entityOf(e: PluginEntity, what: String): Entity =
    when (e) {
      is ActorFacade -> actorOf(e, what)
      is ItemFacade ->
        e.item.also {
          require(world.item(it.id) === it) { "$what: ${it.name} is no longer in the world" }
        }
      else ->
        (e as? EntityFacade)?.entity
          ?: throw IllegalArgumentException("$what: not an entity of this world")
    }

  override fun send(target: PluginActor, text: String) =
    world.send(actorOf(target, "send(target)"), text)

  override fun echo(room: PluginRoom, text: String, exclude: List<PluginActor>) =
    world.echo(
      roomOf(room, "echo(room)"),
      text,
      *exclude.map { actorOf(it, "echo(exclude)") }.toTypedArray(),
    )

  override fun damage(target: PluginActor, amount: Int, type: String, source: PluginActor?) {
    require(amount >= 0) { "damage(amount): can't be negative; use heal()" }
    world.damage(
      actorOf(target, "damage(target)"),
      amount,
      DamageType(type.lowercase()),
      source?.let { actorOf(it, "damage(source)") },
    )
  }

  override fun heal(target: PluginActor, amount: Int) {
    require(amount >= 0) { "heal(amount): can't be negative; use damage()" }
    world.heal(actorOf(target, "heal(target)"), amount)
  }

  override fun applyAffect(
    target: PluginActor,
    name: String,
    duration: Int,
    statDeltas: Map<String, Int>,
    flags: Set<String>,
  ) {
    require(name.isNotBlank()) { "apply_affect(name): must not be blank" }
    require(duration >= 1) { "apply_affect(duration): must be at least 1" }
    world.applyAffect(actorOf(target, "apply_affect(target)"), name, duration, statDeltas, flags)
  }

  override fun removeAffect(target: PluginActor, name: String): Boolean =
    world.removeAffect(actorOf(target, "remove_affect(target)"), name)

  override fun teleport(actor: PluginActor, room: PluginRoom) =
    world.teleport(actorOf(actor, "teleport(actor)"), roomOf(room, "teleport(room)"))

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
    val e = entityOf(entity, "set_attr(entity)")
    val k = key(key, write = true, "set_attr(key)")
    if (value == null) e.customAttributes.remove(k)
    else e.customAttributes[k] = toAttr(value, "set_attr(value)")
    e.dirty = true
  }

  override fun delAttr(entity: PluginEntity, key: String) {
    val e = entityOf(entity, "del_attr(entity)")
    if (e.customAttributes.remove(key(key, write = true, "del_attr(key)")) != null) e.dirty = true
  }

  override val pulse: Long
    get() = world.pulse

  override val seed: Long
    get() = world.config.seed

  override val rng: PluginRng
    get() = RngFacade(world)

  override fun rooms(): List<PluginRoom> = world.rooms.sortedBy { it.label.v }.map(::RoomFacade)

  override fun room(label: String): PluginRoom? =
    runCatching { Label(label) }
      .getOrNull()
      ?.let { l -> world.rooms.firstOrNull { it.label == l } }
      ?.let(::RoomFacade)

  override fun regions(): List<String> = world.rooms.flatMap { it.regions }.toSortedSet().toList()

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
