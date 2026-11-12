package gups.script

import gups.core.HookRef
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.Band
import gups.core.model.Condition
import gups.core.model.DamageType
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.Mob
import gups.core.model.ModifierPoint
import gups.core.model.Player
import gups.core.model.STANDING_MAX
import gups.core.model.STANDING_MIN
import gups.core.model.StandingDelta
import gups.core.model.WorldSchema
import gups.core.model.didYouMean
import gups.core.rules.LevelUpResult
import gups.core.rules.PlayerDeathOutcome
import gups.core.rules.RulesContext
import gups.core.rules.RulesEngine
import gups.core.rules.StartingStats
import gups.core.world.Behavior
import gups.core.world.Feature
import gups.core.world.KernelMsg
import gups.core.world.TargetChoice
import gups.core.world.World
import gups.core.world.dispositionParts
import gups.core.world.factionsOf
import gups.core.world.memoriesOf
import gups.core.world.msg
import gups.core.world.standing
import gups.core.world.standingHistory
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Printer
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkList
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue
import net.starlark.java.spelling.SpellChecker

// The rules module: the files of `//rules` export one function per rules call. The package is
// required, so a call that fails gives a neutral result instead of falling back to anything.

/**
 * Rules exports that must give the same answer every time they are called with the same arguments,
 * and therefore cannot read rng, roll dice or observe pulse.
 */
private val DETERMINISTIC_RULES_EXPORTS = setOf("rules.xp_to_level")

/**
 * The read-only `ctx` of a rules function or modifier. It has the call's fields and `rng` (except
 * in `xp_to_level`), and `deny` for `cast_allowed`; it has no verbs.
 */
