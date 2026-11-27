package org.telegram.custom.aicall;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;
import org.telegram.ui.Components.voip.VoIPHelper;

import java.util.ArrayList;

/**
 * Minimal, native-Telegram Settings screen for AI Auto-Answer Voice Calls.
 * Offers 1-tap pre-built use-case profiles plus full customization of
 * 30 studio voices, emotion styles, Owner Data, and BYOK Gemini Live models.
 */
public class AiCallSettingsActivity extends BaseFragment {

    private static final int REQUEST_CODE_RECORD_AUDIO = 9002;

    private TextCheckCell autoAnswerCheckCell;
    private TextView delayValueView;
    private TextSettingsCell profileCell;
    private TextSettingsCell voiceCell;
    private TextSettingsCell emotionCell;
    private TextSettingsCell ownerDataCell;
    private TextSettingsCell apiKeyCell;
    private TextSettingsCell modelCell;
    private TextCheckCell dualModelCheckCell;
    private TextSettingsCell apiVersionCell;
    private TextCheckCell autoRecordCheckCell;
    private TextCheckCell anonymousListenCheckCell;
    private TextCheckCell backgroundTranscriptCheckCell;
    private TextSettingsCell historyCell;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("AI Auto-Answer Call");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final AiCallConfig config = AiCallConfig.getInstance();

        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- Card 1: General & Pre-Built Profile ---
        LinearLayout generalCard = createWhiteCard(context);
        root.addView(generalCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addHeader(generalCard, context, "General & Use-Case Profile");

        autoAnswerCheckCell = new TextCheckCell(context);
        autoAnswerCheckCell.setTextAndCheck("AI Auto-Answer", config.isAutoAnswerEnabled(), true);
        autoAnswerCheckCell.setOnClickListener(v -> {
            boolean next = !config.isAutoAnswerEnabled();
            if (next && !hasAudioPermission(context)) {
                requestAudioPermission();
            }
            config.setAutoAnswerEnabled(next);
            autoAnswerCheckCell.setChecked(next);
        });
        generalCard.addView(autoAnswerCheckCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout delayRow = new LinearLayout(context);
        delayRow.setOrientation(LinearLayout.VERTICAL);
        delayRow.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(10), AndroidUtilities.dp(21), AndroidUtilities.dp(10));
        generalCard.addView(delayRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout delayLabelRow = new LinearLayout(context);
        delayLabelRow.setOrientation(LinearLayout.HORIZONTAL);
        delayRow.addView(delayLabelRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView delayTitle = new TextView(context);
        delayTitle.setText("Ring Delay");
        delayTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        delayTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        delayLabelRow.addView(delayTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        delayValueView = new TextView(context);
        delayValueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        delayValueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
        delayLabelRow.addView(delayValueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        updateDelayLabel(config.getRingDelaySeconds());

        SeekBarView delaySeekBar = new SeekBarView(context);
        delaySeekBar.setReportChanges(true);
        delaySeekBar.setProgress(config.getRingDelaySeconds() / (float) AiCallConfig.MAX_RING_DELAY_SECONDS);
        delaySeekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                int sec = AiCallConfig.clampRingDelay(Math.round(progress * AiCallConfig.MAX_RING_DELAY_SECONDS));
                updateDelayLabel(sec);
                if (stop) {
                    config.setRingDelaySeconds(sec);
                }
            }
        });
        delayRow.addView(delaySeekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 6, 0, 0));

        profileCell = new TextSettingsCell(context);
        profileCell.setTextAndValue("Use-Case Profile", config.getProfileLabel(), false);
        profileCell.setOnClickListener(v -> showProfileDialog(context, config));
        generalCard.addView(profileCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addInfo(root, context, "Pick a pre-built profile (Personal, Business, Busy, Support, Family, Privacy) for 1-tap voice & tone setup, or customize everything below.");

        // --- Card 2: Voice, Emotion & Owner Knowledge ---
        LinearLayout personaCard = createWhiteCard(context);
        root.addView(personaCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addHeader(personaCard, context, "Voice, Emotion & Knowledge");

        voiceCell = new TextSettingsCell(context);
        voiceCell.setTextAndValue("AI Voice (30 Studio)", config.getVoiceDisplayLabel(config.getVoiceName()), true);
        voiceCell.setOnClickListener(v -> showVoiceDialog(context, config));
        personaCard.addView(voiceCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        emotionCell = new TextSettingsCell(context);
        emotionCell.setTextAndValue("Emotion & Style", config.getEmotionLabel(), true);
        emotionCell.setOnClickListener(v -> showEmotionDialog(context, config));
        personaCard.addView(emotionCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ownerDataCell = new TextSettingsCell(context);
        ownerDataCell.setTextAndValue("Owner Data & Instructions", formatOwnerDataBadge(config.getOwnerData()), false);
        ownerDataCell.setOnClickListener(v -> showOwnerDataDialog(context, config));
        personaCard.addView(ownerDataCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addInfo(root, context, "Owner Data holds your name, availability, rules, and answers. Emotion & Style controls vocal warmth, empathy, or energy.");

        // --- Card 3: Gemini Live Engine (BYOK) ---
        LinearLayout engineCard = createWhiteCard(context);
        root.addView(engineCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addHeader(engineCard, context, "Gemini Live Connection (BYOK)");

        apiKeyCell = new TextSettingsCell(context);
        apiKeyCell.setTextAndValue("Google AI Studio Key", config.getApiKey().isEmpty() ? "Not set" : "Saved locally", true);
        apiKeyCell.setOnClickListener(v -> showApiKeyDialog(context, config));
        engineCard.addView(apiKeyCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        modelCell = new TextSettingsCell(context);
        modelCell.setTextAndValue("Live Model", config.getPresetLabelForModel(config.getModelId()), true);
        modelCell.setOnClickListener(v -> showModelDialog(context, config));
        engineCard.addView(modelCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        dualModelCheckCell = new TextCheckCell(context);
        dualModelCheckCell.setTextAndCheck("Fast Dual-Model Pipeline", config.isDualModelEnabled(), true);
        dualModelCheckCell.setOnClickListener(v -> {
            boolean next = !config.isDualModelEnabled();
            config.setDualModelEnabled(next);
            dualModelCheckCell.setChecked(next);
        });
        engineCard.addView(dualModelCheckCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        apiVersionCell = new TextSettingsCell(context);
        apiVersionCell.setTextAndValue("WebSocket API Version", config.getApiVersion(), false);
        apiVersionCell.setOnClickListener(v -> showApiVersionDialog(context, config));
        engineCard.addView(apiVersionCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addInfo(root, context, "Your API key stays locally on your device. Gemini 2.5 Native Audio also enables server Affective Dialog automatically.");

        // --- Card 4: Recording, Actions & Logs ---
        LinearLayout toolsCard = createWhiteCard(context);
        root.addView(toolsCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addHeader(toolsCard, context, "Recording & Call Actions");

        autoRecordCheckCell = new TextCheckCell(context);
        autoRecordCheckCell.setTextAndCheck("Auto-Record to Saved Messages", config.isAutoRecordToSavedMessages(), true);
        autoRecordCheckCell.setOnClickListener(v -> {
            boolean next = !config.isAutoRecordToSavedMessages();
            config.setAutoRecordToSavedMessages(next);
            autoRecordCheckCell.setChecked(next);
        });
        toolsCard.addView(autoRecordCheckCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        anonymousListenCheckCell = new TextCheckCell(context);
        anonymousListenCheckCell.setTextAndCheck("Anonymous Listen-In by Default", config.isAnonymousListenDefault(), true);
        anonymousListenCheckCell.setOnClickListener(v -> {
            boolean next = !config.isAnonymousListenDefault();
            config.setAnonymousListenDefault(next);
            anonymousListenCheckCell.setChecked(next);
        });
        toolsCard.addView(anonymousListenCheckCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        backgroundTranscriptCheckCell = new TextCheckCell(context);
        backgroundTranscriptCheckCell.setTextAndCheck("Background Transcript (Handover)", config.isBackgroundTranscriptEnabled(), true);
        backgroundTranscriptCheckCell.setOnClickListener(v -> {
            boolean next = !config.isBackgroundTranscriptEnabled();
            config.setBackgroundTranscriptEnabled(next);
            backgroundTranscriptCheckCell.setChecked(next);
        });
        toolsCard.addView(backgroundTranscriptCheckCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextSettingsCell outgoingCallCell = new TextSettingsCell(context);
        outgoingCallCell.setTextAndValue("Place AI Outgoing Call", "Select Contact", true);
        outgoingCallCell.setOnClickListener(v -> showOutgoingGoalDialog(context));
        toolsCard.addView(outgoingCallCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        historyCell = new TextSettingsCell(context);
        int count = AiCallHistoryStore.loadAllRecords().size();
        historyCell.setTextAndValue("AI Call Summaries & Logs", count > 0 ? count + " calls" : "Empty", false);
        historyCell.setOnClickListener(v -> presentFragment(new AiCallHistoryActivity()));
        toolsCard.addView(historyCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addInfo(root, context, "During any call, a live Chat Cover overlays the call screen. After each call, open Saved Messages -> top Call icon to view detailed summaries & recordings.");

        fragmentView = scrollView;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (historyCell != null) {
            int count = AiCallHistoryStore.loadAllRecords().size();
            historyCell.setTextAndValue("AI Call Summaries & Logs", count > 0 ? count + " calls" : "Empty", false);
        }
    }

    // ---- Minimal Native Dialog Pickers ----

    private void showProfileDialog(Context context, final AiCallConfig config) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Choose Use-Case Profile");
        builder.setItems(AiCallConfig.PROFILE_LABELS, (dialog, which) -> {
            if (which >= 0 && which < AiCallConfig.PROFILE_IDS.length) {
                config.applyUseCaseProfile(AiCallConfig.PROFILE_IDS[which]);
                profileCell.setTextAndValue("Use-Case Profile", config.getProfileLabel(), false);
                voiceCell.setTextAndValue("AI Voice (30 Studio)", config.getVoiceDisplayLabel(config.getVoiceName()), true);
                emotionCell.setTextAndValue("Emotion & Style", config.getEmotionLabel(), true);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, "Profile: " + config.getProfileLabel()).show();
            }
        });
        showDialog(builder.create());
    }

    private void showVoiceDialog(Context context, final AiCallConfig config) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Select AI Voice (30 Studio Voices)");
        builder.setItems(AiCallConfig.VOICE_LABELS, (dialog, which) -> {
            if (which >= 0 && which < AiCallConfig.VOICE_NAMES.length) {
                String voice = AiCallConfig.VOICE_NAMES[which];
                config.setVoiceName(voice);
                voiceCell.setTextAndValue("AI Voice (30 Studio)", AiCallConfig.VOICE_LABELS[which], true);
            }
        });
        showDialog(builder.create());
    }

    private void showEmotionDialog(Context context, final AiCallConfig config) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Emotion & Speaking Style");
        builder.setItems(AiCallConfig.EMOTION_LABELS, (dialog, which) -> {
            if (which >= 0 && which < AiCallConfig.EMOTION_IDS.length) {
                String id = AiCallConfig.EMOTION_IDS[which];
                if ("custom".equals(id)) {
                    showCustomEmotionInputDialog(context, config);
                } else {
                    config.setEmotionId(id);
                    emotionCell.setTextAndValue("Emotion & Style", config.getEmotionLabel(), true);
                }
            }
        });
        showDialog(builder.create());
    }

    private void showCustomEmotionInputDialog(Context context, final AiCallConfig config) {
        final EditTextBoldCursor input = createDialogInput(context,
                "e.g. Speak softly with empathy, pause naturally, and laugh lightly when appropriate",
                config.getCustomEmotion(), true);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Custom Emotion & Tone");
        builder.setView(wrapDialogInput(context, input));
        builder.setPositiveButton("Save", (d, w) -> {
            String val = input.getText() != null ? input.getText().toString().trim() : "";
            config.setCustomEmotion(val);
            config.setEmotionId("custom");
            emotionCell.setTextAndValue("Emotion & Style", config.getEmotionLabel(), true);
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showOwnerDataDialog(Context context, final AiCallConfig config) {
        final EditTextBoldCursor input = createDialogInput(context,
                "Write your name, availability, schedule, or rules for answering callers...",
                config.getOwnerData(), true);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Owner Data & Instructions");
        builder.setView(wrapDialogInput(context, input));
        builder.setPositiveButton("Save", (d, w) -> {
            String val = input.getText() != null ? input.getText().toString().trim() : "";
            config.setOwnerData(val);
            ownerDataCell.setTextAndValue("Owner Data & Instructions", formatOwnerDataBadge(val), false);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, "Owner data saved").show();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showApiKeyDialog(Context context, final AiCallConfig config) {
        final EditTextBoldCursor input = createDialogInput(context, "AIzaSy...", config.getApiKey(), false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Google AI Studio API Key (Local)");
        builder.setView(wrapDialogInput(context, input));
        builder.setPositiveButton("Save", (d, w) -> {
            String val = input.getText() != null ? input.getText().toString().trim() : "";
            config.setApiKey(val);
            apiKeyCell.setTextAndValue("Google AI Studio Key", val.isEmpty() ? "Not set" : "Saved locally", true);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, val.isEmpty() ? "API key cleared" : "API key saved locally").show();
        });
        builder.setNeutralButton("Clear", (d, w) -> {
            config.setApiKey("");
            apiKeyCell.setTextAndValue("Google AI Studio Key", "Not set", true);
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showModelDialog(Context context, final AiCallConfig config) {
        String[] options = new String[AiCallConfig.MODEL_LABELS.length + 1];
        System.arraycopy(AiCallConfig.MODEL_LABELS, 0, options, 0, AiCallConfig.MODEL_LABELS.length);
        options[options.length - 1] = "Custom Model ID...";

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Select Gemini Live Model");
        builder.setItems(options, (dialog, which) -> {
            if (which >= 0 && which < AiCallConfig.MODEL_IDS.length) {
                config.applyModelPreset(AiCallConfig.MODEL_IDS[which]);
                modelCell.setTextAndValue("Live Model", config.getPresetLabelForModel(config.getModelId()), true);
                apiVersionCell.setTextAndValue("WebSocket API Version", config.getApiVersion(), false);
            } else if (which == options.length - 1) {
                showCustomModelIdDialog(context, config);
            }
        });
        showDialog(builder.create());
    }

    private void showCustomModelIdDialog(Context context, final AiCallConfig config) {
        final EditTextBoldCursor input = createDialogInput(context, "e.g. gemini-3.8-live", config.getModelId(), false);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Custom Model ID");
        builder.setView(wrapDialogInput(context, input));
        builder.setPositiveButton("Save", (d, w) -> {
            String val = input.getText() != null ? input.getText().toString().trim() : "";
            if (!val.isEmpty()) {
                config.setModelId(val);
                modelCell.setTextAndValue("Live Model", config.getPresetLabelForModel(val), true);
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showApiVersionDialog(Context context, final AiCallConfig config) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("WebSocket API Version");
        builder.setItems(AiCallConfig.API_VERSIONS, (dialog, which) -> {
            if (which >= 0 && which < AiCallConfig.API_VERSIONS.length) {
                String ver = AiCallConfig.API_VERSIONS[which];
                config.setApiVersion(ver);
                apiVersionCell.setTextAndValue("WebSocket API Version", ver, false);
            }
        });
        showDialog(builder.create());
    }

    private void showOutgoingGoalDialog(Context context) {
        if (!hasAudioPermission(context)) {
            requestAudioPermission();
            return;
        }
        final EditTextBoldCursor input = createDialogInput(context,
                "Optional call goal (e.g. Tell them I'll call back in 15 mins)", "", true);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("AI Outgoing Call Goal");
        builder.setView(wrapDialogInput(context, input));
        builder.setPositiveButton("Choose Contact", (d, w) -> {
            String goal = input.getText() != null ? input.getText().toString().trim() : "";
            showOutgoingContactPicker(context, goal);
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showOutgoingContactPicker(Context context, final String goal) {
        ArrayList<TLRPC.TL_contact> rawContacts = ContactsController.getInstance(currentAccount).contacts;
        final ArrayList<TLRPC.User> users = new ArrayList<>();
        ArrayList<CharSequence> names = new ArrayList<>();
        if (rawContacts != null) {
            for (TLRPC.TL_contact c : rawContacts) {
                TLRPC.User u = MessagesController.getInstance(currentAccount).getUser(c.user_id);
                if (u != null && !u.bot && !u.self) {
                    users.add(u);
                    names.add(ContactsController.formatName(u.first_name, u.last_name));
                }
            }
        }
        if (users.isEmpty()) {
            BulletinFactory.of(this).createErrorBulletin("No contacts found to call").show();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Start AI Outgoing Call");
        builder.setItems(names.toArray(new CharSequence[0]), (dialog, which) -> {
            if (which >= 0 && which < users.size() && getParentActivity() != null) {
                TLRPC.User target = users.get(which);
                AiCallController.getInstance().prepareAiOutgoingCall(target.id, goal);
                VoIPHelper.startCall(target, false, false, getParentActivity(), null, AccountInstance.getInstance(currentAccount));
            }
        });
        showDialog(builder.create());
    }

    // ---- UI Helpers ----

    private static String formatOwnerDataBadge(String data) {
        if (data == null || data.trim().isEmpty()) return "Not set";
        return data.trim().length() + " chars";
    }

    private void updateDelayLabel(int seconds) {
        if (delayValueView == null) return;
        if (seconds == 0) delayValueView.setText("0s (Instant)");
        else if (seconds == 60) delayValueView.setText("60s (1 min)");
        else delayValueView.setText(seconds + "s");
    }

    private static LinearLayout createWhiteCard(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        return card;
    }

    private static void addHeader(LinearLayout card, Context context, String title) {
        HeaderCell header = new HeaderCell(context);
        header.setText(title);
        card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private static void addInfo(LinearLayout root, Context context, String text) {
        TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(text);
        root.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private static EditTextBoldCursor createDialogInput(Context context, String hint, String initial, boolean multiline) {
        EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setCursorSize(AndroidUtilities.dp(20));
        input.setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                Theme.getColor(Theme.key_text_RedRegular)
        );
        input.setHint(hint);
        if (multiline) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setMinLines(3);
            input.setMaxLines(8);
            input.setGravity(Gravity.TOP | Gravity.START);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setSingleLine(true);
        }
        if (initial != null) {
            input.setText(initial);
        }
        return input;
    }

    private static FrameLayout wrapDialogInput(Context context, View input) {
        FrameLayout box = new FrameLayout(context);
        box.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(6), AndroidUtilities.dp(20), AndroidUtilities.dp(6));
        box.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return box;
    }

    private static boolean hasAudioPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && getParentActivity() != null) {
            getParentActivity().requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_RECORD_AUDIO);
        }
    }
}
