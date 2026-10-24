package gups.persist

import gups.core.AttrValue
import gups.core.EntityId
import gups.core.HookRef
import gups.core.Label
import gups.core.model.DamageType
import gups.core.model.DoorState
import gups.core.model.Entity
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.world.Diagnostic
import gups.core.world.ScriptHost
import gups.core.world.TOMBSTONE
import gups.core.world.World
import gups.core.world.applyAffect
import gups.core.world.checkInvariants
import gups.core.world.damage
import gups.core.world.moveItem
import gups.core.world.quit
import gups.core.world.setActive
import gups.core.world.setAnchor
import gups.core.world.setDoor
import gups.core.world.spawnItem
import gups.core.model.Direction
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistenceTest {
    private fun count(file: java.nio.file.Path, sql: String): Int = DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
        c.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getInt(1) } }
    }

    @Test
    fun `schema migrations run once, in order, and newer databases are refused`() {
        val file = tempDb()
        Database.open(file).use { assertEquals(1, it.schemaVersion) }
        val v2 = MIGRATIONS + Migration(2, listOf("ALTER TABLE players ADD COLUMN nickname TEXT"))
        Database.open(file, v2).use { assertEquals(2, it.schemaVersion) }
        Database.open(file, v2).use { assertEquals(2, it.schemaVersion) }
        val e = assertFailsWith<SQLException> { Database.open(file) }
        assertContains(e.message!!, "schema version 2; this engine knows up to 1")
        assertEquals(1, count(file, "SELECT COUNT(*) FROM pragma_table_info('players') WHERE name = 'nickname'"))
    }

    @Test
    fun `a world and its characters survive a restart`() {
        val file = tempDb()
        val a = PersistWorld(file)
        val ann = a.login("Ann")
        val hall = a.w.room(P.HALL)
        val rat = a.w.actors.filterIsInstance<Mob>().first()
        a.w.damage(rat, 3, DamageType.FIRE)
        rat.customAttributes["t:angry"] = AttrValue.B(true)
        a.w.setDoor(hall, Direction.SOUTH, DoorState.OPEN)
        val bag = a.w.spawnItem(P.BAG, ItemLocation.Carried(ann.id))
        a.w.spawnItem(P.COIN, ItemLocation.Contained(bag.id), 7)
        a.w.spawnItem(P.GEM, ItemLocation.InRoom(hall.id, 3))
        ann.customAttributes["t:visits"] = AttrValue.L(listOf(AttrValue.I(1), AttrValue.S("x")))
        a.w.after(ann, 500, HookRef(Label("//t:a.star"), "_ring"), mapOf("n" to AttrValue.I(2)))
        a.w.applyAffect(ann, "blessed", 900, statDeltas = mapOf("str" to 2))
        a.run(130)
        // Shutdown takes a final checkpoint (SPEC §5.11).
        a.save()
        val pulse = a.w.pulse
        val ratId = rat.id
        a.close()

        val b = PersistWorld(file)
        assertEquals(pulse, b.w.pulse)
        assertEquals(hall.id, b.w.room(P.HALL).id, "room ids are stable")
        assertEquals(DoorState.OPEN, b.w.room(P.HALL).exits.values.single().door!!.state)
        assertEquals(DoorState.OPEN, b.w.room(P.VAULT).exits.values.single().door!!.state)
        val rat2 = b.w.actor(ratId) as Mob
        assertEquals(rat.hp, rat2.hp, "vitals are saved as they were")
        assertEquals(AttrValue.B(true), rat2.customAttributes["t:angry"])
        assertEquals(2, b.w.actors.count { it is Mob }, "both spawns came back, none refilled")
        assertEquals(listOf("a gem"), b.w.room(P.HALL).items.map { it.name })
        assertTrue(b.w.players.isEmpty(), "players wait for their login")
        val ann2 = b.login("Ann")
        assertEquals(ann.id, ann2.id)
        assertContains(b.sinks.getValue("Ann").transcript, "Welcome back, Ann.")
        val bag2 = ann2.inventory.single()
        assertEquals(7, bag2.contents.single().count)
        assertEquals(AttrValue.L(listOf(AttrValue.I(1), AttrValue.S("x"))), ann2.customAttributes["t:visits"])
        assertEquals(ann.timers.single().due, ann2.timers.single().due, "timers keep their absolute deadline")
        assertEquals(12, ann2.stat("str"))
        assertTrue(b.w.checkInvariants().isEmpty())
        b.close()
    }

    @Test
    fun `restore overlays deltas on new prototypes and handles deleted ones`() {
        val file = tempDb()
        val a = PersistWorld(file)
        val ann = a.login("Ann")
        val rats = a.w.actors.filterIsInstance<Mob>()
        rats[0].maxHp = 50
        rats[0].dirty = true
        a.w.spawnItem(P.GEM, ItemLocation.Carried(ann.id))
        a.w.spawnItem(P.GEM, ItemLocation.InRoom(a.w.room(P.HALL).id, 0))
        a.w.quit(ann)
        a.save()
        a.close()

        val b = PersistWorld(file, P.protos(ratHp = 30, withGem = false))
        val rats2 = b.w.actors.filterIsInstance<Mob>().sortedBy { it.id }
        assertEquals(listOf(50, 30), rats2.map { it.maxHp }, "the changed field stays, the unchanged one follows the prototype")
        assertTrue(b.w.room(P.HALL).items.isEmpty(), "a ground item whose prototype is gone is despawned")
        val ann2 = b.login("Ann")
        val stone = ann2.inventory.single()
        assertEquals(TOMBSTONE, stone.kind)
        assertEquals("a gem", stone.name)
        b.save()
        b.close()

        val c = PersistWorld(file, P.protos(withRat = false, withGem = false))
        assertTrue(c.w.actors.none { it is Mob }, "mobs of a deleted prototype are despawned")
        val saved = c.persistence.db.entityRows().first { it.protoLabel == P.GEM.v }
        assertContains(saved.payload, "\"tombstone\"", message = "the tombstone keeps its last real payload")
        assertEquals("a gem", c.login("Ann").inventory.single().name)
        c.close()
    }

    @Test
    fun `migrate runs when a prototype's version goes up`() {
        val file = tempDb()
        PersistWorld(file).also {
            it.w.actors.filterIsInstance<Mob>().forEach { m -> m.customAttributes["t:alarm"] = AttrValue.B(true) }
            it.save()
            it.close()
        }
        val calls = mutableListOf<Pair<EntityId, Int>>()
        val host = object : ScriptHost {
            override fun invokeHook(world: World, owner: Entity, hook: gups.core.world.HookName, ctx: gups.core.world.HookContext) =
                gups.core.world.HookOutcome.Allow

            override fun invokeRef(world: World, owner: Entity, ref: HookRef, ctx: gups.core.world.HookContext) = Unit

            override fun migrate(world: World, entity: Entity, fromVersion: Int) {
                calls += entity.id to fromVersion
                entity.customAttributes["t:alarm_raised"] = entity.customAttributes.remove("t:alarm")!!
            }
        }
        val b = PersistWorld(file, P.protos(ratVersion = 2), scripts = host)
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.second == 1 })
        val rat = b.w.actors.filterIsInstance<Mob>().first()
        assertEquals(2, rat.protoVersion)
        assertEquals(AttrValue.B(true), rat.customAttributes["t:alarm_raised"])
        b.save()
        b.close()
        assertEquals(2, count(file, "SELECT COUNT(*) FROM world_entities WHERE kind = 'mob' AND proto_version = 2"))
    }

    @Test
    fun `fresh boots from definitions but keeps characters and their items`() {
        val file = tempDb()
        val a = PersistWorld(file)
        val ann = a.login("Ann")
        a.w.spawnItem(P.GEM, ItemLocation.Carried(ann.id))
        a.w.spawnItem(P.GEM, ItemLocation.InRoom(a.w.room(P.HALL).id, 0))
        a.w.actors.filterIsInstance<Mob>().forEach { a.w.damage(it, 100, DamageType.FIRE) }
        a.w.quit(ann)
        a.save()
        a.close()

        val b = PersistWorld(file, fresh = true)
        assertEquals(2, b.w.actors.count { it is Mob }, "spawns refilled from the definitions")
        assertTrue(b.w.room(P.HALL).items.isEmpty())
        b.save()
        assertEquals(1, count(file, "SELECT COUNT(*) FROM world_entities WHERE kind = 'item'"), "only Ann's gem row is left")
        assertEquals("a gem", b.login("Ann").inventory.single().name)
        b.close()
    }

    @Test
    fun `checkpoints follow the schedule, and transfers wait for it with an audit trail`() {
        val file = tempDb()
        val a = PersistWorld(file, checkpointPulses = 240)
        val ann = a.login("Ann")
        val bob = a.login("Bob")
        a.save()
        val before = a.persistence.writer.committed
        val gem = a.w.spawnItem(P.GEM, ItemLocation.Carried(ann.id))
        a.w.moveItem(gem, ItemLocation.Carried(bob.id))
        // The transfer is captured when its cascade ends, but held for the schedule (SPEC §5.11).
        a.run(120)
        assertTrue(a.persistence.writer.flush())
        assertEquals(before, a.persistence.writer.committed, "nothing written at the first world tick")
        a.run(120)
        assertTrue(a.persistence.writer.flush())
        assertEquals(before + 1, a.persistence.writer.committed, "one write at the scheduled checkpoint")
        val events = a.persistence.db.itemEvents(gem.id.v)
        assertEquals(listOf("spawn", "move"), events.map { it.cause })
        assertContains(events[1].toJson!!, "\"carried\":${bob.id.v}")
        val row = a.persistence.db.entityRows().first { it.id == gem.id.v }
        assertContains(row.location!!, "${bob.id.v}")
        a.close()
    }

    @Test
    fun `zone state and quarantine survive a restart`() {
        val file = tempDb()
        val quarantined = mutableSetOf<String>()
        val host = object : ScriptHost {
            override fun invokeHook(world: World, owner: Entity, hook: gups.core.world.HookName, ctx: gups.core.world.HookContext) =
                gups.core.world.HookOutcome.Allow

            override fun invokeRef(world: World, owner: Entity, ref: HookRef, ctx: gups.core.world.HookContext) = Unit

            override val quarantinedPackages: Set<String> get() = quarantined

            override fun quarantine(world: World, pkg: String, why: String) {
                quarantined += pkg
            }
        }
        val a = PersistWorld(file, scripts = host)
        val rat = a.w.actors.filterIsInstance<Mob>().first()
        a.w.damage(rat, 100, DamageType.FIRE)
        quarantined += "t"
        a.run(120)
        val respawnAt = 1 + 300L
        a.save()
        a.close()
        quarantined.clear()

        val b = PersistWorld(file, scripts = host)
        assertEquals(setOf("t"), quarantined)
        assertEquals(1, b.w.actors.count { it is Mob }, "the dead rat isn't back before its respawn")
        b.run((respawnAt - b.w.pulse + 120).toInt())
        assertEquals(2, b.w.actors.count { it is Mob })
        b.close()
    }

    @Test
    fun `script diagnostics are stored with a repro bundle`() {
        val file = tempDb()
        val a = PersistWorld(file)
        val ann = a.login("Ann")
        a.save()
        a.input(ann, "south")
        a.w.record(
            Diagnostic(
                a.w.pulse, "script_error", "on_enter: boom", pkg = "t", label = P.HALL, hook = "on_enter",
                file = "//t:rooms.star", line = 3, ctx = "self=The Hall", seed = 1, room = P.HALL,
            ),
        )
        a.save()
        val d = a.persistence.db.diagnostics().single { it.kind == "script_error" }
        assertEquals("on_enter", d.hook)
        val bundle = ReproBundle.parse(assertNotNull(d.reproJson))
        assertEquals("//t:hall", bundle.room)
        assertEquals(listOf("Ann"), bundle.players.map { it.name })
        assertEquals(2, bundle.entities.count { it.kind == "mob" })
        assertEquals(listOf("south"), bundle.inputs.map { it.line })
        assertNull(a.persistence.db.diagnostics().firstOrNull { it.kind == "nope" })
        a.close()
    }

    @Test
    fun `backups snapshot, prune and restore`() {
        val file = tempDb()
        val a = PersistWorld(file)
        a.login("Ann")
        a.save()
        val backups = Backups(a.persistence.db)
        val t0 = java.time.Instant.parse("2026-01-01T00:00:00Z")
        repeat(50) { backups.snapshot(Backups.Kind.HOURLY, t0.plusSeconds(3600L * it)) }
        assertEquals(48, backups.list(Backups.Kind.HOURLY).size)
        val manual = backups.snapshot(Backups.Kind.MANUAL)
        a.login("Bob")
        a.save()
        a.close()
        assertEquals(2, count(file, "SELECT COUNT(*) FROM players"))
        Database.restore(manual, file)
        assertEquals(1, count(file, "SELECT COUNT(*) FROM players"))
    }

    @Test
    fun `affect modifiers and active and anchor reasons survive a restart`() {
        val file = tempDb()
        val first = PersistWorld(file)
        val rat = first.w.actors.filterIsInstance<Mob>().first()
        val fn = HookRef(Label("//t:mods.star"), "slow")
        first.w.applyAffect(rat, "slowed", 10_000, modifiers = mapOf(gups.core.model.ModifierPoint.REGEN to fn))
        first.w.setActive(rat, "boss")
        first.w.setAnchor(rat, "raid:1")
        first.save()
        first.close()

        val second = PersistWorld(file)
        val back = second.w.actor(rat.id) as Mob
        assertEquals(mapOf(gups.core.model.ModifierPoint.REGEN to fn), back.affects.single().modifiers)
        assertEquals(setOf("boss"), back.activeReasons)
        assertEquals(setOf("raid:1"), back.anchorReasons)
        second.close()
    }
}

