package gups.pluginapi

/**
 * What a plugin may do without changing the world: reads, messages and GMCP events. GMCP input
 * handlers get only this view, and act through [PluginGmcpCall.command].
 */
interface PluginGmcpWorld {
  /**
   * The features this world has: the engine features that are on (`combat`, `track`, `skills`,
   * `leveling`, `standing`) and the features loaded plugins provide.
   */
  val features: Set<String>
    get() = setOf("combat", "track", "skills", "leveling")

  fun send(target: PluginActor, text: String)

  /** A loaded skill's view by absolute label, or null. */
  fun skill(label: String): PluginSkill? = null

  /** A loaded item prototype's catalog fields by absolute label, or null; nothing is spawned. */
  fun itemInfo(label: String): PluginItemInfo? = null

  /**
   * Sends the GMCP event [pkg] with [data] (plain values) to [actor]. [pkg] must be, or nest in, a
   * package this plugin declared with addGmcp or addGmcpEvent. Returns false, sending nothing, for
   * a mob, a link-dead player, or a client that doesn't support [pkg].
   */
  fun gmcp(actor: PluginActor, pkg: String, data: Any?): Boolean

  /** Reads an attribute; a key without `ns:` is in the plugin's own namespace (its id). */
  fun getAttr(entity: PluginEntity, key: String, default: Any? = null): Any?

  /**
   * What [holder] did or lost with [subject] since [since] pulse, summing the standing changes in
   * each direction; a negative [since] counts as 0. [via], when given, must be a mob and filters
   * the changes it applied. [event], when given, is an exact event-name filter. Needs `standing`.
   */
  fun standingHistory(
    holder: Any,
    subject: Any,
    since: Long,
    via: PluginActor? = null,
    event: String? = null,
  ): PluginStandingTotals = throw UnsupportedOperationException("standingHistory is not supported")

  /** Opponents of [actor] in its room within [band] ("melee", "near" or "far"), by id. */
  fun enemiesNear(actor: PluginActor, band: String): List<PluginActor> =
    throw UnsupportedOperationException("enemiesNear is not supported")

  /** Allies of [actor] in its room within [band] ("melee", "near" or "far"), by id. */
  fun alliesNear(actor: PluginActor, band: String): List<PluginActor> =
    throw UnsupportedOperationException("alliesNear is not supported")

  /** Directions of the shortest passable walk; empty when [from] is [to], null when unreachable. */
  fun pathTo(from: PluginRoom, to: PluginRoom): List<String>? =
    throw UnsupportedOperationException("pathTo is not supported")

  /** Goal-oriented planning as `ctx.plan`; fact values are Boolean, Int/Long or String. */
  fun plan(
    facts: Map<String, Any>,
    actions: List<PluginPlanAction>,
    goal: Map<String, Any>,
    maxExpansions: Int = 1024,
  ): List<String>? = throw UnsupportedOperationException("plan is not supported")

  /** The player group [actor] is in, its own or a pet's owner's, or null when it has none. */
  fun groupOf(actor: PluginActor): PluginGroup? =
    throw UnsupportedOperationException("groupOf is not supported")

  /** The members of [actor]'s group, leader first; empty when it has none. */
  fun groupMembers(actor: PluginActor): List<PluginActor> =
    throw UnsupportedOperationException("groupMembers is not supported")

  // ---- Reads ----

  /** The names the world declares, in declaration order. */
  val schema: PluginSchema
  val pulse: Long
  val seed: Long

  /** Every room, sorted by label. */
  fun rooms(): List<PluginRoom>

  fun room(label: String): PluginRoom?

  /** Every region tag a room carries, sorted. */
  fun regions(): List<String>

  // ---- Diagnostics ----

  /** Records a `"plugin"` diagnostic. */
  fun diagnose(message: String)

  /** Writes to the server log. */
  fun log(message: String)
}

/**
 * Safe facade interface exposing the running simulation world to plugins.
 *
 * ## Facade Verb Guarantees & Invariant Preservation
 * - Every verb (`send`, `echo`, `damage`, `heal`, `applyAffect`, `removeAffect`, `teleport`,
 *   `setChoices`, `grantTrait`, `setSkill`, `awardXp`, `takeXp`, `levelUp`, `levelDown`) directly
 *   delegates to the engine's internal implementation of the corresponding Starlark `ctx` verb.
 * - This guarantees that all game invariants, state checks, entity dirty flags, immediate
 *   checkpoint triggers, event cascades, and perception broadcasts behave identically whether
 *   invoked from Starlark scripts or Kotlin plugins.
 *
 * ## Live State Views
 * - Entity handles ([PluginRoom], [PluginActor], [PluginItem]) are lightweight views that re-read
 *   current live state on every property access, preventing stale reads across pulse boundaries.
 *
 * ## Attribute Namespacing
 * - Attribute operations ([getAttr], [setAttr], [delAttr]) automatically default to the plugin's
 *   own namespace prefix (`<pluginId>:<key>`), isolating plugin-specific state from core engine
 *   attributes and other plugins.
 */
