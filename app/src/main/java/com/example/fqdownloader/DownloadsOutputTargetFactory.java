package com.example.fqdownloader;

import android.content.ContentValues;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class DownloadsOutputTargetFactory implements DownloadEngine.OutputTargetFactory {

    private final Context context;

    public DownloadsOutputTargetFactory(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public DownloadEngine.OutputTarget create(String fileName) throws IOException {
        String mimeType = fileName.endsWith(".txt") ? "text/plain" : "application/epub+zip";
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            var uri = context.getContentResolver().insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("无法在 Download 目录创建文件");

            return new DownloadEngine.OutputTarget() {
                @Override
                public OutputStream openStream() throws IOException {
                    OutputStream stream = context.getContentResolver().openOutputStream(uri, "w");
                    if (stream == null) throw new IOException("无法打开 Download 文件");
                    return stream;
                }

                @Override
                public void publish() throws IOException {
                    ContentValues published = new ContentValues();
                    published.put(MediaStore.Downloads.IS_PENDING, 0);
                    if (context.getContentResolver().update(uri, published, null, null) != 1) {
                        throw new IOException("无法发布 Download 文件");
                    }
                }

                @Override
                public void abort() {
                    context.getContentResolver().delete(uri, null, null);
                }

                @Override
                public String getDisplayPath() {
                    return "Download/" + fileName;
                }
            };
        }

        File downloadDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (!downloadDir.isDirectory() && !downloadDir.mkdirs()) {
            throw new IOException("无法创建 Download 目录: " + downloadDir);
        }
        File destination = new File(downloadDir, fileName);
        return new DownloadEngine.OutputTarget() {
            @Override
            public OutputStream openStream() throws IOException {
                return new FileOutputStream(destination);
            }

            @Override
            public void publish() {}

            @Override
            public void abort() throws IOException {
                if (destination.exists() && !destination.delete()) {
                    throw new IOException("无法删除不完整的下载文件: " + destination);
                }
            }

            @Override
            public String getDisplayPath() {
                return destination.getAbsolutePath();
            }
        };
    }
}
