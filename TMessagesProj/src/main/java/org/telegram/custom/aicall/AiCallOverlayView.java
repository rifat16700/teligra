package org.telegram.custom.aicall;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Native Telegram VoIP + Live Chat Call Overlay.
 * Matches Telegram's dark VoIP gradient canvas, authentic asymmetric chat bubbles,
 * compact floating minimize pill, and circular VoIP bottom dock controls.
 */
public final class AiCallOverlayView extends FrameLayout implements CallContextMemory.ChatUpdateListener {

    private static final int[] AVATAR_GRADIENTS_TOP = {
            0xFF4F9CF9, 0xFF8B5CF6, 0xFF10B981, 0xFFF59E0B, 0xFFEC4899, 0xFF06B6D4
    };
    private static final int[] AVATAR_GRADIENTS_BOTTOM = {
            0xFF2563EB, 0xFF6D28D9, 0xFF047857, 0xFFD97706, 0xFFBE185D, 0xFF0E7490
    };

    private final SimpleDateFormat msgTimeFmt = new SimpleDateFormat("HH:mm", Locale.US);

    private LinearLayout expandedContainer;
    private LinearLayout minimizedPill;
    private TextView avatarView;
    private TextView peerTitleView;
    private View statusDotView;
    private TextView statusTimerView;
    private TextView miniDotView;
    private TextView miniLabelView;

    private ScrollView chatScrollView;
    private LinearLayout chatMessagesContainer;

    private FrameLayout listenBtnCircle;
    private ImageView listenIcon;
    private TextView listenLabel;

    private FrameLayout handoverBtnCircle;
    private ImageView handoverIcon;
    private TextView handoverLabel;

    private FrameLayout endBtnCircle;
    private TextView endLabel;

    private boolean isMinimized = false;

    private final Runnable uiTickRunnable = new Runnable() {
        @Override
        public void run() {
            refreshHeaderAndControls();
            if (getVisibility() == View.VISIBLE) {
                AndroidUtilities.runOnUIThread(this, 500L);
            }
        }
    };

