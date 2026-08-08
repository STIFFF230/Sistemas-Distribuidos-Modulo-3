# CLAUDE.md — Implementación del chat distribuido

## 1. Objetivo general

Implementar y adaptar un sistema de chat distribuido cliente-servidor con sockets TCP bloqueantes, interfaz gráfica y soporte para:

- Registro de usuarios mediante un nombre único.
- Chat grupal.
- Lista de usuarios conectados en tiempo real.
- Chats privados en ventanas independientes.
- Múltiples clientes conectados simultáneamente.
- Comunicación entre componentes escritos en lenguajes y sistemas operativos diferentes.

Arquitectura prevista:

| Componente | Sistema operativo | Tecnología |
|---|---|---|
| Servidor | Debian en máquina virtual | Java |
| Cliente rojo | Windows en máquina virtual | Python + Tkinter |
| Cliente amarillo | macOS | Swift + SwiftUI |

Todos los componentes deben comunicarse exclusivamente mediante el protocolo común definido en este documento.

---

## 2. Instrucción obligatoria antes de modificar código

Antes de crear, eliminar o modificar cualquier archivo:

1. Revisa completamente la carpeta `Cajeroreferencia`.
2. Identifica:
   - Cómo se crea el `ServerSocket`.
   - Cómo se aceptan conexiones.
   - Cómo se crea un socket por cliente.
   - Cómo se realizan las lecturas bloqueantes.
   - Cómo se envían datos.
   - Cómo se crean y administran los hilos.
   - Cómo se detecta una desconexión.
   - Qué clases y métodos pueden reutilizarse o adaptarse.
3. Revisa también la estructura completa del repositorio y determina cuáles son las carpetas reales del servidor y de cada cliente.
4. Presenta primero un diagnóstico breve con:
   - Archivos relevantes encontrados.
   - Funcionamiento actual.
   - Problemas técnicos.
   - Elementos reutilizables.
   - Cambios necesarios.
5. Después del diagnóstico, continúa con la implementación, salvo que exista un bloqueo real que requiera una decisión del usuario.

No reescribas todo desde cero sin necesidad. Conserva las partes correctas de `Cajeroreferencia` y adapta su implementación de sockets bloqueantes al nuevo sistema.

No elimines ni dañes `Cajeroreferencia`. Debe mantenerse como referencia, a menos que la estructura del repositorio indique expresamente que allí está el código que debe modificarse.

---

## 3. Topología y modelo de comunicación

La topología lógica es una estrella cliente-servidor:

```text
Cliente Windows ───┐
                   ├── Servidor Java en Debian
Cliente macOS ─────┘
```

Reglas:

- Los clientes nunca se comunican directamente entre sí.
- Cada cliente abre una única conexión TCP persistente con el servidor.
- El servidor escucha en un puerto configurable.
- Al aceptar una conexión, el servidor crea y conserva un socket exclusivo para ese cliente.
- El mismo socket se utiliza para:
  - Registro.
  - Chat grupal.
  - Chat privado.
  - Actualización de usuarios.
  - Desconexión.
- No se debe abrir un socket nuevo por cada chat privado.
- Las ventanas privadas son vistas adicionales de la interfaz; el servidor enruta los mensajes usando los campos `from` y `to`.

---

## 4. Contrato de transporte obligatorio

| Elemento | Acuerdo |
|---|---|
| Protocolo | TCP |
| Tipo de socket | Bloqueante |
| Codificación | UTF-8 |
| Formato | JSON |
| Delimitación | Un salto de línea `\n` después de cada objeto JSON |
| Puerto inicial | `5000`, configurable |
| Dirección del servidor | Configurable en los clientes |
| Nombre de usuario | Único durante la sesión |
| Tamaño máximo inicial | 8 KB por mensaje |

Cada mensaje debe enviarse como una sola línea:

```json
{"type":"REGISTER","username":"ana"}\n
```

No usar:

- Serialización nativa de Java.
- `ObjectInputStream`.
- `ObjectOutputStream`.
- Pickle.
- Formatos binarios dependientes del lenguaje.

El protocolo debe funcionar igual en Java, Python y Swift.

---

## 5. Tipos de mensajes

Implementa y documenta como mínimo los siguientes mensajes.

### Registro

Cliente a servidor:

