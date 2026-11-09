package gups.core.world

import gups.core.EntityId
import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Channel
import gups.core.model.Effect
import gups.core.model.Item
import gups.core.model.Player
import gups.core.model.Room
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.TargetKind
import gups.core.model.TraitEvent
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
  val ANON_VICTIM_DAMAGE =
    msg("cast.anon.victim.damage", "{actor}'s {what} hits you!", "actor,what,damage_type", "actor")
  val ANON_ROOM_DAMAGE =
    msg(
      "cast.anon.room.damage",
      "{actor}'s {what} hits {target}.",
      "actor,target,what,damage_type",
      "actor",
    )
  val ANON_VICTIM_AID =
    msg("cast.anon.victim.aid", "{actor}'s {what} makes you feel better.", "actor,what", "actor")
  val ANON_ROOM_AID =
    msg(
      "cast.anon.room.aid",
      "{actor}'s {what} washes over {target}.",
      "actor,target,what",
      "actor",
    )
  val ANON_VICTIM_HARM =
    msg("cast.anon.victim.harm", "{actor}'s {what} makes you feel worse.", "actor,what", "actor")
  val ANON_ROOM_HARM =
    msg("cast.anon.room.harm", "{actor}'s {what} strikes {target}.", "actor,target,what", "actor")
  val ANON_RESISTED_VICTIM =
    msg("cast.anon.resisted.victim", "You resist {actor}'s {what}.", "actor,what", "actor")
  val ANON_RESISTED_ROOM =
    msg(
      "cast.anon.resisted.room",
      "{target} resists {actor}'s {what}.",
      "actor,target,what",
      "actor",
    )
  val ANON_ROOM = msg("cast.anon.room", "{actor} does something.", "actor", "actor")
  val ANON_ROOM_TARGET =
    msg("cast.anon.room.target", "{actor} does something to {target}.", "actor,target", "actor")
  val ANON_VICTIM = msg("cast.anon.victim", "{actor} does something to you.", "actor", "actor")
  val ANON_NOUN_HARM = msg("cast.anon.noun.harm", "attack")
  val ANON_NOUN_AID = msg("cast.anon.noun.aid", "aid")
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

/** One cast's recognition answers, by player id; true = recognized. */
internal class CastRecognition {
  val answers = HashMap<EntityId, Boolean>()

  /** Players who recognized the skill without knowing it, in the order they were asked. */
  val learnedBy = LinkedHashSet<EntityId>()
}

/**
 * Whether [viewer] recognizes [skill] cast by [caster]: a player who knows it does, anyone else
 * asks `rules.recognizes_skill`, and without it doesn't. The answer is kept in [rec], so every line
 * about the cast agrees.
 */
