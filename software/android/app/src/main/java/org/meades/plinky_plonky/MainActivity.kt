/*
 * Copyright 2026 Rob Meades
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* This written by Google Gemini from my prompts, over several days
 * with several arguments/disagreements and not a few regressions
 * and "imaginative" outcomes along the way.  Some hints for future
 * Rob to avoid detours and very long nights:
 *
 * - if you ask a general question, check, check and check again the
 *   answer,
 * - if you provide a large amount of text as a basis for a
 *   question (e.g. a source file), do the same: beware
 *   skim reading,
 * - if offered an assertion about code behaviour and you
 *   have the slightest whiff of a doubt, ask for a version
 *   of code that only has run-time debug prints added to it and
 *   provide that debug back as evidence: debug text is usually
 *   smaller and more focussed, it dampens hallucinations.
 *
 */

package org.meades.plinky_plonky

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import androidx.core.content.edit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

// Global Configuration
const val DEFAULT_TENSION = 4f
const val SMOOTH_TENSION = 2f
const val SHARP_TENSION = 8f
const val MIN_TENSION = 1.2f
const val MAX_TENSION = 12f

const val DEFAULT_DURATION = 30f
const val MIN_DURATION = 5f
const val MAX_DURATION = 60f

const val SPEED_MAX_HERTZ = 4f

// UUIDs
val SERVICE_UUID: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
val PLAY_STOP_UUID: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
val SPEED_UUID: UUID = UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB")

data class AutomationNode(
    val timePercent: Float, // 0.0 to 1.0 (X-axis)
    val speed: Float        // 0.0 to 2.0 (Y-axis)
)

