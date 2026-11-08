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

class TargetOrderTest {
  private val start = Label("//t:start")
  private val fFoe = Label("//t:foe")
  private val fFriend = Label("//t:friend")

  private fun world(
    mobs: List<MobProto>,
    factions: List<FactionProto> = emptyList(),
    rules: RulesEngine = FixedRules(),
    features: Features = Features.ALL,
  ): World {
    val protos =
      Prototypes(rooms = listOf(RoomProto(start, "Start")), mobs = mobs, factions = factions)
    return Fixture(protos = protos, rules = rules, features = features).w
  }

  @Test
  fun `without prefer targetOrder is never called and lowest id is attacked`() {
    var calls = 0
    val orc =
      MobProto(
        Label("//t:orc"),
        "an orc",
        keywords = listOf("orc"),
        behaviors = listOf(Aggressive(ActorFilter("players"))),
      )
    val w =
      world(
        listOf(orc),
        rules =
          object : FixedRules() {
            override fun targetOrder(c: RulesContext): TargetChoice {
              calls++
              return TargetChoice.Nobody
            }
          },
      )
    val p1 = w.spawnPlayer("Ann", start)
    val p2 = w.spawnPlayer("Bob", start)
    val mob = w.spawnMob(orc.label, w.room(start))
    val prop = mob.proto.behaviors.single().propose(w, mob)
    assertEquals(0, calls)
    assertSame(p1, (prop?.action as? KillAction)?.target)
    assertTrue(p1.id < p2.id)
  }

  @Test
  fun `with prefer Pick attacks chosen candidate Nobody proposes nothing Default attacks lowest id`() {
    var mode = "pick_second"
    var seenPrefer: AttrValue? = null
    val prefer = AttrValue.S("weakest")
    val orc =
      MobProto(
        Label("//t:orc"),
        "an orc",
        keywords = listOf("orc"),
        behaviors = listOf(Aggressive(ActorFilter("players"), prefer = prefer)),
      )
    val w =
      world(
        listOf(orc),
        rules =
          object : FixedRules() {
            override fun targetOrder(c: RulesContext): TargetChoice {
              seenPrefer = c.prefer
              return when (mode) {
                "pick_second" -> TargetChoice.Pick(c.candidates!![1])
                "nobody" -> TargetChoice.Nobody
                else -> TargetChoice.Default
              }
            }
          },
      )
    val p1 = w.spawnPlayer("Ann", start)
    val p2 = w.spawnPlayer("Bob", start)
    val mob = w.spawnMob(orc.label, w.room(start))
    val b = mob.proto.behaviors.single()

    val propPick = b.propose(w, mob)
    assertEquals(prefer, seenPrefer)
    assertSame(p2, (propPick?.action as? KillAction)?.target)

    mode = "nobody"
    assertNull(b.propose(w, mob))

    mode = "default"
    val propDefault = b.propose(w, mob)
    assertSame(p1, (propDefault?.action as? KillAction)?.target)
  }

  @Test
  fun `candidates passed exclude actors failing toward level band owner side disposition or notice`() {
    var seenCandidates: List<Actor> = emptyList()
    val fOrc = Label("//t:orcs")
    val orc =
      MobProto(
        Label("//t:orc"),
        "an orc",
        keywords = listOf("orc"),
        factions = listOf(fOrc),
        behaviors =
          listOf(
            Aggressive(
              toward = ActorFilter("players"),
              unlessLevelAbove = 10,
              unlessLevelBelow = 2,
              dispositionBelow = 0,
              prefer = AttrValue.S("order"),
            )
          ),
      )
    val helperMob = MobProto(Label("//t:helper"), "a helper", keywords = listOf("helper"))
    val w =
      world(
        listOf(orc, helperMob),
        factions =
          listOf(
            FactionProto(fOrc, standing = mapOf(fFoe to -50, fFriend to 50)),
            FactionProto(fFoe),
            FactionProto(fFriend),
          ),
        rules =
          object : FixedRules() {
            override fun canPerceive(c: RulesContext): Boolean = c.source?.name != "Unseen"

            override fun factions(c: RulesContext): List<Label>? =
              when (c.actor?.name) {
                "Friend" -> listOf(fFriend)
                "an orc" -> listOf(fOrc)
                else -> listOf(fFoe)
              }

            override fun targetOrder(c: RulesContext): TargetChoice {
              seenCandidates = c.candidates.orEmpty()
              return TargetChoice.Default
            }
          },
      )
    val valid1 = w.spawnPlayer("ValidOne", start).also { it.level = 5 }
    val tooHigh = w.spawnPlayer("TooHigh", start).also { it.level = 15 }
    val tooLow = w.spawnPlayer("TooLow", start).also { it.level = 1 }
    val owner = w.spawnPlayer("Owner", start).also { it.level = 5 }
    val friend = w.spawnPlayer("Friend", start).also { it.level = 5 }
    val unseen = w.spawnPlayer("Unseen", start).also { it.level = 5 }
    val valid2 = w.spawnPlayer("ValidTwo", start).also { it.level = 5 }
    val otherMob = w.spawnMob(helperMob.label, w.room(start)).also { it.level = 5 }
    val mob = w.spawnMob(orc.label, w.room(start))
    w.setOwner(mob, owner)

    val prop = mob.proto.behaviors.single().propose(w, mob)
    assertEquals(listOf<Actor>(valid1, valid2), seenCandidates)
    assertSame(valid1, (prop?.action as? KillAction)?.target)
    assertTrue(tooHigh !in seenCandidates)
    assertTrue(tooLow !in seenCandidates)
    assertTrue(owner !in seenCandidates)
    assertTrue(friend !in seenCandidates)
    assertTrue(unseen !in seenCandidates)
    assertTrue(otherMob !in seenCandidates)
  }

