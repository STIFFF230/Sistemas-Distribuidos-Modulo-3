import Foundation

struct ChatMessageItem: Identifiable {
    let id = UUID()
    let sender: String
    let text: String
    let timestamp: String
    let sequence: Int
}

/// Extrae "HH:mm:ss" de un timestamp ISO-8601 con offset (p. ej.
/// "2026-08-05T10:45:00.303309-05:00") sin pasar por Date, ya que
/// ISO8601DateFormatter no soporta de forma confiable fracciones de segundo
/// con más de 3 dígitos como las que emite OffsetDateTime en el servidor.
func formatTime(_ isoTimestamp: String) -> String {
    guard let tIndex = isoTimestamp.firstIndex(of: "T") else { return "" }
    let afterT = isoTimestamp[isoTimestamp.index(after: tIndex)...]
    guard afterT.count >= 8 else { return String(afterT) }
    return String(afterT.prefix(8))
}
