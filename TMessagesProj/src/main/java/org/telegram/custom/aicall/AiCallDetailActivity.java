package org.telegram.custom.aicall;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Native Telegram-style Call Detail screen with two tabs:
 * 1. Recording & Chat: Telegram voice-note style audio player + authentic asymmetric chat bubbles.
 * 2. বাংলা সামারি: Bengali Voice Summary player + formatted Bengali explanation + Caller Memory card.
 */
public class AiCallDetailActivity extends BaseFragment {
    private static final String TAG = "AiCallDetailActivity";

    private final AiCallHistoryStore.CallRecord record;
    private MediaPlayer mediaPlayer;
    private int playingKind = 0; // 0 = none, 1 = call wav, 2 = summary wav

    private TextView tabRecordBtn;
    private TextView tabSummaryBtn;
    private LinearLayout recordTabContent;
    private LinearLayout summaryTabContent;

    private TextView playCallCircleBtn;
    private TextView playSummaryCircleBtn;
    private TextView callPlayerSubtitle;
    private TextView summaryPlayerSubtitle;
    private TextView callerDataBody;
    private TextView rollingMemoryView;

    public AiCallDetailActivity(AiCallHistoryStore.CallRecord record) {
        this.record = record;
    }

    @Override
    public View createView(Context context) {
        SimpleDateFormat dateFmt = new SimpleDateFormat("dd MMM, HH:mm", Locale.US);
        String subtitle = formatDuration(record.durationSec) + " · " + dateFmt.format(new Date(record.startedAtMs));

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(record.peerName != null && !record.peerName.isEmpty() ? record.peerName : "Caller");
        actionBar.setSubtitle(subtitle);
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

        // --- Segmented Telegram Pill Switcher ---
        FrameLayout pillOuter = new FrameLayout(context);
        pillOuter.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        pillOuter.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        outerRoot.addView(pillOuter, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout pillTrack = new LinearLayout(context);
        pillTrack.setOrientation(LinearLayout.HORIZONTAL);
        pillTrack.setPadding(AndroidUtilities.dp(3), AndroidUtilities.dp(3), AndroidUtilities.dp(3), AndroidUtilities.dp(3));
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setCornerRadius(AndroidUtilities.dp(12));
        trackBg.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
        pillTrack.setBackground(trackBg);
        pillOuter.addView(pillTrack, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40));

        tabRecordBtn = new TextView(context);
        tabRecordBtn.setText("Recording & Chat");
        tabRecordBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        tabRecordBtn.setTypeface(AndroidUtilities.bold());
        tabRecordBtn.setGravity(Gravity.CENTER);
        tabRecordBtn.setOnClickListener(v -> selectTab(0));
        pillTrack.addView(tabRecordBtn, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));

