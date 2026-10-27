package id.jerco.billingroyal.core

import id.jerco.billingroyal.BuildConfig

/** Feature flag dari BuildConfig (default FALSE; diatur di gradle.properties). */
data class FeatureFlags(
    val markPaid: Boolean = false,
    val cancelInvoice: Boolean = false,
    val priceOverride: Boolean = false,
    val delete: Boolean = false,
) {
    /** Endpoint PATCH /invoices/{id}/status hanya tersedia bila salah satu flag status aktif. */
    val statusChange: Boolean get() = markPaid || cancelInvoice

    companion object {
        fun fromBuildConfig() = FeatureFlags(
            markPaid = BuildConfig.ENABLE_MARK_PAID,
            cancelInvoice = BuildConfig.ENABLE_CANCEL_INVOICE,
            priceOverride = BuildConfig.ENABLE_PRICE_OVERRIDE,
            delete = BuildConfig.ENABLE_DELETE,
        )
    }
}
