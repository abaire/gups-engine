package gups.net

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val IAC = 255
private const val SB = 250
private const val SE = 240

private fun bytes(vararg parts: Any): ByteArray = parts.flatMap { p ->
    when (p) {
        is Int -> listOf(p.toByte())
        is String -> p.toByteArray().toList()
        else -> error("bad part $p")
    }
}.toByteArray()

/** A connection plus everything it wrote and every line it read. */
private class Probe {
    val written = mutableListOf<ByteArray>()
    val lines = mutableListOf<Pair<String, Boolean>>()
    val conn = TelnetConnection { written += it }

    fun feed(vararg parts: Any) = conn.receive(bytes(*parts)) { l, t -> lines += l to t }

    fun take(): List<List<Int>> = written.map { b -> b.map { it.toInt() and 0xff } }.also { written.clear() }
}

class TelnetConnectionTest {
    @Test
    fun `opens by asking for TTYPE and NAWS and offering EOR, GMCP and CHARSET`() {
        val p = Probe()
        p.conn.start()
        assertEquals(
            listOf(listOf(IAC, 253, 24), listOf(IAC, 253, 31), listOf(IAC, 251, 25), listOf(IAC, 251, 201), listOf(IAC, 251, 42)),
            p.take(),
        )
    }

    @Test
    fun `a Mudlet-like client negotiates everything and gets 256 colors`() {
        val p = Probe()
        p.conn.start()
        p.take()
        // Mudlet's replies: WILL TTYPE, WILL NAWS, DO EOR, DO GMCP, DO CHARSET, then window size.
        p.feed(IAC, 251, 24, IAC, 251, 31, IAC, 253, 25, IAC, 253, 201, IAC, 253, 42, IAC, SB, 31, 0, 120, 0, 40, IAC, SE)
        val sent = p.take()
        assertEquals(listOf(IAC, SB, 24, 1, IAC, SE), sent[0], "TTYPE SEND")
        assertEquals(listOf(IAC, SB, 42, 1) + ";UTF-8".map { it.code } + listOf(IAC, SE), sent[1], "CHARSET REQUEST")
        assertEquals(2, sent.size, "no echoes of options already agreed")
        p.feed(IAC, SB, 42, 2, "UTF-8", IAC, SE)
        p.feed(IAC, SB, 24, 0, "MUDLET", IAC, SE)
        p.feed(IAC, SB, 24, 0, "ANSI-TRUECOLOR", IAC, SE)
        p.feed(IAC, SB, 24, 0, "MTTS 2831", IAC, SE)
        assertEquals(2, p.take().size, "asks for TTYPE exactly three times in all")
        p.feed(IAC, SB, 201, "Core.Hello {\"client\":\"Mudlet\"}", IAC, SE)
        with(p.conn) {
            assertEquals("MUDLET", clientName)
            assertEquals("ANSI-TRUECOLOR", terminalType)
            assertEquals(2831, mtts)
            assertEquals(ColorDepth.XTERM256, colorDepth)
            assertEquals(120, width)
            assertEquals(40, height)
            assertTrue(eor && gmcp && utf8Accepted)
            assertEquals(0, errors)
        }
        assertContentEquals(bytes("> ", IAC, 239), p.conn.encodePrompt("> "), "prompts end with EOR")
    }

    @Test
    fun `a plain telnet client refuses EOR and gets GA`() {
        val p = Probe()
        p.conn.start()
        p.take()
        p.feed(IAC, 251, 24, IAC, 252, 31, IAC, 254, 25, IAC, 254, 201, IAC, 254, 42)
        assertEquals(listOf(listOf(IAC, SB, 24, 1, IAC, SE)), p.take(), "refusals of offers need no reply")
        p.feed(IAC, SB, 24, 0, "NETKIT-TELNET", IAC, SE, IAC, SB, 24, 0, "XTERM", IAC, SE, IAC, SB, 24, 0, "XTERM", IAC, SE)
        assertEquals(ColorDepth.ANSI16, p.conn.colorDepth, "no MTTS: the terminal type decides")
        assertContentEquals(bytes("> ", IAC, 249), p.conn.encodePrompt("> "))
        assertEquals(2, p.take().size, "two more TTYPE requests, then the repeat ends the list")
        // A request for something we don't support is refused once, not looped.
        p.feed(IAC, 253, 1, IAC, 253, 1, IAC, 251, 86)
        assertEquals(listOf(listOf(IAC, 252, 1), listOf(IAC, 254, 86)), p.take())
    }

    @Test
    fun `a TinTin-like client reports MTTS`() {
        val p = Probe()
        p.conn.start()
        p.feed(IAC, 251, 24, IAC, SB, 24, 0, "TINTIN++", IAC, SE, IAC, SB, 24, 0, "XTERM-256COLOR", IAC, SE, IAC, SB, 24, 0, "MTTS 137", IAC, SE)
        assertEquals(137, p.conn.mtts)
        assertEquals(ColorDepth.XTERM256, p.conn.colorDepth)
        p.feed(IAC, SB, 24, 0, "MTTS 1", IAC, SE)
        assertEquals(ColorDepth.ANSI16, p.conn.colorDepth, "MTTS bits decide: ANSI only")
    }

    @Test
    fun `raw nc gets plain text, no color and no GA`() {
        val p = Probe()
        p.conn.start()
        p.feed("look\r\nnorth\nsay hi\r\u0000")
        assertEquals(listOf("look" to false, "north" to false, "say hi" to false), p.lines)
        assertFalse(p.conn.speaksTelnet)
        assertEquals(ColorDepth.NONE, p.conn.colorDepth)
        assertContentEquals("{red}Hi{/}\r\n> ".replace("{red}", "").replace("{/}", "").toByteArray(), p.conn.encodeText("{red}Hi{/}\n") + p.conn.encodePrompt("> "))
    }

    @Test
    fun `line framing, escapes, backspace and the length cap`() {
        val p = Probe()
        p.feed("ab", 8, "c", IAC, IAC, "\r\n")
        assertEquals("ac�", p.lines.single().first, "IAC IAC is a data byte; alone it isn't valid UTF-8")
        p.lines.clear()
        p.feed("x".repeat(600) + "\n")
        assertEquals(MAX_LINE_BYTES, p.lines.single().first.length)
        assertTrue(p.lines.single().second)
        p.lines.clear()
        p.feed("héllo\n")
        assertEquals("héllo", p.lines.single().first)
    }

    @Test
    fun `malformed sequences are counted and skipped`() {
        val p = Probe()
        p.feed(IAC, 7, "ok\n", IAC, SE, IAC, SB, 24, 9, IAC, SE, IAC, SB, 31, 1, IAC, SE)
        assertEquals(listOf("ok" to false), p.lines)
        assertEquals(4, p.conn.errors)
    }
}
