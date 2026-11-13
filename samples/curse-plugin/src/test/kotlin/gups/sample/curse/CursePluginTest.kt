package gups.sample.curse

import gups.pluginapi.PluginActor
import gups.pluginapi.PluginCommandCall
import gups.pluginapi.PluginContainmentCall
import gups.pluginapi.PluginCreation
import gups.pluginapi.PluginEffect
import gups.pluginapi.PluginEffectCall
import gups.pluginapi.PluginEntity
import gups.pluginapi.PluginGmcpCall
import gups.pluginapi.PluginGmcpWorld
import gups.pluginapi.PluginHookCall
import gups.pluginapi.PluginItem
import gups.pluginapi.PluginItemAffect
import gups.pluginapi.PluginItemLocation
import gups.pluginapi.PluginLane
import gups.pluginapi.PluginParam
import gups.pluginapi.PluginRegistry
import gups.pluginapi.PluginRestrictionCall
import gups.pluginapi.PluginRestrictionPoint
import gups.pluginapi.PluginRng
import gups.pluginapi.PluginRole
import gups.pluginapi.PluginRoom
import gups.pluginapi.PluginSchema
import gups.pluginapi.PluginTrait
import gups.pluginapi.PluginWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.starlark.java.eval.StarlarkValue

class CursePluginTest {

  private class FakeRegistry : PluginRegistry {
    val restrictions =
      mutableMapOf<
        PluginRestrictionPoint,
        MutableList<Pair<String, (PluginRestrictionCall) -> String?>>,
      >()
    val effectPrimitives = mutableMapOf<String, Pair<List<PluginParam>, PluginEffect>>()
    val containmentFilters = mutableListOf<Pair<String, (PluginContainmentCall) -> Boolean>>()
    val hooks = mutableListOf<Pair<String, (PluginHookCall) -> Any?>>()

    override fun addRestriction(
      point: PluginRestrictionPoint,
      doc: String,
      check: (PluginRestrictionCall) -> String?,
    ) {
      restrictions.getOrPut(point) { mutableListOf() }.add(doc to check)
    }

    override fun addEffectPrimitive(
      name: String,
      doc: String,
      params: List<PluginParam>,
      apply: PluginEffect,
    ) {
      effectPrimitives[name] = params to apply
    }

    override fun addContainmentFilter(doc: String, blocks: (PluginContainmentCall) -> Boolean) {
      containmentFilters.add(doc to blocks)
    }

    override fun addTargetOrder(
      doc: String,
      choose: (gups.pluginapi.PluginTargetOrderCall) -> gups.pluginapi.PluginTargetChoice,
    ) = TODO()

    override fun addChainThink(
      doc: String,
      decide: (gups.pluginapi.PluginChainThinkCall) -> Boolean,
    ) = TODO()

    override fun addOwnedBehaviors(
      doc: String,
      adjust: (gups.pluginapi.PluginOwnedBehaviorsCall) -> List<Map<String, Any?>>,
    ) = TODO()

    override fun addDispositionModifier(
      doc: String,
      adjust: (gups.pluginapi.PluginDispositionCall) -> Int,
    ) = TODO()

    override fun addFactions(
      doc: String,
      adjust: (gups.pluginapi.PluginFactionsCall) -> List<String>,
    ) = TODO()

    override fun addHook(name: String, doc: String, handle: (PluginHookCall) -> Any?) {
      hooks.add(name to handle)
    }

    override fun addTrait(trait: PluginTrait) = TODO()

    override fun setCreation(creation: PluginCreation) = TODO()

    override fun addBuiltin(
      name: String,
      doc: String,
      params: List<PluginParam>,
      returnType: String,
      call: (world: PluginWorld, positional: List<Any?>, named: Map<String, Any?>) -> Any?,
    ) = TODO()

    override fun addBuiltin(name: String, value: StarlarkValue, doc: String) = TODO()

    override fun addCommand(
      name: String,
      lane: PluginLane,
      role: PluginRole,
      doc: String,
      run: (PluginCommandCall) -> Unit,
    ) = TODO()

