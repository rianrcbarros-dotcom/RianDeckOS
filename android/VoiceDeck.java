package br.com.rian.deck;

import android.Manifest;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

final class VoiceDeck {
    static final int PERMISSION = 501;
    private static final int RATE = 24000;
    private final DeckActivity activity;
    private final SharedPreferences settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView phase, caption, heard, connection, action;
    private VoiceOrb waveform;
    private Session session;
    private boolean visible, connected, greeted, attempted, requesting;
    private boolean testing;

    VoiceDeck(DeckActivity activity, SharedPreferences settings) {
        this.activity = activity;
        this.settings = settings;
    }

    void mount(LinearLayout body, boolean online) {
        visible = true;
        LinearLayout center = activity.column();
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        center.setPadding(0, activity.dp(24), 0, 0);
        body.addView(center, new LinearLayout.LayoutParams(-1, -2));
        connection = activity.text("", 12, DeckTheme.MUTED);
        connection.setGravity(Gravity.CENTER);
        center.addView(connection, new LinearLayout.LayoutParams(-1, activity.dp(23)));
        waveform = new VoiceOrb();
        LinearLayout.LayoutParams orb = new LinearLayout.LayoutParams(activity.dp(113), activity.dp(113));
        orb.topMargin = activity.dp(5);
        center.addView(waveform, orb);
        waveform.setOnClickListener(v -> toggle());
        waveform.setFocusable(true);
        phase = activity.text("Aguardando", 19, DeckTheme.INK);
        phase.setGravity(Gravity.CENTER);
        center.addView(phase, new LinearLayout.LayoutParams(-1, activity.dp(27)));
        action = activity.text("Toque para conversar", 12, DeckTheme.MUTED);
        action.setGravity(Gravity.CENTER);
        action.setOnClickListener(v -> toggle());
        center.addView(action, new LinearLayout.LayoutParams(-1, activity.dp(21)));
        heard = activity.text("", 13, DeckTheme.MUTED);
        heard.setGravity(Gravity.CENTER);
        heard.setMaxLines(1);
        caption = activity.text("", 16, DeckTheme.INK);
        caption.setGravity(Gravity.CENTER);
        caption.setMaxLines(3);
        center.addView(heard);
        center.addView(caption);
        setAction(false);
        connection(online);
    }

    private void toggle() {
        if (session == null) { attempted = false; advance(); return; }
        if (session.capturing) {
            session.capturing = false;
            phase.setText("Processando");
            action.setText("Aguarde a resposta");
            waveform.setContentDescription("Processando");
            waveform.setProgress(0);
        } else if (session.readyForInput) session.beginInput();
    }

    private void setAction(boolean running) {
        action.setText(running ? "Aguarde um instante" : "Toque para conversar");
        waveform.setContentDescription(running ? "Preparando Assistente" : "Iniciar conversa");
        waveform.running = running;
        waveform.invalidate();
    }

    private final class VoiceOrb extends View {
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int level;
        private boolean running;

        VoiceOrb() { super(activity); setClickable(true); }
        void setProgress(int value) { level = value; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float x = getWidth()/2f, y = getHeight()/2f, radius = Math.min(x,y) - activity.dp(6);
            ink.setColor(0xff080808);
            c.drawCircle(x, y + activity.dp(3), radius, ink);
            ink.setColor(isPressed() ? DeckTheme.DARK_RED : 0xff141414);
            c.drawCircle(x, y, radius, ink);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(activity.dp(2));
            ink.setColor(running ? DeckTheme.RED : DeckTheme.EDGE);
            c.drawCircle(x, y, radius, ink);
            ink.setStyle(Paint.Style.FILL);
            ink.setColor(running ? DeckTheme.RED : DeckTheme.INK);
            for (int i = -3; i <= 3; i++) {
                float height = activity.dp(4 + (3 - Math.abs(i)) * 3)
                    + (running ? level * (4 - Math.abs(i)) / 20f : 0f);
                float bar = x + activity.dp(8) * i;
                c.drawRoundRect(bar - activity.dp(2), y-height, bar+activity.dp(2), y+height,
                    activity.dp(2), activity.dp(2), ink);
            }
        }
    }

