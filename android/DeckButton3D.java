package br.com.rian.deck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.widget.TextView;

final class DeckButton3D extends TextView {
    private boolean selectedKey, executing, error, available = true;
    private int tint = DeckTheme.KEY;
    private String label = "";
    private final Paint indicator = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Drawable symbol;
    private int symbolSize;

    DeckButton3D(Context context) { super(context); }

    void symbol(Drawable icon, int size) {
        symbol = icon;
        symbolSize = size;
        invalidate();
    }

    void configure(String value, int color, boolean selected) {
        if (label.equals(value) && tint == color && selectedKey == selected) return;
        label = value;
        tint = color;
        selectedKey = selected;
        render();
    }

    void availability(boolean value) {
        if (available == value) return;
        available = value;
        render();
    }

    void executing() { executing = true; error = false; render(); }

    void complete(boolean success) {
        executing = false;
        error = !success;
        render();
        if (!success) postDelayed(() -> { error = false; render(); }, 1050);
    }

    private void render() {
        String value = executing ? "Executando…" : label;
        if (error) value = "Falha · " + label;
        setContentDescription(value);
        setEnabled(available && !executing);
        setAlpha(available ? 1 : .52f);
        float density = getResources().getDisplayMetrics().density;
        setBackground(new VisualAssets.Keycap(15 * density, density, true, selectedKey || error, tint));
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        int size = Math.min(symbolSize, Math.min(getWidth(), getHeight()) - Math.round(13 * density));
        if (symbol != null && size > 0) {
            int left = (getWidth() - size) / 2;
            int top = (getHeight() - size - Math.round(4 * density)) / 2;
            symbol.setBounds(left, top, left + size, top + size);
            symbol.draw(canvas);
        }
        if (executing || error) {
            indicator.setColor(error ? 0xffff6674 : 0xfff6c87f);
            canvas.drawCircle(getWidth() - 10 * density, 10 * density, 3 * density, indicator);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent motion) {
        if (!isEnabled()) return false;
        switch (motion.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                animate().translationY(getResources().getDisplayMetrics().density)
                    .scaleX(.975f).scaleY(.975f).translationZ(-getElevation()).setDuration(68)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                animate().translationY(0).scaleX(1).scaleY(1).translationZ(0).setDuration(175)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(.65f)).start();
                break;
        }
        return super.onTouchEvent(motion);
    }
}
