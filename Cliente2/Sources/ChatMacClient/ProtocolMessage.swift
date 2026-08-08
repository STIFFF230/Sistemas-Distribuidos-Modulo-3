// Codificación/decodificación del protocolo JSON+\n. Ver PROTOCOL.md.

import Foundation

enum ProtocolLimits {
    static let maxMessageBytes = 8192
}

enum ProtocolType {
    static let register = "REGISTER"
    static let registerOk = "REGISTER_OK"
    static let registerError = "REGISTER_ERROR"
    static let groupMessage = "GROUP_MESSAGE"
    static let privateMessage = "PRIVATE_MESSAGE"
    static let userList = "USER_LIST"
    static let userJoined = "USER_JOINED"
    static let userLeft = "USER_LEFT"
    static let disconnect = "DISCONNECT"
    static let error = "ERROR"
}

// ---------- Mensajes salientes ----------

struct RegisterMessage: Encodable {
    let type = ProtocolType.register
    let username: String
}

struct GroupMessageOut: Encodable {
    let type = ProtocolType.groupMessage
    let text: String
}

struct PrivateMessageOut: Encodable {
    let type = ProtocolType.privateMessage
    let to: String
    let text: String
}

struct DisconnectMessage: Encodable {
    let type = ProtocolType.disconnect
}

// ---------- Mensajes entrantes ----------
// Un único sobre con todos los campos opcionales: cada tipo de mensaje del
// servidor usa un subconjunto distinto de estos campos (ver PROTOCOL.md).

struct IncomingEnvelope: Decodable {
    let type: String
    let username: String?
    let message: String?
    let text: String?
    let from: String?
    let to: String?
    let timestamp: String?
    let sequence: Int?
    let users: [String]?
}
