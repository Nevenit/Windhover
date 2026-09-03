package com.pixeltek.windhover.data

import com.pixeltek.windhover.location.TrackedFix
import com.pixeltek.windhover.trip.TripProgress
import kotlinx.coroutines.flow.Flow

class LocationRepository(private val samples: SampleDao, private val trips: TripDao) {

    suspend fun insertSample(fix: TrackedFix, tripId: Long?): Long = samples.insert(fix.toEntity(tripId))

    fun latestSample(): Flow<LocationSample?> = samples.latest()
    fun recentSamples(limit: Int): Flow<List<LocationSample>> = samples.recent(limit)
    fun sampleCount(): Flow<Int> = samples.count()
    fun unsyncedCount(): Flow<Int> = samples.unsyncedCount()

    suspend fun unsynced(limit: Int): List<LocationSample> = samples.unsynced(limit)
    suspend fun markUploaded(ids: List<Long>) = samples.markUploaded(ids)
    suspend fun latestUnsynced(): LocationSample? = samples.latestUnsynced()
    suspend fun markUploadedUpTo(upToMs: Long) = samples.markUploadedUpTo(upToMs)
    suspend fun pruneOlderThan(cutoffMs: Long): Int = samples.deleteOlderThan(cutoffMs)

    suspend fun startTrip(t: TripProgress): Long = trips.insert(t.toEntity(id = 0, ended = false))

    suspend fun updateTrip(id: Long, t: TripProgress, ended: Boolean) = trips.update(t.toEntity(id, ended))

    fun recentTrips(limit: Int): Flow<List<Trip>> = trips.recent(limit)

    suspend fun clearAll() {
        samples.deleteAll()
        trips.deleteAll()
    }
}

fun TrackedFix.toEntity(tripId: Long?) = LocationSample(
    timeMs = timeMs,
    lat = lat,
    lon = lon,
    altitudeM = altitudeM,
    accuracyM = accuracyM,
    speedMps = speedMps,
    speedSource = speedSource.name,
    rawSpeedMps = rawSpeedMps,
    speedAccuracyMps = speedAccuracyMps,
    bearingDeg = bearingDeg,
    motion = motion.name,
    provider = provider,
    isMock = isMock,
    batteryPct = batteryPct,
    tripId = tripId,
)

private fun TripProgress.toEntity(id: Long, ended: Boolean) = Trip(
    id = id,
    startTimeMs = startTimeMs,
    endTimeMs = if (ended) lastTimeMs else null,
    startLat = startLat,
    startLon = startLon,
    endLat = lastLat,
    endLon = lastLon,
    distanceM = distanceM,
    maxSpeedMps = maxSpeedMps,
    avgSpeedMps = avgSpeedMps,
    sampleCount = sampleCount,
)
