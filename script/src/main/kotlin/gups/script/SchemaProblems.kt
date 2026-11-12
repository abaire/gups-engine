package gups.script

import gups.core.model.Channel
import gups.core.model.WorldSchema
import gups.core.world.PROMPT_PLACEHOLDER

private val NAME = Regex("[a-z][a-z0-9_]*")

/** The problems in the schema declared in `MODULE.gups`, worded for the startup report. */
internal fun schemaProblems(schema: WorldSchema): List<String> = buildList {
  addAll(statAndVitalProblems(schema))
  addAll(damageTypeProblems(schema))
  addAll(channelProblems(schema))
  addAll(channelPriorityProblems(schema))
  addAll(promptProblems(schema))
}

private fun statAndVitalProblems(schema: WorldSchema): List<String> = buildList {
  val vitals = schema.vitalNames
  val seen = HashSet<String>()
  fun declared(kind: String, names: List<String>) {
    for (n in names) {
      when {
        !NAME.matches(n) -> add("MODULE.gups: $kind name '$n' must match [a-z][a-z0-9_]*")
        !seen.add(n) -> add("MODULE.gups: '$n' is declared more than once among stats and vitals")
        n in WorldModule.reservedViewNames ->
          add("MODULE.gups: $kind name '$n' is reserved for the actor view")
      }
      if (n.startsWith("max_") && n.removePrefix("max_") in vitals)
        add(
          "MODULE.gups: $kind name '$n' clashes with the maximum of vital '${n.removePrefix("max_")}'"
        )
    }
  }
  declared("stat", schema.statNames)
  declared("vital", vitals)
  for (v in vitals) if (v.startsWith("max"))
    add("MODULE.gups: vital name '$v' may not start with 'max'")
}

private fun damageTypeProblems(schema: WorldSchema): List<String> = buildList {
  val types = HashSet<String>()
  for (t in schema.damageTypes) {
    if (!NAME.matches(t)) add("MODULE.gups: damage type '$t' must match [a-z][a-z0-9_]*")
    else if (!types.add(t)) add("MODULE.gups: damage type '$t' is declared more than once")
  }
  if (schema.unarmedDamageType !in schema.damageTypes)
    add(
      "MODULE.gups: unarmed_damage_type '${schema.unarmedDamageType}' is not a declared damage type" +
        schema.suggest(schema.unarmedDamageType, schema.damageTypes)
    )
}

private fun channelProblems(schema: WorldSchema): List<String> = buildList {
  val channels = HashSet<String>()
  for (c in schema.channels) {
    when {
      !NAME.matches(c) -> add("MODULE.gups: channel '$c' must match [a-z][a-z0-9_]*")
      Channel.BUILT_IN.any { it.name == c } -> add("MODULE.gups: channel '$c' is built in")
      !channels.add(c) -> add("MODULE.gups: channel '$c' is declared more than once")
    }
  }
}

private fun channelPriorityProblems(schema: WorldSchema): List<String> = buildList {
  val ranked = HashSet<String>()
  val knownChannels = schema.allChannels.map { it.name }
  for (c in schema.channelPriority) {
    when {
      c !in knownChannels ->
        add(
          "MODULE.gups: channel_priority names '$c', which is not a channel" +
            schema.suggest(c, knownChannels)
        )
      !ranked.add(c) ->
        add("MODULE.gups: channel '$c' is ranked more than once in channel_priority")
    }
  }
}

private fun promptProblems(schema: WorldSchema): List<String> = buildList {
  val prompt = schema.prompt ?: return@buildList
  val vitals = schema.vitalNames
  for (m in PROMPT_PLACEHOLDER.findAll(prompt)) {
    val key = m.groupValues[1]
    val vital = key.removePrefix("max_")
    if (key !in vitals && !(key.startsWith("max_") && vital in vitals))
      add(
        "MODULE.gups: prompt placeholder '{$key}' is not a declared vital" +
          schema.suggest(key, vitals + vitals.map { "max_$it" })
      )
  }
}
