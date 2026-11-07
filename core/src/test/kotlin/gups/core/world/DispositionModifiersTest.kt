package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.FactionProto
import gups.core.model.Prototypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DispositionModifiersTest {
  private val factionA = Label("//t:faction_a")
  private val factionB = Label("//t:faction_b")
  private val fs = FixtureSchemas.current

  private fun createFixture(factions: List<FactionProto> = emptyList()): Fixture {
    val protos =
      Prototypes(
        rooms = T.rooms,
        mobs = T.mobs(fs),
        items = T.items,
        skills = T.skills(fs),
        factions = factions,
      )
    return Fixture(fs = fs, protos = protos, features = Features.ALL)
  }

  @Test
  fun `dispositionModifiers adjust standing in order and clamp each step`() {
    val f = createFixture()
    val mob = f.mob(T.GOBLIN)
    val player = f.player("Alice")

    // Default disposition between mob and player is 0
    assertEquals(0, f.w.disposition(mob, player))

    f.w.dispositionMemo.clear()
    f.w.dispositionModifiers =
      listOf(
        DispositionModifier { _, _, _, value -> value + 50 },
        DispositionModifier { _, _, _, value -> value + 80 }, // 50 + 80 = 130 -> clamped to 100
        DispositionModifier { _, _, _, value -> value - 30 }, // 100 - 30 = 70
      )

    assertEquals(70, f.w.disposition(mob, player))
  }

  @Test
  fun `factionAdjusters adjust factions in order and drop non-factions`() {
    val protoA = FactionProto(factionA)
    val protoB = FactionProto(factionB)
    val f = createFixture(factions = listOf(protoA, protoB))
    val player = f.player("Alice")

    // Player default factions: empty
    assertEquals(emptyList<Label>(), f.w.factionsOf(player))

    f.w.factionCache.clear()
    val invalidFaction = Label("//t:nonexistent_faction")
    f.w.factionAdjusters =
      listOf(
        FactionAdjuster { _, _, current -> current + listOf(factionA, invalidFaction) },
        FactionAdjuster { _, _, current -> current + listOf(factionB) },
      )

    assertEquals(listOf(factionA, factionB), f.w.factionsOf(player))
  }

  @Test
  fun `a disposition modifier asking the same disposition is refused`() {
    val f = createFixture()
    val mob = f.mob(T.GOBLIN)
    val player = f.player("Alice")
    f.w.dispositionModifiers = listOf(DispositionModifier { w, h, s, _ -> w.disposition(h, s) })

    assertFailsWith<IllegalStateException> { f.w.disposition(mob, player) }

    f.w.dispositionModifiers = emptyList()
    assertEquals(0, f.w.disposition(mob, player))
  }

  @Test
  fun `a faction adjuster asking the same actor's factions is refused`() {
    val f = createFixture()
    val player = f.player("Alice")
    f.w.factionAdjusters = listOf(FactionAdjuster { w, a, _ -> w.factionsOf(a) })

    assertFailsWith<IllegalStateException> { f.w.factionsOf(player) }

    f.w.factionAdjusters = emptyList()
    assertEquals(emptyList<Label>(), f.w.factionsOf(player))
  }
}
