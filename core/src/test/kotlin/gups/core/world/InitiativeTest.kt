package gups.core.world

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.model.MobProto
import gups.core.rules.DefaultRules
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InitiativeTest {
  private val ogre = Label("//t:ogre")
  private val imp = Label("//t:imp")
  private val extra =
    listOf(
      MobProto(ogre, "an ogre", listOf("ogre"), hp = 30),
      MobProto(imp, "an imp", listOf("imp"), hp = 5),
    )

  @Test
  fun `tiers act from highest initiative to lowest`() {
    val host = RecordingHost()
    val order = mutableListOf<String>()
    host.handlers[HookName.ON_DAMAGE] = { _, owner, ctx ->
      if (owner === ctx.target) order += ctx.actor!!.name
      null
    }
    val rules =
      FixedRules(damage = 1, initiative = mapOf("Ann" to 10, "a goblin" to 30, "an ogre" to 20))
    val f = Fixture(rules = rules, scripts = host, protos = T.protos(extraMobs = extra))
    val p = f.player("Ann")
    val g = f.mob()
    val o = f.mob(ogre)
    f.w.startCombat(p, g)
    f.w.startCombat(o, p)
    f.runTo(8)
    assertEquals(listOf("a goblin", "an ogre", "Ann"), order)
  }

  @Test
  fun `an actor killed by a higher tier never acts later in that phase`() {
    val rules = FixedRules(damage = 5, initiative = mapOf("Ann" to 20, "an imp" to 10))
    val f = Fixture(rules = rules, protos = T.protos(extraMobs = extra))
    val p = f.player("Ann")
    val i = f.mob(imp)
    f.w.startCombat(p, i)
    f.runTo(8)
    assertFalse(i.inWorld)
    assertEquals(50, p.hp)
    assertTrue(f.w.room(T.HALL).items.any { it.name == "the corpse of an imp" })
    assertEquals(50L, p.xp)
  }

  @Test
  fun `same-tier mutual kills both land`() {
    val rules = FixedRules(damage = 5, initiative = mapOf("Ann" to 20, "an imp" to 20))
    val f = Fixture(rules = rules, protos = T.protos(extraMobs = extra))
    val p = f.player("Ann", hp = 5)
    p.recallRoom = T.YARD
    val i = f.mob(imp)
    f.w.startCombat(p, i)
    f.runTo(8)
    assertFalse(i.inWorld, "the imp died")
    assertEquals(T.YARD, p.room.label, "Ann died and respawned")
    assertEquals(1, p.hp)
    assertFalse(p.isEngaged)
    assertContains(f.out(p), "You are DEAD!")
  }

  @Test
  fun `a same-tier flee and an attack on the fleeing actor both resolve`() {
    val rules = FixedRules(toHit = 0, initiative = mapOf("Ann" to 20, "a goblin" to 20), flee = 100)
    val f = Fixture(rules = rules)
    val p = f.player("Ann")
    val g = f.mob()
    g.skills[T.ZAP] = 100
    f.w.startCombat(p, g)
    f.runTo(8) // round rolled; both in one tier
    f.input(p, "flee")
    f.w.act(g, "cast zap ann")
    f.run()
    assertEquals(45, p.hp, "the zap landed")
    assertNotEquals(T.HALL, p.room.label, "the flee went through")
    assertFalse(p.isEngaged)
    assertFalse(g.isEngaged)
  }

  @Test
  fun `a lower tier sees the higher tier's flee`() {
    val rules = FixedRules(toHit = 0, initiative = mapOf("Ann" to 30, "a goblin" to 20), flee = 100)
    val f = Fixture(rules = rules)
    val p = f.player("Ann")
    val g = f.mob()
    g.skills[T.ZAP] = 100
    f.w.startCombat(p, g)
    f.runTo(8)
    f.input(p, "flee")
    f.w.act(g, "cast zap ann")
    f.run()
    assertEquals(50, p.hp, "Ann was gone before the goblin acted")
  }

  @Test
  fun `a round-boundary action pulse uses the newly rolled initiative`() {
    val rules = FixedRules(toHit = 0, initiative = mapOf("Ann" to 30, "a goblin" to 20), flee = 100)
    val f = Fixture(rules = rules)
    val p = f.player("Ann")
    val g = f.mob()
    g.skills[T.ZAP] = 100
    f.w.startCombat(p, g)
    f.runTo(15) // round one rolled with Ann ahead
    rules.initiative = mapOf("Ann" to 20, "a goblin" to 30)
    f.input(p, "flee")
    f.w.act(g, "cast zap ann")
    f.run() // pulse 16: the new roll must order this action pulse (D-002)
    assertEquals(45, p.hp, "the goblin acted before Ann's flee")
  }

  @Test
  fun `the tier snapshot covers only simulated actors`() {
    val f = Fixture(protos = C.protos(), bubble = true)
    val p = f.player("Ann", at = C.r(0))
    val near = f.w.spawnMob(C.RAT, f.w.room(C.r(2)))
    val far = f.w.spawnMob(C.RAT, f.w.room(C.r(9)))
    f.run()
    var covered: List<Boolean> = emptyList()
    f.w.runTiers(listOf(p)) { covered = listOf(p, near, far).map { f.w.snapshot!!.covers(it) } }
    assertEquals(listOf(true, true, false), covered, "the dormant rat isn't recorded (D-003)")
    assertEquals(far.room, f.w.roomOf(far), "an unrecorded actor reads its live state")
  }

  @Test
  fun `a mortally wounded actor healed within its tier survives`() {
    val rules =
      FixedRules(toHit = 0, initiative = mapOf("Ann" to 20, "a goblin" to 20, "an ogre" to 20))
    val f = Fixture(rules = rules, protos = T.protos(extraMobs = extra))
    val p = f.player("Ann")
    p.hp = 4
    val g = f.mob()
    val healer = f.mob(ogre)
    g.skills[T.ZAP] = 100
    healer.skills[T.MEND] = 100
    f.w.startCombat(p, g)
    f.w.startCombat(healer, g)
    f.runTo(8)
    f.w.act(g, "cast zap ann") // ids: Ann < goblin < ogre, so the zap lands first
    f.w.act(healer, "cast mend ann")
    f.run()
    assertTrue(p.inWorld)
    assertEquals(T.HALL, p.room.label)
    assertEquals(9, p.hp)
  }

  @Test
  fun `a stun from a higher tier stops a lower tier, but never a same-tier action`() {
    val rules = FixedRules(toHit = 0, initiative = mapOf("Ann" to 30, "a goblin" to 20))
    val f = Fixture(rules = rules)
    val p = f.player("Ann")
    p.skills[T.BASH] = 100
    val g = f.mob()
    g.skills[T.ZAP] = 100
    f.w.startCombat(p, g)
    f.runTo(8)
    f.input(p, "cast bash goblin")
    f.w.act(g, "cast zap ann")
    f.run()
    assertTrue(g.isStunned)
    assertEquals(50, p.hp)
    assertEquals(1, g.actionQueue.size)

    // Same tier: the stun lands, but the goblin still acts.
    val rules2 = FixedRules(toHit = 0, initiative = mapOf("Ann" to 20, "a goblin" to 20))
    val f2 = Fixture(rules = rules2)
    val p2 = f2.player("Ann")
    p2.skills[T.BASH] = 100
    val g2 = f2.mob()
    g2.skills[T.ZAP] = 100
    f2.w.startCombat(p2, g2)
    f2.runTo(8)
    f2.input(p2, "cast bash goblin")
    f2.w.act(g2, "cast zap ann")
    f2.run()
    assertTrue(g2.isStunned)
    assertEquals(45, p2.hp)
  }

  @Test
  fun `replays with the same seed are identical`() {
    fun fight(seed: Long): String {
      val f = Fixture(seed = seed, rules = DefaultRules())
      val p = f.player("Ann", hp = 40)
      val s = f.w.spawnItem(T.SWORD, ItemLocation.Carried(p.id))
      f.mob()
      f.mob()
      f.input(p, "wield sword")
      f.input(p, "kill goblin")
      f.input(p, "kill 2.goblin")
      f.run(200)
      return f.sink(p).transcript + "|${p.hp}|${p.xp}|${p.room.label}|${s.location}"
    }
    val a = fight(42)
    assertEquals(a, fight(42))
    assertContains(a, "You hit")
  }
}
