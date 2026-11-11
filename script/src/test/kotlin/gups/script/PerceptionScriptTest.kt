package gups.script

import gups.core.Label
import gups.core.model.Channel
import gups.core.model.DoorState
import gups.core.model.ItemLocation
import gups.core.rules.RulesContext
import gups.core.world.spawnItem
import gups.script.testing.TestWorlds
import gups.script.testing.tempWorld
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerceptionScriptTest {
  private val rulesPkg = "rules/package.star" to TestWorlds.RULES_PACKAGE

  private fun zone(rooms: String, vararg more: Pair<String, String>) =
    mapOf("zones/z/package.star" to zonePkg(), "zones/z/rooms.star" to rooms) + more

  private fun load(files: Map<String, String>) =
    ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(listOf("zones/z", "rules"))

  /**
   * [source] with the top-level `def fn` (and a constant right above it) removed, and the removed
   * text.
   */
  private fun extract(source: String, fn: String): Pair<String, String> {
    val block = Regex("(?ms)^(?:[A-Z_]+ = [^\\n]*\\n\\n)?def $fn\\(.*?(?=^def |\\z)")
    val text = block.find(source)!!.value
    return source.replace(text, "") to text
  }

  @Test
  fun `rules exports may be spread over the files of the rules package`() {
    val (combat, perception) = extract(TestWorlds.rules(), "can_perceive")
    val f =
      ScriptFixture(
        zone("room(\"start\")") +
          mapOf(rulesPkg, "rules/combat.star" to combat, "rules/perception.star" to perception),
        load = listOf("zones/z", "rules"),
      )
    val p = f.player()
    val hall = p.room
    val heard =
      RulesContext(
        f.w,
        p,
        p,
        room = hall,
        sourceRoom = hall,
        channel = Channel.SOUND,
        event = "say",
        intensity = 1,
        cost = 0,
      )
    assertTrue(f.w.rules.canPerceive(heard))
    assertTrue(f.diagnostics().isEmpty(), f.diagnostics().toString())
  }

  @Test
  fun `an export defined in two files of the rules package fails the package, naming both`() {
    val (_, perception) = extract(TestWorlds.rules(), "can_perceive")
    val result =
      load(
        zone("room(\"start\")") +
          mapOf(
            rulesPkg,
            "rules/combat.star" to TestWorlds.rules(),
            "rules/perception.star" to perception,
          )
      )
    assertTrue("rules" in result.failed)
    val error = result.errors.single { it.kind == "rules" }
    assertContains(error.message, "can_perceive() is defined in more than one file")
    assertContains(error.message, "combat.star")
    assertContains(error.message, "perception.star")
  }

  @Test
  fun `an export missing from every file of the rules package fails it`() {
    val (combat, _) = extract(TestWorlds.rules(), "door_intensity")
    val result = load(zone("room(\"start\")") + mapOf(rulesPkg, "rules/combat.star" to combat))
    assertContains(result.report(), "the rules package must define door_intensity(...)")
  }

  @Test
  fun `a public helper in any rules file is rejected as a misspelled export`() {
    val result =
      load(
        zone("room(\"start\")") +
          mapOf(
            rulesPkg,
            "rules/combat.star" to TestWorlds.rules(),
            "rules/extra.star" to "def sence_cost(ctx):\n    return 1\n",
          )
      )
    assertContains(result.report(), "'sence_cost' is not a rules export")
  }

  @Test
  fun `ctx emit raises an event a mob hears with its channel, intensity and cost and a player is told of`() {
    val f =
      ScriptFixture(
        zone(
          """
              def _ring(ctx):
                  ctx.emit("sound", "bell", intensity = 3, text = "A bell tolls somewhere.")

              def _heard(ctx):
                  ctx.send(ctx.actor, "%s heard %s on %s: %d, cost %d, via %s" % (
                      ctx.self.name, ctx.event, ctx.channel, ctx.intensity, ctx.cost, ctx.via_exit))

              room("start", exits = [exit("north", ":den")], commands = {"ring": _ring})
              room("den", exits = [exit("south", ":start")])
              mob("wolf", on_perceive = _heard)
              """
        )
      )
    val ann = f.player("Ann")
    val bob = f.player("Bob", at = "//zones/z:den")
    f.mob("//zones/z:wolf", at = "//zones/z:den")
    f.input(ann, "ring")
    f.run(3)
    assertContains(f.out(ann), "wolf heard bell on sound: 3, cost 1, via south")
    assertContains(f.out(bob), "A bell tolls somewhere.")
  }

  @Test
  fun `ctx emit checks its channel and event, and an intensity of 0 emits nothing`() {
    val f =
      ScriptFixture(
        zone(
          """
              def _bad_channel(ctx):
                  ctx.emit("smell", "stink")

              def _blank(ctx):
                  ctx.emit("sound", "")

              def _silent(ctx):
                  ctx.emit("sound", "nothing", intensity = 0, text = "never seen")

              room("start", exits = [exit("north", ":den")], commands = {
                  "bad": _bad_channel, "blank": _blank, "silent": _silent})
              room("den", exits = [exit("south", ":start")])
              """
        )
      )
    val ann = f.player("Ann")
    val bob = f.player("Bob", at = "//zones/z:den")
    for (line in listOf("bad", "blank", "silent")) {
      f.input(ann, line)
      f.run(3)
    }
    assertFalse("never seen" in f.out(bob))
    val errors = f.diagnostics().joinToString { it.message }
    assertContains(errors, "emit(channel)")
    assertContains(errors, "emit(event)")
  }

  @Test
  fun `mob senses merge over the built-in defaults, and a sense can be raised or blinded`() {
    val f =
      ScriptFixture(
        zone(
          """
              room("start")
              mob("keen", senses = {"sound": 3})
              mob("blind", senses = {"sight": 0})
              mob("plain")
              """
        )
      )
    fun senses(label: String) =
      f.mob("//zones/z:$label").let { m ->
        m.effectiveSenses(f.w.schema.allChannels).mapKeys { it.key.name }
      }
    assertEquals(1, senses("plain")["sight"])
    assertEquals(
      mapOf("sight" to 1, "sound" to 3),
      senses("keen").filterKeys { it in listOf("sight", "sound") },
    )
    assertEquals(0, senses("blind")["sight"])
    assertEquals(1, senses("blind")["sound"])
    assertEquals(0, senses("plain")["spell"], "a declared channel starts at 0")
  }

  @Test
  fun `senses name declared channels and scores can't be negative`() {
    val unknown = load(zone("mob(\"m\", senses = {\"smell\": 1})"))
    assertContains(unknown.report(), "mob(senses)")
    val negative = load(zone("mob(\"m\", senses = {\"sound\": -1})"))
    assertContains(negative.report(), "must be 0 or more")
    val large = load(zone("mob(\"m\", senses = {\"sound\": 5})"))
    assertTrue(large.ok, large.report())
  }

  @Test
  fun `item and affect sense deltas show in ctx actor senses, and base senses stay put`() {
    val f =
      ScriptFixture(
        zone(
          """
              def _look(ctx):
                  ctx.send(ctx.actor, "sight=%d base=%d" % (ctx.actor.senses["sight"], ctx.actor.base.senses["sight"]))

              def _blind(ctx):
                  ctx.apply_affect(ctx.actor, "blinded", 100, sense_deltas = {"sight": -1})

              room("start", commands = {"look_sense": _look, "blind": _blind})
              item("lens", wear = "wield", sense_deltas = {"sight": 2})
              """
        )
      )
    val p = f.player()
    f.input(p, "look_sense")
    f.run(3)
    assertContains(f.out(p), "sight=1 base=1")
    f.w.spawnItem(Label("//zones/z:lens"), ItemLocation.Equipped(p.id, "wield"))
    f.input(p, "look_sense")
    f.run(3)
    assertContains(f.out(p), "sight=3 base=1")
    f.input(p, "blind")
    f.run(3)
    f.out(p)
    f.input(p, "look_sense")
    f.run(3)
    assertContains(f.out(p), "sight=2 base=1")
    assertEquals(-1, p.affects.single { it.name == "blinded" }.senseDeltas.getValue(Channel.SIGHT))
  }

  @Test
  fun `exit door is a door view with state, key and tags, and the exit has tags`() {
    val f =
      ScriptFixture(
        zone(
          """
              def _peek(ctx):
                  d = ctx.room.exit("north").door
                  s = ctx.room.exit("south")
                  ctx.send(ctx.actor, "%s|%s|%s|%s|%s" % (
                      d.state, d.key, d.tags, s.tags, s.door))

              room("start", commands = {"peek": _peek}, exits = [
                  exit("north", ":den", door = door(state = "locked", key = ":brass", tags = ["heavy", "creaky"])),
                  exit("south", ":den", tags = ["soundproof"]),
              ])
              room("den")
              item("brass", kind = "key")
              """
        )
      )
    val p = f.player()
    f.input(p, "peek")
    f.run(3)
    assertContains(f.out(p), "locked|//zones/z:brass|[\"creaky\", \"heavy\"]|[\"soundproof\"]|None")
    assertEquals(
      DoorState.LOCKED,
      f.w.room(Label("//zones/z:start")).exits.values.first().door!!.state,
    )
  }

  @Test
  fun `door tags must be valid tags`() {
    val bad =
      load(zone("room(\"r\", exits = [exit(\"north\", \":r\", door = door(tags = [\"Loud!\"]))])"))
    assertContains(bad.report(), "not a valid tag")
  }
}
