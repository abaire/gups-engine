package gups.script

import gups.core.world.HookName

/** Kinds of entity a hook can be attached to. */
enum class OwnerKind(val word: String) {
  ROOM("room"),
  MOB("mob"),
  ITEM("item"),
}

/** The hook catalog: attachment rules, signatures, ctx fields and docs. */
internal object HookCatalog {
  private val R = OwnerKind.ROOM
  private val M = OwnerKind.MOB
  private val I = OwnerKind.ITEM

  val specs: List<HookSpec> =
    listOf(
      HookSpec(
        hook = HookName.ON_LOAD,
        signature = "fn(ctx)",
        attachTo = listOf(R, M, I),
        ctxFields = listOf("self", "room", "pulse", "rng"),
        doc =
          "Fires when a room is created on world load or reload, or when a mob or item is loaded.",
      ),
      HookSpec(
        hook = HookName.ON_SPAWN,
        signature = "fn(ctx)",
        attachTo = listOf(R, M, I),
        ctxFields = listOf("self", "item", "room", "pulse", "rng"),
        doc = "Fires when a mob or item is freshly spawned, or on a room when a spawn rule fires.",
      ),
      HookSpec(
        hook = HookName.ON_RESET,
        signature = "fn(ctx)",
        attachTo = listOf(R),
        ctxFields = listOf("self", "room", "pulse", "rng"),
        doc = "Fires on a room when its zone resets.",
      ),
      HookSpec(
        hook = HookName.ON_WAKE,
        signature = "fn(ctx, elapsed_pulses)",
        attachTo = listOf(R, M, I),
        ctxFields = listOf("self", "room", "elapsed_pulses", "pulse", "rng"),
        doc = "Fires when an entity wakes from dormancy as the reality bubble reaches its room.",
      ),
      HookSpec(
        hook = HookName.BEFORE_ENTER,
        signature = "fn(ctx)",
        attachTo = listOf(R, M),
        ctxFields = listOf("self", "actor", "direction", "from_room", "room", "pulse", "rng"),
        doc = "Fires before an actor enters the room; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.ON_ENTER,
        signature = "fn(ctx)",
        attachTo = listOf(R, M),
        ctxFields = listOf("self", "actor", "direction", "from_room", "room", "pulse", "rng"),
        doc = "Fires after an actor enters the room.",
      ),
      HookSpec(
        hook = HookName.BEFORE_EXIT,
        signature = "fn(ctx)",
        attachTo = listOf(R, M),
        ctxFields = listOf("self", "actor", "direction", "to_room", "room", "pulse", "rng"),
        doc = "Fires before an actor leaves the room through an exit; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.ON_EXIT,
        signature = "fn(ctx)",
        attachTo = listOf(R, M),
        ctxFields = listOf("self", "actor", "direction", "to_room", "room", "pulse", "rng"),
        doc = "Fires after an actor leaves the room through an exit.",
      ),
      HookSpec(
        hook = HookName.ON_SAY,
        signature = "fn(ctx)",
        attachTo = listOf(M, R),
        ctxFields = listOf("self", "actor", "text", "room", "pulse", "rng"),
        doc = "Fires when an actor says something aloud in the room.",
      ),
      HookSpec(
        hook = HookName.ON_TELL,
        signature = "fn(ctx)",
        attachTo = listOf(M),
        ctxFields = listOf("self", "actor", "text", "room", "pulse", "rng"),
        doc = "Fires on a mob when an actor sends it a tell.",
      ),
      HookSpec(
        hook = HookName.BEFORE_GET,
        signature = "fn(ctx)",
        attachTo = listOf(I, M),
        ctxFields = listOf("self", "actor", "item", "room", "pulse", "rng"),
        doc = "Fires before an actor picks up an item; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.ON_GET,
        signature = "fn(ctx)",
        attachTo = listOf(I, M),
        ctxFields = listOf("self", "actor", "item", "room", "pulse", "rng"),
        doc = "Fires after an actor picks up an item.",
      ),
      HookSpec(
        hook = HookName.BEFORE_DROP,
        signature = "fn(ctx)",
        attachTo = listOf(I, M),
        ctxFields = listOf("self", "actor", "item", "room", "pulse", "rng"),
        doc = "Fires before an actor drops an item; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.ON_DROP,
        signature = "fn(ctx)",
        attachTo = listOf(I, M),
        ctxFields = listOf("self", "actor", "item", "room", "pulse", "rng"),
        doc = "Fires after an actor drops an item.",
      ),
      HookSpec(
        hook = HookName.BEFORE_USE,
        signature = "fn(ctx)",
        attachTo = listOf(I),
        ctxFields = listOf("self", "actor", "item", "target", "room", "pulse", "rng"),
        doc = "Fires before an actor uses an item; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.ON_USE,
        signature = "fn(ctx)",
        attachTo = listOf(I),
        ctxFields = listOf("self", "actor", "item", "target", "room", "pulse", "rng"),
        doc = "Fires when an actor uses an item.",
      ),
      HookSpec(
        hook = HookName.ON_GIVE,
        signature = "fn(ctx)",
        attachTo = listOf(M, I),
        ctxFields = listOf("self", "actor", "item", "target", "room", "pulse", "rng"),
        doc = "Fires when an actor gives an item to another actor.",
      ),
      HookSpec(
        hook = HookName.BEFORE_ATTACK,
        signature = "fn(ctx)",
        attachTo = listOf(M, I),
        ctxFields = listOf("self", "actor", "target", "weapon", "room", "pulse", "rng"),
        doc = "Fires before an attack swing; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.BEFORE_CAST,
        signature = "fn(ctx)",
        attachTo = listOf(M, I, R),
        ctxFields = listOf("self", "actor", "skill", "target", "room", "pulse", "rng"),
        doc = "Fires before casting begins; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.BEFORE_ORDER,
        signature = "fn(ctx)",
        attachTo = listOf(M, R),
        ctxFields = listOf("self", "actor", "target", "text", "room", "pulse", "rng"),
        doc = "Fires before a pet is ordered; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.BEFORE_SUMMON,
        signature = "fn(ctx)",
        attachTo = listOf(R, M),
        ctxFields = listOf("self", "actor", "label", "count", "room", "pulse", "rng"),
        doc = "Fires before mobs are summoned; return ctx.deny(msg) to veto.",
      ),
      HookSpec(
        hook = HookName.ON_CAST_START,
        signature = "fn(ctx)",
        attachTo = listOf(M, I, R),
        ctxFields = listOf("self", "actor", "skill", "target", "room", "pulse", "rng"),
        doc = "Fires when a skill enters casting warmup.",
      ),
      HookSpec(
        hook = HookName.ON_CAST,
        signature = "fn(ctx)",
        attachTo = listOf(M, I, R),
        ctxFields = listOf("self", "actor", "skill", "target", "room", "pulse", "rng"),
        doc = "Fires after a skill resolves.",
      ),
      HookSpec(
        hook = HookName.ON_SUMMON,
        signature = "fn(ctx)",
        attachTo = listOf(R, M),
        ctxFields = listOf("self", "actor", "targets", "room", "pulse", "rng"),
        doc = "Fires once every mob of the summon has spawned.",
      ),
      HookSpec(
        hook = HookName.ON_DAMAGE,
        signature = "fn(ctx)",
        attachTo = listOf(M, I),
        ctxFields =
          listOf(
            "self",
            "actor",
            "target",
            "amount",
            "type",
            "losses",
            "parts",
            "room",
            "pulse",
            "rng",
          ),
        doc = "Fires when an actor takes damage.",
      ),
      HookSpec(
        hook = HookName.ON_DEATH,
        signature = "fn(ctx)",
        attachTo = listOf(M, R),
        ctxFields = listOf("self", "actor", "target", "room", "pulse", "rng"),
        doc = "Fires when an actor dies; also a global hook (lib and rules packages).",
      ),
      HookSpec(
        hook = HookName.ON_LEVEL_CHANGE,
        signature = "fn(ctx)",
        attachTo = listOf(M, R, I),
        ctxFields = listOf("self", "actor", "old_level", "new_level", "room", "pulse", "rng"),
        doc = "Fires once per level step when an actor's level changes up or down.",
      ),
      HookSpec(
        hook = HookName.ON_ENCUMBRANCE_CHANGE,
        signature = "fn(ctx)",
        attachTo = emptyList(),
        ctxFields =
          listOf(
            "self",
            "actor",
            "old_encumbrance_level",
            "new_encumbrance_level",
            "room",
            "pulse",
            "rng",
          ),
        doc = "Global hook: fires once for each change of an actor's encumbrance level.",
      ),
      HookSpec(
        hook = HookName.ON_GROUP_CHANGE,
        signature = "fn(ctx)",
        attachTo = emptyList(),
        ctxFields = listOf("self", "actor", "event", "group", "room", "pulse", "rng"),
        doc =
          "Global hook: fires for each player-group change, with ctx.event the 'join', 'leave', 'leader' or 'disband'.",
      ),
      HookSpec(
        hook = HookName.ON_OWNER_CHANGE,
        signature = "fn(ctx)",
        attachTo = emptyList(),
        ctxFields = listOf("self", "actor", "target", "event", "room", "pulse", "rng"),
        doc =
          "Global hook: fires after a mob's owner is set or cleared, with ctx.actor the mob, ctx.target the owner and ctx.event 'set' or 'clear'.",
      ),
      HookSpec(
        hook = HookName.ON_SKILL_USE,
        signature = "fn(ctx)",
        attachTo = emptyList(),
        ctxFields =
          listOf(
            "self",
            "actor",
            "skill",
            "target",
            "item",
            "targets",
            "outcome",
            "room",
            "pulse",
            "rng",
          ),
        doc =
          "Global hook: fires for each use of a skill, with ctx.outcome 'fail', 'resist', 'success' or 'report'.",
      ),
      HookSpec(
        hook = HookName.ON_COMBAT_TICK,
        signature = "fn(ctx)",
        attachTo = listOf(M, I),
        ctxFields = listOf("self", "actor", "targets", "room", "pulse", "rng"),
        doc = "Fires once per combat round on each fighting mob and its equipped items.",
      ),
      HookSpec(
        hook = HookName.ON_DISENGAGE,
        signature = "fn(ctx)",
        attachTo = listOf(M, R),
        ctxFields = listOf("self", "actor", "event", "room", "pulse", "rng"),
        doc =
          "Fires when an engaged actor has no opponent left; ctx.event is 'flee', 'death' or 'stop'.",
      ),
      HookSpec(
        hook = HookName.ON_TICK,
        signature = "fn(ctx)",
        attachTo = listOf(R, M, I),
        ctxFields = listOf("self", "room", "pulse", "rng"),
        doc = "Fires on each world tick while simulated.",
      ),
      HookSpec(
        hook = HookName.ON_PERCEIVE,
        signature = "fn(ctx)",
        attachTo = listOf(M),
        ctxFields =
          listOf(
            "self",
            "event",
            "channel",
            "intensity",
            "cost",
            "actor",
            "source_room",
            "via_exit",
            "text",
            "room",
            "pulse",
            "rng",
          ),
        doc =
          "Fires on a mob when `rules.can_perceive` lets it notice an event on a channel, in its room or carried to it from farther away.",
      ),
    ) + manipulationHooks()

