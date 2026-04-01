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

/* This written by Google Gemini from my prompts. */

package org.meades.plinky_plonky

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

// Global Configuration
const val DEFAULT_TENSION = 4f
const val SMOOTH_TENSION = 2f
const val SHARP_TENSION = 8f
const val MIN_TENSION = 1.2f
const val MAX_TENSION = 12f

const val DEFAULT_DURATION = 30f
const val MIN_DURATION = 5f
const val MAX_DURATION = 60f

const val BLE_UPDATE_INTERVAL_MS = 50L

// UUIDs
val SERVICE_UUID: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
val PLAY_STOP_UUID: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
val SPEED_UUID: UUID = UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB")

data class AutomationNode(
    val timePercent: Float, // 0.0 to 1.0 (X-axis)
    val speed: Float        // 0.0 to 2.0 (Y-axis)
)

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

    var isPlaying by mutableStateOf(false)
    var currentSpeed by mutableIntStateOf(0)
    // We use 'val' because the List Object (the buffer) never changes, only its contents.
    // We remove 'by' so we can access the .clear() and .addAll() methods directly.
    val nodes = mutableStateListOf(AutomationNode(0f, 1f), AutomationNode(1f, 1f))
    var durationSeconds by mutableFloatStateOf(DEFAULT_DURATION)
    var currentSequenceName by mutableStateOf<String?>(null)
    var isModified by mutableStateOf(false)
    var originalNodes by mutableStateOf(listOf(AutomationNode(0f, 1f), AutomationNode(1f, 1f)))
    var originalDuration by mutableFloatStateOf(DEFAULT_DURATION)

    // Keep track of the active connection
    private var activeGatt: BluetoothGatt? = null
    private var isConnected by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d("PERM", "All Bluetooth permissions granted")
        } else {
            Log.e("PERM", "Permissions denied. BLE will not work.")
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION.SDK_INT) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissionLauncher.launch(permissions)
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    // The "Event Loop" for Bluetooth actions
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BLE_DEBUG", "Connected. Wiping cache...")

                // Wipe the "brain"
                refreshDeviceCache(gatt)

                // Give the hardware a moment to breathe after the wipe
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    Log.d("BLE_DEBUG", "Discovering services post-wipe...")
                    gatt.discoverServices()
                }, 600)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                activeGatt = null
                runOnUiThread { isConnected = false }
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("BLE_DEBUG", "GATT Error: $status. Cleaning up...")
                gatt.close()
                activeGatt = null
                return
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE_DEBUG", "Services found. Starting Initial Read...")
                // Start the chain: Read the Play/Stop status first
                readCharacteristic(PLAY_STOP_UUID)
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
                        Log.d("BLE_DEBUG", "Read PlayState: $isPlaying. Next: Read Speed.")
                        // Chain the next read
                        readCharacteristic(SPEED_UUID)
                    }
                    SPEED_UUID -> {
                        // Convert 4 bytes (Little Endian) to Int
                        currentSpeed = if (value.size >= 4) {
                            (value[0].toInt() and 0xFF) or
                                    ((value[1].toInt() and 0xFF) shl 8) or
                                    ((value[2].toInt() and 0xFF) shl 16) or
                                    ((value[3].toInt() and 0xFF) shl 24)
                        } else 0

                        Log.d("BLE_DEBUG", "Read Speed: $currentSpeed. UI Synced.")
                        runOnUiThread { isConnected = true }
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE_DEBUG", "Write Successful: ${characteristic.uuid}")

                // If we just wrote to the Play/Stop characteristic,
                // we might want to re-read it or just trust the toggle.
                if (characteristic.uuid == PLAY_STOP_UUID) {
                    // We use runOnUiThread because gattCallback runs on a background thread,
                    // but Compose state should be modified on the Main thread.
                    runOnUiThread {
                        // You can either read it back to be sure:
                        //readCharacteristic(PLAY_STOP_UUID)
                        // Or if you want to be quick, toggle it here.
                    }
                }
            }
        }
    }

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

    // Helper function to trigger a read
    private fun readCharacteristic(uuid: UUID) {
        val service = activeGatt?.getService(SERVICE_UUID)
        val char = service?.getCharacteristic(uuid)
        if (char != null) {
            activeGatt?.readCharacteristic(char)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions() // Kick off the dialog on first launch
        setContent {
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            var status by remember { mutableStateOf("Ready") }

            // If we just rotated into Landscape, ensure the motor is OFF
            // so the Sequencer can take clean control.
            LaunchedEffect(isLandscape) {
                if (isLandscape && isPlaying) {
                    isPlaying = false
                    writeBoolean(PLAY_STOP_UUID, false)
                    writeInt32(SPEED_UUID, 1000) // Reset to 1.0x neutral
                }
            }

            if (isLandscape) {
                LandscapeSequencer(
                    isPlaying = isPlaying, // Passes the Activity state DOWN
                    onTogglePlay = { requestedPlayState ->
                        if (requestedPlayState) {
                            // STARTING
                            isPlaying = true
                            writeBoolean(PLAY_STOP_UUID, true)
                        } else {
                            // NUCLEAR STOPPING SEQUENCE
                            isPlaying = false // Kill the Sequencer's 'while' loop immediately

                            lifecycleScope.launch {
                                // 1. Send the STOP command first
                                writeBoolean(PLAY_STOP_UUID, false)
                                delay(50) // Give BLE stack time to breathe

                                // 2. Force the neutral speed reset
                                writeInt32(SPEED_UUID, 1000)

                                // 3. Final verification write
                                delay(50)
                                writeBoolean(PLAY_STOP_UUID, false)
                            }
                        }
                    },
                    onSpeedUpdate = { writeInt32(SPEED_UUID, it) },
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
                    isPlaying = isPlaying,
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

                    // FIX: Update the Activity state AND send the BLE command
                    onTogglePlay = { requestedState ->
                        if (isPlaying != requestedState) {
                            isPlaying = requestedState
                            writeBoolean(PLAY_STOP_UUID, requestedState)
                        }
                    },
                    onUpdateSpeed = { writeInt32(SPEED_UUID, it) }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Disconnect and close the GATT client to prevent zombie connections
        activeGatt?.let { gatt ->
            gatt.disconnect()
            gatt.close()
        }
        activeGatt = null
        isConnected = false
        Log.d("BLE_DEBUG", "Activity Destroyed: GATT closed and resources released.")
    }

    override fun onStop() {
        super.onStop()
        // If we aren't playing, don't leave the connection 'dangling'
        if (!isPlaying) {
            Log.d("BLE_DEBUG", "App stopped. Cleaning up BLE to prevent zombies.")
            activeGatt?.disconnect()
            activeGatt?.close()
            activeGatt = null
            isConnected = false
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
        val rightPaddingPx = leftPaddingPx
        val bottomBarHeightDp = if (isPlaying) 60.dp else 100.dp
        val bottomBarHeightPx = with(density) { bottomBarHeightDp.toPx() }
        var zoomFactor by remember { mutableFloatStateOf(1f) }
        var panOffset by remember { mutableFloatStateOf(0f) }
        val automationPath = remember { Path() }

        var draggedNodeIndex by remember { mutableIntStateOf(-1) }
        var playbackProgress by remember { mutableFloatStateOf(0f) }
        var lastSelectedIndex by remember { mutableIntStateOf(-1) }

        var showSaveDialog by remember { mutableStateOf(false) }
        var saveNameInput by remember { mutableStateOf("") }
        var sequenceToDelete by remember { mutableStateOf<String?>(null) }
        val context = androidx.compose.ui.platform.LocalContext.current
        var pendingAction by remember { mutableStateOf<String?>(null) } // "NEW" or a Sequence Name
        var showLoadMenu by remember { mutableStateOf(false) }
        var savedSequences by remember { mutableStateOf(getAllSavedNames(context)) }
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
            val defaultNodes = listOf(AutomationNode(0f, 1f), AutomationNode(1f, 1f))
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
            if (isPlaying) {
                var lastFrameTime = System.currentTimeMillis()
                var currentProgress = 0f

                while (isPlaying) {
                    val now = System.currentTimeMillis()
                    val deltaTime = (now - lastFrameTime) / 1000f
                    lastFrameTime = now

                    // PROGRESS ADVANCES SLOWER WHEN ZOOMED IN
                    // (e.g., if zoomFactor is 2.0, delta is halved)
                    val progressDelta = (deltaTime / durationSeconds) / zoomFactor
                    currentProgress += progressDelta
                    playbackProgress = currentProgress.coerceIn(0f, 1f)

                    if (playbackProgress >= 1f) {
                        onTogglePlay(false)
                        break
                    }

                    // MOTOR FREQUENCY IS SCALED BY ZOOM
                    val baseSpeed = calculateSpeedAtTime(playbackProgress, nodes)
                    val actualSpeed = baseSpeed / zoomFactor
                    onSpeedUpdate((actualSpeed * 1000).toInt())

                    delay(16)
                }
            } else {
                playbackProgress = 0f
                onSpeedUpdate(1000)
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
                            var currentSize = getUsableSize(size, zoomFactor)

                            // 1. Hit Test
                            val hitIndex = nodes.indexOfFirst {
                                val nodeX = leftPaddingPx - panOffset + (it.timePercent * currentSize.first)
                                val nodeY = edgePaddingPx + (1f - (it.speed / 2f)) * currentSize.second
                                val dx = nodeX - down.position.x
                                val dy = nodeY - down.position.y
                                (dx * dx + dy * dy) < 10000f
                            }
                            if (hitIndex != -1) { lastSelectedIndex = hitIndex }

                            // 2. Decision Engine
                            var isZooming = false
                            var isPanning = false
                            var upEvent: PointerInputChange? = null

                            // If we touched the node that's already yellow, we don't want to wait!
                            val isInstantDrag = hitIndex != -1 && hitIndex == lastSelectedIndex

                            val isLongPress = if (isInstantDrag) {
                                false // We don't need the timer for an instant drag
                            } else {
                                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.changes.size > 1) { isZooming = true; break }
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || !change.pressed) { upEvent = change; break }

                                        val totalDrag = (change.position - down.position).getDistance()
                                        if (hitIndex == -1 && totalDrag > touchSlop) { isPanning = true; break }
                                        if (hitIndex != -1 && totalDrag > touchSlop * 5) { isPanning = true; break }

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
                            } else if ((isLongPress || isInstantDrag) && hitIndex != -1) {
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
                                        val (uW, uH) = getUsableSize(size, zoomFactor)

                                        val rawT = ((dragChange.position.x + panOffset - leftPaddingPx) / uW).coerceIn(0f, 1f)
                                        val newS = (1f - ((dragChange.position.y - edgePaddingPx) / uH)).coerceIn(0f, 1f) * 2f

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
                                        val (uW, uH) = getUsableSize(size, zoomFactor)
                                        if (hitIndex != -1) {
                                            // Delete node (except ends)
                                            if (hitIndex != 0 && hitIndex != nodes.size - 1) {
                                                val newList = nodes.toMutableList().apply { removeAt(hitIndex) }
                                                onNodesChange(newList)
                                                lastSelectedIndex = -1
                                            }
                                        } else {
                                            // Create node
                                            val t = ((up.position.x + panOffset - leftPaddingPx) / uW).coerceIn(0f, 1f)
                                            val s = (1f - ((up.position.y - edgePaddingPx) / uH)).coerceIn(0f, 1f) * 2f

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
                var (usableWidth, usableHeight) = getUsableSize(size, zoomFactor)
                val gridColor = Color.White.copy(alpha = 0.15f) // Subtle but visible

                // 2. DRAW HORIZONTAL GRID (Speed)
                for (i in 0..4) {
                    val y = edgePaddingPx + (usableHeight * (i / 4f))
                    drawLine(
                        color = gridColor,
                        start = Offset(leftPaddingPx - panOffset, y),
                        end = Offset(leftPaddingPx - panOffset + usableWidth, y),
                        strokeWidth = 1.dp.toPx()
                    )

                    // Speed Labels (0.0, 0.5, 1.0, 1.5, 2.0)
                    val speedVal = 2.0f - (i * 0.5f)
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
                nodes.forEachIndexed { index, _nodes ->
                    // USE THE USABLE DIMENSIONS HERE
                    val centerX = leftPaddingPx - panOffset + (_nodes.timePercent * usableWidth)
                    val centerY = edgePaddingPx + (1f - (_nodes.speed / 2f)) * usableHeight

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
                        val timeValue = _nodes.timePercent * durationSeconds
                        val label = String.format(Locale.getDefault(), "%.1fs, %.2fHz", timeValue, _nodes.speed)

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
                    if (savedSequences.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No saved sequences", color = Color.Gray) },
                            onClick = { showLoadMenu = false }
                        )
                    }

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
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
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
        isPlaying: Boolean,
        currentSpeed: Int,
        status: String,
        onScan: () -> Unit,
        onTogglePlay: (Boolean) -> Unit,
        onUpdateSpeed: (Int) -> Unit
    ) {
        // Timestamped user interactions to avoid race conditions with HW
        var lastUserTouchTime by remember { mutableLongStateOf(0L) }

        // State
        var knobValue by remember { mutableFloatStateOf(currentSpeed / 1000f) }
        val haptics = LocalHapticFeedback.current

        // Hardware sync
        LaunchedEffect(currentSpeed) {
            val timeSinceTouch = System.currentTimeMillis() - lastUserTouchTime
            // Only let the hardware override the UI if the user hasn't touched it
            // in the last 1000ms (1 second)
            if (timeSinceTouch > 1000) {
                knobValue = currentSpeed / 1000f
            }
        }

        // Debounce
        LaunchedEffect(knobValue) {
            delay(300)
            val speedToSend = (knobValue * 1000).toUInt().toInt()
            onUpdateSpeed(speedToSend)

            if (knobValue == 0f && isPlaying) {
                writeBoolean(PLAY_STOP_UUID, false)
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
                val isEffectivelyStopped = !isPlaying || knobValue < 0.01f
                Spacer(modifier = Modifier.height(32.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (knobValue < 0.01f) "STOPPED" else String.format(
                            Locale.getDefault(),
                            "%.2f Hz",
                            knobValue
                        ),
                        style = MaterialTheme.typography.headlineLarge,
                        color = if (knobValue < 0.01f) Color.Red else Color.Black
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    RotaryKnob(
                        value = knobValue,
                        onValueChange = { newValue ->
                            // Update the interaction timer
                            lastUserTouchTime = System.currentTimeMillis()

                            // Update the state
                            knobValue = newValue
                        }
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        modifier = Modifier.fillMaxWidth(0.6f),
                        onClick = {
                            lastUserTouchTime = System.currentTimeMillis() // LOCK the sync
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (isEffectivelyStopped) {
                                if (knobValue < 0.01f) knobValue = 0.1f
                                onTogglePlay(true)
                            } else {
                                onTogglePlay(false)
                            }
                        }) {
                        // This will toggle to "PLAY" when knobValue hits 0.0
                        Text(if (isEffectivelyStopped) "PLAY" else "STOP")
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startReliableScan(onDeviceFound: (BluetoothDevice) -> Unit) {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bluetoothAdapter?.bluetoothLeScanner

        // --- NUCLEAR STEP 1: KILL SYSTEM ZOMBIES ---
        // This finds devices the OS thinks are connected, even if our app just started
        val alreadyConnected = manager.getConnectedDevices(BluetoothProfile.GATT)
        alreadyConnected.forEach { device ->
            if (device.name?.contains("Plinky", ignoreCase = true) == true) {
                Log.d("BLE_DEBUG", "Nuclear: Killing system zombie at ${device.address}")
                // We must connect to it just to call disconnect/close to free the radio
                val tempGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {})
                tempGatt.disconnect()
                tempGatt.close()
            }
        }

        // --- NUCLEAR STEP 2: AGGRESSIVE RECOVERY ---
        // Look for "Bonded" (paired) devices that might be hanging
        bluetoothAdapter?.bondedDevices?.forEach { device ->
            if (device.name?.contains("Plinky", ignoreCase = true) == true) {
                Log.d("BLE_DEBUG", "Nuclear: Found bonded device, attempting direct connect")
                onDeviceFound(device)
                return // Skip scanning if we found a bonded match
            }
        }

        // Standard aggressive scan as fallback
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

    // Helper: Write 1 byte (Boolean)
    private fun writeBoolean(charUuid: UUID, value: Boolean) {
        val service = activeGatt?.getService(SERVICE_UUID)
        val char = service?.getCharacteristic(charUuid)
        if (char != null) {
            val bytes = if (value) byteArrayOf(0x01) else byteArrayOf(0x00)
            activeGatt?.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
    }

    // Helper: Write 4 bytes (Int32 Little Endian)
    private fun writeInt32(charUuid: UUID, value: Int) {
        // If it's a speed update and the sequence has ended/stopped, block the write
        if (charUuid == SPEED_UUID && !isPlaying) {
            Log.d("BLE_DEBUG", "Blocked speed write because motor is STOPPED")
            return
        }
        val gatt = activeGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val char = service.getCharacteristic(charUuid) ?: return

        // Ensure we aren't overwhelming the radio
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val bytes = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )

        if (isConnected) {
            try {
                gatt.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } catch (e: Exception) {
                Log.e("BLE_ERROR", "Write failed: ${e.message}")
            }
        }
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

        prefs.edit()
            .putString("nodes_$name", encodedNodes)
            .putFloat("dur_$name", duration)
            .putFloat("tension_$name", tension)
            .apply()
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
        prefs.edit()
            .remove("nodes_$name")
            .remove("dur_$name")
            .remove("tension_$name")
            .apply()
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
    val visualAngle = startAngle + (value / 2.0f) * sweepAngle

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

                    val newValue = (snappedAngle / 270f) * 2.0f

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
                    updatedOnValueChange(newValue.coerceIn(0f, 2f))
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
    rightPadding: Float,
    panOffset: Float,
    uWidth: Float,
    uHeight: Float,
    tension: Float = DEFAULT_TENSION
) {
    if (nodes.size < 2) return
    path.reset()

    fun getCoord(node: AutomationNode): Offset {
        return Offset(
            leftPadding - panOffset + (node.timePercent * uWidth),
            padding + (1f - (node.speed / 2f)) * uHeight
        )
    }

    val startPoint = getCoord(nodes[0])
    path.moveTo(startPoint.x, startPoint.y)

    for (i in 0 until nodes.size - 1) {
        val p0 = getCoord(nodes[i])
        val p1 = getCoord(nodes[i + 1])

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