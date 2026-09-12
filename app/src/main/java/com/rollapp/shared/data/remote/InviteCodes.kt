package com.rollapp.shared.data.remote

import com.rollapp.shared.core.Limits
import java.security.SecureRandom

/**
 * Invite codes are short enough to read aloud but drawn from a cryptographic RNG,
 * so they cannot be walked or guessed in sequence.
 *
 * The alphabet omits I, O, S and the digits 0, 1, 5 — the characters people confuse
 * when copying a code off someone else's screen. That leaves 30 symbols; over six
 * places that is 7.3e8 codes, and a wrong guess only ever resolves to a group that
 * does not exist.
 */
object InviteCodes {

    private const val ALPHABET = "ABCDEFGHJKLMNPQRTUVWXYZ2346789"
    private val random = SecureRandom()

    fun generate(length: Int = Limits.INVITE_CODE_LENGTH): String =
        buildString(length) {
            repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

    /** Normalises what a user typed: case and the separators people add by habit. */
    fun normalise(input: String): String =
        input.trim()
            .uppercase()
            .filterNot { it == ' ' || it == '-' || it == '_' }

    fun isPlausible(code: String): Boolean =
        code.length == Limits.INVITE_CODE_LENGTH && code.all { it in ALPHABET }

    /** Extracts the code from `https://wewere.vercel.app/join/GA7X2M` or `roll://join/GA7X2M`. */
    fun fromLink(link: String): String? {
        val candidate = link.trim().trimEnd('/').substringAfterLast('/')
        return normalise(candidate).takeIf { isPlausible(it) }
    }
}