    override fun addGmcp(pkg: String, doc: String, produce: (PluginWorld, PluginActor) -> Any?) =
      TODO()

    override fun addGmcpEvent(pkg: String, doc: String) = TODO()

    override fun onGmcp(pkg: String, doc: String, handle: (PluginGmcpCall) -> Unit) = TODO()

    override fun onWorldStarted(fn: (PluginWorld) -> Unit) = TODO()
  }

  private class FakeItem(
    tags: List<String> = emptyList(),
    override val contents: List<PluginItem> = emptyList(),
    override val custodian: PluginActor? = null,
    override val variants: Map<String, String> = emptyMap(),
    override val id: Long = 1L,
    override val name: String = "an item",
  ) : PluginItem {
    override val label: String? = null
    override val keywords: List<String> = emptyList()
    override val kind: String = "item"
    override val wear: String? = null
    override val slotCost: Int = 1
    override val weight: Int = 1
    override val value: Int = 0
    override val maxVitalDeltas: Map<String, Int> = emptyMap()
    override val resistDeltas: Map<String, Int> = emptyMap()
    override val inactive: Boolean = false
    override val tags: List<String> = tags.sorted()

    override fun hasTag(tag: String): Boolean = tag in tags

    override val affects: List<PluginItemAffect> = emptyList()
    override val plural: String? = null
    override val count: Int = 1
    override val location: PluginItemLocation
      get() = TODO()

    override val charges: Int? = null
    override val maxCharges: Int? = null
  }

  private class FakeActor(override val id: Long = 100L, override val name: String = "Tester") :
    PluginActor {
    override val label: String? = null
    override val isPlayer: Boolean = true
    override val role: PluginRole = PluginRole.PLAYER
    override val flags: Set<String> = emptySet()
    override val traits: List<String> = emptyList()
    override val grantedTraits: Set<String> = emptySet()

    override fun hasTrait(name: String): Boolean = false

    override val choices: Map<String, String> = emptyMap()
    override val skills: Map<String, Int> = emptyMap()
    override val inventory: List<PluginItem> = emptyList()
    override val equipment: Map<String, PluginItem> = emptyMap()
    override val room: PluginRoom
      get() = TODO()

    override fun stat(name: String) = 10

    override fun baseStat(name: String) = 10

    override fun vital(name: String) = 20

    override fun maxVital(name: String) = 20

    override fun baseMaxVital(name: String) = 20

    override fun resist(type: String) = 0

    override fun baseResist(type: String) = 0

    override val level = 1
    override val pendingLevels = 0
    override val affects = emptyList<String>()

    override fun hasAffect(name: String) = false
  }

  private class FakeWorld : PluginWorld {
    val addTagCalls = mutableListOf<Pair<PluginItem, String>>()
    val removeTagCalls = mutableListOf<Pair<PluginItem, String>>()
    val transformCalls = mutableListOf<Pair<PluginItem, String>>()

    override val pulse: Long = 0L
    override val seed: Long = 42L
    override val rng: PluginRng
      get() = TODO()

    override val schema: PluginSchema
      get() = TODO()

    override fun send(target: PluginActor, text: String) = TODO()

    override fun gmcp(actor: PluginActor, pkg: String, data: Any?): Boolean = TODO()

    override fun echo(room: PluginRoom, text: String, exclude: List<PluginActor>) = TODO()

    override fun damage(
      target: PluginActor,
      amount: Int,
      type: String,
      vital: String,
      source: PluginActor?,
    ) = TODO()

    override fun heal(target: PluginActor, amount: Int, vital: String, source: PluginActor?) =
      TODO()

    override fun applyAffect(
      target: PluginActor,
      name: String,
      duration: Int,
      statDeltas: Map<String, Int>,
      flags: Set<String>,
      maxVitalDeltas: Map<String, Int>,
      resistDeltas: Map<String, Int>,
    ) = TODO()

    override fun removeAffect(target: PluginActor, name: String): Boolean = TODO()

