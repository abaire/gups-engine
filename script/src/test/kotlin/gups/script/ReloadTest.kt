package gups.script

import gups.core.Label
import gups.core.Role
import gups.core.WORLD_TICK_PULSES
import gups.core.WorldControlEvent
import gups.core.model.DoorState
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.world.SpawnKey
import gups.core.world.TOMBSTONE
import gups.core.world.changeVital
import gups.core.world.despawn
import gups.core.world.handle
import gups.core.world.savedSpawners
import gups.core.world.spawnItem
import gups.core.world.spawnKeyOf
import kotlin.io.path.deleteExisting
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReloadTest {
  private val start =
    """
        def _hello(ctx):
            ctx.send(ctx.actor, "Hello, old.")

        room("start", name = "Old Start", commands = {"hello": _hello},
             exits = [exit("north", ":hall", door = door(state = "closed"))], spawns = [spawn(":rat")])
        room("hall", exits = [exit("south", ":start", door = door(state = "closed"))])
        mob("rat", vitals = vitals(hp = 10), level = 1)
        item("gem")
        item("pebble")
    """

  private fun fixture() =
    ScriptFixture(mapOf("zones/z/package.star" to zonePkg(), "zones/z/rooms.star" to start))

  private fun reload(f: ScriptFixture, vararg pkgs: String) =
    f.w.handle(WorldControlEvent.Reload(pkgs.toList()))

  @Test
  fun `a reload swaps code and data and keeps live state`() {
    val f = fixture()
    val p = f.player()
    p.role = Role.BUILDER
    val rat = f.w.actors.filterIsInstance<Mob>().single()
    f.w.changeVital(rat, "hp", 4 - rat.current("hp"))
    f.w.spawnItem(Label("//zones/z:gem"), ItemLocation.Carried(p.id))
    f.w.spawnItem(Label("//zones/z:pebble"), ItemLocation.InRoom(p.room.id, 0))
    // Opened in play: stays open even though the prototype still says closed.
    f.input(p, "open north")
    f.run()
    f.out(p)
    f.write(
      "zones/z/rooms.star",
      start
        .replace("Hello, old.", "Hello, new.")
        .replace("Old Start", "New Start")
        .replace("vitals = vitals(hp = 10), level = 1", "vitals = vitals(hp = 30), level = 5")
        .replace("item(\"gem\")\n", "")
        .replace("item(\"pebble\")\n", ""),
    )
    reload(f, "zones/z")
    val out = f.out(p)
    assertContains(out, "Reloaded //zones/z.")
    assertContains(out, "became a tombstone")
    assertContains(out, "despawned: its prototype is gone")
    assertEquals("New Start", p.room.name)
    assertEquals(DoorState.OPEN, p.room.exits.values.single().door!!.state)
    assertEquals(30, rat.max("hp"), "max hp followed the prototype: it was never changed in play")
    assertEquals(4, rat.current("hp"), "current hp is live state")
    assertEquals(5, rat.level)
    assertEquals(TOMBSTONE, p.inventory.single().kind)
    assertTrue(p.room.items.isEmpty())
    f.input(p, "hello")
    f.run()
    assertEquals("Hello, new.\n", f.out(p).substringBefore("<"))
    f.input(p, "drop gem")
    f.run()
    assertContains(f.out(p), "You drop gem.")
  }

  @Test
  fun `a failed reload keeps the old version and tells builders where`() {
    val f = fixture()
    val p = f.player()
    p.role = Role.BUILDER
    val bystander = f.player("Bob")
    f.write("zones/z/rooms.star", start.replace("name = \"Old Start\",", "name = \"Broken\""))
    reload(f, "zones/z")
    val out = f.out(p)
    assertContains(out, "Reload of //zones/z failed; the old version stays live.")
    assertContains(out, "//zones/z:rooms.star:4")
    assertContains(out, "^")
    assertEquals("", f.out(bystander))
    assertEquals("Old Start", p.room.name)
    assertEquals("reload", f.diagnostics().last().kind)
  }

  @Test
  fun `deleted rooms send players home and dangling timers are dropped`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
                    def _later(ctx):
                        pass

                    def _arm(ctx):
                        ctx.after(50, _later)

                    room("start", exits = [exit("north", ":attic")], commands = {"arm": _arm})
                    room("attic", exits = [exit("south", ":start")])
                """,
        )
      )
    val p = f.player()
    f.input(p, "arm")
    f.run()
    f.input(p, "north")
    f.run()
    assertEquals("//zones/z:attic", p.room.label.v)
    f.write("zones/z/rooms.star", "room(\"start\")\nroom(\"cellar\")")
    reload(f, "zones/z")
    assertEquals("//zones/z:start", p.room.label.v)
    assertContains(f.out(p), "The world shifts around you.")
    assertTrue(f.w.room(Label("//zones/z:start")).timers.isEmpty())
    assertTrue(f.diagnostics("reload").any { "dropped" in it.message })
    assertTrue(f.w.roomOrNull(Label("//zones/z:cellar")) != null)
  }

  @Test
  fun `reload clears quarantine and a deleted package is removed`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to "room(\"start\", exits = [exit(\"east\", \"//zones/y:shed\")])",
          "zones/y/package.star" to zonePkg(),
          "zones/y/y.star" to "room(\"shed\", exits = [exit(\"west\", \"//zones/z:start\")])",
        )
      )
    f.w
    f.host.quarantine(f.w, "zones/z", "by test")
    reload(f, "zones/z")
    assertFalse("zones/z" in f.host.quarantined)
    // Removing //zones/y breaks //zones/z's exit, so that reload is refused.
    f.root.resolve("zones/y/package.star").deleteExisting()
    reload(f, "zones/y")
    assertTrue(f.w.roomOrNull(Label("//zones/y:shed")) != null)
    assertContains(f.diagnostics("reload").last().message, "//zones/y:shed doesn't resolve")
  }

  @Test
  fun `lowering max keeps live copies and arms only when below the new cap`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
            mob("rat")
            room("start", spawns = [spawn(":rat", max = 3, respawn = 120)])
            """,
        )
      )
    val initialRats = f.w.actors.filterIsInstance<Mob>()
    assertEquals(3, initialRats.size)
    assertNull(f.w.savedSpawners("zones/z").single().at)

    f.write(
      "zones/z/rooms.star",
      """
      mob("rat")
      room("start", spawns = [spawn(":rat", max = 1, respawn = 120)])
      """,
    )
    reload(f, "zones/z")

    assertEquals(3, f.w.actors.filterIsInstance<Mob>().size)
    assertNull(f.w.savedSpawners("zones/z").single().at)
    f.run(WORLD_TICK_PULSES * 2)
    assertEquals(3, f.w.actors.filterIsInstance<Mob>().size)

    f.w.despawn(initialRats[0])
    f.w.despawn(initialRats[1])
    assertNull(f.w.savedSpawners("zones/z").single().at)
    f.run(WORLD_TICK_PULSES * 2)
    assertEquals(1, f.w.actors.filterIsInstance<Mob>().size)

    f.w.despawn(initialRats[2])
    assertNotNull(f.w.savedSpawners("zones/z").single().at)
    f.run(WORLD_TICK_PULSES * 2)
    assertEquals(1, f.w.actors.filterIsInstance<Mob>().size)
  }

  @Test
  fun `changing respawn keeps a pending deadline and uses the new interval next`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
            mob("rat")
            room("start", spawns = [spawn(":rat", max = 1, respawn = 100)])
            """,
        )
      )
    val firstRat = f.w.actors.filterIsInstance<Mob>().single()
    val t0 = f.w.pulse
    f.w.despawn(firstRat)
    val oldDeadline = t0 + 100
    assertEquals(oldDeadline, f.w.savedSpawners("zones/z").single().at)

    f.write(
      "zones/z/rooms.star",
      """
      mob("rat")
      room("start", spawns = [spawn(":rat", max = 1, respawn = 5000)])
      """,
    )
    reload(f, "zones/z")

    assertEquals(oldDeadline, f.w.savedSpawners("zones/z").single().at)
    f.run(WORLD_TICK_PULSES)
    val secondRat = f.w.actors.filterIsInstance<Mob>().single()
    val t1 = f.w.pulse
    f.w.despawn(secondRat)
    assertEquals(t1 + 5000, f.w.savedSpawners("zones/z").single().at)
  }

  @Test
  fun `removing a rule leaves its mobs unbound and makes no refill`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
            mob("rat")
            room("start", spawns = [spawn(":rat", max = 2, respawn = 120)])
            """,
        )
      )
    val rats = f.w.actors.filterIsInstance<Mob>()
    assertEquals(2, rats.size)
    assertTrue(rats.all { f.w.spawnKeyOf(it) != null })

    f.write(
      "zones/z/rooms.star",
      """
      mob("rat")
      room("start")
      """,
    )
    reload(f, "zones/z")

    assertEquals(2, f.w.actors.filterIsInstance<Mob>().size)
    assertTrue(rats.all { f.w.spawnKeyOf(it) == null })
    assertTrue(f.w.savedSpawners("zones/z").isEmpty())

    rats.forEach(f.w::despawn)
    f.run(WORLD_TICK_PULSES * 2)
    assertTrue(f.w.actors.filterIsInstance<Mob>().isEmpty())
  }

  @Test
  fun `adding id to an unnamed rule creates a new rule and releases old copies`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
            mob("rat")
            room("start", spawns = [spawn(":rat", max = 1, respawn = 120)])
            """,
        )
      )
    val oldRat = f.w.actors.filterIsInstance<Mob>().single()
    assertEquals(
      SpawnKey.InRoom(Label("//zones/z:start"), Label("//zones/z:rat"), 0),
      f.w.spawnKeyOf(oldRat),
    )

    f.write(
      "zones/z/rooms.star",
      """
      mob("rat")
      room("start", spawns = [spawn(":rat", id = "x", max = 1, respawn = 120)])
      """,
    )
    reload(f, "zones/z")

    assertNull(f.w.spawnKeyOf(oldRat))
    val rats = f.w.actors.filterIsInstance<Mob>()
    assertEquals(2, rats.size)
    val newRat = rats.single { it.id != oldRat.id }
    assertEquals(SpawnKey.Named(Label("//zones/z:x")), f.w.spawnKeyOf(newRat))
  }

  @Test
  fun `reordering two rules with different labels in a room carries both`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
            mob("rat")
            mob("bat")
            room("start", spawns = [spawn(":rat", max = 1, respawn = 120), spawn(":bat", max = 1, respawn = 120)])
            """,
        )
      )
    val rat = f.w.actors.filterIsInstance<Mob>().single { it.protoLabel.name == "rat" }
    val bat = f.w.actors.filterIsInstance<Mob>().single { it.protoLabel.name == "bat" }

    f.write(
      "zones/z/rooms.star",
      """
      mob("rat")
      mob("bat")
      room("start", spawns = [spawn(":bat", max = 1, respawn = 120), spawn(":rat", max = 1, respawn = 120)])
      """,
    )
    reload(f, "zones/z")

    assertEquals(2, f.w.actors.filterIsInstance<Mob>().size)
    assertEquals(
      SpawnKey.InRoom(Label("//zones/z:start"), Label("//zones/z:rat"), 0),
      f.w.spawnKeyOf(rat),
    )
    assertEquals(
      SpawnKey.InRoom(Label("//zones/z:start"), Label("//zones/z:bat"), 0),
      f.w.spawnKeyOf(bat),
    )
  }

  @Test
  fun `a held copy carries over and one whose room was deleted is dropped and arms timer`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to
            """
            item("gem")
            item("chest", container = container(capacity = 5))
            room("start")
            room("attic", name = "Old Attic")
            spawner("stash", ":gem", rooms = [":attic"], max = 1, respawn = 120, container = ":chest")
            """,
        )
      )
    val initial = f.w.savedSpawners("zones/z").single()
    assertEquals(1, initial.held.size)
    assertEquals(Label("//zones/z:attic"), initial.held.single().room)
    assertNull(initial.at)

    f.write(
      "zones/z/rooms.star",
      """
      item("gem")
      item("chest", container = container(capacity = 5))
      room("start")
      room("attic", name = "New Attic")
      spawner("stash", ":gem", rooms = [":attic"], max = 1, respawn = 120, container = ":chest")
      """,
    )
    reload(f, "zones/z")

    val carried = f.w.savedSpawners("zones/z").single()
    assertEquals(1, carried.held.size)
    assertEquals(Label("//zones/z:attic"), carried.held.single().room)
    assertNull(carried.at)

    f.write(
      "zones/z/rooms.star",
      """
      item("gem")
      item("chest", container = container(capacity = 5))
      room("start")
      spawner("stash", ":gem", rooms = [":start"], max = 1, respawn = 120, container = ":chest")
      """,
    )
    val t = f.w.pulse
    reload(f, "zones/z")

    val afterDelete = f.w.savedSpawners("zones/z").single()
    assertTrue(afterDelete.held.isEmpty())
    assertEquals(t + 120, afterDelete.at)
  }
}
