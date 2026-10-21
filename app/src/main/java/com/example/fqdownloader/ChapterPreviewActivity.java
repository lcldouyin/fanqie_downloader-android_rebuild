package com.example.fqdownloader;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.text.Layout;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.fqdownloader.model.BookInfo;
import com.example.fqdownloader.model.Chapter;
import com.example.fqdownloader.ui.EdgeToEdgeHelper;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;

public class ChapterPreviewActivity extends AppCompatActivity {

    public static final String EXTRA_BOOK_ID = "com.example.fqdownloader.extra.BOOK_ID";
    public static final String EXTRA_BOOK_TITLE = "com.example.fqdownloader.extra.BOOK_TITLE";
    public static final String EXTRA_CHAPTER_ID = "com.example.fqdownloader.extra.CHAPTER_ID";
    public static final String EXTRA_CHAPTER_TITLE = "com.example.fqdownloader.extra.CHAPTER_TITLE";
    public static final String EXTRA_CHAPTER_ORDER = "com.example.fqdownloader.extra.CHAPTER_ORDER";
    public static final String EXTRA_TOTAL_CHAPTERS = "com.example.fqdownloader.extra.TOTAL_CHAPTERS";
    public static final String RECENT_READING_PREFERENCES = "recent_reading";
    public static final String RECENT_BOOK_ID = "book_id";
    public static final String RECENT_BOOK_TITLE = "book_title";
    public static final String RECENT_CHAPTER_ID = "chapter_id";
    public static final String RECENT_CHAPTER_TITLE = "chapter_title";
    public static final String RECENT_CHAPTER_ORDER = "chapter_order";
    public static final String RECENT_TOTAL_CHAPTERS = "total_chapters";
    public static final String RECENT_AUDIO_BOOK_ID = "audio_book_id";
    public static final String RECENT_AUDIO_CHAPTER_ID = "audio_chapter_id";
    public static final String RECENT_AUDIO_POSITION_MS = "audio_position_ms";

    private static final int PAGE_SIZE = 5;
    private static final int REQUEST_STORAGE = 101;
    private static final int REQUEST_NOTIFICATIONS = 102;
    private static final int SCROLL_LAYOUT_RETRIES = 8;
    private static final float[] AUDIO_SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
    private static final String[] AUDIO_SPEED_LABELS = {
            "0.75×", "1.0×", "1.25×", "1.5×", "2.0×"
    };
    private String bookId;
    private String bookTitle;
    private String selectedChapterId;
    private int chapterOrder;
    private int totalChapters;
    private int pageStartIndex;
    private int loadedChapterCount;
    private int previewGeneration;
    private int pageHistoryIndex;
    private boolean previewLoading;
    private boolean previewLoaded;
    private boolean downloadInProgress;
    private boolean downloadWholeBook;
    private boolean pendingDownloadTxt;
    private boolean listenModeActive;
    private boolean audioStartPending;
    private boolean preserveListeningOnStartFailure;
    private boolean ignoreStoppedServiceIndexOnce;
    private boolean audioChapterListLoading;
    private boolean pendingListenAfterNotificationPermission;
    private boolean audioStartRequested;
    private int audioQueueIndex = -1;
    private int audioQueueStartIndex = -1;
    private int audioResumeChapterIndex = -1;
    private int pendingAudioServiceStartIndex = -1;
    private int pendingAudioStartIndex = -1;
    private long audioPositionMs;
    private long audioDurationMs;
    private long audioResumePositionMs;
    private int pendingAudioSyncChapterIndex = -1;
    private long pendingAudioSyncPositionMs;
    private long pendingAudioSyncDurationMs;
    private int audioScrollLayoutRetries;
    private int pendingChapterStartScrollIndex = -1;
    private int chapterStartScrollRetries;
    private int pendingVisibleChapterIndex = -1;
    private float audioPlaybackSpeed = 1.5f;
    private int pendingStart;
    private int pendingEnd;

