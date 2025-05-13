// Csomagnév, amely az app egyedi azonosítója (ez a projekt neve alapján generálódik)
package com.example.okosvarosapp2

// Android alap osztályok, komponensek, szenzorok és helyadatok kezeléséhez szükséges importok
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Looper

// Jetpack Compose és Material3 UI eleme
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Saját témánk (az alap Compose sablonban generált)
import com.example.okosvarosapp2.ui.theme.OkosvarosApp2Theme

// Engedélykéréshez Accompanist könyvtár
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState

// Google Location API (helymeghatározás)
import com.google.android.gms.location.*

// Kotlin korutinok, szálkezeléshez
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Kotlinx serialization – adatok mentéséhez/felolvasásához JSON-ben
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

// Fájlkezeléshez, hálózati küldéshez
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.sqrt

// A fő activity osztályunk – minden Android alkalmazás kiindulópontja
@OptIn(ExperimentalPermissionsApi::class)
class MainActivity : ComponentActivity() {

    // Helyadatok lekéréséhez szükséges kliens
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Helyadat kliens inicializálása
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Jetpack Compose UI tartalom megjelenítése
        setContent {
            // Az alap téma használata (színek, betűtípusok stb.)
            OkosvarosApp2Theme {
                // Teljes képernyős felület, világos háttérrel
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Értesítési engedély kérése Android 13-tól
                    NotificationPermissionRequest()

                    // Helyhozzáférés kérése és helyfigyelés indítása, ha engedélyezve
                    LocationPermissionRequest(fusedLocationClient)
                }
            }
        }
    }
}


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun NotificationPermissionRequest() {
    // Engedély állapot lekérése
    val notificationPermissionState = rememberPermissionState(
        permission = Manifest.permission.POST_NOTIFICATIONS
    )

    // Automatikusan kérjük az engedélyt indításkor, ha még nem engedélyezett
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
    // Helyhozzáférés engedélyének állapota
    val locationPermissionState = rememberPermissionState(
        permission = Manifest.permission.ACCESS_FINE_LOCATION
    )

    // Ha engedélyezett, akkor indulhat a TrackLocation (fő logika)
    when (locationPermissionState.status) {
        is PermissionStatus.Granted -> {
            TrackLocation(fusedLocationClient)
        }
        // Ha nem engedélyezett, mutatunk egy magyarázó szöveget és gombot
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

    // Koordináták tárolása megjelenítéshez
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }

    // Előző GPS mérés koordinátái táv kiszámításához
    var previousLatitude by remember { mutableStateOf<Double?>(null) }
    var previousLongitude by remember { mutableStateOf<Double?>(null) }

    // Előző ténylegesen mentett koordináta – a megtett táv adatbázisba küldéséhez
    var previousSavedLatitude by remember { mutableStateOf<Double?>(null) }
    var previousSavedLongitude by remember { mutableStateOf<Double?>(null) }

    // Összesített megtett távolság
    var totalDistance by remember { mutableStateOf(0f) }

    // Hibakezelő
    var error by remember { mutableStateOf<String?>(null) }

    // A helypontok listája (minden mozgás alapján mentett pont)
    val locationPoints = remember { mutableStateListOf<LocationPoint>() }

    // Szenzorkezelés – mozgásérzékelés (accelerometer)
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    var isMoving by remember { mutableStateOf(false) }

    // Ez csak egyszer fut le (Compose szabály), elindítja az adatgyűjtést
    LaunchedEffect(Unit) {
        //Foreground Service indítása (értesítés, háttérben futás)
        val serviceIntent = Intent(context, LocationForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            serviceIntent.putExtra("foregroundServiceType", "location")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // Mozgásérzékelés gyorsulás alapján
        val accelerometerListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    val x = event.values[0]
                    val y = event.values[1]
                    val z = event.values[2]

                    // Teljes gyorsulás nagysága
                    val accelerationMagnitude = sqrt(x * x + y * y + z * z)
                    //Ha mozgás történik
                    if (abs(accelerationMagnitude - 9.81f) > 0.2f) {
                        isMoving = true
                    } else {
                        isMoving = false
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        // Aktiváljuk a mozgásérzékelőt
        sensorManager.registerListener(accelerometerListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)

        //Előző mentett adatok betöltése JSON fájlból
        val savedPoints = loadLocationPointsFromFile(context)
        locationPoints.addAll(savedPoints)

        // Helyadat frissítés 10 másodpercenként
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10000L
        ).build()

        //Callback, ami akkor hívódik meg, ha új helyadat érkezik
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation
                if (location != null && !location.accuracy.isNaN() && location.accuracy <= 20f) {
                    val newLatitude = location.latitude
                    val newLongitude = location.longitude

                    // Előző GPS frissítés alapján számított távolság
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

                    //Ha mozgást érzékelünk, akkor mentünk új helypontot
                    if (isMoving) {
                        var distanceSinceLastPoint = 0f

                        // Táv az előző ténylegesen mentett ponttól
                        if (previousSavedLatitude != null && previousSavedLongitude != null) {
                            distanceSinceLastPoint = calculateDistance(
                                previousSavedLatitude!!,
                                previousSavedLongitude!!,
                                newLatitude,
                                newLongitude
                            )
                        }

                        // Új helypont objektum létrehozása
                        val newPoint = LocationPoint(
                            latitude = newLatitude,
                            longitude = newLongitude,
                            timestamp = System.currentTimeMillis(),
                            distanceMeters = distanceSinceLastPoint
                        )

                        // Mentjük a listába és fájlba
                        locationPoints.add(newPoint)
                        saveLocationPointsToFile(context, locationPoints)

                        // Ha van internet, feltöltjük az adatokat az InfluxDB-be
                        if (isInternetAvailable(context)) {
                            uploadSavedPointsToInfluxDB(
                                context,
                                serverUrl = "http://vm.smallville.cloud.bme.hu:1683",
                                token = "e84SW5XvxvMuZfJDLKJHA8zr1kRjCm026Cm4K0-oATvitjr3of_fCpIvnVndrqPegk8lsi4ZS0657b-rsE5SOg==",
                                bucket = "okosvaroshf",
                                org = "BME",
                                measurement = "location"
                            )
                        }

                        // Frissítjük az előző mentett pontot
                        previousSavedLatitude = newLatitude
                        previousSavedLongitude = newLongitude
                    }

                    // GPS frissítések követése
                    previousLatitude = newLatitude
                    previousLongitude = newLongitude
                    latitude = newLatitude
                    longitude = newLongitude
                }
            }
        }

        // Indítjuk a helyadat figyelést (Google Location API)
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    // UI felület, ami mutatja a helyzetet, távolságot, mérések számát
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

// Egy rögzített helyadat – ezeket mentjük fájlba és küldjük az adatbázisba is
@Serializable
data class LocationPoint(
    val latitude: Double, // Szélesség
    val longitude: Double, // Hosszúság
    val timestamp: Long, // Időbélyeg
    val distanceMeters: Float // Megtett táv
)

// Két földrajzi koordináta közötti távolság kiszámítása Android Location API-val
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

// A helypontokat elmentjük egy JSON fájlba (offline adatgyűjtéshez)
fun saveLocationPointsToFile(context: Context, locationPoints: List<LocationPoint>) {
    val jsonString = Json.encodeToString(locationPoints)
    val file = File(context.filesDir, "location_points.json")
    file.writeText(jsonString)
}

// A korábban elmentett helyadatokat betöltjük újraindításkor
fun loadLocationPointsFromFile(context: Context): List<LocationPoint> {
    val file = File(context.filesDir, "location_points.json")
    return if (file.exists()) {
        val jsonString = file.readText()
        Json.decodeFromString(jsonString)
    } else {
        emptyList()
    }
}

//  Fájl törlése/ürítése – ha sikerült a szinkronizálás, akkor üres tömböt írunk bele
fun clearLocationPointsFile(context: Context) {
    val file = File(context.filesDir, "location_points.json")
    if (file.exists()) {
        file.writeText("[]")
        println("Mentett helypontok törölve a fájlból.")
    }
}

//  Ellenőrizzük, hogy van-e internetkapcsolat
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

// Az elmentett helyadatokat egyszerre küldjük az InfluxDB adatbázisba
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
        val line = "$measurement latitude=${point.latitude},longitude=${point.longitude},distance_meters=${point.distanceMeters} ${point.timestamp * 1_000_000}\n"
        dataBuilder.append(line)
    }

    // Hálózati kérés külön szálon (korutinon), hogy ne blokkolja a UI-t
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val url = URL("$serverUrl/api/v2/write?bucket=$bucket&org=$org&precision=ns")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Token $token")
            connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            connection.doOutput = true

            // Az adatokat elküldjük a szerverre
            val outputStreamWriter = OutputStreamWriter(connection.outputStream)
            outputStreamWriter.write(dataBuilder.toString())
            outputStreamWriter.flush()
            outputStreamWriter.close()

            val responseCode = connection.responseCode
            println("InfluxDB batch response code: $responseCode")

            // Ha sikeres volt a feltöltés, töröljük a lokális adatokat
            if (responseCode in 200..299) {
                clearLocationPointsFile(context)
            }

            connection.disconnect()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
