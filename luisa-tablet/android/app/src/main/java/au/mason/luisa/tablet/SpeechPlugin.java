package au.mason.luisa.tablet;

import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The robot voice. Android's WebView has no speechSynthesis, so the page's stand-in calls this:
 * speak() keeps its call open and reports {type: "range", charIndex} as each word starts, then {type: "end"}.
 */
@CapacitorPlugin(name = "Speech")
public class SpeechPlugin extends Plugin {
    private TextToSpeech tts;
    private volatile boolean ready;
    private final Map<String, PluginCall> speaking = new ConcurrentHashMap<>();
    private int next = 0;

    @Override
    public void load() {
        tts = new TextToSpeech(getContext(), status -> {
            if (status != TextToSpeech.SUCCESS) return;
            if (tts.setLanguage(new Locale("en", "AU")) < 0) tts.setLanguage(Locale.UK);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { finish(id); }
                @Override public void onError(String id) { finish(id); }
                @Override public void onStop(String id, boolean interrupted) { finish(id); }
                @Override public void onRangeStart(String id, int start, int end, int frame) {
                    PluginCall call = speaking.get(id);
                    if (call == null) return;
                    JSObject r = new JSObject();
                    r.put("type", "range");
                    r.put("charIndex", start);
                    call.resolve(r);
                }
            });
            ready = true;
        });
    }

    @PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
    public void speak(PluginCall call) {
        if (!ready) { end(call); return; }
        String id = "u" + (next++);
        call.setKeepAlive(true);
        speaking.put(id, call);
        tts.setSpeechRate(call.getFloat("rate", 1f));
        tts.setPitch(call.getFloat("pitch", 1f));
        tts.speak(call.getString("text", ""), TextToSpeech.QUEUE_ADD, null, id);
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        if (tts != null) tts.stop();
        for (String id : speaking.keySet()) finish(id);
        call.resolve();
    }

    private void finish(String id) {
        PluginCall call = speaking.remove(id);
        if (call != null) end(call);
    }

    private void end(PluginCall call) {
        JSObject r = new JSObject();
        r.put("type", "end");
        call.resolve(r);
        if (call.isKeptAlive()) bridge.releaseCall(call);
    }

    @Override
    protected void handleOnDestroy() {
        if (tts != null) tts.shutdown();
    }
}
