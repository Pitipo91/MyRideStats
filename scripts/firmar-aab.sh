#!/usr/bin/env bash
# Firma un AAB de MyRideStats con la CLAVE DE SUBIDA (upload key) para Google Play.
#
# Play App Signing: Google guarda la clave de firma de la app (tumbada2, se le entrega una sola vez
# cifrada con PEPK) y re-firma lo que se descarga desde Play. Lo que subes a Play Console va firmado
# con esta otra clave, la de subida. Si se pierde o se filtra, Google permite cambiarla.
#
# La clave NUNCA vive en el proyecto. Se indica con variables de entorno:
#   MRS_UPLOAD_KEYSTORE        ruta al .p12 (por defecto ~/.tumbada-firma/subida.p12)
#   MRS_UPLOAD_KEYSTORE_PASS   contraseña (o MRS_UPLOAD_KEYSTORE_PASS_FILE; por defecto clave-subida.txt junto al .p12)
#   MRS_UPLOAD_KEY_ALIAS       alias (por defecto upload)
#
# Uso: scripts/firmar-aab.sh entrada.aab salida.aab
set -euo pipefail

IN="${1:?Uso: firmar-aab.sh entrada.aab salida.aab}"
OUT="${2:?Uso: firmar-aab.sh entrada.aab salida.aab}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
KS="${MRS_UPLOAD_KEYSTORE:-$HOME/.tumbada-firma/subida.p12}"
ALIAS="${MRS_UPLOAD_KEY_ALIAS:-upload}"

case "$KS" in "$HERE"/*) echo "ERROR: la clave no puede estar dentro del proyecto ($KS)" >&2; exit 1;; esac
[ -f "$KS" ] || { echo "ERROR: no encuentro la clave de subida en $KS (define MRS_UPLOAD_KEYSTORE)" >&2; exit 1; }

if [ -n "${MRS_UPLOAD_KEYSTORE_PASS_FILE:-}" ]; then PASS="$(cat "$MRS_UPLOAD_KEYSTORE_PASS_FILE")"
elif [ -n "${MRS_UPLOAD_KEYSTORE_PASS:-}" ]; then PASS="$MRS_UPLOAD_KEYSTORE_PASS"
elif [ -f "$(dirname "$KS")/clave-subida.txt" ]; then PASS="$(cat "$(dirname "$KS")/clave-subida.txt")"
else echo "ERROR: falta la contraseña (MRS_UPLOAD_KEYSTORE_PASS o MRS_UPLOAD_KEYSTORE_PASS_FILE)" >&2; exit 1; fi
export MRS_PASS="$PASS"

cp "$IN" "$OUT"
jarsigner -keystore "$KS" -storetype PKCS12 -storepass:env MRS_PASS -keypass:env MRS_PASS \
  -sigalg SHA256withRSA -digestalg SHA-256 "$OUT" "$ALIAS" >/dev/null
jarsigner -verify "$OUT" | tail -1
keytool -printcert -jarfile "$OUT" | grep -m1 "SHA256:"
echo "Firmado para subir a Play: $OUT"