        tabSummaryBtn = new TextView(context);
        tabSummaryBtn.setText("বাংলা সামারি");
        tabSummaryBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        tabSummaryBtn.setTypeface(AndroidUtilities.bold());
        tabSummaryBtn.setGravity(Gravity.CENTER);
        tabSummaryBtn.setOnClickListener(v -> selectTab(1));
        pillTrack.addView(tabSummaryBtn, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));

        View topDivider = new View(context);
        topDivider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        outerRoot.addView(topDivider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));

        // --- Content ScrollView ---
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        outerRoot.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        FrameLayout tabContainer = new FrameLayout(context);
        scroll.addView(tabContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        recordTabContent = buildRecordTab(context);
        summaryTabContent = buildSummaryTab(context);
        tabContainer.addView(recordTabContent, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        tabContainer.addView(summaryTabContent, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        selectTab(0);
        fragmentView = outerRoot;
        return fragmentView;
    }

    private LinearLayout buildRecordTab(Context context) {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(24));

        // Call Recording Player Card
        boolean hasRecording = record.audioFilePath != null && new File(record.audioFilePath).exists();
        LinearLayout playerCard = createCard(context);
        playerCard.setOrientation(LinearLayout.HORIZONTAL);
        playerCard.setGravity(Gravity.CENTER_VERTICAL);
        playerCard.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));

        playCallCircleBtn = new TextView(context);
        playCallCircleBtn.setText("▶");
        playCallCircleBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        playCallCircleBtn.setTextColor(Color.WHITE);
        playCallCircleBtn.setGravity(Gravity.CENTER);
        playCallCircleBtn.setBackground(createCircleRipple(hasRecording ? 0xFF2AABEE : 0xFF94A3B8, 0x44FFFFFF));
        playCallCircleBtn.setEnabled(hasRecording);
        playCallCircleBtn.setOnClickListener(v -> togglePlayback(1, record.audioFilePath));
        playerCard.addView(playCallCircleBtn, LayoutHelper.createLinear(44, 44, 0, 0, 14, 0));

        LinearLayout infoCol = new LinearLayout(context);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        playerCard.addView(infoCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView title = new TextView(context);
        title.setText("Call Recording");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        infoCol.addView(title);

        callPlayerSubtitle = new TextView(context);
        callPlayerSubtitle.setText(hasRecording
                ? ("WAV Audio · " + formatDuration(record.durationSec) + " · Tap to play")
                : "No local WAV recording saved for this call");
        callPlayerSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        callPlayerSubtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        infoCol.addView(callPlayerSubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        wrap.addView(playerCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        // Date / Serial pill centered above bubbles
        if (record.serialId != null && !record.serialId.isEmpty()) {
            TextView serialPill = new TextView(context);
            serialPill.setText("Call ID · " + record.serialId);
            serialPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            serialPill.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            serialPill.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(4), AndroidUtilities.dp(12), AndroidUtilities.dp(4));
            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setCornerRadius(AndroidUtilities.dp(12));
            pillBg.setColor(isDarkTheme() ? 0x26FFFFFF : 0x12000000);
            serialPill.setBackground(pillBg);

            LinearLayout centerWrap = new LinearLayout(context);
            centerWrap.setGravity(Gravity.CENTER_HORIZONTAL);
            centerWrap.addView(serialPill);
            wrap.addView(centerWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
        }

        if (record.messages == null || record.messages.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("No live transcript messages were captured for this call.");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(32), AndroidUtilities.dp(16), AndroidUtilities.dp(24));
            wrap.addView(empty);
        } else {
            boolean dark = isDarkTheme();
            SimpleDateFormat msgTimeFmt = new SimpleDateFormat("HH:mm", Locale.US);
            int maxWidth = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.78f);
            if (maxWidth <= 0) {
                maxWidth = AndroidUtilities.dp(275);
            }
            String prevRole = "";

            for (CallContextMemory.ChatEntry e : record.messages) {
                if ("SYSTEM".equals(e.role)) {
                    TextView sys = new TextView(context);
                    sys.setText(e.text);
                    sys.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                    sys.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                    sys.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(4), AndroidUtilities.dp(12), AndroidUtilities.dp(4));
                    GradientDrawable sBg = new GradientDrawable();
                    sBg.setCornerRadius(AndroidUtilities.dp(12));
                    sBg.setColor(dark ? 0x22FFFFFF : 0x10000000);
                    sys.setBackground(sBg);

                    LinearLayout center = new LinearLayout(context);
                    center.setGravity(Gravity.CENTER_HORIZONTAL);
                    center.addView(sys);
                    wrap.addView(center, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 6));
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
                bubble.setBackground(createTranscriptBubbleBg(dark, isCaller, isOwner));

                if (showSender) {
                    TextView sender = new TextView(context);
                    if (isCaller) {
                        sender.setText(record.peerName != null && !record.peerName.isEmpty() ? record.peerName : "Caller");
                        sender.setTextColor(dark ? 0xFF64B5F6 : 0xFF1D74D2);
                    } else if (isOwner) {
                        sender.setText("You");
                        sender.setTextColor(dark ? 0xFF86EFAC : 0xFF15803D);
                    } else {
                        sender.setText("AI Assistant");
                        sender.setTextColor(dark ? 0xFFBAE6FD : 0xFF0369A1);
                    }
                    sender.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                    sender.setTypeface(AndroidUtilities.bold());
                    bubble.addView(sender, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 2));
                }

                TextView msgTv = new TextView(context);
                msgTv.setText(e.text);
                msgTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                msgTv.setTextColor(dark ? Color.WHITE : 0xFF111827);
                msgTv.setMaxWidth(maxWidth);
                msgTv.setLineSpacing(AndroidUtilities.dp(2), 1f);
                msgTv.setTextIsSelectable(true);
                bubble.addView(msgTv);

                TextView tsTv = new TextView(context);
                tsTv.setText(msgTimeFmt.format(new Date(e.timestampMs)));
                tsTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                tsTv.setTextColor(dark ? 0x99DCEBFA : 0xFF64748B);
                tsTv.setGravity(Gravity.END);
                bubble.addView(tsTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

                row.addView(bubble, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
                wrap.addView(row, LayoutHelper.createLinear(
                        LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, showSender ? 6 : 2, 0, 2));
                prevRole = e.role;
            }
        }
        return wrap;
    }

    private LinearLayout buildSummaryTab(Context context) {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(24));

        // Bengali Voice Summary Card
        boolean hasVoice = record.summaryAudioFilePath != null && new File(record.summaryAudioFilePath).exists();
        LinearLayout voiceCard = createCard(context);
        voiceCard.setOrientation(LinearLayout.HORIZONTAL);
        voiceCard.setGravity(Gravity.CENTER_VERTICAL);
        voiceCard.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));

        playSummaryCircleBtn = new TextView(context);
        playSummaryCircleBtn.setText("▶");
        playSummaryCircleBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        playSummaryCircleBtn.setTextColor(Color.WHITE);
        playSummaryCircleBtn.setGravity(Gravity.CENTER);
        playSummaryCircleBtn.setBackground(createCircleRipple(hasVoice ? 0xFF10B981 : 0xFF94A3B8, 0x44FFFFFF));
        playSummaryCircleBtn.setEnabled(hasVoice);
        playSummaryCircleBtn.setOnClickListener(v -> togglePlayback(2, record.summaryAudioFilePath));
        voiceCard.addView(playSummaryCircleBtn, LayoutHelper.createLinear(44, 44, 0, 0, 14, 0));

        LinearLayout vCol = new LinearLayout(context);
        vCol.setOrientation(LinearLayout.VERTICAL);
        voiceCard.addView(vCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView vTitle = new TextView(context);
        vTitle.setText("বাংলা ভয়েস সামারি");
        vTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        vTitle.setTypeface(AndroidUtilities.bold());
        vTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        vCol.addView(vTitle);

        summaryPlayerSubtitle = new TextView(context);
        summaryPlayerSubtitle.setText(hasVoice
                ? "AI Bengali Audio Explanation · Tap to play"
                : "Bengali voice explanation not available for this call");
        summaryPlayerSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        summaryPlayerSubtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        vCol.addView(summaryPlayerSubtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        wrap.addView(voiceCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // Detailed Bengali Text Summary Card
        LinearLayout textCard = createCard(context);
        textCard.setOrientation(LinearLayout.VERTICAL);

        HeaderCell sumHeader = new HeaderCell(context);
        sumHeader.setText("কল সামারি (বাংলা)");
        textCard.addView(sumHeader);

        TextView sumBody = new TextView(context);
        String bodyText = (record.detailedSummary != null && !record.detailedSummary.trim().isEmpty())
                ? record.detailedSummary.trim()
                : "এই কলটির বিস্তারিত বাংলা সামারি এখনো পাওয়া যায়নি।";
        sumBody.setText(bodyText);
        sumBody.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        sumBody.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        sumBody.setLineSpacing(AndroidUtilities.dp(4.5f), 1f);
        sumBody.setTextIsSelectable(true);
        sumBody.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(4), AndroidUtilities.dp(18), AndroidUtilities.dp(16));
        textCard.addView(sumBody);
        wrap.addView(textCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // Caller Context & 2-3 Line Rolling Memory Card
        LinearLayout callerCard = createCard(context);
        callerCard.setOrientation(LinearLayout.VERTICAL);

        HeaderCell memHeader = new HeaderCell(context);
        memHeader.setText("Caller Context & Memory");
        callerCard.addView(memHeader);

        CallerProfileStore profileStore = CallerProfileStore.getInstance();
        String manualData = profileStore.getCallerData(record.peerUserId);
        String rolling = profileStore.getRollingMemory(record.peerUserId);

        callerDataBody = new TextView(context);
        callerDataBody.setText(manualData.isEmpty()
                ? "Caller Note: Not set (tap Edit Note below to tell AI how to talk with this person)."
                : ("Caller Note: " + manualData));
        callerDataBody.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        callerDataBody.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        callerDataBody.setLineSpacing(AndroidUtilities.dp(3), 1f);
        callerDataBody.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(2), AndroidUtilities.dp(18), AndroidUtilities.dp(8));
        callerCard.addView(callerDataBody);

        rollingMemoryView = new TextView(context);
        rollingMemoryView.setText(rolling.isEmpty()
                ? "Past Call Memory (2-3 Lines): Empty"
                : ("Past Call Memory:\n" + rolling));
        rollingMemoryView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        rollingMemoryView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        rollingMemoryView.setLineSpacing(AndroidUtilities.dp(3), 1f);
        rollingMemoryView.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(18), AndroidUtilities.dp(12));
        callerCard.addView(rollingMemoryView);

        LinearLayout actionRow = new LinearLayout(context);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(18), AndroidUtilities.dp(14));

        TextView editNoteBtn = createPillActionButton(context, "Edit Caller Note", 0xFF2AABEE);
        editNoteBtn.setOnClickListener(v -> showEditCallerNoteDialog(context));
        actionRow.addView(editNoteBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 10, 0));

        TextView clearMemBtn = createPillActionButton(context, "Clear Memory", 0xFFEF4444);
        clearMemBtn.setOnClickListener(v -> {
            CallerProfileStore.getInstance().clearRollingMemory(record.peerUserId);
            rollingMemoryView.setText("Past Call Memory (2-3 Lines): Cleared");
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, "Cleared 2-3 line past-call memory").show();
        });
        actionRow.addView(clearMemBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34));

        callerCard.addView(actionRow);
        wrap.addView(callerCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return wrap;
    }

    private LinearLayout createCard(Context context) {
        LinearLayout card = new LinearLayout(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(14));
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        card.setBackground(bg);
        return card;
    }

    private TextView createPillActionButton(Context context, String text, int accentColor) {
        TextView btn = new TextView(context);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        btn.setTypeface(AndroidUtilities.bold());
        btn.setTextColor(accentColor);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(17));
        bg.setColor((accentColor & 0x00FFFFFF) | 0x1A000000);
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf((accentColor & 0x00FFFFFF) | 0x33000000), bg, bg));
        return btn;
    }

    private void showEditCallerNoteDialog(Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Caller Note · " + record.peerName);
        builder.setMessage("Tell AI who this caller is and how to reply to them in future calls.");

        EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        edit.setText(CallerProfileStore.getInstance().getCallerData(record.peerUserId));
        edit.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        edit.setHint("e.g. Close friend — answer casually in Banglish");
        edit.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        edit.setMinLines(3);
        edit.setMaxLines(6);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        int pad = AndroidUtilities.dp(18);
        edit.setPadding(pad, AndroidUtilities.dp(8), pad, AndroidUtilities.dp(8));
        builder.setView(edit);

        builder.setPositiveButton("Save", (d, w) -> {
            String next = edit.getText().toString().trim();
            CallerProfileStore.getInstance().setCallerData(record.peerUserId, next);
            callerDataBody.setText(next.isEmpty()
                    ? "Caller Note: Not set (tap Edit Note below to tell AI how to talk with this person)."
                    : ("Caller Note: " + next));
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, "Caller note saved").show();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void selectTab(int idx) {
        recordTabContent.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        summaryTabContent.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);

        GradientDrawable activePill = new GradientDrawable();
        activePill.setCornerRadius(AndroidUtilities.dp(9));
        activePill.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        if (idx == 0) {
            tabRecordBtn.setBackground(activePill);
            tabRecordBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            tabSummaryBtn.setBackground(null);
            tabSummaryBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        } else {
            tabSummaryBtn.setBackground(activePill);
            tabSummaryBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            tabRecordBtn.setBackground(null);
            tabRecordBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        }
    }

    private void togglePlayback(int kind, String path) {
        if (playingKind == kind) {
            stopAudioPlayback();
            return;
        }
        stopAudioPlayback();
        if (path == null || path.isEmpty() || !new File(path).exists()) {
            return;
        }
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(path);
            mediaPlayer.setOnCompletionListener(mp -> stopAudioPlayback());
            mediaPlayer.prepare();
            mediaPlayer.start();
            playingKind = kind;
            updatePlayButtons();
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Audio playback error for " + path, t);
            stopAudioPlayback();
        }
    }

    private void stopAudioPlayback() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Throwable ignored) {
            }
            mediaPlayer = null;
        }
        playingKind = 0;
        updatePlayButtons();
    }

    private void updatePlayButtons() {
        if (playCallCircleBtn != null) {
            playCallCircleBtn.setText(playingKind == 1 ? "■" : "▶");
        }
        if (playSummaryCircleBtn != null) {
            playSummaryCircleBtn.setText(playingKind == 2 ? "■" : "▶");
        }
    }

    private Drawable createTranscriptBubbleBg(boolean dark, boolean isCaller, boolean isOwner) {
        GradientDrawable gd;
        if (isCaller) {
            gd = new GradientDrawable();
            gd.setColor(dark ? 0xFF1D2938 : 0xFFFFFFFF);
            gd.setCornerRadii(new float[]{
                    r(18), r(18),
                    r(18), r(18),
                    r(18), r(18),
                    r(5), r(5)
            });
        } else if (isOwner) {
            gd = new GradientDrawable();
            gd.setColor(dark ? 0xFF1B5E4B : 0xFFDCFCE7);
            gd.setCornerRadii(new float[]{
                    r(18), r(18),
                    r(18), r(18),
                    r(5), r(5),
                    r(18), r(18)
            });
        } else {
            gd = new GradientDrawable();
            gd.setColor(dark ? 0xFF2B5278 : 0xFFE0F2FE);
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

    private boolean isDarkTheme() {
        int bg = Theme.getColor(Theme.key_windowBackgroundWhite);
        int lum = (Color.red(bg) * 299 + Color.green(bg) * 587 + Color.blue(bg) * 114) / 1000;
        return lum < 128;
    }

    private static float r(int dp) {
        return AndroidUtilities.dp(dp);
    }

    private static String formatDuration(long durationSec) {
        if (durationSec <= 0L) {
            return "0s";
        }
        long mins = durationSec / 60L;
        long secs = durationSec % 60L;
        return mins > 0 ? String.format(Locale.US, "%dm %02ds", mins, secs) : (secs + "s");
    }

    @Override
    public void onFragmentDestroy() {
        stopAudioPlayback();
        super.onFragmentDestroy();
    }
}
