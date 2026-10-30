package gups.net

import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import java.io.StringWriter
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Date
import kotlin.io.path.getLastModifiedTime
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** TLS protocols the listener accepts. */
val TLS_PROTOCOLS = listOf("TLSv1.3", "TLSv1.2")

/** How often the server looks for renewed certificate files. */
val CERT_CHECK_INTERVAL: Duration = Duration.ofHours(1)

/**
 * TLS security configuration for Netty and Ktor listeners.
 *
 * Enforces modern cryptography standards and manages zero-downtime certificate rotation:
 * - **Protocols:** Restricts handshakes strictly to TLS 1.2 and TLS 1.3 ([TLS_PROTOCOLS]); legacy
 *   SSL/TLS versions are rejected.
 * - **Key formats:** Expects PEM-encoded X.509 certificate chains and PEM-encoded PKCS#8 private
 *   keys (compatible with Certbot).
 * - **Hot reloading:** Evaluated periodically ([CERT_CHECK_INTERVAL] = 1 hour). [reloadIfChanged]
 *   inspects file modification timestamps and swaps the Netty `SslContext` without interrupting the
 *   server process or closing active sockets.
 * - **Self-signed generation:** [ServerTls.selfSigned] constructs an ephemeral EC P-256 (secp256r1)
 *   keypair and X.509 certificate valid for 30 days, signed with SHA-256 for local testing.
 */
class ServerTls(val cert: Path, val key: Path) {
  @Volatile private var current: SslContext = build()
  private var stamp: Pair<FileTime, FileTime> = stampNow()

  /** The context new connections use. */
  fun context(): SslContext = current

  /**
   * Rebuilds the context if either file changed on disk since the last load. Returns true if it
   * reloaded. A pair that fails to load keeps the old context and throws.
   */
  @Synchronized
  fun reloadIfChanged(): Boolean {
    val now = stampNow()
    if (now == stamp) return false
    current = build()
    stamp = now
    return true
  }

  private fun stampNow() = cert.getLastModifiedTime() to key.getLastModifiedTime()

  private fun build(): SslContext =
    SslContextBuilder.forServer(cert.toFile(), key.toFile())
      .sslProvider(SslProvider.JDK)
      .protocols(TLS_PROTOCOLS)
      .build()

  companion object {
    /**
     * Writes a throwaway self-signed certificate for [hosts] into [dir] as `cert.pem` and `key.pem`
     * (`gups serve --prod --self-signed`), valid for 30 days. EC P-256, signed with SHA-256, with
     * each host as a subject alternative name.
     */
    fun selfSigned(
      dir: Path,
      hosts: List<String> = listOf("localhost", "127.0.0.1"),
      now: Instant = Instant.now(),
    ): ServerTls {
      Files.createDirectories(dir)
      val pair =
        KeyPairGenerator.getInstance("EC")
          .apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }
          .generateKeyPair()
      val name = X500Name("CN=${hosts.first()}, O=GUPS self-signed")
      val sans =
        hosts.map { h ->
          if (h.all { it.isDigit() || it == '.' } || ':' in h) GeneralName(GeneralName.iPAddress, h)
          else GeneralName(GeneralName.dNSName, h)
        }
      val holder =
        JcaX509v3CertificateBuilder(
            name,
            BigInteger(64, SecureRandom()),
            Date.from(now.minus(Duration.ofHours(1))),
            Date.from(now.plus(Duration.ofDays(30))),
            name,
            pair.public,
          )
          .addExtension(Extension.subjectAlternativeName, false, GeneralNames(sans.toTypedArray()))
          .build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private))
      val certificate = JcaX509CertificateConverter().getCertificate(holder)
      val cert = dir.resolve("cert.pem")
      val key = dir.resolve("key.pem")
      Files.writeString(cert, pem(certificate))
      Files.writeString(key, pem(JcaPKCS8Generator(pair.private, null).generate()))
      return ServerTls(cert, key)
    }

    private fun pem(o: Any): String =
      StringWriter().also { w -> JcaPEMWriter(w).use { it.writeObject(o) } }.toString()
  }
}
