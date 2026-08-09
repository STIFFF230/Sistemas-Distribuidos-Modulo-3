#!/usr/bin/env bash
# Prende el servidor de chat en la VM Debian por SSH, sin tener que entrar a
# mano. Usa la IP/puerto de Modulo3/.env (o del entorno si ya están seteados).
#
# Uso: ./scripts/start-server.sh [usuario@]  (por defecto usuario "stiven")

set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then
  # shellcheck disable=SC1091
  source .env
fi

HOST="${CHAT_HOST:?Falta CHAT_HOST — define .env o exporta la variable}"
PORT="${CHAT_PORT:-5000}"
USER_HOST="${1:-stiven}@${HOST}"

echo "Conectando a ${USER_HOST}..."
# El '< /dev/null' es clave: sin eso, el proceso java hereda la entrada
# estándar del canal SSH y puede morir en cuanto esta conexión se cierra,
# incluso con nohup/disown. setsid además lo desprende de la sesión SSH.
ssh "${USER_HOST}" "
  cd Sistemas-Distribuidos-Modulo-3/ServerChat
  if pgrep -f 'java -cp out Main' > /dev/null; then
    echo 'El servidor ya estaba corriendo.'
  else
    setsid nohup java -cp out Main ${PORT} < /dev/null > server.log 2>&1 &
    disown
    sleep 1
    echo 'Servidor iniciado.'
  fi
  sleep 1
  echo '--- server.log ---'
  tail -n 5 server.log
  echo '--- puerto ---'
  ss -ltnp 2>/dev/null | grep ':${PORT} ' || echo 'AVISO: nada escuchando en el puerto ${PORT} todavia.'
"
