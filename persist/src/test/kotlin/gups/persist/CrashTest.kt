package gups.persist

import gups.core.Label
import gups.core.fixtures.tempDb
import gups.core.model.ItemLocation
import gups.core.world.checkInvariants
import gups.core.world.moveItem
import gups.core.world.quit
import gups.core.world.spawnItem
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val NAMES = listOf("Ann", "Bob", "Cy", "Di")
private const val GEMS = 20

/**
 * A world that keeps shuffling a fixed set of gems between players, bags and the ground,
 * checkpointing all the time. Run as a child JVM by [CrashTest], which kills it with SIGKILL.
 */
fun main(args: Array<String>) {
  val file = Path.of(args[0])
  val rng = Random(args[1].toLong())
  val pw = PersistWorld(file)
  val w = pw.w
  val online = NAMES.map { pw.login(it) }.toMutableList()
  if (w.allEntities.none { it.protoLabel == P.GEM }) {
    val hall = w.room(P.HALL)
    val bags =
      listOf(
        w.spawnItem(P.BAG, ItemLocation.Carried(online[0].id)),
        w.spawnItem(P.BAG, ItemLocation.InRoom(hall.id, 2)),
      )
    repeat(GEMS) { i ->
      w.spawnItem(
        P.GEM,
        if (i % 2 == 0) ItemLocation.Contained(bags[i % 2].id)
        else ItemLocation.Carried(online[i % 4].id),
      )
    }
    pw.save()
  }
  println("READY")
  System.out.flush()
  var step = 0L
  while (true) {
    step++
    val items =
      w.allEntities.filterIsInstance<gups.core.model.Item>().filter {
        it.protoLabel == P.GEM || it.protoLabel == P.BAG
      }
    val item = items.random(rng)
    val to =
      when (rng.nextInt(4)) {
        0 -> online.randomOrNull(rng)?.let { ItemLocation.Carried(it.id) }
        1 ->
          items
            .filter { it.protoLabel == P.BAG }
            .randomOrNull(rng)
            ?.let { ItemLocation.Contained(it.id) }
        2 ->
          ItemLocation.InRoom(w.room(if (rng.nextBoolean()) P.HALL else P.VAULT).id, rng.nextInt(3))
        else -> null
      }
    if (to != null) runCatching { w.moveItem(item, to) }
    if (rng.nextInt(50) == 0 && online.isNotEmpty()) {
      val p = online.random(rng)
      w.quit(p)
      online.remove(p)
    }
    if (rng.nextInt(50) == 0)
      NAMES.firstOrNull { n -> online.none { it.name == n } }?.let { online += pw.login(it) }
    w.runPulse()
    if (step % 3 == 0L) pw.persistence.checkpoint(w, "crash-test", immediate = rng.nextBoolean())
  }
}

class CrashTest {
  private fun child(file: Path, seed: Long): Process {
    val java = ProcessHandle.current().info().command().orElse("java")
    return ProcessBuilder(
        java,
        "-XX:TieredStopAtLevel=1",
        "-Xshare:auto",
        "-cp",
        System.getProperty("java.class.path"),
        "gups.persist.CrashTestKt",
        file.toString(),
        seed.toString(),
      )
      .redirectErrorStream(true)
      .start()
  }

  /**
   * Kill -9 during checkpoint writes; every restart opens cleanly and keeps every item exactly
   * once.
   */
  @Test
  fun `killing the server mid-write never loses or duplicates an item`() {
    val iterations = System.getProperty("gups.crashIterations")?.toInt() ?: 3
    val file = tempDb()
    val rng = Random(42)
    repeat(iterations) { i ->
      val proc = child(file, i.toLong())
      val reader = proc.inputStream.bufferedReader()
      val ready = reader.readLine()
      assertEquals("READY", ready, "child failed to start: $ready\n${reader.readText()}")
      Thread.sleep(rng.nextLong(20, 400))
      proc.destroyForcibly()
      assertTrue(proc.waitFor(10, TimeUnit.SECONDS))

      val pw = PersistWorld(file)
      for (n in NAMES) pw.login(n)
      val gems = pw.w.allEntities.count { it.protoLabel == P.GEM }
      assertEquals(GEMS, gems, "iteration $i: gems lost or duplicated")
      assertEquals(emptyList(), pw.w.checkInvariants(), "iteration $i")
      assertTrue(Label("//t:hall") in pw.w.protos.rooms)
      pw.close()
    }
  }
}
