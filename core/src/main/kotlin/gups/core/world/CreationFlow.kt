package gups.core.world

import gups.core.Label
import gups.core.model.CreationAttempt
import gups.core.model.CreationChoice
import gups.core.model.CreationOption
import gups.core.model.CreationProvider
import gups.core.model.CreationStep
import gups.core.model.Player
import gups.core.model.StepReply

sealed interface CreationOutcome {
  data class Created(val attempt: CreationAttempt) : CreationOutcome

  data class Completed(val answers: Map<String, String>) : CreationOutcome

  object Cancelled : CreationOutcome

  /** Back from the first choice to the name prompt, with the attempt so far. */
  data class BackToName(val attempt: CreationAttempt) : CreationOutcome

  object ProviderFailed : CreationOutcome
}

/**
 * Handles character creation steps over a session: choices, custom steps, prompts, answer matching,
 * confirmation, and Char.Creation GMCP events.
 */
class CreationFlow(
  private val world: World,
  private val session: Session,
  private val name: String,
  private val newCharacter: Boolean,
  private val attempt: CreationAttempt,
  private val onOutcome: (CreationOutcome) -> Unit,
) {
  private val provider: CreationProvider =
    checkNotNull(world.creation) { "CreationFlow started without creation provider" }

  val answers: MutableMap<String, String>
    get() = attempt.answers

  private val history = mutableListOf<String>()
  private var currentStep: Step = Step.Confirm

  private sealed interface Step {
    data class Choice(val choiceId: String) : Step

    data class Custom(val stepId: String) : Step

    object Confirm : Step
  }

  fun start() {
    advance()
  }

  fun input(line: String) {
    val trimmed = line.trim()
    when (val step = currentStep) {
      is Step.Choice -> handleChoiceInput(step.choiceId, trimmed)
      is Step.Custom -> handleCustomInput(step.stepId, trimmed)
      Step.Confirm -> handleConfirmInput(trimmed)
    }
  }

  private fun answersBefore(choiceId: String): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    for (c in provider.choices) {
      if (c.id == choiceId) break
      val ans = answers[c.id] ?: continue
      map[c.id] = ans
    }
    return map
  }

  private fun clearAnswersFrom(choiceId: String) {
    var found = false
    for (c in provider.choices) {
      if (c.id == choiceId) found = true
      if (found) answers.remove(c.id)
    }
  }

  private fun clearAnswersAfter(choiceId: String) {
    var found = false
    for (c in provider.choices) {
      if (found) answers.remove(c.id)
      if (c.id == choiceId) found = true
    }
  }

  private fun availableOptions(
    choice: CreationChoice,
    before: Map<String, String>,
  ): List<CreationOption>? {
    val avail =
      try {
        provider.available(world, choice.id, before)
      } catch (e: Exception) {
        world.diagnose("plugin", "creation provider available() failed: ${e.message}", e)
        return null
      }
    return choice.options.filter { it.id in avail }
  }

  private fun advance() {
    val answersSoFar = LinkedHashMap<String, String>()
    for (c in provider.choices) {
      val options = availableOptions(c, answersSoFar)
      if (options == null) {
        endFlow(CreationOutcome.ProviderFailed)
        return
      }
      if (options.isEmpty()) {
        answers.remove(c.id)
        continue
      }
      val currentAns = answers[c.id]
      if (currentAns != null && options.any { it.id == currentAns }) {
        answersSoFar[c.id] = currentAns
        continue
      }
      // Needs answering
      history.add(c.id)
      askChoice(c, options, answersSoFar)
      return
    }

    if (newCharacter) {
      for (step in provider.steps) {
        val applies =
          try {
            step.applies(world, attempt)
          } catch (e: Throwable) {
            world.diagnose("plugin", "creation step '${step.id}' applies failed: ${e.message}", e)
            endFlow(CreationOutcome.ProviderFailed)
            return
          }
        if (!applies) continue
        if (step.id in attempt.finished) continue
        history.add(step.id)
        showStep(step)
        return
      }
    }

    // All available choices and steps answered: confirmation step
    promptConfirm()
  }

  private fun askChoice(choiceId: String) {
    val choice = provider.choices.first { it.id == choiceId }
    val before = answersBefore(choiceId)
    val options = availableOptions(choice, before)
    if (options == null) {
      endFlow(CreationOutcome.ProviderFailed)
      return
    }
    history.add(choiceId)
    askChoice(choice, options, before)
  }

  private fun askChoice(
    choice: CreationChoice,
    options: List<CreationOption>,
    before: Map<String, String>,
  ) {
    currentStep = Step.Choice(choice.id)
    val maxLabelWidth = options.maxOfOrNull { it.label.length } ?: 0
    val lines = mutableListOf<String>()
    lines += choice.prompt
    options.forEachIndexed { i, opt ->
      val pad = opt.label.padEnd(maxLabelWidth)
      val summary = if (opt.summary.isNotEmpty()) "   ${opt.summary}" else ""
      lines += "  ${i + 1}. $pad$summary"
    }
    lines += world.msg(LoginMsg.CREATION_HINT)
    world.sendSession(session, lines.joinToString("\n"))
    world.flush(session)
    session.sink.prompt(world.msg(LoginMsg.CREATION_PROMPT))

    sendGmcp(
      mapOf(
        "name" to name,
        "step" to "choice",
        "choice" to
          mapOf(
            "id" to choice.id,
            "title" to choice.title,
            "prompt" to choice.prompt,
            "options" to
              options.map { opt ->
                mapOf(
                  "id" to opt.id,
                  "label" to opt.label,
                  "summary" to opt.summary,
                  "help" to opt.help,
                )
              },
          ),
        "custom" to null,
        "answers" to buildAnswersGmcp(before),
        "summaries" to emptyList<Map<String, Any?>>(),
      )
    )
  }

  private fun showStep(step: CreationStep, message: String? = null) {
    currentStep = Step.Custom(step.id)
    if (message != null) {
      world.sendSession(session, message)
    }
    val view =
      try {
        step.show(world, attempt)
      } catch (e: Throwable) {
        world.diagnose("plugin", "creation step '${step.id}' show failed: ${e.message}", e)
        endFlow(CreationOutcome.ProviderFailed)
        return
      }
    world.sendSession(session, view.text)
    world.flush(session)
    session.sink.prompt(world.msg(LoginMsg.CREATION_PROMPT))

    sendGmcp(
      mapOf(
        "name" to name,
        "step" to "custom",
        "choice" to null,
        "custom" to mapOf("id" to step.id, "title" to step.title, "data" to view.gmcp),
        "answers" to buildAnswersGmcp(answers),
        "summaries" to emptyList<Map<String, Any?>>(),
      )
    )
  }

  private fun handleCustomInput(stepId: String, text: String) {
    val step = provider.steps.first { it.id == stepId }
    when {
      text.equals("cancel", ignoreCase = true) -> endFlow(CreationOutcome.Cancelled)
      text.equals("back", ignoreCase = true) -> {
        history.removeLast() // this step
        if (history.isNotEmpty()) {
          val prev = history.removeLast()
          val prevStep = provider.steps.firstOrNull { it.id == prev }
          if (prevStep != null) {
            attempt.finished.remove(prev)
            history.add(prev)
            showStep(prevStep)
          } else {
            reask(prev)
          }
        } else {
          if (newCharacter) endFlow(CreationOutcome.BackToName(attempt))
          else endFlow(CreationOutcome.Cancelled)
        }
      }
      else -> {
        val reply =
          try {
            step.input(world, attempt, text)
          } catch (e: Throwable) {
            world.diagnose("plugin", "creation step '${step.id}' input failed: ${e.message}", e)
            endFlow(CreationOutcome.ProviderFailed)
            return
          }
        when (reply) {
          is StepReply.Stay -> showStep(step, reply.message)
          StepReply.Done -> {
            attempt.finished.add(step.id)
            advance()
          }
        }
      }
    }
  }

  private fun promptConfirm() {
    currentStep = Step.Confirm
    val summaries = mutableListOf<Map<String, String>>()
    val summaryStrings = mutableListOf<String>()
    if (newCharacter) {
      for (step in provider.steps) {
        val applies =
          try {
            step.applies(world, attempt)
          } catch (e: Throwable) {
            world.diagnose("plugin", "creation step '${step.id}' applies failed: ${e.message}", e)
            endFlow(CreationOutcome.ProviderFailed)
            return
          }
        if (!applies) continue
        val sum =
          try {
            step.summary(world, attempt)
          } catch (e: Throwable) {
            world.diagnose("plugin", "creation step '${step.id}' summary failed: ${e.message}", e)
            endFlow(CreationOutcome.ProviderFailed)
            return
          }
        if (sum != null) {
          summaries += mapOf("step" to step.id, "title" to step.title, "text" to sum)
          summaryStrings += "${step.title} $sum"
        }
      }
    }

    val answersList = mutableListOf<String>()
    for (c in provider.choices) {
      val optId = answers[c.id] ?: continue
      val opt = c.options.firstOrNull { it.id == optId } ?: continue
      answersList += "${c.title} ${opt.label}"
    }
    answersList += summaryStrings
    val answersStr = answersList.joinToString(", ")
    val confirmText = world.msg(LoginMsg.CREATION_CONFIRM, "name" to name, "answers" to answersStr)
    world.sendSession(session, confirmText)
    world.flush(session)
    session.sink.prompt(world.msg(LoginMsg.CREATION_PROMPT))

    sendGmcp(
      mapOf(
        "name" to name,
        "step" to "confirm",
        "choice" to null,
        "custom" to null,
        "answers" to buildAnswersGmcp(answers),
        "summaries" to summaries,
      )
    )
  }

  private fun handleChoiceInput(choiceId: String, text: String) {
    val choice = provider.choices.first { it.id == choiceId }
    val before = answersBefore(choiceId)
    val options = availableOptions(choice, before)
    if (options == null) {
      endFlow(CreationOutcome.ProviderFailed)
      return
    }

    when {
      text.equals("cancel", ignoreCase = true) -> endFlow(CreationOutcome.Cancelled)
      text.equals("back", ignoreCase = true) -> {
        if (history.size <= 1) {
          if (newCharacter) endFlow(CreationOutcome.BackToName(attempt))
          else endFlow(CreationOutcome.Cancelled)
        } else {
          history.removeLast() // current choice
          val prevChoiceId = history.removeLast()
          clearAnswersFrom(prevChoiceId)
          attempt.answersChanged()
          askChoice(prevChoiceId)
        }
      }
      text.equals("help", ignoreCase = true) -> {
        world.sendSession(session, world.msg(LoginMsg.CREATION_HINT))
        world.flush(session)
        session.sink.prompt(world.msg(LoginMsg.CREATION_PROMPT))
      }
      text.startsWith("help ", ignoreCase = true) -> {
        val target = text.substring(5).trim()
        val opt = matchOption(target, options)
        if (opt != null) {
          val h = opt.help.ifEmpty { opt.summary }
          val out = if (h.isNotEmpty()) "${opt.label}\n$h" else opt.label
          world.sendSession(session, out)
        } else {
          world.sendSession(
            session,
            world.msg(LoginMsg.CREATION_NO_SUCH_OPTION, "answer" to target),
          )
        }
        world.flush(session)
        session.sink.prompt(world.msg(LoginMsg.CREATION_PROMPT))
      }
      else -> {
        val matched = matchOption(text, options)
        if (matched == null) {
          world.sendSession(session, world.msg(LoginMsg.CREATION_NO_SUCH_OPTION, "answer" to text))
          askChoice(choice, options, before)
        } else {
          answers[choiceId] = matched.id
          clearAnswersAfter(choiceId)
          attempt.answersChanged()
          advance()
        }
      }
    }
  }

  private fun handleConfirmInput(text: String) {
    when {
      text.equals("cancel", ignoreCase = true) -> endFlow(CreationOutcome.Cancelled)
      text.equals("back", ignoreCase = true) -> {
        if (newCharacter) {
          var lastApplicableStep: CreationStep? = null
          for (step in provider.steps.reversed()) {
            val applies =
              try {
                step.applies(world, attempt)
              } catch (e: Throwable) {
                world.diagnose(
                  "plugin",
                  "creation step '${step.id}' applies failed: ${e.message}",
                  e,
                )
                endFlow(CreationOutcome.ProviderFailed)
                return
              }
            if (applies) {
              lastApplicableStep = step
              break
            }
          }
          if (lastApplicableStep != null) {
            attempt.finished.remove(lastApplicableStep.id)
            while (history.isNotEmpty() && history.last() != lastApplicableStep.id) {
              history.removeLast()
            }
            if (history.isEmpty()) {
              history.add(lastApplicableStep.id)
            }
            showStep(lastApplicableStep)
            return
          }
        }
        val last = lastAsked()
        if (last != null) reask(last)
        else if (newCharacter) endFlow(CreationOutcome.BackToName(attempt))
        else endFlow(CreationOutcome.Cancelled)
      }
      text.equals("yes", ignoreCase = true) || text.equals("y", ignoreCase = true) -> {
        val refusal =
          try {
            provider.validate(world, answers)
          } catch (e: Exception) {
            world.diagnose("plugin", "creation provider validate() failed: ${e.message}", e)
            endFlow(CreationOutcome.ProviderFailed)
            return
          }
        if (refusal != null) {
          world.sendSession(session, refusal)
          val last = lastAsked()
          if (last != null) reask(last) else promptConfirm()
        } else {
          if (newCharacter) endFlow(CreationOutcome.Created(attempt))
          else endFlow(CreationOutcome.Completed(answers.toMap()))
        }
      }
      else -> promptConfirm()
    }
  }

  /**
   * The choice to go back to from the confirmation: the last one asked, or, for a new character
   * whose answers were all kept from an earlier attempt, the last one answered. A saved character
   * only revisits the choices it was asked.
   */
  private fun lastAsked(): String? =
    history.removeLastOrNull()
      ?: if (newCharacter) provider.choices.lastOrNull { it.id in answers }?.id else null

  private fun reask(choiceId: String) {
    clearAnswersFrom(choiceId)
    attempt.answersChanged()
    askChoice(choiceId)
  }

  private fun matchOption(input: String, options: List<CreationOption>): CreationOption? {
    if (input.isEmpty()) return null
    val num = input.toIntOrNull()
    if (num != null && num in 1..options.size) return options[num - 1]
    options
      .firstOrNull { it.id.equals(input, ignoreCase = true) }
      ?.let {
        return it
      }
    options
      .firstOrNull { it.label.equals(input, ignoreCase = true) }
      ?.let {
        return it
      }
    val prefixMatches =
      options.filter {
        it.id.startsWith(input, ignoreCase = true) || it.label.startsWith(input, ignoreCase = true)
      }
    if (prefixMatches.size == 1) return prefixMatches.single()
    return null
  }

  private fun buildAnswersGmcp(answersMap: Map<String, String>): List<Map<String, String>> =
    provider.choices.mapNotNull { c ->
      val optId = answersMap[c.id] ?: return@mapNotNull null
      val opt = c.options.firstOrNull { it.id == optId } ?: return@mapNotNull null
      mapOf("choice" to c.id, "title" to c.title, "option" to opt.id, "label" to opt.label)
    }

  private fun sendGmcp(data: Any?) {
    if (session.sink.wantsGmcp("Char.Creation")) {
      session.sink.gmcp("Char.Creation", data)
    }
  }

  private fun endFlow(outcome: CreationOutcome) {
    if (outcome == CreationOutcome.ProviderFailed)
      world.sendSession(session, world.msg(LoginMsg.CREATION_PROVIDER_FAILED))
    sendGmcp(null)
    onOutcome(outcome)
  }
}

