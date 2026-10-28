# SMS conversations: design note for a separately consented flavor

Status: design note for the v0.9 roadmap item "Evaluate full SMS conversations,
new-message sending, and optional contact names as a separately consented plugin
and distribution flavor". Nothing here is implemented. The default build keeps
the v0.5 decision in [sms-call-policy.md](sms-call-policy.md): no SMS, contacts
or call-log permission, and notification-based replies as the permission-light
path.

## Goals and non-goals

Goals, only for people who choose a separate build and then opt in:

- browse SMS conversations from the paired Mac, newest first, with incremental
  updates while connected;
- send a new SMS, or answer a conversation, from the Mac to one recipient;
- optionally show contact names instead of numbers, with its own consent;
- keep notification replies working exactly as today for everyone else.

Non-goals:

- becoming the default SMS app, writing to the SMS provider, or marking
  messages read through it (Android allows provider writes only for the default
  SMS app);
- RCS chats (they are not in the Android SMS provider), group MMS sending, or
  sending pictures;
- call-log access, background upload, or any server or cloud copy;
- shipping any of this in the default build or the Google Play listing.

## Distribution: a separate Android flavor

A Gradle product flavor keeps the permissions out of the default build at the
manifest level, not only behind a switch:

| | `standard` (today's build) | `messages` (opt-in build) |
| --- | --- | --- |
| Permissions added | none | `READ_SMS`, `SEND_SMS`, `READ_CONTACTS` (asked separately and optional) |
| Plugin code | absent | `src/messages/` source set |
| Advertises `sms` | never | only while the person turned it on for that Mac |
| Distribution | every channel, including any store listing | GitHub releases only, unless a store approves the permission declaration below |

Proposed details for review:

- A distinct `applicationIdSuffix` (for example `.messages`) so the default app
  can never gain SMS access through an update, the system shows the extra
  permissions on a separately installed app, and switching back means
  uninstalling it. The cost is pairing that build separately.
- `ManifestPermissionsTest` stays exact for `standard`. A second test for
  `messages` checks the permission delta is exactly the three above, and that the
  merged manifest declares none of `RECEIVE_SMS`, `RECEIVE_MMS`, `WRITE_SMS`,
  call-log permissions, or the default-handler components (`SMS_DELIVER`,
  `WAP_PUSH_DELIVER`, `RESPOND_VIA_MESSAGE`, `SENDTO`).
- CI builds and tests both flavors; release notes and checksums list them
  separately with a plain explanation of the difference.
- `READ_PHONE_STATE` is already declared for calls; the flavor would also use it
  only to list SIMs on dual-SIM phones, so the Mac can choose one.

## Consent and disclosure

- The plugin is off by default in the `messages` build too, with the normal
  global and per-device switches and a new `sms` feature key that is disabled for
  legacy peers. The Mac has its own switch, also off by default, and shows
  nothing unless the phone advertises `sms`.
- Before any runtime request, a full-screen disclosure on the phone says what is
  read (messages, numbers, and names if allowed), where it goes (only to the
  paired Mac over the encrypted session), what the Mac keeps and for how long,
  and how to delete it. Declining leaves the feature off.
- Contacts are a separate, optional step. Without them the Mac shows numbers.
- Turning the feature off, revoking a permission, or forgetting the Mac stops
  all `sms.*` traffic at once and tells the Mac to delete what it kept.

## Data handling and retention

- The phone stores nothing extra. It reads the provider when the Mac asks.
- The Mac keeps a bounded cache per phone (proposed: 200 conversations and the
  newest 200 messages of each) in one file with owner-only permissions, or in
  memory only if "Keep conversations on this Mac" is off. MMS images stay in
  memory.
- Retention controls on the Mac: the keep switch, "Delete conversations now",
  and automatic deletion when the feature is turned off on either device, when
  the phone is forgotten, and from a full reset.
- Message bodies, numbers and names are never logged or included in
  diagnostics on either platform; diagnostics keep only counts and states.

## Protocol outline

To be specified in `docs/protocol.md` in the implementing change, before code.
Every message is AES-GCM encrypted with a `messageId` in the replay window and
`version: 1`; requests carry a `requestId` that the answer echoes.

| Message | Direction | Purpose |
| --- | --- | --- |
| `sms.status` | phone to Mac | permissions granted, SIM list, current sync cursor |
| `sms.changed` | phone to Mac | the provider changed; at most one every 2 seconds |
| `sms.threads` / `sms.threads_page` | Mac asks, phone answers | conversation list, newest first, up to 50 per page |
| `sms.messages` / `sms.messages_page` | Mac asks, phone answers | one conversation, newest first, up to 100 per page |
| `sms.sync` / `sms.sync_page` | Mac asks, phone answers | everything newer than the Mac's cursor, or `reset` when the cursor is unusable |
| `sms.send` / `sms.send_status` | Mac asks, phone answers | one SMS to one recipient; `sending`, `sent`, `delivered`, `failed`, `refused` |
| `sms.contacts` / `sms.contacts_result` | Mac asks, phone answers | up to 20 matches for a new message, only with contacts consent |

Bounds: pages stay well under the 65,536-byte frame limit (about 40 KiB of
plaintext); bodies over 4,000 UTF-16 units are cut and marked. The sync cursor is
the newest provider `_id` of the SMS and MMS tables, so a Mac with no cache
starts from now instead of copying the whole history, and older messages load
only when scrolled to.

Sending limits: one recipient per message (group conversations are answered on
the phone), emergency numbers refused, at most one send per second and 30 in 10
minutes per Mac, and Android's own premium-number and volume confirmations still
appear on the phone.

## Replies stay permission-light

Notification replies keep working in both builds: the Mac returns text to the
messaging app's own `RemoteInput`, which preserves chat features such as RCS. In
the `messages` build, a reply could fall back to `SmsManager` only when the
notification offers no reply action, and only to the conversation's single
participant. The default build never sends SMS itself.

## Store policy and privacy policy

Google Play restricts the SMS permission group to the default SMS handler, with
listed exceptions that include "Cross-device synchronization or transfer of SMS
or calls" and "Connected device companion apps". An exception needs a
Permissions Declaration Form and review, a privacy policy, and a prominent
in-app disclosure with consent under the User Data policy; contacts access falls
under the same disclosure rules. Until such a declaration is approved, the
`messages` build would ship only outside the store, and the default build stays
the only store build.

Before the first `messages` release, the project would need:

- a published privacy policy covering exactly the data above, with no third
  parties;
- the in-app disclosure text, reviewed alongside the permission rationale;
- an abuse review of sending (rate limits, emergency and premium numbers);
- a security review of the new message types, including malformed-input tests.

## Known limits worth stating up front

- Marking messages read from the Mac is not possible through the provider. It
  could fire the messaging app's own "Mark as read" notification action
  (`SEMANTIC_ACTION_MARK_AS_READ`) while that notification exists, which would
  not cover older unread conversations.
- On Android 15 and later, notifications that look like they carry a one-time
  code reach an unlinked notification listener with text and actions removed. A
  companion device association can exempt Bridgey, which is its own consent
  step.
- Dual-SIM sending uses the SIM the conversation last used unless the person
  picks another on the Mac.

## Suggested order of work

1. Flavor scaffolding and the two manifest tests, with no plugin code.
2. The protocol section in `docs/protocol.md`.
3. The Android plugin in the `messages` source set.
4. The Mac conversation window, shown only for phones that advertise `sms`.

Each step stays reviewable on its own, and the default build is unchanged by all
of them.
