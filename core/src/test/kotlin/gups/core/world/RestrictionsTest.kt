package gups.core.world

import gups.core.Role
import gups.core.model.ItemLocation
import gups.core.model.ModifierPoint
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import gups.core.model.RestrictionRequest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestrictionsTest {
  private val host = RecordingHost()
  private val rules = FixedRules(flee = 100)
  private val f =
    Fixture(
      rules = rules,
      scripts = host,
      protos =
        Prototypes(
          listOf(T.rooms[0].copy(modifiers = mapOf(ModifierPoint.CAST_ALLOWED to mod("allowed")))) +
            T.rooms.drop(1),
          T.mobs,
          T.items,
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
}
