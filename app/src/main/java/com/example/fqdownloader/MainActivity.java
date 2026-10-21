package com.example.fqdownloader;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.fqdownloader.jni.FqSigner;
import com.example.fqdownloader.model.BookInfo;
import com.example.fqdownloader.ui.EdgeToEdgeHelper;
import com.google.android.material.card.MaterialCardView;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private DownloadEngine engine;
    private EditText etInput;
    private Button btnSearch;
    private View searchProgress;
    private LinearLayout searchResults;
    private TextView tvSearchResultsStatus;
    private TextView tvRecentReading;
    private boolean searchInProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        EdgeToEdgeHelper.configure(this, findViewById(R.id.main_content_root));
        setupMainUI();
        initEngine();
    }

    @Override
    protected void onResume() {
        super.onResume();
        showRecentReading();
    }

    private void setupMainUI() {
        etInput = findViewById(R.id.et_input);
        btnSearch = findViewById(R.id.btn_search);
        searchProgress = findViewById(R.id.search_progress);
        searchResults = findViewById(R.id.search_results);
        tvSearchResultsStatus = findViewById(R.id.tv_search_results_status);
        tvRecentReading = findViewById(R.id.tv_recent_reading);

        btnSearch.setOnClickListener(v -> searchBooks());
        tvRecentReading.setOnClickListener(v -> openRecentReading());
        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchBooks();
                return true;
            }
            return false;
        });
    }

    private void showRecentReading() {
        if (tvRecentReading == null) return;
        var preferences = getSharedPreferences(
                ChapterPreviewActivity.RECENT_READING_PREFERENCES, MODE_PRIVATE);
        String bookTitle = preferences.getString(ChapterPreviewActivity.RECENT_BOOK_TITLE, null);
        String chapterId = preferences.getString(ChapterPreviewActivity.RECENT_CHAPTER_ID, null);
        int chapterOrder = preferences.getInt(ChapterPreviewActivity.RECENT_CHAPTER_ORDER, 0);
        if (bookTitle == null || bookTitle.trim().isEmpty()
                || chapterId == null || chapterId.trim().isEmpty() || chapterOrder <= 0) {
            tvRecentReading.setVisibility(View.GONE);
            return;
        }
        tvRecentReading.setText(getString(R.string.recent_reading, bookTitle, chapterOrder));
        tvRecentReading.setContentDescription(
                getString(R.string.recent_reading_description, bookTitle, chapterOrder));
        tvRecentReading.setVisibility(View.VISIBLE);
    }

    private void openRecentReading() {
        var preferences = getSharedPreferences(
                ChapterPreviewActivity.RECENT_READING_PREFERENCES, MODE_PRIVATE);
        String bookId = preferences.getString(ChapterPreviewActivity.RECENT_BOOK_ID, null);
        String bookTitle = preferences.getString(ChapterPreviewActivity.RECENT_BOOK_TITLE, null);
        String chapterId = preferences.getString(ChapterPreviewActivity.RECENT_CHAPTER_ID, null);
        String chapterTitle = preferences.getString(
                ChapterPreviewActivity.RECENT_CHAPTER_TITLE, null);
        int chapterOrder = preferences.getInt(ChapterPreviewActivity.RECENT_CHAPTER_ORDER, 0);
        int totalChapters = preferences.getInt(
                ChapterPreviewActivity.RECENT_TOTAL_CHAPTERS, 0);
        if (bookId == null || bookTitle == null || chapterId == null || chapterTitle == null
                || chapterOrder <= 0 || totalChapters < chapterOrder) {
            showRecentReading();
            return;
        }
        Intent intent = new Intent(this, ChapterListActivity.class);
        intent.putExtra(ChapterListActivity.EXTRA_BOOK_ID, bookId);
        intent.putExtra(ChapterListActivity.EXTRA_BOOK_TITLE, bookTitle);
        intent.putExtra(ChapterListActivity.EXTRA_SCROLL_TO_CHAPTER_ID, chapterId);
        intent.putExtra(ChapterListActivity.EXTRA_SCROLL_TO_CHAPTER_ORDER, chapterOrder);
        startActivity(intent);
    }

    private void searchBooks() {
        if (searchInProgress) return;
        String keyword = etInput.getText().toString().trim();
        if (keyword.isEmpty()) {
            etInput.setError(getString(R.string.book_input_required));
            return;
        }

        etInput.setError(null);
        searchInProgress = true;
        btnSearch.setEnabled(false);
        searchProgress.setVisibility(View.VISIBLE);
        tvSearchResultsStatus.setVisibility(View.VISIBLE);
        searchResults.removeAllViews();
        tvSearchResultsStatus.setText(R.string.searching);
        engine.search(keyword);
    }

    private void initEngine() {
        FqSigner.init(this);

        engine = new DownloadEngine(null, new DownloadEngine.ProgressListener() {
            @Override
            public void onLog(String message) {}

            @Override
            public void onProgress(int current, int total, String batchInfo) {}

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (!searchInProgress) return;
                    finishSearch();
                    tvSearchResultsStatus.setText(getString(R.string.search_error_detail, error));
                });
            }

            @Override
            public void onComplete(String outputPath) {}

            @Override
            public void onSearchResults(List<BookInfo> results) {
                runOnUiThread(() -> showSearchResults(results));
            }
        });
    }

    private void showSearchResults(List<BookInfo> results) {
        finishSearch();
        searchResults.removeAllViews();
        tvSearchResultsStatus.setVisibility(View.VISIBLE);
        if (results.isEmpty()) {
            tvSearchResultsStatus.setText(R.string.search_no_results);
            return;
        }

        int validResults = 0;
        for (BookInfo book : results) {
            if (book.bookId == null || book.bookId.trim().isEmpty()) continue;
            searchResults.addView(createSearchResultCard(book));
            validResults++;
        }
        tvSearchResultsStatus.setText(getString(R.string.search_results_count, validResults));
    }

    private MaterialCardView createSearchResultCard(BookInfo book) {
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
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = getResources().getDimensionPixelSize(R.dimen.search_result_padding);
        content.setPadding(padding, padding, padding, padding);

        String titleText = book.title == null || book.title.isEmpty() ? book.bookId : book.title;
        TextView title = createResultText(titleText, 16, true);
        title.setTextColor(ContextCompat.getColor(this, R.color.tomato));
        content.addView(title);

        String author = book.author == null || book.author.isEmpty()
                ? getString(R.string.book_author_unknown) : book.author;
        String category = book.category == null || book.category.isEmpty()
                ? getString(R.string.book_category_unknown) : book.category;
        String score = book.score == null || book.score.isEmpty() ? "N/A" : book.score;
        TextView metadata = createResultText(
                getString(R.string.search_result_meta, author, category, score), 13, false);
        metadata.setPadding(0, padding / 3, 0, 0);
        content.addView(metadata);

        TextView id = createResultText(getString(R.string.search_result_id, book.bookId), 12, false);
        id.setPadding(0, padding / 3, 0, 0);
        content.addView(id);

        if (book.description != null && !book.description.trim().isEmpty()) {
            TextView description = createResultText(book.description.trim(), 13, false);
            description.setMaxLines(2);
            description.setEllipsize(android.text.TextUtils.TruncateAt.END);
            description.setPadding(0, padding / 3, 0, 0);
            content.addView(description);
        }

        card.addView(content);
        card.setContentDescription(getString(R.string.search_result_select_description, titleText));
        card.setOnClickListener(v -> {
            Intent intent = new Intent(this, ChapterListActivity.class);
            intent.putExtra(ChapterListActivity.EXTRA_BOOK_ID, book.bookId);
            intent.putExtra(ChapterListActivity.EXTRA_BOOK_TITLE, titleText);
            startActivity(intent);
        });
        return card;
    }

    private TextView createResultText(String text, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text == null ? "" : text);
        view.setTextColor(ContextCompat.getColor(this, bold
                ? R.color.result_title : R.color.result_metadata));
        view.setTextSize(sizeSp);
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        return view;
    }

    private void finishSearch() {
        searchInProgress = false;
        btnSearch.setEnabled(true);
        searchProgress.setVisibility(View.GONE);
    }
}
