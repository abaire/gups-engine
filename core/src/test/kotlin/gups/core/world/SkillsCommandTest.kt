package gups.core.world

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class SkillsCommandTest {
  private fun without(off: Feature): Features = Features(Feature.entries.toSet() - setOf(off))

  @Test
  fun `the skills command lists known skills sorted by name, with kind and proficiency`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.setSkill(p, T.ZAP, 40)
    f.w.setSkill(p, T.BOLT, 15)
    f.input(p, "skills")
    f.run(4)
    val out = f.out(p)
    val header = out.indexOf("You know these skills:")
    val bolt = out.indexOf("  bolt (spell) 15%")
    val zap = out.indexOf("  zap (spell) 40%")
    assertTrue(header in 0 until bolt, "header before the first line:\n$out")
    assertTrue(bolt < zap, "sorted by skill name:\n$out")
  }

  @Test
  fun `the skills command says the player knows nothing when it does`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.input(p, "skills")
    f.run(4)
    assertContains(f.out(p), "You don't know any skills.")
  }

  @Test
  fun `skills is Huh without the skills feature`() {
    val f = Fixture(features = without(Feature.SKILLS))
    val p = f.player("Ann")
    f.input(p, "skills")
    f.run(4)
    assertContains(f.out(p), "Huh?")
  }
}
