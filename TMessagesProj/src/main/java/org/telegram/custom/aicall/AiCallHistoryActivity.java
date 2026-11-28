package org.telegram.custom.aicall;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Native Telegram Calls-style Archive for AI & Handover Calls (#nc-call-r).
 * Grouped & indexed by serial ID while presenting a clean Telegram-grade list
 * of recorded calls, transcripts, and Bengali AI summaries.
 */
public class AiCallHistoryActivity extends BaseFragment {

    private static final int[] AVATAR_TOP = {
            0xFF549DF6, 0xFF8E63F5, 0xFF1DBA86, 0xFFF29C38, 0xFFEC5794, 0xFF29B6D8
    };
    private static final int[] AVATAR_BOTTOM = {
            0xFF2D6CE0, 0xFF6B3FD4, 0xFF0A8F63, 0xFFD97218, 0xFFC2326D, 0xFF0D83A8
    };

    private LinearLayout listContainer;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("dd MMM, HH:mm", Locale.US);

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("AI Call Archive");
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
        scrollView.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        root.addView(listContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText("Tap a call to listen to the recording, read the full conversation transcript, or view the Bengali AI voice & text summary. Long-press to remove.");
        root.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        reloadList();
        fragmentView = scrollView;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadList();
    }

    private void reloadList() {
        if (listContainer == null || getContext() == null) {
            return;
        }
        Context context = getContext();
        listContainer.removeAllViews();

        List<AiCallHistoryStore.CallRecord> records = AiCallHistoryStore.loadAllRecords();
        if (actionBar != null) {
            actionBar.setSubtitle(records.isEmpty()
                    ? "Recordings & Bengali Summaries"
                    : records.size() + (records.size() == 1 ? " recorded call" : " recorded calls"));
        }

        if (records.isEmpty()) {
            listContainer.setBackgroundColor(Color.TRANSPARENT);

            LinearLayout emptyWrap = new LinearLayout(context);
            emptyWrap.setOrientation(LinearLayout.VERTICAL);
            emptyWrap.setGravity(Gravity.CENTER);
            emptyWrap.setPadding(AndroidUtilities.dp(32), AndroidUtilities.dp(72), AndroidUtilities.dp(32), AndroidUtilities.dp(56));

            FrameLayout iconCircle = new FrameLayout(context);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0x202AABEE);
            iconCircle.setBackground(bg);
            ImageView icon = new ImageView(context);
            icon.setImageResource(R.drawable.call);
            icon.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            iconCircle.addView(icon, LayoutHelper.createFrame(32, 32, Gravity.CENTER));
            emptyWrap.addView(iconCircle, LayoutHelper.createLinear(72, 72, 0, 0, 0, 16));

            TextView emptyTitle = new TextView(context);
            emptyTitle.setText("No AI Call Summaries Yet");
            emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            emptyTitle.setTypeface(AndroidUtilities.bold());
            emptyTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            emptyTitle.setGravity(Gravity.CENTER);
            emptyWrap.addView(emptyTitle);

            TextView emptySub = new TextView(context);
            emptySub.setText("Completed calls handled by AI will appear here with recordings, live chat transcripts, and Bengali summaries.");
            emptySub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            emptySub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            emptySub.setGravity(Gravity.CENTER);
            emptySub.setLineSpacing(AndroidUtilities.dp(3), 1f);
            emptyWrap.addView(emptySub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

            listContainer.addView(emptyWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return;
        }

        listContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        for (int i = 0; i < records.size(); i++) {
            final AiCallHistoryStore.CallRecord record = records.get(i);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Theme.getSelectorDrawable(true));
            row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(11), AndroidUtilities.dp(16), AndroidUtilities.dp(11));

            // 50dp Telegram Gradient Avatar + subtle status badge
            FrameLayout avatarWrap = new FrameLayout(context);
            TextView avatar = new TextView(context);
            avatar.setText(getInitials(record.peerName));
            avatar.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            avatar.setTypeface(AndroidUtilities.bold());
            avatar.setTextColor(Color.WHITE);
            avatar.setGravity(Gravity.CENTER);
            int cIdx = Math.abs((record.peerName != null ? record.peerName.hashCode() : 0)) % AVATAR_TOP.length;
            GradientDrawable circle = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{AVATAR_TOP[cIdx], AVATAR_BOTTOM[cIdx]});
            circle.setShape(GradientDrawable.OVAL);
            avatar.setBackground(circle);
            avatarWrap.addView(avatar, LayoutHelper.createFrame(50, 50));

            View badge = new View(context);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setShape(GradientDrawable.OVAL);
            boolean hasVoiceSummary = record.summaryAudioFilePath != null && !record.summaryAudioFilePath.isEmpty();
            badgeBg.setColor(hasVoiceSummary ? 0xFF2AABEE : 0xFF10B981);
            badgeBg.setStroke(AndroidUtilities.dp(2), Theme.getColor(Theme.key_windowBackgroundWhite));
            badge.setBackground(badgeBg);
            avatarWrap.addView(badge, LayoutHelper.createFrame(14, 14, Gravity.BOTTOM | Gravity.END));

            row.addView(avatarWrap, LayoutHelper.createLinear(50, 50, 0, 0, 14, 0));

            // Middle content column
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            LinearLayout topRow = new LinearLayout(context);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            col.addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            TextView nameView = new TextView(context);
            nameView.setText(record.peerName != null && !record.peerName.isEmpty() ? record.peerName : "Caller");
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            topRow.addView(nameView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 8, 0));

            TextView timeView = new TextView(context);
            timeView.setText(timeFmt.format(new Date(record.startedAtMs)));
            timeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            timeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            topRow.addView(timeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            String preview = record.detailedSummary == null ? "" : record.detailedSummary.trim().replace('\n', ' ');
            TextView summaryLine = new TextView(context);
            summaryLine.setText(preview.isEmpty() ? "Tap to view transcript & Bengali summary" : preview);
            summaryLine.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            summaryLine.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            summaryLine.setSingleLine(true);
            summaryLine.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(summaryLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            TextView metaLine = new TextView(context);
            StringBuilder meta = new StringBuilder();
            meta.append(formatDuration(record.durationSec));
            if (record.handoverCount > 0) {
                meta.append(" · ").append(record.handoverCount).append(" handover").append(record.handoverCount > 1 ? "s" : "");
            }
            if (record.serialId != null && !record.serialId.isEmpty()) {
                meta.append(" · #").append(record.serialId);
            }
            metaLine.setText(meta.toString());
            metaLine.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            metaLine.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            metaLine.setSingleLine(true);
            metaLine.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(metaLine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

            row.setOnClickListener(v -> presentFragment(new AiCallDetailActivity(record)));
            row.setOnLongClickListener(v -> {
                AlertDialog.Builder b = new AlertDialog.Builder(context);
                b.setTitle("Delete Call Record");
                b.setMessage("Remove call with " + record.peerName + " (" + record.serialId + ") from AI Call Archive?");
                b.setPositiveButton("Delete", (d, w) -> {
                    AiCallHistoryStore.deleteRecord(record.serialId);
                    reloadList();
                });
                b.setNegativeButton("Cancel", null);
                showDialog(b.create());
                return true;
            });

            listContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            if (i < records.size() - 1) {
                View div = new View(context);
                div.setBackgroundColor(Theme.getColor(Theme.key_divider));
                LinearLayout.LayoutParams divLp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1);
                divLp.leftMargin = AndroidUtilities.dp(80);
                listContainer.addView(div, divLp);
            }
        }
    }

    private static String formatDuration(long durationSec) {
        if (durationSec <= 0L) {
            return "0s";
        }
        long mins = durationSec / 60L;
        long secs = durationSec % 60L;
        return mins > 0 ? String.format(Locale.US, "%dm %02ds", mins, secs) : (secs + "s");
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
