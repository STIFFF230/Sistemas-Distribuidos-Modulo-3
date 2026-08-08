# Cliente2 — Cliente macOS (Swift + SwiftUI)

Cliente "amarillo" del chat distribuido. Ver `../PROTOCOL.md` y `../CLAUDE-2.md` para el contrato completo.

Empaquetado como paquete Swift Package Manager (no requiere proyecto Xcode) para poder compilarse
y ejecutarse desde línea de comandos con las Command Line Tools.

## Requisitos

- macOS con Swift toolchain (`xcode-select -p` debe apuntar a Xcode o a Command Line Tools).
- `swift --version` ≥ 5.10.

## Compilar

```bash
cd Cliente2
swift build
```

## Ejecutar

```bash
cd Cliente2
swift run
```

Se abre una ventana pidiendo solo el nombre de usuario. El servidor y el puerto no se preguntan
por pantalla: por defecto conecta a `127.0.0.1:1802` (el servidor de pruebas local); para apuntar
a otra máquina (la VM Debian, otra IP/puerto) sin tocar el código, definir las variables de entorno
antes de ejecutar:

```bash
CHAT_HOST=192.168.1.50 CHAT_PORT=5000 swift run
```

Tras un registro exitoso se abre la ventana de chat grupal; doble clic (o botón "Chat privado")
sobre un usuario de la lista abre su ventana privada, reutilizándola si ya existe.

## Estructura

```text
Sources/ChatMacClient/
├── ChatApp.swift            punto de entrada, cierre ordenado del socket al salir
├── ContentView.swift        formulario de conexión/registro
├── ChatViewModel.swift      estado @MainActor, enrutamiento de mensajes, registro
├── NetworkClient.swift      socket TCP bloqueante (Darwin), hilo receptor + cola de escritura
├── ProtocolMessage.swift    tipos Codable del protocolo JSON+\n
├── Models.swift             modelo de mensaje de UI + formateo de hora
├── GroupChatView.swift      ventana grupal (mensajes + panel de usuarios)
└── PrivateChatWindow.swift  NSWindow por par de usuarios + su vista SwiftUI
```

## Notas de implementación

- La conexión y lectura del socket usan llamadas POSIX bloqueantes (`connect`/`recv`/`send` de
  Darwin), igual que el servidor Java y el cliente Python — sin NIO ni sockets asíncronos.
- Solo el hilo receptor lee el socket; toda escritura pasa por una `DispatchQueue` serial
  (`writeQueue`), de forma que nunca hay dos escrituras concurrentes.
- Los datos entrantes se acumulan en un búfer y se extraen líneas completas por `\n`, tolerando
  JSON fragmentado o varios JSON en una misma lectura.
- Todas las actualizaciones de `@Published` ocurren en `MainActor`; los callbacks de red saltan
  explícitamente con `Task { @MainActor in ... }`.

## Pruebas realizadas

- `swift build` compila sin errores ni warnings.
- Prueba de red aislada (fuera de este repo, en un paquete SPM temporal) que reutiliza
  `NetworkClient.swift`/`ProtocolMessage.swift` tal cual y los ejecuta contra una instancia real
  de `ServerChat`: registro, rechazo de nombre duplicado, mensaje grupal, mensaje privado y
  `USER_LEFT` tras desconexión — todos verificados en tiempo de ejecución, no solo compilación.
- El binario compilado (`swift run`) se lanzó y permaneció corriendo sin crashear.

## Limitación pendiente

No fue posible automatizar una prueba interactiva de la interfaz gráfica (clics, apertura de
ventana privada, etc.) en este entorno: `osascript`/System Events no tiene permiso de
Accesibilidad concedido para controlar otras apps. Verificación manual pendiente en un login
gráfico real:

1. Tener un `ServerChat` corriendo (por ejemplo el mismo Mac en `127.0.0.1:1802`).
2. `cd Cliente2 && swift run`, escribir un nombre de usuario y conectar.
3. Repetir con un segundo cliente (otro `swift run` o `Clienter1`) para probar grupal, privado y
   reutilización de ventana privada según los casos de la sección 18 de `CLAUDE-2.md`.
