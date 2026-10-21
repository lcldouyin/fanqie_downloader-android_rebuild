package com.example.fqdownloader;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.fqdownloader.model.BookInfo;
import com.example.fqdownloader.model.Chapter;
import com.example.fqdownloader.ui.EdgeToEdgeHelper;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

public class ChapterListActivity extends AppCompatActivity {

    public static final String EXTRA_BOOK_ID = "com.example.fqdownloader.extra.BOOK_ID";
    public static final String EXTRA_BOOK_TITLE = "com.example.fqdownloader.extra.BOOK_TITLE";
    public static final String EXTRA_SCROLL_TO_CHAPTER_ID =
            "com.example.fqdownloader.extra.SCROLL_TO_CHAPTER_ID";
    public static final String EXTRA_SCROLL_TO_CHAPTER_ORDER =
            "com.example.fqdownloader.extra.SCROLL_TO_CHAPTER_ORDER";

    private String bookId;
    private String bookTitle;
    private String initialChapterId;
    private DownloadEngine engine;
    private ChapterAdapter adapter;
    private LinearLayoutManager layoutManager;
    private RecyclerView chapterList;
    private ProgressBar loading;
    private TextView status;
    private View retryButton;
    private int loadGeneration;
    private int initialChapterOrder;
    private int returnChapterPosition = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bookId = getIntent().getStringExtra(EXTRA_BOOK_ID);
        bookTitle = getIntent().getStringExtra(EXTRA_BOOK_TITLE);
        initialChapterId = getIntent().getStringExtra(EXTRA_SCROLL_TO_CHAPTER_ID);
        initialChapterOrder = getIntent().getIntExtra(EXTRA_SCROLL_TO_CHAPTER_ORDER, 0);
        if (bookId == null || bookId.trim().isEmpty()
                || bookTitle == null || bookTitle.trim().isEmpty()) {
            finish();
            return;
        }

        setContentView(R.layout.activity_chapter_list);
        EdgeToEdgeHelper.configure(this, findViewById(R.id.chapter_list_root));

        MaterialToolbar toolbar = findViewById(R.id.chapter_list_toolbar);
        toolbar.setTitle(bookTitle);
        toolbar.setNavigationOnClickListener(v -> finish());

        loading = findViewById(R.id.chapter_list_loading);
        status = findViewById(R.id.chapter_list_status);
        retryButton = findViewById(R.id.btn_chapter_list_retry);
        chapterList = findViewById(R.id.chapter_list);
        adapter = new ChapterAdapter();
        layoutManager = new LinearLayoutManager(this);
        chapterList.setLayoutManager(layoutManager);
        chapterList.setAdapter(adapter);
        retryButton.setOnClickListener(v -> loadChapters());

        engine = new DownloadEngine(null, createNoOpListener());
        loadChapters();
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreReturnChapterPosition();
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

    private void loadChapters() {
        int generation = ++loadGeneration;
        loading.setVisibility(View.VISIBLE);
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.chapter_list_loading);
        retryButton.setVisibility(View.GONE);
        adapter.setChapters(new ArrayList<>());

