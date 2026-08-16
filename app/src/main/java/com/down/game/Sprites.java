package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class Sprites {

    // Decode + chroma key + PREMULTIPLIED output = the real-device black-box fix.
    public static Bitmap chromaKey(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);

        int corner = px[0];
        int kr = (corner >> 16) & 255, kg = (corner >> 8) & 255, kb = corner & 255;
        boolean greenBg = (kg > 150 && kg - Math.max(kr, kb) > 60);
        boolean magBg   = (kr > 150 && kb > 150 && Math.min(kr, kb) - kg > 60);

        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int a = 255;

            if (greenBg) {
                int ex = g - Math.max(r, b);
                boolean olive = (g > 90 && b < 90 && g * 2 > r);
                boolean darkGreen = (g > r && g > b && g > 40 && r < 90 && b < 90);
                if (ex > 120 || darkGreen) a = 0;
                else if (ex > 60) a = (120 - ex) * 255 / 60;
                else if (olive) a = 90;
            } else if (magBg) {
                int ex = Math.min(r, b) - g;
                if (ex > 140) a = 0;
                else if (ex > 100) a = (140 - ex) * 255 / 40;
            }

            // premultiply RGB by alpha so the GPU blends correctly
            px[i] = (a << 24) | ((r * a / 255) << 16) | ((g * a / 255) << 8) | (b * a / 255);
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        out.setPremultiplied(true);
        return out;
    }

    public static List<Bitmap> cutSheet(Context ctx, String assetPath,
                                        int rows, int cols, int margin) {
        List<Bitmap> frames = new ArrayList<>();
        try {
            InputStream in = ctx.getAssets().open(assetPath);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inPremultiplied = false;
            Bitmap sheet = BitmapFactory.decodeStream(in, null, opts);
            in.close();
            if (sheet == null) return frames;
            sheet = chromaKey(sheet);
            int cw = sheet.getWidth() / cols;
            int ch = sheet.getHeight() / rows;
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < cols; c++)
                    frames.add(Bitmap.createBitmap(sheet,
                            c * cw + margin, r * ch + margin,
                            cw - 2 * margin, ch - 2 * margin));
        } catch (Exception e) {
            // sheet missing -> placeholder
        }
        return frames;
    }
}
