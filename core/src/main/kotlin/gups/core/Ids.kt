package gups.core

/** Identity of a live entity (SPEC §3.3). Allocated monotonically by the world. */
@JvmInline
value class EntityId(val v: Long) : Comparable<EntityId> {
    override fun compareTo(other: EntityId): Int = v.compareTo(other.v)

    override fun toString(): String = "#$v"
}

/** Identity of a network session (SPEC §4.11). */
@JvmInline
value class SessionId(val v: Long) {
    override fun toString(): String = "session:$v"
}

/**
 * Bazel-style address of a declaration: `//zones/forest:clearing` (SPEC §1.6, §2.3).
 * Relative labels such as `:clearing` are resolved with [parse].
 */
@JvmInline
value class Label(val v: String) {
    init {
        require(PATTERN.matches(v)) { "Malformed label '$v'; expected //package/path:name" }
    }

    /** The package path, for example `zones/forest`. */
    val pkg: String get() = v.substring(2, v.indexOf(':'))

    /** The declaration name, for example `clearing`. */
    val name: String get() = v.substring(v.indexOf(':') + 1)

    override fun toString(): String = v

    companion object {
        private val PATTERN = Regex("//[a-z0-9_]+(?:/[a-z0-9_]+)*:[A-Za-z0-9_.\\-]+")

        /** Parses [text], resolving a relative `:name` against package [pkg]. */
        fun parse(text: String, pkg: String? = null): Label =
            if (text.startsWith(":")) {
                requireNotNull(pkg) { "Relative label '$text' needs a package" }
                Label("//$pkg$text")
            } else {
                Label(text)
            }
    }
}

/** A persistable reference to a top-level script function: `(file label, function name)` (SPEC §3.3). */
data class HookRef(val file: Label, val name: String)
