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
    private final HashMap<String, ArrayList<Integer>> ids = new HashMap<>();
    private final Random rnd = new Random();
    private static final String[] NAMES = {
            "swing", "hit", "bolt", "nova", "death", "hurt", "ui", "turn", "step"
    };

    public void init(Context ctx) {
        try {
            pool = new SoundPool.Builder()
                    .setMaxStreams(8)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .build();
            for (String n : NAMES) {
                ArrayList<Integer> list = new ArrayList<>();
                int base = load(ctx, "sounds/" + n + ".ogg");
                if (base != 0) list.add(base);
                for (int v = 2; v
