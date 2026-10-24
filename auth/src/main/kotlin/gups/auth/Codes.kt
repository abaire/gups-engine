package gups.auth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Invite and setup codes (M6 design §3.3, D-133): 10 characters from an alphabet without
 * look-alikes (no 0/O, 1/I/L, U/V), shown as `XXXXX-XXXXX`. Typing them is forgiving: case,
 * spaces and dashes don't matter. Only a code's SHA-256 is stored (SPEC §3.6).
 */
object Codes {
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTWXYZ"
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
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
