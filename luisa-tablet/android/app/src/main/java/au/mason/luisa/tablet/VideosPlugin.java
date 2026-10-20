package au.mason.luisa.tablet;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.view.WindowManager;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds videos in a "Luisa Videos" folder at the top of the SD card (or internal storage).
 * The page plays them through /_video_/<n>/<name> and shows /_thumb_/<n>.jpg, served by VideoWebViewClient.
 */
@CapacitorPlugin(name = "Videos")
public class VideosPlugin extends Plugin {

    static final String FOLDER = "Luisa Videos";
    static final List<String> EXTS = Arrays.asList("mp4", "m4v", "webm", "mkv", "3gp");
    static final String[] THUMB_EXTS = { "jpg", "jpeg", "png", "webp" };

    // The only files the WebView is allowed to load, indexed by /_video_/<n>
    static volatile List<File> videos = new ArrayList<>();

    static boolean needsPermission(android.content.Context ctx) {
        return Build.VERSION.SDK_INT < 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void list(PluginCall call) {
        JSObject ret = new JSObject();
        JSArray folders = new JSArray();
        List<File> found = new ArrayList<>();
        for (File dir : folders()) {
            folders.put(dir.getAbsolutePath());
            File[] files = dir.listFiles();
            if (files == null) continue;
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) if (f.isFile() && !f.getName().startsWith(".") && EXTS.contains(ext(f.getName()))) found.add(f);
        }
        videos = found;

        JSArray list = new JSArray();
        for (int i = 0; i < found.size(); i++) {
            String name = found.get(i).getName();
            JSObject v = new JSObject();
            v.put("title", base(name));
            v.put("src", "/_video_/" + i + "/" + encode(name));
            v.put("thumb", "/_thumb_/" + i + ".jpg?v=" + found.get(i).lastModified());
            list.put(v);
        }
        ret.put("videos", list);
        ret.put("folders", folders);
        ret.put("needPermission", needsPermission(getContext()));
        call.resolve(ret);
    }

    // Keep the screen on while a video plays
    @PluginMethod
    public void keepAwake(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        getActivity().runOnUiThread(() -> {
            if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        call.resolve();
    }

    // "Luisa Videos" at the top of the SD card and internal storage (e.g. /storage/1A2B-3C4D/Luisa Videos),
    // then the app's own folders (Android/data/au.mason.luisa.tablet/files/Luisa Videos), which newer
    // Android versions can read without the storage permission but which are wiped if the app is uninstalled.
    private Set<File> folders() {
        Set<File> roots = new LinkedHashSet<>(), own = new LinkedHashSet<>();
        for (File d : getContext().getExternalFilesDirs(null)) {
            if (d == null) continue;
            String p = d.getAbsolutePath();
            int i = p.indexOf("/Android/");
            if (i > 0) roots.add(canonical(new File(p.substring(0, i), FOLDER)));
            own.add(canonical(new File(d, FOLDER)));
        }
        roots.add(canonical(new File(Environment.getExternalStorageDirectory(), FOLDER)));
        roots.addAll(own);
        return roots;
    }

    static File canonical(File f) {
        try { return f.getCanonicalFile(); } catch (IOException e) { return f; }
    }

    static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }

    static String base(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(0, i);
    }

    private static String encode(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return "video"; }
    }
}
