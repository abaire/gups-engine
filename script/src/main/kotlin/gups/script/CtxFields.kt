package gups.script

import gups.core.model.Actor
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.world.HookContext
import gups.core.world.roomOfEntity
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark

/** Read-only fields of `ctx`: what the hook is about. */
abstract class CtxFields
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxBase(frame, owner, hc, extras) {
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
    doc =
      "Opponents for on_combat_tick, summoned mobs for on_summon, or the actors a cast landed on, for on_skill_use.",
  )
  fun targets(): Any = field {
    (hc.mobs ?: hc.landed ?: extras.targets)?.let { t -> sList(t.map { v(it) }) } ?: Starlark.NONE
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
    name = "outcome",
    structField = true,
    doc = "How a skill use went, for on_skill_use: 'fail', 'resist', 'success' or 'report'.",
  )
  fun outcome(): Any = field { hc.outcome ?: Starlark.NONE }

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
}