interface PluginWorld : PluginGmcpWorld {
  /**
   * Calls this plugin's declared rules export [name]; returns a Long or Boolean, or [default] when
   * the world doesn't define it or the call fails. [fields] named `actor`, `target`, `source`,
   * `skill` and `item` become those `ctx` fields, `base` becomes `ctx.base`, and the rest
   * `ctx.args`.
   */
  fun rules(name: String, fields: Map<String, Any?>, default: Any): Any = default

  // ---- Verbs ----

  fun echo(room: PluginRoom, text: String, exclude: List<PluginActor> = emptyList())

  /** Raw damage to one vital: no to-hit roll and no mitigation, as `ctx.damage`. */
  fun damage(
    target: PluginActor,
    amount: Int,
    type: String,
    vital: String,
    source: PluginActor? = null,
  )

  /**
   * Restores [amount] points of [target]'s [vital], up to its maximum, as `ctx.heal`. [source],
   * when given, is credited with the healing: it gains standing with [target] and any assists.
   */
  fun heal(target: PluginActor, amount: Int, vital: String, source: PluginActor? = null)

  fun applyAffect(
    target: PluginActor,
    name: String,
    duration: Int,
    statDeltas: Map<String, Int> = emptyMap(),
    flags: Set<String> = emptySet(),
    maxVitalDeltas: Map<String, Int> = emptyMap(),
    resistDeltas: Map<String, Int> = emptyMap(),
  )

  fun removeAffect(target: PluginActor, name: String): Boolean

  fun applyItemAffect(
    item: PluginItem,
    name: String,
    duration: Int?,
    applies: String = "worn",
    statDeltas: Map<String, Int> = emptyMap(),
    flags: Set<String> = emptySet(),
    senseDeltas: Map<String, Int> = emptyMap(),
    maxVitalDeltas: Map<String, Int> = emptyMap(),
    resistDeltas: Map<String, Int> = emptyMap(),
  )

  fun removeItemAffect(item: PluginItem, name: String): Boolean

  fun addTag(item: PluginItem, tag: String): Boolean

  fun removeTag(item: PluginItem, tag: String): Boolean

  fun transform(item: PluginItem, label: String)

  /**
   * Sets [item]'s remaining uses, from 0 to its prototype's charges; 0 doesn't run its empty
   * action.
   */
  fun setCharges(item: PluginItem, charges: Int)

  /**
   * Moves [actor] to [room] with no exit: the ENTER restriction, then `before_enter`; guards and
   * `before_exit` don't run. Returns the refusal or veto message, moving nothing, or null once the
   * actor has moved.
   */
  fun teleport(actor: PluginActor, room: PluginRoom): String?

  /**
   * Spawns [count] of the item prototype [label] at [to]: a room (at track index 0), an actor's
   * inventory, or a container item, as `ctx.spawn` does for items. Returns one stack for a
   * stackable label and [count] separate items otherwise. Skips item restrictions and the commands'
   * carry and container limits ([fits] asks them). A mob label, an unknown label, a [count] below
   * 1, or an item [to] that isn't a container is an error.
   */
  fun spawnItem(label: String, to: PluginEntity, count: Int = 1): List<PluginItem>

  /**
   * Moves [count] (by default the whole stack) of [item] to [to], a room (at track index 0), an
   * actor's inventory, or a container item, as `ctx.move_item` does, and returns the item now
   * there. Skips item restrictions and the commands' limits. A [count] outside 1..the stack's, or
   * an item [to] that isn't a container, is an error. There is no `give`: moving or spawning to an
   * actor puts the items in its inventory.
   */
  fun moveItem(item: PluginItem, to: PluginEntity, count: Int? = null): PluginItem

  /**
   * Removes [count] (by default the whole stack) of [item] from the world, with everything inside
   * it when whole, as `ctx.destroy` does. A [count] outside 1..the stack's is an error.
   */
  fun destroy(item: PluginItem, count: Int? = null)

  /**
   * Merges [item] into an identical stack in the same place, as `ctx.merge_stack` does; returns the
   * stack that holds it.
   */
  fun mergeStack(item: PluginItem): PluginItem

  /**
   * Puts on an item [actor] carries as the `wear` command (or `wield`) would, asking the same
   * restrictions and taking off at most one worn item into the inventory. Sends nothing. Returns
   * the refusal message, or null once worn; an item the actor doesn't carry is an error.
   */
  fun wear(actor: PluginActor, item: PluginItem, wield: Boolean = false): String?

  /**
   * Takes off an item [actor] wears, into the inventory, after asking the REMOVE restriction. Sends
   * nothing. Returns the refusal message, or null; an item not worn by the actor is an error.
   */
  fun removeWorn(actor: PluginActor, item: PluginItem): String?

  // Restriction queries. Each returns the message the matching command or verb would refuse with,
  // or null, and changes nothing and runs no hook. Arguments the command couldn't act on are
  // errors: an item the actor doesn't carry (or, for mayRemove, doesn't wear), a mayPut container
  // that isn't one, an unknown skill.

