package gups.core.world

import gups.core.model.Actor
import gups.core.model.Effect
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.Proc
import gups.core.model.ProcOutcome
import gups.core.model.ProcTarget
import gups.core.model.ProcTrigger
import gups.core.model.SkillRange
import gups.core.model.TargetKind
import gups.core.rules.RulesContext
import java.util.Locale

private class ProcSource(
  val trace: String,
  val display: String,
  val procs: List<Proc>,
  val stillThere: () -> Boolean,
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
      )
  }

  for (src in sources) {
    if (!src.stillThere()) continue
    for (proc in src.procs) {
      if (proc.on != trigger) continue
      if (!bearer.inWorld) return
      val r = rng.fraction()
      if (r >= proc.chance) {
        traceRules(
          bearer,
          "proc ${src.trace} ${trigger.word} roll ${"%.2f".format(Locale.ROOT, r)} → no proc",
        )
        continue
      }
      val outcome =
        if (proc.outcomes.size == 1 && proc.outcomes[0].weight >= 1.0) {
          proc.outcomes[0]
        } else {
          val p = rng.fraction()
          var total = 0.0
          var chosen: ProcOutcome? = null
          for (o in proc.outcomes) {
            total += o.weight
            if (total > p) {
              chosen = o
              break
            }
          }
          if (chosen == null) {
            traceRules(
              bearer,
              "proc ${src.trace} ${trigger.word} roll ${"%.2f".format(Locale.ROOT, r)} → nothing",
            )
            continue
          }
          chosen
        }
      val result = resolveProcOutcome(bearer, other, src, outcome)
      traceRules(
        bearer,
        "proc ${src.trace} ${trigger.word} roll ${"%.2f".format(Locale.ROOT, r)} → $result",
      )
    }
  }
}

private fun World.resolveProcOutcome(
  bearer: Actor,
  other: Actor,
  src: ProcSource,
  outcome: ProcOutcome,
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
  if (outcome.skill == null && outcome.effect == null) return "nothing"

  val targetKind =
    outcome.target
      ?: when {
        skill != null ->
          when (skill.target) {
            TargetKind.ENEMY -> ProcTarget.ENEMY
            TargetKind.SELF,
            TargetKind.ALLY -> ProcTarget.SELF
            TargetKind.NONE -> ProcTarget.NONE
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
    sendProcMessages(bearer, src, outcome)
    val recipients =
      if (skill.range == SkillRange.ROOM)
        bearer.room.actors.filter { it !== bearer && isSimulated(it) }
      else listOf(recipient)
    for (each in recipients) {
      if (skill.target == TargetKind.ENEMY && each != null && each !== bearer) {
        startCombat(bearer, each)
      }
      skill.effect?.let { applyEffect(it, bearer, each, skill) }
      skill.resolve?.resolve(this, bearer, each, null)
    }
    return skill.label.toString()
  }

  val effect = outcome.effect!!
  sendProcMessages(bearer, src, outcome)
  applyEffect(effect, bearer, recipient, null)
  return primitiveName(effect)
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
