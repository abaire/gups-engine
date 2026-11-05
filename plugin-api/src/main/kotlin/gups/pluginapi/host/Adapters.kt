package gups.pluginapi.host

import gups.core.Label
import gups.core.model.Channel
import gups.core.model.CreationChoice
import gups.core.model.CreationOption
import gups.core.model.CreationProvider
import gups.core.model.ModifierPoint
import gups.core.model.Player
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import gups.core.model.Trait
import gups.core.model.TraitModifier
import gups.core.world.ContainmentFilter
import gups.core.world.Diagnostic
import gups.core.world.World
import gups.pluginapi.PluginContainmentCall
import gups.pluginapi.PluginCreation
import gups.pluginapi.PluginModifierCall
import gups.pluginapi.PluginModifierPoint
import gups.pluginapi.PluginRestrictionCall
import gups.pluginapi.PluginRestrictionPoint
import gups.pluginapi.PluginTrait
import gups.pluginapi.PluginWorld

internal class CreationProviderAdapter(
  private val world: World,
  private val pluginId: String,
  private val creation: PluginCreation,
) : CreationProvider {
  private val facade: PluginWorld
    get() = WorldFacade(world, pluginId, Facades.logOf(world))

  override val choices: List<CreationChoice> =
    creation.choices.map { c ->
      CreationChoice(
        id = c.id,
        title = c.title,
        prompt = c.prompt,
        options =
          c.options.map { o ->
            CreationOption(
              id = o.id,
              label = o.label,
              summary = o.summary,
              help = o.help,
              traits = o.traits,
            )
          },
      )
    }

  override fun available(world: World, choice: String, answers: Map<String, String>): List<String> {
    val choiceDef = choices.firstOrNull { it.id == choice } ?: return emptyList()
    val raw =
      try {
        creation.available(facade, choice, answers)
      } catch (e: Exception) {
        throw IllegalStateException("plugin '$pluginId': available failed: $e", e)
      }
    val validIds = choiceDef.options.map { it.id }.toSet()
    val result = mutableListOf<String>()
    for (id in raw) {
      if (id in validIds) {
        result.add(id)
      } else {
        world.record(
          Diagnostic(
            world.pulse,
            "plugin",
            "plugin '$pluginId': choice '$choice' available returned unknown option '$id'",
          )
        )
      }
    }
    return result
  }

  override fun validate(world: World, answers: Map<String, String>): String? =
    try {
      creation.validate(facade, answers)
    } catch (e: Exception) {
      throw IllegalStateException("plugin '$pluginId': validate failed: $e", e)
    }

  override fun startRoom(world: World, answers: Map<String, String>): Label? {
    val label =
      try {
        creation.startRoom(facade, answers)
      } catch (e: Exception) {
        throw IllegalStateException("plugin '$pluginId': startRoom failed: $e", e)
      } ?: return null
    return runCatching { Label(label) }
      .getOrElse {
        world.record(
          Diagnostic(world.pulse, "plugin", "plugin '$pluginId': bad start room '$label': $it")
        )
        null
      }
  }

  override fun created(world: World, player: Player, newCharacter: Boolean) {
    try {
      creation.created(facade, ActorFacade(player, world), newCharacter)
    } catch (e: Throwable) {
      world.record(Diagnostic(world.pulse, "plugin", "plugin '$pluginId': created failed: $e", e))
    }
  }
}

