package id.jerco.billingroyal.di

import android.util.Log
import id.jerco.billingroyal.data.remote.LoginExcludingInterceptor
import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Hanya build debug. Level BASIC (tanpa header dan body); Authorization diredaksi sebagai pengaman
 * tambahan, parameter query pencarian diredaksi, dan /auth/login tidak dicatat sama sekali.
 */
fun debugLoggingInterceptors(): List<Interceptor> {
    val logging = HttpLoggingInterceptor { message -> Log.d("BillingRoyalHttp", message) }.apply {
        level = HttpLoggingInterceptor.Level.BASIC
        redactHeader("Authorization")
        redactQueryParams("q")
    }
    return listOf(LoginExcludingInterceptor(logging))
}
