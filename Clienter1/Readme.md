# Clienter1 — Cliente Windows (Python + Tkinter)

Cliente "rojo" del chat distribuido. Ver `../PROTOCOL.md` y `../CLAUDE-2.md` para el contrato completo.

## Requisitos

- Python 3.9+ con Tkinter (incluido en la instalación estándar de Python en Windows).

## Ejecutar

```bash
cd Clienter1/src
python main.py
```

Solo pide el nombre de usuario. El servidor y el puerto no se preguntan por pantalla: por defecto
conecta a `127.0.0.1:1802` (el servidor de pruebas local); para apuntar a otra máquina (la VM
Debian, otra IP/puerto) sin tocar el código, definir las variables de entorno antes de ejecutar:

```bash
CHAT_HOST=192.168.1.50 CHAT_PORT=5000 python main.py
```

Tras registrarse abre la ventana de chat grupal; doble clic (o botón "Chat privado") sobre un
usuario de la lista abre su ventana privada, reutilizándola si ya existe.

## Estructura

```text
src/
├── main.py             punto de entrada: conexión, registro, arranque de la ventana principal
├── gui.py               interfaz Tkinter: ventana grupal + ventanas privadas (Toplevel)
├── network_client.py    hilo receptor (lectura bloqueante) + hilo emisor (queue.Queue FIFO)
├── protocol.py          codificación/decodificación del protocolo JSON+\n
└── models.py            modelo de dominio ChatMessage
```

## Notas de implementación

- `network_client.py` no depende de widgets; la GUI solo interactúa con `NetworkClient.incoming`
  (una `queue.Queue`) y `NetworkClient.send(...)`.
- La GUI aplica los cambios con `root.after(...)` (sondeo periódico no bloqueante), nunca lee ni
  escribe el socket directamente.
- Solo el hilo emisor escribe en el socket, consumiendo una `queue.Queue` FIFO.
