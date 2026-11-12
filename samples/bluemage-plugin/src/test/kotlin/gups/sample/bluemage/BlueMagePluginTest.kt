package gups.sample.bluemage

import gups.pluginapi.PluginActor
import gups.pluginapi.PluginCommandCall
import gups.pluginapi.PluginCreation
import gups.pluginapi.PluginEntity
import gups.pluginapi.PluginGmcpCall
import gups.pluginapi.PluginHookCall
import gups.pluginapi.PluginItem
import gups.pluginapi.PluginLane
import gups.pluginapi.PluginParam
import gups.pluginapi.PluginRegistry
import gups.pluginapi.PluginRng
import gups.pluginapi.PluginRole
import gups.pluginapi.PluginRoom
import gups.pluginapi.PluginRulesType
import gups.pluginapi.PluginSchema
import gups.pluginapi.PluginSkill
import gups.pluginapi.PluginTrait
import gups.pluginapi.PluginTraitCall
import gups.pluginapi.PluginTraitEvent
import gups.pluginapi.PluginWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.starlark.java.eval.StarlarkValue

class BlueMagePluginTest {

  private class FakeRegistry : PluginRegistry {
    val traits = mutableMapOf<String, PluginTrait>()
    val rulesExports = mutableMapOf<String, PluginRulesType>()
    val builtins = mutableMapOf<String, (PluginWorld, List<Any?>, Map<String, Any?>) -> Any?>()
    val commands = mutableMapOf<String, (PluginCommandCall) -> Unit>()

    override fun addTrait(trait: PluginTrait) {
      traits[trait.name] = trait
    }

    override fun addRulesExport(name: String, doc: String, type: PluginRulesType) {
      rulesExports[name] = type
    }

    override fun addBuiltin(
      name: String,
      doc: String,
      params: List<PluginParam>,
      returnType: String,
      call: (world: PluginWorld, positional: List<Any?>, named: Map<String, Any?>) -> Any?,
    ) {
      builtins[name] = call
    }

    override fun addCommand(
      name: String,
      lane: PluginLane,
      role: PluginRole,
      doc: String,
      run: (PluginCommandCall) -> Unit,
    ) {
      commands[name] = run
    }

    override fun addBuiltin(name: String, value: StarlarkValue, doc: String) = TODO()

    override fun addGmcp(pkg: String, doc: String, produce: (PluginWorld, PluginActor) -> Any?) =
      TODO()

    override fun addGmcpEvent(pkg: String, doc: String) = TODO()

    override fun onGmcp(pkg: String, doc: String, handle: (PluginGmcpCall) -> Unit) = TODO()

    override fun addHook(name: String, doc: String, handle: (PluginHookCall) -> Any?) = TODO()

    override fun onWorldStarted(fn: (PluginWorld) -> Unit) = TODO()

    override fun setCreation(creation: PluginCreation) = TODO()

    override fun addRestriction(
      point: gups.pluginapi.PluginRestrictionPoint,
      doc: String,
      check: (gups.pluginapi.PluginRestrictionCall) -> String?,
    ) = TODO()

    override fun addEffectPrimitive(
      name: String,
      doc: String,
      params: List<PluginParam>,
      apply: gups.pluginapi.PluginEffect,
    ) = TODO()

    override fun addContainmentFilter(
      doc: String,
      blocks: (gups.pluginapi.PluginContainmentCall) -> Boolean,
    ) = TODO()

    override fun addTargetOrder(
      doc: String,
      choose: (gups.pluginapi.PluginTargetOrderCall) -> gups.pluginapi.PluginTargetChoice,
    ) = TODO()

    override fun addChainThink(
      doc: String,
      decide: (gups.pluginapi.PluginChainThinkCall) -> Boolean,
    ) = TODO()

    override fun addOwnedBehaviors(
      doc: String,
      adjust: (gups.pluginapi.PluginOwnedBehaviorsCall) -> List<Map<String, Any?>>,
    ) = TODO()

    override fun addDispositionModifier(
      doc: String,
      adjust: (gups.pluginapi.PluginDispositionCall) -> Int,
    ) = TODO()

