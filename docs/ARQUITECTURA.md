# Arquitectura

Dos módulos Gradle. La lógica probable sin dispositivo vive en `core3d` (JVM puro, ~todas las pruebas unitarias); `app` contiene sólo lo que necesita Android.

## `core3d` (paquete `app.universal3d.core`)

| Paquete | Responsabilidad |
|---|---|
| `model` | `Scene3D` (nodos, mallas/primitivas, materiales PBR, texturas/imágenes, pieles, animaciones, avisos de pérdida). Formato interno de intercambio: **GLB**. |
| `io` | `GltfReader` (GLB/glTF: accesores, sparse, cuantización, data-URI, extensiones; rechaza Draco/meshopt/KTX2 *requeridos* con mensaje claro), `ObjReader`/`ObjWriter` (+MTL, texturas, ZIP), `FbxParser` (binario y ASCII) · `FbxReader` · `FbxWriter` (binario 7.4), `GlbWriter`, `ModelImporter` (detecta por contenido, ZIP seguro anti zip-slip/bomba), `Png` |
| `inspect` | `ModelInspector` (sólo valores calculables; el resto “No disponible”), `ExportPlanner` (qué se exporta y qué se pierde) |
| `export` | `ModelExporter` (GLB, OBJ ZIP, FBX, ZIP completo + informe) y `ExportVerifier` (reabre el resultado y compara) |
| `resources` | `DetectedResource` (procedencia, tipo, formato, tamaño, **acceso** separado de **conversión**, dependencias), `ResourceClassifier`, `DependencyTable` (sin duplicados), `DependencyResolver` |
| `detect` | `PageSignals` (datos no confiables, acotados), `DetectionClassifier` (estados), `DetectionEngine` (límite de frecuencia, sondeos `Range`, cancelación por navegación) |
| `adapters` | `PlatformAdapter` + `AdapterRegistry`: dominios, visores, recursos, operaciones, autenticación, formatos, limitaciones. `GenericAdapter`, `GitHubAdapter`, `SketchfabAdapter` |
| `replicate` | `ReplicationEngine` (flujo de eventos: fases, progreso real, hallazgos, errores recuperables, resultado con fidelidad), `WebGlCaptureReconstructor` |
| `recon` | `VisualHullReconstructor` (siluetas → voxels → surface nets), independiente de la copia directa |
| `security` | `UrlPolicy`, `SafeFetcher` (HTTPS, sin IP privadas ni tras DNS, redirecciones revalidadas, límites, cancelación), `LogRedactor` |
| `tasks` | `TaskManager` (concurrencia limitada, cancelar, reintentar) |
| `view` | `OrbitCamera`, `RayPicker`, `AnimationEvaluator` |

## `app` (paquete `app.universal3d.inspector`)

- `web/BrowserController`: un único WebView retenido entre pestañas. Sin `addJavascriptInterface`: el script de página (`assets/u3d_page.js`, inyectado con `addDocumentStartJavaScript`) sólo envía **datos** por `addWebMessageListener`; el nativo valida, acota y descarta todo lo inesperado. Errores TLS siempre cancelan; HTTP bloqueado por defecto; sin acceso a archivos; permisos de página denegados; sin ventanas emergentes.
- `render/`: `ModelRenderer` (GLES 3.0; PBR simplificado, piel por GPU con límite según `GL_MAX_VERTEX_UNIFORM_VECTORS`, alámbrico con aristas únicas, transparencia ordenada, texturas submuestreadas, liberación al cerrar) y `ModelGLView` (gestos).
- `work/Pipelines`: importación, descarga, conversión, exportación, replicación y reconstrucción como **tareas cancelables** fuera del hilo principal. Los archivos exportados se generan en caché privada, se verifican y recién entonces se ofrecen al selector de destino (SAF); tras copiar se vuelve a leer el destino para confirmar tamaño.
- `data/`: ajustes, historial (JSON), biblioteca (GLB + metadatos en almacenamiento privado), tokens cifrados con Android Keystore (AES-GCM).
- `ui/`: Compose (Navegador + panel de detección, replicación, visor, inspector, biblioteca, tareas, ajustes, historial, reconstrucción).

## Flujo “Copiar / Replicar modelo”

1. **Archivo original** accesible (GLB/glTF/OBJ/FBX, con sus dependencias únicas) → fidelidad *exacta*.
2. **Exportación oficial** de un adaptador (p. ej. Sketchfab con token del usuario y modelo descargable) → *oficial*.
3. **Captura de geometría WebGL**: el script observa `bufferData`/`vertexAttribPointer`/`drawElements`…; al pulsar el botón se envían sólo los buffers usados por dibujos de triángulos, atributos (posición/normal/UV/color por nombre o forma, incl. tipos cuantizados), matriz de modelo si se identifica por nombre y texturas **legibles** (sin saltar CORS) → *parcial* (pose actual, sin esqueleto, materiales aproximados).
4. Si nada funciona: se detiene e informa qué falta y ofrece la **reconstrucción visual aproximada** (otro módulo).

Cada fase informa su estado; los porcentajes sólo aparecen cuando el total se conoce (bytes de una descarga, voxels tallados).
