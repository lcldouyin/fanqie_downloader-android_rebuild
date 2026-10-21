package com.example.fqdownloader.jni;

import android.content.Context;
import android.util.Log;

import com.bytedance.mobsec.metasec.ml.MS;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FqSigner {

    private static final String TAG = "FqSigner";
    private static boolean loaded = false;
    private static String soPath;

    public static void init(Context context) {
        // Load cert file for the SO's JNI callbacks
        loadCert(context);
        try {
            System.loadLibrary("fqsigner");
            loaded = true;
            Log.i(TAG, "libfqsigner loaded");

            // Extract libmetasec_ml.so from APK
            soPath = extractSoFromApk(context, "libmetasec_ml.so");
            if (soPath != null) {
                Log.i(TAG, "libmetasec_ml.so extracted to: " + soPath);
            } else {
                Log.w(TAG, "falling back to nativeLibraryDir");
                soPath = context.getApplicationInfo().nativeLibraryDir + File.separator + "libmetasec_ml.so";
            }
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load libfqsigner", e);
        }
    }

    private static String extractSoFromApk(Context context, String soName) {
        try {
            String apkPath = context.getApplicationInfo().sourceDir;
            String abi = "arm64-v8a";
            String entryName = "lib/" + abi + "/" + soName;

            File outDir = new File(context.getFilesDir(), "lib");
            if (!outDir.isDirectory() && !outDir.mkdirs()) {
                Log.e(TAG, "Failed to create native library cache directory");
                return null;
            }
            File outFile = new File(outDir, soName);
            File tempFile = new File(outDir, soName + ".tmp");
            try (ZipFile zip = new ZipFile(apkPath)) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) {
                    Log.w(TAG, "APK entry not found: " + entryName);
                    return null;
                }
                if (outFile.isFile() && outFile.length() > 0
                        && outFile.length() == entry.getSize()) {
                    return outFile.getAbsolutePath();
                }

                try (InputStream is = zip.getInputStream(entry);
                     FileOutputStream fos = new FileOutputStream(tempFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.getFD().sync();
                }
                if (tempFile.length() == 0
                        || (entry.getSize() >= 0 && tempFile.length() != entry.getSize())) {
                    throw new IOException("Extracted library size does not match APK entry");
                }
                if (outFile.exists() && !outFile.delete()) {
                    throw new IOException("Failed to replace cached native library");
                }
                if (!tempFile.renameTo(outFile)) {
                    throw new IOException("Failed to publish extracted native library");
                }
                outFile.setExecutable(true);
                return outFile.getAbsolutePath();
            } finally {
                if (tempFile.exists() && !tempFile.delete()) {
                    Log.w(TAG, "Failed to remove temporary native library file");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "extractSoFromApk failed", e);
            return null;
        }
    }

    private static void loadCert(Context context) {
        try {
            InputStream is = context.getAssets().open("ms_16777218.bin");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            is.close();
            byte[] certData = baos.toByteArray();
            MS.setCertData(certData);
            Log.i(TAG, "Cert file loaded: " + certData.length + " bytes");
        } catch (Exception e) {
            Log.w(TAG, "Failed to load cert file", e);
        }
    }

    public static String generateSignature(String url, String headers) {
        if (!loaded || soPath == null) {
            Log.e(TAG, "FqSigner not initialized");
            return null;
        }
        try {
            return nativeGenerateSignature(url, headers, soPath);
        } catch (Exception e) {
            Log.e(TAG, "nativeGenerateSignature failed", e);
            return null;
        }
    }

    private static native String nativeGenerateSignature(String url, String headers, String libPath);
}