    override fun addFactions(
      doc: String,
      adjust: (gups.pluginapi.PluginFactionsCall) -> List<String>,
    ) = TODO()
  }

  private class FakeSkill(
    override val label: String,
    override val name: String,
    override val tags: List<String> = listOf("blue"),
  ) : PluginSkill {
    override val kind: String = "spell"
  }

  private class FakeActor(
    override val id: Long,
    override val name: String = "Actor$id",
    override val isPlayer: Boolean = true,
    override val level: Int = 5,
    override var skills: Map<String, Int> = emptyMap(),
    var traitNames: List<String> = emptyList(),
  ) : PluginActor {
    override val label: String? = null
    override val role: PluginRole = PluginRole.PLAYER
    override val flags: Set<String> = emptySet()
    override val traits: List<String>
      get() = traitNames

    override val grantedTraits: Set<String>
      get() = traitNames.toSet()

    override fun hasTrait(name: String): Boolean = name in traitNames

    override val choices: Map<String, String> = emptyMap()
    override val room: PluginRoom
      get() = TODO()

    override fun stat(name: String) = 10

    override fun baseStat(name: String) = 10

    override fun vital(name: String) = 20

    override fun maxVital(name: String) = 20

    override fun baseMaxVital(name: String) = 20

    override fun resist(type: String) = 0

    override fun baseResist(type: String) = 0

    override val pendingLevels = 0
    override val affects = emptyList<String>()

    override fun hasAffect(name: String) = false
  }

