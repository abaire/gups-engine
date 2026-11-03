package gups.script

import gups.core.AttrValue
import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.world.actorSlots
import gups.core.world.moveItem
import gups.core.world.spawnItem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemEffectsScriptTest {
  private fun zone(rooms: String, vararg more: Pair<String, String>) =
    mapOf("zones/z/package.star" to zonePkg(), "zones/z/content.star" to rooms) + more

  private fun load(files: Map<String, String>, vararg pkgs: String = arrayOf("zones/z")) =
    ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(pkgs.toList())

  @Test
  fun `carried item affect counts through custody tree and stops when moved to ground`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          item("backpack", wear = "held", container = container())
          item("bag", container = container())
          item("charm", affects = [item_affect("luck", applies = "carried", stat_deltas = stats(dex = -1))])
          """
        )
      )
    val p = f.player()
    val baseDex = p.stat("dex")
    val backpack = f.w.spawnItem(Label("//zones/z:backpack"), ItemLocation.Carried(p.id))
    val bag = f.w.spawnItem(Label("//zones/z:bag"), ItemLocation.Contained(backpack.id))
    f.w.spawnItem(Label("//zones/z:charm"), ItemLocation.Contained(bag.id))

    assertEquals(baseDex - 1, p.stat("dex"))

    f.w.moveItem(bag, ItemLocation.InRoom(p.room.id, 0))
    assertEquals(baseDex, p.stat("dex"))
  }

  @Test
  fun `item affect and variant declaration errors fail to load`() {
    val missingLabel =
      load(zone("room(\"start\")\nitem(\"thing\", variants = {\"uncursed\": \":missing\"})"))
    assertFalse(missingLabel.ok)
    assertContains(missingLabel.report(), "declares no item named 'missing'")

    val badVariantName =
      load(
        zone(
          "room(\"start\")\nitem(\"target\")\nitem(\"thing\", variants = {\"Bad\": \":target\"})"
        )
      )
    assertFalse(badVariantName.ok)
    assertContains(badVariantName.report(), "'Bad' is not a valid variant name")

    val wornWithModsOnSlotless =
      load(
        zone(
          """
          def f(ctx, base):
              return base
          room("start")
          item("thing", affects = [item_affect("mod", applies = "worn", modifiers = {"to_hit": f})])
          """
        )
      )
    assertFalse(wornWithModsOnSlotless.ok)
    assertContains(
      wornWithModsOnSlotless.report(),
      "item(): a worn item_affect with modifiers needs a wear slot",
    )

    val stunnedFlag =
      load(
        zone(
          "room(\"start\")\nitem(\"thing\", affects = [item_affect(\"stun\", flags = [\"stunned\"])])"
        )
      )
    assertFalse(stunnedFlag.ok)
    assertContains(
      stunnedFlag.report(),
      "item_affect(flags): 'stunned' isn't allowed; item affects can't stun",
    )

    val nonItemAffectInAffects =
      load(zone("room(\"start\")\nitem(\"thing\", affects = [\"not_an_affect\"])"))
    assertFalse(nonItemAffectInAffects.ok)
    assertContains(
      nonItemAffectInAffects.report(),
      "item(affects): expected item_affect(...), got string",
    )
  }

  @Test
  fun `a carried item_affect with modifiers needs no wear slot`() {
    val r =
      load(
        zone(
          """
          def f(ctx, base):
              return base
          room("start")
          item("thing", affects = [item_affect("mod", applies = "carried", modifiers = {"to_hit": f})])
          """
        )
      )
    assertTrue(r.ok, r.report())
  }

  @Test
  fun `item_affect names must be non-blank and unique per item`() {
    val blank = load(zone("room(\"start\")\nitem(\"thing\", affects = [item_affect(\" \")])"))
    assertFalse(blank.ok)
    assertContains(blank.report(), "item_affect(name): can't be blank")

    val twice =
      load(
        zone("room(\"start\")\nitem(\"thing\", affects = [item_affect(\"a\"), item_affect(\"a\")])")
      )
    assertFalse(twice.ok)
    assertContains(twice.report(), "item(affects): 'a' appears twice")
  }

  @Test
  fun `a key with affects is listed by inventory, not by keyring`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          item("special_key", name = "a special key", kind = "key", affects = [item_affect("magic", applies = "carried")])
          """
        )
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:special_key"), ItemLocation.Carried(p.id))

    f.input(p, "inventory")
    val inv = f.out(p)
    assertContains(inv, "a special key")

    f.input(p, "keyring")
    val kr = f.out(p)
    assertContains(kr, "Your keyring is empty.")
  }

  @Test
  fun `item skill attaches worn affect to item which affects holder when worn and expires`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          item("sword", wear = "wield")
          skill("enchant", target = "item", effect = apply_affect("keen", 50, applies = "worn", stat_deltas = stats(dex = 1)))
          """
        )
      )
    val p = f.player()
    val baseDex = p.stat("dex")
    val sword = f.w.spawnItem(Label("//zones/z:sword"), ItemLocation.Carried(p.id))
    p.skills[Label("//zones/z:enchant")] = 100

    f.input(p, "cast enchant sword")
    f.run()

    assertEquals(1, sword.affects.size)
    assertEquals("keen", sword.affects[0].name)
    assertEquals(baseDex, p.stat("dex"))

    f.input(p, "wield sword")
    f.run()
    assertEquals(baseDex + 1, p.stat("dex"))

    f.run(50)
    assertEquals(baseDex, p.stat("dex"))
    assertEquals(0, sword.affects.size)
  }

  @Test
  fun `permanent item affect with duration None`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          item("sword", wear = "wield")
          skill("perma_enchant", target = "item", effect = apply_affect("perma", None, applies = "worn", stat_deltas = stats(dex = 1)))
          """
        )
      )
    val p = f.player()
    val sword = f.w.spawnItem(Label("//zones/z:sword"), ItemLocation.Carried(p.id))
    p.skills[Label("//zones/z:perma_enchant")] = 100

    f.input(p, "cast perma_enchant sword")
    f.run()

    assertEquals(1, sword.affects.size)
    assertEquals(Long.MAX_VALUE, sword.affects[0].expiresAt)

    val enemyPerm =
      load(
        zone(
          """
          room("start")
          skill("bad_enemy", target = "enemy", effect = apply_affect("perma", None))
          """
        )
      )
    assertFalse(enemyPerm.ok)
    assertContains(
      enemyPerm.report(),
      "skill(effect): apply_affect(duration) can be None only on an item skill",
    )
  }

  @Test
  fun `disallowed effect primitives on item and enemy skills fail to load`() {
    val damageOnItem =
      load(
        zone(
          """
          room("start")
          skill("bad_item_dmg", target = "item", effect = damage("1d6"))
          """
        )
      )
    assertFalse(damageOnItem.ok)
    assertContains(
      damageOnItem.report(),
      "skill(effect): damage acts on actors; an item skill's effect may use apply_affect and plugin primitives",
    )

    val seqDispelOnItem =
      load(
        zone(
          """
          room("start")
          skill("bad_seq", target = "item", effect = sequence(apply_affect("keen", 10), dispel(all = True)))
          """
        )
      )
    assertFalse(seqDispelOnItem.ok)
    assertContains(
      seqDispelOnItem.report(),
      "skill(effect): dispel acts on actors; an item skill's effect may use apply_affect and plugin primitives",
    )

    val carriedOnEnemy =
      load(
        zone(
          """
          room("start")
          skill("bad_enemy_applies", target = "enemy", effect = apply_affect("curse", 10, applies = "carried"))
          """
        )
      )
    assertFalse(carriedOnEnemy.ok)
    assertContains(
      carriedOnEnemy.report(),
      "skill(effect): apply_affect(applies) is only for item skills",
    )

    val stunnedOnItem =
      load(
        zone(
          """
          room("start")
          skill("stun_item", target = "item", effect = apply_affect("stun", 10, flags = ["stunned"]))
          """
        )
      )
    assertFalse(stunnedOnItem.ok)
    assertContains(stunnedOnItem.report(), "skill(effect): item affects can't stun")
  }

  @Test
  fun `ctx apply_affect and remove_affect on item`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _test(ctx):
              ctx.apply_affect(ctx.self, "glow", None, applies = "carried", stat_deltas = stats(dex = 1))

          def _remove(ctx):
              removed = ctx.remove_affect(ctx.self, "glow")
              ctx.set_attr(ctx.self, "removed", "true" if removed else "false")

          room("start")
          item("wand", commands = {"test": _test, "rm": _remove})
          """
        )
      )
    val p = f.player()
    val baseDex = p.stat("dex")
    val wand = f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Carried(p.id))

    f.input(p, "test")
    f.run()
    assertEquals(baseDex + 1, p.stat("dex"))

    f.input(p, "rm")
    f.run()
    assertEquals(AttrValue.S("true"), wand.customAttributes["z:removed"])
    assertEquals(baseDex, p.stat("dex"))
  }

  @Test
  fun `ctx apply_affect on actor with applies or None duration raises script error`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _bad_applies(ctx):
              ctx.apply_affect(ctx.actor, "x", 5, applies = "worn")

          def _bad_duration(ctx):
              ctx.apply_affect(ctx.actor, "x", None)

          room("start")
          item("wand", commands = {"bad_applies": _bad_applies, "bad_dur": _bad_duration})
          """
        )
      )
    val p = f.player()
    f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Carried(p.id))

    f.input(p, "bad_applies")
    f.run()
    assertContains(
      f.w.diagnostics.last().message,
      "apply_affect(applies): only item affects take applies",
    )

    f.input(p, "bad_dur")
    f.run()
    assertContains(
      f.w.diagnostics.last().message,
      "apply_affect(duration): an actor's affect needs a duration",
    )
  }

  @Test
  fun `item affect on_expire sees ctx self as the item and ctx affect name`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _on_exp(ctx):
              ctx.set_attr(ctx.self, "expired", ctx.affect.name)
              ctx.set_attr(ctx.self, "self_name", ctx.self.name)

          def _test(ctx):
              ctx.apply_affect(ctx.self, "fade", 1, applies = "carried", on_expire = _on_exp)

          room("start")
          item("wand", name = "a magic wand", commands = {"test": _test})
          """
        )
      )
    val p = f.player()
    val wand = f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Carried(p.id))

    f.input(p, "test")
    f.run()
    assertEquals(1, wand.affects.size)

    f.run(2)
    assertEquals(0, wand.affects.size)
    assertEquals(AttrValue.S("fade"), wand.customAttributes["z:expired"])
    assertEquals(AttrValue.S("a magic wand"), wand.customAttributes["z:self_name"])
  }

  @Test
  fun `add_tag and remove_tag return whether effective tags changed and update tags and has_tag`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _test_tags(ctx):
              r1 = ctx.add_tag(ctx.self, "shiny")
              has1 = ctx.self.has_tag("shiny")
              r2 = ctx.add_tag(ctx.self, "shiny")
              tags_after_add = list(ctx.self.tags)
              r3 = ctx.remove_tag(ctx.self, "shiny")
              has2 = ctx.self.has_tag("shiny")
              r4 = ctx.remove_tag(ctx.self, "shiny")
              tags_after_rm = list(ctx.self.tags)
              ctx.set_attr(ctx.self, "r1", str(r1))
              ctx.set_attr(ctx.self, "has1", str(has1))
              ctx.set_attr(ctx.self, "r2", str(r2))
              ctx.set_attr(ctx.self, "tags1", ",".join(tags_after_add))
              ctx.set_attr(ctx.self, "r3", str(r3))
              ctx.set_attr(ctx.self, "has2", str(has2))
              ctx.set_attr(ctx.self, "r4", str(r4))
              ctx.set_attr(ctx.self, "tags2", ",".join(tags_after_rm))

          room("start")
          item("wand", tags = ["metal"], commands = {"test": _test_tags})
          """
        )
      )
    val p = f.player()
    val wand = f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Carried(p.id))

    f.input(p, "test")
    f.run()

    assertEquals(AttrValue.S("True"), wand.customAttributes["z:r1"])
    assertEquals(AttrValue.S("True"), wand.customAttributes["z:has1"])
    assertEquals(AttrValue.S("False"), wand.customAttributes["z:r2"])
    assertEquals(AttrValue.S("metal,shiny"), wand.customAttributes["z:tags1"])
    assertEquals(AttrValue.S("True"), wand.customAttributes["z:r3"])
    assertEquals(AttrValue.S("False"), wand.customAttributes["z:has2"])
    assertEquals(AttrValue.S("False"), wand.customAttributes["z:r4"])
    assertEquals(AttrValue.S("metal"), wand.customAttributes["z:tags2"])
  }

  @Test
  fun `ctx transform keeps item id and updates holder stats`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _cleanse(ctx):
              ctx.set_attr(ctx.self, "old_id", ctx.self.id)
              ctx.transform(ctx.self, ctx.self.variants["uncursed"])
              ctx.set_attr(ctx.self, "new_id", ctx.self.id)

          room("start")
          item(
              "cursed_blade",
              wear = "wield",
              affects = [item_affect("curse", applies = "worn", stat_deltas = stats(dex = -2))],
              variants = {"uncursed": ":blessed_blade"},
              commands = {"cleanse": _cleanse},
          )
          item(
              "blessed_blade",
              wear = "wield",
              affects = [item_affect("blessing", applies = "worn", stat_deltas = stats(dex = 2))],
          )
          """
        )
      )
    val p = f.player()
    val baseDex = p.stat("dex")
    val blade = f.w.spawnItem(Label("//zones/z:cursed_blade"), ItemLocation.Carried(p.id))

    f.input(p, "wield blade")
    f.run()
    assertEquals(baseDex - 2, p.stat("dex"))

    f.input(p, "cleanse")
    f.run()

    assertEquals(AttrValue.I(blade.id.v), blade.customAttributes["z:old_id"])
    assertEquals(AttrValue.I(blade.id.v), blade.customAttributes["z:new_id"])
    assertEquals(baseDex + 2, p.stat("dex"))
  }

  @Test
  fun `item affects lists proto affect then instance affect with correct views`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _test(ctx):
              ctx.apply_affect(ctx.self, "keen", 50, applies = "worn")
              affs = ctx.self.affects
              ctx.set_attr(ctx.self, "count", len(affs))
              p_aff = affs[0]
              i_aff = affs[1]
              ctx.set_attr(ctx.self, "p_name", p_aff.name)
              ctx.set_attr(ctx.self, "p_origin", p_aff.origin)
              ctx.set_attr(ctx.self, "p_exp", "none" if p_aff.expires_at == None else "some")
              ctx.set_attr(ctx.self, "p_host_same", str(p_aff.host.id == ctx.self.id))
              ctx.set_attr(ctx.self, "i_name", i_aff.name)
              ctx.set_attr(ctx.self, "i_origin", i_aff.origin)
              ctx.set_attr(ctx.self, "i_exp", "some" if i_aff.expires_at != None else "none")
              ctx.set_attr(ctx.self, "i_host_same", str(i_aff.host.id == ctx.self.id))

          room("start")
          item(
              "sword",
              wear = "wield",
              affects = [item_affect("sharp", applies = "worn")],
              commands = {"test": _test},
          )
          """
        )
      )
    val p = f.player()
    val sword = f.w.spawnItem(Label("//zones/z:sword"), ItemLocation.Carried(p.id))

    f.input(p, "test")
    f.run()

    assertEquals(AttrValue.I(2), sword.customAttributes["z:count"])
    assertEquals(AttrValue.S("sharp"), sword.customAttributes["z:p_name"])
    assertEquals(AttrValue.S("proto"), sword.customAttributes["z:p_origin"])
    assertEquals(AttrValue.S("none"), sword.customAttributes["z:p_exp"])
    assertEquals(AttrValue.S("True"), sword.customAttributes["z:p_host_same"])
    assertEquals(AttrValue.S("keen"), sword.customAttributes["z:i_name"])
    assertEquals(AttrValue.S("instance"), sword.customAttributes["z:i_origin"])
    assertEquals(AttrValue.S("some"), sword.customAttributes["z:i_exp"])
    assertEquals(AttrValue.S("True"), sword.customAttributes["z:i_host_same"])
  }

  @Test
  fun `actor flags and has_flag include flags from held item affects`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _check(ctx):
              ctx.set_attr(ctx.self, "in_flags", str("glowing" in ctx.actor.flags))
              ctx.set_attr(ctx.self, "has_flag", str(ctx.actor.has_flag("glowing")))

          room("start")
          item(
              "orb",
              affects = [item_affect("light", applies = "carried", flags = ["glowing"])],
              commands = {"check": _check},
          )
          """
        )
      )
    val p = f.player()
    val orb = f.w.spawnItem(Label("//zones/z:orb"), ItemLocation.Carried(p.id))

    f.input(p, "check")
    f.run()

    assertEquals(AttrValue.S("True"), orb.customAttributes["z:in_flags"])
    assertEquals(AttrValue.S("True"), orb.customAttributes["z:has_flag"])
  }

  @Test
  fun `ctx item affect and transform errors are script errors`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _bad_scope(ctx):
              ctx.apply_affect(ctx.self, "x", 5, applies = "sideways")

          def _stun(ctx):
              ctx.apply_affect(ctx.self, "x", 5, flags = ["stunned"])

          def _bad_label(ctx):
              ctx.transform(ctx.self, ":nope")

          def _remove_missing(ctx):
              ctx.set_attr(ctx.self, "removed", str(ctx.remove_affect(ctx.self, "nothing")))

          room("start")
          item(
              "wand",
              commands = {
                  "bad_scope": _bad_scope,
                  "stun": _stun,
                  "bad_label": _bad_label,
                  "rm_missing": _remove_missing,
              },
          )
          """
        )
      )
    val p = f.player()
    val wand = f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Carried(p.id))

    f.input(p, "bad_scope")
    f.run()
    assertContains(f.w.diagnostics.last().message, "apply_affect(applies)")

    f.input(p, "stun")
    f.run()
    assertContains(f.w.diagnostics.last().message, "item affects can't stun")
    assertTrue(wand.affects.isEmpty())

    f.input(p, "bad_label")
    f.run()
    assertContains(f.w.diagnostics.last().message, "transform(label): there is no item")
    assertEquals(Label("//zones/z:wand"), wand.protoLabel)

    f.input(p, "rm_missing")
    f.run()
    assertEquals(AttrValue.S("False"), wand.customAttributes["z:removed"])
  }

  @Test
  fun `an untimed equip affect has no expiry in its affect view`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _check(ctx):
              a = [x for x in ctx.actor.affects if "no_regen" in x.flags][0]
              ctx.set_attr(ctx.self, "exp", str(a.expires_at))
              ctx.set_attr(ctx.self, "left", str(a.remaining))
              ctx.set_attr(ctx.self, "applies", str(a.applies))

          room("start")
          item("blade", wear = "wield", equip_affects = ["no_regen"], commands = {"check": _check})
          """
        )
      )
    val p = f.player()
    val blade = f.w.spawnItem(Label("//zones/z:blade"), ItemLocation.Carried(p.id))

    f.input(p, "wield blade")
    f.run()
    f.input(p, "check")
    f.run()

    assertEquals(AttrValue.S("None"), blade.customAttributes["z:exp"])
    assertEquals(AttrValue.S("None"), blade.customAttributes["z:left"])
    assertEquals(AttrValue.S("None"), blade.customAttributes["z:applies"])
  }

  @Test
  fun `vital and resist deltas on affects and views, and inactive flag flipping`() {
    val f =
      ScriptFixture(
        mapOf(
          "zones/z/package.star" to zonePkg(),
          "zones/z/content.star" to
            """
            def _buff(ctx):
                ctx.apply_affect(ctx.actor, "boost", 10, max_vital_deltas = vitals(hp = 5), resist_deltas = resists(fire = 10))

            def _debuff(ctx):
                ctx.apply_affect(ctx.actor, "weakness", 10, stat_deltas = stats(str = -5))

            def _clean(ctx):
                ctx.remove_affect(ctx.actor, "weakness")

            def _inspect(ctx):
                ctx.set_attr(ctx.self, "max_hp", str(ctx.actor.max_hp))
                ctx.set_attr(ctx.self, "fire_res", str(ctx.actor.resists.fire))
                ctx.set_attr(ctx.self, "has_wield", str("wield" in ctx.actor.slots))

                aff = [x for x in ctx.actor.affects if x.name == "boost"][0]
                ctx.set_attr(ctx.self, "aff_hp", str(aff.max_vital_deltas.hp))
                ctx.set_attr(ctx.self, "aff_fire", str(aff.resist_deltas.fire))

                ring = ctx.actor.equipped("finger_l")
                ctx.set_attr(ctx.self, "ring_hp", str(ring.max_vital_deltas.hp))
                ctx.set_attr(ctx.self, "ring_slash", str(ring.resist_deltas.slash))
                ctx.set_attr(ctx.self, "ring_inactive", str(ring.inactive))

                shield = ctx.actor.equipped("offhand")
                ctx.set_attr(ctx.self, "shield_inactive", str(shield.inactive))

            room("start")
            item("ring", wear = "finger_l", max_vital_deltas = vitals(hp = 10), resist_deltas = resists(slash = 20))
            item("greatsword", wear = "hand", tags = ["heavy"])
            item("shield", wear = "offhand")
            item("tester", commands = {"buff": _buff, "debuff": _debuff, "clean": _clean, "inspect": _inspect})
            """,
          "rules/package.star" to gups.script.testing.TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to
            gups.script.testing.TestWorlds.rules(
              """
              def slot_cost(ctx):
                  if ctx.item.has_tag("heavy"):
                      return 2 if ctx.actor.str < 10 else 1
                  return ctx.item.slot_cost
              """
            ),
        )
      )
    val p = f.player(hp = 50)
    val tester = f.w.spawnItem(Label("//zones/z:tester"), ItemLocation.Carried(p.id))
    f.w.spawnItem(Label("//zones/z:ring"), ItemLocation.Carried(p.id))
    f.w.spawnItem(Label("//zones/z:greatsword"), ItemLocation.Carried(p.id))
    f.w.spawnItem(Label("//zones/z:shield"), ItemLocation.Carried(p.id))

    f.input(p, "wear ring")
    f.run()
    f.input(p, "wield greatsword")
    f.run()
    f.input(p, "wear shield")
    f.run()
    f.input(p, "buff")
    f.run()
    f.input(p, "inspect")
    f.run()

    // 50 (base max) + 10 (ring) + 5 (boost affect) = 65
    assertEquals(AttrValue.S("65"), tester.customAttributes["z:max_hp"])
    assertEquals(AttrValue.S("10"), tester.customAttributes["z:fire_res"])
    assertEquals(AttrValue.S("True"), tester.customAttributes["z:has_wield"])
    assertEquals(AttrValue.S("5"), tester.customAttributes["z:aff_hp"])
    assertEquals(AttrValue.S("10"), tester.customAttributes["z:aff_fire"])
    assertEquals(AttrValue.S("10"), tester.customAttributes["z:ring_hp"])
    assertEquals(AttrValue.S("20"), tester.customAttributes["z:ring_slash"])
    assertEquals(AttrValue.S("False"), tester.customAttributes["z:ring_inactive"])
    assertEquals(AttrValue.S("False"), tester.customAttributes["z:shield_inactive"])

    // Apply weakness debuff: str drops to 5 < 10, greatsword cost becomes 2, shield becomes
    // inactive
    f.input(p, "debuff")
    f.run()
    f.input(p, "inspect")
    f.run()

    assertEquals(AttrValue.S("True"), tester.customAttributes["z:shield_inactive"])

    // Clean weakness: str returns to 10, shield becomes active again
    f.input(p, "clean")
    f.run()
    f.input(p, "inspect")
    f.run()

    assertEquals(AttrValue.S("False"), tester.customAttributes["z:shield_inactive"])
  }

  @Test
  fun `item_affect vital and resist deltas reach the holder by scope and show in affect views`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _inspect(ctx):
              aff = ctx.self.affects[0]
              ctx.set_attr(ctx.self, "aff_hp", aff.max_vital_deltas.hp)
              ctx.set_attr(ctx.self, "aff_fire", aff.resist_deltas.fire)

          room("start")
          item("charm", commands = {"inspect": _inspect}, affects = [item_affect(
              "warmth", applies = "carried", max_vital_deltas = vitals(hp = 7), resist_deltas = resists(fire = 4))])
          item("ring", wear = "finger_l", affects = [item_affect(
              "ward", max_vital_deltas = vitals(hp = 3), resist_deltas = resists(pierce = 6))])
          """
        )
      )
    val p = f.player()
    val charm = f.w.spawnItem(Label("//zones/z:charm"), ItemLocation.Carried(p.id))
    f.w.spawnItem(Label("//zones/z:ring"), ItemLocation.Carried(p.id))

    assertEquals(57, p.max("hp"))
    assertEquals(4, p.effectiveResist("fire"))
    assertEquals(0, p.effectiveResist("pierce"))

    f.input(p, "wear ring")
    f.run()
    assertEquals(60, p.max("hp"))
    assertEquals(6, p.effectiveResist("pierce"))

    f.input(p, "inspect")
    f.run()
    assertEquals(AttrValue.I(7), charm.customAttributes["z:aff_hp"])
    assertEquals(AttrValue.I(4), charm.customAttributes["z:aff_fire"])
  }

  @Test
  fun `apply_affect effect primitive carries vital and resist deltas to actors and items`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          item("sword", wear = "wield")
          skill("ward", target = "self", effect = apply_affect(
              "ward", 20, max_vital_deltas = vitals(hp = 5), resist_deltas = resists(fire = 10)))
          skill("temper", target = "item", effect = apply_affect(
              "tempered", 50, applies = "worn", max_vital_deltas = vitals(hp = 2), resist_deltas = resists(pierce = 3)))
          """
        )
      )
    val p = f.player()
    val sword = f.w.spawnItem(Label("//zones/z:sword"), ItemLocation.Carried(p.id))
    p.skills[Label("//zones/z:ward")] = 100
    p.skills[Label("//zones/z:temper")] = 100

    f.input(p, "cast ward")
    f.run()
    assertEquals(55, p.max("hp"))
    assertEquals(10, p.effectiveResist("fire"))

    f.input(p, "cast temper sword")
    f.run()
    assertEquals(1, sword.affects.size)
    assertEquals(0, p.effectiveResist("pierce"))

    f.input(p, "wield sword")
    f.run()
    assertEquals(57, p.max("hp"))
    assertEquals(3, p.effectiveResist("pierce"))
  }

  @Test
  fun `ctx apply_affect on an item carries vital and resist deltas`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _test(ctx):
              ctx.apply_affect(ctx.self, "glow", None, applies = "carried",
                  max_vital_deltas = vitals(hp = 4), resist_deltas = resists(fire = 8))

          room("start")
          item("wand", commands = {"test": _test})
          """
        )
      )
    val p = f.player()
    val wand = f.w.spawnItem(Label("//zones/z:wand"), ItemLocation.Carried(p.id))

    f.input(p, "test")
    f.run()
    assertEquals(mapOf("hp" to 4), wand.affects.single().maxVitalDeltas)
    assertEquals(54, p.max("hp"))
    assertEquals(8, p.effectiveResist("fire"))
  }

  @Test
  fun `misspelled vitals and damage types in item_affect and apply_affect fail to load`() {
    val badAffectVital =
      load(
        zone(
          """
          room("start")
          item("charm", affects = [item_affect("x", applies = "carried", max_vital_deltas = vitals(hpo = 1))])
          """
        )
      )
    assertFalse(badAffectVital.ok)
    assertContains(badAffectVital.report(), "unknown vital 'hpo' (did you mean 'hp'?)")

    val badEffectResist =
      load(
        zone(
          """
          room("start")
          skill("ward", target = "self", effect = apply_affect("ward", 5, resist_deltas = resists(frie = 1)))
          """
        )
      )
    assertFalse(badEffectResist.ok)
    assertContains(badEffectResist.report(), "unknown damage type 'frie' (did you mean 'fire'?)")

    val plainDict =
      load(
        zone(
          """
          room("start")
          skill("ward", target = "self", effect = apply_affect("ward", 5, max_vital_deltas = {"hp": 1}))
          """
        )
      )
    assertFalse(plainDict.ok)
    assertContains(plainDict.report(), "use vitals(...) instead of a dict")
  }

  @Test
  fun `actor_slots reading actor slots sees every slot instead of recursing`() {
    val f =
      ScriptFixture(
        zone("room(\"start\")") +
          mapOf(
            "rules/package.star" to gups.script.testing.TestWorlds.RULES_PACKAGE,
            "rules/combat.star" to
              gups.script.testing.TestWorlds.rules(
                """
                def actor_slots(ctx):
                    return [s for s in ctx.actor.slots if s != "neck"]
                """
              ),
          )
      )
    val p = f.player()
    val slots = f.w.actorSlots(p)
    assertFalse("neck" in slots)
    assertTrue("wield" in slots)
  }
}