  /** The before and after hooks of `open`, `close`, `lock` and `unlock` on a door or container. */
  private fun manipulationHooks(): List<HookSpec> =
    listOf(
        HookName.BEFORE_OPEN to "opening",
        HookName.ON_OPEN to "opened",
        HookName.BEFORE_CLOSE to "closing",
        HookName.ON_CLOSE to "closed",
        HookName.BEFORE_LOCK to "locking",
        HookName.ON_LOCK to "locked",
        HookName.BEFORE_UNLOCK to "unlocking",
        HookName.ON_UNLOCK to "unlocked",
      )
      .map { (hook, word) ->
        HookSpec(
          hook = hook,
          signature = "fn(ctx)",
          attachTo = listOf(I, R, M),
          ctxFields = listOf("self", "actor", "direction", "item", "room", "pulse", "rng"),
          doc =
            if (hook.vetoable)
              "Fires before an actor starts $word a door or container; return ctx.deny(msg) to veto. For a door ctx.direction is set and ctx.item is None; for a container it is the other way around."
            else
              "Fires after a door or container is $word. For a door ctx.direction is set and ctx.item is None; for a container it is the other way around.",
        )
      }

  val attach: Map<HookName, Set<OwnerKind>> = specs.associate { it.hook to it.attachTo.toSet() }

  val names: List<String> = HookName.entries.map { it.scriptName }

  fun byName(name: String): HookName? = HookName.entries.firstOrNull { it.scriptName == name }

  fun looksLikeHook(name: String): Boolean = name.startsWith("on_") || name.startsWith("before_")
}
