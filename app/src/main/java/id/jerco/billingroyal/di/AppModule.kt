package id.jerco.billingroyal.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import id.jerco.billingroyal.BuildConfig
import id.jerco.billingroyal.core.FeatureFlags
import id.jerco.billingroyal.data.local.AndroidKeystoreCipher
import id.jerco.billingroyal.data.local.AppDatabase
import id.jerco.billingroyal.data.local.CatalogDao
import id.jerco.billingroyal.data.local.CustomerDao
import id.jerco.billingroyal.data.local.DraftDao
import id.jerco.billingroyal.data.local.ProfileDao
import id.jerco.billingroyal.data.local.SessionManager
import id.jerco.billingroyal.data.local.TokenCipher
import id.jerco.billingroyal.data.remote.AuthInterceptor
import id.jerco.billingroyal.data.remote.BillingApi
import id.jerco.billingroyal.data.remote.JsonHeadersInterceptor
import id.jerco.billingroyal.data.remote.NetworkFactory
import id.jerco.billingroyal.data.remote.SessionInterceptor
import id.jerco.billingroyal.data.repo.ScopeProvider
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    abstract fun scopeProvider(sessionManager: SessionManager): ScopeProvider
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun featureFlags(): FeatureFlags = FeatureFlags.fromBuildConfig()

    @Provides
    @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile("session") })

    @Provides
    @Singleton
    fun tokenCipher(): TokenCipher = AndroidKeystoreCipher()

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            // v1 -> v2 dimigrasi tanpa membuang draft/Idempotency-Key. Hanya downgrade yang boleh mengosongkan.
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides fun customerDao(db: AppDatabase): CustomerDao = db.customerDao()
    @Provides fun catalogDao(db: AppDatabase): CatalogDao = db.catalogDao()
    @Provides fun profileDao(db: AppDatabase): ProfileDao = db.profileDao()
    @Provides fun draftDao(db: AppDatabase): DraftDao = db.draftDao()

    @Provides
    @Singleton
    fun okHttpClient(sessionManager: SessionManager): OkHttpClient {
        val interceptors = mutableListOf(
            JsonHeadersInterceptor(),
            AuthInterceptor { sessionManager.currentToken() },
            SessionInterceptor { token, reason, call -> sessionManager.invalidateIfCurrent(token, reason, call) },
        )
        // Logging HTTP hanya ada di source set debug (okhttp-logging = debugImplementation); release: kosong.
        interceptors += debugLoggingInterceptors()
        return NetworkFactory.okHttpClient(interceptors)
    }

    @Provides
    @Singleton
    fun billingApi(client: OkHttpClient): BillingApi = NetworkFactory.api(BuildConfig.BASE_URL, client)
}
