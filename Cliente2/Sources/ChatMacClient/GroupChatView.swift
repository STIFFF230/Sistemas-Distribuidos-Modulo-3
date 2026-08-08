// Ventana principal: chat grupal + panel de usuarios conectados
// (CLAUDE-2.md sección 10). Permanece abierta durante toda la sesión.

import SwiftUI

struct GroupChatView: View {
    @ObservedObject var viewModel: ChatViewModel
    @State private var draft: String = ""
    @State private var selectedUser: String?

    var body: some View {
        VStack(spacing: 0) {
            Text("Usuario: \(viewModel.username)")
                .font(.headline)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()

            Divider()

            HStack(spacing: 0) {
                messageList
                Divider()
                userPanel
                    .frame(width: 200)
            }

            Divider()

            HStack {
                TextField("Escribe un mensaje…", text: $draft)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(send)
                    .disabled(viewModel.status != .connected)
                Button("Enviar", action: send)
                    .disabled(viewModel.status != .connected || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding()

            statusBar
        }
        .frame(minWidth: 720, minHeight: 480)
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 4) {
                    ForEach(viewModel.groupMessages) { message in
                        row(message).id(message.id)
                    }
                }
                .padding()
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .onChange(of: viewModel.groupMessages.count) { _ in
                if let last = viewModel.groupMessages.last {
                    proxy.scrollTo(last.id, anchor: .bottom)
                }
            }
        }
    }

    private func row(_ message: ChatMessageItem) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text("[\(formatTime(message.timestamp))]").foregroundStyle(.secondary)
            Text(message.sender).bold()
            Text(message.text)
        }
        .textSelection(.enabled)
    }

    private var userPanel: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Usuarios conectados")
                .font(.subheadline)
                .padding(.horizontal, 8)
                .padding(.top, 8)

            List(viewModel.connectedUsers, id: \.self, selection: $selectedUser) { user in
                Text(user)
                    .contentShape(Rectangle())
                    .onTapGesture(count: 2) { viewModel.openPrivateChat(with: user) }
                    .contextMenu {
                        Button("Chat privado") { viewModel.openPrivateChat(with: user) }
                    }
            }
            .listStyle(.plain)

            Button("Chat privado") {
                if let selected = selectedUser {
                    viewModel.openPrivateChat(with: selected)
                }
            }
            .disabled(selectedUser == nil)
            .padding(8)
        }
    }

    private var statusBar: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(viewModel.status == .connected ? Color.green : Color.red)
                .frame(width: 8, height: 8)
            Text(statusText).font(.caption)
            if let error = viewModel.errorBanner {
                Text(error).font(.caption).foregroundStyle(.red)
            }
            Spacer()
        }
        .padding(.horizontal)
        .padding(.bottom, 8)
    }

    private var statusText: String {
        switch viewModel.status {
        case .connected: return "Conectado"
        case .connecting: return "Conectando…"
        case .disconnected: return "Desconectado"
        }
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        viewModel.sendGroupMessage(text)
        draft = ""
    }
}
