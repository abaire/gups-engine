package gups.script.testing

import gups.core.fixtures.TempFiles
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Writes [files] (paths relative to the world root) into a fresh temporary world. */
fun tempWorld(files: Map<String, String>): Path {
  val root = TempFiles.dir("gups-world")
  for ((path, text) in TestWorlds.withDefaults(files)) {
    val p = root.resolve(path)
    p.parent.createDirectories()
    p.writeText(text.trimIndent() + "\n")
  }
  return root
}
