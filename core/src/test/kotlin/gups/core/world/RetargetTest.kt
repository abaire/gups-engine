package gups.core.world

import gups.core.AttrValue
import gups.core.Label
import gups.core.model.Actor
import gups.core.model.FactionProto
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import gups.core.rules.RulesContext
import gups.core.rules.RulesEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RetargetTest {
  private val start = Label("//t:start")
  private val fFoe = Label("//t:foe")
  private val fFriend = Label("//t:friend")

  private fun world(
    mobs: List<MobProto>,
    factions: List<FactionProto> = emptyList(),
    rules: RulesEngine = FixedRules(),
  ): World {
    val protos =
      Prototypes(rooms = listOf(RoomProto(start, "Start")), mobs = mobs, factions = factions)
    return Fixture(protos = protos, rules = rules).w
  }

  @Test
  fun `proposes nothing while not engaged and nothing with chance 0`() {
    var calls = 0
    val prefer = AttrValue.S("switch")
    val orc1 =
      MobProto(
        Label("//t:orc1"),
        "an orc",
        keywords = listOf("orc"),
        behaviors = listOf(Retarget(prefer = prefer)),
      )
    val orc0 =
      MobProto(
        Label("//t:orc0"),
        "a lazy orc",
        keywords = listOf("orc"),
        behaviors = listOf(Retarget(prefer = prefer, chance = 0)),
      )
    val w =
      world(
        listOf(orc1, orc0),
        rules =
          object : FixedRules() {
            override fun targetOrder(c: RulesContext): TargetChoice {
              calls++
              return TargetChoice.Pick(c.candidates!!.last())
            }
          },
      )
    val p1 = w.spawnPlayer("Ann", start)
    w.spawnPlayer("Bob", start)
    val m1 = w.spawnMob(orc1.label, w.room(start))
    val m0 = w.spawnMob(orc0.label, w.room(start))

    // Not engaged: proposes nothing and never calls targetOrder.
    assertNull(m1.proto.behaviors.single().propose(w, m1))
    assertEquals(0, calls)

    // Engaged with chance = 0: proposes nothing and never calls targetOrder.
    w.startCombat(p1, m0)
    assertNull(m0.proto.behaviors.single().propose(w, m0))
    assertEquals(0, calls)
  }

  @Test
  fun `engaged opponents bypass toward and notice while bystanders must pass toward disposition and notice`() {
    var seenCandidates: List<Actor>? = null
    val prefer = AttrValue.S("switch")
    val orc =
      MobProto(
        Label("//t:orc"),
        "an orc",
        keywords = listOf("orc"),
        factions = listOf(fFoe),
        behaviors =
          listOf(Retarget(prefer = prefer, toward = ActorFilter("players"), dispositionBelow = 0)),
      )
    val engagedMobFoe =
      MobProto(Label("//t:wolf"), "a wolf", keywords = listOf("wolf"), factions = listOf(fFriend))
    val bystanderMob =
      MobProto(Label("//t:boar"), "a boar", keywords = listOf("boar"), factions = listOf(fFoe))
    val w =
      world(
        listOf(orc, engagedMobFoe, bystanderMob),
        factions =
          listOf(
            FactionProto(fFoe, standing = mapOf(fFoe to -50, fFriend to 50)),
            FactionProto(fFriend),
          ),
        rules =
          object : FixedRules() {
            override fun factions(c: RulesContext): List<Label> =
              when (c.actor?.name) {
                "Liked" -> listOf(fFriend)
                else -> listOf(fFoe)
              }

            override fun canPerceive(c: RulesContext): Boolean {
              if (c.event == "notice") {
                return c.source?.name != "UnseenEngaged" && c.source?.name != "UnseenBystander"
              }
              return true
            }

            override fun targetOrder(c: RulesContext): TargetChoice {
              seenCandidates = c.candidates
              return TargetChoice.Nobody
            }
          },
      )

    val unseenEngaged = w.spawnPlayer("UnseenEngaged", start)
    val wolf = w.spawnMob(engagedMobFoe.label, w.room(start))
    val unseenBystander = w.spawnPlayer("UnseenBystander", start)
    val likedBystander = w.spawnPlayer("Liked", start)
    val boar = w.spawnMob(bystanderMob.label, w.room(start))
    val validBystander = w.spawnPlayer("ValidBystander", start)
    val mob = w.spawnMob(orc.label, w.room(start))

    // Engage unseenEngaged (fails notice) and wolf (fails toward="players" and dispositionBelow=0).
    w.startCombat(unseenEngaged, mob)
    w.startCombat(wolf, mob)

    mob.proto.behaviors.single().propose(w, mob)
    // Engaged opponents (unseenEngaged, wolf) are included despite failing
    // toward/disposition/notice;
    // bystanders must pass all three (so only validBystander is included).
    assertEquals(listOf(unseenEngaged, wolf, validBystander), seenCandidates)
    assertTrue(unseenBystander !== likedBystander && boar !== wolf)
  }

  @Test
  fun `picking current primary target Nobody or Default proposes nothing while picking another switches and engages`() {
    var mode = "current"
    var seenCurrent: Actor? = null
    var seenSince: Long? = null
    val prefer = AttrValue.S("switch")
    val orc =
      MobProto(
        Label("//t:orc"),
        "an orc",
        keywords = listOf("orc"),
        vitals = mapOf("hp" to 200),
        behaviors = listOf(Retarget(prefer = prefer)),
      )
    val w =
      world(
        listOf(orc),
        rules =
          object : FixedRules() {
            override fun targetOrder(c: RulesContext): TargetChoice {
              seenCurrent = c.current
              seenSince = c.currentSince
              return when (mode) {
                "current" -> TargetChoice.Pick(c.current!!)
                "nobody" -> TargetChoice.Nobody
                "default" -> TargetChoice.Default
                "second" -> TargetChoice.Pick(c.candidates!![1])
                else -> TargetChoice.Nobody
              }
            }
          },
      )
    val p1 = w.spawnPlayer("Ann", start)
    val p2 = w.spawnPlayer("Bob", start)
    val mob = w.spawnMob(orc.label, w.room(start))

    w.runPulses(5)
    w.startCombat(p1, mob)
    assertEquals(p1.id, mob.primaryTarget)

    // Picking current primary target proposes nothing.
    assertNull(mob.proto.behaviors.single().propose(w, mob))
    assertSame(p1, seenCurrent)
    assertEquals(5L, seenSince)

    // Nobody and Default propose nothing.
    mode = "nobody"
    assertNull(mob.proto.behaviors.single().propose(w, mob))
    mode = "default"
    assertNull(mob.proto.behaviors.single().propose(w, mob))

    // Picking a bystander proposes kill at priority 55; resolving switches primaryTarget and
    // engages it.
    mode = "second"
    w.runPulses(3)
    val prop = mob.proto.behaviors.single().propose(w, mob)!!
    assertEquals(PRIORITY_RETARGET, prop.priority)
    assertEquals("retarget", prop.source)
    assertEquals("kill bob", prop.commandLine)
    prop.action!!.resolve(w, mob)
    assertEquals(p2.id, mob.primaryTarget)
    assertTrue(p2.id in mob.fighting)
    assertEquals(8L, mob.primaryTargetSince)
  }

  @Test
  fun `owned mob never gets owner or owner groupmate as candidate`() {
    var seenCandidates: List<Actor>? = null
    val prefer = AttrValue.S("switch")
    val orc =
      MobProto(
        Label("//t:orc"),
        "an orc",
        keywords = listOf("orc"),
        behaviors = listOf(Retarget(prefer = prefer, toward = ActorFilter("all"))),
      )
    val foeProto = MobProto(Label("//t:foe"), "a foe", keywords = listOf("foe"))
    val w =
      world(
        listOf(orc, foeProto),
        rules =
          object : FixedRules() {
            override fun targetOrder(c: RulesContext): TargetChoice {
              seenCandidates = c.candidates
              return TargetChoice.Nobody
            }
          },
      )
    val owner = w.spawnPlayer("Owner", start)
    val mate = w.spawnPlayer("Mate", start)
    val stranger = w.spawnPlayer("Stranger", start)
    val foe = w.spawnMob(foeProto.label, w.room(start))
    val pet = w.spawnMob(orc.label, w.room(start))
    w.setOwner(pet, owner)
    w.groupAdd(mate, owner)

    w.startCombat(pet, foe)
    pet.proto.behaviors.single().propose(w, pet)
    assertEquals(listOf(stranger, foe), seenCandidates)
  }

  @Test
  fun `flee beats retarget and retarget outranks assist`() {
    val prefer = AttrValue.S("switch")
    val orc =
      MobProto(
        Label("//t:orc"),
        "an orc",
        keywords = listOf("orc"),
        vitals = mapOf("hp" to 100),
        behaviors =
          listOf(
            Flee(below = mapOf("hp" to 50)),
            Retarget(prefer = prefer),
            Assist(allies = ActorFilter("mobs")),
          ),
      )
    val w =
      world(
        listOf(orc),
        rules =
          object : FixedRules() {
            override fun targetOrder(c: RulesContext): TargetChoice =
              TargetChoice.Pick(c.candidates!!.last())
          },
      )
    val p1 = w.spawnPlayer("Ann", start)
    w.spawnPlayer("Bob", start)
    val mob = w.spawnMob(orc.label, w.room(start))
    w.startCombat(p1, mob)

    val retargetProp = mob.proto.behaviors[1].propose(w, mob)!!
    assertTrue(PRIORITY_FLEE > retargetProp.priority)
    assertTrue(retargetProp.priority > PRIORITY_ASSIST)
  }
}
