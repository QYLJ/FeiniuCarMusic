package com.feiniu.carmusic.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.KeyEvent;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.feiniu.carmusic.R;
import com.feiniu.carmusic.data.CoverLoader;
import com.feiniu.carmusic.data.FnApiClient;
import com.feiniu.carmusic.data.HistoryStore;
import com.feiniu.carmusic.model.MediaCollection;
import com.feiniu.carmusic.model.MediaCollectionPage;
import com.feiniu.carmusic.model.Track;
import com.feiniu.carmusic.model.TrackPage;
import com.feiniu.carmusic.player.PlaybackService;
import com.feiniu.carmusic.player.MediaKeyRouter;
import com.feiniu.carmusic.update.AppUpdater;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LibraryActivity extends Activity {
    private static final int PAGE_SIZE = 500;
    private static final int SOURCE_HOME = 0;
    private static final int SOURCE_LIBRARY = 1;
    private static final int SOURCE_SEARCH = 2;
    private static final int SOURCE_FAVORITES = 3;
    private static final int SOURCE_RECENT = 4;
    private static final int SOURCE_COLLECTION = 5;

    private final List<Track> tracks = new ArrayList<Track>();
    private final Set<String> loadedTrackGuids = new HashSet<String>();
    private final List<MediaCollection> collections = new ArrayList<MediaCollection>();
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());

    private FnApiClient api;
    private HistoryStore historyStore;
    private TrackAdapter trackAdapter;
    private CollectionAdapter collectionAdapter;
    private ListView trackList;
    private GridView collectionGrid;
    private View trackContainer;
    private View homeHeader;
    private TextView pageTitle;
    private TextView listStatus;
    private TextView emptyState;
    private TextView miniTitle;
    private TextView miniArtist;
    private ImageView miniCover;
    private Button miniToggle;
    private EditText searchInput;
    private Button playAllButton;
    private PlaybackService playback;
    private boolean bound;
    private boolean serviceBinding;
    private boolean loading;
    private boolean allLoaded;
    private boolean destroyed;
    private int nextPage = 1;
    private int contentGeneration;
    private int source = SOURCE_HOME;
    private int pendingPlayIndex = -1;
    private int queuedTrackCount;
    private String playingQueueToken = "";
    private boolean shuffleNewQueueTracks;
    private String activeQuery = "";
    private String collectionType = "";
    private String collectionGuid = "";
    private String displayedMiniGuid = "";
    private View selectedNav;
    private int layoutWidth, layoutHeight;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return MediaKeyRouter.dispatch(this, event) || super.dispatchKeyEvent(event);
    }

    private final Runnable updatePlayer = new Runnable() {
        @Override public void run() {
            updateMiniPlayer();
            main.postDelayed(this, 500L);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            playback = ((PlaybackService.LocalBinder) service).getService();
            bound = true;
            if (pendingPlayIndex >= 0) {
                int index = pendingPlayIndex;
                pendingPlayIndex = -1;
                playSelection(index);
            }
            appendLoadedTracksToQueue();
            updateMiniPlayer();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            playback = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);
        api = FnApiClient.get(this);
        historyStore = new HistoryStore(this);
        if (!api.session().hasSession()) {
            openLogin();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) NotificationPermissionApi33.requestIfNeeded(this);

        pageTitle = (TextView) findViewById(R.id.library_title);
        listStatus = (TextView) findViewById(R.id.list_status);
        emptyState = (TextView) findViewById(R.id.empty_state);
        miniTitle = (TextView) findViewById(R.id.mini_title);
        miniArtist = (TextView) findViewById(R.id.mini_artist);
        miniCover = (ImageView) findViewById(R.id.mini_cover);
        miniToggle = (Button) findViewById(R.id.mini_toggle);
        searchInput = (EditText) findViewById(R.id.search_input);
        playAllButton = (Button) findViewById(R.id.play_all_button);
        trackList = (ListView) findViewById(R.id.track_list);
        collectionGrid = (GridView) findViewById(R.id.collection_grid);

        homeHeader = findViewById(R.id.home_header);
        trackContainer = findViewById(R.id.track_container);
        trackAdapter = new TrackAdapter(this, tracks, new TrackAdapter.FavoriteClickListener() {
            @Override public void onFavoriteClick(Track track) { toggleFavorite(track); }
        });
        collectionAdapter = new CollectionAdapter(this, collections);
        trackList.setAdapter(trackAdapter);
        collectionGrid.setAdapter(collectionAdapter);

        TextView server = (TextView) findViewById(R.id.server_status);
        server.setText("已连接");
        server.setContentDescription("已连接 " + shortServer(api.session().getBaseUrl()));

        final View root = findViewById(R.id.library_root);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int l, int t, int r, int b,
                    int oldL, int oldT, int oldR, int oldB) {
                if (r - l != layoutWidth || b - t != layoutHeight) {
                    layoutWidth = r - l;
                    layoutHeight = b - t;
                    root.post(new Runnable() {
                        @Override public void run() { applyLibraryLayout(); }
                    });
                }
            }
        });

        bindNavigation();
        bindContentActions();
        openHome();
    }

    private void applyLibraryLayout() {
        float density = getResources().getDisplayMetrics().density;
        float width = layoutWidth / density;
        boolean compact = layoutHeight / density < 400 || width < 760;
        boolean narrow = width < 580;
        setWidth(findViewById(R.id.library_sidebar), Math.round((compact ? 116 : 148) * density));
        int inset = Math.round((compact ? 10 : 20) * density);
        findViewById(R.id.library_content).setPadding(inset, 0, inset, 0);
        setHeight(findViewById(R.id.library_toolbar), Math.round((compact ? 52 : 68) * density));
        setHeight(findViewById(R.id.home_cards), Math.round((compact ? 64 : 108) * density));
        setHeight(findViewById(R.id.home_section_title), Math.round((compact ? 28 : 42) * density));
        homeHeader.setPadding(0, Math.round(4 * density), 0, Math.round((compact ? 4 : 8) * density));
        int brandHeight = compact ? 40 : 56;
        setHeight(findViewById(R.id.sidebar_brand_row), Math.round(brandHeight * density));
        float navHeight = Math.max(28, Math.min(compact ? 34 : 44, (layoutHeight / density - brandHeight - 4) / 8));
        setWidth(searchInput, Math.round((narrow ? 82 : compact ? 120 : 190) * density));
        pageTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, narrow ? 16 : compact ? 20 : 24);
        int[] navIds = {R.id.nav_home, R.id.nav_favorites, R.id.nav_recent, R.id.nav_albums,
                R.id.nav_artists, R.id.nav_genres, R.id.nav_library, R.id.nav_playlists};
        for (int id : navIds) {
            TextView nav = (TextView) findViewById(id);
            setHeight(nav, Math.round(navHeight * density));
            nav.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 12 : 14);
        }
    }

    private static void setWidth(View view, int width) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        params.width = width;
        view.setLayoutParams(params);
    }

    private static void setHeight(View view, int height) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        params.height = height;
        view.setLayoutParams(params);
    }

    private void bindNavigation() {
        bindNav(R.id.nav_home, new View.OnClickListener() { @Override public void onClick(View v) { openHome(); } });
        bindNav(R.id.nav_favorites, new View.OnClickListener() { @Override public void onClick(View v) { openFavorites(); } });
        bindNav(R.id.nav_recent, new View.OnClickListener() { @Override public void onClick(View v) { openRecent(); } });
        bindNav(R.id.nav_albums, new View.OnClickListener() { @Override public void onClick(View v) { openCollections(MediaCollection.TYPE_ALBUM, "专辑", v); } });
        bindNav(R.id.nav_artists, new View.OnClickListener() { @Override public void onClick(View v) { openCollections(MediaCollection.TYPE_ARTIST, "歌手", v); } });
        bindNav(R.id.nav_genres, new View.OnClickListener() { @Override public void onClick(View v) { openCollections(MediaCollection.TYPE_GENRE, "音乐风格", v); } });
        bindNav(R.id.nav_library, new View.OnClickListener() { @Override public void onClick(View v) { openLibrary(); } });
        bindNav(R.id.nav_playlists, new View.OnClickListener() { @Override public void onClick(View v) { openCollections(MediaCollection.TYPE_PLAYLIST, "歌单", v); } });

        findViewById(R.id.logout_button).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (playback != null) playback.clearQueueAndStop();
                api.session().logout();
                stopService(new Intent(LibraryActivity.this, PlaybackService.class));
                openLogin();
            }
        });
        findViewById(R.id.update_button).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { AppUpdater.check(LibraryActivity.this, true); }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        AppUpdater.resumePendingInstall(this, requestCode);
    }

    private void bindContentActions() {
        View.OnClickListener search = new View.OnClickListener() {
            @Override public void onClick(View view) { beginSearch(); }
        };
        findViewById(R.id.search_button).setOnClickListener(search);
        searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    beginSearch();
                    return true;
                }
                return false;
            }
        });
        playAllButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (!tracks.isEmpty()) playSelection(0); }
        });
        listStatus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadNextPage(); }
        });

        trackList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                int index = position;
                if (index < 0 || index >= tracks.size()) return;
                if (bound && playback != null) playSelection(index); else pendingPlayIndex = index;
                startActivity(new Intent(LibraryActivity.this, PlayerActivity.class));
            }
        });
        trackList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                int index = position;
                if (index < 0 || index >= tracks.size()) return false;
                toggleFavorite(tracks.get(index));
                return true;
            }
        });
        trackList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) { }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                if (total > 0 && first + visible >= total - 8) loadNextPage();
            }
        });
        collectionGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                MediaCollection item = collections.get(position);
                openCollectionDetail(item);
            }
        });

        findViewById(R.id.home_roam).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startRoam(); }
        });
        findViewById(R.id.home_favorite).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openFavorites(); }
        });
        findViewById(R.id.home_recent).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openRecent(); }
        });
        findViewById(R.id.home_library).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openLibrary(); }
        });

        findViewById(R.id.mini_previous).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (playback != null) playback.previous(); }
        });
        miniToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (playback != null) playback.toggle(); }
        });
        findViewById(R.id.mini_next).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (playback != null) playback.next(); }
        });
        findViewById(R.id.mini_info).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(LibraryActivity.this, PlayerActivity.class)); }
        });
    }

    private void bindNav(int id, View.OnClickListener listener) {
        findViewById(id).setOnClickListener(listener);
    }

    private void openHome() {
        selectNav(findViewById(R.id.nav_home));
        source = SOURCE_HOME;
        showTrackView(true);
        pageTitle.setText("首页");
        resetTrackSource();
        loadNextPage();
    }

    private void openLibrary() {
        selectNav(findViewById(R.id.nav_library));
        source = SOURCE_LIBRARY;
        showTrackView(false);
        pageTitle.setText("音乐库");
        resetTrackSource();
        loadNextPage();
    }

    private void openFavorites() {
        selectNav(findViewById(R.id.nav_favorites));
        source = SOURCE_FAVORITES;
        showTrackView(false);
        pageTitle.setText("收藏");
        resetTrackSource();
        loadNextPage();
    }

    private void openRecent() {
        selectNav(findViewById(R.id.nav_recent));
        source = SOURCE_RECENT;
        showTrackView(false);
        pageTitle.setText("最近播放");
        contentGeneration++;
        clearLoadingQueue();
        tracks.clear();
        loadedTrackGuids.clear();
        tracks.addAll(historyStore.list());
        trackAdapter.notifyDataSetChanged();
        allLoaded = true;
        loading = false;
        listStatus.setText(tracks.size() + " 首 · 本机播放记录");
        updateEmptyState();
    }

    private void openCollections(final String type, String title, View nav) {
        selectNav(nav);
        source = SOURCE_COLLECTION;
        collectionType = type;
        collectionGuid = "";
        pageTitle.setText(title);
        showCollectionView();
        final int generation = ++contentGeneration;
        clearLoadingQueue();
        collections.clear();
        collectionAdapter.notifyDataSetChanged();
        listStatus.setText("正在加载…");
        emptyState.setVisibility(View.GONE);
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final MediaCollectionPage result = api.getCollections(type, 1, 300);
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (destroyed || generation != contentGeneration) return;
                            collections.addAll(result.items);
                            collectionAdapter.notifyDataSetChanged();
                            listStatus.setText("共 " + (result.total > 0 ? result.total : collections.size()) + " 项");
                            updateEmptyState();
                        }
                    });
                } catch (final Exception error) {
                    showLoadError(generation, error);
                }
            }
        });
    }

    private void openCollectionDetail(MediaCollection item) {
        source = SOURCE_COLLECTION;
        collectionType = item.type;
        collectionGuid = item.guid;
        showTrackView(false);
        pageTitle.setText(item.name.length() == 0 ? "未命名分类" : item.name);
        resetTrackSource();
        loadNextPage();
    }

    private void beginSearch() {
        hideKeyboard();
        activeQuery = searchInput.getText().toString().trim();
        if (activeQuery.length() == 0) {
            openHome();
            return;
        }
        selectNav(null);
        source = SOURCE_SEARCH;
        showTrackView(false);
        pageTitle.setText("搜索结果");
        resetTrackSource();
        loadNextPage();
    }

    private void resetTrackSource() {
        contentGeneration++;
        clearLoadingQueue();
        tracks.clear();
        loadedTrackGuids.clear();
        trackAdapter.notifyDataSetChanged();
        nextPage = 1;
        allLoaded = false;
        loading = false;
        listStatus.setText("正在加载…");
        emptyState.setVisibility(View.GONE);
    }

    private void loadNextPage() {
        if (destroyed || loading || allLoaded || source == SOURCE_RECENT || collectionGrid.getVisibility() == View.VISIBLE) return;
        loading = true;
        final int page = nextPage;
        final int requestedSource = source;
        final int generation = contentGeneration;
        final String query = activeQuery;
        final String type = collectionType;
        final String guid = collectionGuid;
        if (page > 1) listStatus.setText("正在加载更多…");

        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final TrackPage result;
                    if (requestedSource == SOURCE_FAVORITES) {
                        result = api.getFavoriteTracks(page, PAGE_SIZE);
                    } else if (requestedSource == SOURCE_COLLECTION && guid.length() > 0) {
                        result = api.getCollectionTracks(type, guid, page, PAGE_SIZE);
                    } else {
                        result = api.getTracks(page, PAGE_SIZE,
                                requestedSource == SOURCE_SEARCH ? query : "");
                    }
                    main.post(new Runnable() {
                        @Override public void run() {
                            if (destroyed || generation != contentGeneration || requestedSource != source) return;
                            int before = tracks.size();
                            for (Track track : result.tracks) {
                                if (loadedTrackGuids.add(track.guid)) tracks.add(track);
                            }
                            boolean repeatedPage = !result.tracks.isEmpty() && tracks.size() == before;
                            nextPage++;
                            loading = false;
                            allLoaded = repeatedPage || result.tracks.isEmpty()
                                    || (result.total > 0 && tracks.size() >= result.total);
                            appendLoadedTracksToQueue();
                            trackAdapter.notifyDataSetChanged();
                            if (repeatedPage || (result.tracks.isEmpty() && result.total > tracks.size())) {
                                listStatus.setText("已加载 " + tracks.size() + " 首 · 服务器未返回更多歌曲");
                            } else if (allLoaded) {
                                listStatus.setText("已全部加载 " + tracks.size() + " 首");
                            } else {
                                listStatus.setText("已加载 " + tracks.size()
                                        + (result.total > 0 ? " / " + result.total : "") + " 首 · 继续加载中…");
                            }
                            updateEmptyState();
                            if (!allLoaded) main.post(new Runnable() {
                                @Override public void run() {
                                    if (!destroyed && generation == contentGeneration) loadNextPage();
                                }
                            });
                        }
                    });
                } catch (final Exception error) {
                    showLoadError(generation, error);
                }
            }
        });
    }

    private void startRoam() {
        if (tracks.isEmpty()) {
            Toast.makeText(this, "歌曲加载中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<Track> shuffled = new ArrayList<Track>(tracks);
        Collections.shuffle(shuffled);
        if (playback == null) {
            Toast.makeText(this, "播放器连接中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        playingQueueToken = playback.setQueueAndPlay(shuffled, 0);
        queuedTrackCount = tracks.size();
        shuffleNewQueueTracks = true;
        startActivity(new Intent(this, PlayerActivity.class));
    }

    private void toggleFavorite(final Track track) {
        final boolean wanted = !track.favorite;
        track.favorite = wanted;
        trackAdapter.notifyDataSetChanged();
        Toast.makeText(this, wanted ? "已收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    api.setFavorite(track.guid, wanted);
                } catch (final Exception error) {
                    main.post(new Runnable() {
                        @Override public void run() {
                            track.favorite = !wanted;
                            trackAdapter.notifyDataSetChanged();
                            Toast.makeText(LibraryActivity.this, "操作失败：" + safeMessage(error), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private void playSelection(int index) {
        if (index < 0 || index >= tracks.size()) return;
        if (playback == null) {
            pendingPlayIndex = index;
            return;
        }
        playingQueueToken = playback.setQueueAndPlay(new ArrayList<Track>(tracks), index);
        queuedTrackCount = tracks.size();
        shuffleNewQueueTracks = false;
    }

    private void appendLoadedTracksToQueue() {
        if (playback == null || playingQueueToken.length() == 0 || queuedTrackCount >= tracks.size()) return;
        ArrayList<Track> added = new ArrayList<Track>(tracks.subList(queuedTrackCount, tracks.size()));
        if (shuffleNewQueueTracks) Collections.shuffle(added);
        if (!playback.appendToQueue(playingQueueToken, added)) playingQueueToken = "";
        queuedTrackCount = tracks.size();
    }

    private void clearLoadingQueue() {
        playingQueueToken = "";
        queuedTrackCount = 0;
        pendingPlayIndex = -1;
        shuffleNewQueueTracks = false;
    }

    private void showTrackView(boolean showHome) {
        homeHeader.setVisibility(showHome ? View.VISIBLE : View.GONE);
        trackContainer.setVisibility(View.VISIBLE);
        collectionGrid.setVisibility(View.GONE);
        playAllButton.setVisibility(View.VISIBLE);
        updateEmptyState();
    }

    private void showCollectionView() {
        homeHeader.setVisibility(View.GONE);
        trackContainer.setVisibility(View.GONE);
        collectionGrid.setVisibility(View.VISIBLE);
        playAllButton.setVisibility(View.GONE);
    }

    private void selectNav(View nav) {
        if (selectedNav != null) selectedNav.setSelected(false);
        selectedNav = nav;
        if (selectedNav != null) selectedNav.setSelected(true);
    }

    private void updateEmptyState() {
        boolean empty = collectionGrid.getVisibility() == View.VISIBLE
                ? collections.isEmpty() : tracks.isEmpty();
        emptyState.setVisibility(!loading && empty ? View.VISIBLE : View.GONE);
        if (source == SOURCE_RECENT && empty) emptyState.setText("还没有播放记录");
        else if (source == SOURCE_FAVORITES && empty) emptyState.setText("还没有收藏歌曲\n点击歌曲右侧爱心即可收藏");
        else emptyState.setText("暂无内容");
    }

    private void showLoadError(final int generation, final Exception error) {
        main.post(new Runnable() {
            @Override public void run() {
                if (destroyed || generation != contentGeneration) return;
                loading = false;
                listStatus.setText(collectionGrid.getVisibility() == View.VISIBLE ? safeMessage(error)
                        : "已加载 " + tracks.size() + " 首 · " + safeMessage(error) + " · 点击重试");
                updateEmptyState();
                if (!api.session().hasSession()) openLogin();
            }
        });
    }

    @Override protected void onStart() {
        super.onStart();
        if (!isFinishing() && !serviceBinding) {
            serviceBinding = bindService(new Intent(this, PlaybackService.class), serviceConnection, Context.BIND_AUTO_CREATE);
        }
        main.removeCallbacks(updatePlayer);
        main.post(updatePlayer);
    }

    @Override protected void onStop() {
        main.removeCallbacks(updatePlayer);
        // Keep the service connection while later pages load behind the playback screen.
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!isFinishing()) AppUpdater.onResume(this);
    }

    @Override protected void onPause() {
        AppUpdater.onPause(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        if (serviceBinding) {
            unbindService(serviceConnection);
            serviceBinding = false;
        }
        bound = false;
        playback = null;
        super.onDestroy();
    }

    private void updateMiniPlayer() {
        if (playback == null) return;
        PlaybackService.PlaybackState state = playback.snapshot();
        if (state.track == null) {
            miniTitle.setText(R.string.not_playing);
            miniArtist.setText("选择一首歌曲开始播放");
            miniToggle.setText("播放");
            return;
        }
        Track track = state.track;
        miniTitle.setText(track.title.length() == 0 ? getString(R.string.unknown_title) : track.title);
        miniArtist.setText((track.artist.length() == 0 ? getString(R.string.unknown_artist) : track.artist)
                + "  ·  " + (state.queueIndex + 1) + "/" + state.queueSize);
        miniToggle.setText(state.loading ? "缓冲" : state.playing ? "暂停" : "播放");
        if (!track.guid.equals(displayedMiniGuid)) {
            displayedMiniGuid = track.guid;
            CoverLoader.get(this).load(track.coverId, miniCover, 160);
        }
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    private void openLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    private static String shortServer(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl;
        value = value.replace("http://", "").replace("https://", "");
        return value;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.length() == 0 ? error.getClass().getSimpleName() : message;
    }
}