  /**
   * Asks whether [actor] may put on a carried [item] as [wear] would: slot choice, room, the WEAR
   * restriction and the REMOVE restriction on the one item it would take off.
   */
  fun mayWear(actor: PluginActor, item: PluginItem, wield: Boolean = false): String?

  /** Asks the REMOVE restriction about taking off a worn [item], as [removeWorn] would. */
  fun mayRemove(actor: PluginActor, item: PluginItem): String?

  /** Asks the DROP restriction about [actor] dropping a carried [item]. */
  fun mayDrop(actor: PluginActor, item: PluginItem): String?

  /**
   * Asks about [actor] giving a carried [item] to [to], as the give command would: [to] must be in
   * [actor]'s room and not be [actor], and the GIVE restriction. Not whether [to] can carry it.
   */
  fun mayGive(actor: PluginActor, item: PluginItem, to: PluginActor): String?

  /**
   * Asks about [actor] putting a carried [item] into [container], as the put command would: not the
   * container itself, which must be open, and the PUT restriction. Not whether it fits.
   */
  fun mayPut(actor: PluginActor, item: PluginItem, container: PluginItem): String?

  /**
   * Asks the ENTER restriction about [actor] going to [room] from where it is, with no direction.
   * [teleport] also runs `before_enter`, which a query can't, so it can still be vetoed.
   */
  fun mayEnter(actor: PluginActor, room: PluginRoom): String?

  /** Asks the LEARN restriction as [setSkill] would. Needs the `skills` feature. */
  fun mayLearn(actor: PluginActor, skill: String, proficiency: Int): String?

  /**
   * How many of [count] (by default the whole stack) of [item] would fit at [to], worked out as the
   * put, get and give commands do; changes nothing. A container's free capacity and max weight cap
   * it (whether it is open or locked isn't considered, [mayPut] answers that), an actor's
   * `carry_limit` asked about the item caps it, and a room takes all. An item [to] that isn't a
   * container, or a count outside 1..the stack's, is an error.
   */
  fun fits(item: PluginItem, to: PluginEntity, count: Int? = null): Int

  /**
   * The same for the item prototype [label], with no item spawned: [count] defaults to 1, and the
   * rules see the prototype's `item_info` and no item. A label no loaded item has is an error.
   */
  fun fits(label: String, to: PluginEntity, count: Int? = null): Int

  /** Writes an attribute in the plugin's own namespace; `null` deletes it. */
  fun setAttr(entity: PluginEntity, key: String, value: Any?)

  fun delAttr(entity: PluginEntity, key: String)

  // Choices (players only) and traits (players and mobs).
  // Each returns a refusal message, or null.
  fun setChoices(actor: PluginActor, changes: Map<String, String?>): String?

  fun grantTrait(actor: PluginActor, trait: String): String?

  fun removeTrait(actor: PluginActor, trait: String): String?

  // Skills (`skills` feature). setSkill runs the LEARN restrictions first.
  fun setSkill(actor: PluginActor, skill: String, proficiency: Int): String?

  fun removeSkill(actor: PluginActor, skill: String): Boolean

  /**
   * Reports a use of [skill] by [actor] to on_skill_use, with outcome "report", as
   * `ctx.report_skill_use` does. A skill only rules read advances this way. `target` may be the
   * actor itself. Needs the `skills` feature.
   */
  fun reportSkillUse(
    actor: PluginActor,
    skill: String,
    target: PluginActor? = null,
    item: PluginItem? = null,
  ): Unit = throw UnsupportedOperationException("reportSkillUse is not supported")

  /**
   * Rolls [actor]'s [skill] outside a cast, as `ctx.skill_roll` does: `skill_check` and its chain
   * with [item], [target] and [event] (a lowercase word), plus [adjust], then one draw unless the
   * chance is 100 or more; on success the power from `skill_power`. No cost and no on_skill_use;
   * the actor need not know the skill. Needs the `skills` feature.
   */
  fun skillRoll(
    actor: PluginActor,
    skill: String,
    item: PluginItem? = null,
    target: PluginActor? = null,
    event: String = "craft",
    adjust: Int = 0,
  ): PluginSkillRoll = throw UnsupportedOperationException("skillRoll is not supported")

  // Timed tasks, with the same rules as `ctx.start_task` and `ctx.stop_task`.

  /**
   * Starts a timed task named [name] (a gerund phrase: "You stop {name}.") of [pulses] on [actor].
   * [onDone] runs once it completes, then [lag] applies; [onStop], if given, hears why it ended
   * early. Returns null when started, else the refusal message for the actor.
   *
   * @throws IllegalArgumentException for pulses below 1, negative lag, a name that isn't a single
   *   line of 1 to 80 characters, or a damage percentage outside 1..100.
   */
  fun startTask(
    actor: PluginActor,
    pulses: Int,
    name: String,
    lag: Int = 0,
    interrupt: PluginTaskInterrupt = PluginTaskInterrupt(),
    onStop: ((PluginTaskStop) -> Unit)? = null,
    onDone: (PluginTaskCall) -> Unit,
  ): String? = throw UnsupportedOperationException("startTask is not supported")

