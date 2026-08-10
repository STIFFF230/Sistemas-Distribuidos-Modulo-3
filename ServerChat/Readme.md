# ServerChat — Servidor Java (Debian)

Servidor de chat distribuido sobre sockets TCP **no bloqueantes** (Java NIO). Ver `../PROTOCOL.md`
para el contrato completo de mensajes y `../CLAUDE-2.md` para los requisitos originales del ejercicio.

## Compilar

```bash
cd ServerChat
javac --release 17 -d out $(find src -name "*.java")
```

## Ejecutar

```bash
cd ServerChat
java -cp out Main [puerto]
```

Por defecto escucha en el puerto `5000` en todas las interfaces (`0.0.0.0`). Ejemplo con puerto explícito:

```bash
java -cp out Main 5000
```

## Verificar conectividad desde un cliente

```bash
ping <ip-del-servidor>
nc -vz <ip-del-servidor> 5000   # o Test-NetConnection en PowerShell
```

## Prueba manual rápida sin clientes gráficos

```bash
printf '{"type":"REGISTER","username":"ana"}\n' | nc <ip-del-servidor> 5000
```

Debe responder `REGISTER_OK` seguido de `USER_LIST`.

---

## Arquitectura de concurrencia

Esta sección explica **cómo funcionan los sockets, qué hilos existen, qué algoritmo coordina el
acceso al estado compartido y cómo se procesa una petición de extremo a extremo**. Es la base para
sustentar el diseño del servidor.

### 1. Modelo de sockets

- El servidor usa **sockets TCP no bloqueantes** (`java.nio.channels.ServerSocketChannel` /
  `SocketChannel`, en modo `configureBlocking(false)`), multiplexados con un único
  `java.nio.channels.Selector`. Corresponde al modelo **"máquina de estado finito"** descrito en la
  lectura complementaria 3 (Tanenbaum, sección 3.4.1, figura 3-4): paralelismo lógico entre
  conexiones mediante una única llamada de espera (`selector.select()`) que despierta ante *cualquier*
  canal listo, en vez del modelo "hilos" (un hilo del SO bloqueado por conexión) usado en un diseño
  clásico.
- No existe un hilo por cliente: **un único hilo** (el hilo `main`, que corre el bucle del selector)
  atiende `accept()`, `read()` y `write()` de **todos** los clientes conectados.
- El protocolo de aplicación va sobre TCP como **JSON delimitado por `\n`** (un objeto JSON por
  línea). TCP es un flujo de bytes sin límites de mensaje, así que:
  - un JSON puede llegar **fragmentado** en varias lecturas del canal, o
  - **varios JSON** pueden llegar juntos en una sola lectura, o
  - una sola llamada `channel.read(buffer)` puede devolver **0** bytes (nada disponible todavía) sin
    que eso signifique error ni fin de conexión — a diferencia de un `InputStream` bloqueante, donde
    `read()` simplemente espera hasta que haya datos.

  Por eso existe `chat.util.LineAccumulator` (`src/chat/util/LineAccumulator.java`): mantiene un
  búfer interno **entre llamadas sucesivas al selector** y reconstruye cada línea completa buscando
  el byte `\n`, acumulando bytes a través de múltiples eventos `OP_READ` si hace falta. También
  rechaza (`LineTooLongException`) cualquier línea que supere `MAX_MESSAGE_BYTES` (8 KB).
- De forma simétrica, `SocketChannel.write(buffer)` en modo no bloqueante puede escribir **menos
  bytes de los pedidos** si el búfer de envío del sistema operativo está lleno, y devolver de
  inmediato en vez de esperar. Por eso cada `ClientSession` mantiene una cola de `ByteBuffer`
  pendientes (`pendingWrites`) y el canal se suscribe a `OP_WRITE` solo mientras haya bytes sin
  enviar; el selector reintenta la escritura en la siguiente vuelta del bucle.
- `channel.setOption(StandardSocketOptions.TCP_NODELAY, true)` desactiva el algoritmo de Nagle: los
  mensajes de chat son pequeños y se prioriza baja latencia sobre uso óptimo del ancho de banda.

### 2. Modelo de hilos

Este servidor usa **dos hilos en total, sin importar cuántos clientes estén conectados** — a
diferencia de un modelo de un hilo (o dos) por cliente, que crecería linealmente con *N*.

