package io.watchmatch;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/** Animated aurora background plus glass and liquid panels, with flat fallbacks. UI thread only. */
final class Scene {
    private static final int LAYER_DIV = 4;

    final Shaders.Palette palette = new Shaders.Palette();

    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flatPaint = new Paint();
    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint auroraPaint = new Paint();
    private final Paint glassPaint = new Paint();
    private final Paint liquidPaint = new Paint();
    private final Recording aurora = Recording.create("aurora");
    private final Frost frost = new Frost();

    private RuntimeShader auroraShader, glassShader, liquidShader;
    private Bitmap frostBitmap, glassA, glassB;
    private boolean photoFrost, photoGlass;
    private int w = 1, h = 1, lw = 1, lh = 1;
    private float speed = 1, baseDim, time;
    private boolean dirty = true, flatReady;
    private LinearGradient flat;

    Scene() {
        rim.setStyle(Paint.Style.STROKE);
        rim.setColor(0x33FFFFFF);
        rim.setStrokeWidth(1.5f);
        aurora.setUseCompositingLayer(true, layerPaint);
        if (Ui.lite) {
            AppLog.w("lite: flat aurora and glass");
            return;
        }
        try {
            auroraShader = new RuntimeShader(Shaders.AURORA);
            glassShader = new RuntimeShader(Shaders.GLASS);
            liquidShader = new RuntimeShader(Shaders.LIQUID);
            auroraPaint.setShader(auroraShader);
            glassPaint.setShader(glassShader);
            liquidPaint.setShader(liquidShader);
        } catch (RuntimeException | LinkageError e) {
            AppLog.w("shader unavailable, using gradient: " + e.getMessage());
            auroraShader = glassShader = liquidShader = null;
        }
    }

    boolean moving() { return auroraShader != null; }

    void setSize(int width, int height) {
        w = Math.max(1, width);
        h = Math.max(1, height);
        lw = Math.max(1, Math.round((float) w / LAYER_DIV));
        lh = Math.max(1, Math.round((float) h / LAYER_DIV));
        aurora.setPosition(0, 0, lw, lh);
        flatReady = false;
        if (auroraShader == null) return;
        auroraShader.setFloatUniform("res", w, h);
        auroraShader.setFloatUniform("layer", lw, lh);
        updateFrostScale();
        if (photoGlass) liquidShader.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
        push();
    }

    void setMotion(float speed, float dim) {
        this.speed = speed;
        baseDim = dim;
        push();
    }

    void applyPalette() {
        push();
        flat = new LinearGradient(0, 0, 0, h, palette.color(1), palette.color(0), Shader.TileMode.CLAMP);
        flatPaint.setShader(flat);
        flatReady = true;
    }

    void advance(float seconds) {
        time = seconds;
        if (auroraShader == null) return;
        auroraShader.setFloatUniform("t", seconds);
        if (!(photoFrost && photoGlass)) {
            BitmapShader sky = frost.render(seconds, speed, currentDim(), palette.c, (float) w / h);
            if (!photoFrost) glassShader.setInputShader("frost", sky);
            if (!photoGlass) {
                liquidShader.setInputShader("imageA", sky);
                liquidShader.setInputShader("imageB", sky);
                liquidShader.setFloatUniform("mixB", 0);
                liquidShader.setFloatUniform("imageScale", (float) Frost.W / w, (float) Frost.H / h);
            }
        }
        dirty = true;
    }