    private TextView tvChapterPosition;
    private TextView tvChapterError;
    private TextView tvDownloadStatus;
    private ProgressBar chapterLoading;
    private LinearProgressIndicator downloadProgress;
    private ScrollView chapterContentContainer;
    private LinearLayout chapterPreviewItems;
    private View chapterErrorContainer;
    private View retryButton;
    private MaterialButton scopeButton;
    private MaterialButton epubButton;
    private MaterialButton txtButton;
    private MaterialButton previousButton;
    private MaterialButton nextButton;
    private MaterialButton listenButton;
    private MaterialButton audioSpeedButton;
    private final List<Integer> pageStartHistory = new ArrayList<>();
    private List<Chapter> audioChapters = new ArrayList<>();
    private List<DownloadEngine.ChapterPreview> currentPageChapters = new ArrayList<>();
    private final List<TextView> currentPageBodies = new ArrayList<>();
    private final Runnable audioScrollSyncRunnable = () -> applyAudioScrollSync(
            pendingAudioSyncChapterIndex, pendingAudioSyncPositionMs,
            pendingAudioSyncDurationMs);
    private final Runnable chapterStartScrollRunnable = this::applyPendingChapterStartScroll;
    private DownloadEngine previewEngine;
    private DownloadEngine downloadEngine;
    private boolean audioReceiverRegistered;
    private final Runnable saveVisibleChapterRunnable = () -> {
        if (pendingVisibleChapterIndex >= 0
                && pendingVisibleChapterIndex < currentPageChapters.size()) {
            int index = pendingVisibleChapterIndex;
            pendingVisibleChapterIndex = -1;
            saveRecentReading(currentPageChapters.get(index).chapter, pageStartIndex + index + 1);
        }
    };
    private final BroadcastReceiver audioPlaybackReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean stateActive = intent.getBooleanExtra(
                    AudioPlaybackService.EXTRA_ACTIVE, false);
            String stateBookId = intent.getStringExtra(
                    AudioPlaybackService.EXTRA_STATE_BOOK_ID);
            if (!bookId.equals(stateBookId)) {
                if (!audioStartPending) listenModeActive = false;
                updateAudioControls();
                return;
            }
            int chapterIndex = intent.getIntExtra(
                    AudioPlaybackService.EXTRA_CHAPTER_INDEX, -1);
            if (pendingAudioServiceStartIndex >= 0 && stateActive
                    && chapterIndex != pendingAudioServiceStartIndex) {
                listenModeActive = true;
                updateAudioControls();
                return;
            }
            if (stateActive && chapterIndex == pendingAudioServiceStartIndex) {
                pendingAudioServiceStartIndex = -1;
                preserveListeningOnStartFailure = false;
            } else if (!stateActive) {
                pendingAudioServiceStartIndex = -1;
                preserveListeningOnStartFailure = false;
            }
            listenModeActive = stateActive;
            if (!listenModeActive) {
                pendingChapterStartScrollIndex = -1;
                chapterPreviewItems.removeCallbacks(chapterStartScrollRunnable);
            }
            int queueStartIndex = intent.getIntExtra(
                    AudioPlaybackService.EXTRA_QUEUE_START_INDEX, -1);
            if (queueStartIndex >= 0) audioQueueStartIndex = queueStartIndex;
            String error = intent.getStringExtra(AudioPlaybackService.EXTRA_ERROR);
            if (error != null) {
                Toast.makeText(ChapterPreviewActivity.this, error, Toast.LENGTH_LONG).show();
            }
            int progressPercent = intent.getIntExtra(
                    AudioPlaybackService.EXTRA_PROGRESS_PERCENT, -1);
            audioPositionMs = intent.getLongExtra(AudioPlaybackService.EXTRA_POSITION_MS, 0);
            audioDurationMs = intent.getLongExtra(AudioPlaybackService.EXTRA_DURATION_MS, 0);
            int previousChapterIndex = audioQueueIndex;
            boolean chapterChanged = chapterIndex != audioQueueIndex;
            if (!listenModeActive && !ignoreStoppedServiceIndexOnce && chapterIndex >= 0) {
                audioResumeChapterIndex = chapterIndex;
                audioResumePositionMs = audioPositionMs;
            }
            if (!listenModeActive && ignoreStoppedServiceIndexOnce) {
                ignoreStoppedServiceIndexOnce = false;
            } else if (chapterIndex >= 0 && chapterIndex < audioChapters.size()) {
                audioQueueIndex = chapterIndex;
                audioResumeChapterIndex = chapterIndex;
                if (progressPercent < 0) {
                    saveRecentReading(audioChapters.get(chapterIndex), chapterIndex + 1);
                }
                if (listenModeActive) {
                    if (isLastVisibleChapterTransition(
                            previousChapterIndex, chapterIndex, chapterChanged)) {
                        advanceToNextPage(true);
                    } else {
                        ensureAudioChapterVisible(chapterIndex);
                    }
                    if (chapterChanged) scrollToChapterStart(chapterIndex);
                    if (progressPercent >= 0 && audioDurationMs > 0) {
                        syncPageToAudioProgress(
                                chapterIndex, audioPositionMs, audioDurationMs);
                    }
                }
            } else if (!listenModeActive) {
                pendingChapterStartScrollIndex = -1;
                chapterPreviewItems.removeCallbacks(chapterStartScrollRunnable);
            } else if (listenModeActive && audioChapters.isEmpty()) {
                audioResumeChapterIndex = chapterIndex;
                loadAudioChapterList(false);
            }
            updateAudioControls();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bookId = getIntent().getStringExtra(EXTRA_BOOK_ID);
        bookTitle = getIntent().getStringExtra(EXTRA_BOOK_TITLE);
        selectedChapterId = getIntent().getStringExtra(EXTRA_CHAPTER_ID);
        chapterOrder = getIntent().getIntExtra(EXTRA_CHAPTER_ORDER, 0);
        totalChapters = getIntent().getIntExtra(EXTRA_TOTAL_CHAPTERS, 0);
        if (isBlank(bookId) || isBlank(bookTitle)
                || isBlank(selectedChapterId)
                || isBlank(getIntent().getStringExtra(EXTRA_CHAPTER_TITLE))
                || chapterOrder <= 0 || totalChapters < chapterOrder) {
            finish();
            return;
        }
        pageStartIndex = chapterOrder - 1;
        pageStartHistory.add(pageStartIndex);

        setContentView(R.layout.activity_chapter_preview);
        EdgeToEdgeHelper.configure(this, findViewById(R.id.chapter_content_root));
        MaterialToolbar toolbar = findViewById(R.id.chapter_toolbar);
        toolbar.setTitle(bookTitle);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvChapterPosition = findViewById(R.id.tv_chapter_count);
        tvChapterError = findViewById(R.id.tv_chapter_error);
        tvDownloadStatus = findViewById(R.id.tv_download_status);
        chapterLoading = findViewById(R.id.chapter_loading);
        downloadProgress = findViewById(R.id.download_progress);
        chapterContentContainer = findViewById(R.id.chapter_content_container);
        chapterPreviewItems = findViewById(R.id.chapter_preview_items);
        chapterErrorContainer = findViewById(R.id.chapter_error_container);
        retryButton = findViewById(R.id.btn_chapter_retry);
        scopeButton = findViewById(R.id.btn_download_scope);
        epubButton = findViewById(R.id.btn_preview_download_epub);
        txtButton = findViewById(R.id.btn_preview_download_txt);
        previousButton = findViewById(R.id.btn_previous_page);
        nextButton = findViewById(R.id.btn_next_page);
        listenButton = findViewById(R.id.btn_listen);
        audioSpeedButton = findViewById(R.id.btn_audio_speed);

