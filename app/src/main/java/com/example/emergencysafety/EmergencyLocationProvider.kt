
package com.example.emergencysafety

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class EmergencyLocation(
    val location: Location,
    val isLastKnown: Boolean
)

object EmergencyLocationProvider {

    private const val MAX_LAST_KNOWN_AGE_MS =
        10 * 60 * 1000L

    @SuppressLint("MissingPermission")
    suspend fun getBestAvailableLocation(
        context: Context
    ): EmergencyLocation? {

        val finePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarsePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!finePermission && !coarsePermission) {
            return null
        }

        val client = LocationServices
            .getFusedLocationProviderClient(context)

        val cancellation = CancellationTokenSource()

        val currentLocation = try {
            suspendCancellableCoroutine<Location?> { continuation ->

                continuation.invokeOnCancellation {
                    cancellation.cancel()
                }

                val request = CurrentLocationRequest.Builder()
                    .setPriority(
                        if (finePermission) {
                            Priority.PRIORITY_HIGH_ACCURACY
                        } else {
                            Priority.PRIORITY_BALANCED_POWER_ACCURACY
                        }
                    )
                    .setMaxUpdateAgeMillis(0L)
                    .setDurationMillis(15000L)
                    .build()

                client.getCurrentLocation(
                    request,
                    cancellation.token
                )
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }

        if (currentLocation != null) {
            return EmergencyLocation(
                location = currentLocation,
                isLastKnown = false
            )
        }

        val lastLocation = try {
            suspendCancellableCoroutine<Location?> { continuation ->

                continuation.invokeOnCancellation {
                    cancellation.cancel()
                }

                client.lastLocation
                    .addOnSuccessListener { location ->
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }

        if (lastLocation == null) {
            return null
        }

        val locationAge = (
            System.currentTimeMillis() -
                lastLocation.time
            ).coerceAtLeast(0L)

        if (locationAge > MAX_LAST_KNOWN_AGE_MS) {
            return null
        }

        return EmergencyLocation(
            location = lastLocation,
            isLastKnown = true
        )
    }
}
