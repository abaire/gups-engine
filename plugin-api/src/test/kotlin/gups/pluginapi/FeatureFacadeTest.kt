package gups.pluginapi

import gups.core.Label
import gups.core.world.Feature
import gups.core.world.Features
import gups.pluginapi.host.ActorFacade
import gups.pluginapi.host.RoomFacade
import gups.pluginapi.host.WorldFacade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureFacadeTest {
  private val zone =
    mapOf(
      "zones/z/package.star" to "package(api_version = 1, kind = \"zone\", title = \"Z\")",
      "zones/z/rooms.star" to "room(\"a\", name = \"Glade\")",
    )

  private val noLevel = Features(Feature.entries.toSet() - Feature.LEVELING)
  private val noTrack = Features(Feature.entries.toSet() - Feature.TRACK)

  @Test
  fun `PluginWorld features lists the enabled ids`() {
    val all = WorldFacade(world(), "p") {}
    assertEquals(setOf("combat", "track", "skills", "leveling"), all.features)
    val some = WorldFacade(world(zone, features = noLevel), "p") {}
    assertEquals(setOf("combat", "track", "skills"), some.features)
  }

  @Test
  fun `PluginActor level throws without leveling, naming the feature`() {
    val w = world(zone, features = noLevel)
    val ann = ActorFacade(w.spawnPlayer("Ann", Label("//zones/z:a")), w)
    val e = assertFailsWith<IllegalStateException> { ann.level }
    assertEquals("level needs the leveling feature", e.message)
    val on = world(zone)
    assertEquals(1, ActorFacade(on.spawnPlayer("Ann", Label("//zones/z:a")), on).level)
  }

  @Test
  fun `PluginRoom size throws without the track`() {
    val w = world(zone, features = noTrack)
    val room = RoomFacade(w.room(Label("//zones/z:a")), w)
    assertEquals(
      "size needs the track feature",
      assertFailsWith<IllegalStateException> { room.size }.message,
    )
  }
}
