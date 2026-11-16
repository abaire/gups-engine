package gups.script

import kotlin.test.Test
import kotlin.test.assertEquals

class LinterPerCrossingTest {
  @Test
  fun `per-crossing exit with no opposite direction is reported once`() {
    val report =
      lintRooms(
        """
        room("corridor", name = "Corridor", exits = [exit("crawl", to = [":a", ":b"], one_way = False)])
        room("a", name = "A", flags = ["dead_end_ok"], exits = [])
        room("b", name = "B", flags = ["dead_end_ok"], exits = [])
        """
      )
    val errs = report.errors.filter { "no opposite direction" in it.message }
    assertEquals(
      listOf("exit 'crawl' has no opposite direction and is not marked one_way"),
      errs.map { it.message },
    )
  }
}