@StarlarkBuiltin(name = "rules_ctx", doc = "The read-only context of a rules call or modifier.")
class RulesCtx internal constructor(private val frame: Frame, private val c: RulesContext) :
  StarlarkValue {
  private fun <T> field(block: () -> T): T {
    frame.check()
    return block()
  }

  private fun requireNondeterministic(what: String) {
    if (frame.site.hook in DETERMINISTIC_RULES_EXPORTS) {
      val export = frame.site.hook.removePrefix("rules.")
      fail(
        "$what is not available in $export: $export must give the same answer every time it is asked"
      )
    }
  }

  private fun v(e: gups.core.model.Entity?): Any = view(frame, e)

  @StarlarkMethod(
    name = "actor",
    structField = true,
    doc = "Whom the call is about: the attacker, caster, mover or regenerating actor.",
  )
  fun actor(): Any = field { v(c.actor) }

  @StarlarkMethod(
    name = "attacker",
    structField = true,
    doc = "For attack calls, the same as actor.",
  )
  fun attacker(): Any = field { v(c.actor) }

  @StarlarkMethod(
    name = "defender",
    structField = true,
    doc = "For attack calls, the one attacked; also the one taking damage in mitigate.",
  )
  fun defender(): Any = field { v(c.target) }

  @StarlarkMethod(
    name = "target",
    structField = true,
    doc =
      "The other actor of the call, if any: the defender, the pinner, the victim, the killer, or the actor advanced on or retreated from.",
  )
  fun target(): Any = field { v(c.target) }

  @StarlarkMethod(
    name = "weapon",
    structField = true,
    doc = "The weapon used in an attack, or None for unarmed.",
  )
  fun weapon(): Any = field { v(c.weapon) }

  @StarlarkMethod(name = "item", structField = true, doc = "The item, for get_lag and carry_limit.")
  fun item(): Any = field { v(c.item) }

  @StarlarkMethod(
    name = "band",
    structField = true,
    doc = "'melee', 'near' or 'far' between actor and target, or None.",
  )
  fun band(): Any = field {
    if (Feature.TRACK !in c.world.features) Starlark.NONE else c.band?.word ?: Starlark.NONE
  }

  @StarlarkMethod(
    name = "skill",
    structField = true,
    doc = "The skill being cast or evaluated, or None.",
  )
  fun skill(): Any = field { c.skill?.let { SkillView(it, c.world.schema) } ?: Starlark.NONE }

  @StarlarkMethod(
    name = "damage_type",
    structField = true,
    doc =
      "The damage type as a string in auto-attack to_hit and damage calls, damage chains and mitigate, otherwise None.",
  )
  fun damageType(): Any = field { c.damageType?.name ?: Starlark.NONE }

  @StarlarkMethod(
    name = "slot",
    structField = true,
    doc =
      "The slot of the swinging item in an auto-attack swing, 'wield' for an unarmed swing, otherwise None.",
  )
  fun slot(): Any = field { c.slot ?: Starlark.NONE }

  @StarlarkMethod(
    name = "affect",
    structField = true,
    allowReturnNones = true,
    doc =
      "In a modifier contributed by an actor affect or an item instance affect, that affect's view; None everywhere else.",
  )
  fun affect(): Any = field {
    val a = c.affect ?: return@field Starlark.NONE
    val host = findAffectHost(c, a) ?: return@field Starlark.NONE
    AffectView(frame, host, a)
  }

  @StarlarkMethod(
    name = "task",
    structField = true,
    allowReturnNones = true,
    doc =
      "In interrupt_check for an actor running a timed task, the task view, struct(name, started_at, ends_at, remaining, interrupt); None everywhere else.",
  )
  fun task(): Any = field { c.task?.let { taskStruct(c.world, it) } ?: Starlark.NONE }

  @StarlarkMethod(
    name = "limit",
    structField = true,
    doc =
      "The actor's carry limit asked without an item; None outside encumbrance_level and encumbrance.",
  )
  fun limit(): Any = field { c.limit?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "carried",
    structField = true,
    doc =
      "The weight carried and worn, containers' contents included; None outside encumbrance_level and encumbrance.",
  )
  fun carried(): Any = field { c.carried?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "encumbrance_level",
    structField = true,
    doc = "The level encumbrance_level just gave; None outside encumbrance.",
  )
  fun encumbranceLevel(): Any = field { c.encumbranceLevel?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "xp",
    structField = true,
    doc = "The experience pool a kill is being settled for, in xp_share; None elsewhere.",
  )
  fun xp(): Any = field { c.xp?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "behaviors",
    structField = true,
    doc = "The mob's prototype behaviors, as behavior values, in owned_behaviors; None elsewhere.",
  )
  fun behaviors(): Any = field {
    c.behaviors?.let { sList(it.map { b -> BehaviorDecl(b) }) } ?: Starlark.NONE
  }

  @StarlarkMethod(
    name = "cast_target",
    structField = true,
    doc = "The cast's target actor, in recognizes_skill; None elsewhere.",
  )
  fun castTarget(): Any = field { v(c.castTarget) }

  @StarlarkMethod(
    name = "base",
    structField = true,
    doc = "The plugin's own value, in a plugin-declared rules export; None elsewhere.",
  )
  fun base(): Any = field { plainValue(c.base) }

  @StarlarkMethod(
    name = "args",
    structField = true,
    doc = "Extra fields a plugin passed to its rules export; None elsewhere.",
  )
  fun args(): Any = field {
    c.args?.let { m -> sDict(m.mapValues { (_, x) -> plainValue(x) }) } ?: Starlark.NONE
  }

  /** A plugin-supplied plain value as Starlark; entities become views. */
  private fun plainValue(x: Any?): Any =
    when (x) {
      null -> Starlark.NONE
      is Boolean -> x
      is Int -> sInt(x)
      is Long -> sInt(x)
      is String -> x
      is gups.core.model.Entity -> v(x)
      is List<*> -> sList(x.map(::plainValue))
      is Map<*, *> ->
        sDict(
          x.entries.associate { (k, item) ->
            (k as? String
              ?: fail("rules args: keys must be strings, got ${k?.javaClass?.simpleName}")) to
              plainValue(item)
          }
        )
      else -> fail("rules ctx: cannot pass a ${x.javaClass.simpleName} to a rules export")
    }

  @StarlarkMethod(
    name = "away",
    structField = true,
    doc = "Pulses since ctx.actor became eligible to go home, in leash_return; None elsewhere.",
  )
  fun away(): Any = field { c.away?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "past_leash",
    structField = true,
    doc = "True in leash_return when ctx.actor stands past its leash; None elsewhere.",
  )
  fun pastLeash(): Any = field { c.pastLeash ?: Starlark.NONE }

  @StarlarkMethod(
    name = "command",
    structField = true,
    doc = "In chain_think, the command line of the action that finished; None elsewhere.",
  )
  fun command(): Any = field { c.command ?: Starlark.NONE }

  @StarlarkMethod(
    name = "chain",
    structField = true,
    doc = "In chain_think, how many chained thinks in a row led to that action; None elsewhere.",
  )
  fun chain(): Any = field { c.chain?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "contributors",
    structField = true,
    doc = "The victim's contributors, as contributor structs, in xp_share; None elsewhere.",
  )
  fun contributors(): Any = field {
    c.contributors?.let { sList(it.map { x -> ContributorView(frame, x) }) } ?: Starlark.NONE
  }

  @StarlarkMethod(
    name = "room",
    structField = true,
    doc = "The actor's room; for sense_cost, the room the signal enters.",
  )
  fun room(): Any = field { v(c.room ?: c.actor?.room) }

  @StarlarkMethod(
    name = "source",
    structField = true,
    doc = "For can_perceive and perceive_message: the actor who caused the event, or None.",
  )
  fun source(): Any = field { v(c.source) }

  @StarlarkMethod(
    name = "exit",
    structField = true,
    doc =
      "For sense_cost: the exit of ctx.room leading back toward the signal's source. For door_intensity: the door's exit, or None for a container.",
  )
  fun exit(): Any = field {
    val r = c.room ?: c.actor?.room
    val exit = c.exit
    if (r != null && exit != null) ExitView(frame, r, exit) else Starlark.NONE
  }

  @StarlarkMethod(
    name = "channel",
    structField = true,
    doc = "For the perception calls: the channel name, such as 'sound'.",
  )
  fun channel(): Any = field { c.channel?.name ?: Starlark.NONE }

  @StarlarkMethod(
    name = "event",
    structField = true,
    doc =
      "For can_perceive and perceive_message: the event name, such as 'say' or 'combat'; in skill_check, a skill_roll's event, or None for a cast.",
  )
  fun event(): Any = field { c.event ?: Starlark.NONE }

  @StarlarkMethod(
    name = "intensity",
    structField = true,
    doc = "For can_perceive and perceive_message: the event's strength where it happened.",
  )
  fun intensity(): Any = field { c.intensity?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "cost",
    structField = true,
    doc =
      "For can_perceive and perceive_message: the attenuation summed along the cheapest path from the event's room; 0 in that room.",
  )
  fun cost(): Any = field { c.cost?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "source_room",
    structField = true,
    doc = "For can_perceive and perceive_message: the room the event happened in.",
  )
  fun sourceRoom(): Any = field { v(c.sourceRoom) }

  @StarlarkMethod(
    name = "via_exit",
    structField = true,
    doc =
      "For can_perceive and perceive_message: the listener's exit leading toward the event, or None in the event's own room.",
  )
  fun viaExit(): Any = field { c.viaExit?.name ?: Starlark.NONE }

  @StarlarkMethod(
    name = "text",
    structField = true,
    doc = "For can_perceive and perceive_message: the event's text, or None.",
  )
  fun text(): Any = field { c.text ?: Starlark.NONE }

  @StarlarkMethod(
    name = "action",
    structField = true,
    doc = "For door_intensity: 'open', 'close', 'lock' or 'unlock'.",
  )
  fun action(): Any = field { c.action ?: Starlark.NONE }

  @StarlarkMethod(
    name = "tags",
    structField = true,
    doc = "For door_intensity: the door's or container's tags, sorted.",
  )
  fun tags(): Any = field { sList(c.tags.sorted()) }

  @StarlarkMethod(
    name = "pulse",
    structField = true,
    doc = "Game time; not available in xp_to_level.",
  )
  fun pulse(): Any = field {
    requireNondeterministic("pulse")
    sInt(c.world.pulse)
  }

  @StarlarkMethod(
    name = "rng",
    structField = true,
    doc = "The seeded random number generator; not available in xp_to_level.",
  )
  fun rng(): Any = field {
    requireNondeterministic("rng")
    RngView(frame)
  }

  @StarlarkMethod(
    name = "roll",
    doc = "Rolls dice such as '2d6+1' with the seeded rng; not available in xp_to_level.",
    parameters = [Param(name = "dice", named = true, doc = "Dice expression such as '2d6+1'.")],
  )
  fun roll(dice: Any): Any = field {
    requireNondeterministic("roll()")
    val d =
      try {
        gups.core.model.Dice.parse(str(dice, "roll(dice)"))
      } catch (e: IllegalArgumentException) {
        fail("roll(): ${e.message}")
      }
    sInt(d.roll(c.rng))
  }

  @StarlarkMethod(
    name = "deny",
    doc = "Refuses a cast; valid only in cast_allowed and its modifiers.",
    parameters = [Param(name = "msg", named = true, doc = "Text shown to the caster.")],
  )
  fun deny(msg: Any): Any = field {
    if (!frame.site.vetoable)
      fail("deny() is only valid in cast_allowed (this is ${frame.site.hook})")
    Denial(checkString(str(msg, "deny(msg)"), "deny(msg)", frame.host.limits))
  }

  @StarlarkMethod(
    name = "disposition_parts",
    structField = true,
    doc =
      "In rules.disposition, a struct with declared, faction_to_actor, faction_to_faction, personal and total; None elsewhere.",
  )
  fun dispositionParts(): Any = field {
    c.dispositionParts?.let { p ->
      StructValue(
        linkedMapOf(
          "declared" to sInt(p.declared),
          "faction_to_actor" to sInt(p.factionToActor),
          "faction_to_faction" to sInt(p.factionToFaction),
          "personal" to sInt(p.personal),
          "total" to sInt(p.total),
        )
      )
    } ?: Starlark.NONE
  }

  @StarlarkMethod(
    name = "amount",
    structField = true,
    doc = "In standing_change, the healing done or the count given; otherwise None.",
  )
  fun amount(): Any = field { c.amount?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "helped",
    structField = true,
    doc = "In an assist standing event, the actor helped; None elsewhere.",
  )
  fun helped(): Any = field { v(c.helped) }

  @StarlarkMethod(
    name = "pet",
    structField = true,
    doc = "In a standing event raised for a pet's owner, the pet; None elsewhere.",
  )
  fun pet(): Any = field { v(c.pet) }

  @StarlarkMethod(
    name = "behavior",
    structField = true,
    doc = "In target_order, 'aggressive', 'assist' or 'retarget'; None elsewhere.",
  )
  fun behavior(): Any = field { c.behavior ?: Starlark.NONE }

  @StarlarkMethod(
    name = "prefer",
    structField = true,
    doc = "In target_order, the behavior's prefer value; None elsewhere.",
  )
  fun prefer(): Any = field { c.prefer?.let { fromAttr(it, null) } ?: Starlark.NONE }

  @StarlarkMethod(
    name = "candidates",
    structField = true,
    doc =
      "In target_order, the non-empty candidate actor list in ascending id order; None elsewhere.",
  )
  fun candidates(): Any = field {
    c.candidates?.let { sList(it.map { a -> v(a) }) } ?: Starlark.NONE
  }

  @StarlarkMethod(
    name = "current",
    structField = true,
    doc =
      "In target_order, the primary target when it is in candidates (the ally's for assist, the mob's for retarget); None elsewhere.",
  )
  fun current(): Any = field { v(c.current) }

  @StarlarkMethod(
    name = "current_since",
    structField = true,
    doc =
      "In target_order for 'retarget', the pulse the mob's primary target last changed; None elsewhere.",
  )
  fun currentSince(): Any = field { c.currentSince?.let(::sInt) ?: Starlark.NONE }

  @StarlarkMethod(
    name = "dispositions",
    structField = true,
    doc =
      "In target_order with the standing feature on, actor's disposition toward each candidate; None elsewhere.",
  )
  fun dispositions(): Any = field { c.dispositions?.let { sList(it.map(::sInt)) } ?: Starlark.NONE }

  @StarlarkMethod(
    name = "ally",
    structField = true,
    doc = "In target_order for 'assist', the ally being helped; None elsewhere.",
  )
  fun ally(): Any = field { v(c.ally) }

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
  )
  fun standing(holder: Any, subject: Any): Any = field {
    val pkg = frame.site.pkg
    val h = toStandingHolder(holder, pkg, "standing", c.world.protos)
    sInt(c.world.standing(h, toStandingSubject(subject, pkg, "standing", c.world.protos, h)))
  }

  @StarlarkMethod(
    name = "factions_of",
    doc = "The faction labels actor belongs to, as a list of strings.",
    parameters = [Param(name = "actor", named = true, doc = "The actor.")],
  )
  fun factionsOf(actor: Any): Any = field {
    val a =
      (actor as? ActorView)?.actor
        ?: fail("factions_of(): actor must be an actor view, got ${typeName(actor)}")
    val factions =
      try {
        c.world.factionsOf(a)
      } catch (e: IllegalStateException) {
        fail("factions_of(): ${e.message}")
      }
    sList(factions.map { it.v })
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
  )
  fun standingHistory(holder: Any, subject: Any, since: Any, via: Any, event: Any): Any = field {
    val pkg = frame.site.pkg
    val holderObj = toStandingHolder(holder, pkg, "standing_history", c.world.protos)
    val subjectObj = toStandingSubject(subject, pkg, "standing_history", c.world.protos, holderObj)

    val sincePulse = int(since, "standing_history(since)").toLong().coerceAtLeast(0L)
    val viaId =
      if (isNone(via)) {
        null
      } else {
        val viaActor = (via as? ActorView)?.actor
        if (viaActor !is Mob)
          fail("standing_history(): via must be a mob view, got ${typeName(via)}")
        viaActor.id
      }
    val eventName = if (isNone(event)) null else str(event, "standing_history(event)")

    val totals = c.world.standingHistory(holderObj, subjectObj, sincePulse, viaId, eventName)
    StructValue(linkedMapOf("gained" to sInt(totals.gained), "lost" to sInt(totals.lost)))
  }

  @StarlarkMethod(
    name = "memories",
    doc = "Queries combat memories of ctx.actor, newest first; [] when ctx.actor is not a mob.",
    parameters =
      [
        Param(
          name = "subject",
          defaultValue = "None",
          named = true,
          doc = "Optional actor view filter.",
        ),
        Param(
          name = "event",
          defaultValue = "None",
          named = true,
          doc = "Optional event name filter.",
        ),
        Param(name = "since", defaultValue = "0", named = true, doc = "Minimum pulse."),
      ],
  )
  fun memories(subject: Any, event: Any, since: Any): Any = field {
    FeatureOwnership.refused("rules_ctx.memories", "ctx.memories()", c.world.features)?.let {
      fail(it)
    }
    val m = c.actor as? Mob ?: return@field sList(emptyList())
    val sub =
      if (isNone(subject)) {
        null
      } else {
        (subject as? ActorView)?.actor
          ?: fail("memories(subject): expected an actor view, got ${typeName(subject)}")
      }
    val ev = if (isNone(event)) null else str(event, "memories(event)")
    val sincePulse = int(since, "memories(since)").toLong().coerceAtLeast(0L)
    sList(c.world.memoriesOf(m, sub, ev, sincePulse).map { memoryValue(frame, it) })
  }

  override fun isImmutable(): Boolean = true

  override fun repr(printer: Printer) {
    printer.append("<rules ctx ${frame.site.hook} of ${c.actor?.name ?: c.room?.name ?: "none"}>")
  }
}

