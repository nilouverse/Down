package com.down.game;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

public class Sound {

    private SoundPool pool;
    private boolean enabled = true;
    private boolean initialized = false;
    private final HashMap<String, ArrayList<Integer>> ids = new HashMap<>();
    private final Random rnd = new Random();

    /** Idempotent — safe to call repeatedly; only the first one does work. */
    public void init(Context ctx) {
        if (initialized && pool != null) return;
        try {
            if (pool != null) {
                pool.release();
                pool = null;
            }
            ids.clear();

            pool = new SoundPool.Builder()
                    .setMaxStreams(12)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .build();

            String[] files = ctx.getAssets().list("sounds");
            if (files == null) { initialized = true; return; }

            for (String file : files) {
                if (file == null) continue;
                if (!file.toLowerCase(java.util.Locale.US).endsWith(".ogg")) continue;

                String base = file.substring(0, file.length() - 4);
                while (base.length() > 0
                       && Character.isDigit(base.charAt(base.length() - 1))) {
                    base = base.substring(0, base.length() - 1);
                }
                if (base.length() == 0) continue;

                String key = base.toLowerCase(java.util.Locale.US);

                AssetFileDescriptor afd = null;
                try {
                    afd = ctx.getAssets().openFd("sounds/" + file);
                    int id = pool.load(afd, 1);
                    if (id != 0) {
                        ArrayList<Integer> list = ids.get(key);
                        if (list == null) {
                            list = new ArrayList<>();
                            ids.put(key, list);
                        }
                        list.add(id);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (afd != null) {
                        try { afd.close(); } catch (Exception ignored) {}
                    }
                }
            }
            initialized = true;
        } catch (Exception ignored) {
            initialized = true;
        }
    }

    public void play(String name) {
        if (!enabled || pool == null || name == null) return;
        ArrayList<Integer> list = ids.get(name.toLowerCase(java.util.Locale.US));
        if (list == null || list.isEmpty()) return;
        int id = list.get(rnd.nextInt(list.size()));
        float rate = 0.95f + rnd.nextFloat() * 0.1f;
        pool.play(id, 1f, 1f, 0, 0, rate);
    }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** Pause — keeps the pool alive; called on Activity pause. */
    public void stopAll() {
        if (pool != null) {
            try { pool.autoPause(); } catch (Exception ignored) {}
        }
    }

    /** Resume — called on Activity resume. */
    public void resumeAll() {
        if (pool != null) {
            try { pool.autoResume(); } catch (Exception ignored) {}
        }
    }

    /** True release — only on Activity destroy. */
    public void destroy() {
        if (pool != null) {
            try { pool.release(); } catch (Exception ignored) {}
            pool = null;
        }
        ids.clear();
        initialized = false;
    }

    /** Kept for backward compat; delegates to destroy. */
    public void release() { destroy(); }
}
