# Bridgey 0.6 complete acceptance test

Test both clients from the same 0.6 release candidate. This is one complete
feature candidate, not a sequence of partial alpha builds. A stable 0.6.0 tag
should follow device acceptance, not precede it.

## Install and setup

1. Install the signed APK over 0.5.0 and replace the Mac app, quitting its old
   process first. Trust and settings should survive; do not delete app data.
2. Keep both devices on the same LAN. Confirm automatic secure reconnect.
3. On Mac, Settings → Features: enable **Media controls**, then Media: choose
   **Music** or **Spotify**. Open that player and accept its macOS Automation
   prompt. Media is off by default and does not request Accessibility access.
4. On Android, expand Quick Settings, edit tiles, and add **Bridgey clipboard**.
5. On Mac, Settings → Keyboard shortcuts: keep the default Control+Option+C
   (clipboard) and Control+Option+P (call), or choose different letter keys.
   Assign Link/Ping if wanted. The optional Shift modifier applies to all four.

## New features

| Check | Expected result |
| --- | --- |
| Start a track in Music; repeat in Spotify | Android shows player, title, artist, playback state, position and volume; periodic updates arrive within about 10 seconds |
| Play/pause, next/previous, seek, player volume | Only the selected Mac player changes; a command is acknowledged, rejected, or times out within 8 seconds, never stuck on Sending |
| Music artwork | A small cover appears if Music exposes artwork; absent artwork does not break controls. Spotify covers/browser-wide media are not implemented |
| Quit player / deny Automation | Useful instructions appear; no false success and no automatic player launch |
| Enable Pause media during phone calls | Incoming ringing or an active call pauses the selected player, including when a metadata refresh is in progress |
| End call | Call panel clears; media remains paused until explicitly resumed |
| Copy an HTTP(S) URL and use Link on Mac | Android offers Open link / Dismiss; receiving never auto-opens the browser |
| Copy URL on Android, Send copied link to Mac | Mac panel offers Open in browser / Dismiss |
| Android Share with a URL | Send as link offers confirmed browser handoff; ordinary Send still shares clipboard text |
| Send another URL before dismissing the first | It is explicitly declined, not silently substituted |
| Ping both ways | A short presence alert and acknowledgement, independent of Find Device |
| MacBook on battery / connect charger | Android shows Mac percentage/charging. Desktop Macs without a battery show no invented percentage |
| Quick Settings tile | Clipboard is sent through the short foreground capture activity required by Android privacy rules; an unavailable connection opens Bridgey instead |
| Change shortcuts / assign duplicate / restart | Only the assigned action runs; duplicates/conflicts are reported; selections persist |

## Policy, lifecycle and regression

- Disable Links, Media, Clipboard, Ping or Battery on either device. Related
  controls/data must disappear or disable on the other device. No request
  should keep a permanent Sending status. Re-enable and repeat.
- Disable media while a command is pending; disconnect while sending a link.
  Stale replies must not revive old state after reconnect.
- Restart each application, toggle Wi-Fi, lock/unlock Android, background the
  app, and use Turn off Bridgey. Check reconnect and tile state.
- Test clipboard and a file in both directions, simultaneous transfers and
  cancellation on both ends. Share several photos: one Android Recents task.
- Test one notification action/reply, dismissal sync and application filtering.
- Test incoming call from the home screen, from another app's heads-up popup,
  and after opening that popup full-screen. Then test outgoing calls, Answer,
  Decline and Hang Up. Confirm no phantom Incoming/Call in progress afterward.

Pause-on-call depends on forwarded call state: notification forwarding and the
optional Android Phone permissions must be enabled. It does not carry call
audio to Mac, read SMS history, or replace the dialer.

## Device matrix and release gate

| Environment | Status for this candidate |
| --- | --- |
| Kotlin/JVM protocol, policy, URL and manifest tests; Android lint/build | Automated; inspect the candidate commit's Build workflow |
| Swift protocol, policy, URL, media allowlist/artwork and replay tests | Automated on macOS CI with Xcode |
| Samsung S25+ / user's MacBook | Pending full candidate acceptance; earlier 0.5 results do not certify new media features |
| Pixel/AOSP | Pending physical-device call/notification and Quick Settings checks |
| Other OEM (e.g. Xiaomi/HyperOS) | Pending; do not claim multi-OEM certification |

Before stable 0.6.0: green Build/Security, no unresolved high-severity findings,
signed artifacts, full two-device acceptance, and explicit recording of any
unverified OEM/platform limitation. Do not mark the multi-OEM roadmap item
complete without those results.

If something fails, record client versions, OS versions, player, action and
visible error; export diagnostics as a file. Do not paste private clipboard,
notification, call content, signing keys or passwords into a public issue.
