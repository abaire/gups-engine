package gups.script

import gups.core.AttrValue
import gups.core.EntityId
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.CreationProvider
import gups.core.model.DamageType
import gups.core.model.Direction
import gups.core.model.DoorProto
import gups.core.model.ExitProto
import gups.core.model.Interrupt
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import gups.core.model.SkillProto
import gups.core.model.WorldSchema
import gups.core.model.didYouMean
import gups.core.rules.Contributor
import gups.core.rules.RulesContext
import gups.core.world.BUILTIN_STANDING_EVENTS
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.core.world.disposition
import gups.core.world.dispositionParts

/** What the dry run needs to know about the world it stands in: its features and plugins. */
internal class DryRunEnv(
  val features: Features = Features.ALL,
  val wiring: WorldPluginWiring = WorldPluginWiring.NONE,
)

/**
 * The load-time dry run of the `//rules` package in [result].
 *
 * Every export the rules package defines is exercised against synthetic actors to verify that its
 * return types match declarations and do not throw runtime exceptions.
 *
 * It sets up a throwaway single-room world (`//engine:dry_run`) with one north exit holding a door
 * that leads to itself, and spawns two synthetic players (`Dryrunner` and `Dryrunfoe`), one
 * synthetic mob per loaded mob prototype, and a player for every option of every declared creation
 * choice. See [DryRun] for what is exercised.
 *
 * Returns what went wrong, one line per failed call, and the slots and families that items' `wear`
 * is checked against.
 */
internal fun dryRunRules(
  result: LoadResult,
  schema: WorldSchema,
  limits: ScriptLimits,
  env: DryRunEnv = DryRunEnv(),
): RulesDryRun {
  val rules = StarlarkHost(result, limits).rules("rules")
  val problems = mutableListOf<String>()
  rules.collected = problems
  val room =
    RoomProto(
      Label("//engine:dry_run"),
      "The dry run",
      exits = listOf(ExitProto(Direction.NORTH, Label("//engine:dry_run"), door = DoorProto())),
    )
  val protos =
    Prototypes(
      rooms = listOf(room),
      mobs = result.prototypes().mobs.values.toList(),
      factions = result.prototypes().factions.values.toList(),
    )
  val world =
    World(
        protos,
        schema,
        rules,
        config = WorldConfig(seed = 0, strict = false, features = env.features),
      )
      .also {
        rules.world = it
        env.wiring.install(it)
      }
  var wearSlots: WearSlots? = null
  try {
    wearSlots = dryRunSlots(rules, problems)
    DryRun(result, rules, world, env, problems, room.label).exercise()
  } catch (e: RuntimeException) {
    problems += "the dry run could not set up its actors: ${e.message}"
  } finally {
    rules.collected = null
  }
  return RulesDryRun(problems.distinct(), wearSlots)
}

/** What [dryRunRules] found. */
internal class RulesDryRun(
  /** What went wrong, one line per failed call; empty when the package is sound. */
  val problems: List<String>,
  /** The slots and families items may be worn in, or null if the rules got them wrong. */
  val wearSlots: WearSlots?,
)

/**
 * The names an item's `wear` may use: [slots] from `equipment_slots()`, [families] from
 * `slot_families()`.
 */
internal class WearSlots(val slots: List<String>, val families: Map<String, List<String>>) {
  val names: Set<String> = slots.toSet() + families.keys
}

private val SLOT_NAME = Regex("[a-z][a-z0-9_]*")

/**
 * Checks `equipment_slots()` and `slot_families()` and adds what is wrong to [problems]. Returns
 * the slots and families, or null if either is wrong, so items aren't blamed for the rules'
 * mistake.
 */
private fun dryRunSlots(rules: StarlarkRules, problems: MutableList<String>): WearSlots? {
  val before = problems.size
  val slots = rules.equipmentSlots()
  val families = rules.slotFamilies()
  val owner = mutableMapOf<String, String>()
  for ((name, familySlots) in families) {
    problems += familyProblems(name, familySlots, slots, owner)
  }
  return if (problems.size == before) WearSlots(slots, families) else null
}

/**
 * What is wrong with the family [name] of [familySlots]; [owner] records which family has a slot.
 */
