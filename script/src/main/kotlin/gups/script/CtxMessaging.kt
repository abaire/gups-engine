package gups.script

import gups.core.model.Channel
import gups.core.model.Entity
import gups.core.model.Player
import gups.core.model.TAG_REGEX
import gups.core.world.GMCP_PACKAGE_NAME
import gups.core.world.HookContext
import gups.core.world.callHook
import gups.core.world.echo
import gups.core.world.emote
import gups.core.world.gmcp
import gups.core.world.gmcpWithin
import gups.core.world.perceive
import gups.core.world.roomOfEntity
import gups.core.world.say
import gups.core.world.send
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread

/** Messaging, events, custom hooks and the veto. */
abstract class CtxMessaging
internal constructor(frame: Frame, owner: Entity, hc: HookContext, extras: CtxExtras) :
  CtxFields(frame, owner, hc, extras) {
  @StarlarkMethod(
    name = "say",
    doc = "speaker says text aloud; fires on_say.",
    parameters =
      [
        Param(name = "speaker", named = true, doc = "Actor who speaks."),
        Param(name = "text", named = true, doc = "Text spoken."),
      ],
  )
  fun say(speaker: Any, text: Any): Any =
    verb("say") {
      world.say(actorArg(speaker, "say(speaker)"), text(text, "say(text)"))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "emote",
    doc = "The room sees '<actor> text'.",
    parameters =
      [
        Param(name = "actor", named = true, doc = "Actor performing the emote."),
        Param(name = "text", named = true, doc = "Text that follows the actor's name."),
      ],
  )
  fun emote(actor: Any, text: Any): Any =
    verb("emote") {
      world.emote(actorArg(actor, "emote(actor)"), text(text, "emote(text)"))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "send",
    doc = "Sends text to one actor.",
    parameters =
      [
        Param(name = "target", named = true, doc = "Actor who receives the text."),
        Param(name = "text", named = true, doc = "Text to send."),
      ],
  )
  fun send(target: Any, text: Any): Any =
    verb("send") {
      world.send(actorArg(target, "send(target)"), text(text, "send(text)"))
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "gmcp",
    doc = "Sends a GMCP event to a player's client. Returns whether it was sent.",
    parameters =
      [
        Param(
          name = "player",
          named = true,
          doc = "Actor who receives the event; mobs receive nothing.",
        ),
        Param(
          name = "package",
          named = true,
          doc = "A GMCP package this package declared, or one nested in it.",
        ),
        Param(
          name = "data",
          named = true,
          doc = "None, int, str, bool, list or dict (16 KB of JSON at most).",
        ),
      ],
    useStarlarkThread = true,
  )
  fun gmcp(player: Any, pkg: Any, data: Any, thread: StarlarkThread): Any =
    verb("gmcp", "$pkg") {
      val a = actorArg(player, "gmcp(player)")
      val pkgName = str(pkg, "gmcp(package)")
      require(GMCP_PACKAGE_NAME.matches(pkgName)) { "'$pkgName' is not a GMCP package name" }
      val caller = pkgOf(thread)
      val declared = frame.host.scripts.packages[caller]?.gmcpPackages.orEmpty()
      if (declared.none { gmcpWithin(pkgName, it.name) }) {
        val declarer =
          frame.host.scripts.packages.values.firstOrNull {
            it.gmcpPackages.any { d -> gmcpWithin(pkgName, d.name) }
          }
        fail(
          "gmcp(package): code in //$caller didn't declare a GMCP package for '$pkgName'" +
            (declarer?.let { " (use the helpers exported by //${it.path})" } ?: "")
        )
      }
      val payload = toGmcp(data, "gmcp(data)", frame.host.limits)
      if (a !is Player) false else world.gmcp(a, pkgName, payload)
    }

  @StarlarkMethod(
    name = "call_hook",
    doc =
      "Runs entity's custom hook name as fn(ctx, **args) with ctx.self the entity, then the plugin handlers for name, and returns their result as a fresh copy.",
    parameters =
      [
        Param(name = "entity", named = true, doc = "Actor, item or room whose hook runs."),
        Param(name = "name", named = true, doc = "Custom hook name."),
      ],
    extraKeywords =
      Param(
        name = "args",
        doc =
          "Keyword arguments for the hook: int, str, bool, list, dict or actor, item and room views.",
      ),
    useStarlarkThread = true,
  )
  fun callHook(entity: Any, name: Any, args: Dict<String, Any>, thread: StarlarkThread): Any =
    verb("call_hook", "$name") {
      val hookName = str(name, "call_hook(name)")
      if (!TAG_REGEX.matches(hookName))
        fail("call_hook(name): '$hookName' is not a valid hook name")
      val plain =
        args.entries.associate { (k, x) ->
          k to toPlain(x, "call_hook($k)", frame.host.limits, views = true)
        }
      val e = hookOwner(entity, "call_hook(entity)") ?: return@verb Starlark.NONE
      frame.host.beginCustomHook()
      try {
        fromPlain(world.callHook(e, hookName, plain), thread.mutability(), frame)
      } finally {
        frame.host.endCustomHook()
      }
    }

  @StarlarkMethod(
    name = "echo",
    doc = "Sends text to everyone in a room, except the actors in exclude.",
    parameters =
      [
        Param(name = "room", named = true, doc = "Room, or room label, to send the text to."),
        Param(name = "text", named = true, doc = "Text to send."),
        Param(
          name = "exclude",
          named = true,
          defaultValue = "[]",
          doc = "Actors who don't receive the text.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun echo(room: Any, text: Any, exclude: Any, thread: StarlarkThread): Any =
    verb("echo") {
      val skip = list(exclude, "echo(exclude)").map { actorArg(it, "echo(exclude)") }
      world.echo(
        roomArg(room, "echo(room)", thread),
        text(text, "echo(text)"),
        *skip.toTypedArray(),
      )
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "emit",
    doc =
      "Raises a perceivable event on a channel. It spreads through nearby rooms from room, weakening on the way; actors who can perceive it hear or see it through on_perceive and rules.perceive_message.",
    parameters =
      [
        Param(name = "channel", named = true, doc = "A built-in or declared channel name."),
        Param(name = "event", named = true, doc = "Event name that on_perceive hooks see."),
        Param(
          name = "intensity",
          named = true,
          defaultValue = "1",
          doc = "Strength at the origin; 0 or less emits nothing.",
        ),
        Param(
          name = "text",
          named = true,
          defaultValue = "None",
          doc = "Text that goes with the event.",
        ),
        Param(
          name = "room",
          named = true,
          defaultValue = "None",
          doc = "Room, or room label, where it happens; defaults to ctx.room.",
        ),
        Param(
          name = "actor",
          named = true,
          defaultValue = "None",
          doc = "Actor who caused it; defaults to ctx.actor. The causer does not perceive it.",
        ),
      ],
    useStarlarkThread = true,
  )
  fun emit(
    channel: Any,
    event: Any,
    intensity: Any,
    text: Any,
    room: Any,
    actor: Any,
    thread: StarlarkThread,
  ): Any =
    verb("emit") {
      val known = world.schema.allChannels.map { it.name }
      val ch = Channel(choice(str(channel, "emit(channel)"), "emit(channel)", known))
      val name = text(event, "emit(event)")
      if (name.isBlank()) fail("emit(event): must not be empty")
      val strength = int(intensity, "emit(intensity)")
      val body = if (isNone(text)) null else text(text, "emit(text)")
      val at =
        if (isNone(room)) hc.room ?: world.roomOfEntity(owner)
        else roomArg(room, "emit(room)", thread)
      val cause = if (isNone(actor)) hc.actor else actorArg(actor, "emit(actor)")
      if (strength > 0 && at != null) world.perceive(name, ch, at, cause, body, strength)
      Starlark.NONE
    }

  @StarlarkMethod(
    name = "deny",
    doc = "Vetoes the action; return it from a before_* hook.",
    parameters =
      [Param(name = "msg", named = true, doc = "Text shown to the actor whose action is refused.")],
  )
  fun deny(msg: Any): Any =
    verb("deny") {
      if (!frame.site.vetoable)
        fail(
          "deny() is only valid as the return value of a before_* hook (this is ${frame.site.hook})"
        )
      Denial(text(msg, "deny(msg)"))
    }
}
