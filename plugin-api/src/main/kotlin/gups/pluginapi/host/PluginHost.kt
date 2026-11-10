package gups.pluginapi.host

import gups.core.Role
import gups.core.model.Actor
import gups.core.model.Entity
import gups.core.model.Item
import gups.core.model.Room
import gups.core.model.WorldSchema
import gups.core.world.Action
import gups.core.world.CategoryDef
import gups.core.world.CommandCategory
import gups.core.world.CustomHookHandler
import gups.core.world.Diagnostic
import gups.core.world.EntityRef
import gups.core.world.EventListener
import gups.core.world.FastCommand
import gups.core.world.Features
import gups.core.world.GmcpInput
import gups.core.world.HookContext
import gups.core.world.HookName
import gups.core.world.PeriodicWork
import gups.core.world.PluginEffectRunner
import gups.core.world.TickCommand
import gups.core.world.World
import gups.core.world.submit
import gups.pluginapi.GupsPlugin
import gups.pluginapi.PluginCommandCall
import gups.pluginapi.PluginEffectCall
import gups.pluginapi.PluginEntity
import gups.pluginapi.PluginEventCall
import gups.pluginapi.PluginHookCall
import gups.pluginapi.PluginLane
import gups.pluginapi.PluginTickCall
import gups.script.WorldPluginWiring
import kotlin.io.path.name

/**
 * Host coordinator managing loaded plugins and wiring them into the live engine simulation.
 *
 * ## Responsibilities
 * - Manages plugin lifecycle and classloader isolation for loaded plugin jars.
 * - Exposes plugin members as top-level Starlark package globals under `<pluginId>.<name>`.
 * - Wires plugin commands into engine fast or tick command registries.
 * - Registers GMCP package providers, skill effect primitives, traits, character creation flows,
 *   and restriction hooks.
 * - Dispatches world start lifecycle notifications to registered plugin callbacks.
 */
