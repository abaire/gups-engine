package gups.script

import gups.core.AttrValue
import gups.core.Label
import gups.core.model.Affect
import gups.core.rules.RulesContext
import gups.core.world.spawnItem
import gups.core.world.teleport
import gups.core.world.toHitChain
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomAffectScriptTest {
  private val rulesPkg = "rules/package.star" to TestWorlds.RULES_PACKAGE

  /**
   * A zone with `start` (tags: stone) an exit north of `next`, a rat, and the given room commands.
   */
  private fun zone(commands: String) =
    mapOf(
      rulesPkg,
      "rules/combat.star" to TestWorlds.rules("def to_hit(ctx):\n    return 50\n"),
      "zones/z/package.star" to zonePkg(),
      "zones/z/content.star" to
        """
          def _ward(ctx):
              a = ctx.apply_affect(ctx.self, "ward", 100, add_tags = ["warded"], remove_tags = ["stone"],
                  modifiers = {"to_hit": _mod}, tick_every = 1, on_tick = _tick, on_expire = _expire)
              ctx.set_attr(ctx.self, "host_same", str(a.host.id == ctx.self.id))
              ctx.set_attr(ctx.self, "tags", ",".join(sorted(ctx.self.tags)))
              ctx.set_attr(ctx.self, "exp", str(a.expires_at))
              ctx.set_attr(ctx.self, "left", str(a.remaining))

          def _remove(ctx):
              first = ctx.remove_affect(ctx.self, "ward")
              second = ctx.remove_affect(ctx.self, "ward")
              ctx.set_attr(ctx.self, "rm1", str(first))
              ctx.set_attr(ctx.self, "rm2", str(second))

          def _check(ctx):
              ctx.set_attr(ctx.self, "room_tags", ",".join(sorted(ctx.room.tags)))

          def _mod(ctx, base):
              return base + 100

          def _tick(ctx):
              ctx.set_attr(ctx.self, "tick_label", ctx.self.label)

          def _expire(ctx):
              ctx.set_attr(ctx.self, "expire_label", ctx.self.label)

          def _perma(ctx):
              a = ctx.apply_affect(ctx.self, "perma", SEMI_PERMANENT)
              ctx.set_attr(ctx.self, "perma_exp", str(a.expires_at))
              ctx.set_attr(ctx.self, "perma_left", str(a.remaining))

          def _short(ctx):
              a = ctx.apply_affect(ctx.self, "ward", 10)
              ctx.set_attr(ctx.self, "exp2", str(a.expires_at))

          def _bad_stat(ctx):
              ctx.apply_affect(ctx.self, "x", 5, stat_deltas = stats(dex = 1))

          def _bad_sense(ctx):
              ctx.apply_affect(ctx.self, "x", 5, sense_deltas = {"spell": 3})

          def _bad_vital(ctx):
              ctx.apply_affect(ctx.self, "x", 5, max_vital_deltas = vitals(hp = 5))

          def _bad_resist(ctx):
              ctx.apply_affect(ctx.self, "x", 5, resist_deltas = resists(fire = 10))

          def _bad_proc(ctx):
              ctx.apply_affect(ctx.self, "x", 5, procs = [proc(on = "hit", effect = damage("1d1"))])

          def _bad_applies(ctx):
              ctx.apply_affect(ctx.self, "x", 5, applies = "worn")

          def _bad_dur(ctx):
              ctx.apply_affect(ctx.self, "x", None)

          def _bad_add(ctx):
              ctx.apply_affect(ctx.self, "x", 5, add_tags = ["Bad"])

          $commands
          room("start", tags = ["stone"], exits = [exit("north", ":next")], commands = {
              "ward": _ward, "rm": _remove, "check": _check,
              "perma": _perma, "short": _short,
              "bad_stat": _bad_stat, "bad_sense": _bad_sense, "bad_vital": _bad_vital,
              "bad_resist": _bad_resist, "bad_proc": _bad_proc, "bad_applies": _bad_applies,
              "bad_dur": _bad_dur, "bad_add": _bad_add,
          })
          room("next")
          mob("rat", vitals = vitals(hp = 100))
          """,
    )

  @Test
  fun `ctx apply_affect on a room returns a view hosted by the room and updates its effective tags`() {
    val f = ScriptFixture(zone(""))
    val p = f.player()
    f.input(p, "ward")
    f.run()
    val room = f.w.room(Label("//zones/z:start"))
    assertEquals(AttrValue.S("True"), room.customAttributes["z:host_same"])
    assertEquals(AttrValue.S("warded"), room.customAttributes["z:tags"])
    assertEquals(AttrValue.S((f.w.pulse + 100).toString()), room.customAttributes["z:exp"])
    assertEquals(AttrValue.S("100"), room.customAttributes["z:left"])
    assertEquals(setOf("warded"), room.tagsAt(f.w.pulse))
    assertEquals(1, room.affects.size)
    assertEquals("ward", room.affects[0].name)
  }

  @Test
  fun `a to_hit modifier on a room affect changes the chain in its room and not the next`() {
    val f = ScriptFixture(zone(""))
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val start = f.w.room(Label("//zones/z:start"))
    val next = f.w.room(Label("//zones/z:next"))
    val c = { RulesContext(f.w, p, rat) }
    assertEquals(50, f.w.toHitChain(c()).value)
    f.input(p, "ward")
    f.run()
    val inStart = f.w.toHitChain(c())
    assertEquals(150, inStart.value)
    assertContains(inStart.text, "room-affect:ward")
    f.w.teleport(p, next)
    f.run()
    val inNext = f.w.toHitChain(c())
    assertEquals(50, inNext.value)
    assertTrue("room-affect" !in inNext.text, inNext.text)
    assertEquals(1, start.affects.size)
    assertTrue(next.affects.isEmpty())
  }

  @Test
  fun `a SEMI_PERMANENT room affect has no expiry and no remaining`() {
    val f = ScriptFixture(zone(""))
    val p = f.player()
    f.input(p, "perma")
    f.run()
    val room = f.w.room(Label("//zones/z:start"))
    assertEquals(AttrValue.S("None"), room.customAttributes["z:perma_exp"])
    assertEquals(AttrValue.S("None"), room.customAttributes["z:perma_left"])
    assertEquals(Long.MAX_VALUE, room.affects[0].expiresAt)
  }

  @Test
  fun `ctx remove_affect on a room returns True then False`() {
    val f = ScriptFixture(zone(""))
    val p = f.player()
    f.input(p, "ward")
    f.run()
    f.input(p, "rm")
    f.run()
    val room = f.w.room(Label("//zones/z:start"))
    assertEquals(AttrValue.S("True"), room.customAttributes["z:rm1"])
    assertEquals(AttrValue.S("False"), room.customAttributes["z:rm2"])
    assertTrue(room.affects.isEmpty())
  }

  @Test
  fun `actor-only fields on a room affect fail with exact messages`() {
    val f = ScriptFixture(zone(""))
    val p = f.player()
    for ((command, message) in
      listOf(
        "bad_stat" to "apply_affect(stat_deltas): a room's affect can't change stats",
        "bad_sense" to "apply_affect(sense_deltas): a room's affect can't change senses",
        "bad_vital" to
          "apply_affect(max_vital_deltas): a room's affect can't change maximum vitals",
        "bad_resist" to "apply_affect(resist_deltas): a room's affect can't change resists",
        "bad_proc" to "apply_affect(procs): a room's affect can't have procs",
        "bad_applies" to "apply_affect(applies): only item affects take applies",
        "bad_dur" to "apply_affect(duration): a room's affect needs a duration",
        "bad_add" to "apply_affect(add_tags): 'Bad' is not a valid tag",
      )) {
      f.input(p, command)
      f.run()
      val diagnostic = f.w.diagnostics.last()
      assertContains(diagnostic.message, message)
    }
  }

  @Test
  fun `add_tags and remove_tags on an actor or an item fail with exact messages`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            def _actor_tags(ctx):
                ctx.apply_affect(ctx.actor, "x", 5, add_tags = ["warded"])

            def _actor_rm(ctx):
                ctx.apply_affect(ctx.actor, "x", 5, remove_tags = ["stone"])

            def _item_tags(ctx):
                ctx.apply_affect(ctx.self, "x", 5, applies = "carried", add_tags = ["warded"])

            room("start", commands = {"actor_tags": _actor_tags, "actor_rm": _actor_rm})
            item("wand", commands = {"item_tags": _item_tags})
            """,
        )
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:wand"), gups.core.model.ItemLocation.Carried(p.id))
    f.input(p, "actor_tags")
    f.run()
    assertContains(
      f.w.diagnostics.last().message,
      "apply_affect(add_tags): only a room's affect can add tags",
    )
    f.input(p, "actor_rm")
    f.run()
    assertContains(
      f.w.diagnostics.last().message,
      "apply_affect(remove_tags): only a room's affect can remove tags",
    )
    f.input(p, "item_tags")
    f.run()
    assertContains(
      f.w.diagnostics.last().message,
      "apply_affect(add_tags): only a room's affect can add tags",
    )
  }

  @Test
  fun `on_tick and on_expire run with the room as self`() {
    val f = ScriptFixture(zone(""))
    val p = f.player()
    f.input(p, "ward")
    f.run()
    f.run(2)
    val room = f.w.room(Label("//zones/z:start"))
    assertEquals(AttrValue.S("//zones/z:start"), room.customAttributes["z:tick_label"])
    f.run(200)
    assertEquals(AttrValue.S("//zones/z:start"), room.customAttributes["z:expire_label"])
    assertTrue(room.affects.isEmpty())
  }

  @Test
  fun `re-applying with a shorter duration keeps the existing affect`() {
    val f = ScriptFixture(zone(""))
    val p = f.player()
    f.input(p, "ward")
    f.run()
    f.input(p, "short")
    f.run()
    val room = f.w.room(Label("//zones/z:start"))
    assertEquals(room.customAttributes["z:exp"], room.customAttributes["z:exp2"])
    assertEquals(1, room.affects.size)
  }

  @Test
  fun `room affects and their tags survive a reload`() {
    val f = ScriptFixture(zone(""))
    val p = f.player()
    f.input(p, "ward")
    f.run()
    f.write(
      "zones/z/content.star",
      """
      def _exp(ctx):
          affs = ctx.room.affects
          ctx.set_attr(ctx.self, "count", len(affs))
          ctx.set_attr(ctx.self, "name", affs[0].name if len(affs) > 0 else "")

      room("start", desc = "Reloaded", commands = {"exp": _exp})
      room("next")
      """,
    )
    val result = f.host.reload(f.w, listOf("zones/z"))
    assertTrue(result.ok, result.messages.toString())
    f.input(p, "exp")
    f.run()
    val room = f.w.room(Label("//zones/z:start"))
    assertEquals(1, room.affects.size)
    assertEquals("ward", room.affects[0].name)
    assertEquals(AttrValue.I(1), room.customAttributes["z:count"])
    assertEquals(AttrValue.S("ward"), room.customAttributes["z:name"])
  }

  @Test
  fun `a tag added directly to a room affect is visible through the room tags view`() {
    val f = ScriptFixture(zone(""))
    val p = f.player()
    val room = f.w.room(Label("//zones/z:start"))
    room.affects +=
      Affect(999L, "ward", null, f.w.pulse, f.w.pulse + 100, addTags = setOf("warded"))
    room.dirty = true
    f.input(p, "check")
    f.run()
    assertEquals(AttrValue.S("stone,warded"), room.customAttributes["z:room_tags"])
  }
}
