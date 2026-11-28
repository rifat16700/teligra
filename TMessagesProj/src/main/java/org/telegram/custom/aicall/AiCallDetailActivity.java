package org.telegram.custom.aicall;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.custom.utils.CustomLogger;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Premium Telegram-style Call Detail screen with 2 tabs:
 * Tab 1: Recording & Chat Transcript (WAV player + chat bubble replay)
 * Tab 2: AI Summary in Bengali (Bengali Voice Summary player + Text explanation + 2-3 line Caller Memory)
 */
public class AiCallDetailActivity extends BaseFragment {
    private static final String TAG = "AiCallDetailActivity";

    private final AiCallHistoryStore.CallRecord record;
    private MediaPlayer mediaPlayer;
    private int playingKind = 0; // 0 = none, 1 = call wav, 2 = summary wav

    private TextView tabRecordBtn;
    private TextView tabSummaryBtn;
    private View tabRecordIndicator;
    private View tabSummaryIndicator;
    private LinearLayout recordTabContent;
    private LinearLayout summaryTabContent;

    private TextView playCallBtn;
    private TextView playSummaryVoiceBtn;
    private TextView rollingMemoryView;

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

        LinearLayout outerRoot = new LinearLayout(context);
        outerRoot.setOrientation(LinearLayout.VERTICAL);
        outerRoot.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        // --- Top 2-Tab Bar ---
        LinearLayout tabBar = new LinearLayout(context);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        outerRoot.addView(tabBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

        LinearLayout tab1Wrap = new LinearLayout(context);
        tab1Wrap.setOrientation(LinearLayout.VERTICAL);
        tabRecordBtn = new TextView(context);
        tabRecordBtn.setText("Recording & Chat");
        tabRecordBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tabRecordBtn.setTypeface(AndroidUtilities.bold());
        tabRecordBtn.setGravity(Gravity.CENTER);
        tab1Wrap.addView(tabRecordBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        tabRecordIndicator = new View(context);
        tabRecordIndicator.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        tab1Wrap.addView(tabRecordIndicator, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 3));
        tab1Wrap.setOnClickListener(v -> selectTab(0));
        tabBar.addView(tab1Wrap, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));

