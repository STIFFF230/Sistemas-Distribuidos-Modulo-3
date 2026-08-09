// Flujo inicial: pide solo el nombre de usuario, conecta+registra, y luego
// muestra la ventana grupal (CLAUDE-2.md sección 10).
//
// Servidor y puerto no se preguntan por pantalla ni se fijan en el código
// (CLAUDE-2.md sección 16). Se toman, en este orden:
//   1. Variables de entorno CHAT_HOST/CHAT_PORT si están definidas.
//   2. Archivo .env (ver EnvFile.swift) — busca Cliente2/.env o, si no
//      existe, sube hasta Modulo3/.env. Edita ese archivo cuando cambie la
//      IP de la VM Debian, sin tocar código ni recompilar variables de
//      entorno cada vez.
//   3. localhost, como último recurso para pruebas en la misma máquina.

import SwiftUI
import Foundation

enum ServerDefaults {
    private static let envFile: [String: String] = EnvFile.load()

    static var host: String {
        ProcessInfo.processInfo.environment["CHAT_HOST"]
            ?? envFile["CHAT_HOST"]
            ?? "127.0.0.1"
    }
    static var port: UInt16 {
        let raw = ProcessInfo.processInfo.environment["CHAT_PORT"] ?? envFile["CHAT_PORT"]
        return raw.flatMap(UInt16.init) ?? 5000
    }
}

struct ContentView: View {
    @ObservedObject var viewModel: ChatViewModel
    @State private var username = ""
    @State private var isConnecting = false

    var body: some View {
        Group {
            if viewModel.status == .connected {
                GroupChatView(viewModel: viewModel)
            } else {
                connectionForm
            }
        }
    }

    private var connectionForm: some View {
        VStack(spacing: 12) {
            Text("Chat distribuido")
                .font(.title2)
                .bold()

            TextField("Nombre de usuario", text: $username)
                .textFieldStyle(.roundedBorder)
                .onSubmit(connect)
                .disabled(isConnecting)

            if let error = viewModel.errorBanner {
                Text(error)
                    .foregroundStyle(.red)
                    .font(.caption)
                    .multilineTextAlignment(.center)
            }

            Button(isConnecting ? "Conectando…" : "Conectar") {
                connect()
            }
            .disabled(isConnecting || username.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .padding(32)
        .frame(width: 320)
    }

    private func connect() {
        let trimmedUsername = username.trimmingCharacters(in: .whitespaces)
        guard !trimmedUsername.isEmpty else { return }
        isConnecting = true
        Task {
            await viewModel.connectAndRegister(
                host: ServerDefaults.host, port: ServerDefaults.port, username: trimmedUsername)
            isConnecting = false
        }
    }
}