class PluginHost
internal constructor(
  private val plugins: List<LoadedPlugin>,
  /** Why plugins were skipped or jars ignored, each starting `plugin '<id>': ` or `plugins/`. */
  val warnings: List<String>,
  private val log: (String) -> Unit,
) : WorldPluginWiring, AutoCloseable {
  override val globals: Map<String, Any> =
    plugins.associate { it.id to PluginNamespace(it.id, it.reg.members) }

  override val traits: Set<String>
    get() = plugins.flatMap { it.reg.traits.keys }.toSet()

  override val gmcpPackages: Set<String>
    get() =
      plugins
        .flatMap { p ->
          p.reg.gmcp.map { it.pkg } +
            p.reg.gmcpEvents.map { it.pkg } +
            p.reg.gmcpInputs.map { it.pkg }
        }
        .toSet()

  override val rulesExports: Map<String, String>
    get() =
      plugins.flatMap { it.reg.rulesExports.values }.associate { it.name to it.type }.toSortedMap()

  /** The ids that loaded, sorted. */
  val ids: List<String>
    get() = plugins.map { it.id }

  /** The features provided by the plugins that loaded; scripts test them with `ctx.has_feature`. */
  val providedFeatures: Set<String>
    get() = plugins.flatMap { it.providedFeatures }.toSet()

  private fun facade(world: World, id: String) = WorldFacade(world, id, log)

  private fun guard(world: World, id: String, what: String, block: () -> Unit) {
    try {
      block()
    } catch (e: Exception) {
      world.record(Diagnostic(world.pulse, "plugin", "plugin '$id': $what failed: $e", e))
    }
  }

  /** The event's ctx fields as plain values, per hook. Ints are Longs; actors are PluginActors. */
  private fun fieldsOf(w: World, hook: HookName, ctx: HookContext): Map<String, Any?> =
    when (hook) {
      HookName.ON_LEVEL_CHANGE ->
        mapOf("old_level" to ctx.oldLevel?.toLong(), "new_level" to ctx.newLevel?.toLong())
      HookName.ON_ENCUMBRANCE_CHANGE ->
        mapOf(
          "old_encumbrance_level" to ctx.oldEncumbranceLevel?.toLong(),
          "new_encumbrance_level" to ctx.newEncumbranceLevel?.toLong(),
        )
      HookName.ON_GROUP_CHANGE -> {
        val g = ctx.group!!
        mapOf(
          "event" to ctx.event,
          "group" to
            mapOf(
              "id" to g.id.toLong(),
              "leader" to ActorFacade(g.leader, w),
              "members" to g.members.map { ActorFacade(it, w) },
            ),
        )
      }
      HookName.ON_OWNER_CHANGE ->
        mapOf("target" to ctx.target?.let { ActorFacade(it, w) }, "event" to ctx.event)
      HookName.ON_DISENGAGE -> mapOf("event" to ctx.event)
      HookName.ON_DEATH -> mapOf("killer" to ctx.actor?.let { ActorFacade(it, w) })
      HookName.ON_SKILL_USE ->
        mapOf(
          "skill" to ctx.skill!!.label.v,
          "outcome" to ctx.outcome,
          "target" to ctx.target?.let { ActorFacade(it, w) },
          "item" to ctx.item?.let { ItemFacade(it, w) },
          "targets" to ctx.landed.orEmpty().map { ActorFacade(it, w) },
        )
      else -> emptyMap()
    }

  /** One handler run: read-only world, wrapped entity, plain arguments (views as facades). */
  private fun hookCall(w: World, id: String, name: String, event: HookEvent): PluginHookCall =
    HookCall(
      GmcpWorldFacade(facade(w, id)),
      wrap(w, event.entity),
      name,
      event.args.mapValues { (_, v) -> plainEntity(w, v) },
      event.result,
    )

  private class HookEvent(val entity: Entity, val args: Map<String, Any?>, val result: Any?)

  private fun wrap(w: World, e: Entity): PluginEntity =
    when (e) {
      is Room -> RoomFacade(e, w)
      is Item -> ItemFacade(e, w)
      else -> ActorFacade(e as Actor, w)
    }

  /** [v] with every [EntityRef], nested, replaced by the entity's facade, or null if gone. */
  private fun plainEntity(w: World, v: Any?): Any? =
    when (v) {
      is EntityRef -> w.entity(v.id)?.let { wrap(w, it) }
      is List<*> -> v.map { plainEntity(w, it) }
      is Map<*, *> -> v.entries.associate { (k, x) -> (k as String) to plainEntity(w, x) }
      else -> v
    }

  override fun install(world: World) {
    Facades.registerLog(world, log)
    Facades.registerRulesExports(
      world,
      plugins.associate { p -> p.id to p.reg.rulesExports.values.associate { it.name to it.type } },
    )
    Facades.registerGmcp(
      world,
      plugins.associate { p -> p.id to p.reg.gmcp.map { it.pkg } + p.reg.gmcpEvents.map { it.pkg } },
    )
    installCreationAndTraits(world)
    installRestrictions(world)
    installAdjusters(world)
    installPeriodic(world)
    for (p in plugins) {
      installCategories(world, p)
      installCommands(world, p)
      installGmcp(world, p)
    }
    installHooks(world)
    installEvents(world)
    installEffects(world)
  }

  private fun installCreationAndTraits(world: World) {
    val creationPlugin = plugins.firstOrNull { it.reg.creation != null }
    world.creation =
      creationPlugin?.let { CreationProviderAdapter(world, it.id, it.reg.creation!!) }
    world.traits =
      plugins.flatMap { p -> p.reg.traits.values.map { it.name to traitAdapter(p.id, it) } }.toMap()
  }

  private fun installRestrictions(world: World) {
    val all =
      gups.core.model.RestrictionPoint.entries.associateWith {
        mutableListOf<gups.core.model.Restriction>()
      }
    for (p in plugins) {
      for ((point, list) in p.reg.restrictions) {
        val corePoint = gups.core.model.RestrictionPoint.valueOf(point.name)
        for ((_, check) in list) {
          all.getValue(corePoint).add(restrictionAdapter(world, p.id, corePoint, check))
        }
      }
    }
    world.restrictions = all
  }

  /** One adapter per registration, across all plugins in id order. */
  private fun <R, A> adapters(
    registrations: (PluginRegistryImpl) -> List<Pair<String, R>>,
    adapt: (String, R) -> A,
  ): List<A> = plugins.flatMap { p -> registrations(p.reg).map { (_, r) -> adapt(p.id, r) } }

  private fun installAdjusters(world: World) {
    world.containmentFilters =
      adapters({ it.filters }) { id, f -> containmentFilterAdapter(world, id, f) }
    world.ownedBehaviorAdjusters =
      adapters({ it.ownedBehaviors }) { id, f -> ownedBehaviorsAdapter(world, id, f) }
    world.targetOrderAdjusters =
      adapters({ it.targetOrders }) { id, f -> targetOrderAdapter(world, id, f) }
    world.chainThinkAdjusters =
      adapters({ it.chainThinks }) { id, f -> chainThinkAdapter(world, id, f) }
    world.dispositionModifiers =
      adapters({ it.dispositionModifiers }) { id, f -> dispositionModifierAdapter(world, id, f) }
    world.factionAdjusters =
      adapters({ it.factionAdjusters }) { id, f -> factionAdjusterAdapter(world, id, f) }
  }

  private fun installPeriodic(world: World) {
    world.pluginPeriodic =
      plugins.flatMap { p ->
        p.reg.periodic.map { (pulses, _, fn) ->
          PeriodicWork(pulses) { w, actors ->
            guard(w, p.id, "every($pulses)") {
              fn(PluginTickCall(facade(w, p.id), actors.map { ActorFacade(it, w) }))
            }
          }
        }
      }
  }

  private fun installCategories(world: World, p: LoadedPlugin) {
    for (cat in p.reg.categories) {
      world.commands.registerCategory(
        CategoryDef(id = cat.name, title = cat.title, order = cat.order, help = cat.help)
      )
    }
  }

  private fun installCommands(world: World, p: LoadedPlugin) {
    for (c in p.reg.commands) {
      val role = Role.valueOf(c.role.name)
      val cat =
        if (role != Role.PLAYER || c.name.startsWith("@")) CommandCategory.SPECIAL
        else c.category ?: CommandCategory.GENERAL
      fun run(w: World, actor: Actor, args: String) =
        guard(w, p.id, "command '${c.name}'") {
          c.run(PluginCommandCall(facade(w, p.id), ActorFacade(actor, w), args))
        }
      world.commands.registerPlugin(
        when (c.lane) {
          PluginLane.FAST ->
            FastCommand(c.name, role = role, category = cat, help = c.doc) { call ->
              run(call.world, call.actor, call.args)
            }
          PluginLane.TICK ->
            TickCommand(c.name, role = role, category = cat, help = c.doc) { call ->
              object : Action {
                override fun validate(w: World, actor: Actor): String? = null

                override fun resolve(w: World, actor: Actor): Int {
                  run(w, actor, call.args)
                  return 0
                }
              }
            }
        }
      )
    }
  }

  private fun installGmcp(world: World, p: LoadedPlugin) {
    for (g in p.reg.gmcp) {
      // core records a failing producer and skips it for the pulse.
      world.pluginGmcp[g.pkg] = { player ->
        g.produce(facade(world, p.id), ActorFacade(player, world))
      }
    }
    for (g in p.reg.gmcpInputs) {
      world.pluginGmcpIn[g.pkg.lowercase()] =
        GmcpInput(g.pkg) { player, data ->
          guard(world, p.id, "GMCP input '${g.pkg}'") {
            g.handle(
              GmcpInputCall(
                GmcpWorldFacade(facade(world, p.id)),
                ActorFacade(player, world),
                g.pkg,
                data,
              ) {
                world.submit(player, it)
              }
            )
          }
        }
    }
  }

  private fun installHooks(world: World) {
    world.pluginHooks.clear()
    world.pluginFeatures = providedFeatures
    for (p in plugins) {
      for (h in p.reg.hooks) {
        world.pluginHooks.getOrPut(h.name) { mutableListOf() } +=
          CustomHookHandler { w, entity, args, result ->
            var out = result
            guard(w, p.id, "hook '${h.name}'") {
              out =
                toHookResult(
                  h.handle(hookCall(w, p.id, h.name, HookEvent(entity, args, result))),
                  "hook '${h.name}'(result)",
                )
            }
            out
          }
      }
    }
  }

  private fun installEvents(world: World) {
    world.pluginEvents.clear()
    for (p in plugins) {
      for ((name, _, fn) in p.reg.events) {
        val hook = HookName.entries.first { it.scriptName == name }
        world.pluginEvents.getOrPut(hook) { mutableListOf() } += EventListener { w, subject, ctx ->
          guard(w, p.id, "onEvent '$name'") {
            fn(
              PluginEventCall(
                facade(w, p.id),
                name,
                ActorFacade(subject, w),
                fieldsOf(w, hook, ctx),
              )
            )
          }
        }
      }
    }
  }

  private fun installEffects(world: World) {
    val effects =
      plugins.flatMap { p -> p.reg.effects.map { (p.id to it.name) to (p.id to it) } }.toMap()
    world.pluginEffects =
      PluginEffectRunner { w, effect, caster, target, targetItem, targetRoom, power ->
        val (id, e) =
          effects[effect.pluginId to effect.name]
            ?: run {
              w.record(
                Diagnostic(
                  w.pulse,
                  "plugin",
                  "no plugin effect ${effect.pluginId}.${effect.name} is loaded",
                )
              )
              return@PluginEffectRunner
            }
        guard(w, id, "effect '${e.name}'") {
          e.apply.apply(
            PluginEffectCall(
              facade(w, id),
              ActorFacade(caster, w),
              target?.let { ActorFacade(it, w) },
              effect.args,
              targetItem = targetItem?.let { ItemFacade(it, w) },
              power = power,
              targetRoom = targetRoom?.let { RoomFacade(it, w) },
            )
          )
        }
      }
  }

  override fun started(world: World) {
    for (p in plugins) for (fn in p.reg.started) guard(world, p.id, "onWorldStarted") {
      fn(facade(world, p.id))
    }
  }

  fun describe(): List<PluginInfo> =
    plugins.map { p ->
      PluginInfo(
        id = p.id,
        doc = p.doc,
        builtins = p.reg.builtinInfo.sortedBy { it.name },
        effects =
          p.reg.effects
            .map { PluginMemberInfo(it.name, it.doc, it.params, "effect") }
            .sortedBy { it.name },
        commands =
          p.reg.commands
            .map { PluginCommandInfo(it.name, it.lane, it.role, it.doc, it.category) }
            .sortedBy { it.name },
        gmcp =
          (p.reg.gmcp.map { PluginGmcpInfo(it.pkg, it.doc, "state") } +
              p.reg.gmcpEvents.map { PluginGmcpInfo(it.pkg, it.doc, "event") } +
              p.reg.gmcpInputs.map { PluginGmcpInfo(it.pkg, it.doc, "input") })
            .sortedWith(compareBy({ it.pkg }, { it.kind })),
        hooks = p.reg.hooks.map { PluginHookInfo(it.name, it.doc) }.sortedBy { it.name },
        traits =
          p.reg.traits.values
            .map {
              PluginTraitInfo(
                name = it.name,
                label = it.label,
                doc = it.doc,
                statDeltas = it.statDeltas,
                maxVitalDeltas = it.maxVitalDeltas,
                resistDeltas = it.resistDeltas,
                flags = it.flags,
                modifiers = it.modifiers.keys.map { m -> m.name }.toSet(),
                senseDeltas = it.senseDeltas,
              )
            }
            .sortedBy { it.name },
        rulesExports = p.reg.rulesExports.values.sortedBy { it.name },
        restrictions =
          p.reg.restrictions.flatMap { (point, list) ->
            list.map { (doc, _) -> PluginRestrictionInfo(point.name.lowercase(), doc) }
          },
        containmentFilters = p.reg.filters.map { (doc, _) -> PluginFilterInfo(doc) },
        ownedBehaviors = p.reg.ownedBehaviors.map { (doc, _) -> PluginOwnedBehaviorsInfo(doc) },
        targetOrders = p.reg.targetOrders.map { (doc, _) -> PluginTargetOrderInfo(doc) },
        chainThinks = p.reg.chainThinks.map { (doc, _) -> PluginChainThinkInfo(doc) },
        dispositionModifiers =
          p.reg.dispositionModifiers.map { (doc, _) -> PluginDispositionModifierInfo(doc) },
        factions = p.reg.factionAdjusters.map { (doc, _) -> PluginFactionAdjusterInfo(doc) },
        choices =
          p.reg.creation?.choices?.map { c ->
            PluginChoiceInfo(
              id = c.id,
              title = c.title,
              prompt = c.prompt,
              options =
                c.options.map { o -> PluginOptionInfo(o.id, o.label, o.summary, o.help, o.traits) },
            )
          } ?: emptyList(),
        steps =
          p.reg.creation?.steps?.map { s -> PluginStepInfo(id = s.id, title = s.title) }
            ?: emptyList(),
      )
    }

  override fun close() {
    for (p in plugins) runCatching { p.loader?.close() }
  }

  companion object {
    /** A host with these plugins, already constructed, for tests (no jars, no class loaders). */
    fun of(
      vararg plugins: GupsPlugin,
      schema: WorldSchema = WorldSchema.EMPTY,
      features: Features = Features.ALL,
      log: (String) -> Unit = {},
    ): PluginHost =
      PluginLoader.register(
        plugins.map { it to null },
        log,
        engine = PluginLoader.EngineContext(features = features, schema = schema),
      )
  }
}
