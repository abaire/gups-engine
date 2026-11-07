package gups.script

import gups.core.Label
import gups.core.world.StandingEvent
import gups.core.world.disposition
import gups.core.world.factionsOf
import gups.core.world.raiseStandingEvent
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StandingRulesTest {
  private val zone =
    """
    faction("townsfolk", standing = {":goblins": -50})
    faction("goblins")

    def _call_standing_delta(ctx):
        standing_delta(ctx.actor, ctx.target, 10)

    room("start", name = "A room", desc = "A room.", flags = ["dead_end_ok"],
         commands = {"test_delta": _call_standing_delta})
    mob("goblin", name = "a goblin", keywords = ["goblin"], factions = ["//zones/z:goblins"])
    mob("guard", name = "a guard", keywords = ["guard"], factions = ["//zones/z:townsfolk"])
    """
      .trimIndent()

  private fun fixture(rules: String? = null): ScriptFixture =
    ScriptFixture(
      buildMap {
        put("zones/z/package.star", zonePkg())
        put("zones/z/a.star", zone)
        put("rules/package.star", TestWorlds.RULES_PACKAGE)
        put("rules/combat.star", TestWorlds.rules())
        if (rules != null) {
          put("rules/standing.star", rules)
        }
      }
    )

  @Test
  fun `standing export overrides default and has disposition_parts`() {
    val export =
      """
      def disposition(ctx):
          # ctx.disposition_parts fields are available
          if ctx.disposition_parts.total < 0:
              return -99
          return 42
      """
        .trimIndent()
    val f = fixture(export)
    val ann = f.player()
    val goblin = f.w.spawnMob(Label("//zones/z:goblin"), ann.room)
    val guard = f.w.spawnMob(Label("//zones/z:guard"), ann.room)

    // guard -> goblin has declared -50, so total is < 0 -> returns -99
    assertEquals(-99, f.w.disposition(guard, goblin))
    // goblin -> guard has declared 0 -> returns 42
    assertEquals(42, f.w.disposition(goblin, guard))
  }

  @Test
  fun `standing export clamps return value`() {
    val export =
      """
      def disposition(ctx):
          return 500
      """
        .trimIndent()
    val f = fixture(export)
    val ann = f.player()
    val goblin = f.w.spawnMob(Label("//zones/z:goblin"), ann.room)

    assertEquals(100, f.w.disposition(goblin, ann))
  }

  @Test
  fun `standing export failure keeps default`() {
    val export =
      """
      def disposition(ctx):
          if ctx.target.name == "Ann":
              return "not an int"
          return 0
      """
        .trimIndent()
    val f = fixture(export)
    val ann = f.player()
    val goblin = f.w.spawnMob(Label("//zones/z:goblin"), ann.room)

    // Falls back to default (0 for goblin -> ann)
    assertEquals(0, f.w.disposition(goblin, ann))
    assertTrue(f.diagnostics("script_error").isNotEmpty())
  }

  @Test
  fun `factions export overrides actor factions`() {
    val export =
      """
      def factions(ctx):
          if ctx.actor.name == "a goblin":
              return ["//zones/z:townsfolk"]
          return ["//zones/z:goblins"]
      """
        .trimIndent()
    val f = fixture(export)
    val ann = f.player()
    val goblin = f.w.spawnMob(Label("//zones/z:goblin"), ann.room)

    assertEquals(listOf(Label("//zones/z:townsfolk")), f.w.factionsOf(goblin))
    assertEquals(listOf(Label("//zones/z:goblins")), f.w.factionsOf(ann))
  }

  @Test
  fun `factions export with unknown label logs diagnostic and keeps default`() {
    val export =
      """
      def factions(ctx):
          if ctx.actor.name == "Ann":
              return ["//zones/z:unknown_faction"]
          return ["//zones/z:goblins"]
      """
        .trimIndent()
    val f = fixture(export)
    val ann = f.player()

    // Falls back to default (empty for player)
    assertEquals(emptyList(), f.w.factionsOf(ann))
    assertTrue(f.diagnostics("script_error").isNotEmpty())
  }

  @Test
  fun `factions export asking factions_of its own actor fails the dry run`() {
    val export =
      """
      def factions(ctx):
          return ctx.factions_of(ctx.actor)
      """
        .trimIndent()
    val e = assertFailsWith<IllegalStateException> { fixture(export).player() }
    assertContains(e.message!!, "still being worked out")
  }

  @Test
  fun `factions export asking factions_of its own actor keeps the default`() {
    val export =
      """
      def factions(ctx):
          if ctx.actor.name == "Ann":
              return ctx.factions_of(ctx.actor)
          return []
      """
        .trimIndent()
    val f = fixture(export)
    val ann = f.player()

    assertEquals(emptyList(), f.w.factionsOf(ann))
    assertTrue(f.diagnostics("script_error").isNotEmpty())
  }

  @Test
  fun `standing_change export returns deltas`() {
    val export =
      """
      def standing_change(ctx):
          if ctx.event == "attack":
              return [standing_delta(ctx.actor, ctx.target, -20)]
          return []
      """
        .trimIndent()
    val f = fixture(export)
    val ann = f.player()
    val goblin = f.w.spawnMob(Label("//zones/z:goblin"), ann.room)

    f.w.raiseStandingEvent(StandingEvent("attack", affected = goblin, actor = ann))
    assertEquals(-20, f.w.disposition(goblin, ann))
  }

  @Test
  fun `standing_change returning over 32 deltas falls back to default deltas`() {
    val export =
      """
      def standing_change(ctx):
          if ctx.target.name == "Ann":
              return [standing_delta(ctx.actor, ctx.target, -1) for _ in range(33)]
          return []
      """
        .trimIndent()
    val f = fixture(export)
    val ann = f.player()
    val goblin = f.w.spawnMob(Label("//zones/z:goblin"), ann.room)

    f.w.raiseStandingEvent(StandingEvent("attack", affected = goblin, actor = ann))
    // Default delta for attack is -50
    assertEquals(-50, f.w.disposition(goblin, ann))
    assertTrue(f.diagnostics("script_error").isNotEmpty())
  }

  @Test
  fun `standing_delta outside standing_change fails`() {
    val f = fixture()
    val ann = f.player()
    f.input(ann, "test_delta")
    f.run(1)
    val errors = f.diagnostics("script_error").map { it.message }
    assertTrue(errors.any { "can only be used in rules.standing_change" in it }, errors.toString())
  }

  @Test
  fun `standing_delta player as personal holder fails`() {
    val export =
      """
      def standing_change(ctx):
          if ctx.target.name == "Ann":
              return [standing_delta(ctx.target, ctx.actor, 10)]
          return []
      """
        .trimIndent()
    val f = fixture(export)
    val ann = f.player()
    val goblin = f.w.spawnMob(Label("//zones/z:goblin"), ann.room)

    f.w.raiseStandingEvent(StandingEvent("attack", affected = goblin, actor = ann))
    assertTrue(f.diagnostics("script_error").isNotEmpty())
  }

  @Test
  fun `rules ctx standing reads a record in standing_change`() {
    val export =
      """
      def standing_change(ctx):
          if ctx.event == "heal":
              room = 25 - ctx.standing(ctx.actor, ctx.target)
              return [standing_delta(ctx.actor, ctx.target, min(10, room))]
          return []
      """
        .trimIndent()
    val f = fixture(export)
    val ann = f.player()
    val goblin = f.w.spawnMob(Label("//zones/z:goblin"), ann.room)

    repeat(4) { f.w.raiseStandingEvent(StandingEvent("heal", affected = goblin, actor = ann)) }
    assertEquals(25, f.w.disposition(goblin, ann))
  }

  @Test
  fun `rules ctx factions_of and standing_history`() {
    val export =
      """
      def disposition(ctx):
          factions = ctx.factions_of(ctx.actor)
          hist = ctx.standing_history(ctx.actor, ctx.target) if ctx.actor.name == "a goblin" else struct(gained = 0, lost = 0)
          return len(factions) + hist.gained
      """
        .trimIndent()
    val f = fixture(export)
    val ann = f.player()
    val goblin = f.w.spawnMob(Label("//zones/z:goblin"), ann.room)

    assertEquals(1, f.w.disposition(goblin, ann))
  }
}
