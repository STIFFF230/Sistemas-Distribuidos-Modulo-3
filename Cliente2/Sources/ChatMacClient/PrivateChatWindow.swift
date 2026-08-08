// Ventana no modal para el chat privado con un usuario (CLAUDE-2.md sección 9).
// Se crea una sola vez por par de usuarios y se reutiliza/enfoca en lugar de
// duplicarse. Cerrarla solo la retira del diccionario del ViewModel: no cierra
// la sesión ni la ventana grupal.

import AppKit
import SwiftUI

@MainActor
final class PrivateChatViewModel: ObservableObject {
    let peerUsername: String
    @Published var messages: [ChatMessageItem] = []
    @Published var isConnected: Bool
    var onSend: ((String) -> Void)?

    init(peerUsername: String, isConnected: Bool) {
        self.peerUsername = peerUsername
        self.isConnected = isConnected
    }

    func appendMessage(sender: String, text: String, timestamp: String) {
        messages.append(ChatMessageItem(sender: sender, text: text, timestamp: timestamp, sequence: 0))
    }

    func send(_ text: String) {
        onSend?(text)
    }
}

@MainActor
final class PrivateChatWindowController: NSObject, NSWindowDelegate {
    private let window: NSWindow
    private let viewModel: PrivateChatViewModel
    private let onClose: () -> Void

    init(peerUsername: String, initiallyConnected: Bool, onSend: @escaping (String) -> Void, onClose: @escaping () -> Void) {
        let viewModel = PrivateChatViewModel(peerUsername: peerUsername, isConnected: initiallyConnected)
        viewModel.onSend = onSend
        self.viewModel = viewModel
        self.onClose = onClose

        let hosting = NSHostingController(rootView: PrivateChatView(viewModel: viewModel))
        let window = NSWindow(contentViewController: hosting)
        window.title = "Chat privado — \(peerUsername)"
        window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
        window.setContentSize(NSSize(width: 420, height: 360))
        window.isReleasedWhenClosed = false
        self.window = window
        super.init()
        window.delegate = self
    }

    func showAndFocus() {
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    func appendMessage(sender: String, text: String, timestamp: String) {
        viewModel.appendMessage(sender: sender, text: text, timestamp: timestamp)
    }

    func setConnected(_ connected: Bool) {
        viewModel.isConnected = connected
    }

    func windowWillClose(_ notification: Notification) {
        onClose()
    }
}

struct PrivateChatView: View {
    @ObservedObject var viewModel: PrivateChatViewModel
    @State private var draft: String = ""

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 6) {
                Circle()
                    .fill(viewModel.isConnected ? Color.green : Color.red)
                    .frame(width: 8, height: 8)
                Text(viewModel.isConnected ? "Conectado" : "\(viewModel.peerUsername) está desconectado")
                    .font(.caption)
                Spacer()
            }
            .padding(8)

            Divider()

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        ForEach(viewModel.messages) { message in
                            row(message).id(message.id)
                        }
                    }
                    .padding(8)
                }
                .onChange(of: viewModel.messages.count) { _ in
                    if let last = viewModel.messages.last {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }

            Divider()

            HStack {
                TextField("Mensaje…", text: $draft)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(send)
                    .disabled(!viewModel.isConnected)
                Button("Enviar", action: send)
                    .disabled(!viewModel.isConnected || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(8)
        }
        .frame(minWidth: 380, minHeight: 320)
    }

    private func row(_ message: ChatMessageItem) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text("[\(formatTime(message.timestamp))]").foregroundStyle(.secondary)
            Text(message.sender).bold()
            Text(message.text)
        }
        .textSelection(.enabled)
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        viewModel.send(text)
        draft = ""
    }
}
