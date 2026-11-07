package com.interfree.vmess

import android.net.VpnService
import android.content.Intent
import android.os.ParcelFileDescriptor

class InterfreeVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Placeholder only: the Xray Core data plane must be integrated before this
                // service can provide a working VMess connection.
            }
            ACTION_STOP -> stopTunnel()
        }
        return START_NOT_STICKY
    }

    private fun stopTunnel() {
        vpnInterface?.close()
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.interfree.vmess.START"
        const val ACTION_STOP = "com.interfree.vmess.STOP"
    }
}