  @Test
  fun `assist with prefer passes allys foes and current as allys primary target`() {
    var seenCandidates: List<Actor> = emptyList()
    var seenCurrent: Actor? = null
    var seenCurrentSince: Long? = null
    var seenAlly: Actor? = null
    var seenBehavior: String? = null
    var choiceMode = "pick_first"
    val goblin = MobProto(Label("//t:goblin"), "a goblin", keywords = listOf("goblin"))
    val helper =
      MobProto(
        Label("//t:helper"),
        "a helper",
        keywords = listOf("helper"),
        behaviors = listOf(Assist(ActorFilter("goblin"), prefer = AttrValue.S("focus"))),
      )
    val w =
      world(
        listOf(goblin, helper),
        rules =
          object : FixedRules() {
            override fun targetOrder(c: RulesContext): TargetChoice {
              seenCandidates = c.candidates.orEmpty()
              seenCurrent = c.current
              seenCurrentSince = c.currentSince
              seenAlly = c.ally
              seenBehavior = c.behavior
              return if (choiceMode == "nobody") TargetChoice.Nobody
              else TargetChoice.Pick(c.candidates!!.first())
            }
          },
      )
    val p1 = w.spawnPlayer("Ann", start)
    val p2 = w.spawnPlayer("Bob", start)
    val allyMob = w.spawnMob(goblin.label, w.room(start))
    val helperMob = w.spawnMob(helper.label, w.room(start))
    w.runPulses(7)
    w.startCombat(p1, allyMob)
    w.startCombat(p2, allyMob)
    w.setPrimaryTarget(allyMob, p2.id)

    val b = helperMob.proto.behaviors.single()
    val prop = b.propose(w, helperMob)
    assertEquals("assist", seenBehavior)
    assertEquals(listOf<Actor>(p1, p2), seenCandidates)
    assertSame(p2, seenCurrent)
    assertNull(seenCurrentSince)
    assertSame(allyMob, seenAlly)
    assertSame(p1, (prop?.action as? KillAction)?.target)

    choiceMode = "nobody"
    assertNull(b.propose(w, helperMob))
  }

  @Test
  fun `adjuster that returns non-candidate or throws is ignored with plugin diagnostic`() {
    val orc =
      MobProto(
        Label("//t:orc"),
        "an orc",
        keywords = listOf("orc"),
        behaviors = listOf(Aggressive(ActorFilter("players"), prefer = AttrValue.S("order"))),
      )
    val bystanderProto = MobProto(Label("//t:bystander"), "a bystander", keywords = listOf("by"))
    val w =
      world(
        listOf(orc, bystanderProto),
        rules =
          object : FixedRules() {
            override fun targetOrder(c: RulesContext): TargetChoice =
              TargetChoice.Pick(c.candidates!!.last())
          },
      )
    val p1 = w.spawnPlayer("Ann", start)
    val p2 = w.spawnPlayer("Bob", start)
    val bystander = w.spawnMob(bystanderProto.label, w.room(start))
    val mob = w.spawnMob(orc.label, w.room(start))

    w.targetOrderAdjusters =
      listOf(
        TargetOrderAdjuster { _, _, _ -> TargetChoice.Pick(bystander) },
        TargetOrderAdjuster { _, _, _ -> error("boom") },
      )
    val prop = mob.proto.behaviors.single().propose(w, mob)
    assertSame(p2, (prop?.action as? KillAction)?.target)
    assertTrue(p1 !== p2)
    val diags = w.diagnostics.filter { it.kind == "plugin" }
    assertEquals(2, diags.size)
  }

  @Test
  fun `primaryTargetSince updates on kill on engagement and on disengagement`() {
    val orc =
      MobProto(Label("//t:orc"), "an orc", keywords = listOf("orc"), vitals = mapOf("hp" to 500))
    val w = world(listOf(orc), rules = FixedRules(toHit = 0))
    val p1 = w.spawnPlayer("Ann", start)
    val p2 = w.spawnPlayer("Bob", start)
    val mob = w.spawnMob(orc.label, w.room(start))
    assertEquals(0L, p1.primaryTargetSince)
    assertEquals(0L, mob.primaryTargetSince)

    w.runPulses(5)
    w.startCombat(p1, mob)
    assertEquals(mob.id, p1.primaryTarget)
    assertEquals(5L, p1.primaryTargetSince)
    assertEquals(p1.id, mob.primaryTarget)
    assertEquals(5L, mob.primaryTargetSince)

    // Engaging a second opponent does not change mob's existing primaryTarget or
    // primaryTargetSince.
    w.runPulses(4)
    w.startCombat(p2, mob)
    assertEquals(5L, mob.primaryTargetSince)

    // KillAction switching target updates primaryTargetSince.
    w.runPulses(3)
    KillAction(p2).resolve(w, mob)
    assertEquals(p2.id, mob.primaryTarget)
    assertEquals(12L, mob.primaryTargetSince)

    // Calling KillAction with the same target does not bump primaryTargetSince.
    w.runPulses(3)
    KillAction(p2).resolve(w, mob)
    assertEquals(12L, mob.primaryTargetSince)

    // Disengaging clears primaryTarget and updates primaryTargetSince.
    w.runPulses(5)
    w.stopCombat(mob)
    assertNull(mob.primaryTarget)
    assertEquals(20L, mob.primaryTargetSince)
  }
}
