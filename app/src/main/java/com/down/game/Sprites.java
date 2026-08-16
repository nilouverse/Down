package com.down.game;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class Sprites {

    // Chroma key: now also removes SHADOWED/dark magenta (halo + foot blob fix)
    public static Bitmap chromaKey(Bitmap src) {
        Bitmap out = src.copy(Bitmap.Config.ARGB_8888, true);
        int w = out.getWidth(), h = out.getHeight();
        int[] px = new int[w * h];
        out.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            if (r > 120 && b > 120 && g < 110 && Math.abs(r - b) < 70) px[i] = 0x00000000;
        }
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    // Cuts a sheet into frames (row-major). margin trims separator/border lines.
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
            // sheets not uploaded yet -> game runs with placeholder
        }
        return frames;
    }
}
