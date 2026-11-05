package gups.core.world

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MessagesTest {
  @Test
  fun `every catalog entry has a default whose placeholders match its declaration`() {
    assertTrue(Messages.all.size > 100)
    for (m in Messages.all) {
      assertTrue(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+").matches(m.id), "id ${m.id}")
      assertTrue(m.default.isNotEmpty(), "${m.id} has no default")
      assertTrue(m.required.all { it in m.placeholders }, "${m.id}: required must be declared")
      val used = Messages.fieldsOf(m.default)
      assertTrue(used.all { it in m.placeholders }, "${m.id} uses ${used - m.placeholders.toSet()}")
      assertTrue(
        m.required.all { it in used },
        "${m.id}'s default leaves out a required placeholder",
      )
      assertEquals(emptyList(), Messages.problems(m, m.default), m.id)
    }
  }

  @Test
  fun `ids are unique and grouped by the feature that sends them`() {
    assertEquals(Messages.all.size, Messages.all.map { it.id }.toSet().size)
    for (m in Messages.all) {
      val prefix = m.id.substringBefore('.')
      when (m.feature) {
        Feature.COMBAT -> assertTrue(prefix == "combat", m.id)
        Feature.TRACK -> assertTrue(prefix == "track", m.id)
        Feature.SKILLS -> assertTrue(prefix in setOf("cast", "stop", "skills"), m.id)
        Feature.LEVELING -> assertTrue(prefix == "leveling", m.id)
        null -> assertTrue(prefix !in setOf("combat", "track", "cast", "leveling"), m.id)
      }
    }
  }

  @Test
  fun `fill replaces known placeholders and leaves color markup and escaped braces alone`() {
    assertEquals(
      "{red}Ann{/} says {{hi}} {unknown}",
      Messages.fill("{red}{actor}{/} says {{hi}} {unknown}", mapOf("actor" to "Ann")),
    )
    assertEquals(listOf("actor"), Messages.fieldsOf("{red}{actor}{/} {{x}} {x7} {x255}"))
  }

  @Test
  fun `an override changes the text in play`() {
    val f =
      Fixture(messages = mapOf("say.self" to "You declare, '{text}'", "common.huh" to "Pardon?"))
    val p = f.player("Ann")
    f.input(p, "say hi")
    f.run(1)
    assertContains(f.out(p), "You declare, 'hi'")
    f.input(p, "zork")
    f.run(1)
    assertContains(f.out(p), "Pardon?")
    val plain =
      Fixture().let { g ->
        g.player("Bea")
          .also {
            g.input(it, "zork")
            g.run(1)
          }
          .let { g.out(it) }
      }
    assertContains(plain, "Huh?")
  }

  @Test
  fun `a message with the actor placeholder is filled for onlookers`() {
    val f = Fixture(messages = mapOf("drop.room" to "{actor} lets go of {item}."))
    val a = f.player("Ann")
    val b = f.player("Bea")
    f.w.spawnItem(T.SWORD, gups.core.model.ItemLocation.Carried(a.id))
    f.input(a, "drop sword")
    f.run(4)
    assertContains(f.out(b), "Ann lets go of a sword.")
  }

  /**
   * The player-facing calls in `core` and `auth` that pass a string literal instead of a message.
   */
  @Test
  fun `no player-facing literal remains in core or auth`() {
    val roots = listOf(File("src/main/kotlin"), File("../auth/src/main/kotlin"))
    // Staff tools print engine terms and aren't in the catalog.
    val skip = setOf("Tools.kt", "AccountTools.kt", "Operations.kt")
    val call = Regex("""\b(send|echo|sendSession|prompt)\(|\.append\(""")
    val offenders = mutableListOf<String>()
    for (root in roots) {
      assertTrue(root.isDirectory, "missing $root")
      for (file in root.walkTopDown().filter { it.extension == "kt" && it.name !in skip }) {
        val text = file.readText()
        for (m in call.findAll(text)) {
          if (
            m.value.startsWith(".append(") &&
              !text.substring(maxOf(0, m.range.first - 4), m.range.first).endsWith(".out")
          )
            continue
          val args = balanced(text, m.range.last + 1)
          // Messages built by the catalog may carry quoted placeholder names.
          // An empty line echoes the Enter key.
          val rest = stripMsg(args).replace("\"\"", "")
          if ('"' in rest)
            offenders += "${file.name}: ${m.value}${rest.take(60).replace('\n', ' ')}"
        }
      }
    }
    assertEquals(emptyList(), offenders, "player-facing literals outside the catalog")
  }

  private fun balanced(text: String, from: Int): String {
    var depth = 1
    var i = from
    var inString = false
    while (i < text.length && depth > 0) {
      val c = text[i]
      if (inString) {
        if (c == '\\') i++ else if (c == '"') inString = false
      } else
        when (c) {
          '"' -> inString = true
          '(' -> depth++
          ')' -> depth--
        }
      i++
    }
    return text.substring(from, i - 1)
  }

  /**
   * [args] with each `msg(...)` and `render(...)` call, which names a catalog entry, and each
   * `joinToString(...)` separator blanked.
   */
  private fun stripMsg(args: String): String {
    var out = args
    val m = Regex("""\b(msg|render|joinToString)\(""")
    while (true) {
      val hit = m.find(out) ?: return out
      val inner = balanced(out, hit.range.last + 1)
      out =
        out.substring(0, hit.range.first) +
          "M" +
          out.substring(hit.range.last + 1 + inner.length + 1)
    }
  }

  @Test
  fun `the catalog finds entries by id`() {
    assertNotNull(Messages.find("cast.begin.known"))
    assertEquals(
      "No way! You're fighting for your life!",
      Messages.find("combat.quit_refused")!!.default,
    )
  }

  @Test
  fun `a name placeholder is capitalized only where it opens the text`() {
    val v = mapOf("target" to "the goblin")
    assertEquals("The goblin is out of reach.", Messages.fill("{target} is out of reach.", v))
    assertEquals("You can't reach the goblin.", Messages.fill("You can't reach {target}.", v))
    assertEquals(
      "north (closed)",
      Messages.fill("{direction} ({state})", mapOf("direction" to "north", "state" to "closed")),
    )
  }
}
