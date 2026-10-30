package io.github.nideta231.hermesremote.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Tailscale Funnel: the PC's bridge on the public internet, for phones the user allowed.
 *
 * Over Funnel the bridge wants three things: the device token, this phone's Funnel key (a second
 * secret it got when the user allowed it from home or over Tailscale) and, for the terminal, PC
 * pages and notes, a 5 minute unlock ticket. The ticket is bought by signing the bridge's challenge
 * with [FunnelKeys], a hardware key that only signs right after the user's fingerprint or PIN.
 */
object Funnel {
    const val KEY_HEADER = "X-Funnel-Key"
    const val UNLOCK_HEADER = "X-Funnel-Unlock"

    /** A Funnel address: https on a *.ts.net name (Tailscale's public certificate, no pin). */
    fun isFunnelUrl(url: String): Boolean = runCatching {
        val u = java.net.URI(url.trim())
        u.scheme == "https" && (u.host ?: "").lowercase().endsWith(".ts.net")
    }.getOrDefault(false)

    /** What the phone signs to unlock: bound to this challenge and this device (same text as the bridge). */
    fun unlockMessage(nonce: String, deviceId: String): ByteArray = "hermes-remote-funnel-unlock:$nonce:$deviceId".toByteArray()
}

/** The unlock ticket for the PC in use (process-wide; cleared when switching PCs). */
object FunnelTicket {
    @Volatile private var ticket: String? = null
    @Volatile private var until: Long = 0

    fun set(t: String, seconds: Int, now: Long = System.currentTimeMillis()) { ticket = t; until = now + (seconds - 10).coerceAtLeast(1) * 1000L }
    fun clear() { ticket = null; until = 0 }
    fun current(now: Long = System.currentTimeMillis()): String? = ticket?.takeIf { now < until }
    fun valid(now: Long = System.currentTimeMillis()): Boolean = current(now) != null
}

/**
 * This phone's Funnel signing key in the Android Keystore: EC P-256, never leaves the hardware, and
 * signs only within a few seconds of the user confirming their fingerprint or screen lock.
 */
object FunnelKeys {
    private const val AUTH_SECONDS = 10

    fun alias(pcId: String) = "hermes_remote_funnel_$pcId"

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** The public key (X.509 SubjectPublicKeyInfo, base64) the bridge stores; made on first use. */
    fun publicKey(alias: String): String {
        val ks = store()
        val cert = ks.getCertificate(alias) ?: run { create(alias); store().getCertificate(alias) }
        return android.util.Base64.encodeToString(cert.publicKey.encoded, android.util.Base64.NO_WRAP)
    }

    private fun create(alias: String) {
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                else @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS)
            }
            .build())
        gen.generateKeyPair()
    }

    /** Signs [message] (DER ECDSA, base64). Throws UserNotAuthenticatedException without a fresh fingerprint/PIN. */
    fun sign(alias: String, message: ByteArray): String {
        val key = store().getKey(alias, null) as? PrivateKey ?: throw IllegalStateException("This phone is not allowed over the internet yet")
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(key)
        s.update(message)
        return android.util.Base64.encodeToString(s.sign(), android.util.Base64.NO_WRAP)
    }

    fun delete(alias: String) { runCatching { store().deleteEntry(alias) } }
}
