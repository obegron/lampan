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

data class AirPlayDevice(
    val name: String,
    val ip: String,
    val port: Int
)

class AirPlayDiscovery(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val SERVICE_TYPE = "_raop._tcp."
    private val TAG = "AirPlayDiscovery"
    
    // Semaphore to serialize resolve requests (Android limit is often 1 concurrent resolve)
    private val resolveSemaphore = Semaphore(1)

    fun discoverDevices(): Flow<List<AirPlayDevice>> = callbackFlow {
        val foundDevices = mutableMapOf<String, AirPlayDevice>()
        
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: $service")
                // Use a coroutine or thread to resolve to avoid blocking the NsdManager callback thread?
                // NsdManager callbacks are usually on main or binder thread. Blocking them is bad.
                // We'll start a thread to resolve.
                Thread {
                    resolveServiceSafe(service) { device ->
                        if (device != null) {
                            foundDevices[device.name] = device
                            trySend(foundDevices.values.toList())
                        }
                    }
                }.start()
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e(TAG, "service lost: $service")
                foundDevices.remove(service.serviceName)
                trySend(foundDevices.values.toList())
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start Discovery failed: Error code:$errorCode")
                try {
                    nsdManager.stopServiceDiscovery(this)
                } catch (e: Exception) {
                    // Ignore if already stopped or not started
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop Discovery failed: Error code:$errorCode")
                try {
                    nsdManager.stopServiceDiscovery(this)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate discovery", e)
            close(e)
        }

        awaitClose {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }
    }

    private val resolveExecutor: Executor = Executors.newSingleThreadExecutor()

    private fun resolveServiceSafe(service: NsdServiceInfo, callback: (AirPlayDevice?) -> Unit) {
        try {
            resolveSemaphore.acquire()
            
            if (Build.VERSION.SDK_INT >= 34) {
                nsdManager.registerServiceInfoCallback(service, resolveExecutor, object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        Log.e(TAG, "ServiceInfoCallback registration failed: $errorCode")
                        resolveSemaphore.release()
                        callback(null)
                    }

                    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Service resolved (API 34+): $serviceInfo")
                        val host = serviceInfo.hostAddresses.firstOrNull()
                        val ip = host?.hostAddress
                        val port = serviceInfo.port
                        val name = serviceInfo.serviceName
                        
                        // Unregister immediately after resolving to avoid leaks/updates we don't need
                        try {
                            nsdManager.unregisterServiceInfoCallback(this)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error unregistering callback", e)
                        }
                        
                        resolveSemaphore.release()
                        callback(AirPlayDevice(name, ip ?: "Unknown", port))
                    }

                    override fun onServiceLost() {
                        Log.e(TAG, "Service lost during resolution")
                        // We might not need to release here if we unregister? 
                        // But strictly speaking if we haven't resolved yet, we should.
                        // However, onServiceLost usually happens *after* resolution if tracking updates.
                        // For a one-shot resolve, typically onServiceUpdated is what we want.
                    }

                    override fun onServiceInfoCallbackUnregistered() {
                        // Cleanup if needed
                    }
                })
            } else {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e(TAG, "Resolve failed: $errorCode")
                        resolveSemaphore.release()
                        callback(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Resolve Succeeded. $serviceInfo")
                        val host = serviceInfo.host
                        val ip = host.hostAddress
                        val port = serviceInfo.port
                        val name = serviceInfo.serviceName
                        
                        resolveSemaphore.release()
                        callback(AirPlayDevice(name, ip ?: "Unknown", port))
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during resolve", e)
            resolveSemaphore.release()
            callback(null)
        }
    }
}
