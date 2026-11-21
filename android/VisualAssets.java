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
        private int alpha = 255;
        private Shader finish, baseFinish, bevel;

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

        @Override protected void onBoundsChange(Rect bounds) {
            if (bounds.height() <= 0) return;
            int color = red ? DeckTheme.RED : face;
            finish = new LinearGradient(0, bounds.top, 0, bounds.bottom,
                new int[]{blend(color, 0xffffffff, red ? .16f : .13f),
                    color, blend(color, 0xff000000, red ? .38f : .35f)},
                new float[]{0,.38f,1}, Shader.TileMode.CLAMP);
            baseFinish = new LinearGradient(0,bounds.top,0,bounds.bottom,
                red ? 0xff491319 : 0xff121318,
                red ? 0xff801f2c : large ? 0xff492027 : 0xff08090b, Shader.TileMode.CLAMP);
            bevel = new LinearGradient(0,bounds.top,0,bounds.bottom,
                red ? 0xfffc7881 : 0xff5a5b63,
                red ? 0xff961e2b : 0xff24252a, Shader.TileMode.CLAMP);
        }

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
            float depth = density * (large ? 6 : 3.5f);
            float edge = density * 1.2f;
            paint.setAlpha(alpha);
            float bottom = bounds.bottom - edge;
            plate.set(bounds.left + edge, bounds.top + edge + depth,
                bounds.right - edge, bottom);
            paint.setShader(baseFinish);
            canvas.drawRoundRect(plate, radius, radius, paint);
            float top = bounds.top + edge + (pressed ? depth * .55f : 0);
            plate.set(bounds.left + edge, top, bounds.right - edge,
                bottom - (pressed ? depth * .4f : depth));
            paint.setShader(finish);
            canvas.drawRoundRect(plate, radius, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(edge);
            paint.setShader(bevel);
            canvas.drawRoundRect(plate, radius, radius, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(density);
            paint.setColor(pressed ? 0x11ffffff : 0x2affffff);
            paint.setAlpha((pressed ? 17 : 42) * alpha / 255);
            canvas.drawLine(plate.left + radius, plate.top + density, plate.right - radius,
                plate.top + density, paint);
        }

        @Override public void getOutline(Outline outline) { outline.setRoundRect(getBounds(), radius); }
        @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static int blend(int from, int to, float amount) {
        int red = Math.round((1-amount)*((from>>16)&255) + amount*((to>>16)&255));
        int green = Math.round((1-amount)*((from>>8)&255) + amount*((to>>8)&255));
        int blue = Math.round((1-amount)*(from&255) + amount*(to&255));
        return 0xff000000 | red<<16 | green<<8 | blue;
    }

    static final class Frame extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF edge = new RectF();
        private final float radius, thickness;
        Frame(float radius, float thickness) {
            this.radius = radius;
            this.thickness = thickness;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(thickness);
        }
        @Override protected void onBoundsChange(Rect bounds) {
            edge.set(bounds);
            edge.inset(thickness/2,thickness/2);
            if (bounds.height() > 0) paint.setShader(new LinearGradient(0,bounds.top,0,bounds.bottom,
                new int[]{0xff515158,0xff333339,0xff26262b,0xff40272c},
                new float[]{0,.23f,.78f,1},Shader.TileMode.CLAMP));
        }
        @Override public void draw(Canvas canvas) {
            canvas.drawRoundRect(edge,radius,radius,paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class Cover extends Drawable {
        private static final float[] STARS = {.16f,.21f,.30f,.37f,.48f,.19f,.58f,.69f,
            .66f,.23f,.74f,.49f,.83f,.22f,.91f,.71f,.95f,.31f};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF corners = new RectF();
        private final Bitmap bitmap;
        private final int base;
        private final float radius;
        private final boolean fitHeight, stars;
        private Shader photo;

        Cover(Bitmap bitmap, int base, float radius, boolean fitHeight) {
            this(bitmap, base, radius, fitHeight, false);
        }

        Cover(Bitmap bitmap, int base, float radius, boolean fitHeight, boolean stars) {
            this.bitmap = bitmap;
            this.base = base;
            this.radius = radius;
            this.fitHeight = fitHeight;
            this.stars = stars;
        }

        @Override protected void onBoundsChange(Rect bounds) {
            corners.set(bounds);
            if (bitmap != null) {
                BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                float scale = Math.max(corners.width() / bitmap.getWidth(), corners.height() / bitmap.getHeight());
                Matrix transform = new Matrix();
                transform.setScale(scale, scale);
                transform.postTranslate(fitHeight ? corners.right - bitmap.getWidth() * scale
                        : corners.centerX() - bitmap.getWidth() * scale / 2,
                    corners.centerY() - bitmap.getHeight() * scale / 2);
                shader.setLocalMatrix(transform);
                photo = shader;
            }
        }

        @Override public void draw(Canvas canvas) {
            paint.setColor(base);
            paint.setShader(photo);
            canvas.drawRoundRect(corners, radius, radius, paint);
            paint.setShader(null);
            if (stars) {
                paint.setColor(0xffdee8ff);
                for (int i = 0; i < STARS.length; i += 2)
                    canvas.drawCircle(corners.left + corners.width()*STARS[i],
                        corners.top + corners.height()*STARS[i+1], radius*(i%4==0?.11f:.065f), paint);
            }
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
