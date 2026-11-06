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
    private String curLang = null; // null = English (the chosen voice)

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
        // Snake Catcha speaks Dutch, French and Swiss German too ("nl-NL", "fr-FR", "de-CH")
        String lang = call.getString("lang");
        if (lang != null && (lang.isEmpty() || lang.toLowerCase(Locale.ROOT).startsWith("en"))) lang = null;
        if (lang == null ? curLang != null : !lang.equals(curLang)) {
            if (lang == null) english(); else useLang(lang);
            curLang = lang;
        }
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

    private void english() {
        if (tts.setLanguage(new Locale("en", "AU")) < 0) tts.setLanguage(Locale.UK);
        applyVoice(getContext().getSharedPreferences("speech", Context.MODE_PRIVATE).getString("voice", null));
    }

    // Best voice for the language: installed before online-only, this country first (de-CH falls back to any German)
    private void useLang(String tag) {
        Locale want = Locale.forLanguageTag(tag);
        Voice pick = null;
        int pickScore = -1;
        Set<Voice> all = tts.getVoices();
        if (all != null) for (Voice v : all) {
            if (!want.getLanguage().equals(v.getLocale().getLanguage())) continue;
            if (v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            int score = (v.isNetworkConnectionRequired() ? 0 : 2000) + (want.getCountry().equals(v.getLocale().getCountry()) ? 1000 : 0) + v.getQuality();
            if (score > pickScore) { pick = v; pickScore = score; }
        }
        if (pick != null) tts.setVoice(pick);
        else if (tts.setLanguage(want) < 0) tts.setLanguage(new Locale(want.getLanguage()));
    }

    // For the grown-ups page: "installed", "online" (needs internet), "download" (in the engine but not downloaded) or "none"
    @PluginMethod
    public void langs(PluginCall call) {
        JSObject out = new JSObject();
        try {
            JSArray tags = call.getArray("langs");
            Set<Voice> all = ready ? tts.getVoices() : null;
            for (int i = 0; tags != null && i < tags.length(); i++) {
                String tag = tags.getString(i);
                Locale l = Locale.forLanguageTag(tag);
                int best = 0; // 0 none, 1 download, 2 online, 3 installed
                if (all != null) for (Voice v : all) {
                    if (!l.getLanguage().equals(v.getLocale().getLanguage())) continue;
                    if (!l.getCountry().isEmpty() && !l.getCountry().equals(v.getLocale().getCountry())) continue;
                    int b = v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) ? 1 : v.isNetworkConnectionRequired() ? 2 : 3;
                    best = Math.max(best, b);
                }
                out.put(tag, new String[] { "none", "download", "online", "installed" }[best]);
            }
        } catch (Exception e) { /* answer what we have */ }
        JSObject r = new JSObject();
        r.put("langs", out);
        call.resolve(r);
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