  /** Cancels [actor]'s timed task, script or plugin, with no lag; false when it has none. */
  fun stopTask(actor: PluginActor): Boolean =
    throw UnsupportedOperationException("stopTask is not supported")

  /**
   * Sets [item]'s name override, as `ctx.set_item_name` does; null restores the prototype's name.
   * Returns whether it changed.
   *
   * @throws IllegalArgumentException for a name that isn't a single line of 1 to 80 characters.
   */
  fun setItemName(item: PluginItem, name: String?): Boolean =
    throw UnsupportedOperationException("setItemName is not supported")

  fun setBaseStat(actor: PluginActor, stat: String, value: Int): String? =
    throw UnsupportedOperationException("setBaseStat is not supported")

  fun setBaseMaxVital(actor: PluginActor, vital: String, value: Int): String? =
    throw UnsupportedOperationException("setBaseMaxVital is not supported")

  fun setBaseResist(actor: PluginActor, type: String, value: Int): String? =
    throw UnsupportedOperationException("setBaseResist is not supported")

  // Leveling (`leveling` feature).

  /**
   * Awards [amount] experience points to [actor], applying pending levels in auto mode or sending
   * the ready notice in deferred mode.
   *
   * @throws IllegalStateException naming the leveling feature when leveling is disabled.
   * @throws IllegalArgumentException when [amount] is less than 1.
   */
  fun awardXp(actor: PluginActor, amount: Long)

  /**
   * Removes up to [amount] experience points from [actor], never going below 0. Never changes the
   * actor's level and sends no message.
   *
   * @return the amount of experience actually removed.
   * @throws IllegalStateException naming the leveling feature when leveling is disabled.
   * @throws IllegalArgumentException when [amount] is less than 1.
   */
  fun takeXp(actor: PluginActor, amount: Long): Long

  /**
   * Applies one pending level to [actor], in either level mode. In deferred mode, sends the ready
   * notice again if more levels are still pending.
   *
   * @return true if a level was gained, or false if no level was pending.
   * @throws IllegalStateException naming the leveling feature when leveling is disabled.
   */
  fun levelUp(actor: PluginActor): Boolean

  /**
   * Lowers [actor] by up to [count] levels, stopping at level 1.
   *
   * @return the number of levels actually lost.
   * @throws IllegalStateException naming the leveling feature when leveling is disabled.
   * @throws IllegalArgumentException when [count] is less than 1.
   */
  fun levelDown(actor: PluginActor, count: Int = 1): Int

  // Doors and containers, as `ctx` sets them: no hook runs, no key is named and nothing is
  // printed. Locking fails on a door or container without a key.

  /** Opens the door in direction [dir] of [room], even if locked. */
  fun openDoor(room: PluginRoom, dir: String): Unit =
    throw UnsupportedOperationException("openDoor is not supported")

  /** Closes an open door; a locked door stays locked. */
  fun closeDoor(room: PluginRoom, dir: String): Unit =
    throw UnsupportedOperationException("closeDoor is not supported")

  /** Closes and locks the door in direction [dir] of [room]; only a door with a key has a lock. */
  fun lockDoor(room: PluginRoom, dir: String): Unit =
    throw UnsupportedOperationException("lockDoor is not supported")

  /** Unlocks the door, leaving it closed. */
  fun unlockDoor(room: PluginRoom, dir: String): Unit =
    throw UnsupportedOperationException("unlockDoor is not supported")

  /** Opens [item], a container, even if locked. */
  fun openContainer(item: PluginItem): Unit =
    throw UnsupportedOperationException("openContainer is not supported")

  /** Closes an open container; a locked one stays locked. */
  fun closeContainer(item: PluginItem): Unit =
    throw UnsupportedOperationException("closeContainer is not supported")

  /** Closes and locks the container; only a container whose prototype names a key has a lock. */
  fun lockContainer(item: PluginItem): Unit =
    throw UnsupportedOperationException("lockContainer is not supported")

  /** Unlocks the container, leaving it closed. */
  fun unlockContainer(item: PluginItem): Unit =
    throw UnsupportedOperationException("unlockContainer is not supported")

  // Messaging, as `ctx` sends it: say and emote speak as a named actor at once, and emit raises
  // a perceivable event.

  /** The [speaker] says [text] aloud; the room hears it and `on_say` fires, as `ctx.say`. */
  fun say(speaker: PluginActor, text: String): Unit =
    throw UnsupportedOperationException("say is not supported")

  /** The room, [actor] included, sees "<actor> [text]", as `ctx.emote`. */
  fun emote(actor: PluginActor, text: String): Unit =
    throw UnsupportedOperationException("emote is not supported")

  /** Raises a perceivable event in [room]; an intensity of 0 or less emits nothing. */
  fun emit(
    room: PluginRoom,
    channel: String,
    event: String,
    intensity: Int = 1,
    text: String? = null,
    actor: PluginActor? = null,
  ): Unit = throw UnsupportedOperationException("emit is not supported")

