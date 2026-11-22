package br.com.rian.deck;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

final class TrendsScreen {
    private static final int SURFACE=0xff101116, BORDER=0xff353541, ACCENT=0xffe7354a,
        MUTED=0xffaaaaad, GREEN=0xff35d995, RED=0xffff5268;
    private static final String[] NAMES={"IA Global · BOTZ","Ibovespa","Nasdaq Composite","Bitcoin"};
    private static final String[] SCENES={"ai-mountains","ibovespa-city","nasdaq-mountains","bitcoin-landscape"};
    private static final String[] MARKET_ICONS={"ai","brazil","nasdaq","bitcoin"};
    private static final String[] PERIODS={"1D","7D","30D"};
    private static final String[] PERIOD_LABELS={"24 h / sessão","7 dias","30 dias"};
    private static final Locale BR=Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("dd/MM HH:mm",BR);
    private final DeckActivity deck;
    private final SharedPreferences prefs;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.Executor THUMBNAILS=java.util.concurrent.Executors.newFixedThreadPool(2);
    private final android.util.LruCache<String,android.graphics.Bitmap> pictures=new android.util.LruCache<>(8);
    private final java.util.Set<String> loadingPictures=new java.util.HashSet<>();
    private final android.util.LruCache<String,Long> failedPictures=new android.util.LruCache<>(8);
    private final java.util.Map<String,android.widget.ImageView> pictureTargets=new java.util.HashMap<>();
    private final LinearLayout[] news=new LinearLayout[4], markets=new LinearLayout[4];
    private final int[] periods=new int[4];
    private JSONObject snapshot;
    private String fingerprint="";
    private TextView status;
    private DeckVoiceSocket socket;
    private boolean active;
    private boolean online;
    private final Runnable reconnect=this::connect;

    TrendsScreen(DeckActivity deck, SharedPreferences prefs) {
        this.deck=deck;
        this.prefs=prefs;
        fingerprint=prefs.getString("trends-cache","");
        try { snapshot=new JSONObject(fingerprint); } catch (Exception ignored) { }
    }

