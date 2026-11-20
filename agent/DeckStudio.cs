using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;

internal sealed record DeckKey(string Label, string Icon, string Color, string Action, string? Folder = null);
internal sealed record DeckPage(string Id, string Name, DeckKey[] Buttons);
internal sealed record DeckProfile(string Id, string Name, string[] Processes, DeckPage[] Pages);
internal sealed record DeckStep(string Action, int DelayMs);
internal sealed record DeckMacro(string Id, string Name, DeckStep[] Steps, bool Confirm);
internal sealed record DeckSettings(bool Auto, string Selected, DeckProfile[] Profiles, DeckMacro[] Macros);
internal sealed record DeckRun(string Name, string State, DateTimeOffset Time);

internal sealed class DeckStudio
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { WriteIndented = true };
    private static readonly string[] Icons = ["youtube", "codex", "cursor", "miro", "serpro", "mic", "play", "steam",
        "desktop", "lock", "spotify", "previous", "next", "volume-up", "volume-down", "mute", "folder",
        "terminal", "bolt", "grid", "settings", "chart"];
    private readonly string directory, path, historyPath;
    private readonly object gate = new();
    private readonly IReadOnlyDictionary<string, Action> actions;
    private readonly Dictionary<string, string> scripts;
    private readonly SemaphoreSlim macroQueue = new(1);
    private DeckSettings settings;
    private DeckRun[] history;
    private int revision = 1;

    internal DeckStudio(string directory, IReadOnlyDictionary<string, Action> actions, IConfiguration config)
    {
        this.directory = directory;
        this.actions = actions;
        scripts = config.GetSection("deck-scripts").GetChildren()
            .Where(pair => ValidId(pair.Key) && pair.Value is { } file && Path.IsPathFullyQualified(file)
                && file.EndsWith(".ps1", StringComparison.OrdinalIgnoreCase))
            .ToDictionary(pair => pair.Key, pair => pair.Value!, StringComparer.OrdinalIgnoreCase);
        Directory.CreateDirectory(directory);
        path = Path.Combine(directory, "deck.json");
        historyPath = Path.Combine(directory, "history.json");
        settings = Read<DeckSettings>(path) is { } saved && Validate(saved) is null ? saved : Defaults();
        // Convert the original shortcut in existing profiles without changing custom Miro buttons or macros.
        settings = settings with { Profiles = settings.Profiles.Select(profile => profile with
        {
            Pages = profile.Pages.Select(page => page with { Buttons = page.Buttons.Select(button =>
                button is { Action: "miro", Icon: "miro", Label: "Miro" }
                    ? button with { Label = "Git Corporativo Serpro", Icon = "serpro", Action = "serpro" } : button)
                .ToArray() }).ToArray()
        }).ToArray() };
        history = Read<DeckRun[]>(historyPath) ?? [];
    }

    internal object Catalog => new
    {
        actions = actions.Keys.Append("microphone").Order().ToArray(),
        icons = Icons,
        scripts = scripts.Keys.Order().ToArray()
    };

    internal DeckSettings Configuration { get { lock (gate) return settings; } }

    internal object Snapshot()
    {
        lock (gate)
        {
            string process = settings.Auto ? ForegroundProcess() : "";
            var selected = settings.Profiles.FirstOrDefault(profile =>
                profile.Processes.Contains(process, StringComparer.OrdinalIgnoreCase)) ??
                settings.Profiles.First(profile => profile.Id == settings.Selected);
            return new { revision, settings.Auto, active = selected.Id, selected.Pages,
                profiles = settings.Profiles.Select(p => new { p.Id, p.Name }),
                macros = settings.Macros.Select(m => new { m.Id, m.Name, confirm = Sensitive(m) }),
                history };
        }
    }

    internal string? Save(DeckSettings value)
    {
        lock (gate)
        {
            string? invalid = Validate(value);
            if (invalid is not null) return invalid;
            Write(path, value);
            settings = value;
            revision++;
            return null;
        }
    }

    internal bool Select(string? profile, bool? automatic)
    {
        lock (gate)
        {
            if (profile is not null && !settings.Profiles.Any(p => p.Id == profile)) return false;
            var updated = settings with { Selected = profile ?? settings.Selected, Auto = automatic ?? settings.Auto };
            Write(path, updated);
            settings = updated;
            revision++;
            return true;
        }
    }

    internal int StartMacro(string id, bool confirmed)
    {
        DeckMacro? macro;
        lock (gate) macro = settings.Macros.FirstOrDefault(m => m.Id == id);
        if (macro is null) return 404;
        if (Sensitive(macro) && !confirmed) return 409;
        if (!macroQueue.Wait(0)) return 429;
        _ = ExecuteMacro(macro);
        return 202;
    }

    private async Task ExecuteMacro(DeckMacro macro)
    {
        string state = "Concluída";
        try
        {
            foreach (var step in macro.Steps)
            {
                if (step.DelayMs > 0) await Task.Delay(step.DelayMs);
                if (step.Action == "microphone")
                {
                    if (Microphones.Toggle() is null) throw new InvalidOperationException();
                }
                else if (step.Action.StartsWith("script:", StringComparison.Ordinal))
                {
                    var script = scripts[step.Action[7..]];
                    if (!File.Exists(script)) throw new FileNotFoundException();
                    var info = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory,
                        @"WindowsPowerShell\v1.0\powershell.exe")) { UseShellExecute = false, CreateNoWindow = true };
                    foreach (var argument in new[] { "-NoProfile", "-NonInteractive", "-File", script })
                        info.ArgumentList.Add(argument);
                    using var process = Process.Start(info) ?? throw new InvalidOperationException();
                    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(45));
                    try { await process.WaitForExitAsync(timeout.Token); }
                    catch (OperationCanceledException) { process.Kill(entireProcessTree: true); throw; }
                    if (process.ExitCode != 0) throw new InvalidOperationException();
                }
                else actions[step.Action]();
            }
        }
        catch (Exception) { state = "Falhou"; }
        finally
        {
            lock (gate)
            {
                history = new[] { new DeckRun(macro.Name, state, DateTimeOffset.Now) }
                    .Concat(history).Take(25).ToArray();
                try { Write(historyPath, history); }
                catch (IOException) { }
                finally { macroQueue.Release(); }
            }
        }
    }

    private static bool Sensitive(DeckMacro macro) =>
        macro.Confirm || macro.Steps.Any(step => step.Action == "lock" || step.Action.StartsWith("script:", StringComparison.Ordinal));

    private string? Validate(DeckSettings value)
    {
        if (value.Profiles is null || value.Profiles.Length is < 1 or > 8 || value.Macros is null || value.Macros.Length > 24)
            return "Limite: 1–8 perfis e até 24 macros.";
        if (value.Profiles.Any(p => p is null || !ValidId(p.Id)) ||
            value.Profiles.Select(p => p.Id).Distinct().Count() != value.Profiles.Length ||
            !value.Profiles.Any(p => p.Id == value.Selected)) return "Perfis ou seleção inválidos.";
        if (value.Macros.Any(m => m is null || !ValidId(m.Id) || !ValidText(m.Name, 40) ||
            m.Steps is null || m.Steps.Length is < 1 or > 16 ||
            m.Steps.Any(s => s is null || s.DelayMs is < 0 or > 10000 ||
                (!IsAction(s.Action) && !(s.Action?.StartsWith("script:", StringComparison.Ordinal) == true &&
                    scripts.ContainsKey(s.Action[7..]))))) ||
            value.Macros.Select(m => m.Id).Distinct().Count() != value.Macros.Length) return "Macros inválidas.";
        foreach (var profile in value.Profiles)
        {
            if (!ValidText(profile.Name, 28) || profile.Processes is null || profile.Processes.Length > 20 ||
                profile.Processes.Any(p => !ValidText(p, 48) || p.Contains('/') || p.Contains('\\')) ||
                profile.Pages is null || profile.Pages.Length is < 1 or > 16 ||
                profile.Pages.Any(p => p is null || !ValidId(p.Id)) ||
                profile.Pages.Select(p => p.Id).Distinct().Count() != profile.Pages.Length)
                return "Páginas ou aplicativos inválidos.";
            foreach (var page in profile.Pages)
            {
                if (!ValidText(page.Name, 28) || page.Buttons is null || page.Buttons.Length > 9)
                    return "Cada página aceita até nove botões.";
                foreach (var button in page.Buttons)
                {
                    if (button is null || !ValidText(button.Label, 28) || !Icons.Contains(button.Icon) ||
                        button.Color is not ("graphite" or "red" or "blue" or "green" or "amber"))
                        return "Nome, cor ou ícone de botão inválido.";
                    if (button.Folder is not null)
                    {
                        if (button.Action != "" || button.Folder == page.Id || !profile.Pages.Any(p => p.Id == button.Folder))
                            return "Destino de pasta inválido.";
                    }
                    else if (!IsAction(button.Action) && !(button.Action?.StartsWith("macro:", StringComparison.Ordinal) == true &&
                        value.Macros.Any(m => m.Id == button.Action[6..]))) return "Ação de botão inválida.";
                }
            }
        }
        return null;
    }

    private bool IsAction(string? id) => id is not null && (id == "microphone" || actions.ContainsKey(id));
    private static bool ValidId(string? id) => id is { Length: > 0 and <= 40 } &&
        id.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_');
    private static bool ValidText(string? text, int max) => !string.IsNullOrWhiteSpace(text) && text.Length <= max &&
        !text.Any(char.IsControl);

    private static T? Read<T>(string file)
    {
        try { return JsonSerializer.Deserialize<T>(File.ReadAllText(file), Json); }
        catch (Exception error) when (error is IOException or JsonException) { return default; }
    }

    private static void Write<T>(string file, T value)
    {
        string pending = file + ".tmp";
        File.WriteAllText(pending, JsonSerializer.Serialize(value, Json));
        File.Move(pending, file, true);
    }

    private static string ForegroundProcess()
    {
        GetWindowThreadProcessId(GetForegroundWindow(), out uint pid);
        if (pid == 0) return "";
        try { using var process = Process.GetProcessById((int)pid); return process.ProcessName; }
        catch (Exception error) when (error is ArgumentException or InvalidOperationException or System.ComponentModel.Win32Exception)
        { return ""; }
    }

    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr window, out uint process);

    private static DeckSettings Defaults()
    {
        static DeckKey Key(string label, string icon, string action) => new(label, icon, action == "lock" ? "red" : "graphite", action);
        DeckKey yt = Key("YouTube", "youtube", "youtube"), cursor = Key("Cursor", "cursor", "cursor"),
            codex = Key("Codex", "codex", "codex"), mic = Key("Silenciar MIC", "mic", "microphone"),
            media = Key("Play / Pause", "play", "media"), steam = Key("Steam", "steam", "steam"),
            spotify = Key("Spotify", "spotify", "spotify"), terminal = Key("PowerShell", "terminal", "terminal"),
            down = Key("Diminuir volume", "volume-down", "volume-down"), up = Key("Aumentar volume", "volume-up", "volume-up"),
            prev = Key("Faixa anterior", "previous", "previous"), next = Key("Próxima faixa", "next", "next");
        return new(false, "principal", [
            new("principal", "Principal", [], [
                new("controle", "Controle", [yt, codex, cursor, Key("Git Corporativo Serpro", "serpro", "serpro"), mic, media, steam,
                    Key("Área de trabalho", "desktop", "workspace"), Key("Bloquear tela", "lock", "lock")]),
                new("extras", "Extras", [spotify, prev, next, up, down, Key("Silenciar som", "mute", "mute"),
                    Key("Arquivos", "folder", "explorer"), terminal])]),
            new("dev", "Desenvolvimento", ["cursor", "code", "codex"], [new("dev", "Desenvolvimento", [
                cursor, codex, terminal, Key("Navegador", "desktop", "browser"), Key("Captura", "grid", "screenshot"),
                Key("Arquivos", "folder", "explorer")])]),
            new("reuniao", "Reunião", ["ms-teams", "teams", "zoom"], [new("reuniao", "Reunião", [
                mic, up, down, Key("Abrir reunião", "desktop", "meeting"), Key("Captura", "grid", "screenshot")])]),
            new("lazer", "Entretenimento", ["steam", "spotify", "vlc"], [new("lazer", "Entretenimento", [
                steam, yt, spotify, prev, media, next, up, down])])
        ], []);
    }
}