  /** Records what the plugin does; every verb the plugin shouldn't use throws. */
  private class FakeWorld(
    val skills: Map<String, PluginSkill> = emptyMap(),
    var chanceResult: Boolean = true,
    /** What the world's `bluemage_learn_chance` returns; null means the world doesn't define it. */
    var rulesValue: Long? = null,
    var setSkillRefusal: String? = null,
  ) : PluginWorld {
    val sent = mutableListOf<Pair<PluginActor, String>>()
    val chances = mutableListOf<Int>()
    val rulesCalls = mutableListOf<Triple<String, Map<String, Any?>, Any>>()
    val setSkillCalls = mutableListOf<Triple<PluginActor, String, Int>>()
    val attrs = mutableMapOf<Pair<Long, String>, Any?>()
    val granted = mutableListOf<Pair<PluginActor, String>>()
    var grantRefusal: String? = null

    override val rng =
      object : PluginRng {
        override fun int(min: Int, max: Int): Int = min

        override fun chance(pct: Int): Boolean {
          chances.add(pct)
          return chanceResult
        }
      }

    override fun rules(name: String, fields: Map<String, Any?>, default: Any): Any {
      rulesCalls.add(Triple(name, fields, default))
      return rulesValue ?: default
    }

    override fun skill(label: String): PluginSkill? = skills[label]

    override fun send(target: PluginActor, text: String) {
      sent.add(target to text)
    }

    override fun getAttr(entity: PluginEntity, key: String, default: Any?): Any? =
      attrs[entity.id to key] ?: default

    override fun setAttr(entity: PluginEntity, key: String, value: Any?) {
      attrs[entity.id to key] = value
    }

    override fun setSkill(actor: PluginActor, skill: String, proficiency: Int): String? {
      setSkillCalls.add(Triple(actor, skill, proficiency))
      return setSkillRefusal
    }

    override fun grantTrait(actor: PluginActor, trait: String): String? {
      granted.add(actor to trait)
      return grantRefusal
    }

    override val pulse: Long = 0L
    override val seed: Long = 42L
    override val schema: PluginSchema
      get() = TODO()

    override fun gmcp(actor: PluginActor, pkg: String, data: Any?): Boolean = TODO()

    override fun echo(room: PluginRoom, text: String, exclude: List<PluginActor>) = TODO()

    override fun damage(
      target: PluginActor,
      amount: Int,
      type: String,
      vital: String,
      source: PluginActor?,
    ) = TODO()

    override fun heal(target: PluginActor, amount: Int, vital: String) = TODO()

    override fun applyAffect(
      target: PluginActor,
      name: String,
      duration: Int,
      statDeltas: Map<String, Int>,
      flags: Set<String>,
      maxVitalDeltas: Map<String, Int>,
      resistDeltas: Map<String, Int>,
    ) = TODO()

    override fun removeAffect(target: PluginActor, name: String): Boolean = TODO()

    override fun applyItemAffect(
      item: PluginItem,
      name: String,
      duration: Int?,
      applies: String,
      statDeltas: Map<String, Int>,
      flags: Set<String>,
      senseDeltas: Map<String, Int>,
      maxVitalDeltas: Map<String, Int>,
      resistDeltas: Map<String, Int>,
    ) = TODO()

    override fun removeItemAffect(item: PluginItem, name: String): Boolean = TODO()

    override fun addTag(item: PluginItem, tag: String): Boolean = TODO()

    override fun removeTag(item: PluginItem, tag: String): Boolean = TODO()

    override fun transform(item: PluginItem, label: String) = TODO()

    override fun setCharges(item: PluginItem, charges: Int) = TODO()

    override fun teleport(actor: PluginActor, room: PluginRoom) = TODO()

    override fun wear(actor: PluginActor, item: PluginItem, wield: Boolean): String? = TODO()

    override fun unwear(actor: PluginActor, item: PluginItem): String? = TODO()

    override fun delAttr(entity: PluginEntity, key: String) = TODO()

    override fun setChoices(actor: PluginActor, changes: Map<String, String?>): String? = TODO()

    override fun removeTrait(actor: PluginActor, trait: String): String? = TODO()

    override fun removeSkill(actor: PluginActor, skill: String): Boolean = TODO()

    override fun awardXp(actor: PluginActor, amount: Long) = TODO()

    override fun takeXp(actor: PluginActor, amount: Long): Long = TODO()

    override fun levelUp(actor: PluginActor): Boolean = TODO()

    override fun levelDown(actor: PluginActor, count: Int): Int = TODO()

    override fun disposition(holder: PluginActor, subject: PluginActor): Int = TODO()

    override fun standing(holder: Any, subject: Any): Int = TODO()

    override fun factionsOf(actor: PluginActor): List<String> = TODO()

    override fun raiseStandingEvent(
      affected: PluginActor,
      actor: PluginActor,
      event: String,
      amount: Long,
      item: PluginItem?,
    ) = TODO()

    override fun adjustStanding(holder: Any, subject: Any, amount: Int) = TODO()

    override fun memories(
      mob: PluginActor,
      subject: PluginActor?,
      event: String?,
      since: Long,
    ): List<gups.pluginapi.PluginMemory> = TODO()

    override fun remember(
      mob: PluginActor,
      subject: PluginActor,
      event: String,
      skill: String?,
      amount: Long,
      target: PluginActor?,
    ) = TODO()

    override fun forget(mob: PluginActor, subject: PluginActor?, event: String?): Int = TODO()

    override fun rooms(): List<PluginRoom> = emptyList()

    override fun room(label: String): PluginRoom? = null

    override fun regions(): List<String> = emptyList()

    override fun diagnose(message: String) = TODO()

    override fun log(message: String) = TODO()
  }

  private val label = "//skills:breath"
  private val registry = FakeRegistry().also { BlueMagePlugin().register(it) }
  private val handler =
    registry.traits.getValue("blue_mage").on.getValue(PluginTraitEvent.SKILL_RECOGNIZED)

  private val bearer = FakeActor(1L, "Blue", level = 5, traitNames = listOf("blue_mage"))
  private val mob = FakeActor(2L, "a drake", isPlayer = false, level = 5)

  private fun fire(
    world: FakeWorld,
    source: PluginActor = mob,
    skill: String = label,
    outcome: String = "success",
    target: PluginActor? = null,
    power: Long = 100L,
  ) {
    handler.handle(
      PluginTraitCall(
        world,
        PluginTraitEvent.SKILL_RECOGNIZED,
        bearer,
        mapOf(
          "source" to source,
          "skill" to skill,
          "outcome" to outcome,
          "target" to target,
          "power" to power,
        ),
      )
    )
  }

  private fun world(vararg skills: PluginSkill, chance: Boolean = true) =
    FakeWorld(skills = skills.associateBy { it.label }, chanceResult = chance)

  // ---- Registration -------------------------------------------------------------------------