    void connection(boolean online) {
        boolean returned = !connected && online;
        connected = online;
        if (!visible) return;
        connection.setText(!online ? "○ Windows desconectado" :
            session != null && !session.source.isEmpty() ? session.source : "● Windows conectado · Voz local");
        if (!online && session != null) {
            stop();
            caption.setText("Aguardando a conexão USB.");
        }
        if (returned) attempted = false;
        advance();
    }

    private void advance() {
        if (!visible || attempted || session != null) return;
        if (!settings.getBoolean("assistant-enabled", true)) {
            caption.setText("Ative o Assistente nas Configurações.");
            return;
        }
        if (!connected) return;
        attempted = true;
        start(false);
    }

    void start(boolean test) {
        if (!test && !visible) return;
        testing = test;
        if (!test && !settings.getBoolean("local-voice-consent", false)) {
            new AlertDialog.Builder(activity).setTitle("Conversa por voz")
                .setMessage("O microfone grava apenas entre dois toques. Toque para falar e toque novamente para enviar. O áudio é processado localmente no Windows. Ao sair ou encerrar, a captura para. O Windows pode usar seu microfone padrão se o do celular falhar.")
                .setNegativeButton("Agora não", (dialog, which) -> idle("Conversa não iniciada."))
                .setPositiveButton("Permitir", (dialog, which) -> {
                    settings.edit().putBoolean("local-voice-consent", true).apply();
                    start(false);
                }).show();
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            && !settings.getBoolean("realtime-pc-mic", false)) {
            requesting = true;
            activity.requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, PERMISSION);
            return;
        }
        permission(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
    }

    void permission(boolean allowed) {
        requesting = false;
        if (testing) {
            if (!allowed) { activity.assistantNotice("Microfone do celular sem permissão."); return; }
            new Thread(() -> {
                AudioRecord input = null;
                try {
                    input = recorder();
                    input.startRecording();
                    byte[] bytes = new byte[1920];
                    int count = input.read(bytes, 0, bytes.length);
                    main.post(() -> activity.assistantNotice(count > 0 ? "Microfone do celular disponível." : "Falha na captura do celular."));
                } catch (RuntimeException error) { main.post(() -> activity.assistantNotice("Falha no microfone do celular.")); }
                finally { if (input != null) input.release(); }
            }, "deck-microphone-test").start();
            return;
        }
        if (!visible || session != null) return;
        session = new Session(!allowed || settings.getBoolean("realtime-pc-mic", false));
        phase.setText("Conectando");
        caption.setText("");
        heard.setText("");
        setAction(true);
        new Thread(session::run, "deck-voice-link").start();
    }

    void hide() { visible = false; stop(); attempted = false; }
    void destroy() { hide(); }
    void stop() {
        if (session != null) { session.capturing = false; session.close(); session = null; }
        if (visible) {
            phase.setText("Aguardando");
            waveform.setProgress(0);
            setAction(false);
        }
    }
    void suspend() {
        stop();
        if (!requesting) attempted = false;
    }

    void resume() { if (!requesting && visible && session == null && !attempted) advance(); }

    private void idle(String message) { stop(); if (visible) caption.setText(message); }

