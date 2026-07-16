-- contacts/list provider (macOS). Invoked via `osascript` from
-- bin/kotoba-shell-host-macos. Returns a JSON array of {name, email, phone}
-- on stdout. Requires the user to grant Contacts access to the calling
-- process (Terminal/whatever invokes kotoba-shell) on first use — this is
-- the standard macOS TCC prompt, not something this script can bypass or
-- should try to.

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

tell application "Contacts"
	set jsonParts to {}
	repeat with p in people
		set personName to ""
		try
			set personName to name of p
		end try
		set emailAddr to ""
		try
			set emailAddr to value of first email of p
		end try
		set phoneNum to ""
		try
			set phoneNum to value of first phone of p
		end try
		set end of jsonParts to "{\"name\":\"" & my escapeJSON(personName) & "\",\"email\":\"" & my escapeJSON(emailAddr) & "\",\"phone\":\"" & my escapeJSON(phoneNum) & "\"}"
	end repeat
	set AppleScript's text item delimiters to ","
	set jsonBody to jsonParts as text
	set AppleScript's text item delimiters to ""
	return "[" & jsonBody & "]"
end tell
