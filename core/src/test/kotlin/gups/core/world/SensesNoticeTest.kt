package gups.core.world

import gups.core.Label
import gups.core.model.Channel
import gups.core.model.MobProto
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensesNoticeTest {
  private val spell = Channel("spell")

  private class RecordingPerceiveRules(private val decide: (RulesContext) -> Boolean) :
    FixedRules() {
    val calls = mutableListOf<Pair<Channel, String>>()

    override fun canPerceive(c: RulesContext): Boolean {
      calls += (c.channel ?: Channel("?")) to (c.event ?: "?")
      return decide(c)
    }
  }

  private fun mob(
    name: String,
    senses: Map<Channel, Int> = emptyMap(),
    vararg behaviors: Behavior = arrayOf(Aggressive(ActorFilter("players"))),
  ) =
    MobProto(
      Label("//t:$name"),
      name,
      listOf(name),
      vitals = mapOf("hp" to 40),
      senses = senses,
      behaviors = behaviors.toList(),
    )

  private fun fixture(vararg mobs: MobProto, rules: RecordingPerceiveRules) =
    Fixture(rules = rules, protos = T.protos(extraMobs = mobs.toList()))

  @Test
  fun `default mob whose stub refuses sight and passes sound notices on sound after two calls`() {
    val rules = RecordingPerceiveRules { c -> c.channel == Channel.SOUND }
    val f = fixture(mob("guard"), rules = rules)
    val guard = f.mob(Label("//t:guard"))
    val p = f.player("Ann")
    assertEquals(Channel.SOUND, f.w.sensedChannel(guard, p, "notice"))
    assertEquals(listOf(Channel.SIGHT to "notice", Channel.SOUND to "notice"), rules.calls)
    rules.calls.clear()
    f.runTo(3)
    assertTrue(p.id in guard.fighting)
    assertEquals(listOf(Channel.SIGHT to "notice", Channel.SOUND to "notice"), rules.calls)
  }

  @Test
  fun `mob with sight 0 makes no sight call`() {
    val rules = RecordingPerceiveRules { true }
    val f = fixture(mob("listener", senses = mapOf(Channel.SIGHT to 0)), rules = rules)
    val listener = f.mob(Label("//t:listener"))
    val p = f.player("Ann")
    f.runTo(3)
    assertTrue(p.id in listener.fighting)
    assertEquals(listOf(Channel.SOUND to "notice"), rules.calls)
  }

  @Test
  fun `mob with every sense 0 makes no call and notices nothing`() {
    val rules = RecordingPerceiveRules { true }
    val f =
      fixture(mob("stone", senses = mapOf(Channel.SIGHT to 0, Channel.SOUND to 0)), rules = rules)
    val stone = f.mob(Label("//t:stone"))
    val p = f.player("Ann")
    assertNull(f.w.sensedChannel(stone, p, "notice"))
    f.runTo(3)
    assertFalse(stone.isEngaged)
    assertEquals(emptyList(), rules.calls)
  }

  @Test
  fun `mob blinded by an affect makes no sight call while the affect lasts`() {
    val rules = RecordingPerceiveRules { c -> c.channel == Channel.SIGHT }
    val f = fixture(mob("watcher"), rules = rules)
    val watcher = f.mob(Label("//t:watcher"))
    val p = f.player("Ann")
    f.w.applyAffect(watcher, "blind", 10, senseDeltas = mapOf(Channel.SIGHT to -1))
    f.runTo(3)
    assertFalse(watcher.isEngaged)
    assertEquals(listOf(Channel.SOUND to "notice"), rules.calls)

    rules.calls.clear()
    f.runTo(15)
    assertTrue(p.id in watcher.fighting)
    assertTrue((Channel.SIGHT to "notice") in rules.calls)
  }

  @Test
  fun `declared channel the mob senses is asked after sight and sound following perceptionOrder`() {
    val rules = RecordingPerceiveRules { c -> c.channel == spell }
    val f = fixture(mob("wisp", senses = mapOf(spell to 5)), rules = rules)
    val wisp = f.mob(Label("//t:wisp"))
    val p = f.player("Ann")
    f.runTo(3)
    assertTrue(p.id in wisp.fighting)
    assertEquals(
      listOf(Channel.SIGHT to "notice", Channel.SOUND to "notice", spell to "notice"),
      rules.calls,
    )
  }
}
