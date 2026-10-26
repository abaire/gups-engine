package gups.cli

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** A `git` command that failed, with what it printed. */
class GitException(message: String) : Exception(message)

/**
 * The `git` binary, run in [dir] (SPEC §5.10). [sshKey] is the deploy key, passed through
 * `GIT_SSH_COMMAND` for fetches. Every call blocks.
 */
class Git(
  private val dir: Path,
  private val sshKey: Path? = null,
  private val timeoutSeconds: Long = 300,
) {
  /** Runs `git args...` and returns its standard output, trimmed, or throws [GitException]. */
  fun run(vararg args: String): String {
    val pb = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).redirectErrorStream(true)
    val env = pb.environment()
    env["GIT_TERMINAL_PROMPT"] = "0"
    sshKey?.let {
      env["GIT_SSH_COMMAND"] =
        "ssh -i '$it' -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new"
    }
    val p =
      try {
        pb.start()
      } catch (e: java.io.IOException) {
        throw GitException("can't run git: ${e.message}")
      }
    val out = p.inputStream.bufferedReader().readText()
    if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
      p.destroyForcibly()
      throw GitException("git ${args.first()} took longer than $timeoutSeconds s")
    }
    if (p.exitValue() != 0)
      throw GitException("git ${args.joinToString(" ")} failed: ${out.trim()}")
    return out.trim()
  }

  /** True if [dir] is inside a git working tree. */
  fun isWorkTree(): Boolean =
    runCatching { run("rev-parse", "--is-inside-work-tree") == "true" }.getOrDefault(false)

  fun head(): String = run("rev-parse", "HEAD")

  /** The full commit id [rev] names, or throws. */
  fun commit(rev: String): String = run("rev-parse", "--verify", "--quiet", "$rev^{commit}")

  fun isAncestor(commit: String, of: String): Boolean =
    runCatching {
        run("merge-base", "--is-ancestor", commit, of)
        true
      }
      .getOrDefault(false)

  /**
   * Tracked files changed since the last commit; untracked files such as the database don't count.
   */
  fun dirtyFiles(): List<String> =
    run("status", "--porcelain", "--untracked-files=no")
      .lines()
      .filter { it.isNotBlank() }
      .map { it.drop(3) }

  fun changedFiles(from: String, to: String): List<String> =
    run("diff", "--name-only", from, to).lines().filter { it.isNotBlank() }

  companion object {
    /** True if a `git` binary can be run (`--prod` needs one, SPEC §2.4). */
    fun available(): Boolean =
      runCatching {
          ProcessBuilder("git", "--version").redirectErrorStream(true).start().let {
            it.inputStream.readAllBytes()
            it.waitFor(10, TimeUnit.SECONDS) && it.exitValue() == 0
          }
        }
        .getOrDefault(false)
  }
}
