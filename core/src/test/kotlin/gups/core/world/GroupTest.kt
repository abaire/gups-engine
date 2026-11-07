package gups.core.world

import gups.core.LINKDEAD_PULSES
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Player
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class GroupTest(private val fs: FixtureSchema) {
  init {
    FixtureSchemas.current = fs
  }

  @BeforeEach
  fun useSchema() {
    FixtureSchemas.current = fs
  }

  @AfterEach
  fun resetSchema() {
    FixtureSchemas.current = FixtureSchemas.FANTASY
  }

  /**
   * A fixture whose on_group_change handler records (actor, event, snapshot member names), then
   * runs [react], standing in for a script that changes groups from the hook.
   */
  private class GroupFixture(val react: (World, HookContext) -> Unit = { _, _ -> }) {
    val events = mutableListOf<Triple<String, String, List<String>>>()
    val fixture: Fixture =
      Fixture(
        scripts =
          RecordingHost().also { host ->
            host.handlers[HookName.ON_GROUP_CHANGE] = { w, _, ctx ->
              events +=
                Triple(
                  ctx.actor?.name ?: "",
                  ctx.event ?: "",
                  ctx.group?.members?.map { it.name } ?: emptyList(),
                )
              react(w, ctx)
              null
            }
          }
      )
  }

  @Test
  fun `creating via group_add makes the destination the leader, and a third joins via any member`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    val c = g.fixture.player("Cal")
    g.fixture.w.groupAdd(b, a)
    assertEquals(a, g.fixture.w.groupOf(a)?.leader)
    assertEquals(a, g.fixture.w.groupOf(b)?.leader)
    assertEquals(a.groupId, b.groupId)
    g.fixture.w.groupAdd(c, b)
    assertEquals(a, g.fixture.w.groupOf(c)?.leader)
    assertEquals(3, g.fixture.w.groupMembers(a).size)
  }

  @Test
  fun `a group create fires two join events, the destination first`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    g.fixture.w.groupAdd(b, a)
    assertEquals(
      listOf(Triple("Ann", "join", listOf("Ann")), Triple("Ben", "join", listOf("Ann", "Ben"))),
      g.events,
    )
  }

  @Test
  fun `adding a player already in another group fails, and in the same group is a no-op`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    val c = g.fixture.player("Cal")
    val d = g.fixture.player("Dan")
    g.fixture.w.groupAdd(b, a)
    g.fixture.w.groupAdd(c, d)
    assertFailsWith<IllegalArgumentException> { g.fixture.w.groupAdd(b, c) }
    val size = g.fixture.w.groups.getValue(a.groupId!!).members.size
    g.fixture.w.groupAdd(b, a)
    assertEquals(size, g.fixture.w.groups.getValue(a.groupId!!).members.size, "a no-op")
  }

  @Test
  fun `adding a grouped player to an ungrouped one fails and changes nothing`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    val c = g.fixture.player("Cal")
    g.fixture.w.groupAdd(b, a)
    g.events.clear()
    assertFailsWith<IllegalArgumentException> { g.fixture.w.groupAdd(b, c) }
    assertEquals(listOf(a, b), g.fixture.w.groupMembers(b))
    assertNull(c.groupId)
    assertEquals(1, g.fixture.w.groups.size)
    assertTrue(g.events.isEmpty())
  }

  @Test
  fun `a hook that disbands the group during a leave stops the stale events`() {
    var disbanded = false
    val g = GroupFixture { w, ctx ->
      if (ctx.event == "leave" && !disbanded) {
        disbanded = true
        w.groupDisband(ctx.group!!.members.first())
      }
    }
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    val c = g.fixture.player("Cal")
    g.fixture.w.groupAdd(b, a)
    g.fixture.w.groupAdd(c, a)
    g.events.clear()
    g.fixture.w.groupRemove(a)
    assertEquals(
      listOf(
        Triple("Ann", "leave", listOf("Ben", "Cal")),
        Triple("Ben", "disband", listOf("Ben", "Cal")),
      ),
      g.events,
      "no leader event for a group the hook already disbanded",
    )
    assertTrue(g.fixture.w.groups.isEmpty())
    for (p in listOf(a, b, c)) assertNull(p.groupId)
  }

  @Test
  fun `a hook that adds to the group during its creation fires each join once`() {
    var added = false
    lateinit var cal: Player
    val g = GroupFixture { w, ctx ->
      if (ctx.event == "join" && !added) {
        added = true
        w.groupAdd(cal, ctx.actor as Player)
      }
    }
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    cal = g.fixture.player("Cal")
    g.fixture.w.groupAdd(b, a)
    assertEquals(listOf(a, b, cal), g.fixture.w.groupMembers(a))
    assertEquals(1, g.events.count { it.first == "Ben" && it.second == "join" })
    assertEquals(1, g.events.count { it.first == "Cal" && it.second == "join" })
  }

  @Test
  fun `group_add of a player to itself fails`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    assertFailsWith<IllegalArgumentException> { g.fixture.w.groupAdd(a, a) }
  }

  @Test
  fun `removing a non-leader member fires leave with a snapshot without it`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    val c = g.fixture.player("Cal")
    g.fixture.w.groupAdd(b, a)
    g.fixture.w.groupAdd(c, a)
    g.events.clear()
    g.fixture.w.groupRemove(c)
    assertNull(g.fixture.w.groupOf(c))
    assertEquals(listOf(Triple("Cal", "leave", listOf("Ann", "Ben"))), g.events)
    assertEquals(a, g.fixture.w.groupOf(b)?.leader)
  }

  @Test
  fun `removing the leader fires leave then leader, naming the lowest-id remaining member`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    val c = g.fixture.player("Cal")
    g.fixture.w.groupAdd(b, a)
    g.fixture.w.groupAdd(c, a)
    g.events.clear()
    g.fixture.w.groupRemove(a)
    assertNull(g.fixture.w.groupOf(a))
    assertEquals(b, g.fixture.w.groupOf(b)?.leader, "the lowest-id remaining member leads")
    assertEquals(
      listOf(
        Triple("Ann", "leave", listOf("Ben", "Cal")),
        Triple("Ben", "leader", listOf("Ben", "Cal")),
      ),
      g.events,
    )
  }

  @Test
  fun `a two-member group disbands when its leader leaves, firing leave, leader, disband`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    g.fixture.w.groupAdd(b, a)
    g.events.clear()
    g.fixture.w.groupRemove(a)
    assertNull(g.fixture.w.groupOf(b), "the sole remaining member is disbanded")
    assertTrue(g.fixture.w.groups.isEmpty())
    assertEquals(
      listOf(
        Triple("Ann", "leave", listOf("Ben")),
        Triple("Ben", "leader", listOf("Ben")),
        Triple("Ben", "disband", listOf("Ben")),
      ),
      g.events,
    )
  }

  @Test
  fun `a two-member group disbanded by a non-leader firing leave then disband`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    g.fixture.w.groupAdd(b, a)
    g.events.clear()
    g.fixture.w.groupRemove(b)
    assertNull(g.fixture.w.groupOf(b))
    assertNull(g.fixture.w.groupOf(a), "the sole remaining member is disbanded")
    assertEquals(
      listOf(Triple("Ben", "leave", listOf("Ann")), Triple("Ann", "disband", listOf("Ann"))),
      g.events,
    )
  }

  @Test
  fun `group_remove of a member without a group is a no-op`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    g.fixture.w.groupRemove(a)
    assertNull(g.fixture.w.groupOf(a))
    assertTrue(g.events.isEmpty())
  }

  @Test
  fun `group_set_leader sets the leader and fires leader`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    val c = g.fixture.player("Cal")
    g.fixture.w.groupAdd(b, a)
    g.events.clear()
    g.fixture.w.groupSetLeader(b)
    assertEquals(b, g.fixture.w.groupOf(a)?.leader)
    assertEquals(listOf(Triple("Ben", "leader", listOf("Ben", "Ann"))), g.events)
    assertFailsWith<IllegalArgumentException> { g.fixture.w.groupSetLeader(c) }
    assertFailsWith<IllegalArgumentException> { g.fixture.w.groupSetLeader(b) }
  }

  @Test
  fun `group_disband clears everyone and fires one disband naming the caller`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    g.fixture.w.groupAdd(b, a)
    g.events.clear()
    g.fixture.w.groupDisband(b)
    assertNull(g.fixture.w.groupOf(a))
    assertNull(g.fixture.w.groupOf(b))
    assertTrue(g.fixture.w.groups.isEmpty())
    assertEquals(listOf(Triple("Ben", "disband", listOf("Ann", "Ben"))), g.events)
  }

  @Test
  fun `a quit removes the player from its group and promotes the leader`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    val c = g.fixture.player("Cal")
    g.fixture.w.groupAdd(b, a)
    g.fixture.w.groupAdd(c, a)
    g.fixture.w.quit(a)
    assertNull(g.fixture.w.groupOf(a))
    assertEquals(b, g.fixture.w.groupOf(b)?.leader, "the lowest-id remaining member leads")
  }

  @Test
  fun `a link-dead player keeps its group, and link-dead expiry removes it`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    g.fixture.w.groupAdd(b, a)
    g.fixture.w.disconnect(a.session!!)
    assertEquals(a.groupId, b.groupId, "link-dead keeps the group")
    g.fixture.run(LINKDEAD_PULSES)
    assertTrue(g.fixture.w.groups.isEmpty(), "the expiry removed the quiter and disbanded")
  }

  @Test
  fun `a player's death and respawn keep its group`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    g.fixture.w.groupAdd(b, a)
    g.fixture.w.startCombat(a, b)
    g.fixture.hurt(b, b.current(fs.health) + 1, a)
    assertTrue(b.inWorld, "the player victim respawned")
    assertEquals(a.groupId, b.groupId, "respawn keeps the group")
  }

  @Test
  fun `a pet resolves to its owner's group, and a mob with no player leader has none`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    g.fixture.w.groupAdd(b, a)
    val pet = g.fixture.w.summon(a, T.DUMMY, 1, null).first()
    assertEquals(g.fixture.w.groupOf(a), g.fixture.w.groupOf(pet), "a pet's group is its owner's")
    val wild = g.fixture.mob()
    assertNull(g.fixture.w.groupOf(wild), "a mob with no player leader has no group")
  }

  @Test
  fun `group_members lists the leader first then ascending id`() {
    val g = GroupFixture()
    val a = g.fixture.player("Ann")
    val b = g.fixture.player("Ben")
    val c = g.fixture.player("Cal")
    g.fixture.w.groupAdd(c, a)
    g.fixture.w.groupAdd(b, a)
    assertEquals(listOf(a, b, c), g.fixture.w.groupMembers(a))
  }

  @Test
  fun `a fresh world has no groups`() {
    val g = GroupFixture()
    assertTrue(g.fixture.w.groups.isEmpty())
  }

  @Test
  fun `contributorsOf fills groupId and lists an idle group member with zeros`() {
    val g = GroupFixture()
    val killer = g.fixture.player("Ann")
    val idle = g.fixture.player("Ben")
    g.fixture.w.groupAdd(idle, killer)
    val victim = g.fixture.mob(T.DUMMY)
    g.fixture.w.startCombat(killer, victim)
    g.fixture.hurt(victim, 3, killer)
    val byName = g.fixture.w.contributorsOf(victim, killer).associateBy { it.actor.name }
    assertTrue(byName.containsKey("Ben"), "an idle group member is listed")
    assertEquals(0, byName.getValue("Ben").damage)
    assertEquals(killer.groupId, byName.getValue("Ben").groupId)
    assertTrue(byName.getValue("Ben").inRoom)
  }

  @Test
  fun `contributorsOf marks a group member in another room as not in_room`() {
    val g = GroupFixture()
    val killer = g.fixture.player("Ann")
    val far = g.fixture.player("Far", at = T.YARD)
    g.fixture.w.groupAdd(far, killer)
    val victim = g.fixture.mob(T.DUMMY)
    g.fixture.w.startCombat(killer, victim)
    g.fixture.hurt(victim, 3, killer)
    val byName = g.fixture.w.contributorsOf(victim, killer).associateBy { it.actor.name }
    assertFalse(byName.getValue("Far").inRoom)
  }

  @Test
  fun `contributorsOf lists the group of a pet contributor`() {
    val g = GroupFixture()
    val owner = g.fixture.player("Ann")
    val ally = g.fixture.player("Ben")
    g.fixture.w.groupAdd(ally, owner)
    val pet = g.fixture.w.summon(owner, T.DUMMY, 1, null).first()
    val victim = g.fixture.mob(T.DUMMY)
    g.fixture.w.startCombat(owner, victim)
    g.fixture.w.startCombat(pet, victim)
    g.fixture.hurt(victim, 3, pet)
    val byName = g.fixture.w.contributorsOf(victim, owner).associateBy { it.actor.name }
    assertTrue(byName.containsKey("Ben"), "a pet's owner's group member is listed")
    assertEquals(owner.groupId, byName.getValue("Ben").groupId)
  }

  @Test
  fun `who marks each grouped player with its leader and leaves ungrouped players plain`() {
    val g = GroupFixture()
    val ann = g.fixture.player("Ann")
    val ben = g.fixture.player("Ben")
    val cal = g.fixture.player("Cal")
    g.fixture.w.groupAdd(ben, ann)
    g.fixture.input(cal, "who")
    g.fixture.run(4)
    val out = g.fixture.out(cal)
    assertContains(out, "Ann [Ann's group]")
    assertContains(out, "Ben [Ann's group]")
    assertTrue(out.lines().any { it.trim().endsWith("Cal") && "group" !in it }, out)
  }

  @Test
  fun `score names the group and its size only while grouped`() {
    val g = GroupFixture()
    val ann = g.fixture.player("Ann")
    val ben = g.fixture.player("Ben")
    g.fixture.input(ann, "score")
    g.fixture.run(4)
    val solo = g.fixture.out(ann)
    assertContains(solo, "Ann")
    assertFalse("Group:" in solo)

    g.fixture.w.groupAdd(ben, ann)
    g.fixture.input(ben, "score")
    g.fixture.run(4)
    assertContains(g.fixture.out(ben), "Group: Ann's, 2 members")

    g.fixture.w.groupRemove(ben)
    g.fixture.input(ben, "score")
    g.fixture.run(4)
    val after = g.fixture.out(ben)
    assertContains(after, "Ben")
    assertFalse("Group:" in after)
  }

  @Test
  fun `a world that empties who_group hides groups from who`() {
    val f = Fixture(messages = mapOf("who.group" to ""))
    val ann = f.player("Ann")
    val ben = f.player("Ben")
    f.w.groupAdd(ben, ann)
    f.input(ann, "who")
    f.run(4)
    val out = f.out(ann)
    assertContains(out, "Ann")
    assertContains(out, "Ben")
    assertFalse("group" in out)
  }
}
