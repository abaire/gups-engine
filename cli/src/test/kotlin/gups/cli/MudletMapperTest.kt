package gups.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.JsePlatform

class MudletMapperTest {

  private fun newEnv(): Globals {
    val globals = JsePlatform.standardGlobals()
    loadResource(globals, "gups/mudlet/fake_mudlet.lua")
    loadResource(globals, "gups/mudlet/mapper.lua")
    return globals
  }

  private fun loadResource(globals: Globals, path: String) {
    val stream =
      checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
        "Missing test resource: $path"
      }
    val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    globals.load(text, path).call()
  }

  private class RoomBuilder {
    var area: String = "//zones/a"
    var areaName: String = "Zone A"
    var exits: Map<String, Any?> = emptyMap()
    var doors: Map<String, String> = emptyMap()
    var regions: List<String> = emptyList()
  }

  private fun roomInfo(
    num: Int,
    label: String,
    name: String,
    configure: RoomBuilder.() -> Unit = {},
  ): LuaTable {
    val opts = RoomBuilder().apply(configure)
    val t = LuaTable()
    t.set("num", num)
    t.set("label", label)
    t.set("name", name)
    t.set("area", opts.area)
    t.set("area_name", opts.areaName)

    val exitsTable = LuaTable()
    for ((dir, dest) in opts.exits) {
      exitsTable.set(
        dir,
        when (dest) {
          null -> LuaValue.FALSE
          is Int -> LuaValue.valueOf(dest)
          else -> error("Unsupported exit target: $dest")
        },
      )
    }
    t.set("exits", exitsTable)

    val doorsTable = LuaTable()
    for ((dir, state) in opts.doors) {
      doorsTable.set(dir, state)
    }
    t.set("doors", doorsTable)

    val regionsTable = LuaTable()
    opts.regions.forEachIndexed { idx, r -> regionsTable.set(idx + 1, r) }
    t.set("regions", regionsTable)

    return t
  }

  private fun sendRoomInfo(globals: Globals, info: LuaTable) {
    globals.get("gmcp").get("Room").set("Info", info)
    globals.get("raiseEvent").call(LuaValue.valueOf("gmcp.Room.Info"))
  }

  private fun room(globals: Globals, id: Int): LuaValue = globals.get("fake").get("rooms").get(id)

  private fun area(globals: Globals, id: Int): LuaValue = globals.get("fake").get("areas").get(id)

  @Test
  fun `first room is placed at origin in new area and centered`() {
    val g = newEnv()
    sendRoomInfo(
      g,
      roomInfo(1, "//zones/a:start", "Start") {
        area = "//zones/a"
        areaName = "Starting Area"
      },
    )

    val r = room(g, 1)
    assertFalse(r.isnil())
    assertEquals("Start", r.get("name").tojstring())
    assertEquals("//zones/a:start", r.get("data").get("gups_label").tojstring())
    assertEquals(0, r.get("x").toint())
    assertEquals(0, r.get("y").toint())
    assertEquals(0, r.get("z").toint())

    val areaId = r.get("area").toint()
    val a = area(g, areaId)
    assertEquals("Starting Area", a.get("name").tojstring())
    assertEquals("//zones/a", a.get("data").get("gups_area").tojstring())
    assertEquals(1, g.get("fake").get("centered").toint())
  }

  @Test
  fun `walking north then east places rooms at 0,1,0 and 1,1,0`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("n" to 2) })
    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2") { exits = mapOf("s" to 1, "e" to 3) })
    sendRoomInfo(g, roomInfo(3, "//zones/a:r3", "R3") { exits = mapOf("w" to 2) })

    assertEquals(0, room(g, 1).get("x").toint())
    assertEquals(0, room(g, 1).get("y").toint())
    assertEquals(0, room(g, 1).get("z").toint())

    assertEquals(0, room(g, 2).get("x").toint())
    assertEquals(1, room(g, 2).get("y").toint())
    assertEquals(0, room(g, 2).get("z").toint())

    assertEquals(1, room(g, 3).get("x").toint())
    assertEquals(1, room(g, 3).get("y").toint())
    assertEquals(0, room(g, 3).get("z").toint())
  }

  @Test
  fun `back-link links previous room exit and clears stub without revisit`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("n" to 2) })
    assertTrue(room(g, 1).get("stubs").get("n").toboolean())
    assertTrue(room(g, 1).get("exits").get("n").isnil())

    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2") { exits = mapOf("s" to 1) })
    assertFalse(room(g, 1).get("stubs").get("n").toboolean())
    assertEquals(2, room(g, 1).get("exits").get("n").toint())

    // Same for custom exit -> special exit
    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2") { exits = mapOf("s" to 1, "portal" to 3) })
    assertTrue(room(g, 2).get("special").get("portal").isnil())

    sendRoomInfo(g, roomInfo(3, "//zones/a:r3", "R3"))
    assertEquals(3, room(g, 2).get("special").get("portal").toint())
  }

  @Test
  fun `unmapped standard exit gets stub then links when revisited`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("e" to 3) })
    assertTrue(room(g, 1).get("stubs").get("e").toboolean())
    assertTrue(room(g, 1).get("exits").get("e").isnil())

    // Visit 2 and 3 via teleport so 1->3 is not back-linked
    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2"))
    sendRoomInfo(g, roomInfo(3, "//zones/a:r3", "R3"))
    assertTrue(room(g, 1).get("stubs").get("e").toboolean())

    // Revisit 1: now 3 exists, so stub is cleared and exit is linked
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("e" to 3) })
    assertFalse(room(g, 1).get("stubs").get("e").toboolean())
    assertEquals(3, room(g, 1).get("exits").get("e").toint())
  }

  @Test
  fun `custom exit to mapped room becomes special exit and is removed when gone`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2"))
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("portal" to 2) })
    assertEquals(2, room(g, 1).get("special").get("portal").toint())

    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = emptyMap() })
    assertTrue(room(g, 1).get("special").get("portal").isnil())
  }

  @Test
  fun `doors track closed open and removed states`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2"))
    sendRoomInfo(
      g,
      roomInfo(1, "//zones/a:r1", "R1") {
        exits = mapOf("n" to 2)
        doors = mapOf("n" to "closed")
      },
    )
    assertEquals(2, room(g, 1).get("doors").get("n").toint())

    sendRoomInfo(
      g,
      roomInfo(1, "//zones/a:r1", "R1") {
        exits = mapOf("n" to 2)
        doors = mapOf("n" to "open")
      },
    )
    assertEquals(1, room(g, 1).get("doors").get("n").toint())

    sendRoomInfo(
      g,
      roomInfo(1, "//zones/a:r1", "R1") {
        exits = mapOf("n" to 2)
        doors = emptyMap()
      },
    )
    assertTrue(room(g, 1).get("doors").get("n").isnil())
  }

  @Test
  fun `a custom exit's door is cleared when the exit goes away`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2"))
    sendRoomInfo(
      g,
      roomInfo(1, "//zones/a:r1", "R1") {
        exits = mapOf("portal" to 2)
        doors = mapOf("portal" to "closed")
      },
    )
    assertEquals(2, room(g, 1).get("doors").get("portal").toint())

    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = emptyMap() })
    assertTrue(room(g, 1).get("special").get("portal").isnil())
    assertTrue(room(g, 1).get("doors").get("portal").isnil())
  }

  @Test
  fun `door on u never calls setDoor and all setDoor dirs are valid`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2"))
    sendRoomInfo(
      g,
      roomInfo(1, "//zones/a:r1", "R1") {
        exits = mapOf("u" to 2, "n" to 2, "portal" to 2)
        doors = mapOf("u" to "closed", "n" to "locked", "portal" to "open")
      },
    )

    assertTrue(room(g, 1).get("doors").get("u").isnil())
    assertEquals(3, room(g, 1).get("doors").get("n").toint())
    assertEquals(1, room(g, 1).get("doors").get("portal").toint())

    val compass = setOf("n", "ne", "e", "se", "s", "sw", "w", "nw")
    val doorCalls = g.get("fake").get("doorCalls")
    val len = doorCalls.length()
    assertTrue(len > 0)
    for (i in 1..len) {
      val call = doorCalls.get(i)
      val id = call.get("id").toint()
      val dir = call.get("dir").tojstring()
      val isSpecial = !room(g, id).get("special").get(dir).isnil()
      assertTrue(dir in compass || isSpecial, "setDoor called with invalid dir '$dir' for room $id")
    }
  }

  @Test
  fun `arriving through custom exit places new room at first free spot`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("portal" to 2) })
    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2"))

    assertEquals(-1, room(g, 2).get("x").toint())
    assertEquals(1, room(g, 2).get("y").toint())
    assertEquals(0, room(g, 2).get("z").toint())
  }

  @Test
  fun `arriving with no linking exit places new room at first free spot`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1"))
    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2"))

    assertEquals(-1, room(g, 2).get("x").toint())
    assertEquals(1, room(g, 2).get("y").toint())
    assertEquals(0, room(g, 2).get("z").toint())
  }

  @Test
  fun `entering different area starts at that area origin`() {
    val g = newEnv()
    sendRoomInfo(
      g,
      roomInfo(1, "//zones/a:r1", "R1") {
        area = "//zones/a"
        areaName = "Area A"
        exits = mapOf("n" to 2)
      },
    )
    sendRoomInfo(
      g,
      roomInfo(2, "//zones/b:r2", "R2") {
        area = "//zones/b"
        areaName = "Area B"
      },
    )

    assertEquals(0, room(g, 2).get("x").toint())
    assertEquals(0, room(g, 2).get("y").toint())
    assertEquals(0, room(g, 2).get("z").toint())
  }

  @Test
  fun `revisiting room never moves it`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("n" to 2) })
    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2") { exits = mapOf("e" to 3) })
    sendRoomInfo(g, roomInfo(3, "//zones/a:r3", "R3") { exits = mapOf("w" to 1) })
    // Revisit 1 from 3 via west: 1 must stay at (0,0,0), not move to (0,1,0)
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("n" to 2) })

    assertEquals(0, room(g, 1).get("x").toint())
    assertEquals(0, room(g, 1).get("y").toint())
    assertEquals(0, room(g, 1).get("z").toint())
  }

  @Test
  fun `overlap allows non-closing loop without moving existing room`() {
    val g = newEnv()
    // 1 at (0,0,0) -> n -> 2 at (0,1,0) -> s -> 3 at (0,0,0) overlapping 1
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("n" to 2) })
    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2") { exits = mapOf("s" to 3) })
    sendRoomInfo(g, roomInfo(3, "//zones/a:r3", "R3"))

    assertEquals(0, room(g, 1).get("x").toint())
    assertEquals(0, room(g, 1).get("y").toint())
    assertEquals(0, room(g, 3).get("x").toint())
    assertEquals(0, room(g, 3).get("y").toint())
  }

  @Test
  fun `duplicate area titles disambiguate and changed area_name renames`() {
    val g = newEnv()
    sendRoomInfo(
      g,
      roomInfo(1, "//zones/a:r1", "R1") {
        area = "//zones/a"
        areaName = "Caves"
      },
    )
    sendRoomInfo(
      g,
      roomInfo(2, "//zones/b:r2", "R2") {
        area = "//zones/b"
        areaName = "Caves"
      },
    )

    val areaA = room(g, 1).get("area").toint()
    val areaB = room(g, 2).get("area").toint()
    assertEquals("Caves", area(g, areaA).get("name").tojstring())
    assertEquals("Caves (//zones/b)", area(g, areaB).get("name").tojstring())

    sendRoomInfo(
      g,
      roomInfo(2, "//zones/b:r2", "R2") {
        area = "//zones/b"
        areaName = "Deep Caves"
      },
    )
    assertEquals("Deep Caves", area(g, areaB).get("name").tojstring())
  }

  @Test
  fun `an area whose disambiguated name is taken too is marked as a collision`() {
    val g = newEnv()
    for (taken in listOf("Caves", "Caves (//zones/b)", "Caves (//zones/b) {Collision 1}")) {
      g.get("addAreaName").call(LuaValue.valueOf(taken))
    }
    sendRoomInfo(
      g,
      roomInfo(1, "//zones/b:r1", "R1") {
        area = "//zones/b"
        areaName = "Caves"
      },
    )
    val id = room(g, 1).get("area").toint()
    assertEquals("Caves (//zones/b) {Collision 2}", area(g, id).get("name").tojstring())
    assertEquals("//zones/b", area(g, id).get("data").get("gups_area").tojstring())

    // Revisiting keeps the name rather than counting up again.
    sendRoomInfo(
      g,
      roomInfo(1, "//zones/b:r1", "R1") {
        area = "//zones/b"
        areaName = "Caves"
      },
    )
    assertEquals("Caves (//zones/b) {Collision 2}", area(g, id).get("name").tojstring())
  }

  @Test
  fun `label change replaces room at same num and deletes old room at other num`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(10, "//zones/a:old", "Old Room"))
    // Different label at same num 10 -> replaces it (at origin since no move)
    sendRoomInfo(g, roomInfo(10, "//zones/a:new", "New Room"))
    assertEquals("New Room", room(g, 10).get("name").tojstring())
    assertEquals("//zones/a:new", room(g, 10).get("data").get("gups_label").tojstring())

    // Same label //zones/a:new now arrives with num 20 -> old room 10 is deleted
    sendRoomInfo(g, roomInfo(20, "//zones/a:new", "New Room Moved"))
    assertTrue(room(g, 10).isnil())
    assertFalse(room(g, 20).isnil())
    assertEquals("New Room Moved", room(g, 20).get("name").tojstring())
  }

  @Test
  fun `null destination gives stub and no error`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("n" to null) })

    assertTrue(room(g, 1).get("stubs").get("n").toboolean())
    assertTrue(room(g, 1).get("exits").get("n").isnil())
  }

  @Test
  fun `loading script twice leaves single handler`() {
    val g = newEnv()
    loadResource(g, "gups/mudlet/mapper.lua")

    var count = 0
    val handlers = g.get("fake").get("handlers")
    var key = LuaValue.NIL
    while (true) {
      val next = handlers.next(key)
      key = next.arg1()
      if (key.isnil()) break
      if (next.arg(2).get("event").tojstring() == "gmcp.Room.Info") {
        count++
      }
    }
    assertEquals(1, count)

    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1"))
    assertEquals("R1", room(g, 1).get("name").tojstring())
  }

  private fun startWalk(globals: Globals, dirs: List<String>, path: List<Int>) {
    val dirsTable = LuaTable()
    dirs.forEachIndexed { idx, d -> dirsTable.set(idx + 1, d) }
    val pathTable = LuaTable()
    path.forEachIndexed { idx, p -> pathTable.set(idx + 1, LuaValue.valueOf(p)) }
    globals.set("speedWalkDir", dirsTable)
    globals.set("speedWalkPath", pathTable)
    globals.get("doSpeedWalk").call()
  }

  private fun sentCommands(globals: Globals): List<String> {
    val sent = globals.get("fake").get("sent")
    return (1..sent.length()).map { sent.get(it).tojstring() }
  }

  private fun echoedLines(globals: Globals): List<String> {
    val echoed = globals.get("fake").get("echoed")
    return (1..echoed.length()).map { echoed.get(it).tojstring() }
  }

  @Test
  fun `three-step speedwalk sends one command per Room_Info and stops after last room`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("n" to 2) })
    startWalk(g, listOf("n", "e", "s"), listOf(2, 3, 4))

    assertEquals(listOf("n"), sentCommands(g))
    assertFalse(g.get("gupsMapper").get("walk").isnil())

    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2") { exits = mapOf("e" to 3) })
    assertEquals(listOf("n", "e"), sentCommands(g))

    sendRoomInfo(g, roomInfo(3, "//zones/a:r3", "R3") { exits = mapOf("s" to 4) })
    assertEquals(listOf("n", "e", "s"), sentCommands(g))

    sendRoomInfo(g, roomInfo(4, "//zones/a:r4", "R4"))
    assertEquals(listOf("n", "e", "s"), sentCommands(g))
    assertTrue(g.get("gupsMapper").get("walk").isnil())

    sendRoomInfo(g, roomInfo(5, "//zones/a:r5", "R5"))
    assertEquals(listOf("n", "e", "s"), sentCommands(g))
    assertTrue(echoedLines(g).isEmpty())
  }

  @Test
  fun `a resent Room_Info for the room the player is still in leaves the walk running`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("n" to 2) })
    startWalk(g, listOf("n", "e"), listOf(2, 3))

    sendRoomInfo(
      g,
      roomInfo(1, "//zones/a:r1", "R1") {
        exits = mapOf("n" to 2)
        doors = mapOf("n" to "open")
      },
    )
    assertTrue(echoedLines(g).isEmpty())
    assertEquals(listOf("n"), sentCommands(g))

    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2") { exits = mapOf("e" to 3) })
    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2") { exits = mapOf("e" to 3, "s" to 1) })
    assertEquals(listOf("n", "e"), sentCommands(g))
    sendRoomInfo(g, roomInfo(3, "//zones/a:r3", "R3"))
    assertTrue(echoedLines(g).isEmpty())
    assertTrue(g.get("gupsMapper").get("walk").isnil())
  }

  @Test
  fun `unexpected Room_Info mid-walk echoes Walk interrupted and stops`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("n" to 2) })
    startWalk(g, listOf("n", "e"), listOf(2, 3))
    assertEquals(listOf("n"), sentCommands(g))

    sendRoomInfo(g, roomInfo(99, "//zones/a:other", "Other"))
    assertEquals(listOf("Walk interrupted.\n"), echoedLines(g))
    assertEquals(listOf("n"), sentCommands(g))
    assertTrue(g.get("gupsMapper").get("walk").isnil())

    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2"))
    assertEquals(listOf("n"), sentCommands(g))
  }

  @Test
  fun `blocked step sends nothing more and following player command ends walk quietly`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("n" to 2) })
    startWalk(g, listOf("n", "e"), listOf(2, 3))
    assertEquals(listOf("n"), sentCommands(g))

    g.get("raiseEvent").call(LuaValue.valueOf("sysDataSendRequest"), LuaValue.valueOf("open door"))
    assertTrue(echoedLines(g).isEmpty())
    assertTrue(g.get("gupsMapper").get("walk").isnil())

    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2"))
    assertEquals(listOf("n"), sentCommands(g))
  }

  @Test
  fun `player command mid-walk stops it quietly and next Room_Info sends nothing`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("n" to 2) })
    startWalk(g, listOf("n", "e", "s"), listOf(2, 3, 4))
    assertEquals(listOf("n"), sentCommands(g))

    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2") { exits = mapOf("e" to 3) })
    assertEquals(listOf("n", "e"), sentCommands(g))

    g.get("raiseEvent").call(LuaValue.valueOf("sysDataSendRequest"), LuaValue.valueOf("look"))
    assertTrue(echoedLines(g).isEmpty())
    assertTrue(g.get("gupsMapper").get("walk").isnil())

    sendRoomInfo(g, roomInfo(3, "//zones/a:r3", "R3"))
    assertEquals(listOf("n", "e"), sentCommands(g))
  }

  @Test
  fun `script own send does not cancel walk despite sysDataSendRequest`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("n" to 2) })
    startWalk(g, listOf("n", "e"), listOf(2, 3))

    assertEquals(listOf("n"), sentCommands(g))
    assertFalse(g.get("gupsMapper").get("walk").isnil())
    assertFalse(g.get("gupsMapper").get("sending").toboolean())
  }

  @Test
  fun `special-exit step is sent as typed`() {
    val g = newEnv()
    sendRoomInfo(g, roomInfo(1, "//zones/a:r1", "R1") { exits = mapOf("portal" to 2) })
    startWalk(g, listOf("portal", "n"), listOf(2, 3))
    assertEquals(listOf("portal"), sentCommands(g))

    sendRoomInfo(g, roomInfo(2, "//zones/a:r2", "R2") { exits = mapOf("n" to 3) })
    assertEquals(listOf("portal", "n"), sentCommands(g))

    sendRoomInfo(g, roomInfo(3, "//zones/a:r3", "R3"))
    assertEquals(listOf("portal", "n"), sentCommands(g))
    assertTrue(g.get("gupsMapper").get("walk").isnil())
  }

  @Test
  fun `empty or nil speedWalkDir does nothing`() {
    val g = newEnv()
    startWalk(g, emptyList(), emptyList())
    assertTrue(sentCommands(g).isEmpty())
    assertTrue(echoedLines(g).isEmpty())
    assertTrue(g.get("gupsMapper").get("walk").isnil())

    g.set("speedWalkDir", LuaValue.NIL)
    g.set("speedWalkPath", LuaValue.NIL)
    g.get("doSpeedWalk").call()
    assertTrue(sentCommands(g).isEmpty())
    assertTrue(echoedLines(g).isEmpty())
    assertTrue(g.get("gupsMapper").get("walk").isnil())
  }

  @Test
  fun `lua error in script propagates as LuaError`() {
    val g = JsePlatform.standardGlobals()
    assertFailsWith<LuaError> { g.load("error('boom')", "bad.lua").call() }
  }
}
