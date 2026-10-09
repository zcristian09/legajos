# Legajos de Pacientes — proyecto Android inicial

Aplicación nativa Android, con registro local SQLite y sin permiso de Internet. Incluye búsqueda por DNI/nombre/apellido/número de legajo, alta, prevención de DNI duplicado y exportación/importación de copias cifradas con AES-GCM y clave derivada con PBKDF2.

## Importante
- Es un proyecto inicial para compilar y probar; no es un APK ya compilado ni una aplicación auditada para datos reales.
- La base de datos está en el almacenamiento privado de la app, pero **la base local no está cifrada a nivel de archivo**. El PIN es una barrera de acceso de la interfaz, no cifrado del contenido local. Antes de usar datos reales de pacientes, implementar cifrado de la base local, autenticación más robusta y revisar el cumplimiento de las políticas de la institución.
- No contiene datos reales de pacientes.
- La restauración reemplaza los registros existentes. Probá primero con datos ficticios y conservá una copia separada.

## Compilar
1. Abrí la carpeta `LegajosPacientes` con Android Studio reciente.
2. Permití que Gradle sincronice y descargue el Android Gradle Plugin y Kotlin.
3. Conectá un Android o usá un emulador.
4. Elegí **Build > Build APK(s)**. El APK de depuración suele quedar en `app/build/outputs/apk/debug/app-debug.apk`.

Configuración: minSdk 26 (Android 8.0), compileSdk 35. La compilación requiere conexión a internet para descargar las herramientas de Android la primera vez.
