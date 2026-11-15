package gups.core.model

import gups.core.Label
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomNumbersTest {
  @Test
  fun `mapNumberHash matches expected FNV-1a 32-bit masked vectors`() {
    assertEquals(18652613L, mapNumberHash(""))
    assertEquals(1678518572L, mapNumberHash("a"))
    assertEquals(1786645601L, mapNumberHash("//t:yard"))
    assertEquals(1471930522L, mapNumberHash("//t:hall"))
    assertEquals(1687381018L, mapNumberHash("//zones/tutorial:gate"))
  }

  @Test
  fun `nonZeroMapNumber clears top bit and maps zero to one`() {
    assertEquals(1L, nonZeroMapNumber(0L))
    assertEquals(1L, nonZeroMapNumber(0x80000000L))
    assertEquals(42L, nonZeroMapNumber(42L))
    assertEquals(0x7FFFFFFFL, nonZeroMapNumber(0xFFFFFFFFL))
  }

  @Test
  fun `roomNumbers resolves collisions by probing with suffix`() {
    val fakeHash: (String) -> Long = { s ->
      when (s) {
        "//a:x",
        "//a:y" -> 7L
        "//a:y#1" -> 9L
        else -> 99L
      }
    }
    val result = roomNumbers(listOf(Label("//a:x"), Label("//a:y")), hash = fakeHash)
    assertEquals(mapOf(Label("//a:x") to 7L, Label("//a:y") to 9L), result)
  }

  @Test
  fun `roomNumbers continues probing when #1 is also taken`() {
    val fakeHash: (String) -> Long = { s ->
      when (s) {
        "//a:x" -> 7L
        "//a:y" -> 7L
        "//a:y#1" -> 7L
        "//a:y#2" -> 12L
        else -> 99L
      }
    }
    val result = roomNumbers(listOf(Label("//a:x"), Label("//a:y")), hash = fakeHash)
    assertEquals(mapOf(Label("//a:x") to 7L, Label("//a:y") to 12L), result)
  }

  @Test
  fun `roomNumbers is independent of input order`() {
    val fakeHash: (String) -> Long = { s ->
      when (s) {
        "//a:x",
        "//a:y" -> 7L
        "//a:y#1" -> 9L
        else -> 99L
      }
    }
    val forward = roomNumbers(listOf(Label("//a:x"), Label("//a:y")), hash = fakeHash)
    val reversed = roomNumbers(listOf(Label("//a:y"), Label("//a:x")), hash = fakeHash)
    assertEquals(forward, reversed)
    assertEquals(listOf(Label("//a:x"), Label("//a:y")), reversed.keys.toList())
  }
}
