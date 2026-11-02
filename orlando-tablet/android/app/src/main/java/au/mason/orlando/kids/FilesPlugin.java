package au.mason.orlando.kids;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Travel School's exports. save() writes into Downloads/Orlando Travel School in ~1 MB base64 pieces
 * (first call creates the file and returns its uri, later calls pass {uri, append: true}).
 * print() opens Android's print screen for an HTML page; "Save as PDF" is one of the printers.
 */
@CapacitorPlugin(name = "Files")
public class FilesPlugin extends Plugin {
    private static final String FOLDER = "Orlando Travel School";
    private WebView printView; // kept alive until printing has read it

    @PluginMethod
    public void save(PluginCall call) {
        try {
            byte[] bytes = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            ContentResolver cr = getContext().getContentResolver();
            String uriStr = call.getString("uri");
            Uri uri;
            String path;
            if (uriStr != null && call.getBoolean("append", false)) {
                uri = Uri.parse(uriStr);
                path = call.getString("uri");
            } else if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, call.getString("name", "export.html"));
                v.put(MediaStore.Downloads.MIME_TYPE, call.getString("mime", "text/html"));
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new Exception("Could not create the file");
                path = "Downloads/" + FOLDER + "/" + call.getString("name", "");
            } else {
                File dir = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER);
                dir.mkdirs();
                File f = new File(dir, call.getString("name", "export.html"));
                uri = Uri.fromFile(f);
                path = f.getAbsolutePath();
            }
            boolean append = call.getBoolean("append", false);
            try (OutputStream out = "file".equals(uri.getScheme())
                    ? new FileOutputStream(new File(uri.getPath()), append)
                    : cr.openOutputStream(uri, append ? "wa" : "w")) {
                out.write(bytes);
            }
            JSObject r = new JSObject();
            r.put("uri", uri.toString());
            r.put("path", path);
            call.resolve(r);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void print(PluginCall call) {
        String html = call.getString("html", "");
        String name = call.getString("name", "Travel School");
        Activity activity = getActivity();
        activity.runOnUiThread(() -> {
            // The print screen is another app's window: pinning would block it (MainActivity re-pins on resume)
            try { activity.stopLockTask(); } catch (Exception ignored) { }
            printView = new WebView(activity);
            printView.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) {
                    PrintManager pm = (PrintManager) activity.getSystemService(Context.PRINT_SERVICE);
                    PrintAttributes attrs = new PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build();
                    pm.print(name, view.createPrintDocumentAdapter(name), attrs);
                    call.resolve();
                }
            });
            printView.loadDataWithBaseURL("https://localhost/", html, "text/html", "utf-8", null);
        });
    }
}
