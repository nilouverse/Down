package com.down.game;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;
import java.util.Random;

public class Sound {

    private static final int MAX_STREAMS = 16;
    private static final String[] HERO_FOLDERS = { "nilou", "vex" };

    private final Object lock = new Object();
    private final Random rnd = new Random();

    private final HashMap<String, ArrayList<String>> sfxPaths = new HashMap<>();
    private final HashMap<String, ArrayList<String>> voicePaths = new HashMap<>();
    private final HashMap<String, ArrayList<String>> ambientPaths = new HashMap<>();

    private final HashMap<String, ArrayList<Integer>> sfxLoaded = new HashMap<>();
    private final HashMap<Integer, String> loadIdToKey = new HashMap<>();
    private final HashSet<String> loadingPaths = new HashSet<>();
    private final HashSet<String> failedPaths = new HashSet<>();
    private final HashSet<String> pendingKeys = new HashSet<>();
    private final HashSet<String> checkedKeys = new HashSet<>();

    private MediaPlayer voicePlayer;
    private final Queue<String> voiceQueue = new LinkedList<>();
    private String currentVoiceKey = null;
    private volatile boolean voicePreparing = false;

    // Ambient bed layer — independent of voice and SFX. Silent-fail on missing assets.
    private MediaPlayer ambientPlayer;
    private String ambientKey = null;
    private float ambientTargetVol = 0.75f;
    private float ambientCurVol = 0f;
    private Thread ambientFadeThread = null;
    private volatile boolean ambientFadeStop = false;
    private volatile boolean ambientPaused = false;

    private Context context;
    private SoundPool pool;

    private static String baseKey(String name) {
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
        return i == name.length() ? name : name.substring(0, i);
    }

    public void init(Context ctx) {
        if (pool != null || context != null) return;
        context = ctx.getApplicationContext();
        scan();
        createPool();
        preloadSfx();
    }

    private void addPath(HashMap<String, ArrayList<String>> map, String key, String path) {
        ArrayList<String> l = map.get(key);
        if (l == null) { l = new ArrayList<>(); map.put(key, l); }
        l.add(path);
    }

    private void scan() {
        try {
            String[] root = context.getAssets().list("sounds");
            if (root != null) {
                for (String f : root) {
                    if (f == null) continue;
                    String low = f.toLowerCase(Locale.US);
                    if (!low.endsWith(".ogg") && !low.endsWith(".wav")) continue;
                    String base = f.substring(0, f.lastIndexOf('.'));
                    addPath(sfxPaths, baseKey(base), "sounds/" + f);
                }
            }
            for (String hero : HERO_FOLDERS) {
                String[] files = context.getAssets().list("sounds/" + hero);
                if (files == null) continue;
                for (String f : files) {
                    if (f == null) continue;
                    String low = f.toLowerCase(Locale.US);
                    if (!low.endsWith(".ogg") && !low.endsWith(".wav")) continue;
                    String base = f.substring(0, f.lastIndexOf('.'));
                    addPath(voicePaths, baseKey(base), "sounds/" + hero + "/" + f);
                }
            }
            // Ambient folder: sounds/ambient/ — pure loops for scene beds.
            String[] amb = context.getAssets().list("sounds/ambient");
            if (amb != null) {
                for (String f : amb) {
                    if (f == null) continue;
                    String low = f.toLowerCase(Locale.US);
                    if (!low.endsWith(".ogg") && !low.endsWith(".wav")) continue;
                    String base = f.substring(0, f.lastIndexOf('.'));
                    addPath(ambientPaths, baseKey(base), "sounds/ambient/" + f);
                }
            }
        } catch (Exception ignored) {}
    }

    private void createPool() {
        if (Build.VERSION.SDK_INT >= 21) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            pool = new SoundPool.Builder()
                    .setMaxStreams(MAX_STREAMS)
                    .setAudioAttributes(attributes)
                    .build();
        } else {
            pool = new SoundPool(MAX_STREAMS, android.media.AudioManager.STREAM_MUSIC, 0);
        }