internal fun traitAdapter(pluginId: String, pt: PluginTrait): Trait {
  val mods =
    pt.modifiers
      .mapKeys { ModifierPoint.valueOf(it.key.name) }
      .mapValues { (point, mod) ->
        TraitModifier { c, base ->
          val w = c.world
          val bearer = if (point.defender) c.target else c.requireActor()
          if (bearer == null) return@TraitModifier base
          val opponent = if (point.defender) c.requireActor() else c.target
          val call =
            PluginModifierCall(
              world = WorldFacade(w, pluginId, Facades.logOf(w)),
              point = PluginModifierPoint.valueOf(point.name),
              actor = ActorFacade(bearer, w),
              other = opponent?.let { ActorFacade(it, w) },
              weapon = c.weapon?.let { ItemFacade(it, w) },
              room = RoomFacade(bearer.room, w),
              skill = c.skill?.let { SkillFacade(it) },
              item = c.item?.let { ItemFacade(it, w) },
              event = c.event,
            )
          fun refuse(why: String): Any {
            w.record(
              Diagnostic(
                w.pulse,
                "plugin",
                "plugin '$pluginId': trait '${pt.name}' modifier $point $why",
              )
            )
            return base
          }
          try {
            when (point) {
              ModifierPoint.TO_HIT,
              ModifierPoint.DAMAGE,
              ModifierPoint.DEFEND_HIT,
              ModifierPoint.RECEIVE_DAMAGE,
              ModifierPoint.INITIATIVE,
              ModifierPoint.ADVANCE_LAG,
              ModifierPoint.RETREAT_LAG,
              ModifierPoint.RETREAT_FAIL_LAG,
              ModifierPoint.FLEE_FAIL_LAG,
              ModifierPoint.SKILL_CHECK,
              ModifierPoint.SKILL_POWER,
              ModifierPoint.ITEM_POWER -> {
                val res = mod.modify(call, (base as Int).toLong())
                if (res is Long)
                  res.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                else refuse("returned ${res::class.simpleName}, expected Long")
              }
              ModifierPoint.MOVE_COST,
              ModifierPoint.REGEN,
              ModifierPoint.MITIGATE -> {
                @Suppress("UNCHECKED_CAST")
                val res =
                  mod.modify(call, (base as Map<String, Int>).mapValues { it.value.toLong() })
                if (
                  res is Map<*, *> &&
                    res.keys.all { it is String && it in w.schema.vitalNames } &&
                    res.values.all { it is Long }
                ) {
                  res.entries.associate { (k, v) ->
                    (k as String) to
                      (v as Long).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                  }
                } else refuse("returned an invalid vital map, expected Map<String, Long>")
              }
              ModifierPoint.BONUS_DAMAGE -> {
                @Suppress("UNCHECKED_CAST")
                val res =
                  mod.modify(call, (base as Map<String, Int>).mapValues { it.value.toLong() })
                if (
                  res is Map<*, *> &&
                    res.keys.all { it is String && it in w.schema.damageTypes } &&
                    res.values.all { it is Long }
                ) {
                  res.entries.associate { (k, v) ->
                    (k as String) to
                      (v as Long).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                  }
                } else refuse("returned an invalid damage map, expected Map<String, Long>")
              }
              else -> base
            }
          } catch (e: Throwable) {
            w.record(
              Diagnostic(
                w.pulse,
                "plugin",
                "plugin '$pluginId': trait '${pt.name}' modifier $point failed: $e",
                e,
              )
            )
            base
          }
        }
      }
  return Trait(
    name = pt.name,
    label = pt.label,
    doc = pt.doc,
    statDeltas = pt.statDeltas,
    maxVitalDeltas = pt.maxVitalDeltas,
    resistDeltas = pt.resistDeltas,
    flags = pt.flags,
    modifiers = mods,
    senseDeltas = pt.senseDeltas.mapKeys { Channel(it.key) },
  )
}

internal fun restrictionAdapter(
  world: World,
  pluginId: String,
  corePoint: RestrictionPoint,
  check: (PluginRestrictionCall) -> String?,
): Restriction {
  val facade = WorldFacade(world, pluginId, Facades.logOf(world))
  return Restriction { w, req ->
    val call =
      PluginRestrictionCall(
        world = facade,
        point = PluginRestrictionPoint.valueOf(corePoint.name),
        actor = ActorFacade(req.actor, w),
        item = req.item?.let { ItemFacade(it, w) },
        slot = req.slot,
        room = req.room?.let { RoomFacade(it, w) },
        from = req.from?.let { RoomFacade(it, w) },
        direction = req.direction?.name,
        skill = req.skill?.let { SkillFacade(it) },
        target =
          when (val t = req.target) {
            is gups.core.model.Actor -> ActorFacade(t, w)
            is gups.core.model.Item -> ItemFacade(t, w)
            is gups.core.model.Room -> RoomFacade(t, w)
            else -> null
          },
        proficiency = req.proficiency,
      )
    try {
      check(call)
    } catch (e: Throwable) {
      w.record(
        Diagnostic(w.pulse, "plugin", "plugin '$pluginId': restriction $corePoint failed: $e", e)
      )
      null
    }
  }
}

internal fun containmentFilterAdapter(
  world: World,
  pluginId: String,
  filter: (PluginContainmentCall) -> Boolean,
): ContainmentFilter {
  val facade = WorldFacade(world, pluginId, Facades.logOf(world))
  return ContainmentFilter { w, holder, container, item, ha ->
    val call =
      PluginContainmentCall(
        world = facade,
        holder = ActorFacade(holder, w),
        container = ItemFacade(container, w),
        item = ItemFacade(item, w),
        affect = toPlugin(ha),
      )
    try {
      filter(call)
    } catch (e: Throwable) {
      w.record(
        Diagnostic(w.pulse, "plugin", "plugin '$pluginId': containment filter failed: $e", e)
      )
      false
    }
  }
}