  // Simulation, as `ctx` sets it: reasons are ref-counted by name.

  /** Keeps [entity] simulated even outside the bubble, until the last [reason] is cleared. */
  fun setActive(entity: PluginEntity, reason: String): Unit =
    throw UnsupportedOperationException("setActive is not supported")

  /** Removes a [setActive] reason from [entity]. */
  fun clearActive(entity: PluginEntity, reason: String): Unit =
    throw UnsupportedOperationException("clearActive is not supported")

  /** Makes [entity] project a bubble of its own, until the last [reason] is cleared. */
  fun setAnchor(entity: PluginEntity, reason: String): Unit =
    throw UnsupportedOperationException("setAnchor is not supported")

  /** Removes a [setAnchor] reason from [entity]. */
  fun clearAnchor(entity: PluginEntity, reason: String): Unit =
    throw UnsupportedOperationException("clearAnchor is not supported")

  /** [holder] and [subject] as for [standing]; a null subject clears every record of the holder. */
  fun clearStanding(holder: Any, subject: Any? = null): Unit =
    throw UnsupportedOperationException("clearStanding is not supported")

  /** Rolls dice such as "2d6+1" with the world's RNG. */
  fun roll(dice: String): Int = throw UnsupportedOperationException("roll is not supported")

  // Mobs: spawning, actions, summons, owners and leashes.

  /**
   * Spawns [count] of the mob prototype [label] in [room] as `ctx.spawn` does for a mob label, and
   * returns them: no owner, no leash override and no spawn rule, a home room equal to [room], at
   * track index 0. A label no loaded mob has, or a [count] below 1, is an error.
   */
  fun spawnMob(label: String, room: PluginRoom, count: Int = 1): List<PluginActor> =
    throw UnsupportedOperationException("spawnMob is not supported")

  /** Queues a command line for a mob, exactly as if it typed it; false when the queue is full. */
  fun act(mob: PluginActor, commandLine: String): Boolean =
    throw UnsupportedOperationException("act is not supported")

  /** Queues a move through the exit in direction [dir]; false when the queue is full. */
  fun actMove(mob: PluginActor, dir: String): Boolean =
    throw UnsupportedOperationException("actMove is not supported")

  /**
   * Queues an attack against [target]; false when the queue is full. Needs the `combat` feature.
   */
  fun actAttack(mob: PluginActor, target: PluginActor): Boolean =
    throw UnsupportedOperationException("actAttack is not supported")

  /**
   * Queues casting the skill [skill] at [target], or the skill's default target. Needs `skills`.
   */
  fun actCast(mob: PluginActor, skill: String, target: PluginActor? = null): Boolean =
    throw UnsupportedOperationException("actCast is not supported")

  /** Queues one step in direction [dir]; false when the queue is full. */
  fun moveToward(mob: PluginActor, dir: String): Boolean =
    throw UnsupportedOperationException("moveToward is not supported")

  /** Queues one step toward [room]; false when there is no way. */
  fun moveToward(mob: PluginActor, room: PluginRoom): Boolean =
    throw UnsupportedOperationException("moveToward is not supported")

  /** Queues one step toward [target], advancing within the room when it is in it. */
  fun moveToward(mob: PluginActor, target: PluginActor): Boolean =
    throw UnsupportedOperationException("moveToward is not supported")

  /**
   * Spawns mobs beside [caster] as `ctx.summon` does; a `before_summon` veto returns an empty list.
   */
  fun summon(
    caster: PluginActor,
    label: String,
    count: Int = 1,
    duration: Int? = null,
  ): List<PluginActor> = throw UnsupportedOperationException("summon is not supported")

  /** Makes [player] the owner of [mob]; returns whether anything changed. */
  fun setOwner(mob: PluginActor, player: PluginActor): Boolean =
    throw UnsupportedOperationException("setOwner is not supported")

  /** Removes [mob]'s owner; returns whether it had one. */
  fun clearOwner(mob: PluginActor): Boolean =
    throw UnsupportedOperationException("clearOwner is not supported")

  /** [leash] at least 0, or null to unleash. */
  fun setLeash(mob: PluginActor, leash: Int?): Unit =
    throw UnsupportedOperationException("setLeash is not supported")

  /** Drops [mob]'s leash override so its prototype leash applies again. */
  fun resetLeash(mob: PluginActor): Unit =
    throw UnsupportedOperationException("resetLeash is not supported")

  // Combat and groups, as `ctx` engages and manages them.

  /**
   * Engages two in-world actors in the same room in combat, as `ctx.start_combat` does.
   *
   * @throws IllegalStateException naming the combat feature when combat is disabled.
   */
  fun startCombat(a: PluginActor, b: PluginActor): Unit =
    throw UnsupportedOperationException("startCombat is not supported")

  /**
   * Ends [a]'s fight with [b], or all of [a]'s fights when [b] is null, as `ctx.stop_combat` does.
   *
   * @throws IllegalStateException naming the combat feature when combat is disabled.
   */
  fun stopCombat(a: PluginActor, b: PluginActor? = null): Unit =
    throw UnsupportedOperationException("stopCombat is not supported")

