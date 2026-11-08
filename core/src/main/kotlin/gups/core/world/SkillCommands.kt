package gups.core.world

import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Item
import gups.core.model.Room
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.TargetKind
import gups.core.rules.RulesContext

object SkillMsg : MsgGroup(Feature.SKILLS) {
  val CAST_WHAT = msg("cast.what", "Cast what?")
  val UNKNOWN_SKILL = msg("cast.unknown_skill", "You don't know that skill.")
  val ON_WHAT = msg("cast.on_what", "Cast {skill} on what?", "skill")
  val AT_WHOM = msg("cast.at_whom", "Cast {skill} at whom?", "skill")
  val SELF_ONLY = msg("cast.self_only", "You can only cast {skill} on yourself.", "skill")
  val NOT_ENOUGH = msg("cast.not_enough", "You don't have enough {vital}.", "vital")
  val NOT_HERE = msg("cast.not_here", "{target} isn't here.", "target")
  val OUT_OF_RANGE = msg("cast.out_of_range", "{target} is out of range.", "target")
  val BEGIN_SELF = msg("cast.begin.self", "You begin casting {skill}.", "skill", "skill")
  val BEGIN_KNOWN =
    msg("cast.begin.known", "{actor} begins casting {skill}.", "actor,skill", "skill")
  val BEGIN_UNKNOWN = msg("cast.begin.unknown", "{actor} begins casting.", "actor")
  val DONE_ITEM = msg("cast.done.item", "You cast {skill} on {target}.", "skill,target", "skill")
  val DONE_SELF = msg("cast.done.self", "You cast {skill}.", "skill", "skill")
  val DONE_TARGET =
    msg("cast.done.target", "You cast {skill} at {target}.", "skill,target", "skill")
  val DONE_VICTIM = msg("cast.done.victim", "{actor} casts {skill} at you!", "actor,skill", "skill")
  val ROOM_ITEM =
    msg("cast.room.item", "{actor} casts {skill} on {target}.", "actor,skill,target", "skill")
  val ROOM_SELF = msg("cast.room.self", "{actor} casts {skill}.", "actor,skill", "skill")
  val ROOM_TARGET =
    msg("cast.room.target", "{actor} casts {skill} at {target}.", "actor,skill,target", "skill")
  val RESISTED_SELF =
    msg("cast.resisted.self", "{target} resists your {skill}.", "skill,target", "skill")
  val RESISTED_TARGET =
    msg("cast.resisted.target", "You resist {actor}'s {skill}.", "actor,skill", "skill")
  val RESISTED_ROOM =
    msg("cast.resisted.room", "{target} resists {actor}'s {skill}.", "actor,skill,target", "skill")
  val FAILED_SELF = msg("cast.failed.self", "You fail to cast {skill}.", "skill", "skill")
  val FAILED_ROOM = msg("cast.failed.room", "{actor}'s spell fizzles.", "actor", "actor")
  val INTERRUPTED = msg("cast.interrupted", "Your concentration is broken!")
  val MOVED =
    msg("cast.moved", "You are no longer where you began casting {skill}.", "skill", "skill")
  val STOP_CAST = msg("stop.cast", "You stop casting.")
  val SKILLS_HEADER = msg("skills.header", "You know these skills:")
  val SKILLS_LINE =
    msg("skills.line", "  {skill} ({kind}) {proficiency}%", "skill,kind,proficiency", "skill")
  val SKILLS_NONE = msg("skills.none", "You don't know any skills.")
}

/** The commands of the `skills` feature. */
internal object SkillCommands {
  fun all(): List<CommandDef> =
    listOf(
      FastCommand(
        "skills",
        category = CommandCategory.SKILLS,
        help = "List the skills you know and how well.\nUsage: skills",
      ) {
        listSkills(it)
      },
      TickCommand(
        "cast",
        category = CommandCategory.SKILLS,
        help = "Cast a skill or spell at an optional target.\nUsage: cast <skill> [target]",
      ) {
        planCast(it)
      },
    )

