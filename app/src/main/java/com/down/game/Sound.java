package com.down.game;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

public class Sound {

    private static final int MAX_STREAMS = 16;

    private static final String[] HERO_FOLDERS = {
            "nilou", "vex"
    };

    private static final String[] GENERAL_SOUNDS = {
            "ui", "turn", "step", "hit", "hurt", "death", "poison", "claw", "swing",
            "male_hurt", "male_cry", "male_death",
            "female_hurt", "female_cry", "female_death"
    };

    private static final String[] HERO_ACTIONS = {
            "select", "turn", "move", "attack", "hurt", "wounded", "death", "kill", "victory"
    };

    private final Object lock = new Object();
    private final Random rnd = new Random();

    // Pools of variants: "vex_kill" -> [id1, id2, id3]
    private final HashMap<String, ArrayList<Integer>> loaded = new HashMap<>();
    private final HashMap<Integer, String> loadIdToName = new HashMap<>();
    private final HashSet<String> loading = new HashSet<>();
    private final HashSet<String> failed = new HashSet<>();
    private final HashSet<String> pending = new HashSet<>();
    private final HashSet<String> checkedBases = new HashSet<>();

    private Context context;
    private SoundPool pool;

    private static String baseKey(String name) {
        int i = name.length();
        while (i > 0 && Character.isDigit(name.charAt(i - 1))) i--;
        return i == name.length() ? name : name.substring(0, i);
    }

    public void init(Context ctx) {
        if (pool != null) return;
        context = ctx.getApplicationContext();
        createPool();
        preloadKnownSounds();
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
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                if (status == 0) {
                    String name;
                    boolean shouldPlay = false;

                    synchronized (lock) {
                        name = loadIdToName.remove(sampleId);
                        if (name != null) {
                            String key = baseKey(name);
                            ArrayList<Integer> list = loaded.get(key);
                            if (list == null) {
                                list = new ArrayList<>();
                                loaded.put(key, list);
                            }
                            if (!list.contains(sampleId)) list.add(sampleId);
                            loading.remove(name);

                            if (pending.remove(key)) {
                                shouldPlay = true;
                            }
                        }
                    }

                    if (shouldPlay) {
                        SoundPool p = pool;
                        if (p != null) {
                            float rate = 0.95f + rnd.nextFloat() * 0.1f;
                            p.play(sampleId, 1f, 1f, 0, 0, rate);
                        }
                    }
                } else {
                    synchronized (lock) {
                        String name = loadIdToName.remove(sampleId);
                        if (name != null) {
                            loading.remove(name);
                            failed.add(name);
                            pending.remove(baseKey(name));
                        }
                    }
                }
            }
        });
    }

    private void preloadKnownSounds() {
        for (String name : GENERAL_SOUNDS) load(name);
        for (String hero : HERO_FOLDERS) {
            for (String action : HERO_ACTIONS) load(hero + "_" + action);
        }
    }

    public void play(String name) {
        SoundPool p = pool;
        if (p == null || name == null) return;

        String key = baseKey(name);
        int id = 0;

        synchronized (lock) {
            ArrayList<Integer> list = loaded.get(key);
            if (list != null && !list.isEmpty()) {
                id = list.get(rnd.nextInt(list.size()));
            }
        }

        if (id != 0) {
            float rate = 0.95f + rnd.nextFloat() * 0.1f;
            p.play(id, 1f, 1f, 0, 0, rate);
            return;
        }

        // Not loaded yet. Try to load variants if we haven't checked this base key before.
        synchronized (lock) {
            if (checkedBases.contains(key)) return; // Already tried, files just don't exist
            checkedBases.add(key);
            pending.add(key);
        }

        load(key);
        for (int i = 2; i <= 9; i++) {
            load(key + i);
        }
    }

    private void load(String name) {
        synchronized (lock) {
            if (pool == null || name == null || loading.contains(name) || failed.contains(name)) return;
            loading.add(name);
        }

        AssetFileDescriptor afd = null;
        try {
            String path = resolveSoundPath(name);
            afd = context.getAssets().openFd(path);
            int id = 0;
            synchronized (lock) {
                SoundPool p = pool;
                if (p != null) {
                    id = p.load(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength(), 1);
                }
            }
            synchronized (lock) {
                if (id == 0 || pool == null) {
                    loading.remove(name);
                    failed.add(name);
                } else {
                    loadIdToName.put(id, name);
                }
            }
        } catch (Exception e) {
            synchronized (lock) {
                loading.remove(name);
                failed.add(name);
            }
        } finally {
            if (afd != null) {
                try { afd.close(); } catch (Exception ignored) {}
            }
        }
    }

    private String resolveSoundPath(String name) {
        for (String hero : HERO_FOLDERS) {
            if (name.startsWith(hero + "_")) {
                return "sounds/" + hero + "/" + name + ".ogg";
            }
        }
        return "sounds/" + name + ".ogg";
    }

    public void stopAll() {
        SoundPool p = pool;
        if (p != null) p.autoPause();
    }

    public void resumeAll() {
        SoundPool p = pool;
        if (p != null) p.autoResume();
    }

    public void destroy() {
        SoundPool p = pool;
        pool = null;
        if (p != null) {
            try { p.release(); } catch (Exception ignored) {}
        }
        synchronized (lock) {
            loaded.clear();
            loadIdToName.clear();
            loading.clear();
            failed.clear();
            pending.clear();
            checkedBases.clear();
        }
    }
}
