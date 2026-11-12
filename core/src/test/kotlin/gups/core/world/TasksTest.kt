package gups.core.world

import gups.core.model.Actor
import gups.core.model.Condition
import gups.core.model.Interrupt
import gups.core.model.Prototypes
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Records each callback a task makes. */
private class Recorder(
  val onDone: (World, Actor) -> Unit = { _, _ -> },
  val onStopped: (World, Actor) -> Unit = { _, _ -> },
) : TaskCallbacks {
  val done = mutableListOf<TaskInfo>()
  val stopped = mutableListOf<TaskEnd>()

  override fun done(world: World, actor: Actor, task: TaskInfo) {
    done += task
    onDone(world, actor)
  }

  override fun stopped(world: World, actor: Actor, task: TaskInfo, reason: TaskEnd) {
    stopped += reason
    onStopped(world, actor)
  }
}

/** [FixedRules] whose `interrupt_check` remembers the task it saw and answers [answer]. */
private class TaskInterruptRules(val answer: Boolean) : FixedRules() {
  var seen: TaskInfo? = null
  var skillSeen: Any? = "unset"

  override fun interruptCheck(c: RulesContext, amount: Int): Boolean {
    seen = c.task
    skillSeen = c.skill
    return answer
  }
}

class TasksTest {
  private fun spec(
    pulses: Int = 3,
    name: String = "whittling a stick",
    lag: Int = 4,
    interrupt: Interrupt = Interrupt(),
  ) = TaskSpec(name, pulses, lag, interrupt)

  private fun World.start(actor: Actor, rec: TaskCallbacks, spec: TaskSpec = spec()): String? =
    startTask(actor, spec, rec)

  @Test
  fun `a task completes after its pulses, calls done once, then applies its lag`() {
    val f = Fixture()
    val p = f.player("Ann")
    val rec = Recorder()
    assertNull(f.w.start(p, rec))
    assertTrue(p.isBusy)
    assertFalse(p.isCasting, "a task isn't a cast")
    val info = assertNotNull(f.w.taskOf(p))
    assertEquals("whittling a stick", info.name)
    assertEquals(info.startedAt + 3, info.endsAt)
    f.run(2)
    assertTrue(rec.done.isEmpty())
    f.run()
    assertEquals(1, rec.done.size)
    assertFalse(p.isBusy)
    assertEquals(4, p.waitState)
    f.run(5)
    assertEquals(1, rec.done.size)
    assertTrue(rec.stopped.isEmpty())
  }

  @Test
  fun `bad arguments are errors`() {
    val f = Fixture()
    val p = f.player("Ann")
    val rec = Recorder()
    assertFailsWith<IllegalArgumentException> { spec(pulses = 0) }
    assertFailsWith<IllegalArgumentException> { spec(lag = -1) }
    assertFailsWith<IllegalArgumentException> { spec(name = " ") }
    assertFailsWith<IllegalArgumentException> { spec(name = "a\nb") }
    assertFailsWith<IllegalArgumentException> { spec(name = "x".repeat(81)) }
    assertFailsWith<IllegalArgumentException> { spec(interrupt = Interrupt(damagePct = 0)) }
    assertFailsWith<IllegalArgumentException> { spec(interrupt = Interrupt(damagePct = 101)) }
    assertNull(f.w.start(p, rec, spec(name = "x".repeat(80))))
  }

  @Test
  fun `an actor that can't act or is busy is refused and nothing changes`() {
    val f = Fixture()
    val rec = Recorder()
    val gone = f.player("Gil")
    f.w.quit(gone)
    assertEquals("You can't do that right now.", f.w.start(gone, rec))

    val down = f.player("Dee")
    down.condition = Condition.INCAPACITATED
    assertEquals("You can't do that right now.", f.w.start(down, rec))

    val stunned = f.player("Stu")
    f.w.applyAffect(stunned, AffectSpec("stun", 5, flags = setOf("stunned")))
    assertEquals("You can't do that right now.", f.w.start(stunned, rec))

    val tasked = f.player("Tam")
    assertNull(f.w.start(tasked, rec))
    assertEquals("You're busy.", f.w.start(tasked, rec))

    val lagged = f.player("Lag")
    lagged.waitState = 2
    assertEquals("You're busy.", f.w.start(lagged, rec))
    assertFalse(lagged.isBusy)

    val caster = f.player("Cas")
    caster.skills[T.BOLT] = 100
    f.mob(T.DUMMY, index = 2)
    f.input(caster, "cast bolt dummy")
    f.run()
    assertTrue(caster.isCasting)
    assertEquals("You're busy.", f.w.start(caster, rec))
    assertTrue(rec.done.isEmpty() && rec.stopped.isEmpty())
  }

