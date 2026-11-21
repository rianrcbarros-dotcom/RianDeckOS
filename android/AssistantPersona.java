package br.com.rian.deck;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.os.PowerManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Path;
import android.graphics.Picture;
import android.graphics.RectF;
import android.view.View;

/** Lightweight native 2D rig with independent torso, head, eyes and mouth. */
final class AssistantPersona extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final RectF oval = new RectF();
    private boolean running;
    private int state, level;
    private float mouth;
    private static final int[] COAT = {0xff294c85,0xff294c85,0xff294c85,0xff294c85,0xff294c85,0xff294c85,0xff294c85,0xff294c85,0xff294c85};
    private static final int[] TRIM = {0xff718bc2,0xff6693d8,0xff79bec5,0xffe36b69,0xffb4bb81,0xffb88b5d,0xffaaa0cc,0xff688ac4,0xffc05869};
    private final Picture[] wardrobe = new Picture[COAT.length + 1];
    private final AvatarAnimationScheduler scheduler = new AvatarAnimationScheduler();
    private final AvatarBackground background = new AvatarBackground();
    private final Bitmap pixels = Bitmap.createBitmap(216, 180, Bitmap.Config.ARGB_8888);
    private final Canvas pixelCanvas = new Canvas(pixels);
    private final Paint pixelPaint = new Paint();
    private final Rect target = new Rect();
    private final Bitmap landscape = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888);
    private final Canvas landscapeCanvas = new Canvas(landscape);
    private final Paint fade = new Paint();
    private final Paint edge = new Paint();
    private final Rect landscapeBounds = new Rect();
    private final Picture head = new Picture();
    private boolean wide;
    private long lastBackground;
    private float visualTilt;

    void wideStage() { wide = true; }

    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
        landscapeBounds.set(0,0,w,h);
        if (w > 0) fade.setShader(new LinearGradient(0,0,w,0,
            new int[]{0x15000000,0x26000000,0xed000000,0xff000000},
            new float[]{0,.30f,.55f,.88f},Shader.TileMode.CLAMP));
        if (h > 0) edge.setShader(new LinearGradient(0,0,0,h,
            new int[]{0xa3000000,0x00000000,0x00000000,0x52000000},
            new float[]{0,.16f,.91f,1},Shader.TileMode.CLAMP));
    }

    private void drawLandscape(Canvas output,long now) {
        if (lastBackground == 0 || now-lastBackground >= (reduced ? 250 : economy ? 125 : 65)) {
            landscapeCanvas.save();
            landscapeCanvas.scale(320f/360f,120f/300f);
            background.render(landscapeCanvas,now,settings.getInt("avatar-background",0),reduced);
            landscapeCanvas.restore();
            lastBackground=now;
        }
        output.drawBitmap(landscape,null,landscapeBounds,pixelPaint);
        if (wide) output.drawRect(0,0,getWidth(),getHeight(),fade);
        output.drawRect(0,0,getWidth(),getHeight(),edge);
    }
    private final SharedPreferences settings;
    private final PowerManager power;
    private int scene = -1, outfit = -1, oldOutfit = -1;
    private long sceneStart, reactionUntil, levelAt, lastDraw;
    private long nextPowerCheck;
    private boolean reduced;
    private boolean economy;
    private int reaction;

    private void updateScene(long now) {
        if (!running || state != 0 || now < reactionUntil) return;
        scheduler.update(now, settings.getInt("avatar-idle-seconds",60), settings.getInt("avatar-scene-seconds",120));
        int active = scheduler.activity;
        if (active != scene && active >= 0) {
            oldOutfit = outfit;
            outfit = active;
            sceneStart = scheduler.started;
        }
        if (scene >= 0 && active < 0) outfit = -1;
        scene = active;
    }

    void reaction(boolean success) {
        scene = -1;
        outfit = -1;
        scheduler.reset();
        reaction = success ? 1 : -1;
        reactionUntil = android.os.SystemClock.uptimeMillis() + 1500;
        invalidate();
    }

    private void line(Canvas c, int color, float width, float a, float b, float x, float y) {
        paint.setColor(color);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(a,b,x,y,paint);
    }

    private void rect(Canvas c,int color,float a,float b,float x,float y,float radius) {
        paint.setColor(color);
        c.drawRoundRect(a,b,x,y,radius,radius,paint);
    }

    private void sceneBackdrop(Canvas c, float seconds, float blend) {
        int alpha = Math.round(255 * blend);
        if (alpha == 0) return;
        if (scene == 4) {
            if (alpha < 255) c.saveLayerAlpha(0, 145, 360, 300, alpha);
            shape.reset(); shape.moveTo(4,266); shape.lineTo(47,199); shape.lineTo(70,219);
            shape.lineTo(108,156); shape.lineTo(167,254); shape.lineTo(245,183);
            shape.lineTo(278,219); shape.lineTo(309,172); shape.lineTo(356,251);
            shape.lineTo(356,294); shape.lineTo(4,294); shape.close();
            paint.setColor(0xff242925); c.drawPath(shape,paint);
            line(c,0xff64674d,3,99,173,108,156);
            line(c,0xff64674d,3,108,156,119,175);
            line(c,0xff77715c,2,106,293,270,289);
            if (alpha < 255) c.restore();
        } else if (scene == 3) {
            if (alpha < 255) c.saveLayerAlpha(0, 213, 360, 300, alpha);
            rect(c,0xff2e3137,30,282,330,297,5);
            line(c,0xff43474d,4,287,278,305,231);
            rect(c,0xff484b50,288,224,331,237,4);
            rect(c,0xffb2464b,307,228,323,232,2);
            for(int i=0;i<7;i++){
                float x=45 + (i*38 + seconds*88)%272;
                line(c,0xff773a3d,2,x,288,x+8,288);
            }
            if (alpha < 255) c.restore();
        }
    }

    private static float smooth(float value) {
        float t = Math.max(0, Math.min(1, value));
        return t*t*(3-2*t);
    }

    private void hand(Canvas c, float x, float y, float angle, boolean left, boolean grip) {
        c.save();
        c.translate(x,y);
        c.rotate(angle);
        if (left) c.scale(-1,1);
        c.scale(.72f,.76f);
        // A compact mitten silhouette keeps the glove proportional to the head and sleeve.
        fill(c,0xffbd9083, -6,7, -9,3,-10,-3,-11,-9, -11,-16,-5,-19,1,-18,
            7,-18,10,-12,10,-7, 14,-10,17,-5,13,-1, 10,3,10,7,6,10,
            2,11,-3,9,-6,7);
        if (grip) {
            fill(c,0xffdfb9a7, -7,-9, -8,-18,4,-19,7,-12, 9,-7,6,-3,2,-3,
                -3,-2,-6,-5,-7,-9);
            line(c,0xffa8796d,1.2f,-5,-7,3,-6);
            line(c,0xffedc8b5,1.1f,-6,-11,0,-13);
        } else {
            fill(c,0xffdfb9a7, -8,-9, -9,-17,-2,-19,4,-17, 8,-15,8,-9,7,-5,
                5,-1,-3,-1,-8,-9);
            line(c,0xffcd9f8d,1.15f,1,-15,1,-6);
        }
        c.restore();
    }

    private void arm(Canvas c, int sleeve, float sx, float sy, float ex, float ey, float hx, float hy) {
        line(c,0xff11131a,22,sx,sy,ex,ey);
        line(c,sleeve,17,sx,sy,ex,ey);
        line(c,0xffb78b7f,11,ex,ey,hx,hy);
        line(c,0xffd7ae9d,7,ex,ey,hx,hy);
    }

    private void drink(Canvas c,float t,float reach) {
        int sleeve=COAT[outfit];
        // Pivot the rim at the lips during the sip, so the container never slides past the face.
        float sip = smooth((t-1.25f)/.45f) * smooth((5.05f-t)/.55f);
        float x=242-49*reach, y=230-64*reach;
        float tilt=(scene==0?-32:scene==1?-44:-54)*reach
            + sip * ((float)Math.sin(t*3.1f)*4f-3f);
        double radians=Math.toRadians(tilt);
        float hx=x-(float)Math.sin(radians)*23+8;
        float hy=y+(float)Math.cos(radians)*23;
        arm(c,sleeve,258,228,267-26*reach,262-34*reach,hx,hy);
        c.save();
        c.translate(x,y);
        c.rotate(tilt);
        if(scene==0){
            paint.setColor(0xffb68263); c.drawOval(9,9,29,29,paint);
            paint.setColor(0xff261a18); c.drawOval(12,12,25,26,paint);
            rect(c,0xffede0c8,-13,0,15,33,6);
            paint.setColor(0xff5c392b); c.drawOval(-12,-3,14,4,paint);
            line(c,0xffd9aa75,1,-7,0,5,1);
            if(reach<.65f)for(int i=0;i<3;i++){
                float rise=(t*12+i*10)%26;
                float sx=-8+i*8+(float)Math.sin(t*4+i)*3;
                line(c,0xff9b7c6e,1.7f,sx,-7-rise,sx+3,-14-rise);
            }
        } else if (scene==1) {
            rect(c,0xffb53745,-11,0,13,37,4);
            rect(c,0xffcec7bc,-11,-2,13,4,2);
            rect(c,0xff867a73,-11,33,13,37,2);
            line(c,0xfff3cfc4,3,-7,24,8,11);
        } else {
            rect(c,0xffb4dfe4,-5,-1,7,8,2);
            rect(c,0xff397e98,-10,7,12,40,5);
            rect(c,0xff6bc7da,-8,17,10,36,3);
            line(c,0xffb8e7eb,1,-7,17,8,18);
            line(c,0xffb1e5ed,1.5f,-5,9,-5,25);
        }
        c.restore();
        hand(c,hx,hy,tilt+68,false,true);
    }

    private void stride(Canvas c,float time,float blend) {
        int opacity = (int)(255 * blend);
        if (opacity <= 0) return;
        if (opacity < 255) c.saveLayerAlpha(75, 191, 285, 296, opacity);
        for (int i=0;i<2;i++) {
            double phase=time*9.2+i*Math.PI;
            float stride=(float)Math.sin(phase) * blend;
            float lift=Math.max(0,(float)Math.cos(phase));
            float hip=166+i*20, knee=hip+28*stride, foot=hip+55*stride;
            float kneeY=248-15*lift, footY=281-30*lift;
            int color=i==0?0xff252731:0xff383c47;
            line(c,0xff101118,23,hip,205,knee,kneeY);
            line(c,color,19,hip,205,knee,kneeY);
            line(c,color,15,knee,kneeY,foot,footY);
            line(c,0xffd3d5cc,8,foot-4,footY,foot+15,footY);
            line(c,TRIM[outfit],3,foot-3,footY+4,foot+15,footY+4);
        }
        if (opacity < 255) c.restore();
    }

    private void sceneForeground(Canvas c,float t,float lift,float gait) {
        int sleeve=COAT[outfit < 0 ? 0 : outfit];
        if (scene >= 0 && scene <= 2) drink(c,t,lift);
        else if (scene==3 || scene==4) {
            float l=132+gait*35,r=222-gait*35;
            arm(c,sleeve,84,235,103+gait*14,268,l,250-gait*15);
            arm(c,sleeve,276,235,255-gait*14,268,r,250+gait*15);
            hand(c,l,250-gait*15,62,true,true);
            hand(c,r,250+gait*15,-62,false,true);
        } else if(scene==5) {
            rect(c,0xff35525c,62,270,298,296,6);
            paint.setColor(0xff243b45); c.drawOval(98,252,263,287,paint);
            float turnToTap=smooth((t-2.8f)/.65f);
            boolean rinse=turnToTap>.85f;
            float x=175+35*turnToTap, y=251-5*turnToTap;
            float sweep=(float)Math.sin(t*10)*14;
            arm(c,sleeve,92,227,113,264,x-25,y+7);
            hand(c,x-25,y+7,-58,true,true);
            paint.setColor(0xffdce5da); c.drawOval(x-29,y-18,x+29,y+18,paint);
            paint.setColor(0xff93a8a6); c.drawOval(x-21,y-12,x+21,y+12,paint);
            float handX=rinse ? x+21 : x+sweep;
            float handY=rinse ? y+7 : y-6+(float)Math.cos(t*10)*4;
            arm(c,sleeve,271,225,253,262,handX,handY);
            if(!rinse) rect(c,0xffdbb45a,handX-9,handY-4,handX+10,handY+7,3);
            hand(c,handX,handY,-87,false,true);
            line(c,0xff879b9c,6,255,259,255,214);
            line(c,0xff879b9c,6,255,214,216,214);
            if(rinse){
                line(c,0xff68bed7,2.7f,216,220,214,y+6);
                for(int i=0;i<6;i++){
                    float h=(t*45+i*6)%20;
                    paint.setColor(0xff84c3d1); c.drawCircle(x-14+i*5,260+h,2,paint);
                }
            }else for(int i=0;i<7;i++){
                float bubble=(t*23+i*6)%29;
                paint.setColor(0xff8ebcc4); c.drawCircle(x-20+i*6,248-bubble,2+i%3,paint);
            }
        } else if (scene == 7 || scene == 8) {
            deskAction(c, t, lift);
        } else if(scene==6) {
            float x=248-63*lift,y=235-65*lift;
            arm(c,sleeve,263,235,247-13*lift,254-18*lift,x,y);
            hand(c,x,y,-74,false,false);
        }
    }

    private void deskAction(Canvas c, float elapsed, float reach) {
        float press = (float)Math.sin(elapsed * (scene == 7 ? 19 : 12)) * 3 * reach;
        c.save();
        c.translate(0, 65*(1-reach));
        if (scene == 7) {
            rect(c,0xff5b6e97,123,235,237,284,4);
            rect(c,0xff27364c,126,238,234,281,3);
            line(c,0xff91acd5,3,177,251,169,259);
            line(c,0xff91acd5,3,169,259,177,266);
            line(c,0xff91acd5,3,187,251,195,259);
            line(c,0xff91acd5,3,195,259,187,266);
            rect(c,0xff657ba4,115,281,245,289,3);
        } else {
            line(c,0xff313249,7,113,94,116,137);
            line(c,0xff313249,7,252,94,249,137);
            rect(c,0xffa64f65,107,112,124,143,4);
            rect(c,0xffa64f65,242,112,256,143,4);
            rect(c,0xff272e48,137,251,224,281,11);
            rect(c,0xff7085b3,148,262,170,267,1);
            rect(c,0xff7085b3,156,254,161,275,1);
            rect(c,0xffda7b7e,205,257,211,264,2);
        }
        float y = scene == 7 ? 285 : 266;
        arm(c,COAT[outfit],94,236,111,264,141+press,y);
        arm(c,COAT[outfit],270,236,252,263,220-press,y);
        hand(c,141+press,y,-65,true,true);
        hand(c,220-press,y,65,false,true);
        c.restore();
    }

    AssistantPersona(Context context, SharedPreferences settings) {
        super(context);
        this.settings = settings;
        power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        pixelPaint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        recordHead();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void animateWhileVisible(boolean active) {
        running = active;
        if (!active) { scene = outfit = -1; scheduler.reset(); }
        invalidate();
    }
    void state(int next) {
        if (next != state) {
            state = next;
            level = 0;
            scene = outfit = -1;
            scheduler.reset();
            invalidate();
        }
    }
    void level(int value) { level = value; levelAt = android.os.SystemClock.uptimeMillis(); }

    private void fill(Canvas c, int color, float... points) {
        shape.reset();
        shape.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 6)
            shape.cubicTo(points[i], points[i+1], points[i+2], points[i+3], points[i+4], points[i+5]);
        shape.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawPath(shape, paint);
    }
    private void eye(Canvas c, float x, float y, float blink, float glance) {
        paint.setColor(0xff26232b);
        c.drawOval(x-15, y-4*blink, x+15, y+6*blink, paint);
        if (blink > .3f) {
            paint.setColor(0xffe9e0d8);
            c.drawOval(x-12, y-3*blink, x+12, y+4*blink, paint);
            paint.setColor(0xff9c4348);
            c.drawOval(x-4+glance, y-3*blink, x+4+glance, y+4*blink, paint);
            paint.setColor(0xff19151a);
            c.drawCircle(x+glance, y, 2.1f, paint);
        }
    }

    private Picture garment(int style) {
        int index = style + 1;
        if (wardrobe[index] != null) return wardrobe[index];
        Picture picture = new Picture();
        Canvas c = picture.beginRecording(360, 300);
        int color = style < 0 ? 0xff294c85 : COAT[style];
        int trim = style < 0 ? 0xff5878ae : TRIM[style];
        fill(c,color, 38,297, 48,253,59,234,112,220, 139,215,151,195,180,197,
            207,193,223,207,244,217, 305,229,320,258,329,297, 250,299,120,299,38,297);
        fill(c,0x34000000, 39,296, 58,267,70,247,117,230, 98,252,105,275,104,298,
            75,298,53,298,39,296);
        fill(c,0x31000000, 251,225, 297,239,316,257,328,296, 303,299,284,297,271,297,
            270,267,260,243,251,225);
        line(c,0x40ffffff,1,67,249,106,227);
        line(c,0x40ffffff,1,255,226,293,244);
        if (style == 0 || style == 6) {
            int collar = style == 0 ? 0xff78634a : 0xff655d7b;
            fill(c,collar, 120,222, 137,211,153,213,159,217, 168,227,175,238,181,251,
                160,239,144,247,133,251, 133,238,126,228,120,222);
            fill(c,collar, 242,222, 225,211,213,213,205,217, 196,228,190,238,181,251,
                206,239,219,246,231,250, 232,237,237,229,242,222);
            line(c,0xff24222b,2,182,252,182,297);
            rect(c,collar,210,255,246,279,3);
            line(c,trim,1,212,257,244,257);
            for (int y=260;y<297;y+=11) {
                paint.setColor(trim);
                c.drawCircle(176,y,1.7f,paint);
            }
        } else if (style == 1) {
            rect(c,0xff482337,125,224,239,250,13);
            rect(c,color,133,223,231,242,10);
            for (int i=0;i<3;i++) line(c,trim,2,119,258+i*5,166,258+i*5);
        } else if (style == 2 || style == 4) {
            rect(c,style==2?0xff346f80:0xff374537,130,223,234,242,8);
            line(c,trim,2,182,235,182,297);
            line(c,trim,2,114,263,143,271);
            line(c,trim,2,218,271,247,263);
            if (style == 4) {
                line(c,trim,6,112,226,131,289);
                line(c,trim,6,252,225,232,289);
                rect(c,0xff303e32,201,248,224,270,4);
            } else rect(c,trim,204,249,222,252,2);
        } else if (style == 3) {
            rect(c,0xff48515b,136,223,227,242,8);
            line(c,trim,3,86,245,122,227);
            line(c,trim,3,241,227,277,245);
            line(c,trim,2,129,261,139,297);
            line(c,trim,2,233,261,223,297);
        } else if (style == 5) {
            line(c,trim,6,124,225,146,253);
            line(c,trim,6,240,225,217,253);
            rect(c,trim,142,248,222,298,5);
            rect(c,0xff805c43,161,269,203,289,4);
            line(c,0xffdcad80,1,165,271,199,271);
        } else {
            line(c,trim,2,115,250,162,276);
            line(c,trim,2,248,250,203,276);
        }
        picture.endRecording();
        wardrobe[index] = picture;
        return picture;
    }

    private void recordHead() {
        Canvas c = head.beginRecording(360,300);
        fill(c,0xffa26448, 148,158, 159,164,203,163,215,157, 218,184,217,203,235,219,
            217,241,164,241,131,219, 147,202,146,180,148,158);
        fill(c,0xffd48c5c, 150,180, 170,206,188,215,210,208, 218,209,223,214,230,218,
            203,240,160,236,139,219, 147,206,148,188,150,180);
        fill(c,0xff28212a, 107,125, 93,83,99,41,136,24, 163,5,215,7,248,31,
            267,50,268,90,253,143, 224,117,143,116,107,125);
        fill(c,0xffe2a16c, 119,112, 104,103,100,119,110,147, 114,156,125,157,135,151,
            138,135,132,121,119,112);
        fill(c,0xffefa765, 124,77, 134,51,156,47,181,49, 213,46,242,56,250,82,
            257,108,253,142,243,158, 235,182,217,200,188,203,
            162,201,141,179,132,155, 121,135,115,105,124,77);
        fill(c,0xffc87a4e, 223,74, 241,82,251,90,251,108, 255,147,235,182,209,198,
            208,192,212,182,224,168, 232,148,229,137,231,122, 233,109,232,89,223,74);
        fill(c,0xff19171f, 110,114, 102,88,106,62,115,49, 121,33,142,17,173,16,
            213,13,250,24,260,56, 263,75,259,93,252,106, 251,80,238,66,228,60,
            191,76,168,51,146,74, 134,86,134,104,126,125, 123,118,116,114,110,114);
        fill(c,0xff39303a, 119,57, 141,20,191,20,227,31, 235,35,243,41,247,52,
            223,42,202,43,180,44, 157,44,141,45,119,57);


        fill(c,0xff51404a, 105,77, 111,48,139,23,174,23, 156,34,138,42,125,61,
            117,69,111,73,105,77);
        fill(c,0xff44333e, 109,96, 127,58,155,45,179,45, 166,50,147,62,136,78,
            126,91,117,94,109,96);
        fill(c,0xff4f3f48, 172,28, 202,21,234,32,250,55, 233,46,225,41,211,40,
            198,36,188,33,172,28);
        fill(c,0xff352834, 190,48, 209,36,235,43,249,65, 234,61,222,50,211,50,
            203,50,196,51,190,48);
        fill(c,0xfff8b974, 146,86, 159,65,184,64,206,67, 197,72,179,74,172,78,
            164,82,155,86,146,86);
        fill(c,0xffe79659, 137,137, 146,139,155,143,160,150, 151,152,144,151,140,146,
            139,143,137,139,137,137);
        line(c,0xffa9644a,2,112,125,120,119);
        line(c,0xffa9644a,2,120,119,126,131);
        line(c,0xfff2ad70,2,118,140,124,145);
        line(c,0xffd48855,2,160,184,175,193);
        head.endRecording();
    }

    @Override protected void onDraw(Canvas output) {
        super.onDraw(output);
        long ms = android.os.SystemClock.uptimeMillis();
        updateScene(ms);
        float time = (ms % 120000L) / 1000f;
        float dt = Math.min(100, ms-lastDraw);
        lastDraw=ms;
        float elapsed = (ms - sceneStart) / 1000f;
        float lift = scene < 0 ? 0 : smooth(elapsed / 1.2f) * smooth((6.5f-elapsed) / 1.4f);
        float transition = scene < 0 ? 0 : smooth(elapsed / .42f) * smooth((6.5f - elapsed) / .48f);
        float gait = (float)Math.sin(elapsed*9.2f);
        float breathe = (float)Math.sin(time * 1.4f);
        float blink = (ms % 5300 < 150) ? (float)(.08 + .92 * Math.abs(ms % 5300 - 75) / 75) : 1;
        if (scene==6 && lift>.5f) blink=.18f;
        boolean pleased = ms < reactionUntil && reaction > 0 || scene == 7 && elapsed > 4.4f;
        boolean jogging = scene==3 || scene==4;
        if (ms >= nextPowerCheck) {
            reduced = power != null && power.isPowerSaveMode();
            economy = settings.getBoolean("deck-economy", true);
            nextPowerCheck = ms + 3000;
        }
        int gesture = scheduler.gesture;
        float progress = scheduler.gestureProgress(ms);
        float gestureLift = smooth(progress * 4) * smooth((1-progress)*4);
        if (gesture == 0) blink = .13f + .87f * Math.abs(2*progress-1);
        Canvas c = pixelCanvas;
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        c.save();
        c.scale(.6f,.6f);
        drawLandscape(output,ms);

        sceneBackdrop(c,elapsed,transition);
        c.save();
        if(jogging){
            stride(c,elapsed,transition);
            c.translate(67*transition,(3 - Math.abs(gait)*5)*transition);
            c.scale(1-.37f*transition,1-.32f*transition);
        }
        c.save();
        c.translate(0, breathe*.9f);
        int alpha = scene < 0 ? 255 : Math.round(255 * smooth(elapsed / .62f));
        if (alpha < 255) c.drawPicture(garment(oldOutfit));
        if (alpha > 0) {
            if (alpha < 255) c.saveLayerAlpha(32, 190, 334, 300, alpha);
            c.drawPicture(garment(outfit));
            if (alpha < 255) c.restore();
        }
        c.restore();

        c.save();
        boolean concerned = ms < reactionUntil && reaction < 0;
        float tilt = (float)Math.sin(time*.82f)*(state==2?1.4f:1.1f)
            + (scene>=0 && scene<=2 ? -lift*4 : 0) + (gesture == 4 ? gestureLift * 8 : 0);
        visualTilt += (tilt-visualTilt)*Math.min(1,dt/115f);
        c.rotate(visualTilt,181,205);
        c.translate(state==1?1:0, breathe*.75f);
        c.drawPicture(head);

        paint.setColor(0xff4c3739);
        paint.setStrokeWidth(state==2||state==4?3.5f:3);
        paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(148,104+(state==2?3:0),174,concerned?97:101,paint);
        c.drawLine(202,101,222,105-(state==1?2:0),paint);
        float glance = state==2 ? (float)Math.sin(time*2.1f)*3 : state==4 ? 2 : 0;
        if (gesture == 1) glance = (float)-Math.sin(progress * 2 * Math.PI)*5;
        eye(c,162,115,blink,glance); eye(c,212,115,blink,glance);
        c.save();
        c.translate(0,gesture == 3 ? -gestureLift*3 : 0);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);
        paint.setColor(0xff17151e);
        c.drawRoundRect(140,105,184,129,4,4,paint);
        c.drawRoundRect(192,105,234,129,4,4,paint);
        paint.setStyle(Paint.Style.FILL);
        line(c,0xff17151e,4,184,114,192,114);
        line(c,0xff17151e,4,119,107,140,111);
        c.restore();
        fill(c,0xff241923, 168,159,169,150,180,149,188,152, 197,147,209,151,213,159,
            205,163,200,158,190,157, 182,158,175,163,168,159);
        paint.setColor(0xffaf827b);
        paint.setStrokeWidth(1.6f);
        c.drawLine(190,121,184,140,paint);
        c.drawLine(184,140,192,143,paint);
        c.drawLine(149,154,155,164,paint);
        c.drawLine(233,151,229,161,paint);
        paint.setColor(state==0?0xff7b6868:0xffaa3d44);
        c.drawCircle(238,146,2,paint); c.drawCircle(147,151,1.5f,paint);

        float desired = state == 3 && ms-levelAt < 180 ? Math.min(12, level / 6f)
            : scene==6 ? lift*13 : 0;
        mouth += (desired-mouth)*Math.min(1,dt/65f);
        boolean smile = pleased || gesture == 5 && gestureLift > .3f;
        paint.setColor(0xff664249);
        float half = mouth > 7 && level > 50 ? 10 : 16;
        c.drawOval(191-half,164-mouth*.23f,191+half,168+mouth+(smile?4:0),paint);
        if (mouth>3 || smile) { paint.setColor(0xffffe4cc); c.drawOval(181,165,201,168,paint); }
        else if (!concerned) {
            line(c,0xff664249,2,175,166,179,168);
            line(c,0xff664249,2,203,168,207,165);
        }
        c.restore();
        sceneForeground(c,elapsed,lift,gait);
        if (gesture == 2 || gesture == 3 || pleased && scene < 0) {
            float amount = pleased && gesture < 0 ? smooth((reactionUntil-ms)/380f) : gestureLift;
            float x=267-(gesture==3?49:9)*amount;
            float y=254-(gesture==3?136:181)*amount;
            if (gesture == 2) x+=(float)Math.sin(progress*37)*5*amount;
            arm(c,0xff294c85,270,232,286,237-43*amount,x,y);
            hand(c,x,y,-39,false,false);
        }
        c.restore();
        c.restore();
        float heroHeight = getHeight() * (wide ? .85f : .95f);
        float columnWidth = getWidth() * (wide ? .45f : 1f);
        float scale = Math.min(columnWidth / 216f, heroHeight / 180f);
        int width = Math.round(216*scale), height = Math.round(180*scale);
        int left = Math.round((columnWidth-width)/2);
        int top = getHeight()-height;
        target.set(left,top,left+width,top+height);
        output.drawBitmap(pixels,null,target,pixelPaint);
        if (running && isShown() && getWindowVisibility()==VISIBLE && getAlpha()>0)
            postInvalidateDelayed(reduced ? 83 :
                economy && scene < 0 && gesture < 0 && state != 3 && ms >= reactionUntil ? 65 : 23);
    }
}
