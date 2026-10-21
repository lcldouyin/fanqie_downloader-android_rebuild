package com.example.fqdownloader;

import android.app.Application;

import com.example.fqdownloader.jni.FqSigner;

public final class FqDownloaderApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        FqSigner.init(this);
    }
}
