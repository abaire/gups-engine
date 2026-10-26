package gups.core

import java.util.Properties

/** Version identity of the engine. The engine version and [API_VERSION] are independent. */
object EngineInfo {
  /** The single script `api_version` this engine supports. */
  const val API_VERSION = 1

  /** The engine's semver version, stamped in at build time. */
  val version: String by lazy {
    val props = Properties()
    EngineInfo::class.java.getResourceAsStream("engine.properties")?.use(props::load)
    props.getProperty("engine.version") ?: "unknown"
  }
}
