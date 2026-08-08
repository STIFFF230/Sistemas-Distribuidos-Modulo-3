// Capa de red: no depende de vistas. Un socket TCP bloqueante persistente,
// leído en un hilo dedicado y escrito desde una cola serial (una sola
// escritura a la vez), tal como exige CLAUDE-2.md sección 6/12.
//
// No se usa NIO ni async sockets: se usan llamadas POSIX bloqueantes
// (connect/recv/send) igual que el servidor Java y el cliente Python,
// ejecutadas siempre fuera del hilo principal.

import Foundation
#if canImport(Darwin)
import Darwin
#endif

enum NetworkError: Error, LocalizedError {
    case resolutionFailed(String)
    case connectionFailed(String)
    case notConnected

    var errorDescription: String? {
        switch self {
        case .resolutionFailed(let host):
            return "No fue posible resolver \(host)."
        case .connectionFailed(let reason):
            return "No fue posible conectar: \(reason)."
        case .notConnected:
            return "No hay conexión activa con el servidor."
        }
    }
}

final class NetworkClient: @unchecked Sendable {
    /// Se invoca desde el hilo receptor; quien lo asigne debe saltar a MainActor.
    var onMessage: ((IncomingEnvelope) -> Void)?
    /// Se invoca cuando se detecta desconexión (cierre remoto o error de E/S).
    var onDisconnected: (() -> Void)?

    private var socketFD: Int32 = -1
    private let writeQueue = DispatchQueue(label: "chat.network.write")
    private let stateLock = NSLock()
    private var connected = false
    private static let maxLineBytes = 65536

    var isConnected: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return connected
    }

    /// Resuelve, conecta (llamada bloqueante) y arranca el hilo receptor.
    /// Debe invocarse fuera del hilo principal.
    func connect(host: String, port: UInt16) throws {
        var hints = addrinfo(
            ai_flags: 0, ai_family: AF_UNSPEC, ai_socktype: SOCK_STREAM,
            ai_protocol: IPPROTO_TCP, ai_addrlen: 0, ai_canonname: nil, ai_addr: nil, ai_next: nil)
        var resultPointer: UnsafeMutablePointer<addrinfo>?
        let status = getaddrinfo(host, String(port), &hints, &resultPointer)
        guard status == 0, let firstResult = resultPointer else {
            throw NetworkError.resolutionFailed(host)
        }
        defer { freeaddrinfo(resultPointer) }

        var lastErrno: Int32 = 0
        var connectedFD: Int32 = -1
        var cursor: UnsafeMutablePointer<addrinfo>? = firstResult
        while let info = cursor {
            let fd = socket(info.pointee.ai_family, info.pointee.ai_socktype, info.pointee.ai_protocol)
            if fd >= 0 {
                if Darwin.connect(fd, info.pointee.ai_addr, info.pointee.ai_addrlen) == 0 {
                    connectedFD = fd
                    break
                }
                lastErrno = errno
                Darwin.close(fd)
            } else {
                lastErrno = errno
            }
            cursor = info.pointee.ai_next
        }
        guard connectedFD >= 0 else {
            throw NetworkError.connectionFailed(String(cString: strerror(lastErrno)))
        }

        var noDelay: Int32 = 1
        setsockopt(connectedFD, Int32(IPPROTO_TCP), TCP_NODELAY, &noDelay, socklen_t(MemoryLayout<Int32>.size))

        socketFD = connectedFD
        stateLock.lock()
        connected = true
        stateLock.unlock()

        let thread = Thread { [weak self] in self?.receiveLoop() }
        thread.name = "network-receiver"
        thread.start()
    }

    /// Envuelve `connect(host:port:)` para que nunca bloquee el hilo que la invoca.
    func connectAsync(host: String, port: UInt16) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    try self.connect(host: host, port: port)
                    continuation.resume()
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    /// Encola un mensaje para el hilo/cola emisora. Nunca escribe directamente el socket
    /// desde el llamador, y solo un bloque a la vez corre en `writeQueue`.
    func send<T: Encodable>(_ message: T) {
        writeQueue.async { [weak self] in
            guard let self, self.isConnected else { return }
            guard var data = try? JSONEncoder().encode(message) else { return }
            data.append(0x0A)
            self.writeAll(data)
        }
    }

    func disconnect() {
        stateLock.lock()
        let wasConnected = connected
        connected = false
        stateLock.unlock()
        guard wasConnected else { return }
        shutdown(socketFD, SHUT_RDWR)
    }

    // ---------- Lectura ----------

    private func receiveLoop() {
        var lineBuffer = Data()
        var chunk = [UInt8](repeating: 0, count: 8192)
        let newline: UInt8 = 0x0A

        readLoop: while true {
            let bytesRead = chunk.withUnsafeMutableBytes { buffer -> Int in
                recv(socketFD, buffer.baseAddress, buffer.count, 0)
            }
            if bytesRead <= 0 {
                break readLoop
            }
            lineBuffer.append(contentsOf: chunk[0..<bytesRead])

            while let newlineIndex = lineBuffer.firstIndex(of: newline) {
                let lineData = lineBuffer[lineBuffer.startIndex..<newlineIndex]
                handleLine(lineData)
                lineBuffer.removeSubrange(lineBuffer.startIndex...newlineIndex)
            }
            if lineBuffer.count > Self.maxLineBytes {
                break readLoop
            }
        }

        stateLock.lock()
        connected = false
        stateLock.unlock()
        Darwin.close(socketFD)
        onDisconnected?()
    }

    private func handleLine(_ rawLine: Data) {
        var lineData = rawLine
        if lineData.last == 0x0D { lineData.removeLast() } // tolera CRLF
        guard !lineData.isEmpty else { return }
        guard let envelope = try? JSONDecoder().decode(IncomingEnvelope.self, from: lineData) else { return }
        onMessage?(envelope)
    }

    // ---------- Escritura ----------

    private func writeAll(_ data: Data) {
        data.withUnsafeBytes { (rawBuffer: UnsafeRawBufferPointer) in
            guard let base = rawBuffer.baseAddress else { return }
            var totalSent = 0
            while totalSent < rawBuffer.count {
                let sent = Darwin.send(socketFD, base + totalSent, rawBuffer.count - totalSent, 0)
                if sent <= 0 {
                    stateLock.lock()
                    connected = false
                    stateLock.unlock()
                    return
                }
                totalSent += sent
            }
        }
    }
}
