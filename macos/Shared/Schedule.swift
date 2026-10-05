import Foundation

enum Schedule {
    /// The longest the widget goes without asking the server, to pick up
    /// changes made on the phone.
    static let maxWait: TimeInterval = 30 * 60

    /// When to fetch again: the next slot start or midnight in the user's
    /// zone, so slot tasks flip on time, or `maxWait` if that comes first.
    static func next(after now: Date, settings: DaySettings?) -> Date {
        let cap = now.addingTimeInterval(maxWait)
        guard let settings, let zone = TimeZone(identifier: settings.timezone) else { return cap }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let t = settings.slotTimes
        let today = cal.startOfDay(for: now)
        let tomorrow = cal.date(byAdding: .day, value: 1, to: today) ?? cap
        let starts = [t.morning, t.beforeLunch, t.afternoon, t.evening].compactMap { hhmm -> Date? in
            let parts = hhmm.split(separator: ":").compactMap { Int($0) }
            guard parts.count == 2 else { return nil }
            return cal.date(bySettingHour: parts[0], minute: parts[1], second: 0, of: now)
        }
        let boundary = (starts + [tomorrow]).filter { $0 > now }.min() ?? cap
        return min(boundary, cap)
    }
}
