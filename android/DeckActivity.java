package br.com.rian.deck;

import android.app.Activity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.net.Uri;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ScrollView;
import java.util.ArrayDeque;

public final class DeckActivity extends Activity {
    private static final int BG = DeckTheme.BLACK, CARD = DeckTheme.PANEL, INK = DeckTheme.INK, RED = DeckTheme.RED;
    private static final int SOFT = DeckTheme.MUTED, MINT = 0xff73e5c6, VIOLET = 0xffa998ff;
    private static final int PINK = 0xffff8b9b, GOLD = 0xffffcb80;
    private static final int TRENDS=2, ASSISTANT=3, AUTOMATIONS=4, SETTINGS=5;
    private static final String[] TITLES = {"Controle", "Monitoramento", "Trends", "Assistente", "Automações", "Configurações"};
    private static final String[] ICONS = {"grid", "chart", "trending-up", "voice", "bolt", "settings"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService telemetry = Executors.newSingleThreadExecutor();
    private final Runnable poll = this::pollStatus;
    private static final String[] METRIC_IDS = {"cpu", "ram", "gpu", "storage", "temperature", "network", "fps", "processes"};
    private static final String[] METRIC_NAMES = {"CPU", "RAM", "GPU", "Armazenamento", "Temperaturas", "Rede", "FPS", "Processos"};
    private static final String[] METRIC_EMOJIS = {"⚙️", "🧠", "🎮", "💽", "🌡️", "🌐", "🎞️", "📋"};
    private static final String[] METRIC_UNITS = {"%", "%", "%", "%", " °C", " KB/s", " FPS", ""};
    private static final double[] WARN = {65,75,70,85,75,5000,45,250};
    private static final double[] CRITICAL = {90,90,93,95,90,20000,25,450};
    private static final String[] STATES = {"Normal", "Atenção", "Crítico"};
    private static final String[][] REACTIONS = {
        {"Michael Scott tranquilo", "Michael Scott gritando", "Reação crítica"},
        {"Elon Musk", "Elon Musk", "Elon Musk"},
        {"PC em chamas", "PC em chamas", "PC em chamas"},
        {"Disco normal", "Disco sob atenção", "Stanley Hudson"},
        {"This Is Fine", "This Is Fine", "This Is Fine"},
        {"Jim Halpert", "Jim olhando para a câmera", "Rede sob carga crítica"},
        {"FalleN", "FalleN", "FalleN"},
        {"Saul Goodman", "Saul Goodman", "Saul Goodman"}
    };
    private final History[] histories = new History[METRIC_IDS.length];
    private final Graph[] graphs = new Graph[METRIC_IDS.length];
    private final MemeStatus.Band[] bands = new MemeStatus.Band[METRIC_IDS.length];
    private final int[] levels = new int[METRIC_IDS.length];
    private String memeImportSlot;
    private TextView battery;
    private int batteryPercent = -1;
    private boolean batteryCharging;
    private boolean plugged;
    private long lastTouch;
    private long deckCheckedAt;
    private final android.content.BroadcastReceiver batteryUpdates = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
            int level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 0);
            batteryPercent = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
            int state = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
            batteryCharging = state == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                (state == android.os.BatteryManager.BATTERY_STATUS_FULL && plugged);
            updateBatteryLabel();
            if (active) applyPowerPolicy();
        }
    };
    private MiniMetrics summary;
    private TextView pcStatus, clock;
    private DeckButton3D microphone;
    private final List<DeckButton3D> remoteKeys = new ArrayList<>();
    private final List<TextView> transport = new ArrayList<>();
    private DeckConfig remote;
    private String deckFingerprint = "";
    private JSONObject media, network;
    private LinearLayout mediaCommands;
    private TextView mediaTitle, mediaArtist, mediaTime, networkLabel, historyLabel, mediaToggle;
    private SeekBar mediaProgress;
    private TextView shuffleKey, repeatKey;
    private boolean draggingSeek;
    private ImageView album;
    private String coverKey = "";
    private Bitmap cover;
    private long weatherUpdated, offlineSince;
    private int thermal;
    private float ambient = -1;
    private KioskPolicy kioskPolicy;
    private KioskPin kioskPin;
    private TextView kioskStatus;
    private String kioskError = "";
    private final android.os.PowerManager.OnThermalStatusChangedListener thermalListener = value -> {
        thermal = value;
        if (this.active) applyPowerPolicy();
    };
    private final android.hardware.SensorEventListener lightListener = new android.hardware.SensorEventListener() {
        @Override public void onSensorChanged(android.hardware.SensorEvent event) {
            ambient = ambient < 0 ? event.values[0] : .75f * ambient + .25f * event.values[0];
        }
        @Override public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) { }
    };
    private UsbIndicator status;
    private LinearLayout body, nav;
    private ScrollView scroll;
    private SharedPreferences settings;
    private TrendsScreen trends;
    private int section;
    private VoiceDeck assistant;
    private boolean immersive, active, connected, microphoneBusy, pollRunning;
    private Boolean micMuted;

    private static final String[] BUTTON_IDS = {"youtube", "codex", "cursor", "serpro", "microphone", "media",
        "steam", "workspace", "lock", "spotify", "previous", "next", "volume-up", "volume-down", "mute", "explorer", "terminal"};
    private static final String[] BUTTON_NAMES = {"YouTube", "Codex", "Cursor", "Git Corporativo Serpro", "Silenciar MIC", "Play / Pause",
        "Steam", "Área de trabalho", "Bloquear tela", "Spotify", "Faixa anterior", "Próxima faixa", "Aumentar volume",
        "Diminuir volume", "Silenciar som", "Arquivos", "PowerShell"};
    private static final String[] BUTTON_ICONS = {"youtube", "codex", "cursor", "serpro", "mic", "play",
        "steam", "desktop", "lock", "spotify", "previous", "next", "volume-up", "volume-down", "mute", "folder", "terminal"};
    private static final Weather.City DEFAULT_CITY = new Weather.City("São Paulo · SP", -23.55, -46.63);
    private final ExecutorService internet = Executors.newSingleThreadExecutor();
    private final Runnable refreshSky = this::loadWeather;
    private final Runnable refreshBible = this::loadVerse;
    private Weather.City city;
    private Weather weather;
    private boolean weatherLoading, weatherStale;
    private TextView weatherText;
    private int shortcutPage;
    private int pageCount = 1;
    private LinearLayout commandGrid;
    private float swipeX, swipeY;
    private boolean commandSwipe;
    private boolean pagingDrag;
    private final int[] touchOrigin = new int[2];
    private boolean editingButtons;
    private VerseCard verse;
    private VerseCard.Verse dailyVerse;
    private String verseDate;
    private boolean verseLoading;
    private Bitmap verseArtwork;
    private static final long VERSE_INTERVAL = 6 * 60 * 60 * 1000L;
    private static final long VERSE_RETRY = 5 * 60 * 1000L;
    private long verseFetchedAt, artworkFetchedAt;
    private String artworkDate = "";
    private boolean artworkLoading;
    private Typeface brandTypeface;


    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        for (int i = 0; i < histories.length; i++) {
            histories[i] = new History();
            bands[i] = new MemeStatus.Band();
            levels[i] = -1;
        }
        settings = getSharedPreferences("deck-display", MODE_PRIVATE);
        assistant = new VoiceDeck(this, settings);
        kioskPolicy = new KioskPolicy(this);
        kioskPin = new KioskPin(settings);
        brandTypeface = VisualAssets.brandTypeface(this);
        immersive = settings.getBoolean("immersive", true);
        city = new Weather.City(settings.getString("city", DEFAULT_CITY.name),
            Double.longBitsToDouble(settings.getLong("latitude", Double.doubleToLongBits(DEFAULT_CITY.latitude))),
            Double.longBitsToDouble(settings.getLong("longitude", Double.doubleToLongBits(DEFAULT_CITY.longitude))));
        shortcutPage = settings.getInt("shortcut-page", 0);
        try {
            JSONObject cached = new JSONObject(settings.getString("deck-cache", ""));
            remote = new DeckConfig(cached);
            deckFingerprint = fingerprint(cached);
        } catch (org.json.JSONException missing) { remote = null; }
        editingButtons = saved != null && saved.getBoolean("editingButtons");
        verseDate = settings.getString("verse-date", "");
        verseFetchedAt = settings.getLong("verse-fetched-at", 0L);
        String cachedPassage = settings.getString("verse-text", "");
        if (!cachedPassage.isEmpty()) {
            dailyVerse = new VerseCard.Verse(settings.getString("verse-reference", "Biblia.com"), cachedPassage);
        }
        section = saved == null ? 0 : saved.getInt("section");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setTurnScreenOn(true);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setRequestedOrientation(settings.getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)
            == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : landscapeSide());
        render();
        applyFullscreen();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        render();
        applyFullscreen();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("section", section);
        state.putBoolean("editingButtons", editingButtons);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && settings != null) getWindow().getDecorView().post(this::applyFullscreen);
    }

    private void applyFullscreen() {
        WindowInsetsController bars = getWindow().getInsetsController();
        if (immersive) {
            if (android.os.Build.VERSION.SDK_INT == 30)
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            if (bars == null) return;
            bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            bars.hide(WindowInsets.Type.systemBars());
        } else {
            if (android.os.Build.VERSION.SDK_INT == 30) getWindow().getDecorView().setSystemUiVisibility(0);
            if (bars != null) bars.show(WindowInsets.Type.systemBars());
        }
    }

    @Override public void onBackPressed() {
        if (immersive || armed()) {
            if (editingButtons) show(AUTOMATIONS, true);
            else if (section != 0) show(0, true);
            return;
        }
        super.onBackPressed();
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        int action = event.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            lastTouch = android.os.SystemClock.elapsedRealtime();
            if (economyEnabled()) applyPowerPolicy();
            commandSwipe = false;
            pagingDrag = false;
            if (commandGrid != null && section == 0) {
                commandGrid.animate().cancel();
                commandGrid.setTranslationX(0);
                commandGrid.setAlpha(1);
            }
            if (section == 0 && !editingButtons && pageCount > 1) {
                swipeX = event.getRawX();
                swipeY = event.getRawY();
                commandSwipe = touched(body) && !touched(mediaProgress);
            }
        } else if (action == android.view.MotionEvent.ACTION_CANCEL ||
            action == android.view.MotionEvent.ACTION_POINTER_DOWN) {
            commandSwipe = false;
            if (pagingDrag) {
                if (action == android.view.MotionEvent.ACTION_CANCEL) {
                    pagingDrag = false;
                    settlePage();
                }
                return true;
            }
        } else if (commandSwipe || pagingDrag) {
            float x = event.getRawX() - swipeX;
            float y = event.getRawY() - swipeY;
            boolean horizontal = Math.abs(x) > Math.abs(y) * 1.35f;
            if (commandSwipe && action == android.view.MotionEvent.ACTION_MOVE && !pagingDrag &&
                Math.abs(x) > android.view.ViewConfiguration.get(this).getScaledTouchSlop() && horizontal) {
                android.view.MotionEvent cancel = android.view.MotionEvent.obtain(event);
                cancel.setAction(android.view.MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                pagingDrag = true;
            } else if (!pagingDrag && Math.abs(y) > dp(20) && Math.abs(y) > Math.abs(x)) {
                commandSwipe = false;
            }
            if (action == android.view.MotionEvent.ACTION_UP) {
                boolean consumed = pagingDrag;
                boolean quick = event.getEventTime() - event.getDownTime() < 225 && Math.abs(x) >= dp(24);
                boolean advance = commandSwipe && pagingDrag && horizontal && (Math.abs(x) >= dp(48) || quick)
                    && section == 0 && !editingButtons;
                commandSwipe = pagingDrag = false;
                int direction = x < 0 ? 1 : -1;
                if (advance && shortcutPage + direction >= 0 && shortcutPage + direction < pageCount)
                    movePage(direction, pageCount);
                else if (consumed) settlePage();
                if (consumed) return true;
            } else if (pagingDrag) {
                if (action == android.view.MotionEvent.ACTION_MOVE && commandGrid != null) {
                    boolean edge = x > 0 ? shortcutPage == 0 : shortcutPage == pageCount - 1;
                    float travel = x * (edge ? .18f : .66f);
                    float limit = commandGrid.getWidth() * .34f;
                    commandGrid.setTranslationX(Math.max(-limit, Math.min(limit, travel)));
                }
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private void settlePage() {
        if (commandGrid != null) commandGrid.animate().translationX(0).setDuration(190)
            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.7f)).start();
    }

    private boolean touched(View view) {
        if (view == null || !view.isShown()) return false;
        view.getLocationOnScreen(touchOrigin);
        return swipeX >= touchOrigin[0] && swipeX < touchOrigin[0] + view.getWidth()
            && swipeY >= touchOrigin[1] && swipeY < touchOrigin[1] + view.getHeight();
    }

    @Override protected void onStart() {
        super.onStart();
        registerReceiver(batteryUpdates, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        active = true;
        if (section == TRENDS && trends != null) trends.start();
        lastTouch = android.os.SystemClock.elapsedRealtime();
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        thermal = power.getCurrentThermalStatus();
        power.addThermalStatusListener(getMainExecutor(), thermalListener);
        android.hardware.SensorManager sensors = getSystemService(android.hardware.SensorManager.class);
        android.hardware.Sensor light = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT);
        if (light != null) sensors.registerListener(lightListener, light, android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
        applyPowerPolicy();
        main.removeCallbacks(poll);
        main.post(poll);
        main.post(refreshSky);
        main.post(refreshBible);
    }

    @Override protected void onResume() {
        super.onResume();
        assistant.resume();
        getWindow().getDecorView().post(this::enforceKiosk);
    }

    @Override protected void onStop() {
        unregisterReceiver(batteryUpdates);
        assistant.suspend();
        if (trends != null) trends.stop();
        active = false;
        for (Graph graph : graphs) if (graph != null) graph.meme.pause();
        getSystemService(android.os.PowerManager.class).removeThermalStatusListener(thermalListener);
        getSystemService(android.hardware.SensorManager.class).unregisterListener(lightListener);
        main.removeCallbacks(poll);
        main.removeCallbacks(refreshSky);
        main.removeCallbacks(refreshBible);
        super.onStop();
    }

    @Override protected void onDestroy() {
        assistant.destroy();
        worker.shutdownNow();
        telemetry.shutdownNow();
        internet.shutdownNow();
        super.onDestroy();
    }

    private void render() {
        LinearLayout root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(9), dp(16), dp(8));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text("RianDeckOS", 27, INK);
        android.text.SpannableString mark = new android.text.SpannableString("RianDeckOS");
        mark.setSpan(new android.text.style.ForegroundColorSpan(RED), 8, 10, 0);
        name.setText(mark);
        name.setTypeface(brandTypeface);
        name.setLetterSpacing(-.035f);
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(name);
        TextView version = text("v5.4", 10, SOFT);
        version.setPadding(dp(6), dp(3), dp(6), dp(3));
        version.setBackground(shape(CARD, 6));
        LinearLayout.LayoutParams badge = new LinearLayout.LayoutParams(-2, -2);
        badge.leftMargin = dp(8);
        brand.addView(version, badge);
        if (!landscape()) version.setVisibility(View.GONE);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        nav = new LinearLayout(this);
        if (landscape()) top.addView(nav, new LinearLayout.LayoutParams(dp(173), dp(37)));
        LinearLayout indicators = new LinearLayout(this);
        indicators.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        top.addView(indicators, new LinearLayout.LayoutParams(landscape() ? 0 : -2, -2, landscape() ? 1 : 0));
        pcStatus = text("", 13, SOFT);
        pcStatus.setPadding(dp(8), 0, dp(7), 0);
        indicators.addView(pcStatus);
        status = new UsbIndicator();
        indicators.addView(status, new LinearLayout.LayoutParams(dp(49), dp(29)));
        clock = text("", 18, INK);
        clock.setPadding(dp(9), 0, dp(3), 0);
        clock.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        indicators.addView(clock);
        battery = text("", 12, SOFT);
        battery.setPadding(dp(6), 0, 0, 0);
        battery.setSingleLine(true);
        indicators.addView(battery);
        updateBatteryLabel();
        refreshHeader();
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(37)));

        if (!landscape()) root.addView(nav, new LinearLayout.LayoutParams(-1, dp(39)));
        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        body = column();
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        display(false);
    }

    private void show(int index, boolean animate) {
        navigate(index, animate);
    }

    private void navigate(int index, boolean animate) {
        if (section == ASSISTANT && index != ASSISTANT) assistant.hide();
        if (section == TRENDS && index != TRENDS && trends != null) trends.stop();
        section = index;
        main.removeCallbacks(poll);
        if (active) main.post(poll);
        editingButtons = false;
        display(animate);
    }

    private void display(boolean animate) {
        nav.removeAllViews();
        for (int i = 0; i < TITLES.length; i++) {
            final int destination = i;
            TextView tab = tile(ICONS[i], "", INK,
                i == section ? RED : CARD, () -> show(destination, true));
            setIcon(tab, ICONS[i], INK, 20);
            tab.setContentDescription(TITLES[i]);
            tab.setSelected(i == section);
            addWeighted(nav, tab, 31);
        }
        microphone = null;
        commandGrid = null;
        remoteKeys.clear();
        transport.clear();
        mediaTitle = mediaArtist = mediaTime = networkLabel = historyLabel = mediaToggle = null;
        album = null;
        mediaProgress = null;
        shuffleKey = repeatKey = null;
        draggingSeek = false;
        summary = null;
        kioskStatus = null;
        weatherText = null;
        verse = null;
        body.removeAllViews();
        for (int i = 0; i < graphs.length; i++) graphs[i] = null;
        if (editingButtons) editButtons();
        else if (section == 0) controls();
        else if (section == 1) monitoring();
        else if (section == TRENDS) {
            if (trends == null) trends = new TrendsScreen(this, settings);
            trends.mount(body);
            if (active) trends.start();
        } else if (section == ASSISTANT) assistant.mount(body, connected);
        else if (section == AUTOMATIONS) automations();
        else preferences();
        scroll.setVerticalScrollBarEnabled(section != 0 || !landscape());
        scroll.scrollTo(0, 0);
        body.animate().cancel();
        if (animate) {
            body.setAlpha(.72f);
            body.animate().alpha(1).setDuration(135).start();
        } else {
            body.setAlpha(1);
            body.setTranslationY(0);
        }
    }

    private void refreshHeader() {
        status.update(connected);
        updateKioskStatus();
        pcStatus.setText("●");
        pcStatus.setTextColor(connected ? 0xff83cf9f : SOFT);
        pcStatus.setContentDescription(connected ? "PC conectado" : "PC offline");
        clock.setText(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
    }

    private void updateBatteryLabel() {
        if (battery == null) return;
        String percent = batteryPercent < 0 ? "—" : batteryPercent + "%";
        battery.setText((batteryCharging ? "⚡ " : "") + percent);
        battery.setTextColor(batteryCharging ? MINT : SOFT);
        battery.setContentDescription(batteryPercent < 0 ? "Bateria indisponível" :
            "Bateria " + percent + (batteryCharging ? ", carregando" : ", sem carregar"));
    }

    boolean landscape() {
        return getResources().getConfiguration().screenWidthDp >= 620;
    }

    private void controls() {
        LinearLayout sidebar = column();
        LinearLayout deck = column();
        commandGrid = deck;
        LinearLayout widgets = column();
        boolean wide = landscape();
        scroll.setVerticalScrollBarEnabled(!wide);
        if (wide) {
            LinearLayout layout = row(body);
            LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(0, -1, 26);
            side.setMargins(0, 0, dp(10), 0);
            layout.addView(sidebar, side);
            layout.addView(deck, new LinearLayout.LayoutParams(0, -1, 39));
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -1, 35);
            right.leftMargin = dp(9);
            layout.addView(widgets, right);
            int estimatedHeight = dp(getResources().getConfiguration().screenHeightDp - 96);
            layout.getLayoutParams().height = Math.max(dp(200), estimatedHeight);
            scroll.post(() -> {
                if (layout.getParent() == body && scroll.getHeight() > 0) {
                    layout.getLayoutParams().height = scroll.getHeight();
                    layout.requestLayout();
                }
            });
        } else {
            body.addView(sidebar);
            body.addView(deck);
            body.addView(widgets);
        }
        makeWidgets(widgets, wide);
        verse = new VerseCard(this, wide);
        verse.allowExternal(!armed() && !kioskPolicy.locked());
        verse.edition(settings.getString("verse-edition", "ara"));
        verse.artwork(verseArtwork);
        sidebar.addView(verse, new LinearLayout.LayoutParams(-1, wide ? 0 : dp(148), wide ? 1 : 0));
        refreshVerseCard();

        LinearLayout.LayoutParams weatherLayout = new LinearLayout.LayoutParams(-1, dp(65));
        weatherLayout.topMargin = dp(6);
        weatherText = text("", 13, INK);
        weatherText.setGravity(Gravity.CENTER_VERTICAL);
        weatherText.setMaxLines(3);
        weatherText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        weatherText.setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1,
            android.util.TypedValue.COMPLEX_UNIT_SP);
        weatherText.setPadding(dp(11), 0, dp(10), 0);
        sidebar.addView(weatherText, weatherLayout);
        refreshWeatherText();

        summary = new MiniMetrics();
        summary.setOnClickListener(view -> show(1, true));
        summary.setFocusable(true);
        LinearLayout.LayoutParams metricsLayout = new LinearLayout.LayoutParams(-1, dp(57));
        metricsLayout.topMargin = dp(6);
        sidebar.addView(summary, metricsLayout);
        summary.update();

        List<List<DeckConfig.Button>> availablePages = new ArrayList<>();
        if (remote != null && !remote.pages.isEmpty()) {
            for (DeckConfig.Page page : remote.pages) {
                List<DeckConfig.Button> keys = new ArrayList<>();
                for (DeckConfig.Button key : page.buttons) {
                    if (settings.getBoolean("visible-" + key.action, true)) keys.add(key);
                }
                availablePages.add(keys);
            }
        } else {
            List<DeckConfig.Button> keys = new ArrayList<>();
            for (int i = 0; i < BUTTON_IDS.length; i++) {
                if (isVisible(i)) keys.add(new DeckConfig.Button(BUTTON_NAMES[i], BUTTON_ICONS[i], BUTTON_IDS[i],
                    "", BUTTON_IDS[i].equals("lock") ? "red" : "graphite"));
            }
            int limit = wide ? 9 : 6;
            for (int offset = 0; offset < keys.size(); offset += limit)
                availablePages.add(keys.subList(offset, Math.min(offset + limit, keys.size())));
        }
        if (availablePages.isEmpty()) availablePages.add(new ArrayList<>());
        int pages = availablePages.size();
        pageCount = pages;
        shortcutPage = Math.max(0, Math.min(shortcutPage, pages - 1));
        settings.edit().putInt("shortcut-page", shortcutPage).apply();
        List<DeckConfig.Button> visible = availablePages.get(shortcutPage);
        LinearLayout selector = new LinearLayout(this);
        selector.setBaselineAligned(false);
        selector.setGravity(Gravity.CENTER);
        TextView previous = tile("previous", "", INK, CARD, () -> movePage(-1, pages));
        previous.setContentDescription("Anterior");
        addPageControl(selector, previous, 37);
        TextView count = text("Página " + (shortcutPage + 1) + " de " + pages, 12, INK);
        count.setGravity(Gravity.CENTER);
        addPageControl(selector, count, 99);
        TextView following = tile("next", "", INK, CARD, () -> movePage(1, pages));
        following.setContentDescription("Próxima");
        addPageControl(selector, following, 37);
        selector.getChildAt(0).setEnabled(shortcutPage > 0);
        selector.getChildAt(0).setAlpha(shortcutPage > 0 ? 1 : .45f);
        selector.getChildAt(2).setEnabled(shortcutPage + 1 < pages);
        selector.getChildAt(2).setAlpha(shortcutPage + 1 < pages ? 1 : .45f);
        if (visible.isEmpty()) {
            addWeighted(row(deck), tile("settings", "Escolher botões", INK, CARD, this::openEditor), 72);
            deck.addView(selector);
            return;
        }
        int columns = wide ? 3 : 2;
        for (int from = 0; from < visible.size(); from += columns) {
            LinearLayout line = new LinearLayout(this);
            line.setBaselineAligned(false);
            deck.addView(line, new LinearLayout.LayoutParams(-1, wide ? 0 : dp(112), wide ? 1 : 0));
            for (int index = from; index < Math.min(from + columns, visible.size()); index++) {
                DeckConfig.Button button = visible.get(index);
                DeckButton3D card = bigButton(button);
                addWeighted(line, card, -1);
                LinearLayout.LayoutParams keySpacing = (LinearLayout.LayoutParams) card.getLayoutParams();
                keySpacing.setMargins(dp(4), dp(wide ? 8 : 4), dp(4), dp(wide ? 8 : 4));
                card.setLayoutParams(keySpacing);
                if (button.action.equals("microphone")) {
                    microphone = card;
                    refreshMicrophone();
                }
            }
        }
        deck.addView(selector);
    }

    private void makeWidgets(LinearLayout container, boolean wide) {
        LinearLayout player = column();
        player.setPadding(dp(11), dp(10), dp(11), dp(9));
        player.setBackground(shape(CARD, 15));
        player.setForeground(new VisualAssets.Frame(dp(15),dp(1)));
        container.addView(player, new LinearLayout.LayoutParams(-1, wide ? 0 : dp(238), wide ? 1 : 0));
        TextView heading = text("Mídia do Windows", 13, 0xfff16b75);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        player.addView(heading);
        LinearLayout track = new LinearLayout(this);
        track.setGravity(Gravity.CENTER_VERTICAL);
        player.addView(track, new LinearLayout.LayoutParams(-1, 0, 1));
        album = new ImageView(this);
        album.setScaleType(ImageView.ScaleType.CENTER_CROP);
        album.setBackground(shape(0xff101014, 9));
        album.setClipToOutline(true);
        LinearLayout.LayoutParams coverSize = new LinearLayout.LayoutParams(dp(78), dp(78));
        coverSize.rightMargin = dp(9);
        track.addView(album, coverSize);
        LinearLayout details = column();
        track.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
        mediaTitle = text("Sem mídia ativa", 17, INK);
        mediaTitle.setTypeface(Typeface.DEFAULT_BOLD);
        mediaTitle.setMaxLines(2);
        mediaTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(mediaTitle);
        mediaArtist = text("", 13, SOFT);
        mediaArtist.setSingleLine(true);
        mediaArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(mediaArtist);

        mediaProgress = new SeekBar(this);
        mediaProgress.setMax(1000);
        mediaProgress.setContentDescription("Posição da reprodução");
        mediaProgress.setProgressTintList(ColorStateList.valueOf(RED));
        mediaProgress.setThumbTintList(ColorStateList.valueOf(INK));
        mediaProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { draggingSeek = true; }
            @Override public void onProgressChanged(SeekBar bar, int position, boolean user) {
                if (user && media != null) {
                    double duration = media.optDouble("duration", 0);
                    mediaTime.setText(minutes(duration * position / 1000) + " / " + minutes(duration));
                    if (!draggingSeek) changeMedia("seek", "?fraction=" + position / 1000.0);
                }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                draggingSeek = false;
                changeMedia("seek", "?fraction=" + bar.getProgress() / 1000.0);
            }
        });
        player.addView(mediaProgress, new LinearLayout.LayoutParams(-1, dp(35)));
        mediaTime = text("", 12, SOFT);
        mediaTime.setPadding(dp(6), 0, 0, 0);
        player.addView(mediaTime);
        LinearLayout commands = mediaCommands = row(player);
        commands.setGravity(Gravity.CENTER_VERTICAL);
        shuffleKey = addTransport(commands, "shuffle", "Aleatório", () -> changeMedia("shuffle", ""), false);
        addTransport(commands, "previous", "Faixa anterior", () -> send("previous"), false);
        mediaToggle = addTransport(commands, "play", "Reproduzir / pausar", () -> send("media"), true);
        addTransport(commands, "next", "Próxima faixa", () -> send("next"), false);
        repeatKey = addTransport(commands, "repeat", "Repetição", () -> changeMedia("repeat", ""), false);

        LinearLayout profiles = new LinearLayout(this);
        profiles.setGravity(Gravity.CENTER_VERTICAL);
        profiles.setPadding(dp(5), 0, dp(4), 0);
        profiles.setBackground(shape(CARD, 13));
        profiles.setForeground(new VisualAssets.Frame(dp(13),dp(1)));
        LinearLayout.LayoutParams profileSize = new LinearLayout.LayoutParams(-1, dp(52));
        profileSize.topMargin = dp(6);
        container.addView(profiles, profileSize);
        String activeName = profileName(remote == null ? "" : remote.active);
        TextView current = tile("layers", "", INK, CARD, this::expandProfiles);
        current.setContentDescription("Perfis. Ativo: " + activeName + ". Expandir");
        setIcon(current, "layers", INK, 18);
        profiles.addView(current, new LinearLayout.LayoutParams(dp(33), dp(40)));
        TextView selected = text(activeName, 12, INK);
        selected.setSingleLine(true);
        selected.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selected.setOnClickListener(view -> expandProfiles());
        profiles.addView(selected, new LinearLayout.LayoutParams(0, -2, 1));
        String[] ids = {"dev", "reuniao", "lazer"};
        String[] icons = {"terminal", "users", "gamepad-2"};
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i];
            boolean active = remote != null && remote.active.equals(id);
            TextView shortcut = tile(icons[i], "", INK, active ? RED : CARD, () -> selectProfile(id, false));
            shortcut.setContentDescription(profileName(id));
            shortcut.setSelected(active);
            shortcut.setEnabled(connected && !profileName(id).equals("—"));
            profiles.addView(shortcut, new LinearLayout.LayoutParams(dp(36), dp(40)));
        }
        refreshMedia();
    }

    private String profileName(String id) {
        if (remote == null) return "—";
        for (int i = 0; i < remote.profiles.length(); i++) {
            JSONObject profile = remote.profiles.optJSONObject(i);
            if (profile != null && id.equals(profile.optString("id"))) return profile.optString("name");
        }
        return "—";
    }

    private void expandProfiles() {
        if (remote == null) return;
        LinearLayout options = column();
        options.setPadding(dp(22), dp(12), dp(22), 0);
        options.addView(text("Ativo: " + profileName(remote.active), 16, INK));
        android.widget.Switch automatic = new android.widget.Switch(this);
        automatic.setText("Troca automática");
        automatic.setChecked(remote.automatic);
        automatic.setEnabled(connected);
        automatic.setOnCheckedChangeListener((view, enabled) -> selectProfile(null, enabled));
        options.addView(automatic, new LinearLayout.LayoutParams(-1, dp(46)));
        new AlertDialog.Builder(this).setTitle("Perfis")
            .setView(options)
            .setPositiveButton("Escolher perfil", (dialog, which) -> chooseProfile())
            .setNeutralButton("Editar no Windows", (dialog, which) -> openProfileStudio())
            .setNegativeButton("Fechar", null).show();
    }

    private void openProfileStudio() {
        if (!connected) {
            Toast.makeText(this, "Notebook desconectado", Toast.LENGTH_SHORT).show();
            return;
        }
        worker.execute(() -> {
            boolean opened;
            try { request("POST", "/v1/studio/open", 204); opened = true; }
            catch (Exception failure) { opened = false; }
            boolean result = opened;
            main.post(() -> Toast.makeText(this, result ? "Studio aberto no Windows" : "Studio indisponível",
                Toast.LENGTH_SHORT).show());
        });
    }

    private TextView addTransport(LinearLayout line, String icon, String label, Runnable action, boolean primary) {
        TextView control = tile(icon, "", INK, primary ? RED : CARD, action);
        control.setContentDescription(label);
        setIcon(control, icon, INK, primary ? 24 : 18);
        addWeighted(line, control, primary ? 47 : 41);
        transport.add(control);
        return control;
    }

    private void changeMedia(String command, String parameters) {
        if (!connected) return;
        worker.execute(() -> {
            try { request("POST", "/v1/media/" + command + parameters, 204); }
            catch (Exception failure) {
                main.post(() -> Toast.makeText(this, "Controle indisponível nesta sessão", Toast.LENGTH_SHORT).show());
            }
            main.post(() -> { main.removeCallbacks(poll); if (active) main.post(poll); });
        });
    }

    private void chooseProfile() {
        if (remote == null) return;
        JSONArray profiles = remote.profiles;
        String[] names = new String[profiles.length()];
        for (int index = 0; index < names.length; index++) names[index] = profiles.optJSONObject(index).optString("name");
        new AlertDialog.Builder(this).setTitle("Perfis").setItems(names,
            (dialog, index) -> selectProfile(profiles.optJSONObject(index).optString("id"), false)).show();
    }

    private void selectProfile(String id, boolean automatic) {
        if (!connected) return;
        worker.execute(() -> {
            try {
                request("POST", "/v1/profile?auto=" + automatic + (id == null ? "" : "&id=" + Uri.encode(id)), 204);
                main.post(() -> {
                    deckCheckedAt = 0;
                    main.removeCallbacks(poll);
                    if (active) main.post(poll);
                });
            } catch (Exception error) {
                main.post(() -> Toast.makeText(this, "Não foi possível alterar o perfil", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void refreshMedia() {
        boolean playing = connected && media != null && media.optBoolean("active");
        for (TextView control : transport) { control.setEnabled(playing); control.setAlpha(playing ? 1 : .48f); }
        if (mediaTitle == null) return;
        int visibility = playing ? View.VISIBLE : View.GONE;
        for (View control : new View[] {album, mediaProgress, mediaTime, mediaCommands})
            if (control.getVisibility() != visibility) control.setVisibility(visibility);
        mediaTitle.setText(playing ? media.optString("title") : "Sem mídia ativa");
        mediaArtist.setText(playing ? media.optString("artist") : "Sessão do Windows");
        boolean running = playing && media.optBoolean("playing");
        setIcon(mediaToggle, running ? "pause" : "play", INK, 24);
        double duration = playing ? media.optDouble("duration", 0) : 0;
        double position = playing ? media.optDouble("position", 0) : 0;
        mediaProgress.setEnabled(playing && media.optBoolean("seek"));
        if (!draggingSeek) {
            mediaProgress.setProgress(duration > 0 ? (int) (1000 * position / duration) : 0);
            mediaTime.setText(duration > 0 ? minutes(position) + " / " + minutes(duration) : "—");
        }
        mediaOption(shuffleKey, playing && media.optBoolean("shuffle"), playing && media.optBoolean("shuffled"), "Aleatório");
        String repeat = playing ? media.optString("repeatMode", "None") : "None";
        mediaOption(repeatKey, playing && media.optBoolean("repeat"), !repeat.equals("None") && !repeat.equals("null"),
            repeat.equals("Track") ? "Repetir faixa" : "Repetição");
        setIcon(repeatKey, repeat.equals("Track") ? "repeat-1" : "repeat", INK, 18);
        String key = playing && media.optBoolean("artwork") ? media.optString("key") : "";
        if (!key.equals(coverKey)) {
            coverKey = key;
            cover = null;
            if (!key.isEmpty()) internet.execute(() -> loadCover(key));
        }
        album.setImageBitmap(cover);
    }

    private void mediaOption(TextView control, boolean supported, boolean selected, String label) {
        control.setEnabled(supported);
        control.setAlpha(supported ? 1 : .42f);
        if (control.isSelected() != selected) control.setBackground(keycap(10, false, selected));
        control.setSelected(selected);
        control.setContentDescription(label + (supported ? (selected ? ", ativo" : ", inativo") : ", indisponível"));
    }

    private String minutes(double time) {
        int seconds = (int) Math.max(0, time);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void loadCover(String key) {
        HttpURLConnection connection = null;
        Bitmap image = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:48627/v1/media/cover").openConnection();
            connection.setConnectTimeout(1100);
            connection.setReadTimeout(4500);
            connection.setRequestProperty("Authorization", "Bearer " + Pairing.TOKEN);
            if (connection.getResponseCode() == 200) {
                try (InputStream data = connection.getInputStream()) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 2;
                    image = BitmapFactory.decodeStream(data, null, options);
                }
            }
        } catch (Exception ignored) { }
        finally { if (connection != null) connection.disconnect(); }
        Bitmap result = image;
        main.post(() -> {
            if (!isDestroyed() && key.equals(coverKey)) {
                cover = result;
                if (album != null) album.setImageBitmap(cover);
            }
        });
    }

    private void refreshNetwork() {
        if (networkLabel == null) return;
        networkLabel.setText(network == null ? "Rede · indisponível" : String.format(Locale.getDefault(),
            "Rede   ↓ %.1f KB/s   ↑ %.1f KB/s", network.optDouble("down") / 1024, network.optDouble("up") / 1024));
    }

    private void refreshHistory() {
        if (historyLabel == null) return;
        if (remote == null || remote.history.length() == 0) {
            historyLabel.setText("Nenhuma execução registrada. Crie macros no Studio no notebook.");
            return;
        }
        StringBuilder runs = new StringBuilder("Execuções recentes");
        for (int index = 0; index < Math.min(remote.history.length(), 5); index++) {
            JSONObject item = remote.history.optJSONObject(index);
            if (item == null) continue;
            String time = item.optString("time");
            String when;
            try {
                when = java.time.OffsetDateTime.parse(time).atZoneSameInstant(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm"));
            } catch (java.time.DateTimeException invalid) { when = ""; }
            runs.append("\n").append(item.optString("name")).append(" · ").append(item.optString("state"))
                .append("   ").append(when);
        }
        historyLabel.setText(runs);
    }

    private void applyPowerPolicy() {
        boolean dedicated = armed();
        long now = android.os.SystemClock.elapsedRealtime();
        if (connected) offlineSince = 0;
        else if (offlineSince == 0) offlineSince = now;
        boolean sleeping = !connected && now - offlineSince >= 5000;
        boolean tooHot = thermal >= android.os.PowerManager.THERMAL_STATUS_SEVERE;
        boolean economy = economyEnabled();
        boolean idleOnBattery = economy && !plugged && now - lastTouch >= 45_000;
        android.view.WindowManager.LayoutParams attributes = getWindow().getAttributes();
        float target = tooHot || sleeping || idleOnBattery ? .08f : economy
            ? (float) Math.max(.13, Math.min(plugged ? .42 : .32,
                ambient < 0 ? .26 : .13 + Math.log10(1 + ambient) * .10))
            : dedicated && ambient >= 0
            ? (float) Math.max(.12, Math.min(.7, .13 + Math.log10(1 + ambient) * .16))
            : android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (Math.abs(attributes.screenBrightness - target) > .025f) {
            attributes.screenBrightness = target;
            getWindow().setAttributes(attributes);
        }
        boolean keepAwake = !tooHot && !sleeping && !idleOnBattery;
        int flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        if (keepAwake != ((attributes.flags & flag) != 0)) {
            if (keepAwake) getWindow().addFlags(flag);
            else getWindow().clearFlags(flag);
        }
    }

    private boolean economyEnabled() { return settings != null && settings.getBoolean("deck-economy", true); }

    private boolean armed() { return settings != null && settings.getBoolean(KioskPolicy.ARMED, false); }

    private void enforceKiosk() {
        if (!armed() || !kioskPolicy.owner() || !kioskPin.configured()) return;
        immersive = true;
        setRequestedOrientation(landscapeSide());
        applyFullscreen();
        try {
            kioskPolicy.enter(this);
            kioskError = "";
        } catch (RuntimeException policyFailed) {
            kioskError = "Android não confirmou a entrada em Lock Task.";
            if (!kioskPolicy.locked()) {
                try { kioskPolicy.release(this); }
                catch (RuntimeException rollbackFailed) {
                    kioskError = "Políticas parcialmente aplicadas. Use o PIN para liberar ou recupere pelo ADB.";
                }
            }
        }
        updateKioskStatus();
    }

    private EditText pinField() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        return field;
    }

    private void setupPin() {
        if (kioskPin.configured()) return;
        LinearLayout inputs = column();
        EditText pin = pinField();
        EditText repeat = pinField();
        pin.setHint("PIN");
        repeat.setHint("Repita o PIN");
        inputs.addView(pin);
        inputs.addView(repeat);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("PIN administrativo")
            .setMessage("Escolha de 6 a 10 dígitos e guarde o PIN em local seguro.").setView(inputs)
            .setNegativeButton("Cancelar", null).setPositiveButton("Salvar", (window, which) -> {
                String value = pin.getText().toString();
                if (!KioskPin.valid(value) || !value.equals(repeat.getText().toString())) {
                    Toast.makeText(this, "PIN inválido ou confirmação diferente", Toast.LENGTH_SHORT).show();
                    return;
                }
                worker.execute(() -> {
                    boolean stored;
                    try { kioskPin.setup(value); stored = true; }
                    catch (java.security.GeneralSecurityException | RuntimeException failure) { stored = false; }
                    boolean saved = stored;
                    main.post(() -> {
                        if (isDestroyed()) return;
                        Toast.makeText(this, saved ? "PIN configurado" : "Não foi possível proteger o PIN",
                            Toast.LENGTH_SHORT).show();
                        if (section == SETTINGS) display(false);
                    });
                });
            }).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }

    private void authenticate(Runnable authenticated) {
        long waiting = kioskPin.delaySeconds();
        if (waiting > 0) {
            Toast.makeText(this, "Aguarde " + waiting + " s para tentar novamente", Toast.LENGTH_SHORT).show();
            return;
        }
        EditText pin = pinField();
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("PIN administrativo").setView(pin)
            .setNegativeButton("Cancelar", null).setPositiveButton("Desbloquear", (window, which) -> {
                String value = pin.getText().toString();
                worker.execute(() -> {
                    boolean verified;
                    try { verified = kioskPin.verify(value); }
                    catch (java.security.GeneralSecurityException | RuntimeException failure) { verified = false; }
                    boolean accepted = verified;
                    main.post(() -> {
                        if (isDestroyed() || !active) return;
                        if (accepted) authenticated.run();
                        else Toast.makeText(this, "PIN não aceito. Verifique e tente novamente.",
                            Toast.LENGTH_SHORT).show();
                    });
                });
            }).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }

    private void enableKiosk() {
        if (!kioskPolicy.owner() || !kioskPin.configured()) {
            new AlertDialog.Builder(this).setTitle("Quiosque indisponível")
                .setMessage("Configure o PIN e provisione o RianDeckOS como Device Owner em um Android elegível. "
                    + "Consulte o procedimento de provisionamento no notebook.")
                .setPositiveButton("Fechar", null).show();
            return;
        }
        if (!settings.edit().putBoolean(KioskPolicy.ARMED, true).putBoolean("immersive", true).commit()) return;
        enforceKiosk();
        navigate(0, false);
    }

    private void unlockFullscreen() {
        if (armed() || kioskPolicy.locked() || kioskPin.configured()) {
            if (!kioskPin.configured()) return;
            authenticate(this::releaseFullscreen);
        } else {
            releaseFullscreen();
        }
    }

    private void releaseFullscreen() {
        if (armed() || kioskPolicy.locked()) {
            try { kioskPolicy.release(this); }
            catch (RuntimeException policyFailed) {
                kioskError = "Android não confirmou a saída do quiosque. Tente novamente.";
                updateKioskStatus();
                return;
            }
        }
        if (!settings.edit().putBoolean(KioskPolicy.ARMED, false).putBoolean("immersive", false).commit()) {
            enforceKiosk();
            return;
        }
        kioskError = "";
        immersive = false;
        applyFullscreen();
        applyPowerPolicy();
        navigate(3, false);
    }

    private void updateKioskStatus() {
        if (kioskStatus == null) return;
        boolean owner = kioskPolicy.owner(), locked = owner && kioskPolicy.locked();
        kioskStatus.setText("Device Owner: " + (owner ? "sim" : "não")
            + "   ·   Quiosque: " + (locked ? "ativo" : armed() ? "não confirmado" : "inativo")
            + "   ·   Boot: " + (locked ? "launcher registrado" : "via ADB, quando disponível")
            + "   ·   USB: " + (connected ? "conectado" : "desconectado")
            + "\nNavegação e notificações: " + (locked ? "bloqueadas por Lock Task" : "disponíveis no Android")
            + (kioskError.isEmpty() ? "" : "\n" + kioskError));
    }

    private void movePage(int direction, int pages) {
        int target = shortcutPage + direction;
        if (target < 0 || target >= pages) return;
        shortcutPage = target;
        float dragged = commandGrid == null ? 0 : Math.abs(commandGrid.getTranslationX());
        display(false);
        if (commandGrid != null) {
            commandGrid.setTranslationX(Math.signum(direction) * Math.min(dp(125), dp(42) + dragged * .5f));
            commandGrid.setAlpha(.82f);
            commandGrid.animate().translationX(0).alpha(1).setDuration(230)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f)).start();
        }
    }

    private void addPageControl(LinearLayout row, TextView view, int width) {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(width), dp(25));
        size.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.addView(view, size);
    }

    private DeckButton3D bigButton(DeckConfig.Button key) {
        DeckButton3D view = new DeckButton3D(this);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setTextColor(INK);
        view.setTextSize(13);
        view.setPadding(dp(4), dp(4), dp(4), dp(7));
        view.setMaxLines(2);
        view.setShadowLayer(dp(1), 0, dp(1), 0xff131315);
        view.setHapticFeedbackEnabled(true);
        view.setElevation(dp(4));
        view.setFocusable(true);
        view.configure(key.label, DeckTheme.KEY, key.color.equals("red"));
        keySymbol(view, key.icon);
        if (key.folder.isEmpty()) {
            remoteKeys.add(view);
            view.availability(connected);
        }
        view.setOnClickListener(control -> {
            if (!key.folder.isEmpty()) {
                if (remote == null) return;
                for (int i = 0; i < remote.pages.size(); i++) {
                    if (remote.pages.get(i).id.equals(key.folder)) { movePage(i - shortcutPage, remote.pages.size()); break; }
                }
                return;
            }
            boolean confirm = key.action.equals("lock") || (key.action.startsWith("macro:")
                && remote != null && remote.confirmation(key.action.substring(6)));
            if (confirm) new AlertDialog.Builder(this).setMessage("Executar “" + key.label + "” no notebook?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar", (dialog, which) -> send(key.action, view, true)).show();
            else send(key.action, view, false);
        });
        return view;
    }

    private void keySymbol(DeckButton3D view, String name) {
        Drawable icon = VisualAssets.icon(this, name);
        if (icon != null && !java.util.Arrays.asList("spotify", "youtube", "steam", "codex", "cursor", "miro", "serpro").contains(name))
            icon.setTint(INK);
        view.symbol(icon, dp(landscape() ? 42 : 48));
    }

    private Drawable keycap(int radius, boolean prominent, boolean red) {
        return new VisualAssets.Keycap(dp(radius), getResources().getDisplayMetrics().density, prominent, red);
    }

    private boolean isVisible(int index) {
        return settings.getBoolean("visible-" + BUTTON_IDS[index], true);
    }

    private void openEditor() {
        showButtonEditor();
    }

    private void showButtonEditor() {
        section = SETTINGS;
        editingButtons = true;
        display(true);
    }

    private void editButtons() {
        heading(body, "Botões visíveis");
        addWeighted(row(body), tile("settings", "Voltar às configurações", MINT, CARD,
            () -> show(AUTOMATIONS, true)), 43);
        boolean wide = landscape();
        int columns = wide ? 3 : 1;
        LinearLayout line = null;
        for (int index = 0; index < BUTTON_IDS.length; index++) {
            if (index % columns == 0) line = row(body);
            final int button = index;
            CheckBox toggle = new CheckBox(this);
            toggle.setText(BUTTON_NAMES[index]);
            toggle.setTextSize(15);
            toggle.setTextColor(INK);
            toggle.setPadding(dp(9), 0, dp(6), 0);
            toggle.setBackground(shape(CARD, 12));
            toggle.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{MINT, SOFT}));
            toggle.setChecked(isVisible(index));
            toggle.setOnCheckedChangeListener((view, checked) -> settings.edit()
                .putBoolean("visible-" + BUTTON_IDS[button], checked).apply());
            addWeighted(line, toggle, wide ? 38 : 49);
        }
    }

    private void refreshVerseCard() {
        if (verse != null) {
            verse.update(dailyVerse, verseLoading, !java.time.LocalDate.now().toString().equals(verseDate));
        }
    }

    private long verseDelay(long fetchedAt, String recordedDay, String today, long now) {
        long remaining = fetchedAt + VERSE_INTERVAL - now;
        return fetchedAt > 0 && fetchedAt <= now && today.equals(recordedDay) && remaining > 0
            ? remaining : 0;
    }

    private void scheduleBibleRefresh() {
        main.removeCallbacks(refreshBible);
        if (!active) return;
        long now = System.currentTimeMillis();
        String day = java.time.LocalDate.now().toString();
        long text = verseDelay(verseFetchedAt, verseDate, day, now);
        long art = verseDelay(artworkFetchedAt, artworkDate, day, now);
        long nextPhoto = VerseCard.PHOTO_INTERVAL - Math.floorMod(now, VerseCard.PHOTO_INTERVAL);
        main.postDelayed(refreshBible, Math.min(nextPhoto,
            Math.min(text > 0 ? text : VERSE_RETRY, art > 0 ? art : VERSE_RETRY)));
    }

    private void loadVerse() {
        if (verse != null) verse.refreshPhoto();
        if (verseLoading || isDestroyed()) return;
        String today = java.time.LocalDate.now().toString();
        String edition = settings.getString("verse-edition", "ara");
        loadVerseArtwork(today);
        if (dailyVerse != null && verseDelay(verseFetchedAt, verseDate, today, System.currentTimeMillis()) > 0) {
            scheduleBibleRefresh();
            return;
        }
        verseLoading = true;
        refreshVerseCard();
        internet.execute(() -> {
            VerseCard.Verse result;
            try {
                JSONObject response = new JSONObject(request("GET", "/v1/verse?edition=" + edition, 200));
                result = new VerseCard.Verse(response.getString("reference"), response.getString("passage"));
            } catch (Exception agentUnavailable) {
                try { result = VerseCard.fetch(edition); }
                catch (Exception error) { result = null; }
            }
            VerseCard.Verse received = result;
            main.post(() -> {
                if (isDestroyed()) return;
                verseLoading = false;
                if (!edition.equals(settings.getString("verse-edition", "ara"))) {
                    if (active) loadVerse();
                    return;
                }
                if (received != null) {
                    dailyVerse = received;
                    verseDate = today;
                    verseFetchedAt = System.currentTimeMillis();
                    settings.edit().putString("verse-date", today).putLong("verse-fetched-at", verseFetchedAt)
                        .putString("verse-reference", received.reference)
                        .putString("verse-text", received.passage).apply();
                }
                refreshVerseCard();
                scheduleBibleRefresh();
            });
        });
    }

    private void loadVerseArtwork(String today) {
        if (artworkLoading || verseDelay(artworkFetchedAt, artworkDate, today, System.currentTimeMillis()) > 0) return;
        artworkLoading = true;
        internet.execute(() -> {
            Bitmap picture = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("http://127.0.0.1:48627/v1/verse-art").openConnection();
                connection.setConnectTimeout(1200);
                connection.setReadTimeout(5500);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Authorization", "Bearer " + Pairing.TOKEN);
                connection.setUseCaches(false);
                if (connection.getResponseCode() == 200) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 2;
                    try (InputStream input = connection.getInputStream()) {
                        picture = BitmapFactory.decodeStream(input, null, options);
                    }
                }
            } catch (Exception unavailable) {
                // The photo bundled with the APK remains visible when the daily artwork is unavailable.
            } finally {
                if (connection != null) connection.disconnect();
            }
            Bitmap received = picture;
            main.post(() -> {
                artworkLoading = false;
                if (isDestroyed()) return;
                if (received != null) {
                    verseArtwork = received;
                    artworkDate = today;
                    artworkFetchedAt = System.currentTimeMillis();
                    if (verse != null) verse.artwork(received);
                }
                scheduleBibleRefresh();
            });
        });
    }

    private void refreshWeatherText() {
        if (weatherText == null) return;
        String photo = weather == null ? "cloud" : weather.wet() ? "rain"
            : !weather.day ? "night" : weather.code <= 1 ? "sun" : "cloud";
        Bitmap backdrop = VisualAssets.bitmap(this, "photos/" + photo + ".jpg");
        weatherText.setBackground(new VisualAssets.Cover(backdrop, CARD, dp(12), false, photo.equals("night")));
        weatherText.setForeground(new VisualAssets.Frame(dp(12),dp(1)));
        weatherText.setShadowLayer(dp(3), 0, dp(1), 0xff000000);
        String temperature = weather == null ? "" : weather.degrees + "°C";
        String condition = weather == null ? (weatherLoading ? "Consultando clima…" : "Clima indisponível")
            : weather.description();
        android.text.SpannableString details = new android.text.SpannableString(
            (temperature.isEmpty() ? "" : temperature + "\n") + condition + "\n"
                + city.name + (weatherStale && weather != null ? " · leitura anterior" : ""));
        if (!temperature.isEmpty()) details.setSpan(new android.text.style.AbsoluteSizeSpan(19, true),
            0, temperature.length(), 0);
        weatherText.setText(details);
        if (weatherUpdated > 0 && !weatherStale) {
            String last = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(weatherUpdated));
            // The timestamp is available to screen readers without crowding the weather card.
            weatherText.setContentDescription(weatherText.getText() + ", última atualização às " + last);
        }
        Drawable icon = VisualAssets.icon(this, "weather-" + weatherIcon());
        if (icon != null) icon.setBounds(0, 0, dp(37), dp(37));
        weatherText.setCompoundDrawables(null, null, icon, null);
        weatherText.setCompoundDrawablePadding(dp(2));
    }

    private String weatherIcon() {
        if (weather == null) return "04d";
        if (weather.storm()) return "11d";
        if (weather.snow()) return "13d";
        if (weather.wet()) return weather.code <= 57 ? "09d" : "10d";
        if (weather.code >= 45) return "50d";
        if (weather.code == 3) return "04d";
        if (weather.code == 0) return weather.day ? "01d" : "01n";
        return weather.day ? "02d" : "02n";
    }

    private void loadWeather() {
        if (weatherLoading || isDestroyed()) return;
        Weather.City requested = city;
        weatherLoading = true;
        refreshWeatherText();
        internet.execute(() -> {
            Weather result;
            try { result = Weather.current(requested); }
            catch (Exception error) { result = null; }
            Weather received = result;
            main.post(() -> {
                if (isDestroyed()) return;
                weatherLoading = false;
                if (city != requested) {
                    if (active) loadWeather();
                    return;
                }
                if (received != null) {
                    weather = received;
                    weatherUpdated = System.currentTimeMillis();
                }
                weatherStale = received == null;
                refreshWeatherText();
                main.removeCallbacks(refreshSky);
                if (active) main.postDelayed(refreshSky, received == null ? 60000 : 600000);
            });
        });
    }

    private void chooseCity() {
        EditText name = new EditText(this);
        name.setSingleLine();
        name.setHint("Ex.: Campinas");
        new AlertDialog.Builder(this).setTitle("Buscar cidade").setView(name)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Buscar", (dialog, which) -> {
                String query = name.getText().toString().trim();
                if (query.length() < 2) {
                    Toast.makeText(this, "Digite pelo menos duas letras", Toast.LENGTH_SHORT).show();
                    return;
                }
                internet.execute(() -> {
                    List<Weather.City> cities;
                    try { cities = Weather.search(query); }
                    catch (Exception error) { cities = null; }
                    List<Weather.City> results = cities;
                    main.post(() -> {
                        if (isDestroyed() || !active) return;
                        if (results == null || results.isEmpty()) {
                            Toast.makeText(this, results == null ? "Busca indisponível" : "Nenhuma cidade encontrada",
                                Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String[] labels = new String[results.size()];
                        for (int i = 0; i < labels.length; i++) labels[i] = results.get(i).name;
                        new AlertDialog.Builder(this).setTitle("Cidade do clima")
                            .setItems(labels, (picker, selected) -> setCity(results.get(selected))).show();
                    });
                });
            }).show();
    }

    private void setCity(Weather.City selection) {
        city = selection;
        settings.edit().putString("city", city.name)
            .putLong("latitude", Double.doubleToLongBits(city.latitude))
            .putLong("longitude", Double.doubleToLongBits(city.longitude)).apply();
        weather = null;
        weatherUpdated = 0;
        display(false);
        loadWeather();
    }

    private void monitoring() {
        TextView title = text("Monitoramento", 16, INK);
        title.setPadding(dp(4), 0, 0, dp(4));
        body.addView(title);
        int columns = landscape() ? 4 : 2;
        LinearLayout line = null;
        for (int i = 0; i < graphs.length; i++) {
            if (i % columns == 0) line = row(body);
            Graph graph = new Graph(i);
            graphs[i] = graph;
            addWeighted(line, graph, 144);
            graph.update(connected);
        }
    }

    private double threshold(int metric, boolean critical) {
        return Float.intBitsToFloat(settings.getInt("meme-threshold-" + metric + "-" + critical,
            Float.floatToIntBits((float) (critical ? CRITICAL[metric] : WARN[metric]))));
    }

    private void memeSettings() {
        LinearLayout controls = column();
        controls.setPadding(dp(16), 0, dp(16), 0);
        android.widget.Switch enabled = new android.widget.Switch(this);
        enabled.setText("Animar GIFs");
        enabled.setChecked(settings.getBoolean("memes-enabled", true));
        controls.addView(enabled);
        android.widget.Spinner metrics = new android.widget.Spinner(this);
        metrics.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, METRIC_NAMES));
        metrics.setContentDescription("Componente");
        controls.addView(metrics);
        android.widget.Spinner states = new android.widget.Spinner(this);
        states.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, STATES));
        states.setContentDescription("Estado");
        controls.addView(states);
        TextView reaction = text("", 14, SOFT);
        controls.addView(reaction);
        MemeStatus preview = new MemeStatus(this);
        controls.addView(preview, new LinearLayout.LayoutParams(dp(90), dp(62)));
        Runnable updatePreview = () -> {
            int metric = metrics.getSelectedItemPosition(), state = states.getSelectedItemPosition();
            if (metric < 0 || state < 0) return;
            String slot = METRIC_IDS[metric] + "-" + state;
            java.io.File gif = new java.io.File(getFilesDir(), "meme-" + slot + ".gif");
            boolean bundled = MemeStatus.bundled(DeckActivity.this, slot) != 0;
            reaction.setText(REACTIONS[metric][state] + (gif.isFile() ? " · GIF local"
                : bundled ? " · GIF incluído" : " · aguardando GIF"));
            preview.update(slot, METRIC_EMOJIS[metric], REACTIONS[metric][state], enabled.isChecked());
        };
        enabled.setOnCheckedChangeListener((view, value) -> {
            settings.edit().putBoolean("memes-enabled", value).apply();
            updatePreview.run();
        });
        android.widget.AdapterView.OnItemSelectedListener listener = new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updatePreview.run();
            }
        };
        metrics.setOnItemSelectedListener(listener);
        states.setOnItemSelectedListener(listener);
        new AlertDialog.Builder(this).setTitle("Monitoramento · Memes").setView(controls)
            .setNegativeButton("Fechar", null)
            .setNeutralButton("Limites", (dialog, which) -> editThresholds(metrics.getSelectedItemPosition()))
            .setPositiveButton("Selecionar GIF", (dialog, which) -> pickMeme(metrics.getSelectedItemPosition(), states.getSelectedItemPosition()))
            .show();
    }

    private void editThresholds(int metric) {
        if (metric < 0) return;
        LinearLayout fields = column();
        fields.setPadding(dp(16), 0, dp(16), 0);
        EditText attention = new EditText(this), critical = new EditText(this);
        for (EditText input : new EditText[]{attention, critical}) {
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        }
        attention.setText(Float.toString((float) threshold(metric, false)));
        critical.setText(Float.toString((float) threshold(metric, true)));
        fields.addView(text("Atenção" + METRIC_UNITS[metric], 14, INK));
        fields.addView(attention);
        fields.addView(text("Crítico" + METRIC_UNITS[metric], 14, INK));
        fields.addView(critical);
        new AlertDialog.Builder(this).setTitle("Limites · " + METRIC_NAMES[metric]).setView(fields)
            .setNegativeButton("Cancelar", null).setPositiveButton("Salvar", (dialog, which) -> {
                try {
                    float a = Float.parseFloat(attention.getText().toString().replace(',', '.'));
                    float c = Float.parseFloat(critical.getText().toString().replace(',', '.'));
                    if (!Float.isFinite(a) || !Float.isFinite(c) || a < 0 || c < 0 ||
                        (METRIC_IDS[metric].equals("fps") ? a <= c : a >= c))
                        throw new IllegalArgumentException();
                    settings.edit().putInt("meme-threshold-" + metric + "-false", Float.floatToIntBits(a))
                        .putInt("meme-threshold-" + metric + "-true", Float.floatToIntBits(c)).apply();
                    bands[metric] = new MemeStatus.Band();
                } catch (IllegalArgumentException error) {
                    Toast.makeText(this, "Limites inválidos ou fora de ordem", Toast.LENGTH_SHORT).show();
                }
            }).show();
    }

    private void pickMeme(int metric, int state) {
        if (metric < 0 || state < 0) return;
        if (armed() || kioskPolicy.locked()) {
            Toast.makeText(this, "Importe o GIF após liberar o quiosque", Toast.LENGTH_SHORT).show();
            return;
        }
        memeImportSlot = METRIC_IDS[metric] + "-" + state;
        android.content.Intent picker = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("image/gif");
        try { startActivityForResult(picker, 432); }
        catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "Seletor de arquivos indisponível", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int code, int result, android.content.Intent data) {
        super.onActivityResult(code, result, data);
        if (code != 432 || result != RESULT_OK || data == null || data.getData() == null || memeImportSlot == null) return;
        String slot = memeImportSlot;
        memeImportSlot = null;
        android.net.Uri uri = data.getData();
        internet.execute(() -> {
            boolean saved = false;
            java.io.File pending = new java.io.File(getFilesDir(), "meme-" + slot + ".tmp");
            try (InputStream input = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream output = new java.io.FileOutputStream(pending)) {
                byte[] header = new byte[6];
                new java.io.DataInputStream(input).readFully(header);
                String format = new String(header, StandardCharsets.US_ASCII);
                if (!format.equals("GIF87a") && !format.equals("GIF89a")) throw new java.io.IOException();
                output.write(header);
                byte[] buffer = new byte[8192];
                int count, total = header.length;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 4 * 1024 * 1024) throw new java.io.IOException();
                    output.write(buffer, 0, count);
                }
                output.flush();
                java.nio.file.Files.move(pending.toPath(), new java.io.File(getFilesDir(), "meme-" + slot + ".gif").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                saved = true;
            } catch (Exception ignored) { pending.delete(); }
            boolean complete = saved;
            main.post(() -> {
                if (isDestroyed()) return;
                Toast.makeText(this, complete ? "GIF salvo" : "GIF inválido ou maior que 4 MB", Toast.LENGTH_SHORT).show();
                if (section == 1) display(false);
            });
        });
    }

    private void automations() {
        heading(body, "Automações");
        addWeighted(row(body), tile("bolt", "Nova automação no Windows", INK, CARD, this::openEditor), 44);
        if (remote != null) {
            LinearLayout line = null;
            for (int i = 0; i < Math.min(4, remote.macros.length()); i++) {
                JSONObject macro = remote.macros.optJSONObject(i);
                if (macro == null) continue;
                if (i % 2 == 0) line = row(body);
                DeckConfig.Button key = new DeckConfig.Button(macro.optString("name"), "bolt",
                    "macro:" + macro.optString("id"), "", "graphite");
                TextView button = tile("bolt", key.label, INK, CARD, () -> runMacro(key));
                button.setEnabled(connected);
                addWeighted(line, button, 49);
            }
            if (remote.macros.length() > 4) {
                addWeighted(row(body), tile("bolt", "Todas as macros", INK, CARD, this::chooseMacro), 44);
            }
        }
        historyLabel = text("", 14, SOFT);
        historyLabel.setPadding(dp(5), dp(8), 0, 0);
        body.addView(historyLabel);
        refreshHistory();
    }

    private void chooseMacro() {
        if (remote == null) return;
        JSONArray entries = remote.macros;
        String[] names = new String[entries.length()];
        for (int i = 0; i < names.length; i++) names[i] = entries.optJSONObject(i).optString("name");
        new AlertDialog.Builder(this).setTitle("Macros").setItems(names, (dialog, index) -> {
            String id = entries.optJSONObject(index).optString("id");
            runMacro(new DeckConfig.Button(names[index], "bolt", "macro:" + id, "", "graphite"));
        }).show();
    }

    private void runMacro(DeckConfig.Button key) {
        if (remote != null && remote.confirmation(key.action.substring(6))) {
            new AlertDialog.Builder(this).setMessage("Executar “" + key.label + "” no notebook?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Executar", (dialog, which) -> send(key.action, null, true)).show();
        } else send(key.action, null, false);
    }

    private void preferences() {
        heading(body, "Aparência");
        LinearLayout panel = row(body);
        addWeighted(panel, tile("grid", "Escolher botões", INK, CARD, this::openEditor), 51);
        addWeighted(panel, tile("cloud", "Cidade · " + city.name, INK, CARD, this::chooseCity), 51);
        addWeighted(panel, tile("folder", "Versículo · " + settings.getString("verse-edition", "ara").toUpperCase(Locale.ROOT),
            INK, CARD, this::chooseVerse), 51);

        heading(body, "Conexão");
        LinearLayout connectionOptions = row(body);
        addWeighted(connectionOptions, tile("usb", "Inicialização do Windows", INK, CARD,
            this::startupOptions), 46);
        heading(body, "Assistente");
        LinearLayout voiceOptions = row(body);
        addWeighted(voiceOptions, tile("mic", "Preferências de voz", INK, CARD, this::voiceOptions), 46);
        addWeighted(voiceOptions, tile("codex", "Verificar Codex", INK, CARD, this::assistantStatus), 46);
        addWeighted(row(body), tile("mic", "Testar entrada do Windows", INK, CARD, () -> {
            new AlertDialog.Builder(this).setTitle("Testar entrada do Windows")
                .setMessage("O agente vai fazer uma captura curta na entrada padrão do Windows, sem enviar áudio à internet.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Testar", (d, w) -> worker.execute(() -> {
                    try {
                        JSONObject reply = new JSONObject(request("POST", "/v1/assistant/windows-mic", 200, new byte[0], "application/octet-stream"));
                        main.post(() -> assistantNotice(reply.optString("text")));
                    } catch (Exception e) { main.post(() -> assistantNotice("Agente Windows indisponível.")); }
                })).show();
        }), 46);
        heading(body, "Monitoramento");
        addWeighted(row(body), tile("chart", "Memes", INK, CARD, this::memeSettings), 45);
        heading(body, "Sistema");
        addWeighted(row(body), tile("power", economyEnabled() ? "Economia de bateria · ativa" : "Economia de bateria · desativada",
            INK, CARD, () -> {
                settings.edit().putBoolean("deck-economy", !economyEnabled()).apply();
                applyPowerPolicy();
                display(false);
            }), 46);
        LinearLayout displayOptions = row(body);
        boolean portrait = getResources().getConfiguration().orientation
            == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        if (!armed()) addWeighted(displayOptions, tile("rotate", portrait ? "Girar para horizontal" : "Girar para vertical",
            VIOLET, CARD, () -> {
                int orientation = portrait ? landscapeSide()
                    : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                settings.edit().putInt("orientation", orientation).apply();
                setRequestedOrientation(orientation);
            }), 51);
        if (!portrait) {
            addWeighted(displayOptions, tile("rotate", "Inverter paisagem (180°)", INK, CARD, () -> {
                int side = landscapeSide() == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                settings.edit().putInt("landscape-side", side).putInt("orientation", side).apply();
                setRequestedOrientation(side);
            }), 51);
        }
        addWeighted(displayOptions, tile("fullscreen", immersive ? "Desbloquear tela cheia" : "Bloquear em tela cheia",
            INK, CARD, () -> {
                if (immersive || armed() || kioskPolicy.locked()) unlockFullscreen();
                else {
                    immersive = true;
                    settings.edit().putBoolean("immersive", true).apply();
                    applyFullscreen();
                    display(false);
                }
            }), 51);
        heading(body, "Modo dedicado");
        kioskStatus = text("", 13, SOFT);
        body.addView(kioskStatus);
        updateKioskStatus();
        LinearLayout kioskActions = row(body);
        if (!kioskPin.configured()) addWeighted(kioskActions, tile("lock", "Configurar PIN administrativo",
            INK, CARD, this::setupPin), 48);
        if (!armed()) addWeighted(kioskActions, tile("lock", "Ativar quiosque", INK, CARD, this::enableKiosk), 48);
    }

    private void chooseVerse() {
        String[] editions = {"ARA · Português", "ESV · Inglês"};
        new AlertDialog.Builder(this).setTitle("Edição do Biblia.com").setItems(editions, (dialog, selected) -> {
            String edition = selected == 0 ? "ara" : "esv";
            if (edition.equals(settings.getString("verse-edition", "ara"))) return;
            settings.edit().putString("verse-edition", edition).remove("verse-date")
                .remove("verse-text").remove("verse-reference").apply();
            dailyVerse = null;
            verseDate = "";
            verseFetchedAt = 0;
            main.removeCallbacks(refreshBible);
            if (active) main.post(refreshBible);
            display(false);
        }).show();
    }

    private int landscapeSide() {
        return settings.getInt("landscape-side", ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
    }

    private void send(String id) { send(id, null, false); }

    private void send(String id, DeckButton3D source, boolean confirm) {
        if (!connected) return;
        boolean microphoneAction = id.equals("microphone");
        if (microphoneAction && microphoneBusy) return;
        microphoneBusy = microphoneAction || microphoneBusy;
        if (source != null) source.executing();
        boolean macro = id.startsWith("macro:");
        String route = macro ? "/v1/macro/" + id.substring(6) + "?confirm=" + confirm : "/v1/action/" + id;
        worker.execute(() -> {
            boolean success;
            try { request("POST", route, macro ? 202 : 204); success = true; }
            catch (Exception error) { success = false; }
            boolean sent = success;
            main.post(() -> {
                if (isDestroyed()) return;
                if (source != null) source.complete(sent);
                if (microphoneAction) microphoneBusy = false;
                main.removeCallbacks(poll);
                if (active) main.post(poll);
                if (!sent) Toast.makeText(this, "Ação indisponível no notebook", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void pollStatus() {
        if (!active || pollRunning) return;
        pollRunning = true;
        final int requestedSection = section;
        boolean metricsNeeded = requestedSection == 0 || requestedSection == 1;
        final boolean economy = economyEnabled();
        final long started = android.os.SystemClock.elapsedRealtime();
        boolean deckNeeded = requestedSection == AUTOMATIONS || requestedSection == 0 &&
            (!economy || remote == null || started - deckCheckedAt >= 12_000);
        telemetry.execute(() -> {
            JSONObject sample = readJson(metricsNeeded ? "/v1/status" : "/v1/ping");
            JSONObject task = sample != null && requestedSection == ASSISTANT ? readJson("/v1/operator") : null;
            JSONObject configuration = sample == null || !deckNeeded ? null : readJson("/v1/deck");
            JSONObject playing = sample == null || requestedSection != 0 ? null : readJson("/v1/media");
            main.post(() -> {
                pollRunning = false;
                if (!active) return;
                boolean wasConnected = connected;
                connected = sample != null;
                if (!connected || !wasConnected) deckCheckedAt = 0;
                if (configuration != null) deckCheckedAt = started;
                refreshHeader();
                if (section == ASSISTANT) { assistant.connection(connected); if (requestedSection == ASSISTANT) assistant.task(task); }
                if (metricsNeeded || !connected)
                    micMuted = sample == null || sample.isNull("microphone") ? null : sample.optBoolean("microphone");
                for (DeckButton3D key : remoteKeys) key.availability(connected);
                refreshMicrophone();
                if (metricsNeeded) {
                    network = sample == null ? null : sample.optJSONObject("network");
                }
                for (int i = 0; metricsNeeded && i < histories.length; i++) {
                    String id = METRIC_IDS[i];
                    double value = sample == null ? Double.NaN : sample.optDouble(id);
                    if (id.equals("network") && network != null)
                        value = (network.optDouble("down") + network.optDouble("up")) / 1024;
                    if (Double.isFinite(value)) histories[i].add(value);
                    else histories[i].values.clear();
                    levels[i] = bands[i].update(value, threshold(i, false), threshold(i, true),
                        id.equals("fps"), android.os.SystemClock.elapsedRealtime());
                    if (graphs[i] != null) graphs[i].update(connected);
                }
                if (requestedSection == 0 || !connected) media = playing;
                applyDeck(configuration);
                if (summary != null) summary.update();
                refreshMedia();
                refreshHistory();
                refreshNetwork();
                applyPowerPolicy();
                main.removeCallbacks(poll);
                long interval = !connected ? (economy ? 5000 : 2000)
                    : economy && (requestedSection == 0 || requestedSection == 1 && !plugged) ? 4000
                    : metricsNeeded ? 2000 : 3500;
                main.postDelayed(poll, section != requestedSection ? 0 : interval);
            });
        });
    }

    void openTrendArticle(String id) {
        if (!id.matches("[0-9]+")) return;
        worker.execute(() -> {
            try { request("POST", "/v1/trends/news/" + id, 204); }
            catch (Exception error) {
                main.post(() -> assistantNotice("Não foi possível abrir a notícia no Windows."));
            }
        });
    }

    private JSONObject readJson(String route) {
        try { return new JSONObject(request("GET", route, 200)); }
        catch (Exception error) { return null; }
    }

    private static String fingerprint(JSONObject value) {
        return value.optString("active") + "/" + value.optInt("revision") + "/" + value.optString("pages");
    }

    private void applyDeck(JSONObject data) {
        if (data == null) return;
        DeckConfig current = new DeckConfig(data);
        if (current.pages.isEmpty()) return;
        String updated = fingerprint(data);
        boolean rebuild = !updated.equals(deckFingerprint);
        if (rebuild) {
            if (remote != null && !remote.active.equals(current.active)) shortcutPage = 0;
            deckFingerprint = updated;
            settings.edit().putString("deck-cache", data.toString()).apply();
        }
        remote = current;
        if (rebuild && (section == 0 || section == AUTOMATIONS)) display(false);
    }

    private void refreshMicrophone() {
        if (microphone == null) return;
        boolean muted = Boolean.TRUE.equals(micMuted);
        microphone.configure(micMuted == null ? "MIC indisponível" : muted ? "MIC silenciado" : "Silenciar MIC",
            DeckTheme.KEY, muted);
        keySymbol(microphone, muted ? "mic-off" : "mic");
        microphone.availability(connected && micMuted != null);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == VoiceDeck.PERMISSION)
            assistant.permission(results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }

    void operatorDecision(String id, String decision) {
        if (!id.matches("[0-9a-f]{32}")) return;
        worker.execute(() -> {
            try {
                request("POST", "/v1/operator/" + id + "/" + decision, 204);
                main.post(() -> { main.removeCallbacks(poll); if (active) main.post(poll); });
            } catch (Exception failure) { main.post(() -> assistantNotice("A tarefa mudou ou a conexão falhou.")); }
        });
    }

    void assistantNotice(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
    }

    private void voiceOptions() {
        String[] labels = {"Ativar assistente", "Saudação automática", "Respeitar mídia tocando", "Legendas"};
        String[] keys = {"assistant-enabled", "realtime-greeting", "realtime-respect-media", "realtime-captions"};
        boolean[] values = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) values[i] = settings.getBoolean(keys[i], i != 4);
        new AlertDialog.Builder(this).setTitle("Preferências de voz")
            .setMultiChoiceItems(labels, values, (d, which, checked) ->
                settings.edit().putBoolean(keys[which], checked).apply())
            .setNeutralButton("Voz e ritmo", (d, w) -> voiceDetails())
            .setPositiveButton("Fechar", null).show();
    }

    private void voiceDetails() {
        String[] labels = {"Voz", "Velocidade", "Volume", "Pausa entre frases", "Tempo de inatividade",
            "Avatar · cenário", "Avatar · movimentos de repouso (s)", "Avatar · atividades (s)"};
        new AlertDialog.Builder(this).setTitle("Voz e ritmo").setItems(labels, (d, i) -> {
            switch (i) {
                case 0: voiceChoice("Voz brasileira", "local-piper-voice-output", new String[]{"pt_BR-edresson-low","pt_BR-faber-medium"}); break;
                case 1: voiceNumber("Velocidade (%)", "local-speech-speed", new int[]{90,100,110,118,120}); break;
                case 2: voiceNumber("Volume (%)", "realtime-volume", new int[]{30,50,65,80,100}); break;
                case 3: voiceNumber("Pausa entre frases (centésimos de segundo)", "local-speech-pause", new int[]{0,10,20,30,40,60}); break;
                case 4: voiceNumber("Inatividade (segundos)", "realtime-idle", new int[]{30,60,90,120,180}); break;
                case 5: new AlertDialog.Builder(this).setTitle("Cenário do avatar")
                    .setSingleChoiceItems(new String[]{"Automático","Manhã","Tarde","Noite"},
                        settings.getInt("avatar-background",0), (dialog, item) -> {
                            settings.edit().putInt("avatar-background",item).apply();
                            dialog.dismiss();
                        }).show(); break;
                case 6: voiceNumber("Movimentos de repouso (segundos)", "avatar-idle-seconds", new int[]{30,60,90,120}); break;
                case 7: voiceNumber("Atividades (segundos)", "avatar-scene-seconds", new int[]{60,120,180,300}); break;
                default: break;
            }
        }).show();
    }

    private void voiceChoice(String title, String key, String[] values) {
        new AlertDialog.Builder(this).setTitle(title).setItems(values,
            (d, i) -> settings.edit().putString(key, values[i]).apply()).show();
    }

    private void voiceNumber(String title, String key, int[] values) {
        String[] labels = new String[values.length];
        for (int i = 0; i < labels.length; i++) labels[i] = Integer.toString(values[i]);
        new AlertDialog.Builder(this).setTitle(title).setItems(labels,
            (d, i) -> settings.edit().putInt(key, values[i]).apply()).show();
    }

    private void assistantStatus() {
        worker.execute(() -> {
            JSONObject result = readJson("/v1/assistant");
            main.post(() -> {
                if (isFinishing()) return;
                String message = result == null ? "Agente Windows indisponível." :
                    (result.optBoolean("codexConfigured") ? "Executável e projeto do Codex configurados." :
                        "Configure o executável do Codex CLI e a pasta autorizada no agente Windows.")
                    + "\n" + (result.optBoolean("actionsEnabled") ? "Ações autorizadas exigem confirmação." :
                        "Ações de voz desativadas no Windows.");
                new AlertDialog.Builder(this).setTitle("Integrações do assistente").setMessage(message)
                    .setPositiveButton("Fechar", null).show();
            });
        });
    }

    private void startupOptions() {
        worker.execute(() -> {
            JSONObject result = readJson("/v1/startup");
            main.post(() -> {
                if (isFinishing()) return;
                if (result == null) { assistantNotice("Agente Windows indisponível."); return; }
                boolean enabled = result.optBoolean("enabled");
                new AlertDialog.Builder(this).setTitle("Inicialização do Windows")
                    .setMessage(enabled ? "O agente inicia ao entrar na sua conta do Windows." :
                        "Iniciar o agente em segundo plano ao entrar na sua conta do Windows?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton(enabled ? "Desativar" : "Ativar",
                        (d, w) -> worker.execute(() -> {
                            try {
                                request("POST", "/v1/startup?enabled=" + !enabled, 204);
                                main.post(() -> assistantNotice(enabled ? "Inicialização desativada." : "Inicialização ativada."));
                            } catch (Exception failure) { main.post(() -> assistantNotice("Não foi possível alterar a inicialização.")); }
                        })).show();
            });
        });
    }

    private String request(String method, String route, int expectedStatus) throws Exception {
        return request(method, route, expectedStatus, null, null);
    }

    private String request(String method, String route, int expectedStatus, byte[] inputBody, String contentType) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:48627" + route).openConnection();
        connection.setConnectTimeout(1100);
        connection.setReadTimeout(inputBody != null ? 95000 : route.startsWith("/v1/verse") ? 5500 : 1600);
        connection.setRequestMethod(method);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization", "Bearer " + Pairing.TOKEN);
        try {
            if (inputBody != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(inputBody.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(inputBody); }
            }
            if (connection.getResponseCode() != expectedStatus) throw new IllegalStateException("Falha HTTP");
            if (!method.equals("GET") && inputBody == null) return "";
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (output.size() > (route.equals("/v1/deck") ? 65536 : 8192)) throw new IllegalStateException("Resposta inválida");
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }


    LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    LinearLayout row(LinearLayout parent) {
        LinearLayout view = new LinearLayout(this);
        view.setBaselineAligned(false);
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    void heading(LinearLayout parent, String label) {
        TextView title = text(label, 16, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(4), dp(8), 0, dp(4));
        parent.addView(title);
    }

    private void action(LinearLayout row, String icon, String label, int color, String id, int height) {
        addWeighted(row, tile(icon, label, color, CARD, () -> send(id)), height);
    }

    void addWeighted(LinearLayout row, View view, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, height < 0 ? height : dp(height), 1);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.addView(view, params);
    }

    TextView tile(String icon, String label, int accent, int background, Runnable action) {
        TextView button = text(label, 12, INK);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setPadding(dp(4), dp(2), dp(4), dp(2));
        button.setBackground(keycap(10, false, background == RED));
        setIcon(button, icon, accent);
        button.setFocusable(true);
        button.setOnTouchListener((view, event) -> {
            int gesture = event.getActionMasked();
            if (gesture == android.view.MotionEvent.ACTION_DOWN)
                view.animate().translationY(dp(2)).setDuration(55).start();
            else if (gesture == android.view.MotionEvent.ACTION_UP || gesture == android.view.MotionEvent.ACTION_CANCEL)
                view.animate().translationY(0).setDuration(90).start();
            return false;
        });
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void setIcon(TextView view, String name, int tint) {
        setIcon(view, name, tint, 17);
    }

    void inlineIcon(TextView view, String name, String label) {
        Drawable icon = VisualAssets.icon(this, name);
        if (icon == null) return;
        icon.setTint(INK);
        icon.setBounds(0, 0, dp(16), dp(16));
        android.text.SpannableString line = new android.text.SpannableString("\uFFFC  " + label);
        line.setSpan(new android.text.style.ImageSpan(icon, android.text.style.ImageSpan.ALIGN_CENTER),
            0, 1, 0);
        view.setCompoundDrawables(null, null, null, null);
        view.setText(line);
        view.setTextSize(13);
        view.setSingleLine(true);
        view.setPadding(dp(4), 0, dp(4), dp(3));
    }

    private void setIcon(TextView view, String name, int tint, int size) {
        Drawable icon = VisualAssets.icon(this, name);
        if (icon != null) {
            if (!(name.equals("spotify") || name.equals("youtube") || name.equals("steam")
                || name.equals("codex") || name.equals("cursor") || name.equals("miro") || name.equals("serpro"))) icon.setTint(tint);
            icon.setBounds(0, 0, dp(size), dp(size));
        }
        view.setCompoundDrawables(null, icon, null, null);
        view.setCompoundDrawablePadding(dp(3));
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable result = new GradientDrawable();
        if (color == CARD && radius >= 12) {
            result.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            result.setColors(new int[]{0xff1c1c20, CARD, 0xff0f0f11});
            result.setStroke(dp(1), 0xff34343a);
        } else result.setColor(color);
        result.setCornerRadius(dp(radius));
        return result;
    }

    TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private final class UsbIndicator extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable icon;
        private boolean online;

        UsbIndicator() {
            super(DeckActivity.this);
            icon = VisualAssets.icon(DeckActivity.this, "usb");
            setBackground(shape(CARD, 12));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        void update(boolean connected) {
            online = connected;
            setContentDescription(connected ? "USB conectado" : "USB desconectado");
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            int center = getHeight() / 2;
            int radius = dp(9);
            if (icon != null) {
                icon.setBounds(dp(7), center - radius, dp(7) + radius * 2, center + radius);
                icon.setTint(INK);
                icon.draw(canvas);
            }
            paint.setColor(online ? 0x4658f0a6 : 0x308594ac);
            canvas.drawCircle(getWidth() - dp(11), center, dp(6), paint);
            paint.setColor(online ? 0xff59eda7 : 0xff7e8aa2);
            canvas.drawCircle(getWidth() - dp(11), center, dp(3), paint);
        }
    }

    private final class MiniMetrics extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String[] labels = {"CPU", "RAM", "GPU"};

        MiniMetrics() {
            super(DeckActivity.this);
            setBackground(shape(CARD, 12));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        void update() {
            StringBuilder label = new StringBuilder();
            for (int i = 0; i < labels.length; i++) {
                if (i > 0) label.append(", ");
                Double value = connected ? histories[i].values.peekLast() : null;
                label.append(labels[i]).append(' ').append(value == null ? "indisponível" : Math.round(value) + "%");
            }
            setContentDescription(label.toString());
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float width = getWidth() / 3f;
            for (int i = 0; i < labels.length; i++) {
                float x = width * i + dp(9);
                Double value = connected ? histories[i].values.peekLast() : null;
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextSize(dp(11));
                paint.setColor(SOFT);
                canvas.drawText(labels[i], x, dp(15), paint);
                paint.setTextSize(dp(16));
                paint.setColor(INK);
                canvas.drawText(value == null ? "—" : Math.round(value) + "%", x, dp(35), paint);
                paint.setColor(0xff38383e);
                canvas.drawRoundRect(x, dp(43), x + width - dp(18), dp(47), dp(2), dp(2), paint);
                if (value != null && value > 0) {
                    paint.setColor(RED);
                    canvas.drawRoundRect(x, dp(43), x + (width - dp(18)) * value.floatValue() / 100,
                        dp(47), dp(2), dp(2), paint);
                }
            }
        }
    }

    private static final class History {
        final ArrayDeque<Double> values = new ArrayDeque<>();
        void add(double value) {
            if (!Double.isFinite(value)) return;
            if (values.size() == 30) values.removeFirst();
            values.addLast(Math.max(0, value));
        }
    }

    private final class Graph extends FrameLayout {
        private final int metric;
        private final TextView value, state;
        private final MemeStatus meme;
        private final View chart;

        Graph(int metric) {
            super(DeckActivity.this);
            this.metric = metric;
            GradientDrawable surface = shape(CARD, 12);
            setBackground(surface);
            setForeground(new VisualAssets.Frame(dp(12), dp(1)));
            setClipToOutline(true);
            meme = new MemeStatus(DeckActivity.this, true);
            addView(meme, new LayoutParams(-1, -1));
            LinearLayout detail = column();
            detail.setPadding(dp(8), dp(6), dp(6), dp(6));
            addView(detail, new LayoutParams(-1, -1));
            detail.addView(text(METRIC_EMOJIS[metric] + " " + METRIC_NAMES[metric], 12, SOFT));
            value = text("—", 20, INK);
            value.setTypeface(Typeface.DEFAULT_BOLD);
            detail.addView(value);
            state = text("", 11, SOFT);
            state.setMaxLines(2);
            detail.addView(state);
            setOnClickListener(v -> new AlertDialog.Builder(DeckActivity.this)
                .setTitle(METRIC_NAMES[metric])
                .setMessage(value.getText() + " · " + state.getText() + "\nAmostras recentes: "
                    + histories[metric].values.size())
                .setPositiveButton("Fechar", null).show());
            chart = new View(DeckActivity.this) {
                private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
                private final Path line = new Path();
                @Override protected void onDraw(Canvas canvas) {
                    super.onDraw(canvas);
                    int width = getWidth() - dp(7), height = getHeight() - dp(5);
                    if (width <= 0 || height <= 0) return;
                    brush.setColor(DeckTheme.EDGE);
                    brush.setStrokeWidth(dp(1));
                    canvas.drawLine(0, height, width, height, brush);
                    if (histories[metric].values.isEmpty()) return;
                    double max = metric < 4 ? 100 : 1;
                    for (double sample : histories[metric].values) max = Math.max(max, sample);
                    int index = 0;
                    line.reset();
                    for (double sample : histories[metric].values) {
                        float x = width * index / 29f, y = height - (float)(sample / max) * (height - dp(2));
                        if (index++ == 0) line.moveTo(x, y); else line.lineTo(x, y);
                    }
                    brush.setColor(RED);
                    brush.setStrokeWidth(dp(1.6f));
                    brush.setStyle(Paint.Style.STROKE);
                    canvas.drawPath(line, brush);
                    brush.setStyle(Paint.Style.FILL);
                }
            };
            detail.addView(chart, new LinearLayout.LayoutParams(-1, 0, 1));
        }

        void update(boolean online) {
            int level = online ? levels[metric] : -1;
            Double last = histories[metric].values.peekLast();
            String reading = level < 0 || last == null ? "—" : String.format(Locale.getDefault(),
                metric == 5 ? "%.1f%s" : "%.0f%s", last, METRIC_UNITS[metric]);
            String indicator = level >= 0 ? STATES[level] : online ? "Sem medição" : "Sem conexão";
            int memeLevel = Math.max(0, level);
            String reaction = REACTIONS[metric][memeLevel];
            if (!reading.contentEquals(value.getText())) value.setText(reading);
            String status = (metric == 4 && level >= 0 ? "GPU · " : "") + indicator;
            if (!status.contentEquals(state.getText())) state.setText(status);
            state.setTextColor(level == 2 ? 0xffff6a76 : level == 1 ? 0xffffbf64 : level == 0 ? 0xff8ac8a0 : SOFT);
            meme.allowMotion(plugged || !economyEnabled());
            meme.update(METRIC_IDS[metric] + "-" + memeLevel, METRIC_EMOJIS[metric], reaction,
                active && (level >= 0 || "fps".equals(METRIC_IDS[metric]))
                    && settings.getBoolean("memes-enabled", true));
            setContentDescription(METRIC_NAMES[metric] + ": " + reading + ", " + indicator);
            chart.invalidate();
        }
    }
}
