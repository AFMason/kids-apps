package au.mason.luisa.tablet;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebViewClient;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves the SD-card videos to the page with proper byte-range support (so the
 * player can stream and seek big files), plus a thumbnail for each video.
 */
public class VideoWebViewClient extends BridgeWebViewClient {

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    private static final Object THUMB_LOCK = new Object(); // one thumbnail at a time on the old tablet
    private final File thumbCache;

    public VideoWebViewClient(Bridge bridge) {
        super(bridge);
        thumbCache = new File(bridge.getContext().getCacheDir(), "thumbs");
        thumbCache.mkdirs();
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        String path = request.getUrl().getPath();
        if (path != null && path.startsWith("/_video_/")) return serveVideo(request, fileAt(path, "/_video_/"));
        if (path != null && path.startsWith("/_thumb_/")) return serveThumb(fileAt(path, "/_thumb_/"));
        return super.shouldInterceptRequest(view, request);
    }

    private static File fileAt(String path, String prefix) {
        String rest = path.substring(prefix.length());
        int end = 0;
        while (end < rest.length() && Character.isDigit(rest.charAt(end))) end++;
        if (end == 0) return null;
        List<File> list = VideosPlugin.videos;
        int i = Integer.parseInt(rest.substring(0, end));
        return i < list.size() ? list.get(i) : null;
    }

    private WebResourceResponse serveVideo(WebResourceRequest request, File f) {
        if (f == null || !f.canRead()) return notFound();
        long len = f.length();
        long start = 0, end = len - 1;
        boolean partial = false;
        String range = request.getRequestHeaders().get("Range");
        if (range == null) range = request.getRequestHeaders().get("range");
        if (range != null) {
            Matcher m = RANGE.matcher(range);
            if (m.find()) {
                partial = true;
                if (!m.group(1).isEmpty()) {
                    start = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) end = Math.min(Long.parseLong(m.group(2)), len - 1);
                } else if (!m.group(2).isEmpty()) {
                    start = Math.max(0, len - Long.parseLong(m.group(2))); // "bytes=-500" = last 500 bytes
                }
            }
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Cache-Control", "no-store");
        if (start >= len || start > end) {
            headers.put("Content-Range", "bytes */" + len);
            return new WebResourceResponse(mime(f), null, 416, "Range Not Satisfiable", headers, null);
        }
        try {
            InputStream in = new FileInputStream(f);
            long skip = start;
            while (skip > 0) {
                long n = in.skip(skip);
                if (n <= 0) break;
                skip -= n;
            }
            long count = end - start + 1;
            headers.put("Content-Length", String.valueOf(count));
            if (partial) headers.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
            return new WebResourceResponse(mime(f), null, partial ? 206 : 200, partial ? "Partial Content" : "OK", headers, new Limited(in, count));
        } catch (IOException e) {
            return notFound();
        }
    }

    // A picture next to the video with the same name wins (e.g. "Peppa.mp4" + "Peppa.jpg"),
    // otherwise grab a frame from the video and cache it.
    private WebResourceResponse serveThumb(File video) {
        if (video == null) return notFound();
        String base = VideosPlugin.base(video.getName());
        for (String e : VideosPlugin.THUMB_EXTS) {
            File pic = new File(video.getParentFile(), base + "." + e);
            if (pic.canRead()) return file(pic, e.equals("jpg") ? "image/jpeg" : "image/" + e);
        }
        File cached = new File(thumbCache, Integer.toHexString((video.getAbsolutePath() + video.length() + video.lastModified()).hashCode()) + ".jpg");
        synchronized (THUMB_LOCK) {
            if (!cached.exists() && !makeThumb(video, cached)) return notFound();
        }
        return file(cached, "image/jpeg");
    }

    private static boolean makeThumb(File video, File out) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(video.getAbsolutePath());
            long durUs = 0;
            try { durUs = Long.parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) * 1000; } catch (Exception ignored) { }
            // A few seconds in, to skip black intro frames
            Bitmap b = r.getFrameAtTime(Math.min(8_000_000L, durUs / 4), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) return false;
            int w = 480, h = Math.max(1, b.getHeight() * w / Math.max(1, b.getWidth()));
            Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
            try (FileOutputStream os = new FileOutputStream(out)) {
                small.compress(Bitmap.CompressFormat.JPEG, 80, os);
            }
            return true;
        } catch (Exception e) {
            out.delete();
            return false;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    private static WebResourceResponse file(File f, String mime) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "max-age=86400");
            return new WebResourceResponse(mime, null, 200, "OK", headers, new FileInputStream(f));
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(), null);
    }

    private static String mime(File f) {
        switch (VideosPlugin.ext(f.getName())) {
            case "webm": return "video/webm";
            case "mkv": return "video/x-matroska";
            case "3gp": return "video/3gpp";
            default: return "video/mp4";
        }
    }

    /** Stops reading after `left` bytes (the end of the requested range). */
    private static class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long left) {
            super(in);
            this.left = left;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(buf, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), left);
        }
    }
}
