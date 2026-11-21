using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

using var instance = new Mutex(false, @"Local\RianDeckOS-Agent", out bool firstInstance);
if (!firstInstance) return;

var token = File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "pairing.key")).Trim();
if (token.Length != 64) throw new InvalidOperationException("Chave de pareamento inválida.");
var expected = Encoding.UTF8.GetBytes("Bearer " + token);
var studioDirectory = Path.Combine(AppContext.BaseDirectory, "studio");
Directory.CreateDirectory(studioDirectory);
var editorKeyFile = Path.Combine(studioDirectory, "editor.key");
if (!File.Exists(editorKeyFile))
    File.WriteAllText(editorKeyFile, Convert.ToHexString(RandomNumberGenerator.GetBytes(32)));
var editorExpected = Encoding.UTF8.GetBytes("Bearer " + File.ReadAllText(editorKeyFile).Trim());
var builder = WebApplication.CreateBuilder(new WebApplicationOptions { Args = args, ContentRootPath = AppContext.BaseDirectory });
builder.Logging.AddFilter("Microsoft.AspNetCore.Hosting.Diagnostics", LogLevel.Warning);
builder.Services.AddHttpClient("ollama", c =>
{
    c.BaseAddress = new Uri("http://127.0.0.1:11434/");
    c.Timeout = TimeSpan.FromSeconds(45);
}).ConfigurePrimaryHttpMessageHandler(() => new HttpClientHandler { UseProxy = false, AllowAutoRedirect = false });
builder.Services.AddSingleton<GpuTemperature>();
builder.Services.AddHostedService(services => services.GetRequiredService<GpuTemperature>());
builder.Services.AddHttpClient("biblia", client =>
{
    client.Timeout = TimeSpan.FromSeconds(5);
    client.MaxResponseContentBufferSize = 1024 * 1024;
});
var serial = builder.Configuration["deck-serial"];
if (!string.IsNullOrWhiteSpace(serial))
{
    builder.Services.AddSingleton(new UsbDeckOptions(serial,
        builder.Configuration["deck-adb"] ?? @"C:\RianDeck\platform-tools\adb.exe"));
    builder.Services.AddHostedService<UsbDeckSession>();
}
var app = builder.Build();
app.UseWebSockets();
var shortcuts = new DesktopShortcuts(builder.Configuration);
app.Use(async (context, next) =>
{
    if ((context.Request.Path == "/editor" || context.Request.Path == "/google/return") &&
        HttpMethods.IsGet(context.Request.Method))
    {
        await next(context);
        return;
    }
    var supplied = Encoding.UTF8.GetBytes(context.Request.Headers.Authorization.ToString());
    var required = context.Request.Path.StartsWithSegments("/studio") ? editorExpected : expected;
    if (!CryptographicOperations.FixedTimeEquals(supplied, required))
    {
        context.Response.StatusCode = 401;
        return;
    }
    if (context.Request.Path == "/studio/config" && HttpMethods.IsPut(context.Request.Method))
    {
        var limit = context.Features.Get<Microsoft.AspNetCore.Http.Features.IHttpMaxRequestBodySizeFeature>();
        if (limit is { IsReadOnly: false }) limit.MaxRequestBodySize = 256 * 1024;
    }
    await next(context);
});

