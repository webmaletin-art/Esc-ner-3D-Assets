# Universal 3D Asset Inspector (Android)

Navegador con visor 3D integrado que **detecta** contenido 3D en las páginas, **importa/inspecciona** modelos, **recupera lo que el navegador ya puede ver o descargar** y **convierte/exporta** a GLB, OBJ (ZIP con MTL y texturas), FBX o un ZIP completo.

- Kotlin · Jetpack Compose · WebView (+ `androidx.webkit`) · OpenGL ES 3.0 propio (sin motor de terceros)
- `minSdk 26` (Android 8.0, necesario para GLES 3.0 y las APIs de WebView usadas) · `compileSdk/targetSdk 36`
- `applicationId`: **`app.universal3d.inspector`** (no cambiarlo: identifica la app para las actualizaciones)
- Sin claves de API, sin cuentas y sin servicios de pago. Las integraciones opcionales (token de Sketchfab) están aisladas.

> Uso responsable: la app sólo trabaja con lo que tu navegador ya puede ver y descargar. No elude inicios de sesión, DRM ni restricciones técnicas. Usá únicamente modelos sobre los que tengas derechos.

## Descargar e instalar el APK

1. En GitHub: pestaña **Actions** → ejecución más reciente de **Android CI** → sección **Artifacts**.
2. Descargá `universal3d-debug-apk` (y `universal3d-release-apk` si la firma de distribución está configurada) y descomprimí el `.zip`.
3. Copiá el `.apk` al teléfono, abrilo y permití “instalar apps desconocidas” para tu navegador/gestor de archivos.
4. **Actualizar sin desinstalar:** el APK nuevo debe tener el **mismo applicationId**, la **misma firma** y un **versionCode mayor** (el workflow usa el nº de ejecución). Leé [docs/FIRMA_Y_ACTUALIZACIONES.md](docs/FIRMA_Y_ACTUALIZACIONES.md): **sin los secretos de firma, cada compilación queda firmada con una clave temporal distinta y Android no permite actualizar encima.**

## Qué hace

| Área | Estado |
|---|---|
| Navegador (HTTPS, atrás/adelante/recarga, progreso, historial, descargas con selector de destino, selector de archivos) | Implementado |
| Detección automática de 3D con estados (`model-viewer`, enlaces/recursos GLB/glTF/OBJ/FBX, librerías, canvas WebGL con dibujo real) | Implementado |
| Verificación ligera de archivos (petición `Range` + firma de contenido) y distinción *modelo visible* vs *archivo accesible* | Implementado |
| Importación local GLB / glTF(+bin+texturas) / OBJ(+MTL+texturas) / FBX binario y ASCII / ZIP | Implementado |
| Visor: rotar, zoom, mover, encuadre, fondo, alámbrico, ocultar/mostrar partes, selección, nombres, animaciones, esqueletos (GPU) | Implementado (ver *Verificación*) |
| Inspector (mallas, vértices, triángulos, materiales, texturas con resolución, UV, huesos, animaciones, dependencias ausentes, compatibilidad) | Implementado |
| Exportación GLB / OBJ+MTL+texturas (ZIP) / FBX binario 7.4 / ZIP completo, con verificación por reapertura y pérdidas informadas antes de exportar | Implementado |
| Copiar / Replicar: archivo original → exportación oficial (adaptador) → captura de geometría WebGL → sugerencia de reconstrucción visual | Implementado (ver límites) |
| Adaptadores: genérico, GitHub (blob→raw), Sketchfab (API oficial con token del usuario) | Implementado; Sketchfab sin verificar contra el servicio real |
| Reconstrucción visual aproximada por siluetas (casco visual, mesa giratoria) — módulo independiente, 100 % local | Implementado (aproximada) |
| Gestor de tareas: progreso real, cancelación, reintento, estado, ubicación | Implementado |

Detalles, decisiones y límites: [docs/ARQUITECTURA.md](docs/ARQUITECTURA.md) y [docs/LIMITACIONES_Y_PENDIENTES.md](docs/LIMITACIONES_Y_PENDIENTES.md).

## Compilar

```bash
# requiere JDK 17+ y Android SDK (platform 36, build-tools 36.0.0)
./gradlew :core3d:test :app:testDebugUnitTest   # pruebas
./gradlew :app:lintDebug                         # lint
./gradlew :app:assembleDebug                     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease                   # firmado sólo si hay credenciales (ver docs)
```

## Estructura

```
core3d/   Núcleo en Kotlin/JVM puro (sin Android): modelo de escena, lectores/escritores GLB·glTF·OBJ·FBX, inspector,
          exportación y verificación, detección, adaptadores, replicación, captura WebGL, casco visual, red segura, tareas
app/      Android: WebView + puente de mensajes, renderizador OpenGL ES, pantallas Compose, persistencia, pipelines
.github/workflows/android.yml   CI: pruebas, lint, APK debug/release, artifacts
docs/     Arquitectura, firma y actualizaciones, limitaciones
scripts/  generate-keystore.sh (clave de firma; se guarda en secretos, nunca en el repo)
```