| Hilo | Cantidad | Clase | Responsabilidad |
|---|---|---|---|
| Selector (`main`) | 1 | `ChatServer` | Bucle único: `selector.select()` → despacha `accept`/`read`/`write` de **todos** los clientes. Ejecuta también toda la lógica de negocio (`ServerDispatcher`). |
| Reportero de estadísticas | 1 (daemon) | `StatsReporter` | Duerme 30 s, toma una foto de `ServerStats` y la imprime. Es el único hilo que no es el selector. |

Con *N* clientes conectados sigue habiendo **2 hilos vivos**, no `2 + 2N`. Ningún cliente tiene un
hilo propio: su estado (`ClientSession`) es simplemente una entrada más que el hilo selector visita
en cada vuelta del bucle.

```
                    ┌───────────────────────────────────────────┐
                    │        ChatServer (hilo selector)          │
                    │                                             │
   accept ready ───▶│  selector.select()                         │
   read ready   ───▶│      │                                     │
   write ready  ───▶│      ▼                                     │
                    │  accept() / read() / write()  (no bloq.)   │
                    │      │                                     │
                    │      ▼                                     │
                    │  ServerDispatcher.onLine(...)               │
                    │  (registro, broadcast, mensajes privados)   │
                    │      │                                     │
                    │      ▼                                     │
                    │  sessions: HashMap<username, ClientSession> │
                    │  (solo este hilo la toca)                   │
                    └──────────────┬──────────────────────────────┘
                                   │ actualiza contadores
                                   ▼
                         ┌───────────────────┐        lee cada 30s
                         │   ServerStats      │◀──────────────────┐
                         │  (ReentrantLock)   │                   │
                         └───────────────────┘        ┌───────────┴──────────┐
                                                        │  StatsReporter (daemon) │
                                                        └────────────────────────┘
```

### 3. Algoritmo de coordinación: reactor de un solo hilo (sin colas, sin locks para el estado de negocio)

El servidor **no usa `BlockingQueue` ni hilos por cliente**. La coordinación se resuelve con el
patrón **reactor**: un único hilo pregunta al sistema operativo (vía `Selector`, que en Linux usa
`epoll`) qué canales tienen trabajo pendiente, y atiende ese trabajo **secuencialmente, uno a la
vez, dentro del mismo hilo**:

1. `selector.select()` bloquea *solo* al hilo selector, y *solo* hasta que **algún** canal (de
   cualquier cliente, o el socket de escucha) esté listo para `accept`, `read` o `write`.
2. Por cada canal listo, `ChatServer` llama al método correspondiente (`accept()`, `read()`,
   `write()`), que a su vez invoca a `ServerDispatcher` para la lógica de negocio (validar registro,
   armar el mensaje, decidir a quién reenviarlo, actualizar `sessions`).
3. Como **todo esto ocurre en el mismo hilo**, nunca hay dos fragmentos de lógica de negocio
   ejecutándose en paralelo entre sí. No hace falta una cola para pasar trabajo de un hilo a otro
   porque no hay "otro hilo": el propio hilo que detecta el evento es el que lo procesa.

Esto convierte al **hilo selector en el único ejecutor de lógica de negocio**, igual que el
`ServerDispatcher` centralizado de un diseño productor-consumidor, pero **sin el costo de
serializar objetos a través de colas entre hilos**: aquí no hay "productores" separados del
"consumidor" — es el mismo hilo de principio a fin.

### 4. Sincronización: qué necesita candado y qué no, y por qué

Esta es la pregunta central de la sustentación, porque la respuesta no es "todo" ni "nada": depende
de **cuántos hilos de sistema operativo tocan cada dato**.

- **Sin sincronización (por diseño, no por descuido):** `ServerDispatcher.sessions` (`HashMap`
  normal) y `ServerDispatcher.sequence` (`long` normal) son estructuras **no** thread-safe a
  propósito. Es seguro porque **solo el hilo selector las lee y las escribe, siempre una operación a
  la vez, nunca en paralelo consigo mismo**. No existe memoria compartida entre hilos concurrentes
  sobre ese estado, así que agregar `ConcurrentHashMap` o `AtomicLong` ahí sería sincronización
  innecesaria (y más lenta) para un problema que no existe. Lo mismo aplica a los campos de
  `ClientSession` (`pendingWrites`, `username`, `closed`): todos los toca únicamente el hilo selector.
