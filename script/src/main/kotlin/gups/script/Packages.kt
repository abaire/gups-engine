package gups.script

import gups.core.HookRef
import gups.core.Label
import gups.core.model.ItemProto
import gups.core.model.MobProto
import gups.core.model.Modifiers
import gups.core.model.RegionProto
import gups.core.model.RoomProto
import gups.core.model.SkillProto
import gups.core.world.HookName
import gups.core.world.Lane
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * A course world on disk. Every directory under [root] that holds a `package.star` is a package,
 * addressed by its relative path: `//zones/forest`.
 */
class WorldDir(root: Path) {
  val root: Path = root.toAbsolutePath().normalize()

  /** Every package path under [root], sorted. Hidden and `build` directories are skipped. */
  fun packages(): List<String> {
    if (!root.isDirectory()) return emptyList()
    Files.walk(root).use { paths ->
      return paths
        .filter { it.name == PACKAGE_FILE && it.isRegularFile() }
        .map { root.relativize(it.parent).joinToString("/") }
        .filter { p -> p.isNotEmpty() && p.split('/').none { it.startsWith(".") || it == "build" } }
        .filter { PACKAGE_PATH.matches(it) }
        .sorted()
        .toList()
    }
  }

  fun packageDir(pkg: String): Path = root.resolve(pkg)

  fun hasPackage(pkg: String): Boolean =
    PACKAGE_PATH.matches(pkg) && packageDir(pkg).resolve(PACKAGE_FILE).isRegularFile()

  /** The file a load label such as `//lib:quest.star` names. */
  fun fileOf(label: Label): Path = packageDir(label.pkg).resolve(label.name)

  /**
   * The package's own `.star` files, sorted by name: its `*_test.star` files if [tests], else its
   * content files, which include the `*_testdata.star` fixture files only if [fixtures].
   */
  fun files(pkg: String, tests: Boolean, fixtures: Boolean = false): List<Label> {
    val dir = packageDir(pkg)
    if (!dir.isDirectory()) return emptyList()
    Files.list(dir).use { paths ->
      return paths
        .filter { it.isRegularFile() && it.name.endsWith(".star") && it.name != PACKAGE_FILE }
        .filter { it.name.endsWith(TEST_SUFFIX) == tests }
        .filter { tests || fixtures || !isFixture(it.name) }
        .map { Label("//$pkg:${it.name}") }
        .sorted(compareBy { it.v })
        .toList()
    }
  }

  /** One source line, for caret snippets; null if unavailable. */
  fun sourceLine(file: Label, line: Int): String? =
    runCatching { fileOf(file).readText().lines().getOrNull(line - 1) }.getOrNull()

  companion object {
    const val PACKAGE_FILE = "package.star"
    const val TEST_SUFFIX = "_test.star"
    const val FIXTURE_SUFFIX = "_testdata.star"

    /** Whether a file name is a test-only fixture file, loaded only for tests. */
    fun isFixture(name: String): Boolean = name.endsWith(FIXTURE_SUFFIX)

    private val PACKAGE_PATH = Regex("[a-z0-9_]+(?:/[a-z0-9_]+)*")
  }
}

/** Package kinds. Only `lib` and `rules` packages are privileged. */
enum class PackageKind(val word: String, val privileged: Boolean) {
  ZONE("zone", false),
  LIB("lib", true),
  RULES("rules", true),
  SKILLS("skills", false);

  companion object {
    fun parse(word: String): PackageKind? = entries.firstOrNull { it.word == word }
  }
}

/**
 * Why a package failed to load. [file], [line] and [column] point at the offending code when there
 * is one; [snippet] is that source line with a caret under it.
 */
data class LoadError(
  val pkg: String,
  val kind: String,
  val message: String,
  val file: String? = null,
  val line: Int? = null,
  val column: Int? = null,
  val snippet: String? = null,
) {
  /** `//zones/x:rooms.star:12:5: message`, then the caret snippet. */
  fun render(): String = buildString {
    append(file ?: "//$pkg")
    if (line != null) append(':').append(line)
    if (line != null && column != null) append(':').append(column)
    append(": ").append(message)
    if (snippet != null) append('\n').append(snippet)
  }

  override fun toString(): String = render()
}

