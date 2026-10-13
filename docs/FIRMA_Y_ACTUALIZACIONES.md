# Firma y actualizaciones del APK

Android sólo instala un APK **encima** de otro si coinciden: **`applicationId`** (`app.universal3d.inspector`), **firma (certificado)** y `versionCode` **mayor**. Android puede pedir confirmación al instalar; las actualizaciones no son silenciosas.

## Estado por defecto (sin secretos)

- `versionCode` = número de ejecución del workflow (siempre creciente) y `versionName` = `1.0.<n>`. ✔
- El APK de depuración se firma con la **clave de depuración que el runner genera en cada ejecución**: es distinta cada vez. ❌ Android responderá *“La app no se instaló”* si intentás actualizar encima de una compilación anterior. Para probar una nueva compilación hay que desinstalar la anterior (se pierde la biblioteca local).
- El APK `release` se compila con R8 (comprobación) pero **no se firma ni se publica** si no hay credenciales.

## Configurar la firma estable (una sola vez)

1. En tu computadora con JDK: `./scripts/generate-keystore.sh` (genera `u3d-release.jks` y `u3d-release.jks.base64.txt`).
2. En GitHub → **Settings → Secrets and variables → Actions → New repository secret**:

| Secreto | Valor |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | contenido de `u3d-release.jks.base64.txt` |
| `ANDROID_KEYSTORE_PASSWORD` | contraseña del almacén |
| `ANDROID_KEY_ALIAS` | `u3d` (o el alias que elegiste) |
| `ANDROID_KEY_PASSWORD` | contraseña de la clave |

3. Borrá el `.base64.txt` y guardá el `.jks` en un lugar seguro. **Si perdés esta clave no podrás volver a actualizar la app instalada.** Nunca la subas al repositorio (`.gitignore` bloquea `*.jks`, `*.keystore`, `keystore.properties`).
4. Volvé a ejecutar el workflow: ahora publica `universal3d-release-apk` firmado y el de depuración usa la **misma** firma, de modo que ambos se actualizan entre sí compilación a compilación. El resumen del workflow muestra el hash SHA-256 del certificado para que compruebes que es siempre el mismo.

Para firmar localmente: crear `keystore.properties` (ignorado por git) con `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

## Reglas para no romper actualizaciones

- No cambiar `applicationId`.
- No bajar `versionCode` (el workflow lo calcula; en local es 1).
- No cambiar el certificado. Cambiarlo obliga a desinstalar.
