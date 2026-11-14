package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Lightweight native drawing: no blur, offscreen snapshots or continuously running effects. */
final class WeberinUi {
    static final int WHITE = 0xfff5f5f7, MUTED = 0xffb3bac7;
    private static final android.graphics.Typeface[] FONTS = new android.graphics.Typeface[10];
    private WeberinUi() { }

    static android.graphics.Typeface font(Context context, int weight) {
        int index = Math.max(1, Math.min(9, weight / 100));
        if (FONTS[index] == null) FONTS[index] = new android.graphics.Typeface.Builder(context.getAssets(), "weberin/inter.ttf")
                .setWeight(index * 100).setFontVariationSettings("'opsz' 14, 'wght' " + index * 100).build();
        return FONTS[index];
    }

    static TextView text(Context context, CharSequence text, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setFontFeatureSettings("kern");
        view.setTypeface(font(context, bold ? 600 : 400));
        return view;
    }

    static GradientDrawable round(int fill, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radius);
        if (border != 0) shape.setStroke(1, border);
        return shape;
    }

    static void put(FrameLayout parent, View child, int x, int y, int width, int height) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = x;
        params.topMargin = y;
        parent.addView(child, params);
    }

    static void capitalizeDate(TextView date) {
        date.setTransformationMethod(new android.text.method.TransformationMethod() {
            @Override public CharSequence getTransformation(CharSequence source, View view) {
                if (android.text.TextUtils.isEmpty(source)) return source;
                return source.subSequence(0, 1).toString().toUpperCase(java.util.Locale.getDefault())
                        + source.subSequence(1, source.length());
            }
            @Override public void onFocusChanged(View view, CharSequence source, boolean focused,
                    int direction, android.graphics.Rect previous) { }
        });
    }

    static final class Viewport extends FrameLayout {
        private final View scene;
        Viewport(Context context, View scene) {
            super(context);
            this.scene = scene;
            setBackgroundColor(Color.BLACK);
            setClipChildren(false);
            scene.setPivotX(0); scene.setPivotY(0);
            addView(scene, new FrameLayout.LayoutParams(WeberinGeometry.WIDTH, WeberinGeometry.HEIGHT));
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float scale = WeberinGeometry.scale(w, h);
            scene.setScaleX(scale); scene.setScaleY(scale);
            scene.setTranslationX((w - WeberinGeometry.WIDTH * scale) / 2);
            scene.setTranslationY((h - WeberinGeometry.HEIGHT * scale) / 2);
        }
    }

    /** A held OK opens its menu once and never launches the app on release. */
    static final class CardButton extends FrameLayout {
        private final Runnable hold = () -> {
            if (isPressed() && hasFocus()) {
                setPressed(false);
                performLongClick();
            }
        };
        CardButton(Context context) { super(context); }
        private boolean confirm(int key) {
            return key == android.view.KeyEvent.KEYCODE_DPAD_CENTER || key == android.view.KeyEvent.KEYCODE_ENTER
                    || key == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER;
        }
        @Override public boolean onKeyDown(int key, android.view.KeyEvent event) {
            if (!confirm(key)) return super.onKeyDown(key, event);
            if (event.getRepeatCount() == 0) {
                setPressed(true); event.startTracking();
                postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout());
            }
            return true;
        }
        @Override public boolean onKeyLongPress(int key, android.view.KeyEvent event) {
            if (!confirm(key)) return super.onKeyLongPress(key, event);
            removeCallbacks(hold); hold.run(); return true;
        }
        @Override public boolean onKeyUp(int key, android.view.KeyEvent event) {
            if (!confirm(key)) return super.onKeyUp(key, event);
            removeCallbacks(hold); boolean click = isPressed() && !event.isCanceled(); setPressed(false);
            if (click) performClick();
            return true;
        }
        @Override protected void onFocusChanged(boolean focus, int direction, android.graphics.Rect previous) {
            if (!focus) { removeCallbacks(hold); setPressed(false); }
            super.onFocusChanged(focus, direction, previous);
        }
        @Override protected void onDetachedFromWindow() { removeCallbacks(hold); super.onDetachedFromWindow(); }
    }

    static final class Surface extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path clip = new Path();
        private final boolean tinted;
        private final int opacity;
        Surface(boolean tinted, int opacity) { this.tinted = tinted; this.opacity = Math.max(0, Math.min(255, opacity)); }
        @Override public void draw(Canvas canvas) {
            rect.set(getBounds()); rect.inset(.7f, .7f);
            paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setColor(opacity << 24);
            canvas.drawRoundRect(rect, 32, 32, paint);
            if (tinted) {
                canvas.save(); clip.reset(); clip.addRoundRect(rect, 32, 32, Path.Direction.CW); canvas.clipPath(clip);
                paint.setShader(new RadialGradient(rect.left + rect.width() * .12f, rect.bottom,
                        rect.width() * .52f, 0x483e1c32, Color.TRANSPARENT, Shader.TileMode.CLAMP));
                canvas.drawRect(rect, paint);
                paint.setShader(new RadialGradient(rect.right, rect.top, rect.width() * .58f,
                        0x5036274b, Color.TRANSPARENT, Shader.TileMode.CLAMP));
                canvas.drawRect(rect, paint); canvas.restore(); paint.setShader(null);
            }
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.2f); paint.setColor(0x705f6063);
            canvas.drawRoundRect(rect, 32, 32, paint); paint.setStyle(Paint.Style.FILL);
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Six rounded squares in the supplied design, rather than the original nine-square icon. */
    static final class AppsIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        AppsIcon(Context context) { super(context); paint.setColor(0xffcbd1dc); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            for (int y = 0; y < 2; y++) for (int x = 0; x < 3; x++)
                canvas.drawRoundRect(x * 20, y * 20, x * 20 + 14, y * 20 + 14, 3, 3, paint);
        }
    }

    static final class XboxIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        XboxIcon(Context context) { super(context); paint.setColor(0xff202023); paint.setStrokeWidth(1.3f); paint.setStyle(Paint.Style.STROKE); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.drawCircle(16, 16, 15, paint);
            Path left = new Path(); left.moveTo(5, 27); left.cubicTo(7, 13, 18, 5, 25, 4);
            left.moveTo(27, 27); left.cubicTo(25, 13, 14, 5, 7, 4); canvas.drawPath(left, paint);
            Path middle = new Path(); middle.moveTo(4, 25); middle.cubicTo(9, 19, 13, 12, 16, 10);
            middle.cubicTo(20, 14, 24, 20, 28, 25); middle.moveTo(9, 3); middle.quadTo(16, 8, 23, 3); canvas.drawPath(middle, paint);
        }
    }

    static final class WeatherIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int condition;
        WeatherIcon(Context context, int condition) { super(context); this.condition = condition; }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            if (condition == 0) {
                paint.setColor(0xfff3d393); c.drawCircle(32, 28, 15, paint);
                paint.setStrokeWidth(3);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    c.drawLine(32 + (float) Math.cos(a) * 20, 28 + (float) Math.sin(a) * 20,
                            32 + (float) Math.cos(a) * 26, 28 + (float) Math.sin(a) * 26, paint);
                }
                return;
            }
            paint.setColor(0xffa9c8d8); c.drawCircle(44, 22, 11, paint); c.drawCircle(51, 29, 10, paint);
            paint.setColor(0xffe5eff3); c.drawCircle(28, 27, 13, paint); c.drawCircle(16, 34, 10, paint);
            c.drawCircle(41, 34, 11, paint); c.drawRoundRect(15, 30, 43, 44, 6, 6, paint);
            if (condition >= 2) {
                paint.setStrokeWidth(3); paint.setColor(0xffaac9db);
                if (condition == 2) { c.drawLine(8, 50, 54, 50, paint); c.drawLine(17, 57, 49, 57, paint); }
                else if (condition == 4) { for (int x = 17; x < 52; x += 14) c.drawCircle(x, 54, 3, paint); }
                else if (condition == 5) {
                    paint.setColor(0xfff1d37c); Path bolt = new Path(); bolt.moveTo(34, 43); bolt.lineTo(24, 55);
                    bolt.lineTo(32, 55); bolt.lineTo(27, 65); bolt.lineTo(44, 49); bolt.lineTo(35, 49); c.drawPath(bolt, paint);
                } else for (int x = 17; x < 52; x += 14) c.drawLine(x + 3, 49, x, 56, paint);
            }
        }
    }
}
