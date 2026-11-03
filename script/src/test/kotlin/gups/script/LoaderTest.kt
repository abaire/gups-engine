package gups.script

import gups.core.Label
import gups.core.WORLD_TICK_PULSES
import gups.core.model.Direction
import gups.core.model.DoorState
import gups.core.model.Effect
import gups.core.model.EmptyAction
import gups.core.model.TrackSize
import gups.core.world.HookName
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoaderTest {
  private fun load(
    files: Map<String, String>,
    vararg pkgs: String = arrayOf("zones/z"),
  ): LoadResult = ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(pkgs.toList())

  @Test
  fun `global commands and exits may not take the world's alias words`() {
    val files =
      mapOf(
        "lib/package.star" to libPkg(),
        "lib/a.star" to
          "def f(ctx):\n    pass\nglobal_command(\"peer\", f)\nglobal_command(\"k\", f)",
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to "room(\"start\", exits = [exit(\"gaze\", \":start\")])",
      )
    val plain = load(files, "lib", "zones/z")
    assertTrue(plain.errors.isEmpty(), plain.report())
    val aliased =
      load(
        files +
          ("MODULE.gups" to
            TestWorlds.module("aliases = {\"peer\": \"look\", \"gaze\": \"look\"}")),
        "lib",
        "zones/z",
      )
    assertContains(only(aliased, "lib").message, "'peer' clashes with the engine command")
    assertContains(only(aliased).message, "'gaze' is a reserved keyword")
  }

  private fun only(r: LoadResult, pkg: String = "zones/z"): LoadError {
    val errs = r.errors.filter { it.pkg == pkg }
    assertTrue(errs.isNotEmpty(), "expected an error for $pkg, got: ${r.report()}")
    return errs.first()
  }

  @Test
  fun `declarations become frozen prototypes with resolved labels`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(", reset_world_ticks = 5"),
          "zones/z/rooms.star" to
            """
                    room("start", name = "Start", size = SIZE.large,
                        exits = [exit("north", to = ":hall", entry_index = -1,
                                      door = door(state = "locked", key = ":key"))],
                        spawns = [spawn(":rat", count = 2, at_index = 4)])
                    room("hall", exits = [exit("south", ":start")])
                """,
          "zones/z/things.star" to
            """
                    mob("rat", name = "a fat rat", vitals = vitals(hp = 5), loot = [loot(":key", chance = 0.5)])
                    item("key", name = "a brass key")
                    item("loaf", name = "a loaf of bread", plural = "loaves of bread", stackable = True)
                    skill("bite", range = "melee", effect = sequence(damage("1d4", type = "pierce"), stun(2)))
                """,
        )
      )
    assertTrue(r.ok, r.report())
    val p = r.prototypes()
    val start = p.rooms.getValue(Label("//zones/z:start"))
    assertEquals(TrackSize.LARGE, start.size)
    val north = start.exits.single()
    assertEquals(Direction.NORTH, north.direction)
    assertEquals(Label("//zones/z:hall"), north.to)
    assertEquals(-1, north.entryIndex)
    assertEquals(DoorState.LOCKED, north.door!!.state)
    assertEquals(Label("//zones/z:key"), north.door!!.key)
    assertEquals(2, start.spawns.single().count)
    assertEquals("hall", p.rooms.getValue(Label("//zones/z:hall")).name)
    assertEquals(listOf("fat", "rat"), p.mob(Label("//zones/z:rat")).keywords)
    assertEquals(listOf("brass", "key"), p.item(Label("//zones/z:key")).keywords)
    val loaf = p.item(Label("//zones/z:loaf"))
    assertEquals("loaves of bread", loaf.plural)
    assertEquals(listOf("loaf", "of", "bread"), loaf.keywords)
    assertEquals(600, p.zones.getValue("zones/z").resetPulses)
  }

  @Test
  fun `an item's plural can't be blank`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"coin\", plural = \" \", stackable = True)",
        )
      )
    assertContains(only(r).message, "item(plural): can't be blank")
  }

  @Test
  fun `item with wear = ring loads with template rules, rign fails with did you mean`() {
    val filesGood =
      mapOf(
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(),
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to "item(\"good_ring\", wear = \"ring\")\n",
      )
    val rGood = load(filesGood, "rules", "zones/z")
    assertTrue(rGood.errors.isEmpty(), rGood.report())

    val filesBad =
      mapOf(
        "rules/package.star" to TestWorlds.RULES_PACKAGE,
        "rules/combat.star" to TestWorlds.rules(),
        "zones/z/package.star" to zonePkg(),
        "zones/z/a.star" to "item(\"bad_ring\", wear = \"rign\")\n",
      )
    val rBad = load(filesBad, "rules", "zones/z")
    assertTrue("rules" !in rBad.failed, rBad.report())
    assertTrue("zones/z" in rBad.failed, rBad.report())
    val error = rBad.errors.single { it.kind == "slot" }
    assertEquals("zones/z", error.pkg)
    assertContains(error.file.orEmpty(), "a.star")
    assertEquals(1, error.line)
    assertContains(
      error.message,
      "item //zones/z:bad_ring: wear = 'rign' is not an equipment slot or slot family (did you mean 'ring'?)",
    )
    assertContains(error.message, "families are ring, hand")
  }

  @Test
  fun `a bad wield slot fails the declaring package and names wield`() {
    val r =
      load(
        mapOf(
          "rules/package.star" to TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to TestWorlds.rules(),
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"blade\", wield = \"hadn\")\n",
        ),
        "rules",
        "zones/z",
      )
    assertTrue("rules" !in r.failed, r.report())
    assertContains(
      r.errors.single { it.kind == "slot" }.message,
      "wield = 'hadn' is not an equipment slot or slot family (did you mean 'hand'?)",
    )
  }

  @Test
  fun `slot_cost below 1 fails load`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"sword\", wear = \"wield\", slot_cost = 0)",
        )
      )
    assertContains(only(r).message, "item(slot_cost): must be at least 1")
  }

  @Test
  fun `slot_cost without wear fails load`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"rock\", slot_cost = 2)",
        )
      )
    assertContains(only(r).message, "item(slot_cost): slot_cost = 2 needs wear or wield")
  }

  @Test
  fun `syntax errors point at the file and line with a caret`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to "room(\"start\",\n    name = \"x\"\n    desc = 1)",
        )
      )
    val e = only(r)
    assertEquals("syntax", e.kind)
    assertEquals("//zones/z:rooms.star", e.file)
    assertEquals(3, e.line)
    assertContains(e.render(), "^")
    assertTrue(r.packages.isEmpty())
  }

  @Test
  fun `lambdas are not supported by this Starlark`() {
    val r = load(mapOf("zones/z/package.star" to zonePkg(), "zones/z/a.star" to "f = lambda x: x"))
    assertContains(only(r).message, "lambda")
  }

  @Test
  fun `a different api_version names the gap and the changelog`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to "package(api_version = 2, kind = \"zone\", brand_new_arg = 1)"
        )
      )
    val e = only(r)
    assertEquals("api_version", e.kind)
    assertContains(e.message, "api_version 2")
    assertContains(e.message, "only api_version 1")
    assertContains(e.message, "CHANGELOG.md")
  }

  @Test
  fun `unknown arguments and hook typos get suggestions`() {
    val typo =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "room(\"start\", nme = \"x\")",
        )
      )
    assertContains(only(typo).message, "unexpected argument 'nme' (did you mean 'name'?)")
    val hook =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "def f(ctx):\n    pass\nroom(\"start\", on_entr = f)",
        )
      )
    assertContains(only(hook).message, "unknown hook 'on_entr' (did you mean 'on_enter'?)")
    val kind =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "def f(ctx):\n    pass\nroom(\"start\", on_tell = f)",
        )
      )
    assertContains(only(kind).message, "hook 'on_tell' can't be attached to a room")
  }

  @Test
  fun `hooks must be top-level functions`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
                    def make():
                        def inner(ctx):
                            pass
                        return inner
                    room("start", on_enter = make())
                """,
        )
      )
    // Starlark itself refuses nested functions; lambdas are refused too (see above).
    assertContains(only(r).message, "nested functions are not allowed")
    val ok =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "def _greet(ctx):\n    pass\nroom(\"start\", on_enter = _greet)",
        )
      )
    assertTrue(ok.ok, ok.report())
    assertEquals("_greet", ok.hooks(Label("//zones/z:start")).getValue(HookName.ON_ENTER).name)
  }

  @Test
  fun `declaration names are unique across kinds`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "room(\"thing\")\nitem(\"thing\")",
        )
      )
    assertContains(only(r).message, "'thing' is already declared")
  }

  @Test
  fun `unresolved labels fail the package and its dependents`() {
    val r =
      load(
        mapOf(
          "zones/a/package.star" to zonePkg(),
          "zones/a/a.star" to "room(\"start\", exits = [exit(\"east\", \"//zones/b:start\")])",
          "zones/b/package.star" to zonePkg(),
          "zones/b/b.star" to
            "room(\"start\", exits = [exit(\"west\", \"//zones/a:start\"), exit(\"north\", \":nowhere\")])",
        ),
        "zones/a",
      )
    val b = only(r, "zones/b")
    assertEquals("label", b.kind)
    assertContains(b.message, "//zones/b:nowhere doesn't resolve")
    assertEquals("//zones/b:b.star", b.file)
    assertEquals("dependency", only(r, "zones/a").kind)
  }

  @Test
  fun `referenced packages load automatically and kinds are checked`() {
    val r =
      load(
        mapOf(
          "zones/a/package.star" to zonePkg(),
          "zones/a/a.star" to "room(\"start\", exits = [exit(\"east\", \"//zones/b:rat\")])",
          "zones/b/package.star" to zonePkg(),
          "zones/b/b.star" to "mob(\"rat\")",
        ),
        "zones/a",
      )
    assertContains(only(r, "zones/a").message, "//zones/b:rat is a mob, not a room")
    assertTrue("zones/b" in r.packages)
  }

  @Test
  fun `load pulls in library files and detects cycles`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            "load(\"//lib:names.star\", \"title\")\nroom(\"start\", name = title(\"hall\"))",
          "lib/package.star" to libPkg(),
          "lib/names.star" to "def title(s):\n    return s.upper()",
        )
      )
    assertTrue(r.ok, r.report())
    assertEquals("HALL", r.prototypes().rooms.getValue(Label("//zones/z:start")).name)
    assertTrue("lib" in r.packages)

    val cycle =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "load(\":b.star\", \"y\")\nx = 1",
          "zones/z/b.star" to "load(\":a.star\", \"x\")\ny = 1",
        )
      )
    assertTrue(cycle.errors.any { "load cycle" in it.message }, cycle.report())
  }

  @Test
  fun `zone reset intervals are whole world ticks`() {
    val ok = load(mapOf("zones/z/package.star" to zonePkg(", reset_world_ticks = 3")))
    assertEquals(3 * WORLD_TICK_PULSES, ok.prototypes().zones.getValue("zones/z").resetPulses)
    val pulses = load(mapOf("zones/z/package.star" to zonePkg(", reset_pulses = 7200")))
    assertContains(only(pulses).message, "reset_pulses")
    val zero = load(mapOf("zones/z/package.star" to zonePkg(", reset_world_ticks = 0")))
    assertContains(only(zero).message, "package(reset_world_ticks): must be at least 1")
  }

  @Test
  fun `privileges and namespaces are enforced`() {
    val zoneCmd =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "def f(ctx):\n    pass\nglobal_command(\"dance\", f)",
        )
      )
    assertContains(only(zoneCmd).message, "only lib and rules packages")
    val shared =
      load(mapOf("zones/z/package.star" to zonePkg(", shared_namespaces = [\"faction\"]")))
    assertContains(
      only(shared).message,
      "only lib and rules packages may declare shared namespaces",
    )
    val clash =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "room(\"start\", exits = [exit(\"east\", \"//other/z:start\")])",
          "other/z/package.star" to zonePkg(),
          "other/z/a.star" to "room(\"start\")",
        )
      )
    assertContains(only(clash).message, "attribute namespace 'z' is used by more than one package")
    val shadow =
      load(
        mapOf(
          "lib/package.star" to libPkg(),
          "lib/a.star" to "def f(ctx):\n    pass\nglobal_command(\"look\", f)",
        ),
        "lib",
      )
    assertContains(only(shadow, "lib").message, "clashes with the engine command")
  }

  @Test
  fun `a global command named after a direction or its alias fails to load`() {
    for (verb in listOf("north", "ne")) {
      val dir =
        load(
          mapOf(
            "lib/package.star" to libPkg(),
            "lib/a.star" to "def f(ctx):\n    pass\nglobal_command(\"$verb\", f)",
          ),
          "lib",
        )
      assertContains(only(dir, "lib").message, "'$verb' clashes with the direction")
    }
  }

  @Test
  fun `an exit named after an engine or global command fails to load`() {
    val engine =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "room(\"start\", exits = [exit(\"look\", \":start\")])",
        )
      )
    assertContains(only(engine).message, "'look' is a reserved keyword")
    val alias =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "room(\"start\", exits = [exit(\"inv\", \":start\")])",
          "MODULE.gups" to TestWorlds.module("aliases = {\"inv\": \"inventory\"}"),
        )
      )
    assertContains(only(alias).message, "'inv' is a reserved keyword")
    val global =
      load(
        mapOf(
          "lib/package.star" to libPkg(),
          "lib/a.star" to "def f(ctx):\n    pass\nglobal_command(\"dance\", f)",
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "room(\"start\", exits = [exit(\"dance\", \":start\")])",
        ),
        "lib",
        "zones/z",
      )
    assertContains(only(global).message, "exit 'dance' of //zones/z:start is a reserved keyword")
    val custom =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "room(\"start\", exits = [exit(\"portal\", \":start\")])",
        )
      )
    assertTrue(custom.errors.isEmpty(), custom.errors.toString())
  }

  @Test
  fun `package star holds only package() and must call it`() {
    val missing = load(mapOf("zones/z/package.star" to "x = 1"))
    assertContains(only(missing).message, "must call package(")
    val room = load(mapOf("zones/z/package.star" to zonePkg() + "room(\"start\")"))
    assertContains(only(room).message, "not in package.star")
    val nopkg = load(mapOf("zones/y/package.star" to zonePkg()), "zones/nope")
    assertEquals("missing", only(nopkg, "zones/nope").kind)
  }

  @Test
  fun `top-level evaluation has a step limit`() {
    val r =
      ScriptLoader(
          WorldDir(
            tempWorld(
              mapOf(
                "zones/z/package.star" to zonePkg(),
                "zones/z/a.star" to
                  "def spin():\n    for i in range(100000000):\n        pass\nspin()",
              )
            )
          ),
          ScriptLimits.HARNESS.copy(loadSteps = 10_000),
        )
        .load(listOf("zones/z"))
    assertEquals("step_limit", only(r).kind)
  }

  @Test
  fun `mob behaviors must be behavior values`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "mob(\"rat\", behaviors = [1])",
        )
      )
    assertContains(
      only(r).message,
      "mob(behaviors): expected a behavior such as wander(...), got int",
    )
    val empty =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "mob(\"rat\", behaviors = [])",
        )
      )
    assertTrue(empty.ok, empty.report())
    assertNull(empty.prototypes().mob(Label("//zones/z:rat")).think)
    assertNotNull(empty.packages["zones/z"])
  }

  @Test
  fun `dispel takes structured criteria and needs at least one`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            "skill(\"cure\", effect = dispel(name = \"poison*\", flag = [\"poison\"], count = 2))",
        )
      )
    assertTrue(r.ok, r.report())
    assertEquals(
      Effect.Dispel(name = "poison*", flags = setOf("poison"), count = 2),
      r.prototypes().skill(Label("//zones/z:cure")).effect,
    )
    val empty =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "skill(\"cure\", effect = dispel())",
        )
      )
    assertContains(only(empty).message, "dispel: give name, flag or all = True")
  }

  @Test
  fun `plugin globals are predeclared, and a package may not redefine one`() {
    val weather = StructValue(mapOf("level" to net.starlark.java.eval.StarlarkInt.of(3)))
    val dir =
      WorldDir(
        tempWorld(
          mapOf(
            "zones/z/package.star" to zonePkg(),
            "zones/z/rooms.star" to
              "room(\"a\", name = \"A\", desc = \"x\" * weather.level, flags = [\"dead_end_ok\"])",
            "zones/bad/package.star" to zonePkg(),
            "zones/bad/x.star" to "x = 1\nweather = 1\n",
          )
        )
      )
    val loader =
      ScriptLoader(dir, ScriptLimits.HARNESS, pluginGlobals = mapOf("weather" to weather))
    val ok = loader.load(listOf("zones/z"))
    assertTrue(ok.ok, ok.report())
    val bad = loader.load(listOf("zones/bad"))
    val e = bad.errors.single { it.kind == "plugin" }
    assertEquals("plugin", e.kind)
    assertEquals("cannot define 'weather': it is the namespace of plugin 'weather'", e.message)
    assertEquals(2, e.line)
  }

  @Test
  fun `a package may not own a plugin's attribute namespace`() {
    val dir =
      WorldDir(
        tempWorld(
          mapOf(
            "zones/weather/package.star" to zonePkg(),
            "lib/sky/package.star" to libPkg(", shared_namespaces = [\"weather\"]"),
          )
        )
      )
    val loader =
      ScriptLoader(
        dir,
        ScriptLimits.HARNESS,
        pluginGlobals = mapOf("weather" to StructValue(emptyMap())),
      )
    for (pkg in listOf("zones/weather", "lib/sky")) {
      val e = loader.load(listOf(pkg)).errors.single { it.kind == "namespace" }
      assertEquals(pkg, e.pkg)
      assertEquals("attribute namespace 'weather' is the namespace of plugin 'weather'", e.message)
    }
  }

  @Test
  fun `item with unknown vital or damage type in delta maps fails with did-you-mean`() {
    val badVital =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"i\", wear = \"finger_l\", max_vital_deltas = vitals(hpo = 5))",
        )
      )
    assertContains(only(badVital).message, "unknown vital 'hpo' (did you mean 'hp'?)")

    val badResist =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"i\", wear = \"finger_l\", resist_deltas = resists(frie = 5))",
        )
      )
    assertContains(only(badResist).message, "unknown damage type 'frie' (did you mean 'fire'?)")
  }

  @Test
  fun `item with vital or resist deltas without wear fails to load`() {
    val unwornVital =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"i\", max_vital_deltas = vitals(hp = 5))",
        )
      )
    assertContains(only(unwornVital).message, "needs a wear slot")

    val unwornResist =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"i\", resist_deltas = resists(fire = 5))",
        )
      )
    assertContains(only(unwornResist).message, "needs a wear slot")
  }

  @Test
  fun `item charges below 1 fails load`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"wand\", charges = 0)",
        )
      )
    assertContains(only(r).message, "item(charges): must be at least 1")
  }

  @Test
  fun `item empty without charges fails load`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"wand\", empty = \"keep\")",
        )
      )
    assertContains(only(r).message, "item(empty): needs charges")
  }

  @Test
  fun `stackable item with charges not equal to 1 fails load`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"potion\", stackable = True, charges = 2)",
        )
      )
    assertContains(only(r).message, "item(charges): a stackable item can only have 1 charge")
  }

  @Test
  fun `stackable item with empty keep fails load`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"potion\", stackable = True, charges = 1, empty = \"keep\")",
        )
      )
    assertContains(only(r).message, "item(empty): a stackable item can't keep an empty item")
  }

  @Test
  fun `item empty with unknown variant fails load`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"wand\", charges = 3, empty = \"dust\")",
        )
      )
    assertContains(
      only(r).message,
      "item(empty): 'dust' is not 'destroy', 'keep' or one of this item's variants",
    )
  }

  @Test
  fun `item with charges and empty variant loads successfully`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            item("spent_wand")
            item("wand", charges = 3, empty = "spent", variants = {"spent": ":spent_wand"})
            """,
        )
      )
    assertTrue(r.ok, r.report())
    val wand = r.prototypes().item(Label("//zones/z:wand"))
    assertEquals(3, wand.charges)
    assertEquals(EmptyAction.Variant("spent"), wand.empty)
  }

  @Test
  fun `item empty destroy without charges loads`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"lamp\", empty = \"destroy\")",
        )
      )
    assertTrue(r.ok, r.report())
    val lamp = r.prototypes().item(Label("//zones/z:lamp"))
    assertEquals(null, lamp.charges)
    assertEquals(EmptyAction.Destroy, lamp.empty)
  }

  @Test
  fun `item use_target without use_effect fails load`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"wand\", use_target = \"enemy\")",
        )
      )
    assertContains(only(r).message, "item(use_target): needs use_effect")
  }

  @Test
  fun `item use_target self without use_effect loads`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"lamp\", use_target = \"self\")",
        )
      )
    assertTrue(r.ok, r.report())
    val lamp = r.prototypes().item(Label("//zones/z:lamp"))
    assertEquals(gups.core.model.TargetKind.SELF, lamp.useTarget)
  }

  @Test
  fun `item negative use_lag fails load`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"wand\", use_lag = -1)",
        )
      )
    assertContains(only(r).message, "item(use_lag): can't be negative")
  }

  @Test
  fun `item use_target item with heal fails load naming heal`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            "item(\"potion\", use_effect = heal(dice = \"1d4\", vital = \"hp\"), use_target = \"item\")",
        )
      )
    val msg = only(r).message
    assertContains(msg, "item(use_effect):")
    assertContains(msg, "heal")
    assertContains(
      msg,
      "acts on actors; an item use_effect may use apply_affect and plugin primitives",
    )
  }

  @Test
  fun `item use_target self with apply_affect worn fails load`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            "item(\"wand\", use_effect = apply_affect(\"glow\", 10, applies = \"worn\"), use_target = \"self\")",
        )
      )
    assertContains(
      only(r).message,
      "item(use_effect): apply_affect(applies) is only for use_target = 'item'",
    )
  }

  @Test
  fun `item use_target self with apply_affect without duration fails load`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to "item(\"wand\", use_effect = apply_affect(\"glow\", None))",
        )
      )
    assertContains(
      only(r).message,
      "item(use_effect): apply_affect(duration) can be None only with use_target = 'item'",
    )
  }

  @Test
  fun `item with valid use_effect sequence loads`() {
    val r =
      load(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/a.star" to
            """
            item(
              "salve",
              use_effect = sequence(heal(dice = "1d4", vital = "hp"), apply_affect("bless", 10)),
              use_lag = 2,
            )
            """,
        )
      )
    assertTrue(r.ok, r.report())
    val salve = r.prototypes().item(Label("//zones/z:salve"))
    assertNotNull(salve.useEffect)
    assertEquals(gups.core.model.TargetKind.SELF, salve.useTarget)
    assertEquals(2, salve.useLag)
  }
}