  @Test
  fun `a stun interrupts a task that allows it, with half lag`() {
    val f = Fixture()
    val p = f.player("Ann")
    val rec = Recorder()
    f.w.start(p, rec, spec(pulses = 5))
    f.out(p)
    f.w.applyAffect(p, AffectSpec("stun", 2, flags = setOf("stunned")))
    assertFalse(p.isBusy)
    assertEquals(listOf(TaskEnd.INTERRUPTED), rec.stopped)
    f.w.flushOutput()
    assertContains(f.out(p), "You are interrupted!")
    f.w.removeAffect(p, "stun")
    assertEquals(2, p.waitState)
  }

  @Test
  fun `a stun pauses a task that doesn't allow it`() {
    val f = Fixture()
    val p = f.player("Ann")
    val rec = Recorder()
    f.w.start(p, rec, spec(pulses = 3, interrupt = Interrupt(stun = false)))
    f.w.applyAffect(p, AffectSpec("stun", 3, flags = setOf("stunned")))
    assertTrue(p.isBusy)
    f.run(4)
    assertTrue(rec.done.isEmpty(), "the task didn't advance while stunned")
    f.run(4)
    assertEquals(1, rec.done.size)
    assertTrue(rec.stopped.isEmpty())
  }

  @Test
  fun `damage interrupts per interrupt_check, which sees the task`() {
    val rules = TaskInterruptRules(answer = true)
    val f = Fixture(rules = rules)
    val p = f.player("Ann")
    val rec = Recorder()
    f.w.start(p, rec, spec(interrupt = Interrupt(damagePct = 10)))
    f.hurt(p, 5)
    assertEquals("whittling a stick", rules.seen?.name)
    assertEquals(10, rules.seen?.interrupt?.damagePct)
    assertNull(rules.skillSeen)
    assertEquals(listOf(TaskEnd.INTERRUPTED), rec.stopped)
    assertEquals(2, p.waitState)
  }

  @Test
  fun `damage that interrupt_check declines leaves the task running`() {
    val f = Fixture(rules = TaskInterruptRules(answer = false))
    val p = f.player("Ann")
    val rec = Recorder()
    f.w.start(p, rec)
    f.hurt(p, 5)
    assertTrue(p.isBusy)
    f.run(3)
    assertEquals(1, rec.done.size)
  }

  @Test
  fun `stop cancels a task with no lag, and stopTask does too`() {
    val f = Fixture()
    val p = f.player("Ann")
    val rec = Recorder()
    f.w.start(p, rec)
    f.input(p, "stop")
    f.run()
    assertContains(f.out(p), "You stop whittling a stick.")
    assertEquals(listOf(TaskEnd.CANCELLED), rec.stopped)
    assertEquals(0, p.waitState)
    assertFalse(p.isBusy)

    assertFalse(f.w.stopTask(p), "nothing to stop")
    f.w.start(p, rec)
    assertTrue(f.w.stopTask(p))
    assertEquals(listOf(TaskEnd.CANCELLED, TaskEnd.CANCELLED), rec.stopped)
    assertEquals(0, p.waitState)
  }

  @Test
  fun `stopTask leaves a cast alone`() {
    val f = Fixture()
    val p = f.player("Ann")
    p.skills[T.BOLT] = 100
    f.mob(T.DUMMY, index = 2)
    f.input(p, "cast bolt dummy")
    f.run()
    assertFalse(f.w.stopTask(p))
    assertTrue(p.isCasting)
  }

  @Test
  fun `failed re-validation at the end is invalid, with half lag`() {
    val f = Fixture()
    val p = f.player("Ann")
    val rec = Recorder()
    f.w.start(p, rec, spec(pulses = 2))
    f.run()
    f.out(p)
    // A disabled actor's task pauses rather than ending, so the end comes from a condition set
    // behind the pause's back.
    val state = assertNotNull(p.activity)
    f.runTo(state.endsAt - 1)
    p.condition = Condition.INCAPACITATED
    f.run()
    f.w.advanceActivity(p, state)
    assertEquals(listOf(TaskEnd.INVALID), rec.stopped)
    assertTrue(rec.done.isEmpty())
    f.w.flushOutput()
    assertContains(f.out(p), "You can't finish whittling a stick.")
    assertEquals(2, p.waitState)
  }

  @Test
  fun `death ends a task as gone`() {
    val f = Fixture()
    val p = f.player("Ann")
    val rec = Recorder()
    f.w.start(p, rec)
    f.hurt(p, 1000)
    f.run()
    assertEquals(listOf(TaskEnd.GONE), rec.stopped)
    assertFalse(p.isBusy)
  }

  @Test
  fun `removal from the world ends a task as gone, while the actor is still in the world`() {
    val f = Fixture()
    val p = f.player("Ann")
    var inWorld = false
    val rec = Recorder(onStopped = { _, a -> inWorld = a.inWorld })
    f.w.start(p, rec)
    f.w.quit(p)
    assertEquals(listOf(TaskEnd.GONE), rec.stopped)
    assertTrue(inWorld, "the callback runs before the actor leaves")
  }

