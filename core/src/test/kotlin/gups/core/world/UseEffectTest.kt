package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchema
import gups.core.fixtures.FixtureSchemas
import gups.core.model.AffectScope
import gups.core.model.DamageType
import gups.core.model.Dice
import gups.core.model.Effect
import gups.core.model.EmptyAction
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Mob
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.TargetKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.MethodSource

@ParameterizedClass
@MethodSource("gups.core.world.SchemaArgs#all")
class UseEffectTest(private val fs: FixtureSchema) {
  init {
    FixtureSchemas.current = fs
  }

  @BeforeEach
  fun useSchema() {
    FixtureSchemas.current = fs
  }

  @AfterEach
  fun resetSchema() {
    FixtureSchemas.current = FixtureSchemas.FANTASY
  }

  private val salve = Label("//t:salve")
  private val bandaid = Label("//t:bandaid")
  private val wand = Label("//t:wand")
  private val polish = Label("//t:polish")
  private val horn = Label("//t:horn")
  private val laggedPotion = Label("//t:lagged_potion")
  private val draught = Label("//t:draught")
  private val hookedSalve = Label("//t:hooked_salve")
  private val crank = Label("//t:crank")
  private val emptyWand = Label("//t:empty_wand")

  private val extra =
    listOf(
      ItemProto(
        salve,
        "a salve",
        listOf("salve"),
        useEffect = Effect.Heal(Dice(1, 1, 4), fs.health),
        useTarget = TargetKind.SELF,
      ),
      ItemProto(
        bandaid,
        "a bandaid",
        listOf("bandaid"),
        useEffect = Effect.Heal(Dice(1, 1, 4), fs.health),
        useTarget = TargetKind.ALLY,
      ),
      ItemProto(
        wand,
        "a wand",
        listOf("wand"),
        useEffect = Effect.Damage(Dice(1, 1, 4), DamageType(fs.unarmed)),
        useTarget = TargetKind.ENEMY,
      ),
      ItemProto(
        polish,
        "a polish",
        listOf("polish"),
        useEffect = Effect.ApplyAffect("glow", 10, applies = AffectScope.CARRIED),
        useTarget = TargetKind.ITEM,
      ),
      ItemProto(
        horn,
        "a horn",
        listOf("horn"),
        useEffect = Effect.Summon(T.GOBLIN, 1, null),
        useTarget = TargetKind.NONE,
      ),
      ItemProto(
        laggedPotion,
        "a lagged potion",
        listOf("potion"),
        useEffect = Effect.Heal(Dice(1, 1, 4), fs.health),
        useLag = 3,
      ),
      ItemProto(
        draught,
        "a draught",
        listOf("draught"),
        charges = 2,
        empty = EmptyAction.Keep,
        useEffect = Effect.Heal(Dice(1, 1, 4), fs.health),
      ),
      ItemProto(
        hookedSalve,
        "a hooked salve",
        listOf("salve"),
        useEffect = Effect.Heal(Dice(1, 1, 4), fs.health),
        useTarget = TargetKind.SELF,
      ),
      ItemProto(crank, "a crank", listOf("crank")),
      ItemProto(
        emptyWand,
        "an old wand",
        listOf("old"),
        charges = 1,
        empty = EmptyAction.Keep,
        useEffect = Effect.Damage(Dice(1, 1, 4), DamageType(fs.unarmed)),
        useTarget = TargetKind.ENEMY,
      ),
    )

  private fun fixture(host: ScriptHost = RecordingHost(), features: Features = Features.ALL) =
    Fixture(
      rules = FixedRules(fs),
      scripts = host,
      protos = Prototypes(T.rooms, T.mobs, T.items + extra, T.skills),
      features = features,
    )

  private fun Fixture.doLine(p: Player, line: String): String {
    input(p, line)
    run(3)
    return out(p)
  }

  @Test
  fun `a self effect heals the user and announces it without a hook`() {
    val f = fixture()
    val ann = f.player("Ann")
    val bob = f.player("Bob")
    f.w.spawnItem(salve, ItemLocation.Carried(ann.id))
    val maxHp = ann.current(fs.health)
    f.w.changeVital(ann, fs.health, -10)
    assertEquals(maxHp - 10, ann.current(fs.health))

    val out = f.doLine(ann, "use salve")
    assertContains(out, "You use a salve.")
    assertContains(f.out(bob), "Ann uses a salve.")
    assertEquals(maxHp - 5, ann.current(fs.health))
  }

