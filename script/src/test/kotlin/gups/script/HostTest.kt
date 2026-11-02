package gups.script

import gups.core.AttrValue
import gups.core.Label
import gups.core.model.DamageType
import gups.core.model.DoorState
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.world.damage
import gups.core.world.moveItem
import gups.core.world.spawnItem
import gups.core.world.startCombat
import gups.core.world.teleport
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HostTest {
  private fun zone(rooms: String, vararg more: Pair<String, String>) =
    mapOf("zones/z/package.star" to zonePkg(), "zones/z/rooms.star" to rooms) + more

  @Test
  fun `before hooks veto with a message and on hooks see the result`() {
    val f =
      ScriptFixture(
        zone(
          """
                def _guard(ctx):
                    if not ctx.actor.has_item(":pass"):
                        return ctx.deny("The guard steps in front of you.")

                def _arrive(ctx):
                    ctx.send(ctx.actor, "Welcome to " + ctx.room.name + ", " + ctx.actor.name + ".")

                room("start", exits = [exit("north", ":hall")], before_exit = _guard)
                room("hall", exits = [exit("south", ":start")], on_enter = _arrive)
                item("pass")
                """
        )
      )
    val p = f.player()
    f.input(p, "north")
    f.run()
    assertEquals("The guard steps in front of you.\n", f.out(p))
    assertEquals(0, p.waitState)
    assertEquals("//zones/z:start", p.room.label.v)

    f.w.spawnItem(Label("//zones/z:pass"), gups.core.model.ItemLocation.Carried(p.id))
    f.input(p, "north")
    f.run()
    assertEquals("//zones/z:hall", p.room.label.v)
    assertContains(f.out(p), "Welcome to hall, Ann.")
  }

  @Test
  fun `a crashing hook allows the action and leaves a full diagnostic`() {
    val f =
      ScriptFixture(
        zone(
          """
                def _guard(ctx):
                    x = {}
                    return x["missing"]

                room("start", exits = [exit("north", ":hall")], before_exit = _guard)
                room("hall")
                """
        )
      )
    val p = f.player()
    f.input(p, "north")
    f.run()
    assertEquals("//zones/z:hall", p.room.label.v)
    val d = f.diagnostics("script_error").single()
    assertEquals("zones/z", d.pkg)
    assertEquals(Label("//zones/z:start"), d.label)
    assertEquals("before_exit", d.hook)
    assertEquals("//zones/z:rooms.star", d.file)
    assertEquals(3, d.line)
    assertContains(d.message, "missing")
    assertContains(d.stack!!, "_guard")
    assertContains(d.ctx!!, "actor=Ann")
    assertEquals(1L, d.seed)
    assertEquals("", f.out(p).substringBefore("hall"))
  }

  @Test
  fun `an endless hook hits the step limit and the action proceeds`() {
    val f =
      ScriptFixture(
        zone(
          """
                def _spin(ctx):
                    for i in range(1000000000):
                        pass

                room("start", exits = [exit("north", ":hall")], before_exit = _spin)
                room("hall")
                """
        )
      )
    val p = f.player()
    f.input(p, "north")
    f.run()
    assertEquals("//zones/z:hall", p.room.label.v)
    assertContains(f.diagnostics("step_limit").single().message, "50000-step limit")
  }

  @Test
  fun `nested hooks share the top-level event's step budget`() {
    val f =
      ScriptFixture(
        zone(
          """
                room("start")

                def _burn(n):
                    for i in range(n):
                        pass

                def _a_hurt(ctx):
                    if ctx.target != ctx.self:
                        return
                    _burn(15000)
                    ctx.damage(ctx.find(":b")[0], 1, DAMAGE.fire, "hp")

                def _b_hurt(ctx):
                    if ctx.target != ctx.self:
                        return
                    _burn(15000)

                mob("a", vitals = vitals(hp = 100), on_damage = _a_hurt)
                mob("b", vitals = vitals(hp = 100), on_damage = _b_hurt)
                """
        )
      )
    f.mob("//zones/z:a")
    val b = f.mob("//zones/z:b")
    // Each hook alone fits easily; together they stay under the budget.
    f.w.damage(f.w.actors.first { it.name == "a" }, mapOf("hp" to 1), DamageType("fire"))
    assertEquals(99, b.current("hp"))
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())

    val tight =
      ScriptFixture(
        zone(f.root.resolve("zones/z/rooms.star").toFile().readText()),
        limits = ScriptLimits.HARNESS.copy(hookSteps = 25_000),
      )
    tight.mob("//zones/z:a")
    val b2 = tight.mob("//zones/z:b")
    tight.w.damage(tight.w.actors.first { it.name == "a" }, mapOf("hp" to 1), DamageType("fire"))
    assertEquals(99, b2.current("hp"), "the damage verb applied before the nested hook ran out")
    val limited = tight.diagnostics("step_limit")
    assertEquals(listOf("on_damage"), limited.map { it.hook }.distinct())
    assertTrue(limited.any { it.label == Label("//zones/z:b") }, limited.toString())
  }

  @Test
  fun `more than 20 errors in 60 seconds quarantine the package`() {
    val f =
      ScriptFixture(
        zone(
          """
                def _pull(ctx):
                    fail("the lever is broken")

                def _tick(ctx):
                    ctx.echo(ctx.self, "Tick.")

                def _arm(ctx):
                    ctx.after(30, _tick)

                room("start", commands = {"pull": _pull}, on_load = _arm)
                """
        )
      )
    val p = f.player()
    repeat(21) {
      f.input(p, "pull")
      f.run()
    }
    assertTrue("zones/z" in f.host.quarantined)
    assertEquals(21, f.diagnostics("script_error").size)
    assertNotNull(f.diagnostics("quarantine").single())
    assertEquals(21, f.out(p).lines().count { it == "Nothing happens." })

    // Quarantined timers are kept, not fired or dropped.
    f.run(15)
    assertEquals(1, f.w.room(Label("//zones/z:start")).timers.size)
    assertFalse("Tick." in f.out(p))
    f.input(p, "pull")
    f.run()
    assertEquals("Nothing happens.\n", f.out(p))
    assertEquals(21, f.diagnostics("script_error").size, "quarantined commands don't run")

    f.host.unquarantine("zones/z")
    f.run()
    assertEquals("Tick.\n", f.out(p))
  }

  @Test
  fun `attributes are namespaced by package`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
                    load("//lib:rep.star", "add_rep")

                    def _count(ctx):
                        n = ctx.get_attr(ctx.actor, "visits", 0)
                        ctx.set_attr(ctx.actor, "visits", n + 1)
                        ctx.send(ctx.actor, "visits=%d rep=%d" % (ctx.actor.attrs["visits"], ctx.get_attr(ctx.actor, "faction:rep", 0)))

                    def _rep(ctx):
                        add_rep(ctx, ctx.actor, 5)

                    def _cheat(ctx):
                        ctx.set_attr(ctx.actor, "faction:rep", 999)

                    def _other(ctx):
                        ctx.set_attr(ctx.actor, "lib:x", 1)

                    room("start", commands = {"count": _count, "rep": _rep, "cheat": _cheat, "other": _other})
                """,
          "lib/package.star" to libPkg(", shared_namespaces = [\"faction\"]"),
          "lib/rep.star" to
            """
                    def add_rep(ctx, actor, n):
                        ctx.set_attr(actor, "faction:rep", ctx.get_attr(actor, "faction:rep", 0) + n)
                """,
        )
      )
    val p = f.player()
    for (line in listOf("count", "count", "rep", "count")) {
      f.input(p, line)
      f.run()
    }
    assertEquals("visits=1 rep=0\nvisits=2 rep=0\nvisits=3 rep=5\n", f.out(p))
    assertEquals(AttrValue.I(3), p.customAttributes["z:visits"])
    assertEquals(AttrValue.I(5), p.customAttributes["faction:rep"])

    f.input(p, "cheat")
    f.run()
    f.input(p, "other")
    f.run()
    val errors = f.diagnostics("script_error").map { it.message }
    assertContains(
      errors[0],
      "can only write its own namespace 'z', not 'faction' (use the helpers exported by //lib)",
    )
    assertContains(errors[1], "not 'lib'")
    assertEquals(AttrValue.I(5), p.customAttributes["faction:rep"])
  }

  @Test
  fun `storage and allocation limits`() {
    val f =
      ScriptFixture(
        zone(
          """
                def _big(ctx):
                    ctx.set_attr(ctx.self, "blob", "x" * 70000)

                def _float(ctx):
                    ctx.set_attr(ctx.self, "f", 1.5)

                def _shout(ctx):
                    ctx.send(ctx.actor, "x" * 2000000)

                room("start", commands = {"big": _big, "float": _float, "yell": _shout})
                """
        )
      )
    val p = f.player()
    for (c in listOf("big", "float", "yell")) {
      f.input(p, c)
      f.run()
    }
    val d = f.diagnostics()
    assertEquals(listOf("alloc_limit", "script_error", "alloc_limit"), d.map { it.kind })
    assertContains(d[0].message, "over the 64 KB limit")
    assertContains(d[1].message, "floats can't be stored")
    assertContains(d[2].message, "over the 1 MB limit")
    assertTrue(f.w.room(Label("//zones/z:start")).customAttributes.isEmpty())
  }

  @Test
  fun `deny only works in before hooks and views are read-only`() {
    val f =
      ScriptFixture(
        zone(
          """
                def _enter(ctx):
                    return ctx.deny("no")

                def _poke(ctx):
                    ctx.actor.hp = 1

                room("start", on_enter = _enter, commands = {"poke": _poke}, exits = [exit("north", ":start")])
                """
        )
      )
    val p = f.player()
    f.input(p, "poke")
    f.run()
    f.input(p, "north")
    f.run()
    val d = f.diagnostics("script_error").map { it.message }
    assertEquals(50, p.current("hp"))
    assertContains(d[0], "hp")
    assertContains(d[1], "deny() is only valid as the return value of a before_* hook")
  }

  @Test
  fun `timers call top-level functions with their keyword arguments`() {
    val f =
      ScriptFixture(
        zone(
          """
                def _ring(ctx, times, word):
                    ctx.echo(ctx.self, (word + " ") * times + "at %d" % ctx.pulse)

                def _bell(ctx):
                    ctx.after(3, _ring, times = 2, word = "Dong")
                    ctx.send(ctx.actor, "You pull the rope.")

                def _bad(ctx):
                    ctx.after(3, _ring, times = ctx.actor)

                room("start", commands = {"pull": _bell, "bad": _bad})
                """
        )
      )
    val p = f.player()
    f.input(p, "pull")
    f.run(4)
    assertEquals("You pull the rope.\nDong Dong at 4\n", f.out(p))
    f.input(p, "bad")
    f.run()
    assertContains(f.diagnostics("script_error").single().message, "can't be stored")
  }

  @Test
  fun `zone commands, global commands, think and resolve`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
                    def _read(ctx):
                        ctx.send(ctx.actor, "The sign says: " + ctx.args)

                    def _think(ctx):
                        if ctx.self.hp < ctx.self.max_hp:
                            return ctx.act(ctx.self, "say Ouch!")
                        return "idle"

                    def _zap(ctx):
                        ctx.damage(ctx.target, 7, DAMAGE.fire, "hp", source = ctx.actor)
                        ctx.emote(ctx.actor, "crackles.")

                    room("start", exits = [exit("east", "//zones/z:start")])
                    item("sign", commands = {"read": _read})
                    mob("parrot", vitals = vitals(hp = 20), think = _think, think_every = 2, skills = {":zap": 50})
                    skill("zap", range = "far", resolve = _zap, lag = 2)
                """,
          "lib/package.star" to libPkg(),
          "lib/cmds.star" to
            """
                    def _dance(ctx):
                        ctx.emote(ctx.actor, "dances a jig.")

                    global_command("dance", _dance)
                """,
        ),
        load = listOf("zones/z", "lib"),
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:sign"), gups.core.model.ItemLocation.InRoom(p.room.id, 0))
    f.input(p, "read welcome")
    f.run()
    assertEquals("The sign says: welcome\n", f.out(p))
    f.input(p, "dance")
    assertEquals("Ann dances a jig.\n", f.out(p))

    val parrot = f.mob("//zones/z:parrot")
    f.run(4)
    assertEquals("", f.out(p), "think returned idle")
    p.skills[Label("//zones/z:zap")] = 50
    f.input(p, "cast zap parrot")
    f.run()
    assertEquals(13, parrot.current("hp"))
    assertContains(f.out(p), "Ann crackles.")
    f.run(3)
    assertContains(f.out(p), "Parrot says, 'Ouch!'")
  }

  @Test
  fun `item, door and query verbs`() {
    val f =
      ScriptFixture(
        zone(
          """
                def _go(ctx):
                    a = ctx.actor
                    ctx.give(a, ":coin", count = 5)
                    coins = a.inventory()[0]
                    part = ctx.move_item(coins, ctx.room, index = 0, count = 2)
                    gem = ctx.spawn(":gem", ctx.room)
                    ctx.destroy(gem)
                    ctx.give(a, part, count = 1)
                    ctx.destroy(coins, count = 1)
                    ctx.unlock_door(ctx.room, "north")
                    ctx.open_door("//zones/z:start", "north")
                    path = ctx.path_to(":vault")
                    ctx.send(a, "coins=%d part=%d ground=%d path=%s door=%s near=%d" % (
                        coins.count, part.count, len(ctx.room.items()), path,
                        ctx.room.exit("north").door.state, len(ctx.allies_near(a, "far"))))
                    ctx.teleport(a, ":vault")

                def _bad_move0(ctx):
                    ctx.move_item(ctx.actor.inventory()[0], ctx.room, index = 0, count = 0)

                def _bad_movebig(ctx):
                    coins = ctx.actor.inventory()[0]
                    ctx.move_item(coins, ctx.room, index = 0, count = coins.count + 1)

                def _bad_give0(ctx):
                    ctx.give(ctx.actor, ctx.actor.inventory()[0], count = 0)

                def _bad_givebig(ctx):
                    coins = ctx.actor.inventory()[0]
                    ctx.give(ctx.actor, coins, count = coins.count + 1)

                def _bad_destroy0(ctx):
                    ctx.destroy(ctx.actor.inventory()[0], count = 0)

                def _bad_destroybig(ctx):
                    coins = ctx.actor.inventory()[0]
                    ctx.destroy(coins, count = coins.count + 1)

                def _arrive(ctx):
                    ctx.send(ctx.actor, "In the vault, from " + ctx.from_room.name)

                room("start", name = "Start",
                     commands = {"go": _go, "bad_move0": _bad_move0, "bad_movebig": _bad_movebig,
                                 "bad_give0": _bad_give0, "bad_givebig": _bad_givebig,
                                 "bad_destroy0": _bad_destroy0, "bad_destroybig": _bad_destroybig},
                     exits = [exit("north", ":vault", door = door(state = "locked"))])
                room("vault", exits = [exit("south", ":start", door = door(state = "locked"))], on_enter = _arrive)
                item("coin", stackable = True)
                item("gem")
                """
        )
      )
    val p = f.player()
    f.input(p, "go")
    f.run()
    assertEquals(
      "coins=2 part=1 ground=1 path=[\"north\"] door=open near=0\nIn the vault, from Start\n",
      f.out(p)
        .lines()
        .filter { it.startsWith("coins") || it.startsWith("In the") }
        .joinToString("\n", postfix = "\n"),
    )
    assertEquals("//zones/z:vault", p.room.label.v)
    assertEquals(
      DoorState.OPEN,
      f.w.room(Label("//zones/z:vault")).exits.values.single().door!!.state,
    )
    f.w.teleport(p, f.w.room(Label("//zones/z:start")))
    for ((command, verb) in
      listOf(
        "bad_move0" to "move_item",
        "bad_movebig" to "move_item",
        "bad_give0" to "give",
        "bad_givebig" to "give",
        "bad_destroy0" to "destroy",
        "bad_destroybig" to "destroy",
      )) {
      val before = f.diagnostics("script_error").size
      f.input(p, command)
      f.run()
      val errors = f.diagnostics("script_error")
      assertEquals(before + 1, errors.size, "$command should raise one script error")
      assertContains(errors.last().message, "$verb(count)")
    }
  }

  @Test
  fun `container verbs open, close, lock and unlock`() {
    val f =
      ScriptFixture(
        zone(
          """
                def _go(ctx):
                    chest = ctx.spawn(":chest", ctx.room)
                    seen = [chest.container_state]
                    ctx.unlock_container(chest)
                    seen.append(chest.container_state)
                    ctx.open_container(chest)
                    seen.append(chest.container_state)
                    ctx.lock_container(chest)
                    seen.append(chest.container_state)
                    ctx.close_container(chest)
                    seen.append(chest.container_state)
                    ctx.open_container(chest)
                    seen.append(chest.container_state)
                    ctx.close_container(chest)
                    seen.append(chest.container_state)
                    ctx.send(ctx.actor, "states=" + ",".join(seen))

                def _lock_bag(ctx):
                    ctx.lock_container(ctx.spawn(":bag", ctx.room))

                def _lock_gem(ctx):
                    ctx.lock_container(ctx.spawn(":gem", ctx.room))

                room("start", commands = {"go": _go, "lock_bag": _lock_bag, "lock_gem": _lock_gem})
                item("key")
                item("chest", container = container(state = "locked", key = ":key"))
                item("bag", container = container())
                item("gem")
                """
        )
      )
    val p = f.player()
    f.input(p, "go")
    f.run()
    assertContains(f.out(p), "states=locked,closed,open,locked,locked,open,closed")
    for ((command, why) in
      listOf("lock_bag" to "has no lock", "lock_gem" to "is not a container")) {
      val before = f.diagnostics("script_error").size
      f.input(p, command)
      f.run()
      val errors = f.diagnostics("script_error")
      assertEquals(before + 1, errors.size, "$command should raise one script error")
      assertContains(errors.last().message, why)
    }
  }

  @Test
  fun `mob verbs refuse players and on_load, on_reset fire`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(", reset_world_ticks = 2"),
          "zones/z/rooms.star" to
            """
                    def _loaded(ctx):
                        ctx.set_attr(ctx.self, "loaded", True)

                    def _reset(ctx):
                        ctx.set_attr(ctx.self, "resets", ctx.get_attr(ctx.self, "resets", 0) + 1)

                    def _make(ctx):
                        ctx.act(ctx.actor, "say hi")

                    room("start", on_load = _loaded, on_reset = _reset, commands = {"make": _make},
                         spawns = [spawn(":rat")])
                    mob("rat")
                """,
        )
      )
    val p = f.player()
    val start = f.w.room(Label("//zones/z:start"))
    assertEquals(AttrValue.B(true), start.customAttributes["z:loaded"])
    f.input(p, "make")
    f.run()
    assertContains(f.diagnostics("script_error").single().message, "mob actions apply only to mobs")
    val rat = f.w.actors.filterIsInstance<Mob>().single()
    f.w.damage(rat, mapOf("hp" to 100), DamageType("fire"))
    assertTrue(f.w.actors.none { it is Mob })
    f.run(240)
    assertEquals(AttrValue.I(1), start.customAttributes["z:resets"])
    assertEquals(1, f.w.actors.count { it is Mob }, "the reset refilled the spawn")
  }

  @Test
  fun `module globals are frozen once a package loads`() {
    val f =
      ScriptFixture(
        zone(
          "SEEN = []\n\ndef _x(ctx):\n    SEEN.append(ctx.actor.name)\n\nroom(\"start\", commands = {\"x\": _x})"
        )
      )
    val p = f.player()
    f.input(p, "x")
    f.run()
    assertContains(f.diagnostics("script_error").single().message, "frozen")
  }

  @Test
  fun `declarations can't be called from hooks`() {
    val f =
      ScriptFixture(
        zone("def _x(ctx):\n    room(\"later\")\nroom(\"start\", commands = {\"x\": _x})")
      )
    val p = f.player()
    f.input(p, "x")
    f.run()
    assertContains(
      f.diagnostics("script_error").single().message,
      "room() can only be called while a package loads",
    )
    assertEquals("Nothing happens.\n", f.out(p))
  }

  @Test
  fun `an equipped item's on_combat_tick sees its wearer's opponents`() {
    val f =
      ScriptFixture(
        zone(
          """
                def _hum(ctx):
                    for t in ctx.targets:
                        ctx.send(ctx.actor, ctx.self.name + " hums at " + t.name + ".")

                room("start")
                mob("rat", name = "a rat", vitals = vitals(hp = 1000))
                item("blade", name = "a blade", kind = "melee", wear = "wield", dice = "1d2", on_combat_tick = _hum)
                """
        )
      )
    val p = f.player()
    val rat = f.mob("//zones/z:rat")
    val blade = f.w.spawnItem(Label("//zones/z:blade"), ItemLocation.Carried(p.id))
    f.w.moveItem(blade, ItemLocation.Equipped(p.id, "wield"))
    f.w.startCombat(p, rat)
    f.run(8)
    assertContains(f.out(p), "a blade hums at a rat.")
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }
}
