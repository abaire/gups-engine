package gups.script

import gups.core.Label
import gups.core.world.disposition
import gups.core.world.spawnItem
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandingScriptTest {
  private val zone =
    """
    faction("townsfolk", standing = {":goblins": -50})
    faction("goblins")

    def _test_standing(ctx):
        target = ctx.here("goblin")
        ctx.adjust_standing(target, ctx.actor, -7)
        ctx.adjust_standing(":townsfolk", ":goblins", -5)
        ctx.echo(ctx.actor.room, "standing_mob: " + str(ctx.standing(target, ctx.actor)))
        ctx.echo(ctx.actor.room, "standing_fac_fac: " + str(ctx.standing("//zones/z:townsfolk", "//zones/z:goblins")))
        ctx.echo(ctx.actor.room, "standing_fac_act: " + str(ctx.standing(":townsfolk", ctx.actor)))
        ctx.echo(ctx.actor.room, "disposition_mob: " + str(ctx.disposition(target, ctx.actor)))

    def _test_standing_personal_faction(ctx):
        ctx.standing(ctx.here("goblin"), ":townsfolk")

    def _test_standing_player_holder(ctx):
        ctx.standing(ctx.actor, ctx.here("goblin"))

    def _test_disposition_label(ctx):
        ctx.disposition(ctx.here("goblin"), ":townsfolk")

    def _test_factions_of(ctx):
        target = ctx.here("goblin")
        ctx.echo(ctx.actor.room, "mob_factions: " + str(ctx.factions_of(target)))
        ctx.echo(ctx.actor.room, "player_factions: " + str(ctx.factions_of(ctx.actor)))

    def _test_adjust_and_history(ctx):
        target = ctx.here("goblin")
        ctx.adjust_standing(target, ctx.actor, 20)
        h = ctx.standing_history(target, ctx.actor)
        ctx.echo(ctx.actor.room, "history: " + str(h.gained) + "/" + str(h.lost))

    def _test_clear_standing(ctx):
        target = ctx.here("goblin")
        ctx.adjust_standing(target, ctx.actor, 30)
        ctx.clear_standing(target, ctx.actor)
        ctx.echo(ctx.actor.room, "after_clear: " + str(ctx.standing(target, ctx.actor)))

    def _test_standing_event(ctx):
        target = ctx.here("goblin")
        item = ctx.actor.inventory()[0]
        ctx.standing_event(target, ctx.actor, "theft", amount = 10, item = item)

    def _test_builtin_event_fails(ctx):
        target = ctx.here("goblin")
        ctx.standing_event(target, ctx.actor, "attack")

    room("start", name = "A room", desc = "A room.", flags = ["dead_end_ok"],
         commands = {
             "test_standing": _test_standing,
             "test_personal_faction": _test_standing_personal_faction,
             "test_player_holder": _test_standing_player_holder,
             "test_disposition_label": _test_disposition_label,
             "test_factions": _test_factions_of,
             "test_adjust": _test_adjust_and_history,
             "test_clear": _test_clear_standing,
             "test_event": _test_standing_event,
             "test_builtin_event": _test_builtin_event_fails,
         })
    item("coin", name = "a gold coin", keywords = ["coin"])
    mob("goblin", name = "a goblin", keywords = ["goblin"], factions = ["//zones/z:goblins"])
    """
      .trimIndent()

  private fun fixture(
    rules: String? = null,
    module: String? = null,
    zoneCode: String = zone,
  ): ScriptFixture =
    ScriptFixture(
      buildMap {
        if (module != null) {
          put("MODULE.gups", module)
        }
        put("zones/z/package.star", zonePkg())
        put("zones/z/a.star", zoneCode)
        put("rules/package.star", TestWorlds.RULES_PACKAGE)
        put("rules/combat.star", TestWorlds.rules())
        if (rules != null) {
          put("rules/standing.star", rules)
        }
      }
    )

  @Test
  fun `ctx standing queries actors and factions`() {
    val f = fixture()
    val p = f.player("Alice")
    val mob = f.mob("//zones/z:goblin")
    f.run(1)

    f.input(p, "test_standing")
    f.run(1)
    val out = f.out(p)
    assertContains(out, "standing_mob: -7")
    assertContains(out, "standing_fac_fac: -5")
    assertContains(out, "standing_fac_act: 0")
    assertContains(out, "disposition_mob: -7")
  }

  @Test
  fun `ctx standing refuses a personal faction record, a player holder, and disposition a label`() {
    val f = fixture()
    val p = f.player("Alice")
    f.mob("//zones/z:goblin")
    f.run(1)

    for (cmd in listOf("test_personal_faction", "test_player_holder", "test_disposition_label")) {
      f.input(p, cmd)
      f.run(1)
    }
    val errors = f.diagnostics().map { it.message }
    assertTrue(errors.any { "cannot have a faction subject" in it }, errors.toString())
    assertTrue(errors.any { "a player has no personal standing" in it }, errors.toString())
    assertTrue(errors.any { "disposition(subject): expected an actor" in it }, errors.toString())
  }

  @Test
  fun `ctx factions_of returns factions list for mob and empty for player`() {
    val f = fixture()
    val p = f.player("Alice")
    val mob = f.mob("//zones/z:goblin")
    f.run(1)

    f.input(p, "test_factions")
    f.run(1)
    val out = f.out(p)
    assertContains(out, "mob_factions: [\"//zones/z:goblins\"]")
    assertContains(out, "player_factions: []")
  }

  @Test
  fun `ctx adjust_standing and standing_history`() {
    val f = fixture()
    val p = f.player("Alice")
    val mob = f.mob("//zones/z:goblin")
    f.run(1)

    f.input(p, "test_adjust")
    f.run(1)
    val out = f.out(p)
    assertContains(out, "history: 20/0")
  }

  @Test
  fun `ctx clear_standing removes records`() {
    val f = fixture()
    val p = f.player("Alice")
    val mob = f.mob("//zones/z:goblin")
    f.run(1)

    f.input(p, "test_clear")
    f.run(1)
    val out = f.out(p)
    assertContains(out, "after_clear: 0")
  }

  @Test
  fun `ctx standing_event raises event to standing_change`() {
    val rules =
      """
      def standing_change(ctx):
          if ctx.event == "theft" and ctx.amount == 10 and ctx.item != None:
              return [standing_delta(ctx.actor, ctx.target, -35)]
          return []
      """
        .trimIndent()
    val f = fixture(rules = rules)
    val p = f.player("Alice")
    val mob = f.mob("//zones/z:goblin")
    f.w.spawnItem(Label("//zones/z:coin"), gups.core.model.ItemLocation.Carried(p.id))
    f.run(1)

    f.input(p, "test_event")
    f.run(1)
    assertEquals(-35, f.w.disposition(mob, p))
  }

  @Test
  fun `ctx standing_event with built-in event name fails`() {
    val f = fixture()
    val p = f.player("Alice")
    val mob = f.mob("//zones/z:goblin")
    f.run(1)

    f.input(p, "test_builtin_event")
    f.run(1)
    assertTrue(f.diagnostics().any { "is a built-in event" in it.message })
  }

  @Test
  fun `standing verbs and queries refuse when standing feature is off`() {
    val module = TestWorlds.module("features = [\"combat\", \"track\", \"skills\", \"leveling\"],")
    val zoneCode =
      """
      def _test(ctx):
          ctx.standing(ctx.actor, ctx.actor)

      room("start", name = "A room", desc = "A room.", flags = ["dead_end_ok"],
           commands = {"test": _test})
      """
        .trimIndent()
    val f = fixture(module = module, zoneCode = zoneCode)
    val p = f.player("Alice")
    f.run(1)

    f.input(p, "test")
    f.run(1)
    assertTrue(f.diagnostics().any { "ctx.standing() needs the standing feature" in it.message })
  }

  @Test
  fun `behavior standing thresholds validate range in declarations`() {
    val invalidAggressive =
      """
      mob("bad_orc", vitals = vitals(hp = 10), behaviors = [aggressive(disposition_below = 105)])
      room("start", name = "A room", desc = "A room.", flags = ["dead_end_ok"])
      """
        .trimIndent()
    val f1 = fixture(zoneCode = invalidAggressive)
    val ex1 = kotlin.test.assertFailsWith<IllegalStateException> { f1.run(1) }
    assertTrue("aggressive(disposition_below): must be in -100..100" in (ex1.message ?: ""))

    val invalidAssist =
      """
      mob("bad_guard", vitals = vitals(hp = 10), behaviors = [assist("mobs", disposition_above = -105)])
      room("start", name = "A room", desc = "A room.", flags = ["dead_end_ok"])
      """
        .trimIndent()
    val f2 = fixture(zoneCode = invalidAssist)
    val ex2 = kotlin.test.assertFailsWith<IllegalStateException> { f2.run(1) }
    assertTrue("assist(disposition_above): must be in -100..100" in (ex2.message ?: ""))
  }
}
