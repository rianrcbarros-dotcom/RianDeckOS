using System.Buffers.Binary;
using System.Net.WebSockets;
using System.Text.Json;

internal sealed class LocalVoiceSession(LocalVoiceEngine engine, DeckAssistant assistant, MediaSession media, AssistedExecutionEngine operation)
{
    private readonly SemaphoreSlim sessions = new(1, 1);
    private const int Rate = 24000;
    private static readonly Dictionary<string, string> Commands = new()
    {
        ["status do pc"] = "Status do PC", ["status do computador"] = "Status do PC", ["como esta o computador"] = "Status do PC", ["como esta meu computador"] = "Status do PC",
        ["abra codex"] = "Abra Codex", ["abra o codex"] = "Abra Codex",
        ["abra cursor"] = "Abra Cursor", ["abra o cursor"] = "Abra Cursor",
        ["analise os erros do projeto"] = "Analise os erros do projeto",
        ["resuma as alteracoes recentes do repositorio"] = "Resuma as alterações recentes do repositório",
        ["verifique os testes que falharam"] = "Verifique os testes que falharam"
    };

    private static string Get(JsonElement json, string name) =>
        json.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() ?? "" : "";

    private static int AudioLevel(byte[] pcm, int offset, int count, double gain = 1)
    {
        if (count < 2) return 0;
        long energy = 0;
        for (int index = offset; index + 1 < offset + count; index += 2)
        {
            int value = BinaryPrimitives.ReadInt16LittleEndian(pcm.AsSpan(index,2));
            energy += (long)value * value;
        }
        return (int)Math.Clamp(Math.Sqrt(energy / (count / 2d)) * gain / 30d, 0, 100);
    }

