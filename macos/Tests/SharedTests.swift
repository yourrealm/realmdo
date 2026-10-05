import Foundation
import Testing
@testable import realmdo

@Test func trimsPastedUrlAndToken() {
    let p = Pairing.from(url: " https://realmdo.example.org/ \n", token: "rdo_abc\n")
    #expect(p == Pairing(baseUrl: "https://realmdo.example.org", token: "rdo_abc"))
}

@Test func rejectsWhatIsNotAUrlAndToken() {
    #expect(Pairing.from(url: "realmdo.example.org", token: "rdo_abc") == nil)
    #expect(Pairing.from(url: "https://x", token: "abc") == nil)
    #expect(Pairing.from(url: "", token: "") == nil)
}

private func settings(_ zone: String) -> DaySettings {
    DaySettings(
        timezone: zone,
        slotTimes: .init(morning: "07:00", beforeLunch: "11:00", afternoon: "14:00", evening: "18:00")
    )
}

/// 2026-10-05 at hh:mm in Stockholm (UTC+2 in October).
private func stockholm(_ hour: Int, _ minute: Int) -> Date {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = TimeZone(identifier: "Europe/Stockholm")!
    return cal.date(from: DateComponents(year: 2026, month: 10, day: 5, hour: hour, minute: minute))!
}

@Test func refreshesAtTheNextSlotStart() {
    let next = Schedule.next(after: stockholm(10, 50), settings: settings("Europe/Stockholm"))
    #expect(next == stockholm(11, 0))
}

@Test func refreshesAtMidnightAfterTheLastSlot() {
    let next = Schedule.next(after: stockholm(23, 45), settings: settings("Europe/Stockholm"))
    #expect(next == stockholm(23, 45).addingTimeInterval(15 * 60))
}

@Test func waitsAtMostMaxWait() {
    let now = stockholm(8, 0)
    #expect(Schedule.next(after: now, settings: settings("Europe/Stockholm")) == now.addingTimeInterval(Schedule.maxWait))
    #expect(Schedule.next(after: now, settings: nil) == now.addingTimeInterval(Schedule.maxWait))
}
