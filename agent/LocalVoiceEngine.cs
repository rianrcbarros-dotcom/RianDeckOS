using System.Buffers.Binary;
using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Text.Json;

internal sealed class LocalVoiceEngine(IConfiguration config, IHttpClientFactory clients) : IDisposable
{
    private Process? ownedOllama;
    private string Root => config["deck-local-voice-root"] ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "RianDeckVoice");
    private string Whisper => Path.Combine(Root, "whisper", "whisper-cli.exe");
    private string WhisperModel => Path.Combine(Root, "models", "ggml-base.bin");
    private string PreciseWhisperModel => Path.Combine(Root, "models", "ggml-small-q5_1.bin");
    private string PiperRuntime => Path.Combine(Root, "piper-runtime");
    private string PiperExecutable
    {
        get
        {
            var path = Path.Combine(Root, "python-path.txt");
            return File.Exists(path) ? File.ReadAllText(path).Trim() : "";
        }
    }
    private string Voices => Path.Combine(Root, "voices");
    private string VoiceName => config["deck-voice-name"]?.Trim() ?? "";
    internal string Personalize(string text) => text.Replace("{nome}", VoiceName.Length is > 0 and <= 40 ? ", " + VoiceName : "",
        StringComparison.Ordinal);
    private string Model => config["deck-ollama-model"] ?? "qwen3:1.7b";

    internal string? Missing()
    {
        if (!File.Exists(Whisper) || !File.Exists(WhisperModel)) return "Whisper.cpp e o modelo português ainda precisam ser instalados.";
        if (!File.Exists(PiperExecutable) || !Directory.Exists(Path.Combine(PiperRuntime, "piper")) || !Directory.Exists(Voices) ||
            !Directory.EnumerateFiles(Voices, "pt_BR-*.onnx").Any(p => File.Exists(p + ".json")))
            return "Piper ainda precisa de uma voz brasileira instalada.";
        return null;
    }

    internal async Task<bool> HasModel(CancellationToken token)
    {
        bool? status = await ModelStatus(token);
        if (status.HasValue) return status.Value;
        string executable = Path.Combine(Root, "ollama", "ollama.exe");
        if (!File.Exists(executable)) return false;
        if (ownedOllama?.HasExited != false)
        {
            ownedOllama?.Dispose();
            var start = new ProcessStartInfo(executable)
            {
                UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = Path.GetDirectoryName(executable)!
            };
            start.ArgumentList.Add("serve");
            start.Environment["OLLAMA_HOST"] = "127.0.0.1:11434";
            start.Environment["OLLAMA_MODELS"] = Path.Combine(Root, "ollama-models");
            start.Environment["OLLAMA_NO_CLOUD"] = "1";
            start.Environment["OLLAMA_NUM_PARALLEL"] = "1";
            try { ownedOllama = Process.Start(start); }
            catch (System.ComponentModel.Win32Exception) { return false; }
        }
        for (int i = 0; i < 16; i++)
        {
            await Task.Delay(250, token);
            status = await ModelStatus(token);
            if (status.HasValue) return status.Value;
            if (ownedOllama?.HasExited != false) return false;
        }
        return false;
    }

    private async Task<bool?> ModelStatus(CancellationToken token)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
        deadline.CancelAfter(TimeSpan.FromSeconds(1));
        try
        {
            using var response = await clients.CreateClient("ollama").GetAsync("api/tags", deadline.Token);
            if (!response.IsSuccessStatusCode) return false;
            using var data = JsonDocument.Parse(await response.Content.ReadAsStreamAsync(deadline.Token));
            return data.RootElement.GetProperty("models").EnumerateArray().Any(m =>
                m.GetProperty("name").GetString() == Model);
        }
        catch (Exception e) when (e is HttpRequestException or JsonException or TaskCanceledException) { return null; }
    }

    public void Dispose()
    {
        if (ownedOllama == null) return;
        try { if (!ownedOllama.HasExited) ownedOllama.Kill(entireProcessTree: true); }
        finally { ownedOllama.Dispose(); }
    }

    internal async Task<string> Transcribe(byte[] pcm, CancellationToken token)
    {
        string scratch = Path.Combine(Root, "scratch");
        Directory.CreateDirectory(scratch);
        string wav = Path.Combine(scratch, Guid.NewGuid().ToString("N") + ".wav");
        try
        {
            byte[] audio = Resample(pcm, 24000, 16000);
            using (var file = new BinaryWriter(File.Create(wav)))
            {
                file.Write("RIFF"u8); file.Write(36 + audio.Length); file.Write("WAVEfmt "u8);
                file.Write(16); file.Write((short)1); file.Write((short)1); file.Write(16000); file.Write(32000);
                file.Write((short)2); file.Write((short)16); file.Write("data"u8); file.Write(audio.Length); file.Write(audio);
            }
            async Task<string> Decode(string model, bool scores, int context = 0)
            {
                string[] args = ["-m", model, "-f", wav, "-l", "pt", "-t", "4", "-nt", "-np",
                    "-bo", "1", "-bs", "2", "-nf", "-nth", "0.5"];
                if (scores) args = [..args, "-ojf", "-of", wav];
                if (context > 0) args = [..args, "-ac", context.ToString(CultureInfo.InvariantCulture)];
                var text = await Run(Whisper, args, null, token);
                return Encoding.UTF8.GetString(text).Trim();
            }

            bool preciseAvailable = File.Exists(PreciseWhisperModel);
            // A short utterance fits in a smaller encoder window. Run the more precise model once.
            if (audio.Length <= 96000 && preciseAvailable)
                return await Decode(PreciseWhisperModel, false, 512);

            string result = await Decode(WhisperModel, preciseAvailable);
            if (!string.IsNullOrWhiteSpace(result) && File.Exists(wav + ".json"))
            {
                using var json = JsonDocument.Parse(await File.ReadAllBytesAsync(wav + ".json", token));
                var probabilities = new List<double>();
                if (json.RootElement.TryGetProperty("transcription", out var segments))
                    foreach (var segment in segments.EnumerateArray())
                        if (segment.TryGetProperty("tokens", out var tokens))
                            foreach (var word in tokens.EnumerateArray())
                                if (word.TryGetProperty("id", out var id) && id.GetInt32() < 50257 &&
                                    word.TryGetProperty("p", out var confidence))
                                    probabilities.Add(confidence.GetDouble());
                // A complete command for an allowlisted app can skip the heavier pass when
                // confidence is reasonable. Unclear audio and general questions still get reviewed.
                bool shortcut = VoiceIntent.App(result) is not null;
                double threshold = shortcut ? .59 : audio.Length < 80000 ? .80 : .64;
                if (probabilities.Count != 0 && probabilities.Average() < threshold)
                    result = await Decode(PreciseWhisperModel, false);
            }
            return result;
        }
        finally
        {
            File.Delete(wav);
            File.Delete(wav + ".json");
        }
    }

    internal async Task<string> Answer(List<object> history, string question, CancellationToken token)
    {
        history.Add(new { role = "user", content = question });
        try
        {
            using var response = await clients.CreateClient("ollama").PostAsJsonAsync("api/chat", new
            {
                model = Model, stream = false, think = false, keep_alive = "3m",
                messages = new[] { (object)new { role = "system", content = "Converse em português brasileiro, de forma bem informal, breve e natural. Responda com uma frase curta, em linguagem falada e sem markdown. Dê a resposta direta antes de detalhes. Use fatos verificáveis. Se o texto da fala estiver confuso, peça para a pessoa repetir. Não invente ações, arquivos nem compromissos." } }.Concat(history.TakeLast(9)),
                options = new { num_ctx = 2048, num_predict = 42, temperature = .35 }
            }, token);
            response.EnsureSuccessStatusCode();
            using var data = JsonDocument.Parse(await response.Content.ReadAsStreamAsync(token));
            string reply = data.RootElement.GetProperty("message").GetProperty("content").GetString()?.Trim() ?? "";
            if (reply.Length > 300) reply = reply[..300];
            if (reply.Length > 0) history.Add(new { role = "assistant", content = reply });
            if (history.Count > 10) history.RemoveRange(0, history.Count - 10);
            return reply;
        }
        catch { history.RemoveAt(history.Count - 1); throw; }
    }

    internal async Task<byte[]> Speak(string text, string requestedVoice, double speed, double pause, CancellationToken token)
    {
        var voice = Directory.EnumerateFiles(Voices, "pt_BR-*.onnx")
            .Where(p => File.Exists(p + ".json")).OrderBy(p => p, StringComparer.OrdinalIgnoreCase).ToArray();
        string model = voice.FirstOrDefault(p => Path.GetFileNameWithoutExtension(p) == requestedVoice)
            ?? voice.FirstOrDefault() ?? throw new IOException("Voz indisponível.");
        using var settings = JsonDocument.Parse(await File.ReadAllTextAsync(model + ".json", token));
        int rate = settings.RootElement.GetProperty("audio").GetProperty("sample_rate").GetInt32();
        string speech = VoiceIntent.Spoken(Personalize(text));
        speech = System.Text.RegularExpressions.Regex.Replace(speech, @"\b(\d{1,2}):(\d{2})\b",
            match => match.Groups[1].Value + " horas" +
                (match.Groups[2].Value == "00" ? "" : " e " + match.Groups[2].Value));
        speech = System.Text.RegularExpressions.Regex.Replace(speech.Replace("…", "."),
            @"[\t\r\n]+", " ").Trim();
        if (VoiceName.Length is > 0 and <= 40 && config["deck-voice-pronunciation"] is { Length: > 0 and <= 60 } pronunciation)
            speech = System.Text.RegularExpressions.Regex.Replace(speech,
                $@"\b{System.Text.RegularExpressions.Regex.Escape(VoiceName)}\b", _ => pronunciation,
                System.Text.RegularExpressions.RegexOptions.IgnoreCase);
        var raw = await Run(PiperExecutable, ["-s", "-m", "piper", "-m", model, "--output-raw",
            "--length-scale", (1 / speed).ToString("0.00", CultureInfo.InvariantCulture),
            "--sentence-silence", pause.ToString("0.00", CultureInfo.InvariantCulture)], speech, token, PiperRuntime);
        return Resample(raw, rate, 24000);
    }

    private static async Task<byte[]> Run(string exe, string[] args, string? input, CancellationToken token, string? pythonPath = null)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
        deadline.CancelAfter(TimeSpan.FromSeconds(30));
        var start = new ProcessStartInfo(exe) {
            WorkingDirectory = Path.GetDirectoryName(exe)!, UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true, RedirectStandardInput = input != null,
            StandardInputEncoding = input == null ? null : new UTF8Encoding(false)
        };
        if (pythonPath != null)
        {
            start.Environment["PYTHONPATH"] = pythonPath;
            start.Environment["PYTHONUTF8"] = "1";
        }
        foreach (string arg in args) start.ArgumentList.Add(arg);
        using var process = Process.Start(start) ?? throw new IOException("Processo local indisponível.");
        using var output = new MemoryStream();
        var stdout = process.StandardOutput.BaseStream.CopyToAsync(output, deadline.Token);
        var stderr = process.StandardError.ReadToEndAsync(deadline.Token);
        try
        {
            if (input != null)
            {
                await process.StandardInput.WriteLineAsync(input.AsMemory(), deadline.Token);
                process.StandardInput.Close();
            }
            await Task.WhenAll(stdout, stderr, process.WaitForExitAsync(deadline.Token));
            if (process.ExitCode != 0) throw new IOException($"Falha em {Path.GetFileName(exe)}.");
            return output.ToArray();
        }
        finally
        {
            if (!process.HasExited) { process.Kill(entireProcessTree: true); await process.WaitForExitAsync(); }
        }
    }

    private static byte[] Resample(byte[] pcm, int from, int to)
    {
        if (from is < 8000 or > 96000) throw new InvalidDataException("Taxa de áudio incompatível.");
        if (from == to) return pcm;
        int frames = pcm.Length / 2;
        var result = new byte[(int)((long)frames * to / from) * 2];
        for (int i = 0; i < result.Length / 2; i++)
        {
            double source = (double)i * from / to;
            int first = (int)source;
            short a = BinaryPrimitives.ReadInt16LittleEndian(pcm.AsSpan(first * 2));
            short b = BinaryPrimitives.ReadInt16LittleEndian(pcm.AsSpan(Math.Min(first + 1, frames - 1) * 2));
            BinaryPrimitives.WriteInt16LittleEndian(result.AsSpan(i * 2), (short)(a + (b - a) * (source - first)));
        }
        return result;
    }
}
