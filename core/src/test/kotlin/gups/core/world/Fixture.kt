package gups.core.world

import gups.core.CapturingSink
import gups.core.ClientInputEvent
import gups.core.HookRef
import gups.core.Label
import gups.core.PULSE_MS
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Band
import gups.core.model.Dice
import gups.core.model.Direction
import gups.core.model.DoorProto
import gups.core.model.Effect
import gups.core.model.Entity
import gups.core.model.ExitProto
import gups.core.model.Interrupt
import gups.core.model.ItemProto
import gups.core.model.LootRule
import gups.core.model.Mob
import gups.core.model.MobProto
import gups.core.model.ModifierPoint
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import gups.core.model.SkillProto
import gups.core.model.SkillRange
import gups.core.model.TargetKind
import gups.core.model.TrackSize
import gups.core.rules.DefaultRules
import gups.core.rules.RulesContext
import gups.core.rules.RulesEngine

/**
 * The Kotlin test world:
 * ```
 *            yard (NORMAL)
 *              | north / south (arrive at hall's back)
 * closet ── hall (LARGE) ── east, entry index 2 (clamped in the cramped closet)
 *              | south, locked door (key: //t:key)
 *            vault (NORMAL)
 * ```
 */
object T {
  val HALL = Label("//t:hall")
  val YARD = Label("//t:yard")
  val CLOSET = Label("//t:closet")
  val VAULT = Label("//t:vault")
  val GOBLIN = Label("//t:goblin")
  val DUMMY = Label("//t:dummy")
  val KEY = Label("//t:key")
  val SWORD = Label("//t:sword")
  val BOW = Label("//t:bow")
  val BAG = Label("//t:bag")
  val BOLT = Label("//t:bolt")
  val ZAP = Label("//t:zap")
  val MEND = Label("//t:mend")
  val BASH = Label("//t:bash")

  val rooms =
    listOf(
      RoomProto(
        HALL,
        "The Hall",
        "A long hall.",
        TrackSize.LARGE,
        exits =
          listOf(
            ExitProto(Direction.NORTH, YARD),
            ExitProto(Direction.EAST, CLOSET, entryIndex = 2),
            ExitProto(
              Direction.SOUTH,
              VAULT,
              door = DoorProto(gups.core.model.DoorState.LOCKED, key = KEY),
            ),
          ),
      ),
      RoomProto(
        YARD,
        "The Yard",
        size = TrackSize.NORMAL,
        exits = listOf(ExitProto(Direction.SOUTH, HALL, entryIndex = -1)),
      ),
      RoomProto(
        CLOSET,
        "A Closet",
        size = TrackSize.CRAMPED,
        exits = listOf(ExitProto(Direction.WEST, HALL)),
      ),
      RoomProto(
        VAULT,
        "The Vault",
        exits =
          listOf(
            ExitProto(
              Direction.NORTH,
              HALL,
              door = DoorProto(gups.core.model.DoorState.LOCKED, key = KEY),
            )
          ),
      ),
    )

  val mobs =
    listOf(
      MobProto(
        GOBLIN,
        "a goblin",
        listOf("goblin"),
        level = 2,
        hp = 20,
        loot = listOf(LootRule(KEY)),
      ),
      MobProto(DUMMY, "a training dummy", listOf("dummy"), hp = 1000),
    )

  val items =
    listOf(
      ItemProto(KEY, "a brass key", listOf("key", "brass")),
      ItemProto(
        SWORD,
        "a sword",
        listOf("sword"),
        kind = "melee",
        wear = "wield",
        dice = Dice(1, 1, 4),
      ),
      ItemProto(
        BOW,
        "a short bow",
        listOf("bow"),
        kind = "ranged",
        range = Band.FAR,
        wear = "wield",
        dice = Dice(1, 1, 2),
      ),
      ItemProto(BAG, "a bag", listOf("bag"), container = gups.core.model.ContainerProto()),
    )

  val skills =
    listOf(
      SkillProto(
        BOLT,
        kind = "spell",
        cost = mapOf("mana" to 10),
        warmup = 4,
        lag = 4,
        range = SkillRange.FAR,
        interrupt = Interrupt(damagePct = 10, stun = true),
        effect = Effect.Damage(Dice(1, 1, 7), gups.core.model.DamageType.FIRE),
      ),
      SkillProto(
        ZAP,
        kind = "spell",
        range = SkillRange.FAR,
        effect = Effect.Damage(Dice(1, 1, 4), gups.core.model.DamageType.FIRE),
      ),
      SkillProto(
        MEND,
        kind = "spell",
        range = SkillRange.FAR,
        target = TargetKind.ALLY,
        effect = Effect.Heal(Dice(1, 1, 9)),
      ),
      SkillProto(BASH, range = SkillRange.MELEE, lag = 2, effect = Effect.Stun(3)),
    )

  fun protos(extraRooms: List<RoomProto> = emptyList(), extraMobs: List<MobProto> = emptyList()) =
    Prototypes(rooms + extraRooms, mobs + extraMobs, items, skills)
}

