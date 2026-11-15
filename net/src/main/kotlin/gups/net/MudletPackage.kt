package gups.net

import java.io.ByteArrayOutputStream
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The Mudlet mapper script, packaged as a `.mpackage` Mudlet can install. */
class MudletPackage(script: ByteArray) {
  /** First 16 hex digits of a SHA-256 over the script. */
  val version: String = shortHash(MessageDigest.getInstance("SHA-256").digest(script))

  /** The `.mpackage` zip: `config.lua`, then `gups-mapper.xml`. */
  val bytes: ByteArray = buildZip(script, version)

  companion object {
    const val NAME = "gups-mapper"
    const val PATH = "/mudlet/$NAME.mpackage"
    private val FIXED_ENTRY_TIME = LocalDateTime.of(1980, 1, 1, 0, 0)
    private val FIXED_ENTRY_FILE_TIME = FileTime.from(FIXED_ENTRY_TIME.toInstant(ZoneOffset.UTC))

    /** The package of `gups/mudlet/mapper.lua` on [loader], or null when it isn't there. */
    fun fromClasspath(loader: ClassLoader = MudletPackage::class.java.classLoader): MudletPackage? =
      loader.getResourceAsStream("gups/mudlet/mapper.lua")?.use { MudletPackage(it.readBytes()) }

    private fun buildZip(script: ByteArray, version: String): ByteArray {
      val out = ByteArrayOutputStream()
      ZipOutputStream(out).use { zos ->
        writeEntry(zos, "config.lua", configLua(version))
        writeEntry(zos, "$NAME.xml", packageXml(script))
      }
      return out.toByteArray()
    }

    private fun writeEntry(zos: ZipOutputStream, name: String, content: String) {
      val entry =
        ZipEntry(name).apply {
          setTimeLocal(FIXED_ENTRY_TIME)
          setLastModifiedTime(FIXED_ENTRY_FILE_TIME)
        }
      zos.putNextEntry(entry)
      zos.write(content.toByteArray(Charsets.UTF_8))
      zos.closeEntry()
    }

    private fun configLua(version: String): String =
      """
      |mpackage = [[$NAME]]
      |author = [[GUPS]]
      |title = [[GUPS mapper]]
      |description = [[Draws the map from the server's GMCP Room.Info as you explore.]]
      |version = [[$version]]
      |"""
        .trimMargin()

    private fun packageXml(script: ByteArray): String {
      val escaped =
        String(script, Charsets.UTF_8)
          .replace("&", "&amp;")
          .replace("<", "&lt;")
          .replace(">", "&gt;")
          .replace("\"", "&quot;")
      return """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<!DOCTYPE MudletPackage>
        |<MudletPackage version="1.001">
        |    <ScriptPackage>
        |        <Script isActive="yes" isFolder="no">
        |            <name>$NAME</name>
        |            <packageName>$NAME</packageName>
        |            <script>$escaped</script>
        |            <eventHandlerList />
        |        </Script>
        |    </ScriptPackage>
        |</MudletPackage>
        |"""
        .trimMargin()
    }
  }
}
