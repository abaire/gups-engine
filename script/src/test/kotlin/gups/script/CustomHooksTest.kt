package gups.script

import gups.core.AttrValue
import gups.core.Label
import gups.core.world.spawnItem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomHooksTest {
  private fun zone(content: String, vararg more: Pair<String, String>) =
    mapOf("zones/z/package.star" to zonePkg(), "zones/z/content.star" to content) + more

  private fun load(files: Map<String, String>, vararg pkgs: String = arrayOf("zones/z")) =
    ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(pkgs.toList())

  private fun only(r: LoadResult, pkg: String = "zones/z"): LoadError {
    val errs = r.errors.filter { it.pkg == pkg }
    assertTrue(errs.isNotEmpty(), "expected an error for $pkg, got: ${r.report()}")
    return errs.first()
  }

  @Test
  fun `a mob declares custom hooks`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _price(ctx, base):
              return base * 2

          room("start")
          mob("dealer", hooks = {"price": _price})
          """
        )
      )
    val hooks = f.result.customHooks(Label("//zones/z:dealer"))
    assertTrue("price" in hooks, "expected a custom hook named 'price', got ${hooks.keys}")
  }

  @Test
  fun `rooms and items declare custom hooks`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _greet(ctx):
              return "hi"

          room("start", hooks = {"greet": _greet})
          item("sign", hooks = {"greet": _greet})
          """
        )
      )
    assertTrue("greet" in f.result.customHooks(Label("//zones/z:start")))
    assertTrue("greet" in f.result.customHooks(Label("//zones/z:sign")))
  }

  @Test
  fun `custom hook names are validated`() {
    val r =
      load(
        zone(
          """
          def _f(ctx):
              return None
          room("start")
          mob("bad", hooks = {"Bad-Name": _f})
          """
        )
      )
    assertContains(only(r).message, "'bad-name' is not a valid hook name")
  }

  @Test
  fun `catalog names are refused`() {
    val r =
      load(
        zone(
          """
          def _f(ctx):
              return None
          room("start")
          item("thing", hooks = {"on_use": _f})
          """
        )
      )
    assertContains(only(r).message, "is a catalog hook")
    assertContains(only(r).message, "on_use=")
  }

  @Test
  fun `values must be top-level functions`() {
    val r =
      load(
        zone(
          """
          def _f(ctx):
              return None
          room("start")
          mob("dealer", hooks = {"price": 5})
          """
        )
      )
    assertContains(only(r).message, "expected a function")
  }

  @Test
  fun `missing hooks give an empty map`() {
    val f =
      ScriptFixture(
        zone(
          """
          room("start")
          mob("plain")
          """
        )
      )
    assertEquals(emptyMap(), f.result.customHooks(Label("//zones/z:plain")))
  }

  @Test
  fun `call_hook runs the entity's hook with keyword arguments and returns its result`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _price(ctx, base):
              return base * 2

          def _ask(ctx):
              total = ctx.call_hook(ctx.room, "price", base = 10)
              ctx.send(ctx.actor, "It costs %d." % total)

          room("start", commands={"ask": _ask}, hooks={"price": _price})
          """
        )
      )
    val p = f.player()
    f.input(p, "ask")
    f.run()
    assertContains(f.out(p), "It costs 20.")
  }

  @Test
  fun `views passed as arguments arrive as views`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _value_of(ctx, item):
              return item.value * 10

          def _ask(ctx):
              sign = ctx.room.items()[0]
              total = ctx.call_hook(ctx.room, "value_of", item = sign)
              ctx.send(ctx.actor, "Worth %d." % total)

          item("sign", value = 30)
          room("start", commands={"ask": _ask}, hooks={"value_of": _value_of})
          """
        )
      )
    f.w.spawnItem(
      Label("//zones/z:sign"),
      gups.core.model.ItemLocation.InRoom(f.w.room(Label("//zones/z:start")).id, 0),
    )
    val p = f.player()
    f.input(p, "ask")
    f.run()
    assertContains(f.out(p), "Worth 300.")
  }

  @Test
  fun `the hook runs in its own package`() {
    val files =
      zone(
        """
        def _ask(ctx):
            sign = ctx.room.items()[0]
            r = ctx.call_hook(sign, "score")
            ctx.send(ctx.actor, "Score %d." % r)

        room("start", commands={"ask": _ask})
        """
      ) +
        mapOf(
          "zones/s/package.star" to zonePkg(),
          "zones/s/content.star" to
            """
            def _score(ctx):
                ctx.set_attr(ctx.self, "score", 42)
                return ctx.get_attr(ctx.self, "score")

            item("sign", hooks = {"score": _score})
            """,
        )
    val f = ScriptFixture(files, load = listOf("zones/z", "zones/s"))
    val sign =
      f.w.spawnItem(
        Label("//zones/s:sign"),
        gups.core.model.ItemLocation.InRoom(f.w.room(Label("//zones/z:start")).id, 0),
      )
    val p = f.player()
    f.input(p, "ask")
    f.run()
    assertContains(f.out(p), "Score 42.")
    assertEquals(AttrValue.I(42L), sign.customAttributes["s:score"])
  }

  @Test
  fun `missing hooks, gone entities and failing hooks return None`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _boom(ctx):
              fail("explodes")

          def _ask(ctx):
              a = ctx.call_hook(ctx.room, "missing")
              b = ctx.call_hook(ctx.room, "boom")
              sign = ctx.room.items()[0]
              ctx.destroy(sign)
              c = ctx.call_hook(sign, "price")
              ctx.send(ctx.actor, "a=%s b=%s c=%s" % (a, b, c))

          item("sign")
          room("start", commands={"ask": _ask}, hooks={"boom": _boom})
          """
        )
      )
    f.w.spawnItem(
      Label("//zones/z:sign"),
      gups.core.model.ItemLocation.InRoom(f.w.room(Label("//zones/z:start")).id, 0),
    )
    val p = f.player()
    f.input(p, "ask")
    f.run()
    assertContains(f.out(p), "a=None b=None c=None")
    assertTrue(
      f.diagnostics().any { it.message.contains("hook boom") },
      "expected a diagnostic for the failing hook, got ${f.diagnostics()}",
    )
  }

  @Test
  fun `returning a view fails the hook`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _leaks(ctx):
              return ctx.room

          def _ask(ctx):
              r = ctx.call_hook(ctx.room, "leaks")
              ctx.send(ctx.actor, "r=%s" % r)

          room("start", commands={"ask": _ask}, hooks={"leaks": _leaks})
          """
        )
      )
    val p = f.player()
    f.input(p, "ask")
    f.run()
    assertContains(f.out(p), "r=None")
    assertTrue(
      f.diagnostics().any { it.message.contains("hook leaks return value") },
      "expected a diagnostic for the view return value, got ${f.diagnostics()}",
    )
  }

  @Test
  fun `the result is a mutable copy`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _plan(ctx):
              return {"price": 10}

          def _ask(ctx):
              r = ctx.call_hook(ctx.room, "plan")
              r["price"] = 20
              ctx.send(ctx.actor, "edited=%d" % r["price"])

          room("start", commands={"ask": _ask}, hooks={"plan": _plan})
          """
        )
      )
    val p = f.player()
    f.input(p, "ask")
    f.run()
    assertContains(f.out(p), "edited=20")
  }

  @Test
  fun `nesting deeper than 8 is an error`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _loop(ctx):
              return ctx.call_hook(ctx.room, "loop")

          def _ask(ctx):
              r = ctx.call_hook(ctx.room, "loop")
              ctx.send(ctx.actor, "r=%s" % r)

          room("start", commands={"ask": _ask}, hooks={"loop": _loop})
          """
        )
      )
    val p = f.player()
    f.input(p, "ask")
    f.run()
    assertContains(f.out(p), "r=None")
    assertTrue(
      f.diagnostics().any { it.message.contains("call_hook is nested too deeply") },
      "expected a nesting diagnostic, got ${f.diagnostics()}",
    )
  }

  @Test
  fun `nesting too deeply doesn't lower the limit for later calls`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _loop(ctx):
              return ctx.call_hook(ctx.room, "loop")

          def _down(ctx, n):
              if n == 0:
                  return "bottom"
              return ctx.call_hook(ctx.room, "down", n = n - 1)

          def _deep(ctx):
              ctx.call_hook(ctx.room, "loop")

          def _ask(ctx):
              r = ctx.call_hook(ctx.room, "down", n = 7)
              ctx.send(ctx.actor, "r=%s" % r)

          room("start", commands={"deep": _deep, "ask": _ask},
               hooks={"loop": _loop, "down": _down})
          """
        )
      )
    val p = f.player()
    f.input(p, "deep")
    f.run()
    f.input(p, "ask")
    f.run()
    assertContains(f.out(p), "r=bottom")
  }

  @Test
  fun `a bad hook name is an error even when the entity is gone`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _ask(ctx):
              sign = ctx.room.items()[0]
              ctx.destroy(sign)
              ctx.call_hook(sign, "Bad-Name")
              ctx.send(ctx.actor, "no error")

          item("sign")
          room("start", commands={"ask": _ask})
          """
        )
      )
    f.w.spawnItem(
      Label("//zones/z:sign"),
      gups.core.model.ItemLocation.InRoom(f.w.room(Label("//zones/z:start")).id, 0),
    )
    val p = f.player()
    f.input(p, "ask")
    f.run()
    assertTrue("no error" !in f.out(p), f.out(p))
    assertTrue(
      f.diagnostics().any { it.message.contains("'Bad-Name' is not a valid hook name") },
      "expected a hook name diagnostic, got ${f.diagnostics()}",
    )
  }

  @Test
  fun `results are not capped like GMCP payloads`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _big(ctx):
              return ["x" * 1000] * 20

          def _ask(ctx):
              r = ctx.call_hook(ctx.room, "big")
              ctx.send(ctx.actor, "len=%s" % (len(r) if r != None else None))

          room("start", commands={"ask": _ask}, hooks={"big": _big})
          """
        )
      )
    val p = f.player()
    f.input(p, "ask")
    f.run()
    assertContains(f.out(p), "len=20")
  }

  @Test
  fun `steps are shared with the caller`() {
    val f =
      ScriptFixture(
        zone(
          """
          def _busy(ctx):
              t = 0
              for i in range(100000000):
                  t += 1
              return t

          def _ask(ctx):
              r = ctx.call_hook(ctx.room, "busy")
              ctx.send(ctx.actor, "r=%s" % r)

          room("start", commands={"ask": _ask}, hooks={"busy": _busy})
          """
        )
      )
    val p = f.player()
    f.input(p, "ask")
    f.run()
    assertContains(f.out(p), "Nothing happens.")
    assertTrue(
      f.diagnostics("step_limit").isNotEmpty(),
      "expected a step_limit diagnostic, got ${f.diagnostics()}",
    )
  }
}
