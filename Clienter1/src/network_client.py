"""Capa de red: no depende de widgets. Separa el hilo receptor (lectura
bloqueante) del hilo emisor (cola FIFO), tal como exige CLAUDE-2.md sección 6.
La interfaz gráfica solo interactúa con `incoming` (queue.Queue) y `send()`.
"""

import json
import queue
import socket
import threading
from typing import Optional

import protocol

DISCONNECTED_EVENT = {"type": "_DISCONNECTED"}


class NetworkClient:
    def __init__(self, host: str, port: int):
        self.host = host
        self.port = port
        self.incoming: "queue.Queue[dict]" = queue.Queue()
        self._outgoing: "queue.Queue[Optional[dict]]" = queue.Queue()
        self._socket: Optional[socket.socket] = None
        self._reader = None
        self._writer = None
        self._connected = False

    def connect(self) -> None:
        self._socket = socket.create_connection((self.host, self.port), timeout=10)
        self._socket.settimeout(None)
        self._reader = self._socket.makefile("r", encoding="utf-8")
        self._writer = self._socket.makefile("w", encoding="utf-8")
        self._connected = True
        threading.Thread(target=self._receive_loop, daemon=True, name="network-receiver").start()
        threading.Thread(target=self._send_loop, daemon=True, name="network-sender").start()

    def is_connected(self) -> bool:
        return self._connected

    def send(self, message: dict) -> None:
        """Encola un mensaje para el hilo emisor. Nunca escribe directamente en el socket."""
        if self._connected:
            self._outgoing.put(message)

    def close(self) -> None:
        self._connected = False
        self._outgoing.put(None)  # despierta al hilo emisor para que termine
        if self._socket is not None:
            try:
                self._socket.close()
            except OSError:
                pass

    def _receive_loop(self) -> None:
        try:
            while True:
                line = self._reader.readline()
                if not line:
                    break
                line = line.strip()
                if not line:
                    continue
                try:
                    message = protocol.decode(line)
                except json.JSONDecodeError:
                    continue
                self.incoming.put(message)
        except OSError:
            pass
        finally:
            self._connected = False
            self.incoming.put(DISCONNECTED_EVENT)

    def _send_loop(self) -> None:
        while True:
            message = self._outgoing.get()
            if message is None:
                return
            try:
                self._writer.write(protocol.encode(message))
                self._writer.write("\n")
                self._writer.flush()
            except OSError:
                return
