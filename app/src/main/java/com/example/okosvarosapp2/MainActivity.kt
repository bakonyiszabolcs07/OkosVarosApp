package com.example.okosvarosapp2

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.okosvarosapp2.ui.theme.OkosvarosApp2Theme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalPermissionsApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setContent {
            OkosvarosApp2Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NotificationPermissionRequest()
                    LocationPermissionRequest(fusedLocationClient)
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun NotificationPermissionRequest() {
    val notificationPermissionState = rememberPermissionState(
        permission = Manifest.permission.POST_NOTIFICATIONS
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (notificationPermissionState.status is PermissionStatus.Denied) {
                notificationPermissionState.launchPermissionRequest()
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LocationPermissionRequest(fusedLocationClient: FusedLocationProviderClient) {
    val locationPermissionState = rememberPermissionState(
        permission = Manifest.permission.ACCESS_FINE_LOCATION
    )

    when (locationPermissionState.status) {
        is PermissionStatus.Granted -> {
            TrackLocation(fusedLocationClient)
        }
        else -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Helyhozzáférés szükséges az alkalmazáshoz.", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { locationPermissionState.launchPermissionRequest() }) {
                    Text("Engedély kérése")
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun TrackLocation(fusedLocationClient: FusedLocationProviderClient) {
    val context = LocalContext.current

    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    var previousLatitude by remember { mutableStateOf<Double?>(null) }
    var previousLongitude by remember { mutableStateOf<Double?>(null) }
    var totalDistance by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    val locationPoints = remember { mutableStateListOf<LocationPoint>() }

    LaunchedEffect(Unit) {
        val serviceIntent = Intent(context, LocationForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            serviceIntent.putExtra("foregroundServiceType", "location")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        val savedPoints = loadLocationPointsFromFile(context)
        locationPoints.addAll(savedPoints)

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10000L
        ).build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location != null && !location.accuracy.isNaN() && location.accuracy <= 20f) {
                    val newLatitude = location.latitude
                    val newLongitude = location.longitude

                    if (previousLatitude != null && previousLongitude != null) {
                        val distance = calculateDistance(
                            previousLatitude!!,
                            previousLongitude!!,
                            newLatitude,
                            newLongitude
                        )

                        if (distance > 2) {
                            totalDistance += distance
                        }
                    }

                    val newPoint = LocationPoint(
                        latitude = newLatitude,
                        longitude = newLongitude,
                        timestamp = System.currentTimeMillis()
                    )
                    locationPoints.add(newPoint)
                    saveLocationPointsToFile(context, locationPoints)

                    if (isInternetAvailable(context)) {
                        uploadSavedPointsToInfluxDB(
                            context,
                            serverUrl = "https://eu-central-1-1.aws.cloud2.influxdata.com",
                            token = "z1SVy6HcCHgRYe9mXrVOEpx85P8gvB23CghUhryCU40Uaga1D5FrbsMoN7Efy2C62y_P06A2FzPbVzwBcpnX1Q==",
                            bucket = "myFirstBucketSzabolcs",
                            org = "Student Project",
                            measurement = "location"
                        )
                    }

                    previousLatitude = newLatitude
                    previousLongitude = newLongitude
                    latitude = newLatitude
                    longitude = newLongitude
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center
    ) {
        if (error != null) {
            Text("Hiba történt: $error")
        } else if (latitude != null && longitude != null) {
            Text("Szélesség (latitude): $latitude")
            Spacer(modifier = Modifier.height(8.dp))
            Text("Hosszúság (longitude): $longitude")
            Spacer(modifier = Modifier.height(16.dp))
            Text("Összes megtett távolság: ${"%.2f".format(totalDistance)} méter")
            Spacer(modifier = Modifier.height(16.dp))
            Text("Rögzített helypontok száma: ${locationPoints.size}")
        } else {
            Text("Helyadatok lekérése folyamatban...")
        }
    }
}

@Serializable
data class LocationPoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)

fun calculateDistance(
    startLatitude: Double,
    startLongitude: Double,
    endLatitude: Double,
    endLongitude: Double
): Float {
    val startLocation = Location("").apply {
        latitude = startLatitude
        longitude = startLongitude
    }
    val endLocation = Location("").apply {
        latitude = endLatitude
        longitude = endLongitude
    }
    return startLocation.distanceTo(endLocation)
}

fun saveLocationPointsToFile(context: Context, locationPoints: List<LocationPoint>) {
    val jsonString = Json.encodeToString(locationPoints)
    val file = File(context.filesDir, "location_points.json")
    file.writeText(jsonString)
}

fun loadLocationPointsFromFile(context: Context): List<LocationPoint> {
    val file = File(context.filesDir, "location_points.json")
    return if (file.exists()) {
        val jsonString = file.readText()
        Json.decodeFromString(jsonString)
    } else {
        emptyList()
    }
}

fun clearLocationPointsFile(context: Context) {
    val file = File(context.filesDir, "location_points.json")
    if (file.exists()) {
        file.writeText("[]")
        println("Mentett helypontok törölve a fájlból.")
    }
}

fun isInternetAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false

    return when {
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
        activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
        else -> false
    }
}

fun uploadSavedPointsToInfluxDB(
    context: Context,
    serverUrl: String,
    token: String,
    bucket: String,
    org: String,
    measurement: String
) {
    val savedPoints = loadLocationPointsFromFile(context)
    if (savedPoints.isEmpty()) {
        println("Nincsenek mentett helypontok.")
        return
    }

    val dataBuilder = StringBuilder()

    for (point in savedPoints) {
        val line = "$measurement latitude=${point.latitude},longitude=${point.longitude} ${point.timestamp * 1_000_000}\n"
        dataBuilder.append(line)
    }

    CoroutineScope(Dispatchers.IO).launch {
        try {
            val url = URL("$serverUrl/api/v2/write?bucket=$bucket&org=$org&precision=ns")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Token $token")
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            connection.doOutput = true

            val outputStreamWriter = OutputStreamWriter(connection.outputStream)
            outputStreamWriter.write(dataBuilder.toString())
            outputStreamWriter.flush()
            outputStreamWriter.close()

            val responseCode = connection.responseCode
            println("InfluxDB batch response code: $responseCode")

            if (responseCode in 200..299) {
                clearLocationPointsFile(context)
            }

            connection.disconnect()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
