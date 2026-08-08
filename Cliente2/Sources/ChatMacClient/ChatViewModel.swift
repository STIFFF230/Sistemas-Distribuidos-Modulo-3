// Estado de la aplicación. Toda actualización visual ocurre en MainActor
// (CLAUDE-2.md sección 12); los callbacks de NetworkClient llegan desde el
// hilo receptor y siempre se re-despachan a MainActor antes de tocar @Published.

import Foundation

enum ConnectionStatus {
    case disconnected
    case connecting
    case connected
}

enum ChatError: LocalizedError {
    case registrationTimeout
    case registrationRejected(String)

    var errorDescription: String? {
        switch self {
        case .registrationTimeout:
            return "El servidor no respondió a tiempo."
        case .registrationRejected(let message):
            return message
        }
    }
}

@MainActor
final class ChatViewModel: ObservableObject {
    @Published private(set) var status: ConnectionStatus = .disconnected
    @Published private(set) var username: String = ""
    @Published private(set) var groupMessages: [ChatMessageItem] = []
    @Published private(set) var connectedUsers: [String] = []
    @Published var errorBanner: String?

    private let network = NetworkClient()
    private var privateControllers: [String: PrivateChatWindowController] = [:]
    private var pendingRegistrationHandler: ((IncomingEnvelope) -> Void)?

    init() {
        network.onMessage = { [weak self] envelope in
            Task { @MainActor in self?.handle(envelope) }
        }
        network.onDisconnected = { [weak self] in
            Task { @MainActor in self?.handleDisconnected() }
        }
    }

    // ---------- Conexión y registro ----------

    func connectAndRegister(host: String, port: UInt16, username: String) async {
        status = .connecting
        errorBanner = nil
        do {
            try await network.connectAsync(host: host, port: port)
            try await register(username: username)
            self.username = username
            status = .connected
        } catch {
            status = .disconnected
            errorBanner = (error as? LocalizedError)?.errorDescription ?? "No fue posible conectar."
            network.disconnect()
        }
    }

    private func register(username: String) async throws {
        var received: IncomingEnvelope?
        pendingRegistrationHandler = { envelope in received = envelope }
        network.send(RegisterMessage(username: username))

        let deadline = Date().addingTimeInterval(5)
        while received == nil && Date() < deadline {
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        pendingRegistrationHandler = nil

        guard let envelope = received else {
            throw ChatError.registrationTimeout
        }
        if envelope.type == ProtocolType.registerError {
            throw ChatError.registrationRejected(envelope.message ?? "Registro rechazado.")
        }
    }

    func disconnect() {
        guard status != .disconnected else { return }
        network.send(DisconnectMessage())
        network.disconnect()
        status = .disconnected
    }

    // ---------- Envío ----------

    func sendGroupMessage(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.utf8.count <= ProtocolLimits.maxMessageBytes else { return }
        guard status == .connected else { return }
        network.send(GroupMessageOut(text: trimmed))
    }

    private func sendPrivateMessage(to peer: String, text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.utf8.count <= ProtocolLimits.maxMessageBytes else { return }
        guard status == .connected else { return }
        network.send(PrivateMessageOut(to: peer, text: trimmed))
    }

    // ---------- Chats privados ----------

    func openPrivateChat(with peer: String) {
        guard peer != username else { return }
        privateController(for: peer).showAndFocus()
    }

    @discardableResult
    private func privateController(for peer: String) -> PrivateChatWindowController {
        if let existing = privateControllers[peer] {
            return existing
        }
        let controller = PrivateChatWindowController(
            peerUsername: peer,
            initiallyConnected: connectedUsers.contains(peer),
            onSend: { [weak self] text in self?.sendPrivateMessage(to: peer, text: text) },
            onClose: { [weak self] in self?.privateControllers.removeValue(forKey: peer) }
        )
        privateControllers[peer] = controller
        return controller
    }

    // ---------- Recepción de eventos ----------

    private func handle(_ envelope: IncomingEnvelope) {
        if let handler = pendingRegistrationHandler,
           envelope.type == ProtocolType.registerOk || envelope.type == ProtocolType.registerError {
            handler(envelope)
            return
        }
        switch envelope.type {
        case ProtocolType.groupMessage:
            appendGroupMessage(envelope)
        case ProtocolType.privateMessage:
            handlePrivateMessage(envelope)
        case ProtocolType.userList:
            updateUserList(envelope.users ?? [])
        case ProtocolType.userJoined:
            handleUserJoined(envelope.username)
        case ProtocolType.userLeft:
            handleUserLeft(envelope.username)
        case ProtocolType.error:
            errorBanner = envelope.message
        default:
            break
        }
    }

    private func appendGroupMessage(_ envelope: IncomingEnvelope) {
        groupMessages.append(ChatMessageItem(
            sender: envelope.from ?? "?",
            text: envelope.text ?? "",
            timestamp: envelope.timestamp ?? "",
            sequence: envelope.sequence ?? 0))
    }

    private func handlePrivateMessage(_ envelope: IncomingEnvelope) {
        guard let from = envelope.from, let to = envelope.to else { return }
        let peer = (from == username) ? to : from
        let controller = privateController(for: peer)
        controller.appendMessage(sender: from, text: envelope.text ?? "", timestamp: envelope.timestamp ?? "")
    }

    private func updateUserList(_ users: [String]) {
        connectedUsers = users.filter { $0 != username }.sorted()
        for (peer, controller) in privateControllers {
            controller.setConnected(connectedUsers.contains(peer))
        }
    }

    private func handleUserJoined(_ username: String?) {
        guard let username, username != self.username else { return }
        if !connectedUsers.contains(username) {
            connectedUsers.append(username)
            connectedUsers.sort()
        }
        privateControllers[username]?.setConnected(true)
    }

    private func handleUserLeft(_ username: String?) {
        guard let username else { return }
        connectedUsers.removeAll { $0 == username }
        privateControllers[username]?.setConnected(false)
    }

    private func handleDisconnected() {
        guard status != .disconnected else { return }
        status = .disconnected
        errorBanner = "Se perdió la conexión con el servidor."
        for controller in privateControllers.values {
            controller.setConnected(false)
        }
    }
}