/** The entity carrying [a]: the actor, the target, or an item they hold or the rules call names. */
private fun findAffectHost(c: RulesContext, a: Affect): Entity? {
  fun carries(x: Actor) = x.affects.any { it.id == a.id }
  fun carries(x: Item) = x.affects.any { it.id == a.id }
  fun among(actor: Actor?): Entity? {
    if (actor == null) return null
    if (carries(actor)) return actor
    return actor.inventory.firstOrNull { carries(it) }
      ?: actor.equipment.values.firstOrNull { carries(it) }
  }
  return among(c.actor) ?: among(c.target) ?: c.item?.takeIf { carries(it) }
}

/** How a modifier point's value crosses between the engine's type and Starlark. */
private class PointCodec(
  val toStarlark: (Any?, WorldSchema) -> Any,
  val fromStarlark: (Any?, String) -> Any?,
)

@Suppress("UNCHECKED_CAST")
private fun vitalsCodec() =
  PointCodec(
    { v, schema -> TypedMap(TypedKind.VITALS, v as Map<String, Int>, schema.vitalNames) },
    { v, what -> typed(v, TypedKind.VITALS, what) },
  )

private val POINT_CODECS: Map<ModifierPoint, PointCodec> =
  mapOf(
    ModifierPoint.MOVE_COST to vitalsCodec(),
    ModifierPoint.REGEN to vitalsCodec(),
    ModifierPoint.MITIGATE to vitalsCodec(),
    ModifierPoint.BONUS_DAMAGE to
      PointCodec(
        { v, schema ->
          @Suppress("UNCHECKED_CAST")
          TypedMap(TypedKind.DAMAGES, v as Map<String, Int>, schema.damageTypes)
        },
        { v, what -> typed(v, TypedKind.DAMAGES, what) },
      ),
    ModifierPoint.CAST_ALLOWED to
      PointCodec(
        { v, _ -> (v as String?)?.let(::Denial) ?: Starlark.NONE },
        { v, what -> denialValue(v, what) },
      ),
  )

private val INT_POINT_CODEC = PointCodec({ v, _ -> sInt(v as Int) }, { v, what -> int(v, what) })

private fun codecOf(point: ModifierPoint): PointCodec = POINT_CODECS[point] ?: INT_POINT_CODEC

/** Converts a rules result for [point] to Starlark, as a modifier's `base` argument. */
private fun baseValue(point: ModifierPoint, v: Any?, schema: WorldSchema): Any =
  codecOf(point).toStarlark(v, schema)

private fun denialValue(v: Any?, what: String): String? =
  when {
    isNone(v) -> null
    v is Denial -> v.message
    else -> fail("$what: expected None or ctx.deny(msg), got ${typeName(v)}")
  }

/** Converts a modifier's return value for [point] back to the engine's type. */
private fun pointValue(point: ModifierPoint, v: Any?, what: String): Any? =
  codecOf(point).fromStarlark(v, what)

/** Which modifier a [StarlarkHost.runModifier] runs: its [point], function [ref] and [source]. */
internal data class ModifierCall(val point: ModifierPoint, val ref: HookRef, val source: String)

/**
 * Runs one modifier for [StarlarkHost.modify]: `fn(ctx, base)` with its own rules budget, charged
 * to the package of the function's file. A failure keeps [base].
 */
internal fun StarlarkHost.runModifier(
  world: World,
  modifier: ModifierCall,
  c: RulesContext,
  base: Any?,
): Any? {
  val (point, ref, source) = modifier
  val site =
    Site(
      ref.file.pkg,
      null,
      "modifier ${point.scriptName} ($source)",
      point == ModifierPoint.CAST_ALLOWED,
      c.requireActor().id,
    )
  var out: Any? = base
  val result =
    call(
      world,
      site,
      ref,
      { f -> RulesCtx(f, c) },
      positional = { f -> listOf(RulesCtx(f, c), baseValue(point, base, world.schema)) },
      budget = limits.rulesSteps,
    )
  if (result is CallResult.Ok) {
    try {
      out = pointValue(point, result.value, "${point.scriptName} modifier $source")
    } catch (e: EvalException) {
      report(world, site, "script_error", e.message ?: "bad modifier result", e, null)
    }
  }
  return out
}

/**
 * The Starlark rules module: calls the functions the files of `//<pkg>` export.
 *
 * There is no built-in fallback ruleset. A rules export that fails, exceeds resource budgets,
 * throws an evaluation exception, returns an invalid type, or references an undeclared schema name
 * records a `script_error` diagnostic against the rules package, triggers a `rules_error` alert,
 * and substitutes a neutral fallback result. Neutral fallbacks ensure errors never hurt, kill,
 * reward, or trap actors:
 *
 * ## Neutral Fallback Return Values
 * - `to_hit`: `0` (attack misses).
 * - `retreat_check`: `0` (retreat attempt fails).
 * - `flee_chance`: `0` (flee attempt fails).
 * - `damage`: `0` (deals no damage).
 * - `mitigate`: empty vitals map (the hit causes no vital losses).
 * - `initiative`: `0` (baseline initiative roll).
 * - `cast_allowed`: denied with the engine's `nothing_happens` message.
 * - `interrupt_check`: `false` (spell or action is not interrupted).
 * - `move_cost`: empty map (no movement vital cost deducted).
 * - `regen`: empty map (no vitals regenerated or lost).
 * - `encumbrance`: empty map (no encumbrance stat penalties applied).
 * - `encumbrance_level`: `0` (no encumbrance level applied).
 * - `starting_stats`: empty result (base stats and vitals unmodified from prototypes).
 * - `carry_limit`: `Int.MAX_VALUE` (no weight/carrying cap applied).
 * - `get_lag`, `advance_lag`, `retreat_lag`, `retreat_fail_lag`, `flee_fail_lag`: `0` recovery
 *   pulses (no action delay).
 * - `sense_cost`: `-1` (blocks sensory perception propagation past the exit/hop).
 * - `can_perceive`: `false` (event is not perceived).
 * - `perceive_message`: `null` (no sensory notification text rendered).
 * - `door_intensity`: `0` (door manipulation produces no sensory event).
 * - `condition`: the actor's current condition, unchanged.
 * - `xp_for_kill`: `0` XP awarded.
 * - `xp_to_level`: `Long.MAX_VALUE` (no level up possible).
 * - `on_level_up`: no gains (the level is still raised).
 * - `on_player_death`: respawns at the player's recall room (or the current room when none is set),
 *   restores vitals to effective maxima, with `0` XP lost and no level loss.
 * - `equipment_slots`: the last valid slot list it returned, or an empty list before any.
 *
 * Bound to its world by [StarlarkHost.install].
 */
class StarlarkRules(private val host: StarlarkHost, val pkg: String = "rules") : RulesEngine {
  internal var world: World? = null
  private val lastAlert = HashMap<String, Long>()
  private var goodSlots: List<String> = emptyList()
  private var goodFamilies: Map<String, List<String>> = emptyMap()

  /**
   * While set, failures are collected here instead of recorded and alerted: the dry run uses it to
   * learn what a rules package gets wrong.
   */
  internal var collected: MutableList<String>? = null

  /** True if the rules package is loaded and defines [export]. */
  fun defines(export: RulesExport): Boolean = refOf(export.scriptName) != null

  /** True if the rules package defines the plugin-declared export [name]. */
  fun definesPluginExport(name: String): Boolean = refOf(name) != null

  /** Where the export [name] is defined: any non-test file of the rules package. */
  private fun refOf(name: String): HookRef? = host.scripts.packages[pkg]?.rulesExports?.get(name)