  /**
   * Empties [victim]'s contribution record, or removes one contributor's entry, as
   * `ctx.clear_contributions` does.
   */
  fun clearContributions(victim: PluginActor, contributor: PluginActor? = null): Unit =
    throw UnsupportedOperationException("clearContributions is not supported")

  /** Adds [member] to [to]'s player group, creating one with [to] as leader when there is none. */
  fun groupAdd(member: PluginActor, to: PluginActor): Unit =
    throw UnsupportedOperationException("groupAdd is not supported")

  /** Removes [member] from its player group; a group left with one member is disbanded. */
  fun groupRemove(member: PluginActor): Unit =
    throw UnsupportedOperationException("groupRemove is not supported")

  /** Makes [member] the leader of its player group. */
  fun groupSetLeader(member: PluginActor): Unit =
    throw UnsupportedOperationException("groupSetLeader is not supported")

  /** Dissolves [member]'s whole player group at once. */
  fun groupDisband(member: PluginActor): Unit =
    throw UnsupportedOperationException("groupDisband is not supported")

  // Standing (`standing` feature).

  /**
   * Returns what [holder] thinks of [subject] (-100 to 100): the default sum of its parts or the
   * world's `disposition` rules export, then the disposition modifiers.
   */
  fun disposition(holder: PluginActor, subject: PluginActor): Int

  /**
   * Returns the amount of [holder]'s standing record about [subject], or 0 when there is none.
   * [holder] is a mob [PluginActor] or a faction label string, [subject] a [PluginActor] or a
   * faction label string. Declared faction opinions are not records.
   */
  fun standing(holder: Any, subject: Any): Int

  /** Returns the faction labels [actor] belongs to. */
  fun factionsOf(actor: PluginActor): List<String>

  /** Raises a custom standing event on [affected] by [actor]. */
  fun raiseStandingEvent(
    affected: PluginActor,
    actor: PluginActor,
    event: String,
    amount: Long = 0,
    item: PluginItem? = null,
  )

  /** Adjusts standing directly for [holder] and [subject] by [amount]. */
  fun adjustStanding(holder: Any, subject: Any, amount: Int)

  // Combat memory (`combat` feature).

  /**
   * Returns [mob]'s recorded memories, matching [subject] and/or [event] when specified, since
   * [since] pulse; a negative [since] counts as 0.
   */
  fun memories(
    mob: PluginActor,
    subject: PluginActor? = null,
    event: String? = null,
    since: Long = 0,
  ): List<PluginMemory>

  /** Records a memory entry for [mob]. */
  fun remember(
    mob: PluginActor,
    subject: PluginActor,
    event: String,
    skill: String? = null,
    amount: Long = 0,
    target: PluginActor? = null,
  )

  /**
   * Forgets memories matching [subject] and/or [event] on [mob]. Returns the count of removed
   * entries.
   */
  fun forget(mob: PluginActor, subject: PluginActor? = null, event: String? = null): Int

  /** The world's seeded RNG. Draws change the world's repro, so it stays off the read-only view. */
  val rng: PluginRng
}

/** A declared stat or vital: the name scripts use and the label players see. */
data class PluginNamed(val name: String, val label: String)

/** What a holder gained and lost with a subject over a span, from [PluginWorld.standingHistory]. */
data class PluginStandingTotals(val gained: Long, val lost: Long)

/** One action of [PluginGmcpWorld.plan]: its preconditions and effects over facts, and its cost. */
data class PluginPlanAction(
  val name: String,
  val pre: Map<String, Any> = emptyMap(),
  val effects: Map<String, Any> = emptyMap(),
  val cost: Int = 1,
)

/** A player group as a plugin sees it: the leader first, then the members in ascending id. */
data class PluginGroup(val id: Long, val leader: PluginActor, val members: List<PluginActor>)

/** What the world's `MODULE.gups` declares. */
data class PluginSchema(
  val stats: List<PluginNamed>,
  val vitals: List<PluginNamed>,
  val damageTypes: List<String>,
)

/** The world's seeded RNG. */
interface PluginRng {
  /** A uniform int in `min..max`, inclusive. */
  fun int(min: Int, max: Int): Int

  /** True with probability [pct] percent, as `ctx.rng.chance`. */
  fun chance(pct: Int): Boolean

  /** One element of [items], uniformly, as `ctx.rng.choice`; an empty list throws. */
  fun <T> choice(items: List<T>): T = throw UnsupportedOperationException("choice is not supported")
}

interface PluginEntity {
  val id: Long

  /** The room's label, or a mob's or item's prototype label; null for players. */
  val label: String?
  val name: String
}

interface PluginRoom : PluginEntity {
  val regions: List<String>
  val flags: Set<String>
  val tags: List<String>
  val size: String

  /** The room's description. */
  val desc: String
    get() = throw UnsupportedOperationException("desc is not supported")

  /** Names of the room's live affects, in id order. */
  val affects: List<String>
    get() = throw UnsupportedOperationException("affects is not supported")

