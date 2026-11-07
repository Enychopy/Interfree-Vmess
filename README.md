# INTERFREE VPN VMESS

Proyecto Android nativo independiente para la aplicación VMess.

## Estado de esta primera base
- Interfaz Android nativa con campos de cuenta y teclado del sistema.
- Servicio Android `VpnService` declarado.
- Workflow de GitHub Actions para compilar y publicar el APK como artifact.

**Importante:** esta base todavía NO conecta a Internet mediante VMess. Antes de distribuirla hay que integrar y probar Xray Core para Android, enlazar el descriptor TUN con el motor, implementar el estado real de conexión, ping y estadísticas, y proteger las credenciales. No introducir UUID reales ni secretos mientras el repositorio sea público.

## Compilar
GitHub Actions > Build INTERFREE VMESS APK > Run workflow. Descarga el artifact `INTERFREE-VMESS-debug`.