        pool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            @Override
            public void onLoadComplete(SoundPool sp, int sampleId, int status) {
                String key;
                boolean shouldPlay = false;
                synchronized (lock) {
                    key = loadIdToKey.remove(sampleId);
                    if (key != null) {
                        if (status == 0) {
                            ArrayList<Integer> l = sfxLoaded.get(key);
                            if (l == null) { l = new ArrayList<>(); sfxLoaded.put(key, l); }
                            if (!l.contains(sampleId)) l.add(sampleId);
                            if (pendingKeys.remove(key)) shouldPlay = true;
                        } else {
                            pendingKeys.remove(key);
                        }
                    }
                }
                if (shouldPlay && status == 0) {
                    SoundPool p = pool;
                    if (p != null) {
                        p.play(sampleId, 1f, 1f, 0, 0, 0.95f + rnd.nextFloat() * 0.1f);
                    }
                }
            }
        });
    }

    private void preloadSfx() {
        for (String key : new ArrayList<>(sfxPaths.keySet())) loadSfxKey(key);
    }

    private void loadSfxKey(String key) {
        ArrayList<String> paths = sfxPaths.get(key);
        if (paths == null) return;
        for (String path : paths) loadPath(path, key);
    }

    private void loadPath(String path, String key) {
        synchronized (lock) {
            if (pool == null || loadingPaths.contains(path) || failedPaths.contains(path)) return;
            loadingPaths.add(path);
        }
        AssetFileDescriptor afd = null;
        try {
            afd = context.getAssets().openFd(path);
            int id = 0;
            synchronized (lock) {
                if (pool != null) {
                    id = pool.load(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength(), 1);
                }
            }
            synchronized (lock) {
                loadingPaths.remove(path);
                if (id == 0 || pool == null) failedPaths.add(path);
                else loadIdToKey.put(id, key);
            }
        } catch (Exception e) {
            synchronized (lock) {
                loadingPaths.remove(path);
                failedPaths.add(path);
            }
        } finally {
            if (afd != null) {
                try { afd.close(); } catch (Exception ignored) {}
            }
        }
    }

    public void play(String name) {
        if (name == null || context == null) return;
        String key = baseKey(name.toLowerCase(Locale.US));
        // Route: ambient keys go to the ambient bed layer, voice keys to voice, else SFX.
        if (ambientPaths.containsKey(key)) { setAmbient(key); return; }
        if (voicePaths.containsKey(key)) { playVoice(key); return; }
        playSfx(key);
    }

    // Looping footsteps: plays while any unit moves, stops when idle.
    private int footStream = 0;
    public void setFootsteps(boolean on) {
        SoundPool p = pool;
        if (p == null) return;
        if (on && footStream == 0) {
            int id = 0;
            synchronized (lock) {
                ArrayList<Integer> l = sfxLoaded.get("footsteps_running");
                if (l != null && !l.isEmpty()) id = l.get(rnd.nextInt(l.size()));
            }
            if (id != 0) footStream = p.play(id, 0.8f, 0.8f, 0, -1, 1f);
        } else if (!on && footStream != 0) {
            try { p.stop(footStream); } catch (Exception e) {}
            footStream = 0;
        }
    }

    private void playSfx(String key) {
        SoundPool p = pool;
        if (p == null) return;
        int id = 0;
        synchronized (lock) {
            ArrayList<Integer> l = sfxLoaded.get(key);
            if (l != null && !l.isEmpty()) id = l.get(rnd.nextInt(l.size()));
        }
        if (id != 0) {
            p.play(id, 1f, 1f, 0, 0, 0.95f + rnd.nextFloat() * 0.1f);
            return;
        }
        synchronized (lock) {
            if (checkedKeys.contains(key)) return;
            checkedKeys.add(key);
            pendingKeys.add(key);
        }
        loadSfxKey(key);
    }

    private void playVoice(String key) {
        synchronized (lock) {
            if (key.equals(currentVoiceKey) || voiceQueue.contains(key)) return;
            voiceQueue.add(key);
        }
        processVoiceQueue();
    }

    private void processVoiceQueue() {
        String key;
        synchronized (lock) {
            if (voiceQueue.isEmpty()) return;
            if (voicePlayer != null && (voicePlayer.isPlaying() || voicePreparing)) return;
            key = voiceQueue.poll();
            currentVoiceKey = key;
            voicePreparing = true;
        }
        ArrayList<String> paths = voicePaths.get(key);
        if (paths == null || paths.isEmpty()) {
            voiceDone();
            processVoiceQueue();
            return;
        }
        String path = paths.get(rnd.nextInt(paths.size()));

        if (voicePlayer == null) {
            voicePlayer = new MediaPlayer();
            voicePlayer.setOnCompletionListener(mp -> { voiceDone(); processVoiceQueue(); });
            voicePlayer.setOnErrorListener((mp, w, x) -> { voiceDone(); processVoiceQueue(); return true; });
        } else {
            voicePlayer.reset();
        }
        try {
            AssetFileDescriptor afd = context.getAssets().openFd(path);
            voicePlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            voicePlayer.setOnPreparedListener(mp -> {
                synchronized (lock) { voicePreparing = false; }
                mp.start();
            });
            voicePlayer.prepareAsync();
        } catch (Exception e) {
            voiceDone();
            processVoiceQueue();
        }
    }

    private void voiceDone() {
        synchronized (lock) {
            currentVoiceKey = null;
            voicePreparing = false;
        }
    }

    // =====================================================================
    // AMBIENT BED LAYER — crossfades, loops, silent-fail on missing assets
    // =====================================================================
    public void setAmbient(String key) {
        if (context == null) return;
        if (key == null || key.isEmpty()) { stopAmbient(); return; }
        if (key.equals(ambientKey) && ambientPlayer != null) return;

        ArrayList<String> paths = ambientPaths.get(key);
        if (paths == null || paths.isEmpty()) {
            // Sound law: missing ambient must never break the scene.
            stopAmbient();
            return;
        }
        String path = paths.get(rnd.nextInt(paths.size()));

        final MediaPlayer old = ambientPlayer;
        final String oldKey = ambientKey;
        ambientKey = key;

        final MediaPlayer np = new MediaPlayer();
        ambientPlayer = np;
        np.setLooping(true);
        np.setOnErrorListener((mp, w, x) -> {
            if (mp == ambientPlayer) ambientKey = null;
            try { mp.release(); } catch (Exception ignored) {}
            return true;
        });
        np.setOnPreparedListener(mp -> {
            mp.setVolume(0f, 0f);
            try { mp.start(); } catch (Exception ignored) {}
        });

        try {
            AssetFileDescriptor afd = context.getAssets().openFd(path);
            np.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            np.prepareAsync();
        } catch (Exception e) {
            if (np == ambientPlayer) ambientKey = null;
            try { np.release(); } catch (Exception ignored) {}
            return;
        }

        // Crossfade: old -> 0, new -> target, over ~0.9s on a worker thread.
        ambientFadeStop = true;
        Thread prev = ambientFadeThread;
        if (prev != null) {
            try { prev.join(60); } catch (InterruptedException ignored) {}
        }
        ambientFadeStop = false;
        ambientCurVol = 0f;
        Thread t = new Thread(new Runnable() {
            public void run() {
                final long start = System.currentTimeMillis();
                final long dur = 900;
                while (!ambientFadeStop) {
                    long e = System.currentTimeMillis() - start;
                    float k = e / (float) dur; if (k > 1f) k = 1f;
                    float inV = ambientTargetVol * k;
                    float outV = ambientTargetVol * (1f - k);
                    try { if (np.isPlaying()) np.setVolume(inV, inV); } catch (Exception ignored) {}
                    try { if (old != null && old.isPlaying()) old.setVolume(outV, outV); } catch (Exception ignored) {}
                    if (k >= 1f) break;
                    try { Thread.sleep(30); } catch (InterruptedException ignored) {}
                }
                if (old != null) {
                    try { old.stop(); } catch (Exception ignored) {}
                    try { old.release(); } catch (Exception ignored) {}
                }
            }
        }, "snd-ambient-fade");
        t.setDaemon(true);
        t.start();
        ambientFadeThread = t;
    }

    public void stopAmbient() {
        ambientKey = null;
        ambientFadeStop = true;
        MediaPlayer p = ambientPlayer;
        ambientPlayer = null;
        if (p != null) {
            try { p.stop(); } catch (Exception ignored) {}
            try { p.release(); } catch (Exception ignored) {}
        }
    }

    public void pauseAmbient() {
        ambientPaused = true;
        MediaPlayer p = ambientPlayer;
        if (p != null) { try { p.pause(); } catch (Exception ignored) {} }
    }

    public void resumeAmbient() {
        if (!ambientPaused) return;
        ambientPaused = false;
        MediaPlayer p = ambientPlayer;
        if (p != null) { try { p.start(); } catch (Exception ignored) {} }
    }

    public void setAmbientVolume(float v) {
        if (v < 0f) v = 0f; if (v > 1f) v = 1f;
        ambientTargetVol = v;
    }

    public void stopAll() {
        SoundPool p = pool;
        if (p != null) p.autoPause();
        if (voicePlayer != null && voicePlayer.isPlaying()) {
            try { voicePlayer.pause(); } catch (Exception ignored) {}
        }
        pauseAmbient();
    }

    public void resumeAll() {
        SoundPool p = pool;
        if (p != null) p.autoResume();
        if (voicePlayer != null && !voicePlayer.isPlaying() && currentVoiceKey != null) {
            try { voicePlayer.start(); } catch (Exception ignored) {}
        }
        resumeAmbient();
    }

    public void destroy() {
        SoundPool p = pool;
        pool = null;
        if (p != null) {
            try { p.release(); } catch (Exception ignored) {}
        }
        if (voicePlayer != null) {
            try { voicePlayer.stop(); voicePlayer.release(); } catch (Exception ignored) {}
            voicePlayer = null;
        }
        stopAmbient();
        synchronized (lock) {
            voiceQueue.clear();
            currentVoiceKey = null;
            voicePreparing = false;
            sfxLoaded.clear();
            loadIdToKey.clear();
            loadingPaths.clear();
            failedPaths.clear();
            pendingKeys.clear();
            checkedKeys.clear();
        }
    }
}
