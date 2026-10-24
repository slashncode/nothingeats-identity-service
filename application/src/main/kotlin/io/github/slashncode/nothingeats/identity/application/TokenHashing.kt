package io.github.slashncode.nothingeats.identity.application

import java.security.MessageDigest

/**
 * Tokens sent to users (verification links, refresh tokens) are stored as SHA-256 hashes.
 * They carry 256 random bits, so a fast hash is enough; a database leak reveals no usable token.
 */
object TokenHashing {
    @OptIn(ExperimentalStdlibApi::class)
    fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).toHexString()
}
