package au.mason.luisa.tablet;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Bundle;
import androidx.activity.OnBackPressedCallback;
import androidx.core.app.ActivityCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VideosPlugin.class);
        registerPlugin(SpeechPlugin.class); // robot voice for lines nobody has recorded yet
        super.onCreate(savedInstanceState);
        // Serves the SD-card videos and thumbnails to the page
        bridge.setWebViewClient(new VideoWebViewClient(bridge));
        // Back button does nothing, so Luisa can't close the app
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { }
        });
        // One-time "allow access to files" prompt, needed to read the SD card
        if (VideosPlugin.needsPermission(this)) {
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.READ_EXTERNAL_STORAGE }, 1);
        }
        hideSystemBars();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Pin the app (asks for confirmation). Unpin: hold Back + Recents.
        // Wait until the file permission prompt is answered so the two don't clash.
        if (VideosPlugin.needsPermission(this)) return;
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) {
            try { startLockTask(); } catch (Exception ignored) { }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
}
