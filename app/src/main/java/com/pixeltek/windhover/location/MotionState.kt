package com.pixeltek.windhover.location

/** What the phone is doing, fused from activity recognition and observed speed. */
enum class MotionState(val label: String) {
    UNKNOWN("Unknown"),
    STILL("Still"),
    /** Motion detected (geofence exit / significant-motion sensor) but not yet classified. */
    MOVING("Moving"),
    WALKING("Walking"),
    RUNNING("Running"),
    CYCLING("Cycling"),
    DRIVING("Driving"),
}