        engine.loadChapters(bookId, new DownloadEngine.ChapterListCallback() {
            @Override
            public void onLoaded(List<Chapter> chapters) {
                runOnUiThread(() -> {
                    if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
                    loading.setVisibility(View.GONE);
                    if (chapters.isEmpty()) {
                        showError(getString(R.string.chapter_list_empty));
                        return;
                    }
                    status.setText(getString(R.string.chapter_list_count, chapters.size()));
                    adapter.setChapters(chapters);
                    if (initialChapterId != null || initialChapterOrder > 0) {
                        int targetPosition = findChapterPosition(
                                chapters, initialChapterId, initialChapterOrder);
                        initialChapterId = null;
                        initialChapterOrder = 0;
                        if (targetPosition >= 0) scrollToChapter(targetPosition);
                    }
                });
            }

            @Override
            public void onError(Exception error) {
                runOnUiThread(() -> {
                    if (generation != loadGeneration || isFinishing() || isDestroyed()) return;
                    loading.setVisibility(View.GONE);
                    String message = error.getMessage();
                    if (message == null || message.trim().isEmpty()) {
                        message = error.getClass().getSimpleName();
                    }
                    showError(getString(R.string.chapter_list_error, message));
                });
            }
        });
    }

    private void showError(String message) {
        status.setText(message);
        retryButton.setVisibility(View.VISIBLE);
    }

    private int findChapterPosition(List<Chapter> chapters, String chapterId, int chapterOrder) {
        if (chapterId != null) {
            for (int i = 0; i < chapters.size(); i++) {
                if (chapterId.equals(chapters.get(i).itemId)) return i;
            }
        }
        if (chapterOrder > 0) return Math.min(chapterOrder - 1, chapters.size() - 1);
        return -1;
    }

    private void scrollToChapter(int position) {
        if (position < 0 || position >= adapter.getItemCount()) return;
        returnChapterPosition = position;
        chapterList.post(() -> layoutManager.scrollToPositionWithOffset(position, 0));
    }

    private void restoreReturnChapterPosition() {
        if (returnChapterPosition < 0 || chapterList == null) return;
        chapterList.post(() ->
                layoutManager.scrollToPositionWithOffset(returnChapterPosition, 0));
    }

    private final class ChapterAdapter extends RecyclerView.Adapter<ChapterAdapter.ChapterHolder> {
        private final List<Chapter> chapters = new ArrayList<>();

        void setChapters(List<Chapter> items) {
            chapters.clear();
            chapters.addAll(items);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ChapterHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            MaterialCardView card = new MaterialCardView(ChapterListActivity.this);
            RecyclerView.LayoutParams cardParams = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = getResources().getDimensionPixelSize(
                    R.dimen.search_result_spacing);
            card.setLayoutParams(cardParams);
            card.setCardBackgroundColor(ContextCompat.getColor(
                    ChapterListActivity.this, R.color.result_card_background));
            card.setStrokeColor(ContextCompat.getColor(ChapterListActivity.this, R.color.card_stroke));
            card.setStrokeWidth(getResources().getDimensionPixelSize(R.dimen.search_result_stroke));
            card.setRadius(getResources().getDimensionPixelSize(R.dimen.search_result_radius));
            card.setCardElevation(0);
            card.setClickable(true);
            card.setFocusable(true);

            LinearLayout content = new LinearLayout(ChapterListActivity.this);
            content.setGravity(android.view.Gravity.CENTER_VERTICAL);
            content.setOrientation(LinearLayout.HORIZONTAL);
            int padding = getResources().getDimensionPixelSize(R.dimen.search_result_padding);
            content.setPadding(padding, padding, padding, padding);

            TextView number = new TextView(ChapterListActivity.this);
            number.setTextColor(ContextCompat.getColor(
                    ChapterListActivity.this, R.color.result_metadata));
            number.setTextSize(13);
            content.addView(number);

            TextView title = new TextView(ChapterListActivity.this);
            title.setTextColor(ContextCompat.getColor(
                    ChapterListActivity.this, R.color.result_title));
            title.setTextSize(15);
            title.setTypeface(title.getTypeface(), Typeface.BOLD);
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            titleParams.leftMargin = padding;
            content.addView(title, titleParams);

            TextView arrow = new TextView(ChapterListActivity.this);
            arrow.setText("›");
            arrow.setTextColor(ContextCompat.getColor(
                    ChapterListActivity.this, R.color.result_metadata));
            arrow.setTextSize(22);
            content.addView(arrow);
            card.addView(content);
            return new ChapterHolder(card, number, title);
        }

        @Override
        public void onBindViewHolder(@NonNull ChapterHolder holder, int position) {
            Chapter chapter = chapters.get(position);
            int order = position + 1;
            holder.number.setText(getString(R.string.chapter_list_item_number, order));
            holder.title.setText(chapter.title == null || chapter.title.trim().isEmpty()
                    ? getString(R.string.chapter_list_untitled, order) : chapter.title);
            holder.itemView.setContentDescription(getString(
                    R.string.chapter_list_select_description, holder.title.getText()));
            holder.itemView.setOnClickListener(v -> {
                int clickedPosition = holder.getBindingAdapterPosition();
                if (clickedPosition == RecyclerView.NO_POSITION) return;
                returnChapterPosition = clickedPosition;
                Intent intent = new Intent(ChapterListActivity.this, ChapterPreviewActivity.class);
                intent.putExtra(ChapterPreviewActivity.EXTRA_BOOK_ID, bookId);
                intent.putExtra(ChapterPreviewActivity.EXTRA_BOOK_TITLE, bookTitle);
                intent.putExtra(ChapterPreviewActivity.EXTRA_CHAPTER_ID, chapter.itemId);
                intent.putExtra(ChapterPreviewActivity.EXTRA_CHAPTER_TITLE,
                        holder.title.getText().toString());
                intent.putExtra(ChapterPreviewActivity.EXTRA_CHAPTER_ORDER,
                        clickedPosition + 1);
                intent.putExtra(ChapterPreviewActivity.EXTRA_TOTAL_CHAPTERS, chapters.size());
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return chapters.size();
        }

        final class ChapterHolder extends RecyclerView.ViewHolder {
            final TextView number;
            final TextView title;

            ChapterHolder(View itemView, TextView number, TextView title) {
                super(itemView);
                this.number = number;
                this.title = title;
            }
        }
    }
}