/** Where a Starlark module came from. Set as the module's client data. */
internal data class FileInfo(val label: Label, val pkg: String, val test: Boolean = false)

/** A `global_command` registered by a privileged package. */
data class GlobalCommandDecl(val name: String, val fn: HookRef, val lane: Lane, val pkg: String)

/** A global hook registered by a privileged package: [hook] fires for every actor. */
data class GlobalHookDecl(val hook: String, val fn: HookRef, val pkg: String)

/** A GMCP package a lib or rules package sends with `ctx.gmcp`. */
data class GmcpPackageDecl(val name: String, val doc: String, val pkg: String)

/** Everything one package declared. Built while the package evaluates, then read-only. */
class ScriptPackage internal constructor(val path: String) {
  var kind: PackageKind = PackageKind.ZONE
    internal set

  var title: String = path
    internal set

  var apiVersion: Int = 0
    internal set

  var resetPulses: Int? = null
    internal set

  var sharedNamespaces: List<String> = emptyList()
    internal set

  /** Modifiers that apply in every room of the package. */
  var modifiers: Modifiers = emptyMap()
    internal set

  /** Attribute namespace: the last path segment. */
  val namespace: String
    get() = path.substringAfterLast('/')

  /** Where each rules export is defined, across the files of a rules package. */
  internal val rulesExports = LinkedHashMap<String, HookRef>()

  internal val roomList = mutableListOf<RoomProto>()
  internal val mobList = mutableListOf<MobProto>()
  internal val itemList = mutableListOf<ItemProto>()
  internal val skillList = mutableListOf<SkillProto>()
  internal val regionList = mutableListOf<RegionProto>()
  internal val gmcpList = mutableListOf<GmcpPackageDecl>()
  internal val commandList = mutableListOf<GlobalCommandDecl>()
  internal val hookList = mutableListOf<GlobalHookDecl>()
  internal val hookMap = LinkedHashMap<Label, Map<HookName, HookRef>>()
  internal val customHookMap = HashMap<Label, Map<String, HookRef>>()

  /** Declaration names, unique across kinds within the package. */
  internal val names = HashSet<String>()

  /** The package's `migrate(old_version, attrs)`, if one of its files defines it. */
  var migrate: HookRef? = null
    internal set

  /** Expected tombstones declared for this package. */
  var expectedTombstones: List<Label> = emptyList()
    internal set

  /** Labels whose version bumped without requiring a `migrate()` function. */
  var migrateNoop: List<Label> = emptyList()
    internal set

  /** Labels this package refers to, checked once every package has loaded. */
  internal val refs = mutableListOf<LabelRef>()

  /** The slots its items are worn in, checked once the rules have named the slots. */
  internal val wearRefs = mutableListOf<WearRef>()

  val rooms: List<RoomProto>
    get() = roomList

  val mobs: List<MobProto>
    get() = mobList

  val items: List<ItemProto>
    get() = itemList

  val skills: List<SkillProto>
    get() = skillList

  val regions: List<RegionProto>
    get() = regionList

  val gmcpPackages: List<GmcpPackageDecl>
    get() = gmcpList

  val globalCommands: List<GlobalCommandDecl>
    get() = commandList

  val globalHooks: List<GlobalHookDecl>
    get() = hookList

  /** Hooks per prototype label. */
  val hooks: Map<Label, Map<HookName, HookRef>>
    get() = hookMap

  /** Custom hooks per prototype label. */
  val customHooks: Map<Label, Map<String, HookRef>>
    get() = customHookMap
}

/** What a referenced label must name. */
internal enum class RefKind(val word: String) {
  ROOM("room"),
  MOB("mob"),
  ITEM("item"),
  SKILL("skill"),
}

/** A label one declaration refers to, with where it was written for error messages. */
internal data class LabelRef(
  val label: Label,
  val kind: RefKind,
  val file: String?,
  val line: Int?,
  val column: Int?,
)

/**
 * The slot or slot family an item declared with [param] (`wear` or `wield`), with where it was
 * written for error messages.
 */
internal data class WearRef(
  val item: Label,
  val param: String,
  val wear: String,
  val file: String?,
  val line: Int?,
  val column: Int?,
)
