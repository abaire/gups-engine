package gups.script

import com.google.common.collect.ImmutableMap
import gups.core.EngineInfo
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
            return try {
                Mutability.create(FILE).use { mu ->
                    val thread = StarlarkThread(mu, SEMANTICS)
                    thread.setMaxExecutionSteps(100_000)
                    val module = Module.withPredeclared(SEMANTICS, ImmutableMap.builder<String, Any>().also { Starlark.addMethods(it, reader) }.build())
                    Starlark.execFile(ParserInput.fromString(path.readText(), FILE), FILE_OPTIONS, module, thread)
                }
                val m = reader.result ?: return WorldModule() to LoadError("", "module", "$FILE must call module(...)", FILE)
                m to null
            } catch (e: SyntaxError.Exception) {
                val err = e.errors().first()
                WorldModule() to LoadError("", "syntax", err.message(), FILE, err.location().line(), err.location().column())
            } catch (e: EvalException) {
                val loc = innermost(e)
                WorldModule() to LoadError("", "module", e.message ?: "error", FILE, loc?.line(), loc?.column())
            }
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
            result = WorldModule(
                engine = kw["engine"]?.let { str(it, kw.what("engine")) },
                apiVersion = kw["api_version"]?.let { int(it, kw.what("api_version")) } ?: EngineInfo.API_VERSION,
                checkpointMinutes = minutes,
            )
            kw.finish()
            return Starlark.NONE
        }
    }
}
