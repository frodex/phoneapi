# Sharing the phone

Phase 1, below, is built: MCP sessions, the screen lease with its queue and cleanup, the viewer's Take over, REST calls as one-call holders (since replaced by REST sessions), the security limits, and browser input defaulting to `auto`. Phase 2 is built too: tab ownership with the caps and startup sweep, the placeholder tab that keeps other sessions' tabs off the screen, recovering unloaded tabs, `browser_frozen`, Chrome's Custom Tabs binding, and `took_screen`. It was checked on the Android 16 emulator, not yet on a phone. Where the build differs from the design below, the section says so. [REST sessions](#rest-sessions) are built too. [api.md](api.md) describes what exists.

Several clients can drive one phone at once: two agent conversations, an agent and a person in `/viewer`, or a script on REST. Without rules, one taps while the other types, one navigates a tab the other is reading, or one sees what the other left on screen. This design lets them share the phone without that, and lets background Chrome tabs be used freely while someone else has the screen.

PhoneAPI owns the rules, because only it knows which calls take the screen and which tabs exist. A client only decides how long its MCP session lives. Legwork's side, one session per turn, is in Legwork's [phone-leases.md](../../legwork/docs/phone-leases.md). A model needs no lock tools. `screen_release` lets one give the screen up early, for clients such as Claude Code or Cursor that keep one session open for as long as they run.

## Goals and limits

