package io.github.tuthan.stationar.receiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.github.tuthan.stationar.AppLog

/**
 * Tells the LAN where the receiver is while AR is open: `_stationar._tcp` over mDNS, with the protocol version and
 * the start of the certificate's fingerprint, so `station-ar-send --discover` finds the Station without an address.
 */
class Advertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var registered: NsdManager.RegistrationListener? = null

    fun start(port: Int, fingerprint: String) {
        if (registered != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "Station AR"
            serviceType = TYPE
            this.port = port
            setAttribute("v", "1")
            setAttribute("fp", fingerprint.take(16))
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = AppLog.i("receiver: advertised as \"${info.serviceName}\"")
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = AppLog.w("receiver: advertising failed ($error)")
            override fun onServiceUnregistered(info: NsdServiceInfo) = AppLog.i("receiver: advertisement removed")
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = AppLog.w("receiver: removing the advertisement failed ($error)")
        }
        registered = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    fun stop() {
        val l = registered ?: return
        registered = null
        try {
            nsd.unregisterService(l)
        } catch (e: IllegalArgumentException) {
            // Never registered (it failed).
        }
    }

    companion object {
        const val TYPE = "_stationar._tcp"
    }
}