  @Test
  fun `a self item refuses a target word`() {
    val f = fixture()
    val ann = f.player("Ann")
    f.player("Bob")
    f.w.spawnItem(salve, ItemLocation.Carried(ann.id))

    val out = f.doLine(ann, "use salve Bob")
    assertContains(out, "You can only use a salve on yourself.")
  }

  @Test
  fun `an ally effect defaults to the user and can name another actor`() {
    val f = fixture()
    val ann = f.player("Ann")
    val bob = f.player("Bob")
    f.w.spawnItem(bandaid, ItemLocation.Carried(ann.id))
    f.w.spawnItem(bandaid, ItemLocation.Carried(ann.id))
    val maxAnn = ann.current(fs.health)
    val maxBob = bob.current(fs.health)
    f.w.changeVital(ann, fs.health, -10)
    f.w.changeVital(bob, fs.health, -10)

    f.doLine(ann, "use bandaid")
    assertEquals(maxAnn - 5, ann.current(fs.health))

    f.doLine(ann, "use bandaid Bob")
    assertEquals(maxBob - 5, bob.current(fs.health))

    val notHere = f.doLine(ann, "use bandaid Charlie")
    assertContains(notHere, "They aren't here.")
  }

  @Test
  fun `an enemy effect defaults to the primary target and asks whom otherwise`() {
    val f = fixture()
    val ann = f.player("Ann")
    val goblin = f.mob(T.GOBLIN)
    f.w.spawnItem(wand, ItemLocation.Carried(ann.id))

    val askWhom = f.doLine(ann, "use wand")
    assertContains(askWhom, "Use a wand on whom?")

    f.w.startCombat(ann, goblin)
    val hpBefore = goblin.current(fs.health)
    f.doLine(ann, "use wand")
    assertTrue(goblin.current(fs.health) < hpBefore)
  }

  @Test
  fun `an item-target effect applies an item affect`() {
    val f = fixture()
    val ann = f.player("Ann")
    f.w.spawnItem(polish, ItemLocation.Carried(ann.id))
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Carried(ann.id))

    val askWhat = f.doLine(ann, "use polish")
    assertContains(askWhat, "Use a polish on what?")

