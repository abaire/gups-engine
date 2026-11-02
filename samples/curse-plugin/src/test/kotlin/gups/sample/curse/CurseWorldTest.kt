package gups.sample.curse

import gups.harness.Status
import gups.harness.TestRunner
import gups.pluginapi.host.PluginLoader
import gups.script.WorldDir
import gups.script.WorldModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CurseWorldTest {
  private fun write(root: Path, relativePath: String, text: String) {
    val p = root.resolve(relativePath)
    p.parent.createDirectories()
    p.writeText(text.trimIndent() + "\n")
  }

  @Test
  fun `the built curse jar runs through TestRunner in fixture world`() {
    val tmp = Files.createTempDirectory("gups-curse")
    val jar = tmp.resolve("plugins/curse.jar").also { it.parent.createDirectories() }
    Path.of(System.getProperty("gups.sample.curseJar")).copyTo(jar)
    val schema = Path.of(System.getProperty("gups.templateWorld")).resolve("MODULE.gups").readText()
    write(
      tmp,
      "MODULE.gups",
      schema.replaceFirst(
        "module(",
        "module(\n    plugins = {\"curse\": \"${PluginLoader.sha256(jar)}\"},",
      ),
    )
    val rules = Path.of(System.getProperty("gups.templateWorld")).resolve("rules")
    Files.list(rules)
      .use { s -> s.toList() }
      .forEach { it.copyTo(tmp.resolve("rules").createDirectories().resolve(it.fileName)) }

    write(tmp, "skills/package.star", """package(api_version = 1, kind = "skills")""")
    write(
      tmp,
      "skills/skills.star",
      """
      skill("remove_curse", kind = "spell", target = "item", effect = curse.lift())
      skill("bestow_curse", kind = "spell", target = "item", effect = curse.bestow())
      """,
    )

    write(tmp, "zones/curse_zone/package.star", """package(api_version = 1, kind = "zone")""")
    write(
      tmp,
      "zones/curse_zone/content.star",
      """
      room("sanctuary", name = "Sanctuary", desc = "A peaceful sanctuary.", exits = [exit("north", ":hall")])
      room("hall", name = "Hall", desc = "A stone hall.", exits = [exit("south", ":sanctuary")])

      mob("priest", name = "a priest", desc = "A serene priest.", keywords = ["priest"])

      item("amulet_of_woe",
           name = "an amulet of woe",
           desc = "A cursed amulet.",
           keywords = ["amulet", "woe"],
           wear = "neck",
           tags = ["cursed"],
           affects = [item_affect("misfortune", applies = "carried", stat_deltas = stats(dex = -2))],
           variants = {"uncursed": ":amulet_of_warding"})

      item("amulet_of_warding",
           name = "an amulet of warding",
           desc = "A holy amulet.",
           keywords = ["amulet", "warding"],
           wear = "neck",
           affects = [item_affect("warding", applies = "worn", stat_deltas = stats(dex = 1))])

      item("small_bag",
           name = "a small bag",
           desc = "A small bag.",
           keywords = ["bag", "small"],
           container = container())

      item("backpack",
           name = "a backpack",
           desc = "A leather backpack.",
           keywords = ["backpack"],
           wear = "held",
           container = container())

      item("lead_box",
           name = "a lead box",
           desc = "A lead box.",
           keywords = ["box", "lead"],
           tags = ["warded"],
           container = container())

      item("chest",
           name = "a chest",
           desc = "A sturdy wooden chest.",
           keywords = ["chest"],
           container = container())

      item("cursed_sword",
           name = "a cursed sword",
           desc = "A cursed sword.",
           keywords = ["sword", "cursed"],
           wear = "wield",
           tags = ["cursed"],
           affects = [item_affect("drain", stat_deltas = stats(dex = -1))])

      item("dagger",
           name = "a dagger",
           desc = "A steel dagger.",
           keywords = ["dagger"],
           wear = "wield")

      item("cursed_ring",
           name = "a cursed ring",
           desc = "An obsidian ring.",
           keywords = ["ring", "cring", "cursed"],
           wear = "finger",
           tags = ["cursed"])

      item("plain_ring",
           name = "a plain ring",
           desc = "A silver ring.",
           keywords = ["ring", "pring", "plain"],
           wear = "finger")
      """,
    )

    write(
      tmp,
      "zones/curse_zone/curse_test.star",
      """
      def test_propagation_by_commands(t):
          w = t.world(packages = ["//skills", "//zones/curse_zone"])
          p = w.spawn_player("Alice", at = ":sanctuary", skills = {"//skills:remove_curse": 100, "//skills:bestow_curse": 100})
          base_dex = p.dex
          t.eq(base_dex, 10)
          w.spawn(":amulet_of_woe", at = p)
          w.spawn(":small_bag", at = p)
          w.spawn(":backpack", at = p)
          t.eq(p.dex, base_dex - 2)

          out = p.do("put amulet bag")
          t.contains(out, "You put an amulet of woe in a small bag.")
          t.eq(p.dex, base_dex - 2)

          out = p.do("put bag backpack")
          t.contains(out, "You put a small bag in a backpack.")
          t.eq(p.dex, base_dex - 2)

      def test_cannot_shed_curse(t):
          w = t.world(packages = ["//skills", "//zones/curse_zone"])
          p = w.spawn_player("Bob", at = ":sanctuary", skills = {"//skills:remove_curse": 100, "//skills:bestow_curse": 100})
          base_dex = p.dex
          w.spawn(":priest", at = ":sanctuary")
          w.spawn(":chest", at = ":sanctuary")

          w.spawn(":amulet_of_woe", at = p)
          w.spawn(":small_bag", at = p)
          w.spawn(":backpack", at = p)
          t.eq(p.dex, base_dex - 2)

          p.do("put amulet bag")
          p.do("put bag backpack")
          t.eq(p.dex, base_dex - 2)

          out = p.do("drop backpack")
          t.contains(out, "It won't budge. It must be cursed!")
          t.eq(p.dex, base_dex - 2)

          out = p.do("give backpack priest")
          t.contains(out, "It won't budge. It must be cursed!")
          t.eq(p.dex, base_dex - 2)

          out = p.do("put backpack chest")
          t.contains(out, "It won't budge. It must be cursed!")
          t.eq(p.dex, base_dex - 2)

          p.do("get bag backpack")
          out = p.do("drop bag")
          t.contains(out, "It won't budge. It must be cursed!")
          t.eq(p.dex, base_dex - 2)

          p.do("get amulet bag")
          out = p.do("drop amulet")
          t.contains(out, "It won't budge. It must be cursed!")
          t.eq(p.dex, base_dex - 2)

      def test_warded_box_drop(t):
          w = t.world(packages = ["//skills", "//zones/curse_zone"])
          p = w.spawn_player("Charlie1", at = ":sanctuary", skills = {"//skills:remove_curse": 100, "//skills:bestow_curse": 100})
          base_dex = p.dex
          w.spawn(":amulet_of_woe", at = p)
          w.spawn(":lead_box", at = p)
          t.eq(p.dex, base_dex - 2)

          out = p.do("put amulet box")
          t.contains(out, "You put an amulet of woe in a lead box.")
          t.eq(p.dex, base_dex)

          out = p.do("drop box")
          t.contains(out, "You drop a lead box.")
          t.eq(p.dex, base_dex)

          out = p.do("north")
          t.contains(out, "Hall")
          t.eq(p.dex, base_dex)

      def test_warded_box_put_chest(t):
          w = t.world(packages = ["//skills", "//zones/curse_zone"])
          p = w.spawn_player("Charlie2", at = ":sanctuary", skills = {"//skills:remove_curse": 100, "//skills:bestow_curse": 100})
          base_dex = p.dex
          w.spawn(":chest", at = ":sanctuary")
          w.spawn(":amulet_of_woe", at = p)
          w.spawn(":lead_box", at = p)
          t.eq(p.dex, base_dex - 2)

          p.do("put amulet box")
          t.eq(p.dex, base_dex)

          out = p.do("put box chest")
          t.contains(out, "You put a lead box in a chest.")
          t.eq(p.dex, base_dex)

          out = p.do("north")
          t.contains(out, "Hall")
          t.eq(p.dex, base_dex)

      def test_warded_box_give_priest(t):
          w = t.world(packages = ["//skills", "//zones/curse_zone"])
          p = w.spawn_player("Charlie3", at = ":sanctuary", skills = {"//skills:remove_curse": 100, "//skills:bestow_curse": 100})
          base_dex = p.dex
          w.spawn(":priest", at = ":sanctuary")
          w.spawn(":amulet_of_woe", at = p)
          w.spawn(":lead_box", at = p)
          t.eq(p.dex, base_dex - 2)

          p.do("put amulet box")
          t.eq(p.dex, base_dex)

          out = p.do("give box priest")
          t.contains(out, "You give a lead box to a priest.")
          t.eq(p.dex, base_dex)

          out = p.do("north")
          t.contains(out, "Hall")
          t.eq(p.dex, base_dex)

      def test_ward_inside_bag(t):
          w = t.world(packages = ["//skills", "//zones/curse_zone"])
          p = w.spawn_player("Dave", at = ":sanctuary", skills = {"//skills:remove_curse": 100, "//skills:bestow_curse": 100})
          base_dex = p.dex
          w.spawn(":amulet_of_woe", at = p)
          w.spawn(":lead_box", at = p)
          w.spawn(":backpack", at = p)
          p.do("put amulet box")
          p.do("put box backpack")
          t.eq(p.dex, base_dex)

          out = p.do("drop backpack")
          t.contains(out, "You drop a backpack.")
          t.eq(p.dex, base_dex)

      def test_removing_worn_bag_isnt_shedding(t):
          w = t.world(packages = ["//skills", "//zones/curse_zone"])
          p = w.spawn_player("Eve", at = ":sanctuary", skills = {"//skills:remove_curse": 100, "//skills:bestow_curse": 100})
          base_dex = p.dex
          w.spawn(":amulet_of_woe", at = p)
          w.spawn(":backpack", at = p)
          out = p.do("put amulet backpack")
          t.contains(out, "You put an amulet of woe in a backpack.")
          p.do("wear backpack")
          t.ne(p.equipped("held"), None)
          t.eq(p.dex, base_dex - 2)

          out = p.do("remove backpack")
          t.contains(out, "You stop using a backpack.")
          t.eq(p.equipped("held"), None)
          t.eq(p.dex, base_dex - 2)

          out = p.do("drop backpack")
          t.contains(out, "It won't budge. It must be cursed!")
          t.eq(p.dex, base_dex - 2)

      def test_no_swap_loophole(t):
          w = t.world(packages = ["//skills", "//zones/curse_zone"])
          p = w.spawn_player("Frank", at = ":sanctuary", skills = {"//skills:remove_curse": 100, "//skills:bestow_curse": 100})
          base_dex = p.dex
          sword = w.spawn(":cursed_sword", at = p)
          dagger = w.spawn(":dagger", at = p)

          p.do("wield sword")
          t.eq(p.equipped("wield").id, sword.id)
          t.eq(p.dex, base_dex - 1)

          out = p.do("wield dagger")
          t.contains(out, "It won't budge. It must be cursed!")
          t.eq(p.equipped("wield").id, sword.id)
          t.eq([i.id for i in p.inventory()], [dagger.id])
          t.eq(p.dex, base_dex - 1)

          out = p.do("remove sword")
          t.contains(out, "It won't budge. It must be cursed!")
          t.eq(p.equipped("wield").id, sword.id)
          t.eq(p.dex, base_dex - 1)

          out = p.do("cast remove_curse sword")
          t.contains(out, "You cast remove_curse on a cursed sword.")

          out = p.do("wield dagger")
          t.contains(out, "You wield a dagger.")
          t.eq(p.equipped("wield").id, dagger.id)
          t.eq([i.id for i in p.inventory()], [sword.id])
          t.eq(p.dex, base_dex)

      def test_clinging_ring_slot_family(t):
          w = t.world(packages = ["//skills", "//zones/curse_zone"])
          p = w.spawn_player("Grace", at = ":sanctuary", skills = {"//skills:remove_curse": 100, "//skills:bestow_curse": 100})
          cursed = w.spawn(":cursed_ring", at = p)
          first = w.spawn(":plain_ring", at = p)
          second = w.spawn(":plain_ring", at = p)

          p.do("wear cring")
          t.eq(p.equipped("finger_l").id, cursed.id)
          t.eq(p.equipped("finger_r"), None)

          out = p.do("wear pring")
          t.contains(out, "You wear a plain ring.")
          t.eq(p.equipped("finger_l").id, cursed.id)
          worn = p.equipped("finger_r").id
          t.true(worn == first.id or worn == second.id)

          out = p.do("wear pring")
          t.contains(out, "It won't budge. It must be cursed!")
          t.eq(p.equipped("finger_l").id, cursed.id)
          t.eq(p.equipped("finger_r").id, worn)
          t.eq([i.id for i in p.inventory()], [second.id if worn == first.id else first.id])

      def test_lift_transforms_item(t):
          w = t.world(packages = ["//skills", "//zones/curse_zone"])
          p = w.spawn_player("Heidi", at = ":sanctuary", skills = {"//skills:remove_curse": 100, "//skills:bestow_curse": 100})
          base_dex = p.dex
          amulet = w.spawn(":amulet_of_woe", at = p)
          amulet_id = amulet.id
          p.do("wear amulet")
          t.eq(p.dex, base_dex - 2)

          out = p.do("remove amulet")
          t.contains(out, "It won't budge. It must be cursed!")

          out = p.do("cast remove_curse amulet")
          t.contains(out, "You cast remove_curse on an amulet of woe.")

          worn = p.equipped("neck")
          t.ne(worn, None)
          t.eq(worn.id, amulet_id)
          t.eq(p.dex, base_dex + 1)

          out = p.do("remove amulet")
          t.contains(out, "You stop using an amulet of warding.")
          t.eq(p.equipped("neck"), None)
          t.eq(p.dex, base_dex)

      def test_bestow_curse(t):
          w = t.world(packages = ["//skills", "//zones/curse_zone"])
          p = w.spawn_player("Ivan", at = ":sanctuary", skills = {"//skills:remove_curse": 100, "//skills:bestow_curse": 100})
          w.spawn(":dagger", at = p)

          out = p.do("cast bestow_curse dagger")
          t.contains(out, "You cast bestow_curse on a dagger.")

          out = p.do("drop dagger")
          t.contains(out, "It won't budge. It must be cursed!")
      """,
    )

    val dir = WorldDir(tmp)
    val (module, error) = WorldModule.read(dir)
    assertNull(error)
    PluginLoader.load(dir, module).use { host ->
      assertEquals(emptyList(), host.warnings)
      val report = TestRunner(dir, plugins = host).run(listOf("//zones/curse_zone"))
      assertEquals(emptyList(), report.problems)
      assertEquals(11, report.results.size, report.results.joinToString("\n"))
      for (r in report.results) assertEquals(Status.PASS, r.status, "${r.name}: ${r.message}")
    }
  }
}
