package com.kitefoldlabs.bridle.di

import android.content.Context
import com.kitefoldlabs.bridle.connect.DefaultHostConnector
import com.kitefoldlabs.bridle.connect.SignerSource
import com.kitefoldlabs.bridle.core.data.DataModule
import com.kitefoldlabs.bridle.core.session.HostConnector
import com.kitefoldlabs.bridle.core.ssh.SshEngine
import com.kitefoldlabs.bridle.core.ssh.SshjEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModules {
    @Provides
    @Singleton
    fun data(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): DataModule = DataModule.create(context, scope)

    @Provides fun hosts(data: DataModule) = data.hosts

    @Provides fun groups(data: DataModule) = data.groups

    @Provides fun identities(data: DataModule) = data.identities

    @Provides fun knownHosts(data: DataModule) = data.knownHosts

    @Provides fun launchProfiles(data: DataModule) = data.launchProfiles

    @Provides fun snippets(data: DataModule) = data.snippets

    @Provides fun portForwards(data: DataModule) = data.portForwards

    @Provides fun composeHistory(data: DataModule) = data.composeHistory

    @Provides fun backup(data: DataModule) = data.backup

    @Provides fun settings(data: DataModule) = data.settings
}

@Module
@InstallIn(SingletonComponent::class)
object ConnectModules {
    @Provides
    @Singleton
    fun engine(): SshEngine = SshjEngine()

    @Provides
    fun clock(): () -> Long = System::currentTimeMillis

    @Provides
    fun hostConnector(connector: DefaultHostConnector): HostConnector = connector

    /**
     * Until key storage is wired in, hosts authenticate with passwords only. Replaced by the
     * Keystore/imported-key signer source when core:security lands.
     */
    @Provides
    @Singleton
    fun signerSource(): SignerSource = SignerSource { null }
}
