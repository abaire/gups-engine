package gups.sample.heritage

import gups.pluginapi.PluginActor
import gups.pluginapi.PluginChoice
import gups.pluginapi.PluginOption
import gups.pluginapi.PluginWorld

data class RespecOption(
  val id: String,
  val label: String,
  val summary: String,
  val refusal: String?,
) {
  fun toPlain(): Map<String, Any?> =
    mapOf("id" to id, "label" to label, "summary" to summary, "refusal" to refusal)
}

data class RespecChoice(
  val choice: String,
  val title: String,
  val current: String?,
  val options: List<RespecOption>,
) {
  fun toPlain(): Map<String, Any?> =
    mapOf(
      "choice" to choice,
      "title" to title,
      "current" to current,
      "options" to options.map { it.toPlain() },
    )
}

data class RespecMatch(
  val choice: String?,
  val option: String?,
  val label: String?,
  val refusal: String?,
) {
  fun toPlain(): Map<String, Any?> =
    mapOf("choice" to choice, "option" to option, "label" to label, "refusal" to refusal)

  companion object {
    fun refused(message: String) = RespecMatch(null, null, null, message)
  }
}

/** Who asks for a change, and whether picking the current option again is allowed. */
data class RespecRequest(val world: PluginWorld, val actor: PluginActor, val allowSame: Boolean)

/**
 * Changing one choice of a character in play: what the shrine may offer, matching the player's
 * words to an option, and applying it. The engine re-checks on `setChoices`; this answers first so
 * a shrine can refuse before it takes any payment.
 */
class Respec(private val creation: HeritageCreation) {
  /** One option of one choice, both resolved against the creation provider. */
  private data class Pick(val choice: PluginChoice, val option: PluginOption) {
    val title
      get() = choice.title.lowercase()
  }

  /** The words of a request and the choices they may name. */
  private data class Phrase(val scope: List<PluginChoice>, val words: List<String>) {
    val wanted
      get() = words.joinToString(" ")
  }

  fun refusal(req: RespecRequest, choice: String, option: String): String? =
    refusalFor(req, pickOf(choice, option))

  fun menu(req: RespecRequest, choices: List<String>): List<RespecChoice> =
    choices.map(::choiceOf).map { c ->
      RespecChoice(
        c.id,
        c.title,
        req.actor.choices[c.id],
        c.options.map { o -> RespecOption(o.id, o.label, o.summary, refusalFor(req, Pick(c, o))) },
      )
    }

  fun check(req: RespecRequest, phrase: String, choices: List<String>): RespecMatch {
    val words = phrase.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return RespecMatch.refused("Change what?")
    val narrowed = narrow(Phrase(choices.map(::choiceOf), words))
    val found = candidates(narrowed)
    return when (found.size) {
      0 -> RespecMatch.refused("There's nothing like that to become here.")
      1 -> {
        val p = found.single()
        RespecMatch(p.choice.id, p.option.id, p.option.label, refusalFor(req, p))
      }
      else -> RespecMatch.refused("Which do you mean: ${ambiguity(found, narrowed)}?")
    }
  }

  fun apply(req: RespecRequest, choice: String, option: String): String? {
    refusal(req, choice, option)?.let {
      return it
    }
    if (req.actor.choices[choice] == option) return null
    return req.world.setChoices(req.actor, mapOf(choice to option))
  }

  private fun refusalFor(req: RespecRequest, pick: Pick): String? {
    val (world, actor, allowSame) = req
    if (actor.choices[pick.choice.id] == pick.option.id && !allowSame) {
      return "That is already your ${pick.title}."
    }
    val candidate = actor.choices + (pick.choice.id to pick.option.id)
    return availabilityRefusal(world, candidate, pick) ?: creation.validate(world, candidate)
  }

  /**
   * Walks the choices in declaration order, as creation does, so a change to an earlier choice is
   * refused when it would leave a later answer outside what the earlier ones allow.
   */
  private fun availabilityRefusal(
    world: PluginWorld,
    candidate: Map<String, String>,
    pick: Pick,
  ): String? {
    val before = mutableMapOf<String, String>()
    for (c in creation.choices) {
      val answer = candidate[c.id]
      val open = creation.available(world, c.id, before)
      when {
        c == pick.choice && pick.option.id !in open ->
          return "${pick.option.label} isn't open to you."
        c != pick.choice && answer != null && answer !in open ->
          return "That would leave your ${c.title.lowercase()} " +
            "(${c.options.find { it.id == answer }?.label ?: answer}) unavailable."
      }
      if (answer != null) before[c.id] = answer
    }
    return null
  }

  /** A leading word that names exactly one choice scopes the rest of the phrase to it. */
  private fun narrow(phrase: Phrase): Phrase {
    val first = phrase.words[0]
    if (phrase.words.size < 2) return phrase
    val named =
      phrase.scope.filter { it.id.startsWith(first) || it.title.lowercase().startsWith(first) }
    return if (named.size == 1) Phrase(named, phrase.words.drop(1)) else phrase
  }

  private fun candidates(phrase: Phrase): List<Pick> {
    val wanted = phrase.wanted
    val all = phrase.scope.flatMap { c -> c.options.map { Pick(c, it) } }
    val exact = all.filter { it.option.id == wanted || it.option.label.lowercase() == wanted }
    return exact.ifEmpty {
      all.filter {
        it.option.id.startsWith(wanted) || it.option.label.lowercase().startsWith(wanted)
      }
    }
  }

  private fun ambiguity(found: List<Pick>, phrase: Phrase) =
    found.joinToString(", ") {
      if (phrase.scope.size > 1) "${it.option.label} (${it.title})" else it.option.label
    }

  private fun choiceOf(id: String): PluginChoice =
    creation.choices.find { it.id == id } ?: throw IllegalArgumentException("unknown choice '$id'")

  private fun pickOf(choice: String, option: String): Pick {
    val c = choiceOf(choice)
    val o =
      c.options.find { it.id == option }
        ?: throw IllegalArgumentException("unknown option '$option' for $choice")
    return Pick(c, o)
  }
}