  /** How a rules call is made: its ctx [c] (null for none), [args], and the optional extras. */
  private class Invocation(
    val c: RulesContext?,
    val args: (Frame) -> List<Any>,
    val deny: Boolean = false,
    val onThread: (StarlarkThread) -> Unit = {},
  )

  /**
   * Calls [export] with the rules ctx of [c] (or [args] alone when [c] is null) and converts the
   * result with [convert]. Null means the call failed and the caller gives the neutral result.
   */
  private fun <T : Any> run(
    export: RulesExport,
    c: RulesContext?,
    args: (Frame) -> List<Any>,
    convert: (Any?) -> T,
  ): T? = runNamed(export.scriptName, Invocation(c, args), convert)

  /** [run] for any export by its script name, plugin-declared ones included. */
  private fun <T : Any> runNamed(name: String, call: Invocation, convert: (Any?) -> T): T? {
    val c = call.c
    val w = c?.world ?: world ?: return null
    val ref = definedRef(w, name) ?: return null
    val target = Target(w, Site(pkg, null, "rules.$name", call.deny, c?.actor?.id), name)
    val result =
      host.call(
        w,
        target.site,
        ref,
        { f -> c?.let { RulesCtx(f.withRng(it), it) } ?: Starlark.NONE },
        positional = { f -> call.args(c?.let { f.withRng(it) } ?: f) },
        budget = host.limits.rulesSteps,
        recordErrors = collected == null,
        onThread = call.onThread,
      )
    val converted = outcome(target, result, convert)
    if (converted == null) {
      fault(w, name, "rules.$name failed; using its neutral result for this call")
    }
    return converted
  }

  private fun Frame.withRng(c: RulesContext): Frame = also { rng = c.rng }

  /** Where [name] is defined, or null after raising the alarm that it isn't. */
  private fun definedRef(w: World, name: String): HookRef? {
    val ref = refOf(name)
    if (ref != null && host.scripts.function(ref) != null) return ref
    fault(w, name, "rules.$name is not defined by //$pkg")
    return null
  }

  /** The world, site and export name of one rules call, for reporting what it did wrong. */
  private class Target(val world: World, val site: Site, val name: String)

  /**
   * The converted value of a successful [result], else null with the failure noted if collecting.
   */
  private fun <T : Any> outcome(target: Target, result: CallResult, convert: (Any?) -> T): T? =
    when (result) {
      is CallResult.Ok -> convertResult(target, result.value, convert)
      is CallResult.Failed -> {
        collected?.add("rules.${target.name} failed: ${result.message}")
        null
      }
      CallResult.Skipped -> null
    }

  /** [value] converted, or null after reporting why it is not a valid result of the export. */
  private fun <T : Any> convertResult(target: Target, value: Any?, convert: (Any?) -> T): T? =
    try {
      convert(value)
    } catch (e: EvalException) {
      val message = "rules.${target.name} returned a bad value: ${e.message}"
      val sink = collected
      if (sink == null) {
        host.report(target.world, target.site, "script_error", message, e, null)
      } else {
        sink += message
      }
      null
    }

  /** Loud, but at most once a minute of game time per function. */
  private fun fault(w: World, name: String, message: String) {
    if (collected != null) return
    val last = lastAlert[name]
    if (last != null && w.pulse - last < host.limits.quarantineWindowPulses) return
    lastAlert[name] = w.pulse
    host.alerts("rules_error", message)
  }

  private fun ctxArgs(c: RulesContext, vararg more: Any): (Frame) -> List<Any> = { f ->
    listOf(RulesCtx(f, c), *more)
  }

  private fun intOf(export: RulesExport): (Any?) -> Int = { v ->
    int(v, "rules.${export.scriptName} return value")
  }

  private fun nonNegative(export: RulesExport, n: Int): Int {
    if (n < 0) fail("rules.${export.scriptName} must be at least 0, got $n")
    return n
  }

  private fun lagOf(export: RulesExport): (Any?) -> Int = { v ->
    nonNegative(export, int(v, "rules.${export.scriptName} return value"))
  }

  private fun vitalsOf(export: RulesExport): (Any?) -> Map<String, Int> = { v ->
    typed(v, TypedKind.VITALS, "rules.${export.scriptName} return value")
  }

  override fun equipmentSlots(): List<String> =
    run(RulesExport.EQUIPMENT_SLOTS, null, { emptyList() }) { v ->
        strList(v, "rules.equipment_slots return value").also { slots ->
          for (s in slots) if (!Regex("[a-z][a-z0-9_]*").matches(s))
            fail("rules.equipment_slots: '$s' is not a valid slot name")
        }
      }
      ?.also { goodSlots = it } ?: goodSlots

  override fun slotFamilies(): Map<String, List<String>> =
    run(RulesExport.SLOT_FAMILIES, null, { emptyList() }) { v ->
        val what = "rules.slot_families return value"
        dict(v, what).mapValues { (k, item) -> strList(item, "$what[\"$k\"]") }
      }
      ?.also { goodFamilies = it } ?: goodFamilies

  override fun actorSlots(c: RulesContext): List<String> =
    run(RulesExport.ACTOR_SLOTS, c, ctxArgs(c)) { v ->
      strList(v, "rules.actor_slots return value")
    } ?: equipmentSlots()

  override fun slotCost(c: RulesContext): Int =
    run(RulesExport.SLOT_COST, c, ctxArgs(c)) { v ->
      val n = int(v, "rules.slot_cost return value")
      if (n < 1) fail("rules.slot_cost must be at least 1, got $n")
      n
    } ?: c.item?.proto?.slotCost ?: 1

  override fun startingStats(c: RulesContext): StartingStats =
    run(RulesExport.STARTING_STATS, c, ctxArgs(c)) { v ->
      val what = "rules.starting_stats return value"
      val fields =
        (v as? StructValue)?.fields ?: fail("$what: expected a struct, got ${typeName(v)}")
      for (k in fields.keys) if (k !in STARTING_FIELDS)
        fail("$what: unknown field '$k'${SpellChecker.didYouMean(k, STARTING_FIELDS)}")
      StartingStats(
        stats = typed(fields["stats"], TypedKind.STATS, "$what.stats"),
        max = typed(fields["max"], TypedKind.VITALS, "$what.max"),
        current = typed(fields["current"], TypedKind.VITALS, "$what.current"),
        senses =
          fields["senses"]?.let {
            senses(it, "$what.senses", c.world.schema, allowNegative = false)
          } ?: emptyMap(),
      )
    } ?: StartingStats()

  override fun condition(c: RulesContext): Condition =
    run(RulesExport.CONDITION, c, ctxArgs(c)) { v ->
      val word = str(v, "rules.condition return value")
      Condition.parse(word)
        ?: fail("rules.condition return value: '$word' is not 'ok', 'incapacitated' or 'dead'")
    } ?: c.requireActor().condition

  override fun toHit(c: RulesContext): Int =
    run(RulesExport.TO_HIT, c, ctxArgs(c), convert = intOf(RulesExport.TO_HIT)) ?: 0

  override fun damage(c: RulesContext): Int =
    run(RulesExport.DAMAGE, c, ctxArgs(c), convert = intOf(RulesExport.DAMAGE)) ?: 0

  override fun mitigate(c: RulesContext, amount: Int, type: DamageType): Map<String, Int> =
    run(
      RulesExport.MITIGATE,
      c,
      ctxArgs(c, sInt(amount), type.name),
      convert = vitalsOf(RulesExport.MITIGATE),
    ) ?: emptyMap()

  override fun initiative(c: RulesContext): Int =
    run(RulesExport.INITIATIVE, c, ctxArgs(c), convert = intOf(RulesExport.INITIATIVE)) ?: 0

  override fun castAllowed(c: RulesContext): String? {
    // A None result is a real answer here, so it is wrapped to tell it from a failure.
    val answer =
      runNamed(RulesExport.CAST_ALLOWED.scriptName, Invocation(c, ctxArgs(c), deny = true)) { v ->
        Allowed(denialValue(v, "rules.cast_allowed return value"))
      }
    return if (answer == null) c.world.msg(KernelMsg.NOTHING_HAPPENS) else answer.denial
  }

  private class Allowed(val denial: String?)

  override fun interruptCheck(c: RulesContext, amount: Int): Boolean =
    run(RulesExport.INTERRUPT_CHECK, c, ctxArgs(c, sInt(amount))) { v ->
      bool(v, "rules.interrupt_check return value")
    } ?: false

  override fun skillCheck(c: RulesContext): Int =
    run(RulesExport.SKILL_CHECK, c, ctxArgs(c), convert = intOf(RulesExport.SKILL_CHECK)) ?: 0

  override fun skillPower(c: RulesContext): Int =
    run(RulesExport.SKILL_POWER, c, ctxArgs(c), convert = lagOf(RulesExport.SKILL_POWER)) ?: 100

  override fun retreatCheck(c: RulesContext): Int =
    run(RulesExport.RETREAT_CHECK, c, ctxArgs(c), convert = intOf(RulesExport.RETREAT_CHECK)) ?: 0

