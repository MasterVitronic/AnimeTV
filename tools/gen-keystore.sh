#!/bin/sh
# Genera una keystore de release propia para AnimeTV MV y guarda las
# credenciales en local.properties (ignorado por git).
# No imprime la contraseña.
set -e

KS_DIR="$HOME/.android-keystores"
KS="$KS_DIR/animetv-mv.jks"
ALIAS="animetvmv"
LP="$(dirname "$0")/../local.properties"

mkdir -p "$KS_DIR"

if [ -f "$KS" ]; then
  echo "La keystore ya existe: $KS"
  exit 1
fi

PASS="$(openssl rand -hex 24)"

keytool -genkeypair -v \
  -keystore "$KS" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storepass "$PASS" \
  -keypass "$PASS" \
  -dname "CN=MasterVitronic, OU=AnimeTV MV, O=MasterVitronic, L=Madrid, ST=Madrid, C=ES" \
  >/dev/null 2>&1

if [ ! -f "$LP" ]; then
  printf 'sdk.dir=%s\n' "${ANDROID_HOME:-$HOME/Android/Sdk}" > "$LP"
fi

if grep -q '^animetv.storeFile=' "$LP"; then
  echo "local.properties ya contiene configuración de firma; no se modifica."
  exit 1
fi

printf '\n# Firma de release (NO versionar)\nanimetv.storeFile=%s\nanimetv.storePassword=%s\nanimetv.keyAlias=%s\nanimetv.keyPassword=%s\n' \
  "$KS" "$PASS" "$ALIAS" "$PASS" >> "$LP"

echo "Keystore creada: $KS"
echo "Credenciales añadidas a local.properties (ignorado por git)."
