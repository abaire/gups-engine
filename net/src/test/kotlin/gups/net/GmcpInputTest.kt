package gups.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class GmcpInputTest {
  @Test
  fun `decodes plain values`() {
    val body = decodeGmcpBody("""{"a":"x","b":7,"c":1.5,"d":true,"e":null,"f":[1,{"g":[2]}]}""")
    val ok = assertIs<GmcpBody.Ok>(body)
    val m = @Suppress("UNCHECKED_CAST") (ok.data as Map<String, Any?>)
    assertEquals("x", m["a"])
    assertEquals(7L, m["b"])
    assertEquals(1.5, m["c"])
    assertEquals(true, m["d"])
    assertEquals(null, m["e"])
    val f = m["f"] as List<*>
    assertEquals(1L, f[0])
    val g = f[1] as Map<*, *>
    assertEquals(listOf(2L), g["g"])
  }

  @Test
  fun `integral numbers are longs and others doubles`() {
    val ok = assertIs<GmcpBody.Ok>(decodeGmcpBody("[100000000000, 0.5, 1.0]"))
    val list = ok.data as List<*>
    assertEquals(100000000000L, list[0])
    assertEquals(0.5, list[1])
    assertEquals(1.0, list[2])
    val ok2 = assertIs<GmcpBody.Ok>(decodeGmcpBody("3.0"))
    assertEquals(3.0, ok2.data)
  }

  @Test
  fun `a body over 4096 bytes is malformed`() {
    // 4096 bytes exactly: the quotes plus 4094 'a's.
    val exactly = "\"" + "a".repeat(MAX_GMCP_IN_BYTES - 2) + "\""
    assertEquals(MAX_GMCP_IN_BYTES, exactly.toByteArray(Charsets.UTF_8).size)
    val ok = assertIs<GmcpBody.Ok>(decodeGmcpBody(exactly))
    assertEquals("a".repeat(MAX_GMCP_IN_BYTES - 2), ok.data)
    assertIs<GmcpBody.Malformed>(decodeGmcpBody("\"" + "a".repeat(MAX_GMCP_IN_BYTES) + "\""))
  }

  @Test
  fun `nesting deeper than 8 is malformed`() {
    // Eight levels of arrays is fine; a ninth is not.
    val depth8 = "[".repeat(8) + "1" + "]".repeat(8)
    assertIs<GmcpBody.Ok>(decodeGmcpBody(depth8))
    assertIs<GmcpBody.Malformed>(decodeGmcpBody("[".repeat(9) + "1" + "]".repeat(9)))
    val obj8 = "{\"a\":".repeat(8) + "1" + "}".repeat(8)
    assertIs<GmcpBody.Ok>(decodeGmcpBody(obj8))
    assertIs<GmcpBody.Malformed>(decodeGmcpBody("{\"a\":".repeat(9) + "1" + "}".repeat(9)))
    assertIs<GmcpBody.Ok>(decodeGmcpBody("[".repeat(8) + "]".repeat(8)))
    assertIs<GmcpBody.Malformed>(decodeGmcpBody("[".repeat(9) + "]".repeat(9)))
    assertIs<GmcpBody.Malformed>(decodeGmcpBody("{\"a\":".repeat(8) + "{}" + "}".repeat(8)))
  }

  @Test
  fun `a parsed body is held to the same limits`() {
    assertEquals(GmcpBody.Ok(null), decodeGmcpElement(null))
    assertEquals(GmcpBody.Ok(listOf(1L)), decodeGmcpElement(Json.parseToJsonElement("[1]")))
    assertIs<GmcpBody.Malformed>(
      decodeGmcpElement(Json.parseToJsonElement("[".repeat(9) + "]".repeat(9)))
    )
    assertIs<GmcpBody.Malformed>(decodeGmcpElement(JsonPrimitive("a".repeat(MAX_GMCP_IN_BYTES))))
  }

  @Test
  fun `invalid JSON is malformed`() {
    assertIs<GmcpBody.Malformed>(decodeGmcpBody("{bad"))
    assertIs<GmcpBody.Malformed>(decodeGmcpBody("[1,"))
    assertIs<GmcpBody.Malformed>(decodeGmcpBody("\"unterminated"))
  }

  @Test
  fun `no body is null`() {
    val ok = assertIs<GmcpBody.Ok>(decodeGmcpBody(null))
    assertEquals(null, ok.data)
  }
}
