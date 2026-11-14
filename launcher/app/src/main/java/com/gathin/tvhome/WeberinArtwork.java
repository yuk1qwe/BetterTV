package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.util.Locale;

/** The owner's monochrome card treatment. Only installed applications get cards. */
final class WeberinArtwork extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String name;
    private final int kind;

    WeberinArtwork(Context context, String pkg, String label) {
        super(context);
        name = label;
        String lower = label.toLowerCase(Locale.ROOT);
        kind = pkg.equals("com.google.android.youtube.tv") || pkg.equals("com.google.android.youtube") ? 1
                : lower.equals("happ") || pkg.startsWith("com.happproxy") ? 2
                : pkg.startsWith("ru.kinopoisk") ? 3
                : pkg.startsWith("ru.ivi") ? 4
                : pkg.startsWith("ru.okko") ? 5 : 0;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save(); canvas.scale(getWidth() / 270f, getHeight() / 156f);
        paint.setColor(kind == 1 ? 0xfff5f5f5 : kind == 2 ? 0xff292929 : 0x660e0e0e);
        canvas.drawRoundRect(0, 0, 270, 156, 20, 20, paint);
        if (kind == 1) {
            paint.setTypeface(WeberinUi.font(getContext(), 700)); paint.setTextSize(26);
            float width = 38 + 10 + paint.measureText("YouTube");
            float x = (270 - width) / 2;
            paint.setColor(0xffff0033); canvas.drawRoundRect(x, 65, x + 38, 92, 8, 8, paint);
            paint.setColor(Color.WHITE); Path play = new Path(); play.moveTo(x + 15, 72);
            play.lineTo(x + 15, 85); play.lineTo(x + 25, 78.5f); play.close(); canvas.drawPath(play, paint);
            paint.setColor(0xff101010); canvas.drawText("YouTube", x + 48, 88, paint);
        } else if (kind == 2) {
            paint.setColor(Color.WHITE);
            // Slashed H from the supplied artwork, drawn as vectors at every display resolution.
            Path h = new Path(); h.moveTo(104, 25); h.lineTo(128, 25); h.lineTo(119, 70);
            h.lineTo(148, 70); h.lineTo(154, 25); h.lineTo(177, 25); h.lineTo(163, 131);
            h.lineTo(140, 131); h.lineTo(146, 87); h.lineTo(120, 87); h.lineTo(114, 131);
            h.lineTo(92, 131); h.close(); canvas.drawPath(h, paint);
            paint.setColor(0xff292929); paint.setStrokeWidth(3);
            canvas.drawLine(93, 64, 131, 28, paint); canvas.drawLine(141, 64, 179, 28, paint);
            canvas.drawLine(90, 124, 128, 88, paint); canvas.drawLine(137, 124, 175, 88, paint);
            canvas.drawLine(110, 99, 148, 62, paint);
        } else {
            String title = kind == 3 ? "Кинопоиск" : kind == 4 ? "Иви" : kind == 5 ? "okko" : name;
            paint.setTypeface(WeberinUi.font(getContext(), 700));
            paint.setTextSize(kind == 4 ? 42 : kind == 5 ? 44 : 26);
            while (paint.measureText(title) > 242 && paint.getTextSize() > 17) paint.setTextSize(paint.getTextSize() - 1);
            paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            canvas.drawText(title, 135, 78 - (metrics.ascent + metrics.descent) / 2, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }
        canvas.restore();
    }
}
