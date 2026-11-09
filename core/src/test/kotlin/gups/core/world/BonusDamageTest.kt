package gups.core.world

import gups.core.AttrValue
import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Actor
import gups.core.model.Affect
import gups.core.model.Band
import gups.core.model.CastState
import gups.core.model.Channel
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Direction
import gups.core.model.DispositionParts
import gups.core.model.Effect
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.ModifierPoint
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.ZoneProto
import gups.core.rules.Contributor
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class BonusDamageTest {
  private val slash = DamageType("slash")
  private val fire = DamageType("fire")
  private val flame = Label("//t:flame")
  private val main = Label("//t:main")
  private val off = Label("//t:off")

  /** The base of a `bonus_damage` modifier, as a damage-type-keyed map. */
  private fun bonus(base: Any?): Map<String, Int> {
    @Suppress("UNCHECKED_CAST")
    return base as Map<String, Int>
  }

  /** A flame amulet (no dice) with the [mods]. */
  private fun flameProtos(mods: Map<ModifierPoint, gups.core.HookRef>) =
    Prototypes(
      T.rooms,
      T.mobs,
      T.items +
        listOf(
          ItemProto(flame, "a flame amulet", listOf("flame"), wear = "neck", modifiers = mods)
        ),
      T.skills,
      zones = listOf(ZoneProto("t")),
    )

  /** Two worn weapons with dice, each with a bonus modifier, plus the flame amulet. */
  private fun dualProtos() =
    Prototypes(
      T.rooms,
      T.mobs,
      T.items +
        listOf(
          ItemProto(
            flame,
            "a flame amulet",
            listOf("flame"),
            wear = "neck",
            modifiers = mapOf(ModifierPoint.BONUS_DAMAGE to mod("flame")),
          ),
          ItemProto(
            main,
            "a main sword",
            listOf("main"),
            wear = "wield",
            dice = Dice(1, 1, 2),
            modifiers = mapOf(ModifierPoint.BONUS_DAMAGE to mod("main")),
          ),
          ItemProto(
            off,
            "an off sword",
            listOf("off"),
            wear = "offhand",
            dice = Dice(1, 1, 2),
            modifiers = mapOf(ModifierPoint.BONUS_DAMAGE to mod("off")),
          ),
        ),
      T.skills,
      zones = listOf(ZoneProto("t")),
    )

  private fun Fixture.wearFlame(p: Player) {
    w.moveItem(w.spawnItem(flame, ItemLocation.Carried(p.id)), ItemLocation.Equipped(p.id, "neck"))
  }

  private fun Fixture.wear(a: Actor, label: Label, slot: String) {
    w.moveItem(w.spawnItem(label, ItemLocation.Carried(a.id)), ItemLocation.Equipped(a.id, slot))
  }

  /** [FixedRules] that records its `mitigate` and `interruptCheck` calls. */
  private class RecordingRules(fs: FixtureSchema = FixtureSchemas.current) : FixedRules(fs) {
    /** The (amount, type) of every `mitigate` call, in order. */
    val mitigations = mutableListOf<Pair<Int, DamageType>>()

    /** The amount of every `interruptCheck` call. */
    val interrupts = mutableListOf<Int>()

    override fun mitigate(c: RulesContext, amount: Int, type: DamageType): Map<String, Int> {
      mitigations += amount to type
      return super.mitigate(c, amount, type)
    }

    override fun interruptCheck(c: RulesContext, amount: Int): Boolean {
      interrupts += amount
      return super.interruptCheck(c, amount)
    }
  }

  // ---- The primary part is untouched by the new code --------------------------------------

  @Test
  fun `without a bonus modifier the hit and its trace are exactly as before`() {
    val f = Fixture(rules = RecordingRules())
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    val hit = f.w.resolveDamage(RulesContext(f.w, p, d, damageType = slash), 5, slash)
    assertEquals(mapOf("hp" to 5), hit.losses)
    assertEquals("rules.damage base=5 → mitigate hp 5 slash", hit.text)
    assertEquals(listOf(DamagePart(slash, mapOf("hp" to 5))), hit.parts)
  }

  // ---- Bonus parts on a swing ---------------------------------------------------------------

  @Test
  fun `a bonus part is mitigated with its own type and joins the hit as one on_damage`() {
    val host = RecordingHost()
    host.modifiers["flame"] = { _, _, base -> bonus(base) + ("fire" to 2) }
    var ctx: HookContext? = null
    host.handlers[HookName.ON_DAMAGE] = { _, _, c ->
      ctx = c
      null
    }
    val rules = RecordingRules()
    val f =
      Fixture(
        rules = rules,
        scripts = host,
        protos = flameProtos(mapOf(ModifierPoint.BONUS_DAMAGE to mod("flame"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.wearFlame(p)
    val hit = f.w.resolveDamage(RulesContext(f.w, p, d, damageType = slash), 5, slash)
    // The primary part is mitigated once, then the fire part, each with its own type.
    assertEquals(listOf(5 to slash, 2 to fire), rules.mitigations)
    // The losses of the parts are summed per vital.
    assertEquals(mapOf("hp" to 7), hit.losses)
    f.w.damage(d, hit.losses, slash, p, hit.parts)
    // One on_damage on the combined loss, the primary part first.
    assertEquals(7, ctx?.amount)
    assertEquals(2, ctx?.parts?.size)
    assertEquals(slash, ctx?.parts?.first()?.type)
    assertEquals(mapOf("hp" to 5), ctx?.parts?.first()?.losses)
    assertEquals(fire, ctx?.parts?.get(1)?.type)
    assertEquals(mapOf("hp" to 2), ctx?.parts?.get(1)?.losses)
  }

  @Test
  fun `the trace shows the bonus steps and each part's steps`() {
    val host = RecordingHost()
    host.modifiers["flame"] = { _, _, base -> bonus(base) + ("fire" to 2) }
    val f =
      Fixture(
        scripts = host,
        protos = flameProtos(mapOf(ModifierPoint.BONUS_DAMAGE to mod("flame"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.wearFlame(p)
    val hit = f.w.resolveDamage(RulesContext(f.w, p, d, damageType = slash), 5, slash)
    assertEquals(
      "rules.damage base=5 → mitigate hp 5 slash → bonus_damage → item:flame damages(fire = 2) → part fire 2 → mitigate hp 2 fire",
      hit.text,
    )
  }

  @Test
  fun `a part's defender receive_damage step appears in the trace`() {
    val host = RecordingHost()
    host.modifiers["flame"] = { _, _, base -> bonus(base) + ("fire" to 2) }
    host.modifiers["oily"] = { _, _, base -> (base as Int) * 2 }
    val f =
      Fixture(
        scripts = host,
        protos =
          flameProtos(mapOf(ModifierPoint.BONUS_DAMAGE to mod("flame"))).let {
            Prototypes(
              T.rooms,
              T.mobs,
              T.items +
                listOf(
                  ItemProto(
                    flame,
                    "a flame amulet",
                    listOf("flame"),
                    wear = "neck",
                    modifiers = mapOf(ModifierPoint.BONUS_DAMAGE to mod("flame")),
                  ),
                  ItemProto(
                    Label("//t:rag"),
                    "oily rags",
                    listOf("rag", "oily"),
                    wear = "body",
                    modifiers = mapOf(ModifierPoint.RECEIVE_DAMAGE to mod("oily")),
                  ),
                ),
              T.skills,
              zones = listOf(ZoneProto("t")),
            )
          },
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.wearFlame(p)
    f.wear(d, Label("//t:rag"), "body")
    val hit = f.w.resolveDamage(RulesContext(f.w, p, d, damageType = slash), 5, slash)
    assertContains(
      hit.text,
      " → part fire 2 → defender:receive_damage → item:rag 4 → mitigate hp 4 fire",
    )
  }

  @Test
  fun `bonus parts run in the schema's damage type order`() {
    val host = RecordingHost()
    host.modifiers["flame"] = { _, _, base -> bonus(base) + ("cold" to 1) + ("fire" to 1) }
    val base = FixtureSchemas.FANTASY
    val fs =
      FixtureSchema(
        name = "fantasy5",
        schema =
          base.schema.copy(damageTypes = listOf("bludgeon", "slash", "pierce", "fire", "cold")),
        health = base.health,
        energy = base.energy,
        stamina = base.stamina,
        agility = base.agility,
        strength = base.strength,
        toughness = base.toughness,
        mind = base.mind,
        armor = base.armor,
        magic = base.magic,
        playerMax = base.playerMax,
        mobMax = base.mobMax,
      )
    val rules = RecordingRules(fs)
    val f =
      Fixture(
        fs = fs,
        rules = rules,
        scripts = host,
        protos = flameProtos(mapOf(ModifierPoint.BONUS_DAMAGE to mod("flame"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.wearFlame(p)
    f.w.resolveDamage(RulesContext(f.w, p, d, damageType = slash), 5, slash)
    // The schema lists fire before cold, so fire is mitigated first.
    assertEquals(listOf(5 to slash, 1 to fire, 1 to DamageType("cold")), rules.mitigations)
  }

  @Test
  fun `a part of 0 or less runs no defender chain`() {
    val host = RecordingHost()
    host.modifiers["flame"] = { _, _, base -> bonus(base) + ("fire" to 0) }
    val seen = mutableListOf<DamageType?>()
    host.modifiers["rd"] = { _, c, base ->
      seen += c.damageType
      base
    }
    val f =
      Fixture(
        scripts = host,
        protos = flameProtos(mapOf(ModifierPoint.BONUS_DAMAGE to mod("flame"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.wearFlame(p)
    f.w.applyAffect(d, "rd", 100, modifiers = mapOf(ModifierPoint.RECEIVE_DAMAGE to mod("rd")))
    val hit = f.w.resolveDamage(RulesContext(f.w, p, d, damageType = slash), 5, slash)
    // The fire part of 0 is dropped; the defender's receive_damage ran only for the primary part.
    assertEquals(listOf<DamageType?>(slash), seen)
    assertEquals(mapOf("hp" to 5), hit.losses)
    // The bonus step still ran and traced its 0; only the part segment is dropped.
    assertContains(hit.text, " → bonus_damage → item:flame damages(fire = 0)")
    assertFalse(" → part " in hit.text, "no part segment for a dropped part")
  }

  @Test
  fun `the defender's receive_damage sees each part's type`() {
    val host = RecordingHost()
    host.modifiers["flame"] = { _, _, base -> bonus(base) + ("fire" to 2) }
    val seen = mutableListOf<DamageType?>()
    host.modifiers["rd"] = { _, c, base ->
      seen += c.damageType
      base
    }
    val f =
      Fixture(
        scripts = host,
        protos = flameProtos(mapOf(ModifierPoint.BONUS_DAMAGE to mod("flame"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.wearFlame(p)
    f.w.applyAffect(d, "rd", 100, modifiers = mapOf(ModifierPoint.RECEIVE_DAMAGE to mod("rd")))
    f.w.resolveDamage(RulesContext(f.w, p, d, damageType = slash), 5, slash)
    assertEquals(listOf<DamageType?>(slash, fire), seen)
  }

  @Test
  fun `a gain on a bonus part offsets the combined losses`() {
    val host = RecordingHost()
    host.modifiers["flame"] = { _, _, base -> bonus(base) + ("fire" to 2) }
    host.modifiers["siphon"] = { _, c, base ->
      if (c.damageType == fire) mapOf("hp" to -1) else base
    }
    val f =
      Fixture(
        scripts = host,
        protos = flameProtos(mapOf(ModifierPoint.BONUS_DAMAGE to mod("flame"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.wearFlame(p)
    f.w.applyAffect(d, "siphon", 100, modifiers = mapOf(ModifierPoint.MITIGATE to mod("siphon")))
    val hit = f.w.resolveDamage(RulesContext(f.w, p, d, damageType = slash), 5, slash)
    // 5 primary hp plus the fire part's -1 gain: the combined loss is 4.
    assertEquals(mapOf("hp" to 4), hit.losses)
    // The fire part's losses, filtered to positive values, are empty.
    assertEquals(DamagePart(fire, emptyMap()), hit.parts.get(1))
  }

  // ---- Interrupts and the damage effect ------------------------------------------------------

  @Test
  fun `a casting target gets one interrupt check on the combined amount`() {
    val host = RecordingHost()
    host.modifiers["flame"] = { _, _, base -> bonus(base) + ("fire" to 2) }
    val rules = RecordingRules()
    val f =
      Fixture(
        rules = rules,
        scripts = host,
        protos = flameProtos(mapOf(ModifierPoint.BONUS_DAMAGE to mod("flame"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.wearFlame(p)
    d.casting =
      CastState(CastAction(f.w.protos.skills.getValue(T.BOLT), null, d), f.w.pulse, f.w.pulse + 4)
    val hit = f.w.resolveDamage(RulesContext(f.w, p, d, damageType = slash), 5, slash)
    f.w.damage(d, hit.losses, slash, p)
    // One check, on the combined loss of the parts.
    assertEquals(listOf(7), rules.interrupts)
  }

  @Test
  fun `a damage effect resolves bonus parts too`() {
    val host = RecordingHost()
    host.modifiers["flame"] = { _, _, base -> bonus(base) + ("fire" to 2) }
    var ctx: HookContext? = null
    host.handlers[HookName.ON_DAMAGE] = { _, _, c ->
      ctx = c
      null
    }
    val f =
      Fixture(
        scripts = host,
        protos = flameProtos(mapOf(ModifierPoint.BONUS_DAMAGE to mod("flame"))),
      )
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.wearFlame(p)
    f.w.applyEffect(Effect.Damage(Dice(1, 1, 5), slash), p, d)
    // The effect's cast has no slot, so the worn amulet's bonus modifier runs.
    assertEquals(2, ctx?.parts?.size)
    assertEquals(slash, ctx?.parts?.first()?.type)
    assertEquals(fire, ctx?.parts?.get(1)?.type)
  }

  // ---- The swing-weapon filter ---------------------------------------------------------------

  @Test
  fun `a worn dice item's bonus modifier is skipped by the off-hand swing, not its own`() {
    val host = RecordingHost()
    host.modifiers["main"] = { _, _, base -> bonus(base) + ("fire" to 2) }
    host.modifiers["off"] = { _, _, base -> bonus(base) + ("fire" to 3) }
    val f = Fixture(scripts = host, protos = dualProtos())
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.wear(p, main, "wield")
    f.wear(p, off, "offhand")
    // The main-hand swing runs only the main sword's modifier.
    host.calls.clear()
    f.w.resolveDamage(
      RulesContext(f.w, p, d, weapon = p.equipped("wield"), slot = "wield"),
      5,
      slash,
    )
    assertEquals(listOf("mod:item:main:main"), host.calls)
    // The off-hand swing runs only the off sword's modifier.
    host.calls.clear()
    f.w.resolveDamage(
      RulesContext(f.w, p, d, weapon = p.equipped("offhand"), slot = "offhand"),
      5,
      slash,
    )
    assertEquals(listOf("mod:item:off:off"), host.calls)
  }

  @Test
  fun `a worn dice item's bonus modifier runs for a damage effect of its wearer`() {
    val host = RecordingHost()
    host.modifiers["main"] = { _, _, base -> bonus(base) + ("fire" to 2) }
    val f = Fixture(scripts = host, protos = dualProtos())
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    f.wear(p, main, "wield")
    host.calls.clear()
    f.w.applyEffect(Effect.Damage(Dice(1, 1, 5), slash), p, d)
    assertEquals(listOf("mod:item:main:main"), host.calls)
  }

  // ---- withDamageType -------------------------------------------------------------------------

  @Test
  fun `withDamageType replaces only the damage type`() {
    val f = Fixture()
    val p = f.player("Ann")
    val d = f.mob(T.DUMMY)
    val room = f.w.room(T.HALL)
    val c =
      RulesContext(
        world = f.w,
        actor = p,
        target = d,
        weapon = f.w.spawnItem(T.SWORD, ItemLocation.Carried(p.id)),
        band = Band.MELEE,
        skill = T.skills.first(),
        item = f.w.spawnItem(T.BAG, ItemLocation.InRoom(room.id, 0)),
        room = room,
        exit = room.exits.values.first(),
        channel = Channel.SIGHT,
        event = "say",
        intensity = 3,
        cost = 2,
        sourceRoom = room,
        viaExit = Direction.NORTH,
        text = "hi",
        action = "open",
        tags = setOf("wet"),
        source = d,
        damageType = fire,
        slot = "wield",
        affect = Affect(1, "test", null, 0, 10),
        limit = 100,
        carried = 50,
        encumbranceLevel = 2,
        xp = 10,
        contributors = listOf(Contributor(d, 1, 2, 3, 4L, null, null, true)),
        behaviors = listOf(Heel()),
        dispositionParts = DispositionParts(1, 2, 3, 4),
        amount = 5L,
        helped = d,
        pet = d,
        behavior = "aggressive",
        prefer = AttrValue.S("weakest"),
        candidates = listOf(d),
        current = d,
        currentSince = 6L,
        dispositions = listOf(-10),
        ally = d,
        away = 7L,
        pastLeash = true,
        castTarget = d,
        base = 8L,
        args = mapOf("n" to 1L),
        command = "cast",
        chain = 3,
      )
    val c2 = c.withDamageType(slash)
    assertEquals(slash, c2.damageType)
    // Every val of the context has a getter; check them all except the one that changed.
    // Mangled synthetic getters carry a dash in their name and are skipped.
    for (g in RulesContext::class.java.declaredMethods) {
      val field = g.name.removePrefix("get").replaceFirstChar { it.lowercase() }
      if (
        !g.name.startsWith("get") ||
          g.parameterCount != 0 ||
          g.name.contains('-') ||
          field == "damageType"
      )
        continue
      // A field left at its null default here would pass even if the copy dropped it.
      assertNotNull(g.invoke(c), "set $field in this test so its copy is checked")
      assertEquals(g.invoke(c), g.invoke(c2), "field $field must be copied")
    }
  }
}