    override fun applyItemAffect(
      item: PluginItem,
      name: String,
      duration: Int?,
      applies: String,
      statDeltas: Map<String, Int>,
      flags: Set<String>,
      senseDeltas: Map<String, Int>,
      maxVitalDeltas: Map<String, Int>,
      resistDeltas: Map<String, Int>,
    ) = TODO()

    override fun removeItemAffect(item: PluginItem, name: String): Boolean = TODO()

    override fun addTag(item: PluginItem, tag: String): Boolean {
      addTagCalls.add(item to tag)
      return true
    }

    override fun removeTag(item: PluginItem, tag: String): Boolean {
      removeTagCalls.add(item to tag)
      return true
    }

    override fun transform(item: PluginItem, label: String) {
      transformCalls.add(item to label)
    }

    override fun setCharges(item: PluginItem, charges: Int) = TODO()

    override fun teleport(actor: PluginActor, room: PluginRoom): String? = TODO()

    override fun wear(actor: PluginActor, item: PluginItem, wield: Boolean): String? = TODO()

    override fun removeWorn(actor: PluginActor, item: PluginItem): String? = TODO()

    override fun mayWear(actor: PluginActor, item: PluginItem, wield: Boolean): String? = TODO()

    override fun mayRemove(actor: PluginActor, item: PluginItem): String? = TODO()

    override fun mayDrop(actor: PluginActor, item: PluginItem): String? = TODO()

    override fun mayGive(actor: PluginActor, item: PluginItem, to: PluginActor): String? = TODO()

    override fun mayPut(actor: PluginActor, item: PluginItem, container: PluginItem): String? =
      TODO()

    override fun mayEnter(actor: PluginActor, room: PluginRoom): String? = TODO()

    override fun mayLearn(actor: PluginActor, skill: String, proficiency: Int): String? = TODO()

    override fun fits(item: PluginItem, to: PluginEntity, count: Int?): Int = TODO()

    override fun fits(label: String, to: PluginEntity, count: Int?): Int = TODO()

    override fun spawnItem(label: String, to: PluginEntity, count: Int): List<PluginItem> = TODO()

    override fun moveItem(item: PluginItem, to: PluginEntity, count: Int?): PluginItem = TODO()

    override fun destroy(item: PluginItem, count: Int?) = TODO()

    override fun mergeStack(item: PluginItem): PluginItem = TODO()

    override fun getAttr(entity: PluginEntity, key: String, default: Any?): Any? = default

    override fun setAttr(entity: PluginEntity, key: String, value: Any?) = TODO()

    override fun delAttr(entity: PluginEntity, key: String) = TODO()

    override fun setChoices(actor: PluginActor, changes: Map<String, String?>): String? = null

    override fun grantTrait(actor: PluginActor, trait: String): String? = null

    override fun removeTrait(actor: PluginActor, trait: String): String? = null

    override fun setSkill(actor: PluginActor, skill: String, proficiency: Int): String? = null

    override fun removeSkill(actor: PluginActor, skill: String): Boolean = false

    override fun awardXp(actor: PluginActor, amount: Long) = TODO()

    override fun takeXp(actor: PluginActor, amount: Long): Long = 0L

    override fun levelUp(actor: PluginActor): Boolean = false

    override fun levelDown(actor: PluginActor, count: Int): Int = 0

    override fun disposition(holder: PluginActor, subject: PluginActor): Int = TODO()

    override fun standing(holder: Any, subject: Any): Int = TODO()

    override fun factionsOf(actor: PluginActor): List<String> = TODO()

    override fun raiseStandingEvent(
      affected: PluginActor,
      actor: PluginActor,
      event: String,
      amount: Long,
      item: PluginItem?,
    ) = TODO()

    override fun adjustStanding(holder: Any, subject: Any, amount: Int) = TODO()

    override fun memories(
      mob: PluginActor,
      subject: PluginActor?,
      event: String?,
      since: Long,
    ): List<gups.pluginapi.PluginMemory> = TODO()

