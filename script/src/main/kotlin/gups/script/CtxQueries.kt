package gups.script

import gups.core.ai.FactValue
import gups.core.ai.GoapAction
import gups.core.ai.GoapPlanner
import gups.core.ai.PlanResult
import gups.core.model.Actor
import gups.core.model.Band
import gups.core.model.Dice
import gups.core.model.Entity
import gups.core.model.Room
import gups.core.world.Feature
import gups.core.world.HookContext
import gups.core.world.findActor
import gups.core.world.groupOf
import gups.core.world.isSimulated
import gups.core.world.opponentsOf
import gups.core.world.pathTo
import gups.core.world.roomOfEntity
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkThread

/** World queries, dice, features and planning. */
abstract class CtxQueries
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxSimulation(frame, owner, hc, extras) {
  internal fun ownerRoom(): Room =
    world.roomOfEntity(owner) ?: fail("${owner.name} is not in a room")

  @StarlarkMethod(
    name = "here",
    doc = "The actor or ground item in the owner's room matching a keyword, or None.",
    parameters =
      [Param(name = "keyword", named = true, doc = "Keyword to match, as a player would type it.")],
  )
  fun here(keyword: Any): Any =
    verb("here") {
      val word = str(keyword, "here(keyword)")
      val room = ownerRoom()
      val a =
        (owner as? Actor)?.let { world.findActor(it, word) }
          ?: room.actors.firstOrNull { it !== owner && it.matches(word) }
      v(a ?: room.items.firstOrNull { it.matches(word) })
    }

  @StarlarkMethod(
    name = "find",
    doc =
      "Entities of a prototype, ascending id. scope is 'room' (the owner's room), 'zone' (its package) or 'world'.",
    parameters =
      [
        Param(name = "label", named = true, doc = "Prototype label to look for."),
        Param(
          name = "scope",
          named = true,
          defaultValue = "'room'",
          doc = "Where to look: 'room', 'zone' or 'world'.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun find(label: Any, scope: Any, thread: StarlarkThread): Any =
    verb("find", "$label") {
      val l = label(str(label, "find(label)"), pkgOf(thread), "find(label)")
      val where = choice(scope, "find(scope)", listOf("room", "zone", "world"))
      val room = if (where == "world") null else ownerRoom()
      val found =
        world.allEntities
          .filter { it.protoLabel == l }
          .filter { e ->
            when (where) {
              "room" -> world.roomOfEntity(e) === room
              "zone" -> world.roomOfEntity(e)?.label?.pkg == room!!.label.pkg
              else -> true
            }
          }
          .sortedBy { it.id }
      sList(found.map { v(it) })
    }

  internal fun near(
    actor: Any,
    band: Any,
    what: String,
    pick: (Actor, List<Actor>) -> List<Actor>,
  ): Any =
    verb(what) {
      val a = actorArg(actor, "$what(actor)")
      val reach = Band.parse(choice(band, "$what(band)", Band.entries.map { it.word }))
      val within =
        a.room.actors.filter {
          it !== a &&
            world.isSimulated(it) &&
            reach.reaches(Band.between(a.trackIndex, it.trackIndex))
        }
      sList(pick(a, within).map { v(it) })
    }

  @StarlarkMethod(
    name = "enemies_near",
    doc = "Opponents of actor within a band ('melee', 'near', 'far').",
    parameters =
      [
        Param(name = "actor", named = true, doc = "The actor whose opponents are wanted."),
        Param(name = "band", named = true, doc = "Maximum distance: 'melee', 'near' or 'far'."),
      ],
  )
  fun enemiesNear(actor: Any, band: Any): Any =
    near(actor, band, "enemies_near") { a, within ->
      val foes = world.opponentsOf(a).toSet()
      within.filter { it in foes }
    }

  @StarlarkMethod(
    name = "allies_near",
    doc =
      "Actors of the same side (players with players, mobs with mobs) within a band who are not fighting actor, and members of actor's group (and their pets) within the band, simulated and not fighting actor.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "The actor whose allies are wanted."),
        Param(name = "band", named = true, doc = "Maximum distance: 'melee', 'near' or 'far'."),
      ],
  )
  fun alliesNear(actor: Any, band: Any): Any =
    near(actor, band, "allies_near") { a, within ->
      val ga = world.groupOf(a)
      within.filter { x ->
        x.id !in a.fighting && (x.isPlayer == a.isPlayer || (ga != null && world.groupOf(x) === ga))
      }
    }

  @StarlarkMethod(
    name = "path_to",
    doc =
      "Directions from the owner's room to a room or actor through open exits; [] if already there, None if unreachable.",
    parameters =
      [Param(name = "target", named = true, doc = "Room, room label, or actor to reach.")],
    useStarlarkThread = true,
  )
  fun pathTo(target: Any, thread: StarlarkThread): Any =
    verb("path_to") {
      val dest =
        when (target) {
          is ActorView -> actorArg(target, "path_to(target)").room
          is ItemView ->
            world.roomOfEntity(itemArg(target, "path_to(target)"))
              ?: fail("path_to(): the item is nowhere")
          else -> roomArg(target, "path_to(target)", thread)
        }
      world.pathTo(ownerRoom(), dest)?.let { p -> sList(p.map { it.name }) } ?: Starlark.NONE
    }

  @StarlarkMethod(
    name = "roll",
    doc = "Rolls dice such as '2d6+1'.",
    parameters = [Param(name = "dice", named = true, doc = "Dice expression such as '2d6+1'.")],
  )
  fun roll(dice: Any): Any =
    verb("roll", "$dice") {
      val d =
        try {
          Dice.parse(str(dice, "roll(dice)"))
        } catch (e: IllegalArgumentException) {
          fail("roll(): ${e.message}")
        }
      sInt(d.roll(world.rng))
    }

  @StarlarkMethod(
    name = "has_feature",
    doc =
      "Whether the world has the named feature: an engine feature that is on, or one a loaded plugin provides.",
    parameters = [Param(name = "name", named = true, doc = "Feature id, such as 'combat'.")],
  )
  fun hasFeature(name: Any): Any =
    verb("has_feature", "$name") {
      val id = str(name, "has_feature(name)")
      if (!FEATURE_ID.matches(id)) fail("has_feature(): '$id' is not a feature name")
      Feature.byId(id)?.let { it in world.features } ?: (id in world.pluginFeatures)
    }

  @StarlarkMethod(
    name = "plan",
    doc =
      "Goal-oriented planning: the cheapest list of action names that reaches goal from facts, [] if it already holds, None if unreachable or out of bounds.",
    parameters =
      [
        Param(name = "facts", named = true, doc = "Dict of fact name to bool, int or str."),
        Param(
          name = "actions",
          named = true,
          doc = "List of dicts with name, pre, effects and cost.",
        ),
        Param(name = "goal", named = true, doc = "Dict of fact name to the value it must have."),
        Param(
          name = "max_expansions",
          named = true,
          defaultValue = "1024",
          doc = "Search nodes to expand at most, 1 to 16384.",
        ),
      ],
  )
  fun plan(facts: Any, actions: Any, goal: Any, maxExpansions: Any): Any =
    verb("plan") {
      val maxExp = int(maxExpansions, "ctx.plan(max_expansions)")
      val compiledActions =
        list(actions, "ctx.plan(actions)").map { item ->
          val action = dict(item, "ctx.plan(actions) item")
          val name =
            str(
              action["name"] ?: fail("ctx.plan(actions): every action needs a 'name'"),
              "ctx.plan(actions) name",
            )
          action.keys
            .firstOrNull { it !in ACTION_KEYS }
            ?.let { fail("ctx.plan(actions): unknown key '$it' in action '$name'") }
          val cost = action["cost"]
          GoapAction(
            name = name,
            cost = if (isNone(cost)) 1 else int(cost, "ctx.plan(actions) cost of '$name'"),
            pre =
              action["pre"].let { if (isNone(it)) emptyMap() else factMap(it, "pre of '$name'") },
            effects =
              action["effects"].let {
                if (isNone(it)) emptyMap() else factMap(it, "effects of '$name'")
              },
          )
        }
      val res =
        GoapPlanner.plan(
          facts = factMap(facts, "facts"),
          actions = compiledActions,
          goal = factMap(goal, "goal"),
          maxExpansions = maxExp,
        )
      when (res) {
        is PlanResult.Found -> sList(res.steps)
        is PlanResult.NotFound -> Starlark.NONE
      }
    }

  internal fun factMap(v: Any?, what: String): Map<String, FactValue> =
    dict(v, "ctx.plan($what)").mapValues { (k, value) ->
      when (value) {
        is Boolean -> FactValue.Bool(value)
        is StarlarkInt -> FactValue.Num(value.toLong("ctx.plan($what) fact '$k'"))
        is String -> FactValue.Text(value)
        else ->
          fail("ctx.plan($what): fact '$k' must be a bool, int or str, got ${typeName(value)}")
      }
    }

  companion object {
    private val ACTION_KEYS = setOf("name", "pre", "effects", "cost")
    private val FEATURE_ID = Regex("[a-z][a-z0-9_]*")
  }
}
