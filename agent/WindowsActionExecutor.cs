using System.Diagnostics;
using System.Runtime.InteropServices;

internal sealed class WindowsActionExecutor(IConfiguration config, DesktopShortcuts shortcuts,
    IReadOnlyDictionary<string, Action> actions)
{
    internal string Project()
    {
        string? path = config["deck-codex-project"];
        if (path is null || !Path.IsPathFullyQualified(path) || !Directory.Exists(path))
            throw new InvalidOperationException("Configure a pasta autorizada em deck-codex-project.");
        return Path.GetFullPath(path);
    }

    internal async Task<string> Cursor(bool project, CancellationToken token)
    {
        string? path = project ? Project() : null;
        token.ThrowIfCancellationRequested();
        if (path is null) shortcuts.Cursor();
        else shortcuts.CursorProject(path);
        string? title = path is null ? null : Path.GetFileName(Path.TrimEndingDirectorySeparator(path));
        await WaitFor(() => Window(["Cursor"], title), "Não foi possível verificar a janela do Cursor.", token);
        return title == null ? "Janela do Cursor verificada." : $"Janela do Cursor com o nome {title} verificada.";
    }

    internal async Task<string> Browser(CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        actions["browser"]();
        await WaitFor(() => Window(["msedge", "chrome", "firefox", "brave", "opera", "vivaldi"], null),
            "O Windows recebeu o atalho, mas o navegador não exibiu uma janela verificável.", token);
        return "Janela do navegador verificada.";
    }

    internal async Task<string> Desktop(CancellationToken token)
    {
        if (IsDesktop()) return "Área de trabalho já visível.";
        token.ThrowIfCancellationRequested();
        actions["workspace"]();
        await WaitFor(IsDesktop, "A área de trabalho não ficou em primeiro plano.", token);
        return "Área de trabalho em primeiro plano.";
    }

    private static bool IsDesktop()
    {
        var foreground = GetForegroundWindow();
        var name = new System.Text.StringBuilder(128);
        GetClassName(foreground, name, name.Capacity);
        return name.ToString() is "Progman" or "WorkerW" or "Shell_TrayWnd";
    }

    private static bool Window(string[] names, string? title)
    {
        foreach (string name in names)
        {
            var processes = Process.GetProcessesByName(name);
            try
            {
                if (processes.Any(p => p.MainWindowHandle != IntPtr.Zero &&
                    (title is null || p.MainWindowTitle.Contains(title, StringComparison.OrdinalIgnoreCase))))
                    return true;
            }
            catch (InvalidOperationException) { }
            finally { foreach (var process in processes) process.Dispose(); }
        }
        return false;
    }

    private static async Task WaitFor(Func<bool> condition, string failure, CancellationToken token)
    {
        for (int i = 0; i < 60; i++)
        {
            token.ThrowIfCancellationRequested();
            if (condition()) return;
            await Task.Delay(500, token);
        }
        throw new InvalidOperationException(failure);
    }

    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(
        IntPtr handle, System.Text.StringBuilder name, int capacity);
}