    private String greeting() {
        if (!settings.getBoolean("realtime-greeting", true)) return "";
        ZonedDateTime now = ZonedDateTime.now();
        Locale pt = new Locale("pt", "BR");
        String day = now.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", pt));
        String time = now.format(DateTimeFormatter.ofPattern("HH:mm", pt));
        String period = now.getHour() < 12 ? "Bom dia" : now.getHour() < 18 ? "Boa tarde" : "Boa noite";
        String[] lines = greeted
            ? new String[]{"E aí, Rian, bora continuar?", "Fala, Rian! Manda aí.", "Voltei, Rian. Qual vai ser?", "Tô aqui, Rian. Bora?"}
            : new String[]{
                period + ", Rian! Hoje é " + day + ", são " + time + ". Bora começar?",
                "Fala, Rian! " + period + "! São " + time + ". O que manda?",
                "E aí, Rian! Já estamos na " + day + ". O que a gente vai fazer?",
                period + ", Rian! São " + time + " e o PC tá conectado. Manda aí!"};
        String key = greeted ? "local-last-short-greeting" : "local-last-welcome-greeting";
        int previous = settings.getInt(key, -1);
        int index = java.util.concurrent.ThreadLocalRandom.current().nextInt(previous < 0 ? lines.length : lines.length - 1);
        if (previous >= 0 && index >= previous) index++;
        settings.edit().putInt(key, index).apply();
        return lines[index];
    }

