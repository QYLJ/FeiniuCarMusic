package com.feiniu.carmusic.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import com.feiniu.carmusic.R;
import com.feiniu.carmusic.model.MediaCollection;
import java.util.List;

/** Lightweight sidebar rows; the song list stays in the existing content pane. */
final class SidebarPlaylistAdapter extends BaseAdapter {
    private final Context context;
    private final List<MediaCollection> items;
    private String selectedGuid = "";

    SidebarPlaylistAdapter(Context context, List<MediaCollection> items) {
        this.context = context;
        this.items = items;
    }
    void select(String guid) { selectedGuid = guid; notifyDataSetChanged(); }
    @Override public int getCount() { return items.size(); }
    @Override public MediaCollection getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public View getView(int position, View recycled, ViewGroup parent) {
        TextView row = recycled instanceof TextView ? (TextView) recycled : new TextView(context);
        MediaCollection item = getItem(position);
        boolean selected = item.guid.equals(selectedGuid);
        float density = context.getResources().getDisplayMetrics().density;
        row.setText(item.name.length() == 0 ? "未命名歌单" : item.name);
        row.setContentDescription(row.getText() + "，" + item.trackCount + " 首");
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (12 * density), 0, (int) (8 * density), 0);
        row.setBackgroundResource(R.drawable.bg_nav_button);
        row.setTextColor(context.getResources().getColor(selected ? R.color.primary : R.color.text_secondary));
        row.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        row.setSelected(selected);
        UiAppearance.textSize(row, 14);
        row.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1,
                Math.round(48 * Math.max(1, UiAppearance.textScale(context)) * density)));
        return row;
    }
}
