"""Interfaz gráfica en Tkinter. Corre en el hilo principal; nunca lee ni
escribe del socket directamente. Recibe eventos de red mediante
NetworkClient.incoming y los aplica con root.after (sondeo periódico no bloqueante).
"""

import queue
import tkinter as tk
from datetime import datetime
from tkinter import messagebox, scrolledtext
from typing import Dict, Optional

import protocol
from network_client import NetworkClient

POLL_INTERVAL_MS = 100


def _format_time(timestamp: str) -> str:
    try:
        return datetime.fromisoformat(timestamp).strftime("%H:%M:%S")
    except (ValueError, TypeError):
        return ""


class PrivateChatWindow:
    """Ventana no modal para el chat privado con un usuario. Se reutiliza
    mientras exista (nunca se crea una segunda ventana para el mismo par)."""

    def __init__(self, master: tk.Widget, peer_username: str, on_send, on_close):
        self.peer_username = peer_username
        self.connected = True
        self._on_send = on_send
        self._on_close = on_close

        self.window = tk.Toplevel(master)
        self.window.title(f"Chat privado — {peer_username}")
        self.window.geometry("420x360")
        self.window.minsize(320, 240)
        # Nunca dejar que la ventana crezca mas alla de la pantalla: si eso
        # pasa, el campo de texto/boton (empacados abajo) quedan fuera de
        # vista aunque el codigo de layout sea correcto.
        self.window.maxsize(self.window.winfo_screenwidth(), self.window.winfo_screenheight())
        self.window.protocol("WM_DELETE_WINDOW", self._handle_close)

        self.status_label = tk.Label(self.window, text="Conectado", fg="green", anchor="w")
        self.status_label.pack(side="top", fill="x", padx=8, pady=(8, 0))

        entry_frame = tk.Frame(self.window)
        entry_frame.pack(side="bottom", fill="x", padx=8, pady=(0, 8))
        self.entry = tk.Entry(entry_frame)
        self.entry.pack(side="left", fill="x", expand=True)
        self.entry.bind("<Return>", lambda _e: self._send())
        self.entry.focus_set()
        self.send_button = tk.Button(entry_frame, text="Enviar", command=self._send)
        self.send_button.pack(side="left", padx=(4, 0))

        self.history = scrolledtext.ScrolledText(self.window, state="disabled", wrap="word")
        self.history.pack(side="top", fill="both", expand=True, padx=8, pady=8)

    def _send(self) -> None:
        text = self.entry.get().strip()
        if not text or not self.connected:
            return
        self._on_send(self.peer_username, text)
        self.entry.delete(0, tk.END)

    def append(self, sender: str, text: str, timestamp: str) -> None:
        self.history.configure(state="normal")
        self.history.insert(tk.END, f"[{_format_time(timestamp)}] {sender}: {text}\n")
        self.history.configure(state="disabled")
        self.history.see(tk.END)

    def mark_connected(self) -> None:
        self.connected = True
        self.status_label.configure(text="Conectado", fg="green")
        self.send_button.configure(state="normal")

    def mark_disconnected(self) -> None:
        self.connected = False
        self.status_label.configure(text=f"{self.peer_username} está desconectado", fg="red")
        self.send_button.configure(state="disabled")

    def focus(self) -> None:
        self.window.deiconify()
        self.window.lift()
        self.window.focus_force()

    def _handle_close(self) -> None:
        self._on_close(self.peer_username)
        self.window.destroy()


