package com.pixeltek.windhover.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "samples", indices = [Index("timeMs"), Index("uploaded")])
data class LocationSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val accuracyM: Float,
    val speedMps: Float,
    val speedSource: String,
    val rawSpeedMps: Float?,
    val speedAccuracyMps: Float?,
    val bearingDeg: Float?,
    val motion: String,
    val provider: String?,
    val isMock: Boolean,
    val batteryPct: Int?,
    val tripId: Long?,
    val uploaded: Boolean = false,
)

val LocationSample.speedKmh: Float get() = speedMps * 3.6f

@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTimeMs: Long,
    val endTimeMs: Long?,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double?,
    val endLon: Double?,
    val distanceM: Double,
    val maxSpeedMps: Float,
    val avgSpeedMps: Float,
    val sampleCount: Int,
)
