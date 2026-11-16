package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Direction
import gups.core.model.ExitProto
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import gups.core.model.ZoneProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class TrickExitGraphTest(private val fs: FixtureSchema) {
  init {
    FixtureSchemas.current = fs
  }

  @BeforeEach
  fun useSchema() {
    FixtureSchemas.current = fs
  }

  @AfterEach
  fun resetSchema() {
    FixtureSchemas.current = FixtureSchemas.FANTASY
  }

  private val corridor = Label("//z:corridor")
  private val vaultA = Label("//z:vault_a")
  private val vaultB = Label("//z:vault_b")
  private val side = Label("//z:side")
  private val rat = Label("//z:rat")

  private fun baseProtos(extraSideRoute: Boolean = false): Prototypes {
    val corridorExits = buildList {
      add(
        ExitProto(
          Direction.NORTH,
          to = null,
          candidates = mapOf(vaultA to 1, vaultB to 1),
          oneWay = true,
        )
      )
      if (extraSideRoute) add(ExitProto(Direction.EAST, side))
    }
    val sideRoom =
      if (extraSideRoute) {
        listOf(
          RoomProto(
            side,
            "Side Passage",
            exits = listOf(ExitProto(Direction.WEST, corridor), ExitProto(Direction.NORTH, vaultA)),
          )
        )
      } else {
        emptyList()
      }
    return Prototypes(
      rooms =
        listOf(
          RoomProto(corridor, "The Corridor", exits = corridorExits),
          RoomProto(vaultA, "Vault A"),
          RoomProto(vaultB, "Vault B"),
        ) + sideRoom,
      mobs =
        listOf(
          MobProto(
            rat,
            "a rat",
            listOf("rat"),
            vitals = mapOf(fs.health to 100),
            behaviors = listOf(Wander(radius = 2, chance = 100)),
          )
        ),
      zones = listOf(ZoneProto("z")),
    )
  }

  @Test
  fun `pathTo skips per-crossing exits and goes round when another route exists`() {
    val f1 = Fixture(protos = baseProtos(extraSideRoute = false))
    assertNull(f1.w.pathTo(f1.w.room(corridor), f1.w.room(vaultA)))

    val f2 = Fixture(protos = baseProtos(extraSideRoute = true))
    assertEquals(
      listOf(Direction.EAST, Direction.NORTH),
      f2.w.pathTo(f2.w.room(corridor), f2.w.room(vaultA)),
    )
  }

  @Test
  fun `wander mob never proposes the per-crossing direction`() {
    val wander = Wander(radius = 2, chance = 100)
    for (seed in 1L..10L) {
      val f = Fixture(protos = baseProtos(extraSideRoute = true), seed = seed)
      val mob = f.w.spawnMob(rat, f.w.room(corridor))
      val proposal = wander.propose(f.w, mob)
      assertFalse(proposal?.action is MoveAction && proposal.commandLine == Direction.NORTH.name)
    }
  }

  @Test
  fun `within excludes vaults reached only through a per-crossing exit`() {
    val f = Fixture(protos = baseProtos())
    val reached = f.w.within(f.w.room(corridor), 2)
    assertEquals(setOf(f.w.room(corridor)), reached)
  }

  @Test
  fun `stat lists a per-crossing exit's candidates`() {
    val f = Fixture(protos = baseProtos())
    val line = f.w.statLines(f.w.room(corridor)).first { it.startsWith("Size ") }
    assertTrue(line.endsWith("exits: north -> //z:vault_a | //z:vault_b"), line)
  }

  @Test
  fun `ball includes every candidate of a per-crossing exit`() {
    val f = Fixture(protos = baseProtos(), bubble = true)
    val ball = f.w.ball(f.w.room(corridor)).toSet()
    assertTrue(f.w.room(vaultA) in ball)
    assertTrue(f.w.room(vaultB) in ball)
  }

  @Test
  fun `inbound excludes per-crossing exits and events do not propagate through them`() {
    val f = Fixture(protos = baseProtos())
    val p = f.player("Ann", at = corridor)
    val q = f.player("Bob", at = vaultA)
    f.run()
    f.out(p)
    f.out(q)

    assertTrue(f.w.inbound(f.w.room(vaultA)).isEmpty())

    f.input(q, "say Hello from Vault A!")
    f.run()
    assertFalse("Hello from Vault A!" in f.out(p))
  }

  @Test
  fun `walking through a per-crossing exit lands in a candidate`() {
    val f = Fixture(protos = baseProtos())
    val p = f.player("Ann", at = corridor)
    f.run()
    f.out(p)

    f.input(p, "north")
    f.run()
    assertTrue(p.room.label == vaultA || p.room.label == vaultB)
  }

  @Test
  fun `unknown candidate room fails World construction`() {
    val missing = Label("//z:missing")
    val protos =
      Prototypes(
        rooms =
          listOf(
            RoomProto(
              corridor,
              "The Corridor",
              exits =
                listOf(
                  ExitProto(
                    Direction.NORTH,
                    to = null,
                    candidates = mapOf(vaultA to 1, missing to 1),
                    oneWay = true,
                  )
                ),
            ),
            RoomProto(vaultA, "Vault A"),
          ),
        zones = listOf(ZoneProto("z")),
      )
    val ex = assertFailsWith<IllegalArgumentException> { Fixture(protos = protos) }
    assertEquals("//z:corridor exit north leads to unknown room //z:missing", ex.message)
  }
}