        LinearLayout tab2Wrap = new LinearLayout(context);
        tab2Wrap.setOrientation(LinearLayout.VERTICAL);
        tabSummaryBtn = new TextView(context);
        tabSummaryBtn.setText("AI Summary (বাংলা)");
        tabSummaryBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tabSummaryBtn.setTypeface(AndroidUtilities.bold());
        tabSummaryBtn.setGravity(Gravity.CENTER);
        tab2Wrap.addView(tabSummaryBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        tabSummaryIndicator = new View(context);
        tabSummaryIndicator.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        tab2Wrap.addView(tabSummaryIndicator, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 3));
        tab2Wrap.setOnClickListener(v -> selectTab(1));
        tabBar.addView(tab2Wrap, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));

        View div = new View(context);
        div.setBackgroundColor(Theme.getColor(Theme.key_divider));
        outerRoot.addView(div, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        // --- Scrollable Tab Body ---
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        outerRoot.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        FrameLayout bodyHost = new FrameLayout(context);
        scrollView.addView(bodyHost, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        recordTabContent = buildRecordAndChatTab(context);
        summaryTabContent = buildBengaliSummaryTab(context);
        bodyHost.addView(recordTabContent, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        bodyHost.addView(summaryTabContent, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        selectTab(0);
        fragmentView = outerRoot;
        return fragmentView;
    }

    private LinearLayout buildRecordAndChatTab(Context context) {
        LinearLayout tab = new LinearLayout(context);
        tab.setOrientation(LinearLayout.VERTICAL);

        // Call Recording Player Card
        LinearLayout playerCard = new LinearLayout(context);
        playerCard.setOrientation(LinearLayout.VERTICAL);
        playerCard.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        playerCard.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(14));
        tab.addView(playerCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        String dateStr = new SimpleDateFormat("dd MMM yyyy · HH:mm:ss", Locale.US).format(new Date(record.startedAtMs));
        TextView meta = new TextView(context);
        meta.setText("Serial ID: " + record.serialId + "\n" + dateStr + " · " + record.direction
                + " · " + record.durationSec + "s · Handovers: " + record.handoverCount);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        meta.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        playerCard.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        boolean hasCallAudio = !TextUtils.isEmpty(record.audioFilePath) && new File(record.audioFilePath).exists();
        playCallBtn = createPillButton(context,
                hasCallAudio ? "▶ Play Call Recording (" + record.durationSec + "s)" : "No Call Audio File",
                hasCallAudio);
        playCallBtn.setOnClickListener(v -> togglePlayback(1, record.audioFilePath));
        playerCard.addView(playCallBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 12, 0, 0));

        // Full Conversation Chat Transcript
        HeaderCell chatHeader = new HeaderCell(context);
        chatHeader.setText("Call Transcript (Chat View)");
        chatHeader.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        tab.addView(chatHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        LinearLayout chatContainer = new LinearLayout(context);
        chatContainer.setOrientation(LinearLayout.VERTICAL);
        chatContainer.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(20));
        tab.addView(chatContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (record.messages == null || record.messages.isEmpty()) {
            TextView noChat = new TextView(context);
            noChat.setText("No speech transcript lines recorded for this call.");
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
                    GradientDrawable gd = new GradientDrawable();
                    gd.setColor(bg);
                    gd.setCornerRadius(AndroidUtilities.dp(16));
                    bubble.setBackground(gd);
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
                    bodyTxt.setMaxWidth(AndroidUtilities.dp(275));
                    bodyTxt.setTextIsSelectable(true);
                    bubble.addView(bodyTxt);
                    wrap.addView(bubble);
                }
                chatContainer.addView(wrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 4));
            }
        }
        return tab;
    }

    private LinearLayout buildBengaliSummaryTab(Context context) {
        LinearLayout tab = new LinearLayout(context);
        tab.setOrientation(LinearLayout.VERTICAL);

        // 1. Bengali AI Voice Summary Player
        LinearLayout voiceCard = new LinearLayout(context);
        voiceCard.setOrientation(LinearLayout.VERTICAL);
        voiceCard.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        voiceCard.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(14));
        tab.addView(voiceCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HeaderCell voiceHeader = new HeaderCell(context, Theme.key_windowBackgroundWhiteBlueHeader, 0, 0, false);
        voiceHeader.setText("বাংলা ভয়েস সামারি (AI Explanation)");
        voiceCard.addView(voiceHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        boolean hasSummaryAudio = !TextUtils.isEmpty(record.summaryAudioFilePath) && new File(record.summaryAudioFilePath).exists();
        playSummaryVoiceBtn = createPillButton(context,
                hasSummaryAudio ? "🔊 বাংলা ভয়েস সামারি শুনুন" : "Voice Summary Unavailable",
                hasSummaryAudio);
        playSummaryVoiceBtn.setOnClickListener(v -> togglePlayback(2, record.summaryAudioFilePath));
        voiceCard.addView(playSummaryVoiceBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 8, 0, 0));

        // 2. Detailed Bengali Text Summary
        LinearLayout textCard = new LinearLayout(context);
        textCard.setOrientation(LinearLayout.VERTICAL);
        textCard.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        textCard.setPadding(0, 0, 0, AndroidUtilities.dp(16));
        tab.addView(textCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        HeaderCell sumHeader = new HeaderCell(context);
        sumHeader.setText("বিস্তারিত বাংলা টেক্সট সামারি");
        textCard.addView(sumHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView sumBody = new TextView(context);
        sumBody.setText(TextUtils.isEmpty(record.detailedSummary) ? "কোনো সামারি পাওয়া যায়নি।" : record.detailedSummary);
        sumBody.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        sumBody.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        sumBody.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(4), AndroidUtilities.dp(21), 0);
        sumBody.setTextIsSelectable(true);
        textCard.addView(sumBody, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // 3. Per-Caller Short Rolling Memory & Caller Data
        LinearLayout memCard = new LinearLayout(context);
        memCard.setOrientation(LinearLayout.VERTICAL);
        memCard.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        memCard.setPadding(0, 0, 0, AndroidUtilities.dp(16));
        tab.addView(memCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        HeaderCell memHeader = new HeaderCell(context);
        memHeader.setText("কলারের শর্ট মেমোরি ও পার্সোনাল ডেটা");
        memCard.addView(memHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        rollingMemoryView = new TextView(context);
        String mem = CallerProfileStore.getInstance().getRollingMemory(record.peerUserId);
        String cData = CallerProfileStore.getInstance().getCallerData(record.peerUserId);
        rollingMemoryView.setText("2-3 Line Auto Memory:\n" + (mem.isEmpty() ? "(Empty)" : mem)
                + "\n\nManual Caller Data:\n" + (cData.isEmpty() ? "(Not set)" : cData));
        rollingMemoryView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        rollingMemoryView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        rollingMemoryView.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(4), AndroidUtilities.dp(21), AndroidUtilities.dp(10));
        memCard.addView(rollingMemoryView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (record.peerUserId != 0L) {
            TextView editCallerBtn = createPillButton(context, "Edit Caller Data for " + record.peerName, true);
            editCallerBtn.setOnClickListener(v -> showEditCallerDataDialog(context));
            memCard.addView(editCallerBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 21, 0, 21, 0));
        }

        TextInfoPrivacyCell note = new TextInfoPrivacyCell(context);
        note.setText("Auto 2-3 line memory acts only as light background context for future calls and never overrides your primary Owner Data.");
        tab.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return tab;
    }

    private void showEditCallerDataDialog(Context context) {
        final CallerProfileStore store = CallerProfileStore.getInstance();
        final EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setHint("e.g. Boss, speak formally; or Friend, casual tone...");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setText(store.getCallerData(record.peerUserId));

        FrameLayout box = new FrameLayout(context);
        box.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(6), AndroidUtilities.dp(20), AndroidUtilities.dp(6));
        box.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Caller Data: " + record.peerName);
        builder.setView(box);
        builder.setPositiveButton("Save", (d, w) -> {
            String val = input.getText() != null ? input.getText().toString().trim() : "";
            store.setCallerData(record.peerUserId, val);
            String mem = store.getRollingMemory(record.peerUserId);
            rollingMemoryView.setText("2-3 Line Auto Memory:\n" + (mem.isEmpty() ? "(Empty)" : mem)
                    + "\n\nManual Caller Data:\n" + (val.isEmpty() ? "(Not set)" : val));
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, "Caller data saved").show();
        });
        builder.setNeutralButton("Clear Auto Memory", (d, w) -> {
            store.clearRollingMemory(record.peerUserId);
            String cData = store.getCallerData(record.peerUserId);
            rollingMemoryView.setText("2-3 Line Auto Memory:\n(Cleared)\n\nManual Caller Data:\n" + (cData.isEmpty() ? "(Not set)" : cData));
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void selectTab(int index) {
        int activeText = Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader);
        int inactiveText = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        if (index == 0) {
            recordTabContent.setVisibility(View.VISIBLE);
            summaryTabContent.setVisibility(View.GONE);
            tabRecordBtn.setTextColor(activeText);
            tabSummaryBtn.setTextColor(inactiveText);
            tabRecordIndicator.setVisibility(View.VISIBLE);
            tabSummaryIndicator.setVisibility(View.INVISIBLE);
        } else {
            recordTabContent.setVisibility(View.GONE);
            summaryTabContent.setVisibility(View.VISIBLE);
            tabRecordBtn.setTextColor(inactiveText);
            tabSummaryBtn.setTextColor(activeText);
            tabRecordIndicator.setVisibility(View.INVISIBLE);
            tabSummaryIndicator.setVisibility(View.VISIBLE);
        }
    }

    private void togglePlayback(int kind, String path) {
        if (TextUtils.isEmpty(path) || !new File(path).exists()) {
            BulletinFactory.of(this).createErrorBulletin("Audio file not found").show();
            return;
        }
        if (playingKind == kind) {
            stopPlayback();
            return;
        }
        try {
            stopPlayback();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(path);
            mediaPlayer.setOnCompletionListener(mp -> stopPlayback());
            mediaPlayer.prepare();
            mediaPlayer.start();
            playingKind = kind;
            updatePlayButtons();
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Error playing audio", t);
            stopPlayback();
            BulletinFactory.of(this).createErrorBulletin("Cannot play audio").show();
        }
    }

    private void stopPlayback() {
        playingKind = 0;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Throwable ignored) {}
            try {
                mediaPlayer.release();
            } catch (Throwable ignored) {}
            mediaPlayer = null;
        }
        updatePlayButtons();
    }

    private void updatePlayButtons() {
        if (playCallBtn != null) {
            boolean hasCall = !TextUtils.isEmpty(record.audioFilePath) && new File(record.audioFilePath).exists();
            if (playingKind == 1) {
                playCallBtn.setText("⏹ Stop Call Recording");
            } else {
                playCallBtn.setText(hasCall ? "▶ Play Call Recording (" + record.durationSec + "s)" : "No Call Audio File");
            }
        }
        if (playSummaryVoiceBtn != null) {
            boolean hasSum = !TextUtils.isEmpty(record.summaryAudioFilePath) && new File(record.summaryAudioFilePath).exists();
            if (playingKind == 2) {
                playSummaryVoiceBtn.setText("⏹ Stop Voice Summary");
            } else {
                playSummaryVoiceBtn.setText(hasSum ? "🔊 বাংলা ভয়েস সামারি শুনুন" : "Voice Summary Unavailable");
            }
        }
    }

    private TextView createPillButton(Context context, String label, boolean enabled) {
        TextView btn = new TextView(context);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        btn.setTypeface(AndroidUtilities.bold());
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(0xFFFFFFFF);
        btn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(10),
                enabled ? Theme.getColor(Theme.key_featuredStickers_addButton) : 0xFF9CA3AF,
                enabled ? Theme.getColor(Theme.key_featuredStickers_addButtonPressed) : 0xFF9CA3AF
        ));
        btn.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        return btn;
    }

    @Override
    public void onFragmentDestroy() {
        stopPlayback();
        super.onFragmentDestroy();
    }
}