    override fun remember(
      mob: PluginActor,
      subject: PluginActor,
      event: String,
      skill: String?,
      amount: Long,
      target: PluginActor?,
    ) = TODO()

    override fun forget(mob: PluginActor, subject: PluginActor?, event: String?): Int = TODO()

    override fun rooms(): List<PluginRoom> = emptyList()

    override fun room(label: String): PluginRoom? = null

    override fun regions(): List<String> = emptyList()

    override fun diagnose(message: String) = TODO()

    override fun log(message: String) = TODO()
  }

  private val registry = FakeRegistry().also { CursePlugin().register(it) }
  private val world = FakeWorld()
  private val actor = FakeActor(100L)
  private val otherActor = FakeActor(200L)

  private val removeCheck =
    registry.restrictions.getValue(PluginRestrictionPoint.REMOVE).single().second
  private val dropCheck =
    registry.restrictions.getValue(PluginRestrictionPoint.DROP).single().second
  private val giveCheck =
    registry.restrictions.getValue(PluginRestrictionPoint.GIVE).single().second
  private val putCheck = registry.restrictions.getValue(PluginRestrictionPoint.PUT).single().second
  private val liftEffect = registry.effectPrimitives.getValue("lift").second
  private val bestowEffect = registry.effectPrimitives.getValue("bestow").second
  private val containmentFilter = registry.containmentFilters.single().second

  // ---- bearsCurse decision table ------------------------------------------------------------

  @Test
  fun `bearsCurse decision table - plain item is false`() {
    assertFalse(bearsCurse(FakeItem(listOf())))
  }

  @Test
  fun `bearsCurse decision table - cursed item is true`() {
    assertTrue(bearsCurse(FakeItem(listOf("cursed"))))
  }

  @Test
  fun `bearsCurse decision table - bag holding a cursed item is true`() {
    val bag = FakeItem(listOf(), contents = listOf(FakeItem(listOf("cursed"))))
    assertTrue(bearsCurse(bag))
  }

  @Test
  fun `bearsCurse decision table - bag in a bag holding one is true`() {
    val inner = FakeItem(listOf(), contents = listOf(FakeItem(listOf("cursed"))))
    val outer = FakeItem(listOf(), contents = listOf(inner))
    assertTrue(bearsCurse(outer))
  }

  @Test
  fun `bearsCurse decision table - warded box holding one is false`() {
    val box = FakeItem(listOf("warded"), contents = listOf(FakeItem(listOf("cursed"))))
    assertFalse(bearsCurse(box))
  }

  @Test
  fun `bearsCurse decision table - plain bag holding a warded box holding one is false`() {
    val box = FakeItem(listOf("warded"), contents = listOf(FakeItem(listOf("cursed"))))
    val bag = FakeItem(listOf(), contents = listOf(box))
    assertFalse(bearsCurse(bag))
  }

  @Test
  fun `bearsCurse decision table - cursed warded box is true`() {
    val box = FakeItem(listOf("cursed", "warded"))
    assertTrue(bearsCurse(box))
  }

  // ---- REMOVE restriction -------------------------------------------------------------------

  @Test
  fun `REMOVE - a worn cursed item is refused`() {
    val item = FakeItem(listOf("cursed"))
    val call = PluginRestrictionCall(world, PluginRestrictionPoint.REMOVE, actor, item = item)
    assertEquals("It won't budge. It must be cursed!", removeCheck(call))
  }

  @Test
  fun `REMOVE - a worn plain item is allowed`() {
    val item = FakeItem(listOf())
    val call = PluginRestrictionCall(world, PluginRestrictionPoint.REMOVE, actor, item = item)
    assertNull(removeCheck(call))
  }

  @Test
  fun `REMOVE - a worn backpack holding a cursed amulet is allowed`() {
    val backpack = FakeItem(listOf(), contents = listOf(FakeItem(listOf("cursed"))))
    val call = PluginRestrictionCall(world, PluginRestrictionPoint.REMOVE, actor, item = backpack)
    assertNull(removeCheck(call))
  }