/**
 * The room a new character with [answers] starts in: the provider's, or [default] when it names
 * none or one that doesn't exist. Throws when the provider does.
 */
fun World.creationStartRoom(answers: Map<String, String>, default: Label): Label {
  val named = creation?.startRoom(this, answers) ?: return default
  if (roomOrNull(named) != null) return named
  diagnose("plugin", "creation provider start room $named does not exist")
  return default
}

/**
 * The changes that make [player]'s answers [answers]. An answer to a choice the provider defines
 * that [answers] leaves out is removed (the flow dropped it); answers to choices it no longer
 * defines are kept.
 */
fun World.choiceChanges(player: Player, answers: Map<String, String>): Map<String, String?> {
  val defined = creation?.choices?.map { it.id }?.toSet() ?: emptySet()
  val dropped = player.choices.keys.filter { it in defined && it !in answers }
  return answers + dropped.associateWith { null }
}

/**
 * Returns true if [player] has any choices with available options that are unanswered or invalid.
 */
fun World.hasMissingChoices(player: gups.core.model.Player): Boolean {
  val provider = creation ?: return false
  val answersSoFar = LinkedHashMap<String, String>()
  for (c in provider.choices) {
    val avail =
      try {
        provider.available(this, c.id, answersSoFar)
      } catch (e: Exception) {
        diagnose("plugin", "creation provider available() failed: ${e.message}", e)
        return false
      }
    if (avail.isEmpty()) continue
    val ans = player.choices[c.id]
    if (ans == null || ans !in avail) return true
    answersSoFar[c.id] = ans
  }
  return false
}