private fun familyProblems(
  name: String,
  familySlots: List<String>,
  slots: List<String>,
  owner: MutableMap<String, String>,
): List<String> {
  val what = familyWhat(name)
  val found = mutableListOf<String>()
  if (!SLOT_NAME.matches(name)) {
    found +=
      "$what is not a valid family name; use lowercase letters, digits and underscores, starting with a letter"
  }
  if (name in slots) {
    found += "$what has the name of a slot in equipment_slots(); give the family its own name"
  }
  if (familySlots.isEmpty()) found += "$what lists no slots"
  found += slotProblems(name, familySlots, slots, owner)
  return found
}

/**
 * How a dry-run problem names [a]: a mob by its prototype, since that is what an author can find.
 */
private fun dryRunActor(a: Actor): String =
  if (a is Mob) "mob ${a.proto.label}" else "player ${a.name}"

/**
 * Calls the exports of [rules] in [world] with synthetic contexts, one group of exports per method.
 * Failures land in [problems] through the rules' own collection.
 */
private class DryRun(
  private val result: LoadResult,
  private val rules: StarlarkRules,
  private val world: World,
  env: DryRunEnv,
  private val problems: MutableList<String>,
  private val at: Label,
) {
  private val features = env.features
  private val wiring = env.wiring
  private val skill =
    SkillProto(Label("//engine:dry_run_skill"), interrupt = Interrupt(damagePct = 10))

  private val player: Player = world.spawnPlayer("Dryrunner", at)
  private val foe: Player = world.spawnPlayer("Dryrunfoe", at)
  private val mobs: List<Mob> = world.protos.mobs.keys.map { world.spawnMob(it, world.room(at)) }

  // A second xp_share contributor that is never the victim, so a split runs over two entries.
  private val ally: Player? =
    if (Feature.LEVELING in features) world.spawnPlayer("Dryrunally", at) else null
  private val actors: List<Actor> = listOf(player, foe) + mobs

  fun exercise() {
    world.creation?.let(::spawnCreationVariants)
    exerciseMob()
    exerciseStanding()
    exerciseSlots()
    if (Feature.LEVELING in features) exerciseLeveling()
    for (a in actors) exerciseActor(a)
  }

  /** One player for each option of each creation choice, the other choices answered by default. */
  private fun spawnCreationVariants(provider: CreationProvider) {
    for (c in provider.choices) {
      for (opt in c.options) {
        val answers = answersFor(provider, c.id, opt.id) ?: continue
        world.spawnPlayer("Dryrun_${c.id}_${opt.id}_${world.players.size}", at, choices = answers)
      }
    }
  }

  /** The answers that pick [optionId] for [choiceId], or null if that option isn't available. */
  private fun answersFor(
    provider: CreationProvider,
    choiceId: String,
    optionId: String,
  ): Map<String, String>? {
    val answers = mutableMapOf<String, String>()
    for (choice in provider.choices) {
      // The provider is the plugin's to fix; a path it can't answer is skipped.
      val avail =
        runCatching { provider.available(world, choice.id, answers) }.getOrDefault(emptyList())
      if (choice.id == choiceId) {
        if (optionId !in avail) return null
        answers[choice.id] = optionId
      } else {
        avail.firstOrNull()?.let { answers[choice.id] = it }
      }
    }
    return answers
  }

  /** The exports that only need some mob to ask about. */
  private fun exerciseMob() {
    val dryMob = mobs.firstOrNull()
    if (dryMob != null) exerciseMobExports(dryMob)
    exercisePluginExports()
  }

  private fun exerciseMobExports(dryMob: Mob) {
    if (rules.defines(RulesExport.OWNED_BEHAVIORS)) {
      rules.ownedBehaviors(RulesContext(world, dryMob, player, behaviors = dryMob.proto.behaviors))
    }
    if (rules.defines(RulesExport.LEASH_RETURN)) {
      rules.leashReturn(RulesContext(world, dryMob, null, away = 0L, pastLeash = false))
    }
    exerciseMobThinking(dryMob)
  }

  private fun exerciseMobThinking(dryMob: Mob) {
    if (Feature.SKILLS in features && rules.defines(RulesExport.RECOGNIZES_SKILL)) {
      rules.recognizesSkill(
        RulesContext(world, player, player, source = dryMob, skill = skill, room = player.room)
      )
    }
    if (rules.defines(RulesExport.MOVE_PACE)) {
      rules.movePace(RulesContext(world, dryMob, room = dryMob.room, exit = null))
    }
    if (rules.defines(RulesExport.CHAIN_THINK)) {
      rules.chainThink(
        RulesContext(world, dryMob, null, behavior = "pursue", command = "north", chain = 0)
      )
    }
    if (Feature.COMBAT in features && rules.defines(RulesExport.TARGET_ORDER)) {
      rules.targetOrder(targetOrderContext(dryMob))
    }
  }

  private fun targetOrderContext(dryMob: Mob) =
    RulesContext(
      world,
      actor = dryMob,
      room = dryMob.room,
      behavior = "aggressive",
      prefer = AttrValue.S("dry_run"),
      candidates = listOf(player),
      dispositions =
        if (Feature.STANDING in features) listOf(world.disposition(dryMob, player)) else null,
    )

  private fun exercisePluginExports() {
    for ((name, type) in wiring.rulesExports) {
      if (!rules.definesPluginExport(name)) continue
      rules.pluginExport(
        name,
        type,
        RulesContext(
          world,
          actor = player,
          target = player,
          source = mobs.firstOrNull(),
          base = if (type == "bool") false else 0L,
          args = emptyMap(),
        ),
      )
    }
  }

  private fun exerciseStanding() {
    if (Feature.STANDING !in features) return
    val dryMob = mobs.firstOrNull() ?: return
    if (rules.defines(RulesExport.FACTIONS)) {
      rules.factions(RulesContext(world, dryMob))
      rules.factions(RulesContext(world, player))
    }
    if (rules.defines(RulesExport.DISPOSITION)) {
      val parts1 = world.dispositionParts(dryMob, player)
      rules.disposition(RulesContext(world, dryMob, player, dispositionParts = parts1))
      val parts2 = world.dispositionParts(player, dryMob)
      rules.disposition(RulesContext(world, player, dryMob, dispositionParts = parts2))
    }
    if (rules.defines(RulesExport.STANDING_CHANGE)) {
      for (evt in BUILTIN_STANDING_EVENTS) {
        rules.standingChange(
          RulesContext(
            world,
            actor = dryMob,
            target = player,
            event = evt,
            amount = if (evt in setOf("heal", "give")) 10L else null,
          )
        )
      }
    }
  }

  private fun exerciseSlots() {
    val slots = rules.equipmentSlots()
    for (a in actors) {
      val stray = rules.actorSlots(RulesContext(world, a)).filter { it !in slots }
      for (slot in stray) {
        problems +=
          "rules.actor_slots for ${dryRunActor(a)} returned '$slot', which is not in equipment_slots()${didYouMean(slot, slots)}; equipment_slots() has ${slots.joinToString(", ")}"
      }
    }
    exerciseSlotCost()
  }

  /** A slot_cost that fails usually fails for every actor and item alike, so stop at the first. */
  private fun exerciseSlotCost() {
    val wearable = result.prototypes().items.values.filter { it.wear != null }
    actors.any { a -> wearable.any { proto -> slotCostFails(a, proto) } }
  }

  /** Asks slot_cost about [proto] worn by [a]; true, after naming them, if the call failed. */
  private fun slotCostFails(a: Actor, proto: ItemProto): Boolean {
    val before = problems.size
    val item = Item(EntityId(0L), proto, ItemLocation.Carried(a.id))
    rules.slotCost(RulesContext(world, a, item = item))
    for (i in before until problems.size) {
      problems[i] += " (item ${proto.label}, worn by ${dryRunActor(a)})"
    }
    return problems.size > before
  }

  private fun exerciseLeveling() {
    // A curve that ignores the actor fails the same way for every actor, so stop at the first.
    actors.firstOrNull { !curveIsSound(it) }
    for (a in actors) {
      val originalLevel = a.level
      a.level = 2
      try {
        rules.onLevelUp(RulesContext(world, a))
      } finally {
        a.level = originalLevel
      }
    }
  }

  /** True unless xp_to_level fails or doesn't strictly increase for [a]; says why if not. */
  private fun curveIsSound(a: Actor): Boolean {
    val c = RulesContext(world, a)
    var prevXp: Long? = null
    for (level in 1..50) {
      val problemsBefore = problems.size
      val xp = rules.xpToLevel(c, level)
      if (problems.size > problemsBefore) return false
      if (prevXp != null && xp <= prevXp) {
        problems +=
          "rules.xp_to_level for ${a.name} is not strictly increasing: level $level ($xp) <= level ${level - 1} ($prevXp)"
        return false
      }
      prevXp = xp
    }
    return true
  }

  private fun exerciseActor(a: Actor) {
    val other = if (a === player) foe else player
    val c = RulesContext(world, a, other)
    rules.condition(c)
    if (Feature.COMBAT in features) exerciseCombat(a, c)
    rules.carryLimit(c)
    rules.encumbranceLevel(RulesContext(world, a, limit = 100, carried = 50))
    rules.encumbrance(RulesContext(world, a, limit = 100, carried = 150, encumbranceLevel = 1))
    rules.itemPower(RulesContext(world, a, other, event = "use"))
    rules.regen(c)
    rules.moveCost(c)
    if (Feature.TRACK in features) exerciseTrack(a, other, c)
    if (Feature.SKILLS in features) exerciseSkills(a, other)
    if (ally != null) exerciseXp(a, other, c)
    for (type in world.schema.damageTypes) {
      val damageType = DamageType(type)
      rules.mitigate(RulesContext(world, a, other, damageType = damageType), 5, damageType)
    }
    if (a is Player) rules.onPlayerDeath(RulesContext(world, a))
    exercisePerception(a, other)
    exerciseDoors(a)
  }

  private fun exerciseCombat(a: Actor, c: RulesContext) {
    rules.toHit(c)
    rules.damage(c)
    rules.initiative(c)
    rules.fleeChance(c)
    rules.fleeFailLag(RulesContext(world, a))
  }

  private fun exerciseTrack(a: Actor, other: Actor, c: RulesContext) {
    rules.retreatCheck(c)
    val melee = RulesContext(world, a, other, band = Band.MELEE)
    rules.advanceLag(melee)
    rules.retreatLag(melee)
    rules.retreatFailLag(melee)
    for (band in Band.entries) {
      rules.getLag(RulesContext(world, a, item = null, band = band), band)
    }
  }

  private fun exerciseSkills(a: Actor, other: Actor) {
    val withSkill = RulesContext(world, a, other, skill = skill)
    rules.toHit(withSkill)
    rules.castAllowed(withSkill)
    rules.interruptCheck(withSkill, 1)
    rules.skillCheck(withSkill)
    rules.skillPower(withSkill)
  }

  private fun exerciseXp(a: Actor, other: Actor, c: RulesContext) {
    val contributors =
      listOf(
          Contributor(a, 2, 0, 0, world.pulse, null, null, inRoom = true),
          Contributor(ally!!, 0, 1, 0, world.pulse, null, null, inRoom = true),
        )
        .sortedBy { it.actor.id }
    rules.xpForKill(c)
    rules.xpShare(RulesContext(world, a, other, xp = 100, contributors = contributors))
  }

  private fun exercisePerception(a: Actor, other: Actor) {
    val here = world.room(at)
    val exit = here.exits.getValue(Direction.NORTH)
    for (channel in world.schema.allChannels) {
      rules.senseCost(RulesContext(world, room = here, exit = exit, channel = channel))
      for (sourceRoom in listOf(here, null)) {
        val heard =
          RulesContext(
            world,
            a,
            a,
            room = here,
            channel = channel,
            event = "dry_run",
            intensity = 1,
            cost = if (sourceRoom == null) 1 else 0,
            sourceRoom = sourceRoom,
            viaExit = if (sourceRoom == null) Direction.NORTH else null,
            text = "dry run",
            source = other,
          )
        rules.canPerceive(heard)
        rules.perceiveMessage(heard)
      }
    }
  }

  private fun exerciseDoors(a: Actor) {
    val here = world.room(at)
    val exit = here.exits.getValue(Direction.NORTH)
    for (action in listOf("open", "close", "lock", "unlock")) {
      rules.doorIntensity(
        RulesContext(world, a, room = here, action = action, tags = setOf("heavy"), exit = exit)
      )
      rules.doorIntensity(RulesContext(world, a, room = here, action = action))
    }
  }
}

/** What is wrong with the [familySlots] that family [name] lists. */
private fun slotProblems(
  name: String,
  familySlots: List<String>,
  slots: List<String>,
  owner: MutableMap<String, String>,
): List<String> {
  val what = familyWhat(name)
  val found = mutableListOf<String>()
  val seen = mutableSetOf<String>()
  for (slot in familySlots) {
    if (!seen.add(slot)) {
      found += "$what lists slot '$slot' more than once"
      continue
    }
    if (slot !in slots) {
      found += "$what lists '$slot', which is not in equipment_slots()${didYouMean(slot, slots)}"
    }
    val earlier = owner.putIfAbsent(slot, name)
    if (earlier != null) {
      found +=
        "rules.slot_families: slot '$slot' is in both '$earlier' and '$name'; a slot belongs to at most one family"
    }
  }
  return found
}

private fun familyWhat(name: String) = "rules.slot_families: family '$name'"
