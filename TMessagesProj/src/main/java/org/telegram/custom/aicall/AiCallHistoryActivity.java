package org.telegram.custom.aicall;

import android.content.Context;
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
 * Dedicated UI showing all #nc-call-r recorded & summarized AI calls
 * (hidden from Telegram's Saved Messages chat view).
 */
public class AiCallHistoryActivity extends BaseFragment {

    private LinearLayout listContainer;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.US);

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("AI Call Logs & Summaries");
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
        header.setText("Recorded Calls (#nc-call-r Hidden Archive)");
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText("All #nc-call-r recordings, summaries, and multi-part transcripts are hidden from Saved Messages and organized here by Call Serial ID.");
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
            empty.setText("No AI call records yet. Completed calls will appear here automatically.");
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(40), AndroidUtilities.dp(24), AndroidUtilities.dp(40));
            listContainer.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return;
        }

        for (final AiCallHistoryStore.CallRecord record : records) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            card.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(14));

            TextView title = new TextView(context);
            title.setText(record.peerName + " · " + record.direction + " (" + record.durationSec + "s)");
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            card.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            TextView meta = new TextView(context);
            meta.setText("ID: " + record.serialId + " · " + dateFmt.format(new Date(record.startedAtMs))
                    + " · Handovers: " + record.handoverCount);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            meta.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
            card.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

            String preview = record.detailedSummary == null ? "" : record.detailedSummary.trim();
            if (preview.length() > 140) {
                preview = preview.substring(0, 137) + "...";
            }
            if (!preview.isEmpty()) {
                TextView sum = new TextView(context);
                sum.setText(preview);
                sum.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                sum.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                card.addView(sum, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
            }

            card.setOnClickListener(v -> presentFragment(new AiCallDetailActivity(record)));
            card.setOnLongClickListener(v -> {
                AlertDialog.Builder b = new AlertDialog.Builder(context);
                b.setTitle("Delete Call Record?");
                b.setMessage("Remove " + record.serialId + " from local AI Call History?");
                b.setPositiveButton("Delete", (d, w) -> {
                    AiCallHistoryStore.deleteRecord(record.serialId);
                    reloadList();
                });
                b.setNegativeButton("Cancel", null);
                showDialog(b.create());
                return true;
            });

            listContainer.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            View div = new View(context);
            div.setBackgroundColor(Theme.getColor(Theme.key_divider));
            listContainer.addView(div, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
        }
    }
}
