package org.telegram.custom.aicall;

import android.content.Context;
import android.media.MediaPlayer;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.custom.utils.CustomLogger;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Detailed Call Replay Screen:
 * - Serial ID & metadata
 * - Detailed Post-Call AI Summary
 * - Built-in WAV Audio Player
 * - Full Chat-Style Conversation Replay (Caller left, AI/Owner right)
 */
public class AiCallDetailActivity extends BaseFragment {
    private static final String TAG = "AiCallDetailActivity";

    private final AiCallHistoryStore.CallRecord record;
    private MediaPlayer mediaPlayer;
    private boolean playing = false;
    private TextView playBtn;

    public AiCallDetailActivity(AiCallHistoryStore.CallRecord record) {
        this.record = record;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(record.peerName);
        actionBar.setSubtitle(record.serialId);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 1. Metadata + Audio Player Card
        LinearLayout topCard = new LinearLayout(context);
        topCard.setOrientation(LinearLayout.VERTICAL);
        topCard.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        topCard.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(14));
        root.addView(topCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        String dateStr = new SimpleDateFormat("dd MMM yyyy · HH:mm:ss", Locale.US).format(new Date(record.startedAtMs));
        TextView meta = new TextView(context);
        meta.setText("Serial ID: " + record.serialId + "\nDate: " + dateStr
                + "\nDirection: " + record.direction + " · Duration: " + record.durationSec + "s · Handovers: " + record.handoverCount);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        meta.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        topCard.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        playBtn = new TextView(context);
        boolean hasAudio = !TextUtils.isEmpty(record.audioFilePath) && new File(record.audioFilePath).exists();
        playBtn.setText(hasAudio ? "▶ Play Call Recording (" + record.durationSec + "s)" : "No Audio Recording Available");
        playBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        playBtn.setGravity(Gravity.CENTER);
        playBtn.setTextColor(0xFFFFFFFF);
        playBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(8),
                hasAudio ? Theme.getColor(Theme.key_featuredStickers_addButton) : 0xFF9CA3AF,
                hasAudio ? Theme.getColor(Theme.key_featuredStickers_addButtonPressed) : 0xFF9CA3AF
        ));
        playBtn.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        playBtn.setOnClickListener(v -> togglePlayback());
        topCard.addView(playBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 12, 0, 0));

        // 2. Detailed AI Summary Card
        LinearLayout summaryCard = new LinearLayout(context);
        summaryCard.setOrientation(LinearLayout.VERTICAL);
        summaryCard.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        summaryCard.setPadding(0, 0, 0, AndroidUtilities.dp(14));
        root.addView(summaryCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        HeaderCell sumHeader = new HeaderCell(context);
        sumHeader.setText("Detailed AI Call Summary");
        summaryCard.addView(sumHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView sumBody = new TextView(context);
        sumBody.setText(TextUtils.isEmpty(record.detailedSummary) ? "No summary generated." : record.detailedSummary);
        sumBody.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        sumBody.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        sumBody.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(4), AndroidUtilities.dp(21), 0);
        sumBody.setTextIsSelectable(true);
        summaryCard.addView(sumBody, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // 3. Full Chat-Style Conversation Replay
        HeaderCell chatHeader = new HeaderCell(context);
        chatHeader.setText("Full Conversation Replay");
        chatHeader.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        root.addView(chatHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        LinearLayout chatContainer = new LinearLayout(context);
        chatContainer.setOrientation(LinearLayout.VERTICAL);
        chatContainer.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(20));
        root.addView(chatContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (record.messages == null || record.messages.isEmpty()) {
            TextView noChat = new TextView(context);
            noChat.setText("No chat transcript lines recorded for this call.");
            noChat.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            noChat.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16));
            chatContainer.addView(noChat);
        } else {
            SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.US);
            for (CallContextMemory.ChatEntry e : record.messages) {
                LinearLayout wrap = new LinearLayout(context);
                wrap.setOrientation(LinearLayout.VERTICAL);
                if ("SYSTEM".equals(e.role)) {
                    wrap.setGravity(Gravity.CENTER_HORIZONTAL);
                    TextView pill = new TextView(context);
                    pill.setText(e.text);
                    pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                    pill.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                    pill.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), 0x22000000));
                    pill.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(10), AndroidUtilities.dp(4));
                    wrap.addView(pill);
                } else {
                    boolean isLeft = "CALLER".equals(e.role);
                    wrap.setGravity(isLeft ? Gravity.START : Gravity.END);
                    LinearLayout bubble = new LinearLayout(context);
                    bubble.setOrientation(LinearLayout.VERTICAL);
                    int bg = isLeft ? Theme.getColor(Theme.key_windowBackgroundWhite) : ("OWNER".equals(e.role) ? 0xFFDCF8C6 : 0xFFE3ECFF);
                    bubble.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(14), bg));
                    bubble.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));

                    TextView metaTxt = new TextView(context);
                    metaTxt.setText(e.role + " · " + tf.format(new Date(e.timestampMs)));
                    metaTxt.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                    metaTxt.setTextColor(isLeft ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader) : 0xFF334155);
                    bubble.addView(metaTxt);

                    TextView bodyTxt = new TextView(context);
                    bodyTxt.setText(e.text);
                    bodyTxt.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    bodyTxt.setTextColor(isLeft ? Theme.getColor(Theme.key_windowBackgroundWhiteBlackText) : 0xFF111827);
                    bodyTxt.setMaxWidth(AndroidUtilities.dp(270));
                    bodyTxt.setTextIsSelectable(true);
                    bubble.addView(bodyTxt);
                    wrap.addView(bubble);
                }
                chatContainer.addView(wrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 4));
            }
        }

        fragmentView = scrollView;
        return fragmentView;
    }

    private void togglePlayback() {
        if (TextUtils.isEmpty(record.audioFilePath) || !new File(record.audioFilePath).exists()) {
            BulletinFactory.of(this).createErrorBulletin("Audio recording file not found").show();
            return;
        }
        if (playing) {
            stopPlayback();
            return;
        }
        try {
            stopPlayback();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(record.audioFilePath);
            mediaPlayer.setOnCompletionListener(mp -> stopPlayback());
            mediaPlayer.prepare();
            mediaPlayer.start();
            playing = true;
            if (playBtn != null) {
                playBtn.setText("⏹ Stop Playback");
            }
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Error playing WAV recording", t);
            stopPlayback();
            BulletinFactory.of(this).createErrorBulletin("Cannot play recording").show();
        }
    }

    private void stopPlayback() {
        playing = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Throwable ignored) {}
            try {
                mediaPlayer.release();
            } catch (Throwable ignored) {}
            mediaPlayer = null;
        }
        if (playBtn != null && !TextUtils.isEmpty(record.audioFilePath)) {
            playBtn.setText("▶ Play Call Recording (" + record.durationSec + "s)");
        }
    }

    @Override
    public void onFragmentDestroy() {
        stopPlayback();
        super.onFragmentDestroy();
    }
}
