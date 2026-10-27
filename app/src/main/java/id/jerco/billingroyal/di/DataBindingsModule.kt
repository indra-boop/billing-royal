package id.jerco.billingroyal.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import id.jerco.billingroyal.data.local.ApplicationScope
import id.jerco.billingroyal.data.local.AppDatabase
import id.jerco.billingroyal.data.local.FileLogoStore
import id.jerco.billingroyal.data.local.LocalData
import id.jerco.billingroyal.data.local.LogoStore
import id.jerco.billingroyal.data.local.RequestCanceller
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Room + file logo: data lokal yang dihapus saat sesi berakhir. */
@Singleton
class RoomLocalData @Inject constructor(
    private val database: AppDatabase,
    private val logoStore: LogoStore,
) : LocalData {
    // Room clearAllTables() tidak boleh di main thread (restore() dipanggil dari viewModelScope).
    override suspend fun clearDatabase() = withContext(Dispatchers.IO) { database.clearAllTables() }
    override suspend fun clearLogo() = logoStore.clear()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalBindingsModule {
    @Binds abstract fun logoStore(impl: FileLogoStore): LogoStore
    @Binds abstract fun localData(impl: RoomLocalData): LocalData
}

@Module
@InstallIn(SingletonComponent::class)
object ProcessModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Membatalkan call OkHttp yang masih berjalan saat sesi berakhir. [dagger.Lazy] memutus siklus
     * OkHttpClient -> SessionManager -> RequestCanceller -> OkHttpClient.
     */
    @Provides
    @Singleton
    fun requestCanceller(client: dagger.Lazy<OkHttpClient>): RequestCanceller = RequestCanceller { except ->
        val dispatcher = client.get().dispatcher
        (dispatcher.runningCalls() + dispatcher.queuedCalls())
            .filter { it !== except }
            .forEach { it.cancel() }
    }
}
