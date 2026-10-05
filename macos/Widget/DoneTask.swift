import AppIntents

/// The widget's check button. WidgetKit reloads the timeline after it runs.
struct DoneTask: AppIntent {
    static let title: LocalizedStringResource = "Mark done"
    static let isDiscoverable = false

    @Parameter(title: "Task")
    var id: Int

    init() {}

    init(id: Int64) {
        self.id = Int(id)
    }

    func perform() async throws -> some IntentResult {
        try await Api.load()?.done(Int64(id))
        return .result()
    }
}