- **Con sincronización explícita, porque aquí sí hay dos hilos reales compartiendo estado:**
  `ServerStats` (`src/chat/ServerStats.java`) es la **única** clase del proyecto que dos hilos de
  sistema operativo distintos —el hilo selector, que actualiza contadores en cada conexión, registro
  y mensaje, y el hilo `StatsReporter`, que los lee cada 30 s— tocan de forma concurrente. Por eso, y
  solo por eso, usa un **`java.util.concurrent.locks.ReentrantLock`** explícito: cada método hace
  `lock.lock()` / `try { ... } finally { lock.unlock(); }` alrededor de la región crítica (incrementar
  un contador, o tomar una foto consistente de todos los contadores a la vez en `snapshot()`). Se
  eligió `ReentrantLock` sobre `synchronized` deliberadamente, para que el candado quede explícito en
  el código (llamada a `lock()`/`unlock()` visible) en vez de implícito en la sintaxis del bloque.
  Esta es la **implementación de sincronización con mecanismo de bloqueo** que pide el entregable, y
  es también el ejemplo de **"implementación donde la sincronización es el core"**: la única razón de
  existir de `ServerStats`/`StatsReporter` es demostrar, de forma aislada y mínima, un caso real (no
  artificial) de estado mutable compartido entre hilos y su protección con un candado.
- **Por qué no hace falta ni `volatile` aquí:** en el diseño anterior (bloqueante, con un
  `ClientReader` y un `ClientWriter` por cliente), campos como `username` cruzaban de un hilo a otro
  y necesitaban `volatile` para garantizar visibilidad. En el reactor de un solo hilo eso ya no
  aplica: `ClientSession.username` lo escribe y lo lee siempre el mismo hilo selector, así que no hay
  frontera entre hilos que cruzar.

En resumen: **la necesidad de sincronizar no depende de si el dato es "compartido" en abstracto, sino
de si más de un hilo de sistema operativo puede tocarlo en paralelo.** El reactor de un solo hilo
elimina esa condición para casi todo el estado del servidor; `ServerStats` es la excepción
consciente que sí la tiene, y por eso —y solo por eso— lleva candado.

### 5. Ciclo de vida de una petición

Ejemplo con `GROUP_MESSAGE`, pero el flujo es el mismo para cualquier tipo de mensaje:

1. El cliente envía `{"type":"GROUP_MESSAGE","text":"hola"}\n` por su `SocketChannel`.
2. El sistema operativo marca ese canal como listo para lectura; `selector.select()` despierta con
   esa `SelectionKey` en el conjunto de listas.
3. `ChatServer.read(key, ...)` hace `channel.read(buffer)` (no bloqueante), pasa los bytes leídos a
   `session.accumulator().append(...)`, y por cada línea `\n`-terminada completa que resulte, llama a
   `dispatcher.onLine(session, line)` — todo dentro del mismo hilo selector, sin colas ni cambio de
   hilo de por medio.
4. `ServerDispatcher.onLine` parsea el JSON, ve que la sesión ya está registrada y el `type` es
   `GROUP_MESSAGE`, valida el texto (no vacío, ≤ 8 KB), arma el mensaje de salida con `++sequence` y
   la hora del servidor, y llama a `broadcastAll(mensaje)`.
5. `broadcastAll` recorre `sessions.values()` y llama a `s.enqueue(mensaje)` por cada sesión activa:
   esto serializa el mensaje a bytes y lo agrega a `pendingWrites` de cada `ClientSession`, marcando
   su `SelectionKey` con interés en `OP_WRITE`.
6. En la(s) siguiente(s) vuelta(s) del bucle del selector, cada canal de cliente aparece listo para
   escritura; `ChatServer.write(key)` llama a `session.flushPending()`, que escribe los bytes
   pendientes al `SocketChannel` (reintentando si el SO no aceptó todo de una vez) y, cuando ya no
   queda nada pendiente, vuelve a marcar la llave con solo `OP_READ`.

Para `REGISTER`, `PRIVATE_MESSAGE` y `DISCONNECT` el flujo es idéntico; solo cambia la lógica dentro
de `ServerDispatcher.handleMessage(...)` (validar username único con `sessions.containsKey`, buscar
el destinatario con `sessions.get(to)`, etc.).

### 6. Manejo de desconexión (ordenada y abrupta)

- **Desconexión ordenada:** el cliente envía `{"type":"DISCONNECT"}` → `requestDisconnect()` cierra
  el canal y llama a `onDisconnected()` directamente.
- **Fin de flujo (el cliente cerró su lado del socket):** `channel.read(buffer)` devuelve `-1`;
  `ChatServer.read()` detecta esto y llama a `disconnect(key, dispatcher)`.