  @Test
  fun `REMOVE - a worn item that is both cursed and warded is refused`() {
    val item = FakeItem(listOf("cursed", "warded"))
    val call = PluginRestrictionCall(world, PluginRestrictionPoint.REMOVE, actor, item = item)
    assertEquals("It won't budge. It must be cursed!", removeCheck(call))
  }

  // ---- DROP and GIVE restrictions -----------------------------------------------------------

  @Test
  fun `DROP and GIVE - refused for cursed item and for a bag holding it`() {
    val cursedItem = FakeItem(listOf("cursed"))
    val bag = FakeItem(listOf(), contents = listOf(cursedItem))

    val dropCursed =
      dropCheck(PluginRestrictionCall(world, PluginRestrictionPoint.DROP, actor, item = cursedItem))
    val giveCursed =
      giveCheck(
        PluginRestrictionCall(
          world,
          PluginRestrictionPoint.GIVE,
          actor,
          item = cursedItem,
          target = otherActor,
        )
      )
    val dropBag =
      dropCheck(PluginRestrictionCall(world, PluginRestrictionPoint.DROP, actor, item = bag))
    val giveBag =
      giveCheck(
        PluginRestrictionCall(
          world,
          PluginRestrictionPoint.GIVE,
          actor,
          item = bag,
          target = otherActor,
        )
      )

    assertEquals("It won't budge. It must be cursed!", dropCursed)
    assertEquals("It won't budge. It must be cursed!", giveCursed)
    assertEquals("It won't budge. It must be cursed!", dropBag)
    assertEquals("It won't budge. It must be cursed!", giveBag)
  }

  @Test
  fun `DROP and GIVE - allowed for a warded box holding it`() {
    val cursedItem = FakeItem(listOf("cursed"))
    val box = FakeItem(listOf("warded"), contents = listOf(cursedItem))

    val dropBox =
      dropCheck(PluginRestrictionCall(world, PluginRestrictionPoint.DROP, actor, item = box))
    val giveBox =
      giveCheck(
        PluginRestrictionCall(
          world,
          PluginRestrictionPoint.GIVE,
          actor,
          item = box,
          target = otherActor,
        )
      )

    assertNull(dropBox)
    assertNull(giveBox)
  }

  // ---- PUT within custody -------------------------------------------------------------------

  @Test
  fun `PUT within custody - cursed item into bag in actor's inventory is allowed`() {
    val cursedItem = FakeItem(listOf("cursed"))
    val bagInInv = FakeItem(listOf(), custodian = actor)
    val call =
      PluginRestrictionCall(
        world,
        PluginRestrictionPoint.PUT,
        actor,
        item = cursedItem,
        target = bagInInv,
      )
    assertNull(putCheck(call))
  }

  @Test
  fun `PUT within custody - cursed item into bag inside worn backpack is allowed`() {
    val cursedItem = FakeItem(listOf("cursed"))
    val bagInBackpack = FakeItem(listOf(), custodian = actor)
    val call =
      PluginRestrictionCall(
        world,
        PluginRestrictionPoint.PUT,
        actor,
        item = cursedItem,
        target = bagInBackpack,
      )
    assertNull(putCheck(call))
  }

  @Test
  fun `PUT within custody - cursed item into warded box in inventory is allowed`() {
    val cursedItem = FakeItem(listOf("cursed"))
    val wardedBoxInInv = FakeItem(listOf("warded"), custodian = actor)
    val call =
      PluginRestrictionCall(
        world,
        PluginRestrictionPoint.PUT,
        actor,
        item = cursedItem,
        target = wardedBoxInInv,
      )
    assertNull(putCheck(call))
  }

  // ---- PUT out of custody -------------------------------------------------------------------

  @Test
  fun `PUT out of custody - cursed item into container on the ground is refused`() {
    val cursedItem = FakeItem(listOf("cursed"))
    val groundChest = FakeItem(listOf(), custodian = null)
    val call =
      PluginRestrictionCall(
        world,
        PluginRestrictionPoint.PUT,
        actor,
        item = cursedItem,
        target = groundChest,
      )
    assertEquals("It won't budge. It must be cursed!", putCheck(call))
  }

