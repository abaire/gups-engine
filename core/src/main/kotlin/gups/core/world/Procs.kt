package gups.core.world

import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.Effect
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Proc
import gups.core.model.ProcOutcome
import gups.core.model.ProcTarget
import gups.core.model.ProcTrigger
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.TargetKind
import gups.core.rules.RulesContext

private class ProcSource(
  val trace: String,
  val display: String,
  val procs: List<Proc>,
  val stillThere: () -> Boolean,
  /** The item behind an item or item-affect proc, else null. */
  val item: Item? = null,
  /** The affect behind the proc: an actor affect, or an item's instance affect; null otherwise. */
  val affect: Affect? = null,
)

/**
 * Fires [bearer]'s procs for [trigger] in one auto-attack swing against [other]. [weapon] is the
 * swing's weapon; on a hit, worn weapons other than it contribute nothing.
 */
internal fun World.fireProcs(trigger: ProcTrigger, bearer: Actor, other: Actor, weapon: Item?) {
  val sources = mutableListOf<ProcSource>()
  for (a in bearer.affects.sortedBy { it.id }) {
    if (a.procs.isNotEmpty()) {
      sources +=
        ProcSource(
          trace = "affect:${a.name}",
          display = a.name.replace('_', ' '),
          procs = a.procs,
          stillThere = { a in bearer.affects },
          item = null,
          affect = a,
        )
    }
  }
  for (step in bearer.heldEffects.procSteps) {
    if (
      trigger == ProcTrigger.HIT &&
        step.item.proto.dice != null &&
        step.item.location is ItemLocation.Equipped &&
        step.item !== weapon
    ) {
      continue
    }
    sources +=
      ProcSource(
        trace = step.source,
        display = step.item.name,
        procs = step.procs,
        stillThere = { custodian(step.item) === bearer },
        item = step.item,
        affect = step.affect,
      )
  }

  for (src in sources) {
    if (!src.stillThere()) continue
    for (proc in src.procs) {
      if (proc.on != trigger) continue
      if (!bearer.inWorld) return
      val rolled = proc.chance in 1..99
      val r = if (rolled) rng.int(1, 100) else 0
      val fired = if (rolled) r <= proc.chance else proc.chance >= 100
      val rollText = if (rolled) "roll $r" else "auto"
      if (!fired) {
        traceRules(bearer, "proc ${src.trace} ${trigger.word} $rollText → no proc")
        continue
      }
      val outcome =
        if (proc.outcomes.size == 1 && proc.outcomes[0].weight >= 100) {
          proc.outcomes[0]
        } else {
          val p = rng.int(1, 100)
          var total = 0
          var chosen: ProcOutcome? = null
          for (o in proc.outcomes) {
            total += o.weight
            if (p <= total) {
              chosen = o
              break
            }
          }
          if (chosen == null) {
            traceRules(bearer, "proc ${src.trace} ${trigger.word} $rollText → nothing")
            continue
          }
          chosen
        }
      val result = resolveProcOutcome(bearer, other, src, outcome, trigger)
      traceRules(bearer, "proc ${src.trace} ${trigger.word} $rollText → $result")
    }
  }
}

private fun World.resolveProcOutcome(
  bearer: Actor,
  other: Actor,
  src: ProcSource,
  outcome: ProcOutcome,
  trigger: ProcTrigger,
): String {
  val skill = outcome.skill?.let { protos.skills[it] }
  if (outcome.skill != null && skill == null) {
    diagnose("script", "proc on ${src.trace}: unknown skill ${outcome.skill}")
    return "nothing"
  }
  if (skill?.target == TargetKind.ITEM) {
    diagnose("script", "proc on ${src.trace}: item skill ${skill.label} can't be a proc")
    return "nothing"
  }
  if (skill?.target == TargetKind.ROOM) {
    diagnose("script", "proc on ${src.trace}: room skill ${skill.label} can't be a proc")
    return "nothing"
  }
  if (outcome.skill == null && outcome.effect == null) return "nothing"

  val targetKind =
    outcome.target
      ?: when {
        skill != null ->
          when (skill.target) {
            TargetKind.ENEMY -> ProcTarget.ENEMY
            TargetKind.SELF,
            TargetKind.ALLY -> ProcTarget.SELF
            TargetKind.NONE,
            TargetKind.ROOM -> ProcTarget.NONE
            TargetKind.ITEM -> ProcTarget.ENEMY
          }
        else -> ProcTarget.ENEMY
      }

  val recipient =
    when (targetKind) {
      ProcTarget.ENEMY -> {
        if (!other.inWorld || other.room !== bearer.room) return "target gone"
        other
      }
      ProcTarget.SELF -> bearer
      ProcTarget.NONE -> null
    }

  if (skill != null) {
    if (castAllowed(RulesContext(this, bearer, recipient, skill = skill)) != null) {
      return "cast_allowed denied"
    }
    val power = computeProcPower(bearer, other, src, skill, trigger)
    sendProcMessages(bearer, src, outcome)
    val recipients =
      if (skill.range == SkillRange.ROOM)
        bearer.room.actors.filter { it !== bearer && isSimulated(it) }
      else listOf(recipient)
    for (each in recipients) {
      if (skill.target == TargetKind.ENEMY && each != null && each !== bearer) {
        startCombat(bearer, each)
      }
      skill.effect?.let { applyEffect(it, bearer, each, skill, power = power) }
      skill.resolve?.resolve(this, bearer, each, null, power)
    }
    return skill.label.toString()
  }

  val effect = outcome.effect!!
  val power = computeProcPower(bearer, other, src, null, trigger)
  sendProcMessages(bearer, src, outcome)
  applyEffect(effect, bearer, recipient, null, power = power)
  return primitiveName(effect)
}

private fun World.computeProcPower(
  bearer: Actor,
  other: Actor,
  src: ProcSource,
  skill: SkillProto?,
  trigger: ProcTrigger,
): Int =
  when {
    src.item == null && src.affect != null -> src.affect.power
    src.item != null -> {
      val ip =
        itemPowerChain(
          RulesContext(
            this,
            bearer,
            other,
            item = src.item,
            skill = skill,
            event = trigger.word,
            affect = src.affect,
          )
        )
      src.affect?.let { (it.power.toLong() * ip / 100).toInt() } ?: ip
    }
    else -> 100
  }

private fun World.sendProcMessages(bearer: Actor, src: ProcSource, outcome: ProcOutcome) {
  send(bearer, outcome.message ?: msg(CombatMsg.PROC_SELF, "source" to src.display))
  tellSight(
    bearer.room,
    "proc",
    bearer,
    msg(CombatMsg.PROC_ROOM, "actor" to bearer.name.cap()),
    bearer,
  )
}

private fun primitiveName(e: Effect): String =
  when (e) {
    is Effect.Damage -> "damage"
    is Effect.Heal -> "heal"
    is Effect.ApplyAffect -> "apply_affect"
    is Effect.Dispel -> "dispel"
    is Effect.Knockback -> "knockback"
    is Effect.Pull -> "pull"
    is Effect.Stun -> "stun"
    is Effect.Summon -> "summon"
    is Effect.Sequence -> "sequence"
    is Effect.Plugin -> "${e.pluginId}.${e.name}"
  }
