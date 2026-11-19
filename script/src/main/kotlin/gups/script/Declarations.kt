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
import gups.core.model.Respawn
import gups.core.model.SpawnRule
import gups.core.model.SpawnerProto
import gups.core.model.StandingDelta
import gups.core.model.Until
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
import gups.script.DeclarationSpecs.SPAWNER_SPEC
import gups.script.DeclarationSpecs.SPAWN_SPEC
import gups.script.DeclarationSpecs.STANDING_DELTA_SPEC
import gups.script.DeclarationSpecs.STRUCT_SPEC
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkList
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
    "on_despawn",
    "on_destroy",
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
      val dir = parseExitDir(d, ctx.engineVerbs)
      val rawTo = d["to"]
      val to = if (rawTo is String) label(rawTo, ctx.pkg.path, d.what("to")) else null
      val candidates =
        if (rawTo is String) emptyMap() else parseExitCandidates(rawTo, ctx.pkg.path, d.what("to"))
      val door = parseExitDoor(d, candidates.isNotEmpty())
      val pick = parseExitPick(d, to != null, ctx.pkg.path)
      val proto =
        ExitProto(
          direction = dir,
          to = to,
          entryIndex = d.int("entry_index") ?: 0,
          tags = parseTags(d),
          door = door,
          oneWay = d.bool("one_way") ?: candidates.isNotEmpty(),
          candidates = candidates,
          pick = pick,
        )
      if (proto.entryIndex < -1) fail("exit(entry_index): must be -1 (the back) or a track index")
      ExitDecl(proto)
    }
  }

  private fun parseExitDir(d: ParsedDecl, engineVerbs: Set<String>): Direction {
    val dir = Direction.parse(d.reqStr("direction", "exit() direction"))
    if (!Regex("[a-z][a-z0-9_]*").matches(dir.name)) {
      fail("exit(): '${dir.name}' is not a valid exit name")
    }
    if (dir.name in engineVerbs) {
      fail("exit(): '${dir.name}' is a reserved keyword: it is an engine command")
    }
    return dir
  }

  private fun parseExitDoor(d: ParsedDecl, perCrossing: Boolean): DoorProto? {
    val door =
      d["door"]?.let {
        (it as? DoorDecl)?.proto ?: fail("exit(door): expected door(...), got ${typeName(it)}")
      }
    if (perCrossing && door != null) {
      fail("exit(door): an exit whose destination is picked per crossing can't have a door")
    }
    return door
  }

  private fun parseExitPick(d: ParsedDecl, hasSingleTo: Boolean, pkg: String): ScriptExitPicker? {
    val pickVal = d["pick"]?.takeUnless(::isNone) ?: return null
    if (hasSingleTo) fail("exit(pick): needs a list or dict of rooms in to")
    return ScriptExitPicker(hookRef(pickVal, d.what("pick")), pkg)
  }

  private class RoomPoolSpec(
    val minRooms: Int,
    val minError: String,
    val typeError: (Any?) -> String,
    val dupError: (Label) -> String,
  )

  private fun parseExitCandidates(raw: Any?, pkg: String, what: String): Map<Label, Int> =
    parseWeightedRooms(raw, pkg, what, EXIT_POOL_SPEC)

  private fun parseWeightedRooms(
    raw: Any?,
    pkg: String,
    what: String,
    spec: RoomPoolSpec,
  ): Map<Label, Int> {
    val entries: List<Pair<Label, Int>> =
      when (raw) {
        is StarlarkList<*> -> raw.map { elem -> label(str(elem, what), pkg, what) to 1 }
        is Dict<*, *> -> raw.entries.map { (k, v) -> parseRoomWeight(k, v, pkg, what) }
        else -> fail(spec.typeError(raw))
      }
    if (entries.size < spec.minRooms) fail(spec.minError)
    // Rolling 1..total must stay inside Int.
    if (entries.sumOf { it.second.toLong() } >= Int.MAX_VALUE) {
      fail("$what: weights must add up to less than ${Int.MAX_VALUE}")
    }
    val out = LinkedHashMap<Label, Int>(entries.size)
    for ((lbl, weight) in entries) {
      if (lbl in out) fail(spec.dupError(lbl))
      out[lbl] = weight
    }
    return out
  }

  private fun parseRoomWeight(
    rawKey: Any?,
    rawWeight: Any?,
    pkg: String,
    what: String,
  ): Pair<Label, Int> {
    val lbl = label(str(rawKey, what), pkg, what)
    val weight = int(rawWeight, what)
    if (weight < 1) fail("$what: weight for $lbl must be a positive int")
    return lbl to weight
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
      val id = d.str("id")?.let { label(":$it", ctx.pkg.path, "spawn(id)") }
      SpawnDecl(parseSpawnRule(d, ctx.pkg.path, id))
    }
  }

  @StarlarkMethod(
    name = "spawner",
    doc =
      "Declares a pool spawn rule that places copies across a set of rooms. Zone packages only.",
    extraPositionals = Param(name = "args"),
    extraKeywords = Param(name = "kwargs"),
    useStarlarkThread = true,
  )
  fun spawner(args: Tuple, kwargs: Dict<String, Any>, thread: StarlarkThread): Any {
    val ctx = contentCtx(thread, "spawner")
    if (ctx.pkg.kind != PackageKind.ZONE) {
      fail("spawner(): only zone packages declare spawners")
    }
    return SPAWNER_SPEC.bind(args, kwargs, features = features) { d ->
      val id = label(":${d.reqStr("id", "spawner() id")}", ctx.pkg.path, "spawner() id")
      val rooms = parseSpawnerRooms(d["rooms"], ctx.pkg.path, d.what("rooms"))
      val pick = d["pick"]?.let { ScriptPoolPicker(hookRef(it, d.what("pick")), ctx.pkg.path) }
      val rule = parseSpawnRule(d, ctx.pkg.path, id)
      if (rule.atIndex < 0) fail("spawner(at_index): must be at least 0")
      if (!ctx.pkg.spawnRuleIds.add(id)) fail("spawner(id): $id is already a spawn rule id")
      for (roomLabel in rooms.keys) ctx.ref(roomLabel, RefKind.ROOM, thread)
      ctx.spawnRef(rule, thread)
      rule.container?.let { ctx.ref(it, RefKind.ITEM, thread) }
      ctx.pkg.spawnerList += SpawnerProto(id = id, rule = rule, rooms = rooms, pick = pick)
      Starlark.NONE
    }
  }

  private fun parseSpawnRule(d: ParsedDecl, pkg: String, id: Label?): SpawnRule {
    val def = SpawnRule(Label("//engine:none"))
    val max = parseSpawnMax(d, def.max)
    val batch = d["batch"]?.let { parseRoll(it, d.what("batch"), 1) }
    val respawn = parseSpawnRespawn(d, def.respawn)
    val until = d.choice("until")?.let { Until.valueOf(it.uppercase()) }
    val stack = d["stack"]?.let { parseRoll(it, d.what("stack"), 1) }
    val container = d.str("container")?.let { label(it, pkg, d.what("container")) }
    val atIndex = d.int("at_index") ?: def.atIndex
    val onPlace = d["on_place"]?.let { ScriptPlaceHook(hookRef(it, d.what("on_place")), pkg) }
    return SpawnRule(
      label = label(d.reqStr("label", "${d.fn}() label"), pkg, "${d.fn}() label"),
      id = id,
      max = max,
      batch = batch,
      respawn = respawn,
      until = until,
      stack = stack,
      container = container,
      atIndex = atIndex,
      onPlace = onPlace,
    )
  }

  private fun parseSpawnerRooms(raw: Any?, pkg: String, what: String): Map<Label, Int> =
    parseWeightedRooms(raw, pkg, what, SPAWNER_POOL_SPEC)

  private fun parseSpawnMax(d: ParsedDecl, default: Int): Int {
    val max = d.int("max") ?: return default
    if (max < 1) fail("${d.fn}(max): must be at least 1")
    return max
  }

  private fun parseSpawnRespawn(d: ParsedDecl, default: Respawn): Respawn {
    val respawnRaw = d["respawn"] ?: return default
    return when (respawnRaw) {
      "never" -> Respawn.Never
      "reset" -> Respawn.Reset
      is String ->
        fail("${d.fn}(respawn): expected pulses, a (min, max) range, \"never\" or \"reset\"")
      else -> Respawn.Timed(parseRoll(respawnRaw, d.what("respawn"), 1))
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
    private val EXIT_POOL_SPEC =
      RoomPoolSpec(
        minRooms = 2,
        minError = "exit(to): a list or dict needs at least two rooms",
        typeError = {
          "exit(to): expected a room label, a list of labels or a dict of label to weight, got ${typeName(it)}"
        },
        dupError = { "exit(to): room $it is listed twice" },
      )

    private val SPAWNER_POOL_SPEC =
      RoomPoolSpec(
        minRooms = 1,
        minError = "spawner(rooms): a list or dict needs at least one room",
        typeError = {
          "spawner(rooms): expected a list of labels or a dict of label to weight, got ${typeName(it)}"
        },
        dupError = { "spawner(rooms): $it is listed twice" },
      )

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
