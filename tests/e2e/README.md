# Installed-stack Android E2E

Runs a fresh debug install against the already-running Mac daemon and deployed relay. The test uses Compose/Espresso semantics only; it has no coordinate taps or swipes.

## Run

```sh
npm run e2e:installed -- \
  --serial emulator-5590 \
  --allow-destructive-session \
  --isolate-host-auth \
  --hooks voice,push
```

The harness creates, seeds, and removes its own host-owned E2E session. It never selects or mutates an existing Pi session. Optional overrides:

- `PI_E2E_DATA_DIR`
- `PI_E2E_ARTIFACTS`
- `PI_E2E_KNOWN_CONTENT`
- `PI_E2E_PROMPT`
- `PI_E2E_EXPECTED_REPLY`
- `PI_E2E_TERMINAL_CANARY`
- `PI_E2E_JAVA_HOME`
- `PI_E2E_ISOLATE_HOST_AUTH=1` (or `--isolate-host-auth`)
- `PI_E2E_ALLOW_DESTRUCTIVE_SESSION=1` (or `--allow-destructive-session`)
- `PI_E2E_HOOKS=voice,push` (or `--hooks voice,push`)

Host authentication isolation is explicit opt-in. When enabled, the harness serializes the emulator run, stops the daemon, writes private mode-0600 backups outside artifacts, clears only owner credentials/owner identity/devices while retaining the Mac instance and relay registration, restarts the freshly built host dist, and permits debug passkey registration. Teardown stops instrumentation and the daemon, restores host and route state atomically on every path, restarts, and verifies original owner/device counts plus relay readiness. Without the opt-in, production host authentication state is never mutated.

The harness seeds its owned session with `PONG`, submits `Reply with exactly PI_E2E_FINAL`, and requires a new finalized assistant message containing `PI_E2E_FINAL` after the pre-submit cursor.

## Scenario

1. Verify the allowlisted emulator-console identity, require explicit destructive opt-in, then build debug, androidTest, and unsigned release APKs.
2. Prove the debug bridge and installed-stack tests are absent from release dex.
3. Verify the daemon, deployed relay, and terminal backend; capture the original session-ID set and create one fresh UUIDv4 host-owned E2E semantic session through the mode-0600 admin socket. The daemon returns an unguessable delete capability; `--session-id` and `PI_E2E_SESSION_ID` are rejected. Seed `PONG` through real `prompt` then `get_state` dispatches and wait for its canonical record before any host-auth restart.
4. Uninstall/reset only the `.debug` app and its test package, install both debug APKs, then capture the paired-device baseline. Issue one real relay invitation through the daemon's mode-0600 admin socket. The production release package and data are never touched.
5. Put the invitation and in-memory canaries in a random mode-0700 `/data/local/tmp` directory. Instrumentation requires the matching random run token, then atomically moves, reads, and removes the mode-0600 payload. The invitation is never an adb argument, environment value, log, or artifact. Ordinary aggregate instrumentation runs omit this capability and skip the installed-stack test before activity launch.
6. Pair through the deployed relay, confirm through the admin socket, unlock through the existing debug passkey implementation, and require the original IDs plus the one owned ID with completed canonical sync.
7. Open the configured session through Compose semantics, assert `PONG`, submit the deterministic prompt, and require a new finalized reply. During that wait, fail immediately with a stable code for a canonical sequence gap, command rejection/indeterminate/send failure, or authentication/connection loss; only the timeout retains the configured 180-second bound.
8. Open terminal through the non-exported debug bridge, require the WebView runtime canary, paste a canary through the production terminal bridge, and read it back through bounded server terminal history.
9. Open Agents through Compose semantics and require online rendered state.
10. Pull only safe semantic-title screenshots. Teardown revokes only IDs absent from the successfully captured paired-device baseline; before that capture it does not query or revoke devices. It restores/restarts isolated host authentication state when selected, then deletes the owned session with its capability. Deletion stops its actor, transactionally removes canonical records/session state, deletes only matching journal rows, removes only the validated `sessions/<uuid>` directory, and verifies the original session-ID set is restored. Any teardown failure fails the run.

Selected `voice` and `push` hooks are mandatory deterministic debug-only gates. Unselected hooks are explicit skips. `external-push` remains a separate fail-closed gate requiring a real UnifiedPush distributor; deterministic push coverage is not physical-device/Doze evidence.

## Evidence

Each run writes `artifacts/e2e/<timestamp>/`:

- `results.xml`
- `run.json`
- `screenshots/pairing-title.png`
- `screenshots/agents-title.png`
- `hashes.sha256`

Evidence contains stable result codes, counts, versions, hashes, and per-hook `passed`/`failed`/`not-run`/`not-selected` accounting only. It excludes invitations, device keys, prompts, replies, and terminal/transcript content.
