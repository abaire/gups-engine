package gups.core.loop

import gups.core.ClientEvent
import gups.core.ClientGmcpEvent
import gups.core.ClientInputEvent
import gups.core.PULSE_MS
import gups.core.WorldControlEvent
import gups.core.WorldInbox
import gups.core.world.World
import gups.core.world.checkOverruns
import gups.core.world.handle
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * Pulse-boundary bookkeeping. A pulse that finishes after the next boundary does not trigger
 * catch-up pulses: the timer skips to the next future boundary and reports how many it skipped, so
 * a lag spike slows game time instead of making it lurch.
 */
class PulseTimer(val pulseNanos: Long, startNanos: Long) {
  /** When the next pulse is due. */
  var nextBoundary: Long = startNanos + pulseNanos
    private set

  /** Records that the pulse due at [nextBoundary] finished at [nowNanos]; returns the overruns. */
  fun pulseFinished(nowNanos: Long): Long {
    val following = nextBoundary + pulseNanos
    if (nowNanos <= following) {
      nextBoundary = following
      return 0
    }
    val skipped = (nowNanos - following + pulseNanos - 1) / pulseNanos
    nextBoundary = following + skipped * pulseNanos
    return skipped
  }
}

/**
 * Drives a [World] in real time: between pulse boundaries it applies input and control events as
 * they arrive; at each boundary it runs one pulse. Run it on [worldDispatcher] so every world
 * mutation happens on one thread.
 */
class WorldLoop(
  private val world: World,
  private val pulseNanos: Long = PULSE_MS * 1_000_000,
  private val nanoTime: () -> Long = System::nanoTime,
) : WorldInbox {
  private val input = Channel<ClientEvent>(Channel.UNLIMITED)
  private val control = Channel<WorldControlEvent>(Channel.UNLIMITED)
  private val wake = Channel<Unit>(Channel.CONFLATED)
  private val waiting = AtomicInteger()

  init {
    world.queueDepth = waiting::get
  }

  @Volatile private var stopped = false

  /** Boundaries skipped because a pulse ran long (`gups_pulse_overruns_total`). */
  @Volatile
  var overruns: Long = 0
    private set

  /** Safe to call from any thread. */
  override fun submit(event: ClientInputEvent) {
    if (input.trySend(event).isSuccess) waiting.incrementAndGet()
  }

  /** Safe to call from any thread. */
  override fun submit(event: ClientGmcpEvent) {
    if (input.trySend(event).isSuccess) waiting.incrementAndGet()
  }

  /** Safe to call from any thread. */
  override fun submit(event: WorldControlEvent) {
    control.trySend(event)
  }

  /** Makes [run] return after the current pulse. */
  fun stop() {
    stopped = true
    wake.trySend(Unit)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  suspend fun run() {
    val timer = PulseTimer(pulseNanos, nanoTime())
    while (!stopped && !world.shutdownRequested) {
      val wait = timer.nextBoundary - nanoTime()
      if (wait > 0) {
        // The timeout is a clause of the select, not a withTimeout around it: cancelling
        // a select that has just taken an event from a channel drops that event.
        select {
          wake.onReceive {}
          control.onReceive { world.handle(it, nanoTime()) }
          input.onReceive {
            waiting.decrementAndGet()
            when (it) {
              is ClientInputEvent -> world.handle(it)
              is ClientGmcpEvent -> world.handle(it)
            }
          }
          onTimeout(wait.nanoseconds) {}
        }
        continue
      }
      val started = nanoTime()
      world.runPulse()
      val finished = nanoTime()
      val skipped = timer.pulseFinished(finished)
      overruns += skipped
      world.pulseStats.record(finished - started, finished - started > pulseNanos)
      world.onPulse?.invoke(finished - started)
      world.checkOverruns()
    }
  }

  companion object {
    /**
     * The single-threaded world dispatcher. It is one process-wide view, so every world in a
     * process shares the same thread.
     */
    val worldDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
  }
}
