package com.kitefoldlabs.bridle.core.security

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.IOException
import java.security.GeneralSecurityException

/** Supplies the AEAD that encrypts imported keys at rest. */
public fun interface AeadProvider {
    /** @throws KeyVaultUnavailableException if the AEAD can't be loaded. */
    public fun aead(): Aead
}

/**
 * Production [AeadProvider]: a Tink AES-256-GCM keyset stored in private SharedPreferences and
 * wrapped by an Android Keystore master key ([masterKeyUri]).
 *
 * Tink silently stores keysets in cleartext when the Android Keystore is unusable. With
 * [requireKeystore] (the default) that is refused instead: [aead] throws
 * [KeyVaultUnavailableException] and the unwrapped keyset is discarded (nothing was ever
 * encrypted with it), so a later attempt on a working Keystore starts from a wrapped keyset.
 */
public class AndroidKeystoreAeadProvider(
    context: Context,
    private val prefFileName: String = DEFAULT_PREF_FILE,
    private val keysetName: String = DEFAULT_KEYSET_NAME,
    private val masterKeyUri: String = DEFAULT_MASTER_KEY_URI,
    private val requireKeystore: Boolean = true,
) : AeadProvider {
    private val context: Context = context.applicationContext

    private val aead: Aead by lazy(::load)

    override fun aead(): Aead = aead

    private fun load(): Aead {
        val manager = openKeyset()
        if (requireKeystore && !manager.isUsingKeystore) {
            context
                .getSharedPreferences(prefFileName, Context.MODE_PRIVATE)
                .edit()
                .remove(keysetName)
                .apply()
            throw KeyVaultUnavailableException("The Android Keystore is unavailable; imported keys can't be protected")
        }
        return manager.keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun openKeyset(): AndroidKeysetManager =
        try {
            AeadConfig.register()
            AndroidKeysetManager
                .Builder()
                .withSharedPref(context, keysetName, prefFileName)
                .withKeyTemplate(KeyTemplates.get(KEY_TEMPLATE))
                .withMasterKeyUri(masterKeyUri)
                .build()
        } catch (e: GeneralSecurityException) {
            throw KeyVaultUnavailableException("The key vault can't be opened", e)
        } catch (e: IOException) {
            throw KeyVaultUnavailableException("The key vault can't be opened", e)
        }

    public companion object {
        public const val DEFAULT_PREF_FILE: String = "bridle_key_vault"
        public const val DEFAULT_KEYSET_NAME: String = "bridle_vault_keyset"
        public const val DEFAULT_MASTER_KEY_URI: String = "android-keystore://bridle_master_key"
        private const val KEY_TEMPLATE = "AES256_GCM"
    }
}