/** Rules with pinned results; anything left null falls back to [DefaultRules]. */
class FixedRules(
  var toHit: Int? = 100,
  var damage: Int? = 5,
  var initiative: Map<String, Int> = emptyMap(),
  var flee: Int? = null,
  var retreat: Int? = null,
) : DefaultRules() {
  override fun toHit(c: RulesContext): Int = toHit ?: super.toHit(c)

  override fun damage(c: RulesContext): Int = damage ?: super.damage(c)

  override fun mitigate(c: RulesContext, amount: Int, type: gups.core.model.DamageType): Int =
    amount

  override fun initiative(c: RulesContext): Int = initiative[c.actor.name] ?: 10

  override fun fleeChance(c: RulesContext): Int = flee ?: super.fleeChance(c)

  override fun retreatCheck(c: RulesContext): Int = retreat ?: super.retreatCheck(c)
}

/** A script host built from Kotlin lambdas, recording every invocation. */
class RecordingHost : ScriptHost {
  val calls = mutableListOf<String>()
  val handlers = mutableMapOf<HookName, (World, Entity, HookContext) -> HookOutcome?>()

  /** Modifier functions by [HookRef.name]: `(point, ctx, base) -> value`. */
  val modifiers = mutableMapOf<String, (ModifierPoint, RulesContext, Any?) -> Any?>()

  override fun invokeHook(
    world: World,
    owner: Entity,
    hook: HookName,
    ctx: HookContext,
  ): HookOutcome {
    val h = handlers[hook] ?: return HookOutcome.Allow
    calls += "${hook.scriptName}:${owner.name}"
    return h(world, owner, ctx) ?: HookOutcome.Allow
  }

  /** Run on a timer or affect callback, by function name, after it is recorded in [calls]. */
  val refHandlers = mutableMapOf<String, (World, Entity, HookContext) -> Unit>()

  override fun invokeRef(world: World, owner: Entity, ref: HookRef, ctx: HookContext) {
    calls += "ref:${ref.name}@${world.pulse}"
    refHandlers[ref.name]?.invoke(world, owner, ctx)
  }

  override fun hasHook(owner: Entity, hook: HookName): Boolean = hook in handlers

  override fun modify(
    world: World,
    point: ModifierPoint,
    ref: HookRef,
    source: String,
    c: RulesContext,
    base: Any?,
  ): Any? {
    calls += "mod:$source:${ref.name}"
    return modifiers[ref.name]?.invoke(point, c, base) ?: base
  }
}

/** A modifier reference for [RecordingHost.modifiers]. */
fun mod(name: String) = HookRef(Label("//t:mods.star"), name)

/** The aliases and prefixes the template world's `MODULE.gups` ships with. */
val TEMPLATE_ALIASES =
  CommandAliases(
    words =
      mapOf(
        "l" to "look",
        "i" to "inventory",
        "inv" to "inventory",
        "eq" to "equipment",
        "k" to "kill",
        "attack" to "kill",
        "c" to "cast",
        "take" to "get",
        "unwield" to "remove",
      ),
    prefixes = mapOf('\'' to "say", '"' to "say", '%' to "cast"),
  )

/** A headless world with connected players and a virtual clock. */
class Fixture(
  seed: Long = 1,
  val rules: RulesEngine = FixedRules(),
  scripts: ScriptHost = ScriptHost.NONE,
  protos: Prototypes = T.protos(),
  strict: Boolean = true,
  bubble: Boolean = false,
  aliases: CommandAliases = TEMPLATE_ALIASES,
) {
  val w =
    World(
      protos,
      rules,
      scripts,
      WorldConfig(
        seed = seed,
        checkInvariants = true,
        strict = strict,
        bubble = bubble,
        aliases = aliases,
      ),
    )
  private var nextSession = 1L
  private val sinks = mutableMapOf<Player, CapturingSink>()

  /** Virtual wall clock for the rate limiter. */
  var nanos = 0L

  fun player(
    name: String,
    at: Label = T.HALL,
    index: Int = 0,
    hp: Int = 50,
    mana: Int = 50,
  ): Player {
    val p = w.spawnPlayer(name, at, hp = hp, mana = mana, index = index)
    connect(p)
    return p
  }

  fun connect(p: Player): CapturingSink {
    val id = SessionId(nextSession++)
    val sink = CapturingSink()
    w.handle(WorldControlEvent.Connect(id, sink), nanos)
    w.attach(id, p)
    sinks[p] = sink
    return sink
  }

  fun mob(label: Label = T.GOBLIN, at: Label = T.HALL, index: Int = 0): Mob =
    w.spawnMob(label, w.room(at), index)

  fun input(p: Player, line: String) = w.handle(ClientInputEvent(p.session!!, line, nanos))

  fun sink(p: Player): CapturingSink = sinks.getValue(p)

  /** Output since the last call. */
  fun out(p: Player): String = sink(p).drain()

  fun run(pulses: Int = 1) =
    repeat(pulses) {
      w.runPulse()
      nanos += PULSE_MS * 1_000_000
    }

  /** Runs until the pulse counter reaches [pulse]. */
  fun runTo(pulse: Long) {
    while (w.pulse < pulse) run()
  }
}
