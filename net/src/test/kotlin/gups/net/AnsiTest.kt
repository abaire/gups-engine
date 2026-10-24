package gups.net

import kotlin.test.Test
import kotlin.test.assertEquals

class AnsiTest {
    private val e = "\u001b["

    @Test
    fun `markup renders per color depth`() {
        val text = "{bold}{red}Danger{/} and {x196}more{/}, {{literal}, {nope}"
        assertEquals("Danger and more, {literal}, {nope}", Ansi.render(text, ColorDepth.NONE))
        assertEquals("${e}1m${e}31mDanger${e}0m and ${e}91mmore${e}0m, {literal}, {nope}", Ansi.render(text, ColorDepth.ANSI16))
        assertEquals("${e}1m${e}31mDanger${e}0m and ${e}38;5;196mmore${e}0m, {literal}, {nope}", Ansi.render(text, ColorDepth.XTERM256))
    }

    @Test
    fun `plain text is untouched and xterm colors downgrade sensibly`() {
        assertEquals("You say, 'hi'", Ansi.render("You say, 'hi'", ColorDepth.XTERM256))
        assertEquals(9, Ansi.nearest16(196))
        assertEquals(0, Ansi.nearest16(232))
        assertEquals(15, Ansi.nearest16(255))
        assertEquals("{x300}", Ansi.render("{x300}", ColorDepth.ANSI16))
        assertEquals("${e}32mgreen${e}0m", Ansi.render("{green}green", ColorDepth.ANSI16), "an unclosed color is reset at the end")
    }
}
