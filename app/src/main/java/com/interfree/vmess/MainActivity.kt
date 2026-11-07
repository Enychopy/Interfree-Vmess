package com.interfree.vmess

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startService(Intent(this, InterfreeVpnService::class.java).setAction(InterfreeVpnService.ACTION_START))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("interfree", MODE_PRIVATE)
        setContent {
            var uuid by remember { mutableStateOf(prefs.getString("uuid", "") ?: "") }
            var username by remember { mutableStateOf(prefs.getString("username", "") ?: "") }
            var connected by remember { mutableStateOf(false) }
            var showAccount by remember { mutableStateOf(false) }
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("INTERFREE VPN", style = MaterialTheme.typography.headlineMedium)
                    Text("VMESS", style = MaterialTheme.typography.titleLarge)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (connected) "Estado: Conectado" else "Estado: Desconectado")
                            Text("Servidor: VMess · WebSocket · TLS")
                            Text("Ping: --")
                            Text("Consumo: disponible al integrar el motor Xray")
                            Button(onClick = {
                                if (!connected) {
                                    val prepare = VpnService.prepare(this@MainActivity)
                                    if (prepare != null) vpnPermission.launch(prepare)
                                    else startService(Intent(this@MainActivity, InterfreeVpnService::class.java).setAction(InterfreeVpnService.ACTION_START))
                                } else {
                                    startService(Intent(this@MainActivity, InterfreeVpnService::class.java).setAction(InterfreeVpnService.ACTION_STOP))
                                }
                                connected = !connected
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (connected) "DESCONECTAR" else "CONECTAR")
                            }
                            Text("Nota: esta base solicita el permiso VPN, pero aún no establece un túnel VMess. Falta integrar Xray Core.")
                        }
                    }
                    OutlinedButton(onClick = { showAccount = !showAccount }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showAccount) "CERRAR MI CUENTA" else "MI CUENTA")
                    }
                    if (showAccount) {
                        Text("Configuración de cuenta", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Nombre de usuario") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = uuid, onValueChange = { uuid = it }, label = { Text("UUID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = {
                            prefs.edit().putString("username", username.trim()).putString("uuid", uuid.trim()).apply()
                            showAccount = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("GUARDAR") }
                    }
                }
            }
        }
    }
}
