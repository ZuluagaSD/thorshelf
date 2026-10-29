package dev.thorshelf.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Original vector parcel art: a little eShop-inspired delight without image assets. */
final class ParcelView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final AppTheme.Palette colors;
    private final Shader halo = new RadialGradient(164, 139, 114,
            0x60FFDF8B, 0x00FFDF8B, Shader.TileMode.CLAMP);
    private final Shader backLeft = new LinearGradient(130, 113, 192, 152,
            0xFFFFCC6A, 0xFFF7A333, Shader.TileMode.CLAMP);
    private final Shader backRight = new LinearGradient(197, 111, 238, 151,
            0xFFFFD47C, 0xFFF5A235, Shader.TileMode.CLAMP);
    private final Shader openingGlow = new LinearGradient(162, 156, 162, 93,
            0xFFFFD877, 0x00FFEAB6, Shader.TileMode.CLAMP);
    private final Shader wallLeft = new LinearGradient(90, 147, 166, 226,
            0xFFF6AA43, 0xFFEF8925, Shader.TileMode.CLAMP);
    private final Shader wallRight = new LinearGradient(249, 145, 165, 227,
            0xFFFFAE3F, 0xFFF18A21, Shader.TileMode.CLAMP);
    private final Shader flapLeft = new LinearGradient(82, 135, 153, 175,
            0xFFFFC66B, 0xFFF09A37, Shader.TileMode.CLAMP);
    private final Shader flapRight = new LinearGradient(191, 160, 264, 150,
            0xFFFFBE59, 0xFFEF8C25, Shader.TileMode.CLAMP);
    private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
    private final ContentObserver motionObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { updateAnimation(); }
    };
    private boolean animate = true;
    private boolean observing;
    private float phase = 0.3f;

    ParcelView(Context context) {
        super(context);
        colors = Ui.colors(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        animator.setDuration(3600);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            phase = (float) value.getAnimatedValue();
            invalidate();
        });
    }

    void setAnimating(boolean enabled) {
        animate = enabled;
        updateAnimation();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            getContext().getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver);
            observing = true;
        } catch (SecurityException ignored) {
            // areAnimatorsEnabled() still honors the system setting when the view becomes visible.
        }
        updateAnimation();
    }

    @Override protected void onDetachedFromWindow() {
        animator.cancel();
        if (observing) {
            getContext().getContentResolver().unregisterContentObserver(motionObserver);
            observing = false;
        }
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateAnimation();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateAnimation();
    }

    private void updateAnimation() {
        // View may dispatch visibility while its superclass constructor is still running.
        if (animator == null) return;
        boolean run = animate && isAttachedToWindow() && isShown()
                && getWindowVisibility() == VISIBLE && ValueAnimator.areAnimatorsEnabled();
        if (run && !animator.isStarted()) animator.start();
        else if (!run && animator.isStarted()) {
            animator.cancel();
            phase = 0.3f;
            invalidate();
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = resolveSize(Ui.dp(getContext(), 300), widthSpec);
        int height = resolveSize(Ui.dp(getContext(), 210), heightSpec);
        setMeasuredDimension(width, height);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth() / 320f, getHeight() / 250f);
        if (scale <= 0) return;
        int saved = canvas.save();
        canvas.translate((getWidth() - 320 * scale) / 2f, (getHeight() - 250 * scale) / 2f);
        canvas.scale(scale, scale);

        // Keep the illustration's surroundings unlit on OLED. The parcel itself
        // retains its original colors, like the other artwork in the app.
        if (!colors.oled) {
            paint.setShader(halo);
            paint.setAlpha(colors.dark ? 110 : 255);
            canvas.drawCircle(164, 139, 114, paint);
            paint.setShader(null);
            paint.setAlpha(255);
            paint.setColor(0x25D99024);
            canvas.drawOval(57, 211, 274, 235, paint);
            paint.setColor(0x19F5AA47);
            canvas.drawOval(79, 216, 254, 230, paint);
        }

        // Subtle floating tiles are part of the illustration, never an indeterminate progress signal.
        tile(canvas, 42, 78, 14, -9, 0x29F5A338);
        tile(canvas, 277, 155, 12, 8, 0x35F5A338);
        tile(canvas, 257, 48, 20, 7, 0x22F5A338);
        for (int i = 0; i < 7; i++) {
            float t = (phase + i / 7f) % 1f;
            float x = 164 + (float) Math.sin(t * 5.4 + i * 0.85) * (21 + t * 46);
            float y = 148 - t * 125;
            float size = 11 + (i % 3) * 6;
            int alpha = Math.round(230 * Math.min(1f, t * 5) * Math.min(1f, (1 - t) * 5));
            int tint = i % 2 == 0 ? Color.rgb(255, 177, 53) : Color.rgb(255, 215, 105);
            tile(canvas, x, y, size, 12 - 26 * t, (tint & 0x00FFFFFF) | (alpha << 24));
        }
        star(canvas, 117, 75, 7, 0xFFFFC65F);
        star(canvas, 228, 96, 9, 0xFFFFCD6A);
        star(canvas, 72, 169, 4, 0xFFFFC65F);

        // Back flap and opening.
        paint.setShader(backLeft);
        polygon(canvas, 94, 142, 111, 109, 188, 97, 175, 128);
        paint.setShader(backRight);
        polygon(canvas, 175, 128, 207, 101, 266, 113, 249, 145);
        paint.setShader(null);
        paint.setColor(0xFFE5972A);
        polygon(canvas, 94, 142, 175, 126, 249, 145, 165, 168);
        paint.setColor(0xFFFFD174);
        polygon(canvas, 108, 144, 174, 132, 231, 145, 165, 161);
        paint.setShader(openingGlow);
        polygon(canvas, 138, 156, 121, 106, 197, 106, 187, 156);

        // Parcel walls.
        paint.setShader(wallLeft);
        polygon(canvas, 94, 143, 165, 166, 165, 227, 94, 208);
        paint.setShader(wallRight);
        polygon(canvas, 165, 166, 249, 145, 249, 207, 165, 227);
        paint.setShader(null);
        paint.setColor(0x55C66D14);
        polygon(canvas, 117, 174, 132, 178, 132, 187, 117, 183);
        polygon(canvas, 117, 198, 132, 202, 132, 218, 117, 214);
        paint.setColor(0x28BF6913);
        polygon(canvas, 164, 166, 167, 166, 167, 226, 164, 227);

        // Front flaps and their cream edges.
        paint.setShader(flapLeft);
        polygon(canvas, 94, 143, 61, 126, 133, 145, 165, 166, 140, 183);
        paint.setShader(flapRight);
        polygon(canvas, 165, 166, 249, 145, 280, 132, 258, 157, 187, 181);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xCCFFE1A2);
        line(canvas, 61, 126, 133, 145, 165, 166);
        line(canvas, 165, 166, 249, 145, 280, 132);
        paint.setStyle(Paint.Style.FILL);

        // A smiling shopping bag stamped on the side of the parcel.
        int badge = canvas.save();
        canvas.translate(204, 184);
        canvas.skew(0, -0.24f);
        paint.setColor(0xFFFFE8BA);
        canvas.drawRoundRect(-14, -7, 15, 22, 5, 5, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3.4f);
        canvas.drawArc(-7, -18, 8, 2, 180, 180, false, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFD07B28);
        canvas.drawCircle(-5, 3, 1.7f, paint);
        canvas.drawCircle(6, 3, 1.7f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.9f);
        canvas.drawArc(-5, 5, 7, 15, 15, 150, false, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restoreToCount(badge);
        canvas.restoreToCount(saved);
    }

    private void polygon(Canvas canvas, float x0, float y0, float x1, float y1,
            float x2, float y2, float x3, float y3) {
        path.reset();
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void polygon(Canvas canvas, float x0, float y0, float x1, float y1,
            float x2, float y2, float x3, float y3, float x4, float y4) {
        path.reset();
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.lineTo(x4, y4);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void line(Canvas canvas, float x0, float y0, float x1, float y1, float x2, float y2) {
        path.reset();
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);
        path.lineTo(x2, y2);
        canvas.drawPath(path, paint);
    }

    private void tile(Canvas canvas, float x, float y, float size, float angle, int color) {
        int saved = canvas.save();
        canvas.rotate(angle, x, y);
        paint.setColor(color);
        canvas.drawRoundRect(x - size / 2, y - size / 2, x + size / 2, y + size / 2, 3, 3, paint);
        canvas.restoreToCount(saved);
    }

    private void star(Canvas canvas, float x, float y, float size, int color) {
        paint.setColor(color);
        path.reset();
        path.moveTo(x, y - size);
        path.lineTo(x + size * 0.3f, y - size * 0.3f);
        path.lineTo(x + size, y);
        path.lineTo(x + size * 0.3f, y + size * 0.3f);
        path.lineTo(x, y + size);
        path.lineTo(x - size * 0.3f, y + size * 0.3f);
        path.lineTo(x - size, y);
        path.lineTo(x - size * 0.3f, y - size * 0.3f);
        path.close();
        canvas.drawPath(path, paint);
    }
}
