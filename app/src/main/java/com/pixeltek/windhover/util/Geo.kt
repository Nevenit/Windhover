package com.pixeltek.windhover.util

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure geodesy helpers. No Android dependencies so they can be unit tested on the JVM. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8
    const val METERS_PER_DEG_LAT = 111_320.0

    /** Great-circle distance in metres (haversine). */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from true north in [0, 360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Local flat projection in metres relative to a reference point.
     * x grows east, y grows south, so the result maps directly onto screen coordinates.
     * Accurate to well under 1% for the few kilometres a trail view covers.
     */
    fun project(lat: Double, lon: Double, refLat: Double, refLon: Double): Pair<Double, Double> {
        val x = (lon - refLon) * METERS_PER_DEG_LAT * cos(Math.toRadians(refLat))
        val y = -(lat - refLat) * METERS_PER_DEG_LAT
        return x to y
    }

    /** Inverse of [project]: shift a lat/lon by an offset in metres (x east, y south). */
    fun offset(lat: Double, lon: Double, dxM: Double, dyM: Double): Pair<Double, Double> {
        val newLat = lat - dyM / METERS_PER_DEG_LAT
        val newLon = lon + dxM / (METERS_PER_DEG_LAT * cos(Math.toRadians(lat)))
        return newLat to newLon
    }
}