var actions = new Dictionary<string, Action>
{
    ["explorer"] = () => Process.Start("explorer.exe"),
    ["terminal"] = () => Process.Start("powershell.exe", "-NoLogo -NoProfile"),
    ["volume-up"] = () => Win32.PressKey(0xAF),
    ["volume-down"] = () => Win32.PressKey(0xAE),
    ["mute"] = () => Win32.PressKey(0xAD),
    ["media"] = () => Win32.PressKey(0xB3),
    ["previous"] = () => Win32.PressKey(0xB1),
    ["next"] = () => Win32.PressKey(0xB0),
    ["spotify"] = () => Win32.Open("https://open.spotify.com/"),
    ["youtube"] = () => Win32.Open("https://www.youtube.com/"),
    ["linkedin"] = () => Win32.Open("https://www.linkedin.com/"),
    ["instagram"] = () => Win32.Open("https://www.instagram.com/"),
    ["gmail"] = () => Win32.Open("https://mail.google.com/"),
    ["calendar"] = () => Win32.Open("https://calendar.google.com/"),
    ["codex"] = shortcuts.Codex,
    ["cursor"] = shortcuts.Cursor,
    ["miro"] = shortcuts.Miro,
    ["serpro"] = () =>
    {
        if (!Uri.TryCreate(builder.Configuration["deck-serpro-url"], UriKind.Absolute, out var url)
            || url.Scheme != Uri.UriSchemeHttps)
            throw new InvalidOperationException("Configure deck-serpro-url no agente.");
        Win32.Open(url.AbsoluteUri);
    },
    ["steam"] = () => Win32.Open("steam://open/main"),
    ["workspace"] = () => Win32.PressWindowsKey(0x44),
    ["browser"] = () => Win32.Open("https://www.google.com/"),
    ["calculator"] = () => Win32.Open("ms-calculator:"),
    ["notepad"] = () => Win32.Open(Path.Combine(Environment.SystemDirectory, "notepad.exe")),
    ["explorer"] = () => Win32.Open(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "explorer.exe")),
    ["windows-settings"] = () => Win32.Open("ms-settings:"),
    ["screenshot"] = () => Win32.Open("ms-screenclip:"),
    ["meeting"] = () => Win32.Open("https://teams.microsoft.com/"),
    ["hotkey-save"] = () => Win32.PressChord(0x11, 0x53),
    ["hotkey-copy"] = () => Win32.PressChord(0x11, 0x43),
    ["hotkey-paste"] = () => Win32.PressChord(0x11, 0x56),
    ["lock"] = () => { if (!Win32.LockWorkStation()) throw new InvalidOperationException(); }
};
var studio = new DeckStudio(studioDirectory, actions, builder.Configuration);
app.MapGet("/editor", (HttpContext context) =>
{
    context.Response.Headers.CacheControl = "no-store";
    return Results.File(Path.Combine(AppContext.BaseDirectory, "Editor.html"), "text/html; charset=utf-8");
});
app.MapGet("/studio/config", () => studio.Configuration);
app.MapGet("/studio/catalog", () => studio.Catalog);
app.MapPut("/studio/config", (DeckSettings config) =>
    studio.Save(config) is { } error ? Results.BadRequest(new { error }) : Results.NoContent());
app.MapGet("/v1/deck", () => studio.Snapshot());
app.MapPost("/v1/profile", (string? id, bool? auto) =>
    studio.Select(id, auto) ? Results.NoContent() : Results.NotFound());
app.MapPost("/v1/macro/{id}", (string id, bool? confirm) => Results.StatusCode(studio.StartMacro(id, confirm == true)));
var media = new MediaSession();
app.MapGet("/v1/media", async () => Results.Ok(await media.Read()));
app.MapPost("/v1/studio/open", () =>
{
    Win32.Open("http://127.0.0.1:48627/editor#" + File.ReadAllText(editorKeyFile).Trim());
    return Results.NoContent();
});
app.MapPost("/v1/media/{command}", async (string command, double? fraction) =>
    await media.Change(command, fraction) ? Results.NoContent() : Results.StatusCode(409));
app.MapGet("/v1/media/cover", async () =>
    await media.Cover() is { } image ? Results.File(image, "application/octet-stream") : Results.NotFound());
app.MapPost("/v1/action/microphone", () =>
{
    var muted = Microphones.Toggle();
    return muted.HasValue ? Results.NoContent() : Results.Problem(statusCode: 503);
});
app.MapPost("/v1/action/{id}", (string id) =>
{
    if (!actions.TryGetValue(id, out var action)) return Results.NotFound();
    action();
    return Results.NoContent();
});
using var gpu = new GpuMetrics();
var metrics = new WindowsMetrics(gpu, app.Services.GetRequiredService<GpuTemperature>());
app.MapGet("/v1/ping", () => Results.Ok(new { connected = true }));
app.MapGet("/v1/status", () => metrics.Sample());
var google = new GoogleRead(builder.Configuration);
app.MapGet("/google/return", (Func<HttpContext, Task<IResult>>)(context =>
{
    context.Response.Headers.CacheControl = "no-store";
    context.Response.Headers["Referrer-Policy"] = "no-referrer";
    return google.Complete(context);
}));
var assistant = new DeckAssistant(builder.Configuration, metrics, actions, studio, google);
app.MapGet("/v1/assistant", () => assistant.Status());
using var localEngine = new LocalVoiceEngine(builder.Configuration, app.Services.GetRequiredService<IHttpClientFactory>());
var operation = new AssistedExecutionEngine(new WindowsActionExecutor(builder.Configuration, shortcuts, actions),
    assistant, builder.Configuration, Path.Combine(studioDirectory, "assisted-history.json"));
