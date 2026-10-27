package id.jerco.billingroyal.data.local

import javax.inject.Qualifier

/** Scope coroutine tingkat aplikasi (bukan ViewModel) untuk pekerjaan yang harus selesai walau layar ditutup. */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