  override fun fleeChance(c: RulesContext): Int =
    run(RulesExport.FLEE_CHANCE, c, ctxArgs(c), convert = intOf(RulesExport.FLEE_CHANCE)) ?: 0

  override fun moveCost(c: RulesContext): Map<String, Int> =
    run(RulesExport.MOVE_COST, c, ctxArgs(c), convert = vitalsOf(RulesExport.MOVE_COST))
      ?.mapValues { it.value.coerceAtLeast(0) } ?: emptyMap()

  override fun movePace(c: RulesContext): Int? {
    if (!defines(RulesExport.MOVE_PACE)) return null
    return run(RulesExport.MOVE_PACE, c, ctxArgs(c), convert = intOf(RulesExport.MOVE_PACE))
  }

  override fun regen(c: RulesContext): Map<String, Int> =
    run(RulesExport.REGEN, c, ctxArgs(c), convert = vitalsOf(RulesExport.REGEN)) ?: emptyMap()

  override fun carryLimit(c: RulesContext): Int =
    run(RulesExport.CARRY_LIMIT, c, ctxArgs(c), convert = intOf(RulesExport.CARRY_LIMIT))
      ?: Int.MAX_VALUE

  override fun encumbranceLevel(c: RulesContext): Int =
    run(
      RulesExport.ENCUMBRANCE_LEVEL,
      c,
      ctxArgs(c),
      convert = lagOf(RulesExport.ENCUMBRANCE_LEVEL),
    ) ?: 0

  override fun encumbrance(c: RulesContext): Map<String, Int> =
    run(RulesExport.ENCUMBRANCE, c, ctxArgs(c)) { v ->
      typed(v, TypedKind.STATS, "rules.encumbrance return value")
    } ?: emptyMap()

  override fun itemPower(c: RulesContext): Int =
    run(RulesExport.ITEM_POWER, c, ctxArgs(c), convert = lagOf(RulesExport.ITEM_POWER)) ?: 100

  override fun getLag(c: RulesContext, band: Band): Int? {
    val answer =
      run(RulesExport.GET_LAG, c, ctxArgs(c, band.word)) { v ->
        Lag(
          intOrNull(v, "rules.get_lag return value")?.let { nonNegative(RulesExport.GET_LAG, it) }
        )
      }
    return if (answer == null) 0 else answer.pulses
  }

  private class Lag(val pulses: Int?)

  override fun senseCost(c: RulesContext): Int =
    run(RulesExport.SENSE_COST, c, ctxArgs(c), convert = intOf(RulesExport.SENSE_COST)) ?: -1

  override fun canPerceive(c: RulesContext): Boolean =
    run(RulesExport.CAN_PERCEIVE, c, ctxArgs(c)) { v -> bool(v, "rules.can_perceive return value") }
      ?: false

  override fun perceiveMessage(c: RulesContext): String? =
    run(RulesExport.PERCEIVE_MESSAGE, c, ctxArgs(c)) { v ->
        Message(
          if (isNone(v)) null
          else
            checkString(
              str(v, "rules.perceive_message return value"),
              "rules.perceive_message",
              host.limits,
            )
        )
      }
      ?.text

  private class Message(val text: String?)

  override fun doorIntensity(c: RulesContext): Int =
    run(RulesExport.DOOR_INTENSITY, c, ctxArgs(c), convert = intOf(RulesExport.DOOR_INTENSITY))
      ?.coerceAtLeast(0) ?: 0

  override fun advanceLag(c: RulesContext): Int =
    run(RulesExport.ADVANCE_LAG, c, ctxArgs(c), convert = lagOf(RulesExport.ADVANCE_LAG)) ?: 0

  override fun retreatLag(c: RulesContext): Int =
    run(RulesExport.RETREAT_LAG, c, ctxArgs(c), convert = lagOf(RulesExport.RETREAT_LAG)) ?: 0

  override fun retreatFailLag(c: RulesContext): Int =
    run(RulesExport.RETREAT_FAIL_LAG, c, ctxArgs(c), convert = lagOf(RulesExport.RETREAT_FAIL_LAG))
      ?: 0

  override fun fleeFailLag(c: RulesContext): Int =
    run(RulesExport.FLEE_FAIL_LAG, c, ctxArgs(c), convert = lagOf(RulesExport.FLEE_FAIL_LAG)) ?: 0

  override fun xpForKill(c: RulesContext): Long =
    run(RulesExport.XP_FOR_KILL, c, ctxArgs(c)) { v ->
      int(v, "rules.xp_for_kill return value").toLong()
    } ?: 0

  override fun xpShare(c: RulesContext): Map<Actor, Long> =
    run(RulesExport.XP_SHARE, c, ctxArgs(c)) { v ->
      val what = "rules.xp_share return value"
      val d = v as? Dict<*, *> ?: fail("$what: must be a dict, got ${typeName(v)}")
      d.entries.associate { (k, n) ->
        val a = k as? ActorView ?: fail("$what: keys must be actors, got ${typeName(k)}")
        val amount = int(n, "$what: values must be ints").toLong()
        if (amount < 0) fail("$what: values must be 0 or more")
        a.actor to amount
      }
    } ?: super.xpShare(c)

  override fun ownedBehaviors(c: RulesContext): List<Behavior>? {
    if (!defines(RulesExport.OWNED_BEHAVIORS)) return null
    val marker = { t: StarlarkThread ->
      t.setThreadLocal(OwnedBehaviorsMarker::class.java, OwnedBehaviorsMarker(pkg))
    }
    return runNamed(
      RulesExport.OWNED_BEHAVIORS.scriptName,
      Invocation(c, ctxArgs(c), onThread = marker),
    ) { v ->
      val what = "rules.owned_behaviors return value"
      val list = v as? StarlarkList<*> ?: fail("$what: must be a list, got ${typeName(v)}")
      list.map {
        (it as? BehaviorDecl)?.behavior
          ?: fail("$what: elements must be behaviors, got ${typeName(it)}")
      }
    }
  }

  override fun leashReturn(c: RulesContext): Boolean? {
    if (!defines(RulesExport.LEASH_RETURN)) return null
    return run(RulesExport.LEASH_RETURN, c, ctxArgs(c)) { v ->
      val what = "rules.leash_return return value"
      v as? Boolean ?: fail("$what: must be a bool, got ${typeName(v)}")
    }
  }

  override fun recognizesSkill(c: RulesContext): Boolean? {
    if (!defines(RulesExport.RECOGNIZES_SKILL)) return null
    return run(RulesExport.RECOGNIZES_SKILL, c, ctxArgs(c)) { v ->
      val what = "rules.recognizes_skill return value"
      v as? Boolean ?: fail("$what: must be a bool, got ${typeName(v)}")
    }
  }

  override fun pluginExport(name: String, type: String, c: RulesContext): Any? {
    if (!definesPluginExport(name)) return null
    return runNamed(name, Invocation(c, ctxArgs(c))) { v ->
      val what = "rules.$name return value"
      when (type) {
        "int" -> {
          if (v !is StarlarkInt) fail("$what: must be an int, got ${typeName(v)}")
          int(v, what).toLong()
        }
        "bool" -> v as? Boolean ?: fail("$what: must be a bool, got ${typeName(v)}")
        else -> fail("rules.$name has the unknown type '$type'")
      }
    }
  }

  override fun chainThink(c: RulesContext): Boolean? {
    if (!defines(RulesExport.CHAIN_THINK)) return null
    return run(RulesExport.CHAIN_THINK, c, ctxArgs(c)) { v ->
      val what = "rules.chain_think return value"
      v as? Boolean ?: fail("$what: must be a bool, got ${typeName(v)}")
    }
  }

  override fun factions(c: RulesContext): List<Label>? {
    if (!defines(RulesExport.FACTIONS)) return null
    return run(RulesExport.FACTIONS, c, ctxArgs(c)) { v ->
      val what = "rules.factions return value"
      val list = v as? StarlarkList<*> ?: fail("$what: must be a list, got ${typeName(v)}")
      list.map { elem ->
        val s = elem as? String ?: fail("$what: elements must be strings, got ${typeName(elem)}")
        val label = label(s, pkg, what)
        if (!c.world.protos.isFaction(label)) {
          val known =
            c.world.protos.factions.keys.map { it.v } + c.world.protos.mobs.keys.map { it.v }
          fail("$what: '$s' does not name a faction or mob prototype${didYouMean(label.v, known)}")
        }
        label
      }
    }
  }

  override fun disposition(c: RulesContext): Int? {
    if (!defines(RulesExport.DISPOSITION)) return null
    return run(RulesExport.DISPOSITION, c, ctxArgs(c)) { v ->
      int(v, "rules.disposition return value").coerceIn(STANDING_MIN, STANDING_MAX)
    }
  }