    void drawBackground(Canvas c) {
        if (auroraShader == null) {
            if (!flatReady) applyFlat();
            c.drawRect(0, 0, w, h, flatPaint);
            return;
        }
        if (dirty) {
            Canvas rc = aurora.beginRecording(lw, lh);
            try {
                rc.drawRect(0, 0, lw, lh, auroraPaint);
            } finally {
                aurora.endRecording();
            }
            dirty = false;
        }
        c.save();
        c.scale((float) w / lw, (float) h / lh);
        aurora.draw(c);
        c.restore();
    }

    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha,
               float lift, float body) {
        glass(c, r, radius, tint, tintAlpha, glow, glowAlpha, lift, body, 1);
    }

    void glass(Canvas c, RectF r, float radius, int tint, float tintAlpha, int glow, float glowAlpha,
               float lift, float body, float alpha) {
        if (alpha <= 0.004f) return;
        if (glassShader == null) {
            if (body > 0) {
                fill.setColor(Ui.alpha(0xFF000000 | (tint & 0xFFFFFF), Math.max(0.35f, tintAlpha)));
                c.drawRoundRect(r, radius, radius, fill);
            }
            c.drawRoundRect(r, radius, radius, rim);
            return;
        }
        float hw = r.width() / 2, hh = r.height() / 2, inner = Math.min(hw, hh);
        glassShader.setFloatUniform("center", r.centerX(), r.centerY());
        glassShader.setFloatUniform("halfSize", hw, hh);
        glassShader.setFloatUniform("invHalf", 1 / Math.max(hw, 1), 1 / Math.max(hh, 1));
        glassShader.setFloatUniform("radius", radius);
        glassShader.setFloatUniform("top", r.top);
        glassShader.setFloatUniform("depth", 1 / Math.max(0.6f * inner, 1), Math.min(24, 0.5f * inner),
                1 / Math.max(0.9f * inner, 1), 1 / Math.max(r.height(), 1));
        glassShader.setFloatUniform("tint", rgb(tint, 16), rgb(tint, 8), rgb(tint, 0), tintAlpha);
        glassShader.setFloatUniform("glow", rgb(glow, 16), rgb(glow, 8), rgb(glow, 0), glowAlpha);
        glassShader.setFloatUniform("lift", lift);
        glassShader.setFloatUniform("body", body);
        shaderRect(c, r, glassPaint, alpha);
    }

    void liquid(Canvas c, RectF r, float radius, int tint, float tintAlpha, float lift, float band,
                float refraction, float specular, float alpha) {
        if (alpha <= 0.004f) return;
        if (liquidShader == null) {
            glass(c, r, radius, tint, Math.max(0.5f, tintAlpha), 0, 0, lift, 1, alpha);
            return;
        }
        liquidShader.setFloatUniform("center", r.centerX(), r.centerY());
        liquidShader.setFloatUniform("halfSize", r.width() / 2, r.height() / 2);
        liquidShader.setFloatUniform("radius", Math.min(radius, Math.min(r.width(), r.height()) / 2));
        liquidShader.setFloatUniform("tint", rgb(tint, 16), rgb(tint, 8), rgb(tint, 0), tintAlpha);
        liquidShader.setFloatUniform("lift", lift);
        liquidShader.setFloatUniform("band", Math.max(1, band));
        liquidShader.setFloatUniform("refraction", refraction);
        liquidShader.setFloatUniform("specular", specular);
        shaderRect(c, r, liquidPaint, alpha);
    }

    void setFrost(int[] pixels) {
        if (frostBitmap == null) frostBitmap = Bitmap.createBitmap(Backdrop.FW, Backdrop.FH, Bitmap.Config.ARGB_8888);
        frostBitmap.setPixels(pixels, 0, Backdrop.FW, 0, 0, Backdrop.FW, Backdrop.FH);
        photoFrost = true;
        if (glassShader != null) {
            glassShader.setInputShader("frost", photo(frostBitmap));
            updateFrostScale();
        }
    }

    void clearFrost() {
        if (!photoFrost) return;
        photoFrost = false;
        if (glassShader != null) updateFrostScale();
        advance(time);
    }

    Bitmap frostImage() { return photoFrost ? frostBitmap : null; }

    void setGlassImage(int[] pixels) { setGlassImages(pixels, null); }

    void setGlassImages(int[] from, int[] to) {
        glassA = fillBitmap(glassA, from);
        if (to != null) glassB = fillBitmap(glassB, to);
        photoGlass = true;
        if (liquidShader == null) return;
        liquidShader.setInputShader("imageA", photo(glassA));
        liquidShader.setInputShader("imageB", photo(to != null ? glassB : glassA));
        liquidShader.setFloatUniform("mixB", 0);
        liquidShader.setFloatUniform("imageScale", (float) Backdrop.GW / w, (float) Backdrop.GH / h);
    }

    void setGlassMix(float t) {
        if (liquidShader != null) liquidShader.setFloatUniform("mixB", Ui.clamp(t));
    }

    void clearGlass() {
        if (!photoGlass) return;
        photoGlass = false;
        advance(time);
    }

    void setOffset(float x, float y) {
        if (glassShader != null) glassShader.setFloatUniform("offset", x, y);
        if (liquidShader != null) liquidShader.setFloatUniform("offset", x, y);
    }

    // ---- internals -------------------------------------------------------------------------

    /** Brighter palettes get darker so white text stays readable; never past 0.6. */
    private float currentDim() {
        float extra = Math.max(0, Math.min(0.22f, (palette.brightest() - 0.45f) * 0.5f));
        return Math.min(0.6f, baseDim + extra);
    }

    private void push() {
        if (auroraShader == null) return;
        auroraShader.setFloatUniform("speed", speed);
        auroraShader.setFloatUniform("dim", currentDim());
        palette.apply(auroraShader);
        advance(time);
    }

    private void applyFlat() {
        flat = new LinearGradient(0, 0, 0, h, palette.color(1), palette.color(0), Shader.TileMode.CLAMP);
        flatPaint.setShader(flat);
        flatReady = true;
    }

    private void updateFrostScale() {
        if (glassShader == null) return;
        if (photoFrost) glassShader.setFloatUniform("frostScale", (float) Backdrop.FW / w, (float) Backdrop.FH / h);
        else glassShader.setFloatUniform("frostScale", (float) Frost.W / w, (float) Frost.H / h);
    }

    private void shaderRect(Canvas c, RectF r, Paint p, float alpha) {
        p.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawRect(r.left - 1, r.top - 1, r.right + 1, r.bottom + 1, p);
        p.setAlpha(255);
    }

    private static float rgb(int color, int shift) { return ((color >> shift) & 255) / 255f; }

    private static BitmapShader photo(Bitmap b) {
        return Frost.filter(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
                BitmapShader.FILTER_MODE_LINEAR);
    }

    private static Bitmap fillBitmap(Bitmap b, int[] px) {
        if (b == null) b = Bitmap.createBitmap(Backdrop.GW, Backdrop.GH, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, Backdrop.GW, 0, 0, Backdrop.GW, Backdrop.GH);
        return b;
    }
}
