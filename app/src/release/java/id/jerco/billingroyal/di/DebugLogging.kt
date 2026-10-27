package id.jerco.billingroyal.di

import okhttp3.Interceptor

/** Build release: tidak ada logging HTTP sama sekali (library okhttp-logging tidak ikut ke APK release). */
fun debugLoggingInterceptors(): List<Interceptor> = emptyList()
