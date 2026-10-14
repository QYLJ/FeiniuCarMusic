package com.feiniu.carmusic.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.feiniu.carmusic.R;
import com.feiniu.carmusic.data.CoverLoader;
import com.feiniu.carmusic.model.MediaCollection;

import java.util.List;

final class CollectionAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final List<MediaCollection> items;
    private final CoverLoader covers;

    CollectionAdapter(Context context, List<MediaCollection> items) {
        inflater = LayoutInflater.from(context);
        this.items = items;
        covers = CoverLoader.get(context);
    }

    @Override public int getCount() { return items.size(); }
    @Override public MediaCollection getItem(int position) { return items.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_collection, parent, false);
            holder = new Holder();
            holder.cover = (ImageView) convertView.findViewById(R.id.collection_cover);
            holder.name = (TextView) convertView.findViewById(R.id.collection_name);
            holder.count = (TextView) convertView.findViewById(R.id.collection_count);
            convertView.setTag(holder);
        } else {
            holder = (Holder) convertView.getTag();
        }
        MediaCollection item = getItem(position);
        holder.name.setText(item.name.length() == 0 ? "未命名" : item.name);
        String unit = MediaCollection.TYPE_ARTIST.equals(item.type) ? "首歌曲" : "首歌曲";
        holder.count.setText(item.trackCount > 0 ? item.trackCount + " " + unit : collectionType(item.type));
        covers.load(item.coverId, holder.cover, 320);
        return convertView;
    }

    private static String collectionType(String type) {
        if (MediaCollection.TYPE_ARTIST.equals(type)) return "歌手";
        if (MediaCollection.TYPE_GENRE.equals(type)) return "音乐风格";
        if (MediaCollection.TYPE_PLAYLIST.equals(type)) return "歌单";
        return "专辑";
    }

    private static final class Holder {
        ImageView cover;
        TextView name;
        TextView count;
    }
}
