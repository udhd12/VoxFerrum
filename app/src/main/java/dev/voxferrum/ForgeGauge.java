package dev.voxferrum;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

final class ForgeGauge extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean live, seal;
    private float fill;
    ForgeGauge(Context c){super(c); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void setState(boolean active,float fraction,long savedAt){
        boolean stamped=savedAt>0 && SystemClock.elapsedRealtime()-savedAt<1800;
        if(active!=live || fraction!=fill || seal!=stamped){
            live=active; fill=fraction; seal=stamped; invalidate();
        }
    }
    @Override protected void onDraw(Canvas c){
        if(!live && !seal) return;
        // Same center crop and artwork coordinates as ForgePlate.artwork.
        float scale=Math.max(getWidth()/1400f,getHeight()/560f);
        c.save(); c.translate((getWidth()-1400*scale)/2,(getHeight()-560*scale)/2); c.scale(scale,scale);
        if(live){
            paint.setStyle(Paint.Style.FILL); paint.setColor(0x80e3331d); c.drawCircle(1084,187,28,paint);
            paint.setColor(0xffe92c1d); c.drawCircle(1084,187,16,paint);
            paint.setColor(0xffffbca5); c.drawCircle(1080,182,6,paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(7); paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xff514133); c.drawArc(746,-111,1370,513,143,-112,false,paint);
            paint.setColor(0xffffcc79); c.drawArc(746,-111,1370,513,143,-112*fill,false,paint);
        }
        if(seal){
            c.translate(1280,444); c.rotate(-12);
            Path wax=new Path();
            for(int i=0;i<72;i++){
                double a=Math.PI*2*i/72; double r=76+4*Math.cos(a*9);
                float x=(float)(r*Math.cos(a)),y=(float)(r*Math.sin(a));
                if(i==0) wax.moveTo(x,y); else wax.lineTo(x,y);
            }
            wax.close(); paint.setStyle(Paint.Style.FILL);paint.setColor(0xff952f25);c.drawPath(wax,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(4);paint.setColor(0xffefba85);c.drawCircle(0,0,59,paint);
            paint.setStrokeWidth(8);c.drawLine(-29,0,-8,22,paint);c.drawLine(-8,22,31,-25,paint);
        }
        c.restore();
    }
}
