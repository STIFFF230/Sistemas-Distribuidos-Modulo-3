"""Punto de entrada del cliente Windows (Python + Tkinter)."""

import os
import queue
import tkinter as tk
from tkinter import messagebox, simpledialog

import protocol
from gui import MainWindow
from network_client import NetworkClient

# Servidor y puerto no se preguntan por pantalla: se toman de aquí, o de las
# variables de entorno CHAT_HOST/CHAT_PORT si se necesita apuntar a otra
# máquina (VM Debian, otra IP, etc.) sin tocar el código — ver CLAUDE-2.md
# sección 16. El valor por defecto coincide con el servidor de pruebas local.
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 1802
REGISTER_TIMEOUT_MS = 5000


def connection_params():
    host = os.environ.get("CHAT_HOST", DEFAULT_HOST).strip() or DEFAULT_HOST
    try:
        port = int(os.environ.get("CHAT_PORT", str(DEFAULT_PORT)))
    except ValueError:
        port = DEFAULT_PORT
    return host, port


def register_and_wait(root: tk.Tk, network: NetworkClient, username: str) -> dict:
    """Envía REGISTER y espera REGISTER_OK/REGISTER_ERROR sin bloquear el
    bucle de eventos de Tk (usa wait_variable, que sí procesa eventos)."""
    network.send(protocol.register(username))
    done = tk.IntVar(root, value=0)
    result = {"status": None, "message": None}

    def poll():
        try:
            while True:
                message = network.incoming.get_nowait()
                msg_type = message.get("type")
                if msg_type == protocol.TYPE_REGISTER_OK:
                    result["status"] = "ok"
                elif msg_type == protocol.TYPE_REGISTER_ERROR:
                    result["status"] = "error"
                    result["message"] = message.get("message")
                elif msg_type == "_DISCONNECTED":
                    result["status"] = "error"
                    result["message"] = "Se perdió la conexión con el servidor."
                if result["status"] is not None:
                    done.set(1)
                    return
        except queue.Empty:
            pass
        root.after(100, poll)

    timeout_id = root.after(REGISTER_TIMEOUT_MS, lambda: done.set(1))
    poll()
    root.wait_variable(done)
    root.after_cancel(timeout_id)
    if result["status"] is None:
        result["status"] = "timeout"
    return result


def main() -> None:
    root = tk.Tk()
    root.withdraw()

    host, port = connection_params()

    network = NetworkClient(host, port)
    try:
        network.connect()
    except OSError as e:
        messagebox.showerror("Error de conexión", f"No fue posible conectar con {host}:{port}.\n{e}")
        root.destroy()
        return

    username = None
    while True:
        username = simpledialog.askstring("Registro", "Nombre de usuario:", parent=root)
        if not username:
            network.close()
            root.destroy()
            return
        username = username.strip()
        result = register_and_wait(root, network, username)
        if result["status"] == "ok":
            break
        if result["status"] == "timeout":
            messagebox.showerror("Sin respuesta", "El servidor no respondió a tiempo.")
            network.close()
            root.destroy()
            return
        messagebox.showerror("Registro rechazado", result["message"] or "No fue posible registrar el usuario.")

    root.deiconify()
    MainWindow(root, username, network)
    root.mainloop()


if __name__ == "__main__":
    main()