```json
{"type":"REGISTER","username":"ana"}
```

Servidor a cliente cuando el registro es válido:

```json
{"type":"REGISTER_OK","username":"ana"}
```

Servidor a cliente cuando el nombre ya existe o es inválido:

```json
{"type":"REGISTER_ERROR","message":"El nombre de usuario ya está en uso"}
```

### Mensaje grupal

Cliente a servidor:

```json
{"type":"GROUP_MESSAGE","text":"Hola a todos"}
```

Servidor a clientes:

```json
{
  "type":"GROUP_MESSAGE",
  "from":"ana",
  "text":"Hola a todos",
  "timestamp":"2026-08-05T10:45:00-05:00",
  "sequence":15
}
```

### Mensaje privado

Cliente a servidor:

```json
{
  "type":"PRIVATE_MESSAGE",
  "to":"carlos",
  "text":"Hola, este mensaje es privado"
}
```

Servidor al emisor y al destinatario:

```json
{
  "type":"PRIVATE_MESSAGE",
  "from":"ana",
  "to":"carlos",
  "text":"Hola, este mensaje es privado",
  "timestamp":"2026-08-05T10:45:10-05:00",
  "sequence":16
}
```

El servidor debe devolver el mensaje privado tanto al destinatario como al emisor para que ambos puedan mostrarlo en su ventana.

### Lista de usuarios

Servidor a clientes:

```json
{
  "type":"USER_LIST",
  "users":["ana","carlos","maria"]
}
```

### Entrada y salida

```json
{"type":"USER_JOINED","username":"maria"}
```

```json
{"type":"USER_LEFT","username":"maria"}
```

### Desconexión

```json
{"type":"DISCONNECT"}
```

### Error

```json
{
  "type":"ERROR",
  "message":"Mensaje inválido"
}
```

---

## 6. Modelo de hilos y algoritmo de sincronización

El profesor indicó que el cliente debe separar:

1. El hilo del entorno gráfico.
2. El hilo que escucha al servidor.
3. El hilo que envía mensajes.

Implementa este modelo de forma explícita.

### Algoritmo elegido: productor-consumidor con colas FIFO bloqueantes

No usar espera activa, ciclos que consulten constantemente ni varios hilos escribiendo simultáneamente en el mismo socket.

#### En cada cliente

```text
Hilo de interfaz gráfica
        │
        │ produce mensajes
        ▼
Cola FIFO de salida
        │
        │ consume
        ▼
Hilo emisor ──────────────► socket TCP

socket TCP ───────────────► Hilo receptor
                                  │
                                  ▼
                         Actualización segura de UI
```

Responsabilidades:

- **Hilo de interfaz:** procesa botones, campos de texto, selección de usuarios y ventanas.
- **Hilo receptor:** ejecuta una lectura bloqueante permanente del socket.
- **Hilo emisor:** espera en una cola bloqueante y envía los mensajes en orden FIFO.
- Solo el hilo emisor puede escribir en el socket.
- Solo el hilo receptor puede leer del socket.
- La interfaz nunca debe bloquearse esperando datos de red.

Implementación esperada:

- Python: `queue.Queue`, `threading.Thread` y `root.after(...)`.
- Swift: `MainActor` para UI y un actor o cola serial para las escrituras; recepción en una tarea o cola separada.
- No actualizar componentes gráficos directamente desde un hilo de red.

### En el servidor

Usar el siguiente modelo:

```text
Hilo aceptador
    │
    ├── acepta socket del cliente A
    ├── acepta socket del cliente B
    └── acepta socket del cliente N

Un ClientReader por cliente
    │
    ▼
Cola global FIFO de eventos
    │
    ▼
Dispatcher central
    │
    ├── valida
    ├── asigna sequence
    ├── actualiza usuarios
    ├── enruta mensajes grupales
    └── enruta mensajes privados

Cada cliente:
cola FIFO de salida → ClientWriter → socket
```

Este algoritmo debe usarse para “turnar” y ordenar los mensajes:

- Los hilos lectores producen eventos.
- Un `Dispatcher` central consume los eventos en orden FIFO.
- El servidor asigna un número `sequence` incremental.
- Cada conexión tiene una cola de salida FIFO.
- Un único escritor por cliente envía mensajes por su socket.
- Esto evita escrituras concurrentes, condiciones de carrera y mensajes JSON mezclados.