internal fun World.recognizes(
  viewer: Player,
  caster: Actor,
  skill: SkillProto,
  castTarget: Actor?,
  rec: CastRecognition,
): Boolean =
  rec.answers.getOrPut(viewer.id) {
    if (skill.label in viewer.skills) return@getOrPut true
    val answer =
      rules.recognizesSkill(
        RulesContext(
          this,
          actor = viewer,
          target = viewer,
          source = caster,
          skill = skill,
          room = viewer.room,
          castTarget = castTarget,
        )
      ) ?: false
    traceRules(viewer, "recognizes_skill ${skill.label} → $answer")
    if (answer) rec.learnedBy += viewer.id
    answer
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

  /** Who recognized the skill, decided once per player over the whole cast. */
  private val recognition = CastRecognition()

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
    for ((viewer, seen) in
      w.perceiversOf(actor.room, "cast", actor, setOf(Channel.SIGHT), exclude = arrayOf(actor))) {
      val line =
        when {
          seen.channel != Channel.SIGHT -> seen.text.orEmpty()
          w.recognizes(viewer, actor, skill, target, recognition) ->
            w.msg(SkillMsg.BEGIN_KNOWN, "actor" to actor.name.cap(), "skill" to skill.name)
          else -> w.anonBegin(skill, actor, target)
        }
      w.send(viewer, line)
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
    // Bystanders see every completed cast, so instant spells aren't invisible to the room.
    val who = actor.name.cap()
    val everyone =
      w.perceiversOf(actor.room, "cast", actor, setOf(Channel.SIGHT), exclude = arrayOf(actor))

    fun named(p: Player) = w.recognizes(p, actor, skill, t, recognition)
    val namedRoomLine =
      when {
        onItem != null ->
          w.msg(SkillMsg.ROOM_ITEM, "actor" to who, "skill" to skill.name, "target" to onItem.name)
        t == null || t === actor -> w.msg(SkillMsg.ROOM_SELF, "actor" to who, "skill" to skill.name)
        else ->
          w.msg(SkillMsg.ROOM_TARGET, "actor" to who, "skill" to skill.name, "target" to t.name)
      }
    val targetPlayer = (t as? Player)?.takeIf { it !== actor }
    val targetNamed = targetPlayer?.let(::named)
    if (targetPlayer != null && targetNamed == true)
      w.tell(targetPlayer, SkillMsg.DONE_VICTIM, "actor" to who, "skill" to skill.name)
    val anonBystanders = mutableListOf<Player>()
    for ((p, seen) in everyone) {
      if (p === t) continue
      when {
        seen.channel != Channel.SIGHT -> w.send(p, seen.text.orEmpty())
        named(p) -> w.send(p, namedRoomLine)
        else -> anonBystanders += p
      }
    }
    // A single enemy target is told of a hit only once the landing roll is known.
    val awaitsRoll =
      skill.target == TargetKind.ENEMY &&
        t != null &&
        t !== actor &&
        skill.range != SkillRange.ROOM &&
        onItem == null &&
        onRoom == null
    val roomTarget =
      if (skill.range == SkillRange.ROOM || t === actor) null else t?.name ?: onItem?.name
    if (!awaitsRoll) {
      if (targetPlayer != null && targetNamed == false) {
        val key = if (skill.range == SkillRange.ROOM) KEY_ROOM else KEY_VICTIM
        w.send(targetPlayer, w.anonLine(skill, key, actor, null))
      }
      for (p in anonBystanders) w.send(p, w.anonLine(skill, KEY_ROOM, actor, roomTarget))
    }
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
        if (each is Player) {
          if (named(each))
            w.tell(each, SkillMsg.RESISTED_TARGET, "actor" to who, "skill" to skill.name)
          else w.send(each, w.anonLine(skill, KEY_RESISTED_VICTIM, actor, null))
        }
        for ((p, seen) in everyone) {
          if (p === each) continue
          when {
            seen.channel != Channel.SIGHT -> w.send(p, seen.text.orEmpty())
            named(p) ->
              w.send(
                p,
                w.msg(
                  SkillMsg.RESISTED_ROOM,
                  "actor" to who,
                  "skill" to skill.name,
                  "target" to each.name.cap(),
                ),
              )
            else -> w.send(p, w.anonLine(skill, KEY_RESISTED_ROOM, actor, each.name.cap()))
          }
        }
        continue
      }
      if (awaitsRoll && each === t) {
        if (targetPlayer != null && targetNamed == false)
          w.send(targetPlayer, w.anonLine(skill, KEY_VICTIM, actor, null))
        for (p in anonBystanders) w.send(p, w.anonLine(skill, KEY_ROOM, actor, t.name))
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
    w.fireSkillRecognized(recognition, actor, skill, t, outcome, power)
    return skill.lag
  }
}

/**
 * Runs the SKILL_RECOGNIZED handlers of each player who recognized a skill they don't know, in the
 * order they were asked and then by trait name. A handler that throws is recorded and doesn't stop
 * the others.
 */
private fun World.fireSkillRecognized(
  rec: CastRecognition,
  caster: Actor,
  skill: SkillProto,
  target: Actor?,
  outcome: String,
  power: Int,
) {
  val fields =
    mapOf(
      "source" to caster,
      "skill" to skill.label.v,
      "outcome" to outcome,
      "target" to target,
      "power" to power,
    )
  for (id in rec.learnedBy) {
    val player = entities[id] as? Player ?: continue
    // An earlier handler may have removed the player or taught them the skill.
    if (!player.inWorld || skill.label in player.skills) continue
    for (trait in player.traits) {
      val handler = trait.handlers[TraitEvent.SKILL_RECOGNIZED] ?: continue
      try {
        handler.handle(this, player, TraitEvent.SKILL_RECOGNIZED, fields)
      } catch (e: Exception) {
        diagnose(
          "plugin",
          "trait '${trait.name}' handler ${TraitEvent.SKILL_RECOGNIZED} threw: ${e.message}",
          e,
        )
      }
    }
  }
}

