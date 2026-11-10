package gups.script

import gups.core.Label
import gups.core.model.Proc
import gups.core.model.ProcOutcome
import gups.core.model.ProcTarget
import gups.core.model.ProcTrigger
import gups.core.world.Feature
import gups.core.world.Features
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.StarlarkThread

/**
 * Builds `proc(...)` and `outcome(...)` values. A proc is either one inline outcome or a table of
 * weighted `outcome(...)` values; both share the same skill, effect, target and message rules.
 */
internal class ProcParser(
  private val features: Features,
  private val loadCtx: LoadCtx?,
  private val pkg: String?,
  private val thread: StarlarkThread,
) {
  fun outcome(d: ParsedDecl): ProcOutcome {
    if (d["skill"] != null && d["effect"] != null) fail("outcome(): give skill or effect, not both")
    return single(d, d.percent("weight") ?: 100)
  }

  fun proc(d: ParsedDecl): Proc {
    val on = choice(d["on"], "proc(on)", listOf("hit", "struck", "avoid"))
    val chance = d.percent("chance") ?: 100
    val outcomes = if (d["outcomes"] != null) outcomeTable(d) else listOf(inline(d))
    return Proc(on = ProcTrigger.valueOf(on.uppercase()), chance = chance, outcomes = outcomes)
  }

  private fun inline(d: ParsedDecl): ProcOutcome {
    if ((d["skill"] == null) == (d["effect"] == null)) {
      fail("proc(): give one of skill, effect or outcomes")
    }
    return single(d, 100)
  }

  private fun outcomeTable(d: ParsedDecl): List<ProcOutcome> {
    if (listOf("skill", "effect", "target", "message").any { d[it] != null }) {
      fail("proc(outcomes): can't be combined with skill, effect, target or message")
    }
    val raw = d["outcomes"]
    val list = raw as? Sequence<*> ?: fail("proc(outcomes): expected list, got ${typeName(raw)}")
    if (list.isEmpty()) fail("proc(outcomes): must not be empty")
    val outcomes =
      list.mapIndexed { i, elem ->
        (elem as? OutcomeDecl)?.outcome
          ?: fail("proc(outcomes): element ${i + 1} must be an outcome(...) value")
      }
    if (outcomes.sumOf { it.weight } > 100) fail("proc(outcomes): weights add up to more than 100")
    return outcomes
  }

  private fun single(d: ParsedDecl, weight: Int): ProcOutcome =
    ProcOutcome(
      weight = weight,
      skill = d["skill"]?.let { skillLabel(it, d) },
      effect = d["effect"]?.let { effect(it, d) },
      target = d.choice("target")?.let { ProcTarget.valueOf(it.uppercase()) },
      message = d["message"]?.let { message(it, d) },
    )

  private fun skillLabel(raw: Any, d: ParsedDecl): Label {
    if (Feature.SKILLS !in features) fail(FeatureOwnership.refusal("proc(skill)", Feature.SKILLS))
    val label = label(str(raw, d.what("skill")), pkg, d.what("skill"))
    loadCtx?.ref(label, RefKind.SKILL, thread)
    return label
  }

  private fun effect(raw: Any, d: ParsedDecl) =
    effectValue(raw, d.what("effect")).also {
      EffectChecks.checkProc(it, d.what("effect"), loadCtx, thread)
    }

  private fun message(raw: Any, d: ParsedDecl): String =
    str(raw, d.what("message")).also {
      if (it.isBlank()) fail("${d.fn}(message): must not be blank")
    }
}
