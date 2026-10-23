package com.jarvis.assistant.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.jarvis.assistant.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class WeatherData(
    val temperature: String,
    val condition: String,
    val details: String,
    val iconResId: Int,
    val cityName: String
)

object LocationWeatherHelper {

    private const val TAG = "LocationWeatherHelper"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @SuppressLint("MissingPermission")
    suspend fun fetchWeather(context: Context): WeatherData? = withContext(Dispatchers.IO) {
        if (!PermissionHelper.hasLocationPermission(context)) {
            Log.w(TAG, "Location permission not granted")
            return@withContext null
        }

        val location = getBestLocation(context)
        if (location == null) {
            Log.w(TAG, "No device location available; refusing to show weather for a hard-coded location")
            return@withContext null
        }
        val lat = location.latitude
        val lon = location.longitude

        val cityName = resolveCityName(context, lat, lon)
        fetchOpenMeteoWeather(lat, lon, cityName)
    }

    @SuppressLint("MissingPermission")
    private suspend fun getBestLocation(context: Context): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null

        // 1. Check last known location from GPS, Network, and Passive
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        var bestLocation: Location? = null
        for (provider in providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    val loc = locationManager.getLastKnownLocation(provider)
                    if (loc != null) {
                        if (bestLocation == null || loc.time > bestLocation.time) {
                            bestLocation = loc
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error querying provider $provider: ${e.message}")
            }
        }

        // If last known location is fresh enough (within 15 minutes), use it immediately
        val fifteenMinutesAgo = System.currentTimeMillis() - 15 * 60 * 1000
        if (bestLocation != null && bestLocation.time > fifteenMinutesAgo) {
            return bestLocation
        }

        // 2. Request a quick single location update with 4-second timeout
        val freshLocation = withTimeoutOrNull(4000L) {
            suspendCoroutine<Location?> { cont ->
                try {
                    val request = LocationRequestCompat.Builder(1000L)
                        .setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
                        .setMaxUpdates(1)
                        .build()

                    val listener = object : LocationListenerCompat {
                        override fun onLocationChanged(loc: Location) {
                            try {
                                LocationManagerCompat.removeUpdates(locationManager, this)
                            } catch (_: Exception) {}
                            cont.resume(loc)
                        }
                    }

                    val provider = if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        LocationManager.NETWORK_PROVIDER
                    } else if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        LocationManager.GPS_PROVIDER
                    } else {
                        null
                    }

                    if (provider != null) {
                        LocationManagerCompat.requestLocationUpdates(locationManager, provider, request, listener, Looper.getMainLooper())
                    } else {
                        cont.resume(null)
                    }
                } catch (e: Exception) {
                    cont.resume(null)
                }
            }
        }

        return freshLocation ?: bestLocation
    }

    private fun resolveCityName(context: Context, lat: Double, lon: Double): String {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                addresses?.firstOrNull()?.let {
                    it.locality ?: it.subAdminArea ?: it.adminArea ?: "My Location"
                } ?: "My Location"
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lon, 1)
                addresses?.firstOrNull()?.let {
                    it.locality ?: it.subAdminArea ?: it.adminArea ?: "My Location"
                } ?: "My Location"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Geocoder failed: ${e.message}")
            "My Location"
        }
    }

    private fun fetchOpenMeteoWeather(lat: Double, lon: Double, cityName: String): WeatherData? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto"
        val request = Request.Builder().url(url).build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)

                val current = json.getJSONObject("current")
                val temp = current.getDouble("temperature_2m")
                val feelsLike = current.getDouble("apparent_temperature")
                val humidity = current.getInt("relative_humidity_2m")
                val code = current.getInt("weather_code")

                val daily = json.optJSONObject("daily")
                val maxTemp = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, temp) ?: temp
                val minTemp = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, temp) ?: temp

                val (condText, iconRes) = getWeatherCondition(code)

                val tempStr = "${Math.round(temp)}°C"
                val conditionStr = "$condText • $cityName"
                val detailsStr = "Feels ${Math.round(feelsLike)}° • H ${Math.round(maxTemp)}° L ${Math.round(minTemp)}° • $humidity% humidity"

                WeatherData(
                    temperature = tempStr,
                    condition = conditionStr,
                    details = detailsStr,
                    iconResId = iconRes,
                    cityName = cityName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch weather from Open-Meteo: ${e.message}")
            null
        }
    }

    private fun getWeatherCondition(code: Int): Pair<String, Int> {
        return when (code) {
            0 -> "Clear Sky" to R.drawable.ic_weather_sun
            1 -> "Mainly Clear" to R.drawable.ic_weather_sun
            2 -> "Partly Cloudy" to R.drawable.ic_weather_cloud
            3 -> "Overcast" to R.drawable.ic_weather_cloud
            45, 48 -> "Foggy" to R.drawable.ic_weather_cloud
            51, 53, 55 -> "Drizzle" to R.drawable.ic_weather_rain
            56, 57 -> "Freezing Drizzle" to R.drawable.ic_weather_rain
            61, 63, 65 -> "Rain" to R.drawable.ic_weather_rain
            66, 67 -> "Freezing Rain" to R.drawable.ic_weather_rain
            71, 73, 75, 77 -> "Snow" to R.drawable.ic_weather_cloud
            80, 81, 82 -> "Rain Showers" to R.drawable.ic_weather_rain
            85, 86 -> "Snow Showers" to R.drawable.ic_weather_cloud
            95, 96, 99 -> "Thunderstorm" to R.drawable.ic_weather_thunder
            else -> "Clear" to R.drawable.ic_weather_sun
        }
    }
}
