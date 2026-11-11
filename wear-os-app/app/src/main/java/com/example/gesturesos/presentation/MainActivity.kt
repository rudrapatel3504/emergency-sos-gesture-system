package com.example.gesturesos.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.example.gesturesos.presentation.theme.GestureSOSTheme
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var locationManager: LocationManager
    private lateinit var database: DatabaseReference
    private var sosListener: ValueEventListener? = null

    private var axValue by mutableStateOf(0f)
    private var ayValue by mutableStateOf(0f)
    private var azValue by mutableStateOf(0f)
    private var isDetecting by mutableStateOf(false)
    private var sampleCount by mutableStateOf(0)
    private var status by mutableStateOf("READY")
    private var prediction by mutableStateOf("--")
    private var confidence by mutableStateOf(0f)

    private val ax = FloatArray(GestureClassifier.SAMPLE_COUNT)
    private val ay = FloatArray(GestureClassifier.SAMPLE_COUNT)
    private val az = FloatArray(GestureClassifier.SAMPLE_COUNT)
    private val timeMs = LongArray(GestureClassifier.SAMPLE_COUNT)
    private var firstTimestamp = 0L

    private val liveLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d("GestureSOS", "Live GPS Update: ${location.latitude}, ${location.longitude}")
            updateFirebaseLocation(location)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        database = FirebaseDatabase.getInstance("https://YOUR_PROJECT_ID-default-rtdb.firebaseio.com").reference
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
        startListeningForReset()

        setContent {
            GestureSOSTheme { DetectorScreen() }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        isDetecting = false
        stopLiveTracking()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        axValue = event.values[0]; ayValue = event.values[1]; azValue = event.values[2]
        if (!isDetecting || sampleCount >= GestureClassifier.SAMPLE_COUNT) return

        if (sampleCount == 0) { firstTimestamp = event.timestamp; status = "COLLECTING..." }
        ax[sampleCount] = event.values[0]; ay[sampleCount] = event.values[1]; az[sampleCount] = event.values[2]
        timeMs[sampleCount] = (event.timestamp - firstTimestamp) / 1_000_000L
        sampleCount++
        if (sampleCount == GestureClassifier.SAMPLE_COUNT) classifyWindow()
    }

    private fun classifyWindow() {
        isDetecting = false
        try {
            prediction = GestureClassifier.predict(ax, ay, az, timeMs)
            confidence = GestureClassifier.confidence(ax, ay, az, timeMs)
            if (prediction == "G1") {
                status = "🚨 SOS ACTIVE - LIVE TRACKING"
                val location = getLastKnownLocation()
                triggerCloudSOS(location)
                startLiveTracking()
            } else { status = "NORMAL MOVEMENT" }
        } catch (e: Exception) {
            status = "CLASSIFICATION ERROR"; prediction = "--"; confidence = 0f
        }
    }

    private fun getLastKnownLocation(): Location? {
        return try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) { null }
    }

    private fun triggerCloudSOS(location: Location?) {
        val sosData = HashMap<String, Any>()
        sosData["status"] = "SOS_DETECTED"
        sosData["timestamp"] = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        if (location != null) { sosData["latitude"] = location.latitude; sosData["longitude"] = location.longitude } 
        else { sosData["latitude"] = 0.0; sosData["longitude"] = 0.0 }
        database.child("sos_status").setValue(sosData)
    }

    private fun startLiveTracking() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000, 10f, liveLocationListener, Looper.getMainLooper())
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000, 10f, liveLocationListener, Looper.getMainLooper())
    }

    private fun updateFirebaseLocation(location: Location) {
        val updates = HashMap<String, Any>()
        updates["latitude"] = location.latitude
        updates["longitude"] = location.longitude
        updates["timestamp"] = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        database.child("sos_status").updateChildren(updates)
    }

    private fun stopLiveTracking() { locationManager.removeUpdates(liveLocationListener) }

    private fun startListeningForReset() {
        sosListener = database.child("sos_status").child("status").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val currentStatus = snapshot.getValue(String::class.java)
                if (currentStatus == "NORMAL") {
                    Log.d("GestureSOS", "ESP32 reset detected. Stopping live tracking.")
                    stopLiveTracking()
                    if(status.contains("SOS")) status = "✅ ALARM CLEARED BY BASE STATION"
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun startDetection() {
        sampleCount = 0; firstTimestamp = 0L; prediction = "--"; confidence = 0f; status = "WAITING..."
        isDetecting = true; window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stopDetection() {
        isDetecting = false; stopLiveTracking(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); status = "STOPPED"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @Composable
    fun DetectorScreen() {
        val listState = rememberTransformingLazyColumnState()
        val transformationSpec = rememberTransformationSpec()
        AppScaffold {
            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
                    item { ListHeader(modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec), transformation = SurfaceTransformation(transformationSpec)) { Text("GESTURE SOS") } }
                    item { Column(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("LIVE ACCELEROMETER"); Text("AX: %.2f".format(Locale.US, axValue)); Text("AY: %.2f".format(Locale.US, ayValue)); Text("AZ: %.2f".format(Locale.US, azValue)) } }
                    item { Column(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("STATUS"); Text(status); Text("$sampleCount / ${GestureClassifier.SAMPLE_COUNT}") } }
                    item { Column(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("PREDICTION"); Text(prediction); Text("Confidence: %.0f%%".format(Locale.US, confidence * 100f)) } }
                    item { Button(onClick = { if (isDetecting) stopDetection() else startDetection() }, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Text(if (isDetecting) "STOP" else "START DETECTION") } }
                }
            }
        }
    }
}
