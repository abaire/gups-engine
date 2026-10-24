package gups.script

import com.google.common.collect.ImmutableMap
import gups.core.EngineInfo
import gups.core.Label
import gups.core.PULSE_MS
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Module
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.syntax.ParserInput
import net.starlark.java.syntax.SyntaxError
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * The world's metadata from `MODULE.gups` at the world root (SPEC §2.3): the engine and
 * `api_version` it pins, and server settings such as the checkpoint interval (D-107).
 * Changing it needs a restart; hot reload never reads it (SPEC §5.10).
 */
data class WorldModule(
    /** The engine version the world was written for, if pinned. */
    val engine: String? = null,
    val apiVersion: Int = EngineInfo.API_VERSION,
    /** Minutes between routine checkpoints. */
    val checkpointMinutes: Int = DEFAULT_CHECKPOINT_MINUTES,
    /** Where new characters start (M6 design §3.4); null means the tutorial gate, or else the first room (D-138). */
    val startRoom: Label? = null,
) {
    /** The checkpoint interval in pulses. */
    val checkpointPulses: Long get() = checkpointMinutes * 60_000L / PULSE_MS

    /** Problems that should stop the server, such as a pinned `api_version` this engine lacks. */
    fun problems(): List<String> = buildList {
        if (apiVersion != EngineInfo.API_VERSION) {
            add("MODULE.gups pins api_version $apiVersion, but this engine supports only api_version ${EngineInfo.API_VERSION}. See CHANGELOG.md and MIGRATING.md.")
        }
    }

    /** Things worth a warning, such as a different pinned engine version. */
    fun warnings(): List<String> = buildList {
        if (engine != null && engine != EngineInfo.version) add("MODULE.gups pins engine $engine; this is engine ${EngineInfo.version}.")
    }

    companion object {
        const val FILE = "MODULE.gups"
        const val DEFAULT_CHECKPOINT_MINUTES = 30

        /** Reads `MODULE.gups`; a world without one gets the defaults. Errors are [LoadError]s. */
        fun read(dir: WorldDir): Pair<WorldModule, LoadError?> {
            val path = dir.root.resolve(FILE)
            if (!path.isRegularFile()) return WorldModule() to null
            val reader = Reader()
            execSettings(path, FILE, "module", reader)?.let { return WorldModule() to it }
            val m = reader.result ?: return WorldModule() to LoadError("", "module", "$FILE must call module(...)", FILE)
            return m to null
        }
    }

    /** The `module()` builtin: called exactly once. */
    private class Reader {
        var result: WorldModule? = null

        @StarlarkMethod(name = "module", doc = "Declares the world's metadata.", extraKeywords = Param(name = "kwargs"))
        fun module(kwargs: Dict<String, Any>): Any {
            if (result != null) fail("module() is called more than once")
            val kw = Kw("module", kwargs)
            val minutes = kw["checkpoint_minutes"]?.let { int(it, kw.what("checkpoint_minutes")) } ?: DEFAULT_CHECKPOINT_MINUTES
            if (minutes < 1) fail("module(checkpoint_minutes): must be at least 1")
            val start = kw["start_room"]?.let { v ->
                val text = str(v, kw.what("start_room"))
                runCatching { Label(text) }.getOrElse { fail("module(start_room): '$text' is not a label such as //zones/tutorial:gate") }
            }
            result = WorldModule(
                engine = kw["engine"]?.let { str(it, kw.what("engine")) },
                apiVersion = kw["api_version"]?.let { int(it, kw.what("api_version")) } ?: EngineInfo.API_VERSION,
                checkpointMinutes = minutes,
                startRoom = start,
            )
            kw.finish()
            return Starlark.NONE
        }
    }
}

/**
 * Runs a settings file such as `MODULE.gups` with [reader]'s `@StarlarkMethod`s predeclared.
 * Returns the error, if any, as a [LoadError] of [kind].
 */
internal fun execSettings(path: java.nio.file.Path, name: String, kind: String, reader: Any): LoadError? = try {
    Mutability.create(name).use { mu ->
        val thread = StarlarkThread(mu, SEMANTICS)
        thread.setMaxExecutionSteps(100_000)
        val module = Module.withPredeclared(SEMANTICS, ImmutableMap.builder<String, Any>().also { Starlark.addMethods(it, reader) }.build())
        Starlark.execFile(ParserInput.fromString(path.readText(), name), FILE_OPTIONS, module, thread)
    }
    null
} catch (e: SyntaxError.Exception) {
    val err = e.errors().first()
    LoadError("", "syntax", err.message(), name, err.location().line(), err.location().column())
} catch (e: EvalException) {
    val loc = innermost(e)
    LoadError("", kind, e.message ?: "error", name, loc?.line(), loc?.column())
}