    private static async Task<(byte[] Data, WebSocketMessageType Type)> Read(WebSocket socket, CancellationToken token)
    {
        var buffer = new byte[8192];
        using var message = new MemoryStream();
        WebSocketReceiveResult piece;
        do
        {
            piece = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), token);
            if (piece.MessageType == WebSocketMessageType.Close) return ([], piece.MessageType);
            if (message.Length + piece.Count > 16 * 1024) throw new InvalidDataException();
            message.Write(buffer, 0, piece.Count);
        } while (!piece.EndOfMessage);
        return (message.ToArray(), piece.MessageType);
    }

    internal async Task Run(HttpContext context)
    {
        if (!context.WebSockets.IsWebSocketRequest) { context.Response.StatusCode = 400; return; }
        using var socket = await context.WebSockets.AcceptWebSocketAsync();
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted);
        lifetime.CancelAfter(TimeSpan.FromMinutes(15));
        var token = lifetime.Token;
        using var send = new SemaphoreSlim(1);
        async Task Send(byte[] bytes, WebSocketMessageType mode, CancellationToken cancellation)
        {
            await send.WaitAsync(cancellation);
            try
            {
                // Do not abort the socket when a speaking turn is interrupted.
                cancellation.ThrowIfCancellationRequested();
                await socket.SendAsync(new ArraySegment<byte>(bytes), mode, true, token);
            }
            finally { send.Release(); }
        }

        Task Event(object value, CancellationToken cancellation) =>
            Send(JsonSerializer.SerializeToUtf8Bytes(value), WebSocketMessageType.Text, cancellation);
        if (!sessions.Wait(0))
        {
            await Event(new { type = "error", text = "Já tem uma conversa aberta." }, token);
            return;
        }

        Task? windows = null;
        CancellationTokenSource? windowsCancel = null;
        Task? turn = null;
        CancellationTokenSource? turnCancel = null;
        var history = new List<object>();
        using var voiceGate = new SemaphoreSlim(1);
        using var input = new MemoryStream();
        (string Id, string Command)? pending = null;
        var pendingGate = new object();
        bool recording = false;
        string voice = "";
        double speed = 1, pause = .2, volume = .8;
        bool phoneSpeaker = false;

        async Task Say(string text, CancellationToken stop)
        {
            text = engine.Personalize(text);
            await Event(new { type = "caption", text }, stop);
            byte[] pcm = await engine.Speak(text, voice, speed, pause, stop);
            await Event(new { type = "audio", item = "local_" + Guid.NewGuid().ToString("N"), index = 0 }, stop);
            if (!phoneSpeaker)
            {
                var playback = WindowsVoiceOutput.Play(pcm, volume, stop);
                var clock = System.Diagnostics.Stopwatch.StartNew();
                while (!playback.IsCompleted)
                {
                    int offset = (int)Math.Min(pcm.Length, clock.ElapsedMilliseconds * 48) & ~1;
                    int count = Math.Min(3840, pcm.Length - offset);
                    await Event(new { type = "level", value = AudioLevel(pcm, offset, count, volume) }, stop);
                    await Task.WhenAny(playback, Task.Delay(80, stop));
                }
                await playback;
                await Event(new { type = "done" }, stop);
                return;
            }
            // Pace chunks near playback speed so a voice interruption never leaves a long audio backlog.
            var elapsed = System.Diagnostics.Stopwatch.StartNew();
            const int packet = 3840;
            for (int offset = 0; offset < pcm.Length; offset += packet)
            {
                int due = offset * 1000 / (Rate * 2) - 160;
                int wait = due - (int)elapsed.ElapsedMilliseconds;
                if (wait > 0) await Task.Delay(wait, stop);
                int size = Math.Min(packet, pcm.Length - offset);
                await Send(pcm.AsSpan(offset, size).ToArray(), WebSocketMessageType.Binary, stop);
            }
            await Event(new { type = "done" }, stop);
        }

        void StartTurn(Func<CancellationToken, Task> run)
        {
            turnCancel?.Dispose();
            var cut = CancellationTokenSource.CreateLinkedTokenSource(token);
            turnCancel = cut;
            turn = Task.Run(async () =>
            {
                try { await run(cut.Token); }
                catch (OperationCanceledException) when (cut.IsCancellationRequested) { }
                catch (Exception error) when (error is IOException or HttpRequestException or JsonException
                    or OperationCanceledException or System.ComponentModel.Win32Exception)
                {
                    if (!cut.IsCancellationRequested)
                    {
                        await Event(new { type = "caption", text = "Deu problema no processamento local. Confira Ollama, Whisper e Piper." }, token);
                        await Event(new { type = "done" }, token);
                    }
                }
            }, CancellationToken.None);
        }

        async Task Heard(byte[] pcm, CancellationToken stop)
        {
            await Event(new { type = "thinking" }, stop);
            var text = await engine.Transcribe(pcm, stop);
            if (text.Length < 2 || text.StartsWith('[') || text.StartsWith('('))
            {
                await Event(new { type = "caption", text = "Não consegui transcrever. Toque para falar de novo." }, stop);
                await Event(new { type = "done" }, stop);
                return;
            }
            await Event(new { type = "heard", text }, stop);
            var key = string.Concat(text.ToLowerInvariant().Normalize(System.Text.NormalizationForm.FormD)
                .Where(c => System.Globalization.CharUnicodeInfo.GetUnicodeCategory(c) != System.Globalization.UnicodeCategory.NonSpacingMark))
                .Trim().TrimEnd('.', '?', '!');
            bool google = GoogleRead.Matches(text);
            bool direct = VoiceIntent.IsDirect(text);
            string? assisted = google || direct && VoiceIntent.App(text) != "cursor" ? null : operation.Request(text);
            string reply;
            if (assisted != null) reply = assisted;
            else if (Commands.TryGetValue(key, out var command) || google || direct || VoiceIntent.LooksLikeOpening(text))
            {
                command ??= text;
                var result = await assistant.Answer(new(command), stop);
                if (result.Confirm)
                {
                    string id = Guid.NewGuid().ToString("N");
                    lock (pendingGate) pending = (id, command);
                    await Event(new { type = "confirm", id, text = result.Text }, stop);
                    return;
                }
                if (direct && !google)
                {
                    await Event(new { type = "action_result", text = engine.Personalize(result.Text) }, stop);
                    await Event(new { type = "done" }, stop);
                    return;
                }
                reply = result.Text;
            }
            else reply = await engine.Answer(history, text, stop);
            await Say(string.IsNullOrWhiteSpace(reply) ? "Pode falar de novo{nome}?" : reply, stop);
        }

        async Task Feed(byte[] data)
        {
            await voiceGate.WaitAsync(token);
            try
            {
                int remaining = Rate * 2 * 20 - (int)input.Length;
                if (recording && remaining > 0) input.Write(data, 0, Math.Min(data.Length, remaining));
            }
            finally { voiceGate.Release(); }
        }

        static byte[] TrimVoice(byte[] data)
        {
            const int block = Rate / 50 * 2;
            var levels = new List<(int Offset, double Rms)>();
            for (int offset = 0; offset + block <= data.Length; offset += block)
            {
                long power = 0;
                for (int i = offset; i < offset + block; i += 2)
                {
                    int sample = BinaryPrimitives.ReadInt16LittleEndian(data.AsSpan(i, 2));
                    power += (long)sample * sample;
                }
                levels.Add((offset, Math.Sqrt(power / (block / 2d))));
            }
            if (levels.Count < 8) return [];
            double peak = levels.Select(item => item.Rms).OrderDescending().Take(3).Average();
            double floor = Math.Max(140, peak * .10);
            var voiced = levels.Where(item => item.Rms >= floor).ToArray();
            if (voiced.Length < 8) return [];
            int start = Math.Max(0, voiced[0].Offset - 8 * block);
            int end = Math.Min(data.Length, voiced[^1].Offset + 10 * block);
            return data[start..end];
        }

        async Task CaptureWindows(CancellationToken capture)
        {
            try
            {
                long lastLevel = 0;
                await WindowsVoiceInput.Capture(
                    () => Event(new { type = "source", text = "● Microfone padrão do Windows" }, capture),
                    async pcm =>
                    {
                        await Feed(pcm);
                        long now = Environment.TickCount64;
                        if (now - lastLevel < 80) return;
                        lastLevel = now;
                        await Event(new { type = "level", value = AudioLevel(pcm, 0, pcm.Length) }, capture);
                    }, capture);
            }
            catch (IOException)
            {
                await Event(new { type = "error", text = "Microfone padrão do Windows indisponível." }, token);
                lifetime.Cancel();
            }
            catch (OperationCanceledException) when (capture.IsCancellationRequested) { }
        }

        try
        {
            var initial = await Read(socket, token);
            if (initial.Type != WebSocketMessageType.Text) return;
            using var doc = JsonDocument.Parse(initial.Data);
            var prefs = doc.RootElement;
            string? missing = engine.Missing();
            if (missing == null && !await engine.HasModel(token)) missing = "Ollama local ou modelo qwen3:1.7b indisponível.";
            if (missing != null)
            {
                await Event(new { type = "error", text = missing }, token);
                return;
            }
            voice = Get(prefs, "voice");
            if (voice.Length == 0) voice = "pt_BR-faber-medium";
            phoneSpeaker = prefs.TryGetProperty("phoneSpeaker", out var output) && output.ValueKind == JsonValueKind.True;
            if (prefs.TryGetProperty("volume", out var loudness) && loudness.TryGetDouble(out var level))
                volume = Math.Clamp(level, 0, 1);
            if (prefs.TryGetProperty("speed", out var rate) && rate.TryGetDouble(out var rateValue))
                speed = Math.Clamp(rateValue, .8, 1.2);
            if (prefs.TryGetProperty("pause", out var gap) && gap.TryGetDouble(out var gapValue))
                pause = Math.Clamp(gapValue, 0, .6);
            string greeting = Get(prefs, "greeting");
            if (greeting.Length > 240) greeting = "";
            if (greeting.Length > 0 && prefs.TryGetProperty("respectMedia", out var respect) && respect.ValueKind == JsonValueKind.True)
            {
                var playing = JsonSerializer.SerializeToElement(await media.Read());
                if (playing.TryGetProperty("playing", out var active) && active.ValueKind == JsonValueKind.True) greeting = "";
            }
            await Event(new { type = "ready", greeting = greeting.Length > 0, phoneSpeaker }, token);
            if (greeting.Length > 0) StartTurn(stop => Say(greeting, stop));
            while (!token.IsCancellationRequested)
            {
                var data = await Read(socket, token);
                if (data.Type == WebSocketMessageType.Close) break;
                if (data.Type == WebSocketMessageType.Binary)
                {
                    if (windows == null && data.Data.Length is > 0 and <= 5760 && data.Data.Length % 2 == 0)
                        await Feed(data.Data);
                    continue;
                }
                using var control = JsonDocument.Parse(data.Data);
                var message = control.RootElement;
                switch (Get(message, "type"))
                {
                    case "start_capture":
                        if (recording || pending.HasValue) break;
                        if (turn is { IsCompleted: false }) await turn;
                        input.SetLength(0);
                        recording = true;
                        break;
                    case "pc_input":
                        if (recording && windows == null)
                        {
                            input.SetLength(0);
                            windowsCancel = CancellationTokenSource.CreateLinkedTokenSource(token);
                            windows = CaptureWindows(windowsCancel.Token);
                        }
                        break;
                    case "stop_turn":
                        windowsCancel?.Cancel();
                        if (windows != null) await windows;
                        windowsCancel?.Dispose();
                        windowsCancel = null;
                        windows = null;
                        recording = false;
                        input.SetLength(0);
                        lock (pendingGate) pending = null;
                        if (turn is { IsCompleted: false }) { turnCancel?.Cancel(); await turn; }
                        await Event(new { type = "done" }, token);
                        break;
                    case "submit_capture":
                        if (!recording) break;
                        windowsCancel?.Cancel();
                        if (windows != null) await windows;
                        windowsCancel?.Dispose();
                        windowsCancel = null;
                        windows = null;
                        recording = false;
                        byte[] pcm = TrimVoice(input.ToArray());
                        input.SetLength(0);
                        if (pcm.Length == 0)
                        {
                            await Event(new { type = "caption", text = "Não detectei fala. Toque para tentar de novo." }, token);
                            await Event(new { type = "done" }, token);
                        }
                        else StartTurn(stop => Heard(pcm, stop));
                        break;
                }
                if (Get(message, "type") == "decision")
                {
                    await voiceGate.WaitAsync(token);
                    try
                    {
                        string? command = null;
                        lock (pendingGate)
                        {
                            if (pending is { } proposal && proposal.Id == Get(message, "id"))
                            { command = proposal.Command; pending = null; }
                        }
                        if (command != null)
                        {
                            if (turn != null) await turn;
                            bool allowed = message.TryGetProperty("allow", out var decision) && decision.ValueKind == JsonValueKind.True;
                            StartTurn(async stop =>
                            {
                                await Event(new { type = "thinking" }, stop);
                                var result = allowed ? await assistant.Answer(new(command, true), stop) : new VoiceReply("Beleza, cancelei.");
                                await Say(result.Text[..Math.Min(480, result.Text.Length)], stop);
                            });
                        }
                    }
                    finally { voiceGate.Release(); }
                }
            }
        }
        catch (Exception e) when (e is WebSocketException or IOException or OperationCanceledException or JsonException) { }
        finally
        {
            lifetime.Cancel();
            try { if (windows != null) await windows; if (turn != null) await turn; }
            catch (Exception e) when (e is OperationCanceledException or WebSocketException or IOException) { }
            windowsCancel?.Dispose();
            turnCancel?.Dispose();
            sessions.Release();
        }
    }
}