  /** Direction → destination room label. */
  val exits: Map<String, String>
  val actors: List<PluginActor>
  val items: List<PluginItem>

  /** The exit in direction [dir], or null when there is none. */
  fun exit(dir: String): PluginExit? = throw UnsupportedOperationException("exit is not supported")
}

interface PluginActor : PluginEntity {
  val isPlayer: Boolean
  val role: PluginRole?
  val room: PluginRoom
  val level: Int

  /**
   * The number of levels the actor is currently eligible to advance.
   *
   * @throws IllegalStateException naming the leveling feature when leveling is disabled.
   */
  val pendingLevels: Int

  /** The effective value of a declared stat. An undeclared name throws, with a suggestion. */
  fun stat(name: String): Int

  /** The base value of a declared stat, without affects, equipment or encumbrance. */
  fun baseStat(name: String): Int

  /** The current value of a declared vital. */
  fun vital(name: String): Int

  /** The effective maximum of a declared vital. */
  fun maxVital(name: String): Int

  /** The base maximum of a declared vital. */
  fun baseMaxVital(name: String): Int

  /** The effective resistance percentage to a declared damage type. */
  fun resist(type: String): Int

  /** The base resistance percentage to a declared damage type. */
  fun baseResist(type: String): Int

  /** Affect names. */
  val affects: List<String>

  fun hasAffect(name: String): Boolean

  val flags: Set<String>

  /** Known choices only. */
  val choices: Map<String, String>

  /** Known traits only, sorted. */
  val traits: List<String>

  /** Granted traits as saved, sorted. */
  val grantedTraits: Set<String>

  fun hasTrait(name: String): Boolean

  /** Skill label to proficiency (needs the skills feature). */
  val skills: Map<String, Int>

  /** The items carried, by id; not those worn. */
  val inventory: List<PluginItem>

  /** The items worn by slot, in slot order, including inactive ones. */
  val equipment: Map<String, PluginItem>

  /** The running timed task, script or plugin, or null. */
  val task: PluginTask?
    get() = null

  /** True while the actor warms up a cast or runs a timed task. */
  val isBusy: Boolean
    get() = false

  /** The in-world player that owns this mob (a pet), or null. */
  val owner: PluginActor?
    get() = throw UnsupportedOperationException("owner is not supported")

  /** A mob's home room, or null for a player. */
  val homeRoom: PluginRoom?
    get() = throw UnsupportedOperationException("homeRoom is not supported")

  /** A mob's effective leash; null for no leash or for a player. */
  val leash: Int?
    get() = throw UnsupportedOperationException("leash is not supported")

  /** True while a leash override is in place; false for players and unoverridden mobs. */
  val leashOverridden: Boolean
    get() = throw UnsupportedOperationException("leashOverridden is not supported")

  /** Actors engaged with this one, by id; needs the `combat` feature. */
  val fighting: List<PluginActor>
    get() = throw UnsupportedOperationException("fighting is not supported")

  /** The actor this one is attacking, or null when it has no target or is not engaged. */
  val primaryTarget: PluginActor?
    get() = throw UnsupportedOperationException("primaryTarget is not supported")

  /** False once the actor has died (mobs) or quit (players); how a plugin checks staleness. */
  val inWorld: Boolean
    get() = throw UnsupportedOperationException("inWorld is not supported")

  /** Words that match this actor when targeting. */
  val keywords: List<String>
    get() = throw UnsupportedOperationException("keywords is not supported")

  /** Current experience points; needs the `leveling` feature. */
  val xp: Long
    get() = throw UnsupportedOperationException("xp is not supported")

  /** "ok", "incapacitated" or "dead". */
  val condition: String
    get() = throw UnsupportedOperationException("condition is not supported")

  /** Effective score per declared channel. */
  val senses: Map<String, Int>
    get() = throw UnsupportedOperationException("senses is not supported")

  /** The pulse [vital] last dropped below 0, or null. */
  fun belowZeroSince(vital: String): Long? =
    throw UnsupportedOperationException("belowZeroSince is not supported")

  /** Weight of everything carried and worn. */
  val carriedWeight: Int
    get() = throw UnsupportedOperationException("carriedWeight is not supported")

  /** The encumbrance level last worked out; 0 while it is unencumbered. */
  val encumbranceLevel: Int
    get() = throw UnsupportedOperationException("encumbranceLevel is not supported")

  /** True while the actor is in casting warmup; needs the `skills` feature. */
  val isCasting: Boolean
    get() = throw UnsupportedOperationException("isCasting is not supported")

  /** The skill being cast, or null; needs the `skills` feature. */
  val casting: PluginSkill?
    get() = throw UnsupportedOperationException("casting is not supported")

  /** True if a trait, an affect or an item affect gives [flag]. */
  fun hasFlag(flag: String): Boolean =
    throw UnsupportedOperationException("hasFlag is not supported")

  /** Whether the actor carries or wears an item of prototype [label]. */
  fun hasItem(label: String): Boolean =
    throw UnsupportedOperationException("hasItem is not supported")

