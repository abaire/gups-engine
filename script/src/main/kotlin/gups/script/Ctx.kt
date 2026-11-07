package gups.script

import gups.core.AttrValue
import gups.core.Label
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
import gups.core.model.Entity
import gups.core.model.GroupSnapshot
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Room
import gups.core.model.TAG_REGEX
import gups.core.world.BUILTIN_STANDING_EVENTS
import gups.core.world.Feature
import gups.core.world.GMCP_PACKAGE_NAME
import gups.core.world.HookContext
import gups.core.world.InvariantViolation
import gups.core.world.Proposal
import gups.core.world.STANDING_EVENT_NAME
import gups.core.world.StandingEvent
import gups.core.world.act
import gups.core.world.actAdvance
import gups.core.world.actAttack
import gups.core.world.actCast
import gups.core.world.actMove
import gups.core.world.actRetreat
import gups.core.world.addItemTag
import gups.core.world.applyAffect
import gups.core.world.applyItemAffect
import gups.core.world.applyRoomAffect
import gups.core.world.applyStanding
import gups.core.world.awardXp
import gups.core.world.callHook
import gups.core.world.clearActive
import gups.core.world.clearAnchor
import gups.core.world.clearContributions
import gups.core.world.clearOwner
import gups.core.world.clearStanding
import gups.core.world.damage
import gups.core.world.destroy
import gups.core.world.disposition
import gups.core.world.echo
import gups.core.world.emote
import gups.core.world.factionsOf
import gups.core.world.findActor
import gups.core.world.gmcp
import gups.core.world.gmcpWithin
import gups.core.world.groupAdd
import gups.core.world.groupDisband
import gups.core.world.groupMembers
import gups.core.world.groupOf
import gups.core.world.groupRemove
import gups.core.world.groupSetLeader
import gups.core.world.heal
import gups.core.world.isDying
import gups.core.world.isSimulated
import gups.core.world.levelDown
import gups.core.world.levelUpAndNotify
import gups.core.world.moveItem
import gups.core.world.opponentsOf
import gups.core.world.pathTo
import gups.core.world.perceive
import gups.core.world.raiseStandingEvent
import gups.core.world.removeAffect
import gups.core.world.removeItemAffect
import gups.core.world.removeItemTag
import gups.core.world.removeRoomAffect
import gups.core.world.removeSkill
import gups.core.world.roomOfEntity
import gups.core.world.say
import gups.core.world.send
import gups.core.world.setActive
import gups.core.world.setAnchor
import gups.core.world.setCharges
import gups.core.world.setContainerState
import gups.core.world.setDoor
import gups.core.world.setOwner
import gups.core.world.setSkill
import gups.core.world.spawnItem
import gups.core.world.stack
import gups.core.world.standing
import gups.core.world.standingHistory
import gups.core.world.startCombat
import gups.core.world.stopCombat
import gups.core.world.summon
import gups.core.world.takeXp
import gups.core.world.teleport
import gups.core.world.transformItem
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.Printer
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue

/** What `ctx.deny(msg)` returns; a `before_*` hook returns it to veto. */
@StarlarkBuiltin(name = "denial", doc = "A veto, from ctx.deny(msg).")
class Denial internal constructor(val message: String) : StarlarkValue {
  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    printer.append("<deny ").repr(message).append(">")
  }
}

/** Per-invocation extras that are not in [HookContext]. */
internal class CtxExtras(
  val proposals: List<Proposal>? = null,
  /** A zone or global command's argument text. */
  val commandArgs: String? = null,
  val commandTarget: Actor? = null,
  val targets: List<Actor>? = null,
  val power: Int? = null,
)

/**
 * The `ctx` handed to one hook invocation. Created fresh per call and invalid once the hook
 * returns. Fields are read-only views; every mutation is a verb the engine validates and applies at
 * once, on the world thread.
 */
