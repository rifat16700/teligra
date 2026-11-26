package org.telegram.custom.aicall;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.core.content.ContextCompat;

import org.telegram.custom.utils.CustomLogger;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.voip.VoIPPreNotificationService;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.TLRPC;
import org.webrtc.voiceengine.WebRtcAudioManager;

import java.io.File;

/**
 * Coordinates AI call lifecycle, 0-60s auto-answer scheduling,
 * composable call capabilities, and reversible AI <-> Owner handover.
 */
public final class AiCallController implements AiCallActionBus.CommandListener {
    private static final String TAG = "AiCallController";

    private static AiCallController instance;
    private final AiCallSessionState state = new AiCallSessionState();
    private final SavedMessagesUploader savedMessagesUploader = new SavedMessagesUploader();
    private Runnable pendingAutoAnswerRunnable;
    private long scheduledCallId = 0L;
    private volatile long pendingAiOutgoingUserId = 0L;
    private volatile String pendingAiOutgoingGoal = "";

    private AiCallController() {
        AiCallActionBus.registerListener(this);
        AiCallActionBus.registerListener(savedMessagesUploader);
    }

    public boolean shouldForceJavaAudio() {
        AiCallConfig config = AiCallConfig.getInstance();
        return config.isAutoAnswerEnabled()
                || config.isAutoRecordToSavedMessages()
                || state.getMode() != AiCallSessionState.Mode.IDLE;
    }

    public static synchronized AiCallController getInstance() {
        if (instance == null) {
            instance = new AiCallController();
        }
        return instance;
    }

    public AiCallSessionState getState() {
        return state;
    }

    public synchronized void onPreNotificationIncoming(Context context, int account, long userId, long callId, boolean video) {
        WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(shouldForceJavaAudio());
        AiCallConfig config = AiCallConfig.getInstance();
        if (!config.isAutoAnswerEnabled()) {
            CustomLogger.d(TAG, "Auto-answer disabled; skipping pre-notification timer.");
            return;
        }
        if (callId != 0L && scheduledCallId == callId && pendingAutoAnswerRunnable != null) {
            return;
        }
        cancelPendingAutoAnswer();

        state.setDirection(AiCallSessionState.CallDirection.INCOMING);
        state.setCallId(callId);
        state.setAccount(account);
        state.setPeerUserId(userId);
        state.setVideo(video);
        state.setMode(AiCallSessionState.Mode.RINGING_TIMER);

        scheduleAutoAnswer(context != null ? context.getApplicationContext() : ApplicationLoader.applicationContext, callId, false);
    }

    public synchronized void onPreNotificationDismissed(boolean answered) {
        cancelPendingAutoAnswer();
        if (!answered && state.getMode() == AiCallSessionState.Mode.RINGING_TIMER) {
            state.reset();
        }
    }

    public synchronized void onVoipServiceRinging(VoIPService service) {
        if (service == null) return;
        WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(shouldForceJavaAudio());
        AiCallConfig config = AiCallConfig.getInstance();
        if (!config.isAutoAnswerEnabled()) {
            return;
        }

        long callId = service.getCallID();
        int account = service.getAccount();
        TLRPC.User user = service.getUser();
        long userId = user != null ? user.id : 0L;

        if (callId != 0L && scheduledCallId == callId && pendingAutoAnswerRunnable != null) {
            return;
        }
        if (state.getMode() == AiCallSessionState.Mode.AI_HANDLING) {
            return;
        }

        cancelPendingAutoAnswer();
        state.setDirection(AiCallSessionState.CallDirection.INCOMING);
        state.setCallId(callId);
        state.setAccount(account);
        state.setPeerUserId(userId);
        state.setMode(AiCallSessionState.Mode.RINGING_TIMER);

        scheduleAutoAnswer(service.getApplicationContext(), callId, true);
    }

    public synchronized void onVoipServiceCallAccepted(VoIPService service) {
        cancelPendingAutoAnswer();
        AiCallConfig config = AiCallConfig.getInstance();

        String peerName = "Caller";
        if (service != null) {
            long callId = service.getCallID();
            if (callId != 0L) state.setCallId(callId);
            state.setAccount(service.getAccount());
            TLRPC.User user = service.getUser();
            if (user != null) {
                state.setPeerUserId(user.id);
                peerName = ContactsController.formatName(user.first_name, user.last_name);
            }
        }
        if (state.getStartedAtMs() == 0L) {
            state.setStartedAtMs(System.currentTimeMillis());
        }

        // Enable composable background features regardless of who answered
        state.setRecordingActive(config.isAutoRecordToSavedMessages());
        state.setTranscriptBuffering(config.isBackgroundTranscriptEnabled());

        if (state.getMode() != AiCallSessionState.Mode.AI_HANDLING) {
            state.setMode(AiCallSessionState.Mode.OWNER_HANDLING);
            state.setAiActive(false);
            state.setAnonymousListenIn(false);
        } else {
            state.setAiActive(true);
            state.setAnonymousListenIn(config.isAnonymousListenDefault());
        }

        WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(shouldForceJavaAudio());
        AiAudioBridge.getInstance().onCallStarted(state, config.getOwnerData(), peerName);
        refreshVoipNotification();

        CustomLogger.i(TAG, "Call accepted | mode=" + state.getMode()
                + " | record=" + state.isRecordingActive()
                + " | transcript=" + state.isTranscriptBuffering()
                + " | listenIn=" + state.isAnonymousListenIn());
    }

