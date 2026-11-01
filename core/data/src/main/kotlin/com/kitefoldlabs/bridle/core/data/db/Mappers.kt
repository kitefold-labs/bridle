package com.kitefoldlabs.bridle.core.data.db

import com.kitefoldlabs.bridle.core.model.Group
import com.kitefoldlabs.bridle.core.model.Host
import com.kitefoldlabs.bridle.core.model.Identity
import com.kitefoldlabs.bridle.core.model.KnownHost
import com.kitefoldlabs.bridle.core.model.LaunchProfile
import com.kitefoldlabs.bridle.core.model.PortForward
import com.kitefoldlabs.bridle.core.model.Snippet

internal fun HostEntity.toModel(): Host =
    Host(
        id = id,
        label = label,
        hostname = hostname,
        port = port,
        username = username,
        identityId = identityId,
        jumpHostId = jumpHostId,
        groupId = groupId,
        tags = tags,
        color = color,
        favorite = favorite,
        keepAliveSeconds = keepAliveSeconds,
        defaultTmuxSession = defaultTmuxSession,
        startupCommand = startupCommand,
        env = env,
        lastConnectedAtEpochMs = lastConnectedAtEpochMs,
    )

internal fun Host.toEntity(): HostEntity =
    HostEntity(
        id = id,
        label = label,
        hostname = hostname,
        port = port,
        username = username,
        identityId = identityId,
        jumpHostId = jumpHostId,
        groupId = groupId,
        tags = tags,
        color = color,
        favorite = favorite,
        keepAliveSeconds = keepAliveSeconds,
        defaultTmuxSession = defaultTmuxSession,
        startupCommand = startupCommand,
        env = env,
        lastConnectedAtEpochMs = lastConnectedAtEpochMs,
    )

internal fun GroupEntity.toModel(): Group = Group(id, name, color, defaultIdentityId, defaultUsername)

internal fun Group.toEntity(): GroupEntity = GroupEntity(id, name, color, defaultIdentityId, defaultUsername)

internal fun IdentityRow.toModel(): Identity =
    Identity(
        id = id,
        alias = alias,
        source = source,
        algorithm = algorithm,
        publicKeyOpenSsh = publicKeyOpenSsh,
        keystoreAlias = keystoreAlias,
        requireUserAuth = requireUserAuth,
        authValiditySeconds = authValiditySeconds,
        createdAtEpochMs = createdAtEpochMs,
    )

internal fun Identity.toRow(): IdentityRow =
    IdentityRow(
        id = id,
        alias = alias,
        source = source,
        algorithm = algorithm,
        publicKeyOpenSsh = publicKeyOpenSsh,
        keystoreAlias = keystoreAlias,
        requireUserAuth = requireUserAuth,
        authValiditySeconds = authValiditySeconds,
        createdAtEpochMs = createdAtEpochMs,
    )

internal fun Identity.toEntity(encryptedPrivateKey: ByteArray?): IdentityEntity =
    IdentityEntity(
        id = id,
        alias = alias,
        source = source,
        algorithm = algorithm,
        publicKeyOpenSsh = publicKeyOpenSsh,
        keystoreAlias = keystoreAlias,
        requireUserAuth = requireUserAuth,
        authValiditySeconds = authValiditySeconds,
        createdAtEpochMs = createdAtEpochMs,
        encryptedPrivateKey = encryptedPrivateKey,
    )

internal fun KnownHostEntity.toModel(): KnownHost =
    KnownHost(host, port, keyType, publicKeyBase64, fingerprintSha256, addedAtEpochMs, lastSeenAtEpochMs)

/** [host] must already be normalised. */
internal fun KnownHost.toEntity(host: String): KnownHostEntity =
    KnownHostEntity(host, port, keyType, publicKeyBase64, fingerprintSha256, addedAtEpochMs, lastSeenAtEpochMs)

internal fun LaunchProfileEntity.toModel(): LaunchProfile =
    LaunchProfile(
        id = id,
        hostId = hostId,
        name = name,
        sessionName = sessionName,
        workingDir = workingDir,
        command = command,
        restartMode = restartMode,
        restartCommand = restartCommand,
        env = env,
        mode = mode,
        pinned = pinned,
    )

internal fun LaunchProfile.toEntity(): LaunchProfileEntity =
    LaunchProfileEntity(
        id = id,
        hostId = hostId,
        name = name,
        sessionName = sessionName,
        workingDir = workingDir,
        command = command,
        restartMode = restartMode,
        restartCommand = restartCommand,
        env = env,
        mode = mode,
        pinned = pinned,
    )

internal fun SnippetEntity.toModel(): Snippet = Snippet(id, name, body, runMode, packageName)

internal fun Snippet.toEntity(): SnippetEntity = SnippetEntity(id, name, body, runMode, packageName)

internal fun PortForwardEntity.toModel(): PortForward =
    PortForward(id, hostId, type, bindPort, destHost, destPort, autoStart)

internal fun PortForward.toEntity(): PortForwardEntity =
    PortForwardEntity(id, hostId, type, bindPort, destHost, destPort, autoStart)
