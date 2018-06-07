package com.appsync.ajcra.watchtest2

import android.arch.persistence.room.*

/**
 * Created by ajcra on 1/3/2018.
 */

@Entity
class CRInfo {
    @PrimaryKey
    var id: String = ""
    var name: String = ""
    var picURL: String? = null
    var land: String? = null
    var height: String? = null
    var waitTime: Int? = null
    var fpTime: String? = null
    var waitRating: Double? = null
    var status: String? = null

    var lastChangeTime: Long? = null
    var pinned: Boolean = false
}

@Dao
interface CRInfoDao {
    @Query("SELECT * FROM CRInfo WHERE id=:rideID")
    fun getRide(rideID: String): CRInfo?

    @Query("SELECT * FROM CRInfo ORDER BY name")
    fun listCacheRides(): List<CRInfo>

    @Query("SELECT * FROM CRInfo WHERE pinned=:pinned ORDER BY name")
    fun listCacheRideOfPin(pinned: Boolean): List<CRInfo>

    @Query("UPDATE CRInfo SET status=:status, waitTime=:waitTime, fpTime=:fpTime, waitRating=:waitRating, lastChangeTime=:dateTime WHERE id=:id")
    fun updateRideTime(id: String, status: String?, waitTime: Int?, fpTime: String?, waitRating: Double?, dateTime: Long?)

    @Query("UPDATE CRInfo SET pinned=:pinned WHERE id=:id")
    fun updateRidePinned(id: String, pinned: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addCRInfo(crInfo: CRInfo)

    @Update
    fun updateCRInfo(crInfo: CRInfo)

    @Delete
    fun delete(pinInfo: CRInfo)
}

@Database(entities = arrayOf(CRInfo::class), version=6)
abstract class RideCacheDatabase: RoomDatabase() {
    abstract fun crInfoDao(): CRInfoDao
}