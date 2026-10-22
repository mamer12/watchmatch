package io.watchmatch;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.os.Build;

/** A 48x27 CPU copy of the aurora sky; the GPU's linear upscale blurs it into frosted glass. */
final class Frost {
    static final int W = 48, H = 27;

    private final int[] pixels = new int[W * H];
    private Bitmap bitmap;

    BitmapShader render(float t, float speed, float dim, float[] pal, float aspect) {
        FrostMath.fill(pixels, W, H, t, speed, dim, pal, aspect);
        if (bitmap == null) bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, W, 0, 0, W, H);
        // A shader snapshots its bitmap when built, so each frame needs a new one.
        return filter(new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
                BitmapShader.FILTER_MODE_LINEAR);
    }

    static BitmapShader filter(BitmapShader s, int mode) {
        if (Build.VERSION.SDK_INT >= 33) s.setFilterMode(mode);
        return s;
    }
}
