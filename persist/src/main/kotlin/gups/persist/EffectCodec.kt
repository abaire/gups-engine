@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package gups.persist

import gups.core.Label
import gups.core.model.AffectScope
import gups.core.model.Channel
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Effect
import gups.core.model.ModifierPoint
import gups.core.model.Proc
import gups.core.model.ProcOutcome
import gups.core.model.ProcTarget
import gups.core.model.ProcTrigger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class OutcomeJson(
  val weight: Double,
  val skill: String? = null,
  val effect: EffectJson? = null,
  val target: String? = null,
  val message: String? = null,
)

@Serializable
data class ProcJson(val on: String, val chance: Double, val outcomes: List<OutcomeJson>)

@Serializable
@JsonClassDiscriminator("kind")
sealed class EffectJson {
  @Serializable
  @SerialName("damage")
  data class Damage(val dice: String, val type: String) : EffectJson()

  @Serializable
  @SerialName("heal")
  data class Heal(val dice: String, val vital: String) : EffectJson()

  @Serializable
  @SerialName("apply_affect")
  data class ApplyAffect(
    val name: String,
    val duration: Long? = null,
    val statDeltas: Map<String, Int> = emptyMap(),
    val flags: List<String> = emptyList(),
    val tickEvery: Int? = null,
    val onTick: RefJson? = null,
    val onExpire: RefJson? = null,
    val expireMessage: String = "",
    val modifiers: Map<String, RefJson> = emptyMap(),
    val senseDeltas: Map<String, Int> = emptyMap(),
    val maxVitalDeltas: Map<String, Int> = emptyMap(),
    val resistDeltas: Map<String, Int> = emptyMap(),
    val applies: String? = null,
    val procs: List<ProcJson> = emptyList(),
    val addTags: List<String> = emptyList(),
    val removeTags: List<String> = emptyList(),
  ) : EffectJson()

  @Serializable
  @SerialName("dispel")
  data class Dispel(
    val name: String? = null,
    val flags: List<String> = emptyList(),
    val all: Boolean = false,
    val count: Int? = null,
  ) : EffectJson()

  @Serializable @SerialName("knockback") data class Knockback(val steps: Int) : EffectJson()

  @Serializable @SerialName("pull") data class Pull(val steps: Int) : EffectJson()

  @Serializable @SerialName("stun") data class Stun(val pulses: Int) : EffectJson()

  @Serializable
  @SerialName("summon")
  data class Summon(val label: String, val count: Int = 1, val duration: Int? = null) : EffectJson()

  @Serializable
  @SerialName("sequence")
  data class Sequence(val effects: List<EffectJson>) : EffectJson()

  @Serializable
  @SerialName("plugin")
  data class Plugin(
    val pluginId: String,
    val name: String,
    val args: JsonObject = JsonObject(emptyMap()),
  ) : EffectJson()
}

internal fun Proc.json(): ProcJson =
  ProcJson(on = on.word, chance = chance, outcomes = outcomes.map { it.json() })

internal fun ProcJson.toProc(): Proc =
  Proc(
    on =
      ProcTrigger.entries.firstOrNull { it.word.equals(on, ignoreCase = true) }
        ?: error("Unknown proc trigger '$on'"),
    chance = chance,
    outcomes = outcomes.map { it.toProcOutcome() },
  )

internal fun ProcOutcome.json(): OutcomeJson =
  OutcomeJson(
    weight = weight,
    skill = skill?.v,
    effect = effect?.json(),
    target = target?.word,
    message = message,
  )

internal fun OutcomeJson.toProcOutcome(): ProcOutcome =
  ProcOutcome(
    weight = weight,
    skill = skill?.let(::Label),
    effect = effect?.toEffect(),
    target =
      target?.let { t ->
        ProcTarget.entries.firstOrNull { it.word.equals(t, ignoreCase = true) }
          ?: error("Unknown proc target '$t'")
      },
    message = message,
  )

