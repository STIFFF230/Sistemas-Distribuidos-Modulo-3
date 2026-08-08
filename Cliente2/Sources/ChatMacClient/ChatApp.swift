import SwiftUI
import AppKit

@main
struct ChatApp: App {
    @StateObject private var viewModel = ChatViewModel()
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView(viewModel: viewModel)
                .onAppear {
                    appDelegate.viewModel = viewModel
                    NSApp.activate(ignoringOtherApps: true)
                }
        }
        .windowResizability(.contentSize)
    }
}

/// Envía DISCONNECT y cierra el socket ordenadamente al salir de la app
/// (CLAUDE-2.md sección 10: "Cerrar correctamente socket e hilos al salir").
///
/// También fuerza que la app tome el foco de teclado al arrancar: lanzada
/// con `swift run` (sin empaquetar como .app) macOS no le da foco
/// automáticamente y el usuario termina escribiendo en la terminal.
final class AppDelegate: NSObject, NSApplicationDelegate {
    weak var viewModel: ChatViewModel?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        NSApp.windows.first?.makeKeyAndOrderFront(nil)
    }

    func applicationWillTerminate(_ notification: Notification) {
        viewModel?.disconnect()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }
}
