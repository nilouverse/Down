package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class Sprites {

    // GREEN chroma key (#00FF00), retuned for green-spill on skin:
    // hard cut only strong green, soft anti-aliased edge on the fringe, de-spilled.
    public static Bitmap chromaKey(Bitmap src) {
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        int w = out.getWidth(), h = out.getHeight();
        int[] px = new int[w * h];
        out.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int ex = g - Math.max(r, b);
            if (ex > 120) {
                px[i] = 0;                              // pure/shadowed green
            } else if (ex > 60) {
                int a = (120 - ex) * 255 / 60;          // soft edge
                int m = Math.max(r, b);
                px[i] = (a << 24) | (r << 16) | (m << 8) | b;
            }
        }
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    public static List<Bitmap> cutSheet(Context ctx, String assetPath,
                                        int rows, int cols, int margin) {
        List<Bitmap> frames = new ArrayList<>();
        try {
            InputStream in = ctx.getAssets().open(assetPath);
            Bitmap sheet = chromaKey(BitmapFactory.decodeStream(in));
            in.close();
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
