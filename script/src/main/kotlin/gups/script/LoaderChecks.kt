package gups.script

import gups.core.Label
import gups.core.model.Direction
import gups.core.model.Prototypes
import gups.core.model.didYouMean
import gups.core.world.gmcpOverlaps
import kotlin.io.path.readText
import net.starlark.java.syntax.CallExpression
import net.starlark.java.syntax.Identifier
import net.starlark.java.syntax.NodeVisitor
import net.starlark.java.syntax.ParserInput
import net.starlark.java.syntax.StarlarkFile
import net.starlark.java.syntax.StringLiteral

/**
 * The world-wide checks that run once every package of a load has evaluated: label and faction
 * resolution, attribute namespace, region, GMCP and global command uniqueness.
 */
internal class LoadChecks(
  private val dir: WorldDir,
  private val states: Map<String, PkgState>,
  private val pluginGlobals: Map<String, Any>,
  private val knownGmcp: () -> Set<String>,
  private val engineVerbs: Set<String>,
  private val fixtures: Boolean,
) {
  private val live: List<PkgState>
    get() = livePackages(states)

  /** Every label resolves to a declaration of the right kind. */
  fun resolveLabels() {
    val index = declarationIndex()
    for (st in live) {
      for (ref in st.pkg.refs) {
        val found = index[ref.label]
        if (found == ref.kind) continue
        st.errors +=
          LoadError(
            st.pkg.path,
            "label",
            unresolvableLabel(ref, found),
            ref.file,
            ref.line,
            ref.column,
            snippet(dir, ref.file, ref.line, ref.column),
          )
      }
    }
  }

  private fun declarationIndex(): Map<Label, RefKind> {
    val index = HashMap<Label, RefKind>()
    for (st in live) {
      st.pkg.rooms.forEach { index[it.label] = RefKind.ROOM }
      st.pkg.mobs.forEach { index[it.label] = RefKind.MOB }
      st.pkg.items.forEach { index[it.label] = RefKind.ITEM }
      st.pkg.skills.forEach { index[it.label] = RefKind.SKILL }
    }
    index[Prototypes.CORPSE.label] = RefKind.ITEM
    return index
  }

  /** Why [ref] does not name a declaration of [ref]'s kind. */
  private fun unresolvableLabel(ref: LabelRef, found: RefKind?): String =
    when {
      found != null -> "${ref.label} is a ${found.word}, not a ${ref.kind.word}"
      !dir.hasPackage(ref.label.pkg) ->
        "${ref.label} doesn't resolve: there is no package //${ref.label.pkg}"
      else ->
        "${ref.label} doesn't resolve: //${ref.label.pkg} declares no ${ref.kind.word} named '${ref.label.name}'" +
          fixtureNote(ref.label)
    }

  /** Every faction reference in mob(factions=) or faction(standing=) resolves. */
  fun checkFactions() {
    val declaredFactions = live.flatMap { it.pkg.factions.map { f -> f.label } }.toSet()
    val mobFactions = live.flatMap { it.pkg.mobs.map { m -> m.label } }.toSet()
    val knownFactionLabels = declaredFactions + mobFactions
    val knownStrings = knownFactionLabels.map { it.v }
    for (st in live) {
      for (ref in st.pkg.factionRefs) {
        if (ref.label in knownFactionLabels) continue
        val why =
          "${ref.label} doesn't resolve: it is neither a declared faction nor a mob prototype${didYouMean(ref.label.v, knownStrings)}"
        st.errors +=
          LoadError(
            st.pkg.path,
            "label",
            why,
            ref.file,
            ref.line,
            ref.column,
            snippet(dir, ref.file, ref.line, ref.column),
          )
      }
    }
  }

  private val fixtureDecls = HashMap<String, Map<String, Label>>()

  /**
   * When fixtures are left out, says which fixture file of the label's package declares the name,
   * found by scanning those files for declaration calls without evaluating them.
   */
  fun fixtureNote(label: Label): String {
    if (fixtures) return ""
    val decls =
      fixtureDecls.getOrPut(label.pkg) {
        dir
          .files(label.pkg, tests = false, fixtures = true)
          .filter { WorldDir.isFixture(it.name) }
          .flatMap { file -> declaredNames(file).map { it to file } }
          .toMap()
      }
    val file = decls[label.name] ?: return ""
    return " (it is declared in $file, a test fixture file, which loads only for gups test and gups scenario)"
  }

  /** The names [file]'s `room`, `mob`, `item` and `skill` calls declare, wherever they are. */
  private fun declaredNames(file: Label): List<String> {
    val text = runCatching { dir.fileOf(file).readText() }.getOrNull() ?: return emptyList()
    val parsed = StarlarkFile.parse(ParserInput.fromString(text, file.v), FILE_OPTIONS)
    val kinds = RefKind.entries.mapTo(HashSet()) { it.word }
    val names = mutableListOf<String>()
    parsed.accept(
      object : NodeVisitor() {
        override fun visit(node: CallExpression) {
          val fn = node.function as? Identifier
          val first = node.arguments.firstOrNull()?.takeIf { it.name == null }?.value
          if (fn != null) {
            if (fn.name in kinds && first is StringLiteral) names += first.value
          }
          super.visit(node)
        }
      }
    )
    return names
  }

  /** Attribute namespaces are unique across the world, plugins' included. */
  fun checkNamespaces() {
    reportNamespaceConflicts(namespaceOwners())
    reportPluginNamespaceClashes()
  }

  /** Every attribute namespace and the packages that own it, shared ownership noted. */
  private fun namespaceOwners(): Map<String, List<String>> {
    val owners = LinkedHashMap<String, MutableList<String>>()
    for (st in live) {
      owners.getOrPut(st.pkg.namespace) { mutableListOf() } += st.pkg.path
      for (ns in st.pkg.sharedNamespaces) owners.getOrPut(ns) { mutableListOf() } +=
        "${st.pkg.path} (shared)"
    }
    return owners
  }

  private fun reportNamespaceConflicts(owners: Map<String, List<String>>) {
    for ((ns, pkgs) in owners) {
      if (pkgs.size < 2) continue
      for (p in pkgs) {
        val path = p.removeSuffix(" (shared)")
        states.getValue(path).errors +=
          LoadError(
            path,
            "namespace",
            "attribute namespace '$ns' is used by more than one package: ${pkgs.joinToString(", ")}",
          )
      }
    }
  }

  private fun reportPluginNamespaceClashes() {
    // A plugin writes attributes in its id's namespace, so no package may own it.
    for (st in live) {
      for (ns in listOf(st.pkg.namespace) + st.pkg.sharedNamespaces) {
        if (ns in pluginGlobals)
          st.errors +=
            LoadError(
              st.pkg.path,
              "namespace",
              "attribute namespace '$ns' is the namespace of plugin '$ns'",
            )
      }
    }
  }

  /** Region names are world-wide, so only one package may declare each. */
  fun checkRegions() {
    val owners = LinkedHashMap<String, MutableList<String>>()
    for (st in live) for (r in st.pkg.regions) owners.getOrPut(r.name) { mutableListOf() } +=
      st.pkg.path
    for ((name, pkgs) in owners) {
      if (pkgs.size < 2) continue
      for (p in pkgs.distinct()) {
        states.getValue(p).errors +=
          LoadError(
            p,
            "region",
            "region '$name' is declared by more than one package: ${pkgs.joinToString(", ") { "//$it" }}",
          )
      }
    }
  }

  /** Declared GMCP packages may not overlap across packages or with a plugin's. */
  fun checkGmcp() {
    reportGmcpOverlap()
    reportPluginGmcpOverlap()
  }

  private fun reportGmcpOverlap() {
    val decls = live.flatMap { st -> st.pkg.gmcpPackages.map { it.name to st.pkg.path } }
    for (i in decls.indices) {
      for (j in (i + 1) until decls.size) {
        val (a, pa) = decls[i]
        val (b, pb) = decls[j]
        if (pa == pb || !gmcpOverlaps(a, b)) continue
        states.getValue(pa).errors +=
          LoadError(pa, "gmcp", "GMCP package '$a' of //$pa overlaps '$b' of //$pb")
        states.getValue(pb).errors +=
          LoadError(pb, "gmcp", "GMCP package '$b' of //$pb overlaps '$a' of //$pa")
      }
    }
  }

  private fun reportPluginGmcpOverlap() {
    val plugin = knownGmcp()
    for (st in live) {
      for (decl in st.pkg.gmcpPackages) {
        plugin
          .firstOrNull { gmcpOverlaps(decl.name, it) }
          ?.let {
            st.errors +=
              LoadError(
                st.pkg.path,
                "gmcp",
                "GMCP package '${decl.name}' overlaps the plugin package '$it'",
              )
          }
      }
    }
  }

  /** Global commands may not shadow engine commands, directions or each other. */
  fun checkCommands() {
    val seen = reportCommandClashes()
    reportExitKeywords(seen)
  }

  private fun reportCommandClashes(): Map<String, String> {
    val seen = HashMap<String, String>()
    for (st in live) {
      for (c in st.pkg.globalCommands) {
        val clash = commandClash(c.name, seen, st.pkg.path) ?: continue
        st.errors +=
          LoadError(st.pkg.path, "command", "global_command '${c.name}' clashes with the $clash")
      }
    }
    return seen
  }

  private fun commandClash(name: String, seen: MutableMap<String, String>, owner: String): String? {
    if (name in engineVerbs) return "engine command"
    if (Direction.parse(name) in Direction.STANDARD) return "direction"
    return seen.put(name, owner)?.let { "global command of //$it" }
  }

  private fun reportExitKeywords(seen: Map<String, String>) {
    // A global command would shadow an exit of the same name, so the name is reserved
    // (engine verbs are rejected by exit() itself).
    for (st in live) {
      for (room in st.pkg.rooms) {
        for (exit in room.exits) {
          val owner = seen[exit.direction.name] ?: continue
          st.errors +=
            LoadError(
              st.pkg.path,
              "command",
              "exit '${exit.direction.name}' of ${room.label} is a reserved keyword: " +
                "the global command of //$owner",
            )
        }
      }
    }
  }
}
