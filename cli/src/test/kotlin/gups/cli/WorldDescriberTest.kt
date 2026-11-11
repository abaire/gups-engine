package gups.cli

import gups.pluginapi.host.PluginInfo
import gups.pluginapi.host.PluginMemberInfo
import gups.pluginapi.host.PluginTraitInfo
import gups.script.WorldDir
import gups.script.WorldPluginWiring
import gups.script.testing.TestWorlds
import gups.script.testing.tempWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WorldDescriberTest {
  private val zone = "package(api_version = 1, kind = \"zone\")\n"

  private fun describe(files: Map<String, String>): Pair<JsonObject?, List<String>> {
    val dir = WorldDir(tempWorld(files))
    val problems = mutableListOf<String>()
    val text = describeWorld(dir, WorldPluginWiring.NONE, emptyList(), problems)
    return text?.let { Json.parseToJsonElement(it).jsonObject } to problems
  }

  private fun JsonObject.list(key: String): List<JsonObject> =
    getValue(key).jsonArray.map { it.jsonObject }

  private fun JsonElement.strings(): List<String> = jsonArray.map { it.jsonPrimitive.content }

  private val world =
    mapOf(
      "zones/a/package.star" to zone,
      "zones/a/rooms.star" to
        """
        room("hall", name = "The Hall", size = SIZE.large, regions = ["wild"], tags = ["b", "a"],
            flags = ["dead_end_ok"], exits = [
            exit(DIR.south, to = ":gate"),
            exit(DIR.east, to = ":vault", door = door(state = DOOR.locked, key = ":key", tags = ["creaky"])),
        ])
        room("gate", name = "The Gate", exits = [exit(DIR.north, to = ":hall")])
        room("vault", name = "The Vault", exits = [exit(DIR.west, to = ":hall", door = door())])
        region("wild")
        item("key", name = "a brass key", keywords = ["key", "brass"], kind = "key")
        mob("rat", name = "a rat", keywords = ["rat"], level = 3,
            stats = stats(str = 12, dex = 8), vitals = vitals(hp = 20, mana = 5),
            resists = resists(slash = 10, fire = -10),
            skills = {"//skills/s:zap": 40, "//skills/s:burn": 10})
        mob("cat", name = "a cat", keywords = ["cat"], factions = ["//zones/a:rat"])
        """,
      "skills/s/package.star" to "package(api_version = 1, kind = \"skills\")\n",
      "skills/s/skills.star" to
        """
        skill("zap", kind = "spell", range = "near", target = "ally",
            cost = vitals(move = 2, mana = 8), warmup = 1, lag = 4,
            channels = ["spell", "sound"], tags = ["blue", "arc"],
            effect = heal(dice = "1d4", vital = "hp"))
        skill("burn", effect = damage(dice = "1d4", type = DAMAGE.fire))
        """,
    )

  @Test
  fun `describes schema in declaration order`() {
    val (doc, problems) = describe(world)
    assertTrue(problems.isEmpty(), problems.toString())
    doc!!
    assertEquals(
      listOf(
        "format",
        "api_version",
        "engine_version",
        "schema",
        "features",
        "commands",
        "packages",
        "rooms",
        "mobs",
        "items",
        "skills",
        "factions",
        "regions",
        "plugins",
        "errors",
      ),
      doc.keys.toList(),
    )
    assertEquals(1, doc.getValue("format").jsonPrimitive.int)
    val schema = doc.getValue("schema").jsonObject
    assertEquals(
      listOf("str", "dex", "con", "int", "wis", "cha", "speed", "armor"),
      schema.list("stats").map { it.getValue("name").jsonPrimitive.content },
    )
    assertEquals(
      listOf("Hit points", "Mana", "Movement"),
      schema.list("vitals").map { it.getValue("label").jsonPrimitive.content },
    )
    assertEquals(
      listOf("bludgeon", "slash", "pierce", "fire"),
      schema.list("damage_types").map { it.getValue("name").jsonPrimitive.content },
    )
    assertEquals(JsonNull, schema.list("damage_types")[0].getValue("adjective"))
    assertEquals("Fire", schema.list("damage_types")[3].getValue("label").jsonPrimitive.content)
    assertEquals("bludgeon", schema.getValue("unarmed_damage_type").jsonPrimitive.content)
    assertEquals(listOf("sight", "sound", "spell"), schema.getValue("channels").strings())
    assertEquals(
      listOf("combat", "leveling", "skills", "standing", "track"),
      doc.getValue("features").strings(),
    )
    assertEquals(JsonObject(emptyMap()), doc.getValue("commands"))
  }

  @Test
  fun `room exits keep declared order and doors are described`() {
    val doc = describe(world).first!!
    val rooms = doc.list("rooms")
    assertEquals(
      listOf("//zones/a:gate", "//zones/a:hall", "//zones/a:vault"),
      rooms.map { it.getValue("label").jsonPrimitive.content },
    )
    val hall = rooms[1]
    assertEquals("large", hall.getValue("size").jsonPrimitive.content)
    assertEquals(listOf("wild"), hall.getValue("regions").strings())
    assertEquals(listOf("a", "b"), hall.getValue("tags").strings())
    assertEquals(listOf("dead_end_ok"), hall.getValue("flags").strings())
    val exits = hall.list("exits")
    assertEquals(listOf("south", "east"), exits.map { it.getValue("dir").jsonPrimitive.content })
    assertEquals("//zones/a:gate", exits[0].getValue("to").jsonPrimitive.content)
    assertEquals(false, exits[0].getValue("one_way").jsonPrimitive.boolean)
    assertEquals(JsonNull, exits[0].getValue("door"))
    val door = exits[1].getValue("door").jsonObject
    assertEquals(listOf("state", "key", "tags"), door.keys.toList())
    assertEquals("locked", door.getValue("state").jsonPrimitive.content)
    assertEquals("//zones/a:key", door.getValue("key").jsonPrimitive.content)
    assertEquals(listOf("creaky"), door.getValue("tags").strings())
    val plain = rooms[2].list("exits")[0].getValue("door").jsonObject
    assertEquals("closed", plain.getValue("state").jsonPrimitive.content)
    assertEquals(JsonNull, plain.getValue("key"))
  }

  @Test
  fun `mob fields`() {
    val doc = describe(world).first!!
    val mobs = doc.list("mobs")
    assertEquals(
      listOf("//zones/a:cat", "//zones/a:rat"),
      mobs.map { it.getValue("label").jsonPrimitive.content },
    )
    val rat = mobs[1]
    assertEquals(
      listOf(
        "label",
        "name",
        "keywords",
        "level",
        "stats",
        "vitals",
        "resists",
        "skills",
        "traits",
        "factions",
      ),
      rat.keys.toList(),
    )
    assertEquals(3, rat.getValue("level").jsonPrimitive.int)
    assertEquals(listOf("dex", "str"), rat.getValue("stats").jsonObject.keys.toList())
    assertEquals(listOf("hp", "mana"), rat.getValue("vitals").jsonObject.keys.toList())
    val resists = rat.getValue("resists").jsonObject
    assertEquals(listOf("fire", "slash"), resists.keys.toList())
    assertEquals(-10, resists.getValue("fire").jsonPrimitive.int)
    val skills = rat.getValue("skills").jsonObject
    assertEquals(listOf("//skills/s:burn", "//skills/s:zap"), skills.keys.toList())
    assertEquals(40, skills.getValue("//skills/s:zap").jsonPrimitive.int)
    assertEquals(JsonNull, rat.getValue("factions"))
    assertEquals(listOf("//zones/a:rat"), mobs[0].getValue("factions").strings())
  }

  @Test
  fun `skill fields`() {
    val doc = describe(world).first!!
    val skills = doc.list("skills")
    assertEquals(
      listOf("//skills/s:burn", "//skills/s:zap"),
      skills.map { it.getValue("label").jsonPrimitive.content },
    )
    val zap = skills[1]
    assertEquals(
      listOf("label", "kind", "target", "range", "cost", "warmup", "lag", "channels", "tags"),
      zap.keys.toList(),
    )
    assertEquals("near", zap.getValue("range").jsonPrimitive.content)
    assertEquals("ally", zap.getValue("target").jsonPrimitive.content)
    assertEquals(listOf("mana", "move"), zap.getValue("cost").jsonObject.keys.toList())
    assertEquals(listOf("sound", "spell"), zap.getValue("channels").strings())
    assertEquals(listOf("arc", "blue"), zap.getValue("tags").strings())
    assertEquals(4, zap.getValue("lag").jsonPrimitive.int)
    assertEquals("melee", skills[0].getValue("range").jsonPrimitive.content)
  }

  @Test
  fun `items regions and factions`() {
    val doc = describe(world).first!!
    val items = doc.list("items")
    assertEquals(listOf("//zones/a:key"), items.map { it.getValue("label").jsonPrimitive.content })
    assertEquals(JsonNull, items[0].getValue("wear"))
    assertEquals("key", items[0].getValue("kind").jsonPrimitive.content)
    assertEquals(listOf("wild"), doc.getValue("regions").strings())
    assertEquals(JsonArray(emptyList()), doc.getValue("factions"))
  }

  @Test
  fun `plugins sorted by id with sorted names`() {
    fun member(name: String) = PluginMemberInfo(name, "", emptyList())
    fun plugin(id: String, names: List<String>) =
      PluginInfo(
        id = id,
        doc = "",
        builtins = names.map(::member),
        effects = names.reversed().map(::member),
        commands = emptyList(),
        gmcp = emptyList(),
        traits = names.map { PluginTraitInfo(it, it, "") },
      )
    val dir = WorldDir(tempWorld(world))
    val text =
      describeWorld(
        dir,
        WorldPluginWiring.NONE,
        listOf(plugin("weather", listOf("rain", "fog")), plugin("heritage", listOf("b", "a"))),
        mutableListOf(),
      )!!
    val plugins = Json.parseToJsonElement(text).jsonObject.list("plugins")
    assertEquals(
      listOf("heritage", "weather"),
      plugins.map { it.getValue("id").jsonPrimitive.content },
    )
    val weather = plugins[1]
    assertEquals(listOf("id", "builtins", "effects", "traits"), weather.keys.toList())
    for (key in listOf("builtins", "effects", "traits")) assertEquals(
      listOf("fog", "rain"),
      weather.getValue(key).strings(),
    )
  }

  @Test
  fun `corpse prototype is not listed in items`() {
    val doc = describe(world + ("zones/b/package.star" to zone)).first!!
    assertTrue(
      doc.list("items").none { it.getValue("label").jsonPrimitive.content.contains("corpse") }
    )
  }

  @Test
  fun `failed package is listed and its errors reported`() {
    val files =
      world +
        mapOf(
          "zones/broken/package.star" to zone,
          "zones/broken/rooms.star" to "room(\"x\", name = \n",
        )
    val (doc, problems) = describe(files)
    assertTrue(problems.isEmpty(), problems.toString())
    doc!!
    val packages = doc.list("packages")
    assertEquals(
      listOf("rules", "skills/s", "zones/a", "zones/broken"),
      packages.map { it.getValue("path").jsonPrimitive.content },
    )
    val broken = packages.last()
    assertEquals(false, broken.getValue("ok").jsonPrimitive.boolean)
    assertEquals(JsonNull, broken.getValue("kind"))
    val good = packages[2]
    assertEquals(true, good.getValue("ok").jsonPrimitive.boolean)
    assertEquals("zone", good.getValue("kind").jsonPrimitive.content)
    val errors = doc.list("errors")
    assertTrue(errors.all { it.getValue("package").jsonPrimitive.content == "zones/broken" })
    val error = errors.first()
    assertEquals("zones/broken", error.getValue("package").jsonPrimitive.content)
    assertEquals("//zones/broken:rooms.star", error.getValue("file").jsonPrimitive.content)
    assertNotNull(error.getValue("line").jsonPrimitive.int)
    assertEquals(listOf("package", "file", "line", "message"), error.keys.toList())
    assertEquals(3, doc.list("rooms").size)
  }

  @Test
  fun `module problems return null`() {
    val dir =
      WorldDir(
        tempWorld(
          mapOf("MODULE.gups" to TestWorlds.MODULE.replace("api_version = 1", "api_version = 99"))
        )
      )
    val problems = mutableListOf<String>()
    assertNull(describeWorld(dir, WorldPluginWiring.NONE, emptyList(), problems))
    assertTrue(problems.any { it.contains("api_version") }, problems.toString())
  }

  @Test
  fun `output is deterministic`() {
    val dir = WorldDir(tempWorld(world))
    val a = describeWorld(dir, WorldPluginWiring.NONE, emptyList(), mutableListOf())
    val b = describeWorld(dir, WorldPluginWiring.NONE, emptyList(), mutableListOf())
    assertNotNull(a)
    assertEquals(a, b)
    assertTrue(a.endsWith("}\n") && !a.endsWith("\n\n"))
    assertTrue(!a.contains('\r'))
  }
}