  /** Equipment slots this actor currently has. */
  val slots: List<String>
    get() = throw UnsupportedOperationException("slots is not supported")

  /** Recovery pulses left. */
  val waitState: Int
    get() = throw UnsupportedOperationException("waitState is not supported")

  /** Hundredths of a pulse per room; 100 is one room per pulse. */
  val movePace: Int
    get() = throw UnsupportedOperationException("movePace is not supported")

  /** A player's recall room, or null for a mob. */
  val recallRoom: PluginRoom?
    get() = throw UnsupportedOperationException("recallRoom is not supported")
}

interface PluginItem : PluginEntity {
  val keywords: List<String>
  val kind: String
  val wear: String?

  /** How many slots of its family this item occupies when equipped, at least 1. */
  val slotCost: Int
  val weight: Int

  /** Base price of one item, from its prototype. */
  val value: Int

  /** Vital maximum adjustments while equipped, by vital name. */
  val maxVitalDeltas: Map<String, Int>

  /** Damage resistance percentage adjustments while equipped, by damage type. */
  val resistDeltas: Map<String, Int>

  /** True if equipped in a slot that does not currently fit its wearer. */
  val inactive: Boolean

  /** Effective tags, sorted: the prototype's plus those added, minus those removed. */
  val tags: List<String>

  /** True if [tag] is among the effective [tags]. */
  fun hasTag(tag: String): Boolean

  /** Variant names to item labels. */
  val variants: Map<String, String>

  /** Uses left, or null when the item has unlimited uses. */
  val charges: Int?

  /** The prototype's charges, or null when unlimited. */
  val maxCharges: Int?

  /**
   * This item's affects: its prototype's in declaration order, then its own instance affects by id;
   * empty for an item whose prototype was removed (a tombstone).
   */
  val affects: List<PluginItemAffect>

  /** "open", "closed" or "locked"; null for an item that isn't a container. */
  val containerState: String?
    get() = throw UnsupportedOperationException("containerState is not supported")

  /** Items inside this container, ascending id. */
  val contents: List<PluginItem>

  /** The actor whose custody this item ends in, following containers; null on the ground. */
  val custodian: PluginActor?

  /** Display name for stacks of 2 or more, such as `gold coins`, or null to show `name (count)`. */
  val plural: String?

  /** The stack's size, at least 1; 1 for an item that doesn't stack. */
  val count: Int

  /** Where this item is. */
  val location: PluginItemLocation

  /** False once the item has been destroyed or decayed; how a plugin checks staleness. */
  val inWorld: Boolean
    get() = throw UnsupportedOperationException("inWorld is not supported")

  /** Weapon damage dice such as "1d6", or null. */
  val dice: String?
    get() = throw UnsupportedOperationException("dice is not supported")

  /** Weapon damage type, or the world's unarmed type. */
  val damageType: String
    get() = throw UnsupportedOperationException("damageType is not supported")

  /** Stat changes while worn, by stat name. */
  val statDeltas: Map<String, Int>
    get() = throw UnsupportedOperationException("statDeltas is not supported")

  /** Sense score changes by channel while worn. */
  val senseDeltas: Map<String, Int>
    get() = throw UnsupportedOperationException("senseDeltas is not supported")
}

/** Where an item is, as [PluginItem.location] reports it. */
sealed interface PluginItemLocation {
  /** Lying on the ground in [room]. */
  data class InRoom(val room: PluginRoom) : PluginItemLocation

  /** Carried in [actor]'s inventory. */
  data class Carried(val actor: PluginActor) : PluginItemLocation

  /** Worn by [actor] in [slot]. */
  data class Equipped(val actor: PluginActor, val slot: String) : PluginItemLocation

  /** Inside the [container] item. */
  data class InContainer(val container: PluginItem) : PluginItemLocation
}

/** An affect on an item, as a plugin sees it: declared by its prototype or put on the instance. */
data class PluginItemAffect(
  val name: String,
  /** "worn" or "carried". */
  val applies: String,
  val flags: List<String>,
  val statDeltas: Map<String, Int>,
  val senseDeltas: Map<String, Int>,
  /** "proto" or "instance". */
  val origin: String,
  /** Absolute pulse, or null when permanent or from the prototype. */
  val expiresAt: Long?,
  /** Vital maximum adjustments while the affect reaches the holder, by vital name. */
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  /** Damage resistance adjustments while the affect reaches the holder, by damage type. */
  val resistDeltas: Map<String, Int> = emptyMap(),
)

/** An exit as a plugin sees it; read when the view is taken. */
data class PluginExit(
  val direction: String,
  val to: String,
  val tags: List<String>,
  val door: PluginDoor?,
  val key: String?,
  val passable: Boolean,
  val oneWay: Boolean,
)

/** A door's state as a plugin sees it: "open", "closed" or "locked". */
data class PluginDoor(val state: String, val key: String?, val tags: List<String>)

interface PluginSkill {
  val label: String
  val name: String
  val kind: String
  val tags: List<String>
}