    private AudioRecord recorder() {
        int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException();
        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 3840));
        if (record.getState() != AudioRecord.STATE_INITIALIZED) { record.release(); throw new IllegalStateException(); }
        return record;
    }

    private final class Session {
        final boolean pcFirst;
        final DeckVoiceSocket socket = new DeckVoiceSocket();
        final ArrayBlockingQueue<byte[]> audio = new ArrayBlockingQueue<>(96);
        final String welcome = greeting();
        final int idleSeconds = settings.getInt("realtime-idle", 90);
        final Object playbackGate = new Object();
        final String voice = settings.getString("local-piper-voice-output", "pt_BR-edresson-low");
        final boolean phoneSpeaker = settings.getBoolean("local-phone-speaker", false);
        final float volume = settings.getInt("realtime-volume", 80) / 100f;
        final float speed = settings.getInt("local-speech-speed", 118) / 100f;
        final float pause = settings.getInt("local-speech-pause", 10) / 100f;
        final boolean respectMedia = settings.getBoolean("realtime-respect-media", true);
        volatile boolean closed;
        volatile AudioTrack playback;
        volatile long activityAt = android.os.SystemClock.elapsedRealtime();
        String item = "";
        int content, itemStart = -1;
        volatile boolean capturing;
        boolean readyForInput;
        void enableInput() {
            readyForInput = true;
            phase.setText("Aguardando");
            action.setText("Toque para falar");
            waveform.setContentDescription("Toque para falar");
            waveform.setProgress(0);
            waveform.running = false;
            waveform.invalidate();
        }
        volatile boolean waitingGreeting = !welcome.isEmpty();
        String error = "Serviço de voz desconectado.";
        volatile String source = "";
        AlertDialog confirmation;

        Session(boolean pcFirst) { this.pcFirst = pcFirst; }
        void json(JSONObject value) throws Exception { socket.send(1, value.toString().getBytes(StandardCharsets.UTF_8)); }
        JSONObject event(String type) throws Exception { return new JSONObject().put("type", type); }
        void ui(Runnable code) { main.post(() -> { if (session == this && visible) code.run(); }); }
        void close() {
            closed = true;
            socket.close();
            audio.clear();
            if (confirmation != null) confirmation.dismiss();
        }
        void run() {
            try {
                socket.open();
                json(event("setup").put("voice", voice).put("greeting", welcome).put("speed", speed)
                    .put("pause", pause).put("respectMedia", respectMedia).put("volume", volume).put("phoneSpeaker", phoneSpeaker));
                socket.read((type, bytes) -> {
                    if (type == 2) {
                        activityAt = android.os.SystemClock.elapsedRealtime();
                        if (!closed && !audio.offer(bytes, 1500, TimeUnit.MILLISECONDS))
                            throw new java.io.IOException("Fila de áudio cheia");
                        return;
                    }
                    JSONObject message = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                    switch (message.optString("type")) {
                        case "ready":
                            waitingGreeting = message.optBoolean("greeting");
                            ui(() -> { greeted = true; phase.setText(waitingGreeting ? "Preparando saudação" : "Ouvindo"); });
                            if (phoneSpeaker) new Thread(this::play, "deck-voice-output").start();
                            if (!waitingGreeting) ui(this::enableInput);
                            new Thread(this::watch, "deck-voice-timeout").start();
                            break;
                        case "audio":
                            synchronized (playbackGate) {
                                String next = message.optString("item");
                                if (!item.equals(next)) {
                                    item = next;
                                    content = message.optInt("index");
                                    itemStart = -1;
                                }
                            }
                            ui(() -> phase.setText("Respondendo"));
                            break;
                        case "interrupt": interrupt(); ui(() -> phase.setText("Ouvindo")); break;
                        case "thinking": ui(() -> phase.setText("Processando")); break;
                        case "level": ui(() -> waveform.setProgress(message.optInt("value"))); break;
                        case "done":
                            waitingGreeting = false;
                            if (phoneSpeaker) audio.offer(new byte[0]);
                            else ui(this::enableInput);
                            break;
                        case "heard":
                            activityAt = android.os.SystemClock.elapsedRealtime();
                            ui(() -> heard.setText(settings.getBoolean("realtime-captions", true) ? message.optString("text") : ""));
                            break;
                        case "caption":
                            ui(() -> caption.setText(settings.getBoolean("realtime-captions", true) ? message.optString("text") : ""));
                            break;
                        case "source":
                            source = message.optString("text");
                            ui(() -> connection.setText(source));
                            break;
                        case "confirm":
                            ui(() -> {
                                String id = message.optString("id");
                                phase.setText("Confirmação no telefone");
                                confirmation = new AlertDialog.Builder(activity).setTitle("Confirme a ação")
                                    .setMessage(message.optString("text"))
                                    .setNegativeButton("Cancelar", (d, w) -> decide(id, false))
                                    .setPositiveButton("Confirmar", (d, w) -> decide(id, true))
                                    .setOnCancelListener(d -> decide(id, false)).show();
                            });
                            break;
                        case "error": error = message.optString("text", error); socket.close(); break;
                    }
                });
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (Exception failure) { }
            finally { closeFromWorker(); }
        }
        void closeFromWorker() {
            closed = true;
            socket.close();
            audio.clear();
            ui(() -> idle(error));
        }
        void beginInput() {
            if (!readyForInput || closed) return;
            readyForInput = false;
            capturing = true;
            phase.setText("Ouvindo");
            action.setText("Toque para enviar");
            waveform.setContentDescription("Toque para enviar");
            waveform.running = true;
            waveform.invalidate();
            caption.setText("");
            heard.setText("");
            new Thread(this::record, "deck-voice-input").start();
        }
        void decide(String id, boolean allowed) {
            new Thread(() -> {
                try { json(event("decision").put("id", id).put("allow", allowed)); }
                catch (Exception failure) { closeFromWorker(); }
            }, "deck-voice-confirm").start();
        }
        void interrupt() throws Exception {
            activityAt = android.os.SystemClock.elapsedRealtime();
            synchronized (playbackGate) {
                audio.clear();
                if (playback != null) {
                    int frame = playback.getPlaybackHeadPosition();
                    playback.pause();
                    playback.flush();
                    playback.play();
                    if (itemStart >= 0 && !item.isEmpty())
                        json(event("truncate").put("item", item).put("index", content)
                            .put("ms", Math.max(0, (frame - itemStart) * 1000L / RATE)));
                    itemStart = -1;
                    item = "";
                }
            }
        }
        void record() {
            AudioRecord input = null;
            AcousticEchoCanceler echo = null;
            NoiseSuppressor noise = null;
            try {
                json(event("start_capture"));
                if (pcFirst) { usePcInput(); return; }
                input = recorder();
                if (AcousticEchoCanceler.isAvailable()) {
                    try { echo = AcousticEchoCanceler.create(input.getAudioSessionId()); if (echo != null) echo.setEnabled(true); }
                    catch (RuntimeException ignored) { }
                }
                if (NoiseSuppressor.isAvailable()) {
                    try { noise = NoiseSuppressor.create(input.getAudioSessionId()); if (noise != null) noise.setEnabled(true); }
                    catch (RuntimeException ignored) { }
                }
                input.startRecording();
                source = "● Microfone do celular · USB";
                ui(() -> connection.setText(source));
                byte[] pcm = new byte[1920];
                while (!closed && capturing) {
                    int count = input.read(pcm, 0, pcm.length);
                    if (count <= 0) throw new IllegalStateException();
                    socket.send(2, count == pcm.length ? pcm : java.util.Arrays.copyOf(pcm, count));
                    showLevel(pcm, count);
                }
            } catch (Exception failure) {
                if (!closed && capturing) try { usePcInput(); } catch (Exception transport) { closeFromWorker(); }
            } finally {
                if (echo != null) echo.release();
                if (noise != null) noise.release();
                if (input != null) input.release();
                if (!closed) {
                    try { json(event("submit_capture")); }
                    catch (Exception transport) { closeFromWorker(); }
                }
            }
        }
        void usePcInput() throws Exception {
            json(event("pc_input"));
            while (capturing && !closed) Thread.sleep(25);
        }

        void play() {
            AudioTrack out = null;
            try {
                int minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) throw new IllegalStateException();
                out = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(minimum, 3840)).build();
                if (out.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException();
                AudioManager manager = activity.getSystemService(AudioManager.class);
                for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        out.setPreferredDevice(device);
                        break;
                    }
                }
                out.setVolume(volume);
                synchronized (playbackGate) { playback = out; out.play(); }
                int writtenFrames = 0;
                while (!closed) {
                    byte[] pcm = audio.poll(100, TimeUnit.MILLISECONDS);
                    if (pcm == null) continue;
                    if (pcm.length == 0) {
                        long end = android.os.SystemClock.elapsedRealtime() + 2500;
                        while (!closed && out.getPlaybackHeadPosition() < writtenFrames
                            && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(20);
                        ui(this::enableInput);
                        continue;
                    }
                    for (int offset = 0; offset < pcm.length && !closed;) {
                        int count;
                        synchronized (playbackGate) {
                            if (item.isEmpty()) break;
                            if (itemStart < 0) itemStart = out.getPlaybackHeadPosition();
                            count = out.write(pcm, offset, pcm.length - offset, AudioTrack.WRITE_NON_BLOCKING);
                        }
                        if (count < 0) throw new IllegalStateException();
                        if (count == 0) Thread.sleep(8);
                        else { offset += count; writtenFrames += count / 2; }
                    }
                    showLevel(pcm, pcm.length);
                }
            } catch (Exception failure) {
                if (!closed) { error = "Saída de áudio indisponível no celular."; closeFromWorker(); }
            } finally {
                synchronized (playbackGate) {
                    playback = null;
                    if (out != null) out.release();
                }
            }
        }
        void showLevel(byte[] pcm, int length) {
            int peak = 0;
            for (int i = 0; i + 1 < length; i += 4)
                peak = Math.max(peak, Math.abs((short)((pcm[i] & 255) | (pcm[i+1] << 8))));
            int value = Math.min(100, peak / 170);
            ui(() -> waveform.setProgress(value));
        }
        void watch() {
            try {
                while (!closed) {
                    Thread.sleep(1000);
                    if (android.os.SystemClock.elapsedRealtime() - activityAt > idleSeconds * 1000L) {
                        error = "Conversa encerrada por inatividade.";
                        closeFromWorker();
                    }
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
}