  @Test
  fun `registers the learn chance export as an int`() {
    assertEquals(PluginRulesType.INT, registry.rulesExports["bluemage_learn_chance"])
  }

  @Test
  fun `the trait is named blue_mage`() {
    assertEquals("blue_mage", registry.traits.getValue("blue_mage").name)
  }

  // ---- Gates --------------------------------------------------------------------------------

  private fun assertIgnored(w: FakeWorld) {
    assertEquals(emptyList(), w.rulesCalls)
    assertEquals(emptyList(), w.chances)
    assertEquals(emptyList(), w.setSkillCalls)
    assertEquals(emptyList(), w.sent)
  }

  @Test
  fun `a player caster is ignored`() {
    val w = world(FakeSkill(label, "Breath"))
    fire(w, source = FakeActor(3L, isPlayer = true))
    assertIgnored(w)
  }

  @Test
  fun `a failed cast is ignored`() {
    val w = world(FakeSkill(label, "Breath"))
    fire(w, outcome = "fail")
    assertIgnored(w)
  }

  @Test
  fun `a skill the world doesn't load is ignored`() {
    val w = world()
    fire(w)
    assertIgnored(w)
  }

  @Test
  fun `an untagged skill is ignored`() {
    val w = world(FakeSkill(label, "Breath", tags = listOf("arcane")))
    fire(w)
    assertIgnored(w)
  }

  @Test
  fun `a known skill is ignored`() {
    val w = world(FakeSkill(label, "Breath"))
    bearer.skills = mapOf(label to 40)
    try {
      fire(w)
    } finally {
      bearer.skills = emptyMap()
    }
    assertIgnored(w)
  }

  // ---- The formula --------------------------------------------------------------------------

  @Test
  fun `equal levels at power 100 and not the target gives 1`() {
    val w = world(FakeSkill(label, "Breath"))
    fire(w)
    assertEquals(1L, w.rulesCalls.single().third)
    assertEquals(listOf(1), w.chances)
  }

  @Test
  fun `the target gets 11`() {
    val w = world(FakeSkill(label, "Breath"))
    fire(w, target = bearer)
    assertEquals(11L, w.rulesCalls.single().third)
    assertEquals(listOf(11), w.chances)
  }

  @Test
  fun `a caster three levels higher clamps to 0`() {
    val w = world(FakeSkill(label, "Breath"))
    fire(w, source = FakeActor(3L, isPlayer = false, level = 8))
    assertEquals(-14L, w.rulesCalls.single().third)
    assertEquals(listOf(0), w.chances)
  }

  @Test
  fun `power 200 lowers the base by 10`() {
    val w = world(FakeSkill(label, "Breath"))
    fire(w, target = bearer, power = 200L)
    assertEquals(1L, w.rulesCalls.single().third)
  }

  @Test
  fun `the rules call carries the cast`() {
    val skill = FakeSkill(label, "Breath")
    val w = world(skill)
    fire(w, target = bearer)
    val (name, fields, _) = w.rulesCalls.single()
    assertEquals("bluemage_learn_chance", name)
    assertEquals(bearer, fields["actor"])
    assertEquals(mob, fields["source"])
    assertEquals(skill, fields["skill"])
    assertEquals(11L, fields["base"])
    assertEquals(100L, fields["power"])
    assertEquals(true, fields["was_target"])
  }

  @Test
  fun `a rules value above the cap clamps to 33`() {
    val w = world(FakeSkill(label, "Breath"))
    w.rulesValue = 80L
    fire(w)
    assertEquals(listOf(33), w.chances)
  }

  @Test
  fun `a negative rules value clamps to 0`() {
    val w = world(FakeSkill(label, "Breath"))
    w.rulesValue = -5L
    fire(w)
    assertEquals(listOf(0), w.chances)
  }

  // ---- Learning -----------------------------------------------------------------------------

  @Test
  fun `a failed roll learns nothing`() {
    val w = world(FakeSkill(label, "Breath"), chance = false)
    fire(w)
    assertEquals(emptyList(), w.setSkillCalls)
    assertEquals(emptyList(), w.sent)
  }

