# Protocolo del chat distribuido

Contrato de transporte compartido entre `ServerChat` (Java), `Clienter1` (Python/Windows)
y `Cliente2` (Swift/macOS). Ver también `CLAUDE-2.md` (documento fuente).

## Transporte

- TCP. El servidor usa sockets NO bloqueantes (Java NIO, `Selector`); los clientes (Python, Swift)
  usan sockets bloqueantes de su propia biblioteca estándar. El protocolo de aplicación (framing,
  mensajes JSON) es idéntico en ambos casos: el modelo de I/O es un detalle interno de cada
  implementación y no afecta el contrato de transporte.
- Un socket persistente por cliente, reutilizado para todo (registro, grupal, privado, lista de usuarios, desconexión).
- Codificación UTF-8.
- Cada mensaje es **un objeto JSON en una sola línea**, terminado en `\n`.
- Tamaño máximo de línea: 8192 bytes UTF-8. Líneas mayores se rechazan con `ERROR` y se cierra la conexión.
- Puerto por defecto: `5000` (configurable).

## Reglas de framing

TCP es un flujo de bytes:

- El lector acumula bytes en un búfer hasta encontrar `\n`.
- Puede llegar más de un JSON en una lectura: extraer todas las líneas completas disponibles.
- Puede llegar un JSON fragmentado en varias lecturas: conservar el resto en el búfer.
- Todo mensaje debe tener el campo `"type"`; si falta o el JSON es inválido, se responde `ERROR` sin cerrar la conexión del resto de clientes.

## Mensajes

### REGISTER (cliente → servidor)
```json
{"type":"REGISTER","username":"ana"}
```
- `username`: 3–20 caracteres, sin `\n`, caracteres permitidos `[A-Za-z0-9_-]`, único durante la sesión.
- Ningún otro mensaje es válido antes de un `REGISTER_OK`.

### REGISTER_OK (servidor → cliente)
```json
{"type":"REGISTER_OK","username":"ana"}
```
Inmediatamente después el servidor envía `USER_LIST` al nuevo cliente y `USER_JOINED` a los demás.

### REGISTER_ERROR (servidor → cliente)
```json
{"type":"REGISTER_ERROR","message":"El nombre de usuario ya está en uso"}
```

### GROUP_MESSAGE
Cliente → servidor:
```json
{"type":"GROUP_MESSAGE","text":"Hola a todos"}
```
Servidor → todos los clientes registrados:
```json
{"type":"GROUP_MESSAGE","from":"ana","text":"Hola a todos","timestamp":"2026-08-05T10:45:00-05:00","sequence":15}
```

### PRIVATE_MESSAGE
Cliente → servidor:
```json
{"type":"PRIVATE_MESSAGE","to":"carlos","text":"Hola, este mensaje es privado"}
```
Servidor → emisor y destinatario:
```json
{"type":"PRIVATE_MESSAGE","from":"ana","to":"carlos","text":"Hola, este mensaje es privado","timestamp":"2026-08-05T10:45:10-05:00","sequence":16}
```

### USER_LIST (servidor → clientes)
```json
{"type":"USER_LIST","users":["ana","carlos","maria"]}
```

### USER_JOINED / USER_LEFT (servidor → clientes)
```json
{"type":"USER_JOINED","username":"maria"}
```
```json
{"type":"USER_LEFT","username":"maria"}
```

### DISCONNECT (cliente → servidor, opcional; también se detecta por cierre del socket)
```json
{"type":"DISCONNECT"}
```

### ERROR (servidor → cliente)
```json
{"type":"ERROR","message":"Mensaje inválido"}
```

## Validaciones

- Usuario: no vacío, 3–20 caracteres, sin `\n`, único.
- Mensaje: no vacío tras `trim`, máximo 8 KB UTF-8.
- `PRIVATE_MESSAGE.to` debe ser un usuario conectado; si no existe, el servidor responde `ERROR` al emisor.
- Un socket no registrado (sin `REGISTER_OK` previo) no puede enviar `GROUP_MESSAGE`/`PRIVATE_MESSAGE`.

## Orden

- El servidor asigna `sequence` (entero incremental global, `AtomicLong`) a cada `GROUP_MESSAGE`/`PRIVATE_MESSAGE` que reenvía, para que los clientes puedan ordenar mensajes recibidos por distintas rutas.
