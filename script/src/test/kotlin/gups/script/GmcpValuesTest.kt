package gups.script

import gups.core.world.EntityRef
import gups.core.world.World
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.starlark.java.eval.Dict
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkFloat
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkList

class GmcpValuesTest {
  private val limits = ScriptLimits()

  @Test
  fun `converts nested values and allows None`() {
    val value =
      Dict.immutableCopyOf(
        mapOf(
          "a" to
            StarlarkList.immutableCopyOf(
              listOf(StarlarkInt.of(1), Starlark.NONE, Dict.immutableCopyOf(mapOf("b" to true)))
            )
        )
      )
    val out = toGmcp(value, "data", limits) as Map<*, *>
    val list = out["a"] as List<*>
    assertEquals(1L, list[0])
    assertEquals(null, list[1])
    @Suppress("UNCHECKED_CAST") assertEquals(true, (list[2] as Map<*, *>)["b"])
  }

  @Test
  fun `floats are refused`() {
    val e = assertFailsWith<EvalException> { toGmcp(StarlarkFloat.of(1.5), "data", limits) }
    assertContains(e.message!!, "floats can't be sent")
  }

  @Test
  fun `dict keys must be strings`() {
    val value = Dict.immutableCopyOf(mapOf(StarlarkInt.of(7) to "x"))
    val e = assertFailsWith<EvalException> { toGmcp(value, "data", limits) }
    assertContains(e.message!!, "dict keys must be strings")
  }

  @Test
  fun `views are refused`() {
    val struct = StructValue(mapOf("x" to StarlarkInt.of(1)))
    val e = assertFailsWith<EvalException> { toGmcp(struct, "data", limits) }
    assertContains(e.message!!, "can't be sent")
  }

  @Test
  fun `a payload over 16 KB raises alloc_limit`() {
    // A JSON string is 2 bytes of quotes plus one byte per ASCII character.
    val ok = toGmcp("a".repeat(16382), "data", limits)
    assertEquals("a".repeat(16382), ok)
    val over = assertFailsWith<EvalException> { toGmcp("a".repeat(16383), "data", limits) }
    assertTrue(over.cause is LimitExceeded, "expected an alloc_limit failure, got ${over.message}")
    assertEquals("alloc_limit", (over.cause as LimitExceeded).kind)
    assertContains(over.message!!, "over the 16 KB limit")
  }

  @Test
  fun `the size counts escapes and multibyte characters`() {
    // "a" newline "b" is 2 quotes + 1 + 2 (the \n escape) + 1.
    assertEquals(6, gmcpJsonSize("a\nb"))
    // e-acute is one UTF-16 code unit below 0x800, so two bytes.
    assertEquals(4, gmcpJsonSize("é"))
    // A quote is escaped, adding one byte.
    assertEquals(4, gmcpJsonSize("\""))
    // A control character below 0x20 that is not a named escape is six bytes.
    assertEquals(10, gmcpJsonSize("ab"))
  }

  /** The plain value as the listener encodes it. */
  private fun json(v: Any?): JsonElement =
    when (v) {
      null -> JsonNull
      is String -> JsonPrimitive(v)
      is Boolean -> JsonPrimitive(v)
      is Number -> JsonPrimitive(v)
      is Map<*, *> -> JsonObject(v.entries.associate { (k, value) -> k.toString() to json(value) })
      is List<*> -> JsonArray(v.map(::json))
      else -> error("unexpected ${v::class}")
    }

  @Test
  fun `the size matches the compact JSON encoding`() {
    val values =
      listOf(
        null,
        true,
        false,
        0L,
        -42L,
        Long.MIN_VALUE,
        "",
        "plain",
        "quote \" and backslash \\",
        "\n\t\r\b\u000c",
        "\u0000\u0001\u001f",
        "\u007f",
        "\u2028",
        "caf\u00e9 \u4e2d \ud83d\ude00",
        listOf<Any?>(),
        mapOf<String, Any?>(),
        listOf(1L, null, "x", listOf(true)),
        mapOf("a" to null, "b\n" to listOf(mapOf("c" to 1L)), "\u00e9" to "\ud83d\ude00"),
      )
    for (v in values) {
      val expected = Json.encodeToString(JsonElement.serializer(), json(v)).toByteArray().size
      assertEquals(expected, gmcpJsonSize(v), "size of $v")
    }
  }

  @Test
  fun `a payload sharing one list many times is refused without expanding it`() {
    val a = StarlarkList.immutableCopyOf(List(1000) { StarlarkInt.of(1) })
    val b = StarlarkList.immutableCopyOf(List(1000) { a })
    val c = StarlarkList.immutableCopyOf(List(1000) { b })
    val e = assertFailsWith<EvalException> { toGmcp(c, "data", limits) }
    assertEquals("alloc_limit", (e.cause as LimitExceeded).kind)
  }

  @Test
  fun `a list that contains itself is refused`() {
    val l = StarlarkList.newList<Any>(Mutability.create())
    l.addElement(l)
    val e = assertFailsWith<EvalException> { toGmcp(l, "data", limits) }
    assertEquals("alloc_limit", (e.cause as LimitExceeded).kind)
  }

  @Test
  fun `payloads nest at most 8 deep`() {
    fun nest(n: Int): Any =
      (1..n).fold<Int, Any>(StarlarkInt.of(1)) { v, _ -> StarlarkList.immutableCopyOf(listOf(v)) }
    toGmcp(nest(8), "data", limits)
    val e = assertFailsWith<EvalException> { toGmcp(nest(9), "data", limits) }
    assertEquals("alloc_limit", (e.cause as LimitExceeded).kind)
    assertContains(e.message!!, "nest at most 8 deep")
  }

  @Test
  fun `toPlain converts views to entity refs`() {
    val f =
      ScriptFixture(
        mapOf("zones/z/package.star" to zonePkg(), "zones/z/content.star" to "room(\"start\")")
      )
    val p = f.player()
    val scope =
      object : ViewScope {
        override val world: World
          get() = f.w

        override val pkg: String? = null

        override fun check() = Unit
      }
    val v = view(scope, p)
    assertEquals(EntityRef(p.id), toPlain(v, "arg", limits, views = true))
    val dict =
      toPlain(Dict.immutableCopyOf(mapOf("who" to v)), "arg", limits, views = true) as Map<*, *>
    assertEquals(EntityRef(p.id), dict["who"])
    val list =
      toPlain(StarlarkList.immutableCopyOf(listOf(v)), "arg", limits, views = true) as List<*>
    assertEquals(EntityRef(p.id), list[0])
  }

  @Test
  fun `toPlain without views refuses views`() {
    val f =
      ScriptFixture(
        mapOf("zones/z/package.star" to zonePkg(), "zones/z/content.star" to "room(\"start\")")
      )
    val p = f.player()
    val scope =
      object : ViewScope {
        override val world: World
          get() = f.w

        override val pkg: String? = null

        override fun check() = Unit
      }
    val e = assertFailsWith<EvalException> { toPlain(view(scope, p), "arg", limits) }
    assertContains(e.message!!, "can't be sent")
  }
}
