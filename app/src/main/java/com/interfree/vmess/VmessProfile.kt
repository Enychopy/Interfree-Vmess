
package com.interfree.vmess

import android.util.Base64
import org.json.JSONObject
import java.util.UUID

data class VmessProfile(
    val name: String,
    val address: String,
    val port: Int,
    val uuid: String,
    val alterId: Int,
    val security: String,
    val network: String,
    val host: String,
    val path: String,
    val tls: Boolean,
    val sni: String,
    val fingerprint: String,
    val alpn: List<String>
) {
    companion object {

        fun parse(link: String): VmessProfile {
            val input = link.trim()

            require(input.startsWith("vmess://", ignoreCase = true)) {
                "El enlace debe comenzar con vmess://"
            }

            val encoded = input.substringAfter("://")
                .substringBefore("#")
                .trim()

            require(encoded.isNotBlank()) {
                "El enlace VMess está vacío."
            }

            val jsonText = decodeBase64(encoded)

            val json = try {
                JSONObject(jsonText)
            } catch (_: Exception) {
                throw IllegalArgumentException(
                    "El contenido del enlace VMess no es válido."
                )
            }

            val address = json.optString("add").trim()
            val portText = json.optString("port").trim()
            val uuid = json.optString("id").trim()

            require(address.isNotBlank()) {
                "El perfil no contiene la dirección del servidor."
            }

            val port = portText.toIntOrNull()
                ?: throw IllegalArgumentException(
                    "El puerto del servidor no es válido."
                )

            require(port in 1..65535) {
                "El puerto debe estar entre 1 y 65535."
            }

            try {
                UUID.fromString(uuid)
            } catch (_: Exception) {
                throw IllegalArgumentException(
                    "El UUID del perfil VMess no es válido."
                )
            }

            val alterId = json.optString("aid", "0")
                .toIntOrNull() ?: 0

            require(alterId >= 0) {
                "El alterId no es válido."
            }

            val network = json.optString("net", "tcp")
                .trim().lowercase()

            require(network in listOf("tcp", "ws", "grpc", "http", "h2")) {
                "Transporte VMess no compatible: $network"
            }

            val tlsValue = json.optString("tls", "")
                .trim().lowercase()

            val tls = tlsValue == "tls"

            require(tlsValue.isBlank() || tls) {
                "Este perfil utiliza un tipo de seguridad no compatible."
            }

            val alpnText = json.optString("alpn", "")
            val alpn = alpnText.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            return VmessProfile(
                name = json.optString("ps", "Servidor VMess"),
                address = address,
                port = port,
                uuid = uuid,
                alterId = alterId,
                security = json.optString("scy", "auto")
                    .ifBlank { "auto" },
                network = network,
                host = json.optString("host", "").trim(),
                path = json.optString("path", "/")
                    .ifBlank { "/" },
                tls = tls,
                sni = json.optString("sni", "").trim(),
                fingerprint = json.optString("fp", "chrome")
                    .ifBlank { "chrome" },
                alpn = alpn
            )
        }

        private fun decodeBase64(value: String): String {
            val normalized = value
                .replace("-", "+")
                .replace("_", "/")
                .replace("\n", "")
                .replace("\r", "")
                .replace(" ", "")

            val padded = normalized + "=".repeat(
                (4 - normalized.length % 4) % 4
            )

            val bytes = try {
                Base64.decode(padded, Base64.DEFAULT)
            } catch (_: Exception) {
                throw IllegalArgumentException(
                    "No se pudo decodificar el enlace VMess."
                )
            }

            return try {
                String(bytes, Charsets.UTF_8)
            } catch (_: Exception) {
                throw IllegalArgumentException(
                    "El perfil VMess contiene datos inválidos."
                )
            }
        }
    }
}
