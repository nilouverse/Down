package com.down.game;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.util.HashMap;

public class Sound {

    private SoundPool pool;
    private boolean enabled = true;
    private final HashMap<String, Integer> ids = new HashMap<>();
    private static final String[] NAMES = {
            "swing", "hit", "bolt", "nova", "death", "hurt", "ui", "turn", "step"
    };

    public void init(Context ctx) {
        try {
            pool = new SoundPool.Builder()
                    .setMaxStreams(6)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .build();
            for (String n : NAMES) {
                try {
                    AssetFileDescriptor afd = ctx.getAssets().openFd("sounds/" + n + ".ogg");
                    ids.put(n, pool.load(afd, 1));
                    afd.close();
                } catch (Exception e) {
                }
            }
        } catch (Exception e) {
        }
    }

    public void play(String n) {
        if (!enabled || pool == null) return;
        Integer id = ids.get(n);
        if (id == null) return;
        pool.play(id, 1f, 1f, 0, 0, 1f);
    }

    public void setEnabled(boolean e) { enabled = e; }

    public void release() {
        if (pool != null) pool.release();
        pool = null;
        ids.clear();
    }
}
