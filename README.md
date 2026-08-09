# Pi Mobile

Native Android access to the Pi coding agent running on your Mac.

> Status: research and architecture validation. Implementation follows the staged plan in `docs/`.

## Product goals

- Continue Mac-hosted Pi sessions from Android without moving provider credentials to the phone.
- Stream messages, thinking, tools, diffs, questions, commands, and extension events.
- Pair securely, unlock with a Bitwarden-compatible passkey, reconnect after network loss, and notify on completion.
- Offer near-real-time voice input using Groq `whisper-large-v3-turbo`, with the key retained on the Mac.
- Deliver a polished phone, tablet, and foldable Compose UI with strong accessibility and performance.

## Security posture

No secrets belong in Git. Pi and Groq credentials stay on the Mac. Any optional relay must route encrypted payloads without access to session content.

## Documentation

- [Requirements](docs/requirements.md)
- Research, architecture, protocol, operations, testing, and UX documents are added and kept current as decisions are validated.

## Repository

Private GitHub repository: <https://github.com/VeryBigSad/pi-app>
