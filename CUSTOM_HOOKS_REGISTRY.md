# Custom Hooks Registry

**Purpose**: Track every hook location for disaster recovery after upstream rebase.

**Last Updated**: 2026-11-26
**Total Hooks**: 10

---

## Active Hooks

| # | Upstream File | Class | Method | Hook Call | Added Commit | Status |
|---|---------------|-------|--------|-----------|--------------|--------|
| 1 | Telegram/TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java | SettingsActivity | fillItems | `org.telegram.custom.aicall.AiCallHooks.onSettingsFillItems(items);` | feature/ai-auto-answer | ✅ Active |
| 2 | Telegram/TMessagesProj/src/main/java/org/telegram/ui/SettingsActivity.java | SettingsActivity | onClick | `if (org.telegram.custom.aicall.AiCallHooks.onSettingsItemClick(this, item)) return;` | feature/ai-auto-answer | ✅ Active |
| 3 | Telegram/TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPPreNotificationService.java | VoIPPreNotificationService | show | `org.telegram.custom.aicall.AiCallHooks.onPreNotificationShow(context, account, user_id, call.id, video);` | feature/ai-auto-answer | ✅ Active |
| 4 | Telegram/TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPPreNotificationService.java | VoIPPreNotificationService | dismiss | `org.telegram.custom.aicall.AiCallHooks.onPreNotificationDismiss(answered);` | feature/ai-auto-answer | ✅ Active |
| 5 | Telegram/TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java | VoIPService | startRinging | `org.telegram.custom.aicall.AiCallHooks.onVoipStartRinging(this);` | feature/ai-auto-answer | ✅ Active |
| 6 | Telegram/TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java | VoIPService | acceptIncomingCall | `org.telegram.custom.aicall.AiCallHooks.onVoipAcceptIncomingCall(this);` | feature/ai-auto-answer | ✅ Active |
| 7 | Telegram/TMessagesProj/src/main/java/org/telegram/messenger/voip/VoIPService.java | VoIPService | onDestroy | `org.telegram.custom.aicall.AiCallHooks.onVoipDestroy(this);` | feature/ai-auto-answer | ✅ Active |
| 8 | Telegram/TMessagesProj/src/main/java/org/webrtc/voiceengine/WebRtcAudioManager.java | WebRtcAudioManager | isLowLatencyInputSupported | `if (org.telegram.custom.aicall.AiCallHooks.shouldForceJavaAudio()) return false;` | feature/ai-auto-answer | ✅ Active |
| 9 | Telegram/TMessagesProj/src/main/java/org/webrtc/voiceengine/WebRtcAudioTrack.java | WebRtcAudioTrack.AudioTrackThread | run | `org.telegram.custom.aicall.AiCallHooks.onWebRtcPlayout(byteBuffer, sizeInBytes, sampleRate, audioTrack.getChannelCount());` | feature/ai-auto-answer | ✅ Active |
| 10 | Telegram/TMessagesProj/src/main/java/org/webrtc/voiceengine/WebRtcAudioRecord.java | WebRtcAudioRecord.AudioRecordThread | run | `org.telegram.custom.aicall.AiCallHooks.onWebRtcRecord(byteBuffer, bytesRead, audioRecord.getSampleRate(), audioRecord.getChannelCount());` | feature/ai-auto-answer | ✅ Active |

---

## Template for Adding New Hooks

```markdown
| N | Telegram/TMessagesProj/src/main/java/org/telegram/messenger/XXXActivity.java | XXXActivity | onCreate | CustomHooks.onXXXActivityCreate(this, savedInstanceState); | <commit-hash> | ✅ Active |
```

---

## Verification Checklist (Run After Every Rebase)

- [ ] All hook files exist in `org/telegram/custom/`
- [ ] `CustomHooks.java` compiles without errors
- [ ] Each registered hook location still has `// CUSTOM_HOOK_START/END` comments
- [ ] Hook method signatures match `CustomHooks.java` declarations
- [ ] Run `./verify_hooks.sh` (see below) - count matches registry

---

## Quick Verification Script

Save as `Telegram/verify_hooks.sh`:
```bash
#!/bin/bash
# Usage: ./verify_hooks.sh

EXPECTED=0  # Update this number to match registry count
ACTUAL=$(grep -r "CUSTOM_HOOK_START" Telegram/TMessagesProj/src/main/java/org/telegram/messenger/ --include="*.java" 2>/dev/null | wc -l)

echo "Expected hooks: $EXPECTED"
echo "Found hooks:    $ACTUAL"

if [ "$EXPECTED" -eq "$ACTUAL" ]; then
    echo "✅ All hooks present"
    exit 0
else
    echo "❌ Hook count mismatch! Check registry."
    exit 1
fi
```

---

## Recovery Procedure

If rebase removes hooks:

1. **Check git history**: `git log --oneline --grep="CUSTOM_HOOK" -20`
2. **Restore from commit**: `git show <hash>:<file> > /tmp/restored.java`
3. **Copy hook lines** back to current file
4. **Update registry** with new commit hashes
5. **Run verification**: `./verify_hooks.sh`