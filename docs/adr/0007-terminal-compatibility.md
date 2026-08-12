# ADR-0007: xterm + node-pty + private tmux split input

Status: Accepted
Date: 2026-08-09

## Context

RPC cannot render arbitrary `ctx.ui.custom()`. Termux terminal classes own a local JNI PTY and lack required Kitty release encoding. tmux consumes Pi’s Kitty negotiation and mangles exact release sequences on a naive path.

## Decision

Bundle `@xterm/xterm@6.1.0-beta.292` locally (`sha512-17zqK5tM/l6qeD7McF42OrEJ6w3XqJ2vFVKdWqu0cYLzdFqMWAHp2oFNc8Fj5DmqDSl1E1FZEg6IFflDllTvLA==`; packed SHA-256 `66cd04723b96a17ce85027f3f9480d4398a134db1f5cd359784a01dbc2c05510`) through a reproducible build targeting Chromium 91 in a hardened Android WebView. CSP keeps `script-src` and external stylesheet sources at `'self'`. xterm must position its textarea, canvas, IME, and dynamic dimensions with runtime style attributes, so `style-src-attr 'unsafe-inline'` is enabled. Chromium 91 classifies those property writes under `style-src-elem`, requiring its matching `'unsafe-inline'` compatibility allowance; this is the smallest policy that works on the supported floor. Nonces/hashes cannot authorize value-dependent attributes. No untrusted content is inserted as HTML, CSS cannot fetch off-origin resources, and a startup computed-style canary fails closed before xterm starts unless property assignment, `setAttribute("style", ...)`, and injected `<style>` each apply in computed style. OSC 8 sequences are consumed without link activation or navigation. The API 29 AVD currently supplies WebView 91.0.4472.114: it has `WeakRef`/required font and canvas behavior but lacks `structuredClone`, so a project-owned clone shim is loaded before xterm and source-locked to xterm's plain mode objects. At startup a JS/native canary verifies engine version, shim, required globals, write/render, Unicode width, resize, and input. Failure disables terminal mode with WebView-update guidance; it never loads a CDN or pretends semantic mode is compatible. On Mac, Node 22 and `node-pty@1.2.0-beta.15` (`sha512-vORSzHXi4Ofl7HemVWpuudLqCPdaQb4LfpRCUpE5HPxhp4JYscl8zZwxh11p26v2wvW24WMwnMfLjhRLixrfxA==`) own a display client to a private tmux server; a persistent tmux control client injects parsed Kitty/CSI-u bytes directly into the pane with `send-keys -H`. Text, paste, mouse, replies, output, and resize use the display PTY. tmux supplies visible-pane reconnect redraw. Connected xterm keeps 5,000 lines; reconnect does not restore them. A separate bounded read-only `capture-pane` drawer exposes server history without a full-scrollback claim.

## Rejected

- Termux fork: large maintained remote-session/Kitty refactor.
- Semantic-only 1.0: fails current custom-TUI compatibility.
- Send every key through tmux display client: loses modified/release fidelity.
- CDN xterm: supply-chain/offline/security risk.

## Consequences

These beta pins are accepted because the tested xterm Kitty path and executable node-pty helpers are required; upgrades need source-locator, bundle-hash, API 29/34/36 WebView canary, and full terminal fixtures. CI builds the asset twice and compares hashes. Licenses, WebView isolation, writer lease, fresh-generation redraw, IME/hardware-key tests, renderer-kill recovery, and package-signing smoke are mandatory. tmux images use native companion cards rather than parity claims.
