# ServerChat — Servidor Java (Debian)

Servidor de chat distribuido. Ver `../PROTOCOL.md` y `../CLAUDE-2.md` para el contrato completo.

## Compilar

```bash
cd ServerChat
javac -d out $(find src -name "*.java")
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

## Notas de implementación

- `ChatServer`: hilo aceptador, un socket por cliente.
- `ClientReader`/`ClientWriter`: un lector y un escritor por sesión (nunca escrituras concurrentes al mismo socket).
- `ServerDispatcher`: consumidor único de la cola global de eventos; asigna `sequence`, valida, enruta grupal/privado y mantiene `username → ClientSession`.
- `chat.protocol.Json`: codec JSON propio (sin dependencias externas), soporta objetos, arrays, strings, números y booleanos.
- `chat.util.LineReader`: framing por `\n` sobre el flujo de bytes del socket, tolera fragmentación y rechaza líneas > 8 KB.
