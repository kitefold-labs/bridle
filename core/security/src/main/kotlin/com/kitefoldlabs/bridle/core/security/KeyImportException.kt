package com.kitefoldlabs.bridle.core.security

import java.security.GeneralSecurityException

/**
 * Why a private key couldn't be imported. Messages are fixed strings (plus, for
 * [UnsupportedKeyType], a sanitised key-type name) and no cause is attached, so nothing derived
 * from the key or passphrase can leak into logs or crash reports.
 */
public sealed class KeyImportException(
    message: String,
) : Exception(message) {
    /** The key is encrypted and no passphrase was given. */
    public class PassphraseRequired : KeyImportException("This key is encrypted; enter its passphrase")

    /** The passphrase didn't decrypt the key (or the encrypted key is damaged). */
    public class WrongPassphrase : KeyImportException("Wrong passphrase, or the key is damaged")

    /** A readable key of a type Bridle doesn't support (DSA, FIDO security keys, unusual curves). */
    public class UnsupportedKeyType(
        public val keyType: String,
    ) : KeyImportException("Unsupported key type: $keyType")

    /** A key file format Bridle doesn't read (PuTTY, ssh.com/SSH2, ...). */
    public class UnsupportedFormat : KeyImportException("Unsupported key file format; use OpenSSH or PEM")

    /** Not a private key, or a damaged one. */
    public class Malformed : KeyImportException("This doesn't look like a valid private key")
}

/** The stored (encrypted) copy of an imported key can't be used. Never carries key material. */
public class StoredKeyException(
    message: String,
) : GeneralSecurityException(message)

/** The vault can't protect keys on this device (e.g. the Android Keystore is unusable). */
public class KeyVaultUnavailableException(
    message: String,
    cause: Throwable? = null,
) : GeneralSecurityException(message, cause)
