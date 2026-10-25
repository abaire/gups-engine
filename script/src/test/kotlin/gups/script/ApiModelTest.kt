package gups.script

import gups.core.EngineInfo
import net.starlark.java.annot.StarlarkBuiltin
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiModelTest {
    private val model = ScriptApi.model(harness = emptyList())
    private val scriptExamples = DocExamples.ALL - setOf("diagnostic", "p", "t", "w")

    private fun load(files: Map<String, String>, vararg pkgs: String = arrayOf("zones/z")): LoadResult =
        ScriptLoader(WorldDir(tempWorld(files)), ScriptLimits.HARNESS).load(pkgs.toList())

    @Test
    fun `model covers every package global, builtin, method, hook, rules export and modifier point with docs`() {
        val builtins = findBuiltinClasses(Ctx::class.java)
        assertEquals(ScriptApi.BUILTIN_CLASSES.toSet(), builtins.toSet(), "ScriptApi.BUILTIN_CLASSES is out of date")
        val problems = ScriptApi.validate(model, builtins)
        assertTrue(problems.isEmpty(), "API model completeness failed:\n" + problems.joinToString("\n"))
        // This model has no harness types (they live in :harness), so leave out their examples.
        val exampleProblems = DocExamples.problems(model, scriptExamples)
        assertTrue(exampleProblems.isEmpty(), exampleProblems.joinToString("\n"))
        assertEquals(EngineInfo.API_VERSION, model.apiVersion)
        assertEquals(EngineInfo.version, model.engineVersion)
    }

    @Test
    fun `DocExamples problems reports unknown symbols, blank examples and missing required examples`() {
        assertEquals(emptyList(), DocExamples.problems(model, scriptExamples))
        val broken = scriptExamples - "room" + ("room.nope" to DocExample(pkg = "x = 1\n")) + ("heal" to DocExample())
        val problems = DocExamples.problems(model, broken)
        assertTrue(problems.any { "'room' needs an example" in it }, problems.joinToString("\n"))
        assertTrue(problems.any { "'room.nope', which is not an api.json symbol" in it }, problems.joinToString("\n"))
        assertTrue(problems.any { "'heal' has neither pkg nor test" in it }, problems.joinToString("\n"))
    }

    @Test
    fun `validate catches missing builtins, missing globals, blank docs, unknown ctx fields and clashing symbols`() {
        @StarlarkBuiltin(name = "undocumented_widget", doc = "A widget.")
        class UndocumentedWidget

        fun problems(m: ScriptApiModel = model, classes: List<Class<*>> = ScriptApi.BUILTIN_CLASSES) = ScriptApi.validate(m, classes)
        assertEquals(emptyList(), problems())

        assertTrue(problems(classes = ScriptApi.BUILTIN_CLASSES + UndocumentedWidget::class.java).any { "undocumented_widget" in it })

        val blankHookDoc = model.copy(hooks = model.hooks.mapIndexed { i, h -> if (i == 0) h.copy(doc = "   ") else h })
        assertTrue(problems(blankHookDoc).any { "blank doc string" in it })

        val blankParamDoc = model.copy(
            declarations = model.declarations.map { d ->
                if (d.name == "room") d.copy(params = d.params.mapIndexed { i, p -> if (i == 0) p.copy(doc = "") else p }) else d
            },
        )
        assertTrue(problems(blankParamDoc).any { "declaration 'room'" in it && "blank doc string" in it })

        val blankMethodDoc = model.copy(ctx = model.ctx.copy(methods = model.ctx.methods.mapIndexed { i, m -> if (i == 0) m.copy(doc = "") else m }))
        assertTrue(problems(blankMethodDoc).any { "type 'ctx'" in it && "blank doc string" in it })

        val blankMethodParamDoc = model.copy(
            effects = model.effects.map { e -> if (e.name == "damage") e.copy(params = e.params.map { it.copy(doc = "") }) else e },
        )
        assertTrue(problems(blankMethodParamDoc).any { "effect 'damage' param 'dice' has a blank doc string" in it })

        val missingGlobal = model.copy(declarations = model.declarations.filterNot { it.name == "region" })
        assertTrue(problems(missingGlobal).any { "PACKAGE_GLOBALS entry 'region' is missing" in it })

        val unknownHookField = model.copy(hooks = model.hooks.map { if (it.name == "on_say") it.copy(ctxFields = it.ctxFields + "speech") else it })
        assertTrue(problems(unknownHookField).any { "hook 'on_say' lists ctx field 'speech'" in it })

        val unknownRulesField = model.copy(
            rules = model.rules.copy(exports = model.rules.exports.map { if (it.name == "to_hit") it.copy(ctxFields = it.ctxFields + "speed") else it }),
        )
        assertTrue(problems(unknownRulesField).any { "rules export 'to_hit' lists ctx field 'speed'" in it })

        val missingExport = model.copy(rules = model.rules.copy(exports = model.rules.exports.filterNot { it.name == "regen" }))
        assertTrue(problems(missingExport).any { "RulesExport.REGEN ('regen') is missing" in it })

        val clash = model.copy(views = model.views + model.views.first())
        assertTrue(problems(clash).any { "symbol 'view:${model.views.first().name}' names 2 entries" in it })
    }

    @Test
    fun `api json is canonical and deterministic across runs`() {
        val first = model.toJson()
        assertEquals(first, ScriptApi.model(harness = emptyList()).toJson())
        assertFalse('\r' in first, "api.json must use LF line endings")
        assertTrue(first.endsWith("}\n"))
        assertContains(first, "\"api_version\": ${EngineInfo.API_VERSION}")
        assertContains(first, "\"engine_version\": \"${EngineInfo.version}\"")
        val symbols = model.symbols().toSet()
        for (s in listOf("room", "room.size", "ALL", "hook:on_wake", "ctx.act_move", "ctx.apply_affect.name", "view:room.exits", "damage.dice", "rules:to_hit", "modifier:to_hit", "rules_ctx.weapon", "module.start_room")) {
            assertTrue(s in symbols, "missing symbol $s")
        }
    }

    // ---- Declarations accept exactly their spec's parameters ---------------------------------

    /** How to call a declaration with only its required arguments plus [extra], and get what it made. */
    private class Case(val pkg: String, val source: (extra: String?) -> String, val made: (LoadResult) -> Any?)

    private fun String?.after() = this?.let { ", $it" } ?: ""

    private fun String?.only() = this ?: ""

    private val zone = "zones/z"
    private fun LoadResult.pkg(path: String) = packages.getValue(path)

    private val cases: Map<String, Case> = mapOf(
        "package" to Case("lib", { "" }) { r ->
            r.pkg("lib").let { listOf(it.kind, it.title, it.resetPulses, it.sharedNamespaces, it.modifiers, it.expectedTombstones, it.migrateNoop) }
        },
        "room" to Case(zone, { "room(\"r\"${it.after()})" }) { it.pkg(zone).roomList.single() },
        "exit" to Case(zone, { "room(\"r\", exits = [exit(\"north\", \":r\"${it.after()})])" }) { it.pkg(zone).roomList.single().exits.single() },
        "door" to Case(zone, { "room(\"r\", exits = [exit(\"north\", \":r\", door = door(${it.only()}))])" }) {
            it.pkg(zone).roomList.single().exits.single().door
        },
        "spawn" to Case(zone, { "mob(\"m\")\nroom(\"r\", spawns = [spawn(\":m\"${it.after()})])" }) { it.pkg(zone).roomList.single().spawns.single() },
        "loot" to Case(zone, { "item(\"i\")\nmob(\"m\", loot = [loot(\":i\"${it.after()})])" }) { it.pkg(zone).mobList.single().loot.single() },
        "container" to Case(zone, { "item(\"i\", container = container(${it.only()}))" }) { it.pkg(zone).itemList.single().container },
        "mob" to Case(zone, { "mob(\"m\"${it.after()})" }) { it.pkg(zone).mobList.single() },
        "item" to Case(zone, { "item(\"i\"${it.after()})" }) { it.pkg(zone).itemList.single() },
        "skill" to Case(zone, { "skill(\"s\"${it.after()})" }) { it.pkg(zone).skillList.single() },
        "region" to Case("lib", { "region(\"g\"${it.after()})" }) { it.pkg("lib").regionList.single() },
        "global_command" to Case("lib", { "def _f(ctx):\n    pass\n\nglobal_command(\"wave\", _f${it.after()})" }) { it.pkg("lib").commandList.single() },
        "struct" to Case(zone, { "S = struct(${it.only()})" }) { null },
    )

    /** Loads [spec]'s declaration with [extra] as one more argument; returns what it made, or the load report. */
    private fun declare(spec: DeclSpec, extra: String?): Result<Any?> {
        when (spec.name) {
            "module" -> {
                val root = Files.createTempDirectory("gups-world")
                root.resolve(WorldModule.FILE).writeText("module(${extra.only()})\n")
                val (m, err) = WorldModule.read(WorldDir(root))
                return if (err == null) Result.success(m) else Result.failure(AssertionError(err.message))
            }
            "server" -> {
                val file = Files.createTempFile("server", ".gups")
                file.writeText("server(${extra.only()})\n")
                val (c, err) = ServerConfig.read(file)
                return if (err == null) Result.success(c) else Result.failure(AssertionError(err.message))
            }
        }
        val case = cases.getValue(spec.name)
        val files = if (spec.name == "package") {
            mapOf("lib/package.star" to "package(api_version = 1, kind = \"lib\"${extra.after()})\n")
        } else {
            val header = if (case.pkg == "lib") libPkg() else zonePkg()
            mapOf("${case.pkg}/package.star" to header, "${case.pkg}/a.star" to case.source(extra))
        }
        val r = load(files, case.pkg)
        return if (r.ok) Result.success(case.made(r)) else Result.failure(AssertionError(r.report()))
    }

    private val allSpecs = DeclarationBuiltins.DECLARATION_SPECS + listOf(WorldModule.SPEC, ServerConfig.SPEC)

    @Test
    fun `every declaration has a test case`() {
        assertEquals(allSpecs.map { it.name }.toSet() - setOf("module", "server"), cases.keys)
    }

    @Test
    fun `every declaration accepts each optional keyword parameter and rejects others with a suggestion`() {
        // Every call also checks that the builtin reads every parameter of its spec (DeclSpec.bind).
        for (spec in allSpecs) {
            assertTrue(declare(spec, null).isSuccess, "${spec.name}() with only its required arguments: ${declare(spec, null)}")
            for (p in spec.params.filter { it.keyword && !it.required }) {
                val r = declare(spec, "${p.name} = None")
                assertTrue(r.isSuccess, "${spec.name}(${p.name} = None): ${r.exceptionOrNull()?.message}")
            }
            if (spec.extraKeywords) continue
            val bogus = declare(spec, "zzz_unknown = None")
            assertContains(bogus.exceptionOrNull()?.message.orEmpty(), "${spec.name}(): unexpected argument 'zzz_unknown'")
            val typo = spec.params.firstOrNull { it.keyword && it.name.length > 4 } ?: continue
            val misspelt = typo.name.dropLast(1)
            assertContains(
                declare(spec, "$misspelt = None").exceptionOrNull()?.message.orEmpty(),
                "unexpected argument '$misspelt' (did you mean '${typo.name}'?)",
            )
        }
    }

    /** Defaults that describe a computed value instead of giving one. */
    private val descriptive = setOf("id with spaces", "words of name", "package path")

    @Test
    fun `each documented default is what the declaration really uses`() {
        val wrong = mutableListOf<String>()
        for (spec in allSpecs) {
            val plain = declare(spec, null).getOrThrow()
            for (p in spec.params) {
                val default = p.default ?: continue
                if (default in descriptive || default == "None") continue
                val explicit = declare(spec, "${p.name} = $default")
                if (explicit.getOrNull() != plain) wrong += "${spec.name}(${p.name}): documented default $default, ${explicit.exceptionOrNull()?.message ?: "but omitting it differs"}"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `required parameters say what they accept`() {
        val r = load(mapOf("zones/z/package.star" to "package(api_version = 1)\n"))
        assertContains(r.report(), "package(): kind is required (zone, lib, rules or skills)")
    }
}
