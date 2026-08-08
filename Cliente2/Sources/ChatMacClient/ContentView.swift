// Flujo inicial: pide solo el nombre de usuario, conecta+registra, y luego
// muestra la ventana grupal (CLAUDE-2.md sección 10).
//
// Servidor y puerto no se preguntan por pantalla: se toman de las variables
// de entorno CHAT_HOST/CHAT_PORT si están definidas (para apuntar a otra
// máquina, p. ej. la VM Debian, sin tocar el código — ver CLAUDE-2.md
// sección 16), o si no de ServerDefaults, que coincide con el servidor de
// pruebas local.

import SwiftUI
import Foundation

enum ServerDefaults {
    static var host: String {
        ProcessInfo.processInfo.environment["CHAT_HOST"] ?? "127.0.0.1"
    }
    static var port: UInt16 {
        ProcessInfo.processInfo.environment["CHAT_PORT"].flatMap(UInt16.init) ?? 1802
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
