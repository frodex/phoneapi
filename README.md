# PhoneAPI

PhoneAPI is an Android app that lets a paired client drive the device and its Chrome tabs. The HTTP and WebSocket API, plus an on-device [MCP](https://modelcontextprotocol.io) server, listen on an abstract local socket named after the package while the listener is running. A computer reaches that socket with `adb forward` (USB or Wireless Debugging).

The shell helper reads the UI through UiAutomation, injects input, captures the screen, and runs privileged app commands. It is started over USB, or on Android 11 or newer over Wireless Debugging. Chrome DevTools uses the same split. On Android 11 and newer adbd opens Chrome's socket, because the helper is not allowed to. On Android 10 the helper connects to an adb reverse socket, and the computer forwards that to Chrome. Video and audio are encoded on the device and played in a browser.

Android 10 and newer (minSdk 29). Wireless Debugging and helper restart without a USB cable need Android 11.

- HTTP, WebSocket, and MCP surface: [docs/api.md](docs/api.md) (includes OpenAPI)
- How the pieces fit together: [docs/architecture.md](docs/architecture.md)
- Building from source: [docs/development.md](docs/development.md)

## Requirements

- A phone or emulator with USB debugging (and Wireless Debugging on Android 11+ if you want to drop the cable later)
- On the computer, `adb`, `jq`, and `curl` on your `PATH`. `phoneapi` (pair, server, helper, and papi) shells out to these. Most machines already have `curl`; `adb` and `jq` usually need a separate install — for example `brew install android-platform-tools jq` on macOS, or [platform-tools](https://developer.android.com/tools/releases/platform-tools) from Google plus your OS package for `jq`
- An [MCP](https://modelcontextprotocol.io) client (for example Cursor or Claude Code) if you want an agent to drive the phone

## Install

1. Download a release APK from the project's GitHub Releases page (`phoneapi-v*.apk`).
2. Copy [`scripts/phoneapi`](scripts/phoneapi) somewhere on your `PATH` (for example `~/bin/phoneapi`). It does not need the rest of the repo.
3. Install the APK. Optionally grant `WRITE_SECURE_SETTINGS` so the app can turn Wireless Debugging on itself (handy after reboot; without it you enable that toggle in Developer options):

```sh
adb install -r -g phoneapi-vX.Y.Z.apk
adb shell pm grant net.die.phoneapi android.permission.WRITE_SECURE_SETTINGS
```

4. Pair, expose the server, and start the helper:

```sh
phoneapi pair --name laptop
phoneapi server
phoneapi helper
```

`phoneapi` targets the release package (`net.die.phoneapi`) and stores pairing state in `~/.config/phoneapi/pairing.json` (or `$XDG_CONFIG_HOME/phoneapi`). Override the package with `PHONEAPI_PKG`, the directory that holds the pairing and session files with `PHONEAPI_STATE`, or the pairing file alone with `PAPI_PAIRING`. `pair` writes the pairing file and does not print the token. `server` forwards the on-device API to localhost; run it again if the tunnel drops.

Open PhoneAPI for screen reading and taps, wireless debugging, and the computers that have access. That switch starts the helper and shuts it down. On Android 11 and newer the same screen can turn Wireless Debugging on and pair it. You can remove a computer's access there at any time.

## Pairing

A paired computer holds its own bearer token. Mint one with `phoneapi pair`. The shell user is already trusted, so no approval on the phone is needed:

```sh
phoneapi pair --name laptop
phoneapi server
```

The saved JSON looks like this (`scheme` `http`, host `127.0.0.1`):

```json
{"scheme":"http","host":"127.0.0.1","port":41234,"token":"pa_…","name":"laptop"}
```

Every request needs `Authorization: Bearer <token>`. Scopes, the `access_token` query exception, token admin, and error shapes are documented in [docs/api.md](docs/api.md).

## Helper

The helper is a separate `app_process` that registers a binder with the app. `/v1/device` reports it as `running`, `starting`, `needs_pairing`, `needs_usb`, or `stopped`. `capabilities` shrinks to what works in the current state.

| Status | Meaning |
| --- | --- |
| `running` | The helper is connected. |
| `starting` | A launch is in progress. |
| `needs_pairing` | Android 11+ and no paired Wireless Debugging key yet. |
| `needs_usb` | Android 10. Relaunch from a computer. |
| `stopped` | The automatic restart gave up after repeated failures. |

Launch it once from the computer:

```sh
phoneapi helper
```

`phoneapi helper <package>` starts it for another install, such as a debug build. The process detaches, so the command returns while the helper keeps running. That USB start does not survive a reboot; run it again after reboot, or start the listener and helper over Wireless Debugging. On Android 11+, after pairing Wireless Debugging once, the app restarts the helper itself after a crash or a package update while its process is running. Turning the helper switch off shuts that process down and leaves it stopped until the switch is turned on again. A successful launch leaves Wireless Debugging on, because that connection is also how the browser API reaches Chrome. The optional `WRITE_SECURE_SETTINGS` grant lets the app flip that switch; without it, turn Wireless Debugging on in Developer options when it drops (for example after reboot).

On Android 10, browser calls use a USB tunnel instead. `phoneapi helper` sets that up.

To pair Wireless Debugging from the phone (Android 11+), tap "Pair wireless debugging" in the app. It opens Developer options and watches mDNS for this phone's pairing service. When the system "Pair device with pairing code" dialog opens, a heads-up notification asks for the code inline. The dialog has to stay in front: Android closes the pairing port when it is dismissed, including when the user switches apps. On Android 10 that row is hidden.

Snapshots, injected input, screenshots, logcat, browser routes, and streams need the helper. App launch and wake still run without it (with limited fallbacks). See [docs/api.md](docs/api.md) for which routes require the helper or the browser scope.

## Usage

Start the helper (`phoneapi helper`) and `phoneapi server`, then point an MCP client at `http://127.0.0.1:<port>/mcp` with the bearer token, both from the pairing file. In Claude Code (project `.mcp.json`, or user scope in `~/.claude.json`):

```json
{
  "mcpServers": {
    "phone": {
      "type": "http",
      "url": "http://127.0.0.1:<port>/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

Cursor takes the same entry without `type`. Each client gets its own session, and clients share the phone's screen one at a time, as [docs/sharing.md](docs/sharing.md) describes.

Then ask the agent something concrete, for example:

> On the phone, use Chrome to open https://github.com/die-net/phoneapi/ and go to the Releases page.

Tool list, resources, and scopes are in [docs/api.md](docs/api.md).

### HTTP and WebSocket API

You can also call the REST and WebSocket API directly (`curl`, your own client, or `phoneapi papi` while developing). Calls that act on the phone need a session from `POST /v1/sessions`, named in the `Mcp-Session-Id` header; `phoneapi papi` keeps one for you. Run `phoneapi server` first so localhost reaches the on-device socket. Auth, routes, and OpenAPI are in [docs/api.md](docs/api.md). The viewer at `http://127.0.0.1:<port>/viewer` plays video and audio streams with WebCodecs (stream scope).

## AI Disclosure

Though the design, architecture, some of the code, reviews, and testing were
done by a human, most of the code was written by Opus 5.5 and Grok 4.7.

## License

Copyright 2026 Aaron Hopkins and contributors. All rights reserved.

Licensed under the [Apache License, Version 2.0](LICENSE).

`app/src/main/assets/readability/` is Mozilla's [Readability.js](https://github.com/mozilla/readability) 0.6.0, under the Apache License 2.0; see its `LICENSE.md`.

## Screen cleanup

In the app, **When screen control ends** chooses the device-wide release policy:

- **Go home and lock** (`home-lock`, the default): hide the keyboard, show Chrome's blank placeholder, go HOME and lock.
- **Go home without locking** (`home`): the same steps without the lock.
- **Do not perform release cleanup** (`none`): skip those four steps.

There is no session override or remote setter. Clients can read the current policy in `GET /v1/device`. Android's normal display timeout still applies, and ending a session still closes its tabs. Neither alternative keeps a phone unlocked indefinitely. See [screen sharing](docs/sharing.md#cleanup) for release and hand-back rules.