En Java se pueden usar:

- `ServerSocket`.
- `Socket`.
- `BufferedReader`.
- `BufferedWriter` o `PrintWriter`.
- `BlockingQueue`.
- `LinkedBlockingQueue`.
- `ConcurrentHashMap`.
- `AtomicLong`.
- `ExecutorService`, si encaja con la implementación existente.

No introducir asincronía no bloqueante tipo NIO si contradice la implementación bloqueante de `Cajeroreferencia`.

---

## 7. Estado del servidor

El servidor es con estado durante la sesión y debe mantener, como mínimo:

```text
username → ClientSession
```

Cada `ClientSession` debe contener:

- Nombre de usuario.
- Socket.
- Lector.
- Cola de salida.
- Estado de conexión.
- Hilo lector.
- Hilo escritor.

Usar una estructura concurrente para los usuarios conectados.

Condiciones obligatorias:

- El nombre debe ser único.
- Un socket no registrado no puede enviar mensajes.
- Al desconectarse un cliente:
  - Retirarlo del mapa de usuarios.
  - Cerrar recursos.
  - Avisar a los demás.
  - Enviar la nueva lista de usuarios.
- La limpieza debe ejecutarse también ante:
  - Cierre normal.
  - Error de red.
  - Excepción.
  - Cierre abrupto de la máquina virtual.

---

## 8. Chat grupal

Al registrar correctamente un nombre:

1. El usuario entra automáticamente al chat grupal.
2. El servidor envía la lista actual de conectados.
3. El servidor notifica a los demás usuarios.
4. La ventana grupal permanece abierta durante toda la sesión.
5. Los mensajes deben visualizarse como mínimo con:
   - Usuario.
   - Texto.
   - Hora.

Ejemplo visual:

```text
ana: hola
carlos: hola
maria: :)
```

El área de mensajes debe ser de solo lectura.

---

## 9. Chat privado

La lista de usuarios conectados debe permitir iniciar un chat privado mediante una interacción clara, por ejemplo:

- Doble clic sobre el usuario.
- Botón “Chat privado”.
- Menú contextual.

Comportamiento obligatorio:

1. Al seleccionar otro usuario, abrir una segunda ventana no modal.
2. No cerrar, ocultar ni reemplazar la ventana grupal.
3. Mantener una sola ventana privada por pareja de usuarios.
4. Si la ventana ya existe, enfocarla en lugar de crear otra.
5. Todos los chats privados reutilizan el socket principal.
6. Los mensajes privados se enrutan por el nombre del destinatario.
7. Si llega un privado y la ventana no existe, crearla o mostrar una notificación y abrirla.
8. Si el destinatario se desconecta:
   - Informarlo en la ventana.
   - Desactivar el envío hasta que vuelva a conectarse o cerrar el chat.
9. No permitir abrir un chat privado con el mismo usuario.
10. No mostrar mensajes privados dentro del chat grupal.

Mantener una estructura local semejante a:

```text
username → ventana privada
```

---

## 10. Interfaz gráfica

La interfaz debe parecerse lo máximo razonablemente posible al diseño de referencia.

### Ventana principal

Debe incluir:

- Nombre del usuario en la parte superior.
- Área grande de mensajes grupales.
- Panel lateral con usuarios conectados.
- Campo de escritura inferior.
- Botón `Send` o `Enviar`.
- Estados visuales de conexión y errores.
- Acción para abrir chat privado.

Distribución esperada:

```text
┌─────────────────────────────────────────────┐
│ Nombre del usuario                          │
├───────────────────────────────┬─────────────┤
│                               │ Usuarios    │
│ Chat grupal                   │ conectados  │
│                               │             │
├───────────────────────────────┴─────────────┤
│ Campo de mensaje                  [Enviar]  │
└─────────────────────────────────────────────┘
```

### Ventana privada

Debe incluir:

- Nombre del destinatario.
- Historial privado.
- Campo de texto.
- Botón de envío.
- Estado conectado/desconectado.

### Reglas de experiencia de usuario

