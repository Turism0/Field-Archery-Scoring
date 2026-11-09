package uk.co.fnurble.fieldarchery.data

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** What phone to phone is doing, for the banner. */
data class NearbyStatus(
    /** Not allowed "Nearby devices" (or location on older phones): phone to phone is off. */
    val needsPermission: Boolean = false,
    val bluetoothOff: Boolean = false,
    val wifiOff: Boolean = false,
    /** Host: advertising the group. Others: looking for the host's phone. */
    val running: Boolean = false,
    /** Host: phones connected now. Others: 1 when connected to the host's phone. */
    val connected: Int = 0,
    /** Starting failed (the radios' own message), if it did. */
    val problem: String? = null,
)

/**
 * Phone to phone for live groups (app 2.3), with Google Nearby Connections (Bluetooth and Wi-Fi, no signal needed).
 * The host's phone is the hub (P2P_STAR): it advertises "FAH:<code>:<groupId>"; the others find it by group id (or by
 * code when joining) and connect. Messages are JSON (the same shapes as the server's), gzipped, one per payload.
 * Works while the app is open.
 */
class NearbyLink(private val context: Context) {
    sealed interface Target {
        /** The host's phone: advertise this group. */
        data class Host(val groupId: String, val code: String) : Target
        /** Another phone: find the host of [groupId], or (joining) the group with [code]. */
        data class Member(val groupId: String?, val code: String, val myName: String) : Target
    }

    interface Listener {
        fun onConnected(endpointId: String)
        fun onDisconnected(endpointId: String)
        fun onMessage(endpointId: String, message: JSONObject)
    }

    private val client: ConnectionsClient by lazy { Nearby.getConnectionsClient(context) }
    private var target: Target? = null
    private var listener: Listener? = null
    private val connected = linkedSetOf<String>()
    private var connecting: String? = null
    private var advertising = false
    private var discovering = false
    private var problem: String? = null

    private val _status = MutableStateFlow(NearbyStatus())
    val status: StateFlow<NearbyStatus> = _status.asStateFlow()

    val endpoints: Set<String> @Synchronized get() = connected.toSet()

    /** Follow [target] (null stops). Safe to call again with the same target, e.g. every few seconds. */
    @Synchronized
    fun use(target: Target?, listener: Listener?) {
        val old = this.target
        if (target != old) {
            when {
                // Just joined: same host, now known by group id. Keep the connection.
                old is Target.Member && target is Target.Member && old.groupId == null && old.code == target.code -> {}
                // The host's code changed (taken on the server): advertise the new one, keep the phones connected.
                old is Target.Host && target is Target.Host && old.groupId == target.groupId -> {
                    if (advertising) client.stopAdvertising()
                    advertising = false
                }
                else -> stopAll()
            }
            this.target = target
        }
        this.listener = listener
        ensure()
    }

    /** Starts advertising or discovery if they should be running and aren't (e.g. permission just given). */
    @Synchronized
    fun ensure() {
        val t = target
        val missing = !hasPermissions(context)
        val bt = bluetoothOn()
        val wifi = wifiOn()
        if (t != null && !missing) {
            when (t) {
                is Target.Host -> if (!advertising) startAdvertising(t)
                is Target.Member -> if (connected.isEmpty() && connecting == null && !discovering) startDiscovery()
            }
        }
        _status.value = NearbyStatus(
            needsPermission = t != null && missing,
            bluetoothOff = !bt,
            wifiOff = !wifi,
            running = advertising || discovering || connected.isNotEmpty(),
            connected = connected.size,
            problem = problem,
        )
    }

    @Synchronized
    fun stop() = use(null, null)

    /** Sends one message to one phone (dropped if it's gone). */
    fun send(endpointId: String, message: JSONObject) = send(listOf(endpointId), message)

    fun send(endpointIds: Collection<String>, message: JSONObject) {
        if (endpointIds.isEmpty()) return
        val bytes = gzip(message.toString())
        if (bytes.size > ConnectionsClient.MAX_BYTES_DATA_SIZE) {
            Log.w(TAG, "Message too big for Nearby (${bytes.size} bytes), not sent")
            return
        }
        client.sendPayload(endpointIds.toList(), Payload.fromBytes(bytes))
            .addOnFailureListener { Log.w(TAG, "Send failed: ${it.message}") }
    }

    fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
        lost(endpointId)
    }

    // ---- Nearby ----

    private fun stopAll() {
        if (advertising) client.stopAdvertising()
        if (discovering) client.stopDiscovery()
        client.stopAllEndpoints()
        val gone = connected.toList()
        connected.clear()
        connecting = null
        advertising = false
        discovering = false
        problem = null
        gone.forEach { listener?.onDisconnected(it) }
    }

    private fun startAdvertising(t: Target.Host) {
        advertising = true
        client.startAdvertising(
            "$HOST_PREFIX${t.code}:${t.groupId}", SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build(),
        ).addOnSuccessListener { synchronized(this) { problem = null; ensure() } }
            .addOnFailureListener { e -> synchronized(this) { advertising = false; problem = e.message; ensure() } }
    }

    private fun startDiscovery() {
        discovering = true
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build())
            .addOnSuccessListener { synchronized(this) { problem = null; ensure() } }
            .addOnFailureListener { e -> synchronized(this) { discovering = false; problem = e.message; ensure() } }
    }

    /** Does an advertised name belong to the group we're after? */
    private fun matches(name: String): Boolean {
        val t = target as? Target.Member ?: return false
        if (!name.startsWith(HOST_PREFIX)) return false
        val parts = name.removePrefix(HOST_PREFIX).split(":", limit = 2)
        if (parts.size != 2) return false
        return if (t.groupId != null) parts[1] == t.groupId else parts[0] == t.code
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            synchronized(this@NearbyLink) {
                val t = target as? Target.Member ?: return
                if (!matches(info.endpointName) || connecting != null || connected.isNotEmpty()) return
                connecting = endpointId
                client.requestConnection("$MEMBER_PREFIX${t.myName.take(30)}", endpointId, lifecycle)
                    .addOnFailureListener { e ->
                        synchronized(this@NearbyLink) {
                            if (connecting == endpointId) connecting = null
                            problem = e.message
                            ensure()
                        }
                    }
            }
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            synchronized(this@NearbyLink) {
                // The host takes any phone of ours (it checks the group code in their HELLO); others only the host.
                val ok = when (target) {
                    is Target.Host -> info.endpointName.startsWith(MEMBER_PREFIX)
                    is Target.Member -> matches(info.endpointName)
                    null -> false
                }
                if (ok) client.acceptConnection(endpointId, payloads) else client.rejectConnection(endpointId)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val l: Listener?
            synchronized(this@NearbyLink) {
                if (connecting == endpointId) connecting = null
                if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                    ensure()
                    return
                }
                connected += endpointId
                // One host is all a member needs: stop looking (saves battery).
                if (target is Target.Member && discovering) {
                    client.stopDiscovery()
                    discovering = false
                }
                l = listener
                ensure()
            }
            l?.onConnected(endpointId)
        }

        override fun onDisconnected(endpointId: String) = lost(endpointId)
    }

    private fun lost(endpointId: String) {
        val l: Listener?
        synchronized(this) {
            if (!connected.remove(endpointId)) return
            l = listener
            ensure()   // a member starts looking for the host again
        }
        l?.onDisconnected(endpointId)
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val json = runCatching { JSONObject(gunzip(payload.asBytes()!!)) }.getOrNull() ?: return
            val l = synchronized(this@NearbyLink) { listener }
            l?.onMessage(endpointId, json)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun bluetoothOn(): Boolean = runCatching {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true
    }.getOrDefault(true)

    private fun wifiOn(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
    }.getOrDefault(true)

    companion object {
        private const val TAG = "NearbyLink"
        const val SERVICE_ID = "uk.co.fnurble.fieldarchery.group"
        private const val HOST_PREFIX = "FAH:"
        private const val MEMBER_PREFIX = "FAJ:"

        /** The runtime permissions phone to phone needs on this version of Android. */
        fun permissions(): Array<String> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
            // Finding other phones over Bluetooth also needs location (the app already asks for it for shoot locations).
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray()

        fun hasPermissions(context: Context) = permissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        private fun gzip(text: String): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            return out.toByteArray()
        }

        private fun gunzip(bytes: ByteArray): String =
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
