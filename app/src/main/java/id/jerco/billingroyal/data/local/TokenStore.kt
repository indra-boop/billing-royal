package id.jerco.billingroyal.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import id.jerco.billingroyal.data.remote.UserDto
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Info sesi aktif. Password TIDAK pernah disimpan. */
data class StoredSession(
    val token: String,
    val expiresAt: String?,
    val userId: Long,
    val businessId: Long,
    val username: String,
    val fullName: String,
    val role: String,
    val businessName: String,
    val businessCode: String,
) {
    /** Kunci pemisah cache: user_id + business_id. */
    val scopeKey: String get() = "$userId:$businessId"

    // Hindari token ikut tercetak lewat toString()/log.
    override fun toString(): String = "StoredSession(userId=$userId, businessId=$businessId, token=<redacted>)"
}

/**
 * Menyimpan token terenkripsi (AES/GCM, kunci di Android Keystore) di DataStore.
 * Token plaintext hanya ada di memori proses.
 */
@Singleton
class TokenStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
) {
    @Volatile
    private var memory: StoredSession? = null

    fun current(): StoredSession? = memory
    fun currentToken(): String? = memory?.token

    suspend fun load(): StoredSession? {
        val prefs = dataStore.data.first()
        val encrypted = prefs[K_TOKEN] ?: return null.also { memory = null }
        val token = try {
            String(cipher.decrypt(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)
        } catch (_: Exception) {
            // Kunci Keystore hilang/rusak: anggap tidak ada sesi.
            clear()
            return null
        }
        val userId = prefs[K_USER_ID] ?: return null
        val businessId = prefs[K_BUSINESS_ID] ?: return null
        val session = StoredSession(
            token = token,
            expiresAt = prefs[K_EXPIRES],
            userId = userId,
            businessId = businessId,
            username = prefs[K_USERNAME].orEmpty(),
            fullName = prefs[K_FULL_NAME].orEmpty(),
            role = prefs[K_ROLE].orEmpty(),
            businessName = prefs[K_BUSINESS_NAME].orEmpty(),
            businessCode = prefs[K_BUSINESS_CODE].orEmpty(),
        )
        memory = session
        return session
    }

    suspend fun save(token: String, expiresAt: String?, user: UserDto): StoredSession {
        val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(token.toByteArray(Charsets.UTF_8)))
        dataStore.edit { p ->
            p[K_TOKEN] = encrypted
            if (expiresAt != null) p[K_EXPIRES] = expiresAt else p.remove(K_EXPIRES)
            p[K_USER_ID] = user.id
            p[K_BUSINESS_ID] = user.business.id
            p[K_USERNAME] = user.username
            p[K_FULL_NAME] = user.full_name
            p[K_ROLE] = user.role
            p[K_BUSINESS_NAME] = user.business.name
            p[K_BUSINESS_CODE] = user.business.code
        }
        val session = StoredSession(
            token, expiresAt, user.id, user.business.id, user.username, user.full_name,
            user.role, user.business.name, user.business.code,
        )
        memory = session
        return session
    }

    /** Memperbarui info user (hasil GET /me) tanpa mengubah token. */
    suspend fun updateUser(user: UserDto): StoredSession? {
        val old = memory ?: return null
        if (old.userId != user.id || old.businessId != user.business.id) return old
        dataStore.edit { p ->
            p[K_USERNAME] = user.username
            p[K_FULL_NAME] = user.full_name
            p[K_ROLE] = user.role
            p[K_BUSINESS_NAME] = user.business.name
            p[K_BUSINESS_CODE] = user.business.code
        }
        val updated = old.copy(
            username = user.username, fullName = user.full_name, role = user.role,
            businessName = user.business.name, businessCode = user.business.code,
        )
        memory = updated
        return updated
    }

    /** Menghapus token, seluruh isi DataStore akun, dan kunci Keystore. */
    suspend fun clear() {
        memory = null
        dataStore.edit { it.clear() }
        try {
            cipher.deleteKey()
        } catch (_: Exception) {
            // abaikan: kunci akan dibuat ulang bila perlu
        }
    }

    private companion object {
        val K_TOKEN = stringPreferencesKey("token_enc")
        val K_EXPIRES = stringPreferencesKey("expires_at")
        val K_USER_ID = longPreferencesKey("user_id")
        val K_BUSINESS_ID = longPreferencesKey("business_id")
        val K_USERNAME = stringPreferencesKey("username")
        val K_FULL_NAME = stringPreferencesKey("full_name")
        val K_ROLE = stringPreferencesKey("role")
        val K_BUSINESS_NAME = stringPreferencesKey("business_name")
        val K_BUSINESS_CODE = stringPreferencesKey("business_code")
    }
}
