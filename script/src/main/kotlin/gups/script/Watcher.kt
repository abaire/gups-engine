package gups.script

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Finds changed packages for `--dev` auto-reload (SPEC §5.10). It polls file times and sizes
 * rather than using the JDK's WatchService, which polls slowly on macOS anyway (D-086).
 * Changes are reported once nothing has changed for [debounceNanos].
 */
class WorldWatcher(private val dir: WorldDir, private val debounceNanos: Long = 200_000_000) {
    private var snapshot = scan()
    private val pending = sortedSetOf<String>()
    private var lastChange = 0L

    /** Call periodically. Returns the packages to reload, or an empty list. */
    fun poll(nowNanos: Long = System.nanoTime()): List<String> {
        val next = scan()
        if (next != snapshot) {
            val changed = (next.keys + snapshot.keys).filter { next[it] != snapshot[it] }
            pending += changed.mapNotNull { packageOf(it) }
            snapshot = next
            lastChange = nowNanos
        }
        if (pending.isEmpty() || nowNanos - lastChange < debounceNanos) return emptyList()
        return pending.toList().also { pending.clear() }
    }

    /** `.star` files that aren't tests, with their modification time and size. */
    private fun scan(): Map<Path, Pair<Long, Long>> {
        if (!Files.isDirectory(dir.root)) return emptyMap()
        Files.walk(dir.root).use { paths ->
            return paths
                .filter { it.name.endsWith(".star") && !it.name.endsWith(WorldDir.TEST_SUFFIX) }
                .filter { p -> dir.root.relativize(p).none { it.name.startsWith(".") || it.name == "build" } }
                .toList()
                .mapNotNull { p -> runCatching { p to (p.getLastModifiedTime().toMillis() to Files.size(p)) }.getOrNull() }
                .toMap()
        }
    }

    /** The package a file belongs to: its nearest directory with a package.star, or its own directory if that's gone. */
    private fun packageOf(file: Path): String? {
        var d: Path? = file.parent
        while (d != null && d.startsWith(dir.root) && d != dir.root) {
            if (d.resolve(WorldDir.PACKAGE_FILE).isRegularFile() || (file.name == WorldDir.PACKAGE_FILE && d == file.parent)) {
                return dir.root.relativize(d).joinToString("/")
            }
            d = d.parent
        }
        return null
    }
}
