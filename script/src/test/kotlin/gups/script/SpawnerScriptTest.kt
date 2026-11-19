package gups.script

import gups.core.AttrValue
import gups.core.Label
import gups.core.WORLD_TICK_PULSES
import gups.core.model.Entity
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.world.World
import gups.core.world.despawn
import gups.core.world.destroy
import gups.core.world.moveItem
import gups.core.world.spawnKeyOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpawnerScriptTest {
  @Test
  fun `on_place runs after on_spawn with self, copy, room and spawner`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            def item_spawn(ctx):
                ctx.set_attr(ctx.self, "order", ["spawn"])

            def item_place(ctx):
                order = list(ctx.get_attr(ctx.self, "order", []))
                order.append("place")
                ctx.set_attr(ctx.self, "order", order)
                ctx.set_attr(ctx.self, "same_item", ctx.self == ctx.item)
                ctx.set_attr(ctx.self, "room_lbl", ctx.room.label)
                ctx.set_attr(ctx.self, "sp_id", ctx.spawner.id)
                ctx.set_attr(ctx.self, "sp_label", ctx.spawner.label)
                ctx.set_attr(ctx.self, "sp_room", ctx.spawner.room)
                ctx.set_attr(ctx.self, "sp_rooms", ctx.spawner.rooms)
                ctx.set_attr(ctx.self, "eq_spawned_by", ctx.item.spawned_by == ctx.spawner)

            def mob_spawn(ctx):
                ctx.set_attr(ctx.self, "order", ["spawn"])

            def mob_place(ctx):
                order = list(ctx.get_attr(ctx.self, "order", []))
                order.append("place")
                ctx.set_attr(ctx.self, "order", order)
                ctx.set_attr(ctx.self, "same_actor", ctx.self == ctx.actor)
                ctx.set_attr(ctx.self, "room_lbl", ctx.room.label)
                ctx.set_attr(ctx.self, "eq_spawned_by", ctx.actor.spawned_by == ctx.spawner)

            item("key", name = "a key", on_spawn = item_spawn)
            mob("rat", on_spawn = mob_spawn)
            room("r", spawns = [
                spawn(":key", id = "vault_key", respawn = "never", on_place = item_place),
                spawn(":rat", respawn = "never", on_place = mob_place),
            ])
            """,
        )
      )
    val r = f.w.room(Label("//zones/z:r"))
    val key = r.items.single()
    val rat = r.actors.filterIsInstance<Mob>().single()

    val c = f.host.testCtx(f.w, r, "zones/z")
    assertEquals(listOf("spawn", "place"), f.w.getAttrList(key, "z:order"))
    assertEquals(true, f.w.getAttrBool(key, "z:same_item"))
    assertEquals("//zones/z:r", f.w.getAttrStr(key, "z:room_lbl"))
    assertEquals("//zones/z:vault_key", f.w.getAttrStr(key, "z:sp_id"))
    assertEquals("//zones/z:key", f.w.getAttrStr(key, "z:sp_label"))
    assertEquals("//zones/z:r", f.w.getAttrStr(key, "z:sp_room"))
    assertEquals(listOf("//zones/z:r"), f.w.getAttrList(key, "z:sp_rooms"))
    assertEquals(true, f.w.getAttrBool(key, "z:eq_spawned_by"))

    assertEquals(listOf("spawn", "place"), f.w.getAttrList(rat, "z:order"))
    assertEquals(true, f.w.getAttrBool(rat, "z:same_actor"))
    assertEquals("//zones/z:r", f.w.getAttrStr(rat, "z:room_lbl"))
    assertEquals(true, f.w.getAttrBool(rat, "z:eq_spawned_by"))
    assertTrue(c.toString().isNotEmpty())
  }

  @Test
  fun `held copy runs on_place when its dormant room wakes and pick sees ctx spawner`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(", reset_world_ticks = 1"),
          "zones/z/a.star" to
            """
            def pick_room(ctx):
                ctx.set_attr(ctx.room, "pick_spawner_id", ctx.spawner.id)
                ctx.set_attr(ctx.room, "pick_spawner_rooms", ctx.spawner.rooms)
                return ":vault"

            def on_key_place(ctx):
                ctx.set_attr(ctx.self, "placed_at", ctx.pulse)
                ctx.set_attr(ctx.self, "sp_room_is_none", ctx.spawner.room == None)
                ctx.set_attr(ctx.self, "sp_id", ctx.spawner.id)

            item("key", name = "a key")
            room("start", exits = [exit("north", to = ":vault")])
            room("vault", exits = [exit("south", to = ":start")])
            spawner("pool_key", ":key", rooms = [":vault"], max = 1, respawn = "reset", pick = pick_room, on_place = on_key_place)
            """,
        ),
        bubble = true,
      )
    val vault = f.w.room(Label("//zones/z:vault"))
    val firstKey = vault.items.single()
    assertEquals(0L, f.w.getAttrLong(firstKey, "z:placed_at"))
    assertEquals(true, f.w.getAttrBool(firstKey, "z:sp_room_is_none"))
    assertEquals("//zones/z:pool_key", f.w.getAttrStr(firstKey, "z:sp_id"))
    assertEquals("//zones/z:pool_key", f.w.getAttrStr(vault, "z:pick_spawner_id"))
    assertEquals(listOf("//zones/z:vault"), f.w.getAttrList(vault, "z:pick_spawner_rooms"))

    // Destroy the first key while vault is dormant; reset holds the replacement.
    f.w.destroy(firstKey)
    assertTrue(vault.isDormant)
    f.run(WORLD_TICK_PULSES)
    assertTrue(vault.items.isEmpty(), "replacement is held while vault is dormant")

    // Walking a player into start wakes vault (distance 1), placing the held copy and running
    // on_place.
    f.player("Ann", at = "//zones/z:start")
    f.run(1)
    val secondKey = vault.items.single()
    assertTrue(f.w.getAttrLong(secondKey, "z:placed_at")!! > 0L)
    assertEquals("//zones/z:pool_key", f.w.getAttrStr(secondKey, "z:sp_id"))
  }

  @Test
  fun `handles print in both forms, work as dict keys, and stay equal across reload`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            def inspect(ctx):
                key = ctx.room.items()[0]
                rat = [a for a in ctx.room.actors() if not a.is_player][0]
                h_named, h_anon = key.spawned_by, rat.spawned_by
                d = {h_named: "k", h_anon: "r"}
                ctx.set_attr(ctx.room, "repr_named", repr(h_named))
                ctx.set_attr(ctx.room, "repr_anon", repr(h_anon))
                ctx.set_attr(ctx.room, "dict_ok", d[h_named] == "k" and d[h_anon] == "r")
                ctx.set_attr(ctx.room, "player_none", ctx.actor.spawned_by == None)
                ctx.set_attr(ctx.room, "anon_id_none", h_anon.id == None)
                ctx.set_attr(ctx.room, "anon_room", h_anon.room)

            def check_eq(ctx):
                key = ctx.room.items()[0]
                rat = [a for a in ctx.room.actors() if not a.is_player][0]
                h_named, h_anon = key.spawned_by, rat.spawned_by
                ctx.set_attr(ctx.room, "named_after", repr(h_named))
                ctx.set_attr(ctx.room, "anon_after", repr(h_anon))
                ctx.set_attr(ctx.room, "info_named", ctx.spawner_info(h_named).live)
                ctx.set_attr(ctx.room, "info_anon", ctx.spawner_info(h_anon).live)

            item("key", name = "a key")
            mob("rat")
            room("r",
                 spawns = [
                     spawn(":key", id = "vault_key", respawn = "never"),
                     spawn(":rat", respawn = "never"),
                 ],
                 commands = {"inspect": inspect, "checkeq": check_eq})
            """,
        )
      )
    val p = f.player("Ann", at = "//zones/z:r")
    f.input(p, "inspect")
    f.run(1)
    val r = f.w.room(Label("//zones/z:r"))
    assertTrue(f.diagnostics("script_error").isEmpty())
    assertEquals("spawner(\"//zones/z:vault_key\")", f.w.getAttrStr(r, "z:repr_named"))
    assertEquals(
      "spawner(room=\"//zones/z:r\", label=\"//zones/z:rat\", n=0)",
      f.w.getAttrStr(r, "z:repr_anon"),
    )
    assertEquals(true, f.w.getAttrBool(r, "z:dict_ok"))
    assertEquals(true, f.w.getAttrBool(r, "z:player_none"))
    assertEquals(true, f.w.getAttrBool(r, "z:anon_id_none"))
    assertEquals("//zones/z:r", f.w.getAttrStr(r, "z:anon_room"))

    val beforeNamed = spawnerHandle(f.w, f.w.spawnKeyOf(r.items.single())!!)
    val beforeAnon = spawnerHandle(f.w, f.w.spawnKeyOf(r.actors.filterIsInstance<Mob>().single())!!)
    assertTrue(f.host.reload(f.w, listOf("zones/z")).ok)
    assertEquals(beforeNamed, spawnerHandle(f.w, f.w.spawnKeyOf(r.items.single())!!))
    assertEquals(
      beforeAnon,
      spawnerHandle(f.w, f.w.spawnKeyOf(r.actors.filterIsInstance<Mob>().single())!!),
    )

    f.input(p, "checkeq")
    f.run(1)
    assertEquals("spawner(\"//zones/z:vault_key\")", f.w.getAttrStr(r, "z:named_after"))
    assertEquals(
      "spawner(room=\"//zones/z:r\", label=\"//zones/z:rat\", n=0)",
      f.w.getAttrStr(r, "z:anon_after"),
    )
    assertEquals(1L, f.w.getAttrLong(r, "z:info_named"))
    assertEquals(1L, f.w.getAttrLong(r, "z:info_anon"))
  }

  @Test
  fun `spawner_info reports live, held, max, next_at and ran across modes including away and held`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(", reset_world_ticks = 2"),
          "zones/z/a.star" to
            """
            def probe(ctx):
                for name in ["never_key", "reset_key", "timed_key"]:
                    info = ctx.spawner_info(":" + name)
                    ctx.set_attr(ctx.room, name + "_live", info.live)
                    ctx.set_attr(ctx.room, name + "_held", info.held)
                    ctx.set_attr(ctx.room, name + "_max", info.max)
                    ctx.set_attr(ctx.room, name + "_next_at", info.next_at)
                    ctx.set_attr(ctx.room, name + "_ran", info.ran)

            item("key", name = "a key")
            item("chest", name = "a chest", container = container(capacity = 10))
            room("r",
                 spawns = [
                     spawn(":key", id = "never_key", max = 1, respawn = "never"),
                     spawn(":key", id = "reset_key", max = 1, respawn = "reset"),
                     spawn(":key", id = "timed_key", max = 1, respawn = 500, container = ":chest"),
                 ],
                 commands = {"probe": probe})
            """,
        )
      )
    val p = f.player("Ann", at = "//zones/z:r")
    val r = f.w.room(Label("//zones/z:r"))

    // Initially: never_key live=1 next_at=None ran=True; reset_key live=1 next_at=None (not short);
    // timed_key held=1 (missing chest) next_at=None (not short, held counts).
    f.input(p, "probe")
    f.run(1)
    assertEquals(1L, f.w.getAttrLong(r, "z:never_key_live"))
    assertEquals(0L, f.w.getAttrLong(r, "z:never_key_held"))
    assertEquals(1L, f.w.getAttrLong(r, "z:never_key_max"))
    assertEquals(null, f.w.getAttrLong(r, "z:never_key_next_at"))
    assertEquals(true, f.w.getAttrBool(r, "z:never_key_ran"))

    assertEquals(1L, f.w.getAttrLong(r, "z:reset_key_live"))
    assertEquals(null, f.w.getAttrLong(r, "z:reset_key_next_at"))

    assertEquals(0L, f.w.getAttrLong(r, "z:timed_key_live"))
    assertEquals(1L, f.w.getAttrLong(r, "z:timed_key_held"))
    assertEquals(null, f.w.getAttrLong(r, "z:timed_key_next_at"))

    // Destroy reset_key -> next_at becomes the package's next reset pulse.
    val resetKey =
      r.items.first { f.w.spawnKeyOf(it)?.let { k -> k.toString().contains("reset_key") } == true }
    f.w.destroy(resetKey)
    // Put never_key in Ann's inventory and log Ann out -> never_key is away, still counted in live!
    val neverKey = r.items.single()
    f.w.moveItem(neverKey, ItemLocation.Carried(p.id))
    f.input(p, "quit")
    f.run(1)

    val p2 = f.player("Bob", at = "//zones/z:r")
    f.input(p2, "probe")
    f.run(1)
    assertEquals(1L, f.w.getAttrLong(r, "z:never_key_live"), "away copy counts in live")
    assertEquals(0L, f.w.getAttrLong(r, "z:reset_key_live"))
    assertEquals((2 * WORLD_TICK_PULSES).toLong(), f.w.getAttrLong(r, "z:reset_key_next_at"))
  }

  @Test
  fun `refill brings back never key, caps at max, leaves pending deadline alone, and errors on unknown`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            def do_refill_never(ctx):
                ctx.refill(":never_key")
                info = ctx.spawner_info(":never_key")
                ctx.set_attr(ctx.room, "never_live", info.live)
                ctx.set_attr(ctx.room, "never_ran", info.ran)

            def do_refill_timed(ctx):
                before = ctx.spawner_info(":timed_rats").next_at
                ctx.refill(":timed_rats", 5)
                after = ctx.spawner_info(":timed_rats")
                ctx.set_attr(ctx.room, "before_next", before)
                ctx.set_attr(ctx.room, "after_next", after.next_at)
                ctx.set_attr(ctx.room, "after_live", after.live)

            def bad_info(ctx):
                ctx.spawner_info(":nope")

            def bad_refill(ctx):
                ctx.refill(":nope")

            item("key", name = "a key")
            mob("rat")
            room("r",
                 spawns = [
                     spawn(":key", id = "never_key", max = 1, respawn = "never"),
                     spawn(":rat", id = "timed_rats", max = 2, batch = 1, respawn = 500),
                 ],
                 commands = {"rnever": do_refill_never, "rtimed": do_refill_timed, "badinfo": bad_info, "badrefill": bad_refill})
            """,
        )
      )
    val r = f.w.room(Label("//zones/z:r"))
    val p = f.player("Ann", at = "//zones/z:r")

    f.w.destroy(r.items.single())
    f.input(p, "rnever")
    f.run(1)
    assertEquals(1L, f.w.getAttrLong(r, "z:never_live"))
    assertEquals(true, f.w.getAttrBool(r, "z:never_ran"))
    assertEquals(1, r.items.size)

    f.w.despawn(r.actors.filterIsInstance<Mob>().first())
    f.input(p, "rtimed")
    f.run(1)
    assertEquals(2L, f.w.getAttrLong(r, "z:after_live"))
    assertEquals(2, r.actors.filterIsInstance<Mob>().size)
    val beforeNext = f.w.getAttrLong(r, "z:before_next")
    assertTrue(beforeNext != null && beforeNext > 0L)
    assertEquals(beforeNext, f.w.getAttrLong(r, "z:after_next"))

    f.input(p, "badinfo")
    f.run(1)
    assertContains(
      f.diagnostics("script_error").last().message,
      "spawner_info: no spawn rule //zones/z:nope",
    )
    f.input(p, "badrefill")
    f.run(1)
    assertContains(
      f.diagnostics("script_error").last().message,
      "refill: no spawn rule //zones/z:nope",
    )
  }

  private fun World.getAttrStr(e: Entity, k: String): String? =
    (e.customAttributes[k] as? AttrValue.S)?.v

  private fun World.getAttrBool(e: Entity, k: String): Boolean? =
    (e.customAttributes[k] as? AttrValue.B)?.v

  private fun World.getAttrLong(e: Entity, k: String): Long? =
    (e.customAttributes[k] as? AttrValue.I)?.v

  private fun World.getAttrList(e: Entity, k: String): List<String>? =
    (e.customAttributes[k] as? AttrValue.L)?.v?.map { (it as AttrValue.S).v }
}
