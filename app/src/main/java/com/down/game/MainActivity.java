package com.down.game;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

public class MainActivity extends Activity {

    private GameView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new GameView(this);
        setContentView(view);
        applyImmersive();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override
    public void onBackPressed() {
        if (view != null && view.onBack()) return;
        super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        view.start();
    }

    @Override protected void onPause() {
        super.onPause();
        view.stop();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (view != null) view.destroy();
    }

    private void applyImmersive() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController ic = getWindow().getInsetsController();
                if (ic != null) {
                    ic.hide(WindowInsets.Type.systemBars());
                    ic.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                      View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Exception ignored) {}
    }
}
