package br.com.rian.deck;

import android.content.Context;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;

final class MemeStatus extends FrameLayout {
    static final class Band {
        private int level = -1, pending = -1;
        private long since;
        int update(double value, double attention, double critical, boolean lower, long now) {
            if (!Double.isFinite(value)) { level = pending = -1; return level; }
            if (lower) { value = -value; attention = -attention; critical = -critical; }
            double gap = Math.max(.01, (critical - attention) * .10);
            int target = value >= critical - (level == 2 ? gap : 0) ? 2
                : value >= attention - (level >= 1 ? gap : 0) ? 1 : 0;
            if (level < 0) { level = target; pending = -1; }
            else if (target == level) pending = -1;
            else if (target != pending) { pending = target; since = now; }
            else if (now - since >= 6000) { level = target; pending = -1; }
            return level;
        }
    }

    private final ImageView image;
    private final TextView fallback;
    private AnimatedImageDrawable animation;
    private String current = "";
    private boolean enabled;
    private boolean motionAllowed = true;

    private final boolean backdrop;

    MemeStatus(Context context) {
        this(context, false);
    }

    MemeStatus(Context context, boolean backdrop) {
        super(context);
        this.backdrop = backdrop;
        if (!backdrop) setBackgroundColor(DeckTheme.KEY);
        image = new ImageView(context);
        image.setScaleType(backdrop ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (backdrop) image.setAlpha(.40f);
        addView(image, new LayoutParams(-1, -1));
        fallback = new TextView(context);
        fallback.setGravity(Gravity.CENTER);
        fallback.setTextSize(23);
        addView(fallback, new LayoutParams(-1, -1));
    }

    static int bundled(Context context, String slot) {
        String metric = slot.substring(0, slot.lastIndexOf('-'));
        switch (metric) {
            case "gpu":
            case "fps":
            case "processes": slot = metric + "-0"; break;
            case "ram":
            case "temperature": slot = metric + "-2"; break;
            default: break;
        }
        return context.getResources().getIdentifier("meme_" + slot.replace('-', '_'), "raw", context.getPackageName());
    }

    void update(String id, String emoji, String message, boolean enabled) {
        boolean reenabled = enabled && !this.enabled;
        this.enabled = enabled;
        if (!backdrop && !emoji.contentEquals(fallback.getText())) fallback.setText(emoji);
        if (!current.equals(id) || reenabled) {
            pause();
            image.setImageDrawable(null);
            animation = null;
            current = id;
            File file = new File(getContext().getFilesDir(), "meme-" + id + ".gif");
            int embedded = bundled(getContext(), id);
            if (enabled && (file.isFile() || embedded != 0)) {
                try {
                    ImageDecoder.Source source = file.isFile() ? ImageDecoder.createSource(file)
                        : ImageDecoder.createSource(getResources(), embedded);
                    Drawable drawable = ImageDecoder.decodeDrawable(source,
                        (decoder, info, origin) -> {
                            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                            float scale = Math.min(1f, (backdrop ? 176f : 112f) / Math.max(width, height));
                            decoder.setTargetSize(Math.max(1, (int)(width * scale)), Math.max(1, (int)(height * scale)));
                        });
                    if (drawable instanceof AnimatedImageDrawable) {
                        animation = (AnimatedImageDrawable) drawable;
                        animation.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                        image.setImageDrawable(animation);
                    }
                } catch (Exception ignored) { }
            }
        }
        fallback.setVisibility(!backdrop && (animation == null || !enabled) ? VISIBLE : INVISIBLE);
        image.setVisibility(enabled && animation != null ? VISIBLE : INVISIBLE);
        setContentDescription(enabled && animation != null ? message + " · GIF animado"
            : backdrop ? null : message + " · emoji");
        if (enabled && motionAllowed && animation != null && isShown()) {
            if (!animation.isRunning()) animation.start();
        }
        else pause();
    }

    void pause() { if (animation != null) animation.stop(); }

    void allowMotion(boolean allowed) {
        motionAllowed = allowed;
        if (!allowed) pause();
    }

    @Override protected void onDetachedFromWindow() {
        pause();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && motionAllowed && enabled && animation != null) animation.start();
        else pause();
    }
}
