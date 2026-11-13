package gups.script

import gups.core.EngineInfo
import gups.core.Label
import gups.core.model.AffectScope
import gups.core.model.ContainerProto
import gups.core.model.DamageType
import gups.core.model.Direction
import gups.core.model.DoorProto
import gups.core.model.DoorState
import gups.core.model.ExitProto
import gups.core.model.FactionProto
import gups.core.model.ItemAffect
import gups.core.model.LootRule
import gups.core.model.RegionProto
import gups.core.model.SpawnRule
import gups.core.model.StandingDelta
import gups.core.model.WorldSchema
import gups.core.world.ENGINE_GMCP_PACKAGES
import gups.core.world.Features
import gups.core.world.GMCP_PACKAGE_NAME
import gups.core.world.Lane
import gups.core.world.gmcpOverlaps
import gups.core.world.gmcpWithin
import gups.script.DeclarationSpecs.CONTAINER_SPEC
import gups.script.DeclarationSpecs.DOOR_SPEC
import gups.script.DeclarationSpecs.EXIT_SPEC
import gups.script.DeclarationSpecs.FACTION_SPEC
import gups.script.DeclarationSpecs.GLOBAL_COMMAND_SPEC
import gups.script.DeclarationSpecs.GLOBAL_HOOK_SPEC
import gups.script.DeclarationSpecs.GMCP_PACKAGE_SPEC
import gups.script.DeclarationSpecs.ITEM_AFFECT_SPEC
import gups.script.DeclarationSpecs.ITEM_SPEC
import gups.script.DeclarationSpecs.LOOT_SPEC
import gups.script.DeclarationSpecs.MOB_SPEC
import gups.script.DeclarationSpecs.OUTCOME_SPEC
import gups.script.DeclarationSpecs.PACKAGE_SPEC
import gups.script.DeclarationSpecs.PROC_SPEC
import gups.script.DeclarationSpecs.REGION_SPEC
import gups.script.DeclarationSpecs.ROOM_SPEC
import gups.script.DeclarationSpecs.SKILL_SPEC
import gups.script.DeclarationSpecs.SPAWN_SPEC
import gups.script.DeclarationSpecs.STANDING_DELTA_SPEC
import gups.script.DeclarationSpecs.STRUCT_SPEC
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.Tuple

// The content declarations of a package. They are only callable while a
// package's files evaluate; each one produces a frozen Kotlin prototype.

private val GLOBAL_HOOK_NAMES =
  listOf(
    "on_level_change",
    "on_encumbrance_change",
    "on_group_change",
    "on_owner_change",
    "on_disengage",
    "on_death",
    "on_skill_use",
  )

/**
 * The declaration builtins, plus the effect primitives and constants. Effect primitives work
 * anywhere; everything else only while a package loads.
 */
