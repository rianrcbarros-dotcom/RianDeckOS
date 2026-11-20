using System.Diagnostics;
using Microsoft.Win32;

internal sealed class DesktopShortcuts(IConfiguration config)
{
    private static readonly string Local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
    private static readonly string Programs = Environment.GetFolderPath(Environment.SpecialFolder.Programs);

    internal void Codex()
    {
        if (OpenExisting(config["deck-codex"])) return;
        var explorer = new ProcessStartInfo("explorer.exe");
        explorer.ArgumentList.Add(@"shell:AppsFolder\OpenAI.Codex_2p2nqsd0c76g0!App");
        Process.Start(explorer);
    }

    internal void Cursor()
    {
        if (OpenExisting(config["deck-cursor"]) ||
            OpenExisting(RegisteredCursor()) ||
            OpenExisting(Path.Combine(Local, "Programs", "cursor", "Cursor.exe")) ||
            OpenExisting(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Cursor", "Cursor.exe")) ||
            OpenExisting(Path.Combine(Programs, "Cursor", "Cursor.lnk")) ||
            OpenExisting(Path.Combine(Programs, "Cursor.lnk"))) return;
        throw new FileNotFoundException("Instalação do Cursor indisponível.");
    }

    private static string? RegisteredCursor()
    {
        if (!OperatingSystem.IsWindows()) return null;
        using var products = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall");
        if (products is null) return null;
        foreach (var key in products.GetSubKeyNames())
        {
            using var product = products.OpenSubKey(key);
            if (product?.GetValue("DisplayName") is string name &&
                (name.Equals("Cursor (User)", StringComparison.OrdinalIgnoreCase) ||
                 name.Equals("Cursor", StringComparison.OrdinalIgnoreCase)) &&
                product.GetValue("InstallLocation") is string path)
                return Path.Combine(path, "Cursor.exe");
        }
        return null;
    }

    internal void Miro()
    {
        if (OpenExisting(config["deck-miro"]) ||
            OpenExisting(Path.Combine(Local, "Programs", "Miro", "Miro.exe")) ||
            OpenExisting(Path.Combine(Local, "Programs", "miro", "Miro.exe")) ||
            OpenExisting(Path.Combine(Programs, "Miro.lnk"))) return;
        Win32.Open("https://miro.com/app/dashboard/");
    }

    private static bool OpenExisting(string? path)
    {
        if (string.IsNullOrWhiteSpace(path) || !Path.IsPathFullyQualified(path) || !File.Exists(path))
            return false;
        Win32.Open(path);
        return true;
    }
}
