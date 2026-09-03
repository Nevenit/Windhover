package com.pixeltek.windhover.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SampleDao {
    @Insert
    suspend fun insert(sample: LocationSample): Long

    @Query("SELECT * FROM samples ORDER BY timeMs DESC LIMIT 1")
    fun latest(): Flow<LocationSample?>

    @Query("SELECT * FROM samples ORDER BY timeMs DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<LocationSample>>

    @Query("SELECT * FROM samples WHERE uploaded = 0 ORDER BY timeMs ASC LIMIT :limit")
    suspend fun unsynced(limit: Int): List<LocationSample>

    @Query("UPDATE samples SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    @Query("SELECT * FROM samples WHERE uploaded = 0 ORDER BY timeMs DESC LIMIT 1")
    suspend fun latestUnsynced(): LocationSample?

    /** For "latest position only" targets: everything older than what we just sent is obsolete. */
    @Query("UPDATE samples SET uploaded = 1 WHERE uploaded = 0 AND timeMs <= :upToMs")
    suspend fun markUploadedUpTo(upToMs: Long)

    @Query("SELECT COUNT(*) FROM samples")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM samples WHERE uploaded = 0")
    fun unsyncedCount(): Flow<Int>

    @Query("DELETE FROM samples WHERE timeMs < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int

    @Query("DELETE FROM samples")
    suspend fun deleteAll()
}

@Dao
interface TripDao {
    @Insert
    suspend fun insert(trip: Trip): Long

    @Update
    suspend fun update(trip: Trip)

    @Query("SELECT * FROM trips ORDER BY startTimeMs DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<Trip>>

    @Query("DELETE FROM trips")
    suspend fun deleteAll()
}