        chapterContentContainer.setOnScrollChangeListener(
                (View view, int scrollX, int scrollY, int oldScrollX, int oldScrollY) ->
                        scheduleVisibleChapterSave(scrollY));
        previousButton.setOnClickListener(v -> loadPreviousPage());
        nextButton.setOnClickListener(v -> loadNextPage());
        retryButton.setOnClickListener(v ->
                loadPage(pageStartIndex, null, listenModeActive));
        scopeButton.setOnClickListener(v -> showDownloadScopeChoices());
        epubButton.setOnClickListener(v -> requestDownload(false));
        txtButton.setOnClickListener(v -> requestDownload(true));
        listenButton.setOnClickListener(v -> toggleListening());
        audioSpeedButton.setOnClickListener(v -> showAudioSpeedChoices());

        previewEngine = new DownloadEngine(null, createNoOpListener());
        downloadEngine = new DownloadEngine(
                new DownloadsOutputTargetFactory(this), createDownloadListener());
        tvDownloadStatus.setText(R.string.download_ready);
        updateControls();
        loadPage(pageStartIndex, selectedChapterId);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(AudioPlaybackService.ACTION_STATE);
        ContextCompat.registerReceiver(this, audioPlaybackReceiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        audioReceiverRegistered = true;
        Intent stateIntent = new Intent(this, AudioPlaybackService.class)
                .setAction(AudioPlaybackService.ACTION_QUERY_STATE);
        startService(stateIntent);
    }

