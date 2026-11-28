package org.telegram.custom.aicall;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
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
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Telegram-style Call History & AI Summary Archive.
 * Displays serial-ID grouped calls (#nc-call-r) with circular avatars,
 * timestamps, durations, handover counts, and Bengali summary previews.
 */
public class AiCallHistoryActivity extends BaseFragment {

    private static final int[] AVATAR_COLORS = {
            0xFF5B6DF6, 0xFF7C3AED, 0xFF059669, 0xFFD97706, 0xFFDB2777, 0xFF0284C7
    };

    private LinearLayout listContainer;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd MMM · HH:mm", Locale.US);

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("AI Call Archive");
        actionBar.setSubtitle("Hidden #nc-call-r Summaries");
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

        HeaderCell header = new HeaderCell(context);
        header.setText("Recent AI & Handover Calls (By Serial ID)");
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText("Tap any call to play its recording, read the full transcript chat, or listen to the Bengali AI voice & text summary. Long-press to delete.");
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
        if (listContainer == null || getContext() == null) return;
        Context context = getContext();
        listContainer.removeAllViews();

        List<AiCallHistoryStore.CallRecord> records = AiCallHistoryStore.loadAllRecords();
        if (records.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("No AI call records yet.\nCompleted calls with recordings & Bengali summaries will appear here.");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(48), AndroidUtilities.dp(28), AndroidUtilities.dp(48));
            listContainer.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return;
        }

        for (final AiCallHistoryStore.CallRecord record : records) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Theme.getSelectorDrawable(true));
            row.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));

            // Circular Avatar with Initial
            TextView avatar = new TextView(context);
            String initial = getInitials(record.peerName);
            avatar.setText(initial);
            avatar.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            avatar.setTypeface(AndroidUtilities.bold());
            avatar.setTextColor(0xFFFFFFFF);
            avatar.setGravity(Gravity.CENTER);
            int colorIdx = Math.abs((record.peerName != null ? record.peerName.hashCode() : 0)) % AVATAR_COLORS.length;
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(AVATAR_COLORS[colorIdx]);
            avatar.setBackground(circle);
            row.addView(avatar, LayoutHelper.createLinear(46, 46, 0, 0, 14, 0));

            // Right content column
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            LinearLayout topRow = new LinearLayout(context);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            col.addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            TextView nameView = new TextView(context);
            nameView.setText(record.peerName);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            topRow.addView(nameView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            TextView timeView = new TextView(context);
            timeView.setText(dateFmt.format(new Date(record.startedAtMs)));
            timeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            timeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            topRow.addView(timeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

            TextView serialRow = new TextView(context);
            serialRow.setText("ID: " + record.serialId + " · " + record.direction + " · " + record.durationSec + "s"
                    + (record.handoverCount > 0 ? " · Handovers: " + record.handoverCount : ""));
            serialRow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            serialRow.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            col.addView(serialRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            String preview = record.detailedSummary == null ? "" : record.detailedSummary.trim().replace('\n', ' ');
            if (preview.length() > 110) {
                preview = preview.substring(0, 107) + "...";
            }
            if (!preview.isEmpty()) {
                TextView sumView = new TextView(context);
                sumView.setText(preview);
                sumView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                sumView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                sumView.setMaxLines(2);
                col.addView(sumView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
            }

            row.setOnClickListener(v -> presentFragment(new AiCallDetailActivity(record)));
            row.setOnLongClickListener(v -> {
                AlertDialog.Builder b = new AlertDialog.Builder(context);
                b.setTitle("Delete Call Record?");
                b.setMessage("Remove " + record.serialId + " from local AI Call Archive?");
                b.setPositiveButton("Delete", (d, w) -> {
                    AiCallHistoryStore.deleteRecord(record.serialId);
                    reloadList();
                });
                b.setNegativeButton("Cancel", null);
                showDialog(b.create());
                return true;
            });

            listContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            View div = new View(context);
            div.setBackgroundColor(Theme.getColor(Theme.key_divider));
            listContainer.addView(div, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
        }
    }

    private static String getInitials(String name) {
        if (name == null || name.trim().isEmpty()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, 1).toUpperCase(Locale.US);
        return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase(Locale.US);
    }
}