  private fun listSkills(c: CommandCall) {
    val w = c.world
    val known =
      c.actor.skills
        .mapNotNull { (label, proficiency) -> w.protos.skills[label]?.let { it to proficiency } }
        .sortedWith(compareBy({ it.first.name }, { it.first.label.v }))
    if (known.isEmpty()) {
      w.tell(c.actor, SkillMsg.SKILLS_NONE)
      return
    }
    w.tell(c.actor, SkillMsg.SKILLS_HEADER)
    for ((proto, proficiency) in known) {
      w.tell(
        c.actor,
        SkillMsg.SKILLS_LINE,
        "skill" to proto.name,
        "kind" to proto.kind,
        "proficiency" to proficiency,
      )
    }
  }

  private fun planCast(c: CommandCall): Action? {
    val w = c.world
    val (skillWord, targetWord) = splitSkill(c.args)
    if (skillWord.isEmpty()) return null.also { w.tell(c.actor, SkillMsg.CAST_WHAT) }
    val skill =
      c.actor.skills.keys.map(w.protos::skill).firstOrNull {
        it.name.startsWith(skillWord.lowercase())
      } ?: return null.also { w.tell(c.actor, SkillMsg.UNKNOWN_SKILL) }
    return CastAction(skill, targetWord.ifEmpty { null })
  }

  /** `cast fire goblin` or `cast 'magic missile' goblin`. */
  private fun splitSkill(s: String): Pair<String, String> {
    val t = s.trim()
    val q = t.firstOrNull()
    if (q == '\'' || q == '"') {
      val end = t.indexOf(q, 1)
      if (end > 0) return t.substring(1, end).replace(' ', '_') to t.substring(end + 1).trim()
    }
    return splitFirst(t)
  }
}

/**
 * A skill cast with the full action lifecycle. The target is found by [targetWord] at the first
 * validation, unless the script verb `act_cast` fixed it up front.
 */
