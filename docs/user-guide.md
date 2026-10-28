# User guide

Bridgey connects one Android phone and one Mac on the same local network. It
has no account and no cloud service: the two devices talk to each other
directly over an encrypted connection.

## Install

Download both clients from the same release on
[GitHub Releases](https://github.com/singeol/bridgey/releases). Each release has:

- `Bridgey-Android.apk`: the signed Android application (Android 8.0 or later);
- `Bridgey-macOS.dmg` and `Bridgey-macOS.zip`: the Mac application (macOS 13 or
  later), as a disk image or a ZIP archive;
- `SHA256SUMS-Android.txt` and `SHA256SUMS-macOS.txt`: SHA-256 checksums;
- `Bridgey-Android.cdx.json` and `Bridgey-macOS.cdx.json`: software bills of
  materials (SBOMs) that list what each client was built from.

### Verify the checksums

Put the downloaded files and the checksum files in one folder, then run:

```bash
shasum -a 256 -c SHA256SUMS-Android.txt
shasum -a 256 -c SHA256SUMS-macOS.txt
```

Each file should report `OK`. Files you did not download are reported as
missing; add `--ignore-missing` to skip them. On Linux, `sha256sum -c` works the
same way. To check that a file was built by the project's release workflow
with the GitHub CLI, see
[releasing.md](releasing.md#verifying-a-download).

### Android

Open `Bridgey-Android.apk` on the phone. Android asks you to allow the browser
or file manager to install applications; allow it for this install. To update,
install a newer APK over the existing one. Trust records and settings are kept.
An update must be signed with the same key as the installed app; Android
refuses to install it otherwise.

### macOS

Open the DMG and drag Bridgey to the Applications folder, or unzip the ZIP and
move `Bridgey.app` to Applications. To update, quit Bridgey from its menu-bar
panel first, then replace the app.

Release builds currently use an ad-hoc signature, not Developer ID signing and
notarization. The first time you open Bridgey, macOS may refuse to open it. Open
System Settings → Privacy & Security, scroll to the Security section, and
choose **Open Anyway** for Bridgey, then confirm.

The published Mac build is for Apple silicon. See
[compatibility.md](compatibility.md) for tested devices and versions.

## Pair a phone and a Mac

1. Connect both devices to the same Wi-Fi network.
2. Open Bridgey on the phone and on the Mac. The Mac app lives in the menu bar.
   Allow Local Network access when macOS asks.
3. On either device, choose the other one from the nearby list: **Nearby** on
   Android, **Nearby devices** in the Mac panel.
4. Both devices show a six-digit code. Compare them. Only if they are the same,
   confirm on both: **Codes match** on Android and **Codes Match** on the Mac.
   Cancel if they differ.

After pairing, the devices reconnect automatically when both are running and
on the same network. A paired device shows "Paired · tap to reconnect" (Android)
or "Paired · click to reconnect" (Mac) while it is not connected.

Information learned through discovery, such as the advertised device name, is
not trusted until the code has been confirmed on both devices.

## Features

Each feature works only when it is enabled on both devices (see
[Settings](#settings)). A control is hidden or disabled when the other device
has the feature turned off.

### Clipboard

- **Android to Mac:** tap **Clipboard** in Bridgey, the **Send clipboard**
  action in the Bridgey connection notification, or the Quick Settings tile.
  Android does not let Bridgey read the clipboard in the background, so sending
  always needs one of these actions.
- **Mac to Android:** click **Clipboard** in the menu-bar panel or press
  Control-Option-C.

Received content replaces the clipboard on the other device. Plain text and
HTML (with a plain-text fallback) are supported, up to 32 KiB. Send larger text
as a file.

### Files

- **Android to Mac:** tap **File** in Bridgey and pick a file, or share one or
  more files to Bridgey from another app and tap **Send**.
- **Mac to Android:** click **File** in the menu-bar panel, or click **Open file
  drop window** and drag a file from Finder into that window.

Files up to 10 GiB are supported. Transfers show progress and can be cancelled
on either side. Received files go to `Download/Bridgey` on Android and to the
folder chosen in Mac Settings (`Downloads/Bridgey` by default). Receiving files
on Android needs Android 10 or later.

Recent transfers stay listed until you clear them. A failed or interrupted
outgoing transfer can be retried after the devices reconnect; a retry starts
from the beginning. On the Mac, the panel shows a file transfers button that
opens the transfer window when transfers are listed.

### Notifications

Android notifications can appear as native macOS notifications.

1. On Android, tap **Enable** on the **Notification forwarding** card and turn
   on notification access for Bridgey in the system screen that opens.
2. On the Mac, open Settings and click **Enable Mac notifications** in the
   Features section, then allow notifications.

Bridgey forwards the application name and icon, the title and the text. It
skips its own notifications, ongoing notifications (except calls),
notifications marked secret, minimum-importance notifications, group summaries
and notifications without a title or text.

- **Filters:** in Android Settings → **Notification applications**, turn off any
  application you do not want forwarded. Applications appear in the list after
  they post a notification. Filtered notifications never leave the phone.
- **Actions and replies:** when the Android notification has action buttons or
  an inline reply, they are available on the Mac notification.
- **Dismissal:** dismissing a mirrored notification on the Mac dismisses it on
  the phone, and removing it on the phone removes it from the Mac.
- **History:** Mac Settings → **Notification history** → **Keep a private local
  history**. It is off by default. When on, the Mac keeps at most 200
  notifications for seven days. Turning it off deletes the history; **Clear
  history** deletes it at any time.

### Calls

- **Start a call from the Mac:** copy a phone number and click **Call** in the
  menu-bar panel or press Control-Option-P. In applications that support macOS
  Services, select a number and choose Services → **Call with Bridgey**. To use
  Bridgey for `tel:` links in browsers, click **Use Bridgey for phone links** in
  Mac Settings once.
- **Confirmation by default:** the phone shows a notification. Tapping it opens
  the system dialer with the number, and you place the call yourself.
- **Call status and controls:** in Android Settings, under the features, turn
  on **Call status and controls** and grant the Phone permissions. The Mac can
  then start a validated call directly, and a floating call panel on the Mac
  shows incoming and active calls with **Answer**, **Decline** and **Hang Up**.
  Emergency numbers are never dialled directly by Bridgey.

Call state on the Mac comes from forwarded call notifications, so notification
forwarding must also be enabled. Call audio stays on the phone.

### Battery

The Mac panel shows the phone's battery level and charging state. Android shows
the Mac's battery level and charging state when the Mac has a battery; desktop
Macs show nothing.

### Find Device

Tap **Ring** on Android, or click **Ring** in the Mac panel, to play an alert
on the other device. Stop it from the same button, or from the **Stop**
action in the Android notification while the phone rings. Turning off Find
Device on either device stops an alert that is playing.

### Ping

Ping plays a short alert on the other device and confirms delivery, without
starting the Find Device alarm. Tap **Ping** on Android or click **Ping** in the
Mac panel. Both devices need Bridgey 0.6 or later.

### Web links

- **Mac to Android:** copy an HTTP or HTTPS link and click **Link** in the Mac
  panel (or assign a shortcut to it). Android offers **Open link** and
  **Dismiss**.
- **Android to Mac:** copy a link and tap **Send copied link to Mac** in the
  Web links card, or share a URL to Bridgey and tap **Send as link**. The Mac
  panel offers **Open in browser** and **Dismiss**.

A received link is never opened automatically. While one link is waiting, a
second one is declined rather than replacing it.

### Mac media controls

Control Music or Spotify on the Mac from the phone.

1. In Mac Settings → Features, turn on **Media controls**. It is off by default.
2. In the **Media** section, choose **Music** or **Spotify** as the player, open
   that player, and allow the Automation prompt from macOS.
3. On Android, the media card shows the player, track, playback state and
   position, with **Previous**, **Play**/**Pause**, **Next**, seeking and player
   volume.

Artwork is shown for Music only, when Music exposes it. **Pause media during
phone calls** pauses the selected player when a forwarded call rings or becomes
active; playback stays paused after the call. Bridgey does not control browser
media or the system-wide media keys.

### Quick Settings tile

On Android, edit the Quick Settings tiles and add **Bridgey clipboard**. When
the Mac is connected and Clipboard is available, tapping the tile sends the
current clipboard. Otherwise it opens Bridgey.

### macOS keyboard shortcuts

Mac Settings → **Keyboard shortcuts** assigns a letter to each of Clipboard,
Call, Link and Ping. Shortcuts use Control-Option plus the letter; **Also require
Shift** adds Shift to all four. Clipboard (C) and Call (P) are assigned by
default; Link and Ping are off. A letter can be used by one action only, and
Bridgey reports a shortcut that another application already uses.

## Settings

Open **Settings** on Android (top of the main screen) or **Settings** at the
bottom of the Mac menu-bar panel.

- **Device name:** the name shown to nearby Bridgey devices.
- **Features:** the global switches control what this device shares with every
  paired device. Changes reach a connected device immediately.
- **Per-device overrides:** under **Paired devices**, each trusted device has its
  own switches. A feature runs only when the global switch and the device
  switch are both on. "Off on <device>" means the other device has turned it
  off.
- **Android only:** notification application filters and **Call status and
  controls**.
- **Mac only:** **Launch at login**, the received files folder, phone links,
  media player, keyboard shortcuts and notification history.
- **Permissions:** each permission Bridgey can use, what it is for, whether it
  is allowed, and a button to the matching system settings.
- **Diagnostics:** exports a report without private content. See
  [diagnostics.md](diagnostics.md).
- **Reset Bridgey on this phone** or **on this Mac:** see below.

### Forget a device

Choose **Forget device** for a device under **Paired devices** in Settings on
either platform. On Android you can also tap **Forget <device>** on the main
screen while it is connected. Forgetting removes the trust record and that
device's overrides on this device and closes the connection. The other device
keeps its own record until you forget it there too. To use the pair again,
pair from the beginning and compare a new code.

### Reset Bridgey on a device

**Reset Bridgey** at the bottom of Settings deletes this device's paired
devices and their keys, all settings and per-device choices, and local history,
then shows the welcome guide again. It asks for confirmation first and lists
what is deleted and what is kept.

- On Android it also deletes the notification application list and call
  settings and clears the recent transfer list.
- On the Mac it also deletes keyboard shortcuts, media settings, the
  notification history, mirrored notifications in Notification Center and the
  transfer list.
- It keeps received files, permissions you granted, and the device's identity.
  Launch at login on the Mac stays as it is.

The other device keeps its record of the reset device. Forget it there too
before you pair again for a clean start.

### Turn off Bridgey on Android

Back closes the Bridgey screen but leaves the connection running. **Turn off
Bridgey** in the app, or **Turn off** in its notification, stops discovery and
the connection and removes the notification. Opening Bridgey again turns it
back on.

## Permissions

Bridgey asks for access only when the related feature needs it. The welcome
guide names the optional permissions, and **Permissions** in Settings shows
each one with its current state:

- **Android notifications** keep the connection, transfer progress, received
  files and Find Device controls visible.
- **Android notification access** is optional and is used only to forward a
  notification's application name, title and text to the paired Mac.
- **Android Phone access** is optional and requested only when you enable call
  status and controls: `CALL_PHONE` starts validated calls,
  `READ_PHONE_STATE` distinguishes ringing from active calls, and
  `ANSWER_PHONE_CALLS` enables Answer, Decline and Hang Up. Bridgey does not
  request contacts or call-log access.
- **macOS Local Network** access is required for discovery and connections.
- **macOS notifications** are optional and requested only when you enable
  Android notification display on the Mac.
- **macOS Automation** for Music or Spotify is requested only when you choose
  that player for media control.
- **Files** are limited to files you choose and the receive folders.

Bridgey does not request location, contacts, SMS, call-log, camera,
microphone, screen recording, Accessibility, Input Monitoring or Full Disk
Access. Denying a permission disables only the related feature.

## Troubleshooting

**The devices do not see each other.** Both must be on the same local network
with Bridgey open. Some guest or public Wi-Fi networks block traffic between
devices; try another network. Discovery works best on real devices.

**The Mac says Local Network access is off.** Click **Open Local Network
Settings** in the Bridgey panel, or open System Settings → Privacy & Security →
Local Network, and turn on Bridgey.

**The connection drops when the phone is idle.** Some Android vendors stop
background applications. See the background limits section of
[compatibility.md](compatibility.md#background-limits).

**A feature is greyed out or missing.** Check the global and per-device
switches on both devices. "Quick actions are turned off in Settings on one of
your devices" means every quick action is off on one side.

**Mac notifications do not appear.** Check that notification access is on for
Bridgey on Android, that the application is not filtered, and that Bridgey is
allowed in System Settings → Notifications on the Mac.

**Something else fails.** Export diagnostics from both devices and open an
issue with the client and OS versions. See [diagnostics.md](diagnostics.md).
