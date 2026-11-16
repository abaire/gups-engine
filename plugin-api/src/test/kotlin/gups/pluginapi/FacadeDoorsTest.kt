package gups.pluginapi

import gups.core.world.World
import gups.pluginapi.host.WorldFacade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FacadeDoorsTest {
  private val testWorld: World = world(files = DOOR_FILES)
  private val facade = WorldFacade(testWorld, "weather") {}

  private val roomA: PluginRoom
    get() = facade.room("//zones/z:a")!!

  private val roomB: PluginRoom
    get() = facade.room("//zones/z:b")!!

  private fun doorState(room: PluginRoom, dir: String): String? = room.exit(dir)?.door?.state

  private fun spawn(label: String): PluginItem =
    facade.spawnItem("//zones/z:$label", roomA).single()

  @Test
  fun `lockDoor locks both sides of the passage`() {
    facade.lockDoor(roomA, "north")
    assertEquals("locked", doorState(roomB, "south"))
    assertEquals("locked", doorState(roomA, "north"))
  }

  @Test
  fun `unlockDoor leaves the door closed`() {
    facade.lockDoor(roomA, "north")
    facade.unlockDoor(roomA, "north")
    assertEquals("closed", doorState(roomA, "north"))
  }

  @Test
  fun `openDoor opens a locked door`() {
    facade.lockDoor(roomA, "north")
    facade.openDoor(roomA, "north")
    assertEquals("open", doorState(roomA, "north"))
  }

  @Test
  fun `closeDoor keeps a locked door locked`() {
    facade.lockDoor(roomA, "north")
    facade.closeDoor(roomA, "north")
    assertEquals("locked", doorState(roomA, "north"))
  }

  @Test
  fun `lockDoor fails on a door without a key`() {
    val e = assertFailsWith<IllegalArgumentException> { facade.lockDoor(roomA, "east") }
    assertTrue(e.message!!.contains("has no lock"), e.message)
  }

  @Test
  fun `a direction with no door fails`() {
    val e = assertFailsWith<IllegalArgumentException> { facade.openDoor(roomA, "up") }
    assertTrue(e.message!!.contains("has no door"), e.message)
  }

  @Test
  fun `exit is null for a direction with no exit`() {
    assertNull(roomA.exit("up"))
  }

  @Test
  fun `exit reads the door the way the view does`() {
    val ex = roomA.exit("north")!!
    val door = ex.door!!
    assertEquals("north", ex.direction)
    assertEquals("//zones/z:b", ex.to)
    assertEquals("//zones/z:b", ex.originalTo)
    assertEquals(emptyList(), ex.candidates)
    assertEquals(emptyMap(), ex.weights)
    assertEquals("closed", door.state)
    assertEquals("//zones/z:key", ex.key)
    assertEquals(emptyList(), ex.tags)
    assertEquals(emptyList(), door.tags)
    assertEquals(false, ex.passable)
    assertEquals(false, ex.oneWay)
  }

  @Test
  fun `exit exposes per-crossing candidates and weights`() {
    val w =
      world(
        files =
          mapOf(
            "zones/z/package.star" to "package(api_version = 1, kind = \"zone\")\n",
            "zones/z/rooms.star" to
              """
              room("start", exits = [exit("north", to = {":a": 3, ":b": 1})])
              room("a")
              room("b")
              """,
          )
      )
    val f = WorldFacade(w, "weather") {}
    val start = f.room("//zones/z:start")!!
    val ex = start.exit("north")!!
    assertNull(ex.to)
    assertNull(ex.originalTo)
    assertEquals(listOf("//zones/z:a", "//zones/z:b"), ex.candidates)
    assertEquals(mapOf("//zones/z:a" to 3, "//zones/z:b" to 1), ex.weights)
    assertTrue(ex.oneWay)
  }

  @Test
  fun `a denying before_open does not stop openDoor`() {
    facade.openDoor(roomA, "north")
    assertEquals("open", doorState(roomA, "north"))
    assertNull(facade.getAttr(roomA, "z:open_attempts"))
  }

  @Test
  fun `container verbs move the state as ctx does`() {
    val chest = spawn("chest")
    assertEquals("closed", chest.containerState)
    facade.openContainer(chest)
    assertEquals("open", chest.containerState)
    facade.closeContainer(chest)
    assertEquals("closed", chest.containerState)
    facade.lockContainer(chest)
    assertEquals("locked", chest.containerState)
    facade.unlockContainer(chest)
    assertEquals("closed", chest.containerState)
  }

  @Test
  fun `lockContainer fails on a container without a key`() {
    val box = spawn("box")
    val e = assertFailsWith<IllegalArgumentException> { facade.lockContainer(box) }
    assertTrue(e.message!!.contains("has no lock"), e.message)
  }

  @Test
  fun `containerState is null and opening fails for a non-container`() {
    val stick = spawn("stick")
    assertNull(stick.containerState)
    val e = assertFailsWith<IllegalArgumentException> { facade.openContainer(stick) }
    assertTrue(e.message!!.contains("is not a container"), e.message)
  }

  @Test
  fun `setExitTo retargets an ordinary exit, restores on null, and rejects invalid exits`() {
    val w =
      world(
        files =
          mapOf(
            "zones/z/package.star" to "package(api_version = 1, kind = \"zone\")\n",
            "zones/z/rooms.star" to
              """
              room("start", exits = [
                  exit("down", ":grotto", one_way = True),
                  exit("north", ":vault", door = door(state = "closed"), one_way = True),
                  exit("west", [":grotto", ":cave"]),
              ])
              room("grotto")
              room("cave")
              room("vault")
              """,
          )
      )
    val f = WorldFacade(w, "weather") {}
    val start = f.room("//zones/z:start")!!

    f.setExitTo(start, "down", "//zones/z:cave")
    assertEquals("//zones/z:cave", start.exit("down")!!.to)
    assertEquals("//zones/z:grotto", start.exit("down")!!.originalTo)

    f.setExitTo(start, "down", null)
    assertEquals("//zones/z:grotto", start.exit("down")!!.to)

    val doorErr =
      assertFailsWith<IllegalArgumentException> { f.setExitTo(start, "north", "//zones/z:cave") }
    assertTrue(doorErr.message!!.contains("has a door"), doorErr.message)

    val crossErr =
      assertFailsWith<IllegalArgumentException> { f.setExitTo(start, "west", "//zones/z:cave") }
    assertTrue(crossErr.message!!.contains("picked per crossing"), crossErr.message)

    val unknownErr =
      assertFailsWith<IllegalArgumentException> { f.setExitTo(start, "down", "//zones/z:nope") }
    assertTrue(unknownErr.message!!.contains("unknown room //zones/z:nope"), unknownErr.message)
  }

  companion object {
    val DOOR_FILES =
      ZONE +
        mapOf(
          "zones/z/rooms.star" to
            """
            def _deny_open(ctx):
                ctx.set_attr(ctx.self, "open_attempts", ctx.get_attr(ctx.self, "open_attempts", 0) + 1)
                return ctx.deny("Sealed.")

            room("a", name = "Glade", desc = "A glade.", exits = [exit(DIR.north, ":b", door = door(state = DOOR.closed, key = ":key")), exit(DIR.east, ":c", door = door(state = DOOR.closed))], before_open = _deny_open)
            room("b", name = "Cave", desc = "A cave.", exits = [exit("south", ":a", door = door(state = DOOR.closed, key = ":key"))])
            room("c", name = "Meadow", desc = "A meadow.", exits = [exit("west", ":a", door = door(state = DOOR.closed))])
            """,
          "zones/z/items.star" to
            """
            item("stick", name = "a stick", desc = "A stick.", keywords = ["stick"])
            item("key", name = "a key", keywords = ["key"])
            item("chest", name = "a chest", keywords = ["chest"], container = container(state = DOOR.closed, key = ":key"))
            item("box", name = "a box", keywords = ["box"], container = container())
            """,
        )
  }
}
