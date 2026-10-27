package id.jerco.billingroyal.data.remote

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Pembuat klien jaringan; dipisah dari Hilt agar dapat dipakai langsung di unit test. */
object NetworkFactory {
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 30L

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false     // null tidak dikirim (mis. unit_price saat override nonaktif)
        encodeDefaults = true     // status = "Draft" harus selalu terkirim
    }

    fun okHttpClient(interceptors: List<Interceptor>, networkInterceptors: List<Interceptor> = emptyList()): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // tidak ada retry diam-diam untuk request tulis
            .followRedirects(false)          // API v1 tidak mengalihkan; jangan ikuti redirect diam-diam
            .followSslRedirects(false)
            .apply {
                interceptors.forEach(::addInterceptor)
                networkInterceptors.forEach(::addNetworkInterceptor)
            }
            .build()

    fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
        .build()

    fun api(baseUrl: String, client: OkHttpClient): BillingApi = retrofit(baseUrl, client).create(BillingApi::class.java)
}
