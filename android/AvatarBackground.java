package br.com.rian.deck;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

final class AvatarBackground {
    private final Paint paint = new Paint();
    private final Path mountains = new Path();
    private float light = -1;
    private long lastHourCheck;
    private int localHour = 20;

    AvatarBackground() {
        mountains.moveTo(0,185);
        mountains.lineTo(64,112);
        mountains.lineTo(118,175);
        mountains.lineTo(176,96);
        mountains.lineTo(246,169);
        mountains.lineTo(304,116);
        mountains.lineTo(360,174);
        mountains.lineTo(360,224);
        mountains.lineTo(0,224);
        mountains.close();
    }

    void render(Canvas c, long now, int scene, boolean reduced) {
        if (now >= lastHourCheck) {
            localHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            lastHourCheck = now + 30_000L;
        }
        float target = scene == 0 ? (localHour < 6 || localHour >= 19 ? 0 : localHour < 12 ? 1 : .52f)
            : scene == 1 ? 1 : scene == 2 ? .52f : 0;
        light = light < 0 ? target : light + (target - light) * .09f;
        float t = reduced ? 0 : (now % 180_000L) / 1000f;
        box(c, shade(0xff10162c, 0xff315568, light), 0,0,360,220);
        for (int y=72; y<=192; y+=12) {
            float horizon = (y-60)/156f;
            box(c, shade(0xff18213d, shade(0xff75465a,0xff789892,light), horizon), 0,y,360,y+12);
        }
        float sun = 1 - Math.min(1, Math.abs(light-.48f)*2);
        if (light > .15f) {
            for (int y=-25;y<=25;y+=3) {
                int span = (int)Math.sqrt(Math.max(0,676-y*y));
                box(c,shade(0xffcc7660,0xfff0ba76,sun),44-span,126+y,44+span,129+y);
            }
        }
        if (light < .3f) {
            for (int i=0;i<14;i++) {
                int x=(i*59+17)%358,y=8+(i*37)%99;
                int brightness=(int)(125+75*Math.sin(t*.65+i*2));
                box(c,(brightness<<24)|0x00c0d8f7,x,y,x+3,y+3);
            }
            if (!reduced && t>149 && t<169) {
                float x=(t-149)*22-40;
                box(c,0xff73849a,x,40,x+23,44);
                box(c,0xff3c566f,x+4,44,x+16,48);
                box(c,0xffbd946a,x-5,41,x+1,44);
            }
        }
        for (int i=0;i<4;i++) {
            float x=((i*92+t*(1.3f+i*.4f))%420)-45;
            int color=shade(0xff293353,0xff809b9d,light);
            box(c,color,x,74+i*10,x+42,79+i*10);
            box(c,color,x+9,69+i*10,x+31,74+i*10);
        }
        paint.setColor(shade(0xff25365b,0xff56777c,light));
        c.drawPath(mountains,paint);
        for (int i=0;i<15;i++) {
            int x=49+i*3,y=130-(i<6?i:12-i)*3;
            box(c,shade(0xff435370,0xff759397,light),x,y,x+5,y+3);
        }
        box(c,shade(0xff122039,0xff386069,light),0,220,360,300);
        for(int i=0;i<17;i++) {
            int x=i*23,h=9+(i*13)%27;
            box(c,0xff17202d,x,220-h,x+16,222);
            box(c,0xff283345,x+2,218-h,x+13,220-h);
            if (light<.68f) for(int window=0;window<3;window++) {
                if ((int)(t/5+i*7+window*3)%6!=0 && h>6+window*5)
                    box(c,0xffb68462,x+4+(window%2)*5,215-h+window*5,x+7+(window%2)*5,217-h+window*5);
            }
        }
        for(int i=0;i<9;i++) {
            float x=(i*53+t*(1+i*.12f))%373-13;
            box(c,shade(0xff30354c,0xff597c7b,light),x,232+i*7,x+12+i,234+i*7);
        }
        for(int i=0;i<8;i++) {
            float width=16+i*3+(float)Math.sin(t*1.6+i)*5;
            int color=shade(0xff363c56,0xff806361,sun);
            box(c,color,44-width,225+i*4,44+width,227+i*4);
        }
    }

    private void box(Canvas c,int color,float x,float y,float right,float bottom) {
        paint.setColor(color);
        c.drawRect(x,y,right,bottom,paint);
    }

    private static int shade(int from,int to,float amount) {
        int r=(int)(((from>>16)&255)*(1-amount)+((to>>16)&255)*amount);
        int g=(int)(((from>>8)&255)*(1-amount)+((to>>8)&255)*amount);
        int b=(int)((from&255)*(1-amount)+(to&255)*amount);
        return 0xff000000|(r<<16)|(g<<8)|b;
    }
}