@StarlarkBuiltin(name = "ctx", doc = "The context of one hook invocation.")
class Ctx
internal constructor(
  private val frame: Frame,
  private val owner: Entity,
  private val hc: HookContext,
  private val extras: CtxExtras,
) : StarlarkValue {
  private val world
    get() = frame.world

  private fun <T> field(block: () -> T): T {
    frame.check()
    return block()
  }

  /** Every verb: validity, the wall-clock backstop, tracing, and engine errors as script errors. */
  private fun <T> verb(name: String, detail: String = "", block: () -> T): T {
    frame.check()
    FeatureOwnership.refused("ctx.$name", "ctx.$name()", world.features)?.let { fail(it) }
    frame.checkClock()
    frame.trace("  $name($detail)")
    try {
      return block()
    } catch (e: InvariantViolation) {
      throw e
    } catch (e: IllegalArgumentException) {
      fail("ctx.$name(): ${e.message}")
    } catch (e: IllegalStateException) {
      fail("ctx.$name(): ${e.message}")
    } catch (e: NoSuchElementException) {
      fail("ctx.$name(): ${e.message}")
    }
  }

  private fun v(e: Entity?): Any = view(frame, e)

  /** A non-zero track [index] needs the track feature. */
  private fun trackIndex(verb: String, index: Any?) {
    val i = intOrNull(index, "$verb(index)") ?: return
    if (i != 0)
      FeatureOwnership.refused("ctx.$verb.index", "ctx.$verb(index)", world.features)?.let {
        fail(it)
      }
  }

  private fun pkgOf(thread: StarlarkThread): String = callerFile(thread)?.pkg ?: frame.pkg

  // ---- Fields --------------------------------------------------------------------------------

  @StarlarkMethod(name = "self", structField = true, doc = "The hook's owner.")
  fun self(): Any = field { v(owner) }

  @StarlarkMethod(
    name = "actor",
    structField = true,
    doc = "The actor performing the action, or the damage/kill source.",
  )
  fun actor(): Any = field { v(hc.actor) }

  @StarlarkMethod(
    name = "target",
    structField = true,
    doc = "The target actor, or the target item of use and item skills.",
  )
  fun target(): Any = field { v(hc.target ?: hc.targetItem ?: extras.commandTarget) }

  @StarlarkMethod(
    name = "targets",
    structField = true,
    doc = "Opponents for on_combat_tick, or summoned mobs for on_summon.",
  )
  fun targets(): Any = field {
    (hc.mobs ?: extras.targets)?.let { t -> sList(t.map { v(it) }) } ?: Starlark.NONE
  }

  @StarlarkMethod(
    name = "label",
    structField = true,
    doc = "The prototype label of mobs being summoned, for before_summon.",
  )
  fun label(): Any = field { hc.summonLabel?.v ?: Starlark.NONE }

  @StarlarkMethod(
    name = "count",
    structField = true,
    doc = "How many mobs are being summoned, for before_summon.",
  )
  fun count(): Any = field { hc.count?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "item",
    structField = true,
    doc = "The item being picked up, dropped, used or given.",
  )
  fun item(): Any = field { v(hc.item) }

  @StarlarkMethod(name = "room", structField = true, doc = "The room the event happens in.")
  fun room(): Any = field { v(hc.room ?: world.roomOfEntity(owner)) }

  @StarlarkMethod(
    name = "direction",
    structField = true,
    doc = "The movement direction name, for enter and exit hooks.",
  )
  fun direction(): Any = field { hc.direction?.name ?: Starlark.NONE }

  @StarlarkMethod(
    name = "from_room",
    structField = true,
    doc = "The room the actor came from, for enter hooks.",
  )
  fun fromRoom(): Any = field { v(hc.fromRoom) }

  @StarlarkMethod(
    name = "to_room",
    structField = true,
    doc = "The room the actor is going to, for exit hooks.",
  )
  fun toRoom(): Any = field { v(hc.toRoom) }

  @StarlarkMethod(name = "text", structField = true, doc = "Spoken text, for on_say and on_tell.")
  fun text(): Any = field { hc.text ?: Starlark.NONE }

  @StarlarkMethod(
    name = "args",
    structField = true,
    doc = "A command's argument text, or a timer's keyword arguments as a dict.",
  )
  fun args(): Any = field {
    extras.commandArgs
      ?: if (hc.args.isEmpty()) Starlark.NONE
      else sDict(hc.args.mapValues { fromAttr(it.value, null) })
  }

  @StarlarkMethod(
    name = "event",
    structField = true,
    doc =
      "For on_perceive: 'say', 'emote', 'shout', 'combat_start', 'combat', 'death', 'enter', 'exit', 'open', 'close', 'lock', 'unlock' or the event a script passed to ctx.emit. For on_group_change: 'join', 'leave', 'leader' or 'disband'. For on_owner_change: 'set' or 'clear'. For on_disengage: 'flee', 'death' or 'stop'.",
  )
  fun event(): Any = field { hc.event ?: Starlark.NONE }

  @StarlarkMethod(
    name = "group",
    structField = true,
    doc = "The group after the change, for on_group_change; None elsewhere.",
  )
  fun group(): Any = field { hc.group?.let { GroupView(frame, it) } ?: Starlark.NONE }

  @StarlarkMethod(
    name = "channel",
    structField = true,
    doc = "For on_perceive: the channel the event travelled on, such as 'sound'.",
  )
  fun channel(): Any = field { hc.channel ?: Starlark.NONE }

  @StarlarkMethod(
    name = "intensity",
    structField = true,
    doc = "For on_perceive: the event's strength where it happened.",
  )
  fun intensity(): Any = field { hc.intensity?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "cost",
    structField = true,
    doc =
      "For on_perceive: how much the event weakened on its way here; 0 in the room it happened in.",
  )
  fun cost(): Any = field { hc.cost?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "source_room",
    structField = true,
    doc = "For on_perceive: the room the event happened in.",
  )
  fun sourceRoom(): Any = field { v(hc.sourceRoom) }

  @StarlarkMethod(
    name = "via_exit",
    structField = true,
    doc = "For on_perceive: this mob's own exit toward the event, or None for its own room.",
  )
  fun viaExit(): Any = field { hc.viaExit?.name ?: Starlark.NONE }

  @StarlarkMethod(
    name = "skill",
    structField = true,
    doc = "The skill being cast, for before_cast, on_cast_start and on_cast.",
  )
  fun skill(): Any = field { hc.skill?.let { SkillView(it, frame.world.schema) } ?: Starlark.NONE }

  @StarlarkMethod(
    name = "power",
    structField = true,
    doc = "In a skill's resolve, the cast's power; None elsewhere.",
  )
  fun power(): Any = field { extras.power?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "weapon",
    structField = true,
    doc = "The weapon used in an attack, or None for unarmed.",
  )
  fun weapon(): Any = field { v(hc.weapon) }

  @StarlarkMethod(
    name = "amount",
    structField = true,
    doc = "The total of the vital losses a damage event caused, for on_damage.",
  )
  fun amount(): Any = field { hc.amount?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "losses",
    structField = true,
    doc = "The vital losses behind amount, as vitals(...), for on_damage.",
  )
  fun losses(): Any = field {
    hc.losses?.let { TypedMap(TypedKind.VITALS, it, frame.world.schema.vitalNames) }
      ?: Starlark.NONE
  }

  @StarlarkMethod(
    name = "parts",
    structField = true,
    doc = "The damage event's parts, the hit's type first, as struct(type, losses), for on_damage.",
  )
  fun parts(): Any = field {
    hc.parts?.let {
      sList(
        it.map { part ->
          StructValue(
            mapOf(
              "type" to part.type.name,
              "losses" to TypedMap(TypedKind.VITALS, part.losses, frame.world.schema.vitalNames),
            )
          )
        }
      )
    } ?: Starlark.NONE
  }

  @StarlarkMethod(name = "type", structField = true, doc = "The damage type, for on_damage.")
  fun type(): Any = field { hc.type?.name ?: Starlark.NONE }

  @StarlarkMethod(
    name = "affect",
    structField = true,
    doc = "The affect, for affect on_tick and on_expire.",
  )
  fun affect(): Any = field {
    val a = hc.affect ?: return@field Starlark.NONE
    AffectView(frame, owner as? Actor ?: owner as? Item ?: return@field Starlark.NONE, a)
  }

  @StarlarkMethod(
    name = "reason",
    structField = true,
    doc = "Why the affect ended, 'expired' or 'removed', for affect on_expire.",
  )
  fun reason(): Any = field { hc.affectEndReason?.name?.lowercase() ?: Starlark.NONE }

  @StarlarkMethod(
    name = "proposals",
    structField = true,
    doc = "For think: behavior proposals, best first.",
  )
  fun proposals(): Any = field {
    extras.proposals?.let { p -> sList(p.map(::ProposalView)) } ?: Starlark.NONE
  }

  @StarlarkMethod(
    name = "elapsed_pulses",
    structField = true,
    doc = "Pulses spent dormant, for on_wake.",
  )
  fun elapsedPulses(): Any = field { hc.elapsedPulses?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(name = "pulse", structField = true, doc = "Game time.")
  fun pulse(): Any = field { sInt(world.pulse) }

  @StarlarkMethod(name = "rng", structField = true, doc = "The seeded random number generator.")
  fun rng(): Any = field { RngView(frame) }

  @StarlarkMethod(
    name = "old_level",
    structField = true,
    doc = "For on_level_change: the actor's level before the change.",
  )
  fun oldLevel(): Any = field { hc.oldLevel?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "new_level",
    structField = true,
    doc = "For on_level_change: the actor's level after the change.",
  )
  fun newLevel(): Any = field { hc.newLevel?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "old_encumbrance_level",
    structField = true,
    doc = "For on_encumbrance_change: the actor's encumbrance level before the change.",
  )
  fun oldEncumbranceLevel(): Any = field { hc.oldEncumbranceLevel?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "new_encumbrance_level",
    structField = true,
    doc = "For on_encumbrance_change: the actor's encumbrance level after the change.",
  )
  fun newEncumbranceLevel(): Any = field { hc.newEncumbranceLevel?.let(::sInt) ?: Starlark.NONE }

  // ---- Argument conversion -------------------------------------------------------------------

  /** The in-world actor that applied the affect whose hook is running, or null. */
  private fun affectApplier(): Actor? = hc.affect?.source?.let(world::actor)?.takeIf { it.inWorld }

  private fun actorArg(x: Any?, what: String): Actor {
    val a = (x as? ActorView)?.actor ?: fail("$what: expected an actor, got ${typeName(x)}")
    if (!a.inWorld) fail("$what: ${a.name} is no longer in the world")
    return a
  }

  private fun mobArg(x: Any?, what: String): Mob =
    actorArg(x, what) as? Mob ?: fail("$what: mob actions apply only to mobs, never players")

  private fun playerArg(x: Any?, what: String): Player =
    actorArg(x, what) as? Player ?: fail("$what: must be a player")

  private fun itemArg(x: Any?, what: String): Item {
    val i = (x as? ItemView)?.item ?: fail("$what: expected an item, got ${typeName(x)}")
    if (world.item(i.id) !== i) fail("$what: ${i.name} is no longer in the world")
    return i
  }

  private fun roomArg(x: Any?, what: String, thread: StarlarkThread): Room =
    when (x) {
      is RoomView -> x.room
      is String ->
        world.roomOrNull(label(x, pkgOf(thread), what)) ?: fail("$what: there is no room $x")
      else -> fail("$what: expected a room or a room label, got ${typeName(x)}")
    }

  private fun entityArg(x: Any?, what: String): Entity =
    when (x) {
      is ActorView -> actorArg(x, what)
      is ItemView -> itemArg(x, what)
      is RoomView -> x.room
      else -> fail("$what: expected an actor, room or item, got ${typeName(x)}")
    }

  /** An actor, item or room view whose entity is still in the world; null for a stale view. */
  private fun hookOwner(x: Any?, what: String): Entity? =
    when (x) {
      is ActorView -> if (x.actor.inWorld) x.actor else null
      is ItemView -> world.item(x.item.id)
      is RoomView -> x.room
      else -> fail("$what: expected an actor, item or room, got ${typeName(x)}")
    }

  private fun text(x: Any?, what: String): String =
    checkString(str(x, what), what, frame.host.limits)

  private fun dir(x: Any?, what: String): Direction = Direction.parse(str(x, what))

  /** A destination: a room (ground, at [index]), an actor (carried) or a container. */
  private fun location(at: Any?, index: Any?, what: String, thread: StarlarkThread): ItemLocation =
    when (at) {
      is ActorView -> ItemLocation.Carried(actorArg(at, what).id)
      is ItemView -> ItemLocation.Contained(itemArg(at, what).id)
      else ->
        ItemLocation.InRoom(roomArg(at, what, thread).id, intOrNull(index, "$what index") ?: 0)
    }

  // ---- Messaging -----------------------------------------------------------------------------

  @StarlarkMethod(
    name = "say",
    doc = "speaker says text aloud; fires on_say.",
    parameters =
      [
        Param(name = "speaker", named = true, doc = "Actor who speaks."),
        Param(name = "text", named = true, doc = "Text spoken."),
      ],
  )
  fun say(speaker: Any, text: Any): Any =
    verb("say") {
      world.say(actorArg(speaker, "say(speaker)"), text(text, "say(text)"))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "emote",
    doc = "The room sees '<actor> text'.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor performing the emote."),
        Param(name = "text", named = true, doc = "Text that follows the actor's name."),
      ],
  )
  fun emote(actor: Any, text: Any): Any =
    verb("emote") {
      world.emote(actorArg(actor, "emote(actor)"), text(text, "emote(text)"))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "send",
    doc = "Sends text to one actor.",
    parameters =
      [
        Param(name = "target", named = true, doc = "Actor who receives the text."),
        Param(name = "text", named = true, doc = "Text to send."),
      ],
  )
  fun send(target: Any, text: Any): Any =
    verb("send") {
      world.send(actorArg(target, "send(target)"), text(text, "send(text)"))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "gmcp",
    doc = "Sends a GMCP event to a player's client. Returns whether it was sent.",
    parameters =
      [
        Param(
          name = "player",
          named = true,
          doc = "Actor who receives the event; mobs receive nothing.",
        ),
        Param(
          name = "package",
          named = true,
          doc = "A GMCP package this package declared, or one nested in it.",
        ),
        Param(
          name = "data",
          named = true,
          doc = "None, int, str, bool, list or dict (16 KB of JSON at most).",
        ),
      ],
    useStarlarkThread = true,
  )
  fun gmcp(player: Any, pkg: Any, data: Any, thread: StarlarkThread): Any =
    verb("gmcp", "$pkg") {
      val a = actorArg(player, "gmcp(player)")
      val pkgName = str(pkg, "gmcp(package)")
      require(GMCP_PACKAGE_NAME.matches(pkgName)) { "'$pkgName' is not a GMCP package name" }
      val caller = pkgOf(thread)
      val declared = frame.host.scripts.packages[caller]?.gmcpPackages.orEmpty()
      if (declared.none { gmcpWithin(pkgName, it.name) }) {
        val declarer =
          frame.host.scripts.packages.values.firstOrNull {
            it.gmcpPackages.any { d -> gmcpWithin(pkgName, d.name) }
          }
        fail(
          "gmcp(package): code in //$caller didn't declare a GMCP package for '$pkgName'" +
            (declarer?.let { " (use the helpers exported by //${it.path})" } ?: "")
        )
      }
      val payload = toGmcp(data, "gmcp(data)", frame.host.limits)
      if (a !is Player) false else world.gmcp(a, pkgName, payload)
    }

  @StarlarkMethod(
    name = "call_hook",
    doc =
      "Runs entity's custom hook name as fn(ctx, **args) with ctx.self the entity, then the plugin handlers for name, and returns their result as a fresh copy.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room whose hook runs."),
        Param(name = "name", named = true, doc = "Custom hook name."),
      ],
    extraKeywords =
      Param(
        name = "args",
        doc =
          "Keyword arguments for the hook: int, str, bool, list, dict or actor, item and room views.",
      ),
    useStarlarkThread = true,
  )
  fun callHook(entity: Any, name: Any, args: Dict<String, Any>, thread: StarlarkThread): Any =
    verb("call_hook", "$name") {
      val hookName = str(name, "call_hook(name)")
      if (!TAG_REGEX.matches(hookName))
        fail("call_hook(name): '$hookName' is not a valid hook name")
      val plain =
        args.entries.associate { (k, x) ->
          k to toPlain(x, "call_hook($k)", frame.host.limits, views = true)
        }
      val e = hookOwner(entity, "call_hook(entity)") ?: return@verb Starlark.NONE
      frame.host.beginCustomHook()
      try {
        fromPlain(world.callHook(e, hookName, plain), thread.mutability(), frame)
      } finally {
        frame.host.endCustomHook()
      }
    }

  @StarlarkMethod(
    name = "echo",
    doc = "Sends text to everyone in a room, except the actors in exclude.",
    parameters =
      [
        Param(name = "room", named = true, doc = "Room, or room label, to send the text to."),
        Param(name = "text", named = true, doc = "Text to send."),
        Param(
          name = "exclude",
          named = true,
          defaultValue = "[]",
          doc = "Actors who don't receive the text.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun echo(room: Any, text: Any, exclude: Any, thread: StarlarkThread): Any =
    verb("echo") {
      val skip = list(exclude, "echo(exclude)").map { actorArg(it, "echo(exclude)") }
      world.echo(
        roomArg(room, "echo(room)", thread),
        text(text, "echo(text)"),
        *skip.toTypedArray(),
      )
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "emit",
    doc =
      "Raises a perceivable event on a channel. It spreads through nearby rooms from room, weakening on the way; actors who can perceive it hear or see it through on_perceive and rules.perceive_message.",
    parameters =
      [
        Param(name = "channel", named = true, doc = "A built-in or declared channel name."),
        Param(name = "event", named = true, doc = "Event name that on_perceive hooks see."),
        Param(
          name = "intensity",
          named = true,
          defaultValue = "1",
          doc = "Strength at the origin; 0 or less emits nothing.",
        ),
        Param(
          name = "text",
          named = true,
          defaultValue = "None",
          doc = "Text that goes with the event.",
        ),
        Param(
          name = "room",
          named = true,
          defaultValue = "None",
          doc = "Room, or room label, where it happens; defaults to ctx.room.",
        ),
        Param(
          name = "actor",
          named = true,
          defaultValue = "None",
          doc = "Actor who caused it; defaults to ctx.actor. The causer does not perceive it.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun emit(
    channel: Any,
    event: Any,
    intensity: Any,
    text: Any,
    room: Any,
    actor: Any,
    thread: StarlarkThread,
  ): Any =
    verb("emit") {
      val known = world.schema.allChannels.map { it.name }
      val ch = Channel(choice(str(channel, "emit(channel)"), "emit(channel)", known))
      val name = text(event, "emit(event)")
      if (name.isBlank()) fail("emit(event): must not be empty")
      val strength = int(intensity, "emit(intensity)")
      val body = if (isNone(text)) null else text(text, "emit(text)")
      val at =
        if (isNone(room)) hc.room ?: world.roomOfEntity(owner)
        else roomArg(room, "emit(room)", thread)
      val cause = if (isNone(actor)) hc.actor else actorArg(actor, "emit(actor)")
      if (strength > 0 && at != null) world.perceive(name, ch, at, cause, body, strength)
      Starlark.NONE
    }

  // ---- Veto ----------------------------------------------------------------------------------

  @StarlarkMethod(
    name = "deny",
    doc = "Vetoes the action; return it from a before_* hook.",
    parameters =
      [Param(name = "msg", named = true, doc = "Text shown to the actor whose action is refused.")],
  )
  fun deny(msg: Any): Any =
    verb("deny") {
      if (!frame.site.vetoable)
        fail(
          "deny() is only valid as the return value of a before_* hook (this is ${frame.site.hook})"
        )
      Denial(text(msg, "deny(msg)"))
    }

  // ---- Mob actions (tick lane) ---------------------------------------------------------------

  private fun acted(mob: Mob, ok: Boolean): Any {
    if (mob === owner) frame.acted = true
    return ok
  }

  @StarlarkMethod(
    name = "act",
    doc = "Queues a command line for a mob, exactly as if it typed it.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that acts."),
        Param(
          name = "command_line",
          named = true,
          doc = "The command line, as a player would type it.",
        ),
      ],
  )
  fun act(mob: Any, commandLine: Any): Any =
    verb("act") {
      val m = mobArg(mob, "act(mob)")
      acted(m, world.act(m, text(commandLine, "act(command_line)")))
    }

  @StarlarkMethod(
    name = "act_move",
    doc = "Queues a move through an exit for a mob.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that acts."),
        Param(name = "dir", named = true, doc = "Direction of the exit to take."),
      ],
  )
  fun actMove(mob: Any, dir: Any): Any =
    verb("act_move") {
      val m = mobArg(mob, "act_move(mob)")
      acted(m, world.actMove(m, dir(dir, "act_move(dir)")))
    }

  @StarlarkMethod(
    name = "act_attack",
    doc = "Queues an attack against target for a mob.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that acts."),
        Param(name = "target", named = true, doc = "Actor to attack."),
      ],
  )
  fun actAttack(mob: Any, target: Any): Any =
    verb("act_attack") {
      val m = mobArg(mob, "act_attack(mob)")
      acted(m, world.actAttack(m, actorArg(target, "act_attack(target)")))
    }

  @StarlarkMethod(
    name = "act_cast",
    doc = "Queues casting a skill for a mob.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that acts."),
        Param(name = "skill", named = true, doc = "Skill label to cast."),
        Param(
          name = "target",
          defaultValue = "None",
          named = true,
          doc = "Target actor or item, or None for the skill's default target.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun actCast(mob: Any, skill: Any, target: Any, thread: StarlarkThread): Any =
    verb("act_cast") {
      val m = mobArg(mob, "act_cast(mob)")
      val proto =
        when (skill) {
          is SkillView -> skill.skill
          else ->
            world.protos.skills[
                label(str(skill, "act_cast(skill)"), pkgOf(thread), "act_cast(skill)")]
              ?: fail("act_cast(skill): there is no skill $skill")
        }
      acted(
        m,
        world.actCast(m, proto, if (isNone(target)) null else actorArg(target, "act_cast(target)")),
      )
    }

  @StarlarkMethod(
    name = "act_advance",
    doc = "Queues one step along the room track toward target for a mob.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that acts."),
        Param(name = "target", named = true, doc = "Actor or item in the same room to step toward."),
      ],
  )
  fun actAdvance(mob: Any, target: Any): Any =
    verb("act_advance") {
      val m = mobArg(mob, "act_advance(mob)")
      acted(m, world.actAdvance(m, actorArg(target, "act_advance(target)")))
    }

  @StarlarkMethod(
    name = "act_retreat",
    doc =
      "Queues one step along the room track away from target (or toward the room back) for a mob.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that acts."),
        Param(
          name = "target",
          defaultValue = "None",
          named = true,
          doc = "Actor or item to step away from, or None to step toward the back of the room.",
        ),
      ],
  )
  fun actRetreat(mob: Any, target: Any): Any =
    verb("act_retreat") {
      val m = mobArg(mob, "act_retreat(mob)")
      acted(
        m,
        world.actRetreat(m, if (isNone(target)) null else actorArg(target, "act_retreat(target)")),
      )
    }

  @StarlarkMethod(
    name = "move_toward",
    doc =
      "Queues one step toward a direction, an actor (advancing within the room) or a room. False if there is no way.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that acts."),
        Param(
          name = "dir_or_target",
          named = true,
          doc = "Direction, actor, or room (view or label) to head for.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun moveToward(mob: Any, dirOrTarget: Any, thread: StarlarkThread): Any =
    verb("move_toward") {
      val m = mobArg(mob, "move_toward(mob)")
      val dest: Room =
        when (dirOrTarget) {
          is ActorView -> {
            val t = actorArg(dirOrTarget, "move_toward(target)")
            if (t.room === m.room) {
              return@verb if (t.trackIndex == m.trackIndex) false
              else acted(m, world.actAdvance(m, t))
            }
            t.room
          }
          is String ->
            if (dirOrTarget.startsWith("//") || dirOrTarget.startsWith(":")) {
              roomArg(dirOrTarget, "move_toward(target)", thread)
            } else {
              return@verb acted(m, world.actMove(m, dir(dirOrTarget, "move_toward(dir)")))
            }
          else -> roomArg(dirOrTarget, "move_toward(target)", thread)
        }
      val step = world.pathTo(m.room, dest)?.firstOrNull() ?: return@verb false
      acted(m, world.actMove(m, step))
    }

  // ---- Movement ------------------------------------------------------------------------------

  @StarlarkMethod(
    name = "teleport",
    doc = "Moves an actor to a room; fires the exit and enter hooks.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor to move."),
        Param(name = "room", named = true, doc = "Destination room or room label."),
        Param(
          name = "index",
          defaultValue = "None",
          named = true,
          doc = "Track index on arrival, or None for the room's default.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun teleport(actor: Any, room: Any, index: Any, thread: StarlarkThread): Any =
    verb("teleport") {
      trackIndex("teleport", index)
      val r = roomArg(room, "teleport(room)", thread)
      val i = intOrNull(index, "teleport(index)")
      if (i != null && i !in 0 until r.trackSize) fail("teleport(index): $i is outside ${r.label}")
      world.teleport(actorArg(actor, "teleport(actor)"), r, i)
      Starlark.NONE
    }

  // ---- Combat --------------------------------------------------------------------------------

  @StarlarkMethod(
    name = "damage",
    doc =
      "Deals damage directly to one vital (no to-hit roll, no mitigation); fires on_damage with just that loss.",
    parameters =
      [
        Param(name = "target", named = true, doc = "Actor to damage."),
        Param(name = "amount", named = true, doc = "Points of damage."),
        Param(name = "type", named = true, doc = "A declared damage type, such as DAMAGE.fire."),
        Param(name = "vital", named = true, doc = "The declared vital that loses the points."),
        Param(
          name = "source",
          defaultValue = "None",
          named = true,
          doc =
            "Actor credited with the damage; an omitted or None source is the applier of the affect whose hook is running.",
        ),
      ],
  )
  fun damage(target: Any, amount: Any, type: Any, vital: Any, source: Any): Any =
    verb("damage") {
      val t = actorArg(target, "damage(target)")
      val n = int(amount, "damage(amount)")
      if (n < 0) fail("damage(amount): can't be negative; use heal()")
      val schema = world.schema
      val damageType =
        checkName(
          str(type, "damage(type)").lowercase(),
          schema.damageTypes,
          "damage type",
          "damage(type)",
        )
      val name = checkName(str(vital, "damage(vital)"), schema.vitalNames, "vital", "damage(vital)")
      world.damage(
        t,
        mapOf(name to n),
        DamageType(damageType),
        if (isNone(source)) affectApplier() else actorArg(source, "damage(source)"),
      )
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "heal",
    doc = "Restores a vital of target, up to its maximum.",
    parameters =
      [
        Param(name = "target", named = true, doc = "Actor to heal."),
        Param(name = "amount", named = true, doc = "Points to restore."),
        Param(name = "vital", named = true, doc = "The declared vital to restore."),
        Param(
          name = "source",
          defaultValue = "None",
          named = true,
          doc =
            "Actor credited with the healing; an omitted or None source is the applier of the affect whose hook is running.",
        ),
      ],
  )
  fun heal(target: Any, amount: Any, vital: Any, source: Any): Any =
    verb("heal") {
      val n = int(amount, "heal(amount)")
      if (n < 0) fail("heal(amount): can't be negative; use damage()")
      val name =
        checkName(str(vital, "heal(vital)"), world.schema.vitalNames, "vital", "heal(vital)")
      world.heal(
        actorArg(target, "heal(target)"),
        name,
        n,
        source = if (isNone(source)) affectApplier() else actorArg(source, "heal(source)"),
      )
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "award_xp",
    doc =
      "Awards experience points to actor, applying pending levels in auto mode or sending" +
        " the ready notice in deferred mode. Returns None. Needs the leveling feature.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor to receive experience."),
        Param(name = "amount", named = true, doc = "Points to award; must be at least 1."),
      ],
  )
  fun awardXp(actor: Any, amount: Any): Any =
    verb("award_xp") {
      val a = actorArg(actor, "award_xp(actor)")
      val n = long(amount, "award_xp(amount)")
      if (n < 1L) fail("award_xp(amount): must be at least 1")
      world.awardXp(a, n)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "take_xp",
    doc =
      "Removes up to amount experience points from actor, never going below 0. Never changes" +
        " level and sends no message. Returns the amount actually removed. Needs the leveling" +
        " feature.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor to remove experience from."),
        Param(name = "amount", named = true, doc = "Points to remove; must be at least 1."),
      ],
  )
  fun takeXp(actor: Any, amount: Any): Any =
    verb("take_xp") {
      val a = actorArg(actor, "take_xp(actor)")
      val n = long(amount, "take_xp(amount)")
      if (n < 1L) fail("take_xp(amount): must be at least 1")
      sInt(world.takeXp(a, n))
    }

  @StarlarkMethod(
    name = "level_up",
    doc =
      "Applies one pending level to actor, in either level mode. In deferred mode, sends the" +
        " ready notice again if more levels are still pending. Returns True if a level was" +
        " gained, or False, changing nothing, if none was pending. Needs the leveling feature.",
    parameters = [Param(name = "actor", named = true, doc = "Actor to advance.")],
  )
  fun levelUp(actor: Any): Any =
    verb("level_up") {
      val a = actorArg(actor, "level_up(actor)")
      world.levelUpAndNotify(a)
    }

  @StarlarkMethod(
    name = "level_down",
    doc =
      "Lowers actor by up to count levels, stopping at level 1. Returns the number of levels" +
        " actually lost. Needs the leveling feature.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor to lower."),
        Param(
          name = "count",
          named = true,
          defaultValue = "1",
          doc = "Number of level steps to unwind; must be at least 1.",
        ),
      ],
  )
  fun levelDown(actor: Any, count: Any): Any =
    verb("level_down") {
      val a = actorArg(actor, "level_down(actor)")
      val c = int(count, "level_down(count)")
      if (c < 1) fail("level_down(count): must be at least 1")
      sInt(world.levelDown(a, c))
    }

  // ---- Skills -------------------------------------------------------------------------------

  /** A skill argument: a skill view's label, or a string resolved against the caller's package. */
  private fun skillArg(skill: Any?, what: String, thread: StarlarkThread): Label =
    when (skill) {
      is SkillView -> skill.skill.label
      is String -> label(skill, pkgOf(thread), "$what(skill)")
      else -> fail("$what(skill): expected a skill label or skill view, got ${typeName(skill)}")
    }

  @StarlarkMethod(
    name = "set_skill",
    doc =
      "Gives actor the skill, a label or skill view, at proficiency (0 to 100), or changes the" +
        " proficiency it has. Runs the learn restrictions and returns the refusal message, or None" +
        " once the skill is set. Needs the skills feature.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who learns or raises the skill."),
        Param(name = "skill", named = true, doc = "Skill label, or the skill's view."),
        Param(name = "proficiency", named = true, doc = "Proficiency, 0 to 100."),
      ],
    useStarlarkThread = true,
  )
  fun setSkill(actor: Any, skill: Any, proficiency: Any, thread: StarlarkThread): Any =
    verb("set_skill") {
      val a = actorArg(actor, "set_skill(actor)")
      val l = skillArg(skill, "set_skill", thread)
      if (l !in world.protos.skills) fail("set_skill(skill): unknown skill '${l.v}'")
      val n = int(proficiency, "set_skill(proficiency)")
      if (n !in 0..100) fail("set_skill(proficiency): must be 0 to 100, got $n")
      world.setSkill(a, l, n) ?: Starlark.NONE
    }

  @StarlarkMethod(
    name = "remove_skill",
    doc = "Forgets the skill, a label or skill view, and returns whether the actor knew it.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who forgets the skill."),
        Param(name = "skill", named = true, doc = "Skill label, or the skill's view."),
      ],
    useStarlarkThread = true,
  )
  fun removeSkill(actor: Any, skill: Any, thread: StarlarkThread): Any =
    verb("remove_skill") {
      val a = actorArg(actor, "remove_skill(actor)")
      val l = skillArg(skill, "remove_skill", thread)
      if (l !in world.protos.skills) fail("remove_skill(skill): unknown skill '${l.v}'")
      world.removeSkill(a, l)
    }

  @StarlarkMethod(
    name = "skill_info",
    doc = "The skill a label names, as ctx.skill in a cast, or None when no loaded skill has it.",
    parameters = [Param(name = "label", named = true, doc = "Skill label, or the skill's view.")],
    useStarlarkThread = true,
  )
  fun skillInfo(label: Any, thread: StarlarkThread): Any =
    verb("skill_info") {
      val l = skillArg(label, "skill_info", thread)
      world.protos.skills[l]?.let { SkillView(it, world.schema) } ?: Starlark.NONE
    }

  @StarlarkMethod(
    name = "start_combat",
    doc = "Engages two actors in the same room in combat.",
    parameters =
      [
        Param(name = "a", named = true, doc = "First actor."),
        Param(name = "b", named = true, doc = "Second actor, in the same room."),
      ],
  )
  fun startCombat(a: Any, b: Any): Any =
    verb("start_combat") {
      val x = actorArg(a, "start_combat(a)")
      val y = actorArg(b, "start_combat(b)")
      if (x === y) fail("start_combat(): an actor can't fight itself")
      if (x.room !== y.room)
        fail("start_combat(): ${x.name} and ${y.name} are not in the same room")
      for (d in listOf(x, y)) if (world.isDying(d)) fail("start_combat(): ${d.name} is dying")
      world.startCombat(x, y)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "stop_combat",
    doc = "Ends combat between a and b, or all of a's fights when b is None.",
    parameters =
      [
        Param(name = "a", named = true, doc = "Actor whose fighting stops."),
        Param(
          name = "b",
          defaultValue = "None",
          named = true,
          doc = "Opponent, or None for all of a's fights.",
        ),
      ],
  )
  fun stopCombat(a: Any, b: Any): Any =
    verb("stop_combat") {
      world.stopCombat(
        actorArg(a, "stop_combat(a)"),
        if (isNone(b)) null else actorArg(b, "stop_combat(b)"),
      )
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "clear_contributions",
    doc = "Empties victim's contribution record, or removes one contributor's entry.",
    parameters =
      [
        Param(name = "victim", named = true, doc = "Actor whose record is cleared."),
        Param(
          name = "contributor",
          defaultValue = "None",
          named = true,
          doc = "Contributor to remove, or None for the whole record.",
        ),
      ],
  )
  fun clearContributions(victim: Any, contributor: Any): Any =
    verb("clear_contributions") {
      val v = actorArg(victim, "clear_contributions(victim)")
      world.clearContributions(
        v,
        if (isNone(contributor)) null else actorArg(contributor, "clear_contributions(contributor)"),
      )
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "disposition",
    doc =
      "What holder thinks of subject, -100 to 100: the default sum of its parts or the world's disposition export, then plugins.",
    parameters =
      [
        Param(name = "holder", named = true, doc = "The actor holding the opinion."),
        Param(name = "subject", named = true, doc = "The actor the opinion is about."),
      ],
  )
  fun disposition(holder: Any, subject: Any): Any =
    verb("disposition") {
      sInt(
        world.disposition(
          actorArg(holder, "disposition(holder)"),
          actorArg(subject, "disposition(subject)"),
        )
      )
    }

  @StarlarkMethod(
    name = "standing",
    doc =
      "The amount of holder's standing record about subject, 0 when there is none; declared faction opinions are not records.",
    parameters =
      [
        Param(name = "holder", named = true, doc = "Mob view or faction label holding the record."),
        Param(
          name = "subject",
          named = true,
          doc = "Actor view or faction label the record is about.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun standing(holder: Any, subject: Any, thread: StarlarkThread): Any =
    verb("standing") {
      val pkg = pkgOf(thread)
      val h =
        toStandingHolder(holder, pkg, "standing", world.protos) { actorArg(it, "standing(holder)") }
      val sub =
        toStandingSubject(subject, pkg, "standing", world.protos, h) {
          actorArg(it, "standing(subject)")
        }
      sInt(world.standing(h, sub))
    }

  @StarlarkMethod(
    name = "factions_of",
    doc = "The faction labels actor belongs to, as a list of strings.",
    parameters = [Param(name = "actor", named = true, doc = "The actor.")],
  )
  fun factionsOf(actor: Any): Any =
    verb("factions_of") {
      sList(world.factionsOf(actorArg(actor, "factions_of(actor)")).map { it.v })
    }

  @StarlarkMethod(
    name = "standing_history",
    doc = "Queries standing history for holder and subject since a pulse.",
    parameters =
      [
        Param(
          name = "holder",
          named = true,
          doc = "Mob view or faction label holding the opinion.",
        ),
        Param(
          name = "subject",
          named = true,
          doc = "Actor view or faction label the opinion is about.",
        ),
        Param(name = "since", defaultValue = "0", named = true, doc = "Start pulse for the query."),
        Param(
          name = "via",
          defaultValue = "None",
          named = true,
          doc = "Optional mob view filtering the event source.",
        ),
        Param(
          name = "event",
          defaultValue = "None",
          named = true,
          doc = "Optional event name filter.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun standingHistory(
    holder: Any,
    subject: Any,
    since: Any,
    via: Any,
    event: Any,
    thread: StarlarkThread,
  ): Any =
    verb("standing_history") {
      val pkg = pkgOf(thread)
      val holderObj =
        toStandingHolder(holder, pkg, "standing_history", world.protos) {
          actorArg(it, "standing_history(holder)")
        }
      val subjectObj =
        toStandingSubject(subject, pkg, "standing_history", world.protos, holderObj) {
          actorArg(it, "standing_history(subject)")
        }
      val sincePulse = int(since, "standing_history(since)").toLong().coerceAtLeast(0L)
      val viaId =
        if (isNone(via)) {
          null
        } else {
          mobArg(via, "standing_history(via)").id
        }
      val eventName = if (isNone(event)) null else str(event, "standing_history(event)")

      val totals = world.standingHistory(holderObj, subjectObj, sincePulse, viaId, eventName)
      StructValue(linkedMapOf("gained" to sInt(totals.gained), "lost" to sInt(totals.lost)))
    }

  @StarlarkMethod(
    name = "standing_event",
    doc = "Raises a custom standing event on affected by actor.",
    parameters =
      [
        Param(name = "affected", named = true, doc = "The actor affected."),
        Param(name = "actor", named = true, doc = "The actor who acted."),
        Param(name = "event", named = true, doc = "The event name."),
        Param(name = "amount", defaultValue = "0", named = true, doc = "Optional positive amount."),
        Param(name = "item", defaultValue = "None", named = true, doc = "Optional item view."),
      ],
  )
  fun standingEvent(affected: Any, actor: Any, event: Any, amount: Any, item: Any): Any =
    verb("standing_event") {
      val aff = actorArg(affected, "standing_event(affected)")
      val act = actorArg(actor, "standing_event(actor)")
      val eventName = str(event, "standing_event(event)")
      if (eventName in BUILTIN_STANDING_EVENTS) {
        fail("standing_event(): '$eventName' is a built-in event")
      }
      if (!STANDING_EVENT_NAME.matches(eventName)) {
        fail("standing_event(): event name must match [a-z][a-z0-9_]*, got '$eventName'")
      }
      val amt = int(amount, "standing_event(amount)")
      if (amt < 0) {
        fail("standing_event(amount): must be non-negative, got $amt")
      }
      val itm = if (isNone(item)) null else itemArg(item, "standing_event(item)")

      world.raiseStandingEvent(
        StandingEvent(
          event = eventName,
          affected = aff,
          actor = act,
          amount = amt.toLong(),
          item = itm,
        )
      )
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "adjust_standing",
    doc = "Adjusts standing directly for holder and subject by amount.",
    parameters =
      [
        Param(
          name = "holder",
          named = true,
          doc = "Mob view or faction label holding the opinion.",
        ),
        Param(
          name = "subject",
          named = true,
          doc = "Actor view or faction label the opinion is about.",
        ),
        Param(name = "amount", named = true, doc = "Integer amount to adjust."),
      ],
    useStarlarkThread = true,
  )
  fun adjustStanding(holder: Any, subject: Any, amount: Any, thread: StarlarkThread): Any =
    verb("adjust_standing") {
      val pkg = pkgOf(thread)
      val holderObj =
        toStandingHolder(holder, pkg, "adjust_standing", world.protos) {
          actorArg(it, "adjust_standing(holder)")
        }
      val subjectObj =
        toStandingSubject(subject, pkg, "adjust_standing", world.protos, holderObj) {
          actorArg(it, "adjust_standing(subject)")
        }
      val amt = int(amount, "adjust_standing(amount)")
      world.applyStanding(holderObj, subjectObj, amt, event = "adjust", via = null)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "clear_standing",
    doc = "Clears standing records for holder, optionally restricted to subject.",
    parameters =
      [
        Param(
          name = "holder",
          named = true,
          doc = "Mob view or faction label holding the opinion.",
        ),
        Param(
          name = "subject",
          defaultValue = "None",
          named = true,
          doc = "Actor view or faction label, or None to clear all records for holder.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun clearStanding(holder: Any, subject: Any, thread: StarlarkThread): Any =
    verb("clear_standing") {
      val pkg = pkgOf(thread)
      val holderObj =
        toStandingHolder(holder, pkg, "clear_standing", world.protos) {
          actorArg(it, "clear_standing(holder)")
        }
      val subjectObj =
        if (isNone(subject)) null
        else
          toStandingSubject(subject, pkg, "clear_standing", world.protos, holderObj) {
            actorArg(it, "clear_standing(subject)")
          }
      world.clearStanding(holderObj, subjectObj)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "group_add",
    doc = "Adds a player to another player's group, creating one if the other has none.",
    parameters =
      [
        Param(name = "member", named = true, doc = "Player to add."),
        Param(name = "to", named = true, doc = "Player whose group the member joins."),
      ],
  )
  fun groupAdd(member: Any, to: Any): Any =
    verb("group_add") {
      world.groupAdd(playerArg(member, "group_add(member)"), playerArg(to, "group_add(to)"))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "group_remove",
    doc = "Removes a player from its group; a group left with one member is disbanded.",
    parameters = [Param(name = "member", named = true, doc = "Player to remove.")],
  )
  fun groupRemove(member: Any): Any =
    verb("group_remove") {
      world.groupRemove(playerArg(member, "group_remove(member)"))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "group_set_leader",
    doc = "Makes a member the leader of its group.",
    parameters = [Param(name = "member", named = true, doc = "Player to make the leader.")],
  )
  fun groupSetLeader(member: Any): Any =
    verb("group_set_leader") {
      world.groupSetLeader(playerArg(member, "group_set_leader(member)"))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "group_disband",
    doc = "Dissolves a player's whole group at once.",
    parameters = [Param(name = "member", named = true, doc = "Player whose group is dissolved.")],
  )
  fun groupDisband(member: Any): Any =
    verb("group_disband") {
      world.groupDisband(playerArg(member, "group_disband(member)"))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "group_of",
    doc = "The group an actor is in, or None; a pet resolves through its owner.",
    parameters = [Param(name = "actor", named = true, doc = "Actor whose group is wanted.")],
  )
  fun groupOf(actor: Any): Any =
    verb("group_of") {
      val a = actorArg(actor, "group_of(actor)")
      val g = world.groupOf(a) ?: return@verb Starlark.NONE
      GroupView(frame, GroupSnapshot(g.id, g.leader, world.groupMembers(a)))
    }

  @StarlarkMethod(
    name = "group_members",
    doc = "The members of an actor's group, leader first; [] when it has none.",
    parameters =
      [Param(name = "actor", named = true, doc = "Actor whose group members are wanted.")],
  )
  fun groupMembers(actor: Any): Any =
    verb("group_members") {
      val a = actorArg(actor, "group_members(actor)")
      sList(world.groupMembers(a).map { view(frame, it) })
    }

  @StarlarkMethod(
    name = "apply_affect",
    doc =
      "Attaches a buff or debuff to an actor, an item or a room; re-applying a name replaces it.",
    parameters =
      [
        Param(name = "target", named = true, doc = "Actor, item or room to receive the affect."),
        Param(
          name = "name",
          named = true,
          doc = "Affect name; applying the same name again replaces the existing affect.",
        ),
        Param(
          name = "duration",
          named = true,
          doc = "Duration in pulses; None makes an item affect permanent.",
        ),
        Param(
          name = "stat_deltas",
          named = true,
          defaultValue = "None",
          doc = "Stat adjustments while the affect lasts, built with stats(...).",
        ),
        Param(
          name = "flags",
          named = true,
          defaultValue = "[]",
          doc = "Flags the affect grants, such as 'no_regen'.",
        ),
        Param(
          name = "tick_every",
          named = true,
          defaultValue = "None",
          doc = "Pulses between on_tick calls, or None for no ticking.",
        ),
        Param(
          name = "on_tick",
          named = true,
          defaultValue = "None",
          doc = "Top-level function fn(ctx) called every tick_every pulses.",
        ),
        Param(
          name = "on_expire",
          named = true,
          defaultValue = "None",
          doc = "Top-level function fn(ctx) called when the affect expires or is removed.",
        ),
        Param(
          name = "expire_msg",
          named = true,
          defaultValue = "''",
          doc = "Text sent to the target when the affect ends.",
        ),
        Param(
          name = "modifiers",
          named = true,
          defaultValue = "{}",
          doc = "Rules modifiers active while the affect lasts, by modifier point name.",
        ),
        Param(
          name = "sense_deltas",
          named = true,
          defaultValue = "{}",
          doc = "Sense score changes by channel while the affect lasts.",
        ),
        Param(
          name = "max_vital_deltas",
          named = true,
          defaultValue = "None",
          doc = "Vital maximum adjustments while the affect lasts, built with vitals(...).",
        ),
        Param(
          name = "resist_deltas",
          named = true,
          defaultValue = "None",
          doc = "Damage resistance adjustments while the affect lasts, built with resists(...).",
        ),
        Param(
          name = "applies",
          named = true,
          defaultValue = "None",
          doc = "Item affects only: 'worn' or 'carried'.",
        ),
        Param(
          name = "procs",
          named = true,
          defaultValue = "[]",
          doc = "Combat procs that fire during auto-attack swings.",
        ),
        Param(
          name = "add_tags",
          named = true,
          defaultValue = "[]",
          doc = "Tags a room gains while the affect lasts; a room affect only.",
        ),
        Param(
          name = "remove_tags",
          named = true,
          defaultValue = "[]",
          doc = "Tags a room loses while the affect lasts; a room affect only.",
        ),
      ],
  )
  fun applyAffect(
    target: Any,
    name: Any,
    duration: Any,
    statDeltas: Any,
    flags: Any,
    tickEvery: Any,
    onTick: Any,
    onExpire: Any,
    expireMsg: Any,
    modifiers: Any,
    senseDeltas: Any,
    maxVitalDeltas: Any,
    resistDeltas: Any,
    applies: Any,
    procs: Any,
    addTags: Any,
    removeTags: Any,
  ): Any =
    verb("apply_affect") {
      val e =
        affectEffect(
          name,
          duration,
          statDeltas,
          flags,
          tickEvery,
          onTick,
          onExpire,
          expireMsg,
          modifiers,
          senseDeltas,
          maxVitalDeltas,
          resistDeltas,
          "apply_affect",
          world.schema,
          world.features,
          allowPermanent = true,
          procs = procs,
          procsSymbol = "ctx.apply_affect.procs",
          addTags = addTags,
          removeTags = removeTags,
        )
      if (target is RoomView) {
        val room = target.room
        val d = e.duration ?: fail("apply_affect(duration): a room's affect needs a duration")
        if (e.statDeltas.isNotEmpty())
          fail("apply_affect(stat_deltas): a room's affect can't change stats")
        if (e.senseDeltas.isNotEmpty())
          fail("apply_affect(sense_deltas): a room's affect can't change senses")
        if (e.maxVitalDeltas.isNotEmpty())
          fail("apply_affect(max_vital_deltas): a room's affect can't change maximum vitals")
        if (e.resistDeltas.isNotEmpty())
          fail("apply_affect(resist_deltas): a room's affect can't change resists")
        if (e.procs.isNotEmpty()) fail("apply_affect(procs): a room's affect can't have procs")
        if (!isNone(applies)) fail("apply_affect(applies): only item affects take applies")
        val a =
          world.applyRoomAffect(
            room,
            e.name,
            d,
            e.flags,
            e.tickEvery,
            e.onTick,
            e.onExpire,
            e.expireMessage,
            owner as? Actor,
            e.modifiers,
            e.addTags,
            e.removeTags,
          )
        a?.let { AffectView(frame, room, it) } ?: Starlark.NONE
      } else if (target is ItemView) {
        val item = itemArg(target, "apply_affect(target)")
        if (e.addTags.isNotEmpty())
          fail("apply_affect(add_tags): only a room's affect can add tags")
        if (e.removeTags.isNotEmpty())
          fail("apply_affect(remove_tags): only a room's affect can remove tags")
        val scope =
          if (isNone(applies)) AffectScope.WORN
          else {
            val s =
              choice(
                str(applies, "apply_affect(applies)"),
                "apply_affect(applies)",
                listOf("worn", "carried"),
              )
            AffectScope.valueOf(s.uppercase())
          }
        val a =
          world.applyItemAffect(
            item,
            e.name,
            e.duration,
            scope,
            e.statDeltas,
            e.flags,
            e.tickEvery,
            e.onTick,
            e.onExpire,
            e.expireMessage,
            owner as? Actor,
            e.modifiers,
            e.senseDeltas,
            maxVitalDeltas = e.maxVitalDeltas,
            resistDeltas = e.resistDeltas,
            procs = e.procs,
          )
        a?.let { AffectView(frame, item, it) } ?: Starlark.NONE
      } else {
        val t = actorArg(target, "apply_affect(target)")
        if (e.addTags.isNotEmpty())
          fail("apply_affect(add_tags): only a room's affect can add tags")
        if (e.removeTags.isNotEmpty())
          fail("apply_affect(remove_tags): only a room's affect can remove tags")
        if (!isNone(applies)) fail("apply_affect(applies): only item affects take applies")
        val d = e.duration ?: fail("apply_affect(duration): an actor's affect needs a duration")
        val a =
          world.applyAffect(
            t,
            e.name,
            d,
            e.statDeltas,
            e.flags,
            e.tickEvery,
            e.onTick,
            e.onExpire,
            e.expireMessage,
            owner as? Actor,
            e.modifiers,
            senseDeltas = e.senseDeltas,
            maxVitalDeltas = e.maxVitalDeltas,
            resistDeltas = e.resistDeltas,
            procs = e.procs,
          )
        a?.let { AffectView(frame, t, it) } ?: Starlark.NONE
      }
    }

  @StarlarkMethod(
    name = "remove_affect",
    doc = "Removes an affect by name; True if there was one.",
    parameters =
      [
        Param(name = "target", named = true, doc = "Actor, item or room carrying the affect."),
        Param(name = "name", named = true, doc = "Affect name."),
      ],
  )
  fun removeAffect(target: Any, name: Any): Any =
    verb("remove_affect") {
      if (target is RoomView) {
        world.removeRoomAffect(target.room, str(name, "remove_affect(name)"))
      } else if (target is ItemView) {
        world.removeItemAffect(
          itemArg(target, "remove_affect(target)"),
          str(name, "remove_affect(name)"),
        )
      } else {
        world.removeAffect(
          actorArg(target, "remove_affect(target)"),
          str(name, "remove_affect(name)"),
        )
      }
    }

  @StarlarkMethod(
    name = "summon",
    doc =
      "Spawns count mobs of prototype label beside caster as its summons. Returns the mobs as a list.",
    parameters =
      [
        Param(name = "caster", named = true, doc = "Actor the mobs are summoned beside."),
        Param(name = "label", named = true, doc = "Mob prototype label."),
        Param(
          name = "count",
          named = true,
          defaultValue = "1",
          doc = "Number of mobs to summon; at least 1.",
        ),
        Param(
          name = "duration",
          named = true,
          defaultValue = "None",
          doc = "Pulses until the summoned affect expires and the mob fades away, or None.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun summon(caster: Any, label: Any, count: Any, duration: Any, thread: StarlarkThread): Any =
    verb("summon", "$label") {
      val c = actorArg(caster, "summon(caster)")
      val l = label(str(label, "summon(label)"), pkgOf(thread), "summon(label)")
      if (l !in world.protos.mobs) fail("summon(label): there is no mob $l")
      val n = int(count, "summon(count)")
      if (n < 1) fail("summon(count): must be at least 1")
      val d = if (duration == Starlark.NONE) null else int(duration, "summon(duration)")
      if (d != null && d < 1) fail("summon(duration): must be at least 1")
      val mobs = world.summon(c, l, n, d)
      sList(mobs.map { v(it) })
    }

  // ---- Items ---------------------------------------------------------------------------------

  @StarlarkMethod(
    name = "spawn",
    doc =
      "Spawns a mob (in a room) or an item (in a room, on an actor, or in a container). Returns its view, or a list when count > 1 makes several.",
    parameters =
      [
        Param(name = "label", named = true, doc = "Mob or item prototype label."),
        Param(name = "at", named = true, doc = "Room or room label, actor, or container item."),
        Param(
          name = "index",
          named = true,
          defaultValue = "0",
          doc = "Track index when spawning into a room.",
        ),
        Param(
          name = "count",
          named = true,
          defaultValue = "1",
          doc = "Number to spawn; more than 1 returns a list.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun spawn(label: Any, at: Any, index: Any, count: Any, thread: StarlarkThread): Any =
    verb("spawn", "$label") {
      trackIndex("spawn", index)
      val l = label(str(label, "spawn(label)"), pkgOf(thread), "spawn(label)")
      val n = int(count, "spawn(count)")
      if (n < 1) fail("spawn(count): must be at least 1")
      val made =
        when {
          l in world.protos.mobs -> {
            val room = roomArg(at, "spawn(at)", thread)
            val i = int(index, "spawn(index)")
            if (i !in 0 until room.trackSize) fail("spawn(index): $i is outside ${room.label}")
            List(n) { world.spawnMob(l, room, i) }
          }
          l in world.protos.items -> {
            val loc = location(at, index, "spawn(at)", thread)
            if (world.protos.item(l).stackable) listOf(world.spawnItem(l, loc, n))
            else List(n) { world.spawnItem(l, loc) }
          }
          else -> fail("spawn(label): there is no mob or item $l")
        }
      if (made.size == 1) v(made[0]) else sList(made.map { v(it) })
    }

  @StarlarkMethod(
    name = "give",
    doc = "Puts an item (a view, or a label to spawn) into an actor's inventory.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor who receives the item."),
        Param(
          name = "label_or_item",
          named = true,
          doc = "Item view to move, or item prototype label to spawn.",
        ),
        Param(
          name = "count",
          named = true,
          defaultValue = "None",
          doc =
            "Number of items to spawn or move; defaults to 1 for a label, or the whole stack for an item.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun give(actor: Any, labelOrItem: Any, count: Any, thread: StarlarkThread): Any =
    verb("give") {
      val a = actorArg(actor, "give(actor)")
      val to = ItemLocation.Carried(a.id)
      if (labelOrItem is ItemView) {
        val i = itemArg(labelOrItem, "give(item)")
        val n = intOrNull(count, "give(count)") ?: i.count
        if (n !in 1..i.count) fail("give(count): must be between 1 and ${i.count}")
        return@verb v(world.moveItem(i, to, n))
      }
      val l = label(str(labelOrItem, "give(label_or_item)"), pkgOf(thread), "give(label)")
      if (l !in world.protos.items) fail("give(label): there is no item $l")
      val n = intOrNull(count, "give(count)") ?: 1
      if (n < 1) fail("give(count): must be at least 1")
      val made =
        if (world.protos.item(l).stackable) listOf(world.spawnItem(l, to, n))
        else List(n) { world.spawnItem(l, to) }
      if (made.size == 1) v(made[0]) else sList(made.map { v(it) })
    }

  @StarlarkMethod(
    name = "move_item",
    doc =
      "Moves an item, or count of a stack, to a room (at index), an actor's inventory, or a container. Returns the moved item.",
    parameters =
      [
        Param(name = "item", named = true, doc = "Item to move."),
        Param(name = "to", named = true, doc = "Room or room label, actor, or container item."),
        Param(
          name = "index",
          named = true,
          defaultValue = "0",
          doc = "Track index when moving to a room.",
        ),
        Param(
          name = "count",
          named = true,
          defaultValue = "None",
          doc = "Number of items of the stack to move, or None for the whole stack.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun moveItem(item: Any, to: Any, index: Any, count: Any, thread: StarlarkThread): Any =
    verb("move_item") {
      trackIndex("move_item", index)
      val i = itemArg(item, "move_item(item)")
      val n = intOrNull(count, "move_item(count)") ?: i.count
      if (n !in 1..i.count) fail("move_item(count): must be between 1 and ${i.count}")
      v(world.moveItem(i, location(to, index, "move_item(to)", thread), n))
    }

  @StarlarkMethod(
    name = "destroy",
    doc = "Removes an item and everything inside it, or count of a stack.",
    parameters =
      [
        Param(name = "item", named = true, doc = "Item to remove from the world."),
        Param(
          name = "count",
          named = true,
          defaultValue = "None",
          doc = "Number of items of the stack to remove, or None for the whole stack.",
        ),
      ],
  )
  fun destroy(item: Any, count: Any): Any =
    verb("destroy") {
      val i = itemArg(item, "destroy(item)")
      val n = intOrNull(count, "destroy(count)") ?: i.count
      if (n !in 1..i.count) fail("destroy(count): must be between 1 and ${i.count}")
      world.destroy(i, n)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "merge_stack",
    doc =
      "Merges an item into an identical stack in the same place; returns the stack that holds it.",
    parameters = [Param(name = "item", named = true, doc = "Item to merge.")],
  )
  fun mergeStack(item: Any): Any =
    verb("merge_stack") { v(world.stack(itemArg(item, "merge_stack(item)"))) }

  @StarlarkMethod(
    name = "set_charges",
    doc = "Sets an item's remaining uses, from 0 to its prototype's charges.",
    parameters =
      [
        Param(name = "item", named = true, doc = "Item with charges."),
        Param(name = "charges", named = true, doc = "Uses left; 0 doesn't run the empty action."),
      ],
  )
  fun setCharges(item: Any, charges: Any): Any =
    verb("set_charges") {
      val i = itemArg(item, "set_charges(item)")
      val n = int(charges, "set_charges(charges)")
      val max = i.proto.charges ?: fail("set_charges: ${i.name} has unlimited uses")
      if (i.proto.stackable) fail("set_charges: ${i.name} is stackable")
      if (n !in 0..max) fail("set_charges: charges must be between 0 and $max, got $n")
      world.setCharges(i, n)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "add_tag",
    doc = "Adds a tag to an item's effective tags; True if it was not already present.",
    parameters =
      [
        Param(name = "item", named = true, doc = "Item to tag."),
        Param(name = "tag", named = true, doc = "Tag to add."),
      ],
  )
  fun addTag(item: Any, tag: Any): Any =
    verb("add_tag") {
      val i = itemArg(item, "add_tag(item)")
      val t = str(tag, "add_tag(tag)")
      world.addItemTag(i, t)
    }

  @StarlarkMethod(
    name = "remove_tag",
    doc = "Removes a tag from an item's effective tags; True if it was present.",
    parameters =
      [
        Param(name = "item", named = true, doc = "Item to untag."),
        Param(name = "tag", named = true, doc = "Tag to remove."),
      ],
  )
  fun removeTag(item: Any, tag: Any): Any =
    verb("remove_tag") {
      val i = itemArg(item, "remove_tag(item)")
      val t = str(tag, "remove_tag(tag)")
      world.removeItemTag(i, t)
    }

  @StarlarkMethod(
    name = "transform",
    doc = "Swaps an item's prototype in place, keeping instance attributes, affects and tags.",
    parameters =
      [
        Param(name = "item", named = true, doc = "Item to transform."),
        Param(name = "label", named = true, doc = "Target item prototype label."),
      ],
    useStarlarkThread = true,
  )
  fun transform(item: Any, label: Any, thread: StarlarkThread): Any =
    verb("transform") {
      val i = itemArg(item, "transform(item)")
      val l = label(str(label, "transform(label)"), pkgOf(thread), "transform(label)")
      if (l !in world.protos.items) fail("transform(label): there is no item $l")
      world.transformItem(i, l)
      Starlark.NONE
    }

  // ---- Doors and exits -----------------------------------------------------------------------

  private fun door(
    room: Any,
    dir: Any,
    thread: StarlarkThread,
    name: String,
    to: (DoorState) -> DoorState,
  ): Any =
    verb(name) {
      val r = roomArg(room, "$name(room)", thread)
      val d = dir(dir, "$name(dir)")
      val door = r.exits[d]?.door ?: fail("$name(): ${r.label} has no door $d")
      world.setDoor(r, d, to(door.state))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "open_door",
    doc = "Opens the door in direction dir, even if locked.",
    parameters =
      [
        Param(name = "room", named = true, doc = "Room, or room label, that has the exit."),
        Param(name = "dir", named = true, doc = "Direction of the exit."),
      ],
    useStarlarkThread = true,
  )
  fun openDoor(room: Any, dir: Any, thread: StarlarkThread): Any =
    door(room, dir, thread, "open_door") { DoorState.OPEN }

  @StarlarkMethod(
    name = "close_door",
    doc = "Closes an open door in direction dir.",
    parameters =
      [
        Param(name = "room", named = true, doc = "Room, or room label, that has the exit."),
        Param(name = "dir", named = true, doc = "Direction of the exit."),
      ],
    useStarlarkThread = true,
  )
  fun closeDoor(room: Any, dir: Any, thread: StarlarkThread): Any =
    door(room, dir, thread, "close_door") { if (it == DoorState.LOCKED) it else DoorState.CLOSED }

  @StarlarkMethod(
    name = "lock_door",
    doc = "Closes and locks the door.",
    parameters =
      [
        Param(name = "room", named = true, doc = "Room, or room label, that has the exit."),
        Param(name = "dir", named = true, doc = "Direction of the exit."),
      ],
    useStarlarkThread = true,
  )
  fun lockDoor(room: Any, dir: Any, thread: StarlarkThread): Any =
    door(room, dir, thread, "lock_door") { DoorState.LOCKED }

  @StarlarkMethod(
    name = "unlock_door",
    doc = "Unlocks the door, leaving it closed.",
    parameters =
      [
        Param(name = "room", named = true, doc = "Room, or room label, that has the exit."),
        Param(name = "dir", named = true, doc = "Direction of the exit."),
      ],
    useStarlarkThread = true,
  )
  fun unlockDoor(room: Any, dir: Any, thread: StarlarkThread): Any =
    door(room, dir, thread, "unlock_door") { if (it == DoorState.LOCKED) DoorState.CLOSED else it }

  // ---- Containers ----------------------------------------------------------------------------

  private fun container(item: Any, name: String, to: (DoorState) -> DoorState): Any =
    verb(name) {
      val i = itemArg(item, "$name(item)")
      val box = i.container ?: fail("$name(item): ${i.name} is not a container")
      world.setContainerState(i, to(box.state))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "open_container",
    doc = "Opens the container, even if locked.",
    parameters = [Param(name = "item", named = true, doc = "Container item to open.")],
  )
  fun openContainer(item: Any): Any = container(item, "open_container") { DoorState.OPEN }

  @StarlarkMethod(
    name = "close_container",
    doc = "Closes an open container; a locked one stays locked.",
    parameters = [Param(name = "item", named = true, doc = "Container item to close.")],
  )
  fun closeContainer(item: Any): Any =
    container(item, "close_container") { if (it == DoorState.LOCKED) it else DoorState.CLOSED }

  @StarlarkMethod(
    name = "lock_container",
    doc =
      "Closes and locks the container. Only a container whose prototype names a key has a lock.",
    parameters = [Param(name = "item", named = true, doc = "Container item to lock.")],
  )
  fun lockContainer(item: Any): Any = container(item, "lock_container") { DoorState.LOCKED }

  @StarlarkMethod(
    name = "unlock_container",
    doc = "Unlocks the container, leaving it closed.",
    parameters = [Param(name = "item", named = true, doc = "Container item to unlock.")],
  )
  fun unlockContainer(item: Any): Any =
    container(item, "unlock_container") { if (it == DoorState.LOCKED) DoorState.CLOSED else it }

  // ---- State ---------------------------------------------------------------------------------

  /** The stored key for [key] as written by code in the caller's package. */
  private fun attrKey(key: Any, write: Boolean, what: String, thread: StarlarkThread): String {
    val k = str(key, what)
    val caller = pkgOf(thread)
    val pkg = frame.host.scripts.packages[caller]
    val own = pkg?.namespace ?: caller.substringAfterLast('/')
    val (ns, short) = if (':' in k) k.substringBefore(':') to k.substringAfter(':') else own to k
    if (!Regex("[A-Za-z0-9_]+").matches(short) || !Regex("[a-z0-9_]+").matches(ns))
      fail("$what: '$k' is not a valid attribute key")
    if (write && ns != own && ns !in pkg?.sharedNamespaces.orEmpty()) {
      val declarer = frame.host.scripts.packages.values.firstOrNull { ns in it.sharedNamespaces }
      fail(
        "$what: code in //$caller can only write its own namespace '$own', not '$ns'" +
          (declarer?.let { " (use the helpers exported by //${it.path})" } ?: "")
      )
    }
    return "$ns:$short"
  }

  @StarlarkMethod(
    name = "set_attr",
    doc = "Stores a value (int, str, bool, list or dict) on an entity. None deletes the key.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(
          name = "key",
          named = true,
          doc = "Attribute key; unprefixed keys are in your package's namespace.",
        ),
        Param(
          name = "value",
          named = true,
          doc = "Value to store (int, str, bool, list or dict), or None to delete the key.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun setAttr(entity: Any, key: Any, value: Any, thread: StarlarkThread): Any =
    verb("set_attr", "$key") {
      val e = entityArg(entity, "set_attr(entity)")
      val k = attrKey(key, write = true, "set_attr(key)", thread)
      val stored = toAttr(value, "set_attr(value)", frame.host.limits)
      if (stored == null) {
        e.customAttributes.remove(k)
      } else {
        val next = LinkedHashMap<String, AttrValue>(e.customAttributes).also { it[k] = stored }
        val size = jsonSize(next)
        if (size > frame.host.limits.maxAttrBytes) {
          limitFail(
            "alloc_limit",
            "set_attr(): ${e.name}'s attributes would be $size bytes, over the ${frame.host.limits.maxAttrBytes / 1024} KB limit",
          )
        }
        e.customAttributes[k] = stored
      }
      e.dirty = true
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "get_attr",
    doc = "Reads a value; your own keys by short name, other packages' by 'namespace:key'.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(
          name = "key",
          named = true,
          doc = "Attribute key; your own by short name, another package's as 'namespace:key'.",
        ),
        Param(
          name = "default",
          defaultValue = "None",
          named = true,
          doc = "Value returned when the key is absent.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun getAttr(entity: Any, key: Any, default: Any, thread: StarlarkThread): Any =
    verb("get_attr", "$key") {
      val e = entityArg(entity, "get_attr(entity)")
      e.customAttributes[attrKey(key, write = false, "get_attr(key)", thread)]?.let {
        fromAttr(it, thread.mutability())
      } ?: default
    }

  @StarlarkMethod(
    name = "del_attr",
    doc = "Deletes an attribute key from an entity.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(
          name = "key",
          named = true,
          doc = "Attribute key; unprefixed keys are in your package's namespace.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun delAttr(entity: Any, key: Any, thread: StarlarkThread): Any =
    verb("del_attr", "$key") {
      val e = entityArg(entity, "del_attr(entity)")
      if (e.customAttributes.remove(attrKey(key, write = true, "del_attr(key)", thread)) != null)
        e.dirty = true
      Starlark.NONE
    }

  // ---- Timers --------------------------------------------------------------------------------

  @StarlarkMethod(
    name = "after",
    doc =
      "Calls fn(ctx, **args) on this hook's owner after some pulses. fn must be a top-level function; args must be storable.",
    parameters =
      [
        Param(name = "pulses", named = true, doc = "Pulses to wait, at least 1."),
        Param(name = "fn", named = true, doc = "Top-level function fn(ctx, **args) to call."),
      ],
    extraKeywords =
      Param(
        name = "args",
        doc =
          "Keyword arguments passed to fn; values must be storable (int, str, bool, list or dict).",
      ),
  )
  fun after(pulses: Any, fn: Any, args: Dict<String, Any>): Any =
    verb("after") {
      val n = int(pulses, "after(pulses)")
      if (n < 1) fail("after(pulses): must be at least 1")
      val ref = hookRef(fn, "after(fn)")
      val stored =
        args.entries.associate { (k, x) ->
          k to
            (toAttr(x, "after($k)", frame.host.limits)
              ?: fail("after($k): None can't be passed to a timer"))
        }
      sInt(world.after(owner, n, ref, stored))
    }

  @StarlarkMethod(
    name = "cancel",
    doc = "Cancels one of this owner's timers; True if it was pending.",
    parameters = [Param(name = "timer_id", named = true, doc = "Timer id returned by ctx.after.")],
  )
  fun cancel(timerId: Any): Any =
    verb("cancel") { world.cancel(owner, int(timerId, "cancel(timer_id)").toLong()) }

  // ---- Simulation ----------------------------------------------------------------------------

  private fun reason(
    entity: Any,
    reason: Any,
    name: String,
    change: (Entity, String) -> Boolean,
  ): Any =
    verb(name) {
      val e = entityArg(entity, "$name(entity)")
      val r = str(reason, "$name(reason)")
      if (r.isEmpty()) fail("$name(reason): must not be empty")
      change(e, r)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "set_active",
    doc =
      "Keeps an entity simulated (its queue, timers, AI and movement) even outside the bubble, until its last reason is cleared.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(
          name = "reason",
          named = true,
          doc = "Name for this reason; clear_active with the same name removes it.",
        ),
      ],
  )
  fun setActive(entity: Any, reason: Any): Any =
    reason(entity, reason, "set_active") { e, r -> world.setActive(e, r) }

  @StarlarkMethod(
    name = "clear_active",
    doc = "Removes a set_active reason from an entity.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(name = "reason", named = true, doc = "The reason given to set_active."),
      ],
  )
  fun clearActive(entity: Any, reason: Any): Any =
    reason(entity, reason, "clear_active") { e, r -> world.clearActive(e, r) }

  @StarlarkMethod(
    name = "set_owner",
    doc =
      "Makes a player the owner of a mob, replacing any owner it had. The mob then runs its owned behaviors, leaves its spawn rule and stops fighting its owner's side. Returns whether anything changed.",
    parameters =
      [
        Param(name = "mob", named = true, doc = "The mob that becomes a pet."),
        Param(name = "player", named = true, doc = "The in-world player that owns it."),
      ],
  )
  fun setOwner(mob: Any, player: Any): Any =
    verb("set_owner") {
      val m = (mob as? ActorView)?.actor as? Mob ?: fail("set_owner(mob): must be a mob")
      val p =
        (player as? ActorView)?.actor?.takeIf { it is Player && it.inWorld } as? Player
          ?: fail("set_owner(player): must be a player in the world")
      world.setOwner(m, p)
    }

  @StarlarkMethod(
    name = "clear_owner",
    doc = "Removes a mob's owner. Returns whether it had one.",
    parameters = [Param(name = "mob", named = true, doc = "The mob to release.")],
  )
  fun clearOwner(mob: Any): Any =
    verb("clear_owner") {
      val m = (mob as? ActorView)?.actor as? Mob ?: fail("clear_owner(mob): must be a mob")
      world.clearOwner(m)
    }

  @StarlarkMethod(
    name = "set_anchor",
    doc =
      "Makes an entity project a bubble of its own, as players do, until its last reason is cleared.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(
          name = "reason",
          named = true,
          doc = "Name for this reason; clear_anchor with the same name removes it.",
        ),
      ],
  )
  fun setAnchor(entity: Any, reason: Any): Any =
    reason(entity, reason, "set_anchor") { e, r -> world.setAnchor(e, r) }

  @StarlarkMethod(
    name = "clear_anchor",
    doc = "Removes a set_anchor reason from an entity.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room."),
        Param(name = "reason", named = true, doc = "The reason given to set_anchor."),
      ],
  )
  fun clearAnchor(entity: Any, reason: Any): Any =
    reason(entity, reason, "clear_anchor") { e, r -> world.clearAnchor(e, r) }

  // ---- Queries -------------------------------------------------------------------------------

  private fun ownerRoom(): Room =
    world.roomOfEntity(owner) ?: fail("${owner.name} is not in a room")

  @StarlarkMethod(
    name = "here",
    doc = "The actor or ground item in the owner's room matching a keyword, or None.",
    parameters =
      [Param(name = "keyword", named = true, doc = "Keyword to match, as a player would type it.")],
  )
  fun here(keyword: Any): Any =
    verb("here") {
      val word = str(keyword, "here(keyword)")
      val room = ownerRoom()
      val a =
        (owner as? Actor)?.let { world.findActor(it, word) }
          ?: room.actors.firstOrNull { it !== owner && it.matches(word) }
      v(a ?: room.items.firstOrNull { it.matches(word) })
    }

  @StarlarkMethod(
    name = "find",
    doc =
      "Entities of a prototype, ascending id. scope is 'room' (the owner's room), 'zone' (its package) or 'world'.",
    parameters =
      [
        Param(name = "label", named = true, doc = "Prototype label to look for."),
        Param(
          name = "scope",
          named = true,
          defaultValue = "'room'",
          doc = "Where to look: 'room', 'zone' or 'world'.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun find(label: Any, scope: Any, thread: StarlarkThread): Any =
    verb("find", "$label") {
      val l = label(str(label, "find(label)"), pkgOf(thread), "find(label)")
      val where = choice(scope, "find(scope)", listOf("room", "zone", "world"))
      val room = if (where == "world") null else ownerRoom()
      val found =
        world.allEntities
          .filter { it.protoLabel == l }
          .filter { e ->
            when (where) {
              "room" -> world.roomOfEntity(e) === room
              "zone" -> world.roomOfEntity(e)?.label?.pkg == room!!.label.pkg
              else -> true
            }
          }
          .sortedBy { it.id }
      sList(found.map { v(it) })
    }

  private fun near(
    actor: Any,
    band: Any,
    what: String,
    pick: (Actor, List<Actor>) -> List<Actor>,
  ): Any =
    verb(what) {
      val a = actorArg(actor, "$what(actor)")
      val reach = Band.parse(choice(band, "$what(band)", Band.entries.map { it.word }))
      val within =
        a.room.actors.filter {
          it !== a &&
            world.isSimulated(it) &&
            reach.reaches(Band.between(a.trackIndex, it.trackIndex))
        }
      sList(pick(a, within).map { v(it) })
    }

  @StarlarkMethod(
    name = "enemies_near",
    doc = "Opponents of actor within a band ('melee', 'near', 'far').",
    parameters =
      [
        Param(name = "actor", named = true, doc = "The actor whose opponents are wanted."),
        Param(name = "band", named = true, doc = "Maximum distance: 'melee', 'near' or 'far'."),
      ],
  )
  fun enemiesNear(actor: Any, band: Any): Any =
    near(actor, band, "enemies_near") { a, within ->
      val foes = world.opponentsOf(a).toSet()
      within.filter { it in foes }
    }

  @StarlarkMethod(
    name = "allies_near",
    doc =
      "Actors of the same side (players with players, mobs with mobs) within a band who are not fighting actor, and members of actor's group (and their pets) within the band, simulated and not fighting actor.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "The actor whose allies are wanted."),
        Param(name = "band", named = true, doc = "Maximum distance: 'melee', 'near' or 'far'."),
      ],
  )
  fun alliesNear(actor: Any, band: Any): Any =
    near(actor, band, "allies_near") { a, within ->
      val ga = world.groupOf(a)
      within.filter { x ->
        x.id !in a.fighting && (x.isPlayer == a.isPlayer || (ga != null && world.groupOf(x) === ga))
      }
    }

  @StarlarkMethod(
    name = "path_to",
    doc =
      "Directions from the owner's room to a room or actor through open exits; [] if already there, None if unreachable.",
    parameters =
      [Param(name = "target", named = true, doc = "Room, room label, or actor to reach.")],
    useStarlarkThread = true,
  )
  fun pathTo(target: Any, thread: StarlarkThread): Any =
    verb("path_to") {
      val dest =
        when (target) {
          is ActorView -> actorArg(target, "path_to(target)").room
          is ItemView ->
            world.roomOfEntity(itemArg(target, "path_to(target)"))
              ?: fail("path_to(): the item is nowhere")
          else -> roomArg(target, "path_to(target)", thread)
        }
      world.pathTo(ownerRoom(), dest)?.let { p -> sList(p.map { it.name }) } ?: Starlark.NONE
    }

  @StarlarkMethod(
    name = "roll",
    doc = "Rolls dice such as '2d6+1'.",
    parameters = [Param(name = "dice", named = true, doc = "Dice expression such as '2d6+1'.")],
  )
  fun roll(dice: Any): Any =
    verb("roll", "$dice") {
      val d =
        try {
          Dice.parse(str(dice, "roll(dice)"))
        } catch (e: IllegalArgumentException) {
          fail("roll(): ${e.message}")
        }
      sInt(d.roll(world.rng))
    }

  @StarlarkMethod(
    name = "has_feature",
    doc = "Whether the world has the named feature module on.",
    parameters = [Param(name = "name", named = true, doc = "Feature id, such as 'combat'.")],
  )
  fun hasFeature(name: Any): Any =
    verb("has_feature", "$name") {
      val id = str(name, "has_feature(name)")
      val f =
        Feature.byId(id)
          ?: fail(
            "has_feature(): unknown feature '$id'; expected one of " +
              Feature.entries.joinToString { it.id }
          )
      f in world.features
    }

  @StarlarkMethod(
    name = "plan",
    doc =
      "Goal-oriented planning: the cheapest list of action names that reaches goal from facts, [] if it already holds, None if unreachable or out of bounds.",
    parameters =
      [
        Param(name = "facts", named = true, doc = "Dict of fact name to bool, int or str."),
        Param(
          name = "actions",
          named = true,
          doc = "List of dicts with name, pre, effects and cost.",
        ),
        Param(name = "goal", named = true, doc = "Dict of fact name to the value it must have."),
        Param(
          name = "max_expansions",
          named = true,
          defaultValue = "1024",
          doc = "Search nodes to expand at most, 1 to 16384.",
        ),
      ],
  )
  fun plan(facts: Any, actions: Any, goal: Any, maxExpansions: Any): Any =
    verb("plan") {
      val maxExp = int(maxExpansions, "ctx.plan(max_expansions)")
      val compiledActions =
        list(actions, "ctx.plan(actions)").map { item ->
          val action = dict(item, "ctx.plan(actions) item")
          val name =
            str(
              action["name"] ?: fail("ctx.plan(actions): every action needs a 'name'"),
              "ctx.plan(actions) name",
            )
          action.keys
            .firstOrNull { it !in ACTION_KEYS }
            ?.let { fail("ctx.plan(actions): unknown key '$it' in action '$name'") }
          val cost = action["cost"]
          GoapAction(
            name = name,
            cost = if (isNone(cost)) 1 else int(cost, "ctx.plan(actions) cost of '$name'"),
            pre =
              action["pre"].let { if (isNone(it)) emptyMap() else factMap(it, "pre of '$name'") },
            effects =
              action["effects"].let {
                if (isNone(it)) emptyMap() else factMap(it, "effects of '$name'")
              },
          )
        }
      val res =
        GoapPlanner.plan(
          facts = factMap(facts, "facts"),
          actions = compiledActions,
          goal = factMap(goal, "goal"),
          maxExpansions = maxExp,
        )
      when (res) {
        is PlanResult.Found -> sList(res.steps)
        is PlanResult.NotFound -> Starlark.NONE
      }
    }

  private fun factMap(v: Any?, what: String): Map<String, FactValue> =
    dict(v, "ctx.plan($what)").mapValues { (k, value) ->
      when (value) {
        is Boolean -> FactValue.Bool(value)
        is StarlarkInt -> FactValue.Num(value.toLong("ctx.plan($what) fact '$k'"))
        is String -> FactValue.Text(value)
        else ->
          fail("ctx.plan($what): fact '$k' must be a bool, int or str, got ${typeName(value)}")
      }
    }

  override fun repr(printer: Printer) {
    printer.append("<ctx ${frame.site.hook} of ${owner.name}>")
  }

  /** One line for diagnostics. */
  internal fun summary(): String = describeCtx(owner, hc)

  companion object {
    private val ACTION_KEYS = setOf("name", "pre", "effects", "cost")
  }
}

internal fun describeCtx(owner: Entity, hc: HookContext): String =
  buildList {
      add("self=$owner")
      hc.actor?.let { add("actor=$it") }
      hc.target?.let { add("target=$it") }
      hc.item?.let { add("item=$it") }
      hc.room?.let { add("room=${it.label}") }
      hc.direction?.let { add("direction=$it") }
      hc.text?.let { add("text=${it.take(80)}") }
      hc.skill?.let { add("skill=${it.label}") }
      hc.amount?.let { add("amount=$it") }
    }
    .joinToString(" ")