    public synchronized void prepareAiOutgoingCall(long userId, String goal) {
        this.pendingAiOutgoingUserId = userId;
        this.pendingAiOutgoingGoal = goal == null ? "" : goal.trim();
        WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true);
        CustomLogger.i(TAG, "Prepared AI outgoing call for userId=" + userId);
    }

    public synchronized void onVoipServiceOutgoingCall(VoIPService service) {
        cancelPendingAutoAnswer();
        AiCallConfig config = AiCallConfig.getInstance();
        long userId = 0L;
        String peerName = "Caller";
        if (service != null) {
            long callId = service.getCallID();
            if (callId != 0L) state.setCallId(callId);
            state.setAccount(service.getAccount());
            TLRPC.User user = service.getUser();
            if (user != null) {
                userId = user.id;
                state.setPeerUserId(userId);
                peerName = ContactsController.formatName(user.first_name, user.last_name);
            }
        }
        state.setDirection(AiCallSessionState.CallDirection.OUTGOING);
        state.setStartedAtMs(System.currentTimeMillis());
        state.setRecordingActive(config.isAutoRecordToSavedMessages());
        state.setTranscriptBuffering(config.isBackgroundTranscriptEnabled());

        String promptData = config.getOwnerData();
        if (pendingAiOutgoingUserId != 0L && (userId == 0L || userId == pendingAiOutgoingUserId)) {
            state.setMode(AiCallSessionState.Mode.AI_HANDLING);
            state.setAiActive(true);
            state.setAnonymousListenIn(config.isAnonymousListenDefault());
            if (!pendingAiOutgoingGoal.isEmpty()) {
                promptData = promptData + "\n\n[Outgoing Call Goal]\n" + pendingAiOutgoingGoal;
            }
            pendingAiOutgoingUserId = 0L;
            pendingAiOutgoingGoal = "";
        } else {
            state.setMode(AiCallSessionState.Mode.OWNER_HANDLING);
            state.setAiActive(false);
            state.setAnonymousListenIn(false);
        }

        WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(shouldForceJavaAudio());
        AiAudioBridge.getInstance().onCallStarted(state, promptData, peerName);
        refreshVoipNotification();
    }

    public synchronized void onVoipServiceDestroyed(VoIPService service) {
        cancelPendingAutoAnswer();
        AiAudioBridge bridge = AiAudioBridge.getInstance();
        CallContextMemory memory = bridge.getMemory();
        File recordedFile = bridge.onCallEnded();
        long durationSec = bridge.getLastRecordedDurationSeconds();
        if (durationSec <= 0L && state.getStartedAtMs() > 0L) {
            durationSec = Math.max(1L, (System.currentTimeMillis() - state.getStartedAtMs()) / 1000L);
        }

        if (state.getMode() != AiCallSessionState.Mode.IDLE && state.getStartedAtMs() > 0L) {
            AiCallHistoryStore.CallRecord record = new AiCallHistoryStore.CallRecord();
            record.serialId = memory.getSerialId();
            record.peerUserId = state.getPeerUserId();
            record.peerName = memory.getPeerName();
            record.direction = state.getDirection().name();
            record.startedAtMs = state.getStartedAtMs();
            record.durationSec = durationSec;
            record.handoverCount = memory.getHandoverCount();
            record.audioFilePath = (recordedFile != null && recordedFile.exists()) ? recordedFile.getAbsolutePath() : "";
            record.messages = memory.getEntriesSnapshot();

            PostCallSummaryGenerator.finalizeAndDispatch(
                    record,
                    memory.getAiPhaseSummaries(),
                    memory.getOwnerPhaseSummaries(),
                    state.getAccount(),
                    state.getCallId(),
                    state.isRecordingActive()
            );
        }
        state.reset();
        WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(shouldForceJavaAudio());
    }

    public synchronized void takeOverByOwner() {
        if (state.getMode() == AiCallSessionState.Mode.IDLE) return;
        state.setMode(AiCallSessionState.Mode.OWNER_HANDLING);
        state.setAiActive(false);
        state.setAnonymousListenIn(false);
        AiAudioBridge.getInstance().onTakeOverByOwner(state);
        refreshVoipNotification();
        CustomLogger.i(TAG, "Owner took over callId=" + state.getCallId());
    }

    public synchronized void handoverToAi() {
        if (state.getMode() == AiCallSessionState.Mode.IDLE) return;
        state.setMode(AiCallSessionState.Mode.AI_HANDLING);
        state.setAiActive(true);
        state.setAnonymousListenIn(AiCallConfig.getInstance().isAnonymousListenDefault());
        AiAudioBridge.getInstance().onHandoverToAi(state, AiCallConfig.getInstance().getOwnerData());
        refreshVoipNotification();
        CustomLogger.i(TAG, "Call handed over to AI | callId=" + state.getCallId()
                + " | bufferedTranscriptLines=" + state.getTranscriptSnapshot().size());
    }

    public synchronized void setAnonymousListenIn(boolean enabled) {
        state.setAnonymousListenIn(enabled);
        AiAudioBridge.getInstance().updateAnonymousListenState(state);
        refreshVoipNotification();
        CustomLogger.i(TAG, "Anonymous listen-in set to " + enabled);
    }

    public synchronized void setRecordingActive(boolean enabled) {
        state.setRecordingActive(enabled);
        refreshVoipNotification();
        CustomLogger.i(TAG, "Call recording active set to " + enabled);
    }

    private void refreshVoipNotification() {
        AndroidUtilities.runOnUIThread(() -> {
            VoIPService s = VoIPService.getSharedInstance();
            if (s != null) {
                s.refreshCustomNotification();
            }
        });
    }

    private void scheduleAutoAnswer(Context context, long callId, boolean fromVoipService) {
        int delaySec = AiCallConfig.getInstance().getRingDelaySeconds();
        scheduledCallId = callId;

        pendingAutoAnswerRunnable = () -> triggerAutoAnswer(context, callId, fromVoipService);
        CustomLogger.i(TAG, "Scheduled AI auto-answer in " + delaySec + "s for callId=" + callId);
        AndroidUtilities.runOnUIThread(pendingAutoAnswerRunnable, delaySec * 1000L);
    }

    private synchronized void triggerAutoAnswer(Context context, long callId, boolean fromVoipService) {
        pendingAutoAnswerRunnable = null;
        scheduledCallId = 0L;

        if (!AiCallConfig.getInstance().isAutoAnswerEnabled()) {
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            CustomLogger.w(TAG, "RECORD_AUDIO permission missing; cannot auto-answer callId=" + callId);
            state.reset();
            return;
        }

        AiCallConfig config = AiCallConfig.getInstance();
        state.setMode(AiCallSessionState.Mode.AI_HANDLING);
        state.setAiActive(true);
        state.setAnonymousListenIn(config.isAnonymousListenDefault());
        state.setRecordingActive(config.isAutoRecordToSavedMessages());
        state.setTranscriptBuffering(config.isBackgroundTranscriptEnabled());
        state.setStartedAtMs(System.currentTimeMillis());

        CustomLogger.i(TAG, "Triggering AI auto-answer for callId=" + callId);
        VoIPService shared = VoIPService.getSharedInstance();
        if (shared != null) {
            if (shared.getCallState() == VoIPService.STATE_WAITING_INCOMING) {
                shared.acceptIncomingCall();
            }
        } else if (!fromVoipService) {
            VoIPPreNotificationService.answer(context);
        }
    }

    private void cancelPendingAutoAnswer() {
        if (pendingAutoAnswerRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(pendingAutoAnswerRunnable);
            pendingAutoAnswerRunnable = null;
        }
        scheduledCallId = 0L;
    }

    @Override
    public void onAiCallCommand(String action, Bundle extras) {
        if (AiCallActionBus.ACTION_TAKE_OVER.equals(action)) {
            takeOverByOwner();
        } else if (AiCallActionBus.ACTION_HANDOVER_TO_AI.equals(action)) {
            handoverToAi();
        } else if (AiCallActionBus.ACTION_TOGGLE_LISTEN_IN.equals(action)) {
            boolean enabled = extras != null ? extras.getBoolean(AiCallActionBus.EXTRA_ENABLED, !state.isAnonymousListenIn()) : !state.isAnonymousListenIn();
            setAnonymousListenIn(enabled);
        } else if (AiCallActionBus.ACTION_TOGGLE_RECORDING.equals(action)) {
            boolean enabled = extras != null ? extras.getBoolean(AiCallActionBus.EXTRA_ENABLED, !state.isRecordingActive()) : !state.isRecordingActive();
            setRecordingActive(enabled);
        } else if (AiCallActionBus.ACTION_END_CALL.equals(action)) {
            VoIPService shared = VoIPService.getSharedInstance();
            if (shared != null) {
                shared.hangUp();
            }
        }
    }
}
