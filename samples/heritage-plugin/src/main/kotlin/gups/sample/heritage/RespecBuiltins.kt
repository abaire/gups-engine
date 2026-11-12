package gups.sample.heritage

import gups.pluginapi.PluginActor
import gups.pluginapi.PluginParam
import gups.pluginapi.PluginRegistry

private val ACTOR = PluginParam("actor", "actor", "The player whose choices change.")
private val CHOICES =
  PluginParam("choices", "list", "The choice ids this shrine may change, for example [\"class\"].")
private val ALLOW_SAME =
  PluginParam(
    "allow_same",
    "bool",
    "Whether the current option may be picked again, for a fresh start in the same class.",
    required = false,
    defaultValue = false,
  )

/** The respec builtins: `heritage.respec_menu`, `respec_check`, `respec` and `starter_skills`. */
internal fun registerRespecBuiltins(r: PluginRegistry, creation: HeritageCreation) {
  val respec = Respec(creation)
  r.addBuiltin(
    name = "respec_menu",
    doc =
      "Lists each choice with its current option and every option with a refusal or None. " +
        "Returns a list of {choice, title, current, options: [{id, label, summary, refusal}]}.",
    params = listOf(ACTOR, CHOICES, ALLOW_SAME),
    returnType = "list",
  ) { world, pos, _ ->
    val args = RespecArgs("respec_menu", pos)
    respec.menu(RespecRequest(world, args.player(0), args.flag(2)), args.choices(1)).map {
      it.toPlain()
    }
  }
  r.addBuiltin(
    name = "respec_check",
    doc =
      "Matches the player's words to one option and says whether it may be picked; changes " +
        "nothing. Returns {choice, option, label, refusal}.",
    params =
      listOf(ACTOR, PluginParam("phrase", "str", "What the player typed."), CHOICES, ALLOW_SAME),
    returnType = "dict",
  ) { world, pos, _ ->
    val args = RespecArgs("respec_check", pos)
    respec
      .check(
        RespecRequest(world, args.player(0), args.flag(3)),
        args.text(1, "phrase"),
        args.choices(2),
      )
      .toPlain()
  }
  r.addBuiltin(
    name = "respec",
    doc = "Re-checks, then changes one choice. Returns the refusal message or None.",
    params =
      listOf(
        ACTOR,
        PluginParam("choice", "str", "The choice id to change."),
        PluginParam("option", "str", "The option id to change it to."),
        ALLOW_SAME,
      ),
    returnType = "str",
  ) { world, pos, _ ->
    val args = RespecArgs("respec", pos)
    respec.apply(
      RespecRequest(world, args.player(0), args.flag(3)),
      args.text(1, "choice"),
      args.text(2, "option"),
    )
  }
  r.addBuiltin(
    name = "starter_skills",
    doc = "Grants the current class's starter skills the player lacks. Returns the labels granted.",
    params = listOf(ACTOR),
    returnType = "list",
  ) { world, pos, _ ->
    creation.grantStarterSkills(world, RespecArgs("starter_skills", pos).player(0))
  }
}

/** Reads a respec builtin's bound arguments, naming the builtin in every refusal. */
private class RespecArgs(private val builtin: String, private val pos: List<Any?>) {
  fun player(i: Int): PluginActor =
    (pos.getOrNull(i) as? PluginActor)?.takeIf { it.isPlayer }
      ?: throw IllegalArgumentException("heritage.$builtin(actor): expected a player")

  fun choices(i: Int): List<String> {
    val list = pos.getOrNull(i) as? List<*>
    if (list == null || list.any { it !is String }) {
      throw IllegalArgumentException("heritage.$builtin(choices): expected a list of choice ids")
    }
    return list.map { it as String }
  }

  fun text(i: Int, name: String): String =
    pos.getOrNull(i) as? String
      ?: throw IllegalArgumentException("heritage.$builtin($name): expected a string")

  fun flag(i: Int): Boolean =
    when (val v = pos.getOrNull(i)) {
      null -> false
      is Boolean -> v
      else -> throw IllegalArgumentException("heritage.$builtin(allow_same): expected a bool")
    }
}
