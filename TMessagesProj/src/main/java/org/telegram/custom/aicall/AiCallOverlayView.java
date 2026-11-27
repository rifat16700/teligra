package org.telegram.custom.aicall;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Smooth dark-glass Chat-Style Cover Overlay placed directly over Telegram's
 * native VoIPFragment calling screen. Displays live Caller/AI/Owner bubbles,
 * call timer, handover controls, Anonymous Listen-In, and a Hide/Show pill
 * so the user can peek at the native call UI anytime.
 */
public final class AiCallOverlayView extends FrameLayout implements CallContextMemory.ChatUpdateListener {

    private final LinearLayout coverRoot;
    private final TextView floatingShowPill;
    private final TextView callerTitleView;
    private final TextView statusPillView;
    private final TextView timerView;
    private final TextView hideCoverBtn;
    private final ScrollView chatScrollView;
    private final LinearLayout messagesContainer;
    private final TextView handoverBtn;
    private final TextView listenBtn;
    private final TextView endCallBtn;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private int renderedCount = 0;
    private TextView lastRenderedBody = null;
    private String lastRenderedRole = null;
    private boolean coverExpanded = false;
    private boolean timerRunning = false;

    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!timerRunning) return;
            updateHeaderAndButtons();
            AndroidUtilities.runOnUIThread(this, 1000L);
        }
    };

    public AiCallOverlayView(Context context) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // 1. Full-screen Dark-Glass Cover
        coverRoot = new LinearLayout(context);
        coverRoot.setOrientation(LinearLayout.VERTICAL);
        coverRoot.setBackgroundColor(0xF00B0F1A);
        coverRoot.setClickable(true);
        coverRoot.setFocusable(true);
        coverRoot.setPadding(0, AndroidUtilities.statusBarHeight, 0, AndroidUtilities.navigationBarHeight);
        addView(coverRoot, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // Header card
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        header.setBackgroundColor(0xFF121826);
        coverRoot.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        header.addView(titleCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        callerTitleView = new TextView(context);
        callerTitleView.setTextColor(0xFFFFFFFF);
        callerTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        callerTitleView.setTypeface(AndroidUtilities.bold());
        titleCol.addView(callerTitleView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout metaRow = new LinearLayout(context);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        titleCol.addView(metaRow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        statusPillView = new TextView(context);
        statusPillView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        statusPillView.setTextColor(0xFFFFFFFF);
        statusPillView.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(3), AndroidUtilities.dp(8), AndroidUtilities.dp(3));
        metaRow.addView(statusPillView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        timerView = new TextView(context);
        timerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        timerView.setTextColor(0xFF94A3B8);
        timerView.setPadding(AndroidUtilities.dp(10), 0, 0, 0);
        metaRow.addView(timerView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        hideCoverBtn = new TextView(context);
        hideCoverBtn.setText("Hide Cover");
        hideCoverBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        hideCoverBtn.setTextColor(0xFFE2E8F0);
        hideCoverBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16), 0x26FFFFFF));
        hideCoverBtn.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(7), AndroidUtilities.dp(12), AndroidUtilities.dp(7));
        hideCoverBtn.setOnClickListener(v -> setCoverExpanded(false, true));
        header.addView(hideCoverBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        View topDiv = new View(context);
        topDiv.setBackgroundColor(0x1FFFFFFF);
        coverRoot.addView(topDiv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        // Scrollable chat area
        chatScrollView = new ScrollView(context);
        chatScrollView.setFillViewport(true);
        chatScrollView.setVerticalScrollBarEnabled(false);
        coverRoot.addView(chatScrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        messagesContainer = new LinearLayout(context);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);
        messagesContainer.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14));
        chatScrollView.addView(messagesContainer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Bottom dock
        LinearLayout dock = new LinearLayout(context);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setBackgroundColor(0xFF121826);
        dock.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(14));
        coverRoot.addView(dock, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        handoverBtn = createDockButton(context, 0xFF4F46E5, 0xFF4338CA);
        handoverBtn.setOnClickListener(v -> {
            AiCallSessionState state = AiCallController.getInstance().getState();
            if (state.getMode() == AiCallSessionState.Mode.IDLE) return;
            if (state.isAiActive()) {
                AiCallActionBus.dispatch(AiCallActionBus.ACTION_TAKE_OVER, null);
            } else {
                AiCallActionBus.dispatch(AiCallActionBus.ACTION_HANDOVER_TO_AI, null);
            }
            updateHeaderAndButtons();
        });
        dock.addView(handoverBtn, LayoutHelper.createLinear(0, 46, 1.25f, 0, 0, 8, 0));

        listenBtn = createDockButton(context, 0xFF334155, 0xFF1E293B);
        listenBtn.setOnClickListener(v -> {
            AiCallSessionState state = AiCallController.getInstance().getState();
            if (state.getMode() == AiCallSessionState.Mode.IDLE) return;
            Bundle b = new Bundle();
            b.putBoolean(AiCallActionBus.EXTRA_ENABLED, !state.isAnonymousListenIn());
            AiCallActionBus.dispatch(AiCallActionBus.ACTION_TOGGLE_LISTEN_IN, b);
            updateHeaderAndButtons();
        });
        dock.addView(listenBtn, LayoutHelper.createLinear(0, 46, 1.1f, 0, 0, 8, 0));

        endCallBtn = createDockButton(context, 0xFFDC2626, 0xFFB91C1C);
        endCallBtn.setText("End Call");
        endCallBtn.setOnClickListener(v -> AiCallActionBus.dispatch(AiCallActionBus.ACTION_END_CALL, null));
        dock.addView(endCallBtn, LayoutHelper.createLinear(0, 46, 0.85f));

        // 2. Floating Pill when cover is hidden (or during normal owner call)
        floatingShowPill = new TextView(context);
        floatingShowPill.setText("AI Chat");
        floatingShowPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        floatingShowPill.setTypeface(AndroidUtilities.bold());
        floatingShowPill.setTextColor(0xFFFFFFFF);
        GradientDrawable pillBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF4F46E5, 0xFF7C3AED}
        );
        pillBg.setCornerRadius(AndroidUtilities.dp(20));
        floatingShowPill.setBackground(pillBg);
        floatingShowPill.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8), AndroidUtilities.dp(14), AndroidUtilities.dp(8));
        floatingShowPill.setOnClickListener(v -> setCoverExpanded(true, true));
        FrameLayout.LayoutParams pillLp = LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.END, 0, 14, 14, 0
        );
        pillLp.topMargin += AndroidUtilities.statusBarHeight;
        addView(floatingShowPill, pillLp);

        boolean autoExpand = AiCallController.getInstance().getState().isAiActive();
        setCoverExpanded(autoExpand, false);
        rebuildMessages();
        updateHeaderAndButtons();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        AiAudioBridge.getInstance().getMemory().addListener(this);
        timerRunning = true;
        AndroidUtilities.runOnUIThread(timerRunnable, 1000L);
        rebuildMessages();
        updateHeaderAndButtons();
    }

    @Override
    protected void onDetachedFromWindow() {
        timerRunning = false;
        AndroidUtilities.cancelRunOnUIThread(timerRunnable);
        AiAudioBridge.getInstance().getMemory().removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onChatUpdated() {
        AiCallSessionState state = AiCallController.getInstance().getState();
        if (state.isAiActive() && !coverExpanded) {
            setCoverExpanded(true, true);
        }
        syncMessagesIncrementally();
        updateHeaderAndButtons();
    }

    public void setCoverExpanded(boolean expanded, boolean animate) {
        this.coverExpanded = expanded;
        if (expanded) {
            floatingShowPill.setVisibility(GONE);
            coverRoot.setVisibility(VISIBLE);
            if (animate) {
                coverRoot.setAlpha(0f);
                coverRoot.animate().alpha(1f).setDuration(200).start();
            } else {
                coverRoot.setAlpha(1f);
            }
        } else {
            coverRoot.setVisibility(GONE);
            floatingShowPill.setVisibility(VISIBLE);
            if (animate) {
                floatingShowPill.setAlpha(0f);
                floatingShowPill.animate().alpha(1f).setDuration(180).start();
            } else {
                floatingShowPill.setAlpha(1f);
            }
        }
    }

    private void updateHeaderAndButtons() {
        AiCallSessionState state = AiCallController.getInstance().getState();
        CallContextMemory memory = AiAudioBridge.getInstance().getMemory();

        callerTitleView.setText(memory.getPeerName());
        long started = state.getStartedAtMs();
        long elapsedSec = started > 0L ? Math.max(0L, (System.currentTimeMillis() - started) / 1000L) : 0L;
        timerView.setText(String.format(Locale.US, "%02d:%02d", elapsedSec / 60, elapsedSec % 60));

        if (state.getMode() == AiCallSessionState.Mode.IDLE || state.getMode() == AiCallSessionState.Mode.RINGING_TIMER) {
            statusPillView.setText("● Ringing / Connecting");
            statusPillView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), 0xFF475569));
            handoverBtn.setText("Return to AI");
            listenBtn.setText("Listen: OFF");
            return;
        }

        if (state.isAiActive()) {
            if (state.isAnonymousListenIn()) {
                statusPillView.setText("● AI Active · Listening");
                statusPillView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), 0xFFD97706));
                listenBtn.setText("Listen: ON");
                listenBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12), 0xFFD97706, 0xFFB45309));
            } else {
                statusPillView.setText("● AI Active · Silent");
                statusPillView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), 0xFF6366F1));
                listenBtn.setText("Listen: OFF");
                listenBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12), 0xFF334155, 0xFF1E293B));
            }
            handoverBtn.setText("Take Over");
            handoverBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12), 0xFF059669, 0xFF047857));
            listenBtn.setEnabled(true);
            floatingShowPill.setText("AI Live · Open");
        } else {
            statusPillView.setText("● Owner Speaking");
            statusPillView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(10), 0xFF059669));
            handoverBtn.setText("Return to AI");
            handoverBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12), 0xFF4F46E5, 0xFF4338CA));
            listenBtn.setText("Owner Mic");
            listenBtn.setEnabled(false);
            listenBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), 0xFF1E293B));
            floatingShowPill.setText("AI Chat");
        }
    }

    private void rebuildMessages() {
        messagesContainer.removeAllViews();
        renderedCount = 0;
        lastRenderedBody = null;
        lastRenderedRole = null;
        syncMessagesIncrementally();
    }

    private void syncMessagesIncrementally() {
        Context context = getContext();
        if (context == null) return;
        List<CallContextMemory.ChatEntry> entries = AiAudioBridge.getInstance().getMemory().getEntriesSnapshot();

        if (entries.isEmpty()) {
            if (messagesContainer.getChildCount() == 0) {
                TextView empty = new TextView(context);
                empty.setText("Live AI call conversation will appear here...");
                empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                empty.setTextColor(0xFF64748B);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(48), AndroidUtilities.dp(16), AndroidUtilities.dp(48));
                messagesContainer.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            return;
        }

        if (renderedCount == 0 && messagesContainer.getChildCount() > 0) {
            messagesContainer.removeAllViews();
        }

        // Update last bubble text smoothly if merged with same speaker
        if (renderedCount > 0 && renderedCount == entries.size() && lastRenderedBody != null) {
            CallContextMemory.ChatEntry last = entries.get(entries.size() - 1);
            if (last.role.equals(lastRenderedRole)) {
                lastRenderedBody.setText(last.text);
                chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN));
                return;
            }
        }

        if (entries.size() < renderedCount) {
            messagesContainer.removeAllViews();
            renderedCount = 0;
            lastRenderedBody = null;
            lastRenderedRole = null;
        }

        for (int i = renderedCount; i < entries.size(); i++) {
            CallContextMemory.ChatEntry entry = entries.get(i);
            View bubbleRow = createAnimatedBubble(context, entry);
            messagesContainer.addView(bubbleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 5, 0, 5));
        }
        renderedCount = entries.size();
        chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private View createAnimatedBubble(Context context, CallContextMemory.ChatEntry entry) {
        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        if ("SYSTEM".equals(entry.role)) {
            wrapper.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView pill = new TextView(context);
            pill.setText(entry.text);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            pill.setTextColor(0xFFCBD5E1);
            pill.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), 0x24FFFFFF));
            pill.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(5), AndroidUtilities.dp(12), AndroidUtilities.dp(5));
            wrapper.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            lastRenderedBody = null;
            lastRenderedRole = "SYSTEM";
        } else {
            boolean isLeft = "CALLER".equals(entry.role);
            wrapper.setGravity(isLeft ? Gravity.START : Gravity.END);

            LinearLayout bubble = new LinearLayout(context);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10));

            if (isLeft) {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFF1E2433);
                bg.setCornerRadius(AndroidUtilities.dp(18));
                bg.setStroke(AndroidUtilities.dp(1), 0x1FFFFFFF);
                bubble.setBackground(bg);
            } else if ("OWNER".equals(entry.role)) {
                GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF059669, 0xFF0D9488});
                bg.setCornerRadius(AndroidUtilities.dp(18));
                bubble.setBackground(bg);
            } else {
                GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF4F46E5, 0xFF7C3AED});
                bg.setCornerRadius(AndroidUtilities.dp(18));
                bubble.setBackground(bg);
            }

            TextView meta = new TextView(context);
            meta.setText(entry.role + " · " + timeFmt.format(new Date(entry.timestampMs)));
            meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
            meta.setTextColor(isLeft ? 0xFF94A3B8 : 0xFFE0E7FF);
            bubble.addView(meta, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            TextView body = new TextView(context);
            body.setText(entry.text);
            body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            body.setTextColor(0xFFFFFFFF);
            body.setMaxWidth(AndroidUtilities.dp(275));
            bubble.addView(body, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

            wrapper.addView(bubble, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            lastRenderedBody = body;
            lastRenderedRole = entry.role;
        }

        wrapper.setAlpha(0f);
        wrapper.setTranslationY(AndroidUtilities.dp(14));
        wrapper.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        return wrapper;
    }

    private TextView createDockButton(Context context, int bgColor, int pressedColor) {
        TextView btn = new TextView(context);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        btn.setTypeface(AndroidUtilities.bold());
        btn.setTextColor(0xFFFFFFFF);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12), bgColor, pressedColor));
        btn.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        return btn;
    }
}
