package gups.core.world

import gups.core.Role
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HelpCommandTest {
  @Test
  fun `help lists commands grouped logically and sorted alphabetically`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.input(p, "help")
    val out = f.out(p)
    val lines = out.lines().filter { it.isNotBlank() }

    // Categories are present and in order
    assertTrue(lines.any { it.startsWith("Communication: ") }, out)
    assertTrue(lines.any { it.startsWith("Movement: ") }, out)
    assertTrue(lines.any { it.startsWith("Information: ") }, out)
    assertTrue(lines.any { it.startsWith("Items: ") }, out)
    assertTrue(lines.any { it.startsWith("Combat: ") }, out)
    assertTrue(lines.any { it.startsWith("Skills: ") }, out)
    assertTrue(lines.any { it.startsWith("Game: ") }, out)

    // Regular players should never see the Special category or @ commands
    assertFalse(lines.any { it.startsWith("Special:") }, out)
    assertFalse("@" in out, out)

    // Check alphabetical sorting within groups
    assertEquals(
      "Communication: gossip order say shout tell whisper",
      lines.first { it.startsWith("Communication:") },
    )
    assertEquals(
      "Movement: advance retreat, and any exit name",
      lines.first { it.startsWith("Movement:") },
    )
    assertEquals(
      "Information: equipment help inventory keyring look score who",
      lines.first { it.startsWith("Information:") },
    )
    assertEquals(
      "Items: close drop get give lock open put remove unlock use wear wield",
      lines.first { it.startsWith("Items:") },
    )
    assertEquals("Combat: flee kill", lines.first { it.startsWith("Combat:") })
    assertEquals("Skills: cast skills", lines.first { it.startsWith("Skills:") })
    assertEquals("Game: clear quit stop", lines.first { it.startsWith("Game:") })
  }

  @Test
  fun `builders and instructors see special commands in a separate grouping sorted alphabetically`() {
    val f = Fixture()
    val builder = f.player("Bob").also { it.role = Role.BUILDER }
    f.input(builder, "help")
    val out = f.out(builder)
    val specialLine = out.lines().firstOrNull { it.startsWith("Special: ") }
    assertTrue(specialLine != null, "Special category should be visible to builder: $out")

    val specialCommands = specialLine.removePrefix("Special: ").split(" ")
    assertEquals(
      specialCommands.sorted(),
      specialCommands,
      "Special commands must be sorted: $specialLine",
    )
    assertTrue("@goto" in specialCommands)
    assertTrue("@spawn" in specialCommands)
    assertTrue("@stat" in specialCommands)
    assertTrue("@errors" in specialCommands)
    assertTrue("@where" in specialCommands)
    // Instructor commands must not be in builder help
    assertFalse("@shutdown" in specialCommands)
    assertFalse("@reload" in specialCommands)
  }

  @Test
  fun `instructors see all special commands including instructor tools`() {
    val f = Fixture()
    val instructor = f.player("Carol").also { it.role = Role.INSTRUCTOR }
    f.input(instructor, "help")
    val out = f.out(instructor)
    val specialLine = out.lines().firstOrNull { it.startsWith("Special: ") }
    assertTrue(specialLine != null, "Special category should be visible to instructor: $out")

    val specialCommands = specialLine.removePrefix("Special: ").split(" ")
    assertEquals(
      specialCommands.sorted(),
      specialCommands,
      "Special commands must be sorted: $specialLine",
    )
    assertTrue("@goto" in specialCommands)
    assertTrue("@shutdown" in specialCommands)
    assertTrue("@reload" in specialCommands)
    assertTrue("@deploy" in specialCommands)
  }

  @Test
  fun `help command provides detailed information about specific command`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.input(p, "help look")
    val out = f.out(p)
    assertContains(out, "Command: look")
    assertContains(out, "Aliases: l")
    assertContains(out, "Look at your surroundings")
    assertContains(out, "Usage: look [target]")
  }

  @Test
  fun `help alias looks up the full command`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.input(p, "help l")
    val out = f.out(p)
    assertContains(out, "Command: look")
    assertContains(out, "Aliases: l")
    assertContains(out, "Look at your surroundings")
  }

  @Test
  fun `help on a command with no aliases reports none`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.input(p, "help score")
    val out = f.out(p)
    assertContains(out, "Command: score")
    assertContains(out, "Aliases: none")
    assertContains(out, "Usage: score")
  }

  @Test
  fun `help on command with prefix character lists prefix and resolves via prefix`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.input(p, "help say")
    val outSay = f.out(p)
    assertContains(outSay, "Command: say")
    assertContains(outSay, "Aliases: \", '")

    f.input(p, "help '")
    val outPrefix = f.out(p)
    assertContains(outPrefix, "Command: say")
    assertContains(outPrefix, "Aliases: \", '")
  }

  @Test
  fun `help on standard directions displays aliases and usage`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.input(p, "help north")
    val outNorth = f.out(p)
    assertContains(outNorth, "Command: north")
    assertContains(outNorth, "Aliases: n")
    assertContains(outNorth, "Move north through an exit.")

    f.input(p, "help n")
    val outN = f.out(p)
    assertContains(outN, "Command: north")
    assertContains(outN, "Aliases: n")
  }

  @Test
  fun `help on unknown command reports no help available`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.input(p, "help xyzzy")
    assertEquals("No help available for 'xyzzy'.\n", f.out(p))
  }

  @Test
  fun `player cannot access detailed help on builder commands`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.input(p, "help @goto")
    assertEquals("No help available for '@goto'.\n", f.out(p))

    f.input(p, "help goto")
    assertEquals("No help available for 'goto'.\n", f.out(p))
  }

  @Test
  fun `builder can access detailed help on builder commands with or without at prefix`() {
    val f = Fixture()
    val b = f.player("Bob").also { it.role = Role.BUILDER }
    f.input(b, "help @goto")
    val outAt = f.out(b)
    assertContains(outAt, "Command: @goto")
    assertContains(outAt, "Move to a room")

    f.input(b, "help goto")
    val outNoAt = f.out(b)
    assertContains(outNoAt, "Command: @goto")
  }

  @Test
  fun `plugin commands report their own doc and appear in help`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.commands.registerPlugin(
      FastCommand(
        "weather",
        aliases = listOf("forecast"),
        category = "Environment",
        help = "Shows the current weather forecast.\nUsage: weather",
      ) {}
    )

    f.input(p, "help")
    val helpOut = f.out(p)
    assertContains(helpOut, "Environment: weather")

    f.input(p, "help weather")
    val weatherOut = f.out(p)
    assertContains(weatherOut, "Command: weather")
    assertContains(weatherOut, "Aliases: forecast")
    assertContains(weatherOut, "Shows the current weather forecast.")

    f.input(p, "help forecast")
    val forecastOut = f.out(p)
    assertContains(forecastOut, "Command: weather")
    assertContains(forecastOut, "Aliases: forecast")
  }

  @Test
  fun `renamed commands are reflected in help index and detailed help`() {
    val f = Fixture(rename = mapOf("cast" to "invoke"))
    val p = f.player("Ann")

    f.input(p, "help")
    val indexOut = f.out(p)
    assertContains(indexOut, "Skills: invoke")
    assertFalse("cast" in indexOut)

    f.input(p, "help invoke")
    val invokeOut = f.out(p)
    assertContains(invokeOut, "Command: invoke")

    f.input(p, "help cast")
    assertEquals("No help available for 'cast'.\n", f.out(p))
  }

  @Test
  fun `custom categories sort by explicit order or default to alphabetical in the middle`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.commands.registerCategory(CategoryDef("magic", "Magic", order = 550))
    f.w.commands.registerPlugin(FastCommand("spell", category = "Magic") {})
    f.w.commands.registerPlugin(FastCommand("forge", category = "Crafting") {})
    f.w.commands.registerPlugin(FastCommand("decorate", category = "Housing") {})

    f.input(p, "help")
    val lines = f.out(p).lines().filter { it.isNotBlank() }

    val categoryNames = lines.map { it.substringBefore(':') }
    // Combat (500) -> Magic (550) -> Skills (600)
    val combatIdx = categoryNames.indexOf("Combat")
    val magicIdx = categoryNames.indexOf("Magic")
    val skillsIdx = categoryNames.indexOf("Skills")
    assertTrue(combatIdx != -1 && magicIdx != -1 && skillsIdx != -1)
    assertTrue(combatIdx < magicIdx)
    assertTrue(magicIdx < skillsIdx)

    // Unordered custom categories default to middle tier (800) and sort alphabetically
    val gameIdx = categoryNames.indexOf("Game")
    val craftingIdx = categoryNames.indexOf("Crafting")
    val housingIdx = categoryNames.indexOf("Housing")
    assertTrue(gameIdx < craftingIdx)
    assertTrue(craftingIdx < housingIdx)
  }

  @Test
  fun `help on category displays category help and commands`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.commands.registerCategory(
      CategoryDef(
        id = "crafting",
        title = "Crafting",
        help = "The crafting system allows players to forge items.",
      )
    )
    f.w.commands.registerPlugin(FastCommand("forge", category = "Crafting") {})
    f.w.commands.registerPlugin(FastCommand("smelt", category = "Crafting") {})

    f.input(p, "help crafting")
    val out = f.out(p)
    assertContains(out, "Category: Crafting")
    assertContains(out, "Commands: forge smelt")
    assertContains(out, "The crafting system allows players to forge items.")
  }

  @Test
  fun `help on category without category help reports no help available`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.commands.registerCategory(CategoryDef("trade", "Trade", help = null))
    f.w.commands.registerPlugin(FastCommand("barter", category = "Trade") {})

    f.input(p, "help trade")
    assertEquals("No help available for 'trade'.\n", f.out(p))

    f.input(p, "help movement")
    assertEquals("No help available for 'movement'.\n", f.out(p))
  }

  @Test
  fun `help on staff category is hidden from players without required role`() {
    val f = Fixture()
    val p = f.player("Ann")
    val admin = f.player("Root").also { it.role = Role.ADMIN }

    f.w.commands.registerCategory(
      CategoryDef(
        id = "admin",
        title = "Administration",
        help = "Admin tools for managing the realm.",
      )
    )
    f.w.commands.registerPlugin(
      FastCommand("@ban", role = Role.ADMIN, category = "Administration") {}
    )

    f.input(p, "help administration")
    assertEquals("No help available for 'administration'.\n", f.out(p))

    f.input(admin, "help administration")
    val outAdmin = f.out(admin)
    assertContains(outAdmin, "Category: Administration")
    assertContains(outAdmin, "Commands: @ban")
    assertContains(outAdmin, "Admin tools for managing the realm.")
  }
}
