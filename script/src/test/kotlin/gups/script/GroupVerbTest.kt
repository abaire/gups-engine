package gups.script

import gups.core.Label
import gups.core.world.groupAdd
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertTrue

/** Room commands exercising the group verbs and queries. */
private const val GROUP_ZONE: String =
  """
  def _gwho(ctx):
      g = ctx.group_of(ctx.actor)
      ctx.send(ctx.actor, "group %s" % (g.id if g else "none"))

  def _members(ctx):
      ms = ctx.group_members(ctx.actor)
      ctx.send(ctx.actor, "members %d" % len(ms))

  def _leader(ctx):
      g = ctx.group_of(ctx.actor)
      ctx.send(ctx.actor, "leader %s" % (g.leader.name if g else "none"))

  def _kick(ctx):
      ctx.group_remove(ctx.actor)
      ctx.send(ctx.actor, "kicked")

  def _disband(ctx):
      ctx.group_disband(ctx.actor)
      ctx.send(ctx.actor, "disbanded")

  def _releader(ctx):
      ctx.group_set_leader(ctx.actor)
      ctx.send(ctx.actor, "releadered")

  def _bad_join(ctx):
      ctx.group_add(ctx.actor, "not-an-actor")
      ctx.send(ctx.actor, "joined")

  def _bad_member(ctx):
      ctx.group_add(ctx.actor, ctx.here("victim"))
      ctx.send(ctx.actor, "joined")

  room("start",
      name = "A room",
      desc = "A room.",
      flags = ["dead_end_ok"],
      commands = {
          "gwho": _gwho,
          "members": _members,
          "leader": _leader,
          "kick": _kick,
          "disband": _disband,
          "releader": _releader,
          "bad_join": _bad_join,
          "bad_member": _bad_member,
      })
  mob("victim", vitals = vitals(hp = 10))
  """

/** A lib package that registers the on_group_change global hook. */
private const val GROUP_LIB: String =
  """
  def _note(ctx):
      g = ctx.group
      ctx.send(ctx.actor, "changed %s" % ctx.event)
      ctx.send(ctx.actor, "snapshot %d" % len(g.members))

  global_hook("on_group_change", _note)
  """

class GroupVerbTest {
  private fun fixture(vararg files: Pair<String, String>): ScriptFixture =
    ScriptFixture(
      mapOf(
        "zones/z/package.star" to zonePkg(),
        "lib/package.star" to libPkg(),
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
      ) + files,
      load = listOf("zones/z", "lib"),
    )

  private fun world(files: Map<String, String>): ScriptFixture {
    val all = (mapOf("rules/combat.star" to TestWorlds.rules()) + files).entries.map { it.toPair() }
    return fixture(*all.toTypedArray())
  }

  @Test
  fun `the queries reflect a group formed from the engine`() {
    val f = world(mapOf("zones/z/a.star" to GROUP_ZONE, "lib/group.star" to GROUP_LIB))
    val ann = f.player("Ann")
    val ben = f.player("Ben")
    f.w.groupAdd(ben, ann)
    f.input(ann, "gwho")
    f.input(ann, "members")
    f.input(ann, "leader")
    f.run(3)
    val out = f.out(ann)
    assertTrue(out.contains("group 1"), out)
    assertTrue(out.contains("members 2"), out)
    assertTrue(out.contains("leader Ann"), out)
  }

  @Test
  fun `the mutating verbs run without a script error, and the hook fires`() {
    val f = world(mapOf("zones/z/a.star" to GROUP_ZONE, "lib/group.star" to GROUP_LIB))
    val ann = f.player("Ann")
    val ben = f.player("Ben")
    val cal = f.player("Cal")
    f.w.groupAdd(ben, ann)
    f.w.groupAdd(cal, ann)
    f.input(ben, "releader")
    f.run(1)
    f.input(ben, "kick")
    f.run(1)
    f.input(cal, "disband")
    f.run(1)
    f.input(ann, "gwho")
    f.run(1)
    assertTrue(
      f.diagnostics("script_error").isEmpty(),
      f.diagnostics("script_error").joinToString { it.message },
    )
    val out = f.out(ann) + f.out(ben) + f.out(cal)
    assertTrue(out.contains("releadered"), out)
    assertTrue(out.contains("kicked"), out)
    assertTrue(out.contains("disbanded"), out)
    assertTrue(out.contains("changed leader"), "the hook saw the leader change")
    assertTrue(out.contains("changed disband"), "the hook saw the disband")
    assertTrue(out.contains("group none"), "the disband left ann without a group: $out")
  }

  @Test
  fun `the hook sees the group snapshot of the event`() {
    val f = world(mapOf("zones/z/a.star" to GROUP_ZONE, "lib/group.star" to GROUP_LIB))
    val ann = f.player("Ann")
    val ben = f.player("Ben")
    f.w.groupAdd(ben, ann)
    f.run(3)
    val toAnn = f.out(ann)
    val toBen = f.out(ben)
    assertTrue(toAnn.contains("snapshot 1"), "the creator's join snapshot has one member: $toAnn")
    assertTrue(toBen.contains("snapshot 2"), "the joiner's join snapshot has two: $toBen")
  }

  @Test
  fun `a non-actor argument and a mob are script errors naming the verb`() {
    val f = world(mapOf("zones/z/a.star" to GROUP_ZONE, "lib/group.star" to GROUP_LIB))
    val ann = f.player("Ann")
    f.w.spawnMob(Label("//zones/z:victim"), ann.room)
    f.input(ann, "bad_join")
    f.run(1)
    f.input(ann, "bad_member")
    f.run(1)
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(errors.any { "group_add" in it }, errors.toString())
    assertTrue(errors.any { "must be a player" in it }, errors.toString())
  }
}