sealed interface HeadlessSteps {
  data object Finished : HeadlessSteps

  data class Unfinished(val stepId: String) : HeadlessSteps

  data class Failed(val stepId: String, val error: Throwable) : HeadlessSteps
}

/**
 * Runs every applicable custom step of the provider for [attempt] without a session, feeding each
 * the lines [linesFor] gives it. Stops at the first step that isn't done after its lines.
 */
fun World.runStepsHeadless(
  attempt: CreationAttempt,
  linesFor: (stepId: String) -> List<String>,
): HeadlessSteps {
  val steps = creation?.steps ?: return HeadlessSteps.Finished
  for (step in steps) {
    val applies =
      try {
        step.applies(this, attempt)
      } catch (e: Throwable) {
        return HeadlessSteps.Failed(step.id, e)
      }
    if (!applies) continue

    try {
      step.show(this, attempt)
    } catch (e: Throwable) {
      return HeadlessSteps.Failed(step.id, e)
    }

    val lines = linesFor(step.id)
    var done = false
    for (line in lines) {
      val reply =
        try {
          step.input(this, attempt, line)
        } catch (e: Throwable) {
          return HeadlessSteps.Failed(step.id, e)
        }
      if (reply is StepReply.Done) {
        attempt.finished.add(step.id)
        done = true
        break
      }
    }
    if (!done) {
      return HeadlessSteps.Unfinished(step.id)
    }
  }
  return HeadlessSteps.Finished
}
