"""Codificación/decodificación del protocolo JSON+\\n. Ver PROTOCOL.md."""

import json

TYPE_REGISTER = "REGISTER"
TYPE_REGISTER_OK = "REGISTER_OK"
TYPE_REGISTER_ERROR = "REGISTER_ERROR"
TYPE_GROUP_MESSAGE = "GROUP_MESSAGE"
TYPE_PRIVATE_MESSAGE = "PRIVATE_MESSAGE"
TYPE_USER_LIST = "USER_LIST"
TYPE_USER_JOINED = "USER_JOINED"
TYPE_USER_LEFT = "USER_LEFT"
TYPE_DISCONNECT = "DISCONNECT"
TYPE_ERROR = "ERROR"

MAX_MESSAGE_BYTES = 8192


def encode(message: dict) -> str:
    return json.dumps(message, ensure_ascii=False, separators=(",", ":"))


def decode(line: str) -> dict:
    return json.loads(line)


def register(username: str) -> dict:
    return {"type": TYPE_REGISTER, "username": username}


def group_message(text: str) -> dict:
    return {"type": TYPE_GROUP_MESSAGE, "text": text}


def private_message(to: str, text: str) -> dict:
    return {"type": TYPE_PRIVATE_MESSAGE, "to": to, "text": text}


def disconnect() -> dict:
    return {"type": TYPE_DISCONNECT}
