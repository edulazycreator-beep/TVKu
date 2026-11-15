package com.tvku.core.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class StoredIdentity(val installationUuid: String, val encryptedSecretBase64: String)

interface IdentityStorage {
    suspend fun read(): StoredIdentity?
    suspend fun write(identity: StoredIdentity)
}

/**
 * Stored in its own DataStore file, which is excluded from Android backup
 * (see app/src/main/res/xml/data_extraction_rules.xml): a restored copy would
 * carry a secret the new device's Keystore cannot decrypt.
 */
@Singleton
class DataStoreIdentityStorage @Inject constructor(
    @ApplicationContext context: Context,
) : IdentityStorage {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile(FILE_NAME)
    }

    override suspend fun read(): StoredIdentity? {
        val p = store.data.first()
        val uuid = p[UUID_KEY] ?: return null
        val secret = p[SECRET_KEY] ?: return null
        return StoredIdentity(uuid, secret)
    }

    override suspend fun write(identity: StoredIdentity) {
        store.edit {
            it[UUID_KEY] = identity.installationUuid
            it[SECRET_KEY] = identity.encryptedSecretBase64
        }
    }

    companion object {
        const val FILE_NAME = "installation_identity"
        private val UUID_KEY = stringPreferencesKey("installation_uuid")
        private val SECRET_KEY = stringPreferencesKey("installation_secret_enc")
    }
}
