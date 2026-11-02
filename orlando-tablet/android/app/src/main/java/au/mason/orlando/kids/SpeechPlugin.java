package au.mason.orlando.kids;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
            applyVoice(getContext().getSharedPreferences("speech", Context.MODE_PRIVATE).getString("voice", null));
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

    // The chosen voice (Grown-ups → Voices in Travel School), else the best offline Australian one, else British
    private void applyVoice(String name) {
        Set<Voice> all = tts.getVoices();
        if (all == null) return;
        Voice pick = null;
        for (Voice v : all) if (v.getName().equals(name)) pick = v;
        for (String country : new String[] { "AU", "GB" }) {
            if (pick != null) break;
            for (Voice v : all) {
                if (!"en".equals(v.getLocale().getLanguage()) || !country.equals(v.getLocale().getCountry())) continue;
                if (v.isNetworkConnectionRequired() || v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                if (pick == null || v.getQuality() > pick.getQuality()) pick = v;
            }
        }
        if (pick != null) tts.setVoice(pick);
    }

    @PluginMethod
    public void voices(PluginCall call) {
        JSArray list = new JSArray();
        Set<Voice> all = ready ? tts.getVoices() : null;
        if (all != null) for (Voice v : all) {
            if (!"en".equals(v.getLocale().getLanguage())) continue;
            JSObject o = new JSObject();
            o.put("name", v.getName());
            o.put("country", v.getLocale().getCountry());
            o.put("quality", v.getQuality());
            o.put("offline", !v.isNetworkConnectionRequired());
            o.put("installed", !v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
            list.put(o);
        }
        JSObject r = new JSObject();
        r.put("voices", list);
        r.put("current", ready && tts.getVoice() != null ? tts.getVoice().getName() : null);
        call.resolve(r);
    }

    @PluginMethod
    public void setVoice(PluginCall call) {
        String name = call.getString("name");
        getContext().getSharedPreferences("speech", Context.MODE_PRIVATE).edit().putString("voice", name).apply();
        if (ready) applyVoice(name);
        call.resolve();
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
