package gups.script

import gups.core.Label
import gups.core.model.Direction
import gups.core.model.ItemProto
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.RoomProto
import net.starlark.java.syntax.Argument
import net.starlark.java.syntax.CallExpression
import net.starlark.java.syntax.DotExpression
import net.starlark.java.syntax.FileOptions
import net.starlark.java.syntax.Identifier
import net.starlark.java.syntax.NodeVisitor
import net.starlark.java.syntax.ParserInput
import net.starlark.java.syntax.StarlarkFile
import net.starlark.java.syntax.StringLiteral
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Static checks for course world packages (SPEC §7.3 gate 2, M6 design §8.2, D-166).
 */
class Linter(
    private val dir: WorldDir,
    private val basePrototypes: Prototypes? = null,
) {
    data class Finding(
        val severity: Severity,
        val message: String,
        val file: String? = null,
        val line: Int? = null,
        val label: Label? = null,
    ) {
        enum class Severity { ERROR, WARNING }

        fun renderText(): String {
            val loc = when {
                file != null && line != null -> "$file:$line: "
                file != null -> "$file: "
                label != null -> "${label.v}: "
                else -> ""
            }
            return "${severity.name.lowercase()}: $loc$message"
        }

        fun renderGithub(): String {
            val f = toRepoPath(file, label)
            val l = line?.let { "line=$it," } ?: ""
            val fullMsg = if (label != null && !message.contains(label.v)) "${label.v}: $message" else message
            return "::${severity.name.lowercase()} file=$f,${l}title=gups lint::${escapeGithub(fullMsg)}"
        }

        companion object {
            fun toRepoPath(file: String?, label: Label?): String {
                if (file != null) {
                    val raw = file.removePrefix("//")
                    return if (':' in raw) {
                        val pkg = raw.substringBefore(':')
                        val filename = raw.substringAfter(':')
                        if (pkg.isEmpty()) filename else "$pkg/$filename"
                    } else {
                        raw
                    }
                }
                if (label != null) {
                    val pkg = label.pkg
                    return if (pkg.isEmpty()) "package.star" else "$pkg/package.star"
                }
                return "package.star"
            }

            fun escapeGithub(s: String): String =
                s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        }
    }

    data class Report(val findings: List<Finding>) {
        val errors: List<Finding> get() = findings.filter { it.severity == Finding.Severity.ERROR }
        val warnings: List<Finding> get() = findings.filter { it.severity == Finding.Severity.WARNING }
        val ok: Boolean get() = errors.isEmpty()

        fun summary(): String = "${errors.size} error${if (errors.size == 1) "" else "s"}, ${warnings.size} warning${if (warnings.size == 1) "" else "s"}"

        fun renderMarkdown(): String = buildString {
            appendLine("## `gups lint` summary\n")
            if (ok && warnings.isEmpty()) {
                appendLine("All static checks passed (0 errors, 0 warnings).\n")
                return@buildString
            }
            appendLine("| Severity | Location | Message |")
            appendLine("|---|---|---|")
            for (f in findings) {
                val loc = f.file?.let { if (f.line != null) "`$it:${f.line}`" else "`$it`" }
                    ?: f.label?.let { "`${it.v}`" } ?: "—"
                val badge = if (f.severity == Finding.Severity.ERROR) "❌ Error" else "⚠️ Warning"
                appendLine("| $badge | $loc | ${f.message.replace("|", "\\|")} |")
            }
            appendLine("\n**Total:** ${summary()}.\n")
        }
    }

    /** Runs the static checks against packages matching [patterns]. */
    fun lint(patterns: List<String> = listOf("//...")): Report {
        val matchers = patterns.map { PatternMatcher(it) }
        fun matches(label: Label): Boolean = matchers.any { it.matches(label) }
        fun matchesPkg(pkg: String): Boolean = matchers.any { it.matchesPkg(pkg) }

        val findings = mutableListOf<Finding>()

        // 1. Load the packages to inspect prototypes and catch load errors.
        val loader = ScriptLoader(dir, ScriptLimits())
        val loadResult = loader.load(dir.packages())

        // Load errors (catalog hook errors D-074, namespace uniqueness D-064, etc.)
        for (err in loadResult.errors) {
            if (matchesPkg(err.pkg)) {
                findings += Finding(
                    Finding.Severity.ERROR,
                    err.message,
                    file = err.file,
                    line = err.line,
                )
            }
        }

        val protos = loadResult.prototypes()

        // 2. Dead-end rooms and reverse exits / doors
        for (room in protos.rooms.values) {
            if (!matches(room.label)) continue

            // Dead-end rooms (one exit or fewer) without dead_end_ok
            if (room.exits.size <= 1 && "dead_end_ok" !in room.flags) {
                findings += Finding(
                    Finding.Severity.ERROR,
                    "dead-end room (${room.exits.size} exit${if (room.exits.size == 1) "" else "s"}) without 'dead_end_ok' flag",
                    label = room.label,
                )
            }

            // Exits reverse check and doors match
            for (exit in room.exits) {
                if (exit.oneWay) continue
                val opp = exit.direction.opposite
                val targetRoom = protos.rooms[exit.to]
                if (targetRoom == null) continue // loader already reports missing room

                if (opp == null) {
                    findings += Finding(
                        Finding.Severity.ERROR,
                        "exit '${exit.direction}' to ${exit.to} has no opposite direction and is not marked one_way",
                        label = room.label,
                    )
                    continue
                }

                val reverse = targetRoom.exits.firstOrNull { it.direction == opp && it.to == room.label }
                if (reverse == null) {
                    findings += Finding(
                        Finding.Severity.ERROR,
                        "exit '${exit.direction}' to ${exit.to} has no reverse exit in ${exit.to} leading '$opp' back to ${room.label}",
                        label = room.label,
                    )
                } else if (exit.door != reverse.door) {
                    findings += Finding(
                        Finding.Severity.ERROR,
                        "doors do not match between ${room.label} exit '${exit.direction}' and ${exit.to} exit '$opp'",
                        label = room.label,
                    )
                }
            }
        }

        // 3. Schema changes since --base without a version bump (Q13)
        if (basePrototypes != null) {
            checkBaseDiffs(protos, basePrototypes, ::matches, findings)
        }

        // 4. Region consistency: applied to rooms but never declared, and the reverse
        val declaredRegions = protos.regions.keys
        val appliedRegions = LinkedHashMap<String, MutableSet<Label>>()
        for (room in protos.rooms.values) {
            for (r in room.regions) appliedRegions.computeIfAbsent(r) { LinkedHashSet() }.add(room.label)
        }

        for ((r, rooms) in appliedRegions) {
            if (r !in declaredRegions && rooms.any(::matches)) {
                findings += Finding(
                    Finding.Severity.WARNING,
                    "region '$r' applied to room(s) (${rooms.joinToString { it.v }}) is never declared",
                    label = rooms.first(),
                )
            }
        }
        for (r in declaredRegions) {
            val regProto = protos.regions[r]
            if (r !in appliedRegions && (regProto == null || matchesPkg(regProto.pkg))) {
                findings += Finding(
                    Finding.Severity.WARNING,
                    "region '$r' is declared in //${regProto?.pkg} but never applied to any room",
                )
            }
        }

        // 5. Attribute keys written but never read anywhere in the repository
        val (written, read) = scanAttributeKeys(dir, loadResult)
        for ((key, locs) in written) {
            if (key !in read) {
                val pkg = locs.first().pkg
                if (matchesPkg(pkg)) {
                    findings += Finding(
                        Finding.Severity.WARNING,
                        "attribute key '$key' is written but never read anywhere in the repository",
                        file = locs.first().file,
                        line = locs.first().line,
                    )
                }
            }
        }

        return Report(findings)
    }

    private fun RoomProto.withoutPresentation() = copy(name = "", desc = "", commands = emptyMap(), version = 0)
    private fun MobProto.withoutPresentation() = copy(name = "", desc = "", keywords = emptyList(), think = null, version = 0)
    private fun ItemProto.withoutPresentation() = copy(name = "", desc = "", keywords = emptyList(), commands = emptyMap(), version = 0)

    private fun checkBaseDiffs(
        curr: Prototypes,
        base: Prototypes,
        matches: (Label) -> Boolean,
        findings: MutableList<Finding>,
    ) {
        // Compare rooms
        for (room in curr.rooms.values) {
            if (!matches(room.label)) continue
            val old = base.rooms[room.label] ?: continue
            val changed = room.withoutPresentation() != old.withoutPresentation()
            if (changed && room.version <= old.version) {
                findings += Finding(
                    Finding.Severity.ERROR,
                    "room prototype declaration changed since base without a version bump (version is ${room.version}, base was ${old.version})",
                    label = room.label,
                )
            }
        }

        // Compare mobs
        for (mob in curr.mobs.values) {
            if (!matches(mob.label)) continue
            val old = base.mobs[mob.label] ?: continue
            val changed = mob.withoutPresentation() != old.withoutPresentation()
            if (changed && mob.version <= old.version) {
                findings += Finding(
                    Finding.Severity.ERROR,
                    "mob prototype declaration changed since base without a version bump (version is ${mob.version}, base was ${old.version})",
                    label = mob.label,
                )
            }
        }

        // Compare items
        for (item in curr.items.values) {
            if (!matches(item.label) || item.label == Prototypes.CORPSE.label) continue
            val old = base.items[item.label] ?: continue
            val changed = item.withoutPresentation() != old.withoutPresentation()
            if (changed && item.version <= old.version) {
                findings += Finding(
                    Finding.Severity.ERROR,
                    "item prototype declaration changed since base without a version bump (version is ${item.version}, base was ${old.version})",
                    label = item.label,
                )
            }
        }
    }

    private data class AttrLoc(val pkg: String, val file: String, val line: Int)

    private fun scanAttributeKeys(
        worldDir: WorldDir,
        loadResult: LoadResult,
    ): Pair<Map<String, List<AttrLoc>>, Set<String>> {
        val written = LinkedHashMap<String, MutableList<AttrLoc>>()
        val read = LinkedHashSet<String>()

        val starFiles = mutableListOf<Path>()
        Files.walk(worldDir.root).use { stream ->
            stream.filter { it.isRegularFile() && it.fileName.toString().endsWith(".star") }.forEach(starFiles::add)
        }

        for (path in starFiles) {
            val relPath = worldDir.root.relativize(path).toString().replace('\\', '/')
            var d: Path? = path.parent
            var foundPkg: String? = null
            while (d != null && d != worldDir.root.parent) {
                if (Files.exists(d.resolve("package.star"))) {
                    foundPkg = worldDir.root.relativize(d).toString().replace('\\', '/')
                    break
                }
                d = d.parent
            }
            val pkg = foundPkg ?: (worldDir.packages().filter { relPath.startsWith("$it/") }.maxByOrNull { it.length } ?: "")
            val loadedPkg = loadResult.packages[pkg]
            val namespace = loadedPkg?.namespace ?: (if (pkg.isNotEmpty()) pkg.substringAfterLast('/') else "")
            val content = runCatching { path.readText() }.getOrNull() ?: continue

            val input = ParserInput.fromString(content, relPath)
            val file = StarlarkFile.parse(input, FileOptions.DEFAULT)

            file.accept(object : NodeVisitor() {
                override fun visit(node: CallExpression) {
                    val fn = node.function
                    val name = when (fn) {
                        is DotExpression -> fn.field.name
                        is Identifier -> fn.name
                        else -> null
                    }
                    if (name == "set_attr" || name == "get_attr") {
                        val keyArg = findKeyArg(node.arguments)
                        if (keyArg is StringLiteral) {
                            val raw = keyArg.value
                            val key = when {
                                ':' in raw -> raw
                                namespace.isNotEmpty() -> "$namespace:$raw"
                                else -> raw
                            }
                            val line = node.startLocation.line()
                            if (name == "set_attr") {
                                written.computeIfAbsent(key) { mutableListOf() }.add(AttrLoc(pkg, relPath, line))
                            } else {
                                read += key
                            }
                        }
                    }
                    super.visit(node)
                }
            })
        }

        return written to read
    }

    private fun findKeyArg(args: List<Argument>): net.starlark.java.syntax.Expression? {
        val kw = args.firstOrNull { it.name == "key" }
        if (kw != null) return kw.value
        val positional = args.filter { it.name == null }
        return if (positional.size >= 2) positional[1].value else null
    }

    private class PatternMatcher(val text: String) {
        val base: String
        val recursive: Boolean
        val target: String?

        init {
            val raw = text.removePrefix("//")
            target = raw.substringAfter(':', "").ifEmpty { null }
            val path = raw.substringBefore(':')
            recursive = path == "..." || path.endsWith("/...")
            base = path.removeSuffix("...").removeSuffix("/")
        }

        fun matchesPkg(pkg: String): Boolean =
            if (recursive) base.isEmpty() || pkg == base || pkg.startsWith("$base/") else pkg == base

        fun matches(label: Label): Boolean =
            matchesPkg(label.pkg) && (target == null || target == label.name)
    }
}
