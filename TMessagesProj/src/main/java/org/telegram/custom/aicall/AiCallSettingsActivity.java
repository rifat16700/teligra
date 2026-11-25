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

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

/**
 * Native Telegram settings fragment for configuring AI Auto-Answer Voice Calls,
 * ring delay (0s - 60s), Owner Data / knowledge context, and composable call options.
 */
public class AiCallSettingsActivity extends BaseFragment {

    private static final int REQUEST_CODE_RECORD_AUDIO = 9002;

    private TextCheckCell autoAnswerCheckCell;
    private TextView delayValueView;
    private SeekBarView delaySeekBar;
    private EditTextBoldCursor ownerDataInput;
    private TextCheckCell autoRecordCheckCell;
    private TextCheckCell anonymousListenCheckCell;
    private TextCheckCell backgroundTranscriptCheckCell;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("AI Auto-Answer Call");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    saveOwnerDataQuietly();
                    finishFragment();
                }
            }
        });

        AiCallConfig config = AiCallConfig.getInstance();

        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- Section 1: Auto-Answer & Ring Delay ---
        LinearLayout autoAnswerSection = createWhiteCard(context);
        root.addView(autoAnswerSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HeaderCell generalHeader = new HeaderCell(context);
        generalHeader.setText("Auto-Answer");
        autoAnswerSection.addView(generalHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        autoAnswerCheckCell = new TextCheckCell(context);
        autoAnswerCheckCell.setTextAndCheck("Enable AI Auto-Answer", config.isAutoAnswerEnabled(), true);
        autoAnswerCheckCell.setOnClickListener(v -> {
            boolean next = !config.isAutoAnswerEnabled();
            if (next && !hasAudioPermission(context)) {
                requestAudioPermission();
            }
            config.setAutoAnswerEnabled(next);
            autoAnswerCheckCell.setChecked(next);
        });
        autoAnswerSection.addView(autoAnswerCheckCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout delayRow = new LinearLayout(context);
        delayRow.setOrientation(LinearLayout.VERTICAL);
        delayRow.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(12), AndroidUtilities.dp(21), AndroidUtilities.dp(14));
        autoAnswerSection.addView(delayRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout delayLabelRow = new LinearLayout(context);
        delayLabelRow.setOrientation(LinearLayout.HORIZONTAL);
        delayRow.addView(delayLabelRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView delayTitle = new TextView(context);
        delayTitle.setText("Ring Delay Before Auto-Answer");
        delayTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        delayTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        delayLabelRow.addView(delayTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        delayValueView = new TextView(context);
        delayValueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        delayValueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
        delayLabelRow.addView(delayValueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        int currentDelay = config.getRingDelaySeconds();
        updateDelayLabel(currentDelay);

        delaySeekBar = new SeekBarView(context);
        delaySeekBar.setReportChanges(true);
        delaySeekBar.setProgress(currentDelay / (float) AiCallConfig.MAX_RING_DELAY_SECONDS);
        delaySeekBar.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                int seconds = Math.round(progress * AiCallConfig.MAX_RING_DELAY_SECONDS);
                seconds = AiCallConfig.clampRingDelay(seconds);
                updateDelayLabel(seconds);
                if (stop) {
                    config.setRingDelaySeconds(seconds);
                }
            }
        });
        delayRow.addView(delaySeekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38, 0, 8, 0, 0));

        TextInfoPrivacyCell autoAnswerInfo = new TextInfoPrivacyCell(context);
        autoAnswerInfo.setText("When enabled, incoming calls ring for the selected delay (0s instant up to 60s) and are then answered automatically.");
        root.addView(autoAnswerInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // --- Section 2: Owner Data & AI Context ---
        LinearLayout ownerSection = createWhiteCard(context);
        root.addView(ownerSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HeaderCell ownerHeader = new HeaderCell(context);
        ownerHeader.setText("Owner Data (AI Knowledge & Instructions)");
        ownerSection.addView(ownerHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        FrameLayout inputContainer = new FrameLayout(context);
        inputContainer.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(8), AndroidUtilities.dp(21), AndroidUtilities.dp(8));
        ownerSection.addView(inputContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ownerDataInput = new EditTextBoldCursor(context);
        ownerDataInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        ownerDataInput.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        ownerDataInput.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        ownerDataInput.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        ownerDataInput.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        ownerDataInput.setCursorSize(AndroidUtilities.dp(20));
        ownerDataInput.setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                Theme.getColor(Theme.key_text_RedRegular)
        );
        ownerDataInput.setHint("Write your status, availability, rules, or info the AI should use to answer callers...");
        ownerDataInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        ownerDataInput.setMinLines(4);
        ownerDataInput.setMaxLines(10);
        ownerDataInput.setGravity(Gravity.TOP | Gravity.START);
        ownerDataInput.setText(config.getOwnerData());
        inputContainer.addView(ownerDataInput, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView saveButton = new TextView(context);
        saveButton.setText("Save Owner Data");
        saveButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        saveButton.setGravity(Gravity.CENTER);
        saveButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        saveButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                AndroidUtilities.dp(8),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_addButtonPressed)
        ));
        saveButton.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
        saveButton.setOnClickListener(v -> {
            saveOwnerDataQuietly();
            BulletinFactory.of(this).createSimpleBulletin(org.telegram.messenger.R.raw.contact_check, "Owner data saved").show();
        });
        ownerSection.addView(saveButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 21, 8, 21, 16));

        TextInfoPrivacyCell ownerInfo = new TextInfoPrivacyCell(context);
        ownerInfo.setText("This owner data is sent to the AI session when answering or taking over a call so it responds strictly from your saved context.");
        root.addView(ownerInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // --- Section 3: Composable Call Controls ---
        LinearLayout controlsSection = createWhiteCard(context);
        root.addView(controlsSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HeaderCell controlsHeader = new HeaderCell(context);
        controlsHeader.setText("Call Recording & Monitoring");
        controlsSection.addView(controlsHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        autoRecordCheckCell = new TextCheckCell(context);
        autoRecordCheckCell.setTextAndCheck("Auto-Record to Saved Messages", config.isAutoRecordToSavedMessages(), true);
        autoRecordCheckCell.setOnClickListener(v -> {
            boolean next = !config.isAutoRecordToSavedMessages();
            config.setAutoRecordToSavedMessages(next);
            autoRecordCheckCell.setChecked(next);
        });
        controlsSection.addView(autoRecordCheckCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        anonymousListenCheckCell = new TextCheckCell(context);
        anonymousListenCheckCell.setTextAndCheck("Anonymous Live Listen-In by Default", config.isAnonymousListenDefault(), true);
        anonymousListenCheckCell.setOnClickListener(v -> {
            boolean next = !config.isAnonymousListenDefault();
            config.setAnonymousListenDefault(next);
            anonymousListenCheckCell.setChecked(next);
        });
        controlsSection.addView(anonymousListenCheckCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        backgroundTranscriptCheckCell = new TextCheckCell(context);
        backgroundTranscriptCheckCell.setTextAndCheck("Background Call Transcript (Handover)", config.isBackgroundTranscriptEnabled(), false);
        backgroundTranscriptCheckCell.setOnClickListener(v -> {
            boolean next = !config.isBackgroundTranscriptEnabled();
            config.setBackgroundTranscriptEnabled(next);
            backgroundTranscriptCheckCell.setChecked(next);
        });
        controlsSection.addView(backgroundTranscriptCheckCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell statusFooter = new TextInfoPrivacyCell(context);
        statusFooter.setText("Phase 1 active: Settings, Owner Data, and 0-60s Auto-Answer trigger are live. Audio engine & AI API bridge follow in Phases 2-3.");
        root.addView(statusFooter, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fragmentView = scrollView;
        return fragmentView;
    }

    @Override
    public void onPause() {
        super.onPause();
        saveOwnerDataQuietly();
    }

    private void saveOwnerDataQuietly() {
        if (ownerDataInput != null && ownerDataInput.getText() != null) {
            AiCallConfig.getInstance().setOwnerData(ownerDataInput.getText().toString().trim());
        }
    }

    private void updateDelayLabel(int seconds) {
        if (delayValueView == null) return;
        if (seconds == 0) {
            delayValueView.setText("0s (Instant)");
        } else if (seconds == 60) {
            delayValueView.setText("60s (1 min)");
        } else {
            delayValueView.setText(seconds + "s");
        }
    }

    private LinearLayout createWhiteCard(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        return card;
    }

    private boolean hasAudioPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && getParentActivity() != null) {
            getParentActivity().requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_CODE_RECORD_AUDIO);
        }
    }
}
