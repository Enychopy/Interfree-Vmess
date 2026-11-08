package com.interfree.vmess

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import libXray.DialerController
import libXray.LibXray
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

class InterfreeVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var xrayStarted = false

    @Volatile
    private var dnsConfigured = false

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                val uuid = intent.getStringExtra(EXTRA_UUID).orEmpty()
                startInForeground()
                worker.execute {
                    startTunnel(uuid)
                }
            }
            ACTION_STOP -> {
                worker.execute {
                    stopTunnel()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val channelId = "interfree_vpn"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "INTERFREE VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("INTERFREE VPN")
                .setContentText("Servicio VPN en ejecución")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("INTERFREE VPN")
                .setContentText("Servicio VPN en ejecución")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setOngoing(true)
                .build()
        }

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startTunnel(uuid: String) {
        try {
            try {
                UUID.fromString(uuid)
            } catch (_: Exception) {
                throw IllegalArgumentException("El UUID guardado no es válido. Revísalo en MI CUENTA.")
            }

            stopXrayIfNeeded()

            try {
                vpnInterface?.close()
            } catch (_: Exception) {}

            val tun = Builder()
                .setSession("INTERFREE VPN")
                .setMtu(1500)
                .addAddress("172.19.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .establish()
                ?: throw IllegalStateException("Android no permitió crear la interfaz VPN")

            vpnInterface = tun

            val controller = object : DialerController {
                override fun protectFd(fd: Long): Boolean {
                    return this@InterfreeVpnService.protect(fd.toInt())
                }
            }

            LibXray.touch()
            LibXray.registerDialerController(controller)
            LibXray.registerListenerController(controller)
            LibXray.setDNS(controller, "1.1.1.1:53")
            dnsConfigured = true

            val xrayConfig = buildXrayConfig(uuid, tun.fd)
            
            val request = JSONObject()
                .put("apiVersion", 3)
                .put("method", "runXray")
                .put("payload", JSONObject().put("xrayJson", xrayConfig.toString()))

            val response = JSONObject(LibXray.invoke(request.toString()))

            if (!response.optBoolean("success", false)) {
                val error = response.optString("error", "Xray falló al arrancar")
                throw IllegalStateException(error)
            }

            xrayStarted = true
            broadcastState("CONECTANDO", "Motor Xray iniciado. Verificando tráfico real...")

            var isConnected = false
            for (i in 1..4) {
                try {
                    val url = URL("http://connectivitycheck.gstatic.com/generate_204")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    if (connection.responseCode == 204 || connection.responseCode == 200) {
                        isConnected = true
                        break
                    }
                } catch (e: Exception) {
                    Thread.sleep(1500)
                }
            }

            if (isConnected) {
                broadcastState("CONECTADO", "Conexión VMess establecida. Tráfico fluyendo.")
            } else {
                throw IllegalStateException("Túnel abierto sin acceso a Internet. Revisa tu UUID o el estado del VPS.")
            }

        } catch (e: Exception) {
            stopTunnel()
            broadcastState("DESCONECTADO", e.message ?: "Error al conectar")
        }
    }

    private fun buildXrayConfig(uuid: String, tunFd: Int): JSONObject {
        val vmessUser = JSONObject()
            .put("id", uuid)
            .put("alterId", 0)
            .put("security", "auto")

        val vmessSettings = JSONObject().put("vnext", JSONArray().put(
            JSONObject()
                .put("address", SERVER_ADDRESS)
                .put("port", 443)
                .put("users", JSONArray().put(vmessUser))
        ))

        val tlsSettings = JSONObject()
            .put("serverName", TLS_SNI)
            .put("fingerprint", "chrome")
            .put("alpn", JSONArray().put("h2").put("http/1.1"))

        val wsSettings = JSONObject()
            .put("path", "/")
            .put("headers", JSONObject().put("Host", WS_HOST))

        val streamSettings = JSONObject()
            .put("network", "ws")
            .put("security", "tls")
            .put("tlsSettings", tlsSettings)
            .put("wsSettings", wsSettings)

        // AQUÍ ESTÁ LA CORRECCIÓN CLAVE PARA EVITAR EL ERROR DE NETLINKRIB
        val inbound = JSONObject()
            .put("tag", "tun-in")
            .put("port", 0)
            .put("protocol", "tun")
            .put("settings", JSONObject()
                .put("mtu", 1500)
                .put("autoRoute", false)
                .put("strictRoute", false)
            )
            .put("sniffing", JSONObject()
                .put("enabled", true)
                .put("destOverride", JSONArray().put("http").put("tls"))
            )

        val outboundProxy = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vmess")
            .put("settings", vmessSettings)
            .put("streamSettings", streamSettings)

        val outboundDirect = JSONObject()
            .put("tag", "direct")
            .put("protocol", "freedom")

        val routing = JSONObject()
            .put("domainStrategy", "AsIs")
            .put("rules", JSONArray().put(
                JSONObject()
                    .put("type", "field")
                    .put("outboundTag", "proxy")
                    .put("network", "tcp,udp")
            ))

        val dns = JSONObject()
            .put("servers", JSONArray().put("1.1.1.1").put("8.8.8.8"))

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("dns", dns)
            .put("env", JSONObject().put("xray.tun.fd", tunFd.toString()))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outboundProxy).put(outboundDirect))
            .put("routing", routing)
    }

    private fun stopXrayIfNeeded() {
        if (xrayStarted) {
            try {
                val request = JSONObject()
                    .put("apiVersion", 3)
                    .put("method", "stopXray")
                    .put("payload", JSONObject())
                LibXray.invoke(request.toString())
            } catch (_: Exception) {} 
            finally {
                xrayStarted = false
            }
        }
        if (dnsConfigured) {
            try {
                LibXray.resetDNS()
            } catch (_: Exception) {}
            dnsConfigured = false
        }
    }

    private fun stopTunnel() {
        stopXrayIfNeeded()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
        
        vpnInterface = null
        stopForegroundCompat()
        stopSelf()
    }

    private fun broadcastState(state: String, message: String) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_MESSAGE, message)
        )
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        stopTunnel()
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.interfree.vmess.START"
        const val ACTION_STOP = "com.interfree.vmess.STOP"
        const val ACTION_STATUS = "com.interfree.vmess.STATUS"
        const val EXTRA_UUID = "uuid"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"

        private const val NOTIFICATION_ID = 4107
        private const val SERVER_ADDRESS = "103.232.95.108"
        private const val TLS_SNI = "www.googletagmanager.com"
        private const val WS_HOST = "interfree-vpn.duckdns.org"
    }
}