private const val KEY_ROOM = "room"
private const val KEY_VICTIM = "victim"
private const val KEY_RESISTED_ROOM = "resisted_room"
private const val KEY_RESISTED_VICTIM = "resisted_victim"

/** The warmup line for a player who doesn't recognize [skill]. */
private fun World.anonBegin(skill: SkillProto, caster: Actor, target: Actor?): String {
  val own = skill.unrecognized["begin"]
  val values = mapOf("actor" to caster.name.cap(), "target" to target?.name.orEmpty())
  return if (own != null) Messages.fill(own, values)
  else msg(SkillMsg.BEGIN_UNKNOWN, "actor" to caster.name.cap())
}

/** The first damage primitive of [effect], depth-first through sequences, or null. */
internal fun firstDamage(effect: Effect?): Effect.Damage? =
  when (effect) {
    is Effect.Damage -> effect
    is Effect.Sequence -> effect.effects.firstNotNullOfOrNull(::firstDamage)
    else -> null
  }

/**
 * The line a player who doesn't recognize [skill] is told. [key] is "room", "victim",
 * "resisted_room" or "resisted_victim"; [target] is the display name of the cast's target (an actor
 * or item), or null when there is none.
 */
internal fun World.anonLine(
  skill: SkillProto,
  key: String,
  caster: Actor,
  target: String?,
): String {
  val noun =
    skill.unrecognized["noun"]
      ?: msg(
        if (skill.target == TargetKind.ENEMY) SkillMsg.ANON_NOUN_HARM else SkillMsg.ANON_NOUN_AID
      )
  val dmg = firstDamage(skill.effect)
  val what =
    listOfNotNull(dmg?.let { schema.damageTypeAdjective(it.type.name) }, noun).joinToString(" ")
  val actor = caster.name.cap()
  val hasTarget = target != null
  // Without a target, a room line is about the caster (a self-cast) and a victim line about the
  // viewer.
  val shown = target ?: if (key == KEY_ROOM) actor else "you"

  skill.unrecognized[key]?.let {
    return Messages.fill(it, mapOf("actor" to actor, "target" to shown))
  }

  fun say(m: Msg, vararg extra: Pair<String, Any?>): String {
    val args = mutableListOf<Pair<String, Any?>>("actor" to actor)
    if ("target" in m.placeholders) args += "target" to shown
    if ("what" in m.placeholders) args += "what" to what
    args += extra
    return msg(m, *args.toTypedArray())
  }
  val damageType = dmg?.let { "damage_type" to schema.damageTypeLabel(it.type.name) }

  if (damageType != null) {
    if (key == "victim") return say(SkillMsg.ANON_VICTIM_DAMAGE, damageType)
    if (key == "room" && hasTarget) return say(SkillMsg.ANON_ROOM_DAMAGE, damageType)
  }
  when (skill.target) {
    TargetKind.ENEMY ->
      when {
        key == "victim" -> return say(SkillMsg.ANON_VICTIM_HARM)
        key == "room" && hasTarget -> return say(SkillMsg.ANON_ROOM_HARM)
        key == "resisted_victim" -> return say(SkillMsg.ANON_RESISTED_VICTIM)
        key == "resisted_room" -> return say(SkillMsg.ANON_RESISTED_ROOM)
      }
    TargetKind.ALLY,
    TargetKind.SELF ->
      when {
        key == "victim" -> return say(SkillMsg.ANON_VICTIM_AID)
        key == "room" && hasTarget -> return say(SkillMsg.ANON_ROOM_AID)
      }
    else -> {}
  }
  return when {
    key == "victim" -> say(SkillMsg.ANON_VICTIM)
    hasTarget -> say(SkillMsg.ANON_ROOM_TARGET)
    else -> say(SkillMsg.ANON_ROOM)
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
