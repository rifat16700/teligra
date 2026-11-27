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

- **Phase 2 (Implemented)**:
  - `PcmRingBuffer`, `AiSessionProvider`, `LocalAudioTestProvider`, `CallAudioRecorder`, `SavedMessagesUploader`, `AiAudioBridge`.
  - Java WebRTC audio hooks (`WebRtcAudioManager`, `WebRtcAudioTrack`, `WebRtcAudioRecord`) enforcing Java audio path, capturing caller PCM, injecting AI/test PCM, supporting `anonymousListenIn` live monitoring, and auto-recording WAV calls to Saved Messages.

- **Phase 3 (Implemented)**:
  - `PcmResampler` (channel downmix + 48 kHz <-> 16 kHz / 24 kHz resampling).
  - `GeminiLiveSessionProvider` using pure WebSocket (`wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.<ver>.GenerativeService.BidiGenerateContent`) with local BYOK API key, 6 selectable model presets + editable model ID, Owner Data + mid-call transcript system prompt, and barge-in buffer clearing.

- **Phase 4 (Implemented)**:
  - `BackgroundTranscriptSession` buffering full-call transcripts during normal owner-handled calls so mid-call handover to AI passes full prior conversation context.
  - Live call notification action buttons (`Take Over`, `Handover to AI`, `Listen Live / Mute Listen`, `Rec: ON/OFF`) wired through `VoIPActionsReceiver` & `AiCallActionBus`.
  - Gemini `end_call` tool declaration for automatic polite hang-up.
  - `Place AI Outgoing Call` launcher in `AiCallSettingsActivity` (`VoIPHelper.startCall` + outgoing goal prompt).
  - Saved Messages recording caption includes transcript preview.

- **Phase 5 (Implemented)**:
  - **Audio Fixes**: `AiMonitorPlayer` (standalone `AudioTrack` outside WebRTC so AEC never cancels AI voice), 140ms jitter pre-buffer in `PcmRingBuffer`, stereo-aware `PcmResampler.monoToChannels`.
  - **Dual-Model Multitasking (`DualModelOrchestrator`)**: `gemini-2.5-flash-native-audio-preview-12-2025` handles continuous speech listening, server end-of-turn detection, live transcription & phase summarization; finalized text feeds user's selected Voice model (e.g. `gemini-3.8-live`).
  - **Multi-Handover Memory & Post-Call Report**: `CallContextMemory` + `PostCallSummaryGenerator` merges all AI & Owner phases into a detailed post-call explanation and serial-tagged `#nc-call-r` Saved Messages upload.
  - **Chat-Style Live Calling UI (`AiCallOverlayView`) & Saved Messages Archive Button**:
    - `AiCallOverlayView` covers `VoIPFragment` natively with animated dark-glass bubbles, live timer, Hide/Show pill, Take Over / Return to AI, Anonymous Listen, and End Call.
    - **Instant-Reply Dual Pipeline**: `gemini-3.5-transcribe-live` (`TEXT` STT + Fast 350ms VAD + Noise Gate + 450ms flush) feeds finalized caller text directly into pre-connected selected Voice model (e.g. `gemini-3.8-live`) via `clientContent(turnComplete=true)` for ~1–1.5s replies, plus ring-time WebSocket pre-warming.
    - Dedicated top-bar button inside Saved Messages (`ChatActivity`) opening `AiCallHistoryActivity` + `AiCallDetailActivity` (`#nc-call-r` filter).
    - Fast GitHub Actions caching for C++ `.cxx` & Android NDK/CMake in `.github/workflows/build-apk.yml`.
