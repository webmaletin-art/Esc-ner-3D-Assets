#!/usr/bin/env bash
# Genera UNA VEZ la clave de firma de distribución y la prepara para guardarla como secretos de GitHub.
# NO subas el .jks al repositorio. Guardá una copia de seguridad: si la perdés, no podrás actualizar la app instalada.
set -euo pipefail
OUT="${1:-u3d-release.jks}"
ALIAS="${2:-u3d}"
if [ -e "$OUT" ]; then echo "El archivo $OUT ya existe; no se sobrescribe." >&2; exit 1; fi
read -r -s -p "Contraseña del almacén (mín. 6 caracteres): " STOREPASS; echo
KEYPASS="$STOREPASS"
keytool -genkeypair -v -keystore "$OUT" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$STOREPASS" -keypass "$KEYPASS" -dname "CN=Universal 3D Inspector, O=Personal, C=AR"
base64 -w0 "$OUT" > "$OUT.base64.txt" 2>/dev/null || base64 "$OUT" | tr -d '\n' > "$OUT.base64.txt"
cat <<MSG

Listo. Ahora, en GitHub: Settings → Secrets and variables → Actions → New repository secret:

  ANDROID_KEYSTORE_BASE64    = contenido de $OUT.base64.txt
  ANDROID_KEYSTORE_PASSWORD  = (la contraseña que escribiste)
  ANDROID_KEY_ALIAS          = $ALIAS
  ANDROID_KEY_PASSWORD       = (la misma contraseña)

Después borrá $OUT.base64.txt y guardá $OUT en un lugar seguro (gestor de contraseñas / copia offline).
MSG