app.MapGet("/v1/operator", () => new { job = operation.Latest() });
app.MapGet("/v1/operator/history", () => operation.History());
app.MapPost("/v1/operator/{id}/approve", (string id) => operation.Approve(id) ? Results.NoContent() : Results.Conflict());
app.MapPost("/v1/operator/{id}/cancel", (string id) => operation.Cancel(id) ? Results.NoContent() : Results.Conflict());
var realtime = new LocalVoiceSession(localEngine, assistant, media, operation);
app.MapGet("/v1/assistant/realtime", realtime.Run);
app.MapPost("/v1/assistant/windows-mic", async (CancellationToken token) =>
    Results.Ok(new { text = await WindowsVoiceInput.Test(token) }));
app.MapPost("/v1/assistant", async (VoiceRequest input, CancellationToken cancellation) =>
    Results.Ok(await assistant.Answer(input, cancellation)));
app.MapGet("/v1/startup", () => new { enabled = DeckAutostart.Enabled() });
app.MapPost("/v1/startup", (bool enabled) =>
{
    try { DeckAutostart.Set(enabled, serial, builder.Configuration["deck-adb"]); return Results.NoContent(); }
    catch (Exception error) when (error is InvalidOperationException or UnauthorizedAccessException or IOException)
    { app.Logger.LogWarning(error, "Falha ao configurar inicialização do agente."); return Results.Problem(statusCode: 409); }
});
app.MapGet("/v1/verse", async (string? edition, IHttpClientFactory clients, CancellationToken cancellation) =>
{
    try
    {
        var verse = await DailyVerse.ReadAsync(clients.CreateClient("biblia"), edition, cancellation);
        return verse is null ? Results.Problem(statusCode: 502) : Results.Ok(verse);
    }
    catch (Exception error) when (error is HttpRequestException or TaskCanceledException)
    {
        return Results.Problem(statusCode: 502);
    }
});
app.MapGet("/v1/verse-art", async (IHttpClientFactory clients, CancellationToken cancellation) =>
{
    try
    {
        using var image = await clients.CreateClient("biblia").GetAsync(
            "https://biblia.com/api/plugins/verseoftheday/esv/fullsize.jpg", cancellation);
        if (!image.IsSuccessStatusCode || image.Content.Headers.ContentType?.MediaType != "image/jpeg")
            return Results.Problem(statusCode: 502);
        return Results.File(await image.Content.ReadAsByteArrayAsync(cancellation), "image/jpeg");
    }
    catch (Exception error) when (error is HttpRequestException or TaskCanceledException)
    {
        return Results.Problem(statusCode: 502);
    }
});
app.Run("http://127.0.0.1:48627");

internal sealed record UsbDeckOptions(string Serial, string Adb);

internal sealed class UsbDeckSession(UsbDeckOptions options) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var opened = false;
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(3));
        do
        {
            var tunnels = await Adb(stoppingToken, "reverse", "--list");
            if (tunnels is null)
            {
                opened = false;
                continue;
            }
            if (!tunnels.Split('\n').Any(line => line.Trim().EndsWith(
                "tcp:48627 tcp:48627", StringComparison.Ordinal)))
            {
                opened = false;
                if (await Adb(stoppingToken, "reverse", "tcp:48627", "tcp:48627") is null) continue;
            }
            if (!opened)
            {
                await Adb(stoppingToken, "shell", "input", "keyevent", "KEYCODE_WAKEUP");
                var launch = await Adb(stoppingToken, "shell", "am", "start", "-n",
                    "br.com.rian.deck/.DeckActivity");
                opened = launch?.Contains("Starting:", StringComparison.Ordinal) == true;
            }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }

    private async Task<string?> Adb(CancellationToken stoppingToken, params string[] arguments)
    {
        var info = new ProcessStartInfo(options.Adb)
        {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true
        };
        info.ArgumentList.Add("-s");
        info.ArgumentList.Add(options.Serial);
        foreach (var argument in arguments) info.ArgumentList.Add(argument);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(stoppingToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(8));
        try
        {
            using var process = Process.Start(info);
            if (process is null) return null;
            var output = process.StandardOutput.ReadToEndAsync(timeout.Token);
            var errors = process.StandardError.ReadToEndAsync(timeout.Token);
            try
            {
                await process.WaitForExitAsync(timeout.Token);
                await errors;
                return process.ExitCode == 0 ? await output : null;
            }
            catch (OperationCanceledException)
            {
                if (!process.HasExited) process.Kill();
                return null;
            }
        }
        catch (System.ComponentModel.Win32Exception)
        {
            return null;
        }
    }
}