- Pulsar Enter debe enviar.
- No enviar mensajes vacíos.
- Limitar mensajes a 8 KB en UTF-8.
- Mostrar errores sin cerrar la aplicación.
- Deshabilitar el botón de envío cuando no haya conexión.
- Cerrar correctamente socket e hilos al salir.
- La UI jamás debe congelarse.

---

## 11. Cliente Windows en Python

Usar preferentemente la biblioteca gráfica ya presente en el repositorio. Si no existe una decisión previa, usar Tkinter.

Separar como mínimo:

```text
client-windows/
├── main.py
├── gui.py
├── network_client.py
├── protocol.py
└── models.py
```

Requisitos:

- `network_client.py` no debe depender directamente de widgets.
- La UI debe recibir eventos mediante una cola segura.
- Usar `root.after(...)` para aplicar cambios gráficos.
- El hilo emisor consume `queue.Queue`.
- El hilo receptor realiza lectura bloqueante.
- Encapsular codificación y decodificación JSON en `protocol.py`.

---

## 12. Cliente macOS en Swift

Usar Swift con SwiftUI, salvo que el proyecto existente ya tenga una tecnología diferente aprobada.

Separar como mínimo:

```text
client-macos/
├── ChatApp.swift
├── ContentView.swift
├── ChatViewModel.swift
├── NetworkClient.swift
├── ProtocolMessage.swift
├── GroupChatView.swift
└── PrivateChatWindow.swift
```

Requisitos:

- Toda actualización visual debe ejecutarse en `MainActor`.
- La red no debe bloquear el hilo principal.
- Mantener una sola conexión TCP.
- Implementar recepción delimitada por `\n`.
- Debe manejar fragmentación TCP:
  - Un JSON puede llegar en varios paquetes.
  - Varios JSON pueden llegar juntos.
- Acumular bytes en un búfer y extraer líneas completas.
- Serializar y deserializar con `Codable`.
- Usar una cola serial o un actor para garantizar una sola escritura a la vez.

---

## 13. Servidor Java en Debian

Separar responsabilidades. Una estructura posible es:

```text
server-java/
├── ChatServer.java
├── ClientSession.java
├── ClientReader.java
├── ClientWriter.java
├── ServerDispatcher.java
├── ProtocolMessage.java
├── MessageCodec.java
└── ServerConfig.java
```

No es obligatorio usar estos nombres exactos si la estructura actual ofrece una opción mejor.

Requisitos:

- Puerto configurable.
- Escuchar en una interfaz configurable o en `0.0.0.0`.
- Aceptar múltiples conexiones.
- Un socket por usuario.
- Registro obligatorio antes del chat.
- Lista concurrente de sesiones.
- Enrutamiento grupal y privado.
- Orden global mediante `sequence`.
- Cierre seguro.
- Registro en consola de:
  - Inicio del servidor.
  - IP y puerto.
  - Conexiones.
  - Registros.
  - Desconexiones.
  - Errores.
- No imprimir contenido sensible innecesario.

---

## 14. Manejo correcto de TCP

TCP es un flujo de bytes, no un sistema de mensajes. Por lo tanto:

- No asumir que una llamada a `recv` o `read` contiene exactamente un JSON.
- Implementar un búfer de entrada.
- Extraer mensajes únicamente cuando aparezca `\n`.
- Conservar en el búfer cualquier fragmento incompleto.
- Rechazar líneas mayores de 8 KB.
- Validar que el JSON tenga el campo `type`.
- No permitir saltos de línea sin escapar dentro de un mensaje.
- Normalizar todos los envíos a UTF-8.

---

## 15. Validaciones mínimas

### Usuario

- No vacío.
- Longitud razonable, por ejemplo entre 3 y 20 caracteres.
- Sin saltos de línea.
- Único durante la sesión.
- Preferiblemente letras, números, guion y guion bajo.

### Mensaje

- No vacío después de aplicar `trim`.
- Máximo 8 KB en UTF-8.
- Destinatario existente para mensajes privados.
- Tipo permitido.

### JSON

- Manejar mensajes mal formados.
- Responder con `ERROR`.
- No cerrar todo el servidor por el error de un cliente.

---

## 16. Configuración de red virtual

El sistema se ejecutará en máquinas virtuales.

Configuración prevista:

```text
Mac anfitrión
├── Cliente macOS en Swift
├── VM Debian con servidor Java
└── VM Windows con cliente Python
```

