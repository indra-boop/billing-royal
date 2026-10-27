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
        PendingIdempotencyEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customerDao(): CustomerDao
    abstract fun catalogDao(): CatalogDao
    abstract fun profileDao(): ProfileDao
    abstract fun draftDao(): DraftDao
    abstract fun pendingIdempotencyDao(): PendingIdempotencyDao

    companion object {
        const val NAME = "billing_royal_cache.db"

        /** v2: penanda "hasil kirim invoice belum pasti" pada draft. Draft + Idempotency-Key TIDAK dibuang. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE invoice_drafts ADD COLUMN uncertainSince INTEGER")
            }
        }

        /** v3: tabel Idempotency-Key customer/expense. Hanya menambah tabel; draft invoice dan cache TIDAK disentuh. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_idempotency` (`scope` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`idempotencyKey` TEXT NOT NULL, `payloadHash` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`scope`, `kind`))",
                )
            }
        }
    }
}
