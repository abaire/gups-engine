package gups.script

import gups.core.model.didYouMean
import gups.core.world.Features
import gups.core.world.Messages
import kotlin.io.path.isRegularFile
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.Tuple

/**
 * The world's overrides of the engine message catalog, from `MESSAGES.gups` at the world root.
 * Optional, and read only at startup: changing it needs a restart.
 */
data class WorldMessages(val texts: Map<String, String> = emptyMap()) {
  /** Problems that should stop the server: unknown ids, unknown or missing placeholders. */
  fun problems(): List<String> = buildList {
    val ids = Messages.all.map { it.id }
    for ((id, text) in texts.toSortedMap()) {
      val msg = Messages.find(id)
      if (msg == null) {
        add("$FILE: unknown message '$id'" + didYouMean(id, ids))
        continue
      }
      for (problem in Messages.problems(msg, text)) {
        val hint =
          if (problem.startsWith("unknown placeholder"))
            didYouMean(problem.substringAfter('{').substringBefore('}'), msg.placeholders)
          else ""
        add("$FILE: '$id': $problem$hint")
      }
    }
  }

  /** Overrides of messages that [features] never sends. */
  fun warnings(features: Features): List<String> =
    texts.keys.sorted().mapNotNull { id ->
      val feature = Messages.find(id)?.feature ?: return@mapNotNull null
      if (feature in features) null
      else "$FILE: '$id' belongs to the '${feature.id}' feature, which is off; it is never sent"
    }

  companion object {
    const val FILE = "MESSAGES.gups"

    val SPEC =
      DeclSpec(
        name = "messages",
        doc = "Overrides engine messages in MESSAGES.gups, by message id.",
        params =
          listOf(
            DeclParam(
              "texts",
              "dict[str, str]",
              "Message text by id. {name} marks a placeholder and {{ a literal brace; see the message catalog in the reference.",
              positional = true,
              required = true,
            )
          ),
      )

    /** Reads `MESSAGES.gups`, which is optional. Errors are [LoadError]s. */
    fun read(dir: WorldDir): Pair<WorldMessages, LoadError?> {
      val path = dir.root.resolve(FILE)
      if (!path.isRegularFile()) return WorldMessages() to null
      val reader = Reader()
      execSettings(path, FILE, "messages", reader)?.let {
        return WorldMessages() to it
      }
      val m =
        reader.result
          ?: return WorldMessages() to
            LoadError("", "messages", "$FILE must call messages(...)", FILE)
      return m to null
    }
  }

  /** The `messages()` builtin: called exactly once. */
  private class Reader {
    var result: WorldMessages? = null

    @StarlarkMethod(
      name = "messages",
      doc = "Overrides engine messages.",
      extraPositionals = Param(name = "args"),
      extraKeywords = Param(name = "kwargs"),
    )
    fun messages(args: Tuple, kwargs: Dict<String, Any>): Any {
      if (result != null) fail("messages() is called more than once")
      return SPEC.bind(args, kwargs) { d ->
        result =
          WorldMessages(
            d.dict("texts").mapValues { (id, v) ->
              v as? String ?: fail("messages(): the text of '$id' must be a string")
            }
          )
        Starlark.NONE
      }
    }
  }
}
