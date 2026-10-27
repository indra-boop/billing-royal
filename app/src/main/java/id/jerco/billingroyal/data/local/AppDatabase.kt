package id.jerco.billingroyal.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CustomerEntity::class,
        ServiceEntity::class,
        PriceListEntity::class,
        PriceListItemEntity::class,
        ProfileEntity::class,
        InvoiceDraftEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customerDao(): CustomerDao
    abstract fun catalogDao(): CatalogDao
    abstract fun profileDao(): ProfileDao
    abstract fun draftDao(): DraftDao

    companion object {
        const val NAME = "billing_royal_cache.db"
    }
}
