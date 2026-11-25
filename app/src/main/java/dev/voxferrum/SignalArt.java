package dev.voxferrum;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import java.util.Arrays;

/** Live peak history or a hand-drawn emblem; all measurements scale with the view. */
final class SignalArt extends View {
    private final Skin skin;
    private final boolean emblem;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] history = new float[80];
    private int cursor;

    SignalArt(Context context, Skin skin, boolean emblem) {
        super(context);
        this.skin = skin;
        this.emblem = emblem;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void push(float peak) {
        history[cursor] = Math.max(0f, Math.min(1f, peak / 100f));
        cursor = (cursor + 1) % history.length;
        invalidate();
    }

    void resetTrace() {
        Arrays.fill(history, 0f);
        invalidate();
    }

    private void pen(int color, float width) {
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        if (!emblem) {
            float mid = h * .5f;
            pen(skin.line, Math.max(1f, h / 70f));
            for (int i = 0; i <= 8; i++) c.drawLine(w*i/8, h*.08f, w*i/8, h*.92f, p);
            c.drawLine(0, mid, w, mid, p);
            pen(skin.accent, Math.max(1.2f, w / 170f));
            for (int i = 0; i < history.length; i++) {
                float x = w * (i + .5f) / history.length;
                float amp = Math.max(0.9f, history[(cursor+i)%history.length] * h*.47f);
                c.drawLine(x, mid-amp, x, mid+amp, p);
            }
            return;
        }
        c.save();
        float unit = Math.min(w, h) / 106f;
        c.translate(w / 2, h / 2);
        c.scale(unit, unit);
        if (skin.tide) tide(c); else forge(c);
        c.restore();
    }

    private void forge(Canvas c) {
        pen(skin.line, 1);
        c.drawCircle(0, 0, 48, p);
        for (int i=0; i<36; i++) {
            c.drawLine(0,-48,0,i%3==0 ? -42 : -46,p);
            c.rotate(10);
        }
        pen(skin.accent, 1.8f);
        path.reset();
        for (int tooth=0; tooth<12; tooth++) {
            for (int k=0; k<4; k++) {
                double angle = Math.toRadians(tooth*30 + new int[]{0,4,18,22}[k]);
                int r = (k==0 || k==3) ? 31 : 37;
                float x = (float)Math.cos(angle)*r, y = (float)Math.sin(angle)*r;
                if (tooth==0 && k==0) path.moveTo(x,y); else path.lineTo(x,y);
            }
        }
        path.close(); c.drawPath(path,p);
        p.setColor(skin.plate); p.setStyle(Paint.Style.FILL);
        c.drawCircle(0, 0, 27, p);
        pen(skin.accent, 1.5f);
        c.drawCircle(0,0,26,p);

        // Split metal mask, with an ocular lens and three grille slots.
        path.reset(); path.moveTo(-18,-8); path.quadTo(-15,-22,0,-22);
        path.quadTo(19,-21,19,-5); path.lineTo(17,5); path.lineTo(11,10);
        path.lineTo(9,18); path.lineTo(-7,18); path.lineTo(-10,10);
        path.lineTo(-17,5); path.close();
        c.drawPath(path,p);
        c.drawLine(0,-21,0,4,p);
        path.reset(); path.moveTo(-13,-4); path.lineTo(-3,-1); path.lineTo(-6,4);
        path.lineTo(-13,1); path.close();
        p.setStyle(Paint.Style.FILL); c.drawPath(path,p);
        pen(skin.accent,1.6f);
        c.drawCircle(9,-2,5,p); c.drawCircle(9,-2,2,p);
        for (int x=-5;x<=5;x+=5) c.drawLine(x,11,x,16,p);
    }

    private void tide(Canvas c) {
        pen(skin.line, 1.3f);
        c.drawCircle(0,0,44,p);
        c.drawCircle(0,0,39,p);
        pen(skin.accent,2.1f);
        c.drawArc(new RectF(-43,-43,43,43),210,87,false,p);
        for (int i=0;i<5;i++) {
            float y = -13 + i*7;
            path.reset(); path.moveTo(-30,y);
            path.cubicTo(-7,y-20,6,y+20,30,y);
            c.drawPath(path,p);
        }
        p.setStyle(Paint.Style.FILL); c.drawCircle(14,-24,4,p);
    }
}