internal class DeclarationBuiltins(
  private val schema: WorldSchema = WorldSchema.EMPTY,
  private val features: Features = Features.ALL,
  private val knownTraits: () -> Set<String> = { emptySet() },
) {
  /** Fails with "<name> needs the <feature> feature" if the world has [symbol]'s feature off. */
  private fun need(symbol: String, name: String = symbol) = requireFeature(features, symbol, name)

  @StarlarkMethod(
    name = "package",
    doc = "Declares the package. Must be called exactly once, in package.star.",
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun pkg(kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = loadCtx(thread, "package")
    if (!ctx.header) fail("package() can only be called in package.star")
    if (ctx.packageDeclared) fail("package() is called more than once")
    ctx.packageDeclared = true
    val p = ctx.pkg
    return PACKAGE_SPEC.bind(
      kwargs = kwargs,
      features = features,
      beforeUnknownCheck = { parsed ->
        p.apiVersion = parsed.int("api_version") ?: fail("package(): api_version is required")
        // Checked first: a package for another version may use arguments this engine doesn't know.
        if (p.apiVersion != EngineInfo.API_VERSION)
          ApiVersionMismatch(p.apiVersion).let { throw EvalException(it.message, it) }
      },
    ) { d ->
      fillPackage(p, d, features)
      Starlark.NONE
    }
  }

  @StarlarkMethod(
    name = "room",
    doc = "Declares a room.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun room(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "room")
    return ROOM_SPEC.bind(args, kwargs, features = features) { d ->
      val proto = parseRoom(d, ctx, thread, features)
      ctx.pkg.roomList += proto
      registerHooks(ctx, proto.label, d)
      Starlark.NONE
    }
  }

  @StarlarkMethod(
    name = "exit",
    doc = "An exit, for room(exits=[...]).",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun exit(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "exit")
    return EXIT_SPEC.bind(args, kwargs, features = features) { d ->
      val dir = Direction.parse(d.reqStr("direction", "exit() direction"))
      if (!Regex("[a-z][a-z0-9_]*").matches(dir.name))
        fail("exit(): '${dir.name}' is not a valid exit name")
      if (dir.name in ctx.engineVerbs)
        fail("exit(): '${dir.name}' is a reserved keyword: it is an engine command")
      val to = label(d.reqStr("to", "exit() destination"), ctx.pkg.path, d.what("to"))
      val proto =
        ExitProto(
          direction = dir,
          to = to,
          entryIndex = d.int("entry_index") ?: 0,
          tags = parseTags(d),
          door =
            d["door"]?.let {
              (it as? DoorDecl)?.proto
                ?: fail("exit(door): expected door(...), got ${typeName(it)}")
            },
          oneWay = d.bool("one_way") ?: false,
        )
      if (proto.entryIndex < -1) fail("exit(entry_index): must be -1 (the back) or a track index")
      ExitDecl(proto)
    }
  }

  @StarlarkMethod(
    name = "door",
    doc = "A door, for exit(door=...).",
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun door(kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "door")
    return DOOR_SPEC.bind(kwargs = kwargs, features = features) { d ->
      val def = DoorProto()
      val proto =
        DoorProto(
          state = d.choice("state")?.let { DoorState.valueOf(it.uppercase()) } ?: def.state,
          key = d.str("key")?.let { label(it, ctx.pkg.path, d.what("key")) },
          tags = parseTags(d),
        )
      if (proto.state == DoorState.LOCKED && proto.key == null)
        fail("door(state): a locked door needs a key")
      DoorDecl(proto)
    }
  }

  @StarlarkMethod(
    name = "spawn",
    doc = "A spawn rule, for room(spawns=[...]).",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun spawn(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "spawn")
    return SPAWN_SPEC.bind(args, kwargs, features = features) { d ->
      val def = SpawnRule(Label("//engine:none"))
      val rule =
        SpawnRule(
          label = label(d.reqStr("label", "spawn() label"), ctx.pkg.path, "spawn() label"),
          count = d.int("count") ?: def.count,
          respawnPulses = d.int("respawn_pulses") ?: def.respawnPulses,
          atIndex = d.int("at_index") ?: def.atIndex,
        )
      if (rule.count < 1) fail("spawn(count): must be at least 1")
      if (rule.respawnPulses < 1) fail("spawn(respawn_pulses): must be at least 1")
      SpawnDecl(rule)
    }
  }

  @StarlarkMethod(
    name = "loot",
    doc = "A loot roll, for mob(loot=[...]). chance is a percent from 0 to 100.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun loot(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "loot")
    return LOOT_SPEC.bind(args, kwargs, features = features) { d ->
      val rule =
        LootRule(
          label = label(d.reqStr("label", "loot() label"), ctx.pkg.path, "loot() label"),
          chance = d.percent("chance") ?: 100,
          count = d.int("count") ?: 1,
        )
      if (rule.count < 1) fail("loot(count): must be at least 1")
      LootDecl(rule)
    }
  }

  @StarlarkMethod(
    name = "container",
    doc = "Container settings, for item(container=...).",
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun container(kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "container")
    return CONTAINER_SPEC.bind(kwargs = kwargs, features = features) { d ->
      val def = ContainerProto()
      val proto =
        ContainerProto(
          capacity = d.int("capacity") ?: def.capacity,
          maxWeight = d.int("max_weight") ?: def.maxWeight,
          state = d["state"]?.let { doorState(it, d.what("state")) } ?: def.state,
          key = d.str("key")?.let { label(it, ctx.pkg.path, d.what("key")) },
        )
      if (proto.state == DoorState.LOCKED && proto.key == null)
        fail("container(state): a locked container needs a key")
      ContainerDecl(proto)
    }
  }

  @StarlarkMethod(
    name = "item_affect",
    doc = "An affect an item carries, for item(affects=[...]).",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun itemAffect(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    contentCtx(thread, "item_affect")
    return ITEM_AFFECT_SPEC.bind(args, kwargs, features = features) { d ->
      val name = d.reqStr("name", "item_affect() name")
      if (name.isBlank()) fail("item_affect(name): can't be blank")
      val applies =
        d.choice("applies")?.let { AffectScope.valueOf(it.uppercase()) } ?: AffectScope.WORN
      val statDeltas = typed(d["stat_deltas"], TypedKind.STATS, d.what("stat_deltas"))
      val senseDeltas = senses(d["sense_deltas"], d.what("sense_deltas"), schema, true)
      val maxVitalDeltas =
        typed(d["max_vital_deltas"], TypedKind.VITALS, d.what("max_vital_deltas"))
      val resistDeltas =
        typed(d["resist_deltas"], TypedKind.RESISTS, d.what("resist_deltas")).mapKeys { (k, _) ->
          DamageType(k)
        }
      val modifiers = modifiers(d["modifiers"], d.what("modifiers"), features)
      val rawFlags = d.strList("flags").map { flag(it, d.what("flags")) }
      if ("stunned" in rawFlags)
        fail("item_affect(flags): 'stunned' isn't allowed; item affects can't stun")
      val procs = procList(d["procs"], "item_affect(procs)")
      ItemAffectDecl(
        ItemAffect(
          name = name,
          applies = applies,
          statDeltas = statDeltas,
          senseDeltas = senseDeltas,
          maxVitalDeltas = maxVitalDeltas,
          resistDeltas = resistDeltas,
          modifiers = modifiers,
          flags = rawFlags.toSortedSet(),
          procs = procs,
        )
      )
    }
  }

  @StarlarkMethod(
    name = "outcome",
    doc = "A weighted outcome in a proc's outcome table.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun outcome(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    need("outcome", "procs")
    val parser = procParser(thread)
    return OUTCOME_SPEC.bind(args, kwargs, features = features) { d ->
      OutcomeDecl(parser.outcome(d))
    }
  }

  private fun procParser(thread: StarlarkThread): ProcParser {
    val loadCtx = thread.getThreadLocal(LoadCtx::class.java)
    val pkg = loadCtx?.pkg?.path ?: callerFile(thread)?.pkg
    return ProcParser(features, loadCtx, pkg, thread)
  }

  @StarlarkMethod(
    name = "proc",
    doc = "A combat proc that fires during auto-attack swings.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun proc(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    need("proc", "procs")
    val parser = procParser(thread)
    return PROC_SPEC.bind(args, kwargs, features = features) { d -> ProcDecl(parser.proc(d)) }
  }

  @StarlarkMethod(
    name = "mob",
    doc = "Declares a mob prototype.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun mob(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "mob")
    return MOB_SPEC.bind(args, kwargs, features = features) { d ->
      val proto = MobDeclParser(d, ctx, thread, schema, features, knownTraits).parse()
      ctx.pkg.mobList += proto
      registerHooks(ctx, proto.label, d)
      Starlark.NONE
    }
  }

  @StarlarkMethod(
    name = "item",
    doc = "Declares an item prototype.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun item(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "item")
    return ITEM_SPEC.bind(args, kwargs, features = features) { d ->
      val proto = ItemDeclParser(d, ctx, thread, schema, features).parse()
      ctx.pkg.itemList += proto
      registerHooks(ctx, proto.label, d)
      Starlark.NONE
    }
  }

  @StarlarkMethod(
    name = "skill",
    doc = "Declares a skill or spell.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun skill(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "skill")
    need("skill", "skill()")
    return SKILL_SPEC.bind(args, kwargs, features = features) { d ->
      ctx.pkg.skillList += SkillDeclParser(d, ctx, thread, schema, features).parse()
      Starlark.NONE
    }
  }

  @StarlarkMethod(
    name = "region",
    doc =
      "Declares the modifiers of a region, a tag rooms carry with room(regions=[...]). Region names are world-wide.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun region(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "region")
    return REGION_SPEC.bind(args, kwargs, features = features) { d ->
      val id = declName("region", d, ctx)
      if (!Regex("[a-z0-9_]+").matches(id)) fail("region(): '$id' is not a valid region name")
      val proto =
        RegionProto(id, ctx.pkg.path, modifiers(d["modifiers"], d.what("modifiers"), features))
      ctx.pkg.regionList += proto
      Starlark.NONE
    }
  }

  @StarlarkMethod(
    name = "faction",
    doc = "Declares a faction and its declared opinion of other factions.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun faction(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "faction")
    need("faction", "faction()")
    return FACTION_SPEC.bind(args, kwargs, features = features) { d ->
      val id = declName("faction", d, ctx)
      val pkg = ctx.pkg.path
      val label = Label("//$pkg:$id")
      val standing =
        d.dict("standing").entries.associate { (k, v) ->
          val target = label(k, pkg, d.what("standing"))
          ctx.factionRef(target, thread)
          val opinion = int(v, "faction(standing[\"$k\"])")
          if (opinion !in -100..100) fail("faction(): standing of $target must be in -100..100")
          target to opinion
        }
      val proto = FactionProto(label = label, standing = standing, doc = d.str("doc") ?: "")
      ctx.pkg.factionList += proto
      Starlark.NONE
    }
  }

  @StarlarkMethod(
    name = "standing_delta",
    doc = "Builds a standing delta for rules.standing_change.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun standingDelta(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val marker = standingChangeCtx(thread, "standing_delta")
    need("standing_delta", "standing_delta()")
    return STANDING_DELTA_SPEC.bind(args, kwargs, features = features) { d ->
      val holder = d["holder"] ?: fail("standing_delta(): 'holder' is required")
      val subject = d["subject"] ?: fail("standing_delta(): 'subject' is required")
      val holderObj = toStandingHolder(holder, marker.pkg, "standing_delta", marker.protos)
      val subjectObj =
        toStandingSubject(subject, marker.pkg, "standing_delta", marker.protos, holderObj)
      val amt = d.int("amount") ?: fail("standing_delta(): amount must be an int")
      StandingDeltaValue(StandingDelta(holderObj, subjectObj, amt))
    }
  }

  @StarlarkMethod(
    name = "global_command",
    doc = "Registers a command available everywhere. lib and rules packages only.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun globalCommand(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "global_command")
    return GLOBAL_COMMAND_SPEC.bind(args, kwargs, features = features) { d ->
      if (!ctx.pkg.kind.privileged)
        fail("global_command(): only lib and rules packages may register global commands")
      val verb = d.reqStr("name", "global_command() name").lowercase()
      if (!Regex("[a-z][a-z0-9_]*").matches(verb))
        fail("global_command(): '$verb' is not a valid verb")
      val l = if ((d.choice("lane") ?: "fast") == "fast") Lane.FAST else Lane.TICK
      if (ctx.pkg.commandList.any { it.name == verb })
        fail("global_command(): '$verb' is already registered")
      ctx.pkg.commandList +=
        GlobalCommandDecl(verb, hookRef(d["fn"], "global_command(fn)"), l, ctx.pkg.path)
      Starlark.NONE
    }
  }

  @StarlarkMethod(
    name = "global_hook",
    doc =
      "Registers a hook that fires for every actor, wherever it is. lib and rules packages only.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun globalHook(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "global_hook")
    return GLOBAL_HOOK_SPEC.bind(args, kwargs, features = features) { d ->
      if (!ctx.pkg.kind.privileged)
        fail("global_hook(): only lib and rules packages may register global hooks")
      val name = d.reqStr("name", "global_hook() name")
      if (name !in GLOBAL_HOOK_NAMES) {
        fail(
          "global_hook(): '$name' can't be a global hook; only ${GLOBAL_HOOK_NAMES.dropLast(1).joinToString(", ")} and ${GLOBAL_HOOK_NAMES.last()} can"
        )
      }
      val fn = hookRef(d["fn"], "global_hook(fn)")
      if (ctx.pkg.hookList.any { it.hook == name && it.fn == fn })
        fail("global_hook(): '$name' is already registered")
      ctx.pkg.hookList += GlobalHookDecl(name, fn, ctx.pkg.path)
      Starlark.NONE
    }
  }

  @StarlarkMethod(
    name = "gmcp_package",
    doc = "Declares a GMCP package this package sends with ctx.gmcp. lib and rules packages only.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun gmcpPackage(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "gmcp_package")
    return GMCP_PACKAGE_SPEC.bind(args, kwargs, features = features) { d ->
      if (!ctx.pkg.kind.privileged)
        fail("gmcp_package(): only lib and rules packages may declare GMCP packages")
      val name = d.reqStr("name", "gmcp_package() name")
      if (!GMCP_PACKAGE_NAME.matches(name))
        fail("gmcp_package(): '$name' needs at least two dot-separated parts, such as Shop.Stock")
      SCRIPT_RESERVED_GMCP_ROOTS.firstOrNull { gmcpWithin(name, it) }
        ?.let { fail("gmcp_package(): '$name' uses the reserved root $it") }
      ENGINE_GMCP_PACKAGES.firstOrNull { gmcpOverlaps(name, it) }
        ?.let { fail("gmcp_package(): '$name' overlaps the engine's $it") }
      ctx.pkg.gmcpList
        .firstOrNull { gmcpOverlaps(name, it.name) }
        ?.let { fail("gmcp_package(): '$name' overlaps '${it.name}', already declared here") }
      ctx.pkg.gmcpList += GmcpPackageDecl(name, d.str("doc") ?: "", ctx.pkg.path)
      Starlark.NONE
    }
  }

  @StarlarkMethod(
    name = "struct",
    doc =
      "An immutable record of named fields, for example a library's exported functions: struct(start = _start).",
    extraKeywords = Param(name = "kwargs"),
  )
  fun struct(kwargs: Dict<String, Any>): Any {
    return STRUCT_SPEC.bind(kwargs = kwargs, features = features) {
      StructValue(LinkedHashMap(kwargs))
    }
  }

  companion object {
    val CONSTANTS: Map<String, Any>
      get() = DeclarationSpecs.CONSTANTS

    val CONSTANT_SPECS: List<ConstantSpec>
      get() = DeclarationSpecs.CONSTANT_SPECS

    val EFFECT_NAMES: Set<String>
      get() = DeclarationSpecs.EFFECT_NAMES

    val BEHAVIOR_NAMES: Set<String>
      get() = DeclarationSpecs.BEHAVIOR_NAMES

    val DECLARATION_SPECS: List<DeclSpec>
      get() = DeclarationSpecs.DECLARATION_SPECS

    /** Constants available to every package file. */
    fun constants(schema: WorldSchema): Map<String, Any> = DeclarationSpecs.constants(schema)
  }
}
