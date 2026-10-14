package com.feiniu.carmusic.player;

import com.feiniu.carmusic.model.Track;

interface MediaControlSession {
    interface Callback {
        void onPlay();
        void onPause();
        void onToggle();
        void onNext();
        void onPrevious();
        void onSeekTo(long positionMs);
    }

    void update(Track track, boolean playing, boolean loading, long positionMs, long durationMs);
    void release();
}
