package id.jerco.billingroyal.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        CustomerEntity::class,
        ServiceEntity::class,
        PriceListEntity::class,
        PriceListItemEntity::class,
        ProfileEntity::class,
        InvoiceDraftEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customerDao(): CustomerDao
    abstract fun catalogDao(): CatalogDao
    abstract fun profileDao(): ProfileDao
    abstract fun draftDao(): DraftDao

    companion object {
        const val NAME = "billing_royal_cache.db"

        /** v2: penanda "hasil kirim invoice belum pasti" pada draft. Draft + Idempotency-Key TIDAK dibuang. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE invoice_drafts ADD COLUMN uncertainSince INTEGER")
            }
        }
    }
}
