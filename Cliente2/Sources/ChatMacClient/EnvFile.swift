// Carga variables desde un archivo .env (formato KEY=VALOR, una por línea,
// líneas que empiezan con # se ignoran) para no fijar la IP del servidor
// en el código fuente (ver CLAUDE-2.md sección 16).
//
// Busca el archivo empezando en el directorio de trabajo actual y subiendo
// hasta la raíz del repo, así funciona tanto con un .env dentro de Cliente2/
// como con uno compartido en la raíz del proyecto (Modulo3/.env).

import Foundation

enum EnvFile {
    static func load(filename: String = ".env") -> [String: String] {
        guard let path = findEnvFile(named: filename) else { return [:] }
        guard let contents = try? String(contentsOfFile: path, encoding: .utf8) else { return [:] }

        var result: [String: String] = [:]
        for rawLine in contents.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty, !line.hasPrefix("#") else { continue }
            guard let eqIndex = line.firstIndex(of: "=") else { continue }

            let key = String(line[line.startIndex..<eqIndex]).trimmingCharacters(in: .whitespaces)
            var value = String(line[line.index(after: eqIndex)...]).trimmingCharacters(in: .whitespaces)
            if value.count >= 2, value.hasPrefix("\""), value.hasSuffix("\"") {
                value = String(value.dropFirst().dropLast())
            }
            guard !key.isEmpty else { continue }
            result[key] = value
        }
        return result
    }

    private static func findEnvFile(named filename: String) -> String? {
        var dir = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        for _ in 0..<6 {
            let candidate = dir.appendingPathComponent(filename)
            if FileManager.default.fileExists(atPath: candidate.path) {
                return candidate.path
            }
            let parent = dir.deletingLastPathComponent()
            if parent.path == dir.path { break }
            dir = parent
        }
        return nil
    }
}
