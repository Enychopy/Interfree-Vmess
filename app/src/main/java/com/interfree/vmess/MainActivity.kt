package com.interfree.vmess

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

private val Navy = Color(0xFF080D1B)
private val Panel = Color(0xFF121B31)
private val Panel2 = Color(0xFF192541)
private val Violet = Color(0xFF9875FF)
private val Cyan = Color(0xFF4DD9E8)
private val Muted = Color(0xFF9AA8C4)

class MainActivity : ComponentActivity() {

    private var vpnState by mutableStateOf("DESCONECTADO")
    private var vpnMessage by mutableStateOf("")
    private var pendingUuid = ""

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService(pendingUuid)
        } else {
            vpnState = "DESCONECTADO"
            vpnMessage = "Permiso VPN denegado por el usuario"
        }
    }

    private var receiverRegistered = false

    private val vpnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val newState = it.getStringExtra(InterfreeVpnService.EXTRA_STATE)
                val newMessage = it.getStringExtra(InterfreeVpnService.EXTRA_MESSAGE)
                
                if (newState != null) vpnState = newState
                if (newMessage != null) vpnMessage = newMessage
            }
        }
    }

    private fun startVpnService(uuid: String) {
        vpnState = "CONECTANDO"
        vpnMessage = "Iniciando servicio de túnel..."

        val serviceIntent = Intent(this, InterfreeVpnService::class.java).apply {
            action = InterfreeVpnService.ACTION_START
            putExtra(InterfreeVpnService.EXTRA_UUID, uuid)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopVpnService() {
        vpnState = "DESCONECTANDO"
        vpnMessage = "Cerrando conexión VPN..."

        val serviceIntent = Intent(this, InterfreeVpnService::class.java).apply {
            action = InterfreeVpnService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(InterfreeVpnService.ACTION_STATUS)

        // En Android 34+, para broadcasts locales (dentro de la misma app), se debe usar RECEIVER_EXPORTED o LocalBroadcastManager.
        // Como estamos usando sendBroadcast estándar, usamos RECEIVER_EXPORTED para evitar bloqueos del sistema.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(vpnReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(vpnReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(vpnReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("interfree", MODE_PRIVATE)

        setContent {
            var username by remember { mutableStateOf(prefs.getString("username", "") ?: "") }
            var uuid by remember { mutableStateOf(prefs.getString("uuid", "") ?: "") }
            var showAccount by remember { mutableStateOf(false) }
            var showUuid by remember { mutableStateOf(false) }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Violet, secondary = Cyan, background = Navy,
                    surface = Panel, onPrimary = Color.White, onBackground = Color.White, onSurface = Color.White
                )
            ) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(0xFF111A35), Navy, Color(0xFF070B15)))
                    )
                ) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 22.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        // ENCABEZADO
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(46.dp).clip(RoundedCornerShape(15.dp))
                                    .background(Brush.linearGradient(listOf(Violet, Color(0xFF5144B8)))),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("IF", fontSize = 21.sp, fontWeight = FontWeight.Black, color = Color.White)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("INTERFREE VPN", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                                Text("CONEXIÓN SEGURA", fontSize = 10.sp, color = Muted, letterSpacing = 2.sp)
                            }
                            Surface(color = Panel2, shape = CircleShape) {
                                Text("VMESS", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        // PANEL PRINCIPAL
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = .96f)),
                            shape = RoundedCornerShape(28.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(13.dp)
                            ) {
                                Text("ESTADO DE CONEXIÓN", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)

                                Box(
                                    Modifier.size(178.dp)
                                        .border(1.dp, Violet.copy(alpha = .42f), CircleShape).padding(11.dp)
                                        .border(1.dp, Cyan.copy(alpha = .25f), CircleShape).padding(10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        Modifier.fillMaxSize().clip(CircleShape).background(
                                            Brush.radialGradient(listOf(Color(0xFF352A68), Color(0xFF1A2140), Panel))
                                        ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                "●",
                                                color = if (vpnState == "CONECTADO") Cyan else if (vpnState == "DESCONECTADO") Color(0xFFE05C69) else Color(0xFFFFC66D),
                                                fontSize = 24.sp
                                            )
                                            Text(
                                                when (vpnState) {
                                                    "CONECTADO" -> "ACTIVO"
                                                    "CONECTANDO" -> "..."
                                                    "DESCONECTANDO" -> "..."
                                                    else -> "LISTO"
                                                },
                                                fontSize = 24.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                            Text(
                                                vpnState,
                                                fontSize = 9.sp,
                                                color = if (vpnState == "CONECTADO") Cyan else Muted,
                                                letterSpacing = 1.sp
                                            )
                                        }
                                    }
                                }

                                Text(
                                    vpnMessage.ifBlank { "Tu privacidad, a un toque de distancia" },
                                    color = if (vpnState == "DESCONECTADO" && vpnMessage.contains("Error", ignoreCase = true)) Color(0xFFE05C69) else Muted,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )

                                Button(
                                    onClick = {
                                        if (vpnState == "CONECTADO" || vpnState == "CONECTANDO") {
                                            stopVpnService()
                                        } else {
                                            val savedUuid = prefs.getString("uuid", "")?.trim().orEmpty()
                                            try {
                                                UUID.fromString(savedUuid)
                                                pendingUuid = savedUuid
                                                val prepare = VpnService.prepare(this@MainActivity)
                                                if (prepare != null) {
                                                    vpnPermission.launch(prepare)
                                                } else {
                                                    startVpnService(savedUuid)
                                                }
                                            } catch (_: Exception) {
                                                vpnMessage = "Ingresa un UUID válido en MI CUENTA."
                                                vpnState = "DESCONECTADO"
                                                showAccount = true
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(54.dp),
                                    shape = RoundedCornerShape(17.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (vpnState == "CONECTADO" || vpnState == "CONECTANDO") Color(0xFFE05C69) else Violet
                                    )
                                ) {
                                    Text(
                                        if (vpnState == "CONECTADO" || vpnState == "CONECTANDO") "DESCONECTAR VPN" else "ACTIVAR VPN",
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }
                                Text("Conexión VMess mediante Xray Core", color = Muted, fontSize = 10.sp)
                            }
                        }

                        // ESTADÍSTICAS
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            MetricCard("PING", "—", "ms", Modifier.weight(1f))
                            MetricCard("DESCARGA", "—", "Mbps", Modifier.weight(1f))
                            MetricCard("SUBIDA", "—", "Mbps", Modifier.weight(1f))
                        }

                        // SERVIDOR
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("▣", color = Cyan, fontSize = 19.sp)
                                    Spacer(Modifier.width(9.dp))
                                    Text("SERVIDOR", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Spacer(Modifier.weight(1f))
                                    Text("VMess", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                HorizontalDivider(color = Color.White.copy(alpha = .08f))
                                InfoLine("Protocolo", "VMess · WebSocket · TLS")
                                InfoLine("Ubicación", "Servidor configurado")
                                InfoLine("Tráfico", if (vpnState == "CONECTADO") "Túnel Activo" else "Aún no disponible")
                            }
                        }

                        // MI CUENTA
                        OutlinedButton(
                            onClick = { showAccount = !showAccount },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Violet.copy(alpha = .7f))
                        ) {
                            Text(if (showAccount) "CERRAR MI CUENTA" else "MI CUENTA", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }

                        if (showAccount) {
                            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("CONFIGURACIÓN DE CUENTA", color = Cyan, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    OutlinedTextField(
                                        value = username, onValueChange = { username = it }, label = { Text("Nombre de usuario") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), colors = fieldColors()
                                    )
                                    OutlinedTextField(
                                        value = uuid, onValueChange = { uuid = it }, label = { Text("UUID") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                        visualTransformation = if (showUuid) VisualTransformation.None else PasswordVisualTransformation(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                        trailingIcon = { TextButton(onClick = { showUuid = !showUuid }) { Text(if (showUuid) "OCULTAR" else "VER", color = Cyan, fontSize = 10.sp) } },
                                        colors = fieldColors()
                                    )
                                    Button(
                                        onClick = {
                                            prefs.edit().putString("username", username.trim()).putString("uuid", uuid.trim()).apply()
                                            showAccount = false
                                            vpnMessage = "Datos de cuenta guardados."
                                        },
                                        modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)
                                    ) {
                                        Text("GUARDAR CAMBIOS", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        Text("INTERFREE VPN · VMESS", Modifier.fillMaxWidth(), color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 15.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Muted, fontSize = 9.sp, letterSpacing = .7.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(3.dp))
                Text(unit, color = Cyan, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Violet, unfocusedBorderColor = Color(0xFF3B4968),
    focusedLabelColor = Cyan, unfocusedLabelColor = Muted,
    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Cyan
)
