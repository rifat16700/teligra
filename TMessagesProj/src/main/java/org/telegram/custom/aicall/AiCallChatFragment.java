package org.telegram.custom.aicall;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Live Chat-Style Calling UI.
 * Displays live caller vs AI/Owner conversation bubbles in real time,
 * with reversible Take Over / Return to AI, Anonymous Listen-In, and End Call controls.
 */
public class AiCallChatFragment extends BaseFragment implements CallContextMemory.ChatUpdateListener {

    private TextView statusBannerView;
    private ScrollView chatScrollView;
    private LinearLayout messagesContainer;
    private TextView handoverBtn;
    private TextView listenBtn;
    private TextView endCallBtn;

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Live AI Call Chat");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        statusBannerView = new TextView(context);
        statusBannerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusBannerView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        statusBannerView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        statusBannerView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        root.addView(statusBannerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        View topDivider = new View(context);
        topDivider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        root.addView(topDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        chatScrollView = new ScrollView(context);
        chatScrollView.setFillViewport(true);
        root.addView(chatScrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        messagesContainer = new LinearLayout(context);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);
        messagesContainer.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        chatScrollView.addView(messagesContainer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View bottomDivider = new View(context);
        bottomDivider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        root.addView(bottomDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        LinearLayout controlsBar = new LinearLayout(context);
        controlsBar.setOrientation(LinearLayout.HORIZONTAL);
        controlsBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        controlsBar.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        root.addView(controlsBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        handoverBtn = createActionButton(context, 0xFF3E7BFA, 0xFF2B62D6);
        handoverBtn.setOnClickListener(v -> {
            AiCallSessionState state = AiCallController.getInstance().getState();
            if (state.getMode() == AiCallSessionState.Mode.IDLE) return;
            if (state.isAiActive()) {
                AiCallActionBus.dispatch(AiCallActionBus.ACTION_TAKE_OVER, null);
            } else {
                AiCallActionBus.dispatch(AiCallActionBus.ACTION_HANDOVER_TO_AI, null);
            }
            refreshUI();
        });
        controlsBar.addView(handoverBtn, LayoutHelper.createLinear(0, 44, 1.2f, 0, 0, 6, 0));

        listenBtn = createActionButton(context, 0xFF6B7280, 0xFF4B5563);
        listenBtn.setOnClickListener(v -> {
            AiCallSessionState state = AiCallController.getInstance().getState();
            if (state.getMode() == AiCallSessionState.Mode.IDLE) return;
            Bundle b = new Bundle();
            b.putBoolean(AiCallActionBus.EXTRA_ENABLED, !state.isAnonymousListenIn());
            AiCallActionBus.dispatch(AiCallActionBus.ACTION_TOGGLE_LISTEN_IN, b);
            refreshUI();
        });
        controlsBar.addView(listenBtn, LayoutHelper.createLinear(0, 44, 1.1f, 0, 0, 6, 0));

        endCallBtn = createActionButton(context, 0xFFE53935, 0xFFC62828);
        endCallBtn.setText("End Call");
        endCallBtn.setOnClickListener(v -> {
            AiCallActionBus.dispatch(AiCallActionBus.ACTION_END_CALL, null);
            finishFragment();
        });
        controlsBar.addView(endCallBtn, LayoutHelper.createLinear(0, 44, 0.8f));

        AiAudioBridge.getInstance().getMemory().addListener(this);
        refreshUI();

        fragmentView = root;
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        AiAudioBridge.getInstance().getMemory().removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public void onChatUpdated() {
        refreshUI();
    }

    private void refreshUI() {
        if (messagesContainer == null || getContext() == null) return;
        Context context = getContext();
        AiCallSessionState state = AiCallController.getInstance().getState();
        CallContextMemory memory = AiAudioBridge.getInstance().getMemory();

        actionBar.setSubtitle(memory.getPeerName() + " · " + memory.getSerialId());

        if (state.getMode() == AiCallSessionState.Mode.IDLE) {
            statusBannerView.setText("No active call right now. Start or receive a call to monitor it live here.");
            handoverBtn.setText("Idle");
            handoverBtn.setEnabled(false);
            listenBtn.setText("Listen: OFF");
            listenBtn.setEnabled(false);
            endCallBtn.setEnabled(false);
        } else {
            handoverBtn.setEnabled(true);
            endCallBtn.setEnabled(true);
            if (state.isAiActive()) {
                statusBannerView.setText("Mode: AI Handling (" + AiCallConfig.getInstance().getModelId() + ")"
                        + (state.isAnonymousListenIn() ? " · Anonymous Listen: ON" : " · Silent"));
                handoverBtn.setText("Take Over");
                listenBtn.setEnabled(true);
                listenBtn.setText(state.isAnonymousListenIn() ? "Listen: ON" : "Listen: OFF");
            } else {
                statusBannerView.setText("Mode: Owner Speaking · Background Summary Active");
                handoverBtn.setText("Return to AI");
                listenBtn.setEnabled(false);
                listenBtn.setText("Owner Mic");
            }
        }

        messagesContainer.removeAllViews();
        List<CallContextMemory.ChatEntry> entries = memory.getEntriesSnapshot();
        if (entries.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("Live conversation transcript will appear here as bubbles...");
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(32), AndroidUtilities.dp(16), AndroidUtilities.dp(32));
            messagesContainer.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        } else {
            for (CallContextMemory.ChatEntry entry : entries) {
                messagesContainer.addView(createBubbleView(context, entry),
                        LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 4));
            }
        }
        chatScrollView.post(() -> chatScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private View createBubbleView(Context context, CallContextMemory.ChatEntry entry) {
        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        if ("SYSTEM".equals(entry.role)) {
            wrapper.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView pill = new TextView(context);
            pill.setText(entry.text);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            pill.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            pill.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), 0x22000000));
            pill.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(5), AndroidUtilities.dp(12), AndroidUtilities.dp(5));
            wrapper.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            return wrapper;
        }

        boolean isLeft = "CALLER".equals(entry.role);
        wrapper.setGravity(isLeft ? Gravity.START : Gravity.END);

        LinearLayout bubble = new LinearLayout(context);
        bubble.setOrientation(LinearLayout.VERTICAL);
        int bgColor;
        if (isLeft) {
            bgColor = Theme.getColor(Theme.key_windowBackgroundWhite);
        } else if ("OWNER".equals(entry.role)) {
            bgColor = 0xFFDCF8C6;
        } else {
            bgColor = 0xFFE3ECFF;
        }
        bubble.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(14), bgColor));
        bubble.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));

        TextView metaView = new TextView(context);
        metaView.setText(entry.role + " · " + timeFmt.format(new Date(entry.timestampMs)));
        metaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        metaView.setTextColor(isLeft ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader) : 0xFF334155);
        bubble.addView(metaView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        TextView bodyView = new TextView(context);
        bodyView.setText(entry.text);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        bodyView.setTextColor(isLeft ? Theme.getColor(Theme.key_windowBackgroundWhiteBlackText) : 0xFF111827);
        bodyView.setMaxWidth(AndroidUtilities.dp(270));
        bubble.addView(bodyView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        wrapper.addView(bubble, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        return wrapper;
    }

    private TextView createActionButton(Context context, int bgColor, int pressedColor) {
        TextView btn = new TextView(context);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        btn.setTextColor(0xFFFFFFFF);
        btn.setGravity(Gravity.CENTER);
        btn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10), bgColor, pressedColor));
        btn.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        return btn;
    }
}