internal sealed class WindowsMetrics(GpuMetrics gpuMetrics, GpuTemperature gpuTemperature)
{
    private ulong previousIdle, previousTotal;
    private readonly NetworkMetrics networkMetrics = new();


    public object Sample()
    {
        lock (this)
        {
            double? cpu = null;
            if (Win32.GetSystemTimes(out var idle, out var kernel, out var user))
            {
                var total = kernel + user;
                if (previousTotal > 0 && total > previousTotal)
                    cpu = Math.Clamp(100.0 * (1.0 - (double)(idle - previousIdle) / (total - previousTotal)), 0, 100);
                previousIdle = idle;
                previousTotal = total;
            }

            var memory = new Win32.MemoryInfo { Length = (uint)Marshal.SizeOf<Win32.MemoryInfo>() };
            var memoryOk = Win32.GlobalMemoryStatusEx(ref memory);
            var drive = new DriveInfo(Path.GetPathRoot(Environment.SystemDirectory)!);
            return new
            {
                cpu,
                ram = memoryOk ? (double?)memory.Load : null,
                gpu = gpuMetrics.Sample(),
                network = networkMetrics.Sample(),
                processes = Win32.ProcessCount(),
                temperature = gpuTemperature.Reading,
                storage = 100.0 * (drive.TotalSize - drive.TotalFreeSpace) / drive.TotalSize,
                microphone = Microphones.State()
            };
        }
    }
}

internal static class Win32
{
    internal static void Open(string destination) =>
        Process.Start(new ProcessStartInfo(destination) { UseShellExecute = true });

    [StructLayout(LayoutKind.Sequential)]
    private struct PerformanceInformation
    {
        internal uint Size;
        internal UIntPtr CommitTotal, CommitLimit, CommitPeak;
        internal UIntPtr PhysicalTotal, PhysicalAvailable, SystemCache;
        internal UIntPtr KernelTotal, KernelPaged, KernelNonpaged, PageSize;
        internal uint HandleCount, ProcessCount, ThreadCount;
    }

    [DllImport("psapi.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetPerformanceInfo(out PerformanceInformation information, uint size);

    internal static uint? ProcessCount() =>
        GetPerformanceInfo(out var information, (uint)Marshal.SizeOf<PerformanceInformation>())
            ? information.ProcessCount : null;

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool LockWorkStation();

    [DllImport("user32.dll")]
    private static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);

    internal static void PressKey(byte key)
    {
        keybd_event(key, 0, 0, UIntPtr.Zero);
        keybd_event(key, 0, 2, UIntPtr.Zero);
    }

    internal static void PressWindowsKey(byte key)
    {
        const byte leftWindows = 0x5B;
        keybd_event(leftWindows, 0, 1, UIntPtr.Zero);
        try { PressKey(key); }
        finally { keybd_event(leftWindows, 0, 3, UIntPtr.Zero); }
    }

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool GetSystemTimes(out ulong idle, out ulong kernel, out ulong user);

    internal static void PressChord(byte modifier, byte key)
    {
        keybd_event(modifier, 0, 0, UIntPtr.Zero);
        try { PressKey(key); }
        finally { keybd_event(modifier, 0, 2, UIntPtr.Zero); }
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct MemoryInfo
    {
        public uint Length, Load;
        public ulong TotalPhysical, AvailablePhysical, TotalPageFile, AvailablePageFile,
            TotalVirtual, AvailableVirtual, AvailableExtendedVirtual;
    }

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool GlobalMemoryStatusEx(ref MemoryInfo info);
}