internal fun Effect.json(): EffectJson =
  when (this) {
    is Effect.Damage -> EffectJson.Damage(dice.toString(), type.name)
    is Effect.Heal -> EffectJson.Heal(dice.toString(), vital)
    is Effect.ApplyAffect ->
      EffectJson.ApplyAffect(
        name = name,
        duration = duration,
        statDeltas = statDeltas,
        flags = flags.sorted(),
        tickEvery = tickEvery,
        onTick = onTick?.json(),
        onExpire = onExpire?.json(),
        expireMessage = expireMessage,
        modifiers = modifiers.entries.associate { (k, v) -> k.scriptName to v.json() },
        senseDeltas = senseDeltas.entries.associate { (k, v) -> k.name to v },
        maxVitalDeltas = maxVitalDeltas,
        resistDeltas = resistDeltas.entries.associate { (k, v) -> k.name to v },
        applies = applies?.name?.lowercase(),
        procs = procs.map { it.json() },
        addTags = addTags.sorted(),
        removeTags = removeTags.sorted(),
      )
    is Effect.Dispel ->
      EffectJson.Dispel(name = name, flags = flags.sorted(), all = all, count = count)
    is Effect.Knockback -> EffectJson.Knockback(steps)
    is Effect.Pull -> EffectJson.Pull(steps)
    is Effect.Stun -> EffectJson.Stun(pulses)
    is Effect.Summon -> EffectJson.Summon(label.v, count, duration)
    is Effect.Sequence -> EffectJson.Sequence(effects.map { it.json() })
    is Effect.Plugin ->
      EffectJson.Plugin(
        pluginId = pluginId,
        name = name,
        args = JsonObject(args.mapValues { plainJson(it.value) }),
      )
  }

internal fun EffectJson.toEffect(): Effect =
  when (this) {
    is EffectJson.Damage -> Effect.Damage(Dice.parse(dice), DamageType(type))
    is EffectJson.Heal -> Effect.Heal(Dice.parse(dice), vital)
    is EffectJson.ApplyAffect ->
      Effect.ApplyAffect(
        name = name,
        duration = duration,
        statDeltas = statDeltas,
        flags = flags.toSet(),
        tickEvery = tickEvery,
        onTick = onTick?.ref(),
        onExpire = onExpire?.ref(),
        expireMessage = expireMessage,
        modifiers =
          modifiers.entries
            .mapNotNull { (k, v) -> ModifierPoint.byName(k)?.let { it to v.ref() } }
            .toMap(),
        senseDeltas = senseDeltas.mapKeys { Channel(it.key) },
        maxVitalDeltas = maxVitalDeltas,
        resistDeltas = resistDeltas.mapKeys { DamageType(it.key) },
        applies = applies?.let { AffectScope.valueOf(it.uppercase()) },
        procs = procs.map { it.toProc() },
        addTags = addTags.toSet(),
        removeTags = removeTags.toSet(),
      )
    is EffectJson.Dispel ->
      Effect.Dispel(name = name, flags = flags.toSet(), all = all, count = count)
    is EffectJson.Knockback -> Effect.Knockback(steps)
    is EffectJson.Pull -> Effect.Pull(steps)
    is EffectJson.Stun -> Effect.Stun(pulses)
    is EffectJson.Summon -> Effect.Summon(Label(label), count, duration)
    is EffectJson.Sequence -> Effect.Sequence(effects.map { it.toEffect() })
    is EffectJson.Plugin ->
      Effect.Plugin(
        pluginId = pluginId,
        name = name,
        args = args.mapValues { fromPlainJson(it.value) },
      )
  }

private fun plainJson(v: Any?): JsonElement =
  when (v) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(v)
    is Long -> JsonPrimitive(v)
    is Int -> JsonPrimitive(v)
    is Double -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v.toDouble())
    is String -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map(::plainJson))
    is Map<*, *> -> JsonObject(v.entries.associate { (k, val_) -> k.toString() to plainJson(val_) })
    else -> error("Unsupported plain value: $v (${v::class})")
  }

private fun fromPlainJson(e: JsonElement): Any? =
  when (e) {
    is JsonNull -> null
    is JsonPrimitive ->
      when {
        e.isString -> e.content
        e.booleanOrNull != null -> e.booleanOrNull!!
        e.longOrNull != null -> e.longOrNull!!
        e.doubleOrNull != null -> e.doubleOrNull!!
        else -> e.content
      }
    is JsonArray -> e.map(::fromPlainJson)
    is JsonObject -> e.mapValues { fromPlainJson(it.value) }
  }
