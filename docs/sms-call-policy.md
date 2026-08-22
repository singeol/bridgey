# SMS and call integration policy

Reviewed on 2026-08-17 against the current Android and Google Play guidance.

## Bridgey v0.5 decision

Bridgey v0.5 does not declare `READ_SMS`, `SEND_SMS`, `READ_CALL_LOG`,
`WRITE_CALL_LOG`, or `PROCESS_OUTGOING_CALLS`.

- Incoming message content can already be mirrored through the explicitly
  enabled Android notification-listener service.
- A reply is available only when the source notification provides a bounded
  free-form `RemoteInput`; Bridgey returns that reply to the retained action
  token and cannot address an arbitrary recipient.
- `CATEGORY_CALL` notifications are forwarded even when marked ongoing. When
  the optional call integration is enabled, Bridgey uses `READ_PHONE_STATE`
  only to distinguish ringing, active, and ended states and uses
  `ANSWER_PHONE_CALLS` for Answer, Decline, and Hang Up. It does not request
  contacts or call-log access. Without this opt-in, controls remain limited to
  the actions exposed by the phone application and may vary by phone/OEM.
- Bridgey does not read the SMS database or call log and does not make itself
  the default SMS or Phone application.
- A Mac may send a validated phone number to Android. The default path uses
  `ACTION_DIAL` and requires confirmation on the phone. Users may separately
  opt in to call status and controls, which requests `CALL_PHONE`,
  `READ_PHONE_STATE`, and `ANSWER_PHONE_CALLS`. Bridgey blocks direct handling
  of platform-recognized emergency numbers.

This keeps the feature useful without adding broad access to unrelated message
history or telephony metadata.

## Future full conversation access

Google Play treats SMS and Call Log permissions as restricted. Its policy lists
connected-device companion apps and cross-device SMS/call synchronization as
possible exception use cases, but access remains subject to a Permissions
Declaration Form, review, prominent disclosure, consent, and the User Data
policy. Default-handler requirements otherwise apply.

Before Bridgey adds full conversation browsing or arbitrary SMS composition, a
separate release must include a privacy policy, permission declaration, explicit
opt-in UX, retention controls, abuse review, and approval for its distribution
channel. The permission-free v0.5 implementation remains the fallback.

Primary references:

- [Google Play: Use of SMS or Call Log permission groups](https://support.google.com/googleplay/android-developer/answer/10208820?hl=en)
- [Android: Permissions used only in default handlers](https://developer.android.com/guide/topics/permissions/default-handlers)
- [Android: CallStyle notifications](https://developer.android.com/develop/ui/compose/notifications/call-style)
