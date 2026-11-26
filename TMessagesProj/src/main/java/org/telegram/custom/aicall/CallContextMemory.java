package org.telegram.custom.aicall;

import org.telegram.messenger.AndroidUtilities;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds structured chat messages, unique call serial ID (#nc-call-r),
 * and multi-stage AI/Owner handover summaries for the active call.
 */
public final class CallContextMemory {
    public static final String HIDDEN_TAG = "#nc-call-r";

    public static final class ChatEntry {
        public final String role; // "CALLER", "AI", "OWNER", "SYSTEM"
        public final String text;
        public final long timestampMs;

        public ChatEntry(String role, String text, long timestampMs) {
            this.role = role;
            this.text = text;
            this.timestampMs = timestampMs;
        }
    }

    public interface ChatUpdateListener {
        void onChatUpdated();
    }

    private final CopyOnWriteArrayList<ChatEntry> entries = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> aiPhaseSummaries = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> ownerPhaseSummaries = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<ChatUpdateListener> listeners = new CopyOnWriteArrayList<>();

    private volatile String serialId = "";
    private volatile String peerName = "Caller";
    private volatile int handoverCount = 0;
    private volatile int phaseStartIndex = 0;

    public synchronized void reset(long callId, long peerUserId, String peerName) {
        entries.clear();
        aiPhaseSummaries.clear();
        ownerPhaseSummaries.clear();
        handoverCount = 0;
        phaseStartIndex = 0;
        this.peerName = (peerName == null || peerName.trim().isEmpty()) ? "Caller" : peerName.trim();
        this.serialId = generateSerialId(callId, peerUserId);
        notifyListeners();
    }

    public static String generateSerialId(long callId, long peerUserId) {
        String datePart = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        long seed = callId != 0L ? Math.abs(callId) : (peerUserId != 0L ? Math.abs(peerUserId) : System.nanoTime());
        long tail = seed % 10000L;
        return String.format(Locale.US, "NCR-%s-%04d", datePart, tail);
    }

    public String getSerialId() {
        if (serialId == null || serialId.isEmpty()) {
            serialId = generateSerialId(0L, 0L);
        }
        return serialId;
    }

    public String getPeerName() {
        return peerName;
    }

    public int getHandoverCount() {
        return handoverCount;
    }

    public synchronized void addOrAppendEntry(String role, String text, boolean mergeWithLastSameRole) {
        if (text == null || text.trim().isEmpty()) return;
        String clean = text.trim();
        if (mergeWithLastSameRole && !entries.isEmpty()) {
            int lastIdx = entries.size() - 1;
            ChatEntry last = entries.get(lastIdx);
            if (last.role.equals(role)) {
                String merged = last.text + (last.text.endsWith(" ") ? "" : " ") + clean;
                entries.set(lastIdx, new ChatEntry(role, merged, System.currentTimeMillis()));
                notifyListeners();
                return;
            }
        }
        entries.add(new ChatEntry(role, clean, System.currentTimeMillis()));
        notifyListeners();
    }

    public synchronized void recordTakeOverFromAi(String externalSummary) {
        handoverCount++;
        String summary = (externalSummary != null && !externalSummary.trim().isEmpty())
                ? externalSummary.trim()
                : buildLocalPhaseSummary("AI Phase");
        if (!summary.isEmpty()) {
            aiPhaseSummaries.add(summary);
        }
        phaseStartIndex = entries.size();
        entries.add(new ChatEntry("SYSTEM", "Owner took over from AI · Local summary saved", System.currentTimeMillis()));
        notifyListeners();
    }

    public synchronized void recordHandoverToAi(String externalSummary) {
        handoverCount++;
        String summary = (externalSummary != null && !externalSummary.trim().isEmpty())
                ? externalSummary.trim()
                : buildLocalPhaseSummary("Owner Phase");
        if (!summary.isEmpty()) {
            ownerPhaseSummaries.add(summary);
        }
        phaseStartIndex = entries.size();
        entries.add(new ChatEntry("SYSTEM", "Returned call to AI with conversation context", System.currentTimeMillis()));
        notifyListeners();
    }

    public String buildHandoverContextPrompt(String ownerData) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are answering a live Telegram voice call on behalf of the owner. ");
        sb.append("Speak naturally, concisely, and strictly follow the owner's information below.\n");
        if (ownerData != null && !ownerData.trim().isEmpty()) {
            sb.append("\n[Owner Data]\n").append(ownerData.trim()).append("\n");
        } else {
            sb.append("\n[Owner Data]\nThe owner is currently unavailable. Offer to take a message.\n");
        }
        if (!aiPhaseSummaries.isEmpty()) {
            sb.append("\n[Previous AI Conversation Summary]\n");
            for (int i = 0; i < aiPhaseSummaries.size(); i++) {
                sb.append("- ").append(aiPhaseSummaries.get(i)).append("\n");
            }
        }
        if (!ownerPhaseSummaries.isEmpty()) {
            sb.append("\n[Owner Conversation Summary Before Handover]\n");
            for (int i = 0; i < ownerPhaseSummaries.size(); i++) {
                sb.append("- ").append(ownerPhaseSummaries.get(i)).append("\n");
            }
        }
        int start = Math.max(0, entries.size() - 12);
        if (start < entries.size()) {
            sb.append("\n[Recent Conversation]\n");
            for (int i = start; i < entries.size(); i++) {
                ChatEntry e = entries.get(i);
                if (!"SYSTEM".equals(e.role)) {
                    sb.append(e.role).append(": ").append(e.text).append("\n");
                }
            }
        }
        return sb.toString();
    }

    public String buildFullTranscriptText() {
        StringBuilder sb = new StringBuilder();
        for (ChatEntry e : entries) {
            sb.append("[").append(e.role).append("]: ").append(e.text).append("\n");
        }
        return sb.toString().trim();
    }

    public List<ChatEntry> getEntriesSnapshot() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public List<String> getAiPhaseSummaries() {
        return Collections.unmodifiableList(new ArrayList<>(aiPhaseSummaries));
    }

    public List<String> getOwnerPhaseSummaries() {
        return Collections.unmodifiableList(new ArrayList<>(ownerPhaseSummaries));
    }

    public void addListener(ChatUpdateListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(ChatUpdateListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners() {
        if (listeners.isEmpty()) return;
        AndroidUtilities.runOnUIThread(() -> {
            for (ChatUpdateListener l : listeners) {
                try {
                    l.onChatUpdated();
                } catch (Throwable ignored) {}
            }
        });
    }

    private String buildLocalPhaseSummary(String label) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = Math.max(0, phaseStartIndex); i < entries.size(); i++) {
            ChatEntry e = entries.get(i);
            if ("SYSTEM".equals(e.role)) continue;
            if (count > 0) sb.append(" | ");
            sb.append(e.role).append(": ").append(e.text);
            count++;
            if (sb.length() > 450) break;
        }
        return count == 0 ? "" : (label + ": " + sb);
    }
}