    f.doLine(ann, "use polish sword")
    assertTrue(sword.affects.any { it.name == "glow" })
  }

  @Test
  fun `a none effect summons`() {
    val f = fixture()
    val ann = f.player("Ann")
    f.w.spawnItem(horn, ItemLocation.Carried(ann.id))
    f.w.spawnItem(horn, ItemLocation.Carried(ann.id))

    val initialMobs = f.w.actors.count { it is Mob && f.w.roomOf(it) === ann.room }
    f.doLine(ann, "use horn")
    assertEquals(initialMobs + 1, f.w.actors.count { it is Mob && f.w.roomOf(it) === ann.room })

    f.doLine(ann, "use horn ignored_word")
    assertEquals(initialMobs + 2, f.w.actors.count { it is Mob && f.w.roomOf(it) === ann.room })
  }

  @Test
  fun `with an on_use hook the engine sends no use message`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_USE] = { w, owner, ctx ->
      if (owner.name == "a hooked salve") w.send(ctx.actor!!, "The salve stings.")
      null
    }
    val f = fixture(host)
    val ann = f.player("Ann")
    val bob = f.player("Bob")
    f.w.spawnItem(hookedSalve, ItemLocation.Carried(ann.id))

    val maxHp = ann.current(fs.health)
    f.w.changeVital(ann, fs.health, -10)

    val out = f.doLine(ann, "use salve")
    assertContains(out, "The salve stings.")
    assertEquals(maxHp - 5, ann.current(fs.health))
    assertFalse(out.contains("You use a hooked salve."))
    assertFalse(f.out(bob).contains("uses a hooked salve."))
  }

  @Test
  fun `use_lag sets the recovery lag, and a veto costs none`() {
    val host = RecordingHost()
    var veto = false
    host.handlers[HookName.BEFORE_USE] = { _, _, _ ->
      if (veto) HookOutcome.Deny("Not now.") else null
    }
    val f = fixture(host)
    val ann = f.player("Ann")
    f.w.spawnItem(laggedPotion, ItemLocation.Carried(ann.id))
    f.w.spawnItem(laggedPotion, ItemLocation.Carried(ann.id))

    f.input(ann, "use potion")
    f.run()
    assertEquals(3, ann.waitState)

    ann.waitState = 0
    veto = true
    val out = f.doLine(ann, "use potion")
    assertContains(out, "Not now.")
    assertEquals(0, ann.waitState)
  }

  @Test
  fun `the effect runs before on_use and the charge after it`() {
    val host = RecordingHost()
    var hpInHook = 0
    var chargesInHook: Int? = null
    host.handlers[HookName.ON_USE] = { _, _, ctx ->
      hpInHook = ctx.actor!!.current(fs.health)
      chargesInHook = ctx.item?.charges
      null
    }
    val f = fixture(host)
    val ann = f.player("Ann")
    val item = f.w.spawnItem(draught, ItemLocation.Carried(ann.id))
    val maxHp = ann.current(fs.health)
    f.w.changeVital(ann, fs.health, -10)

    f.doLine(ann, "use draught")
    assertEquals(maxHp - 5, hpInHook)
    assertEquals(2, chargesInHook)
    assertEquals(1, item.charges)
  }

  @Test
  fun `without combat an enemy damage effect lowers hp without engaging`() {
    val f = fixture(features = Features(emptySet()))
    val ann = f.player("Ann")
    val goblin = f.mob(T.GOBLIN)
    f.w.spawnItem(wand, ItemLocation.Carried(ann.id))

    val hpBefore = goblin.current(fs.health)
    f.doLine(ann, "use wand goblin")
    val hpAfter = goblin.current(fs.health)
    assertTrue(hpAfter < hpBefore)
    assertNull(ann.primaryTarget)
    assertNull(goblin.primaryTarget)
  }

  @Test
  fun `a harmful effect engages the target`() {
    val f = fixture()
    val ann = f.player("Ann")
    val goblin = f.mob(T.GOBLIN)
    f.w.spawnItem(wand, ItemLocation.Carried(ann.id))

    f.doLine(ann, "use wand goblin")
    assertEquals(goblin.id, ann.primaryTarget)
  }

  @Test
  fun `a target item must be within reach`() {
    val f = fixture()
    val ann = f.player("Ann")
    val hall = f.w.room(T.HALL)
    f.w.spawnItem(polish, ItemLocation.InRoom(hall.id, 0))
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.InRoom(hall.id, 4))

    assertContains(f.doLine(ann, "use polish sword"), "You can't reach a sword from here.")
    assertTrue(sword.affects.none { it.name == "glow" })
  }

  @Test
  fun `a hook-only item's target item must be within reach`() {
    val host = RecordingHost()
    host.handlers[HookName.ON_USE] = { w, _, ctx ->
      w.send(ctx.actor!!, "You turn ${ctx.item?.name} in ${ctx.targetItem?.name}.")
      null
    }
    val f = fixture(host)
    val ann = f.player("Ann")
    val hall = f.w.room(T.HALL)
    f.w.spawnItem(crank, ItemLocation.Carried(ann.id))
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.InRoom(hall.id, 0))

    assertContains(f.doLine(ann, "use crank sword"), "You turn a crank in a sword.")
    f.w.moveItem(sword, ItemLocation.InRoom(hall.id, 4))
    assertContains(f.doLine(ann, "use crank sword"), "You can't reach a sword from here.")
  }

  @Test
  fun `an empty item is refused before its target is chosen`() {
    val f = fixture()
    val ann = f.player("Ann")
    val wand = f.w.spawnItem(emptyWand, ItemLocation.Carried(ann.id))
    wand.charges = 0

    val out = f.doLine(ann, "use old")
    assertContains(out, "There are no charges left in an old wand.")
    assertFalse(out.contains("on whom?"))
  }
}
