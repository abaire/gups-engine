package gups.core.fixtures

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively

/**
 * Temporary files and directories for tests that have no `@TempDir` to hand, such as shared
 * fixtures. Everything lives under one per-JVM root that is deleted when the JVM exits, so tests
 * need no cleanup of their own.
 */
object TempFiles {
  @OptIn(kotlin.io.path.ExperimentalPathApi::class)
  private val root: Path by lazy {
    Files.createTempDirectory("gups-test").also { root ->
      Runtime.getRuntime().addShutdownHook(Thread { runCatching { root.deleteRecursively() } })
    }
  }

  fun dir(prefix: String): Path = Files.createTempDirectory(root, prefix)

  fun file(prefix: String, suffix: String): Path = Files.createTempFile(root, prefix, suffix)
}

/** A path for a world database inside a fresh temporary directory; the file doesn't exist yet. */
fun tempDb(): Path = TempFiles.dir("gups-db").resolve("world.db")
