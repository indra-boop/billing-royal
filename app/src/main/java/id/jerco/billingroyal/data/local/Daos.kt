package id.jerco.billingroyal.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface CustomerDao {
    @Query(
        "SELECT * FROM customers WHERE scope = :scope AND " +
            "(:q = '' OR name LIKE '%' || :q || '%' OR phone LIKE '%' || :q || '%') " +
            "ORDER BY name COLLATE NOCASE LIMIT :limit OFFSET :offset",
    )
    suspend fun search(scope: String, q: String, limit: Int, offset: Int): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE scope = :scope AND id = :id")
    suspend fun byId(scope: String, id: Long): CustomerEntity?

    @Upsert
    suspend fun upsert(items: List<CustomerEntity>)

    @Query("DELETE FROM customers WHERE scope = :scope")
    suspend fun deleteAll(scope: String)

    @Query("DELETE FROM customers WHERE scope = :scope AND id = :id")
    suspend fun deleteById(scope: String, id: Long)
}

@Dao
interface CatalogDao {
    @Query(
        "SELECT * FROM services WHERE scope = :scope AND " +
            "(:q = '' OR name LIKE '%' || :q || '%' OR itemCode LIKE '%' || :q || '%') ORDER BY name COLLATE NOCASE",
    )
    suspend fun searchServices(scope: String, q: String): List<ServiceEntity>

    @Upsert
    suspend fun upsertServices(items: List<ServiceEntity>)

    @Query("DELETE FROM services WHERE scope = :scope")
    suspend fun deleteServices(scope: String)

    @Query("SELECT * FROM price_lists WHERE scope = :scope ORDER BY name COLLATE NOCASE")
    suspend fun priceLists(scope: String): List<PriceListEntity>

    @Upsert
    suspend fun upsertPriceLists(items: List<PriceListEntity>)

    @Query("DELETE FROM price_lists WHERE scope = :scope")
    suspend fun deletePriceLists(scope: String)

    @Query("SELECT * FROM price_list_items WHERE scope = :scope AND priceListId = :priceListId")
    suspend fun priceListItems(scope: String, priceListId: Long): List<PriceListItemEntity>

    @Upsert
    suspend fun upsertPriceListItems(items: List<PriceListItemEntity>)

    @Query("DELETE FROM price_list_items WHERE scope = :scope AND priceListId = :priceListId")
    suspend fun deletePriceListItems(scope: String, priceListId: Long)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE scope = :scope")
    suspend fun get(scope: String): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM invoice_drafts WHERE scope = :scope")
    suspend fun get(scope: String): InvoiceDraftEntity?

    @Upsert
    suspend fun upsert(draft: InvoiceDraftEntity)

    @Query("DELETE FROM invoice_drafts WHERE scope = :scope")
    suspend fun delete(scope: String)
}
