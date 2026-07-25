import EventKit
import Foundation

struct CalendarEvent: Encodable {
    let id: String
    let title: String
    let start: String
    let end: String
    let calendar: String
    let location: String
    let allDay: Bool
}

struct Envelope: Encodable {
    let ok: Bool
    let source: String
    let events: [CalendarEvent]
    let authorization: String
    let error: String?
}

func emit(_ value: Envelope, exit: Int32 = 0) -> Never {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    FileHandle.standardOutput.write((try! encoder.encode(value)))
    FileHandle.standardOutput.write(Data("\n".utf8))
    Foundation.exit(exit)
}

let args = Array(CommandLine.arguments.dropFirst())
guard args.count == 2 else {
    emit(Envelope(ok: false, source: "macos-eventkit", events: [],
                  authorization: "unknown", error: "usage: <from-iso8601> <to-iso8601>"), exit: 64)
}

let iso = ISO8601DateFormatter()
let fractionalISO = ISO8601DateFormatter()
fractionalISO.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
func parseDate(_ value: String) -> Date? {
    fractionalISO.date(from: value) ?? iso.date(from: value)
}
guard let from = parseDate(args[0]), let to = parseDate(args[1]), from < to else {
    emit(Envelope(ok: false, source: "macos-eventkit", events: [],
                  authorization: "unknown", error: "invalid calendar window"), exit: 64)
}
guard to.timeIntervalSince(from) <= 93 * 24 * 60 * 60 else {
    emit(Envelope(ok: false, source: "macos-eventkit", events: [],
                  authorization: "unknown", error: "calendar window exceeds 93 days"), exit: 64)
}

let store = EKEventStore()
if #available(macOS 14.0, *) {
    if EKEventStore.authorizationStatus(for: .event) == .notDetermined {
        let semaphore = DispatchSemaphore(value: 0)
        var requestError: Error?
        store.requestFullAccessToEvents { _, error in requestError = error; semaphore.signal() }
        _ = semaphore.wait(timeout: .now() + 60)
        if let error = requestError {
            emit(Envelope(ok: false, source: "macos-eventkit", events: [],
                          authorization: "denied", error: error.localizedDescription), exit: 77)
        }
    }
}

let status = EKEventStore.authorizationStatus(for: .event)
let calendarAuthorized: Bool
if #available(macOS 14.0, *) {
    calendarAuthorized = status == .fullAccess
} else {
    calendarAuthorized = status == .authorized
}
guard calendarAuthorized else {
    emit(Envelope(ok: false, source: "macos-eventkit", events: [],
                  authorization: "denied",
                  error: "Calendar access is not granted. Enable it in System Settings > Privacy & Security > Calendars."), exit: 77)
}

let outputISO = ISO8601DateFormatter()
let events = store.events(matching: store.predicateForEvents(withStart: from, end: to, calendars: nil))
    .sorted { $0.startDate < $1.startDate }
    .map { event in
        CalendarEvent(id: event.eventIdentifier ?? event.calendarItemIdentifier,
                      title: event.title ?? "(untitled)",
                      start: outputISO.string(from: event.startDate),
                      end: outputISO.string(from: event.endDate),
                      calendar: event.calendar.title,
                      location: event.location ?? "",
                      allDay: event.isAllDay)
    }
emit(Envelope(ok: true, source: "macos-eventkit", events: events,
              authorization: "authorized", error: nil))
