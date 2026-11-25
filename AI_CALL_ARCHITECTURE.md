# AI Call Architecture & Roadmap

This document tracks the architecture, user requirements, and phased implementation plan for the **AI Auto-Answer & Composable Voice Call** system inside `org.telegram.custom.aicall`.

---

## Core Design Principles
1. **Zero C++/JNI Changes**: Everything runs in the Java layer (`org.telegram.custom.aicall` + minimal `// CUSTOM_HOOK_START` ... `// CUSTOM_HOOK_END` hooks in upstream Java files).
2. **Composable & Non-Blocking State**: All features operate as independent flags/commands on `AiCallSessionState` and `AiCallActionBus` so enabling or triggering any action never disables or conflicts with any other action.
3. **Reversible Bi-Directional Control**: Owner and AI can hand off the call back and forth at any point during a call.

---

## User Requirements & Capabilities

1. **Auto-Answer Ring Delay (0s – 60s)**
   - Configurable in Settings (`AiCallSettingsActivity`) from `0` seconds (instant) to `60` seconds (1 minute).
   - Works whether the app is in foreground (`VoIPService`) or background (`VoIPPreNotificationService`).
   - Cancels cleanly if caller hangs up or owner answers manually before timer expires.

2. **Owner Data / Knowledge Base**
   - Saved in Settings (`AiCallConfig`) and passed into `AiSessionProvider.startSession(ownerData, callerInfo, ...)` so the AI responds accurately on behalf of the owner.

3. **Anonymous Live Monitoring (Listen-In)**
   - Owner can listen live to an ongoing AI-handled call without the caller hearing the owner's microphone (`anonymousListenIn` flag).
   - Can be toggled on/off anytime mid-call without interrupting AI or recording.

4. **Default Background Audio Recording -> Saved Messages**
   - Call audio recording is enabled by default (`autoRecordToSavedMessages = true`).
   - Background action (`AiCallActionBus.ACTION_SEND_RECORDING_TO_SAVED_MESSAGES`) automatically sends the recorded audio (and summary/transcript if enabled) to the owner's **Saved Messages** when the call ends.

5. **Reversible Bi-Directional Handover (`AI ↔ Owner`)**
   - **AI -> Owner (`Take Over`)**: Owner can take over an AI-answered call anytime; AI pauses/stops speaking and owner's mic/speaker become active.
   - **Owner -> AI (`Handover to AI`)**: During any normal call (incoming or outgoing), the owner can hand the call over to AI at any time, and take it back again if needed.

6. **Background Full-Call Transcript Buffering**
   - Even during a normal owner-handled call, conversation transcript is buffered in the background (`transcriptBuffer` in `AiCallSessionState`) when enabled.
   - If the owner hands the call over to AI mid-call, the buffered transcript + Owner Data are passed to AI so it has full context.

7. **AI Outgoing Calls**
   - Supports `CallDirection.OUTGOING` so the owner can initiate an outgoing call handled by AI (`ACTION_START_AI_OUTGOING_CALL`).

---

## Phased Rollout

- **Phase 1 (Implemented)**:
  - `feature/ai-auto-answer` branch, `AiCallConfig`, `AiCallSessionState`, `AiCallActionBus`, `AiCallController`, `AiCallSettingsActivity`, `AiCallHooks`.
  - Settings menu entry, 0–60s ring delay, Owner Data editor, feature toggles, and incoming call auto-answer hooks in `VoIPPreNotificationService` and `VoIPService`.

- **Phase 2 (Next)**:
  - Java WebRTC audio bridge (`AiAudioBridge`, `PcmRingBuffer`), `WebRtcAudioManager` Java audio path enforcement, `WebRtcAudioTrack` capture + selective speaker mute (supporting `anonymousListenIn`), `WebRtcAudioRecord` PCM injection, local audio loopback/WAV test, and default call audio recorder saving to Saved Messages.

- **Phase 3**:
  - `PcmResampler` (48 kHz <-> 16 kHz / 24 kHz) and live WebSocket `AiSessionProvider` implementation connecting to the AI Voice API / backend proxy with Owner Data + mid-call transcript context.

- **Phase 4**:
  - Live in-call & notification controls (`Take Over`, `Handover to AI`, `Anonymous Listen-In`, `Start AI Outgoing Call`), barge-in handling, AI `end_call` hangup, and Saved Messages summary + recording upload.
