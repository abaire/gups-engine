package gups.script

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkList

class StorableValuesTest {
  private val limits = ScriptLimits()

  private fun nest(n: Int): Any =
    (1..n).fold<Int, Any>(StarlarkInt.of(1)) { v, _ -> StarlarkList.immutableCopyOf(listOf(v)) }

  private fun limitKind(e: EvalException) = (e.cause as LimitExceeded).kind

  @Test
  fun `values nest at most 8 deep`() {
    toAttr(nest(8), "value", limits)
    val e = assertFailsWith<EvalException> { toAttr(nest(9), "value", limits) }
    assertEquals("alloc_limit", limitKind(e))
    assertContains(e.message!!, "nest at most 8 deep")
  }

  @Test
  fun `a list that contains itself is refused`() {
    val l = StarlarkList.newList<Any>(Mutability.create())
    l.addElement(l)
    val e = assertFailsWith<EvalException> { toAttr(l, "value", limits) }
    assertEquals("alloc_limit", limitKind(e))
  }

  @Test
  fun `a value sharing one list many times is refused without expanding it`() {
    val a = StarlarkList.immutableCopyOf(List(1000) { StarlarkInt.of(1) })
    val b = StarlarkList.immutableCopyOf(List(1000) { a })
    val c = StarlarkList.immutableCopyOf(List(1000) { b })
    val e = assertFailsWith<EvalException> { toAttr(c, "value", limits) }
    assertEquals("alloc_limit", limitKind(e))
    assertContains(e.message!!, "over the 64 KB limit")
  }

  @Test
  fun `a value of 64 KB is accepted`() {
    // A JSON string is its characters plus two quotes.
    toAttr("a".repeat(64 * 1024 - 2), "value", limits)
    val e = assertFailsWith<EvalException> { toAttr("a".repeat(64 * 1024 - 1), "value", limits) }
    assertEquals("alloc_limit", limitKind(e))
  }
}
