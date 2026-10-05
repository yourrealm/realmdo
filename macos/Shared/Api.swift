import Foundation

/// One open task as `GET /api/tasks` returns it, cut to what the widget shows.
struct RemoteTask: Codable, Identifiable, Sendable, Equatable {
    let id: Int64
    let title: String
    let kind: String
    let urgency: Double
    let important: Bool
    /// Slot tasks: the slot a bare "done" would check.
    let pendingSlot: String?
}

/// `GET /api/settings`: the user's zone and when each slot starts, `HH:MM`.
struct DaySettings: Codable, Sendable {
    struct SlotTimes: Codable, Sendable {
        let morning: String
        let beforeLunch: String
        let afternoon: String
        let evening: String
    }

    let timezone: String
    let slotTimes: SlotTimes
}

struct ApiError: LocalizedError {
    let status: Int
    let message: String
    var errorDescription: String? { message }
}

/// The JSON API behind the gate, authenticated with a bearer token.
struct Api: Sendable {
    let pairing: Pairing

    /// The stored pairing's client; nil before pairing.
    static func load() -> Api? {
        Credentials.load().map(Api.init)
    }

    func me() async throws -> String {
        struct Me: Decodable { let username: String }
        return try await request(Me.self, "GET", "/api/me").username
    }

    /// Open tasks in the user's order.
    func tasks() async throws -> [RemoteTask] {
        try await request([RemoteTask].self, "GET", "/api/tasks")
    }

    func settings() async throws -> DaySettings {
        try await request(DaySettings.self, "GET", "/api/settings")
    }

    /// Marks a task done; a slot task checks its most pressing slot.
    func done(_ id: Int64) async throws {
        _ = try await raw("POST", "/api/tasks/\(id)/done", body: Data("{}".utf8))
    }

    private func request<T: Decodable>(_ type: T.Type, _ method: String, _ path: String) async throws -> T {
        let data = try await raw(method, path, body: nil)
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(type, from: data)
    }

    private func raw(_ method: String, _ path: String, body: Data?) async throws -> Data {
        guard let url = URL(string: pairing.baseUrl + path) else {
            throw ApiError(status: 0, message: "bad server URL")
        }
        var req = URLRequest(url: url, timeoutInterval: 10)
        req.httpMethod = method
        req.setValue("Bearer \(pairing.token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            req.httpBody = body
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status >= 400 {
            struct Failure: Decodable { let error: String }
            let message = (try? JSONDecoder().decode(Failure.self, from: data))?.error ?? "HTTP \(status)"
            throw ApiError(status: status, message: message)
        }
        return data
    }
}