  override fun standingChange(c: RulesContext): List<StandingDelta>? {
    if (!defines(RulesExport.STANDING_CHANGE)) return null
    val marker = { t: StarlarkThread ->
      t.setThreadLocal(StandingChangeMarker::class.java, StandingChangeMarker(pkg, c.world.protos))
    }
    return runNamed(
      RulesExport.STANDING_CHANGE.scriptName,
      Invocation(c, ctxArgs(c), onThread = marker),
    ) { v ->
      val what = "rules.standing_change return value"
      val list = v as? StarlarkList<*> ?: fail("$what: must be a list, got ${typeName(v)}")
      if (list.size > 32) fail("$what: at most 32 deltas allowed, got ${list.size}")
      list.map {
        (it as? StandingDeltaValue)?.delta
          ?: fail("$what: elements must be standing_delta(...) values, got ${typeName(it)}")
      }
    }
  }

  override fun targetOrder(c: RulesContext): TargetChoice {
    if (!defines(RulesExport.TARGET_ORDER)) return TargetChoice.Default
    return run(RulesExport.TARGET_ORDER, c, ctxArgs(c)) { v ->
      val what = "rules.target_order return value"
      if (isNone(v)) {
        TargetChoice.Nobody
      } else if (v is ActorView) {
        val allowed = c.candidates.orEmpty()
        if (allowed.any { it === v.actor }) {
          TargetChoice.Pick(v.actor)
        } else {
          fail("$what: actor '${v.actor.name}' is not in ctx.candidates")
        }
      } else {
        fail("$what: expected an actor in ctx.candidates or None, got ${typeName(v)}")
      }
    } ?: TargetChoice.Default
  }

  override fun xpToLevel(c: RulesContext, level: Int): Long =
    run(RulesExport.XP_TO_LEVEL, c, ctxArgs(c, sInt(level))) { v ->
      int(v, "rules.xp_to_level return value").toLong().also {
        if (it < 1) fail("rules.xp_to_level must be at least 1")
      }
    } ?: Long.MAX_VALUE

  override fun onLevelUp(c: RulesContext): LevelUpResult? =
    run(RulesExport.ON_LEVEL_UP, c, ctxArgs(c)) { v ->
        if (isNone(v)) return@run LevelUp(null)
        val what = "rules.on_level_up return value"
        val fields =
          (v as? StructValue)?.fields
            ?: fail("$what: expected None or a struct, got ${typeName(v)}")
        for (k in fields.keys) if (k !in LEVEL_UP_FIELDS)
          fail("$what: unknown field '$k'${SpellChecker.didYouMean(k, LEVEL_UP_FIELDS)}")
        LevelUp(
          LevelUpResult(
            stats = typed(fields["stats"], TypedKind.STATS, "$what.stats"),
            max = typed(fields["max"], TypedKind.VITALS, "$what.max"),
            current = typed(fields["current"], TypedKind.VITALS, "$what.current"),
          )
        )
      }
      ?.result

  private class LevelUp(val result: LevelUpResult?)

  override fun onPlayerDeath(c: RulesContext): PlayerDeathOutcome =
    run(RulesExport.ON_PLAYER_DEATH, c, ctxArgs(c)) { v ->
      val what = "rules.on_player_death return value"
      val d = dict(v, what)
      for (k in d.keys) choice(k, what, DEATH_FIELDS)
      if ("xp_loss" in d && Feature.LEVELING !in c.world.features)
        fail(FeatureOwnership.refusal("$what[\"xp_loss\"]", Feature.LEVELING))
      if ("level_loss" in d && Feature.LEVELING !in c.world.features)
        fail(FeatureOwnership.refusal("$what[\"level_loss\"]", Feature.LEVELING))
      val respawn =
        label(
          str(d["respawn"], "rules.on_player_death[\"respawn\"]"),
          pkg,
          "rules.on_player_death[\"respawn\"]",
        )
      if (c.world.roomOrNull(respawn) == null)
        fail("rules.on_player_death: there is no room $respawn")
      val xpLoss = d["xp_loss"]?.let { int(it, "rules.on_player_death[\"xp_loss\"]") } ?: 0
      if (xpLoss < 0) fail("rules.on_player_death[\"xp_loss\"]: must be at least 0, got $xpLoss")
      val levelLoss = d["level_loss"]?.let { int(it, "rules.on_player_death[\"level_loss\"]") } ?: 0
      if (levelLoss < 0)
        fail("rules.on_player_death[\"level_loss\"]: must be at least 0, got $levelLoss")
      PlayerDeathOutcome(
        respawn,
        d["vitals"]?.let { typed(it, TypedKind.VITALS, "rules.on_player_death[\"vitals\"]") },
        xpLoss.toLong(),
        levelLoss,
      )
    }
      ?: PlayerDeathOutcome(
        (c.requireActor() as? Player)?.recallRoom ?: c.requireActor().room.label,
        null,
        0,
      )

  companion object {
    private val STARTING_FIELDS = listOf("stats", "max", "current", "senses")
    private val DEATH_FIELDS = listOf("respawn", "vitals", "xp_loss", "level_loss")
    private val LEVEL_UP_FIELDS = listOf("stats", "max", "current")
  }
}

/** The functions the files of `//rules` may export. [RULES_EXPORTS] documents each. */
enum class RulesExport(val scriptName: String) {
  EQUIPMENT_SLOTS("equipment_slots"),
  SLOT_FAMILIES("slot_families"),
  ACTOR_SLOTS("actor_slots"),
  SLOT_COST("slot_cost"),
  STARTING_STATS("starting_stats"),
  CONDITION("condition"),
  TO_HIT("to_hit"),
  DAMAGE("damage"),
  MITIGATE("mitigate"),
  INITIATIVE("initiative"),
  CAST_ALLOWED("cast_allowed"),
  INTERRUPT_CHECK("interrupt_check"),
  SKILL_CHECK("skill_check"),
  SKILL_POWER("skill_power"),
  RECOGNIZES_SKILL("recognizes_skill"),
  RETREAT_CHECK("retreat_check"),
  FLEE_CHANCE("flee_chance"),
  MOVE_COST("move_cost"),
  MOVE_PACE("move_pace"),
  REGEN("regen"),
  CARRY_LIMIT("carry_limit"),
  ENCUMBRANCE_LEVEL("encumbrance_level"),
  ENCUMBRANCE("encumbrance"),
  ITEM_POWER("item_power"),
  GET_LAG("get_lag"),
  ADVANCE_LAG("advance_lag"),
  RETREAT_LAG("retreat_lag"),
  RETREAT_FAIL_LAG("retreat_fail_lag"),
  FLEE_FAIL_LAG("flee_fail_lag"),
  XP_FOR_KILL("xp_for_kill"),
  XP_SHARE("xp_share"),
  OWNED_BEHAVIORS("owned_behaviors"),
  LEASH_RETURN("leash_return"),
  CHAIN_THINK("chain_think"),
  FACTIONS("factions"),
  DISPOSITION("disposition"),
  STANDING_CHANGE("standing_change"),
  TARGET_ORDER("target_order"),
  XP_TO_LEVEL("xp_to_level"),
  ON_LEVEL_UP("on_level_up"),
  ON_PLAYER_DEATH("on_player_death"),
  SENSE_COST("sense_cost"),
  CAN_PERCEIVE("can_perceive"),
  PERCEIVE_MESSAGE("perceive_message"),
  DOOR_INTENSITY("door_intensity"),
}

