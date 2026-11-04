package gups.cli

import com.github.ajalt.clikt.testing.test
import gups.core.EngineInfo
import gups.harness.HarnessApi
import gups.script.ApiDiff
import gups.script.ScriptApi
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandsTest {
  @Test
  fun `version reports engine and api versions`() {
    val result = gupsCommand().test("--version")
    assertEquals(0, result.statusCode)
    assertEquals("gups ${EngineInfo.version} (api_version 1)", result.stdout.trim())
  }

  @Test
  fun `help lists every subcommand`() {
    val result = gupsCommand().test("--help")
    assertEquals(0, result.statusCode)
    for (name in
      listOf(
        "serve",
        "test",
        "lint",
        "fuzz",
        "snapshot",
        "migrate-check",
        "docs",
        "repro",
        "scenario",
        "backup",
        "restore",
        "deploy",
        "invite",
        "import-roster",
      )) {
      assertContains(result.stdout, name)
    }
  }

  @Test
  fun `docs writes the HTML reference`() {
    val dir = Files.createTempDirectory("gups-docs-html").toFile()
    val result = gupsCommand().test(listOf("docs", "--out", dir.resolve("ref").path))
    assertEquals(0, result.statusCode, result.output)
    assertContains(
      result.stdout,
      "Wrote the API reference (api_version ${EngineInfo.API_VERSION}) to ${dir.resolve("ref")}",
    )
    for (file in DocsRenderer.FILES) assertTrue(dir.resolve("ref/$file").isFile, "missing $file")
    assertEquals(
      ScriptApi.model(HarnessApi.specs()).toJson(),
      dir.resolve("ref/api.json").readText(),
    )

    val world = System.getProperty("gups.templateWorld")
    val withWorld =
      gupsCommand().test(listOf("docs", "--world", world, "--out", dir.resolve("world").path))
    assertEquals(0, withWorld.statusCode, withWorld.output)
    // The template world has every feature on, so only world.html differs: it adds the schema.
    for (file in DocsRenderer.FILES - "world.html") {
      assertEquals(dir.resolve("ref/$file").readText(), dir.resolve("world/$file").readText(), file)
    }
    assertContains(dir.resolve("world/world.html").readText(), "Hit points")
    assertFalse("Hit points" in dir.resolve("ref/world.html").readText())

    val aFile = dir.resolve("existing.txt").also { it.writeText("x") }
    val onFile = gupsCommand().test(listOf("docs", "--out", aFile.path))
    assertEquals(1, onFile.statusCode)
    assertContains(onFile.stderr, "give a directory for the HTML reference")
    val onJson = gupsCommand().test(listOf("docs", "--out", dir.resolve("x.json").path))
    assertEquals(1, onJson.statusCode)
    assertContains(onJson.stderr, "give a directory for the HTML reference, not x.json")
    assertFalse(dir.resolve("x.json").exists())

    val jsonWithWorld = gupsCommand().test(listOf("docs", "--json", "--world", world))
    assertEquals(1, jsonWithWorld.statusCode)
    assertContains(jsonWithWorld.stderr, "api.json never includes plugins")
  }

  @Test
  fun `docs --json writes api json to stdout, a directory or a json file`() {
    val expected = ScriptApi.model(HarnessApi.specs()).toJson()

    val stdout = gupsCommand().test("docs --json")
    assertEquals(0, stdout.statusCode, stdout.output)
    assertEquals(expected, stdout.stdout)

    val dir = Files.createTempDirectory("gups-docs-out").toFile()
    val toDir = gupsCommand().test(listOf("docs", "--json", "--out", dir.resolve("ref").path))
    assertEquals(0, toDir.statusCode, toDir.output)
    assertEquals(expected, dir.resolve("ref/api.json").readText())

    val toFile =
      gupsCommand().test(listOf("docs", "--json", "--out", dir.resolve("out/model.json").path))
    assertEquals(0, toFile.statusCode, toFile.output)
    assertEquals(expected, dir.resolve("out/model.json").readText())

    val wrongExtension =
      gupsCommand().test(listOf("docs", "--json", "--out", dir.resolve("model.txt").path))
    assertEquals(1, wrongExtension.statusCode)
    assertContains(wrongExtension.stderr, "give a directory or a .json path")
    assertFalse(dir.resolve("model.txt").exists())
  }

  /**
   * The repository check: without an api_version bump, the script API may only grow since the
   * committed baseline.
   */
  @Test
  fun `repository check - no breaking API change without an api_version bump`() {
    val docs = File(System.getProperty("gups.docsDir"))
    val n = EngineInfo.API_VERSION
    val problems =
      ApiDiff.check(docs.resolve("api/api-$n.json").readText(), ScriptApi.model(HarnessApi.specs()))
    assertTrue(
      problems.isEmpty(),
      "The script API changed incompatibly since docs/api/api-$n.json (see README.md):\n" +
        problems.joinToString("\n"),
    )
  }

  @Test
  fun `docs --update-baseline writes only the API baseline`() {
    val docs = Files.createTempDirectory("gups-docs-dir").toFile()
    val result = gupsCommand().test(listOf("docs", "--update-baseline", "--docs-dir", docs.path))
    assertEquals(0, result.statusCode, result.output)
    val api = docs.resolve("api/api-$currentApi.json")
    assertContains(result.stdout, "Wrote $api")
    assertEquals(ApiDiff.normalize(ScriptApi.model(HarnessApi.specs()).toJson()), api.readText())
    assertEquals(listOf("api"), docs.list()!!.toList())
    assertEquals(listOf(api.name), docs.resolve("api").list()!!.toList())

    val withOut = gupsCommand().test(listOf("docs", "--update-baseline", "--out", docs.path))
    assertEquals(1, withOut.statusCode)
    assertContains(withOut.stderr, "--update-baseline takes no --json, --out or --world")
  }

  private val currentApi = EngineInfo.API_VERSION

  /**
   * `api.json` byte for byte against a committed copy, so a change to the API shows up in review
   * and the output is the same on every CI platform. After a deliberate change, regenerate it with
   * `./gradlew :cli:test --tests '*CommandsTest*' -Dgups.updateGolden=true`.
   */
  @Test
  fun `api json matches the committed golden copy`() {
    val actual =
      ScriptApi.model(HarnessApi.specs())
        .toJson()
        .replace(
          "\"engine_version\": \"${EngineInfo.version}\"",
          "\"engine_version\": \"<engine>\"",
        )
    val golden = File(System.getProperty("gups.apiGolden"))
    if (System.getProperty("gups.updateGolden") == "true")
      golden.also { it.parentFile.mkdirs() }.writeText(actual)
    assertTrue(golden.isFile, "missing $golden; run with -Dgups.updateGolden=true to create it")
    assertEquals(
      golden.readText(),
      actual,
      "api.json changed; if that is intended, run with -Dgups.updateGolden=true",
    )
  }

  @Test
  fun `serve requires exactly one profile`() {
    assertEquals(1, gupsCommand().test("serve").statusCode)
    assertEquals(1, gupsCommand().test("serve --dev --prod").statusCode)
  }

  @Test
  fun `test runs the tutorial zone`() {
    val world = System.getProperty("gups.templateWorld")
    val result = gupsCommand().test(listOf("test", "--world", world, "//zones/tutorial/..."))
    assertEquals(0, result.statusCode, result.output)
    assertContains(result.stdout, "//zones/tutorial:tutorial_test.star")
    assertContains(result.stdout, "PASS  test_the_guide_blocks_the_arena_until_ready")
    assertContains(result.stdout, "0 failed, 0 errors")
  }

  @Test
  fun `test fails when nothing matches`() {
    val world = System.getProperty("gups.templateWorld")
    val result = gupsCommand().test(listOf("test", "--world", world, "//zones/nowhere"))
    assertEquals(EXIT_TESTS_FAILED, result.statusCode)
    assertContains(result.stderr, "no package matches //zones/nowhere")
  }

  @Test
  fun `scenario runs a file from outside the world and prints its transcript`() {
    val world = System.getProperty("gups.templateWorld")
    val file = kotlin.io.path.createTempDirectory("gups-scenario").resolve("flee.star")
    file
      .toFile()
      .writeText(
        """
        def test_flee_then_trade(t):
            w = t.world(seed = 1)
            tess = w.spawn_player("Tess", at = ":shop")
            ash = w.spawn_player("Ash", at = ":shop")
            ash.do("kill shopkeeper")
            t.contains(ash.do("list"), "busy fighting")
            t.contains(tess.do("list"), "busy fighting")
            for _ in range(20):
                if ash.room.label != "//zones/tutorial:shop":
                    break
                ash.do("flee")
            print("Ash fled")
            ash.do("east")
            t.contains(tess.do("list"), "offers")
            t.contains(ash.do("list"), "offers")
        """
          .trimIndent() + "\n"
      )
    val result =
      gupsCommand()
        .test(listOf("scenario", "--world", world, "--package", "//zones/tutorial", "$file"))
    assertEquals(0, result.statusCode, result.output)
    assertContains(result.stdout, "Ash@//zones/tutorial:shop> kill shopkeeper")
    assertContains(result.stdout, "(Ash is now in //zones/tutorial:armory)")
    assertContains(result.stdout, "Ash fled")
    assertContains(result.stdout, "Tess@//zones/tutorial:shop> list")
    assertContains(result.stdout, "PASS  test_flee_then_trade")
    assertContains(result.stdout, "1 passed, 0 failed, 0 errors")
  }

  @Test
  fun `scenario fails on an unknown package`() {
    val world = System.getProperty("gups.templateWorld")
    val file = kotlin.io.path.createTempDirectory("gups-scenario").resolve("x.star")
    file.toFile().writeText("def test_x(t):\n    pass\n")
    val result =
      gupsCommand().test(listOf("scenario", "--world", world, "--package", "//zones/nope", "$file"))
    assertEquals(EXIT_TESTS_FAILED, result.statusCode)
    assertContains(result.stderr, "no package //zones/nope")
  }

  @Test
  fun `lint checks template-world packages`() {
    val world = System.getProperty("gups.templateWorld")
    val result = gupsCommand().test(listOf("lint", "--world", world, "//..."))
    assertEquals(0, result.statusCode, result.output)
  }

  @Test
  fun `fuzz runs against the tutorial zone`() {
    val world = System.getProperty("gups.templateWorld")
    val result =
      gupsCommand().test(listOf("fuzz", "--pulses", "10", "--world", world, "//zones/tutorial/..."))
    assertEquals(0, result.statusCode, result.output)
    assertContains(result.stdout, "Fuzz walker completed 10 pulses with 1 bots")
  }

  @Test
  fun `snapshot creates a sanitized database copy including WAL data`() {
    val db = tempDb()
    val pw = PersistWorld(db, P.protos())
    try {
      val acc =
        pw.persistence.accounts.create(
          gups.core.Account(
            id = 0,
            login = "ann",
            pwHash = "secret-hash",
            role = gups.core.Role.PLAYER,
            createdAt = 1L,
          )
        )!!
      val p = pw.login("Ann")
      p.accountId = acc.id
      p.dirty = true
      pw.save()

      // Take the snapshot while pw is still open, so the data sits in world.db-wal.
      val snapFile = java.nio.file.Files.createTempFile("snap-out", ".db").toFile()
      val refused =
        gupsCommand().test(listOf("snapshot", "--db", db.toString(), "--out", snapFile.path))
      assertEquals(1, refused.statusCode, "--sanitized is required")
      assertContains(refused.stderr, "only sanitized snapshots are supported")
      val result =
        gupsCommand()
          .test(listOf("snapshot", "--sanitized", "--db", db.toString(), "--out", snapFile.path))
      assertEquals(0, result.statusCode, result.output)
      assertTrue(snapFile.length() > 0)

      java.sql.DriverManager.getConnection("jdbc:sqlite:${snapFile.path}").use { c ->
        c.createStatement().use { st ->
          fun one(sql: String) =
            st.executeQuery(sql).use {
              it.next()
              it.getString(1)
            }
          assertEquals("0", one("SELECT COUNT(*) FROM accounts"))
          assertEquals("Player1", one("SELECT name FROM players"))
          assertEquals(null, one("SELECT account_id FROM players"))
          assertTrue(one("SELECT COUNT(*) FROM world_entities").toInt() > 0)
        }
      }
    } finally {
      pw.close()
    }
  }

  @Test
  fun `migrate-check skips with warning if snapshot does not exist`() {
    val result = gupsCommand().test(listOf("migrate-check", "--snapshot", "missing_snapshot.db"))
    assertEquals(0, result.statusCode, result.output)
    assertContains(result.stderr, "skipping migration check")
  }

  @Test
  fun `deploy needs the admin token`() {
    val noToken = gupsCommand().test("deploy --commit abc123")
    assertEquals(1, noToken.statusCode)
    assertContains(noToken.stderr, "needs its token")
  }

  @Test
  fun `invite and import-roster work on the world's database`() {
    val root = java.nio.file.Files.createTempDirectory("gups-accounts")
    val roster =
      root.resolve("roster.csv").toFile().apply { writeText("login,role\nann,builder\nbob,\n") }
    val first = gupsCommand().test(listOf("import-roster", roster.path, "--world", root.toString()))
    assertEquals(0, first.statusCode, first.output)
    val lines = first.stdout.trim().lines()
    assertEquals("login,setup_code", lines[0])
    assertEquals(listOf("ann", "bob"), lines.drop(1).map { it.substringBefore(',') })
    assertContains(first.stderr, "2 accounts created, 0 updated, 0 unchanged")
    val again = gupsCommand().test(listOf("import-roster", roster.path, "--world", root.toString()))
    assertEquals("login,setup_code", again.stdout.trim())
    assertContains(again.stderr, "0 accounts created, 0 updated, 2 unchanged")

    roster.writeText("login\nX\n")
    val bad = gupsCommand().test(listOf("import-roster", roster.path, "--world", root.toString()))
    assertEquals(1, bad.statusCode)
    assertContains(bad.stderr, "line 2: 'x'")

    val invite =
      gupsCommand()
        .test(listOf("invite", "--role", "Builder", "--days", "3", "--world", root.toString()))
    assertEquals(0, invite.statusCode, invite.output)
    assertTrue(Regex("[A-Z0-9]{5}-[A-Z0-9]{5}").matches(invite.stdout.trim()), invite.stdout)
    assertEquals(
      1,
      gupsCommand()
        .test(listOf("invite", "--role", "wizard", "--world", root.toString()))
        .statusCode,
    )
  }

  @Test
  fun `import-roster offline with a disabled row prints counts, warning, and writes the flag`() {
    val root = java.nio.file.Files.createTempDirectory("gups-accounts-disabled")
    val roster =
      root.resolve("roster.csv").toFile().apply { writeText("login,disabled\nann,true\n") }
    val res = gupsCommand().test(listOf("import-roster", roster.path, "--world", root.toString()))
    assertEquals(0, res.statusCode, res.output)
    assertContains(res.stderr, "1 accounts created, 0 updated, 0 unchanged, 1 disabled, 0 enabled")
    assertContains(
      res.stderr,
      "Warning: characters already in the world were not quit; use --admin against a running server.",
    )

    val dbFile = root.resolve(".gups").resolve("world.db")
    gups.persist.Database.open(dbFile).use { d ->
      gups.persist.SqliteAccountStore(dbFile, d::write).use { s ->
        val ann = s.byLogin("ann")!!
        assertTrue(ann.disabled)
        assertEquals("roster", ann.disabledReason)
      }
    }
  }

  @Test
  fun `fuzz walker failure outputs rerun command and repro bundle that reproduces in repro`() {
    val root =
      tempWorld(
        mapOf(
          "zones/trap/package.star" to "package(api_version = 1, kind = \"zone\")\n",
          "zones/trap/rooms.star" to
            """
            def _trap(actor, from_exit):
                fail("trap room triggered")

            room("start", name = "Start", flags = ["dead_end_ok"], exits = [exit("north", to = ":death")])
            room("death", name = "Death", flags = ["dead_end_ok"], exits = [exit("south", to = ":start")], on_enter = _trap)
            """
              .trimIndent(),
        )
      )

    val result =
      gupsCommand()
        .test(
          listOf(
            "fuzz",
            "--pulses",
            "50",
            "--seed",
            "1",
            "--world",
            root.toString(),
            "//zones/trap/...",
          )
        )
    assertEquals(1, result.statusCode, result.output)
    assertContains(result.stderr, "gups fuzz failed:")
    assertContains(result.stdout, "Failed at pulse")
    assertContains(result.stdout, "Reproduce with: gups fuzz --seed 1 --pulses")
    assertContains(result.stdout, "Repro bundle:")

    val bundleJson = result.stdout.substringAfter("Repro bundle:\n").trim()
    val bundleFile = root.resolve("bundle.json").toFile().apply { writeText(bundleJson) }

    val reproResult =
      gupsCommand().test(listOf("repro", bundleFile.path, "--world", root.toString()))
    assertEquals(0, reproResult.statusCode, reproResult.output)
    assertContains(reproResult.stdout, "Replaying")
    assertContains(reproResult.stdout, "Reproduced at pulse")
  }

  @Test
  fun `lint --base compares prototypes against git base ref`() {
    val root =
      tempWorld(
        mapOf(
          "zones/z/package.star" to "package(api_version = 1, kind = \"zone\")\n",
          "zones/z/rooms.star" to
            """
            room("start", name = "Start", flags = ["dead_end_ok"], exits = [])
            mob("goblin", name = "Goblin", vitals = vitals(hp = 10), behaviors = [wander(), flee(below = vitals(hp = 20))], version = 1)
            """
              .trimIndent(),
        )
      )
    val git = Git(root)
    git.run("init")
    git.run("config", "user.name", "Test")
    git.run("config", "user.email", "test@test.com")
    git.run("add", "-A")
    git.run("commit", "-m", "initial")

    val r1 = gupsCommand().test(listOf("lint", "--base", "HEAD", "--world", root.toString()))
    assertEquals(0, r1.statusCode, r1.output)

    root
      .resolve("zones/z/rooms.star")
      .toFile()
      .writeText(
        """
        room("start", name = "Start", flags = ["dead_end_ok"], exits = [])
        mob("goblin", name = "Goblin", vitals = vitals(hp = 20), behaviors = [wander(), flee(below = vitals(hp = 20))], version = 1)
        """
          .trimIndent() + "\n"
      )
    val r2 = gupsCommand().test(listOf("lint", "--base", "HEAD", "--world", root.toString()))
    assertEquals(1, r2.statusCode, r2.output)
    assertContains(r2.stdout, "mob prototype declaration changed since base without a version bump")

    root
      .resolve("zones/z/rooms.star")
      .toFile()
      .writeText(
        """
        room("start", name = "Start", flags = ["dead_end_ok"], exits = [])
        mob("goblin", name = "Fierce Goblin", desc = "A scary goblin", vitals = vitals(hp = 10), behaviors = [wander(), flee(below = vitals(hp = 20))], version = 1)
        """
          .trimIndent() + "\n"
      )
    val r3 = gupsCommand().test(listOf("lint", "--base", "HEAD", "--world", root.toString()))
    assertEquals(0, r3.statusCode, r3.output)

    val r4 =
      gupsCommand().test(listOf("lint", "--base", "nonexistent-ref", "--world", root.toString()))
    assertEquals(1, r4.statusCode)
    assertContains(r4.stderr, "failed to load base commit")
  }

  @Test
  fun `serve --prod checks its flags and configuration`() {
    val world = System.getProperty("gups.templateWorld")
    val noTelnet = gupsCommand().test(listOf("serve", "--prod", "--world", world))
    assertEquals(1, noTelnet.statusCode)
    assertContains(noTelnet.stderr, "--telnet :4000")
    assertContains(
      gupsCommand().test(listOf("serve", "--prod", "--port", "4000")).stderr,
      "--port is for --dev",
    )
    val docsPort =
      gupsCommand().test(listOf("serve", "--prod", "--world", world, "--docs-port", "4080"))
    assertEquals(1, docsPort.statusCode)
    assertContains(docsPort.stderr, "--docs-port is for --dev")
    assertContains(
      gupsCommand()
        .test(listOf("serve", "--prod", "--world", world, "--telnet", ":0", "--self-signed"))
        .stderr,
      "--self-signed needs --telnet-tls or --https",
    )
    assertContains(
      gupsCommand().test(listOf("serve", "--prod", "--world", world, "--telnet-tls", ":0")).stderr,
      "TLS needs --tls-cert and --tls-key",
    )
    assertContains(
      gupsCommand().test(listOf("serve", "--dev", "--telnet", ":4000")).stderr,
      "are for --prod",
    )
    assertContains(
      gupsCommand()
        .test(
          listOf("serve", "--prod", "--world", world, "--telnet", ":0", "--shutdown-timeout", "0")
        )
        .stderr,
      "--shutdown-timeout must be at least 1",
    )
    val cfg =
      java.nio.file.Files.createTempFile("server", ".gups").toFile().apply {
        writeText("server(telnet = \":4000\", colour = 1)\n")
      }
    assertContains(
      gupsCommand().test(listOf("serve", "--prod", "--config", cfg.path)).stderr,
      "unexpected argument 'colour'",
    )
    assertEquals("0.0.0.0" to 4000, parseAddress(":4000"))
    assertEquals("::1" to 4001, parseAddress("[::1]:4001"))
    assertEquals("127.0.0.1" to 0, parseAddress("127.0.0.1:0"))
  }
}
