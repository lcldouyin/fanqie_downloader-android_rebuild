package com.example.fqdownloader;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.fqdownloader.jni.FqSigner;
import com.example.fqdownloader.security.Activation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_STORAGE = 100;
    private static final String PREFS_NAME = "activation_prefs";
    private static final String KEY_BANNER_DISMISSED = "banner_dismissed";
    private static final String TAG = "MainActivity";

    private TextView tvLog;
    private TextView tvProgress;
    private com.google.android.material.progressindicator.LinearProgressIndicator progressBar;
    private DownloadEngine engine;
    private File outputDir;
    private EditText etInput, etStart, etEnd;
    private boolean pendingDownloadAsTxt;
    private boolean isWarningShown = false;
    private View refreshContainer;
    private TextView tvRefreshStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String tempType = Activation.getTempWarningType(this);
        if (tempType != null) {
            showFullScreenWarning(tempType);
            return;
        }

        Activation.AuthorizedUser user = Activation.getAuthorizedUser(this);

        if (user == null) {
            startActivity(new Intent(this, com.example.fqdownloader.security.ActivationActivity.class));
            finish();
            return;
        }

        if (user.isExpired()) {
            Activation.saveTempUserInfo(this, user, "expired");
            new File(getFilesDir(), "activation.dat").delete();
            showFullScreenWarning("expired");
            return;
        }

        if (user.warnLevel == 4) {
            showFullScreenWarning("level4");
            return;
        }

        setContentView(R.layout.activity_main);

        setTitle(getString(R.string.app_name) + " - 授权给 " + user.user);

        setupWarningBanner(user);
        setupMainUI();

        refreshContainer = findViewById(R.id.refresh_container);
        tvRefreshStatus = findViewById(R.id.tv_refresh_status);

        startRefresh(user);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isWarningShown) {
            String tempType = Activation.getTempWarningType(this);
            if (tempType != null) {
                showFullScreenWarning(tempType);
            }
        }
    }

    private void startRefresh(Activation.AuthorizedUser user) {
        refreshContainer.setVisibility(View.VISIBLE);
        tvRefreshStatus.setText("正在获取数据...");

        new Thread(() -> {
            Activation.AuthorizedUser updated = Activation.refreshData(this, msg -> {
                runOnUiThread(() -> {
                    String display = msg;
                    if (!display.startsWith("正在请求 ") && !display.startsWith("更新数据")) {
                        display = "正在请求 " + display;
                    }
                    tvRefreshStatus.setText("更新数据：" + display);
                    Log.i(TAG, "更新数据：" + display);
                });
            });

            runOnUiThread(() -> {
                refreshContainer.setVisibility(View.GONE);

                if (Activation.getTempWarningType(this) != null) {
                    String type = Activation.getTempWarningType(this);
                    showFullScreenWarning(type);
                    return;
                }

                Activation.AuthorizedUser fresh = Activation.getAuthorizedUser(this);
                if (fresh != null) {
                    Log.i(TAG, "刷新后: warnLevel=" + fresh.warnLevel + ", warnText=" + fresh.warnText);

                    if (fresh.warnLevel == 4 || fresh.isExpired()) {
                        showFullScreenWarning(fresh.warnLevel == 4 ? "level4" : "expired");
                        return;
                    }

                    setTitle(getString(R.string.app_name) + " - 授权给 " + fresh.user);
                    if (fresh.warnLevel > 0) {
                        Log.i(TAG, "调用 setupWarningBanner, warnLevel=" + fresh.warnLevel);
                        setupWarningBanner(fresh);
                    } else {
                        Log.i(TAG, "warnLevel=0, 跳过横幅");
                    }
                } else {
                    Log.w(TAG, "刷新后 getAuthorizedUser 返回 null");
                }

                initEngine();

                Activation.checkAndRevoke(this, () -> runOnUiThread(this::onRevoked));
            });
        }).start();
    }

    private void onRevoked() {
        if (isFinishing()) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String user = prefs.getString(Activation.KEY_TMP_USER, "");
        String id = prefs.getString(Activation.KEY_TMP_ID, "");

        new AlertDialog.Builder(this)
                .setTitle("授权已被撤销")
                .setMessage("用户名: " + user + "\nID: " + id + "\n\n请联系开发者获取新的激活码")
                .setCancelable(false)
                .setPositiveButton("重新激活", (d, w) -> {
                    Activation.clearTempUserInfo(this);
                    startActivity(new Intent(this, com.example.fqdownloader.security.ActivationActivity.class));
                    finish();
                })
                .show();
    }

    private void showFullScreenWarning(String type) {
        isWarningShown = true;
        setContentView(R.layout.layout_warning_red);

        TextView tvTitle = findViewById(R.id.tv_warning_title);
        TextView tvMessage = findViewById(R.id.tv_warning_message);
        TextView tvUser = findViewById(R.id.tv_warning_user);
        TextView tvId = findViewById(R.id.tv_warning_id);
        TextView tvExpiry = findViewById(R.id.tv_warning_expiry);
        TextView tvExpiryLabel = findViewById(R.id.tv_warning_expiry_label);
        TextView tvUserLabel = findViewById(R.id.tv_warning_user_label);
        TextView tvIdLabel = findViewById(R.id.tv_warning_id_label);
        Button btnReactivate = findViewById(R.id.btn_warning_reactivate);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String tmpUser = prefs.getString(Activation.KEY_TMP_USER, "");
        String tmpId = prefs.getString(Activation.KEY_TMP_ID, "");
        String tmpExpiry = prefs.getString(Activation.KEY_TMP_EXPIRY, "");

        tvUserLabel.setVisibility(View.GONE);
        tvUser.setVisibility(View.GONE);
        tvIdLabel.setVisibility(View.GONE);
        tvId.setVisibility(View.GONE);
        tvExpiryLabel.setVisibility(View.GONE);
        tvExpiry.setVisibility(View.GONE);
        btnReactivate.setVisibility(View.GONE);

        switch (type) {
            case "expired":
                tvTitle.setText(R.string.auth_expired_title);
                tvMessage.setText("授权已于 " + tmpExpiry + " 过期");
                tvUserLabel.setVisibility(View.VISIBLE);
                tvUser.setVisibility(View.VISIBLE);
                tvIdLabel.setVisibility(View.VISIBLE);
                tvId.setVisibility(View.VISIBLE);
                tvExpiryLabel.setVisibility(View.VISIBLE);
                tvExpiry.setVisibility(View.VISIBLE);
                tvUser.setText(tmpUser);
                tvId.setText(tmpId);
                tvExpiry.setText(tmpExpiry);
                btnReactivate.setVisibility(View.VISIBLE);
                break;
            case "revoked":
                tvTitle.setText(R.string.auth_revoked_title);
                tvMessage.setText("请联系开发者获取新的激活码");
                tvUserLabel.setVisibility(View.VISIBLE);
                tvUser.setVisibility(View.VISIBLE);
                tvIdLabel.setVisibility(View.VISIBLE);
                tvId.setVisibility(View.VISIBLE);
                tvUser.setText(tmpUser);
                tvId.setText(tmpId);
                btnReactivate.setVisibility(View.VISIBLE);
                break;
            case "level4": {
                Activation.AuthorizedUser current = Activation.getAuthorizedUser(this);
                String warnText = (current != null && current.warnText != null && !current.warnText.isEmpty())
                        ? current.warnText : getString(R.string.warn_level_4_default);
                tvTitle.setText(warnText);
                tvMessage.setText("如有疑问请联系开发者");
                btnReactivate.setVisibility(View.VISIBLE);
                btnReactivate.setText("重新激活");
                break;
            }
        }

        btnReactivate.setOnClickListener(v -> {
            Activation.clearTempUserInfo(this);
            startActivity(new Intent(this, com.example.fqdownloader.security.ActivationActivity.class));
            finish();
        });
    }

    private void setupWarningBanner(Activation.AuthorizedUser user) {
        FrameLayout container = findViewById(R.id.warning_container);
        if (container == null) {
            Log.w(TAG, "warning_container 为 null");
            return;
        }

        container.removeAllViews();

        if (user.warnLevel == 0) {
            Log.i(TAG, "setupWarningBanner: warnLevel=0, 跳过");
            return;
        }

        // warnLevel 1 不检查关闭状态，每次启动都显示
        // 关闭按钮仅隐藏当前会话的横幅
        Log.i(TAG, "setupWarningBanner: 显示横幅, warnLevel=" + user.warnLevel + ", text=" + user.warnText);

        String text = user.warnText;
        if (text == null || text.isEmpty()) {
            int resId = getResources().getIdentifier(
                    "warn_level_" + user.warnLevel + "_default", "string", getPackageName());
            if (resId != 0) text = getString(resId);
        }
        if (text == null) text = "";

        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setPadding(16, 12, 16, 12);
        banner.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tv = new TextView(this);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tvLp.gravity = Gravity.CENTER_VERTICAL;
        tv.setLayoutParams(tvLp);
        tv.setTextSize(13);
        tv.setText(text);

        switch (user.warnLevel) {
            case 1:
                banner.setBackgroundColor(0xFFFFD600);
                tv.setTextColor(0xFF000000);
                Button dismissBtn = new Button(this);
                dismissBtn.setText("×");
                dismissBtn.setTextSize(18);
                dismissBtn.setTextColor(0xFF000000);
                dismissBtn.setBackground(null);
                dismissBtn.setPadding(8, 0, 8, 0);
                dismissBtn.setOnClickListener(v -> {
                    container.setVisibility(View.GONE);
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit().putBoolean(KEY_BANNER_DISMISSED, true).apply();
                });
                banner.addView(tv);
                banner.addView(dismissBtn);
                break;
            case 2:
                banner.setBackgroundColor(0xFFFFD600);
                tv.setTextColor(0xFFD50000);
                banner.addView(tv);
                break;
            case 3:
                banner.setBackgroundColor(0xFFD50000);
                tv.setTextColor(0xFFFFFFFF);
                banner.addView(tv);
                break;
        }

        container.addView(banner);
        container.setVisibility(View.VISIBLE);
    }

    private void setupMainUI() {
        tvLog = findViewById(R.id.tv_log);
        tvLog.setTextIsSelectable(true);
        tvProgress = findViewById(R.id.tv_progress);
        progressBar = findViewById(R.id.progress_bar);

        etInput = findViewById(R.id.et_input);
        etStart = findViewById(R.id.et_start);
        etEnd = findViewById(R.id.et_end);
        var btnSearch = (Button) findViewById(R.id.btn_search);
        var btnDownload = (Button) findViewById(R.id.btn_download);
        var btnDownloadTxt = (Button) findViewById(R.id.btn_download_txt);
        var btnClearCache = (Button) findViewById(R.id.btn_clear_cache);

        btnSearch.setOnClickListener(v -> {
            String keyword = etInput.getText().toString().trim();
            if (keyword.isEmpty()) {
                log("请输入搜索关键词");
                return;
            }
            log("搜索: " + keyword);
            engine.search(keyword);
        });

        btnDownload.setOnClickListener(v -> startDownload(false));
        btnDownloadTxt.setOnClickListener(v -> startDownload(true));

        btnClearCache.setOnClickListener(v -> clearCache());
    }

    private void initEngine() {
        log("初始化签名引擎...");
        FqSigner.init(this);
        log("签名引擎就绪");

        outputDir = new File(getExternalFilesDir(null), "output");
        engine = new DownloadEngine(outputDir, new DownloadEngine.ProgressListener() {
            @Override
            public void onLog(String message) {
                runOnUiThread(() -> log(message));
            }

            @Override
            public void onProgress(int current, int total, String batchInfo) {
                runOnUiThread(() -> {
                    progressBar.setProgressCompat(current, true);
                    progressBar.setMax(total);
                    tvProgress.setText(batchInfo);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> log("错误: " + error));
            }

            @Override
            public void onComplete(String outputPath) {
                runOnUiThread(() -> {
                    log("完成! 已保存: " + outputPath);
                    copyToDownloads(outputPath);
                });
            }
        });
    }

    private void startDownload(boolean asTxt) {
        String bookId = etInput.getText().toString().trim();
        if (bookId.isEmpty()) {
            log("请输入 Book ID");
            return;
        }

        if (Build.VERSION.SDK_INT < 29) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingDownloadAsTxt = asTxt;
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
                return;
            }
        }

        doDownload(bookId, asTxt);
    }

    private void doDownload(String bookId, boolean asTxt) {
        int start = 1, end = 0;
        try {
            if (!etStart.getText().toString().trim().isEmpty())
                start = Integer.parseInt(etStart.getText().toString().trim());
            if (!etEnd.getText().toString().trim().isEmpty())
                end = Integer.parseInt(etEnd.getText().toString().trim());
        } catch (NumberFormatException e) {
            log("章节号格式错误");
            return;
        }

        log("开始下载: " + bookId + " (章节 " + start + "-" + (end > 0 ? end : "全部") + ")");
        engine.download(bookId, start, end, asTxt);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            String bookId = etInput.getText().toString().trim();
            if (!bookId.isEmpty()) doDownload(bookId, pendingDownloadAsTxt);
        } else {
            log("存储权限被拒绝，无法复制到 Download 目录");
        }
    }

    private void copyToDownloads(String filePath) {
        try {
            File src = new File(filePath);
            if (!src.exists()) return;
            String fileName = src.getName();

            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE,
                        fileName.endsWith(".txt") ? "text/plain" : "application/epub+zip");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                var uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri);
                         FileInputStream fis = new FileInputStream(src)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = fis.read(buf)) != -1) os.write(buf, 0, n);
                    }
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);
                    log("已复制到: Download/" + fileName);
                }
            } else {
                File dstDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File dst = new File(dstDir, fileName);
                try (FileInputStream fis = new FileInputStream(src);
                     FileOutputStream fos = new FileOutputStream(dst)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
                }
                log("已复制到: Download/" + fileName);
            }
        } catch (Exception e) {
            log("复制到 Download 失败: " + e);
        }
    }

    private void clearCache() {
        new Thread(() -> {
            try {
                deleteRecursive(outputDir);
                deleteRecursive(new File(getFilesDir(), "lib"));
                runOnUiThread(() -> log("缓存已清除"));
            } catch (Exception e) {
                runOnUiThread(() -> log("清除缓存失败: " + e));
            }
        }).start();
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    private void log(String msg) {
        if (isWarningShown) return;
        if (tvLog == null) return;
        Log.d(TAG, msg);
        tvLog.append(msg + "\n");
        var layout = tvLog.getLayout();
        if (layout != null) {
            int scrollAmount = layout.getLineTop(tvLog.getLineCount()) - tvLog.getHeight();
            if (scrollAmount > 0) tvLog.scrollTo(0, scrollAmount);
        }
    }
}
