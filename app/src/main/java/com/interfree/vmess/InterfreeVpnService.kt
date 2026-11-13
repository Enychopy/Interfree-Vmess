
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
import java.util.concurrent.Executors

class InterfreeVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var activeDialerController: DialerController? = null

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
                val profileLink =
                    intent.getStringExtra(EXTRA_PROFILE).orEmpty()

                startInForeground()

                worker.execute {
                    startTunnel(profileLink)
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

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val notification: Notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

    private fun startTunnel(profileLink: String) {
        try {
            broadcastState(
                "CONECTANDO",
                "Validando perfil VMess..."
            )

            val profile = VmessProfile.parse(profileLink)

            if (profile.network != "ws" &&
                profile.network != "tcp"
            ) {
                throw IllegalArgumentException(
                    "Este servicio admite actualmente VMess WS y TCP. " +
                    "El transporte recibido es: ${profile.network}"
                )
            }

            stopXrayIfNeeded()

            try {
                vpnInterface?.close()
            } catch (_: Exception) {
            }

            val tun = Builder()
                .setSession("INTERFREE VPN")
                .setMtu(1500)
                .addAddress("172.19.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .establish()
                ?: throw IllegalStateException(
                    "Android no permitió crear la interfaz VPN."
                )

            vpnInterface = tun

            activeDialerController = object : DialerController {
                override fun protectFd(fd: Long): Boolean {
                    return this@InterfreeVpnService.protect(
                        fd.toInt()
                    )
                }
            }

            LibXray.touch()

            LibXray.registerDialerController(
                activeDialerController
            )

            LibXray.registerListenerController(
                activeDialerController
            )

            LibXray.setDNS(
                activeDialerController,
                "1.1.1.1:53"
            )

            dnsConfigured = true

            val xrayConfig = buildXrayConfig(
                profile,
                tun.fd
            )

            val request = JSONObject()
                .put("apiVersion", 3)
                .put("method", "runXray")
                .put(
                    "payload",
                    JSONObject().put(
                        "xrayJson",
                        xrayConfig.toString()
                    )
                )

            val response = JSONObject(
                LibXray.invoke(request.toString())
            )

            if (!response.optBoolean("success", false)) {
                throw IllegalStateException(
                    response.optString(
                        "error",
                        "Xray no pudo iniciar."
                    )
                )
            }

            xrayStarted = true

            broadcastState(
                "CONECTANDO",
                "Xray inició. Comprobando tráfico por el túnel..."
            )

            if (verifyTunnelTraffic()) {
                broadcastState(
                    "CONECTADO",
                    "Prueba automática completada. Verifica también tu IP pública."
                )
            } else {
                // No detener Xray por una prueba HTTP no concluyente.
                // El usuario podrá comprobar la navegación manualmente.
                broadcastState(
                    "CONECTANDO",
                    "Xray sigue activo, pero la prueba automática no fue concluyente. " +
                    "Prueba abrir una página en Chrome y consulta tu IP pública."
                )
            }

        } catch (e: Exception) {
            val error = e.message ?: "Error desconocido"

            stopTunnel()

            broadcastState(
                "DESCONECTADO",
                error
            )
        }
    }

    private fun buildXrayConfig(
        profile: VmessProfile,
        tunFd: Int
    ): JSONObject {

        val vmessUser = JSONObject()
            .put("id", profile.uuid)
            .put("alterId", profile.alterId)
            .put("security", profile.security)

        val vmessSettings = JSONObject()
            .put(
                "vnext",
                JSONArray().put(
                    JSONObject()
                        .put("address", profile.address)
                        .put("port", profile.port)
                        .put(
                            "users",
                            JSONArray().put(vmessUser)
                        )
                )
            )

        val streamSettings = JSONObject()
            .put("network", profile.network)
            .put(
                "security",
                if (profile.tls) "tls" else "none"
            )

        if (profile.tls) {
            val tlsSettings = JSONObject()
                .put(
                    "serverName",
                    profile.sni.ifBlank {
                        profile.address
                    }
                )
                .put(
                    "fingerprint",
                    profile.fingerprint
                )

            if (profile.alpn.isNotEmpty()) {
                tlsSettings.put(
                    "alpn",
                    JSONArray(profile.alpn)
                )
            }

            streamSettings.put(
                "tlsSettings",
                tlsSettings
            )
        }

        if (profile.network == "ws") {
            val wsSettings = JSONObject()
                .put("path", profile.path)

            if (profile.host.isNotBlank()) {
                wsSettings.put(
                    "headers",
                    JSONObject().put(
                        "Host",
                        profile.host
                    )
                )
            }

            streamSettings.put(
                "wsSettings",
                wsSettings
            )
        }

        val inbound = JSONObject()
            .put("tag", "tun-in")
            .put("port", 0)
            .put("protocol", "tun")
            .put(
                "settings",
                JSONObject()
                    .put("name", "tun0")
                    .put("mtu", 1500)
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
            .put(
                "rules",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "field")
                            .put("port", "53")
                            .put("network", "udp,tcp")
                            .put("outboundTag", "proxy")
                    )
                    .put(
                        JSONObject()
                            .put("type", "field")
                            .put("outboundTag", "proxy")
                            .put("network", "tcp,udp")
                    )
            )

        val dns = JSONObject()
            .put(
                "servers",
                JSONArray()
                    .put("fakedns")
                    .put("1.1.1.1")
                    .put("8.8.8.8")
            )
            .put("queryStrategy", "UseIP")

        val env = JSONObject()
            .put("xray.tun.fd", tunFd.toString())

        return JSONObject()
            .put("env", env)
            .put(
                "log",
                JSONObject().put(
                    "loglevel",
                    "warning"
                )
            )
            .put("dns", dns)
            .put(
                "fakedns",
                JSONArray().put(
                    JSONObject()
                        .put("ipPool", "198.18.0.0/15")
                        .put("poolSize", 65535)
                )
            )
            .put(
                "inbounds",
                JSONArray().put(inbound)
            )
            .put(
                "outbounds",
                JSONArray()
                    .put(outboundProxy)
                    .put(outboundDirect)
            )
            .put("routing", routing)
    }

    private fun verifyTunnelTraffic(): Boolean {
        for (attempt in 1..3) {
            var connection: HttpURLConnection? = null

            try {
                val url = URL(
                    "https://www.cloudflare.com/cdn-cgi/trace"
                )

                connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.useCaches = false

                val code = connection.responseCode

                if (code == 200) {
                    val body = connection.inputStream
                        .bufferedReader()
                        .use { it.readText() }

                    if (body.contains("ip=")) {
                        return true
                    }
                }

            } catch (_: Exception) {
                // Se intenta nuevamente.
            } finally {
                connection?.disconnect()
            }

            if (attempt < 3) {
                Thread.sleep(2000)
            }
        }

        return false
    }

    private fun stopXrayIfNeeded() {
        if (xrayStarted) {
            try {
                val request = JSONObject()
                    .put("apiVersion", 3)
                    .put("method", "stopXray")
                    .put("payload", JSONObject())

                LibXray.invoke(request.toString())

            } catch (_: Exception) {
            } finally {
                xrayStarted = false
            }
        }

        if (dnsConfigured) {
            try {
                LibXray.resetDNS()
            } catch (_: Exception) {
            }

            dnsConfigured = false
        }
    }

    private fun stopTunnel() {
        stopXrayIfNeeded()

        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }

        vpnInterface = null
        activeDialerController = null

        stopForegroundCompat()
        stopSelf()
    }

    private fun broadcastState(
        state: String,
        message: String
    ) {
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
        const val ACTION_START =
            "com.interfree.vmess.START"

        const val ACTION_STOP =
            "com.interfree.vmess.STOP"

        const val ACTION_STATUS =
            "com.interfree.vmess.STATUS"

        const val EXTRA_PROFILE = "vmess_profile"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"

        private const val NOTIFICATION_ID = 4107
    }
}