    public AiCallOverlayView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setVisibility(GONE);
        buildUi(context);
    }

    private void buildUi(Context context) {
        expandedContainer = new LinearLayout(context);
        expandedContainer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable backdrop = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0E1621, 0xFF152232, 0xFF0D1520});
        expandedContainer.setBackground(backdrop);
        expandedContainer.setClickable(true);

        // --- Header ---
        int topPad = Math.max(AndroidUtilities.statusBarHeight, AndroidUtilities.dp(24)) + AndroidUtilities.dp(10);
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(AndroidUtilities.dp(16), topPad, AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        header.setBackgroundColor(0x33080D14);
        expandedContainer.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        avatarView = new TextView(context);
        avatarView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        avatarView.setTypeface(AndroidUtilities.bold());
        avatarView.setTextColor(Color.WHITE);
        avatarView.setGravity(Gravity.CENTER);
        header.addView(avatarView, LayoutHelper.createLinear(44, 44, 0, 0, 12, 0));

        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        header.addView(titleCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        peerTitleView = new TextView(context);
        peerTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        peerTitleView.setTypeface(AndroidUtilities.bold());
        peerTitleView.setTextColor(Color.WHITE);
        peerTitleView.setSingleLine(true);
        peerTitleView.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(peerTitleView);

        LinearLayout statusRow = new LinearLayout(context);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        titleCol.addView(statusRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        statusDotView = new View(context);
        statusRow.addView(statusDotView, LayoutHelper.createLinear(8, 8, 0, 0, 6, 0));

        statusTimerView = new TextView(context);
        statusTimerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusTimerView.setTextColor(0xFFB3C7DB);
        statusTimerView.setSingleLine(true);
        statusRow.addView(statusTimerView);

        TextView minimizeBtn = new TextView(context);
        minimizeBtn.setText("⌄");
        minimizeBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        minimizeBtn.setTypeface(AndroidUtilities.bold());
        minimizeBtn.setTextColor(0xFFEAF2FA);
        minimizeBtn.setGravity(Gravity.CENTER);
        minimizeBtn.setIncludeFontPadding(false);
        minimizeBtn.setPadding(0, 0, 0, AndroidUtilities.dp(4));
        minimizeBtn.setBackground(createCircleRipple(0x24FFFFFF, 0x44FFFFFF));
        minimizeBtn.setOnClickListener(v -> setMinimized(true));
        header.addView(minimizeBtn, LayoutHelper.createLinear(38, 38, 8, 0, 0, 0));

        View topHairline = new View(context);
        topHairline.setBackgroundColor(0x16FFFFFF);
        expandedContainer.addView(topHairline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        // --- Live Conversation ScrollView ---
        chatScrollView = new ScrollView(context);
        chatScrollView.setVerticalScrollBarEnabled(false);
        chatScrollView.setFillViewport(true);
        expandedContainer.addView(chatScrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        chatMessagesContainer = new LinearLayout(context);
        chatMessagesContainer.setOrientation(LinearLayout.VERTICAL);
        chatMessagesContainer.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14));
        chatScrollView.addView(chatMessagesContainer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        // --- Bottom Telegram VoIP Circular Dock ---
        int bottomPad = Math.max(AndroidUtilities.navigationBarHeight, AndroidUtilities.dp(12)) + AndroidUtilities.dp(18);
        LinearLayout dockWrap = new LinearLayout(context);
        dockWrap.setOrientation(LinearLayout.VERTICAL);
        dockWrap.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(14), AndroidUtilities.dp(20), bottomPad);
        GradientDrawable dockBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x000A1018, 0xCC0A1018, 0xEE080D14});
        dockWrap.setBackground(dockBg);
        expandedContainer.addView(dockWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout buttonsRow = new LinearLayout(context);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setGravity(Gravity.CENTER);
        dockWrap.addView(buttonsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Left: Listen-In Toggle
        LinearLayout listenCol = createDockColumn(context);
        listenBtnCircle = createCircleIconContainer(context, R.drawable.msg_voicechat, 24);
        listenLabel = createDockLabel(context, "Listen");
        listenCol.addView(listenBtnCircle, LayoutHelper.createLinear(56, 56));
        listenCol.addView(listenLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 7, 0, 0));
        listenBtnCircle.setOnClickListener(v -> {
            AiCallSessionState st = AiCallController.getInstance().getState();
            boolean nextListen = !st.isAnonymousListenIn();
            android.os.Bundle extras = new android.os.Bundle();
            extras.putBoolean(AiCallActionBus.EXTRA_ENABLED, nextListen);
            AiCallActionBus.dispatch(AiCallActionBus.ACTION_TOGGLE_LISTEN_IN, extras);
            refreshHeaderAndControls();
        });
        buttonsRow.addView(listenCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        // Center: Primary Take Over / Return to AI
        LinearLayout handoverCol = createDockColumn(context);
        handoverBtnCircle = createCircleIconContainer(context, R.drawable.call, 28);
        handoverLabel = createDockLabel(context, "Take Over");
        handoverCol.addView(handoverBtnCircle, LayoutHelper.createLinear(66, 66));
        handoverCol.addView(handoverLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 7, 0, 0));
        handoverBtnCircle.setOnClickListener(v -> {
            AiCallSessionState st = AiCallController.getInstance().getState();
            if (st.getMode() == AiCallSessionState.Mode.OWNER_HANDLING) {
                AiCallActionBus.dispatch(AiCallActionBus.ACTION_HANDOVER_TO_AI, null);
            } else {
                AiCallActionBus.dispatch(AiCallActionBus.ACTION_TAKE_OVER, null);
            }
            refreshHeaderAndControls();
        });
        buttonsRow.addView(handoverCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        // Right: End Call
        LinearLayout endCol = createDockColumn(context);
        endBtnCircle = createCircleIconContainer(context, R.drawable.ic_call_end_white_24dp, 26);
        endBtnCircle.setBackground(createCircleRipple(0xFFEF4444, 0x66FFFFFF));
        endLabel = createDockLabel(context, "End Call");
        endCol.addView(endBtnCircle, LayoutHelper.createLinear(56, 56));
        endCol.addView(endLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 7, 0, 0));
        endBtnCircle.setOnClickListener(v -> AiCallActionBus.dispatch(AiCallActionBus.ACTION_END_CALL, null));
        buttonsRow.addView(endCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        addView(expandedContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // --- Compact Minimized Floating Pill ---
        minimizedPill = new LinearLayout(context);
        minimizedPill.setOrientation(LinearLayout.HORIZONTAL);
        minimizedPill.setGravity(Gravity.CENTER_VERTICAL);
        minimizedPill.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(9), AndroidUtilities.dp(14), AndroidUtilities.dp(9));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(AndroidUtilities.dp(24));
        pillBg.setColor(0xEE182330);
        pillBg.setStroke(AndroidUtilities.dp(1), 0x30FFFFFF);
        minimizedPill.setBackground(pillBg);
        minimizedPill.setElevation(AndroidUtilities.dp(6));
        minimizedPill.setVisibility(GONE);
        minimizedPill.setOnClickListener(v -> setMinimized(false));

        miniDotView = new TextView(context);
        miniDotView.setText("●");
        miniDotView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        miniDotView.setTextColor(0xFF34D399);
        minimizedPill.addView(miniDotView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));

        miniLabelView = new TextView(context);
        miniLabelView.setTextColor(Color.WHITE);
        miniLabelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        miniLabelView.setTypeface(AndroidUtilities.bold());
        miniLabelView.setSingleLine(true);
        miniLabelView.setEllipsize(TextUtils.TruncateAt.END);
        minimizedPill.addView(miniLabelView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        LayoutParams pillLp = LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT,
                LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | Gravity.END,
                16, Math.max(AndroidUtilities.statusBarHeight, AndroidUtilities.dp(20)) + 8, 16, 0);
        addView(minimizedPill, pillLp);
    }

    private LinearLayout createDockColumn(Context context) {
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        return col;
    }

    private FrameLayout createCircleIconContainer(Context context, int iconRes, int iconSizeDp) {
        FrameLayout frame = new FrameLayout(context);
        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setColorFilter(Color.WHITE);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(icon, LayoutHelper.createFrame(iconSizeDp, iconSizeDp, Gravity.CENTER));
        frame.setTag(icon);
        return frame;
    }

    private TextView createDockLabel(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        tv.setTextColor(0xFFD6E2EE);
        tv.setSingleLine(true);
        return tv;
    }

    private void setMinimized(boolean minimized) {
        this.isMinimized = minimized;
        if (minimized) {
            expandedContainer.setVisibility(GONE);
            minimizedPill.setVisibility(VISIBLE);
            setClickable(false);
            setFocusable(false);
        } else {
            minimizedPill.setVisibility(GONE);
            expandedContainer.setVisibility(VISIBLE);
            setClickable(true);
            setFocusable(true);
        }
    }

    public void showOverlay() {
        CallContextMemory memory = AiAudioBridge.getInstance().getMemory();
        memory.addListener(this);
        isMinimized = false;
        setMinimized(false);
        setVisibility(VISIBLE);
        refreshHeaderAndControls();
        rebuildChatBubbles();
        AndroidUtilities.cancelRunOnUIThread(uiTickRunnable);
        AndroidUtilities.runOnUIThread(uiTickRunnable, 500L);
    }

    public void hideOverlay() {
        AndroidUtilities.cancelRunOnUIThread(uiTickRunnable);
        AiAudioBridge.getInstance().getMemory().removeListener(this);
        setVisibility(GONE);
    }

    @Override
    public void onChatUpdated() {
        if (getVisibility() == VISIBLE) {
            rebuildChatBubbles();
        }
    }

    private void refreshHeaderAndControls() {
        AiCallSessionState state = AiCallController.getInstance().getState();
        CallContextMemory memory = AiAudioBridge.getInstance().getMemory();
        String peerName = memory.getPeerName();
        if (peerName == null || peerName.trim().isEmpty()) {
            peerName = "Incoming Call";
        }

        peerTitleView.setText(peerName);
        avatarView.setText(getInitials(peerName));
        int idx = Math.abs(peerName.hashCode()) % AVATAR_GRADIENTS_TOP.length;
        GradientDrawable avatarBg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{AVATAR_GRADIENTS_TOP[idx], AVATAR_GRADIENTS_BOTTOM[idx]});
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarView.setBackground(avatarBg);

        long elapsedSec = state.getStartedAtMs() > 0L
                ? Math.max(0L, (System.currentTimeMillis() - state.getStartedAtMs()) / 1000L)
                : 0L;
        String timerStr = String.format(Locale.US, "%02d:%02d", elapsedSec / 60L, elapsedSec % 60L);

        String statusText;
        int dotColor;
        if (state.getMode() == AiCallSessionState.Mode.RINGING_TIMER) {
            statusText = "Waiting before AI picks up · " + timerStr;
            dotColor = 0xFFFBBF24;
        } else if (state.getMode() == AiCallSessionState.Mode.AI_HANDLING) {
            statusText = state.isAnonymousListenIn() ? ("AI Answering (Listening) · " + timerStr)
                    : ("AI Assistant Speaking · " + timerStr);
            dotColor = 0xFF34D399;
        } else if (state.getMode() == AiCallSessionState.Mode.OWNER_HANDLING) {
            statusText = "You are speaking · " + timerStr;
            dotColor = 0xFF60A5FA;
        } else {
            statusText = "Connected · " + timerStr;
            dotColor = 0xFF94A3B8;
        }

        GradientDrawable dotDrawable = new GradientDrawable();
        dotDrawable.setShape(GradientDrawable.OVAL);
        dotDrawable.setColor(dotColor);
        statusDotView.setBackground(dotDrawable);
        statusTimerView.setText(statusText);

        miniDotView.setTextColor(dotColor);
        miniLabelView.setText(peerName + " · " + timerStr + "  ▴");

        if (state.isAnonymousListenIn()) {
            listenBtnCircle.setBackground(createCircleRipple(0xFF2AABEE, 0x55FFFFFF));
            listenLabel.setText("Listening");
            listenLabel.setTextColor(0xFF7CC8F8);
        } else {
            listenBtnCircle.setBackground(createCircleRipple(0xFF223042, 0x44FFFFFF));
            listenLabel.setText("Listen");
            listenLabel.setTextColor(0xFFD6E2EE);
        }

        if (state.getMode() == AiCallSessionState.Mode.OWNER_HANDLING) {
            GradientDrawable aiGrad = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF22C55E, 0xFF15803D});
            aiGrad.setShape(GradientDrawable.OVAL);
            handoverBtnCircle.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), aiGrad, aiGrad));
            handoverLabel.setText("Return to AI");
            handoverLabel.setTextColor(0xFF86EFAC);
        } else {
            GradientDrawable takeGrad = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF38BDF8, 0xFF0284C7});
            takeGrad.setShape(GradientDrawable.OVAL);
            handoverBtnCircle.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), takeGrad, takeGrad));
            handoverLabel.setText("Take Over");
            handoverLabel.setTextColor(0xFFE0F2FE);
        }
    }

    private void rebuildChatBubbles() {
        Context context = getContext();
        if (context == null || chatMessagesContainer == null) {
            return;
        }
        chatMessagesContainer.removeAllViews();

        List<CallContextMemory.ChatEntry> entries = AiAudioBridge.getInstance().getMemory().getEntriesSnapshot();
        if (entries.isEmpty()) {
            LinearLayout emptyWrap = new LinearLayout(context);
            emptyWrap.setOrientation(LinearLayout.VERTICAL);
            emptyWrap.setGravity(Gravity.CENTER);
            emptyWrap.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(80), AndroidUtilities.dp(24), AndroidUtilities.dp(40));

            TextView emptyBadge = new TextView(context);
            emptyBadge.setText("🎙");
            emptyBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
            emptyBadge.setGravity(Gravity.CENTER);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(0x1E2AABEE);
            emptyBadge.setBackground(circle);
            emptyWrap.addView(emptyBadge, LayoutHelper.createLinear(58, 58, 0, 0, 0, 12));

            TextView emptyTitle = new TextView(context);
            emptyTitle.setText("Live Call Transcript");
            emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            emptyTitle.setTypeface(AndroidUtilities.bold());
            emptyTitle.setTextColor(0xFFE2ECF5);
            emptyTitle.setGravity(Gravity.CENTER);
            emptyWrap.addView(emptyTitle);

            TextView emptySub = new TextView(context);
            emptySub.setText("Caller & AI conversation bubbles will appear here in real time.");
            emptySub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            emptySub.setTextColor(0xFF8FA3B8);
            emptySub.setGravity(Gravity.CENTER);
            emptyWrap.addView(emptySub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

            chatMessagesContainer.addView(emptyWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return;
        }

        String peerName = AiAudioBridge.getInstance().getMemory().getPeerName();
        String prevRole = "";
        int maxBubbleWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.76f);
        if (maxBubbleWidth <= 0) {
            maxBubbleWidth = AndroidUtilities.dp(270);
        }

        for (int i = 0; i < entries.size(); i++) {
            CallContextMemory.ChatEntry e = entries.get(i);
            if ("SYSTEM".equals(e.role)) {
                TextView sysPill = new TextView(context);
                sysPill.setText(e.text);
                sysPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                sysPill.setTextColor(0xFF9FB2C6);
                sysPill.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(4), AndroidUtilities.dp(12), AndroidUtilities.dp(4));
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(AndroidUtilities.dp(12));
                bg.setColor(0x661E2C3A);
                sysPill.setBackground(bg);

                LinearLayout wrap = new LinearLayout(context);
                wrap.setGravity(Gravity.CENTER_HORIZONTAL);
                wrap.addView(sysPill);
                chatMessagesContainer.addView(wrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 8));
                prevRole = e.role;
                continue;
            }

            boolean isCaller = "CALLER".equals(e.role);
            boolean isOwner = "OWNER".equals(e.role);
            boolean showSender = !e.role.equals(prevRole);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(isCaller ? Gravity.START : Gravity.END);

            LinearLayout bubble = new LinearLayout(context);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(AndroidUtilities.dp(13), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(7));
            bubble.setBackground(createTelegramBubbleBg(isCaller, isOwner));

            if (showSender) {
                TextView senderTag = new TextView(context);
                if (isCaller) {
                    senderTag.setText(peerName != null && !peerName.isEmpty() ? peerName : "Caller");
                    senderTag.setTextColor(0xFF64B5F6);
                } else if (isOwner) {
                    senderTag.setText("You");
                    senderTag.setTextColor(0xFF86EFAC);
                } else {
                    senderTag.setText("AI Assistant");
                    senderTag.setTextColor(0xFFBAE6FD);
                }
                senderTag.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                senderTag.setTypeface(AndroidUtilities.bold());
                bubble.addView(senderTag, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 2));
            }

            TextView msgView = new TextView(context);
            msgView.setText(e.text);
            msgView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            msgView.setTextColor(Color.WHITE);
            msgView.setMaxWidth(maxBubbleWidth);
            msgView.setLineSpacing(AndroidUtilities.dp(2), 1f);
            bubble.addView(msgView);

            TextView timeView = new TextView(context);
            timeView.setText(msgTimeFmt.format(new Date(e.timestampMs)));
            timeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            timeView.setTextColor(isCaller ? 0xFF7D90A4 : 0xB3DCEBFA);
            timeView.setGravity(Gravity.END);
            bubble.addView(timeView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            row.addView(bubble, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            chatMessagesContainer.addView(row, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, showSender ? 7 : 2, 0, 2));
            prevRole = e.role;
        }

        chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private Drawable createTelegramBubbleBg(boolean isCaller, boolean isOwner) {
        GradientDrawable gd;
        if (isCaller) {
            gd = new GradientDrawable();
            gd.setColor(0xFF1A2634);
            gd.setCornerRadii(new float[]{
                    r(18), r(18),
                    r(18), r(18),
                    r(18), r(18),
                    r(5), r(5)
            });
        } else if (isOwner) {
            gd = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF195E4B, 0xFF1D6E58});
            gd.setCornerRadii(new float[]{
                    r(18), r(18),
                    r(18), r(18),
                    r(5), r(5),
                    r(18), r(18)
            });
        } else {
            gd = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFF2B5278, 0xFF346796});
            gd.setCornerRadii(new float[]{
                    r(18), r(18),
                    r(18), r(18),
                    r(5), r(5),
                    r(18), r(18)
            });
        }
        return gd;
    }

    private static Drawable createCircleRipple(int fillColor, int rippleColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(fillColor);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, shape);
    }

    private static float r(int dp) {
        return AndroidUtilities.dp(dp);
    }

    private static String getInitials(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "?";
        }
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, 1).toUpperCase(Locale.US);
        }
        return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase(Locale.US);
    }
}