- **Desconexión abrupta (RST, caída de red, línea demasiado larga):** cualquier `read()`/`write()`
  lanza `IOException`, capturada en el bucle principal del selector, que llama a
  `disconnect(key, dispatcher)` para esa llave específica **sin afectar a los demás clientes ni al
  socket de escucha** — una excepción de un cliente nunca tumba el selector completo.
- `ClientSession.markClosed()` es idempotente (usa una bandera simple, no `AtomicBoolean`, porque
  solo el hilo selector la toca) para que la limpieza (`sessions.remove`, notificar `USER_LEFT` y
  `USER_LIST` al resto) ocurra una sola vez sin importar por cuál camino se detectó la desconexión.
- `disconnect()` cierra el `SocketChannel` y cancela la `SelectionKey` (`key.cancel()`), liberando el
  recurso del selector.

### 7. Por qué este diseño (para la sustentación)

- **¿Por qué NIO con un solo hilo en vez de un hilo (o dos) por cliente?** Con sockets bloqueantes,
  cada `accept()`/`read()` que bloquea *ese* hilo necesita su propio hilo del sistema operativo para
  no detener a los demás clientes; el costo de memoria y de cambio de contexto crece linealmente con
  el número de conexiones. Con NIO, una sola llamada (`select()`) espera por *todos* los canales a la
  vez, así que un único hilo atiende cualquier cantidad de clientes sin crear hilos adicionales. Es
  exactamente el contraste que describe la lectura complementaria 3 entre el modelo "hilos" y el
  modelo "máquina de estado finito" (figura 3-4).
- **¿Por qué no hace falta un dispatcher separado del hilo que lee, como en un diseño con colas?**
  Porque en NIO no hay "otro hilo" al que pasarle el trabajo: el mismo hilo que detecta que hay una
  línea completa es libre de procesarla de inmediato, ya que nunca está bloqueado esperando a otro
  cliente. Introducir una cola y un hilo consumidor aparte solo tendría sentido si se quisiera
  paralelizar la lógica de negocio entre varios hilos — lo cual reintroduciría la necesidad de locks
  que este diseño evita a propósito.
- **¿Cuál es el costo de este diseño?** Todo el procesamiento (parseo JSON, broadcast, validaciones)
  ocurre en un único hilo: si una operación fuera lenta (por ejemplo, una consulta a base de datos
  bloqueante), congelaría a *todos* los clientes, no solo a uno. Para la lógica actual (JSON en
  memoria, sin I/O bloqueante adicional) esto no es un problema; en un sistema con operaciones
  pesadas, la salida natural sería delegar ese trabajo a un pool de hilos aparte y devolver el
  resultado al selector, sin bloquear el bucle principal.
- **¿Qué garantiza el orden de los mensajes?** Al ser un único hilo el que procesa todos los eventos
  secuencialmente, el orden de *procesamiento* respeta el orden en que el selector detectó cada
  evento; y como cada `ClientSession` escribe sus `pendingWrites` en orden FIFO, el orden de
  *entrega* a cada cliente respeta el orden en que el dispatcher generó esos mensajes para él.
- **¿Por qué `ServerStats` sí necesita un candado si "todo lo demás" no lo necesita?** Porque es la
  única pieza del diseño que introduce deliberadamente un segundo hilo de sistema operativo
  (`StatsReporter`) que comparte estado mutable con el hilo selector. Es el contraejemplo que
  demuestra, por contraste, por qué el resto del servidor no necesita sincronización: aquí sí hay dos
  hilos reales compitiendo por el mismo dato, y ahí es exactamente donde aparece el candado.

### 8. Componentes de soporte

- `chat.protocol.Json` (`src/chat/protocol/Json.java`): codec JSON propio, sin dependencias
  externas; soporta objetos, arrays, strings, números y booleanos — suficiente para el subconjunto
  de JSON que usa `PROTOCOL.md`. Sin cambios respecto al diseño bloqueante original.
- `chat.util.LineAccumulator` (`src/chat/util/LineAccumulator.java`): framing por `\n` adaptado a
  lecturas no bloqueantes (ver sección 1); reemplaza al antiguo `chat.util.LineReader`, que asumía
  lecturas bloqueantes de un `InputStream`.
- `ServerConfig`: constantes del protocolo (puerto por defecto, tamaño máximo de mensaje 8 KB,
  longitud de username 3–20). Sin cambios.
- `ServerStats` / `StatsReporter`: mecanismo de sincronización explícito descrito en la sección 4.