  @Test
  fun `a successful roll teaches the skill at 10 and records it`() {
    val w = world(FakeSkill(label, "Breath"))
    fire(w)
    assertEquals(listOf(Triple<PluginActor, String, Int>(bearer, label, 10)), w.setSkillCalls)
    assertEquals(
      listOf<Pair<PluginActor, String>>(bearer to "You grasp the secret of Breath!"),
      w.sent,
    )
    assertEquals(listOf(label), w.attrs[bearer.id to "learned"])
  }

  @Test
  fun `a second lesson appends to the learned list`() {
    val other = "//skills:gaze"
    val w = world(FakeSkill(label, "Breath"), FakeSkill(other, "Gaze"))
    fire(w)
    fire(w, skill = other)
    assertEquals(listOf(label, other), w.attrs[bearer.id to "learned"])
  }

  @Test
  fun `a refused setSkill sends and records nothing`() {
    val w = world(FakeSkill(label, "Breath"))
    w.setSkillRefusal = "You can't learn that."
    fire(w)
    assertEquals(emptyList(), w.sent)
    assertNull(w.attrs[bearer.id to "learned"])
  }

  // ---- The awaken builtin -------------------------------------------------------------------

  @Test
  fun `awaken grants the trait and returns None on success`() {
    val w = world()
    val result = registry.builtins.getValue("awaken")(w, listOf(bearer), emptyMap())
    assertNull(result)
    assertEquals(listOf<Pair<PluginActor, String>>(bearer to "blue_mage"), w.granted)
  }

  @Test
  fun `awaken returns the refusal`() {
    val w = world()
    w.grantRefusal = "Nope."
    assertEquals("Nope.", registry.builtins.getValue("awaken")(w, listOf(bearer), emptyMap()))
  }

  // ---- The bluemagic command ----------------------------------------------------------------

  private fun bluemagic(w: FakeWorld, actor: PluginActor) =
    registry.commands.getValue("bluemagic")(PluginCommandCall(w, actor, ""))

  @Test
  fun `bluemagic tells a non-bearer they are not a blue mage`() {
    val w = world()
    val plain = FakeActor(9L)
    bluemagic(w, plain)
    assertEquals(listOf<Pair<PluginActor, String>>(plain to "You are not a blue mage."), w.sent)
  }

  @Test
  fun `bluemagic with nothing learned says so`() {
    val w = world()
    bluemagic(w, bearer)
    assertEquals(
      listOf<Pair<PluginActor, String>>(bearer to "You haven't learned any blue magic."),
      w.sent,
    )
  }

  @Test
  fun `bluemagic lists learned skills by name`() {
    val w = world(FakeSkill(label, "Breath"), FakeSkill("//skills:gaze", "Gaze"))
    w.attrs[bearer.id to "learned"] = listOf(label, "//skills:gaze")
    bluemagic(w, bearer)
    val text = w.sent.single().second
    assertTrue("Breath" in text && "Gaze" in text, text)
    assertEquals(2, text.lines().count { it.contains("Breath") || it.contains("Gaze") })
  }

  // ---- Studying attackers -------------------------------------------------------------------

  private fun damaged(world: FakeWorld, source: PluginActor?) {
    registry.traits
      .getValue("blue_mage")
      .on
      .getValue(PluginTraitEvent.DAMAGED)
      .handle(
        PluginTraitCall(
          world,
          PluginTraitEvent.DAMAGED,
          bearer,
          mapOf("source" to source, "amount" to 3L, "type" to "fire"),
        )
      )
  }

  @Test
  fun `a mob that hurts a blue mage is studied once`() {
    val w = world()
    damaged(w, mob)
    damaged(w, mob)
    assertEquals(
      listOf<Pair<PluginActor, String>>(bearer to "You study how a drake fights."),
      w.sent,
    )
    damaged(w, FakeActor(4L, "a wyvern", isPlayer = false))
    assertEquals("You study how a wyvern fights.", w.sent.last().second)
  }

  @Test
  fun `damage from a player or from nothing isn't studied`() {
    val w = world()
    damaged(w, FakeActor(3L, isPlayer = true))
    damaged(w, null)
    assertEquals(emptyList(), w.sent)
  }
}
