package com.kitefoldlabs.bridle.core.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Makes the JCA provider named "BC" the full BouncyCastle that ships with Bridle.
 *
 * sshj looks algorithms up by provider *name* ("BC"). On Android that name belongs to the
 * platform's stripped, repackaged BouncyCastle, which has no Ed25519 and none of the ECDSA or
 * Curve25519 pieces sshj needs, and sshj's own registration keeps it because a provider with that
 * name already exists. [install] replaces it, before any sshj use.
 *
 * The full provider takes the platform one's **position** rather than position 1: Android puts
 * `AndroidKeyStoreBCWorkaround` just above "BC" so that signatures and ciphers on Keystore keys
 * (which pick a provider when they are initialised) reach the Keystore before BouncyCastle, and
 * Conscrypt stays the default for everything else.
 */
public object BouncyCastleRegistration {
    /** The provider name sshj uses ([BouncyCastleProvider.PROVIDER_NAME]). */
    public const val NAME: String = BouncyCastleProvider.PROVIDER_NAME

    /**
     * Ensures "BC" is the full BouncyCastle provider. Idempotent and thread-safe; returns true if
     * it changed the provider list.
     */
    @Synchronized
    public fun install(): Boolean {
        if (Security.getProvider(NAME) is BouncyCastleProvider) return false
        val index = Security.getProviders().indexOfFirst { it.name == NAME }
        val full = BouncyCastleProvider()
        if (index < 0) {
            Security.addProvider(full)
        } else {
            Security.removeProvider(NAME)
            Security.insertProviderAt(full, index + 1) // positions are 1-based
        }
        return true
    }
}
