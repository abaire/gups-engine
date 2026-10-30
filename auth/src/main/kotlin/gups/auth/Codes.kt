package gups.auth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Generation, normalization, and hashing of one-time registration invite and account setup codes.
 *
 * ## Code Format & Alphabet
 * - Generated as 10 characters grouped into two 5-character blocks separated by a dash:
 *   `XXXXX-XXXXX`.
 * - Characters are selected from a 29-character alphabet without look-alikes:
 *   `23456789ABCDEFGHJKMNPQRSTWXYZ`. It leaves out 0, 1, I, L, O, U and V.
 *
 * ## Normalization & Storage
 * - Normalization is forgiving: lowercase letters are converted to uppercase, and all whitespace
 *   and dashes are stripped before length and character verification.
 * - Stored in the database (`invites.code_hash`) strictly as the lowercase hexadecimal SHA-256
 *   digest of the canonical 10-character string; raw codes are never persisted.
 */
object Codes {
  /** The 29 characters codes are drawn from. */
  const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTWXYZ"
  /** Canonical length of an unformatted code (10 characters). */
  const val LENGTH = 10

  private val random = SecureRandom()

  fun generate(rng: SecureRandom = random): String {
    val chars = CharArray(LENGTH) { ALPHABET[rng.nextInt(ALPHABET.length)] }
    return String(chars, 0, 5) + "-" + String(chars, 5, 5)
  }

  /** The canonical form of a typed code, or null if it can't be one. */
  fun normalize(input: String): String? {
    val s = input.uppercase().filterNot { it == '-' || it.isWhitespace() }
    return s.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
  }

  /** The stored hash of a code, as lowercase hex. [code] may be typed loosely. */
  fun hash(code: String): String {
    val canonical = requireNotNull(normalize(code)) { "not a code: $code" }
    return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") {
      "%02x".format(it)
    }
  }
}
