package dev.voxferrum;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import java.io.*;
import java.util.List;

final class ClipWave extends View {
    interface Change { void boundary(boolean start,int ms); }
    private final Skin skin;
    private final int duration;
    private final List<Integer> marks;
    private final Change change;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] peaks;
    private int from,to,position=-1;
    private boolean dragStart;
    ClipWave(Context c,Skin theme,int ms,List<Integer> marks,Change change){
        super(c); skin=theme; duration=Math.max(ms,1); this.marks=marks; this.change=change;
        to=duration; setContentDescription("Звуковая волна и метки. Для точного выбора используйте ползунки ниже.");
    }
    static float[] read(Context c,android.net.Uri uri) throws IOException {
        try(InputStream raw=c.getContentResolver().openInputStream(uri)){
            if(raw==null) throw new IOException("Файл недоступен");
            DataInputStream in=new DataInputStream(new BufferedInputStream(raw));
            long bytes=TelegramAudio.readHeader(in);
            float[] values=new float[512]; byte[] chunk=new byte[65536]; long offset=0;
            while(offset<bytes){
                if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                int n=(int)Math.min(chunk.length,bytes-offset); in.readFully(chunk,0,n);
                // Work in peak bins. Keep both channels and each audio frame.
                for(int i=0;i<n;){
                    int bin=(int)((offset+i)*values.length/bytes);
                    long nextFrame=(((long)(bin+1)*(bytes/4))+values.length-1)/values.length;
                    int end=(int)Math.min(n,nextFrame*4-offset);
                    int peak=0;
                    while(i<end){
                        int sample=(short)((chunk[i]&255)|(chunk[i+1]<<8));
                        peak=Math.max(peak,Math.abs(sample)); i+=2;
                    }
                    values[bin]=Math.max(values[bin],peak/32768f);
                }
                offset+=n;
            }
            return values;
        }
    }
    void setPeaks(float[] values){peaks=values; invalidate();}
    void range(int start,int end){from=start; to=end; invalidate();}
    void cursor(int ms){position=ms;invalidate();}
    private float x(int ms){return 6+(getWidth()-12)*(ms/(float)duration);}
    @Override protected void onDraw(Canvas c){
        float w=getWidth(),h=getHeight(); c.drawColor(skin.panel);
        paint.setColor(skin.plate);c.drawRect(x(from),0,x(to),h,paint);
        paint.setStrokeWidth(Math.max(1,w/512));paint.setColor(skin.muted);
        if(peaks!=null) for(int i=0;i<peaks.length;i++){
            float px=6+(w-12)*i/peaks.length;
            float amp=Math.max(1,(float)Math.sqrt(peaks[i])*h*.39f);
            paint.setColor(px>=x(from)&&px<=x(to)?skin.accent:skin.muted);
            c.drawLine(px,h/2-amp,px,h/2+amp,paint);
        }
        paint.setColor(skin.muted);c.drawLine(6,h/2,w-6,h/2,paint);
        for(int mark:marks){
            float px=x(mark);paint.setColor(skin.accent);paint.setStrokeWidth(1);
            c.drawLine(px,0,px,h,paint);c.drawCircle(px,7,4,paint);
        }
        paint.setStrokeWidth(4);paint.setColor(skin.accent);
        for(int edge:new int[]{from,to}) c.drawLine(x(edge),0,x(edge),h,paint);
        if(position>=0){paint.setColor(skin.ink);c.drawLine(x(position),0,x(position),h,paint);}
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            dragStart=Math.abs(e.getX()-x(from))<Math.abs(e.getX()-x(to));
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP){
            change.boundary(dragStart, Math.round((e.getX()-6)*duration/Math.max(1,getWidth()-12)));
        }
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL){
            getParent().requestDisallowInterceptTouchEvent(false);
            if(action==MotionEvent.ACTION_UP)performClick();
        }
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
