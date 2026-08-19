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

    private final HashMap<String, ArrayList<Integer>> sfxLoaded = new HashMap<>();
    private final HashMap<Integer, String> loadIdToKey = new HashMap<>();
    private final HashSet<String> loadingPaths = new HashSet<>();
    private final HashSet<String> failedPaths = new HashSet<>();
    private final HashSet<String> pendingKeys = new HashSet<>();
    private final HashSet<String> checkedKeys = new HashSet<>();

    private MediaPlayer voiceA, voiceB;
    private MediaPlayer activeVoice;
    private final Queue<String> voiceQueue = new LinkedList<>();
    private String currentVoiceKey = null;
    private volatile boolean voicePreparing = false;

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
                        float vol = isVoicePlaying() ? 0.3f : 1.0f;
                        p.play(sampleId, vol, vol, 0, 0, 0.95f + rnd.nextFloat() * 0.1f);
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
        if (voicePaths.containsKey(key)) playVoice(key);
        else playSfx(key);
    }

    private boolean isVoicePlaying() {
        return activeVoice != null && (activeVoice.isPlaying() || voicePreparing);
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
            float vol = isVoicePlaying() ? 0.3f : 1.0f;
            p.play(id, vol, vol, 0, 0, 0.95f + rnd.nextFloat() * 0.1f);
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
            if (isVoicePlaying()) return;
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

        MediaPlayer prep = (activeVoice == voiceA) ? voiceB : voiceA;
        if (prep == null) {
            prep = new MediaPlayer();
            prep.setOnCompletionListener(mp -> { voiceDone(); processVoiceQueue(); });
            prep.setOnErrorListener((mp, w, x) -> { voiceDone(); processVoiceQueue(); return true; });
            if (voiceA == null) voiceA = prep; else voiceB = prep;
        } else {
            prep.reset();
        }
        
        final MediaPlayer currentPrep = prep;
        try {
            AssetFileDescriptor afd = context.getAssets().openFd(path);
            currentPrep.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            currentPrep.setOnPreparedListener(mp -> {
                synchronized (lock) { 
                    voicePreparing = false; 
                    activeVoice = mp;
                }
                mp.start();
            });
            currentPrep.prepareAsync();
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

    public void stopAll() {
        SoundPool p = pool;
        if (p != null) p.autoPause();
        if (activeVoice != null && activeVoice.isPlaying()) {
            try { activeVoice.pause(); } catch (Exception ignored) {}
        }
    }

    public void resumeAll() {
        SoundPool p = pool;
        if (p != null) p.autoResume();
        if (activeVoice != null && !activeVoice.isPlaying() && currentVoiceKey != null) {
            try { activeVoice.start(); } catch (Exception ignored) {}
        }
    }

    public void destroy() {
        SoundPool p = pool;
        pool = null;
        if (p != null) {
            try { p.release(); } catch (Exception ignored) {}
        }
        if (voiceA != null) { try { voiceA.stop(); voiceA.release(); } catch (Exception ignored) {} voiceA = null; }
        if (voiceB != null) { try { voiceB.stop(); voiceB.release(); } catch (Exception ignored) {} voiceB = null; }
        activeVoice = null;
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
