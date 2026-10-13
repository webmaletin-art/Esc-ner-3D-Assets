# Limitaciones conocidas y trabajo pendiente

## Verificación realizada / no realizada
- **Verificado automáticamente (JVM):** formatos GLB/glTF/OBJ/FBX (ida y vuelta), validación independiente de los GLB con el *Khronos glTF Validator* (0 errores), apertura de OBJ/GLB con *trimesh* y de FBX con *Assimp* (lector independiente), detección, adaptadores con respuestas simuladas, red segura con servidor local, tareas, captura WebGL con **salida real de Chromium (WebGL2)**, casco visual.
- **No verificado en un teléfono real** (requiere dispositivo): rendimiento/FPS en Mali‑G57, aspecto del renderizador OpenGL ES, gestos, WebView real contra sitios reales, selector de archivos/destino del sistema. No se afirma ninguna cifra de FPS; el visor muestra los FPS medidos en el dispositivo mientras hay animación.
- **Sketchfab:** el análisis de respuestas está probado con muestras de la documentación pública; no se probó contra el servicio real (requiere token).

## Formatos
- **Importación:** GLB/glTF 2.0 (sin Draco, meshopt ni KTX2/Basis: se informa “formato no compatible con el motor actual”), OBJ+MTL, FBX 7.x binario/ASCII (geometría, jerarquía, materiales, texturas embebidas o externas; **esqueletos, animaciones y blend shapes de FBX no se importan** y se informa).
- **Exportación FBX:** binario 7.4 con malla, jerarquía, normales, UV, color de vértice, materiales Phong aproximados y texturas embebidas. **No exporta esqueletos ni animaciones.** Validado con Assimp; no probado en Autodesk/Blender. Los materiales PBR se aproximan (metal/rugosidad/oclusión empaquetados no se enlazan).
- **Exportación OBJ:** sin jerarquía (se aplica a vértices), sin esqueleto/animación; mapas empaquetados metal‑rugosidad/oclusión se incluyen como archivos pero MTL no puede enlazarlos.
- No hay: morph targets, cámaras/luces importadas (se omiten con aviso), KHR_materials_* avanzadas (clearcoat, transmisión…) → se pierden con aviso, USDZ/DAE/STL/PLY/3DS/BLEND (se reconocen y se informa que no están soportados).

## Copia / Replicación
- La captura WebGL es **parcial**: pose actual, sin esqueleto, sin animación, materiales aproximados; sólo texturas que el navegador permite leer; puede no funcionar en visores dentro de iframes de otro origen, `OffscreenCanvas`/workers, WebGPU o geometría comprimida que no pase por `bufferData`. Los datos son los que la GPU recibe (posiblemente cuantizados, sin la transformación de dequantización del shader).
- No se reconstruye geometría exacta a partir de imágenes/canvas. La reconstrucción visual por siluetas (casco visual) es **aproximada**: no recupera concavidades, no genera UV y requiere ≥6 fotos con ángulos uniformes (mesa giratoria) y fondo liso o transparente. No hay captura automática de vistas del navegador (WebGL no es capturable de forma fiable); se usan fotos/capturas que el usuario elija.
- No se usan servicios de IA ni envío de imágenes a servidores.

## Rendimiento / memoria
- Los modelos se cargan en memoria (límite de 512 MB por archivo, 768 MB descomprimidos en ZIP); advertencias para modelos grandes (>800 000 triángulos o archivos >50 MB). Texturas submuestreadas al tamaño configurado (1024/2048/4096). Alámbrico no disponible para primitivas >400 000 triángulos.
- Piel por GPU hasta ~96 huesos según el dispositivo; con más se muestra sin deformación y con aviso.

## Seguridad
- HTTPS por defecto; HTTP sólo con ajuste explícito; errores de certificado nunca se ignoran; sin interfaces JavaScript nativas; las URL que sugiere una página se validan (HTTPS, hosts públicos incluso tras DNS, redirecciones revalidadas, límites de tamaño). Las cookies sólo se reenvían a recursos del mismo sitio que la página que el usuario está viendo y nunca se registran.

## Pendiente (no implementado)
- Importar esqueleto/animación de FBX; exportar esqueleto/animación a FBX; Draco/meshopt/KTX2.
- Más adaptadores (cada uno requiere documentación pública del servicio).
- Pruebas instrumentadas en dispositivo y medición de rendimiento real.
- Release público automático (a propósito no se crea; sólo artifacts).

## Licencias de dependencias
Kotlin/coroutines/serialization (Apache‑2.0), OkHttp/Okio (Apache‑2.0), AndroidX/Compose/WebKit (Apache‑2.0). No se incluye código ni assets de terceros; los modelos de muestra son originales del proyecto (CC0).
