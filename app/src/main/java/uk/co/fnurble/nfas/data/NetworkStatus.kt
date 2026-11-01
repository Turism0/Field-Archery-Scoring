package uk.co.fnurble.nfas.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the server answered the last time the app tried. */
enum class ServerState { UNKNOWN, AVAILABLE, UNAVAILABLE }

/**
 * Tells apart "this device is offline" (no usable internet connection) from "the server is unavailable"
 * (the device is online but the server didn't answer, or answered 502/503/504 because it's down).
 * Shown as small notices on the home screen and used to word error messages.
 */
class NetworkStatus(context: Context) {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _online = MutableStateFlow(checkOnline())
    /** True when Android reports a working internet connection (Wi-Fi or mobile data). */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _server = MutableStateFlow(ServerState.UNKNOWN)
    val server: StateFlow<ServerState> = _server.asStateFlow()

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _online.value = caps.isUsable()
            }

            override fun onLost(network: Network) {
                _online.value = checkOnline()
            }
        })
    }

    /** Called by the API client after every call. */
    fun serverAnswered() { _server.value = ServerState.AVAILABLE }

    /** Called by the API client when the server couldn't be reached (only counts if the device is online). */
    fun serverFailed() {
        _online.value = checkOnline()
        if (_online.value) _server.value = ServerState.UNAVAILABLE
    }

    private fun checkOnline(): Boolean =
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.isUsable() == true

    private fun NetworkCapabilities.isUsable() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
