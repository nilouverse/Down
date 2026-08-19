package com.down.game;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;

import java.util.HashMap;
import java.util.HashSet;

public class Sound {

    private static final int MAX_STREAMS = 16;

    private static final String[] HERO_FOLDERS = {
            "nilou", "vex"
    };

    private static final String[] GENERAL_SOUNDS = {
            "ui",
            "turn",
            "step",
            "hit",
            "hurt",
            "death",
            "poison",
            "claw",
            "swing",
            "male_hurt",
            "male_cry",
            "male_death",
            "female_hurt",
            "female_cry",
            "female_death"
    };

    private static final String[] HERO_ACTIONS = {
            "select",
            "turn",
            "move",
            "attack",
            "hurt",
            "wounded",
            "death",
            "kill",
            "victory"
    };

    private final Object lock = new Object();

    private final HashMap<String, Integer> loaded = new HashMap<>();
    private final HashMap<Integer, String> loadIdToName = new HashMap<>();
    private final HashSet<String> loading = new HashSet<>();
    private final HashSet<String> failed = new HashSet<>();
    private final HashSet<String> pending = new HashSet<>();

    private Context context;
    private SoundPool pool;

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
            pool = new SoundPool(
                    MAX_STREAMS,
                    android.media.AudioManager.STREAM_MUSIC,
                    0
            );
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
                            loaded.put(name, sampleId);
                            loading.remove(name);

                            if (pending.remove(name)) {
                                shouldPlay = true;
                            }
                        }
                    }

                    if (shouldPlay) {
                        SoundPool p = pool;
                        if (p != null) {
                            p.play(sampleId, 1f, 1f, 0, 0, 1f);
                        }
                    }
                } else {
                    synchronized (lock) {
                        String name = loadIdToName.remove(sampleId);
                        if (name != null) {
                            loading.remove(name);
                            failed.add(name);
                            pending.remove(name);
                        }
                    }
                }
            }
        });
    }

    private void preloadKnownSounds() {
        for (String name : GENERAL_SOUNDS) {
            load(name);
        }

        for (String hero : HERO_FOLDERS) {
            for (String action : HERO_ACTIONS) {
                load(hero + "_" + action);
            }
        }
    }

    public void play(String name) {
        SoundPool p = pool;
        if (p == null || name == null) return;

        int id = 0;
        boolean shouldLoad = false;

        synchronized (lock) {
            if (failed.contains(name)) return;

            Integer loadedId = loaded.get(name);
            if (loadedId != null) {
                id = loadedId;
            } else {
                shouldLoad = true;
                pending.add(name);
            }
        }

        if (id != 0) {
            p.play(id, 1f, 1f, 0, 0, 1f);
            return;
        }

        if (shouldLoad) {
            load(name);
        }
    }

    private void load(String name) {
        synchronized (lock) {
            if (pool == null
                    || name == null
                    || loaded.containsKey(name)
                    || loading.contains(name)
                    || failed.contains(name)) {
                return;
            }

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
                    id = p.load(
                            afd.getFileDescriptor(),
                            afd.getStartOffset(),
                            afd.getLength(),
                            1
                    );
                }
            }

            synchronized (lock) {
                if (id == 0 || pool == null) {
                    loading.remove(name);
                    failed.add(name);
                    pending.remove(name);
                } else {
                    loadIdToName.put(id, name);
                }
            }
        } catch (Exception e) {
            synchronized (lock) {
                loading.remove(name);
                failed.add(name);
                pending.remove(name);
            }
        } finally {
            if (afd != null) {
                try {
                    afd.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String resolveSoundPath(String name) {
        for (String hero : HERO_FOLDERS) {
            String prefix = hero + "_";
            if (name.startsWith(prefix)) {
                return "sounds/" + hero + "/" + name + ".ogg";
            }
        }

        return "sounds/" + name + ".ogg";
    }

    public void stopAll() {
        SoundPool p = pool;
        if (p != null) {
            p.autoPause();
        }
    }

    public void resumeAll() {
        SoundPool p = pool;
        if (p != null) {
            p.autoResume();
        }
    }

    public void destroy() {
        SoundPool p = pool;
        pool = null;

        if (p != null) {
            try {
                p.release();
            } catch (Exception ignored) {
            }
        }

        synchronized (lock) {
            loaded.clear();
            loadIdToName.clear();
            loading.clear();
            failed.clear();
            pending.clear();
        }
    }
}
