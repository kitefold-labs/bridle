package com.kitefoldlabs.bridle.core.backup

/**
 * Base class of every failure [BackupCrypto] reports. Messages are fixed, human-readable strings
 * and never contain passphrases, key material or backup contents, so they are safe to log or show.
 */
public sealed class BackupException(
    message: String,
) : Exception(message)

/**
 * The passphrase is wrong, or the file was modified or damaged. AES-GCM cannot tell these apart,
 * and the UI should not pretend to: "Wrong passphrase, or the file is damaged."
 */
public class WrongPassphraseOrCorruptException : BackupException("Wrong passphrase, or the backup file is damaged")

/** The file is a Bridle backup written by a newer (or unknown) format version. */
public class UnsupportedBackupVersionException(
    public val version: Int,
) : BackupException("Backup format version $version is not supported by this version of Bridle")

/** The file is not a Bridle backup, is truncated, or declares out-of-range parameters. */
public class MalformedBackupException(
    reason: String,
) : BackupException("Not a valid Bridle backup: $reason")

/** Refused at export time: the passphrase is shorter than [minLength] characters. */
public class PassphraseTooShortException(
    public val minLength: Int,
) : BackupException("The backup passphrase must be at least $minLength characters long")