    void mount(LinearLayout body) {
        LinearLayout header=deck.row(body);
        header.setBackground(surface(0xff140c10));
        header.setPadding(dp(8),0,dp(8),0);
        TextView title=label("↗  Trends",22,DeckTheme.INK,1);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(39),1));
        status=label("Consultando Windows…",11,MUTED,1);
        status.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
        header.addView(status,new LinearLayout.LayoutParams(0,dp(39),1));
        status.setOnClickListener(v->{ stop(); start(); });
        status.setContentDescription("Trends. Toque para reconectar e consultar atualizações.");

        boolean landscape=deck.landscape();
        LinearLayout layout=landscape?deck.row(body):deck.column();
        if (!landscape) body.addView(layout,new LinearLayout.LayoutParams(-1,-2));
        addGroup(layout,"Notícias de tecnologia",news,landscape);
        addGroup(layout,"Mercados e IA",markets,landscape);
        render();
    }

    private void addGroup(LinearLayout layout,String title,LinearLayout[] cards,boolean landscape) {
        LinearLayout group=deck.column();
        group.setPadding(dp(5),0,dp(5),dp(4));
        group.setBackground(surface(0xff101011));
        group.setForeground(new VisualAssets.Frame(dp(8),dp(1)));
        LinearLayout.LayoutParams box=landscape ? new LinearLayout.LayoutParams(0,-2,1)
            : new LinearLayout.LayoutParams(-1,-2);
        box.setMargins(dp(3),dp(2),dp(3),dp(3));
        layout.addView(group,box);
        TextView heading=label(title,15,DeckTheme.INK,1);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(dp(5),0,0,0);
        LinearLayout titleRow=deck.row(group);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        if(cards==news) titleRow.addView(new PixelIcon("news"),new LinearLayout.LayoutParams(dp(22),dp(22)));
        titleRow.addView(heading,new LinearLayout.LayoutParams(0,dp(27),1));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        int height=landscape ? Math.max(88,Math.min(150,
            (deck.getResources().getConfiguration().screenHeightDp-136)/2)) : 116;
        for (int row=0;row<2;row++) {
            LinearLayout line=deck.row(group);
            for(int col=0;col<2;col++) {
                LinearLayout card=deck.column();
                card.setPadding(dp(6),dp(4),dp(6),dp(4));
                card.setBackground(surface(SURFACE));
                deck.addWeighted(line,card,height);
                cards[row*2+col]=card;
            }
        }
    }

    void start() {
        if (active) return;
        active=true;
        connect();
    }

    void stop() {
        active=false;
        online=false;
        ui.removeCallbacks(reconnect);
        if(socket!=null) { socket.close(); socket=null; }
    }

    private void connect() {
        if (!active || socket!=null) return;
        DeckVoiceSocket stream=new DeckVoiceSocket();
        socket=stream;
        new Thread(()->{
            try {
                stream.open("/v1/trends/stream");
                stream.read((kind,bytes)-> {
                    if(kind!=1) return;
                    String packet=new String(bytes,StandardCharsets.UTF_8);
                    JSONObject data=new JSONObject(packet);
                    ui.post(()->{
                        if(socket!=stream || !active) return;
                        boolean changed=!packet.equals(fingerprint);
                        boolean regained=!online;
                        online=true;
                        if(changed) {
                            boolean hasNews=data.optJSONObject("news")!=null&&!data.optJSONObject("news").isNull("data");
                            boolean hasQuote=false;
                            JSONArray entries=data.optJSONArray("markets");
                            for(int i=0;entries!=null&&i<entries.length();i++) {
                                JSONObject entry=entries.optJSONObject(i);
                                JSONObject quote=entry==null?null:entry.optJSONObject("quote");
                                if(quote!=null&&!quote.isNull("data")) hasQuote=true;
                            }
                            if(hasNews||hasQuote||snapshot==null) {
                                fingerprint=packet;
                                snapshot=data;
                                if(hasNews||hasQuote) prefs.edit().putString("trends-cache",packet).apply();
                            }
                        }
                        if(changed||regained) render();
                    });
                });
            } catch(Exception ignored) { }
            finally {
                stream.close();
                ui.post(()->{
                    if(socket!=stream) return;
                    socket=null;
                    online=false;
                    render();
                    if(active) ui.postDelayed(reconnect,12000);
                });
            }
        },"deck-trends").start();
    }

    private void render() {
        if(status==null) return;
        JSONObject feed=snapshot==null?null:snapshot.optJSONObject("news");
        boolean stale=feed!=null&&(feed.optBoolean("stale")||feed.optBoolean("failed"));
        JSONArray articles=feed==null?null:feed.optJSONArray("data");
        JSONArray quotes=snapshot==null?null:snapshot.optJSONArray("markets");
        status.setText(!active?"Dados em cache":!online?"Windows indisponível · cache":
            feed==null||articles==null?(stale?"Falha no feed":"Atualizando…"):
            (stale?"Notícias em cache · ":"Notícias · ")+date(feed.optString("updated")));
        pictureTargets.clear();
        for(int i=0;i<4;i++) {
            newsCard(news[i],articles==null?null:articles.optJSONObject(i),stale);
            marketCard(i,quotes==null?null:quotes.optJSONObject(i));
        }
    }

    private void newsCard(LinearLayout card,JSONObject item,boolean failed) {
        card.removeAllViews();
        card.setOnClickListener(null);
        card.setClickable(false);
        if(item==null) {
            card.addView(label("Notícia indisponível",13,MUTED,2));
            card.addView(label(failed?"Erro no provedor":"Aguardando dados",11,MUTED,1));
            return;
        }
        LinearLayout content=deck.row(card);
        content.setLayoutParams(new LinearLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams graphic=new LinearLayout.LayoutParams(dp(55),-1);
        graphic.rightMargin=dp(6);
        android.widget.FrameLayout preview=new android.widget.FrameLayout(deck);
        preview.setBackground(surface(0xff231b23));
        preview.setClipToOutline(true);
        preview.addView(new PixelIcon("news"),new android.widget.FrameLayout.LayoutParams(-1,-1));
        android.widget.ImageView picture=new android.widget.ImageView(deck);
        picture.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        preview.addView(picture,new android.widget.FrameLayout.LayoutParams(-1,-1));
        content.addView(preview,graphic);
        if(!item.isNull("image")) bindPicture(item.optString("id"),picture);
        LinearLayout copy=deck.column();
        content.addView(copy,new LinearLayout.LayoutParams(0,-1,1));
        int score=item.optInt("score");
        copy.addView(label(item.optString("category").toUpperCase(BR)+(score>0?" · ↑ "+score:""),
            10,ACCENT,1));
        TextView title=label(item.optString("title"),13,DeckTheme.INK,3);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        copy.addView(title,new LinearLayout.LayoutParams(-1,0,1));
        copy.addView(label(item.optString("source")+" · "+age(item.optString("published")),10,MUTED,1));
        card.setOnClickListener(v->deck.openTrendArticle(item.optString("id")));
    }

    private void bindPicture(String id, android.widget.ImageView picture) {
        if(!id.matches("[0-9]{1,20}")) return;
        pictureTargets.put(id,picture);
        android.graphics.Bitmap cached=pictures.get(id);
        if(cached!=null) { picture.setImageBitmap(cached); return; }
        Long retry=failedPictures.get(id);
        if(!online||retry!=null&&retry>android.os.SystemClock.elapsedRealtime()||!loadingPictures.add(id)) return;
        THUMBNAILS.execute(()->{
            android.graphics.Bitmap image=null;
            java.net.HttpURLConnection connection=null;
            try {
                connection=(java.net.HttpURLConnection)new java.net.URL(
                    "http://127.0.0.1:48627/v1/trends/image/"+id).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(2200);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("Authorization","Bearer "+Pairing.TOKEN);
                if(connection.getResponseCode()==200) try(java.io.InputStream stream=connection.getInputStream()) {
                    android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();
                    options.inSampleSize=2;
                    image=android.graphics.BitmapFactory.decodeStream(stream,null,options);
                }
            } catch(Exception ignored) { }
            finally { if(connection!=null) connection.disconnect(); }
            android.graphics.Bitmap result=image;
            ui.post(()->{
                loadingPictures.remove(id);
                if(result==null) { failedPictures.put(id,android.os.SystemClock.elapsedRealtime()+120_000); return; }
                pictures.put(id,result);
                android.widget.ImageView target=pictureTargets.get(id);
                if(active&&target!=null) target.setImageBitmap(result);
            });
        });
    }

    private void marketCard(int i,JSONObject entry) {
        LinearLayout card=markets[i];
        card.removeAllViews();
        card.setOnClickListener(null);
        card.setClickable(false);
        JSONObject state=entry==null?null:entry.optJSONObject("quote");
        JSONObject quote=state==null?null:state.optJSONObject("data");
        TextView name=label(NAMES[i],13,DeckTheme.INK,1);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout heading=deck.row(card);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams badge=new LinearLayout.LayoutParams(dp(15),dp(15));
        badge.rightMargin=dp(4);
        heading.addView(new PixelIcon(MARKET_ICONS[i]),badge);
        heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        if(quote==null) {
            card.addView(label(state!=null&&state.optBoolean("failed")?"Provedor indisponível":"Aguardando cotação",11,MUTED,2));
            return;
        }
        double change=quote.optDouble("change");
        int color=Double.isNaN(change)?MUTED:change<0?RED:GREEN;
        LinearLayout values=deck.row(card);
        String currency=quote.optString("currency");
        NumberFormat number=NumberFormat.getNumberInstance(BR);
        number.setMinimumFractionDigits(2);
        number.setMaximumFractionDigits(2);
        String value=(currency.equals("USD")?"US$ ":"")+number.format(quote.optDouble("value"))+(currency.equals("PTS")?" pts":"");
        values.addView(label(value,14,DeckTheme.INK,1),new LinearLayout.LayoutParams(0,-2,1));
        values.addView(label(Double.isNaN(change)?"—":String.format(BR,"%+.2f%%",change),10,color,1));
        JSONObject history=quote.optJSONObject("series");
        int selection=periods[i];
        JSONArray series=history==null?null:history.optJSONArray(PERIODS[selection]);
        if(history!=null && (series==null || series.length()<2)) {
            for(int p=0;p<PERIODS.length;p++) {
                JSONArray candidate=history.optJSONArray(PERIODS[p]);
                if(candidate!=null&&candidate.length()>=2) { selection=p; series=candidate; break; }
            }
        }
        periods[i]=selection;
        card.addView(new Chart(series,color,i),new LinearLayout.LayoutParams(-1,0,1));
        boolean delayed=!online||state.optBoolean("stale")||state.optBoolean("failed");
        card.addView(label(quote.optString("source")+" · "+date(quote.optString("quoted"))+(delayed?" · cache":""),
            9,MUTED,1));
        card.addView(label(series==null?"Sem histórico":PERIOD_LABELS[selection]+" ↻",9,MUTED,1));
        if(history!=null) card.setOnClickListener(v-> {
            for(int step=1;step<=PERIODS.length;step++) {
                int p=(periods[i]+step)%PERIODS.length;
                JSONArray candidate=history.optJSONArray(PERIODS[p]);
                if(candidate!=null&&candidate.length()>=2) {
                    periods[i]=p;
                    marketCard(i,entry);
                    break;
                }
            }
        });
    }

    private static String date(String text) {
        try { return OffsetDateTime.parse(text).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(TIME); }
        catch(Exception invalid) { return "horário indisponível"; }
    }

    private static String age(String value) {
        try {
            long minutes=Math.max(0,Duration.between(OffsetDateTime.parse(value),OffsetDateTime.now()).toMinutes());
            return minutes<60 ? "há "+minutes+" min" : "há "+minutes/60+" h";
        } catch(Exception invalid) { return "horário indisponível"; }
    }

    private int dp(float value) { return deck.dp(value); }

    private TextView label(String value,int size,int color,int lines) {
        TextView v=deck.text(value,size,color);
        v.setMaxLines(lines);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setIncludeFontPadding(false);
        return v;
    }

    private GradientDrawable surface(int color) {
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(8));
        shape.setStroke(dp(1),BORDER);
        return shape;
    }

    private final class PixelIcon extends View {
        private final Paint ink=new Paint();
        private final android.graphics.Rect destination=new android.graphics.Rect();
        private final android.graphics.Bitmap icon;
        PixelIcon(String name) {
            super(deck);
            icon=VisualAssets.bitmap(deck,"trends/icons/"+name+".png");
            ink.setFilterBitmap(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH) {
            int size=Math.min(w,h);
            int left=(w-size)/2, top=(h-size)/2;
            destination.set(left,top,left+size,top+size);
        }
        @Override protected void onDraw(Canvas c) {
            if(icon!=null) c.drawBitmap(icon,null,destination,ink);
        }
    }

    private final class Chart extends View {
        private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint backdrop=new Paint();
        private final android.graphics.Rect plot=new android.graphics.Rect();
        private final android.graphics.Bitmap landscape;
        private final android.graphics.Path line=new android.graphics.Path(), area=new android.graphics.Path();
        private final double[] data;
        private final double minimum, span;
        private final int color;
        private android.graphics.Shader fill;
        private float lastX,lastY;
        Chart(JSONArray values,int color,int index) {
            super(deck);
            this.color=color;
            landscape=VisualAssets.bitmap(deck,"trends/backgrounds/"+SCENES[index]+".png");
            backdrop.setAlpha(85);
            backdrop.setFilterBitmap(false);
            data=new double[values==null?0:values.length()];
            double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
            for(int i=0;i<data.length;i++) {
                data[i]=values.optDouble(i);
                if(Double.isFinite(data[i])) { min=Math.min(min,data[i]); max=Math.max(max,data[i]); }
            }
            minimum=min;
            span=Math.max(Math.abs(max-min),.0001);
            setContentDescription("Histórico de preço. Toque no cartão para alternar o período.");
        }
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH) {
            plot.set(0,0,w,h);
            line.reset();
            area.reset();
            if(data.length<2||!Double.isFinite(minimum)||h<=dp(8)||w<=dp(8)) return;
            float left=dp(3), top=dp(4), bottom=h-dp(4), step=(w-2*left)/(data.length-1);
            for(int i=0;i<data.length;i++) {
                float x=left+i*step;
                float y=(float)(top+(1-(data[i]-minimum)/span)*(bottom-top));
                if(i==0) line.moveTo(x,y);
                else line.lineTo(x,y);
                lastX=x;
                lastY=y;
            }
            area.set(line);
            area.lineTo(lastX,bottom);
            area.lineTo(left,bottom);
            area.close();
            fill=new android.graphics.LinearGradient(0,top,0,bottom,
                (color&0x00ffffff)|0x51000000,(color&0x00ffffff)|0x06000000,
                android.graphics.Shader.TileMode.CLAMP);
        }
        @Override protected void onDraw(Canvas c) {
            if(line.isEmpty()) return;
            if(landscape!=null) c.drawBitmap(landscape,null,plot,backdrop);
            ink.setShader(null);
            ink.setColor(0x29c4c5d2);
            ink.setStrokeWidth(dp(1));
            for(int i=1;i<=3;i++) {
                float y=getHeight()*i/4f;
                c.drawLine(dp(2),y,getWidth()-dp(2),y,ink);
            }
            ink.setStyle(Paint.Style.FILL);
            ink.setShader(fill);
            c.drawPath(area,ink);
            ink.setShader(null);
            ink.setColor(color);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeJoin(Paint.Join.ROUND);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setColor(0xd0101014);
            ink.setStrokeWidth(dp(3.5f));
            c.drawPath(line,ink);
            ink.setColor(color);
            ink.setStrokeWidth(dp(1.5f));
            c.drawPath(line,ink);
            ink.setStyle(Paint.Style.FILL);
            c.drawCircle(lastX,lastY,dp(2.2f),ink);
        }
    }
}
