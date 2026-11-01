package com.kitefoldlabs.bridle.core.security

import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.ssh.OpenSshKeys
import java.security.PublicKey

/** Fingerprints for display, in the format `ssh-keygen -lf` prints (`SHA256:<base64>`). */
public object KeyFingerprints {
    /** Fingerprint of [identity]'s public key. */
    public fun sha256(identity: Identity): String = sha256(identity.publicKeyOpenSsh)

    /**
     * Fingerprint of an OpenSSH public-key line (`<type> <base64> [comment]`).
     *
     * @throws IllegalArgumentException if [publicKeyLine] isn't a valid public key.
     */
    public fun sha256(publicKeyLine: String): String =
        OpenSshKeys.fingerprintSha256(OpenSshKeys.parseAuthorizedKeyLine(publicKeyLine))

    /** Fingerprint of [key]. */
    public fun sha256(key: PublicKey): String = OpenSshKeys.fingerprintSha256(key)

    /** The `<type> <base64>` part of [identity]'s public key, without its comment (for comparisons). */
    public fun publicKeyWithoutComment(identity: Identity): String =
        identity.publicKeyOpenSsh
            .trim()
            .split(Regex("\\s+"))
            .take(2)
            .joinToString(" ")
}
