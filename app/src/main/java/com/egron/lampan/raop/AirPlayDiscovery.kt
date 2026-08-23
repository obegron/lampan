package com.egron.lampan.raop

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

enum class AirPlayProtocol {
    AIRPLAY_1,
    AIRPLAY_2,
}

data class AirPlayDevice(
    val name: String,
    val ip: String,
    val airPlay1Port: Int? = null,
    val airPlay2Port: Int? = null,
    val airPlay2RequiresPassword: Boolean? = null,
    val receiverId: String? = null,
    val protocolPreference: AirPlayProtocol? = null,
) {
    val preferredProtocol: AirPlayProtocol
        get() = when {
            protocolPreference == AirPlayProtocol.AIRPLAY_2 && airPlay2Port != null ->
                AirPlayProtocol.AIRPLAY_2
            protocolPreference == AirPlayProtocol.AIRPLAY_1 && airPlay1Port != null ->
                AirPlayProtocol.AIRPLAY_1
            airPlay1Port != null -> AirPlayProtocol.AIRPLAY_1
            else -> AirPlayProtocol.AIRPLAY_2
        }

    fun portFor(protocol: AirPlayProtocol): Int? = when (protocol) {
        AirPlayProtocol.AIRPLAY_1 -> airPlay1Port
        AirPlayProtocol.AIRPLAY_2 -> airPlay2Port
    }

    val protocolLabel: String
        get() = when {
            airPlay1Port != null && airPlay2Port != null -> "AirPlay 1 + 2"
            airPlay2Port != null -> "AirPlay 2"
            else -> "AirPlay 1"
        }
}

internal fun mergeAirPlayDevice(
    current: AirPlayDevice?,
    discovered: AirPlayDevice,
): AirPlayDevice {
    if (current == null) return discovered
    require(current.ip == discovered.ip) { "Only records at the same address can be merged" }
    val discoveredHasAirPlay2 = discovered.airPlay2Port != null
    return AirPlayDevice(
        name = if (discoveredHasAirPlay2) discovered.name else current.name,
        ip = current.ip,
        airPlay1Port = discovered.airPlay1Port ?: current.airPlay1Port,
        airPlay2Port = discovered.airPlay2Port ?: current.airPlay2Port,
        airPlay2RequiresPassword = if (discoveredHasAirPlay2) {
            discovered.airPlay2RequiresPassword
        } else {
            current.airPlay2RequiresPassword
        },
        receiverId = discovered.receiverId ?: current.receiverId,
        protocolPreference = current.protocolPreference ?: discovered.protocolPreference,
    )
}

class AirPlayDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val resolveSemaphore = Semaphore(1)
    private val resolveExecutor: Executor = Executors.newSingleThreadExecutor()

    fun discoverDevices(): Flow<List<AirPlayDevice>> = callbackFlow {
        val foundDevices = mutableMapOf<String, AirPlayDevice>()
        val serviceAddresses = mutableMapOf<String, String>()

        fun publish(device: AirPlayDevice, serviceKey: String) {
            val devices = synchronized(foundDevices) {
                serviceAddresses[serviceKey] = device.ip
                foundDevices[device.ip] = mergeAirPlayDevice(foundDevices[device.ip], device)
                foundDevices.values.sortedBy { it.name.lowercase() }
            }
            trySend(devices)
        }

        fun remove(service: NsdServiceInfo, kind: ServiceKind) {
            val devices = synchronized(foundDevices) {
                val address = serviceAddresses.remove(serviceKey(service, kind))
                    ?: return@synchronized null
                val current = foundDevices[address] ?: return@synchronized null
                val updated = when (kind.protocol) {
                    AirPlayProtocol.AIRPLAY_1 -> current.copy(airPlay1Port = null)
                    AirPlayProtocol.AIRPLAY_2 -> current.copy(
                        airPlay2Port = null,
                        airPlay2RequiresPassword = null,
                    )
                }
                if (updated.airPlay1Port == null && updated.airPlay2Port == null) {
                    foundDevices.remove(address)
                } else {
                    foundDevices[address] = updated
                }
                foundDevices.values.sortedBy { it.name.lowercase() }
            }
            if (devices != null) trySend(devices)
        }

        val listeners = SERVICE_KINDS.associateWith { kind ->
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.d(TAG, "${kind.label} discovery started")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    Log.d(TAG, "${kind.label} service found: $service")
                    Thread {
                        resolveServiceSafe(service, kind) { device ->
                            if (device != null) publish(device, serviceKey(service, kind))
                        }
                    }.start()
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    Log.i(TAG, "${kind.label} service lost: $service")
                    remove(service, kind)
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.i(TAG, "Discovery stopped: $serviceType")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Start discovery failed for $serviceType: $errorCode")
                    stopDiscovery(this)
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Stop discovery failed for $serviceType: $errorCode")
                    stopDiscovery(this)
                }
            }
        }

        var started = 0
        listeners.forEach { (kind, listener) ->
            try {
                nsdManager.discoverServices(
                    kind.serviceType,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener,
                )
                started++
            } catch (error: Exception) {
                Log.e(TAG, "Failed to initiate ${kind.label} discovery", error)
            }
        }
        if (started == 0) close(IllegalStateException("Could not start AirPlay discovery"))

        awaitClose { listeners.values.forEach(::stopDiscovery) }
    }

    private fun resolveServiceSafe(
        service: NsdServiceInfo,
        kind: ServiceKind,
        callback: (AirPlayDevice?) -> Unit,
    ) {
        var acquired = false
        try {
            resolveSemaphore.acquire()
            acquired = true
            if (Build.VERSION.SDK_INT >= 34) {
                nsdManager.registerServiceInfoCallback(
                    service,
                    resolveExecutor,
                    object : NsdManager.ServiceInfoCallback {
                        private var finished = false

                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                            Log.e(TAG, "Service info callback registration failed: $errorCode")
                            finish(null)
                        }

                        override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                            Log.d(TAG, "${kind.label} service resolved: $serviceInfo")
                            finish(serviceInfo.toDevice(kind))
                        }

                        override fun onServiceLost() {
                            finish(null)
                        }

                        override fun onServiceInfoCallbackUnregistered() = Unit

                        private fun finish(device: AirPlayDevice?) {
                            if (finished) return
                            finished = true
                            try {
                                nsdManager.unregisterServiceInfoCallback(this)
                            } catch (error: Exception) {
                                Log.d(TAG, "Service callback already unregistered", error)
                            }
                            resolveSemaphore.release()
                            callback(device)
                        }
                    },
                )
            } else {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Resolve failed: $errorCode")
                        resolveSemaphore.release()
                        callback(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "${kind.label} service resolved: $serviceInfo")
                        resolveSemaphore.release()
                        callback(serviceInfo.toDevice(kind))
                    }
                })
            }
        } catch (error: Exception) {
            Log.e(TAG, "Exception during resolve", error)
            if (acquired) resolveSemaphore.release()
            callback(null)
        }
    }

    private fun NsdServiceInfo.toDevice(kind: ServiceKind): AirPlayDevice? {
        val address = if (Build.VERSION.SDK_INT >= 34) {
            hostAddresses.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            host
        } ?: return null
        val ip = address.hostAddress ?: return null
        val displayName = if (kind.protocol == AirPlayProtocol.AIRPLAY_1) {
            serviceName.substringAfter('@', serviceName)
        } else {
            serviceName
        }
        val requiresPassword = if (kind.protocol == AirPlayProtocol.AIRPLAY_2) {
            attributes.entries
                .firstOrNull { it.key.equals("pw", ignoreCase = true) }
                ?.value
                ?.toString(Charsets.UTF_8)
                ?.let { it == "1" || it.equals("true", ignoreCase = true) }
        } else {
            null
        }
        // Prefer the hardware-style ID. Looking for deviceid and pi in one
        // firstOrNull made the result depend on Android's attribute iteration
        // order, even though both values can legitimately identify one receiver.
        val advertisedReceiverId = attributes.entries
            .firstOrNull { it.key.equals("deviceid", ignoreCase = true) }
            ?.value
            ?.toString(Charsets.UTF_8)
            ?: attributes.entries
                .firstOrNull { it.key.equals("pi", ignoreCase = true) }
                ?.value
                ?.toString(Charsets.UTF_8)
        val raopReceiverId = serviceName.substringBefore('@')
            .takeIf { kind.protocol == AirPlayProtocol.AIRPLAY_1 && it.length == 12 }
        return AirPlayDevice(
            name = displayName,
            ip = ip,
            airPlay1Port = port.takeIf { kind.protocol == AirPlayProtocol.AIRPLAY_1 },
            airPlay2Port = port.takeIf { kind.protocol == AirPlayProtocol.AIRPLAY_2 },
            airPlay2RequiresPassword = requiresPassword,
            receiverId = (advertisedReceiverId ?: raopReceiverId)
                ?.let(::normalizeAirPlayReceiverId),
        )
    }

    private fun stopDiscovery(listener: NsdManager.DiscoveryListener) {
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (error: Exception) {
            Log.d(TAG, "Discovery listener already stopped", error)
        }
    }

    private fun serviceKey(service: NsdServiceInfo, kind: ServiceKind): String =
        "${kind.serviceType}|${service.serviceName}"

    private data class ServiceKind(
        val serviceType: String,
        val protocol: AirPlayProtocol,
        val label: String,
    )

    private companion object {
        const val TAG = "AirPlayDiscovery"
        val SERVICE_KINDS = listOf(
            ServiceKind("_raop._tcp.", AirPlayProtocol.AIRPLAY_1, "AirPlay 1"),
            ServiceKind("_airplay._tcp.", AirPlayProtocol.AIRPLAY_2, "AirPlay 2"),
        )
    }
}
