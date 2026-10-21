package com.example.fqdownloader.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;

import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.example.fqdownloader.R;
import androidx.core.content.ContextCompat;

public final class EdgeToEdgeHelper {

    private EdgeToEdgeHelper() {}

    public static void configure(Activity activity, View contentRoot) {
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }

        int backgroundColor = ContextCompat.getColor(activity, R.color.screen_background);
        boolean lightBackground = ColorUtils.calculateLuminance(backgroundColor) > 0.5;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, contentRoot);
        controller.setAppearanceLightStatusBars(lightBackground);
        controller.setAppearanceLightNavigationBars(lightBackground);

        int initialLeft = contentRoot.getPaddingLeft();
        int initialTop = contentRoot.getPaddingTop();
        int initialRight = contentRoot.getPaddingRight();
        int initialBottom = contentRoot.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (view, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(
                    initialLeft + systemBars.left,
                    initialTop + systemBars.top,
                    initialRight + systemBars.right,
                    initialBottom + Math.max(systemBars.bottom, ime.bottom));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(contentRoot);
    }
}
