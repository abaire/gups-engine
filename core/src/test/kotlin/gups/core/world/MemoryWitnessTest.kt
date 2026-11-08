package gups.core.world

import gups.core.Label
import gups.core.model.Channel
import gups.core.model.DamageType
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.TargetKind
import gups.core.rules.RulesContext
import gups.core.rules.RulesEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryWitnessTest {
  private val watcherLabel = Label("//t:watcher")
  private val spellSenseLabel = Label("//t:spell_sense")
  private val spell = Channel("spell")
  private val slash = DamageType("slash")

  private fun fixture(
    memoryPulses: Long = 100,
    bubble: Boolean = false,
    canPerceive: ((RulesContext) -> Boolean)? = null,
  ): Fixture {
    val fs = gups.core.fixtures.FixtureSchemas.current
    val mobs =
      T.mobs +
        MobProto(watcherLabel, "a watcher", listOf("watcher"), vitals = mapOf(fs.health to 1000)) +
        MobProto(
          spellSenseLabel,
          "a mystic",
          listOf("mystic"),
          vitals = mapOf(fs.health to 1000),
          senses = mapOf(Channel.SIGHT to 0, Channel.SOUND to 0, spell to 1),
        )
    val base = FixedRules()
    val rules =
      if (canPerceive == null) base
      else
        object : RulesEngine by base {
          override fun canPerceive(c: RulesContext): Boolean = canPerceive(c)
        }
    return Fixture(
        rules = rules,
        scripts = RecordingHost(),
        protos = Prototypes(T.rooms, mobs, T.items, T.skills),
        bubble = bubble,
      )
      .also { it.w.memoryPulses = memoryPulses }
  }

  @Test
  fun `heal is witnessed with target and amount unless perception refuses`() {
    val f = fixture()
    val watcher = f.mob(watcherLabel)
    val tank = f.player("Tank")
    val cleric = f.player("Cleric")
    f.w.damage(tank, mapOf(f.fs.health to 10), slash, watcher)
    f.w.heal(tank, f.fs.health, 7, source = cleric)

    val mems = f.w.memoriesOf(watcher, subject = cleric, event = "heal")
    assertEquals(1, mems.size)
    assertEquals(tank.id, mems.single().key.target)
    assertEquals(7L, mems.single().amount)
    assertEquals(Channel.SIGHT, mems.single().channel)

    val blindF = fixture(canPerceive = { false })
    val blindWatcher = blindF.mob(watcherLabel)
    val t2 = blindF.player("Tank")
    val c2 = blindF.player("Cleric")
    blindF.w.damage(t2, mapOf(blindF.fs.health to 10), slash, blindWatcher)
    blindF.w.heal(t2, blindF.fs.health, 7, source = c2)
    assertTrue(blindF.w.memoriesOf(blindWatcher, event = "heal").isEmpty())
  }

  @Test
  fun `declared-channel-only mob witnesses cast on that channel and ignores heal`() {
    val f =
      fixture(
        canPerceive = { c -> c.channel == spell && (c.event == "cast" || c.event == "cast_start") }
      )
    val mystic = f.mob(spellSenseLabel)
    val mage = f.player("Mage").also { it.skills[T.BOLT] = 100 }
    val target = f.player("Target")

    f.w.damage(target, mapOf(f.fs.health to 10), slash, mystic)
    f.w.heal(target, f.fs.health, 5, source = mage)
    assertTrue(f.w.memoriesOf(mystic, event = "heal").isEmpty())

    f.input(mage, "cast bolt target")
    f.run(1)
    val startMems = f.w.memoriesOf(mystic, subject = mage, event = "cast_start")
    assertEquals(1, startMems.size)
    assertEquals(T.BOLT, startMems.single().key.skill)
    assertEquals(target.id, startMems.single().key.target)
    assertEquals(spell, startMems.single().channel)

    f.run(5)
    val castMems = f.w.memoriesOf(mystic, subject = mage, event = "cast")
    assertEquals(1, castMems.size)
    assertEquals(T.BOLT, castMems.single().key.skill)
    assertEquals(target.id, castMems.single().key.target)
    assertEquals(spell, castMems.single().channel)
  }

  @Test
  fun `help and kill are witnessed with target and the subject never witnesses itself`() {
    val f = fixture()
    val watcher = f.mob(watcherLabel)
    val actorMob = f.mob(watcherLabel)
    val ally = f.player("Ally")
    val victim = f.mob(T.GOBLIN)

    f.w.applyAffect(ally, "bless", duration = 10, source = actorMob, hostile = false)
    assertEquals(1, f.w.memoriesOf(watcher, subject = actorMob, event = "help").size)
    assertEquals(
      ally.id,
      f.w.memoriesOf(watcher, subject = actorMob, event = "help").single().key.target,
    )
    assertTrue(f.w.memoriesOf(actorMob, subject = actorMob, event = "help").isEmpty())

    val victimId = victim.id
    f.w.damage(victim, mapOf(f.fs.health to 100), slash, actorMob)
    val killMems = f.w.memoriesOf(watcher, subject = actorMob, event = "kill")
    assertEquals(1, killMems.size)
    assertEquals(victimId, killMems.single().key.target)
    assertTrue(f.w.memoriesOf(actorMob, subject = actorMob, event = "kill").isEmpty())
  }

  @Test
  fun `heal, help and kill from another room are witnessed in the subject's room`() {
    val f = fixture()
    val nearTarget = f.mob(watcherLabel, at = T.YARD)
    val nearSource = f.mob(watcherLabel, at = T.HALL)
    val tank = f.player("Tank", at = T.YARD)
    val cleric = f.player("Cleric", at = T.HALL)
    f.w.damage(tank, mapOf(f.fs.health to 10), slash, nearTarget)
    f.w.heal(tank, f.fs.health, 7, source = cleric)
    f.w.applyAffect(tank, "bless", duration = 10, source = cleric, hostile = false)
    val victim = f.mob(T.GOBLIN, at = T.YARD)
    f.w.damage(victim, mapOf(f.fs.health to 100), slash, cleric)

    for (event in listOf("heal", "help", "kill")) {
      assertEquals(1, f.w.memoriesOf(nearSource, subject = cleric, event = event).size, event)
      assertTrue(f.w.memoriesOf(nearTarget, subject = cleric, event = event).isEmpty(), event)
    }
  }

  @Test
  fun `cross-room say is remembered on sound and 50 says merge without evicting attack`() {
    val f = fixture()
    val watcher = f.mob(watcherLabel, at = T.YARD)
    val speaker = f.player("Ann", at = T.HALL)
    f.input(speaker, "say hello")
    f.run(1)

    val crossRoom = f.w.memoriesOf(watcher, subject = speaker, event = "say")
    assertEquals(1, crossRoom.size)
    assertEquals(Channel.SOUND, crossRoom.single().channel)

    val inRoomWatcher = f.mob(watcherLabel, at = T.HALL)
    f.w.startCombat(speaker, inRoomWatcher)
    repeat(50) {
      f.w.say(speaker, "spam $it")
      f.run(1)
    }
    val says = f.w.memoriesOf(inRoomWatcher, subject = speaker, event = "say")
    assertEquals(1, says.size)
    assertEquals(50, says.single().count)
    assertEquals(1, f.w.memoriesOf(inRoomWatcher, subject = speaker, event = "attack").size)
  }

  @Test
  fun `same-pulse enter is remembered immediately and not duplicated in phase 5`() {
    val f = fixture()
    val watcher = f.mob(watcherLabel, at = T.YARD)
    val mover = f.player("Ann", at = T.HALL)

    f.w.transition(mover, f.w.room(T.HALL).exits.values.first { it.to == T.YARD })
    val immediate = f.w.memoriesOf(watcher, subject = mover, event = "enter")
    assertEquals(1, immediate.size)
    assertEquals(1, immediate.single().count)

    f.run(1)
    val afterPhase5 = f.w.memoriesOf(watcher, subject = mover, event = "enter")
    assertEquals(1, afterPhase5.size)
    assertEquals(1, afterPhase5.single().count)
  }

  @Test
  fun `mob outside the bubble witnesses nothing`() {
    val f = fixture(bubble = true)
    val dormantWatcher = f.mob(watcherLabel, at = T.VAULT)
    val victim = f.mob(T.GOBLIN, at = T.VAULT)
    val healer = f.mob(watcherLabel, at = T.VAULT)
    f.w.settleBubble()

    f.w.damage(victim, mapOf(f.fs.health to 5), slash, healer)
    f.w.heal(victim, f.fs.health, 3, source = healer)
    assertTrue(f.w.memoriesOf(dormantWatcher, event = "heal").isEmpty())
  }

  @Test
  fun `deliverPerceptions makes no can_perceive call for a mob with window 0 and no on_perceive`() {
    var callsForMob = 0
    val f =
      fixture(
        memoryPulses = 0,
        canPerceive = { c ->
          if (c.actor?.id != c.source?.id && c.actor?.name == "a watcher") callsForMob++
          true
        },
      )
    f.mob(watcherLabel, at = T.HALL)
    val speaker = f.player("Ann", at = T.HALL)
    f.w.say(speaker, "hello")
    f.run(1)
    assertEquals(0, callsForMob)
  }

  @Test
  fun `a resolver can forget the cast it is part of`() {
    val calm = Label("//t:calm")
    val fs = gups.core.fixtures.FixtureSchemas.current
    lateinit var mystic: gups.core.model.Mob
    val f =
      Fixture(
        rules = FixedRules(),
        scripts = RecordingHost(),
        protos =
          Prototypes(
            T.rooms,
            T.mobs +
              MobProto(
                watcherLabel,
                "a watcher",
                listOf("watcher"),
                vitals = mapOf(fs.health to 1000),
              ),
            T.items,
            T.skills +
              SkillProto(
                calm,
                kind = "spell",
                range = SkillRange.FAR,
                target = TargetKind.ALLY,
                resolve = SkillResolver { w, caster, _, _, _ -> w.forget(mystic, caster.id) },
              ),
          ),
      )
    f.w.memoryPulses = 100
    mystic = f.mob(watcherLabel)
    val mage = f.player("Mage").also { it.skills[calm] = 100 }
    val friend = f.player("Friend")
    f.input(mage, "cast calm friend")
    f.run(6)
    assertTrue(f.w.memoriesOf(mystic, subject = mage).isEmpty(), "cast must not outlive the forget")
  }
}
