"""Punto de entrada del cliente Windows (Python + Tkinter)."""

import os
import queue
import tkinter as tk
from tkinter import messagebox, simpledialog

import protocol
from gui import MainWindow
from network_client import NetworkClient


def _load_env_file(filename=".env"):
    """Carga variables KEY=VALOR desde un archivo .env, buscando desde el
    directorio de trabajo actual hacia arriba (encuentra tanto un .env junto
    a main.py como uno compartido en la raíz del proyecto, Modulo3/.env).
    No sobreescribe variables que ya estén definidas en el entorno real."""
    here = os.path.abspath(os.getcwd())
    for _ in range(6):
        candidate = os.path.join(here, filename)
        if os.path.isfile(candidate):
            with open(candidate, "r", encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    key, _, value = line.partition("=")
                    key = key.strip()
                    value = value.strip().strip('"').strip("'")
                    if key:
                        os.environ.setdefault(key, value)
            return
        parent = os.path.dirname(here)
        if parent == here:
            break
        here = parent


_load_env_file()

# Servidor y puerto no se preguntan por pantalla ni se fijan en el código
# (CLAUDE-2.md sección 16). Se toman de las variables de entorno
# CHAT_HOST/CHAT_PORT (definidas directamente o cargadas desde .env arriba).
# Edita el archivo .env cuando cambie la IP de la VM Debian, sin tocar código.
DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 5000
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
