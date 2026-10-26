package gups.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Hashes and checks passwords. Both calls are slow by design, so they never run on the world thread
 * (SPEC §2.5).
 */
interface PasswordHasher {
  /** A self-describing hash of [password], such as a PHC string. */
  fun hash(password: String): String

  /** True if [password] matches [hash]. A malformed hash never matches. */
  fun verify(password: String, hash: String): Boolean
}

/**
 * Argon2id with Bouncy Castle's pure-Java generator (SPEC §5.12): by default m = 64 MiB, t = 3, p =
 * 1, a 16-byte salt and a 32-byte hash, stored as the PHC string
 * `$argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>` (unpadded base64). [verify] reads the parameters
 * from the hash, so changing the defaults never locks anyone out.
 */
class Argon2Hasher(
  val memoryKiB: Int = 65536,
  val iterations: Int = 3,
  val parallelism: Int = 1,
  private val random: SecureRandom = SecureRandom(),
) : PasswordHasher {
  override fun hash(password: String): String {
    val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
    val out = derive(password, salt, memoryKiB, iterations, parallelism, HASH_BYTES)
    return "\$argon2id\$v=19\$m=$memoryKiB,t=$iterations,p=$parallelism\$${B64.encodeToString(salt)}\$${B64.encodeToString(out)}"
  }

  override fun verify(password: String, hash: String): Boolean {
    val phc = Phc.parse(hash) ?: return false
    val actual =
      derive(password, phc.salt, phc.memoryKiB, phc.iterations, phc.parallelism, phc.hash.size)
    return MessageDigest.isEqual(actual, phc.hash)
  }

  /** The parts of an Argon2id PHC string. */
  class Phc(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val salt: ByteArray,
    val hash: ByteArray,
  ) {
    companion object {
      private val PARAMS = Regex("""m=(\d{1,8}),t=(\d{1,4}),p=(\d{1,3})""")

      /** Reads `$argon2id$v=19$m=…,t=…,p=…$<salt>$<hash>`, or returns null. */
      fun parse(s: String): Phc? {
        val parts = s.split('$')
        if (
          parts.size != 6 || parts[0].isNotEmpty() || parts[1] != "argon2id" || parts[2] != "v=19"
        )
          return null
        val (mem, t, p) = PARAMS.matchEntire(parts[3])?.destructured ?: return null
        return runCatching {
            Phc(mem.toInt(), t.toInt(), p.toInt(), B64D.decode(parts[4]), B64D.decode(parts[5]))
          }
          .getOrNull()
          ?.takeIf {
            it.memoryKiB >= 8 * it.parallelism &&
              it.iterations >= 1 &&
              it.parallelism >= 1 &&
              it.hash.size >= 4
          }
      }
    }
  }

  companion object {
    const val SALT_BYTES = 16
    const val HASH_BYTES = 32
    private val B64 = Base64.getEncoder().withoutPadding()
    private val B64D = Base64.getDecoder()

    private fun derive(
      password: String,
      salt: ByteArray,
      memoryKiB: Int,
      iterations: Int,
      parallelism: Int,
      length: Int,
    ): ByteArray {
      val params =
        Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
          .withVersion(Argon2Parameters.ARGON2_VERSION_13)
          .withMemoryAsKB(memoryKiB)
          .withIterations(iterations)
          .withParallelism(parallelism)
          .withSalt(salt)
          .build()
      val out = ByteArray(length)
      Argon2BytesGenerator()
        .apply { init(params) }
        .generateBytes(password.toByteArray(Charsets.UTF_8), out)
      return out
    }
  }
}
