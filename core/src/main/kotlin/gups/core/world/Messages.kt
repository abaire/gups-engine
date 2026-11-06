package gups.core.world

/**
 * One player-facing engine message: a stable dotted [id], the [placeholders] its text may use, the
 * [required] ones an override must keep, and the [default] text. [feature] is the feature that
 * sends it, or null for the kernel.
 */
class Msg(
  val id: String,
  val feature: Feature?,
  val placeholders: List<String>,
  val required: Set<String>,
  val default: String,
) {
  override fun toString(): String = id
}

/**
 * A set of messages sent by one feature (or the kernel), declared next to the code that sends them.
 */
abstract class MsgGroup(private val feature: Feature?) {
  internal val entries = mutableListOf<Msg>()

  /** [placeholders] and [required] are comma-separated names. */
  protected fun msg(
    id: String,
    default: String,
    placeholders: String = "",
    required: String = "",
  ): Msg =
    Msg(id, feature, names(placeholders), names(required).toSet(), default).also { entries += it }

  private fun names(list: String) = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** The message catalog: every entry of the kernel and the features. */
object Messages {
  val all: List<Msg> by lazy {
    listOf(KernelMsg, LoginMsg, CombatMsg, TrackMsg, SkillMsg, LevelingMsg)
      .flatMap { it.entries }
      .also { entries ->
        val dup = entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
        check(dup.isEmpty()) { "duplicate message ids: $dup" }
      }
  }

  private val byId: Map<String, Msg> by lazy { all.associateBy { it.id } }

  fun find(id: String): Msg? = byId[id]

  private val FIELD = Regex("\\{\\{|\\{([a-z][a-z0-9_]*)}")

  /** Color markup tags, which are not placeholders. */
  private val COLOR =
    Regex("(bold|underline|(bright_)?(black|red|green|yellow|blue|magenta|cyan|white)|x\\d{1,3})")

  /** Name placeholders that callers fill with plain names; see [fill]. */
  private val NAMES = setOf("target", "item", "container", "name", "pet")

  /**
   * [text] with each `{name}` in [values] filled in; other braces, `{{` included, stay. A name
   * placeholder that opens the text starts a sentence, so its value is capitalized there and only
   * there; `{actor}` arrives capitalized already.
   */
  fun fill(text: String, values: Map<String, String>): String =
    FIELD.replace(text) { m ->
      if (m.value == "{{") return@replace m.value
      val name = m.groupValues[1]
      val value = values[name] ?: return@replace m.value
      if (m.range.first == 0 && name in NAMES) value.cap() else value
    }

  /** The `{name}` fields of [text] that aren't color tags. */
  fun fieldsOf(text: String): List<String> =
    FIELD.findAll(text)
      .mapNotNull { it.groups[1]?.value }
      .filterNot { COLOR.matches(it) }
      .distinct()
      .toList()

  /** Why [text] can't replace the text of [msg]; empty if it can. */
  fun problems(msg: Msg, text: String): List<String> = buildList {
    val used = fieldsOf(text)
    for (f in used.filter { it !in msg.placeholders }) add("unknown placeholder {$f}")
    for (f in msg.required.filter { it !in used }) add("missing required placeholder {$f}")
  }
}

/** The text of [m] in this world, with [args] filled in. */
fun World.msg(m: Msg, vararg args: Pair<String, Any?>): String {
  for ((name, _) in args) require(name in m.placeholders) { "${m.id} has no placeholder $name" }
  val text = config.messages[m.id] ?: m.default
  return Messages.fill(text, args.associate { (k, v) -> k to v.toString() })
}

/** Sends the message [m] to [actor]. */
fun World.tell(actor: gups.core.model.Actor, m: Msg, vararg args: Pair<String, Any?>) =
  send(actor, msg(m, *args))
