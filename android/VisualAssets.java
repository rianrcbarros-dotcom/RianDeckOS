package br.com.rian.deck;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import java.util.HashMap;
import java.util.Map;

final class VisualAssets {
    private static final Map<String, Bitmap> BITMAPS = new HashMap<>();

    static Bitmap bitmap(Context context, String name) {
        Bitmap image = BITMAPS.get(name);
        if (image != null) return image;
        int dot = name.lastIndexOf('.');
        String resource = name.substring(0, dot).replace('/', '_').replace('-', '_');
        int id = context.getResources().getIdentifier(resource, "drawable", context.getPackageName());
        if (id == 0) {
            android.util.Log.w("RianDeckAssets", "Imagem indisponível: " + name);
            return null;
        }
        image = BitmapFactory.decodeResource(context.getResources(), id);
        if (image != null) BITMAPS.put(name, image);
        return image;
    }

    static Typeface brandTypeface(Context context) {
        int id = context.getResources().getIdentifier("manrope", "font", context.getPackageName());
        if (id == 0) {
            android.util.Log.w("RianDeckAssets", "Fonte Manrope indisponível");
            return Typeface.DEFAULT;
        }
        return Typeface.create(context.getResources().getFont(id), 650, false);
    }

    static Drawable icon(Context context, String name) {
        Bitmap bitmap = bitmap(context, "icons/" + name + ".png");
        return bitmap == null ? null : new BitmapDrawable(context.getResources(), bitmap);
    }

    static final class Keycap extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF plate = new RectF();
        private final float radius, density;
        private final boolean large, red;
        private final int face;
        private boolean pressed;

        Keycap(float radius, float density, boolean large, boolean red) {
            this(radius, density, large, red, DeckTheme.KEY);
        }

        Keycap(float radius, float density, boolean large, boolean red, int face) {
            this.radius = radius;
            this.density = density;
            this.large = large;
            this.red = red;
            this.face = face;
        }

        @Override public boolean isStateful() { return true; }

        @Override protected boolean onStateChange(int[] states) {
            boolean down = false;
            for (int state : states) down |= state == android.R.attr.state_pressed;
            if (pressed == down) return false;
            pressed = down;
            invalidateSelf();
            return true;
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float depth = density * (large ? 5 : 3);
            float edge = density * 1.2f;
            float bottom = bounds.bottom - edge;
            plate.set(bounds.left + edge, bounds.top + edge + depth,
                bounds.right - edge, bottom);
            paint.setColor(large || red ? DeckTheme.DARK_RED : DeckTheme.BLACK);
            canvas.drawRoundRect(plate, radius, radius, paint);
            float top = bounds.top + edge + (pressed ? depth * .55f : 0);
            plate.set(bounds.left + edge, top, bounds.right - edge,
                bottom - (pressed ? depth * .4f : depth));
            paint.setColor(red ? DeckTheme.RED : large ? face : DeckTheme.KEY);
            canvas.drawRoundRect(plate, radius, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(edge);
            paint.setColor(red ? DeckTheme.DARK_RED : DeckTheme.EDGE);
            canvas.drawRoundRect(plate, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(density);
            paint.setColor(0x20ffffff);
            canvas.drawLine(plate.left + radius, plate.top + density, plate.right - radius,
                plate.top + density, paint);
        }

        @Override public void getOutline(Outline outline) { outline.setRoundRect(getBounds(), radius); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class Cover extends Drawable {
        private static ColorFilter grayscale() {
            android.graphics.ColorMatrix matrix = new android.graphics.ColorMatrix();
            matrix.setSaturation(0);
            return new android.graphics.ColorMatrixColorFilter(matrix);
        }
        private static final ColorFilter MONOCHROME = grayscale();
        private static final int VERSE_PHOTO_ALPHA = 230; // Approximately 90% of 255
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF corners = new RectF();
        private final Bitmap bitmap;
        private final int base;
        private final float radius;
        private final boolean subdued;
        private Shader photo, tint;

        Cover(Bitmap bitmap, int base, float radius, boolean subdued) {
            this.bitmap = bitmap;
            this.base = base;
            this.radius = radius;
            this.subdued = subdued;
        }

        @Override protected void onBoundsChange(Rect bounds) {
            corners.set(bounds);
            if (bitmap != null) {
                BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float scale = subdued ? corners.height() / bitmap.getHeight()
                    : Math.max(corners.width() / bitmap.getWidth(), corners.height() / bitmap.getHeight());
                Matrix transform = new Matrix();
                transform.setScale(scale, scale);
                transform.postTranslate(subdued ? corners.right - bitmap.getWidth() * scale
                        : corners.centerX() - bitmap.getWidth() * scale / 2,
                    corners.centerY() - bitmap.getHeight() * scale / 2);
                shader.setLocalMatrix(transform);
                photo = shader;
            }
            if (!subdued && bounds.width() > 0) {
                tint = new LinearGradient(corners.left, corners.bottom, corners.right, corners.top,
                    new int[]{0xfe111111, 0xd2111111, 0x90111111}, null, Shader.TileMode.CLAMP);
            }
        }

        @Override public void draw(Canvas canvas) {
            paint.setColor(base);
            if (subdued) {
                canvas.drawRoundRect(corners, radius, radius, paint);
                if (photo != null) {
                    paint.setShader(photo);
                    paint.setColorFilter(MONOCHROME);
                    paint.setAlpha(VERSE_PHOTO_ALPHA);
                    canvas.drawRoundRect(corners, radius, radius, paint);
                    paint.setColorFilter(null);
                    paint.setAlpha(255);
                }
            } else {
                paint.setShader(photo);
                canvas.drawRoundRect(corners, radius, radius, paint);
            }
            if (photo != null && tint != null) {
                paint.setShader(tint);
                canvas.drawRoundRect(corners, radius, radius, paint);
            }
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1);
            paint.setColor(DeckTheme.EDGE);
            canvas.drawRoundRect(corners, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
