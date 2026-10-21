package com.example.arm64trace;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class RunReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        android.util.Log.i("Arm64TraceProbe", "Synthetic signer broadcast received");
        ProbeActivity.runSyntheticSigner(context);
        android.util.Log.i("Arm64TraceProbe", "Synthetic signer broadcast returned");
    }
}
