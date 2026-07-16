-- calendar/list-events provider (macOS). Invoked via `osascript` from
-- bin/kotoba-shell-host-macos. Returns a JSON array of
-- {calendar, summary, start} for events in the next 30 days across every
-- calendar, on stdout. Bounded to 30 days (not "every event ever") so this
-- stays fast on calendars with years of history. Requires the user to grant
-- Calendar access to the calling process on first use (standard macOS TCC
-- prompt).

on escapeJSON(txt)
	set AppleScript's text item delimiters to "\\"
	set txt to text items of txt
	set AppleScript's text item delimiters to "\\\\"
	set txt to txt as text
	set AppleScript's text item delimiters to "\""
	set txt to text items of txt
	set AppleScript's text item delimiters to "\\\""
	set txt to txt as text
	set AppleScript's text item delimiters to ""
	return txt
end escapeJSON

set startDate to current date
set endDate to startDate + (30 * days)

tell application "Calendar"
	set jsonParts to {}
	repeat with c in calendars
		set eventList to (every event of c whose start date ≥ startDate and start date ≤ endDate)
		repeat with e in eventList
			set evtSummary to ""
			try
				set evtSummary to summary of e
			end try
			set evtStart to ""
			try
				set evtStart to (start date of e) as string
			end try
			set evtCalendar to name of c
			set end of jsonParts to "{\"calendar\":\"" & my escapeJSON(evtCalendar) & "\",\"summary\":\"" & my escapeJSON(evtSummary) & "\",\"start\":\"" & my escapeJSON(evtStart) & "\"}"
		end repeat
	end repeat
	set AppleScript's text item delimiters to ","
	set jsonBody to jsonParts as text
	set AppleScript's text item delimiters to ""
	return "[" & jsonBody & "]"
end tell
