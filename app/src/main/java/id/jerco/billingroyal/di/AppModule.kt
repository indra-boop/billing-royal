package id.jerco.billingroyal.di

import android.content.Context
import android.util.Log
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
import id.jerco.billingroyal.data.remote.LoginExcludingInterceptor
import id.jerco.billingroyal.data.remote.NetworkFactory
import id.jerco.billingroyal.data.remote.SessionInterceptor
import id.jerco.billingroyal.data.repo.ScopeProvider
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

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
            .fallbackToDestructiveMigration(dropAllTables = true) // cache saja; aman dibuang
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
            SessionInterceptor { sessionManager.invalidateFromInterceptor() },
        )
        if (BuildConfig.DEBUG) {
            // Hanya debug, level BASIC (tanpa header dan body). Authorization diredaksi sebagai pengaman
            // tambahan, dan /auth/login tidak dicatat sama sekali.
            val logging = HttpLoggingInterceptor { message -> Log.d("BillingRoyalHttp", message) }.apply {
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")
            }
            interceptors += LoginExcludingInterceptor(logging)
        }
        return NetworkFactory.okHttpClient(interceptors)
    }

    @Provides
    @Singleton
    fun billingApi(client: OkHttpClient): BillingApi = NetworkFactory.api(BuildConfig.BASE_URL, client)
}