/** The docs of every [RulesExport]. */
internal val RULES_EXPORTS: List<RulesExportSpec> =
  listOf(
    RulesExportSpec(
      export = RulesExport.EQUIPMENT_SLOTS,
      signature = "equipment_slots()",
      returnType = "list[str]",
      ctxFields = emptyList(),
      doc = "Equipment slot names in display and modifier evaluation order.",
    ),
    RulesExportSpec(
      export = RulesExport.SLOT_FAMILIES,
      signature = "slot_families()",
      returnType = "dict[str, list[str]]",
      ctxFields = emptyList(),
      doc = "Slot families: a family name to its ordered concrete slots.",
    ),
    RulesExportSpec(
      export = RulesExport.ACTOR_SLOTS,
      signature = "actor_slots(ctx)",
      returnType = "list[str]",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "The slots ctx.actor has, a subset of equipment_slots().",
    ),
    RulesExportSpec(
      export = RulesExport.SLOT_COST,
      signature = "slot_cost(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "item", "room", "pulse", "rng"),
      doc = "How many slots of its family ctx.item takes up when ctx.actor wears it; at least 1.",
    ),
    RulesExportSpec(
      export = RulesExport.STARTING_STATS,
      signature = "starting_stats(ctx)",
      returnType = "struct(stats, max, current)",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc =
        "Numbers for a new character or spawned mob, as struct(stats = stats(...), max = vitals(...), current = vitals(...)); every field is optional and a current value left out equals the maximum.",
    ),
    RulesExportSpec(
      export = RulesExport.CONDITION,
      signature = "condition(ctx)",
      returnType = "str",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc =
        "The actor's life state: 'ok', 'incapacitated' or 'dead'. Asked after any change to one of its vitals and at each world tick.",
    ),
    RulesExportSpec(
      export = RulesExport.TO_HIT,
      signature = "to_hit(ctx)",
      returnType = "int",
      ctxFields =
        listOf(
          "actor",
          "attacker",
          "defender",
          "target",
          "weapon",
          "band",
          "skill",
          "room",
          "damage_type",
          "slot",
          "pulse",
          "rng",
        ),
      doc =
        "Hit chance in percent before modifiers, for an auto-attack swing or an enemy spell (ctx.skill set, no weapon or slot).",
    ),
    RulesExportSpec(
      export = RulesExport.DAMAGE,
      signature = "damage(ctx)",
      returnType = "int",
      ctxFields =
        listOf(
          "actor",
          "attacker",
          "defender",
          "target",
          "weapon",
          "band",
          "skill",
          "room",
          "damage_type",
          "slot",
          "pulse",
          "rng",
        ),
      doc = "Raw attack damage before modifiers and mitigation.",
    ),
    RulesExportSpec(
      export = RulesExport.MITIGATE,
      signature = "mitigate(ctx, amount, type)",
      returnType = "vitals",
      ctxFields =
        listOf(
          "actor",
          "attacker",
          "defender",
          "target",
          "weapon",
          "room",
          "damage_type",
          "pulse",
          "rng",
        ),
      doc = "The vital losses that amount damage of type causes the defender.",
    ),
    RulesExportSpec(
      export = RulesExport.INITIATIVE,
      signature = "initiative(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Combat round initiative score; higher acts first.",
    ),
    RulesExportSpec(
      export = RulesExport.CAST_ALLOWED,
      signature = "cast_allowed(ctx)",
      returnType = "None | denial",
      ctxFields = listOf("actor", "target", "skill", "band", "room", "pulse", "rng"),
      doc = "Returns None if casting is allowed, or ctx.deny(msg) to refuse.",
    ),
    RulesExportSpec(
      export = RulesExport.INTERRUPT_CHECK,
      signature = "interrupt_check(ctx, amount)",
      returnType = "bool",
      ctxFields = listOf("actor", "skill", "task", "room", "pulse", "rng"),
      doc =
        "True if a damage event with this total loss interrupts the cast being warmed up (ctx.skill) or the timed task (ctx.task).",
    ),
    RulesExportSpec(
      export = RulesExport.SKILL_CHECK,
      signature = "skill_check(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "target", "item", "skill", "event", "room", "pulse", "rng"),
      doc =
        "Percent chance that ctx.actor gets ctx.skill off. ctx.event names a skill_roll's purpose, such as 'craft'; a cast leaves it None.",
    ),
    RulesExportSpec(
      export = RulesExport.SKILL_POWER,
      signature = "skill_power(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "target", "item", "skill", "room", "pulse", "rng"),
      doc = "Percent strength of a successful cast; 100 is the skill as declared.",
    ),
    RulesExportSpec(
      export = RulesExport.RECOGNIZES_SKILL,
      signature = "recognizes_skill(ctx)",
      returnType = "bool",
      ctxFields =
        listOf("actor", "target", "source", "skill", "cast_target", "room", "pulse", "rng"),
      doc =
        "Whether ctx.actor, a player watching ctx.source cast ctx.skill, recognizes it without knowing it.",
      hasDefault = true,
    ),
    RulesExportSpec(
      export = RulesExport.RETREAT_CHECK,
      signature = "retreat_check(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "target", "room", "pulse", "rng"),
      doc = "Chance in percent to retreat along the track when pinned in melee.",
    ),
    RulesExportSpec(
      export = RulesExport.FLEE_CHANCE,
      signature = "flee_chance(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Chance in percent for a fleeing actor to escape through an exit.",
    ),
    RulesExportSpec(
      export = RulesExport.MOVE_COST,
      signature = "move_cost(ctx)",
      returnType = "vitals",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "The vitals spent when leaving a room.",
    ),
    RulesExportSpec(
      export = RulesExport.MOVE_PACE,
      signature = "move_pace(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "room", "exit", "pulse", "rng"),
      doc =
        "Hundredths of a pulse per room for ctx.actor walking through ctx.exit; 100 is one room per pulse.",
      hasDefault = true,
    ),
    RulesExportSpec(
      export = RulesExport.REGEN,
      signature = "regen(ctx)",
      returnType = "vitals",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "What each vital gains per world tick; negative values lose.",
    ),
    RulesExportSpec(
      export = RulesExport.CARRY_LIMIT,
      signature = "carry_limit(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "item", "room", "pulse", "rng"),
      doc = "Maximum weight the actor can carry without encumbrance.",
    ),
    RulesExportSpec(
      export = RulesExport.ENCUMBRANCE_LEVEL,
      signature = "encumbrance_level(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "limit", "carried", "room", "pulse", "rng"),
      doc = "The actor's encumbrance level, 0 for none; the world decides what the levels mean.",
    ),
    RulesExportSpec(
      export = RulesExport.ENCUMBRANCE,
      signature = "encumbrance(ctx)",
      returnType = "stats",
      ctxFields = listOf("actor", "limit", "carried", "encumbrance_level", "room", "pulse", "rng"),
      doc = "Stat deltas applied while the encumbrance level is above 0.",
    ),
    RulesExportSpec(
      export = RulesExport.ITEM_POWER,
      signature = "item_power(ctx)",
      returnType = "int",
      ctxFields =
        listOf("actor", "target", "item", "skill", "event", "affect", "room", "pulse", "rng"),
      doc = "Percent strength of what ctx.item does for ctx.actor: use or proc.",
    ),
    RulesExportSpec(
      export = RulesExport.GET_LAG,
      signature = "get_lag(ctx, band)",
      returnType = "int | None",
      ctxFields = listOf("actor", "item", "room", "pulse", "rng"),
      doc = "Wait-state pulses for picking up an item at band, or None if out of reach.",
    ),
    RulesExportSpec(
      export = RulesExport.ADVANCE_LAG,
      signature = "advance_lag(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "target", "band", "room", "pulse", "rng"),
      doc =
        "Recovery lag in pulses after advance. ctx.target is the actor approached; ctx.band is the band after the step.",
    ),
    RulesExportSpec(
      export = RulesExport.RETREAT_LAG,
      signature = "retreat_lag(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "target", "band", "room", "pulse", "rng"),
      doc =
        "Recovery lag after a successful retreat. ctx.target is the threat; ctx.band is the band after the step.",
    ),
    RulesExportSpec(
      export = RulesExport.RETREAT_FAIL_LAG,
      signature = "retreat_fail_lag(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "target", "band", "room", "pulse", "rng"),
      doc =
        "Recovery lag after a failed retreat_check. ctx.target is the pinner; ctx.band is \"melee\".",
    ),
    RulesExportSpec(
      export = RulesExport.FLEE_FAIL_LAG,
      signature = "flee_fail_lag(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc =
        "Recovery lag after a failed flee, whether the roll failed or the exit refused. ctx.target is None.",
    ),
    RulesExportSpec(
      export = RulesExport.XP_FOR_KILL,
      signature = "xp_for_kill(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "target", "room", "pulse", "rng"),
      doc = "Experience points awarded to actor for slaying target.",
    ),
    RulesExportSpec(
      export = RulesExport.XP_SHARE,
      signature = "xp_share(ctx)",
      returnType = "dict",
      ctxFields = listOf("actor", "target", "xp", "contributors", "room", "pulse", "rng"),
      doc = "Splits a kill's experience pool among actors.",
      hasDefault = true,
    ),
    RulesExportSpec(
      export = RulesExport.OWNED_BEHAVIORS,
      signature = "owned_behaviors(ctx)",
      returnType = "list",
      ctxFields = listOf("actor", "target", "behaviors", "room", "pulse", "rng"),
      doc =
        "The behaviors a mob runs while a player owns it; ctx.actor is the mob, ctx.target its owner.",
      hasDefault = true,
    ),
    RulesExportSpec(
      export = RulesExport.LEASH_RETURN,
      signature = "leash_return(ctx)",
      returnType = "bool",
      ctxFields = listOf("actor", "away", "past_leash", "room", "pulse", "rng"),
      doc = "Whether a leashed mob outside its home ground heads home this think.",
      hasDefault = true,
    ),
    RulesExportSpec(
      export = RulesExport.CHAIN_THINK,
      signature = "chain_think(ctx)",
      returnType = "bool",
      ctxFields = listOf("actor", "behavior", "command", "exit", "chain", "room", "pulse", "rng"),
      doc = "Whether ctx.actor, a mob, thinks again as soon as it can act.",
      hasDefault = true,
    ),
    RulesExportSpec(
      export = RulesExport.FACTIONS,
      signature = "factions(ctx)",
      returnType = "list[str]",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "The faction labels of ctx.actor.",
      hasDefault = true,
    ),
    RulesExportSpec(
      export = RulesExport.DISPOSITION,
      signature = "disposition(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "target", "disposition_parts", "room", "pulse", "rng"),
      doc = "What ctx.actor thinks of ctx.target, an int in -100..100.",
      hasDefault = true,
    ),
    RulesExportSpec(
      export = RulesExport.STANDING_CHANGE,
      signature = "standing_change(ctx)",
      returnType = "list",
      ctxFields =
        listOf(
          "actor",
          "target",
          "event",
          "amount",
          "item",
          "affect",
          "helped",
          "pet",
          "room",
          "pulse",
          "rng",
        ),
      doc = "The changes a standing event makes, as a list of standing_delta(...) values.",
      hasDefault = true,
    ),
    RulesExportSpec(
      export = RulesExport.TARGET_ORDER,
      signature = "target_order(ctx)",
      returnType = "actor",
      ctxFields =
        listOf(
          "actor",
          "behavior",
          "prefer",
          "candidates",
          "current",
          "current_since",
          "dispositions",
          "ally",
          "room",
          "pulse",
          "rng",
        ),
      doc = "Which candidate ctx.actor attacks or switches to, or None to propose nothing.",
      hasDefault = true,
    ),
    RulesExportSpec(
      export = RulesExport.XP_TO_LEVEL,
      signature = "xp_to_level(ctx, level)",
      returnType = "int",
      ctxFields = listOf("actor"),
      doc = "The total XP at which the given level ends for the actor.",
    ),
    RulesExportSpec(
      export = RulesExport.ON_LEVEL_UP,
      signature = "on_level_up(ctx)",
      returnType = "struct | None",
      ctxFields = listOf("actor", "pulse", "rng"),
      doc =
        "Stat and vital deltas awarded when an actor reaches a new level, as a struct(stats = stats(...), max = vitals(...), current = vitals(...)) or None.",
    ),
    RulesExportSpec(
      export = RulesExport.ON_PLAYER_DEATH,
      signature = "on_player_death(ctx)",
      returnType = "dict[str, any]",
      ctxFields = listOf("actor", "target", "room", "pulse", "rng"),
      doc =
        "Player death outcome as {'respawn': label, 'vitals': vitals(...), 'xp_loss': int, 'level_loss': int}; vitals sets the respawned character's current vitals.",
    ),
    RulesExportSpec(
      export = RulesExport.SENSE_COST,
      signature = "sense_cost(ctx)",
      returnType = "int",
      ctxFields = listOf("room", "exit", "channel", "pulse", "rng"),
      doc =
        "What a perceivable event on ctx.channel loses crossing ctx.exit into ctx.room, one hop toward the listeners. The price is added to the event's cost; a negative price blocks the hop. ctx.actor is None.",
    ),
    RulesExportSpec(
      export = RulesExport.CAN_PERCEIVE,
      signature = "can_perceive(ctx)",
      returnType = "bool",
      ctxFields =
        listOf(
          "actor",
          "target",
          "source",
          "room",
          "source_room",
          "via_exit",
          "channel",
          "event",
          "intensity",
          "cost",
          "text",
          "pulse",
          "rng",
        ),
      doc =
        "Whether ctx.actor, a candidate listener, notices the event. Asked for every simulated actor in a room the event reaches, the room it happened in included, except the actor who caused it.",
    ),
    RulesExportSpec(
      export = RulesExport.PERCEIVE_MESSAGE,
      signature = "perceive_message(ctx)",
      returnType = "str | None",
      ctxFields =
        listOf(
          "actor",
          "target",
          "source",
          "room",
          "source_room",
          "via_exit",
          "channel",
          "event",
          "intensity",
          "cost",
          "text",
          "pulse",
          "rng",
        ),
      doc =
        "What a player in another room is told about an event they can_perceive, or None for nothing. The room of the event is told as it happens and never asked.",
    ),
    RulesExportSpec(
      export = RulesExport.DOOR_INTENSITY,
      signature = "door_intensity(ctx)",
      returnType = "int",
      ctxFields = listOf("actor", "room", "action", "tags", "exit", "item", "pulse", "rng"),
      doc =
        "The sound intensity of ctx.action ('open', 'close', 'lock' or 'unlock') on a door (ctx.exit) or container (ctx.item); 0 or less is silent.",
    ),
  )

