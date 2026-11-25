package dev.voxferrum;

import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;

/** Machined brass trim for the forge controls. The interior of the cover is transparent. */
final class ForgePlate extends Drawable {
    enum Finish { IRON, ENAMEL, COVER }

    private final Finish finish;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private LinearGradient enamel;

    ForgePlate(Finish finish, float density) {
        this.finish = finish;
        this.density = density;
    }

    static Drawable artwork(Bitmap bitmap) {
        ShapeDrawable cover = new ShapeDrawable(new RectShape());
        cover.getPaint().setFilterBitmap(true);
        cover.setShaderFactory(new ShapeDrawable.ShaderFactory() {
            @Override public Shader resize(int width, int height) {
                BitmapShader shader = new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
                float scale = Math.max((float)width / bitmap.getWidth(), (float)height / bitmap.getHeight());
                Matrix position = new Matrix();
                position.setScale(scale,scale);
                position.postTranslate((width-bitmap.getWidth()*scale)/2,(height-bitmap.getHeight()*scale)/2);
                shader.setLocalMatrix(position);
                return shader;
            }
        });
        return cover;
    }

    @Override protected void onBoundsChange(Rect bounds) {
        float w = bounds.width() / density, h = bounds.height() / density;
        float cut = finish == Finish.COVER ? 0 : 5;
        outline.reset();
        outline.moveTo(cut + 1, 1);
        outline.lineTo(w-1, 1);
        outline.lineTo(w-1, h-cut-1);
        outline.lineTo(w-cut-1, h-1);
        outline.lineTo(1, h-1);
        outline.lineTo(1, cut+1);
        outline.close();
        if (finish == Finish.ENAMEL && h > 0)
            enamel = new LinearGradient(0,0,0,h,0xffa9382b,0xff79251e,Shader.TileMode.CLAMP);
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float w = b.width()/density, h = b.height()/density;
        c.save();
        c.translate(b.left,b.top);
        c.scale(density,density);
        if (finish != Finish.COVER) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(finish == Finish.IRON ? 0xff211c19 : 0xffffffff);
            paint.setShader(finish == Finish.ENAMEL ? enamel : null);
            c.drawPath(outline,paint);
            paint.setShader(null);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(finish == Finish.IRON ? 0xff705445 : 0xffab8351);
        c.drawPath(outline,paint);
        paint.setColor(0xffe2bd76);
        for (int i = 0; i < 2; i++) {
            c.drawLine(4,3,24,3,paint);
            c.drawLine(3,4,3,13,paint);
            c.rotate(180,w/2,h/2);
        }
        if (finish == Finish.ENAMEL) {
            paint.setColor(0xffbc7561);
            for (int i=0;i<3;i++) {
                c.drawLine(9+i*4,h/2+4,14+i*4,h/2-4,paint);
                c.drawLine(w-9-i*4,h/2-4,w-14-i*4,h/2+4,paint);
            }
        }
        c.restore();
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter filter) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
