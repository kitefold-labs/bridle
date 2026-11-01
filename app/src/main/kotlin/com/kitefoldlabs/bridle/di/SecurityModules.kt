package com.kitefoldlabs.bridle.di

import android.content.Context
import com.kitefoldlabs.bridle.core.security.AeadProvider
import com.kitefoldlabs.bridle.core.security.AndroidKeyStoreBackend
import com.kitefoldlabs.bridle.core.security.AndroidKeyStoreWrappingStore
import com.kitefoldlabs.bridle.core.security.AndroidKeystoreAeadProvider
import com.kitefoldlabs.bridle.core.security.BiometricGate
import com.kitefoldlabs.bridle.core.security.ImportedKeyVault
import com.kitefoldlabs.bridle.core.security.KeyStoreBackend
import com.kitefoldlabs.bridle.core.security.KeyWrappingStore
import com.kitefoldlabs.bridle.core.security.KeystoreKeyManager
import com.kitefoldlabs.bridle.feature.keys.ActivityBiometricGate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The parts of key storage that need the real Android Keystore. Kept apart from
 * [SecurityModules] so tests can swap in software implementations (Robolectric has no Keystore).
 */
@Module
@InstallIn(SingletonComponent::class)
object KeystoreModule {
    @Provides
    @Singleton
    fun keyStoreBackend(
        @ApplicationContext context: Context,
    ): KeyStoreBackend = AndroidKeyStoreBackend(context)

    @Provides
    @Singleton
    fun aeadProvider(
        @ApplicationContext context: Context,
    ): AeadProvider = AndroidKeystoreAeadProvider(context)

    /** Per-key Keystore wrapping keys, so the Keystore enforces imported keys' user authentication. */
    @Provides
    @Singleton
    fun keyWrappingStore(
        @ApplicationContext context: Context,
    ): KeyWrappingStore = AndroidKeyStoreWrappingStore(context)
}

@Module
@InstallIn(SingletonComponent::class)
object SecurityModules {
    @Provides
    @Singleton
    fun keystoreKeyManager(backend: KeyStoreBackend): KeystoreKeyManager = KeystoreKeyManager(backend)

    @Provides
    @Singleton
    fun importedKeyVault(
        aead: AeadProvider,
        wrappingKeys: KeyWrappingStore,
    ): ImportedKeyVault = ImportedKeyVault(aead, wrappingKeys = wrappingKeys)

    @Provides
    fun biometricGate(gate: ActivityBiometricGate): BiometricGate = gate
}
