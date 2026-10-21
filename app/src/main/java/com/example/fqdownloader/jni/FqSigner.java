package com.example.fqdownloader.jni;

import android.content.Context;
import android.util.Log;

import com.bytedance.mobsec.metasec.ml.MS;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class FqSigner {

    private static final String TAG = "FqSigner";
    private static final String SO_NAME = "libmetasec_ml.so";
    private static final String SO_SHA256 =
            "ebfdfa5539e18fa18cc675293a1dc623126e259b96e0a30b1fca9d52ad674a78";

    private static volatile FutureTask<String> initialization;
    private static volatile String lastError;

    private FqSigner() {}

    public static void init(Context context) {
        synchronized (FqSigner.class) {
            if (initialization != null) return;
            Context appContext = context.getApplicationContext();
            FutureTask<String> task = new FutureTask<>(() -> initialize(appContext));
            initialization = task;
            Thread initThread = new Thread(task, "fq-signer-init");
            initThread.setDaemon(true);
            initThread.start();
        }
    }

    private static String initialize(Context context) throws IOException {
        loadCert(context);
        System.loadLibrary("fqsigner");
        Log.i(TAG, "libfqsigner loaded");

        String path = extractSoFromApk(context);
        Log.i(TAG, SO_NAME + " verified and ready at " + path);
        return path;
    }

    private static String extractSoFromApk(Context context) throws IOException {
        String apkPath = context.getApplicationInfo().sourceDir;
        String entryName = "lib/arm64-v8a/" + SO_NAME;
        File outDir = new File(context.getFilesDir(), "lib");
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("Failed to create native library cache directory");
        }

        File outFile = new File(outDir, "libmetasec_ml_" + SO_SHA256 + ".so");
        File tempFile = new File(outDir, outFile.getName() + ".tmp");
        try (ZipFile zip = new ZipFile(apkPath)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                File installedLibrary = new File(
                        context.getApplicationInfo().nativeLibraryDir, SO_NAME);
                if (hasExpectedHash(installedLibrary)) {
                    return installedLibrary.getAbsolutePath();
                }
                throw new IOException("APK entry not found and installed library is invalid: "
                        + entryName);
            }
            try (InputStream is = zip.getInputStream(entry)) {
                String apkLibraryHash = sha256(is);
                if (!SO_SHA256.equals(apkLibraryHash)) {
                    throw new IOException("Unsupported " + SO_NAME + " version: "
                            + apkLibraryHash);
                }
            }

            if (outFile.isFile()) {
                try (InputStream is = new FileInputStream(outFile)) {
                    if (SO_SHA256.equals(sha256(is))) return outFile.getAbsolutePath();
                }
                if (!outFile.delete()) {
                    throw new IOException("Failed to remove invalid cached native library");
                }
            }

            try (InputStream is = zip.getInputStream(entry);
                 FileOutputStream fos = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = is.read(buffer)) != -1) fos.write(buffer, 0, count);
                fos.getFD().sync();
            } catch (IOException error) {
                if (tempFile.exists() && !tempFile.delete()) {
                    error.addSuppressed(new IOException("Failed to remove partial native library"));
                }
                throw error;
            }

            try (InputStream is = new FileInputStream(tempFile)) {
                if (!SO_SHA256.equals(sha256(is))) {
                    throw new IOException("Extracted library SHA-256 does not match APK entry");
                }
            }
            if (!tempFile.setExecutable(true, true)) {
                throw new IOException("Failed to mark extracted native library executable");
            }
            if (!tempFile.renameTo(outFile)) {
                throw new IOException("Failed to publish verified native library");
            }
            return outFile.getAbsolutePath();
        } finally {
            if (tempFile.exists() && !tempFile.delete()) {
                Log.w(TAG, "Failed to remove temporary native library file");
            }
        }
    }

    private static String sha256(InputStream input) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);

        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(hex[(value >> 4) & 0xf]);
            result.append(hex[value & 0xf]);
        }
        return result.toString();
    }

    private static boolean hasExpectedHash(File file) throws IOException {
        if (!file.isFile()) return false;
        try (InputStream is = new FileInputStream(file)) {
            return SO_SHA256.equals(sha256(is));
        }
    }

    private static void loadCert(Context context) throws IOException {
        byte[] certData;
        try (InputStream is = context.getAssets().open("ms_16777218.bin");
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = is.read(buffer)) != -1) baos.write(buffer, 0, count);
            certData = baos.toByteArray();
        }
        if (certData.length == 0) throw new IOException("Certificate file is empty");
        MS.setCertData(certData);
        Log.i(TAG, "Cert file loaded: " + certData.length + " bytes");
    }

    public static String generateSignature(String url, String headers) {
        FutureTask<String> task = initialization;
        if (task == null) {
            lastError = "FqSigner initialization was not requested";
            Log.e(TAG, lastError);
            return null;
        }
        try {
            String soPath = task.get();
            String signature = nativeGenerateSignature(url, headers, soPath);
            lastError = signature == null ? "Native signature function returned null" : null;
            return signature;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            lastError = "Interrupted while waiting for FqSigner initialization";
            Log.e(TAG, "Interrupted while waiting for FqSigner initialization", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            lastError = "FqSigner initialization failed: "
                    + (cause == null ? error.getMessage() : cause.getMessage());
            Log.e(TAG, "FqSigner initialization failed", error.getCause());
        } catch (RuntimeException | LinkageError error) {
            lastError = error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage();
            Log.e(TAG, "nativeGenerateSignature failed", error);
        }
        return null;
    }

    public static String getLastError() {
        return lastError;
    }

    private static native String nativeGenerateSignature(String url, String headers, String libPath);
}
