package com.example.fqdownloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.example.fqdownloader.model.BookInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AudioPlaybackService extends Service {

    public static final String ACTION_START = "com.example.fqdownloader.audio.START";
    public static final String ACTION_STOP = "com.example.fqdownloader.audio.STOP";
    public static final String ACTION_SET_SPEED = "com.example.fqdownloader.audio.SET_SPEED";
    public static final String ACTION_QUERY_STATE = "com.example.fqdownloader.audio.QUERY_STATE";
    public static final String ACTION_STATE = "com.example.fqdownloader.audio.STATE";
    public static final String EXTRA_BOOK_ID = "book_id";
    public static final String EXTRA_BOOK_TITLE = "book_title";
    public static final String EXTRA_CHAPTER_IDS = "chapter_ids";
    public static final String EXTRA_CHAPTER_TITLES = "chapter_titles";
    public static final String EXTRA_START_INDEX = "start_index";
    public static final String EXTRA_SPEED = "speed";
    public static final String EXTRA_ACTIVE = "active";
    public static final String EXTRA_STATE_BOOK_ID = "state_book_id";
    public static final String EXTRA_CHAPTER_INDEX = "chapter_index";
    public static final String EXTRA_QUEUE_START_INDEX = "queue_start_index";
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_PROGRESS_PERCENT = "progress_percent";
    public static final String EXTRA_POSITION_MS = "position_ms";
    public static final String EXTRA_DURATION_MS = "duration_ms";
    public static final String EXTRA_RESUME_POSITION_MS = "resume_position_ms";
    public static final String EXTRA_SAVE_RESUME_POSITION = "save_resume_position";

    private static final String CHANNEL_ID = "chapter_audio_playback";
    private static final int NOTIFICATION_ID = 4101;
    private static final int AUDIO_URL_BATCH_SIZE = 5;
    private static final long PROGRESS_CHECK_INTERVAL_MS = 500;
    private static final long PAGE_SYNC_INTERVAL_MS = 2500;
    private static final long RESUME_SAVE_INTERVAL_MS = 1000;
    private static final long AUDIO_FOCUS_RETRY_INTERVAL_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressCheck = this::checkPlaybackProgress;
    private final Runnable audioFocusRetry = this::tryRestoreAudioFocus;
    private final List<String> chapterIds = new ArrayList<>();
    private final List<String> chapterTitles = new ArrayList<>();
    private final Map<String, String> audioUrls = new HashMap<>();
    private DownloadEngine engine;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private MediaPlayer player;
    private String bookId;
    private String bookTitle;
    private int currentIndex = -1;
    private int queueStartIndex = -1;
    private int generation;
    private long lastPageSyncBroadcastAt;
    private long lastResumeSaveAt;
    private long resumePositionMs;
    private int resumeChapterIndex = -1;
    private float playbackSpeed = 1.5f;
    private boolean active;
    private boolean currentPrepared;
    private boolean chapterTransitionInProgress;
    private boolean pausedForFocus;
    private boolean duckedForFocus;

    private final AudioManager.OnAudioFocusChangeListener focusChangeListener =
            this::onAudioFocusChanged;
    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(
                        List<AudioPlaybackConfiguration> configurations) {
                    if (active && pausedForFocus) tryRestoreAudioFocus();
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        engine = new DownloadEngine(null, new DownloadEngine.ProgressListener() {
            @Override public void onLog(String message) {}
            @Override public void onProgress(int current, int total, String batchInfo) {}
            @Override public void onError(String error) {}
            @Override public void onComplete(String outputPath) {}
            @Override public void onSearchResults(List<BookInfo> results) {}
        });
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusChangeListener, handler)
                .setWillPauseWhenDucked(false)
                .setAcceptsDelayedFocusGain(true)
                .build();
        audioManager.registerAudioPlaybackCallback(playbackCallback, handler);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            startPlayback(intent);
        } else if (ACTION_STOP.equals(action)) {
            stopPlayback(null, intent.getBooleanExtra(EXTRA_SAVE_RESUME_POSITION, true));
        } else if (ACTION_SET_SPEED.equals(action)) {
            setPlaybackSpeed(intent.getFloatExtra(EXTRA_SPEED, playbackSpeed));
            if (!active) stopSelf(startId);
        } else if (ACTION_QUERY_STATE.equals(action)) {
            broadcastState(null);
            if (!active) stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void startPlayback(Intent intent) {
        stopPlayers();
        generation++;
        chapterIds.clear();
        chapterTitles.clear();
        audioUrls.clear();
        String[] ids = intent.getStringArrayExtra(EXTRA_CHAPTER_IDS);
        String[] titles = intent.getStringArrayExtra(EXTRA_CHAPTER_TITLES);
        if (ids == null || titles == null || ids.length == 0 || ids.length != titles.length) {
            stopPlayback(getString(R.string.audio_chapters_unavailable));
            return;
        }
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == null || ids[i].trim().isEmpty()) {
                stopPlayback(getString(R.string.audio_chapters_unavailable));
                return;
            }
            chapterIds.add(ids[i]);
            chapterTitles.add(titles[i] == null ? "" : titles[i]);
        }
        bookId = intent.getStringExtra(EXTRA_BOOK_ID);
        bookTitle = intent.getStringExtra(EXTRA_BOOK_TITLE);
        currentIndex = intent.getIntExtra(EXTRA_START_INDEX, 0);
        queueStartIndex = currentIndex;
        resumeChapterIndex = currentIndex;
        resumePositionMs = Math.max(0,
                intent.getLongExtra(EXTRA_RESUME_POSITION_MS, 0));
        chapterTransitionInProgress = false;
        playbackSpeed = intent.getFloatExtra(EXTRA_SPEED, playbackSpeed);
        if (bookId == null || currentIndex < 0 || currentIndex >= chapterIds.size()) {
            stopPlayback(getString(R.string.audio_chapters_unavailable));
            return;
        }
        active = true;
        pausedForFocus = false;
        startForeground(NOTIFICATION_ID, buildNotification());
        int focusResult = audioManager.requestAudioFocus(audioFocusRequest);
        if (focusResult == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
            stopPlayback(getString(R.string.audio_focus_unavailable));
            return;
        }
        pausedForFocus = focusResult == AudioManager.AUDIOFOCUS_REQUEST_DELAYED;
        if (pausedForFocus) scheduleAudioFocusRetry();
        playChapter(currentIndex, generation);
    }

    private void playChapter(int chapterIndex, int requestGeneration) {
        if (!active || requestGeneration != generation
                || chapterIndex < 0 || chapterIndex >= chapterIds.size()) return;
        currentIndex = chapterIndex;
        if (resumePositionMs == 0 || chapterIndex != resumeChapterIndex) {
            persistResumePosition(0);
        }
        lastPageSyncBroadcastAt = 0;
        currentPrepared = false;
        releaseCurrentPlayer();
        updateNotification();
        broadcastState(null);

        String chapterId = chapterIds.get(chapterIndex);
        String cachedAudioUrl = audioUrls.get(chapterId);
        if (cachedAudioUrl != null && !cachedAudioUrl.trim().isEmpty()) {
            preparePlayer(cachedAudioUrl, chapterIndex, requestGeneration);
            return;
        }
        int batchEnd = Math.min(chapterIds.size(), chapterIndex + AUDIO_URL_BATCH_SIZE);
        List<String> batchChapterIds = new ArrayList<>(
                chapterIds.subList(chapterIndex, batchEnd));
        engine.loadChapterAudioUrls(batchChapterIds,
                new DownloadEngine.AudioUrlsCallback() {
                    @Override
                    public void onLoaded(Map<String, String> loadedAudioUrls) {
                        handler.post(() -> {
                            if (!active || requestGeneration != generation
                                    || chapterIndex != currentIndex) return;
                            audioUrls.putAll(loadedAudioUrls);
                            String audioUrl = audioUrls.get(chapterId);
                            if (audioUrl == null || audioUrl.trim().isEmpty()) {
                                stopPlayback(getString(R.string.audio_url_unavailable));
                                return;
                            }
                            preparePlayer(audioUrl, chapterIndex, requestGeneration);
                        });
                    }

                    @Override
                    public void onError(Exception error) {
                        handler.post(() -> {
                            if (active && requestGeneration == generation
                                    && chapterIndex == currentIndex) {
                                stopPlayback(getReadableMessage(error));
                            }
                        });
                    }
                });
    }

    private void preparePlayer(String audioUrl, int chapterIndex, int requestGeneration) {
        MediaPlayer nextPlayer = new MediaPlayer();
        releaseCurrentPlayer();
        player = nextPlayer;
        currentPrepared = false;
        nextPlayer.setOnPreparedListener(mediaPlayer -> {
            if (nextPlayer != player || !active || requestGeneration != generation
                    || chapterIndex != currentIndex) return;
            try {
                mediaPlayer.setPlaybackParams(
                        mediaPlayer.getPlaybackParams().setSpeed(playbackSpeed));
                currentPrepared = true;
                if (chapterIndex == resumeChapterIndex && resumePositionMs > 0) {
                    int seekPosition = (int) Math.min(resumePositionMs,
                            Math.max(0, mediaPlayer.getDuration() - 500));
                    mediaPlayer.seekTo(seekPosition);
                    resumePositionMs = 0;
                    resumeChapterIndex = -1;
                }
                startCurrentPlayerIfAllowed();
                updateNotification();
            } catch (IllegalStateException error) {
                stopPlayback(getReadableMessage(error));
            }
        });
        nextPlayer.setOnCompletionListener(mediaPlayer -> {
            if (nextPlayer != player || !active || requestGeneration != generation) return;
            advanceToNextChapter(requestGeneration);
        });
        nextPlayer.setOnErrorListener((mediaPlayer, what, extra) -> {
            if (nextPlayer != player || !active || requestGeneration != generation) return true;
            stopPlayback(getString(R.string.audio_playback_error, what, extra));
            return true;
        });
        try {
            nextPlayer.setDataSource(audioUrl);
            nextPlayer.prepareAsync();
        } catch (Exception error) {
            stopPlayback(getReadableMessage(error));
        }
    }

    private void advanceToNextChapter(int requestGeneration) {
        if (!active || requestGeneration != generation || chapterTransitionInProgress) return;
        chapterTransitionInProgress = true;
        handler.removeCallbacks(progressCheck);
        releaseCurrentPlayer();
        int nextIndex = currentIndex + 1;
        if (nextIndex >= chapterIds.size()) {
            clearResumePosition();
            stopPlayback(null, false);
            return;
        }
        currentIndex = nextIndex;
        broadcastState(null);
        updateNotification();
        playChapter(nextIndex, requestGeneration);
    }

    private void startCurrentPlayerIfAllowed() {
        if (!currentPrepared || player == null || pausedForFocus) return;
        if (duckedForFocus) player.setVolume(0.2f, 0.2f);
        if (!player.isPlaying()) player.start();
        chapterTransitionInProgress = false;
        scheduleProgressCheck();
    }

    private void checkPlaybackProgress() {
        if (!active || !currentPrepared || player == null) return;
        try {
            int duration = player.getDuration();
            int position = player.getCurrentPosition();
            int progressPercent = duration > 0
                    ? (int) Math.min(100, position * 100L / duration) : 0;
            long now = SystemClock.elapsedRealtime();
            if (now - lastResumeSaveAt >= RESUME_SAVE_INTERVAL_MS) {
                persistResumePosition(position);
                lastResumeSaveAt = now;
            }
            if (now - lastPageSyncBroadcastAt >= PAGE_SYNC_INTERVAL_MS) {
                broadcastPlaybackProgress(progressPercent, position, duration);
                lastPageSyncBroadcastAt = now;
            }
            if (player != null && player.isPlaying()) scheduleProgressCheck();
        } catch (IllegalStateException error) {
            stopPlayback(getReadableMessage(error));
        }
    }

    private void scheduleProgressCheck() {
        handler.removeCallbacks(progressCheck);
        handler.postDelayed(progressCheck, PROGRESS_CHECK_INTERVAL_MS);
    }

    private void setPlaybackSpeed(float speed) {
        playbackSpeed = speed;
        try {
            if (currentPrepared && player != null) {
                player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
            }
        } catch (IllegalStateException error) {
            stopPlayback(getReadableMessage(error));
        }
    }

    private void onAudioFocusChanged(int focusChange) {
        if (!active) return;
        try {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                pauseForAudioFocus();
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                duckedForFocus = true;
                if (player != null && currentPrepared) {
                    player.setVolume(0.2f, 0.2f);
                }
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                pauseForAudioFocus();
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                handler.removeCallbacks(audioFocusRetry);
                if (duckedForFocus && player != null) {
                    player.setVolume(1.0f, 1.0f);
                }
                duckedForFocus = false;
                if (pausedForFocus) {
                    pausedForFocus = false;
                    if (player != null && currentPrepared) {
                        startCurrentPlayerIfAllowed();
                    }
                }
            }
        } catch (IllegalStateException error) {
            stopPlayback(getReadableMessage(error));
        }
    }

    private void pauseForAudioFocus() {
        pausedForFocus = true;
        if (player != null && currentPrepared && player.isPlaying()) {
            player.pause();
        }
        handler.removeCallbacks(progressCheck);
        persistResumePosition(getCurrentPositionOrResumeMs());
        scheduleAudioFocusRetry();
    }

    private void scheduleAudioFocusRetry() {
        handler.removeCallbacks(audioFocusRetry);
        handler.postDelayed(audioFocusRetry, AUDIO_FOCUS_RETRY_INTERVAL_MS);
    }

    private void tryRestoreAudioFocus() {
        if (!active || !pausedForFocus) return;
        if (hasOtherActivePlayback()) {
            scheduleAudioFocusRetry();
            return;
        }
        int result = audioManager.requestAudioFocus(audioFocusRequest);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            pausedForFocus = false;
            if (player != null && currentPrepared) startCurrentPlayerIfAllowed();
        } else if (result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED) {
            scheduleAudioFocusRetry();
        } else {
            scheduleAudioFocusRetry();
        }
    }

    private boolean hasOtherActivePlayback() {
        return audioManager.isMusicActive();
    }

    private void stopPlayback(@Nullable String error) {
        stopPlayback(error, true);
    }

    private void stopPlayback(@Nullable String error, boolean saveResumePosition) {
        int stoppedPositionMs = getCurrentPositionOrResumeMs();
        int stoppedDurationMs = getCurrentDurationMs();
        if (saveResumePosition) {
            persistResumePosition(stoppedPositionMs);
        } else {
            clearResumePosition();
        }
        active = false;
        generation++;
        handler.removeCallbacks(progressCheck);
        handler.removeCallbacks(audioFocusRetry);
        stopPlayers();
        if (audioManager != null && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
        pausedForFocus = false;
        duckedForFocus = false;
        broadcastState(error, stoppedPositionMs, stoppedDurationMs);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopPlayers() {
        releaseCurrentPlayer();
        currentPrepared = false;
    }

    private void releaseCurrentPlayer() {
        if (player != null) {
            releasePlayer(player);
            player = null;
        }
        currentPrepared = false;
    }

    private void releasePlayer(MediaPlayer mediaPlayer) {
        mediaPlayer.setOnPreparedListener(null);
        mediaPlayer.setOnCompletionListener(null);
        mediaPlayer.setOnErrorListener(null);
        try {
            mediaPlayer.stop();
        } catch (IllegalStateException ignored) {
            // A player that is still preparing or already released cannot be stopped.
        }
        mediaPlayer.release();
    }

    private void broadcastState(@Nullable String error) {
        broadcastState(error, getCurrentPositionMs(), getCurrentDurationMs());
    }

    private void broadcastState(@Nullable String error, int positionMs, int durationMs) {
        if (active && currentIndex >= 0 && currentIndex < chapterIds.size()) {
            String title = chapterTitles.get(currentIndex);
            getSharedPreferences(ChapterPreviewActivity.RECENT_READING_PREFERENCES, MODE_PRIVATE)
                    .edit()
                    .putString(ChapterPreviewActivity.RECENT_BOOK_ID, bookId)
                    .putString(ChapterPreviewActivity.RECENT_BOOK_TITLE, safeBookTitle())
                    .putString(ChapterPreviewActivity.RECENT_CHAPTER_ID,
                            chapterIds.get(currentIndex))
                    .putString(ChapterPreviewActivity.RECENT_CHAPTER_TITLE, title)
                    .putInt(ChapterPreviewActivity.RECENT_CHAPTER_ORDER, currentIndex + 1)
                    .putInt(ChapterPreviewActivity.RECENT_TOTAL_CHAPTERS, chapterIds.size())
                    .apply();
        }
        Intent intent = new Intent(ACTION_STATE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_ACTIVE, active)
                .putExtra(EXTRA_STATE_BOOK_ID, bookId)
                .putExtra(EXTRA_CHAPTER_INDEX, currentIndex)
                .putExtra(EXTRA_QUEUE_START_INDEX, queueStartIndex)
                .putExtra(EXTRA_PROGRESS_PERCENT, durationMs > 0
                        ? (int) Math.min(100, positionMs * 100L / durationMs) : 0)
                .putExtra(EXTRA_POSITION_MS, (long) positionMs)
                .putExtra(EXTRA_DURATION_MS, (long) durationMs);
        if (error != null) intent.putExtra(EXTRA_ERROR, error);
        sendBroadcast(intent);
    }

    private void broadcastPlaybackProgress(int progressPercent, int positionMs, int durationMs) {
        Intent intent = new Intent(ACTION_STATE)
                .setPackage(getPackageName())
                .putExtra(EXTRA_ACTIVE, active)
                .putExtra(EXTRA_STATE_BOOK_ID, bookId)
                .putExtra(EXTRA_CHAPTER_INDEX, currentIndex)
                .putExtra(EXTRA_QUEUE_START_INDEX, queueStartIndex)
                .putExtra(EXTRA_PROGRESS_PERCENT, progressPercent)
                .putExtra(EXTRA_POSITION_MS, (long) positionMs)
                .putExtra(EXTRA_DURATION_MS, (long) durationMs);
        sendBroadcast(intent);
    }

    private int getCurrentPositionMs() {
        if (currentPrepared && player != null) {
            try {
                return player.getCurrentPosition();
            } catch (IllegalStateException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private int getCurrentPositionOrResumeMs() {
        if (!currentPrepared && resumeChapterIndex == currentIndex) {
            return (int) Math.min(Integer.MAX_VALUE, resumePositionMs);
        }
        return getCurrentPositionMs();
    }

    private int getCurrentDurationMs() {
        if (currentPrepared && player != null) {
            try {
                return player.getDuration();
            } catch (IllegalStateException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private void persistResumePosition(int positionMs) {
        if (bookId == null || currentIndex < 0 || currentIndex >= chapterIds.size()) return;
        getSharedPreferences(ChapterPreviewActivity.RECENT_READING_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putString(ChapterPreviewActivity.RECENT_AUDIO_BOOK_ID, bookId)
                .putString(ChapterPreviewActivity.RECENT_AUDIO_CHAPTER_ID,
                        chapterIds.get(currentIndex))
                .putLong(ChapterPreviewActivity.RECENT_AUDIO_POSITION_MS,
                        Math.max(0, positionMs))
                .apply();
    }

    private void clearResumePosition() {
        getSharedPreferences(ChapterPreviewActivity.RECENT_READING_PREFERENCES, MODE_PRIVATE)
                .edit()
                .remove(ChapterPreviewActivity.RECENT_AUDIO_BOOK_ID)
                .remove(ChapterPreviewActivity.RECENT_AUDIO_CHAPTER_ID)
                .remove(ChapterPreviewActivity.RECENT_AUDIO_POSITION_MS)
                .apply();
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 2, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(getString(R.string.audio_notification_title, safeBookTitle()))
                .setContentText(getCurrentChapterTitle())
                .setContentIntent(openPendingIntent)
                .setOngoing(active)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification() {
        if (!active) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.audio_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private String safeBookTitle() {
        return bookTitle == null ? getString(R.string.app_name) : bookTitle;
    }

    private String getCurrentChapterTitle() {
        if (currentIndex < 0 || currentIndex >= chapterTitles.size()) {
            return getString(R.string.audio_notification_loading);
        }
        String title = chapterTitles.get(currentIndex);
        return title.isEmpty() ? getString(R.string.chapter_list_item_number, currentIndex + 1)
                : title;
    }

    private String getReadableMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(progressCheck);
        handler.removeCallbacks(audioFocusRetry);
        if (active) {
            persistResumePosition(getCurrentPositionOrResumeMs());
            active = false;
            stopPlayers();
            if (audioManager != null && audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
        }
        if (audioManager != null) {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback);
        }
        super.onDestroy();
    }
}