Usar una red virtual donde los tres nodos puedan comunicarse, preferiblemente:

- Adaptador privado o host-only para el chat.
- NAT adicional si las máquinas requieren internet.

No fijar permanentemente una IP en el código. La dirección del servidor debe configurarse mediante:

- Archivo de configuración.
- Variable de entorno.
- Argumento.
- Campo inicial de conexión.

Documentar cómo comprobar conectividad con `ping` y cómo probar el puerto.

---

## 17. Compatibilidad con la implementación existente

Al adaptar `Cajeroreferencia`:

- Mantén sockets bloqueantes.
- Reutiliza patrones correctos de conexión, lectura y cierre.
- Corrige accesos concurrentes inseguros.
- Separa interfaz, red y protocolo.
- Evita copiar nombres o responsabilidades confusas.
- No dupliques código de protocolo en múltiples archivos sin una razón.
- No cambies de forma silenciosa las reglas JSON.
- Si el código existente contradice este documento, explica la diferencia y aplica este documento como contrato, salvo que exista un requisito explícito del profesor.

---

## 18. Pruebas obligatorias

Implementa o documenta pruebas para los siguientes casos:

1. Registrar un primer usuario.
2. Rechazar un nombre duplicado.
3. Conectar Windows y macOS.
4. Enviar un mensaje grupal desde Windows.
5. Enviar un mensaje grupal desde macOS.
6. Ver el mensaje en ambos clientes.
7. Abrir un chat privado sin cerrar el grupal.
8. Enviar un privado en ambas direcciones.
9. Verificar que otro usuario no reciba ese privado.
10. Abrir nuevamente el mismo chat y reutilizar su ventana.
11. Desconectar un usuario normalmente.
12. Cerrar abruptamente un cliente.
13. Reiniciar una máquina virtual cliente.
14. Enviar dos mensajes rápidamente.
15. Enviar mensajes simultáneos desde varios clientes.
16. Recibir varios JSON en un mismo paquete TCP.
17. Recibir un JSON fragmentado en varios paquetes.
18. Rechazar un mensaje mayor de 8 KB.
19. Manejar JSON inválido.
20. Cerrar el servidor sin dejar recursos abiertos.

---

## 19. Criterios de aceptación

La implementación estará terminada cuando:

- El servidor Java corre correctamente en Debian.
- El cliente Python corre en Windows.
- El cliente Swift corre en macOS.
- Todos usan TCP, UTF-8, JSON y delimitación por salto de línea.
- Cada usuario tiene un socket persistente.
- El nombre es único.
- El chat grupal funciona.
- La lista de conectados se actualiza.
- Los privados se abren en ventanas separadas.
- La ventana grupal permanece abierta.
- La UI no se congela.
- Existen tres responsabilidades separadas en los clientes:
  - UI.
  - Recepción.
  - Envío.
- El productor-consumidor FIFO ordena los envíos.
- El dispatcher del servidor ordena y enruta eventos.
- No existen escrituras concurrentes al mismo socket.
- Se manejan desconexiones y errores.
- La interfaz conserva una apariencia cercana al diseño proporcionado.
- Existe documentación para compilar, ejecutar y probar en las máquinas virtuales.

---

## 20. Forma de trabajo esperada

Trabaja en este orden:

1. Inspeccionar `Cajeroreferencia`.
2. Inspeccionar el resto del repositorio.
3. Explicar la arquitectura actual.
4. Identificar riesgos y diferencias.
5. Proponer un plan de archivos concretos.
6. Implementar primero el protocolo compartido.
7. Adaptar el servidor Java.
8. Implementar o adaptar el cliente Windows.
9. Implementar o adaptar el cliente macOS.
10. Probar registro y chat grupal.
11. Agregar chat privado.
12. Agregar manejo de desconexiones.
13. Ajustar la interfaz para que se parezca al diseño.
14. Crear o actualizar documentación.
15. Entregar un resumen final con:
    - Archivos modificados.
    - Decisiones tomadas.
    - Cómo ejecutar cada componente.
    - Pruebas realizadas.
    - Limitaciones pendientes.

No declares que algo funciona si no fue compilado o probado. Cuando no puedas ejecutar una parte por depender de otro sistema operativo, indícalo claramente y deja instrucciones exactas para verificarla.