  @Test
  fun `a mob's task ends as gone when its room goes dormant`() {
    val f = Fixture(protos = C.protos(), bubble = true)
    val p = f.player("Ann", at = C.r(9))
    val m = f.w.spawnMob(C.RAT, f.w.room(C.r(9)))
    f.run()
    val rec = Recorder()
    assertNull(f.w.start(m, rec, spec(pulses = 50)))
    f.w.teleport(p, f.w.room(C.r(0)))
    f.run()
    assertTrue(f.w.room(C.r(9)).isDormant)
    assertEquals(listOf(TaskEnd.GONE), rec.stopped)
    assertFalse(m.isBusy)
  }

  @Test
  fun `a despawned mob's task ends as gone`() {
    val f = Fixture()
    val m = f.mob(T.GOBLIN)
    val rec = Recorder()
    f.w.start(m, rec, spec(pulses = 50))
    f.w.applyPrototypes(
      Prototypes(T.rooms, T.mobs(f.fs).filter { it.label != T.GOBLIN }, T.items, T.skills(f.fs))
    )
    assertEquals(listOf(TaskEnd.GONE), rec.stopped)
    assertFalse(m.inWorld)
  }

  @Test
  fun `a warmup that starts over a task begun in its veto cancels the task`() {
    val f = Fixture()
    val p = f.player("Ann")
    val rec = Recorder()
    val cast =
      object : Action {
        override val warmup = 5
        override val lag = 0

        override fun validate(w: World, actor: Actor): String? = null

        override fun veto(w: World, actor: Actor): String? {
          w.startTask(actor, TaskSpec("whittling a stick", 3), rec)
          return null
        }

        override fun resolve(w: World, actor: Actor): Int = 0
      }
    f.w.execute(p, cast)
    assertEquals(listOf(TaskEnd.CANCELLED), rec.stopped)
    assertSame(cast, p.activity?.action)
    f.run(6)
    assertTrue(rec.done.isEmpty())
  }

  @Test
  fun `the queue waits during a task`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.start(p, Recorder(), spec(lag = 0))
    f.input(p, "north")
    f.run(2)
    assertEquals(T.HALL, p.room.label, "the move waits for the task")
    f.run(3)
    assertTrue(p.room.label != T.HALL, "the move ran once the task ended")
  }

  @Test
  fun `a done callback can start another task`() {
    val f = Fixture()
    val p = f.player("Ann")
    val second = Recorder()
    var refusal: String? = "unset"
    val first = Recorder(onDone = { w, a -> refusal = w.start(a, second, spec(lag = 0)) })
    f.w.start(p, first, spec(lag = 0))
    f.run(3)
    assertNull(refusal)
    assertTrue(p.isBusy)
    f.run(3)
    assertEquals(1, second.done.size)
    assertEquals(emptyList(), f.w.checkInvariants())
  }

  @Test
  fun `a throwing callback leaves the world consistent`() {
    val f = Fixture(strict = false)
    val p = f.player("Ann")
    val rec = Recorder(onDone = { _, _ -> error("boom") })
    f.w.start(p, rec)
    f.run(3)
    assertFalse(p.isBusy)
    assertEquals(4, p.waitState)
    assertTrue(f.w.diagnostics.any { "boom" in it.message })
    assertEquals(emptyList(), f.w.checkInvariants())
  }

  @Test
  fun `a world without skills runs tasks and keeps its invariants`() {
    val f =
      Fixture(features = Features(Feature.entries.toSet() - Feature.SKILLS - Feature.LEVELING))
    val p = f.player("Ann")
    val rec = Recorder()
    assertNull(f.w.start(p, rec))
    f.run()
    assertEquals(emptyList(), f.w.checkInvariants())
    f.run(2)
    assertEquals(1, rec.done.size)
  }

  @Test
  fun `Char Status reports a task's progress, and casting stays null`() {
    val f = Fixture()
    val (p, _) = f.gmcpPlayer("Ann")
    f.w.start(p, Recorder(), spec(pulses = 4))
    f.run(2)
    @Suppress("UNCHECKED_CAST") val status = f.w.gmcpPayload(p, "Char.Status") as Map<String, Any?>
    assertEquals(mapOf("name" to "whittling a stick", "progress" to 50L), status["task"])
    assertNull(status["casting"])
    f.run(2)
    @Suppress("UNCHECKED_CAST") val after = f.w.gmcpPayload(p, "Char.Status") as Map<String, Any?>
    assertTrue("task" in after)
    assertNull(after["task"])
  }

  @Test
  fun `stat shows the task`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.start(p, Recorder(), spec(pulses = 4))
    val lines = f.w.statLines(p).joinToString("\n")
    assertContains(lines, "task whittling a stick until pulse ${f.w.taskOf(p)!!.endsAt}")
  }
}
