import SwiftUI
import WidgetKit

/// The widget's host: it pairs this Mac and nothing else.
@main
struct RealmdoApp: App {
    var body: some Scene {
        Window("realmdo", id: "main") {
            PairingView()
                .frame(width: 420)
                .fixedSize()
        }
        .windowResizability(.contentSize)
    }
}

struct PairingView: View {
    @State private var paired = Credentials.load()
    @State private var username: String?
    @State private var url = ""
    @State private var token = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let paired {
                Text("Paired").font(.title2.bold())
                Text(username.map { "Signed in as \($0) at \(paired.baseUrl)." } ?? paired.baseUrl)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                Text("Add the Tasks widget from the desktop or Notification Center.")
                Button("Unpair", role: .destructive) {
                    Credentials.clear()
                    self.paired = nil
                    username = nil
                    WidgetCenter.shared.reloadAllTimelines()
                }
            } else {
                Text("Pair this Mac").font(.title2.bold())
                Text("On realmdo's settings page, create a token. Paste it here with the server URL.")
                    .foregroundStyle(.secondary)
                TextField("Server URL", text: $url)
                    .textFieldStyle(.roundedBorder)
                SecureField("Token", text: $token)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { Task { await pair() } }
                Button("Pair") { Task { await pair() } }
                    .keyboardShortcut(.defaultAction)
                    .disabled(busy || url.isEmpty || token.isEmpty)
            }
            if let error {
                Text(error).foregroundStyle(.red)
            }
        }
        .padding(20)
        .task(id: paired) {
            guard let paired else { return }
            username = try? await Api(pairing: paired).me()
        }
    }

    /// Checks the token against the server before keeping it.
    private func pair() async {
        error = nil
        guard let pairing = Pairing.from(url: url, token: token) else {
            error = "The URL starts with http and the token with rdo_."
            return
        }
        busy = true
        defer { busy = false }
        do {
            username = try await Api(pairing: pairing).me()
            try Credentials.save(pairing)
            paired = pairing
            url = ""
            token = ""
            WidgetCenter.shared.reloadAllTimelines()
        } catch {
            self.error = error.localizedDescription
        }
    }
}
