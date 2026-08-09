#!/usr/bin/env bash
# Apaga el servidor de chat en la VM Debian por SSH.
#
# Uso: ./scripts/stop-server.sh [usuario@]  (por defecto usuario "stiven")

set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then
  # shellcheck disable=SC1091
  source .env
fi

HOST="${CHAT_HOST:?Falta CHAT_HOST — define .env o exporta la variable}"
USER_HOST="${1:-stiven}@${HOST}"

echo "Conectando a ${USER_HOST}..."
ssh "${USER_HOST}" "
  if pkill -f 'java -cp out Main'; then
    echo 'Servidor detenido.'
  else
    echo 'No estaba corriendo.'
  fi
"