internal class CastAction(
  override val skill: SkillProto,
  private val targetWord: String?,
  private val fixedTarget: Actor? = null,
) : Action, Castable {
  override val warmup: Int
    get() = skill.warmup

  override val lag: Int
    get() = skill.lag

  override val interrupt
    get() = skill.interrupt

  override val attackWhileCasting: Boolean
    get() = skill.attackWhileCasting

  /** Chosen on the first validation; re-validation checks the same target. */
  private var target: Actor? = null
  private var targetChosen = false

  /** The target of an `item` skill: carried, worn or on the ground. */
  private var targetItem: Item? = null

  /** The room a `room` skill acts on: the caster's. */
  private var targetRoom: Room? = null

  private fun chooseTarget(w: World, caster: Actor): Result<Actor?> {
    if (targetChosen) return Result.success(target)
    fun fail(m: Msg, vararg args: Pair<String, Any?>) =
      Result.failure<Actor?>(IllegalArgumentException(w.msg(m, *args)))
    val t =
      if (fixedTarget != null && skill.target in setOf(TargetKind.ENEMY, TargetKind.ALLY))
        fixedTarget
      else
        when (skill.target) {
          TargetKind.SELF -> caster
          TargetKind.NONE -> null
          TargetKind.ROOM -> {
            targetRoom = caster.room
            null
          }
          TargetKind.ITEM -> {
            val word = targetWord ?: return fail(SkillMsg.ON_WHAT, "skill" to skill.name)
            targetItem =
              caster.equipment.values.firstOrNull { it.matches(word) }
                ?: w.findItem(caster, word, inventory = true, ground = true)
                ?: return fail(KernelMsg.DONT_SEE)
            null
          }
          TargetKind.ALLY ->
            targetWord?.let { w.findActor(caster, it) ?: return fail(KernelMsg.NOT_HERE) } ?: caster
          TargetKind.ENEMY ->
            targetWord?.let { w.findActor(caster, it) ?: return fail(KernelMsg.NOT_HERE) }
              ?: caster.primaryTarget?.let(w::actor)
              ?: return fail(SkillMsg.AT_WHOM, "skill" to skill.name)
        }
    target = t
    targetChosen = true
    return Result.success(t)
  }

  override fun validate(w: World, actor: Actor): String? {
    if (actor.hasFlag("silenced")) return w.msg(KernelMsg.SILENCED)
    val t =
      chooseTarget(w, actor).getOrElse {
        return it.message
      }
    targetRoom?.let { r ->
      if (actor.room !== r) return w.msg(SkillMsg.MOVED, "skill" to skill.name)
    }
    targetItem?.let { i ->
      if (w.item(i.id) !== i || !w.isPresent(i, w.roomOf(actor)))
        return w.msg(SkillMsg.NOT_HERE, "target" to i.name)
      w.reach(actor, i)?.let {
        return it
      }
    }
    if (t != null && t !== actor) {
      if (!w.isPresent(t, w.roomOf(actor))) return w.msg(SkillMsg.NOT_HERE, "target" to t.name)
      val band = Band.between(w.indexOf(actor), w.indexOf(t))
      val reach =
        when (skill.range) {
          SkillRange.MELEE -> Band.MELEE
          SkillRange.NEAR -> Band.NEAR
          SkillRange.FAR,
          SkillRange.ROOM -> Band.FAR
          SkillRange.SELF -> return w.msg(SkillMsg.SELF_ONLY, "skill" to skill.name)
        }
      if (!reach.reaches(band)) return w.msg(SkillMsg.OUT_OF_RANGE, "target" to t.name)
    }
    for ((vital, need) in skill.cost) {
      if (actor.current(vital) < need)
        return w.msg(SkillMsg.NOT_ENOUGH, "vital" to w.schema.vitalLabel(vital))
    }
    return null
  }

  override fun veto(w: World, actor: Actor): String? =
    w.castAllowed(RulesContext(w, actor, target, skill = skill))
      ?: w.dispatch(
        HookName.BEFORE_CAST,
        actor,
        actor.room,
        HookContext(actor = actor, target = target, skill = skill, targetItem = targetItem),
      )

  override fun begin(w: World, actor: Actor) {
    w.tell(actor, SkillMsg.BEGIN_SELF, "skill" to skill.name)
    // Onlookers who know the spell recognize it; others only see that a spell is starting.
    for (viewer in actor.room.actorIndex.values) {
      if (viewer === actor) continue
      w.send(
        viewer,
        if (viewer.skills.containsKey(skill.label))
          w.msg(SkillMsg.BEGIN_KNOWN, "actor" to actor.name.cap(), "skill" to skill.name)
        else w.msg(SkillMsg.BEGIN_UNKNOWN, "actor" to actor.name.cap()),
      )
    }
    w.witness(actor.room, actor, "cast_start", skill = skill.label, target = target)
    w.dispatch(
      HookName.ON_CAST_START,
      actor,
      actor.room,
      HookContext(actor = actor, target = target, skill = skill, targetItem = targetItem),
    )
  }

  override fun resolve(w: World, actor: Actor): Int {
    w.changeVitals(actor, skill.cost.mapValues { -it.value })
    val c = RulesContext(w, actor, target, item = targetItem, skill = skill)
    if (!w.skillSucceeds(c)) {
      w.tell(actor, SkillMsg.FAILED_SELF, "skill" to skill.name)
      w.tellSight(
        actor.room,
        "cast",
        actor,
        w.msg(SkillMsg.FAILED_ROOM, "actor" to actor.name.cap()),
        actor,
      )
      w.reportCastUse(actor, skill, target, targetItem, "fail", emptyList())
      return skill.lag
    }
    val power = w.skillPowerChain(c)
    val t = target
    val onItem = targetItem
    val onRoom = targetRoom
    w.send(
      actor,
      when {
        onItem != null -> w.msg(SkillMsg.DONE_ITEM, "skill" to skill.name, "target" to onItem.name)
        t == null || t === actor -> w.msg(SkillMsg.DONE_SELF, "skill" to skill.name)
        else -> w.msg(SkillMsg.DONE_TARGET, "skill" to skill.name, "target" to t.name)
      },
    )
    if (t != null && t !== actor)
      w.tell(t, SkillMsg.DONE_VICTIM, "actor" to actor.name.cap(), "skill" to skill.name)
    // Bystanders see every completed cast, so instant spells aren't invisible to the room.
    val who = actor.name.cap()
    w.tellSight(
      actor.room,
      "cast",
      actor,
      when {
        onItem != null ->
          w.msg(SkillMsg.ROOM_ITEM, "actor" to who, "skill" to skill.name, "target" to onItem.name)
        t == null || t === actor -> w.msg(SkillMsg.ROOM_SELF, "actor" to who, "skill" to skill.name)
        else ->
          w.msg(SkillMsg.ROOM_TARGET, "actor" to who, "skill" to skill.name, "target" to t.name)
      },
      *listOfNotNull(actor, t).toTypedArray(),
    )
    // Witnessed before the effects run, so a resolver can make a mob forget the cast.
    w.witness(actor.room, actor, "cast", skill = skill.label, target = t)
    val targets =
      if (onRoom != null) listOf(null)
      else if (skill.range == SkillRange.ROOM)
        actor.room.actors.filter { it !== actor && w.isSimulated(it) }
      else listOf(t)
    if (onItem != null && onItem.id in w.entities)
      skill.effect?.let { w.applyItemEffect(it, actor, onItem, power = power) }
    onRoom?.let { r -> skill.effect?.let { w.applyRoomEffect(it, actor, r, power = power) } }
    // The victims the cast reached, and the ones it landed on, for the on_skill_use report.
    var reached = 0
    val landed = mutableListOf<Actor>()
    for (each in targets) {
      val hostile = skill.target == TargetKind.ENEMY && each != null && each !== actor
      // An enemy skill engages whatever it is cast at, whether or not it lands.
      if (hostile) {
        reached++
        w.startCombat(actor, each)
      }
      if (hostile && !w.spellLands(actor, each, skill)) {
        w.send(
          actor,
          w.msg(SkillMsg.RESISTED_SELF, "target" to each.name.cap(), "skill" to skill.name),
        )
        w.tell(each, SkillMsg.RESISTED_TARGET, "actor" to actor.name.cap(), "skill" to skill.name)
        w.tellSight(
          actor.room,
          "cast",
          actor,
          w.msg(
            SkillMsg.RESISTED_ROOM,
            "actor" to who,
            "skill" to skill.name,
            "target" to each.name.cap(),
          ),
          actor,
          each,
        )
        continue
      }
      if (each != null) landed += each
      // An item or room skill's effect already ran on the item or the room.
      if (onItem == null && onRoom == null)
        skill.effect?.let { w.applyEffect(it, actor, each, skill, power = power) }
      skill.resolve?.resolve(w, actor, each, onItem, power)
    }
    w.dispatch(
      HookName.ON_CAST,
      actor,
      actor.room,
      HookContext(actor = actor, target = t, skill = skill, targetItem = onItem),
    )
    val outcome =
      if (skill.target == TargetKind.ENEMY && reached > 0 && landed.isEmpty()) "resist"
      else "success"
    w.reportCastUse(actor, skill, t, onItem, outcome, landed)
    return skill.lag
  }
}

