package gups.cli

import com.github.ajalt.clikt.testing.test
import gups.core.Label
import gups.core.WorldControlEvent
import gups.core.world.DeployOutcome
import gups.core.world.World
import gups.core.world.changeVital
import gups.pluginapi.testplugins.TidesPlugin
import gups.pluginapi.testplugins.WeatherPlugin
import gups.pluginapi.testplugins.buildPluginJar
import gups.script.WorldDir
import gups.script.testing.TestWorlds
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class OperationsTest {
  @TempDir lateinit var tmp: Path

  private val token = "admin-secret"
  private val gate = Label("//zones/tutorial:gate")

  private fun git(dir: Path, vararg args: String): String {
    val p =
      ProcessBuilder(listOf("git") + args).directory(dir.toFile()).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader().readText()
    check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) {
      "git ${args.joinToString(" ")} failed:\n$out"
    }
    return out.trim()
  }

  /** A course repository: a bare remote, the server's working tree, and a developer's clone. */
  private inner class Repo(seedChange: (Path) -> Unit = {}) {
    val root: Path = Files.createTempDirectory(tmp, "gups-ops")
    val bare: Path = root.resolve("course.git")
    val world: Path = root.resolve("world")
    val dev: Path = root.resolve("dev")

    init {
      val seed = root.resolve("seed")
      TestWorlds.copyWorld(to = seed)
      seed.resolve(".gitignore").writeText(".gups/\n")
      seedChange(seed)
      git(seed, "init", "--quiet", "-b", "main")
      identity(seed)
      git(seed, "add", "-A")
      git(seed, "commit", "--quiet", "-m", "seed")
      git(root, "clone", "--quiet", "--bare", seed.toString(), bare.toString())
      git(root, "clone", "--quiet", bare.toString(), world.toString())
      git(root, "clone", "--quiet", bare.toString(), dev.toString())
      identity(world)
      identity(dev)
    }

    private fun identity(dir: Path) {
      git(dir, "config", "user.email", "dev@example.com")
      git(dir, "config", "user.name", "Dev")
      // Git's detached background maintenance after a commit races with tests that scan the tree.
      git(dir, "config", "gc.auto", "0")
      git(dir, "config", "maintenance.auto", "false")
    }

    /** Commits [change] in the developer's clone, pushes it to [branch], and returns the commit. */
    fun push(message: String, branch: String = "main", change: (Path) -> Unit): String {
      if (branch != "main") git(dev, "checkout", "--quiet", "-B", branch)
      change(dev)
      git(dev, "add", "-A")
      git(dev, "commit", "--quiet", "-m", message)
      git(dev, "push", "--quiet", "origin", branch)
      val sha = git(dev, "rev-parse", "HEAD")
      if (branch != "main") git(dev, "checkout", "--quiet", "main")
      return sha
    }

    fun rooms(dir: Path = dev): Path = dir.resolve("zones/tutorial/rooms.star")

    val db: Path
      get() = GupsServer.defaultDb(WorldDir(world))
  }

  private class Running(val server: GupsServer, val logs: StringBuffer) {
    val admin: String
      get() = "http://127.0.0.1:${server.ports.getValue("admin")}"

    /** Runs [block] on the world thread and waits for its answer. */
    fun <T> onWorld(block: (World) -> T): T {
      val f = CompletableFuture<T>()
      server.world.post!!(WorldControlEvent.Task { w -> f.complete(block(w)) })
      return f.get(10, TimeUnit.SECONDS)
    }

    fun deploy(commit: String? = null, progress: (String) -> Unit = {}): DeployOutcome {
      val f = CompletableFuture<DeployOutcome>()
      server.world.deploys!!.start(commit, progress) { f.complete(it) }
      return f.get(2, TimeUnit.MINUTES)
    }
  }

  private fun withServer(
    repo: Repo,
    force: Boolean = false,
    stallMs: Long? = null,
    nanoTime: () -> Long = System::nanoTime,
    play: (Running) -> Unit,
  ) {
    val logs = StringBuffer()
    val prod =
      ProdOptions(
        "127.0.0.1",
        admin = AdminListener("127.0.0.1", 0, token),
        forceCommit = force,
        stallMs = stallMs ?: 5_000L,
      )
    val server =
      GupsServer(WorldDir(repo.world), port = 0, prod = prod, nanoTime = nanoTime) {
        logs.append(it).append('\n')
      }
    assertTrue(server.boot(), logs.toString())
    val ready = CompletableFuture<Int>()
    val runner = thread { runBlocking(Dispatchers.Default) { server.run { ready.complete(it) } } }
    try {
      ready.get(10, TimeUnit.SECONDS)
      play(Running(server, logs))
    } finally {
      server.stop()
      runner.join(10_000)
    }
  }

  private val http = HttpClient.newHttpClient()

  private fun get(url: String, auth: Boolean = true): HttpResponse<String> =
    http.send(
      HttpRequest.newBuilder(URI(url))
        .apply { if (auth) header("Authorization", "Bearer $token") }
        .build(),
      HttpResponse.BodyHandlers.ofString(),
    )

  @Test
  fun `a pushed commit deploys through the admin port, and CODEOWNERS takes effect`() {
    val repo = Repo()
    gups.persist.Database.open(repo.db).use { d ->
      gups.persist.SqliteAccountStore(repo.db, d::write).use {
        it.create(
          gups.core.Account(0, "bea", null, gups.core.Role.BUILDER, githubHandle = "bea-gh")
        )
      }
    }
    val first = git(repo.world, "rev-parse", "HEAD")
    val sha =
      repo.push("rename the gate") { dev ->
        repo
          .rooms(dev)
          .writeText(repo.rooms(dev).readText().replace("The Tutorial Gate", "The Deployed Gate"))
        dev.resolve("CODEOWNERS").writeText("/zones/tutorial/ @bea-gh\n")
      }
    withServer(repo) { r ->
      assertEquals("The Tutorial Gate", r.onWorld { it.room(gate).name })
      val res =
        http.send(
          HttpRequest.newBuilder(URI("${r.admin}/deploy"))
            .header("Authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build(),
          HttpResponse.BodyHandlers.ofString(),
        )
      val lines = res.body().trim().lines()
      assertContains(lines.first(), "Fetching main")
      assertEquals(
        """{"ok":true,"commit":"$sha","packages":["zones/tutorial"],"errors":[]}""",
        lines.last(),
        res.body(),
      )
      assertEquals("The Deployed Gate", r.onWorld { it.room(gate).name })
      assertEquals(sha, git(repo.world, "rev-parse", "HEAD"), "the live tree moved after the swap")
      assertTrue(
        Files.list(repo.db.parent.resolve("backups")).use { s ->
          s.anyMatch { it.fileName.toString().startsWith("predeploy-") }
        }
      )
      gups.persist
        .SqliteAccountStore(repo.db) { error("read only") }
        .use { store -> assertEquals(listOf("zones/tutorial"), store.byLogin("bea")!!.scope) }
      assertContains(r.logs, "Deployed $sha")
    }
    assertTrue(first != sha)
    gups.persist.Database.open(repo.db).use { assertEquals(sha, it.meta()["world_commit"]) }
  }

  @Test
  fun `deploys keep plugin commands and refuse plugin changes`() {
    val repo = Repo { seed ->
      val sum = buildPluginJar(seed.resolve("plugins/weather.jar"), WeatherPlugin::class)
      val module = seed.resolve("MODULE.gups")
      module.writeText(
        module.readText().replace("module(", "module(\n    plugins = {\"weather\": \"$sum\"},")
      )
    }
    repo.push("rename the gate") { dev ->
      repo
        .rooms(dev)
        .writeText(repo.rooms(dev).readText().replace("The Tutorial Gate", "The Weather Gate"))
    }
    withServer(repo) { r ->
      assertTrue(r.deploy().ok, r.logs.toString())
      assertEquals("The Weather Gate", r.onWorld { it.room(gate).name })
      assertEquals(
        listOf("crash", "forecast", "rainmake", "storm"),
        r.onWorld { w -> w.commands.pluginCommands.map { it.name }.sorted() },
      )
    }
    repo.push("change the plugin") { dev ->
      buildPluginJar(dev.resolve("plugins/weather.jar"), WeatherPlugin::class, TidesPlugin::class)
    }
    withServer(repo) { r -> assertContains(r.deploy().errors.single(), "plugins/ changed") }
  }

  @Test
  fun `failed and refused deploys leave the world untouched`() {
    val repo = Repo()
    val live = git(repo.world, "rev-parse", "HEAD")
    val stray =
      repo.push("off the branch", branch = "experiment") {
        it.resolve("zones/tutorial/rooms.star").let { f -> f.writeText(f.readText() + "\n") }
      }
    repo.push("break the tutorial") { dev ->
      repo
        .rooms(dev)
        .writeText(
          repo.rooms(dev).readText().replace("The Tutorial Gate", "The Broken Gate") +
            "\nthis is not starlark(\n"
        )
    }
    withServer(repo) { r ->
      val broken = r.deploy()
      assertFalse(broken.ok)
      assertContains(broken.errors.joinToString("\n"), "rooms.star")
      assertEquals(
        "The Tutorial Gate",
        r.onWorld { it.room(gate).name },
        "a load failure swaps nothing",
      )
      assertEquals(live, git(repo.world, "rev-parse", "HEAD"))
      assertTrue(r.onWorld { w -> w.diagnostics.any { it.kind == "deploy" } }, "@errors has it")

      assertContains(r.deploy(stray).errors.single(), "is not on main")
      assertContains(
        r.deploy("0123456789abcdef").errors.single(),
        "0123456789abcdef is not on main",
      )
      val local =
        git(
          repo.world,
          "commit-tree",
          "-m",
          "never pushed",
          git(repo.world, "rev-parse", "HEAD^{tree}"),
        )
      assertTrue(
        "$local is not on main" in r.deploy(local).errors.single(),
        "known but off the branch",
      )

      // Only one deploy at a time.
      val inside = CountDownLatch(1)
      val release = CountDownLatch(1)
      val first = CompletableFuture<DeployOutcome>()
      r.server.world.deploys!!.start(
        null,
        {
          inside.countDown()
          release.await(30, TimeUnit.SECONDS)
        },
      ) {
        first.complete(it)
      }
      assertTrue(inside.await(30, TimeUnit.SECONDS))
      assertContains(r.deploy().errors.single(), "Another deploy is running")
      release.countDown()
      first.get(2, TimeUnit.MINUTES)
    }
    repo.push("upgrade the engine") { dev ->
      repo.rooms(dev).writeText(repo.rooms(dev).readText().replace("\nthis is not starlark(\n", ""))
      dev
        .resolve("MODULE.gups")
        .writeText(
          dev
            .resolve("MODULE.gups")
            .readText()
            .replace("checkpoint_minutes = 30", "checkpoint_minutes = 20")
        )
    }
    withServer(repo) { r ->
      assertContains(r.deploy().errors.single(), "MODULE.gups changed")
      assertEquals(live, git(repo.world, "rev-parse", "HEAD"))
    }
  }

  @Test
  fun `a deploy that changes MESSAGES gups is refused like one that changes MODULE gups`() {
    val repo = Repo()
    val live = git(repo.world, "rev-parse", "HEAD")
    repo.push("reword the engine") { dev ->
      dev.resolve("MESSAGES.gups").writeText("messages({\"common.huh\": \"Pardon?\"})\n")
    }
    withServer(repo) { r ->
      assertContains(r.deploy().errors.single(), "MESSAGES.gups changed")
      assertEquals(live, git(repo.world, "rev-parse", "HEAD"))
    }
  }

  @Test
  fun `the server refuses to start off its recorded commit unless forced`() {
    val repo = Repo()
    withServer(repo) {}
    repo.rooms(repo.world).writeText(repo.rooms(repo.world).readText() + "\n")
    val logs = StringBuffer()
    val refuse =
      GupsServer(WorldDir(repo.world), port = 0, prod = ProdOptions("127.0.0.1")) {
        logs.append(it).append('\n')
      }
    assertFalse(refuse.boot())
    assertContains(logs, "changes nobody committed")
    git(repo.world, "commit", "--quiet", "-am", "a hand edit on the server")
    logs.setLength(0)
    assertFalse(
      GupsServer(WorldDir(repo.world), port = 0, prod = ProdOptions("127.0.0.1")) {
          logs.append(it).append('\n')
        }
        .boot()
    )
    assertContains(logs, "but the database was saved at")
    withServer(repo, force = true) { r -> assertContains(r.logs, "Recording world commit") }
    withServer(repo) {}
  }

  @Test
  fun `a wipe that the commit check refuses deletes nothing`() {
    val repo = Repo()
    withServer(repo) {}
    repo.rooms(repo.world).writeText(repo.rooms(repo.world).readText() + "\n")
    val logs = StringBuffer()
    val refuse =
      GupsServer(WorldDir(repo.world), port = 0, prod = ProdOptions("127.0.0.1"), wipe = true) {
        logs.append(it).append('\n')
      }
    assertFalse(refuse.boot())
    assertContains(logs, "changes nobody committed")
    assertFalse("Wiped" in logs, logs.toString())
    val backups = repo.db.resolveSibling("backups")
    assertFalse(
      Files.exists(backups) &&
        Files.list(backups).use { s -> s.anyMatch { "prewipe-" in it.fileName.toString() } },
      "no prewipe backup was taken",
    )
  }

  @Test
  fun `metrics have the spec's names after one pulse, and healthz fails while the world stalls`() {
    val repo = Repo()
    // Only the health check reads this clock; it stays still unless the test moves it.
    val clock = AtomicLong(0)
    withServer(repo, stallMs = 300, nanoTime = clock::get) { r ->
      r.onWorld { it.pulse }
      var text = get("${r.admin}/metrics").body()
      val seen = System.nanoTime() + 5_000_000_000
      while ("gups_pulse_duration_seconds_count" !in text && System.nanoTime() < seen) {
        Thread.sleep(20)
        text = get("${r.admin}/metrics").body()
      }
      for (name in
        listOf(
          "gups_pulse_duration_seconds_bucket",
          "gups_pulse_duration_seconds_count",
          "gups_pulse_overruns_total",
          "gups_world_input_queue_depth",
          "gups_script_steps_total{package=\"zones/tutorial\"}",
          "gups_script_errors_total{kind=\"script_error\",package=\"zones/tutorial\"}",
          "gups_quarantined_packages",
          "gups_bubble_rooms",
          "gups_bubble_entities",
          "gups_sessions{transport=\"telnet\"}",
          "gups_checkpoint_duration_seconds_count",
          "gups_checkpoint_entities",
          "gups_checkpoint_backlog",
        )) {
        assertContains(text, name)
      }
      assertEquals(401, get("${r.admin}/metrics", auth = false).statusCode())

      val health = get("${r.admin}/healthz", auth = false)
      assertEquals(200, health.statusCode())
      assertContains(health.body(), "\"writer_healthy\":true")

      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      // The timeout only keeps a failed test from wedging the world forever.
      r.server.world.post!!(
        WorldControlEvent.Task {
          entered.countDown()
          release.await(60, TimeUnit.SECONDS)
        }
      )
      try {
        assertTrue(entered.await(30, TimeUnit.SECONDS), "the world thread took the task")
        clock.addAndGet(299_000_000)
        assertEquals(200, get("${r.admin}/healthz", auth = false).statusCode())
        clock.addAndGet(2_000_000)
        assertEquals(
          503,
          get("${r.admin}/healthz", auth = false).statusCode(),
          "a stalled world thread is unhealthy",
        )
      } finally {
        release.countDown()
      }
      // Waits for the next pulse to happen, not for time to pass: the clock no longer moves.
      var status = 503
      val recovery = System.nanoTime() + 30_000_000_000
      while (status != 200 && System.nanoTime() < recovery) {
        Thread.sleep(20)
        status = get("${r.admin}/healthz", auth = false).statusCode()
      }
      assertEquals(200, status, "a pulsing world is healthy again")
    }
  }

  @Test
  fun `import-roster and the sanitized snapshot go through the admin port`() {
    val repo = Repo()
    withServer(repo) { r ->
      r.onWorld { w ->
        w.spawnPlayer("Tess", gate).also { it.accountId = 1 }.let { w.requestImmediate("create") }
      }
      r.onWorld { it.checkpointNow("test") }
      val tokenFile = Files.createTempFile("token", "").also { it.writeText("$token\n") }
      val roster = Files.createTempFile("roster", ".csv").also { it.writeText("login\nann\n") }
      val address = r.admin.removePrefix("http://")
      val imported =
        gupsCommand()
          .test(
            listOf(
              "import-roster",
              roster.toString(),
              "--admin",
              address,
              "--admin-token-file",
              tokenFile.toString(),
            )
          )
      assertEquals(0, imported.statusCode, imported.output)
      assertTrue(
        Regex("login,setup_code\nann,[A-Z0-9]{5}-[A-Z0-9]{5}").containsMatchIn(imported.stdout),
        imported.stdout,
      )
      val wrong = Files.createTempFile("token", "").also { it.writeText("nope") }
      assertEquals(
        1,
        gupsCommand()
          .test(
            listOf(
              "import-roster",
              roster.toString(),
              "--admin",
              address,
              "--admin-token-file",
              wrong.toString(),
            )
          )
          .statusCode,
      )

      val snap =
        http.send(
          HttpRequest.newBuilder(URI("${r.admin}/snapshot"))
            .header("Authorization", "Bearer $token")
            .build(),
          HttpResponse.BodyHandlers.ofByteArray(),
        )
      assertEquals(200, snap.statusCode())
      val file = Files.createTempFile("snapshot", ".db").also { Files.write(it, snap.body()) }
      java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
        c.createStatement().use { st ->
          fun one(sql: String) =
            st.executeQuery(sql).use {
              it.next()
              it.getString(1)
            }
          assertEquals("0", one("SELECT COUNT(*) FROM accounts"), "no accounts")
          assertEquals("0", one("SELECT COUNT(*) FROM invites"))
          assertEquals("Player1", one("SELECT name FROM players"))
          assertEquals(null, one("SELECT account_id FROM players"))
          assertTrue(one("SELECT COUNT(*) FROM world_entities").toInt() > 0, "the world is kept")
        }
      }

      // CLI snapshot --admin creates sanitized snapshot
      val cliSnapFile = Files.createTempFile("cli-snap", ".db")
      Files.deleteIfExists(cliSnapFile)
      val snapRes =
        gupsCommand()
          .test(
            listOf(
              "snapshot",
              "--sanitized",
              "--out",
              cliSnapFile.toString(),
              "--admin",
              address,
              "--admin-token-file",
              tokenFile.toString(),
            )
          )
      assertEquals(0, snapRes.statusCode, snapRes.output)
      assertTrue(Files.exists(cliSnapFile))
      java.sql.DriverManager.getConnection("jdbc:sqlite:$cliSnapFile").use { c ->
        c.createStatement().use { st ->
          fun one(sql: String) =
            st.executeQuery(sql).use {
              it.next()
              it.getString(1)
            }
          assertEquals("0", one("SELECT COUNT(*) FROM accounts"))
          assertEquals("Player1", one("SELECT name FROM players"))
        }
      }

      // CLI snapshot --admin with wrong token fails and does NOT leave a file
      val failSnapFile = Files.createTempFile("cli-snap-fail", ".db")
      Files.deleteIfExists(failSnapFile)
      val failRes =
        gupsCommand()
          .test(
            listOf(
              "snapshot",
              "--sanitized",
              "--out",
              failSnapFile.toString(),
              "--admin",
              address,
              "--admin-token-file",
              wrong.toString(),
            )
          )
      assertEquals(1, failRes.statusCode)
      assertFalse(Files.exists(failSnapFile), "Failed snapshot download should not leave a file")
    }
  }

  @Test
  fun `POST roster with disabled=true for a logged-in player quits that character before response returns`() {
    val repo = Repo()
    withServer(repo) { r ->
      val accounts = r.server.world.persistence.accounts!!
      val ann = accounts.create(gups.core.Account(0, "ann", "hash", gups.core.Role.PLAYER))!!
      r.onWorld { w ->
        w.spawnPlayer("Ann", gate, accountId = ann.id)
        w.requestImmediate("create")
      }
      r.onWorld { it.checkpointNow("test") }
      // Changed after the last checkpoint, so only the quit's own checkpoint can save it.
      r.onWorld { w ->
        val p = w.players.single { it.accountId == ann.id }
        w.changeVital(p, "hp", 7 - p.current("hp"))
      }

      val res =
        http.send(
          HttpRequest.newBuilder(URI("${r.admin}/roster"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "text/csv")
            .POST(HttpRequest.BodyPublishers.ofString("login,disabled\nann,true\n"))
            .build(),
          HttpResponse.BodyHandlers.ofString(),
        )
      assertEquals(200, res.statusCode(), res.body())

      assertFalse(r.onWorld { it.players.any { p -> p.accountId == ann.id } })
      assertEquals(7, r.onWorld { w -> w.persistence.loadCharacter(w, "Ann")?.current("hp") })
      assertTrue(accounts.byId(ann.id)!!.disabled)
      assertEquals("roster", accounts.byId(ann.id)!!.disabledReason)
    }
  }
}