- No client drives the screen while another holds it, and none reads, navigates, or closes another's tabs.
- Background tabs need no lock. Reading and cdp input on a client's own tabs run while someone else holds the screen.
- Background means background. Chrome never shows a tab whose owner lacks the screen, so a tab behaves the same whether one conversation is running or several. A workflow that only works while its tab is on screen fails every time, not only under load.
- Waiting is the tool call blocking, not the model polling.
- Normal release cleanup with the default `home-lock` prepares the home screen with the keyboard hidden and the phone locked. `home` makes the same preparations without locking; `none` skips them. Viewer hand-back and other no-cleanup exceptions still apply.
- Every client gets the same rules: MCP clients on HTTP, a person in `/viewer`, and REST. The `phoneapi mcp` stdio relay is retired, as in [The stdio relay](#the-stdio-relay).
- Not a privacy boundary. Every client uses the same Google and app accounts on the phone, so Chrome history, Maps searches, and app state are shared whatever cleanup does. `device_info` and the `state` on errors still show the foreground package. The `home-lock` and `home` policies prepare a neutral screen on normal release; `none` skips those preparations. Tab ownership rules still apply in every mode.
- Not isolation between sites. Tab ownership separates tabs, not cookies, logins, or what a site keeps on its servers. Two conversations on one site can log each other out, share a shopping cart, or trip a site that refuses a second open tab. That is the same problem as two threads editing one Google Doc, and a tab lock cannot fix it. Incognito would separate the state, but it would also drop the logged-in accounts that are the point of using the phone.
- Not a security boundary either. Any token with the right scope can act as a viewer or open a session. ADB stays the trust boundary.

## Holders

A lease belongs to a holder. These are the kinds:

| Holder | Starts | Ends |
| --- | --- | --- |
| MCP session | `initialize` on `/mcp` | `DELETE`, or idle expiry |
| Started session | `session_start`, from an MCP session | `session_end`, idle expiry, or the session that started it ending |
| Viewer | Its input socket opening | The socket closing |
| REST session | `POST /v1/sessions` | `DELETE /v1/sessions/current`, or idle expiry |

The screen lease and tab ownership are keyed by holder, so the viewer and every session follow the same queue. A session's holder is its `Mcp-Session-Id`, whether MCP or REST made it.

## MCP sessions

`/mcp` becomes stateful. `initialize` assigns an `Mcp-Session-Id`, which is unguessable, so it needs no separate owner field. Every later request carries it.

- A request must carry the token that started the session. A session's tools and scopes are fixed at `initialize`, so another token could otherwise use the first one's scopes. A different token gets the empty 404 an unknown token gets.
- An unknown or expired session id gets 404, as the Streamable HTTP spec says, and the client starts a new session.
- `DELETE` ends the session. That releases the screen, runs cleanup, and closes the session's tabs.
- A session with no requests for 20 minutes expires, and expiry does what `DELETE` does. The timeout is a backstop, not the normal release. It is longer than a Legwork turn waiting on an Approve button (up to 15 minutes), so PhoneAPI never ends a live session someone is waiting on. Its screen is another matter, as in [Waiting](#waiting). It is the same for every client, and a client cannot ask for another.
- The app keeps sessions in memory, so restarting it ends them all. Clients see 404 and start again. PhoneAPI saves the ids of tabs that sessions opened and closes any that are left when it starts, so a restart does not strand them. It does this once per start, when the helper first connects; a helper that restarts later leaves live sessions' tabs alone. Tabs a session only claimed are not saved, since they were not its to close.

### Sessions for subagents

One MCP client is usually one session, so its subagents share a turn at the screen and a set of tabs, and parallel ones tap over each other. `session_start` gives a subagent or parallel task a session of its own:

- It returns a new session id, under the session that called it and bound to the same token. Every other tool takes an optional `session` argument naming it, and a call with it acts as that session: its own place in the screen queue, its own tabs, its own grace period and notes. Without the argument, a call acts as the caller's own session.
- A session can name only itself or sessions it started. Another session's id, or one that has ended, fails with `not_found` and a message to call `session_start`.
- A session and one it started never wait for each other's screen. A screen call while the other one holds the screen fails at once with `session_conflict`, saying which way round it is: a session from `session_start` called while its starter held the screen, which on 2026-10-22 was an agent passing `session` on its own calls, or the starter called while one of its sessions held it. Waiting would queue the caller's own work behind itself, and after 30 seconds the idle-holder rule would hand the screen over with a cleanup the caller never asked for. Two sessions started by the same one still wait for each other, since that is parallel work.
- Tabs are not shared between them. A tab that belongs to another session in the same family still fails with `not_found`, but the message says it belongs to the caller's own session or one from `session_start`, and how to use it. A tab of an unrelated session gets the plain `not_found`, so nothing about other clients leaks.
- `session_end` ends one, as `DELETE` would: cleanup if it held the screen, and the tabs it opened close. It also ends after 20 minutes unused, and when the session that started it ends.
- A started session has no transport, so `/mcp` answers its id in `Mcp-Session-Id` with 404. It counts toward the session caps and shows in `GET /v1/sessions` with `parent`, the hash of the session that started it.

This is also the shape MCP's stateless revision (2026-07-28) asks for: it drops protocol sessions, and servers that need state across calls mint handles that clients pass as tool arguments. PhoneAPI's MCP SDK (0.15.0) does not speak that revision yet, so such clients still cannot connect, but the tools would carry over.

`initialize` returns `instructions`, a short overview for the model: the phone is shared, which calls wait for the screen and for how long, that reading tabs and `cdp` input need no screen, `screen_release`, `session_start` for parallel work, and how to keep calls few. Clients decide whether the model sees them. Claude Code does. Legwork's hub serves its own tools and does not pass an upstream's instructions on yet, so there the tool descriptions have to carry it alone.

The model carries the id, so it can forget it or pass a stale one. A forgotten id makes the call act as the caller's own session, and a stale one fails loudly. The id sits in the model's context and the client's logs, where the header kept it out of sight, but it is useless with any other token.

The MCP SDK (0.15.0) has a stateful transport with session ids, `DELETE`, and open and close callbacks, but no idle timeout, and its Ktor helper keeps the session table private. PhoneAPI therefore owns the `/mcp` route and drives `StreamableHttpServerTransport` itself, so expiry, the token check, and release live in one place.

A prototype on the Android 16 emulator showed, on 2026-10-20:

- `initialize` assigns a random session id. `notifications/cancelled` cancels the tool's coroutine at once. `DELETE` cancels every call in flight, and the session then answers 404.
- The id a tool handler sees, `ClientConnection.sessionId`, is the SDK's own, not the `Mcp-Session-Id` the client holds. PhoneAPI creates each session's transport and `Server` together, with `setSessionIdGenerator` returning an id it picked, so that id is in the tools' closure. Leases and tabs are keyed by it.
- A call cut off by `DELETE` comes back as HTTP 500 `Job was cancelled`, from PhoneAPI's error mapping. It should be a JSON-RPC error.
- Responses are JSON. The SDK's streaming mode answers a POST with an event stream that Ktor closes as soon as the transport hands the request off, before the result, so it does not work without more plumbing. In JSON mode the SDK sends a request's progress on the client's GET stream, and drops it when the client has none.

So `/mcp` answers POSTs with JSON and refuses the GET stream with 405, which the spec allows, and sends no progress for now. See [Progress](#progress).
- Nothing from the SDK, session ids included, reached logcat in a debug build. A release build uses a different logging backend and is not checked yet.

## REST sessions

Built. Before REST sessions, a REST call was a one-call holder: it took the screen, let it go when it returned, and left any tab it opened unowned. That does not work for scripts, which run sequences such as unlock, launch an app, tap a field, type. Another holder can take the screen between two of those steps, and a session can claim a tab the script opened by using it. So REST has sessions too, the same ones MCP has.

### Starting and ending

- `POST /v1/sessions` creates a session and returns `{"id": "...", "idleMs": 1200000}`. It is bound to the calling token, counts toward the same caps as MCP sessions (16 per token, 32 in all), and fails with 429 `too_many_sessions` past them. Any token can create one; the admin scope is only for listing and ending other sessions.
- Every REST call that needs a session carries its id in `Mcp-Session-Id`, the header MCP uses, so one id works on both. An id another token created, or one that has ended, gets 404 `not_found`, as on `/mcp`.
- `DELETE /v1/sessions/current`, with the header, ends the caller's session the way an MCP `DELETE` does: cleanup if it held the screen, its opened tabs close, its claimed tabs go back to being unowned. Idle expiry after 20 minutes without a request, revoking the token, and the admin `DELETE /v1/sessions/{hash}` end it too.
- `POST /v1/screen/release`, with the header, is the REST counterpart of `screen_release`: cleanup, then the next waiter gets the screen, and the session keeps its tabs.

### What a REST session gets

The same rules as an MCP session, since both are the same kind of holder:

- It keeps the screen from its first screen call until it ends, releases it, or makes no screen call for 30 seconds while someone else waits. Notes reach it as `X-PhoneAPI-Note` headers, as they do today.
- Tabs it opens are its own, popups they open follow them, and unowned tabs it uses become its own. Other sessions cannot see or use them, and it cannot see theirs. `GET /v1/browser/targets` lists its own tabs and unowned ones, with `owned`.
- `took_screen` watches its calls that could launch an app while it lacks the screen, and Chrome shows only its tabs or the placeholder while it holds the screen.
- The placeholder, unloaded-tab recovery, `browser_frozen`, and `tab_frozen` apply as they do everywhere.

### Which calls need a session

Every route that acts on the phone, shows the screen, or touches a tab, which is every route that can take the screen or own a tab:

- `POST /v1/device/wake`, `/unlock`, `/lock`, `/orientation`
- `GET /v1/ui/snapshot`, `POST /v1/ui/find`, `POST /v1/ui/nodes/{ref}/action`, `GET /v1/screenshot`
- `POST /v1/input/tap`, `/swipe`, `/gesture`, `/key`, `/text`, and `POST /v1/ime/hide` and `/show`
- `POST /v1/apps/{pkg}/launch`, `/stop`, `/clear`, and `POST /v1/intents`
- `POST /v1/wait`
- Everything under `/v1/browser/` except the raw CDP socket: listing tabs, opening, navigating, closing, reading, finding, snapshots, input, `evaluate`, and `console`.

One without the header fails with 428 `session_required`: "Create a session with POST /v1/sessions and send its id in Mcp-Session-Id." 428 Precondition Required says the request is fine but a precondition is missing, which a client can tell from a bad request.

These stay sessionless:

- `POST /v1/sessions`, `DELETE /v1/sessions/current` (it names the session in the header), and the admin `GET /v1/sessions` and `DELETE /v1/sessions/{hash}`.
- Token admin, `/v1/tokens`.
- What neither shows the screen nor touches tabs: `GET /v1/device`, `GET /v1/apps`, setting and clearing the stored PIN (`PUT` and `DELETE /v1/device/pin`), `GET /v1/openapi.json`, and the events socket.
- The viewer: `/viewer`, its input socket, which is its own holder, and the video and audio streams, which are the viewer's view of the screen.
- The raw CDP socket, `/v1/browser/targets/{id}/cdp`. A client on it can address any tab through `Target.*`, so ownership cannot be enforced there. It stays limited to tabs no session owns, as now, and takes no session.

### Sharing ids with MCP

- **MCP to REST.** The id `initialize` returns works on REST calls as the same holder: same place in the screen queue, same tabs, same notes. An agent's client can make a REST call mid-turn without losing its screen.
- **REST to MCP.** MCP requires `initialize` before anything else, so PhoneAPI accepts an `initialize` that carries an existing session's id in `Mcp-Session-Id`, from the same token, and attaches the MCP transport to that session instead of making a new one. A standard client never sends one, so it cannot affect them. A session that already has a transport answers a second `initialize` as any MCP session does, with the SDK's 400 that it is already initialized. A session from `session_start` cannot take an `initialize`; its id stays a tool argument.
- **Started sessions.** An id from `session_start` works in the header on REST too, for the token that owns it. On REST there is no parent session in the request, so any of the token's live sessions can be named.
- **Ending.** A session is one session whichever side ends it. A REST `DELETE /v1/sessions/current` on a session with an MCP transport closes the transport, and the MCP client gets 404 on its next request. An MCP `DELETE` ends it for REST callers too.

### The CLI

`phoneapi papi` is the only REST client in this repository; the viewer page uses only `GET /v1/device` and its sockets.

- `papi` creates a session when the server first answers 428 and keeps the id next to the pairing file, in `.dev/session` for `phoneapi-dev`, so a sequence of `papi` calls is one holder, as a script expects. It sends `Mcp-Session-Id` on every call.
- When a call gets 404 because the session ended, `papi` creates a new one, retries once, and says so on stderr: a new session starts without the screen or the old session's tabs.
- `PAPI_SESSION` names a session to use instead of the file, so two scripts can run as separate holders, or a script can join an MCP session.
- `phoneapi session end` ends the stored session and removes the file; `phoneapi session id` prints it.
- `phoneapi setup` and `phoneapi pair` work as now; they use broadcasts and the token, not sessions.

### Decided

- **No transition.** Nobody outside this repository uses REST yet, so calls without a session go straight to 428 `session_required` in the release that adds sessions.
- **The sessionless routes above stay sessionless.** `GET /v1/apps` and the PIN routes show nothing on the screen and touch no tabs, so a one-off call to them needs no session.

## Screen lease

One exclusive lease per phone. The first call that needs the screen takes it. An MCP session keeps it until the session ends, it calls `screen_release`, or it makes no screen call for 30 seconds while another client waits. PhoneAPI decides which calls need it, in the tool catalog:

- Device and app tools: `tap`, `swipe`, `type_text`, `press_key`, `clipboard_read`, `clipboard_write`, `ui_act`, `keyboard_*`, `app_*`, `open_intent`, `device_wake`, `device_unlock`, `device_lock`, `device_orientation`.
- Observing the screen: `ui_snapshot`, `ui_find`, `screenshot`. These would show another holder's screen.
- `wait_for` with any condition that is not `browser.*`, or with `snapshot` or `screenshot`.
- Browser input with `input` `touch`, which brings the tab forward. Pointer input with `input` `cdp` too: Chrome only takes cdp touch on the tab it is showing, so it already needs Chrome in front.
- Anything else that brings Chrome or a tab to the front.

Calls that need no lease: `device_info`, `logcat_tail`, and browser tools on the caller's own tabs with `input` `cdp` (keys and text) or `cdp-mouse`, or no input at all.

Browser input defaults to `auto`: `touch` when the caller holds the screen, and otherwise `cdp-mouse` for a tap and `cdp` for keys and text. A swipe or gesture with `auto` still needs the screen, since cdp touch only reaches a tab on screen. Without this, a tap that left `input` unset would take a free screen and keep it for the rest of the session. Passing `touch` still takes the screen on purpose.

The REST routes for the same operations follow the same list.

### Waiting

A call that needs the screen while another holder has it waits in a FIFO queue for up to 60 seconds. It sends no progress while it waits. When the time passes, the call fails with `screen_busy` and how long the lease has been held. It never says who holds it, except that a person in the viewer is reported as a person. A canceled request leaves the queue.

Only `notifications/cancelled`, or the session ending, takes a call out of the queue. A client dropping its connection does not: the prototype's tool ran to the end after its client went away, on today's stateless `/mcp` as well as on stateful sessions. That matches the spec, which says not to treat a disconnect as a cancellation. So a waiting call whose client has gone stays queued until its 60 seconds pass or its session ends, and clients have to send `notifications/cancelled` to stop a wait. Legwork's Go SDK (v1.8.0) does that when a call's context ends, sends `DELETE` when the session closes, and opens the GET stream after `initialize`.

A session that holds the screen and has made no screen call for 30 seconds, while another client waits, loses the screen to that client. Cleanup runs as for any release, and the session keeps its tabs. Its next screen call waits like anyone else's and carries a note that the screen changed hands, so it checks the screen before acting. With nobody waiting, a session keeps the screen however long it is idle, so a lone client keeps its screen state between turns. There is no limit on how long a session holds the screen while it keeps using it.

This is what makes PhoneAPI usable by clients other than Legwork. Claude Code, Cursor, and most MCP clients keep one session open for as long as they run, and any request, a `ping` included, keeps a session from expiring. Without the grace period, one screen call from such a client would keep everyone else off the screen until it was idle for 20 minutes or quit.

With the default `home-lock` policy, an idle holder can come back to a home screen and a locked phone. That includes a Legwork turn waiting on an Approve button: if another conversation wants the screen meanwhile, the turn resumes on a cleaned-up screen and has to find its place again. Before the grace period, the other conversation got `screen_busy` instead.

A session can also let the screen go itself with `screen_release`, which does the same as the grace period at once: cleanup, then the next waiter. Idle expiry, ending the session from the admin routes, and **Take over** in the viewer still cover a stuck client.

### Cleanup

When a holder releases the screen or expires, PhoneAPI applies the device owner's `screenCleanupOnRelease` policy before granting the next waiter. Choose it in the app's **When screen control ends** setting. There is no remote setter or session override.

| Value | Release cleanup |
| --- | --- |
| `home-lock` (default) | Hide the keyboard, show the blank placeholder tab, go HOME, then lock. |
| `home` | Hide the keyboard, show the blank placeholder tab, then go HOME. Do not lock. |
| `none` | Do not perform release cleanup. |

The default preserves the existing behavior. Clipboard contents are not cleared by release cleanup. The policy is sampled once when cleanup starts; a setting change applies to the next release. Missing or unknown stored policy values use `home-lock` without changing other settings.

`home` and `none` do not unlock an already locked phone or disable Android's normal display timeout. Session end still closes tabs that session opened, even with `none`, so that mode does not promise an unchanged display. The lease rules, idle clocks, takeover and hand-back behavior are unchanged. A non-holder release does not clean up; viewer hand-back to its existing session skips cleanup.

The next holder receives a note describing the policy that ran, or the steps that failed. Release calls request asynchronous cleanup; their response does not prove it has finished.

Notes waiting for a session appear on its next call of any kind, not only one that needs the screen, so a session that lost the screen while idle hears about it on its next read or `screen_release`. A screen call that waited more than 2 seconds for another holder says so in a note, so a slow call is not mistaken for a slow phone.

A REST call runs as its [session](#rest-sessions), which keeps the screen between calls, so a script's steps are not undone and no other holder takes the screen between them.

With `home-lock` or `home`, normal release cleanup puts Chrome in the background. `none` does not move Chrome or prepare the placeholder, so the next holder must inspect the screen. Tab ownership still applies in every mode: when a holder brings Chrome forward through PhoneAPI, it shows one of that holder's tabs or the placeholder.

## Viewer

Watching needs no lease. The video and audio sockets show whatever is on screen, because the person at the viewer is the phone's operator.

Input does. Today the viewer sends touches on `WS /v1/input/pointer` and keys, text, and orientation as REST posts, and it opens the pointer socket on load and reconnects every 500 ms whether the page is visible or not. So holding the socket open cannot mean holding the screen. Instead:

- The viewer's keys, text, and orientation move onto its socket, next to pointer frames. The socket is the holder, and needs no extra header to tell viewer posts from a script's.
- The viewer's first input takes the screen at once if no one holds it. That input is delivered.
- If someone else holds it, the input is dropped, not queued, and the socket tells the page. The page shows that the phone is in use, by an agent or by another viewer, and for how long, with a **Take over** button.
- **Take over** takes the screen at once. The holder's call in progress is canceled. Until the screen comes back, the holder's screen calls fail at once with `screen_taken`, rather than waiting 60 seconds, so the model stops and says so instead of retrying. No cleanup runs, so the person sees what the agent left, such as a half-filled form or a captcha.
- The viewer gives the screen up after 30 seconds without input, or when its socket closes. The page shows that it has.
- If it took the screen over and that holder is still alive, the screen goes straight back to it, without cleanup, since the person may have fixed something for it. Its first result that needs the screen says a person used the screen and for how long. Otherwise cleanup runs and the queue goes on.
- The socket sends lease changes to the page, so it can show who has the screen without polling.

Agents never take the screen over. They queue behind a viewer like anyone else.

## Tabs

A tab belongs to the holder that opened it, or that claimed it. A REST session owns the tabs it opens, as an MCP session does. A tab Chrome opens for an `open_intent` or `app_launch` belongs to the session that made that call too: PhoneAPI notes Chrome's tabs before the call and claims the new ones that appear in the next 2 seconds, since Chrome handles the intent's URL after the activity starts. Before 2026-10-22 such a tab stayed unowned and outlived its session.

A tab id that no longer works says why: the tab closed, as a session's tabs do when it ends, or it belongs to another session. Chrome refuses a DevTools connection to a closed tab, with 500 "No such target id" in Chrome 133 (404 in some releases), which PhoneAPI reports as that `not_found` rather than retrying.

- `browser_targets` lists the caller's tabs and unowned ones, marked as unowned. Other holders' tabs are left out.
- `browser_navigate`, `browser_close`, `browser_read`, `browser_find`, `browser_snapshot`, `browser_eval`, `browser_console`, browser input, and `browser.*` wait conditions refuse a tab another holder owns, the same way they refuse a tab that does not exist. `browser.newTarget` only matches tabs the caller owns.
- A tab that a page opens, through `window.open` or a `target=_blank` link, belongs to the owner of the tab that opened it. That needs Chrome's `openerId`, which `/json/list` does not report, so ownership is read from the `Target` domain (`Target.getTargets`, or `Target.setDiscoverTargets` on the browser connection). On the emulator `Target.getTargets` reported `openerId` for both, with the opener on screen, behind another tab, and with Chrome in the background.
- Tabs that existed before PhoneAPI started and tabs left by a session that died belong to no holder. On a phone an agent uses every day they are mostly its own pages from earlier conversations, such as a signed-in shopping tab, so they stay usable. A session claims an unowned tab by using it, and the first session to do so gets it. When that session ends, the tab goes back to being unowned instead of closing.
- WebViews belong to the app that shows them. The screen holder can use the WebViews of the app in front, which it can already see with `ui_snapshot`. Other sessions cannot.
- The raw CDP socket can use any tab except one a live holder owns.
- Each session can have 4 tabs open, and all sessions 8, not counting the placeholder, unowned tabs, or tabs a session claimed. Opening past either fails with `tab_limit` and a message to close one. Under memory pressure Chrome unloads hidden tabs, as in [Tabs that stop answering](#tabs-that-stop-answering). The caps keep that rare, and a few tabs at a time is what a turn needs. On the 2 GB emulator unloading started at 17 tabs, so 8 leaves room for the tab on screen, unowned tabs, and other apps on a 4 GB phone.
- The tabs a session opened close when it ends. Tabs it claimed go back to being unowned.
- A tab's [recorded responses](api.md#recorded-responses) stay with its owner. Recording stops when a session claims a tab and when it gives one back, so a tab never changes hands with someone else's responses in it.

Whoever holds the screen can open Chrome's tab switcher and see other holders' tab titles. Given that the accounts are shared anyway, that is accepted.

### Background tabs stay in the background

Android 10 and later block an app in the background from starting activities, unless it was on screen in the last few seconds, and Chrome does not hand a navigation in a hidden tab to another app. Neither protects a tab Chrome is showing. Today a lone agent's tab often is: Chrome on Android ignores `Target.createTarget`'s background flag and switches to a new tab while Chrome is in front, which is why `tookScreen` exists. So:

- PhoneAPI keeps one `about:blank` placeholder tab that no holder owns, and opens it again if it is closed.
- Whenever Chrome is in front, it shows the screen holder's own tab or the placeholder. When Chrome selects another holder's tab, such as one just opened or a popup, PhoneAPI selects the holder's tab or the placeholder again with `Target.activateTarget`, between the holder's calls, never during one. The holder's tab is the one it last opened or brought forward with touch input or `show`. If Chrome will not switch, PhoneAPI closes the new tab and fails the call with `took_screen`.
- A popup from a tab that is not on screen becomes the tab Chrome shows while Chrome is in front, with no change of foreground app. After each browser input, `browser_eval`, and `browser_navigate` from a session without the screen, PhoneAPI gives new popups to the opener's owner and puts Chrome back as for a tab that session opened. A popup a page opens later, on a timer, is caught before the holder's next screen call.
- Selecting a tab can take the screen. On the emulator, `Target.activateTarget` and `Page.bringToFront` brought Chrome forward from the background, even 15 seconds after HOME. On the phone they only changed which tab was selected, and the launcher stayed in front. The emulator runs Chrome as a debug app with `adb root`, which may explain the difference. PhoneAPI never sends either for a session without the screen.
- Chrome stays in the background when nobody holds the screen. Cleanup shows the placeholder before HOME.
- While Chrome is in the background, PhoneAPI leaves its selected tab alone, since selecting one brings Chrome forward. It fixes the selection before each of the holder's screen calls while Chrome is in front, and after one that brought Chrome forward, such as `app_launch` of Chrome, before that call returns. It checks after selecting, since Chrome can change its selection as it comes forward. While only one session owns tabs, none of this runs.
- `tookScreen` is always false for a caller without the screen.

### Tabs that stop answering

Three things stop a hidden tab, measured on the Android 16 emulator on 2026-10-21:

- **Chrome throttles and then freezes it.** With Chrome in front and the tab behind another, its timers ran about once a second for the first minute, about once a minute after that, and stopped at about 5 minutes. A frozen tab still answers DevTools: script evaluation, reading, mouse input, and navigation all work, and only the page's own JavaScript is paused, so content it would load or poll for does not arrive. `Page.setWebLifecycleState` with `active` answered but did not restart its timers. Navigating or reloading gives a fresh page that runs until it freezes again.

  More on the emulator on 2026-10-22, with Chrome in the background and PhoneAPI's helper holding Chrome's binding:

  - The 5 minutes count from when the tab was hidden, not created. A tab kept on screen for 3 minutes froze about 5 minutes after it was hidden; tabs hidden from the start froze at 5 minutes. Reading a tab over DevTools once a minute did not delay it.
  - `Page.setWebLifecycleState` with `frozen` and then `active` thaws a frozen tab without bringing it forward. A fetch the page started then completed, where one on a frozen tab never did. Its timers ran about once a minute, as for any tab hidden that long. It had not frozen again 9 minutes later.
  - With Chrome started with `--disable-background-timer-throttling --disable-renderer-backgrounding`, tabs still froze at 5 minutes, but a toggled tab then ran its timers once a second.
  - A tab reloaded while hidden ran its timers once a second for a minute, then about once a minute. It froze again about 5 minutes after the reload, so a reload restarts the clock.
  - A frozen tab still evaluates DevTools script but runs none of its own tasks, so a message it posts to itself never arrives. That tells a frozen tab from a running one in about 10 ms on a running tab and a 200 ms timeout on a frozen one. A page busy running script answers no script at all, which tells it apart.
- **Chrome unloads it under memory pressure.** Opening heavy pages one after another, nothing was unloaded through 15 tabs, ten of them real sites such as YouTube, CNN, Amazon, Maps, and the New York Times, with free memory down to about 220 MB. From 17 tabs, with 250 MB pages added, Chrome unloaded the oldest tabs first, six by 19 tabs. An unloaded tab stays in the tab list and answers page-level commands, but script evaluation never answers, since its renderer is gone. That matches the long-hidden tabs on the Android 11 test phone. `Page.reload` or `Page.navigate` brings it back as a fresh load and leaves it hidden. Selecting it also reloads it, but puts it on screen. `document.wasDiscarded` stayed false, so a changed `performance.timeOrigin` is what shows the reload.
- **Android freezes all of Chrome.** On Android 12 and later the cached-app freezer is on by default. About 2 minutes after Chrome went to the background, every Chrome process was frozen, `isFrozen=true`, and nothing answered, not even the browser endpoint. Android 11 has the freezer but leaves it off by default, which fits the phone answering at the browser level.

  The freezer's effective state is in `adb shell dumpsys activity`, under "Freezer settings", as `use_freezer=true` or `false`. The setting behind it is `cached_apps_freezer` (`settings get global cached_apps_freezer`), which is unset by default. On the Android 16 emulator, changing the setting changed `use_freezer` at once, without the reboot the developer option asks for: with it `disabled`, Chrome was never frozen in 150 seconds in the background, and set back to the default, Chrome was frozen within 90 seconds. Turning it off does not thaw an app that is already frozen; that waits until the app comes to the front or the phone restarts. Whether Android 11 shows the same section and applies changes as quickly is not checked.

So PhoneAPI:

- Asks Chrome whether the tab has a renderer before a call that needs it, rather than waiting out the 15-second DevTools timeout.
- When it has none, it was unloaded. PhoneAPI reloads it in the background and says so in the result, since anything typed or scrolled is gone, rather than acting on the fresh page as if nothing happened. Input calls fail instead with `tab_reloaded`, so a session does not type into a page it has not looked at again.
- When the browser does not answer either, Android has frozen Chrome, and the call fails with `browser_frozen`. PhoneAPI keeps that from happening while its helper runs, as in [Android's app freezer](#androids-app-freezer).
- Refuses input into a frozen tab, `cdp` and `cdp-mouse` taps, swipes, gestures, keys, and text, with `tab_frozen`. Input still reaches a frozen page, but its scripts do not run, so a click or keystroke seems to do nothing, and an agent loses more time working out why than bringing the tab forward or reloading it would cost. The error says how to do either. Touch input brings the tab on screen, which wakes it, so it is let through. Reads and `eval` still work on a frozen tab.
- Refuses that input too when the tab's timers have stopped, which on the phone happens long before a pause, and fails waits on the tab, as below.
- Says in reads that the tab's timers have stopped, so content it would load or update on its own is missing, next to `shown` and `pending`.

**On the phone**, checked on 2026-10-22 with the phone locked and Chrome in the background: a hidden tab's timers ran once a second for about a minute, then stopped entirely, where the emulator kept them at about once a minute. The page itself paused between 7 and 9.5 minutes, later than the emulator's 5, and `tab_frozen` caught it then. Between the two, input and reads work but the page's timers never fire, so a page that reacts through them, such as a debounced search box, never does, and nothing says why.

So a second check, for timers, runs next to the pause check. Chrome runs a hidden tab's timers at most about once a second even when it has not stopped them, so the check has to allow 1.1 seconds, too long to wait before every input. Instead it starts alongside each `cdp` or `cdp-mouse` input, in the background, on its own DevTools connection, and the next call on that tab reads the result:

- the next input to the tab is refused with `tab_frozen`, saying the last input may not have taken effect, after a fresh check confirms the timers are still stopped;
- a `wait_for` on the tab fails at once, or as soon as the check comes due, rather than running out its timeout; a wait starts its own check when no input has;
- a read notes `timersStopped`.

The check's timer has a 1 ms delay: Chrome runs a zero-delay timer at once on a queue it never throttles, which on the emulator fired at once in a tab whose other timers had slowed to once a minute. On the phone, both stopped along with the page's own. Bringing the tab forward, reloading it, or navigating clears the check. Both checks run in an isolated world, so the page can neither see them nor fool them by replacing `MessageChannel` or `setTimeout`; Chrome stops that world's timers along with the page's, since it throttles per frame.

As built, the check is `Inspector.enable` on the page's own connection. Chrome answers it from the browser process and, for a tab with no renderer, sends `Inspector.targetCrashed` first. A script probe with a timeout cannot tell an unloaded tab from a page whose main thread is busy: on the emulator, a page in an 8-second loop answered neither script nor, on a fresh connection, `Runtime.getIsolateId`, and the first build reloaded it. With `Inspector.enable`, that read waited for the loop and kept the page. When `Inspector.enable` gets no answer in 2 seconds, PhoneAPI checks Chrome's browser endpoint on a socket that gives up reading after 2 seconds. A read of an unloaded tab returned the reloaded page in about a second on the emulator, with Chrome's renderers killed to stand in for memory pressure. An unloaded tab answered neither script nor `Page.getFrameTree` there, so "answers page-level commands" above holds for `Page.reload` and `Page.navigate`, not every page command. When Android has frozen Chrome, the page connection's handshake waits out the 15-second read timeout before the browser check, so `browser_frozen` takes about 17 seconds rather than four 15-second retries. Checked with `am freeze --sticky com.android.chrome`.

### Android's app freezer

Android only freezes an app it counts as cached, one that nothing in front of the user depends on. While the shell helper is running, PhoneAPI's foreground service binds Chrome's Custom Tabs service (`android.support.customtabs.action.CustomTabsService`), which any app may bind, so Chrome counts as bound by a foreground service, not cached. It holds the binding whether or not any client is connected, and drops it when the helper stops. The manifest declares the Custom Tabs action under `<queries>`, which Android 11 and later require before an app can see another app's service.

Checked on the Android 16 emulator, with the freezer at its default, on 2026-10-21, using a throwaway build:

- With the binding held, Chrome's browser process stayed at importance 200, the same as PhoneAPI's service, and Android froze none of Chrome's live processes in 10 minutes in the background. A hidden tab answered DevTools throughout. Its timers still stopped at about 5 minutes, which is Chrome's own freezing and leaves reads and input working.
- The renderers of hidden tabs sat at importance 900 to 910, the cached range, yet were not frozen. Android's low-memory killer can still take them, which is unloading as above.
- Without the binding, Chrome dropped to the cached range within a minute, and every Chrome process was frozen by 2 minutes.

This needs no system setting and touches only Chrome. Turning the freezer off for the whole phone, with `settings put global cached_apps_freezer disabled` or the "Suspend execution for cached apps" developer option, stays a manual fallback for a device where the binding falls short. PhoneAPI does not offer it as a setting unless that turns out to be needed. `browser_frozen` says what happened, and names the developer option.

The freezer's effective state is `use_freezer` under "Freezer settings" in `dumpsys activity`, which the helper can read. `dumpsys activity settings` leaves that section out, and the whole dump is hundreds of kilobytes, so the helper reads it through `grep -m 1`. `GET /v1/device` reports it as `appFreezer`, read when the helper starts, and whether PhoneAPI holds Chrome's binding as `chromeKept`. On the emulator, Chrome's Custom Tabs service listed PhoneAPI as its client from a foreground-service process.

### Content that loads when drawn

A hidden tab is not drawn. Images that load lazily do not load, which saves work, since PhoneAPI reads text and only a screen capture shows pixels. A capture needs the screen, and the images load once the tab comes forward. But some pages also build their text only when drawn or scrolled into view: infinite scroll, "load more" sections, skeleton placeholders. In a hidden tab that text never appears, and a read looks complete.

So reads say when that may have happened:

- `browser_read` reports `shown`: whether Chrome is drawing the tab, meaning Chrome is in front with this tab selected. `document.visibilityState` alone is not enough, since a tab opened while Chrome was in the background reports `visible` while Chrome is still hidden.
- `page-pending.js` reports `pending`, next to `blocked` and `sparse`: signs the page is waiting to load content, which are a laid-out `aria-busy` region, empty skeleton, shimmer, spinner, or loading boxes, and a "Load more" or "Show more" control.
- When the tab is not shown, the result says so. When the page is also `sparse` or `pending`, it says content may not have loaded while the tab was hidden, and that reading with `show` may load it. `browser_snapshot` says the same in lines after its header.
- `browser_read` and `browser_snapshot` take `show`. It brings the tab forward, launching Chrome if it is behind, and waits until the page's text holds still for 750 ms, at most 5 seconds, then reads. Under this design it also takes the screen, waiting like any screen call. It only draws what is on screen, so content further down may still need scrolling.

This part is built, without the screen lease, since it needs no sessions.

### When something comes forward anyway

If a call from a session without the screen still brings an app or Chrome forward, a workflow is relying on something it should not, and PhoneAPI makes that loud.

- PhoneAPI already tracks the foreground package. It watches the top task, not what is visible: between holders the phone is locked, and an app launched then waits behind the lock screen for the next holder.
- A change of top task during a call by a session without the screen, or within 2 seconds after, while the holder has no call in progress, is that session's doing. A change during the holder's call is the holder's. A change with no call in progress anywhere, such as an incoming call or an alarm, belongs to no session and is reported in the holder's next result that needs the screen.
- The session's call fails with `took_screen`, naming what came forward, even if the action itself worked. PhoneAPI also sends a `screen.taken` event on `/v1/events`, logs a warning, and counts it per session in `GET /v1/sessions`.
- If nobody held the screen, the session now holds it. It caused what is showing, so it is the one that can look and clean up, such as stopping navigation that Maps started. Cleanup runs when it releases.
- If another holder had the screen, PhoneAPI brings the holder's app back to the front, and the holder's next result that needs the screen says something came forward briefly. Whatever the launched app did is not undone.
- If two sessions without the screen had calls in progress, both fail with `took_screen` and neither gets the screen. As built, the app that was in front comes back, rather than cleanup running.
- Chrome switching to another holder's tab, as a popup makes it do, does not change the foreground app, so it is handled as in [Background tabs stay in the background](#background-tabs-stay-in-the-background).

On the emulator, tabs that were not on screen launched no app in 18 tries: an `intent:` link, an app link, and a custom scheme, each by mouse click, by script, and by script with a user gesture, from a tab behind another and with Chrome in the background. App links opened inside the tab instead. From the tab on screen, the same clicks launched YouTube.

Android and Chrome should block nearly all of these, so `took_screen` is a tripwire for workflows to fix, not something agents handle in normal use.

As built, PhoneAPI asks for the top task, through the helper's direct call to the activity task manager, before and after `browser_open`, `browser_navigate`, `browser_eval`, and browser input from a session without the screen, the calls through which a page could launch something. Reads cannot. It checks again 2 seconds after the call. A change found then cannot fail the call, so it reaches the session as a note on its next screen call, along with the event, log line, and count. Nothing is checked while a person in the viewer holds the screen, since the viewer's input makes no screen calls.

The first build ran `dumpsys activity activities` for this. It took about 8 ms on the emulator but 90 to 140 ms on the phone, where any `dumpsys` costs that much to start, adding about a quarter second to each background input. The helper now calls `IActivityTaskManager.getTasks` directly, choosing the overload as it does for input injection, and falls back to `dumpsys` if that call fails. The same call answers the "is Chrome in front" checks behind the placeholder.

## Progress

PhoneAPI sends no progress for now. The wait for the screen is capped at 60 seconds and ends in the screen or `screen_busy`, so nothing depends on it. Legwork's optional status line, its phase 4, is the one use in sight.

When something wants it, there are two ways:

- The session's GET stream. The SDK already sends a request's progress there in JSON mode, and the prototype showed it working. Clients may skip that stream, though Legwork's Go SDK opens it by default.
- Streaming POST responses, which every client must accept. The SDK's transport cannot do it through Ktor, so PhoneAPI would write its own transport on the SDK's `Transport` interface, about 250 to 350 lines. It would also own session ids, headers, and errors. Wrapping the SDK's transport instead is smaller but depends on its internals. Kotlin SDK 0.15.0, the latest release, has no fix.

## The stdio relay

`phoneapi mcp`, the bash loop that relays stdio to `/mcp`, is retired rather than rewritten for sessions. It would have needed to keep the session id, run requests concurrently so a cancellation reaches PhoneAPI while a call waits, and send `DELETE` when stdin closes. The clients in use connect over HTTP. It can come back if a stdio-only client needs it.

## Security

The spec's main warning about sessions is not to treat a session id as authentication. PhoneAPI doesn't: every request still needs the bearer token, and the token that started the session. The SDK makes ids with Kotlin's `Uuid.random()`, which uses `SecureRandom` on the JVM, so PhoneAPI keeps its generator. `/mcp`, including `GET` and `DELETE`, takes only the bearer header, never the `phoneapi` cookie or `access_token`, so a page in a browser cannot use a session even if it learns the id.

Every Legwork turn uses one token, though, so the token check does not keep one conversation out of another's session. A session id is the only thing that does, and the following keep it, and the phone, from being misused.

- **Session ids stay out of the log.** `logcat_tail` lets any session with `observe` read the log, so a full id there would let one conversation use or close another's tabs, or end its session. PhoneAPI logs a short hash of an id, never the id. The SDK's own logging is checked for ids at every level before phase 1 ships.
- **Sessions are capped.** Each session holds memory on the phone until it ends or expires. A token can have 16 sessions open and the phone 32 in all, to start. Past either, `initialize` fails with `too_many_sessions`. The SDK's event store, which keeps past messages for resuming a stream, stays off.
- **A stuck session can be ended.** Any request resets the idle timer, so a client that keeps calling holds the screen as long as it likes, and only **Take over** in the viewer breaks it. `GET /v1/sessions` (admin) lists sessions by hash, with their age, idle time, tab count, and whether they hold the screen. `DELETE /v1/sessions/{hash}` (admin) ends one as its own `DELETE` would.
- **Revoking a token ends its sessions.** Requests with a revoked token already fail, but its sessions would otherwise keep the screen, their tabs, and any queued call until they expired. Revocation ends them at once, with cleanup.
- **The viewer's socket checks `Origin`.** The `phoneapi` cookie is `SameSite=Strict`, but a page on `127.0.0.1` at any port is the same site, so another local web server's page in the same browser could open the viewer's socket with it. Today that socket injects touches. Under this design it can also take the screen over. The upgrade is refused unless `Origin` is the API's own `http://127.0.0.1:<port>`. The other WebSocket routes get the same check.

Leaks between holders are limited by the rules above: `screen_busy` says how long the screen has been held but not by whom, and tab listings show only the caller's tabs.

## Errors

| Status | `error` | Cause |
| --- | --- | --- |
| 409 | `screen_busy` | Another holder had the screen for the whole wait. The message says how long it has held it |
| 409 | `screen_taken` | A person in the viewer took the screen from this session |
| 409 | `took_screen` | A call from a session without the screen brought an app or Chrome forward. The message names it |
| 409 | `tab_limit` | The session has 4 tabs open, or all sessions 8 |
| 409 | `tab_reloaded` | Chrome had unloaded the tab, so PhoneAPI reloaded it instead of sending input to a page the session has not seen |
| 409 | `tab_frozen` | Chrome has paused the hidden tab, or stopped its timers, so it would not respond to input. Bring it forward or reload it |
| 409 | `session_conflict` | A screen call while the session that started the caller, or one the caller started, holds the screen |
| 503 | `browser_frozen` | Android has frozen Chrome in the background, so its tabs cannot answer. The message names the developer option that turns the freezer off |
| 428 | `session_required` | A REST call that needs a session came without `Mcp-Session-Id` |
| 429 | `too_many_sessions` | The token has 16 sessions open, or the phone has 32 |

On MCP these are tool errors, like other `ApiException`s.

## Phases

1. Stateful `/mcp` with the token check, idle expiry, the session caps, hashed ids in the log, ending sessions on revocation, the admin session routes, and retiring `phoneapi mcp`. The `Origin` check on WebSocket upgrades. Holders, the screen lease with its queue, and cleanup. REST calls as one-call holders. The viewer's socket as a holder, with **Take over**. Browser input defaulting to `auto`. Built.
2. Tab ownership, including opener inheritance and the startup sweep. Recovering unloaded tabs, `browser_frozen`, and holding Chrome's Custom Tabs binding while the helper runs. The placeholder tab and keeping Chrome in the background. `took_screen`. The tab caps. Built.
3. REST sessions: `POST /v1/sessions`, `Mcp-Session-Id` on REST calls, 428 `session_required`, session-owned REST tabs, `POST /v1/screen/release`, ids shared with MCP, and `papi` keeping a session. Built.

Legwork's phases 3 and 4 need phase 1. Phase 2 can follow. `shown`, `pending`, and `show` on reads and snapshots needed neither phase.

## To check on the phone

The emulator runs Chrome 133, and the phone Chrome 155.0.8059.84. Checked on the phone on 2026-10-21:

- `Target.activateTarget` and `Page.bringToFront` change the selected tab without bringing Chrome forward, unlike the emulator.
- A popup from a tab not on screen becomes the tab Chrome shows while Chrome is in front, as on the emulator, and `Target.getTargets` reports its `openerId`.
- Tabs not on screen launched no app in 8 tries with `tel:` and `intent:` links, by click and by script with a user gesture. From the tab on screen, both opened the dialer.
- Page commands work on each page's own DevTools endpoint. Attaching through the browser connection got no answers.

Checked on the phone on 2026-10-22, with v0.3.0, by the phone's agent through its tools and over adb:

- `use_freezer=false`, so Android 11 prints "Freezer settings" and has the freezer off, and `cached_apps_freezer` is unset. PhoneAPI holds Chrome's Custom Tabs binding (`chromeKept`).
- A session's own tab stayed on screen while another session opened a tab, so switching back works on Chrome 155.
- Tabs a request opened were gone in the next request.
- Hidden tabs: timers stopped after about a minute, and the page paused between 7 and 9.5 minutes, when `tab_frozen` caught it, as above.
- `dumpsys` takes 90 to 140 ms per call, which led to the direct top-task query.
- "UiAutomationService already registered" came from two calls connecting UiAutomation at once in one helper; connecting is now serialized.

Checked on the emulators on 2026-10-22, with Chrome 133:

- The direct top-task query works on Android 16, where `getTasks` takes `(int, boolean, boolean, int)`, and on Android 10, where it takes `(int)`; `show` brought Chrome forward on both.
- A tab hidden for a minute ran a single 1 ms timer within a second, while its `setInterval` ran once a minute, so the timer check let input through. With the check's timer swapped for a chain of nested timers, which Chrome had slowed to once a minute, a background key was let through, a read noted `timers stopped`, the next key was refused with `tab_frozen` after the 1.1 s confirmation, a wait on the tab failed the same way, and a tab opened a moment before was left alone. Bringing the tab forward with `show` cleared it.
- A tab closed outside PhoneAPI refused DevTools with 500 "No such target id", which now reads as `not_found`.
- A tab Chrome opened for `open_intent` became the caller's, and another session did not see it.

Checked on the phone on 2026-10-22, with this branch's dev build beside v0.3.0, over adb with the phone locked:

- `session_conflict` both ways, `screenHolder` as `you`, `agent`, `parent`, and `subagent`, and the cleanup and waited notes.
- A hidden tab's timers ran once a second until about a minute after it was hidden, then all stopped at once: its `setInterval`, and a 0 ms and a 1 ms timer in an isolated world, none of which fired within 5 seconds. So on Chrome 155 the 1 ms check sees what the page sees.
- About 2.5 minutes after hiding, the pause check, now in an isolated world, refused the first background key with `tab_frozen`, much sooner than v0.3.0's main-world check caught a pause (7 to 9.5 minutes). A `wait_for` on the tab ran out its 8-second timeout, since that refused key had started no timer check; waits now start their own.
- The tab Chrome had selected kept running at full rate and reported `visible` with Chrome in the background and the screen off, so only tabs behind another one in Chrome go hidden.
- Chrome 155 refuses a closed tab with 500 "No such target id", as 133 does, and PhoneAPI reports it as `not_found`.
- The direct top-task query uses `getTasks(int)` on Android 11, as on Android 10, and `show` brought Chrome forward with it.
- Two `ui_snapshot` calls at once connected UiAutomation once, with no "already registered". The first snapshot right after connecting saw 0 nodes and 0 windows; the next, 9 seconds later, saw 30 nodes.
- Restarting the dev helper closed both test tabs a live session had: the startup sweep ran on every helper connection. It now runs once per app start. The release build's helper also restarted during cleanup, when the USB connection dropped, and its app relaunched it within 30 seconds.

Still to check on the phone:
- Whether a tab opened while Chrome is in the background becomes Chrome's selected tab. On the Android 16 emulator it does: Chrome selects it as it comes forward, even over a `Page.bringToFront` sent just before, which is why `show` checks that its tab is still showing and tries again. Opening the tab did not bring Chrome forward.
- Whether the shell user can clear the clipboard on Android 11, without clearing what the phone's owner copied.
- That clipboard text and images go both ways on Android 11, checked so far on the Android 10 and 16 emulators. Also that the helper can read an image another app copied, which the emulators' Chrome would not put on the clipboard.
- That the viewer's copy and paste work from a real keyboard in Chrome, Safari, and Firefox, including the second Cmd+C that Safari and Firefox need when the page guessed text and the phone held an image.
- How many hidden tabs Chrome keeps loaded on the Moto G Power before it unloads them, to check the cap of 8.
- Whether a release build logs anything from the SDK, such as session ids.
- That binding Chrome's Custom Tabs service works on a real Android 12 or later phone, and does not change anything visible on the Android 11 test phone.
- That Chrome 155 still sends `Inspector.targetCrashed` on `Inspector.enable` for a tab it unloaded.
- Whether the first `ui_snapshot` after UiAutomation connects can come back empty, as one did, and should wait for windows.
- Whether a hidden tab's timers also stop while Chrome itself is in front, with the tab behind another, or only while Chrome is in the background.
- That `show` brings a background tab on screen, including when Chrome is behind another app, and that `shown` and `pending` match what is on screen.

## Open questions

None. The last three were settled on 2026-10-21: one idle timeout for every client, no total limit on holding the screen, and unowned tabs visible and claimable, with the screen holder able to use the foreground app's WebViews.