/** The modifier points in the rules modifier chain. */
internal val MODIFIER_POINTS: List<ModifierPointSpec> =
  listOf(
    ModifierPointSpec(
      point = ModifierPoint.TO_HIT,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields =
        listOf(
          "actor",
          "attacker",
          "defender",
          "target",
          "weapon",
          "band",
          "skill",
          "room",
          "damage_type",
          "slot",
          "pulse",
          "rng",
        ),
      doc = "Adjusts hit chance in percent, for swings and enemy spells.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.DAMAGE,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields =
        listOf(
          "actor",
          "attacker",
          "defender",
          "target",
          "weapon",
          "band",
          "skill",
          "room",
          "damage_type",
          "slot",
          "pulse",
          "rng",
        ),
      doc = "Adjusts damage before mitigation, for auto-attacks and the damage(...) effect.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.BONUS_DAMAGE,
      signature = "fn(ctx, base)",
      valueType = "damages",
      ctxFields =
        listOf(
          "actor",
          "attacker",
          "defender",
          "target",
          "weapon",
          "band",
          "skill",
          "room",
          "damage_type",
          "slot",
          "pulse",
          "rng",
        ),
      doc =
        "Adds typed damage parts to an auto-attack hit or a damage(...) effect, on the attacker's chain; " +
          "each part is mitigated with its own type and applied with the hit as one damage event.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.DEFEND_HIT,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields =
        listOf(
          "actor",
          "attacker",
          "defender",
          "target",
          "weapon",
          "band",
          "skill",
          "room",
          "damage_type",
          "slot",
          "pulse",
          "rng",
        ),
      doc =
        "Adjusts an auto-attack's or enemy spell's hit chance on the defender's chain, after the attacker's to_hit chain. ctx.defender is the actor whose modifiers run; ctx.attacker is still the swinger or caster.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.RECEIVE_DAMAGE,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields =
        listOf(
          "actor",
          "attacker",
          "defender",
          "target",
          "weapon",
          "band",
          "skill",
          "room",
          "damage_type",
          "slot",
          "pulse",
          "rng",
        ),
      doc =
        "Adjusts the raw damage of a hit or damage(...) effect on the defender's chain, after the attacker's damage chain and before mitigate. A negative result counts as 0.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.MITIGATE,
      signature = "fn(ctx, base)",
      valueType = "vitals",
      ctxFields =
        listOf(
          "actor",
          "attacker",
          "defender",
          "target",
          "weapon",
          "band",
          "skill",
          "room",
          "damage_type",
          "slot",
          "pulse",
          "rng",
        ),
      doc =
        "Adjusts the vital losses mitigate returned, on the defender's chain. A negative loss is a gain to that vital.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.INITIATIVE,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Adjusts combat round initiative.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.CAST_ALLOWED,
      signature = "fn(ctx, base)",
      valueType = "None | denial",
      ctxFields = listOf("actor", "target", "skill", "band", "room", "pulse", "rng"),
      doc = "Returns None to allow casting, or ctx.deny(msg) to refuse.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.SKILL_CHECK,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields = listOf("actor", "target", "item", "skill", "room", "pulse", "rng"),
      doc = "Adjusts the percent chance that ctx.actor gets ctx.skill off.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.SKILL_POWER,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields = listOf("actor", "target", "item", "skill", "room", "pulse", "rng"),
      doc = "Adjusts the percent strength of a successful cast. The final result is clamped to 0.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.MOVE_COST,
      signature = "fn(ctx, base)",
      valueType = "vitals",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Adjusts the vitals spent for leaving a room.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.MOVE_PACE,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields = listOf("actor", "room", "exit", "pulse", "rng"),
      doc =
        "Adjusts the walking pace in hundredths of a pulse per room. The final result is clamped to at least 100 / MOVES_PER_PULSE_MAX (25).",
    ),
    ModifierPointSpec(
      point = ModifierPoint.REGEN,
      signature = "fn(ctx, base)",
      valueType = "vitals",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc = "Adjusts the per-tick regeneration of each vital.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.ADVANCE_LAG,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields = listOf("actor", "target", "band", "room", "pulse", "rng"),
      doc =
        "Adjusts the recovery lag in pulses after advance. ctx.target is the actor approached; ctx.band is the band after the step. The final result is clamped to 0.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.RETREAT_LAG,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields = listOf("actor", "target", "band", "room", "pulse", "rng"),
      doc =
        "Adjusts the recovery lag after a successful retreat. ctx.target is the threat; ctx.band is the band after the step. The final result is clamped to 0.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.RETREAT_FAIL_LAG,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields = listOf("actor", "target", "band", "room", "pulse", "rng"),
      doc =
        "Adjusts the recovery lag after a failed retreat_check. ctx.target is the pinner; ctx.band is \"melee\". The final result is clamped to 0.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.FLEE_FAIL_LAG,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields = listOf("actor", "room", "pulse", "rng"),
      doc =
        "Adjusts the recovery lag after a failed flee. ctx.target and ctx.band are None. The final result is clamped to 0.",
    ),
    ModifierPointSpec(
      point = ModifierPoint.ITEM_POWER,
      signature = "fn(ctx, base)",
      valueType = "int",
      ctxFields =
        listOf("actor", "target", "item", "skill", "event", "affect", "room", "pulse", "rng"),
      doc =
        "Adjusts the percent strength of what ctx.item does for ctx.actor: use or proc. The final result is clamped to 0.",
    ),
  )
