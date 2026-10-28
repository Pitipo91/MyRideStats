#!/usr/bin/env bash
# Firma un APK de Tumbada con la clave de release y la prueba de rotación.
#
# La clave NUNCA vive en el proyecto. Se indica con variables de entorno:
#   TUMBADA_KEYSTORE        ruta al .p12 (por defecto ~/.tumbada-firma/tumbada-firma.p12)
#   TUMBADA_KEYSTORE_PASS   contraseña (o TUMBADA_KEYSTORE_PASS_FILE con la ruta a un fichero que la contiene)
#   TUMBADA_KEY_ALIAS       alias (por defecto tumbada2)
#   APKSIGNER               comando apksigner (por defecto el de $ANDROID_HOME/build-tools más reciente)
#
# Uso: scripts/firmar-apk.sh entrada-sin-firmar.apk salida.apk
#
# Firma solo con APK Signature Scheme v3 + lineage (minSdk 28): los móviles con la versión anterior
# (firmada con la clave antigua) aceptan la actualización, y después rechazan cualquier APK firmado
# solo con la clave antigua (rollback desactivado en la prueba de rotación).
set -euo pipefail

IN="${1:?Uso: firmar-apk.sh entrada.apk salida.apk}"
OUT="${2:?Uso: firmar-apk.sh entrada.apk salida.apk}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
LINEAGE="$HERE/signing-lineage/tumbada.lineage"
KS="${TUMBADA_KEYSTORE:-$HOME/.tumbada-firma/tumbada-firma.p12}"
ALIAS="${TUMBADA_KEY_ALIAS:-tumbada2}"

case "$KS" in "$HERE"/*) echo "ERROR: la clave no puede estar dentro del proyecto ($KS)" >&2; exit 1;; esac
[ -f "$KS" ] || { echo "ERROR: no encuentro la clave en $KS (define TUMBADA_KEYSTORE)" >&2; exit 1; }
[ -f "$LINEAGE" ] || { echo "ERROR: falta $LINEAGE" >&2; exit 1; }

if [ -n "${TUMBADA_KEYSTORE_PASS_FILE:-}" ]; then PASS_ARG="file:$TUMBADA_KEYSTORE_PASS_FILE"
elif [ -n "${TUMBADA_KEYSTORE_PASS:-}" ]; then PASS_ARG="env:TUMBADA_KEYSTORE_PASS"
elif [ -f "$(dirname "$KS")/clave.txt" ]; then PASS_ARG="file:$(dirname "$KS")/clave.txt"
else echo "ERROR: falta la contraseña (TUMBADA_KEYSTORE_PASS o TUMBADA_KEYSTORE_PASS_FILE)" >&2; exit 1; fi

if [ -z "${APKSIGNER:-}" ]; then
  BT="$(ls -d "${ANDROID_HOME:-/nonexistent}"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)"
  APKSIGNER="${BT:+$BT/apksigner}"
fi
[ -n "${APKSIGNER:-}" ] || { echo "ERROR: no encuentro apksigner (define APKSIGNER o ANDROID_HOME)" >&2; exit 1; }

# zipalign si está disponible (el APK de Gradle ya suele venir alineado)
ALIGNED="$IN"
ZA="$(dirname "$APKSIGNER")/zipalign"
if [ -x "$ZA" ]; then ALIGNED="$(mktemp --suffix=.apk)"; "$ZA" -f -p 4 "$IN" "$ALIGNED"; fi

$APKSIGNER sign \
  --ks "$KS" --ks-pass "$PASS_ARG" --ks-key-alias "$ALIAS" \
  --lineage "$LINEAGE" \
  --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
  --rotation-min-sdk-version 28 --min-sdk-version 28 \
  --out "$OUT" "$ALIGNED"

$APKSIGNER verify --min-sdk-version 28 --print-certs "$OUT" | grep -E "Verifies|v3|certificate SHA-256"
echo "Firmado: $OUT"