  @Test
  fun `PUT out of custody - cursed item into container another actor carries is refused`() {
    val cursedItem = FakeItem(listOf("cursed"))
    val otherBag = FakeItem(listOf(), custodian = otherActor)
    val call =
      PluginRestrictionCall(
        world,
        PluginRestrictionPoint.PUT,
        actor,
        item = cursedItem,
        target = otherBag,
      )
    assertEquals("It won't budge. It must be cursed!", putCheck(call))
  }

  @Test
  fun `PUT out of custody - bag holding cursed item into ground chest is refused`() {
    val cursedItem = FakeItem(listOf("cursed"))
    val bag = FakeItem(listOf(), contents = listOf(cursedItem))
    val groundChest = FakeItem(listOf(), custodian = null)
    val call =
      PluginRestrictionCall(
        world,
        PluginRestrictionPoint.PUT,
        actor,
        item = bag,
        target = groundChest,
      )
    assertEquals("It won't budge. It must be cursed!", putCheck(call))
  }

  @Test
  fun `PUT out of custody - warded box holding cursed item into ground chest is allowed`() {
    val cursedItem = FakeItem(listOf("cursed"))
    val wardedBox = FakeItem(listOf("warded"), contents = listOf(cursedItem))
    val groundChest = FakeItem(listOf(), custodian = null)
    val call =
      PluginRestrictionCall(
        world,
        PluginRestrictionPoint.PUT,
        actor,
        item = wardedBox,
        target = groundChest,
      )
    assertNull(putCheck(call))
  }

  // ---- lift with and without a variant ------------------------------------------------------

  @Test
  fun `lift with a variant transforms the item to uncursed variant`() {
    val item = FakeItem(listOf("cursed"), variants = mapOf("uncursed" to "//zones/z:amulet_clean"))
    val call = PluginEffectCall(world, actor, target = null, args = emptyMap(), targetItem = item)
    liftEffect.apply(call)

    assertEquals(
      listOf<Pair<PluginItem, String>>(item to "//zones/z:amulet_clean"),
      world.transformCalls,
    )
    assertEquals(emptyList<Pair<PluginItem, String>>(), world.removeTagCalls)
    assertEquals(emptyList<Pair<PluginItem, String>>(), world.addTagCalls)
  }

  @Test
  fun `lift without a variant removes cursed tag`() {
    val item = FakeItem(listOf("cursed"), variants = emptyMap())
    val call = PluginEffectCall(world, actor, target = null, args = emptyMap(), targetItem = item)
    liftEffect.apply(call)

    assertEquals(emptyList<Pair<PluginItem, String>>(), world.transformCalls)
    assertEquals(listOf<Pair<PluginItem, String>>(item to "cursed"), world.removeTagCalls)
    assertEquals(emptyList<Pair<PluginItem, String>>(), world.addTagCalls)
  }

  @Test
  fun `lift with null targetItem does nothing`() {
    val call = PluginEffectCall(world, actor, target = null, args = emptyMap(), targetItem = null)
    liftEffect.apply(call)

    assertEquals(emptyList<Pair<PluginItem, String>>(), world.transformCalls)
    assertEquals(emptyList<Pair<PluginItem, String>>(), world.removeTagCalls)
    assertEquals(emptyList<Pair<PluginItem, String>>(), world.addTagCalls)
  }

  // ---- bestow -------------------------------------------------------------------------------

  @Test
  fun `bestow adds cursed tag to the item`() {
    val item = FakeItem(listOf())
    val call = PluginEffectCall(world, actor, target = null, args = emptyMap(), targetItem = item)
    bestowEffect.apply(call)

    assertEquals(listOf<Pair<PluginItem, String>>(item to "cursed"), world.addTagCalls)
    assertEquals(emptyList<Pair<PluginItem, String>>(), world.removeTagCalls)
    assertEquals(emptyList<Pair<PluginItem, String>>(), world.transformCalls)
  }

