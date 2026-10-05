import Foundation

/// The server URL and token, as copied from the settings page.
struct Pairing: Codable, Sendable, Equatable {
    /// Origin of the app, no trailing slash.
    let baseUrl: String
    /// A `rdo_...` token minted on the settings page.
    let token: String

    /// Trims what was pasted; nil unless it looks like a URL and a token.
    static func from(url: String, token: String) -> Pairing? {
        var url = url.trimmingCharacters(in: .whitespacesAndNewlines)
        let token = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard url.hasPrefix("http"), token.hasPrefix("rdo_") else { return nil }
        while url.hasSuffix("/") { url.removeLast() }
        return Pairing(baseUrl: url, token: token)
    }
}

/// The pairing in a keychain item that the app and the widget share.
enum Credentials {
    private static var base: [String: Any] { [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "me.malm.realmdo",
        kSecAttrAccount as String: "pairing",
        kSecUseDataProtectionKeychain as String: true,
    ] }

    static func load() -> Pairing? {
        var query = base
        query[kSecReturnData as String] = true
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return try? JSONDecoder().decode(Pairing.self, from: data)
    }

    /// No access group: the item lands in the first one both targets list.
    static func save(_ pairing: Pairing) throws {
        clear()
        var item = base
        item[kSecValueData as String] = try JSONEncoder().encode(pairing)
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw ApiError(status: 0, message: "keychain error \(status)")
        }
    }

    static func clear() {
        SecItemDelete(base as CFDictionary)
    }
}
