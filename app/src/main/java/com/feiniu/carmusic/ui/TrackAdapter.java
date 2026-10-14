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
import com.feiniu.carmusic.model.Track;

import java.util.List;

final class TrackAdapter extends BaseAdapter {
    interface FavoriteClickListener {
        void onFavoriteClick(Track track);
    }

    private final LayoutInflater inflater;
    private final List<Track> tracks;
    private final CoverLoader covers;
    private final FavoriteClickListener favoriteClickListener;

    TrackAdapter(Context context, List<Track> tracks, FavoriteClickListener favoriteClickListener) {
        inflater = LayoutInflater.from(context);
        this.tracks = tracks;
        covers = CoverLoader.get(context);
        this.favoriteClickListener = favoriteClickListener;
    }

    @Override
    public int getCount() {
        return tracks.size();
    }

    @Override
    public Track getItem(int position) {
        return tracks.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder holder;
        if (convertView == null) {
            convertView = inflater.inflate(R.layout.item_track, parent, false);
            holder = new Holder();
            holder.cover = (ImageView) convertView.findViewById(R.id.track_cover);
            holder.title = (TextView) convertView.findViewById(R.id.track_title);
            holder.subtitle = (TextView) convertView.findViewById(R.id.track_subtitle);
            holder.duration = (TextView) convertView.findViewById(R.id.track_duration);
            holder.favorite = (TextView) convertView.findViewById(R.id.track_favorite);
            convertView.setTag(holder);
        } else {
            holder = (Holder) convertView.getTag();
        }

        final Track track = getItem(position);
        holder.title.setText(track.title.length() == 0 ? "未知歌曲" : track.title);
        String subtitle = track.artist;
        if (track.album.length() > 0) subtitle += (subtitle.length() == 0 ? "" : " · ") + track.album;
        holder.subtitle.setText(subtitle.length() == 0 ? "未知歌手" : subtitle);
        holder.duration.setText(formatDuration(track.durationMs));
        holder.favorite.setSelected(track.favorite);
        holder.favorite.setText(track.favorite ? "取消收藏" : "收藏");
        holder.favorite.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (favoriteClickListener != null) favoriteClickListener.onFavoriteClick(track);
            }
        });
        covers.load(track.coverId, holder.cover, 160);
        return convertView;
    }

    static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    private static final class Holder {
        ImageView cover;
        TextView title;
        TextView subtitle;
        TextView duration;
        TextView favorite;
    }
}