@Composable
fun PermissionRequester(onGranted: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onGranted()
    }

    // Automatically pop the dialog once this screen appears
    LaunchedEffect(Unit) {
        launcher.launch(arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ))
    }

    // A simple, clean "Wait" screen
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text("Waiting for Bluetooth permissions...", color = Color.White)
    }
}

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    sealed class BleOperation {
        data class Read(val uuid: UUID) : BleOperation()
        data class Write(
            val uuid: UUID,
            val values: List<Byte>,
            val writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ) : BleOperation()
    }
    var isPlaying by mutableStateOf(false)
    var currentSpeed by mutableIntStateOf(-1)
    // We use 'val' because the List Object (the buffer) never changes, only its contents.
    // We remove 'by' so we can access the .clear() and .addAll() methods directly.
    val nodes = mutableStateListOf(AutomationNode(0f, SPEED_MAX_HERTZ / 2), AutomationNode(1f, SPEED_MAX_HERTZ / 2))
    var durationSeconds by mutableFloatStateOf(DEFAULT_DURATION)
    var currentSequenceName by mutableStateOf<String?>(null)
    var isModified by mutableStateOf(false)
    var originalNodes by mutableStateOf(listOf(AutomationNode(0f, SPEED_MAX_HERTZ / 2), AutomationNode(1f, SPEED_MAX_HERTZ / 2)))
    var originalDuration by mutableFloatStateOf(DEFAULT_DURATION)
    var savedSequences by mutableStateOf(listOf<String>())
    var pendingImportData by mutableStateOf<List<Triple<String, List<AutomationNode>, Pair<Float, Float>>>?>(null)
    var isPlayEnabled by mutableStateOf(false)

    // Keep these in a companion object so that they are not
    // destroyed on transitioning between portrait and landscape
    companion object {
        var activeGatt: BluetoothGatt? = null
        var isConnected by mutableStateOf(false)
        var isWorkerRunning = false
        var knobValue by mutableFloatStateOf(SPEED_MAX_HERTZ / 2)
        var hasPerformedInitialSync = false

        @Volatile
        var workerSilenced: Boolean = false
    }

    // It is difficult to believe but apparently
    // the BLE interface on Android is single-threaded,
    // hence we need a queue to feed it
    private val operationChannel = Channel<BleOperation>(Channel.RENDEZVOUS)

    // Helpers to push to the queue
    // For Booleans (Start/Stop)
    private fun enqueue(uuid: UUID, value: Boolean) {
        val byteValue = if (value) 0x01.toByte() else 0x00.toByte()
        enqueue(BleOperation.Write(uuid, listOf(byteValue)))
    }

    // For Int32 (Speed updates)
    private fun enqueue(uuid: UUID, value: Int) {
        val bytes = listOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
        enqueue(BleOperation.Write(uuid, bytes))
    }

    // For Reads (Simple UUID)
    private fun enqueue(uuid: UUID) {
        enqueue(BleOperation.Read(uuid))
    }

    // For Fast Speed updates (No Response / Fire-and-Forget)
    private fun enqueueNoResponse(uuid: UUID, value: Int) {
        val bytes = listOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
        // We pass WRITE_TYPE_NO_RESPONSE here
        enqueue(BleOperation.Write(uuid, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE))
    }
    // The core "Private" sender
    private fun enqueue(op: BleOperation) {
        lifecycleScope.launch {
            operationChannel.send(op)
        }
    }

    // BLE channel management
    private val bleMutex = kotlinx.coroutines.sync.Mutex()
    private var completionDeferred: CompletableDeferred<Int>? = null

    // The permissions nightmare
    private fun hasRequiredPermissions(): Boolean {
        val perms = arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        return perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    // The serialized BLE execution
    private suspend fun executeSafeOperation(
        op: BleOperation,
        characteristic: BluetoothGattCharacteristic,
        gatt: BluetoothGatt
    ): Boolean {
        return bleMutex.withLock {
            // 1. Prepare the "Signal" that we are waiting for a response
            completionDeferred = CompletableDeferred()

            try {
                when (op) {
                    is BleOperation.Read -> {
                        Log.d("BLE_DEBUG", "LOCK: Acquired for READ ${op.uuid}")
                        gatt.readCharacteristic(characteristic)
                    }
                    is BleOperation.Write -> {
                        Log.d("BLE_DEBUG", "LOCK: Acquired for WRITE ${op.uuid}")
                        // Use the writeType stored in the operation (Default vs NoResponse)
                        gatt.writeCharacteristic(
                            characteristic,
                            op.values.toByteArray(),
                            op.writeType
                        )

                        // If we aren't expecting a response,
                        // tell the worker to proceed immediately.
                        if (op.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                            completionDeferred?.complete(0)
                        }
                    }
                }

                // 2. The "Wait": This suspends the worker until the callback calls .complete()
                // We use a timeout so a lost packet doesn't hang the whole app forever.
                withTimeout(1500) {
                    completionDeferred?.await()
                }
                true
            } catch (e: Exception) {
                Log.e("BLE_DEBUG", "Operation Error on ${characteristic.uuid}: ${e.message}")
                false
            } finally {
                // Cleanup to prevent memory leaks or stale signals
                completionDeferred = null
            }
        }
    }

    // Start BLE command queue processor
    private fun startBleWorker(gatt: BluetoothGatt) {
        // Only one loop needed now
        if (isWorkerRunning) return
        isWorkerRunning = true

        lifecycleScope.launch(Dispatchers.IO) {
            Log.i("BLE_DEBUG", "WORKER: Unified Loop Started.")
            try {
                // This pulls BOTH reads and writes in the order they were enqueued
                for (op in operationChannel) {
                    if (workerSilenced) continue

                    val uuid = when (op) {
                        is BleOperation.Read -> op.uuid
                        is BleOperation.Write -> op.uuid
                    }

                    val characteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(uuid)
                    if (characteristic == null) {
                        Log.e("BLE_DEBUG", "WORKER: Characteristic $uuid not found.")
                        continue
                    }

                    // Reuse your existing logic but inside the unified flow
                    executeSafeOperation(op, characteristic, gatt)
                }
            } catch (e: Exception) {
                Log.e("BLE_DEBUG", "WORKER: Fatal crash in loop: ${e.message}")
            } finally {
                Log.e("BLE_DEBUG", "WORKER: Loop exited! BLE communication is now DEAD.")
                isWorkerRunning = false
            }
        }
    }

    // Wot it says
    private fun sendStopCommand() {
        // We only send this if we are actually connected
        if (isConnected) {
            // This puts the STOP command at the end of the queue
            enqueue(PLAY_STOP_UUID, false)
            Log.d("BLE_DEBUG", "Stop command queued for exit.")
        }
    }

    // The "Event Loop" for Bluetooth actions, only initialised once we
    // have permissions checked
    private val gattCallback by lazy {
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e("BLE_DEBUG", "GATT Error: $status. Cleaning up...")
                    gatt.close()
                    activeGatt = null
                    runOnUiThread { isConnected = false }
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("BLE_DEBUG", "Connected. Wiping cache...")
                    refreshDeviceCache(gatt)

                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        gatt.discoverServices()
                    }, 600)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("BLE_DEBUG", "Disconnected state reached.")
                    gatt.close()
                    activeGatt = null
                    runOnUiThread { isConnected = false }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i("BLE_DEBUG", "Services Discovered. Starting Worker...")

                    // Request a high priority connection
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

                    // Update our global reference
                    activeGatt = gatt

                    // 1. Start the worker with the freshly discovered GATT
                    startBleWorker(gatt)

                    // 2. Perform your initial state sync
                    // These go into the queue and wait for the worker to process them
                    enqueue(PLAY_STOP_UUID)
                    enqueue(SPEED_UUID)
                } else {
                    Log.e("BLE_DEBUG", "Service discovery failed with status: $status")
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    when (characteristic.uuid) {
                        PLAY_STOP_UUID -> {
                            isPlaying = value.getOrNull(0) == 0x01.toByte()
                            Log.d("BLE_DEBUG", "Read PlayState: $isPlaying.")
                        }
                        SPEED_UUID -> {
                            // Convert 4 bytes (Little Endian) to Int
                            currentSpeed = if (value.size >= 4) {
                                (value[0].toInt() and 0xFF) or
                                        ((value[1].toInt() and 0xFF) shl 8) or
                                        ((value[2].toInt() and 0xFF) shl 16) or
                                        ((value[3].toInt() and 0xFF) shl 24)
                            } else -1

                            Log.d("BLE_DEBUG", "Read Speed: $currentSpeed.")
                            runOnUiThread {
                                // Sync the UI knob to the hardware immediately on connection
                                if ((currentSpeed >= 0) && !hasPerformedInitialSync) {
                                    knobValue = currentSpeed / 1000f
                                    hasPerformedInitialSync = true
                                    Log.i("BLE_DEBUG", "START_OF_DAY: One-time knob sync complete.")
                                }
                                isConnected = true
                            }
                        }
                    }
                }
                completionDeferred?.complete(status)
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                Log.d("BLE_DEBUG", "Write Confirmed: ${characteristic.uuid} with status $status")
                completionDeferred?.complete(status) // This "wakes up" the worker
            }
        }
    }

    // More BLE management
    private fun refreshDeviceCache(gatt: BluetoothGatt): Boolean {
        return try {
            val method = gatt.javaClass.getMethod("refresh")
            val result = method.invoke(gatt) as Boolean
            Log.d("BLE_DEBUG", "GATT cache refresh successful: $result")
            result
        } catch (e: Exception) {
            Log.e("BLE_DEBUG", "An exception occurred while refreshing device cache", e)
            false
        }
    }

    // Save to file
    private val exportJsonLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { saveJsonToUri(it) }
    }

    // Load from file
    private val importJsonLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadJsonFromUri(it) }
    }

    // Save to file process
    private fun saveJsonToUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val allNames = getAllSavedNames(this@MainActivity)
                val rootArray = JSONArray()

                allNames.forEach { name ->
                    val data = loadSequence(this@MainActivity, name) ?: return@forEach
                    val seqObj = JSONObject().apply {
                        put("name", name)
                        put("duration", data.second.toDouble())
                        put("tension", data.third.toDouble())
                        val nodesArray = JSONArray()
                        data.first.forEach { node ->
                            nodesArray.put(JSONObject().apply {
                                put("t", node.timePercent.toDouble())
                                put("s", node.speed.toDouble())
                            })
                        }
                        put("nodes", nodesArray)
                    }
                    rootArray.put(seqObj)
                }

                contentResolver.openOutputStream(uri)?.use { it.write(rootArray.toString(2).toByteArray()) }
            } catch (e: Exception) {
                Log.e("EXPORT", "Failed to export", e)
            }
        }
    }

    // Load from file process
    private fun loadJsonFromUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. Read the file from disk (Background Thread)
                val jsonString = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@launch
                val rootArray = JSONArray(jsonString)
                val tempList = mutableListOf<Triple<String, List<AutomationNode>, Pair<Float, Float>>>()

                for (i in 0 until rootArray.length()) {
                    val obj = rootArray.getJSONObject(i)
                    val name = obj.getString("name")
                    val duration = obj.getDouble("duration").toFloat()
                    val tension = obj.getDouble("tension").toFloat()
                    val nodesArray = obj.getJSONArray("nodes")
                    val importedNodes = mutableListOf<AutomationNode>()

                    for (j in 0 until nodesArray.length()) {
                        val n = nodesArray.getJSONObject(j)
                        importedNodes.add(AutomationNode(
                            n.getDouble("t").toFloat(),
                            n.getDouble("s").toFloat()
                        ))
                    }
                    tempList.add(Triple(name, importedNodes, Pair(duration, tension)))
                }

                // 2. Process results on the UI Thread (Main Thread)
                withContext(Dispatchers.Main) {
                    val existingNames = getAllSavedNames(this@MainActivity)
                    val conflicts = tempList.map { it.first }.filter { it in existingNames }

                    if (conflicts.isNotEmpty()) {
                        // There are naming conflicts, trigger the AlertDialog
                        pendingImportData = tempList
                    } else {
                        // No conflicts, save everything immediately
                        tempList.forEach { (name, nodes, params) ->
                            saveSequence(this@MainActivity, name, nodes, params.first, params.second)
                        }

                        // Trigger a refresh of the UI state list
                        savedSequences = getAllSavedNames(this@MainActivity)

                        android.widget.Toast.makeText(this@MainActivity,
                            "Imported ${tempList.size} sequences", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("IMPORT", "Failed to import JSON", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(this@MainActivity,
                        "Import failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Composable
    fun KeepScreenOn(enabled: Boolean) {
        val context = LocalContext.current
        DisposableEffect(enabled) {
            if (enabled) {
                val activity = context as? Activity
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {
                val activity = context as? Activity
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("KNOB_DEBUG", "LIFECYCLE: onCreate. Current knobValue in companion is: $knobValue")
        setContent {
            val permissionsGranted = remember { mutableStateOf(hasRequiredPermissions()) }
            if (permissionsGranted.value) {
                LaunchedEffect(Unit) {
                    val runId = (1000..9999).random()
                    Log.i("BLE_DEBUG", "LAUNCH_ID [$runId]: Effect Started.")

                    // 1. Get Adapter
                    val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
                    bluetoothAdapter = manager.adapter
                    Log.d("BLE_DEBUG", "LAUNCH_ID [$runId]: Adapter initialized.")

                    // 3. Connect
                    Log.d("BLE_DEBUG", "LAUNCH_ID [$runId]: Calling autoConnect...")
                    autoConnect()
                }

                val configuration = androidx.compose.ui.platform.LocalConfiguration.current
                val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                var status by remember { mutableStateOf("Ready") }

                KeepScreenOn(enabled = isPlaying)

                LaunchedEffect(isLandscape) {
                    // Rotated: ensure the motor is OFF
                    // to avoid any surprises
                    isPlaying = false
                    enqueue(PLAY_STOP_UUID, false)
                    if (!isLandscape) {
                        isPlayEnabled = false
                    }
                }

                if (isLandscape) {
                    // If we're switching to Landscape mode,
                    // read the current speed first so that it
                    // is not stale when we switch back to
                    // Portrait mode
                    //readCharacteristic(SPEED_UUID)
                    LandscapeSequencer(
                        isPlaying = isPlaying, // Passes the Activity state DOWN
                        onTogglePlay = { requestedState ->
                            Log.i("KNOB_DEBUG", "LANDSCAPE_UI: TogglePlay requested -> $requestedState")
                            isPlaying = requestedState

                            if (requestedState) {
                                enqueue(PLAY_STOP_UUID, true)
                            } else {
                                Log.d("KNOB_DEBUG", "LANDSCAPE_UI: sending STOP")
                                // TODO: we used to drain the speed channel here - is it necessary?
                                enqueue(PLAY_STOP_UUID, false)
                            }
                        },
                        onSpeedUpdate = { enqueueNoResponse(SPEED_UUID,it) },
                        nodes = nodes, // Pass the current value
                        onNodesChange = { newNodes ->
                            nodes.clear()
                            nodes.addAll(newNodes) }, // When the UI wants to change it, update the Activity variable
                        durationSeconds = durationSeconds,
                        onDurationChange = { durationSeconds = it },
                        currentSequenceName = currentSequenceName,
                        onNameChange = { currentSequenceName = it },
                        isModified = isModified,
                        onModifiedChange = { isModified = it },
                        originalNodes = originalNodes,
                        onOriginalNodesChange = {originalNodes = it },
                        originalDuration = originalDuration,
                        onOriginalDurationChange = { originalDuration = it },
                    )
                } else {
                    PortraitController(
                        isConnected = isConnected,
                        currentSpeed = currentSpeed,
                        status = status,
                        onScan = {
                            status = "Scanning..."
                            startReliableScan { device ->
                                status = "Connecting..."
                                // Use transport LE to bypass dual-mode issues on modern phones
                                activeGatt = device.connectGatt(
                                    this@MainActivity,
                                    false,
                                    gattCallback,
                                    BluetoothDevice.TRANSPORT_LE
                                )
                            }
                        },

                        // Update the Activity state AND send the BLE command
                        onTogglePlay = { requestedState ->
                            isPlayEnabled = requestedState // Capture the actual user intent

                            if (requestedState) {
                                isPlaying = true
                                enqueue(SPEED_UUID, (knobValue * 1000).toInt())
                                enqueue(PLAY_STOP_UUID, true)
                            } else {
                                isPlaying = false
                                // TODO: we used to drain the speed channel here - is it necessary?
                                enqueue(PLAY_STOP_UUID, false)
                            }
                        },
                        onSpeedUpdate = { enqueue(SPEED_UUID, it) },
                    )
                }
            } else {
                // This shows on the very first launch only.
                PermissionRequester(onGranted = {
                    permissionsGranted.value = true
                })
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val orient = if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "LANDSCAPE" else "PORTRAIT"
        Log.i("BLE_DEBUG", "LIFECYCLE: Rotation to $orient. isWorkerRunning is $isWorkerRunning")
        sendStopCommand()
    }

    override fun onPause() {
        // Silence the motor during the split-second layout shift
        Log.d("BLE_DEBUG", "LIFECYCLE: onPause - Silencing workers")
        workerSilenced = true
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        Log.d("BLE_DEBUG", "LIFECYCLE: onResume - Unsilencing workers")
        workerSilenced = false
    }

    override fun onDestroy() {
        Log.i("BLE_DEBUG", "LIFECYCLE: onDestroy - App being closed permanently")

        // 1. Kill the loop flag
        workerSilenced = true
        isWorkerRunning = false

        // 2. Emergency Stop
        if (isConnected) {
            val gatt = activeGatt
            val char = gatt?.getService(SERVICE_UUID)?.getCharacteristic(PLAY_STOP_UUID)
            if (gatt != null && char != null) {
                try {
                    Log.d("BLE_DEBUG", "onDestroy: Sending final Stop Command")
                    gatt.writeCharacteristic(char, byteArrayOf(0x00), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                    Thread.sleep(180) // Give hardware a moment to process before we kill the link
                } catch (e: Exception) {
                    Log.e("BLE_DEBUG", "onDestroy: Stop failed: ${e.message}")
                }
            }
        }

        // 3. Close the connection
        try {
            activeGatt?.disconnect()
            activeGatt?.close()
            activeGatt = null
            isConnected = false
            Log.d("BLE_DEBUG", "onDestroy: Bluetooth cleaned up")
        } catch (e: Exception) {
            Log.e("BLE_DEBUG", "onDestroy: Cleanup error: ${e.message}")
        }

        super.onDestroy()
    }

    private fun autoConnect() {
        // Safety check: Don't scan if we are already connected or
        // if permissions aren't actually handled yet.
        if (isConnected || !hasRequiredPermissions()) return

        activeGatt?.let {
            Log.d("BLE_DEBUG", "Already have a GATT handle, checking services...")
            it.discoverServices()
            return
        }

        Log.d("BLE_DEBUG", "Triggering auto-reconnect...")
        startReliableScan { device ->
            activeGatt = device.connectGatt(
                this@MainActivity,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }
    }

    @Composable
    fun LandscapeSequencer(
        isPlaying: Boolean,
        onTogglePlay: (Boolean) -> Unit,
        onSpeedUpdate: (Int) -> Unit,
        nodes: SnapshotStateList<AutomationNode>,
        onNodesChange: (List<AutomationNode>) -> Unit,
        durationSeconds: Float,
        onDurationChange: (Float) -> Unit,
        currentSequenceName: String?,
        onNameChange: (String?) -> Unit,
        isModified: Boolean,
        onModifiedChange: (Boolean) -> Unit,
        originalNodes: List<AutomationNode>,
        onOriginalNodesChange: (List<AutomationNode>) -> Unit,
        originalDuration: Float,
        onOriginalDurationChange: (Float) -> Unit)
    {
        val haptics = LocalHapticFeedback.current
        val density = LocalDensity.current
        val edgePaddingPx = remember(density) { with(density) { 24.dp.toPx() } }
        val leftPaddingPx = remember(density) { with(density) { 60.dp.toPx() } }
        val rightPaddingPx = remember(density) { with(density) { 60.dp.toPx() } }
        val bottomBarHeightPx = with(density) { (if (isPlaying) 60.dp else 100.dp).toPx() }
        var zoomFactor by remember { mutableFloatStateOf(1f) }
        var panOffset by remember { mutableFloatStateOf(0f) }
        val automationPath = remember { Path() }

        var draggedNodeIndex by remember { mutableIntStateOf(-1) }
        var playbackProgress by remember { mutableFloatStateOf(0f) }
        var lastSelectedIndex by remember { mutableIntStateOf(-1) }

        var showSaveDialog by remember { mutableStateOf(false) }
        var saveNameInput by remember { mutableStateOf("") }
        var sequenceToDelete by remember { mutableStateOf<String?>(null) }
        val context = LocalContext.current
        var pendingAction by remember { mutableStateOf<String?>(null) } // "NEW" or a Sequence Name
        var showLoadMenu by remember { mutableStateOf(false) }
        var curveTension by remember { mutableFloatStateOf(DEFAULT_TENSION) }
        val loadAction = { name: String ->
            val loaded = loadSequence(context, name)
            if (loaded != null) {
                nodes.clear()
                nodes.addAll(loaded.first)
                onOriginalNodesChange(loaded.first.toList()) // Update the backup
                onDurationChange(loaded.second)
                onOriginalDurationChange(loaded.second)
                curveTension = loaded.third
                onNameChange(name)
                onModifiedChange(false)
            }
        }

        val resetToNew = {
            // Reset the graph data
            val defaultNodes = listOf(AutomationNode(0f, SPEED_MAX_HERTZ), AutomationNode(1f, SPEED_MAX_HERTZ))
            nodes.clear()
            nodes.addAll(defaultNodes)
            onOriginalNodesChange(defaultNodes) // Set the new "baseline" to the default
            onNameChange(null)
            onDurationChange(DEFAULT_DURATION)
            onOriginalDurationChange(durationSeconds)
            curveTension = DEFAULT_TENSION
            // Reset the state flags
            onModifiedChange(false)

            // Hide the dialog
            pendingAction = null
        }

        fun getUsableSize(totalSize: androidx.compose.ui.unit.IntSize, zoom: Float = 1f): Pair<Float, Float> {
            val baseWidth = totalSize.width - (leftPaddingPx + rightPaddingPx)
            val zoomedWidth = baseWidth * zoom
            val usableHeight = totalSize.height - (edgePaddingPx + bottomBarHeightPx)
            return Pair(zoomedWidth, usableHeight)
        }

        // Float version for Canvas
        fun getUsableSize(totalSize: Size, zoom: Float = 1f): Pair<Float, Float> {
            val baseWidth = totalSize.width - (leftPaddingPx + rightPaddingPx)
            val zoomedWidth = baseWidth * zoom
            val usableHeight = totalSize.height - (edgePaddingPx + bottomBarHeightPx)
            return Pair(zoomedWidth, usableHeight)
        }

        // Playback engine
        LaunchedEffect(isPlaying) {
            if (!isPlaying) {
                playbackProgress = 0f
                return@LaunchedEffect
            }

            try {
                var lastFrameTime = System.currentTimeMillis()
                var currentProgress = 0f

                // Use 'isActive' to satisfy the compiler.
                // It automatically handles the cancellation for you.
                while (isActive) {
                    val now = System.currentTimeMillis()
                    val deltaTime = (now - lastFrameTime) / 1000f
                    lastFrameTime = now

                    val progressDelta = (deltaTime / durationSeconds) / zoomFactor
                    currentProgress += progressDelta
                    playbackProgress = currentProgress.coerceIn(0f, 1f)

                    if (playbackProgress >= 1f) {
                        onTogglePlay(false)
                        break
                    }

                    val baseSpeed = calculateSpeedAtTime(playbackProgress, nodes)
                    val actualSpeed = baseSpeed / zoomFactor

                    onSpeedUpdate((actualSpeed * 1000).toInt())

                    delay(50)
                }
            } finally {
                // This will always run when the loop ends or is canceled
                playbackProgress = 0f
            }
        }

        // UI, with graph, line and some control boxes
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0A))
        )  {
            val textMeasurer = rememberTextMeasurer()
            Canvas(modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var lastTapTime = 0L
                        val touchSlop = viewConfiguration.touchSlop // Standard distance before panning starts

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val currentTime = System.currentTimeMillis()
                            val currentSize = getUsableSize(size, zoomFactor)

                            // 1. Hit Test
                            val hitIndex = nodes.indexOfFirst {
                                val nodeX = leftPaddingPx - panOffset + (it.timePercent * currentSize.first)
                                val nodeY = edgePaddingPx + (1f - (it.speed / SPEED_MAX_HERTZ)) * currentSize.second
                                val dx = nodeX - down.position.x
                                val dy = nodeY - down.position.y
                                (dx * dx + dy * dy) < 10000f
                            }
                            if (hitIndex != -1) { lastSelectedIndex = hitIndex }

                            // 2. Decision Engine
                            var isZooming = false
                            var isPanning = false
                            var upEvent: PointerInputChange? = null

                            var isDraggingNode = false

                            val isLongPress = if (hitIndex != -1) {
                                // If hitting a node, wait to see if we move or hold
                                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || !change.pressed) { upEvent = change; break }

                                        val totalDrag = (change.position - down.position).getDistance()
                                        if (totalDrag > touchSlop) { isDraggingNode = true; break }
                                        event.changes.forEach { it.consume() }
                                    }
                                } == null && !isDraggingNode
                            } else {
                                // Standard panning/zooming logic for empty space
                                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.changes.size > 1) { isZooming = true; break }
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || !change.pressed) { upEvent = change; break }

                                        val totalDrag = (change.position - down.position).getDistance()
                                        if (totalDrag > touchSlop) { isPanning = true; break }
                                        event.changes.forEach { it.consume() }
                                    }
                                } == null && !isZooming && !isPanning
                            }

                            // 3. Execution
                            if (isZooming) {
                                // --- ZOOM LOOP ---
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val activeChanges = event.changes.filter { it.pressed }
                                    if (activeChanges.size < 2) break
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    if (zoomChange != 1f || panChange != Offset.Zero) {
                                        zoomFactor = (zoomFactor * zoomChange).coerceIn(1f, 5f)
                                        zoomFactor *= (1f + (zoomChange - 1f) * 0.5f)
                                        val (zoomedW, _) = getUsableSize(size, zoomFactor)
                                        val maxScroll = (zoomedW - getUsableSize(size, 1f).first).coerceAtLeast(0f)
                                        panOffset = (panOffset - panChange.x).coerceIn(0f, maxScroll)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } else if (isPanning) {
                                // --- SINGLE FINGER PAN LOOP ---
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) break

                                    val panChange = change.position.x - change.previousPosition.x
                                    val (zoomedW, _) = getUsableSize(size, zoomFactor)
                                    val maxScroll = (zoomedW - getUsableSize(size, 1f).first).coerceAtLeast(0f)

                                    panOffset = (panOffset - panChange).coerceIn(0f, maxScroll)
                                    change.consume()
                                }
                            } else if ((isLongPress || isDraggingNode) && hitIndex != -1) {
                                // --- STABLE DRAG NODE LOOP ---
                                draggedNodeIndex = hitIndex
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)

                                var hasMovedPastSlop = false
                                val dragSlopPx = 15f // Adjust this: higher = more "sticky", lower = more "sensitive"

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val dragChange = event.changes.firstOrNull { it.id == down.id }
                                    if (dragChange == null || !dragChange.pressed) break

                                    dragChange.consume()

                                    // Only start moving the node if the finger has moved enough
                                    if (!hasMovedPastSlop) {
                                        val distanceFromStart = (dragChange.position - down.position).getDistance()
                                        if (distanceFromStart > dragSlopPx) {
                                            hasMovedPastSlop = true
                                        }
                                    }

                                    if (hasMovedPastSlop) {
                                        val (usableWidth, usableHeight) = getUsableSize(size, zoomFactor)

                                        val rawT = ((dragChange.position.x + panOffset - leftPaddingPx) / usableWidth).coerceIn(0f, 1f)
                                        val newS = (1f - ((dragChange.position.y - edgePaddingPx) / usableHeight)).coerceIn(0f, 1f) * SPEED_MAX_HERTZ

                                        // --- Your Snap/Boundary Math ---
                                        val minT = if (draggedNodeIndex > 0) nodes[draggedNodeIndex - 1].timePercent + 0.005f else 0f
                                        val maxT = if (draggedNodeIndex < nodes.size - 1) nodes[draggedNodeIndex + 1].timePercent - 0.005f else 1f

                                        val finalT = when (draggedNodeIndex) {
                                            0 -> 0f
                                            nodes.size - 1 -> 1f
                                            else -> rawT.coerceIn(minT, maxT)
                                        }

                                        var finalS = newS
                                        val snapThreshold = 0.08f
                                        val prevNode = if (draggedNodeIndex > 0) nodes[draggedNodeIndex - 1] else null
                                        val nextNode = if (draggedNodeIndex < nodes.size - 1) nodes[draggedNodeIndex + 1] else null
                                        if (prevNode != null && kotlin.math.abs(finalS - prevNode.speed) < snapThreshold) finalS = prevNode.speed
                                        else if (nextNode != null && kotlin.math.abs(finalS - nextNode.speed) < snapThreshold) finalS = nextNode.speed

                                        nodes[draggedNodeIndex] = AutomationNode(finalT, finalS)
                                    }
                                }
                                draggedNodeIndex = -1
                                onModifiedChange(true)
                            } else if (upEvent != null) {
                                // --- TAP / DOUBLE TAP ---
                                    val up = upEvent!!
                                    val isDoubleTap = (currentTime - lastTapTime) < viewConfiguration.doubleTapTimeoutMillis

                                    if (isDoubleTap) {
                                        val (usableWidth, usableHeight) = getUsableSize(size, zoomFactor)
                                        if (hitIndex != -1) {
                                            // Delete node (except ends)
                                            if (hitIndex != 0 && hitIndex != nodes.size - 1) {
                                                val newList = nodes.toMutableList().apply { removeAt(hitIndex) }
                                                onNodesChange(newList)
                                                lastSelectedIndex = -1
                                            }
                                        } else {
                                            // Create node
                                            val t = ((up.position.x + panOffset - leftPaddingPx) / usableWidth).coerceIn(0f, 1f)
                                            val s = (1f - ((up.position.y - edgePaddingPx) / usableHeight)).coerceIn(0f, SPEED_MAX_HERTZ / 2) * SPEED_MAX_HERTZ

                                            // Create-time speed snapping
                                            var finalS = s
                                            val previousNode = nodes.lastOrNull { it.timePercent < t }
                                            if (previousNode != null && kotlin.math.abs(s - previousNode.speed) < 0.1f) {
                                                finalS = previousNode.speed
                                            }

                                            val newNode = AutomationNode(t, finalS)
                                            val newList = (nodes + newNode).sortedBy { it.timePercent }
                                            onNodesChange(newList)
                                            lastSelectedIndex = newList.indexOf(newNode)
                                        }
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onModifiedChange(true)
                                        lastTapTime = 0L // Reset
                                    } else {
                                        // Single Tap selection
                                        if (hitIndex != -1) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        lastTapTime = currentTime
                                    }
                                }
                            }
                        }
                ) {
                val (usableWidth, usableHeight) = getUsableSize(size, zoomFactor)
                val gridColor = Color.White.copy(alpha = 0.15f) // Subtle but visible

                // 2. DRAW HORIZONTAL GRID (Speed)
                for (i in 0..SPEED_MAX_HERTZ.toInt()) {
                    val y = edgePaddingPx + (usableHeight * (i.toFloat() / SPEED_MAX_HERTZ))
                    drawLine(
                        color = gridColor,
                        start = Offset(leftPaddingPx - panOffset, y),
                        end = Offset(leftPaddingPx - panOffset + usableWidth, y),
                        strokeWidth = 1.dp.toPx()
                    )

                    // Speed Labels
                    val speedVal = SPEED_MAX_HERTZ.toInt() - i
                    drawText(
                        textMeasurer = textMeasurer,
                        text = "$speedVal Hz",
                        topLeft = Offset(5.dp.toPx(), y - 10.dp.toPx()),
                        style = TextStyle(color = Color.Gray, fontSize = 10.sp)
                    )
                }

                // 3. DRAW VERTICAL GRID (Time - Every 5s)
                val secondsPerLine = 5f
                val numVerticalLines = (durationSeconds / secondsPerLine).toInt()
                for (i in 0..numVerticalLines) {
                    val time = i * secondsPerLine
                    val x = leftPaddingPx - panOffset + (time / durationSeconds * usableWidth)

                    drawLine(
                        color = gridColor,
                        start = Offset(x, edgePaddingPx),
                        end = Offset(x, edgePaddingPx + usableHeight),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // 4. DRAW BEZIER & NODES (Must come AFTER grid to be on top)
                drawBezierPath(automationPath, nodes, Color.Cyan, edgePaddingPx, leftPaddingPx, rightPaddingPx, panOffset,usableWidth, usableHeight, tension = curveTension)

                // Draw Nodes
                nodes.forEachIndexed { index, node ->
                    // USE THE USABLE DIMENSIONS HERE
                    val centerX = leftPaddingPx - panOffset + (node.timePercent * usableWidth)
                    val centerY = edgePaddingPx + (1f - (node.speed / SPEED_MAX_HERTZ)) * usableHeight

                    val isDragging = index == draggedNodeIndex
                    val isLastSelected = index == lastSelectedIndex

                    // 1. Draw a "Halo" if dragging (visible around the finger)
                    if (isDragging) {
                        drawCircle(
                            color = Color.Cyan.copy(alpha = 0.3f),
                            radius = 24.dp.toPx(), // Large enough to see past a thumb
                            center = Offset(centerX, centerY)
                        )
                    }

                    // 2. Draw the Node itself
                    drawCircle(
                        color = when {
                            isDragging -> Color.Cyan
                            isLastSelected -> Color.Yellow // Different color for "active" node
                            else -> Color.White
                        },
                        radius = if (isLastSelected) 8.dp.toPx() else 6.dp.toPx(),
                        center = Offset(centerX, centerY)
                    )

                    // 3. Optional: Add a stroke to the selected node to make it "pop"
                    if (isLastSelected) {
                        drawCircle(
                            color = Color.Black,
                            radius = 8.dp.toPx(),
                            center = Offset(centerX, centerY),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    if (index == lastSelectedIndex || index == draggedNodeIndex) {
                        val timeValue = node.timePercent * durationSeconds
                        val label = String.format(Locale.getDefault(), "%.1fs, %.2fHz", timeValue, node.speed)

                        val horizontalOffset = if (centerX < size.width / 2) 150f else -350f
                        val verticalOffset = if (centerY < 150f) 60f else -100f

                        drawText(
                            textMeasurer = textMeasurer,
                            text = label,
                            style = TextStyle(
                                color = Color.Yellow,
                                fontSize = 14.sp, // Slightly larger for better visibility
                                background = Color.Black.copy(alpha = 0.7f) // Darker background for contrast
                            ),
                            topLeft = Offset(centerX + horizontalOffset, centerY + verticalOffset)
                        )
                    }
                }

                // Playback Cursor
                if (isPlaying) {
                    // Offset the cursor by the same padding the nodes use
                    val cursorX = leftPaddingPx - panOffset + (playbackProgress * usableWidth)
                    drawLine(
                        color = Color.Yellow,
                        start = Offset(cursorX, edgePaddingPx),
                        end = Offset(cursorX, edgePaddingPx + usableHeight),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            }

            // Save/Load/Play Control (Top Right)
            Row(Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)) {
                // LOAD BUTTON
                IconButton(onClick = {
                    savedSequences = getAllSavedNames(context) // Refresh list
                    showLoadMenu = true
                }) {
                    Icon(Icons.AutoMirrored.Filled.List, "Load", tint = Color.White)
                }

                // DROPDOWN MENU
                DropdownMenu(
                    expanded = showLoadMenu,
                    onDismissRequest = { showLoadMenu = false }
                ) {
                    savedSequences.forEach { name ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(name, modifier = Modifier.weight(1f))
                                    // DELETE BUTTON
                                    IconButton(
                                        onClick = {
                                            // Instead of deleting, just "nominate" this name for deletion
                                            sequenceToDelete = name
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Delete",
                                            tint = Color.Red.copy(alpha = 0.6f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                if (isModified) {
                                    pendingAction = name // The dialog will now show the name of the file
                                } else {
                                    // Safe to load immediately
                                    loadAction(name)
                                }
                                showLoadMenu = false
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    DropdownMenuItem(
                        text = { Text("RESTORE FROM FILE", color = Color.Black, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                        onClick = {
                            showLoadMenu = false
                            importJsonLauncher.launch(arrayOf("application/json"))
                        }
                    )

                    // Only show Backup if there is actually data to save
                    if (savedSequences.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("BACKUP ALL (JSON)", color = Color.Black, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                            onClick = {
                                showLoadMenu = false
                                exportJsonLauncher.launch("plinky_plonky_backup.json")
                            }
                        )
                    }
                }

                IconButton(onClick = {
                    if (isModified) pendingAction = "NEW" else resetToNew()
                }) {
                    Icon(Icons.Default.Add, "New", tint = Color.White)
                }
                Button(onClick = {
                    // If we are currently playing, we definitely want to STOP (false)
                    // If we are stopped, we definitely want to PLAY (true)
                    onTogglePlay(!isPlaying)
                }) {
                    Text(if (isPlaying) "STOP" else "PLAY SEQUENCE")
                }
            }

            if (showSaveDialog) {
                // Pre-fill the input with the current name if it exists
                if (saveNameInput.isEmpty() && currentSequenceName != null) {
                    saveNameInput = currentSequenceName
                }

                AlertDialog(
                    onDismissRequest = { showSaveDialog = false; saveNameInput = "" },
                    title = { Text(if (currentSequenceName == null) "Save Sequence" else "Save Changes") },
                    text = {
                        TextField(
                            value = saveNameInput,
                            onValueChange = { saveNameInput = it },
                            label = { Text("Sequence Name") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (saveNameInput.isNotBlank()) {
                                        saveSequence(context, saveNameInput, nodes, durationSeconds, curveTension)
                                        onNameChange(saveNameInput)
                                        showSaveDialog = false
                                        saveNameInput = ""
                                        onOriginalNodesChange(nodes.toList())
                                        onOriginalDurationChange(durationSeconds)
                                        onModifiedChange(false)
                                    }
                                }
                            )
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (saveNameInput.isNotBlank()) {
                                saveSequence(context, saveNameInput, nodes, durationSeconds, curveTension)
                                onNameChange(saveNameInput)
                                showSaveDialog = false
                                saveNameInput = ""
                                onOriginalNodesChange(nodes.toList())
                                onOriginalDurationChange(durationSeconds)
                                onModifiedChange(false)
                            }
                        }) {
                            Text(if (saveNameInput == currentSequenceName) "Overwrite" else "Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSaveDialog = false; saveNameInput = "" }) { Text("Cancel") }
                    }
                )
            }

            if (sequenceToDelete != null) {
                AlertDialog(
                    onDismissRequest = { sequenceToDelete = null },
                    title = { Text("Delete Sequence?") },
                    text = { Text("Are you sure you want to delete '${sequenceToDelete}'? This cannot be undone.") },
                    confirmButton = {
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                            onClick = {
                                sequenceToDelete?.let { name ->
                                    deleteSequence(context, name)
                                    // Reset UI if we just deleted the active session
                                    if (name == currentSequenceName) {
                                        onNameChange(null)
                                        onModifiedChange(false)
                                    }
                                    savedSequences = getAllSavedNames(context)
                                }
                                sequenceToDelete = null
                                showLoadMenu = false
                            }
                        ) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = { sequenceToDelete = null }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            if (pendingAction != null) {
                AlertDialog(
                    onDismissRequest = { pendingAction = null },
                    title = { Text("Unsaved Changes") },
                    text = { Text("Abandon changes to '${currentSequenceName ?: "New Sequence"}'?") },
                    confirmButton = {
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                            onClick = {
                                if (pendingAction == "NEW") {
                                    resetToNew()
                                } else {
                                    loadAction(pendingAction!!)
                                }
                                pendingAction = null
                            }
                        ) { Text("Abandon") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingAction = null }) { Text("Cancel") }
                    }
                )
            }

            if (pendingImportData != null) {
                val conflicts = pendingImportData!!.filter { it.first in savedSequences }.map { it.first }

                AlertDialog(
                    onDismissRequest = { pendingImportData = null },
                    title = { Text("Import Conflicts") },
                    text = {
                        Text("The following sequences already exist:\n\n${conflicts.joinToString(", ")}\n\nDo you want to overwrite them or skip the duplicates?")
                    },
                    confirmButton = {
                        Button(onClick = {
                            pendingImportData?.forEach { (name, nodes, params) ->
                                saveSequence(context, name, nodes, params.first, params.second)
                            }
                            savedSequences = getAllSavedNames(context)
                            pendingImportData = null
                        }) { Text("Overwrite All") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            pendingImportData?.filter { it.first !in savedSequences }?.forEach { (name, nodes, params) ->
                                saveSequence(context, name, nodes, params.first, params.second)
                            }
                            savedSequences = getAllSavedNames(context)
                            pendingImportData = null
                        }) { Text("Skip Duplicates") }
                    }
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(if (isPlaying) 60.dp else 100.dp),
                color = Color.Black.copy(alpha = 0.85f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 40.dp, vertical = 8.dp), // Increased horizontal padding
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start // Align to start to control spacing manually
                ) {
                    // --- SECTION 1: SEQUENCE INFO ---
                    Column(modifier = Modifier.width(160.dp)) {
                        Text(
                            text = (currentSequenceName?.uppercase() ?: "NEW SEQUENCE"),
                            color = if (isModified) Color.Yellow else Color.Cyan,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                        Row {
                            TextButton(onClick = { onNodesChange(originalNodes.toList()); onModifiedChange(false) }, enabled = isModified, contentPadding = PaddingValues(0.dp)) {
                                Text("REVERT", color = if(isModified) Color.White else Color.DarkGray, fontSize = 11.sp)
                            }
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = { showSaveDialog = true }, enabled = isModified || currentSequenceName == null, contentPadding = PaddingValues(0.dp)) {
                                Text("SAVE", color = if(isModified || currentSequenceName == null) Color.Cyan else Color.DarkGray, fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(Modifier.width(24.dp))
                    Box(Modifier.fillMaxHeight().width(1.dp).background(Color.White.copy(alpha = 0.1f)))
                    Spacer(Modifier.width(32.dp)) // Extra "safety" space

                    // --- SECTION 2: SLIDERS OR TELEMETRY ---
                    if (!isPlaying) {
                        // We use a Row with a maximum width so they don't stretch to the screen edge
                        Row(modifier = Modifier.widthIn(max = 600.dp).weight(1f), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                            // Duration Slider
                            Column(modifier = Modifier.weight(1f)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("DURATION", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                    Text("${durationSeconds.toInt()}s", color = Color.White, fontSize = 12.sp)
                                }
                                Slider(
                                    value = durationSeconds,
                                    onValueChange = {
                                        onDurationChange(it)
                                        onModifiedChange((nodes != originalNodes || durationSeconds != originalDuration))
                                    },
                                    valueRange = MIN_DURATION..MAX_DURATION,
                                    steps = 10,
                                    colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
                                )
                            }

                            // Tension Slider
                            Column(modifier = Modifier.weight(1f)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("TENSION", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                    val label = if (curveTension > SHARP_TENSION) "Sharp" else if (curveTension < SMOOTH_TENSION) "Loose" else "Smooth"
                                    Text(label, color = Color.Magenta, fontSize = 12.sp)
                                }
                                Slider(
                                    value = curveTension,
                                    onValueChange = {
                                        // Haptic pulse when hitting limit
                                        @Suppress("ReplaceTwoComparisonWithRangeCheck")
                                        if ((it >= MAX_TENSION && curveTension < MAX_TENSION) || (it <= MIN_TENSION && curveTension > MIN_TENSION)) {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        curveTension = it
                                        onModifiedChange(true)
                                    },
                                    valueRange = MIN_TENSION..MAX_TENSION,
                                    colors = SliderDefaults.colors(thumbColor = Color.Magenta, activeTrackColor = Color.Magenta)
                                )
                            }
                        }
                    } else {
                        // PLAYBACK MODE: Telemetry (Centered)
                        val currentSpeedHz = calculateSpeedAtTime(playbackProgress, nodes)
                        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly)  {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("CURRENT SPEED", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                Text(
                                    text = String.format(Locale.getDefault(), "%.2f Hz", currentSpeedHz),
                                    color = Color.Yellow,
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("PROGRESS", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                Text(
                                    text = String.format(Locale.getDefault(), "%.1fs / %ds",
                                        playbackProgress * durationSeconds, durationSeconds.toInt()),
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                        }
                    }

                    // Final Spacer to keep everything away from the right edge
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
    }

    @Composable
    fun PortraitController(
        isConnected: Boolean,
        currentSpeed: Int,
        status: String,
        onScan: () -> Unit,
        onTogglePlay: (Boolean) -> Unit,
        onSpeedUpdate: (Int) -> Unit,
    ) {
        val haptics = LocalHapticFeedback.current
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val focusManager = LocalFocusManager.current
        var textFieldValue by remember(knobValue) {
            mutableStateOf(String.format(Locale.getDefault(), "%.2f", knobValue))
        }

        // 1. START OF DAY SYNC: Only runs once when connection is established
        LaunchedEffect(isConnected, currentSpeed) {
            if (isConnected && !hasPerformedInitialSync && currentSpeed >= 0) {
                val hardwareHz = currentSpeed / 1000f
                Log.i("BLE_DEBUG", "START_OF_DAY: initializing knob to hardware value: $hardwareHz")
                knobValue = hardwareHz
                hasPerformedInitialSync = true
            }
        }

        // 2. DEBOUNCE
        LaunchedEffect(knobValue) {
            if (configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
                delay(300)
                val targetSpeed = (knobValue * 1000).toInt()

                // Only send if we are actually playing or if the master switch is on
                if (isPlayEnabled) {
                    Log.d("BLE_DEBUG", "DEBOUNCE: sending Speed $targetSpeed")
                    onSpeedUpdate(targetSpeed)
                }
            }
        }

        Column(modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)) {
            Text("Plinky-Plonky Controller", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))

            // Restore the Status text here
            Text("Status: ${if (isConnected) "Connected" else status}")

            if (!isConnected) {
                Button(
                    modifier = Modifier.padding(top = 16.dp),
                    onClick = onScan
                ) {
                    Text("Search & Connect")
                }
            } else {
                // Use a tiny epsilon check (0.01) instead of an exact 0f
                Spacer(modifier = Modifier.height(32.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OutlinedTextField(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            // Filter: only digits and one decimal
                            val filtered = newValue.filter { it.isDigit() || it == '.' }
                            if (filtered.count { it == '.' } <= 1) {
                                textFieldValue = filtered
                            }
                        },
                        // Using a fixed width to keep it compact
                        modifier = Modifier.width(130.dp),
                        textStyle = MaterialTheme.typography.headlineSmall.copy(
                            textAlign = TextAlign.Center
                        ),
                        suffix = { Text("Hz") },
                        singleLine = true,
                        isError = (textFieldValue.toFloatOrNull() ?: 0f) > SPEED_MAX_HERTZ,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                val parsedValue = textFieldValue.toFloatOrNull()
                                if (parsedValue != null) {
                                    val validatedValue = parsedValue.coerceIn(0f, SPEED_MAX_HERTZ)
                                    knobValue = validatedValue
                                    onSpeedUpdate((validatedValue * 1000).toInt())
                                    textFieldValue = String.format(Locale.getDefault(), "%.2f", validatedValue)
                                } else {
                                    textFieldValue = String.format(Locale.getDefault(), "%.2f", knobValue)
                                }
                                focusManager.clearFocus() // Hides cursor and keyboard
                            }
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = if (knobValue < 0.01f) Color.Red else Color.Black,
                            unfocusedTextColor = if (knobValue < 0.01f) Color.Red else Color.Black,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            cursorColor = if (knobValue < 0.01f) Color.Red else Color.Black
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    RotaryKnob(
                        value = knobValue,
                        onValueChange = { newValue ->
                            // Clear focus if user touches the knob while typing
                            focusManager.clearFocus()

                            knobValue = newValue
                            textFieldValue = String.format(Locale.getDefault(), "%.2f", newValue)
                        }
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        modifier = Modifier.fillMaxWidth(0.6f),
                        onClick = {
                            focusManager.clearFocus() // Also clear focus when pressing Play/Stop
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)

                            if (!isPlayEnabled) {
                                val currentTarget = (knobValue * 1000).toInt()
                                onSpeedUpdate(currentTarget)
                            }
                            onTogglePlay(!isPlayEnabled)
                        }) {
                        Text(if (!isPlayEnabled) "PLAY" else "STOP")
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startReliableScan(onDeviceFound: (BluetoothDevice) -> Unit) {
        if (!hasRequiredPermissions()) {
            Log.d("BLE_DEBUG", "Scan aborted: permissions not yet granted.")
            return
        }

        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bluetoothAdapter?.bluetoothLeScanner

        // Now it is safe to check for zombies
        val alreadyConnected = try {
            manager.getConnectedDevices(BluetoothProfile.GATT)
        } catch (_: SecurityException) {
            emptyList()
        }

        alreadyConnected.forEach { device ->
            if (device.name?.contains("Plinky", ignoreCase = true) == true) {
                Log.d("BLE_DEBUG", "Nuclear: Killing system zombie at ${device.address}")
                val tempGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {})
                tempGatt.disconnect()
                tempGatt.close()
            }
        }

        // ... rest of your existing scanning logic ...
        bluetoothAdapter?.bondedDevices?.forEach { device ->
            if (device.name?.contains("Plinky", ignoreCase = true) == true) {
                onDeviceFound(device)
                return
            }
        }

        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.name?.contains("Plinky", ignoreCase = true) == true) {
                    scanner?.stopScan(this)
                    onDeviceFound(result.device)
                }
            }
        }

        Log.d("BLE_DEBUG", "Starting fresh hardware scan...")
        scanner?.startScan(null, settings, callback)
    }

    fun calculateSpeedAtTime(timePercent: Float, nodes: List<AutomationNode>): Float {
        if (nodes.isEmpty()) return 1f
        if (timePercent <= nodes.first().timePercent) return nodes.first().speed
        if (timePercent >= nodes.last().timePercent) return nodes.last().speed

        for (i in 0 until nodes.size - 1) {
            val p0 = nodes[i]
            val p1 = nodes[i + 1]

            if (timePercent >= p0.timePercent && timePercent <= p1.timePercent) {
                // Calculate 't' (0.0 to 1.0) for this specific segment
                val t = (timePercent - p0.timePercent) / (p1.timePercent - p0.timePercent)

                // Full Cubic Bezier Formula
                // Points: P0 (node), P1 (control 1), P2 (control 2), P3 (node)
                // We only care about the Y (speed) component here
                val speed = (1 - t).pow(3) * p0.speed +
                        3 * (1 - t).pow(2) * t * p0.speed + // Control Point 1 Y
                        3 * (1 - t) * t.pow(2) * p1.speed + // Control Point 2 Y
                        t.pow(3) * p1.speed

                return speed
            }
        }
        return 1f
    }

    private fun saveSequence(context: android.content.Context, name: String, nodes: List<AutomationNode>, duration: Float, tension: Float) {
        val prefs = context.getSharedPreferences("PlinkyPlonkySequences", MODE_PRIVATE)
        // Convert the list of nodes into a string
        val encodedNodes = nodes.joinToString("|") { String.format(Locale.getDefault(), "%.3f", it.timePercent) + ",${it.speed}"}

        prefs.edit {
            putString("nodes_$name", encodedNodes)
                .putFloat("dur_$name", duration)
                .putFloat("tension_$name", tension)
        }
    }

    private fun getAllSavedNames(context: android.content.Context): List<String> {
        val prefs = context.getSharedPreferences("PlinkyPlonkySequences", MODE_PRIVATE)
        // Filter the keys to find only the ones that start with "nodes_"
        return prefs.all.keys.filter { it.startsWith("nodes_") }.map { it.removePrefix("nodes_") }
    }

    private fun loadSequence(context: android.content.Context, name: String): Triple<List<AutomationNode>, Float, Float>? {
        val prefs = context.getSharedPreferences("PlinkyPlonkySequences", MODE_PRIVATE)
        val data = prefs.getString("nodes_$name", null) ?: return null
        val duration = prefs.getFloat("dur_$name", DEFAULT_DURATION)
        val tension = prefs.getFloat("tension_$name", DEFAULT_TENSION)

        val nodes = data.split("|").map {
            val parts = it.split(",")
            AutomationNode(parts[0].toFloat(), parts[1].toFloat())
        }
        return Triple(nodes, duration, tension)
    }

    private fun deleteSequence(context: android.content.Context, name: String) {
        val prefs = context.getSharedPreferences("PlinkyPlonkySequences", MODE_PRIVATE)
        prefs.edit {
            remove("nodes_$name")
                .remove("dur_$name")
                .remove("tension_$name")
            }
    }
}

@Composable
fun RotaryKnob(
    modifier: Modifier = Modifier,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val updatedOnValueChange by rememberUpdatedState(onValueChange)

    // This tracks the value locally so we don't rely on the slow state-update loop
    var lastVibratedValue by remember { mutableFloatStateOf(value) }

    // Ensure our local tracker stays in sync if the value is changed externally (e.g. Preset load)
    LaunchedEffect(value) {
        lastVibratedValue = value
    }

    val startAngle = 135f
    val sweepAngle = 270f
    val visualAngle = startAngle + (value / SPEED_MAX_HERTZ) * sweepAngle

    Canvas(modifier = modifier
        .size(200.dp)
        .pointerInput(Unit) {
            detectDragGestures { change, _ ->
                val center = IntOffset(size.width / 2, size.height / 2)
                val touchPoint = change.position

                val rawAngle = Math.toDegrees(
                    kotlin.math.atan2(
                        (touchPoint.y - center.y).toDouble(),
                        (touchPoint.x - center.x).toDouble()
                    )
                ).toFloat()

                val normalizedAngle = (rawAngle - 135f + 360f) % 360f

                if (normalizedAngle <= 270f) {
                    val snappedAngle = when {
                        normalizedAngle < 2f -> 0f
                        normalizedAngle > 268f -> 270f
                        else -> normalizedAngle
                    }

                    val newValue = (snappedAngle / 270f) * SPEED_MAX_HERTZ

                    // 1. LIMIT HAPTICS (Using lastVibratedValue)
                    if ((lastVibratedValue > 0f && newValue == 0f) ||
                        (lastVibratedValue < 2f && newValue == 2f)) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }

                    // 2. CENTER TICK (Using lastVibratedValue)
                    val crossedCenter = (lastVibratedValue < 1f && newValue >= 1f) ||
                            (lastVibratedValue > 1f && newValue <= 1f)

                    if (crossedCenter) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    }

                    // CRITICAL: Update the tracker IMMEDIATELY
                    lastVibratedValue = newValue
                    updatedOnValueChange(newValue.coerceIn(0f, SPEED_MAX_HERTZ))
                }
                change.consume()
            }
        }
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2

        // Background Track
        drawArc(
            color = Color.Gray.copy(alpha = 0.2f),
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            style = Stroke(width = 8f),
            size = Size(radius * 2, radius * 2),
            topLeft = Offset(center.x - radius, center.y - radius)
        )

        // 3. Draw the Pointer Line and "Cap" circle
        val angleRad = Math.toRadians(visualAngle.toDouble()).toFloat()
        val lineLength = radius * 0.8f
        val endPoint = Offset(
            x = center.x + lineLength * cos(angleRad),
            y = center.y + lineLength * sin(angleRad)
        )

        // Draw the Knob Body (The face)
        drawCircle(
            color = Color.Black,
            radius = radius * 0.9f,
            center = center
        )

        // The Line
        drawLine(
            color = Color.Cyan,
            start = center,
            end = endPoint,
            strokeWidth = 8f,
            cap = StrokeCap.Round
        )

        // The "Cap" Circle at the end of the line
        drawCircle(
            color = Color.Cyan,
            radius = 6.dp.toPx(), // This creates the "indicator dot"
            center = endPoint
        )
    }
}

fun DrawScope.drawBezierPath(
    path: Path,
    nodes: List<AutomationNode>,
    color: Color,
    padding: Float,
    leftPadding: Float,
    @Suppress("UNUSED_PARAMETER") rightPadding: Float,
    panOffset: Float,
    usableWidth: Float,
    usableHeight: Float,
    tension: Float = DEFAULT_TENSION
) {
    if (nodes.size < 2) return
    path.reset()

    fun getCoordinate(node: AutomationNode): Offset {
        return Offset(
            leftPadding - panOffset + (node.timePercent * usableWidth),
            padding + (1f - (node.speed / SPEED_MAX_HERTZ)) * usableHeight
        )
    }

    val startPoint = getCoordinate(nodes[0])
    path.moveTo(startPoint.x, startPoint.y)

    for (i in 0 until nodes.size - 1) {
        val p0 = getCoordinate(nodes[i])
        val p1 = getCoordinate(nodes[i + 1])

        // The closer 'tension' is to a high number, the straighter the line.
        // 2.0f is a smooth standard curve. 10.0f is almost a straight line.
        val distance = (p1.x - p0.x) / tension
        val controlX1 = p0.x + distance
        val controlX2 = p1.x - distance

        path.cubicTo(
            controlX1, p0.y,
            controlX2, p1.y,
            p1.x, p1.y
        )
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
    )
}
