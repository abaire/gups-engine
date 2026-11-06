package gups.core.world

import gups.core.Label
import gups.core.model.MobProto
import gups.core.model.Prototypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnedBehaviorsTest {
  private fun mob(name: String, vararg behaviors: Behavior) =
    MobProto(
      Label("//t:$name"),
      name,
      listOf(name),
      vitals = mapOf("hp" to 40),
      behaviors = behaviors.toList(),
    )

  private fun fixture(vararg mobs: MobProto, features: Features = Features.ALL) =
    Fixture(
      rules = FixedRules(flee = 100),
      protos = T.protos(extraMobs = mobs.toList()),
      features = features,
    )

  @Test
  fun `every behavior with a priority proposes at its priority argument`() {
    val f = fixture(mob("pet"))
    val ann = f.player("Ann")
    val m = f.mob(Label("//t:pet"))
    f.w.setOwner(m, ann)
    val goblin = f.mob(T.GOBLIN)
    f.w.startCombat(ann, goblin)
    val key = f.w.spawnItem(T.KEY, gups.core.model.ItemLocation.InRoom(f.w.room(T.HALL).id, 0))
    assertEquals(11, Wander(2, 100, priority = 11).propose(f.w, f.mob(T.DUMMY))?.priority)
    assertEquals(12, Aggressive(ActorFilter("mobs"), priority = 12).propose(f.w, m)?.priority)
    assertEquals(13, Assist(ActorFilter("players"), priority = 13).propose(f.w, m)?.priority)
    assertEquals(14, Flee(mapOf("hp" to 100), priority = 14).propose(f.w, m)?.priority)
    assertEquals(17, Defend(priority = 17).propose(f.w, m)?.priority)
    assertEquals(15, Scavenge(priority = 15).propose(f.w, m)?.priority)
    assertNotNull(key)
  }

  @Test
  fun `follow and pursue honor a custom priority`() {
    val f = fixture(mob("pet"))
    val m = f.mob(Label("//t:pet"))
    val ann = f.player("Ann", at = T.YARD)
    m.leader = ann.id
    assertEquals(16, Follow(ActorFilter("players"), priority = 16).propose(f.w, m)?.priority)
    assertEquals(18, Pursue(ActorFilter("players"), priority = 18).propose(f.w, m)?.priority)
  }

  @Test
  fun `priority reorders two behaviors`() {
    val wantsMove =
      fixture(
        mob("p", Aggressive(ActorFilter("players"), priority = 5), Wander(3, 100, priority = 90))
      )
    val p = wantsMove.mob(Label("//t:p"))
    val ann = wantsMove.player("Ann")
    wantsMove.runTo(3)
    assertFalse(ann.id in p.fighting, "the higher-priority wander wins")
  }

  private fun corridor(vararg mobs: MobProto): Fixture {
    val c = C.protos(n = 8)
    return Fixture(
      rules = FixedRules(flee = 100),
      protos = Prototypes(c.rooms.values.toList(), mobs.toList(), zones = c.zones.values.toList()),
    )
  }

  @Test
  fun `heel follows an owner far beyond the bubble radius`() {
    val pet = MobProto(Label("//c:pet"), "pet", listOf("pet"), vitals = mapOf("hp" to 40))
    val f = corridor(pet)
    val m = f.w.spawnMob(pet.label, f.w.room(C.r(0)))
    val ann = f.player("Ann", at = C.r(6))
    f.w.setOwner(m, ann)
    f.runTo(80)
    assertEquals(C.r(6), m.room.label)
  }

  @Test
  fun `heel does nothing when engaged or unowned`() {
    val f = fixture(mob("pet"))
    val m = f.mob(Label("//t:pet"))
    val ann = f.player("Ann", at = T.YARD)
    assertNull(Heel().propose(f.w, m), "unowned")
    f.w.setOwner(m, ann)
    assertNotNull(Heel().propose(f.w, m))
    f.w.startCombat(m, f.mob(T.GOBLIN))
    assertNull(Heel().propose(f.w, m), "engaged")
  }

  @Test
  fun `defend joins the owner's fight, and with group a groupmate's`() {
    val f = fixture(mob("pet"))
    val m = f.mob(Label("//t:pet"))
    val ann = f.player("Ann")
    val bo = f.player("Bo")
    f.w.groupAdd(bo, ann)
    f.w.setOwner(m, ann)
    assertNull(Defend().propose(f.w, m), "nobody is fighting")
    val goblin = f.mob(T.GOBLIN)
    f.w.startCombat(bo, goblin)
    assertTrue(Defend(group = true).propose(f.w, m)!!.commandLine.endsWith(" goblin"))
    assertNull(Defend(group = false).propose(f.w, m), "only the owner's own fights")
    f.w.startCombat(ann, goblin)
    assertTrue(Defend(group = false).propose(f.w, m)!!.commandLine.endsWith(" goblin"))
  }

  @Test
  fun `defend never picks an owner-side actor`() {
    val f = fixture(mob("pet"))
    val m = f.mob(Label("//t:pet"))
    val ann = f.player("Ann")
    val bo = f.player("Bo")
    f.w.groupAdd(bo, ann)
    f.w.setOwner(m, ann)
    f.w.startCombat(ann, bo)
    assertNull(Defend().propose(f.w, m))
  }

  @Test
  fun `defend needs combat`() {
    val noCombat =
      Features(
        Feature.entries.toSet() -
          Feature.COMBAT -
          Feature.entries.filter { Feature.COMBAT in it.requires }.toSet()
      )
    val f = fixture(mob("pet"), features = noCombat)
    val m = f.mob(Label("//t:pet"))
    val ann = f.player("Ann")
    f.w.setOwner(m, ann)
    assertTrue(f.w.behaviorsOf(m).none { it is Defend })
    assertNull(Defend().propose(f.w, m))
  }

  @Test
  fun `the default owned list keeps flee and sentinel, then heel and defend`() {
    val flee = Flee(mapOf("hp" to 20))
    val f = fixture(mob("pet", Wander(2, 100), flee, Aggressive(ActorFilter("players")), Sentinel))
    val m = f.mob(Label("//t:pet"))
    assertEquals(m.proto.behaviors, f.w.behaviorsOf(m))
    val ann = f.player("Ann")
    f.w.setOwner(m, ann)
    assertEquals(listOf(flee, Sentinel, Heel(), Defend()), f.w.behaviorsOf(m))
    f.w.clearOwner(m)
    assertEquals(m.proto.behaviors, f.w.behaviorsOf(m))
  }

  @Test
  fun `an owned wanderer neither wanders nor attacks its owner, until released`() {
    val f = fixture(mob("pet", Wander(3, 100), Aggressive(ActorFilter("players"))))
    val m = f.mob(Label("//t:pet"))
    val ann = f.player("Ann")
    f.w.setOwner(m, ann)
    f.runTo(20)
    assertEquals(T.HALL, m.room.label)
    assertFalse(ann.id in m.fighting)
    f.w.clearOwner(m)
    f.runTo(40)
    assertTrue(ann.id in m.fighting || m.room.label != T.HALL, "it acts like a wild mob again")
  }

  @Test
  fun `aggressive and assist skip the owner's side`() {
    val f = fixture(mob("pet"))
    val m = f.mob(Label("//t:pet"))
    val ann = f.player("Ann")
    val bo = f.player("Bo")
    f.w.groupAdd(bo, ann)
    f.w.setOwner(m, ann)
    assertNull(Aggressive(ActorFilter("players")).propose(f.w, m), "owner and groupmate are spared")
    val stranger = f.player("Cy")
    assertNotNull(Aggressive(ActorFilter("players")).propose(f.w, m), "a stranger is not")
    f.w.quit(stranger)
    f.w.startCombat(ann, bo)
    assertNull(
      Assist(ActorFilter("players")).propose(f.w, m),
      "never joins against the owner's side",
    )
  }

  @Test
  fun `a kept sentinel stops heel`() {
    val pet =
      MobProto(
        Label("//c:pet"),
        "pet",
        listOf("pet"),
        vitals = mapOf("hp" to 40),
        behaviors = listOf(Sentinel),
      )
    val f = corridor(pet)
    val m = f.w.spawnMob(pet.label, f.w.room(C.r(0)))
    val ann = f.player("Ann", at = C.r(3))
    f.w.setOwner(m, ann)
    f.runTo(40)
    assertEquals(C.r(0), m.room.label)
  }
}