  @Test
  fun `bestow with null targetItem does nothing`() {
    val call = PluginEffectCall(world, actor, target = null, args = emptyMap(), targetItem = null)
    bestowEffect.apply(call)

    assertEquals(emptyList<Pair<PluginItem, String>>(), world.addTagCalls)
  }

  // ---- Containment filter decision table ----------------------------------------------------

  @Test
  fun `filter decision table - warded container and cursed item blocks`() {
    val container = FakeItem(listOf("warded"))
    val item = FakeItem(listOf("cursed"))
    val affect =
      PluginItemAffect("misfortune", "carried", emptyList(), emptyMap(), emptyMap(), "proto", null)
    val call = PluginContainmentCall(world, actor, container, item, affect)

    assertTrue(containmentFilter(call))
  }

  @Test
  fun `filter decision table - warded container and plain item does not block`() {
    val container = FakeItem(listOf("warded"))
    val item = FakeItem(listOf())
    val affect =
      PluginItemAffect("luck", "carried", emptyList(), emptyMap(), emptyMap(), "proto", null)
    val call = PluginContainmentCall(world, actor, container, item, affect)

    assertFalse(containmentFilter(call))
  }

  @Test
  fun `filter decision table - plain container and cursed item does not block`() {
    val container = FakeItem(listOf())
    val item = FakeItem(listOf("cursed"))
    val affect =
      PluginItemAffect("misfortune", "carried", emptyList(), emptyMap(), emptyMap(), "proto", null)
    val call = PluginContainmentCall(world, actor, container, item, affect)

    assertFalse(containmentFilter(call))
  }

  @Test
  fun `filter decision table - plain container and plain item does not block`() {
    val container = FakeItem(listOf())
    val item = FakeItem(listOf())
    val affect =
      PluginItemAffect("luck", "carried", emptyList(), emptyMap(), emptyMap(), "proto", null)
    val call = PluginContainmentCall(world, actor, container, item, affect)

    assertFalse(containmentFilter(call))
  }

  // ---- The shop_price hook handler ------------------------------------------------------------

  private val shopPriceHandler = registry.hooks.single().second

  @Test
  fun `the plugin registers one shop_price hook`() {
    assertEquals(1, registry.hooks.size)
    assertEquals("shop_price", registry.hooks.single().first)
  }

  /** A `shop_price` call on the keeper [actor], as the shop library makes it. */
  private fun hookCall(action: String, item: PluginItem?, price: Long?, result: Any?) =
    object : PluginHookCall {
      override val world: PluginGmcpWorld = this@CursePluginTest.world
      override val entity = actor
      override val name = "shop_price"
      override val args: Map<String, Any?> =
        mapOf("action" to action, "item" to item, "price" to price, "refusal" to null)
      override val result = result
    }

  @Test
  fun `shop_price surcharges buying a cursed item`() {
    val cursed = FakeItem(listOf("cursed"))
    assertEquals(35L, shopPriceHandler(hookCall("buy", cursed, 10L, null)))
    assertEquals(35L, shopPriceHandler(hookCall("list", cursed, 10L, null)))
  }

  @Test
  fun `shop_price surcharges the keeper's own price`() {
    assertEquals(37L, shopPriceHandler(hookCall("buy", FakeItem(listOf("cursed")), 10L, 12L)))
  }

  @Test
  fun `shop_price leaves clean items, sales and refusals alone`() {
    val cursed = FakeItem(listOf("cursed"))
    assertNull(shopPriceHandler(hookCall("buy", FakeItem(listOf()), 10L, null)))
    assertNull(shopPriceHandler(hookCall("sell", cursed, 10L, null)))
    assertNull(shopPriceHandler(hookCall("buy", null, 10L, null)))
    assertNull(shopPriceHandler(hookCall("buy", cursed, null, null)))
    assertEquals("Not you.", shopPriceHandler(hookCall("buy", cursed, 10L, "Not you.")))
  }
}
