package gups.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Password hashing and verification contract.
 *
 * Both hashing and verification operations are computationally intensive by design to resist
 * brute-force attacks; these operations must never execute on the single world simulation thread
 * and should run on background worker threads.
 */
interface PasswordHasher {
  /** A self-describing hash of [password], formatted as an Argon2id PHC string. */
  fun hash(password: String): String

  /** True if [password] matches [hash]. A malformed hash never matches. */
  fun verify(password: String, hash: String): Boolean
}

/**
 * Argon2id password hasher utilizing Bouncy Castle's pure-Java cryptographic implementation.
 *
 * Default configuration follows OWASP password storage recommendations:
 * - **Memory:** 64 MiB (65,536 KiB, [memoryKiB]).
 * - **Iterations:** 3 passes ([iterations], t = 3).
 * - **Parallelism:** 1 thread lane ([parallelism], p = 1).
 * - **Salt:** 16 cryptographically secure random bytes ([SALT_BYTES]).
 * - **Output length:** 32-byte derived key ([HASH_BYTES]).
 * - **Encoding:** PHC string format: `$argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>` using unpadded
 *   Base64.
 *
 * [verify] extracts parameters directly from the PHC string header, enabling the engine to adjust
 * future default cost parameters without invalidating existing stored hashes.
 *
 * To defend against timing attacks and username enumeration, login handlers verify against a
 * precomputed dummy hash when an account does not exist or has no password set, ensuring constant
 * verification latency.
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
