package gups.core.world

import gups.core.Capabilities
import gups.core.Label
import gups.core.Restriction
import gups.core.Role
import gups.core.fixtures.FixtureSchemas
import gups.core.model.AffectScope
import gups.core.model.Item
import gups.core.model.ItemAffect
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Mob
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolsTest {
  private val shed = Label("//u:shed")

  private fun fixture(strict: Boolean = true) =
    Fixture(protos = T.protos(extraRooms = listOf(RoomProto(shed, "A Shed"))), strict = strict)

  private fun Fixture.builder(name: String = "Bea", packages: Set<String> = setOf("t")) =
    player(name).also {
      it.role = Role.BUILDER
      it.scope = packages
      it.accountId = 1
    }

  @Test
  fun `an at-prefix resolves only when it matches one usable tool`() {
    val f = fixture()
    val b = f.builder()
    val reg = f.w.commands
    assertEquals("@spawn", reg.resolve(b, "@sp")?.name)
    assertNull(reg.resolve(b, "@s"), "@stat and @spawn")
    // @stats needs an instructor, so for a builder @sta can only mean @stat.
    assertEquals("@stat", reg.resolve(b, "@sta")?.name)
    b.role = Role.INSTRUCTOR
    assertNull(reg.resolve(b, "@sta"), "@stat and @stats")
    assertEquals("@stat", reg.resolve(b, "@stat")?.name)
  }

  @Test
  fun `builder commands are confined to the builder's packages`() {
    val f = fixture()
    val b = f.builder()
    val ann = f.player("Ann", at = T.YARD)
    f.input(b, "@goto //u:shed")
    assertEquals("That isn't one of your packages.\n", f.out(b))
    f.input(b, "@goto :vault")
    assertContains(f.out(b), "The Vault")
    f.input(b, "@goto ann")
    assertEquals(T.YARD, b.room.label, "any player, by default")
    f.input(b, "@goto hall")
    assertEquals(
      "No player or room called 'hall'.\n",
      f.out(b).lines().last { it.isNotEmpty() } + "\n",
    )

    f.w.teleport(ann, f.w.room(shed))
    val gnome = f.mob(at = T.HALL)
    f.input(b, "@stat #${gnome.id.v}")
    assertContains(f.out(b), "#${gnome.id.v} //t:goblin \"a goblin\" (mob, version 1)")
    f.input(b, "@stat #${f.w.room(shed).id.v}")
    assertEquals("That isn't one of your packages.\n", f.out(b))
    f.input(b, "@stat #${ann.id.v}")
    assertEquals(
      "That isn't one of your packages.\n",
      f.out(b),
      "a player belongs to their room's package",
    )
    f.input(b, "@where")
    assertContains(f.out(b), "//t:yard: ")
    f.input(b, "@spawn //u:anything")
    assertEquals("That isn't one of your packages.\n", f.out(b))

    // Instructors may use them anywhere.
    b.role = Role.INSTRUCTOR
    f.input(b, "@goto //u:shed")
    assertContains(f.out(b), "A Shed")
    f.input(b, "@where")
    assertContains(f.out(b), "Ann #${ann.id.v}: index 0, melee")
  }

  @Test
  fun `errors shows a builder only their packages' diagnostics`() {
    val f = fixture()
    val b = f.builder()
    f.w.record(Diagnostic(0, "script_error", "on_enter: mine", pkg = "t"))
    f.w.record(Diagnostic(0, "script_error", "on_enter: theirs", pkg = "u"))
    f.w.record(Diagnostic(0, "engine", "engine trouble"))
    f.w.flushOutput()
    f.out(b)
    f.input(b, "@errors")
    assertEquals("[0] script_error //t: on_enter: mine\n", f.out(b))
    f.input(b, "@errors //u")
    assertEquals("That isn't one of your packages.\n", f.out(b))
    b.role = Role.INSTRUCTOR
    f.input(b, "@errors")
    assertEquals(3, f.out(b).trim().lines().size)
  }

  @Test
  fun `debug spawns are never saved, award nothing, and despawn`() {
    val f = fixture()
    val b = f.builder()
    f.input(b, "@spawn :goblin 2")
    val out = f.out(b)
    assertEquals(2, Regex("You spawn a goblin \\(#\\d+, debug\\)").findAll(out).count(), out)
    val goblins = f.w.actors.filterIsInstance<Mob>()
    assertTrue(goblins.all { it.debug && it.debugOwner == b.id })
    f.input(b, "@spawn :key")
    val key = b.inventory.single()
    assertTrue(key.debug)
    f.input(b, "@spawn :nothing")
    assertEquals(
      "There is no mob or item //t:nothing.\n",
      f.out(b).lines().last { it.isNotEmpty() } + "\n",
    )
    f.input(b, "@spawn :goblin 11")
    assertEquals("You can spawn 1 to 10 at a time.\n", f.out(b))

    // Killing one: no XP, and the corpse and its loot are debug.
    val g = goblins.first()
    f.setVital(g, "hp", 1)
    f.hurt(g, 5, b)
    assertEquals(0, b.xp)
    val corpse = f.w.room(T.HALL).items.single { it.name.startsWith("the corpse") }
    assertTrue(corpse.debug && corpse.contents.all(Item::debug))

    // The other despawns when its spawner logs out.
    f.w.quit(b)
    assertFalse(goblins[1].inWorld)

    val c = f.builder("Cy")
    f.input(c, "@spawn :goblin")
    val left = f.w.actors.filterIsInstance<Mob>().single()
    f.run(DEBUG_MOB_PULSES.toInt() - 120)
    assertTrue(left.inWorld)
    f.run(240)
    assertFalse(left.inWorld, "gone after 30 minutes")
  }

  @Test
  fun `stat on an item shows its affects and tag changes`() {
    val charm =
      ItemProto(
        Label("//t:charm"),
        "a charm",
        listOf("charm"),
        tags = setOf("shiny"),
        affects =
          listOf(ItemAffect("misfortune", AffectScope.CARRIED, statDeltas = mapOf("str" to -2))),
      )
    val f = Fixture(protos = Prototypes(T.rooms, T.mobs, T.items + charm, T.skills))
    val b = f.builder()
    val item = f.w.spawnItem(Label("//t:charm"), ItemLocation.Carried(b.id))
    f.w.applyItemAffect(
      item,
      AffectSpec("keen", 500, flags = setOf("glowing")),
      applies = AffectScope.WORN,
    )
    assertTrue(f.w.addItemTag(item, "cursed"))
    assertTrue(f.w.removeItemTag(item, "shiny"))
    f.input(b, "@stat charm")
    val out = f.out(b)
    assertContains(out, "Tags: cursed")
    assertContains(out, "Affects: misfortune (carried, prototype), keen (worn, 125s left; glowing)")
    assertContains(out, "Tag changes: +cursed, -shiny")
  }

  @Test
  fun `stat on an actor shows each affect once with its flags`() {
    val f = fixture()
    val b = f.builder()
    val mob = f.mob(at = b.room.label)
    f.w.applyAffect(mob, AffectSpec("keen", 500, flags = setOf("glowing")))
    f.input(b, "@stat #${mob.id.v}")
    assertContains(f.out(b), "Affects: keen (125s left; glowing)")
  }

  @Test
  fun `stat on an item without affects or tag changes says so`() {
    val f = fixture()
    val b = f.builder()
    f.w.spawnItem(T.KEY, ItemLocation.Carried(b.id))
    f.input(b, "@stat key")
    val out = f.out(b)
    assertContains(out, "Affects: none")
    assertFalse("Tag changes:" in out)
  }

  @Test
  fun `stat on a tombstone lists the instance affects it keeps`() {
    val charm =
      ItemProto(
        Label("//t:charm"),
        "a charm",
        listOf("charm"),
        affects =
          listOf(ItemAffect("misfortune", AffectScope.CARRIED, statDeltas = mapOf("str" to -2))),
      )
    val f = Fixture(protos = Prototypes(T.rooms, T.mobs, T.items + charm, T.skills))
    val b = f.builder()
    val item = f.w.spawnItem(Label("//t:charm"), ItemLocation.Carried(b.id))
    f.w.applyItemAffect(item, AffectSpec("ward", null), applies = AffectScope.CARRIED)
    item.proto = tombstone(item.proto)
    f.input(b, "@stat charm")
    val out = f.out(b)
    assertContains(out, "Affects: ward (carried, permanent)\n")
    assertFalse("misfortune" in out, "a tombstone has no prototype affects")
  }

  @Test
  fun `restrictions take capabilities away until they end`() {
    val f = fixture()
    var now = 1_000_000L
    f.w.wallClock = { now }
    val b = f.builder()
    f.player("Ann", at = T.YARD)
    f.w.accountRestrictions =
      listOf(
        Restriction(1, 1, Capabilities.GOTO_PLAYER, now + 60_000, "stalking", 9, now),
        Restriction(2, 1, "gossip", null, null, 9, now),
        Restriction(3, 1, "spawn", now + 1, null, 9, now, liftedBy = 9, liftedAt = now),
      )
    f.input(b, "@goto ann")
    assertEquals("You can't do that right now. That changes 1970-01-01 00:17 UTC.\n", f.out(b))
    f.input(b, "gossip hi")
    assertEquals("You can't do that right now.\n", f.out(b))
    f.input(b, "@spawn :key")
    assertTrue("You spawn a brass key" in f.out(b), "a lifted restriction doesn't count")
    now += 60_000
    f.input(b, "@goto ann")
    assertEquals(T.YARD, b.room.label)
    b.role = Role.INSTRUCTOR
    f.input(b, "gossip hi")
    assertTrue("[gossip] Bea: hi" in f.out(b), "instructors are never restricted")
  }

  @Test
  fun `instructor tools`() {
    val f = fixture(strict = false)
    val i = f.player("Ida").also { it.role = Role.INSTRUCTOR }
    f.player("Ann", at = T.YARD)
    f.input(i, "@who")
    val who = f.out(i)
    assertContains(who, "Ann ")
    assertContains(who, "instructor")
    assertContains(who, "local local, idle 0s")
    f.input(i, "@bubble")
    assertEquals("The reality bubble is off: every room is simulated.\n", f.out(i))
    f.input(i, "@stats")
    assertContains(f.out(i), "Sessions: local 2.")
    f.input(i, "@deploy")
    assertContains(f.out(i), "This server can't deploy")
    f.input(i, "@snapshot")
    assertContains(f.out(i), "Snapshot failed: this world has no database to back up")
    f.input(i, "@audit item 5")
    assertContains(f.out(i), "No saved custody changes for item #5.")
    f.input(i, "@audit thing")
    assertEquals("Usage: @audit item <id>\n", f.out(i))

    val ann = f.w.players.single { it.name == "Ann" }
    f.input(i, "@shutdown 61 maintenance")
    assertContains(f.out(ann), "The world will shut down in 61s: maintenance")
    f.run(4)
    assertContains(f.out(ann), "The world shuts down in 60 seconds: maintenance")
    f.run(30 * 4)
    assertContains(f.out(ann), "30 seconds")
    f.run(25 * 4)
    assertContains(f.out(ann), "5 seconds")
    assertFalse(f.w.shutdownRequested)
    f.run(5 * 4)
    assertTrue(f.w.shutdownRequested)
  }

  @Test
  fun `bubble lists active rooms, anchors and dormant rooms`() {
    val f =
      Fixture(protos = T.protos(extraRooms = listOf(RoomProto(shed, "A Shed"))), bubble = true)
    val i = f.player("Ida").also { it.role = Role.INSTRUCTOR }
    f.run()
    f.out(i)
    f.input(i, "@bubble")
    val out = f.out(i)
    assertContains(out, "Active rooms: 4 of 5.")
    assertContains(out, "  Ida #${i.id.v} (player) in //t:hall")
    assertContains(out, "Dormant rooms: //u 1.")
  }

  @Test
  fun `reload is refused in production`() {
    val w =
      World(
        T.protos(),
        FixtureSchemas.FANTASY.schema,
        FixedRules(),
        config = WorldConfig(production = true),
      )
    val p = w.spawnPlayer("Ida", T.HALL).also { it.role = Role.INSTRUCTOR }
    val sink = gups.core.CapturingSink()
    w.handle(gups.core.WorldControlEvent.Connect(gups.core.SessionId(1), sink))
    w.attach(gups.core.SessionId(1), p)
    w.handle(gups.core.ClientInputEvent(gups.core.SessionId(1), "@reload //t", 0))
    assertContains(sink.drain(), "@reload is off on this server: changes go live with @deploy")

    val f = Fixture()
    val i = f.player("Ida").also { it.role = Role.INSTRUCTOR }
    f.input(i, "@reload //t")
    assertContains(f.out(i), "This world has no script host to reload t")
  }

  @Test
  fun `alerts reach instructors at most once a minute per kind, and the log every time`() {
    val f = fixture()
    val i = f.player("Ida").also { it.role = Role.INSTRUCTOR }
    val b = f.builder()
    val log = mutableListOf<String>()
    f.w.onAlert = { log += it }
    f.w.alert("quarantine", "package //t quarantined")
    f.w.alert("quarantine", "package //t quarantined")
    f.w.flushOutput()
    assertEquals("{yellow}[alert]{/} package //t quarantined\n", f.out(i))
    assertEquals("", f.out(b), "builders don't get instructor alerts")
    f.run(MINUTE_PULSES.toInt())
    f.w.alert("quarantine", "package //t quarantined")
    f.w.flushOutput()
    assertContains(f.out(i), "[alert]")
    assertEquals(3, log.size)

    // Owner alerts: one per package per 10 seconds.
    repeat(3) { f.w.record(Diagnostic(f.w.pulse, "script_error", "on_enter: boom", pkg = "t")) }
    f.w.flushOutput()
    assertEquals(1, f.out(b).lines().count { "[error]" in it })
    f.run(OWNER_ALERT_PULSES.toInt())
    f.w.record(Diagnostic(f.w.pulse, "step_limit", "think: too long", pkg = "t"))
    f.w.flushOutput()
    assertContains(f.out(b), "think: too long")

    // More than 100 engine exceptions in a minute.
    repeat(ENGINE_ERRORS_PER_MINUTE) { f.w.record(Diagnostic(f.w.pulse, "engine", "oops")) }
    f.w.flushOutput()
    assertFalse("engine exceptions" in f.out(i))
    f.w.record(Diagnostic(f.w.pulse, "engine", "oops"))
    f.w.flushOutput()
    assertContains(f.out(i), "101 engine exceptions in the last minute")
  }

  @Test
  fun `an overrun alert names the package that used the most steps`() {
    val steps = HashMap<String, Long>()
    val host =
      object : gups.core.world.ScriptHost by ScriptHost.NONE {
        override val stepsByPackage: Map<String, Long>
          get() = HashMap(steps)
      }
    val f = Fixture(scripts = host)
    val i = f.player("Ida").also { it.role = Role.INSTRUCTOR }
    repeat(MINUTE_PULSES.toInt()) { n ->
      f.run()
      steps["zones/a"] = n * 2L
      steps["zones/b"] = n * 5L
      f.w.pulseStats.record(if (n % 5 == 0) 300_000_000 else 1_000_000, n % 5 == 0)
      f.w.checkOverruns()
    }
    f.w.flushOutput()
    val out = f.out(i)
    assertContains(
      out,
      "20% of pulses in the last minute overran; //zones/b used the most script steps",
    )
    assertEquals(1, out.lines().count { "[alert]" in it })
    assertEquals("zones/b", f.w.busiestPackage()?.first)
    assertNull(PulseStats(4).also { it.record(1, true) }.overrunFraction.takeIf { it > 0 })
  }
}
