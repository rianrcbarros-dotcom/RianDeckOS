using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Text.Json;
using Microsoft.Win32;

internal static class DeckAutostart
{
    internal const string Key = @"Software\Microsoft\Windows\CurrentVersion\Run";
    internal const string Value = "RianDeckOS";
    internal static bool Enabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(Key);
        return key?.GetValue(Value) is string;
    }

    internal static void Set(bool enabled, string? serial, string? adb)
    {
        using var key = Registry.CurrentUser.CreateSubKey(Key);
        if (!enabled) { key.DeleteValue(Value, false); return; }
        string exe = Environment.ProcessPath ?? throw new InvalidOperationException("Agente não localizado.");
        if (!exe.EndsWith("RianDeck.Agent.exe", StringComparison.OrdinalIgnoreCase)
            || serial is null || !System.Text.RegularExpressions.Regex.IsMatch(serial, "^[A-Za-z0-9_-]+$")
            || adb?.Contains('"') == true) throw new InvalidOperationException("Inicie o agente com o executável e o serial USB.");
        string command = $"\"{exe}\" --deck-serial={serial}";
        if (!string.IsNullOrWhiteSpace(adb)) command += $" \"--deck-adb={adb}\"";
        if (command.Length > 260) throw new InvalidOperationException("Caminho longo para a inicialização do Windows.");
        key.SetValue(Value, command);
    }
}

internal sealed record VoiceRequest(string? Text, bool Confirm = false);
internal sealed record VoiceReply(string Text, bool Confirm = false);

internal sealed class DeckAssistant(IConfiguration config,
    WindowsMetrics metrics, IReadOnlyDictionary<string, Action> actions, DeckStudio studio)
{
    private readonly SemaphoreSlim codexGate = new(1);
    private string? CodexPath => config["deck-codex-cli"];
    private string? Project => config["deck-codex-project"];
    private bool CodexReady => Path.IsPathFullyQualified(CodexPath ?? "") && File.Exists(CodexPath)
        && Path.IsPathFullyQualified(Project ?? "") && Directory.Exists(Project);

    internal object Status() => new { codexConfigured = CodexReady,
        actionsEnabled = config.GetValue("deck-assistant-actions", true) };

    internal async Task<VoiceReply> Answer(VoiceRequest request, CancellationToken cancellation)
    {
        var text = request.Text?.Trim() ?? "";
        if (text.Length is 0 or > 1000) return new("Digite uma pergunta de até mil caracteres.");
        var phrase = Normalize(text);
        if (phrase.StartsWith("execute a macro ") || phrase.StartsWith("executar macro "))
        {
            if (!config.GetValue("deck-assistant-actions", true)) return new("Ações de voz desativadas no agente.");
            var name = phrase[(phrase.IndexOf("macro ", StringComparison.Ordinal) + 6)..];
            var candidates = studio.Configuration.Macros.Where(m => Normalize(m.Name) == name).Take(2).ToArray();
            if (candidates.Length != 1) return new("Informe o nome exato de uma macro cadastrada.");
            if (!request.Confirm) return new($"Executar a macro {candidates[0].Name} no Windows?", true);
            return new(studio.StartMacro(candidates[0].Id, true) == 202
                ? "Macro iniciada. Acompanhe em Automações." : "Não foi possível iniciar a macro.");
        }

        string? action = phrase switch
        {
            "pausar musica" or "pause a musica" or "play pause" => "media",
            "proxima musica" => "next",
            "musica anterior" => "previous",
            _ => null
        };
        string? app = VoiceIntent.App(text);
        action ??= app;
        if (action != null)
        {
            if (!config.GetValue("deck-assistant-actions", true)) return new("Ações de voz desativadas no agente.");
            if (!request.Confirm && app == null) return new($"Executar {action} no Windows?", true);
            try { cancellation.ThrowIfCancellationRequested(); actions[action](); return new(app == null ? "Pronto, Rian." : "Abri pra você, Rian."); }
            catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception)
            { return new("O Windows não conseguiu abrir ou executar esse comando."); }
        }

        var task = phrase switch
        {
            "resuma as alteracoes recentes do repositorio" or "codex resuma o repositorio" =>
                "Resuma o status do Git e as alterações recentes neste repositório.",
            "analise os erros do projeto" or "peca ao codex para analisar os erros" =>
                "Leia os arquivos de código e logs existentes neste repositório e explique possíveis erros.",
            "verifique os testes que falharam" =>
                "Leia somente os relatórios de testes já existentes no repositório e resuma as falhas. Não execute testes.",
            _ => null
        };
        if (task != null)
        {
            if (!CodexReady) return new("Configure o executável do Codex CLI e o projeto autorizado no agente.");
            if (!request.Confirm) return new("Enviar uma análise somente leitura ao Codex no projeto autorizado?", true);
            return new(await ReadWithCodex(task, cancellation));
        }

        if (phrase is "status do pc" or "como esta o computador" or "uso de cpu" or "uso de memoria"
            or "explique o que esta consumindo cpu")
        {
            using var info = JsonDocument.Parse(JsonSerializer.Serialize(metrics.Sample()));
            var d = info.RootElement;
            static string Percent(JsonElement element) => element.TryGetDoubleSafe(out var value)
                ? $"{value:F0}%" : "indisponível";
            string usage = $"CPU em {Percent(d.GetProperty("cpu"))}, memória em {Percent(d.GetProperty("ram"))}, GPU em {Percent(d.GetProperty("gpu"))}.";
            return new(phrase == "explique o que esta consumindo cpu" ? usage + " Só tenho o total de uso." : usage);
        }
        return new("Essa ação não está na lista autorizada.");
    }

    private async Task<string> ReadWithCodex(string task, CancellationToken cancellation)
    {
        if (!codexGate.Wait(0)) return "O Codex já está analisando uma solicitação.";
        try
        {
            var start = new ProcessStartInfo(CodexPath!)
            {
                WorkingDirectory = Project!, UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true
            };
            foreach (var arg in new[] { "--ask-for-approval", "never", "exec", "--sandbox", "read-only",
                $"Responda em português do Brasil. Somente leitura. Não instale, altere arquivos nem procure credenciais. {task}" })
                start.ArgumentList.Add(arg);
            using var process = Process.Start(start);
            if (process is null) return "Não foi possível iniciar o Codex.";
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            timeout.CancelAfter(TimeSpan.FromSeconds(90));
            var stdout = process.StandardOutput.ReadToEndAsync(timeout.Token);
            var stderr = process.StandardError.ReadToEndAsync(timeout.Token);
            try
            {
                await process.WaitForExitAsync(timeout.Token);
                await stderr;
                var answer = (await stdout).Trim();
                return process.ExitCode == 0 && answer.Length > 0 ? Clip(answer)
                    : "O Codex não concluiu. Verifique o login, a versão e o projeto configurado.";
            }
            finally { if (!process.HasExited) process.Kill(entireProcessTree: true); }
        }
        catch (Exception ex) when (ex is OperationCanceledException or System.ComponentModel.Win32Exception)
        { return "A análise do Codex foi interrompida ou não pôde iniciar."; }
        finally { codexGate.Release(); }
    }

    private static string Clip(string value) => value[..Math.Min(value.Length, 2200)];
    private static string Normalize(string value) => string.Concat(value.ToLowerInvariant()
        .Normalize(NormalizationForm.FormD).Where(c => CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark))
        .Trim().TrimEnd('.', '?', '!');
}

internal static class VoiceJson
{
    internal static bool TryGetDoubleSafe(this JsonElement value, out double number)
    {
        number = 0;
        return value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out number);
    }
}