    @Override
    protected void onStop() {
        saveVisibleChapterRunnable.run();
        if (audioReceiverRegistered) {
            unregisterReceiver(audioPlaybackReceiver);
            audioReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (chapterContentContainer != null) {
            chapterContentContainer.removeCallbacks(saveVisibleChapterRunnable);
        }
        if (chapterPreviewItems != null) {
            chapterPreviewItems.removeCallbacks(audioScrollSyncRunnable);
            chapterPreviewItems.removeCallbacks(chapterStartScrollRunnable);
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (pendingListenAfterNotificationPermission) {
                pendingListenAfterNotificationPermission = false;
                if (grantResults.length == 0
                        || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.audio_notification_permission_denied,
                            Toast.LENGTH_LONG).show();
                }
                beginListeningFrom(pendingAudioStartIndex);
            }
            return;
        }
        if (requestCode != REQUEST_STORAGE) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startDownload(pendingStart, pendingEnd, pendingDownloadTxt);
        } else {
            tvDownloadStatus.setText(R.string.download_permission_denied);
        }
    }

    private DownloadEngine.ProgressListener createNoOpListener() {
        return new DownloadEngine.ProgressListener() {
            @Override
            public void onLog(String message) {}

            @Override
            public void onProgress(int current, int total, String batchInfo) {}

            @Override
            public void onError(String error) {}

            @Override
            public void onComplete(String outputPath) {}

            @Override
            public void onSearchResults(List<BookInfo> results) {}
        };
    }

    private DownloadEngine.ProgressListener createDownloadListener() {
        return new DownloadEngine.ProgressListener() {
            @Override
            public void onLog(String message) {}

            @Override
            public void onProgress(int current, int total, String batchInfo) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    downloadProgress.setIndeterminate(false);
                    downloadProgress.setMax(total);
                    downloadProgress.setProgressCompat(current, true);
                    tvDownloadStatus.setText(batchInfo);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    finishDownload();
                    tvDownloadStatus.setText(getString(R.string.download_error_detail, error));
                });
            }

            @Override
            public void onComplete(String outputPath) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    finishDownload();
                    tvDownloadStatus.setText(getString(R.string.download_finished, outputPath));
                    Toast.makeText(ChapterPreviewActivity.this,
                            R.string.download_complete_toast, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onSearchResults(List<BookInfo> results) {}
        };
    }

    private void loadPage(int startIndex) {
        loadPage(startIndex, null, false);
    }

    private void loadPage(int startIndex, String startingItemId) {
        loadPage(startIndex, startingItemId, false);
    }

    private void loadPage(int startIndex, String startingItemId, boolean audioDriven) {
        if (startIndex < 0 || (totalChapters > 0 && startIndex >= totalChapters)) return;
        boolean changingPage = previewLoaded && startIndex != pageStartIndex;
        saveVisibleChapterRunnable.run();
        chapterContentContainer.removeCallbacks(saveVisibleChapterRunnable);
        pendingVisibleChapterIndex = -1;
        if (!audioDriven) {
            pendingChapterStartScrollIndex = -1;
            chapterPreviewItems.removeCallbacks(chapterStartScrollRunnable);
            if (listenModeActive) {
                ignoreStoppedServiceIndexOnce = true;
                stopListening(!changingPage);
            }
            audioResumeChapterIndex = -1;
            audioResumePositionMs = 0;
        }
        pageStartIndex = startIndex;
        currentPageChapters.clear();
        currentPageBodies.clear();
        int generation = ++previewGeneration;
        previewLoading = true;
        previewLoaded = false;
        loadedChapterCount = 0;
        chapterLoading.setVisibility(View.VISIBLE);
        chapterErrorContainer.setVisibility(View.GONE);
        chapterContentContainer.setVisibility(View.GONE);
        chapterPreviewItems.removeAllViews();
        tvChapterPosition.setText(R.string.chapter_page_loading);
        updateControls();

        DownloadEngine.ChapterPageCallback callback = new DownloadEngine.ChapterPageCallback() {
                    @Override
                    public void onLoaded(List<DownloadEngine.ChapterPreview> chapters, int count,
                                         int actualStartIndex) {
                        runOnUiThread(() -> {
                            if (generation != previewGeneration || isFinishing() || isDestroyed()) return;
                            pageStartIndex = actualStartIndex;
                            if (audioDriven) {
                                if (pageStartHistory.get(pageHistoryIndex) != actualStartIndex) {
                                    while (pageStartHistory.size() > pageHistoryIndex + 1) {
                                        pageStartHistory.remove(pageStartHistory.size() - 1);
                                    }
                                    pageStartHistory.add(actualStartIndex);
                                    pageHistoryIndex++;
                                }
                            } else {
                                pageStartHistory.set(pageHistoryIndex, actualStartIndex);
                            }
                            totalChapters = count;
                            loadedChapterCount = chapters.size();
                            previewLoading = false;
                            chapterLoading.setVisibility(View.GONE);
                            chapterContentContainer.setVisibility(View.VISIBLE);
                            showPage(chapters);
                            previewLoaded = true;
                            updateControls();
                        });
                    }

                    @Override
                    public void onError(Exception error) {
                        runOnUiThread(() -> {
                            if (generation != previewGeneration || isFinishing() || isDestroyed()) return;
                            previewLoading = false;
                            chapterLoading.setVisibility(View.GONE);
                            chapterErrorContainer.setVisibility(View.VISIBLE);
                            String message = error.getMessage();
                            if (isBlank(message)) message = error.getClass().getSimpleName();
                            tvChapterError.setText(getString(R.string.chapter_page_error, message));
                            tvChapterPosition.setText(getString(R.string.chapter_page_range,
                                    pageStartIndex + 1,
                                    Math.min(pageStartIndex + PAGE_SIZE, totalChapters),
                                    totalChapters));
                            updateControls();
                        });
                    }
                };
        if (startingItemId == null) {
            previewEngine.loadChapterPageStartingAt(bookId, startIndex, PAGE_SIZE, callback);
        } else {
            previewEngine.loadChapterPageStartingAtItem(bookId, startingItemId, PAGE_SIZE, callback);
        }
    }

    private void loadPreviousPage() {
        boolean continueListening = listenModeActive;
        if (pageHistoryIndex > 0) {
            pageHistoryIndex--;
        } else {
            int previousStart = Math.max(0, pageStartIndex - PAGE_SIZE);
            if (previousStart == pageStartIndex) return;
            pageStartHistory.add(0, previousStart);
        }
        int previousPageStart = pageStartHistory.get(pageHistoryIndex);
        loadPage(previousPageStart, null, continueListening);
        if (continueListening) restartListeningFromPageStart(previousPageStart);
    }

    private void loadNextPage() {
        advanceToNextPage(listenModeActive);
    }

    private void advanceToNextPage(boolean continueListening) {
        int nextPageStart;
        if (pageHistoryIndex + 1 < pageStartHistory.size()) {
            nextPageStart = pageStartHistory.get(++pageHistoryIndex);
        } else {
            nextPageStart = pageStartIndex + PAGE_SIZE;
            if (nextPageStart >= totalChapters) return;
            pageStartHistory.add(nextPageStart);
            pageHistoryIndex++;
        }
        loadPage(nextPageStart, null, continueListening);
        if (!continueListening) return;
        restartListeningFromPageStart(nextPageStart);
    }

    private void restartListeningFromPageStart(int pageStart) {
        preserveListeningOnStartFailure = true;
        if (audioChapters.isEmpty()) {
            pendingAudioStartIndex = pageStart;
            audioStartRequested = true;
            audioStartPending = true;
            if (!audioChapterListLoading) loadAudioChapterList(true);
            return;
        }
        startListeningWithChapterList(audioChapters, pageStart, 0);
    }

    private boolean isLastVisibleChapterTransition(
            int previousChapterIndex, int chapterIndex, boolean chapterChanged) {
        return chapterChanged
                && previousChapterIndex >= pageStartIndex
                && previousChapterIndex == pageStartIndex + currentPageChapters.size() - 1
                && chapterIndex == previousChapterIndex + 1
                && chapterIndex < totalChapters;
    }

    private void showPage(List<DownloadEngine.ChapterPreview> chapters) {
        currentPageChapters = new ArrayList<>(chapters);
        for (int i = 0; i < chapters.size(); i++) {
            chapterPreviewItems.addView(createChapterCard(
                    chapters.get(i), pageStartIndex + i));
        }
        chapterContentContainer.scrollTo(0, 0);
        tvChapterPosition.setText(getString(R.string.chapter_page_range,
                pageStartIndex + 1, pageStartIndex + chapters.size(), totalChapters));
        if (!chapters.isEmpty()) {
            if (listenModeActive && audioQueueIndex >= pageStartIndex
                    && audioQueueIndex < pageStartIndex + chapters.size()) {
                saveRecentReading(chapters.get(audioQueueIndex - pageStartIndex).chapter,
                        audioQueueIndex + 1);
            } else {
                saveRecentReading(chapters.get(0).chapter, pageStartIndex + 1);
            }
        }
        if (listenModeActive && audioQueueIndex >= pageStartIndex
                && audioQueueIndex < pageStartIndex + chapters.size()) {
            if (pendingChapterStartScrollIndex == audioQueueIndex) {
                chapterStartScrollRetries = 0;
                schedulePendingChapterStartScroll();
            }
            if (audioDurationMs > 0) {
                syncPageToAudioProgress(audioQueueIndex, audioPositionMs, audioDurationMs);
            }
        }
    }

    private void saveRecentReading(Chapter chapter, int order) {
        getSharedPreferences(RECENT_READING_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putString(RECENT_BOOK_ID, bookId)
                .putString(RECENT_BOOK_TITLE, bookTitle)
                .putString(RECENT_CHAPTER_ID, chapter.itemId)
                .putString(RECENT_CHAPTER_TITLE, chapter.title)
                .putInt(RECENT_CHAPTER_ORDER, order)
                .putInt(RECENT_TOTAL_CHAPTERS, totalChapters)
                .apply();
    }

    private void scheduleVisibleChapterSave(int scrollY) {
        if (currentPageChapters.isEmpty() || chapterPreviewItems.getChildCount() == 0) return;
        int visibleTop = scrollY + chapterContentContainer.getPaddingTop();
        for (int i = 0; i < chapterPreviewItems.getChildCount(); i++) {
            View card = chapterPreviewItems.getChildAt(i);
            if (card.getBottom() > visibleTop) {
                pendingVisibleChapterIndex = i;
                chapterContentContainer.removeCallbacks(saveVisibleChapterRunnable);
                chapterContentContainer.postDelayed(saveVisibleChapterRunnable, 300);
                if (!listenModeActive) {
                    int visibleChapterIndex = pageStartIndex + i;
                    if (audioResumeChapterIndex != visibleChapterIndex) {
                        audioResumeChapterIndex = visibleChapterIndex;
                        audioResumePositionMs = 0;
                    }
                }
                return;
            }
        }
    }

    private void syncPageToAudioProgress(int chapterIndex, long positionMs, long durationMs) {
        if (durationMs <= 0) return;
        pendingAudioSyncChapterIndex = chapterIndex;
        pendingAudioSyncPositionMs = positionMs;
        pendingAudioSyncDurationMs = durationMs;
        audioScrollLayoutRetries = 0;
        chapterPreviewItems.removeCallbacks(audioScrollSyncRunnable);
        chapterPreviewItems.post(audioScrollSyncRunnable);
    }

    private void scrollToChapterStart(int chapterIndex) {
        pendingChapterStartScrollIndex = chapterIndex;
        chapterStartScrollRetries = 0;
        schedulePendingChapterStartScroll();
    }

    private void schedulePendingChapterStartScroll() {
        chapterPreviewItems.removeCallbacks(chapterStartScrollRunnable);
        chapterPreviewItems.post(chapterStartScrollRunnable);
    }

    private void applyPendingChapterStartScroll() {
        int chapterIndex = pendingChapterStartScrollIndex;
        if (!listenModeActive || chapterIndex < 0) return;
        if (chapterIndex < pageStartIndex
                || chapterIndex >= pageStartIndex + chapterPreviewItems.getChildCount()) {
            retryChapterStartScroll();
            return;
        }
        int pageIndex = chapterIndex - pageStartIndex;
        View chapterCard = chapterPreviewItems.getChildAt(pageIndex);
        if (chapterCard.getHeight() == 0 || chapterPreviewItems.getHeight() == 0) {
            retryChapterStartScroll();
            return;
        }
        int targetScroll = chapterPreviewItems.getTop() + chapterCard.getTop()
                - chapterContentContainer.getPaddingTop();
        chapterContentContainer.scrollTo(0, Math.max(0, targetScroll));
        pendingChapterStartScrollIndex = -1;
        chapterStartScrollRetries = 0;
    }

    private void retryChapterStartScroll() {
        if (chapterStartScrollRetries >= SCROLL_LAYOUT_RETRIES) return;
        chapterStartScrollRetries++;
        chapterPreviewItems.postOnAnimation(chapterStartScrollRunnable);
    }

    private void applyAudioScrollSync(int chapterIndex, long positionMs, long durationMs) {
        int pageChapterIndex = chapterIndex - pageStartIndex;
        if (!listenModeActive || pageChapterIndex < 0
                || pageChapterIndex >= chapterPreviewItems.getChildCount()
                || pageChapterIndex >= currentPageBodies.size()) return;
        if (chapterIndex != pendingAudioSyncChapterIndex
                || !listenModeActive || durationMs <= 0) return;
        int pageIndex = chapterIndex - pageStartIndex;
        View currentCard = chapterPreviewItems.getChildAt(pageIndex);
        TextView body = currentPageBodies.get(pageIndex);
        Layout textLayout = body.getLayout();
        if (textLayout == null || durationMs <= 0) {
            retryAudioScrollSync();
            return;
        }
        audioScrollLayoutRetries = 0;

        CharSequence text = body.getText();
        int textLength = text.length();
        if (textLength == 0) return;
        long clampedPosition = Math.max(0, Math.min(positionMs, durationMs));
        int textOffset = (int) (textLength * clampedPosition / durationMs);
        int line = textLayout.getLineForOffset(textOffset);
        int lineStart = textLayout.getLineStart(line);
        int lineEnd = Math.max(lineStart + 1, textLayout.getLineEnd(line));
        float lineProgress = Math.max(0f, Math.min(1f,
                (textOffset - lineStart) / (float) (lineEnd - lineStart)));
        float lineTop = textLayout.getLineTop(line);
        float lineHeight = textLayout.getLineBottom(line) - lineTop;
        int textY = body.getExtendedPaddingTop()
                + Math.round(lineTop + lineHeight * lineProgress);

        View content = (View) body.getParent();
        int cardTop = chapterPreviewItems.getTop() + currentCard.getTop();
        int bodyTop = cardTop + content.getTop() + body.getTop();
        int minScroll = Math.max(0, cardTop - chapterContentContainer.getPaddingTop());
        int maxScroll = Math.max(minScroll, cardTop + currentCard.getHeight()
                - chapterContentContainer.getHeight()
                + chapterContentContainer.getPaddingBottom());
        int targetScroll = bodyTop + textY - chapterContentContainer.getPaddingTop()
                - chapterContentContainer.getHeight() / 5;
        targetScroll = Math.max(minScroll, Math.min(maxScroll, targetScroll));
        chapterContentContainer.scrollTo(0, targetScroll);
    }

    private void retryAudioScrollSync() {
        if (audioScrollLayoutRetries >= SCROLL_LAYOUT_RETRIES) return;
        audioScrollLayoutRetries++;
        chapterPreviewItems.postOnAnimation(audioScrollSyncRunnable);
    }

    private MaterialCardView createChapterCard(
            DownloadEngine.ChapterPreview preview, int absoluteChapterIndex) {
        MaterialCardView card = new MaterialCardView(this);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.search_result_spacing);
        card.setLayoutParams(cardParams);
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.result_card_background));
        card.setStrokeColor(ContextCompat.getColor(this, R.color.card_stroke));
        card.setStrokeWidth(getResources().getDimensionPixelSize(R.dimen.search_result_stroke));
        card.setRadius(getResources().getDimensionPixelSize(R.dimen.search_result_radius));
        card.setCardElevation(0);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = getResources().getDimensionPixelSize(R.dimen.search_result_padding);
        content.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(preview.chapter.title);
        title.setTextColor(ContextCompat.getColor(this, R.color.chapter_title));
        title.setTextSize(18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        MaterialButton listenFromButton = new MaterialButton(this);
        listenFromButton.setText(R.string.audio_listen_from_here);
        listenFromButton.setTextSize(12);
        listenFromButton.setMinWidth(0);
        listenFromButton.setMinHeight(0);
        listenFromButton.setInsetTop(0);
        listenFromButton.setInsetBottom(0);
        listenFromButton.setPadding(padding / 2, 0, padding / 2, 0);
        listenFromButton.setOnClickListener(v -> requestListeningFrom(absoluteChapterIndex));
        titleRow.addView(listenFromButton);
        content.addView(titleRow);

        TextView body = new TextView(this);
        body.setText(isBlank(preview.content)
                ? getString(R.string.chapter_content_unavailable) : preview.content);
        body.setTextColor(ContextCompat.getColor(this, R.color.result_title));
        body.setTextSize(16);
        body.setLineSpacing(getResources().getDimension(R.dimen.chapter_line_spacing), 1.0f);
        body.setTextIsSelectable(true);
        body.setPadding(0, padding, 0, 0);
        content.addView(body);
        currentPageBodies.add(body);

        card.addView(content);
        return card;
    }

    private void toggleListening() {
        if (listenModeActive) {
            stopListening();
            return;
        }
        requestListeningFrom(-1);
    }

    private void requestListeningFrom(int chapterIndex) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingAudioStartIndex = chapterIndex;
            pendingListenAfterNotificationPermission = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        beginListeningFrom(chapterIndex);
    }

    private void beginListeningFrom(int chapterIndex) {
        preserveListeningOnStartFailure = listenModeActive;
        audioQueueStartIndex = -1;
        pendingAudioStartIndex = chapterIndex;
        audioStartRequested = true;
        audioStartPending = true;
        listenModeActive = true;
        updateAudioControls();
        if (audioChapters.isEmpty()) {
            loadAudioChapterList(true);
        } else {
            startListeningWithChapterList(audioChapters, chapterIndex);
        }
    }

    private void stopListening() {
        stopListening(true);
    }

    private void stopListening(boolean saveResumePosition) {
        if (audioQueueIndex >= 0) {
            audioResumeChapterIndex = audioQueueIndex;
        }
        audioResumePositionMs = audioPositionMs;
        listenModeActive = false;
        preserveListeningOnStartFailure = false;
        pendingAudioServiceStartIndex = -1;
        audioStartRequested = false;
        audioStartPending = false;
        pendingAudioStartIndex = -1;
        Intent intent = new Intent(this, AudioPlaybackService.class)
                .setAction(AudioPlaybackService.ACTION_STOP)
                .putExtra(AudioPlaybackService.EXTRA_SAVE_RESUME_POSITION,
                        saveResumePosition);
        startService(intent);
        updateAudioControls();
    }

    private void loadAudioChapterList(boolean startPlayback) {
        if (startPlayback) audioStartRequested = true;
        if (audioChapterListLoading) return;
        audioChapterListLoading = true;
        previewEngine.loadChapters(bookId, new DownloadEngine.ChapterListCallback() {
            @Override
            public void onLoaded(List<Chapter> chapters) {
                runOnUiThread(() -> {
                    audioChapterListLoading = false;
                    boolean shouldStartPlayback = audioStartRequested;
                    audioStartRequested = false;
                    if (shouldStartPlayback) audioStartPending = false;
                    if (isFinishing() || isDestroyed() || !listenModeActive) return;
                    audioChapters = chapters;
                    int startIndex = pendingAudioStartIndex >= 0
                            ? pendingAudioStartIndex
                            : audioResumeChapterIndex >= 0
                                    ? audioResumeChapterIndex : pageStartIndex;
                    boolean explicitlySelectedChapter = pendingAudioStartIndex >= 0;
                    long resumePositionMs = explicitlySelectedChapter
                            ? 0 : audioResumePositionMs;
                    if (!explicitlySelectedChapter && shouldStartPlayback) {
                        int savedResumeIndex = findSavedAudioChapterIndex(chapters);
                        if (savedResumeIndex >= 0) {
                            startIndex = savedResumeIndex;
                            resumePositionMs = getSavedAudioPositionMs();
                        }
                    }
                    pendingAudioStartIndex = -1;
                    if (startIndex < 0 || startIndex >= audioChapters.size()) {
                        listenModeActive = preserveListeningOnStartFailure;
                        preserveListeningOnStartFailure = false;
                        updateAudioControls();
                        Toast.makeText(ChapterPreviewActivity.this,
                                R.string.audio_chapters_unavailable, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    audioQueueIndex = startIndex;
                    if (audioQueueStartIndex < 0) audioQueueStartIndex = startIndex;
                    audioResumeChapterIndex = startIndex;
                    if (shouldStartPlayback && (startIndex < pageStartIndex
                            || startIndex >= pageStartIndex + PAGE_SIZE)) {
                        ensureAudioChapterVisible(startIndex);
                    }
                    if (!shouldStartPlayback) {
                        updateAudioControls();
                        if (listenModeActive) {
                            syncPageToAudioProgress(
                                    startIndex, audioPositionMs, audioDurationMs);
                        }
                        return;
                    }
                    startListeningWithChapterList(chapters, startIndex, resumePositionMs);
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    audioChapterListLoading = false;
                    boolean shouldStartPlayback = audioStartRequested;
                    audioStartRequested = false;
                    if (shouldStartPlayback) audioStartPending = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (!shouldStartPlayback) {
                        audioChapters.clear();
                        return;
                    }
                    listenModeActive = preserveListeningOnStartFailure;
                    preserveListeningOnStartFailure = false;
                    updateAudioControls();
                    showAudioError(error);
                });
            }
        });
    }

    private void startListeningWithChapterList(List<Chapter> chapters, int requestedStartIndex) {
        int startIndex = requestedStartIndex;
        long resumePositionMs = 0;
        if (startIndex < 0) {
            startIndex = findSavedAudioChapterIndex(chapters);
            if (startIndex >= 0) {
                resumePositionMs = getSavedAudioPositionMs();
            } else {
                startIndex = audioResumeChapterIndex >= 0
                        ? audioResumeChapterIndex : pageStartIndex;
                if (startIndex == audioResumeChapterIndex) {
                    resumePositionMs = audioResumePositionMs;
                }
            }
        }
        startListeningWithChapterList(chapters, startIndex, resumePositionMs);
    }

    private void startListeningWithChapterList(
            List<Chapter> chapters, int requestedStartIndex, long resumePositionMs) {
        if (requestedStartIndex < 0) {
            resumePositionMs = audioResumePositionMs;
        }
        int startIndex = requestedStartIndex >= 0
                ? requestedStartIndex : audioResumeChapterIndex >= 0
                        ? audioResumeChapterIndex : pageStartIndex;
        if (startIndex < 0 || startIndex >= chapters.size()) {
            listenModeActive = preserveListeningOnStartFailure;
            preserveListeningOnStartFailure = false;
            audioStartPending = false;
            audioStartRequested = false;
            pendingAudioStartIndex = -1;
            updateAudioControls();
            Toast.makeText(this, R.string.audio_chapters_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        audioStartPending = false;
        audioStartRequested = false;
        pendingAudioStartIndex = -1;
        audioQueueIndex = startIndex;
        audioQueueStartIndex = startIndex;
        audioResumeChapterIndex = startIndex;
        audioResumePositionMs = resumePositionMs;
        pendingAudioServiceStartIndex = startIndex;
        if (startIndex < pageStartIndex || startIndex >= pageStartIndex + PAGE_SIZE) {
            ensureAudioChapterVisible(startIndex);
        }
        scrollToChapterStart(startIndex);
        String[] chapterIds = new String[chapters.size()];
        String[] chapterTitles = new String[chapters.size()];
        for (int i = 0; i < chapters.size(); i++) {
            chapterIds[i] = chapters.get(i).itemId;
            chapterTitles[i] = chapters.get(i).title;
        }
        Intent intent = new Intent(this, AudioPlaybackService.class)
                .setAction(AudioPlaybackService.ACTION_START)
                .putExtra(AudioPlaybackService.EXTRA_BOOK_ID, bookId)
                .putExtra(AudioPlaybackService.EXTRA_BOOK_TITLE, bookTitle)
                .putExtra(AudioPlaybackService.EXTRA_CHAPTER_IDS, chapterIds)
                .putExtra(AudioPlaybackService.EXTRA_CHAPTER_TITLES, chapterTitles)
                .putExtra(AudioPlaybackService.EXTRA_START_INDEX, startIndex)
                .putExtra(AudioPlaybackService.EXTRA_RESUME_POSITION_MS, resumePositionMs)
                .putExtra(AudioPlaybackService.EXTRA_SPEED, audioPlaybackSpeed);
        ContextCompat.startForegroundService(this, intent);
    }

    private int findSavedAudioChapterIndex(List<Chapter> chapters) {
        var preferences = getSharedPreferences(RECENT_READING_PREFERENCES, MODE_PRIVATE);
        String savedBookId = preferences.getString(RECENT_AUDIO_BOOK_ID, null);
        String savedChapterId = preferences.getString(RECENT_AUDIO_CHAPTER_ID, null);
        if (!bookId.equals(savedBookId) || savedChapterId == null) return -1;
        for (int i = 0; i < chapters.size(); i++) {
            if (savedChapterId.equals(chapters.get(i).itemId)) return i;
        }
        return -1;
    }

    private long getSavedAudioPositionMs() {
        return Math.max(0, getSharedPreferences(RECENT_READING_PREFERENCES, MODE_PRIVATE)
                .getLong(RECENT_AUDIO_POSITION_MS, 0));
    }

    private void ensureAudioChapterVisible(int chapterIndex) {
        if (chapterIndex >= pageStartIndex
                && chapterIndex < Math.min(pageStartIndex + PAGE_SIZE, totalChapters)) return;
        int nextPageStart = chapterIndex;
        if (audioQueueStartIndex >= 0 && chapterIndex >= audioQueueStartIndex) {
            int pageOffset = (chapterIndex - audioQueueStartIndex) / PAGE_SIZE * PAGE_SIZE;
            nextPageStart = audioQueueStartIndex + pageOffset;
        }
        if (nextPageStart == pageStartIndex) nextPageStart = chapterIndex;
        loadPage(nextPageStart, null, true);
    }

    private void showAudioError(Exception error) {
        String message = error.getMessage();
        if (isBlank(message)) message = error.getClass().getSimpleName();
        Toast.makeText(this, getString(R.string.audio_error, message), Toast.LENGTH_LONG).show();
    }

    private void showAudioSpeedChoices() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.audio_speed_title)
                .setSingleChoiceItems(AUDIO_SPEED_LABELS, getAudioSpeedIndex(),
                        (dialog, which) -> {
                            audioPlaybackSpeed = AUDIO_SPEEDS[which];
                            if (listenModeActive) {
                                Intent intent = new Intent(this, AudioPlaybackService.class)
                                        .setAction(AudioPlaybackService.ACTION_SET_SPEED)
                                        .putExtra(AudioPlaybackService.EXTRA_SPEED,
                                                audioPlaybackSpeed);
                                startService(intent);
                            }
                            updateAudioControls();
                            dialog.dismiss();
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private int getAudioSpeedIndex() {
        for (int i = 0; i < AUDIO_SPEEDS.length; i++) {
            if (Float.compare(audioPlaybackSpeed, AUDIO_SPEEDS[i]) == 0) return i;
        }
        return 3;
    }

    private void updateAudioControls() {
        if (listenButton == null) return;
        listenButton.setEnabled(listenModeActive || previewLoaded);
        listenButton.setText(listenModeActive
                ? R.string.audio_stop_listening : R.string.audio_listen);
        audioSpeedButton.setText(getString(
                R.string.audio_speed_value, AUDIO_SPEED_LABELS[getAudioSpeedIndex()]));
    }

    private void updateControls() {
        int firstChapter = pageStartIndex + 1;
        int lastChapter = Math.min(pageStartIndex + PAGE_SIZE, totalChapters);
        previousButton.setEnabled(pageStartIndex > 0 && !downloadInProgress && !previewLoading);
        nextButton.setEnabled(totalChapters > 0
                && pageStartIndex + PAGE_SIZE < totalChapters
                && !downloadInProgress && !previewLoading);
        if (downloadWholeBook) {
            scopeButton.setText(R.string.download_scope_whole);
        } else if (totalChapters > 0) {
            scopeButton.setText(getString(
                    R.string.download_scope_current, firstChapter, lastChapter));
        }
        scopeButton.setEnabled(totalChapters > 0 && !downloadInProgress);
        boolean canDownload = previewLoaded && !downloadInProgress;
        epubButton.setEnabled(canDownload);
        txtButton.setEnabled(canDownload);
        updateAudioControls();
    }

    private void showDownloadScopeChoices() {
        int firstChapter = pageStartIndex + 1;
        int lastChapter = Math.min(pageStartIndex + loadedChapterCount, totalChapters);
        String[] choices = {
                getString(R.string.download_scope_current_full, firstChapter, lastChapter),
                getString(R.string.download_scope_whole_full, totalChapters)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.download_scope_title)
                .setSingleChoiceItems(choices, downloadWholeBook ? 1 : 0,
                        (dialog, which) -> {
                            downloadWholeBook = which == 1;
                            dialog.dismiss();
                            updateControls();
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void requestDownload(boolean asTxt) {
        if (downloadInProgress || !previewLoaded || totalChapters <= 0) return;
        int start = downloadWholeBook ? 1 : pageStartIndex + 1;
        int end = downloadWholeBook ? totalChapters
                : Math.min(pageStartIndex + loadedChapterCount, totalChapters);
        if (downloadWholeBook) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.download_whole_book_title)
                    .setMessage(getString(
                            R.string.download_whole_book_confirmation, bookTitle, totalChapters))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.download_start, (dialog, which) ->
                            checkPermissionAndDownload(start, end, asTxt))
                    .show();
        } else {
            checkPermissionAndDownload(start, end, asTxt);
        }
    }

    private void checkPermissionAndDownload(int start, int end, boolean asTxt) {
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = start;
            pendingEnd = end;
            pendingDownloadTxt = asTxt;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            return;
        }
        startDownload(start, end, asTxt);
    }

    private void startDownload(int start, int end, boolean asTxt) {
        if (downloadInProgress) return;
        downloadInProgress = true;
        downloadProgress.setIndeterminate(true);
        downloadProgress.setVisibility(View.VISIBLE);
        tvDownloadStatus.setText(getString(
                R.string.download_starting_range, start, end, asTxt ? "TXT" : "EPUB"));
        updateControls();
        downloadEngine.download(bookId, start, end, asTxt);
    }

    private void finishDownload() {
        downloadInProgress = false;
        downloadProgress.setVisibility(View.GONE);
        updateControls();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
