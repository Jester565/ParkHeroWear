package com.appsync.ajcra.watchtest2

import android.arch.persistence.room.*

/**
 * Created by ajcra on 1/3/2018.
 */

@Entity
class PinInfo {
    @PrimaryKey
    var id: String = ""
    var rank: Long = 0
}

@Dao
interface PinInfoDao {
    @Query("SELECT id, rank FROM PinInfo ORDER BY rank")
    fun listPins(): List<PinInfo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addPin(pinInfo: PinInfo)

    @Query("SELECT id, rank FROM PinInfo WHERE id=:id")
    fun getPin(id: String): PinInfo?

    @Query("SELECT rank FROM PinInfo ORDER BY rank LIMIT 1")
    fun getMaxRank(): Int

    @Delete
    fun delete(pinInfo: PinInfo)
}

@Database(entities = arrayOf(PinInfo::class), version=2)
abstract class PinDatabase: RoomDatabase() {
    abstract fun pinInfoDao(): PinInfoDao
}