/**
 * Reports a resolved cast to the global on_skill_use hook: once per resolve, only while the user is
 * still in the world. A cast aimed at the user carries no target.
 */
private fun World.reportCastUse(
  actor: Actor,
  skill: SkillProto,
  target: Actor?,
  item: Item?,
  outcome: String,
  landed: List<Actor>,
) {
  if (!actor.inWorld) return
  dispatchSkillUse(actor, skill, target?.takeIf { it !== actor }, item, outcome, landed)
}

/**
 * Whether [caster]'s enemy [skill] lands on [victim]: to_hit with the skill set, the caster's
 * to_hit chain, then the victim's defend_hit chain. A chance of 100 or more lands without a roll.
 */
internal fun World.spellLands(caster: Actor, victim: Actor, skill: SkillProto): Boolean {
  val c =
    RulesContext(
      this,
      caster,
      victim,
      band = Band.between(indexOf(caster), indexOf(victim)),
      skill = skill,
    )
  val chance = toHitChain(c)
  if (chance.value >= 100) {
    traceRules(caster, "${chance.text} → auto LAND")
    return true
  }
  val roll = rng.int(1, 100)
  traceRules(caster, "${chance.text} → roll $roll ${if (roll > chance.value) "RESIST" else "LAND"}")
  return roll <= chance.value
}