class MainWindow:
    """Ventana grupal: permanece abierta durante toda la sesión (sección 8)."""

    def __init__(self, root: tk.Tk, username: str, network: NetworkClient):
        self.root = root
        self.username = username
        self.network = network
        self.private_windows: Dict[str, PrivateChatWindow] = {}
        self.connected_users: list[str] = []

        self.root.title(f"Chat grupal — {username}")
        self.root.geometry("780x480")
        self.root.minsize(560, 360)
        # Nunca dejar que la ventana crezca mas alla de la pantalla: si eso
        # pasa, el campo de texto/boton (empacados abajo) quedan fuera de
        # vista aunque el codigo de layout sea correcto.
        self.root.maxsize(self.root.winfo_screenwidth(), self.root.winfo_screenheight())
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

        self._build_widgets()
        self.root.after(POLL_INTERVAL_MS, self._poll_events)

    def _build_widgets(self) -> None:
        header = tk.Label(self.root, text=f"Usuario: {self.username}", font=("Helvetica", 13, "bold"), anchor="w")
        header.pack(side="top", fill="x", padx=10, pady=8)

        self.status_label = tk.Label(self.root, text="Conectado", fg="green", anchor="w")
        self.status_label.pack(side="bottom", fill="x", padx=10, pady=(0, 8))

        bottom = tk.Frame(self.root)
        bottom.pack(side="bottom", fill="x", padx=10, pady=8)
        self.message_entry = tk.Entry(bottom)
        self.message_entry.pack(side="left", fill="x", expand=True)
        self.message_entry.bind("<Return>", lambda _e: self._send_group_message())
        self.message_entry.focus_set()
        self.send_button = tk.Button(bottom, text="Enviar", command=self._send_group_message)
        self.send_button.pack(side="left", padx=(4, 0))

        body = tk.Frame(self.root)
        body.pack(side="top", fill="both", expand=True, padx=10)

        self.chat_area = scrolledtext.ScrolledText(body, state="disabled", wrap="word")
        self.chat_area.pack(side="left", fill="both", expand=True)

        side = tk.Frame(body, width=200)
        side.pack(side="left", fill="y", padx=(8, 0))
        side.pack_propagate(False)
        tk.Label(side, text="Usuarios conectados", anchor="w").pack(fill="x")
        self.user_listbox = tk.Listbox(side)
        self.user_listbox.pack(fill="both", expand=True)
        self.user_listbox.bind("<Double-Button-1>", self._open_private_chat_from_selection)
        tk.Button(side, text="Chat privado", command=self._open_private_chat_from_selection).pack(fill="x", pady=(4, 0))

    # ---------- envío ----------

    def _send_group_message(self) -> None:
        text = self.message_entry.get().strip()
        if not text or not self.network.is_connected():
            return
        if len(text.encode("utf-8")) > protocol.MAX_MESSAGE_BYTES:
            messagebox.showerror("Mensaje muy largo", "El mensaje supera los 8 KB permitidos.")
            return
        self.network.send(protocol.group_message(text))
        self.message_entry.delete(0, tk.END)

    def _send_private_message(self, to_username: str, text: str) -> None:
        if len(text.encode("utf-8")) > protocol.MAX_MESSAGE_BYTES:
            messagebox.showerror("Mensaje muy largo", "El mensaje supera los 8 KB permitidos.")
            return
        self.network.send(protocol.private_message(to_username, text))

    # ---------- chats privados ----------

    def _open_private_chat_from_selection(self, _event=None) -> None:
        selection = self.user_listbox.curselection()
        if not selection:
            return
        self._open_private_chat(self.user_listbox.get(selection[0]))

    def _open_private_chat(self, peer_username: str) -> None:
        if peer_username == self.username:
            return
        window = self.private_windows.get(peer_username)
        if window is not None:
            window.focus()
            return
        window = PrivateChatWindow(
            self.root, peer_username,
            on_send=self._send_private_message,
            on_close=self._close_private_chat,
        )
        if peer_username not in self.connected_users:
            window.mark_disconnected()
        self.private_windows[peer_username] = window
        window.focus()

    def _close_private_chat(self, peer_username: str) -> None:
        self.private_windows.pop(peer_username, None)

    # ---------- recepción de eventos ----------

    def _poll_events(self) -> None:
        try:
            while True:
                message = self.network.incoming.get_nowait()
                self._handle_message(message)
        except queue.Empty:
            pass
        self.root.after(POLL_INTERVAL_MS, self._poll_events)

    def _handle_message(self, message: dict) -> None:
        msg_type = message.get("type")
        if msg_type == protocol.TYPE_GROUP_MESSAGE:
            self._append_group_message(message)
        elif msg_type == protocol.TYPE_PRIVATE_MESSAGE:
            self._handle_private_message(message)
        elif msg_type == protocol.TYPE_USER_LIST:
            self._update_user_list(message.get("users", []))
        elif msg_type == protocol.TYPE_USER_JOINED:
            self._handle_user_joined(message.get("username"))
        elif msg_type == protocol.TYPE_USER_LEFT:
            self._handle_user_left(message.get("username"))
        elif msg_type == protocol.TYPE_ERROR:
            messagebox.showwarning("Error del servidor", message.get("message", ""))
        elif msg_type == "_DISCONNECTED":
            self._handle_connection_lost()

    def _append_group_message(self, message: dict) -> None:
        self.chat_area.configure(state="normal")
        hora = _format_time(message.get("timestamp", ""))
        self.chat_area.insert(tk.END, f"[{hora}] {message.get('from')}: {message.get('text')}\n")
        self.chat_area.configure(state="disabled")
        self.chat_area.see(tk.END)

    def _handle_private_message(self, message: dict) -> None:
        sender = message.get("from")
        recipient = message.get("to")
        peer = recipient if sender == self.username else sender
        self._open_private_chat(peer)
        self.private_windows[peer].append(sender, message.get("text", ""), message.get("timestamp", ""))

    def _update_user_list(self, users: list) -> None:
        self.connected_users = [u for u in users if u != self.username]
        self.user_listbox.delete(0, tk.END)
        for user in self.connected_users:
            self.user_listbox.insert(tk.END, user)
        for peer, window in self.private_windows.items():
            (window.mark_connected if peer in self.connected_users else window.mark_disconnected)()

    def _handle_user_joined(self, username: Optional[str]) -> None:
        if username and username != self.username and username not in self.connected_users:
            self.connected_users.append(username)
            self.user_listbox.insert(tk.END, username)
        window = self.private_windows.get(username)
        if window is not None:
            window.mark_connected()

    def _handle_user_left(self, username: Optional[str]) -> None:
        if username in self.connected_users:
            self.connected_users.remove(username)
            items = list(self.user_listbox.get(0, tk.END))
            if username in items:
                self.user_listbox.delete(items.index(username))
        window = self.private_windows.get(username)
        if window is not None:
            window.mark_disconnected()

    def _handle_connection_lost(self) -> None:
        self.status_label.configure(text="Desconectado del servidor", fg="red")
        self.send_button.configure(state="disabled")
        for window in self.private_windows.values():
            window.mark_disconnected()

    def _on_close(self) -> None:
        if self.network.is_connected():
            self.network.send(protocol.disconnect())
        self.network.close()
        self.root.destroy()
