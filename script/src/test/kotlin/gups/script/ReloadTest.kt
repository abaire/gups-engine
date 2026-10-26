package gups.script

import gups.core.Label
import gups.core.Role
import gups.core.WorldControlEvent
import gups.core.model.DoorState
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.world.TOMBSTONE
import gups.core.world.handle
import gups.core.world.spawnItem
import kotlin.io.path.deleteExisting
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReloadTest {
  private val start =
    """
        def _hello(ctx):
            ctx.send(ctx.actor, "Hello, old.")

        room("start", name = "Old Start", commands = {"hello": _hello},
             exits = [exit("north", ":hall", door = door(state = "closed"))], spawns = [spawn(":rat")])
        room("hall", exits = [exit("south", ":start", door = door(state = "closed"))])
        mob("rat", hp = 10, level = 1)
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
    rat.hp = 4
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
        .replace("hp = 10, level = 1", "hp = 30, level = 5")
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
    assertEquals(30, rat.maxHp, "max hp followed the prototype: it was never changed in play")
    assertEquals(4, rat.hp, "current hp is live state")
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
}
