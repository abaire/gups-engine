package gups.script

import gups.core.Label
import gups.core.model.Effect
import gups.core.model.ItemLocation
import gups.core.model.ProcTarget
import gups.core.model.ProcTrigger
import gups.core.world.spawnItem
import gups.script.testing.TestWorlds
import gups.script.testing.tempWorld
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcScriptTest {
  private fun zonePkg(path: String = "zones/z") =
    "package(title = \"Z\", api_version = 1, kind = \"zone\")"

  private fun zone(rooms: String, vararg more: Pair<String, String>) =
    mapOf("zones/z/package.star" to zonePkg(), "zones/z/content.star" to rooms) + more

  private fun load(files: Map<String, String>, vararg pkgs: String = arrayOf("zones/z")) =
    ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(pkgs.toList())

  @Test
  fun `item with proc loads into ItemProto procs with weight 1 and null target`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          item("blade", wear = "wield", dice = "1d4", procs = [
            proc(on = "hit", effect = damage(dice = "1d6", type = DAMAGE.fire))
          ])
          """
        )
      )
    val proto = f.w.protos.item(Label("//zones/z:blade"))
    assertEquals(1, proto.procs.size)
    val p = proto.procs[0]
    assertEquals(ProcTrigger.HIT, p.on)
    assertEquals(100, p.chance)
    assertEquals(1, p.outcomes.size)
    val outcome = p.outcomes[0]
    assertEquals(100, outcome.weight)
    assertNull(outcome.target)
    assertTrue(outcome.effect is Effect.Damage)
  }

  @Test
  fun `outcome table loads in order with weights`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          item("wand", wear = "wield", dice = "1d4", procs = [
            proc(on = "hit", outcomes = [
              outcome(10, effect = damage(dice = "1d6", type = DAMAGE.fire)),
              outcome(40, effect = heal(dice = "1d4", vital = "hp"), target = "self"),
              outcome(30, effect = stun(2), message = "A blinding flash!"),
            ])
          ])
          """
        )
      )
    val proto = f.w.protos.item(Label("//zones/z:wand"))
    val p = proto.procs[0]
    assertEquals(3, p.outcomes.size)
    assertEquals(10, p.outcomes[0].weight)
    assertNull(p.outcomes[0].target)
    assertEquals(40, p.outcomes[1].weight)
    assertEquals(ProcTarget.SELF, p.outcomes[1].target)
    assertEquals(30, p.outcomes[2].weight)
    assertEquals("A blinding flash!", p.outcomes[2].message)
  }

  @Test
  fun `proc with skill resolves label in declaring package`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          skill("fireball", effect = damage(dice = "2d6", type = DAMAGE.fire))
          item("staff", wear = "wield", dice = "1d4", procs = [
            proc(on = "hit", skill = ":fireball")
          ])
          """
        )
      )
    val proto = f.w.protos.item(Label("//zones/z:staff"))
    val outcome = proto.procs[0].outcomes[0]
    assertEquals(Label("//zones/z:fireball"), outcome.skill)
  }

  @Test
  fun `proc load errors assert their messages`() {
    fun check(code: String, expectedMsg: String) {
      val r = load(zone("room(\"start\")\n${code.trimIndent()}"))
      assertFalse(r.ok, "Expected error for: $code")
      assertContains(r.report(), expectedMsg)
    }

    check(
      "item(\"a\", wear = \"wield\", procs = [proc(on = \"jump\", effect = damage(dice = \"1d1\", type = DAMAGE.fire))])",
      "'jump' is not one of hit, struck, avoid",
    )
    check(
      "item(\"a\", wear = \"wield\", procs = [proc(on = \"hit\", chance = 1.5, effect = damage(dice = \"1d1\", type = DAMAGE.fire))])",
      "proc(chance): must be a whole percent from 0 to 100, not a fraction (write 25 for 0.25)",
    )
    check(
      """
      item("a", wear = "wield", procs = [proc(on = "hit", outcomes = [
        outcome(70, effect = damage(dice = "1d1", type = DAMAGE.fire)),
        outcome(50, effect = damage(dice = "1d1", type = DAMAGE.fire)),
      ])])
      """,
      "proc(outcomes): weights add up to more than 100",
    )
    check(
      "item(\"a\", wear = \"wield\", procs = [proc(on = \"hit\", skill = \":x\", outcomes = [outcome(50)])])",
      "proc(outcomes): can't be combined with skill, effect, target or message",
    )
    check(
      "item(\"a\", wear = \"wield\", procs = [proc(on = \"hit\")])",
      "proc(): give one of skill, effect or outcomes",
    )
    check(
      """
      skill("s")
      item("a", wear = "wield", procs = [proc(on = "hit", skill = ":s", effect = damage(dice = "1d1", type = DAMAGE.fire))])
      """,
      "proc(): give one of skill, effect or outcomes",
    )
    check(
      "item(\"a\", wear = \"wield\", procs = [proc(on = \"hit\", effect = summon(\":x\"))])",
      "proc(effect): summon can't be used in a proc",
    )
    check(
      "item(\"a\", wear = \"wield\", procs = [proc(on = \"hit\", effect = apply_affect(\"buff\", None))])",
      "proc(effect): apply_affect in a proc needs a duration and no applies",
    )
    check(
      "item(\"a\", wear = \"wield\", procs = [proc(on = \"hit\", effect = apply_affect(\"buff\", 10, add_tags = [\"lit\"]))])",
      "proc(effect): apply_affect(add_tags) is only for room skills",
    )
    check(
      "item(\"a\", wear = \"wield\", procs = [proc(on = \"hit\", effect = apply_affect(\"buff\", 10, remove_tags = [\"dark\"]))])",
      "proc(effect): apply_affect(remove_tags) is only for room skills",
    )
    check(
      "item(\"a\", procs = [proc(on = \"hit\", effect = damage(dice = \"1d1\", type = DAMAGE.fire))])",
      "needs a wear slot",
    )
    check(
      "item(\"a\", wear = \"wield\", procs = [\"not_a_proc\"])",
      "item(procs): element 1 must be a proc(...) value",
    )
    check(
      "item(\"a\", wear = \"wield\", procs = [proc(on = \"hit\", skill = \":unknown_skill\")])",
      "unknown_skill",
    )
    check(
      """
      skill("s")
      item("a", wear = "wield", procs = [proc(on = "hit", outcomes = [
        outcome(50, skill = ":s", effect = damage(dice = "1d1", type = DAMAGE.fire)),
      ])])
      """,
      "outcome(): give skill or effect, not both",
    )
    check(
      """
      item("a", affects = [item_affect("glow", procs = [
        proc(on = "hit", effect = damage(dice = "1d1", type = DAMAGE.fire)),
      ])])
      """,
      "a worn item_affect with procs needs a wear slot",
    )
  }

  @Test
  fun `procs in world without combat fails at load`() {
    val files =
      zone(
        "room(\"start\")\nitem(\"a\", wear = \"wield\", procs = [proc(on = \"hit\", effect = damage(dice = \"1d1\", type = DAMAGE.fire))])"
      ) + ("MODULE.gups" to TestWorlds.module("features = [\"skills\"]"))
    val r = load(files)
    assertFalse(r.ok)
    assertContains(r.report(), "procs needs the combat feature")
  }

  @Test
  fun `skill proc in world without skills fails at load`() {
    val files =
      zone(
        "room(\"start\")\nitem(\"a\", wear = \"wield\", procs = [proc(on = \"hit\", skill = \":fireball\")])"
      ) + ("MODULE.gups" to TestWorlds.module("features = [\"combat\"]"))
    val r = load(files)
    assertFalse(r.ok)
    assertContains(r.report(), "proc(skill) needs the skills feature")
  }

  @Test
  fun `end to end ctx apply_affect with procs fires on landed swing`() {
    val files =
      zone(
        """
        room("start")
        item("sword", wear = "wield", dice = "1d4")

        def on_init(ctx):
            ctx.apply_affect(
                ctx.actor,
                "flame_blade",
                50,
                procs = [proc(on = "hit", effect = damage(dice = "1d1", type = DAMAGE.fire))],
            )

        mob("dummy", vitals = vitals(hp = 100), before_attack = on_init)
        """
      )
    val f = ScriptFixture(files)
    val p = f.player()
    val dummy = f.mob("//zones/z:dummy")
    f.w.spawnItem(Label("//zones/z:sword"), ItemLocation.Equipped(p.id, "wield"))

    f.input(p, "kill dummy")
    f.run(16)
    val out = f.out(p)
    assertContains(out, "Power surges from flame blade!")
  }

  @Test
  fun `end to end item_affect with procs fires while worn`() {
    val files =
      zone(
        """
        room("start")
        item("sword", wear = "wield", dice = "1d4", affects = [
            item_affect("frost", procs = [proc(on = "hit", effect = damage(dice = "1d1", type = DAMAGE.fire))])
        ])
        mob("dummy", vitals = vitals(hp = 100))
        """
      )
    val f = ScriptFixture(files)
    val p = f.player()
    val dummy = f.mob("//zones/z:dummy")
    f.w.spawnItem(Label("//zones/z:sword"), ItemLocation.Equipped(p.id, "wield"))

    f.input(p, "kill dummy")
    f.run(16)
    val out = f.out(p)
    assertContains(out, "Power surges from sword!")
  }
}
