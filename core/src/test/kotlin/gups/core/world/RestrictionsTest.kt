package gups.core.world

import gups.core.Label
import gups.core.Role
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.ModifierPoint
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import gups.core.model.RestrictionRequest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestrictionsTest {
  private val host = RecordingHost()
  private val rules = FixedRules(flee = 100)

  private val HELM = Label("//t:helm")
  private val DAGGER = Label("//t:dagger")
  private val RING = Label("//t:ring")

  private val testItems =
    T.items +
      listOf(
        ItemProto(HELM, "a helmet", listOf("helmet"), wear = "head"),
        ItemProto(DAGGER, "a dagger", listOf("dagger"), wear = "wield"),
        ItemProto(RING, "a ring", listOf("ring"), wear = "finger"),
      )

  private val f =
    Fixture(
      rules = rules,
      scripts = host,
      protos =
        Prototypes(
          listOf(T.rooms[0].copy(modifiers = mapOf(ModifierPoint.CAST_ALLOWED to mod("allowed")))) +
            T.rooms.drop(1),
          T.mobs,
          testItems,
          T.skills,
        ),
    )

  /** Every request the checks saw, in order, with the hooks the world ran around them. */
  private val order = mutableListOf<String>()

  private fun restrict(point: RestrictionPoint, vararg checks: Restriction) {
    f.w.restrictions = f.w.restrictions + (point to checks.toList())
  }

  /** A check that records its call and refuses with [message] while [refuse] says so. */
  private fun check(message: String, refuse: (RestrictionRequest) -> Boolean) =
    Restriction { _, r ->
      order +=
        "restrict:${r.point.name.lowercase()}:${r.room?.label?.name ?: r.item?.protoLabel?.name ?: r.skill?.label?.name}"
      if (refuse(r)) message else null
    }

  init {
    host.handlers[HookName.BEFORE_ENTER] = { _, owner, _ ->
      order += "before_enter:${owner.name}"
      null
    }
    host.modifiers["allowed"] = { _, _, base ->
      order += "cast_allowed"
      base
    }
  }

  @Test
  fun `a wear check refuses with its message and no lag, and allows when it allows`() {
    var refuse = true
    restrict(RestrictionPoint.WEAR, check("Too heavy for you.") { refuse })
    val p = f.player("Ann")
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Carried(p.id))
    f.input(p, "wield sword")
    f.run()
    assertContains(f.out(p), "Too heavy for you.")
    assertNull(p.equipped("wield"))
    assertEquals(0, p.waitState)
    refuse = false
    f.input(p, "wield sword")
    f.run()
    assertEquals(sword, p.equipped("wield"))
  }

  @Test
  fun `a wear refusal comes before anything worn is swapped out`() {
    val p = f.player("Ann")
    val bow = f.w.spawnItem(T.BOW, ItemLocation.Carried(p.id))
    f.w.moveItem(bow, ItemLocation.Equipped(p.id, "wield"))
    f.w.spawnItem(T.SWORD, ItemLocation.Carried(p.id))
    restrict(RestrictionPoint.WEAR, check("Not that.") { it.item?.protoLabel == T.SWORD })
    f.out(p)
    f.input(p, "wield sword")
    f.run()
    val out = f.out(p)
    assertContains(out, "Not that.")
    assertEquals(bow, p.equipped("wield"))
    assertTrue("stop" !in out, out)
    assertEquals(listOf("restrict:wear:sword"), order)
  }

  @Test
  fun `an enter check runs before before_enter when walking and refuses with no lag`() {
    var refuse = true
    restrict(RestrictionPoint.ENTER, check("The yard is closed.") { refuse })
    val p = f.player("Ann")
    f.input(p, "north")
    f.run()
    assertContains(f.out(p), "The yard is closed.")
    assertEquals(T.HALL, p.room.label)
    assertEquals(0, p.waitState)
    assertEquals(listOf("restrict:enter:yard"), order)
    order.clear()
    refuse = false
    f.input(p, "north")
    f.run()
    assertEquals(T.YARD, p.room.label)
    assertEquals("restrict:enter:yard", order.first())
    assertTrue(
      order.indexOf("restrict:enter:yard") < order.indexOfFirst { it.startsWith("before_enter") },
      "$order",
    )
  }

  @Test
  fun `an enter check covers fleeing`() {
    restrict(RestrictionPoint.ENTER, check("No way out.") { true })
    val p = f.player("Ann")
    f.w.startCombat(p, f.mob())
    f.input(p, "flee")
    f.run()
    assertEquals(T.HALL, p.room.label)
    assertTrue(order.isNotEmpty() && order.all { it.startsWith("restrict:enter:") }, "$order")
  }

  @Test
  fun `an enter check covers teleport, for players and mobs, and can allow mobs`() {
    restrict(RestrictionPoint.ENTER, check("You are pushed back.") { it.actor is Player })
    val p = f.player("Ann")
    val goblin = f.mob()
    f.out(p)
    f.w.teleport(p, f.w.room(T.YARD))
    f.run()
    assertContains(f.out(p), "You are pushed back.")
    assertEquals(T.HALL, p.room.label)
    f.w.teleport(goblin, f.w.room(T.YARD))
    f.run()
    assertEquals(T.YARD, goblin.room.label)
  }

  @Test
  fun `a check that sees a mob can refuse it`() {
    restrict(RestrictionPoint.ENTER, check("Mobs stay.") { it.actor !is Player })
    val goblin = f.mob()
    f.w.teleport(goblin, f.w.room(T.YARD))
    f.run()
    assertEquals(T.HALL, goblin.room.label)
  }

  @Test
  fun `a cast check runs before cast_allowed and refuses with no lag or cost`() {
    var refuse = true
    restrict(RestrictionPoint.CAST, check("Not your kind of magic.") { refuse })
    val p = f.player("Ann", mana = 50)
    p.skills[T.BOLT] = 100
    f.mob(T.DUMMY)
    f.input(p, "cast bolt dummy")
    f.run()
    assertContains(f.out(p), "Not your kind of magic.")
    assertEquals(50, p.current("mana"))
    assertEquals(0, p.waitState)
    assertNull(p.casting)
    assertEquals(listOf("restrict:cast:bolt"), order)
    order.clear()
    refuse = false
    f.input(p, "cast bolt dummy")
    f.run()
    assertEquals(listOf("restrict:cast:bolt", "cast_allowed"), order.take(2))
  }

  @Test
  fun `a learn check runs in setSkill`() {
    restrict(
      RestrictionPoint.LEARN,
      Restriction { _, r ->
        if (r.skill?.label == T.BOLT && (r.proficiency ?: 0) > 10) "Too advanced." else null
      },
    )
    val p = f.player("Ann")
    assertEquals("Too advanced.", f.w.setSkill(p, T.BOLT, 50))
    assertNull(p.skills[T.BOLT])
    assertNull(f.w.setSkill(p, T.BOLT, 10))
    assertEquals(10, p.skills[T.BOLT])
    assertNull(f.w.setSkill(p, T.ZAP, 90))
  }

  @Test
  fun `a check reading the role lets a builder through`() {
    restrict(
      RestrictionPoint.ENTER,
      Restriction { _, r ->
        val a = r.actor
        if (a is Player && a.role.atLeast(Role.BUILDER)) null else "Staff only."
      },
    )
    val ann = f.player("Ann")
    val bob = f.player("Bob")
    bob.role = Role.BUILDER
    f.input(ann, "north")
    f.input(bob, "north")
    f.run()
    assertEquals(T.HALL, ann.room.label)
    assertEquals(T.YARD, bob.room.label)
  }

  @Test
  fun `a throwing check allows and records a diagnostic`() {
    restrict(RestrictionPoint.ENTER, Restriction { _, _ -> error("check broke") })
    val p = f.player("Ann")
    f.input(p, "north")
    f.run()
    assertEquals(T.YARD, p.room.label)
    assertTrue(f.w.diagnostics.any { it.kind == "plugin" && "check broke" in it.message })
  }

  @Test
  fun `checks run in order and the first refusal wins`() {
    restrict(
      RestrictionPoint.ENTER,
      check("First.") { false },
      check("Second.") { true },
      check("Third.") { true },
    )
    val p = f.player("Ann")
    f.input(p, "north")
    f.run()
    val out = f.out(p)
    assertContains(out, "Second.")
    assertTrue("Third." !in out)
    assertEquals(2, order.size)
  }

  @Test
  fun `each of remove, drop, give and put of a tagged item is refused, nothing moves, and waitState stays 0`() {
    val tagCheck = Restriction { _, r -> if ("t" in r.item?.tags.orEmpty()) "Nope." else null }
    val p = f.player("Ann")
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Carried(p.id))
    f.w.addItemTag(sword, "t")

    // remove
    f.w.moveItem(sword, ItemLocation.Equipped(p.id, "wield"))
    restrict(RestrictionPoint.REMOVE, tagCheck)
    f.input(p, "remove sword")
    f.run()
    assertContains(f.out(p), "Nope.")
    assertEquals(ItemLocation.Equipped(p.id, "wield"), sword.location)
    assertEquals(0, p.waitState)

    // drop
    f.w.moveItem(sword, ItemLocation.Carried(p.id))
    restrict(RestrictionPoint.DROP, tagCheck)
    f.input(p, "drop sword")
    f.run()
    assertContains(f.out(p), "Nope.")
    assertEquals(ItemLocation.Carried(p.id), sword.location)
    assertEquals(0, p.waitState)

    // give
    f.mob()
    restrict(RestrictionPoint.GIVE, tagCheck)
    f.input(p, "give sword to goblin")
    f.run()
    assertContains(f.out(p), "Nope.")
    assertEquals(ItemLocation.Carried(p.id), sword.location)
    assertEquals(0, p.waitState)

    // put
    f.w.spawnItem(T.BAG, ItemLocation.Carried(p.id))
    restrict(RestrictionPoint.PUT, tagCheck)
    f.input(p, "put sword bag")
    f.run()
    assertContains(f.out(p), "Nope.")
    assertEquals(ItemLocation.Carried(p.id), sword.location)
    assertEquals(0, p.waitState)
  }

  @Test
  fun `wear of a new helmet into a head slot holding a tagged helmet is refused at REMOVE, and nothing moves`() {
    val tagCheck = Restriction { _, r -> if ("t" in r.item?.tags.orEmpty()) "Nope." else null }
    restrict(RestrictionPoint.REMOVE, tagCheck)
    val p = f.player("Ann")
    val oldHelm = f.w.spawnItem(HELM, ItemLocation.Carried(p.id))
    f.w.addItemTag(oldHelm, "t")
    f.w.moveItem(oldHelm, ItemLocation.Equipped(p.id, "head"))
    val newHelm = f.w.spawnItem(HELM, ItemLocation.Carried(p.id))
    f.input(p, "wear helmet")
    f.run()
    assertContains(f.out(p), "Nope.")
    assertEquals(ItemLocation.Equipped(p.id, "head"), oldHelm.location)
    assertEquals(ItemLocation.Carried(p.id), newHelm.location)
    assertEquals(0, p.waitState)
  }

  @Test
  fun `wield of a dagger while a tagged sword is wielded is refused`() {
    val tagCheck = Restriction { _, r -> if ("t" in r.item?.tags.orEmpty()) "Nope." else null }
    restrict(RestrictionPoint.REMOVE, tagCheck)
    val p = f.player("Ann")
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Carried(p.id))
    f.w.addItemTag(sword, "t")
    f.w.moveItem(sword, ItemLocation.Equipped(p.id, "wield"))
    val dagger = f.w.spawnItem(DAGGER, ItemLocation.Carried(p.id))
    f.input(p, "wield dagger")
    f.run()
    assertContains(f.out(p), "Nope.")
    assertEquals(ItemLocation.Equipped(p.id, "wield"), sword.location)
    assertEquals(ItemLocation.Carried(p.id), dagger.location)
    assertEquals(0, p.waitState)
  }

  @Test
  fun `remove restriction works through renamed command and mob act`() {
    val tagCheck = check("Nope.") { "t" in it.item?.tags.orEmpty() }
    val fRename =
      Fixture(
        rules = rules,
        scripts = host,
        protos = Prototypes(T.rooms, T.mobs, testItems, T.skills),
        rename = mapOf("remove" to "doff"),
      )
    fRename.w.restrictions = mapOf(RestrictionPoint.REMOVE to listOf(tagCheck))
    val p = fRename.player("Ann")
    val sword = fRename.w.spawnItem(T.SWORD, ItemLocation.Carried(p.id))
    fRename.w.addItemTag(sword, "t")
    fRename.w.moveItem(sword, ItemLocation.Equipped(p.id, "wield"))
    fRename.input(p, "doff sword")
    fRename.run()
    assertContains(fRename.out(p), "Nope.")
    assertEquals(ItemLocation.Equipped(p.id, "wield"), sword.location)
    assertEquals(listOf("restrict:remove:sword"), order)
    order.clear()

    // mob's f.w.act(mob, "wield dagger")
    val mob = f.mob()
    val mobSword = f.w.spawnItem(T.SWORD, ItemLocation.Carried(mob.id))
    f.w.addItemTag(mobSword, "t")
    f.w.moveItem(mobSword, ItemLocation.Equipped(mob.id, "wield"))
    val mobDagger = f.w.spawnItem(DAGGER, ItemLocation.Carried(mob.id))
    restrict(RestrictionPoint.REMOVE, tagCheck)
    f.w.act(mob, "wield dagger")
    f.run()
    assertEquals(listOf("restrict:remove:sword"), order)
    assertEquals(ItemLocation.Equipped(mob.id, "wield"), mobSword.location)
    assertEquals(ItemLocation.Carried(mob.id), mobDagger.location)
  }

  @Test
  fun `slot family wears into free slot but refuses when occupant is restricted`() {
    val tagCheck = Restriction { _, r -> if ("t" in r.item?.tags.orEmpty()) "Nope." else null }
    restrict(RestrictionPoint.REMOVE, tagCheck)
    val p = f.player("Ann")
    val ring1 = f.w.spawnItem(RING, ItemLocation.Carried(p.id))
    f.w.addItemTag(ring1, "t")
    f.w.moveItem(ring1, ItemLocation.Equipped(p.id, "finger_l"))

    val ring2 = f.w.spawnItem(RING, ItemLocation.Carried(p.id))
    f.input(p, "wear ring")
    f.run()
    assertEquals(ItemLocation.Equipped(p.id, "finger_l"), ring1.location)
    assertEquals(ItemLocation.Equipped(p.id, "finger_r"), ring2.location)

    val ring3 = f.w.spawnItem(RING, ItemLocation.Carried(p.id))
    f.input(p, "wear ring")
    f.run()
    assertContains(f.out(p), "Nope.")
    assertEquals(ItemLocation.Equipped(p.id, "finger_l"), ring1.location)
    assertEquals(ItemLocation.Equipped(p.id, "finger_r"), ring2.location)
    assertEquals(ItemLocation.Carried(p.id), ring3.location)
  }

  @Test
  fun `drop all with a tagged and an untagged item drops untagged and reports refusal`() {
    val tagCheck = Restriction { _, r -> if ("t" in r.item?.tags.orEmpty()) "Nope." else null }
    restrict(RestrictionPoint.DROP, tagCheck)
    val p = f.player("Ann")
    val tagged = f.w.spawnItem(T.SWORD, ItemLocation.Carried(p.id))
    f.w.addItemTag(tagged, "t")
    val plain = f.w.spawnItem(DAGGER, ItemLocation.Carried(p.id))
    f.input(p, "drop all")
    f.run()
    val out = f.out(p)
    assertContains(out, "Nope.")
    assertContains(out, "You drop a dagger.")
    assertEquals(ItemLocation.Carried(p.id), tagged.location)
    assertEquals(ItemLocation.InRoom(p.room.id, p.trackIndex), plain.location)
  }

  @Test
  fun `drop, give and put of an equipped item fail with not-carrying and do not call restriction`() {
    var called = false
    val check = Restriction { _, _ ->
      called = true
      "Nope."
    }
    restrict(RestrictionPoint.DROP, check)
    restrict(RestrictionPoint.GIVE, check)
    restrict(RestrictionPoint.PUT, check)

    val p = f.player("Ann")
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Carried(p.id))
    f.w.moveItem(sword, ItemLocation.Equipped(p.id, "wield"))
    f.mob()
    f.w.spawnItem(T.BAG, ItemLocation.Carried(p.id))

    f.input(p, "drop sword")
    f.run()
    assertContains(f.out(p), "You aren't carrying that.")
    assertFalse(called)

    f.input(p, "give sword to goblin")
    f.run()
    assertContains(f.out(p), "You aren't carrying that.")
    assertFalse(called)

    f.input(p, "put sword bag")
    f.run()
    assertContains(f.out(p), "You aren't carrying that.")
    assertFalse(called)
  }

  @Test
  fun `a mob drop command is refused by restriction`() {
    restrict(RestrictionPoint.DROP, check("Nope.") { "t" in it.item?.tags.orEmpty() })
    val mob = f.mob()
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Carried(mob.id))
    f.w.addItemTag(sword, "t")
    f.w.act(mob, "drop sword")
    f.run()
    assertEquals(listOf("restrict:drop:sword"), order)
    assertEquals(ItemLocation.Carried(mob.id), sword.location)
  }

  @Test
  fun `moveItem and destroy bypass item restrictions`() {
    val refuseAll = check("Nope.") { true }
    restrict(RestrictionPoint.REMOVE, refuseAll)
    restrict(RestrictionPoint.DROP, refuseAll)
    restrict(RestrictionPoint.GIVE, refuseAll)
    restrict(RestrictionPoint.PUT, refuseAll)

    val p = f.player("Ann")
    val mob = f.mob()
    val bag = f.w.spawnItem(T.BAG, ItemLocation.Carried(p.id))
    val item = f.w.spawnItem(T.SWORD, ItemLocation.Equipped(p.id, "wield"))
    f.w.addItemTag(item, "t")

    f.w.moveItem(item, ItemLocation.Carried(p.id))
    assertEquals(ItemLocation.Carried(p.id), item.location)
    f.w.moveItem(item, ItemLocation.Contained(bag.id))
    assertEquals(ItemLocation.Contained(bag.id), item.location)
    f.w.moveItem(item, ItemLocation.Carried(mob.id))
    assertEquals(ItemLocation.Carried(mob.id), item.location)
    f.w.moveItem(item, ItemLocation.InRoom(p.room.id, p.trackIndex))
    assertEquals(ItemLocation.InRoom(p.room.id, p.trackIndex), item.location)

    f.w.destroy(item)
    assertFalse(item.id in f.w.entities)
    assertEquals(emptyList(), order)
  }
}
