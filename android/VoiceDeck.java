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
    private TextView phase, action, transcript, replyCaption;
    private AssistantPersona persona;
    private VoiceOrb waveform;
    private JSONObject currentJob;
    private Session session;
    private boolean visible, connected, greeted, attempted, requesting;
    private boolean testing;

    VoiceDeck(DeckActivity activity, SharedPreferences settings) {
        this.activity = activity;
        this.settings = settings;
    }

    void mount(LinearLayout body, boolean online) {
        visible = true;
        boolean landscape = activity.landscape();
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        persona = new AssistantPersona(activity, settings);
        if (landscape) {
            android.widget.FrameLayout stage = new android.widget.FrameLayout(activity);
            int height = Math.max(155, activity.getResources().getConfiguration().screenHeightDp - 102);
            body.addView(stage, new LinearLayout.LayoutParams(-1,activity.dp(height)));
            stage.addView(persona, new android.widget.FrameLayout.LayoutParams(-1,-1));
            stage.addView(panel, new android.widget.FrameLayout.LayoutParams(-1,-1));
            panel.addView(new View(activity), new LinearLayout.LayoutParams(0,-1,.45f));
            persona.wideStage();
        } else {
            body.addView(panel, new LinearLayout.LayoutParams(-1,-2));
            panel.addView(persona, new LinearLayout.LayoutParams(-1,activity.dp(190)));
        }
        persona.animateWhileVisible(true);

        LinearLayout info = activity.column();
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.setPadding(activity.dp(18), 0, activity.dp(22), 0);
        panel.addView(info, landscape ? new LinearLayout.LayoutParams(0,-1,.55f)
            : new LinearLayout.LayoutParams(-1,-2));
        TextView title = activity.text("", landscape ? 29 : 24, DeckTheme.INK);
        android.text.SpannableString name = new android.text.SpannableString("Assistente Pião");
        name.setSpan(new android.text.style.ForegroundColorSpan(DeckTheme.RED), 11, name.length(), 0);
        title.setText(name);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        info.addView(title);
        phase = activity.text("", 16, DeckTheme.MUTED);
        phase.setMaxLines(2);
        phase.setPadding(0, activity.dp(5), 0, 0);
        phase.setOnClickListener(v -> { if (currentJob != null) taskDetails(); });
        info.addView(phase);
        waveform = new VoiceOrb();
        info.addView(waveform, new LinearLayout.LayoutParams(-1, activity.dp(50)));
        transcript = activity.text("", 15, DeckTheme.INK);
        transcript.setMaxLines(2);
        transcript.setEllipsize(android.text.TextUtils.TruncateAt.END);
        transcript.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        transcript.setVisibility(View.GONE);
        info.addView(transcript);
        replyCaption = activity.text("", 15, DeckTheme.INK);
        replyCaption.setMaxLines(3);
        replyCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        replyCaption.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        replyCaption.setVisibility(View.GONE);
        replyCaption.setOnClickListener(v -> new AlertDialog.Builder(activity)
            .setTitle("Resposta do Pião").setMessage(replyCaption.getText())
            .setPositiveButton("Fechar", null).show());
        info.addView(replyCaption);

        LinearLayout controls = activity.row(body);
        action = activity.tile("mic", "Falar", DeckTheme.INK, DeckTheme.RED, this::toggle);
        activity.addWeighted(controls, action, 48);
        LinearLayout.LayoutParams talkSize = (LinearLayout.LayoutParams) action.getLayoutParams();
        talkSize.weight = 1.65f;
        action.setLayoutParams(talkSize);
        activity.inlineIcon(action,"mic","Falar");
        TextView interrupt = activity.tile("close", "Interromper", DeckTheme.INK, DeckTheme.PANEL, this::interruptCurrent);
        activity.inlineIcon(interrupt,"close","Interromper");
        activity.addWeighted(controls, interrupt, 48);
        TextView end = activity.tile("power", "Encerrar", DeckTheme.INK, DeckTheme.PANEL, () -> {
            stop();
            state("Sessão encerrada");
        });
        activity.inlineIcon(end,"power","Encerrar");
        activity.addWeighted(controls, end, 48);
        setAction(false);
        state(online ? "Pronto para te ouvir." : "Windows desconectado");
        connection(online);
    }

    private boolean taskActive() {
        if (currentJob == null) return false;
        String status = currentJob.optString("state");
        return status.equals("Executando") || status.equals("Validando") ||
            status.equals("Planejada") || status.equals("Aguardando autorização");
    }

    void task(JSONObject response) {
        if (!visible) return;
        currentJob = response == null ? null : response.optJSONObject("job");
        if (phase == null || (session != null && !session.readyForInput)) return;
        if (taskActive()) state(currentJob.optString("state").equals("Aguardando autorização")
            ? "Aguardando autorização · toque para ver o plano" : "Executando tarefa · toque para ver o plano");
        else if (phase.getText().toString().contains("· toque para ver o plano"))
            state(currentJob == null ? "Pronto para te ouvir." : "Tarefa " + currentJob.optString("state").toLowerCase(java.util.Locale.ROOT) + " · ver detalhes");
    }

    private void showTranscript(String text) {
        if (transcript == null) return;
        transcript.setText(text.isEmpty() ? "" : "Entendi: " + text);
        transcript.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showReply(String text) {
        if (replyCaption == null) return;
        replyCaption.setText(text.isEmpty() ? "" : "Pião: " + text);
        replyCaption.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void state(String status) {
        phase.setText(status);
        int mode = status.startsWith("Ouvindo") ? 1 : status.startsWith("Processando") || status.startsWith("Conectando")
            || status.startsWith("Preparando") ? 2 : status.startsWith("Respondendo") ? 3 : status.startsWith("Executando") ? 4
            : !connected ? 5 : 0;
        if (persona != null) persona.state(mode);
        if (waveform != null) waveform.setMode(mode);
    }

    private void interruptCurrent() {
        Session active = session;
        if (active != null && !active.readyForInput) {
            active.waitingGreeting = false;
            active.discardCapture = true;
            active.capturing = false;
            state("Interrompendo…");
            active.stopTurn();
        } else if (taskActive()) {
            activity.operatorDecision(currentJob.optString("id"), "cancel");
        } else state("Nenhuma resposta ou tarefa em andamento");
    }

    private void taskDetails() {
        if (currentJob == null) { activity.assistantNotice("Nenhuma tarefa registrada."); return; }
        JSONObject job = currentJob;
        String id = job.optString("id");
        String state = job.optString("state");
        StringBuilder detail = new StringBuilder(state);
        try {
            long seconds = java.time.Duration.between(java.time.OffsetDateTime.parse(job.getString("started")),
                state.equals("Executando") || state.equals("Validando") ? java.time.OffsetDateTime.now() :
                    java.time.OffsetDateTime.parse(job.getString("updated"))).getSeconds();
            detail.append(" · ").append(Math.max(0, seconds)).append(" s");
        } catch (Exception ignored) { }
        org.json.JSONArray steps = job.optJSONArray("steps");
        if (steps != null) for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) continue;
            detail.append("\n\n").append(i + 1).append(". ").append(step.optString("description"))
                .append(" · ").append(step.optString("state"));
            if (!step.isNull("result")) detail.append("\n").append(step.optString("result"));
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("Tarefa em andamento")
            .setMessage(detail).setNegativeButton("Fechar", null);
        if (state.equals("Aguardando autorização"))
            dialog.setPositiveButton("Autorizar plano", (d, w) -> activity.operatorDecision(id, "approve"));
        if (state.equals("Executando") || state.equals("Validando") || state.equals("Planejada") ||
            state.equals("Aguardando autorização"))
            dialog.setNeutralButton("Cancelar tarefa", (d, w) -> activity.operatorDecision(id, "cancel"));
        dialog.show();
    }

    private void toggle() {
        if (session == null) { attempted = false; advance(); return; }
        if (session.capturing) {
            session.capturing = false;
            state("Processando");
            action.setContentDescription("Falar · aguarde a resposta");
            waveform.setContentDescription("Processando");
            waveform.setProgress(0);
        } else if (session.readyForInput) session.beginInput();
    }

    private void setAction(boolean running) {
        action.setContentDescription(running ? "Falar · preparando" : "Falar");
    }

    private final class VoiceOrb extends View {
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int level, mode;

        VoiceOrb() { super(activity); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void setMode(int value) { if (mode != value) { mode = value; level = 0; invalidate(); } }
        void setProgress(int value) {
            level = value;
            if (persona != null) persona.level(value);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float middle = getHeight() / 2f;
            float spacing = Math.min(activity.dp(11), getWidth()/28f);
            float center = getWidth()/2f;
            double tick = android.os.SystemClock.uptimeMillis()/180.0;
            ink.setColor(mode == 0 ? 0xff55252a : DeckTheme.RED);
            for (int i = -10; i <= 10; i++) {
                float weight = 1f-Math.abs(i)/11f;
                float pulse = (float)(.45 + .55 * Math.abs(Math.sin(i*.85 + tick)));
                float h = activity.dp(2) + weight * activity.dp(5)
                    + weight * pulse * activity.dp(mode == 1 || mode == 3 ? level/5f : mode == 0 ? 0 : 9);
                float x = center+i*spacing, w = spacing*.25f;
                c.drawRoundRect(x-w,middle-h,x+w,middle+h,w,w,ink);
            }
            if (mode != 0 && visible && isShown() && getWindowVisibility()==VISIBLE) postInvalidateDelayed(50);
        }
    }

    void connection(boolean online) {
        boolean returned = !connected && online;
        connected = online;
        if (!visible) return;
        if (!online && session != null) {
            stop();
            state("Aguardando a conexão USB.");
        }
        if (returned) { attempted = false; if (session == null && !taskActive()) state("Pronto para te ouvir."); }
        else if (!online && session == null) state("Windows desconectado");
    }

    private void advance() {
        if (!visible || attempted || session != null) return;
        if (!settings.getBoolean("assistant-enabled", true)) {
            state("Ative o Assistente nas Configurações.");
            return;
        }
        if (!connected) { state("Windows desconectado"); return; }
        attempted = true;
        start(false);
    }

    void start(boolean test) {
        if (!test && !visible) return;
        testing = test;
        if (!test && !settings.getBoolean("local-voice-consent", false)) {
            new AlertDialog.Builder(activity).setTitle("Conversa por voz")
                .setMessage("A gravação usa a entrada padrão do Windows. Toque para falar e toque novamente para enviar. A resposta sai pela saída padrão do computador.")
                .setNegativeButton("Agora não", (dialog, which) -> idle("Conversa não iniciada."))
                .setPositiveButton("Permitir", (dialog, which) -> {
                    settings.edit().putBoolean("local-voice-consent", true).apply();
                    start(false);
                }).show();
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            && test) {
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
        session = new Session(true);
        state("Conectando");
        setAction(true);
        new Thread(session::run, "deck-voice-link").start();
    }

    void hide() { showTranscript(""); if (persona != null) persona.animateWhileVisible(false); visible = false; stop(); attempted = false; }
    void destroy() { hide(); }
    void stop() {
        if (session != null) { session.capturing = false; session.close(); session = null; }
        if (visible) {
            state("Aguardando");
            waveform.setProgress(0);
            setAction(false);
        }
    }
    void suspend() {
        if (persona != null) persona.animateWhileVisible(false);
        stop();
        if (!requesting) attempted = false;
    }

    void resume() { if (visible && persona != null) persona.animateWhileVisible(true); }

    private void idle(String message) { stop(); if (visible) state(message); }

    private String greeting() {
        if (!settings.getBoolean("realtime-greeting", true)) return "";
        ZonedDateTime now = ZonedDateTime.now();
        Locale pt = new Locale("pt", "BR");
        String day = now.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", pt));
        String time = now.format(DateTimeFormatter.ofPattern("HH:mm", pt));
        String period = now.getHour() < 12 ? "Bom dia" : now.getHour() < 18 ? "Boa tarde" : "Boa noite";
        String[] lines = greeted
            ? new String[]{"E aí{nome}, bora continuar?", "Fala{nome}! Manda aí.", "Voltei{nome}. Qual vai ser?", "Tô aqui{nome}. Bora?"}
            : new String[]{
                period + "{nome}! Hoje é " + day + ", são " + time + ". Bora começar?",
                "Fala{nome}! " + period + "! São " + time + ". O que manda?",
                "E aí{nome}! Já estamos na " + day + ". O que a gente vai fazer?",
                period + "{nome}! São " + time + " e o PC tá conectado. Manda aí!"};
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
        {
        if (!settings.getBoolean("local-voice-profile-refined", false)) {
            android.content.SharedPreferences.Editor migration = settings.edit()
                .putBoolean("local-voice-profile-refined", true);
            if ("pt_BR-edresson-low".equals(settings.getString("local-piper-voice-output", "")))
                migration.putString("local-piper-voice-output", "pt_BR-faber-medium");
            if (settings.getInt("local-speech-speed", 106) == 118) migration.putInt("local-speech-speed", 106);
            if (settings.getInt("local-speech-pause", 17) == 10) migration.putInt("local-speech-pause", 17);
            migration.apply();
        }
        }
        final String voice = settings.getString("local-piper-voice-output", "pt_BR-faber-medium");
        final boolean phoneSpeaker = false;
        final float volume = settings.getInt("realtime-volume", 80) / 100f;
        final float speed = settings.getInt("local-speech-speed", 106) / 100f;
        final float pause = settings.getInt("local-speech-pause", 17) / 100f;
        final boolean respectMedia = settings.getBoolean("realtime-respect-media", true);
        volatile boolean closed;
        volatile AudioTrack playback;
        volatile long activityAt = android.os.SystemClock.elapsedRealtime();
        String item = "";
        int content, itemStart = -1;
        volatile boolean capturing, discardCapture;
        boolean readyForInput;
        String actionResult = "";
        void enableInput() {
            readyForInput = true;
            state(actionResult.isEmpty() ? "Aguardando" : actionResult);
            actionResult = "";
            action.setContentDescription("Falar");
            waveform.setContentDescription("Toque para falar");
            waveform.setProgress(0);

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
                            ui(() -> { greeted = true; state(waitingGreeting ? "Preparando saudação" : "Ouvindo"); });
                            if (phoneSpeaker) new Thread(this::play, "deck-voice-output").start();
                            if (!waitingGreeting) ui(() -> { enableInput(); beginInput(); });
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
                            ui(() -> state("Respondendo"));
                            break;
                        case "interrupt": interrupt(); ui(() -> state("Ouvindo")); break;
                        case "thinking": ui(() -> state("Processando")); break;
                        case "action_result": ui(() -> {
                            actionResult = message.optString("text");
                            showReply(actionResult);
                            if (persona != null) persona.reaction(actionResult.startsWith("Abri") ||
                                actionResult.startsWith("Pasta aberta") || actionResult.startsWith("Pesquisa enviada"));
                        }); break;
                        case "level": ui(() -> waveform.setProgress(message.optInt("value"))); break;
                        case "done":
                            boolean greetedNow = waitingGreeting;
                            waitingGreeting = false;
                            if (phoneSpeaker) audio.offer(new byte[0]);
                            else ui(() -> { enableInput(); if (greetedNow) beginInput(); });
                            break;
                        case "heard":
                            activityAt = android.os.SystemClock.elapsedRealtime();
                            ui(() -> showTranscript(message.optString("text")));
                            break;

                        case "caption":
                            String note = message.optString("text");
                            if (note.startsWith("Não detectei") || note.startsWith("Não consegui") || note.startsWith("Deu problema"))
                                ui(() -> activity.assistantNotice(note));
                            ui(() -> showReply(note));
                            break;
                        case "source":
                            source = message.optString("text");

                            break;
                        case "confirm":
                            ui(() -> {
                                String id = message.optString("id");
                                state("Confirmação no telefone");
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
            discardCapture = false;
            showTranscript("");
            showReply("");
            capturing = true;
            state("Ouvindo · toque em Falar para enviar");
            action.setContentDescription("Falar · enviar gravação");
            waveform.setContentDescription("Toque para enviar");

            new Thread(this::record, "deck-voice-input").start();
        }
        void stopTurn() {
            new Thread(() -> {
                try { json(event("stop_turn")); }
                catch (Exception error) { closeFromWorker(); }
            }, "deck-voice-stop").start();
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
                if (!closed && !discardCapture) {
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
