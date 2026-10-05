import AppIntents
import SwiftUI
import WidgetKit

@main
struct RealmdoWidgets: WidgetBundle {
    var body: some Widget { TasksWidget() }
}

struct TasksWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "tasks", intent: TasksConfig.self, provider: TasksProvider()) { entry in
            TasksView(entry: entry)
        }
        .configurationDisplayName("Tasks")
        .description("What's up next, checked off in place.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

/// Nothing to configure; AppIntentConfiguration just gives an async provider.
struct TasksConfig: WidgetConfigurationIntent {
    static let title: LocalizedStringResource = "Tasks"
}

struct TasksEntry: TimelineEntry {
    enum State {
        case unpaired
        /// `stale`: the last good list, shown while the server is unreachable.
        case tasks([RemoteTask], stale: Bool)
        case failed(String)
    }

    let date: Date
    let state: State
}

struct TasksProvider: AppIntentTimelineProvider {
    private static let retry: TimeInterval = 15 * 60

    func placeholder(in context: Context) -> TasksEntry {
        TasksEntry(date: .now, state: .tasks(Self.sample, stale: false))
    }

    func snapshot(for configuration: TasksConfig, in context: Context) async -> TasksEntry {
        if context.isPreview && Api.load() == nil { return placeholder(in: context) }
        return await load().entry
    }

    func timeline(for configuration: TasksConfig, in context: Context) async -> Timeline<TasksEntry> {
        let (entry, next) = await load()
        return Timeline(entries: [entry], policy: .after(next))
    }

    private func load() async -> (entry: TasksEntry, next: Date) {
        let now = Date.now
        guard let api = Api.load() else {
            return (TasksEntry(date: now, state: .unpaired), now.addingTimeInterval(Schedule.maxWait))
        }
        async let settings = try? api.settings()
        do {
            let tasks = try await api.tasks()
            TaskCache.save(tasks)
            return (TasksEntry(date: now, state: .tasks(tasks, stale: false)), Schedule.next(after: now, settings: await settings))
        } catch let error as ApiError where error.status == 401 {
            return (TasksEntry(date: now, state: .failed("Token revoked. Open realmdo to pair again.")), now.addingTimeInterval(Self.retry))
        } catch {
            let state: TasksEntry.State = TaskCache.load().map { .tasks($0, stale: true) } ?? .failed(error.localizedDescription)
            return (TasksEntry(date: now, state: state), now.addingTimeInterval(Self.retry))
        }
    }

    private static let sample = [
        RemoteTask(id: 1, title: "Water the plants", kind: "interval", urgency: 1.2, important: true, pendingSlot: nil),
        RemoteTask(id: 2, title: "Stretch", kind: "slot", urgency: 0.6, important: false, pendingSlot: "morning"),
        RemoteTask(id: 3, title: "Call the dentist", kind: "once", urgency: 0.3, important: false, pendingSlot: nil),
    ]
}

/// The last good list, in the extension's own caches directory.
enum TaskCache {
    private static var file: URL? {
        FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first?.appendingPathComponent("tasks.json")
    }

    static func save(_ tasks: [RemoteTask]) {
        guard let file, let data = try? JSONEncoder().encode(tasks) else { return }
        try? data.write(to: file, options: .atomic)
    }

    static func load() -> [RemoteTask]? {
        guard let file, let data = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder().decode([RemoteTask].self, from: data)
    }
}

private let slotLabel = [
    "morning": "Morning",
    "before_lunch": "Before lunch",
    "afternoon": "Afternoon",
    "evening": "Evening",
]

struct TasksView: View {
    @Environment(\.widgetFamily) private var family
    let entry: TasksEntry

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .containerBackground(.background, for: .widget)
    }

    @ViewBuilder private var content: some View {
        switch entry.state {
        case .unpaired:
            notice("Open realmdo to pair this Mac.")
        case let .failed(message):
            notice(message)
        case let .tasks(all, stale):
            let visible = all.filter { $0.urgency >= 0 }
            if let top = visible.first {
                list(top: top, rest: Array(visible.dropFirst()), stale: stale)
            } else {
                notice(stale ? "Offline." : "All clear.")
            }
        }
    }

    /// Rows under the top card, by widget size.
    private var rowCount: Int {
        switch family {
        case .systemSmall: 0
        case .systemMedium: 2
        default: 7
        }
    }

    private func list(top: RemoteTask, rest: [RemoteTask], stale: Bool) -> some View {
        let shown = rest.prefix(rowCount)
        let more = rest.count - shown.count
        return VStack(alignment: .leading, spacing: 6) {
            if family == .systemSmall {
                smallCard(top)
            } else {
                card(top)
                ForEach(shown) { row($0) }
            }
            Spacer(minLength: 0)
            footer(more: more, stale: stale)
        }
    }

    private func upNext(_ t: RemoteTask) -> String {
        t.pendingSlot.flatMap { slotLabel[$0] }.map { "Up next · \($0)" } ?? "Up next"
    }

    private func card(_ t: RemoteTask) -> some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(upNext(t)).font(.caption.bold()).foregroundStyle(.tint)
                Text(t.title).font(.headline).lineLimit(2)
            }
            Spacer(minLength: 4)
            doneButton(t, size: 32)
        }
        .padding(10)
        .background(.tint.opacity(0.12), in: .rect(cornerRadius: 12))
    }

    private func smallCard(_ t: RemoteTask) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(upNext(t)).font(.caption.bold()).foregroundStyle(.tint)
            Text(t.title).font(.headline).lineLimit(3)
            Spacer(minLength: 0)
            HStack {
                Spacer()
                doneButton(t, size: 36)
            }
        }
    }

    private func row(_ t: RemoteTask) -> some View {
        HStack(spacing: 8) {
            Button(intent: DoneTask(id: t.id)) {
                Image(systemName: "circle").foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Mark \(t.title) done")
            Text(t.title).font(.subheadline).lineLimit(1)
            if t.important {
                Circle().fill(.tint).frame(width: 6, height: 6)
            }
        }
        .padding(.horizontal, 10)
    }

    private func doneButton(_ t: RemoteTask, size: CGFloat) -> some View {
        Button(intent: DoneTask(id: t.id)) {
            Image(systemName: "checkmark")
                .font(.system(size: size * 0.45, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .background(.tint, in: .circle)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Mark \(t.title) done")
    }

    @ViewBuilder private func footer(more: Int, stale: Bool) -> some View {
        let parts = [more > 0 ? "+\(more) more" : nil, stale ? "Offline" : nil].compactMap { $0 }
        if !parts.isEmpty {
            Text(parts.joined(separator: " · ")).font(.caption2).foregroundStyle(.secondary)
                .padding(.horizontal, family == .systemSmall ? 0 : 10)
        }
    }

    private func notice(_ text: String) -> some View {
        Text(text).font(.subheadline).foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .multilineTextAlignment(.center)
    }
}
