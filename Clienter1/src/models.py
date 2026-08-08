"""Modelos de dominio usados por la interfaz gráfica."""

from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class ChatMessage:
    sender: str
    text: str
    timestamp: str
    sequence: int
    recipient: Optional[str] = None  # None = mensaje grupal

    @property
    def is_private(self) -> bool:
        return self.recipient is not None
