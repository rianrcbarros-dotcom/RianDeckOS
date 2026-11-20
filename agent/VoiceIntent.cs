using System.Text.RegularExpressions;

internal static class VoiceIntent
{
    private static readonly Dictionary<string, string> Apps = new()
    {
        ["navegador"] = "browser", ["browser"] = "browser", ["internet"] = "browser", ["google"] = "browser",
        ["youtube"] = "youtube", ["you tube"] = "youtube", ["iutube"] = "youtube",
        ["spotify"] = "spotify", ["spotifai"] = "spotify", ["cursor"] = "cursor",
        ["codex"] = "codex", ["codecs"] = "codex", ["code x"] = "codex", ["steam"] = "steam",
        ["serpro"] = "serpro", ["git da serpro"] = "serpro", ["git corporativo"] = "serpro",
        ["git corporativo da serpro"] = "serpro", ["calculadora"] = "calculator",
        ["bloco de notas"] = "notepad", ["notepad"] = "notepad", ["explorador de arquivos"] = "explorer",
        ["explorador"] = "explorer", ["arquivos"] = "explorer", ["configuracoes do windows"] = "windows-settings",
        ["configuracoes"] = "windows-settings"
    };

    internal static string Clean(string value)
    {
        var folded = string.Concat(value.ToLowerInvariant().Normalize(System.Text.NormalizationForm.FormD)
            .Where(c => System.Globalization.CharUnicodeInfo.GetUnicodeCategory(c)
                != System.Globalization.UnicodeCategory.NonSpacingMark));
        return Regex.Replace(folded, "[^a-z0-9]+", " ").Trim();
    }

    internal static string? App(string text)
    {
        string phrase = Clean(text);
        var match = Regex.Match(phrase,
            @"^(?:(?:oi|ei|ola) (?:rian )?|por favor )?(?:(?:voce )?(?:pode|poderia) |(?:eu )?quero que (?:voce )?)?(?:abre|abra|abrir|inicie|iniciar|a brir) (?:o |a |os |as |meu |minha )?(.+?)$");
        if (!match.Success) return null;
        string name = Regex.Replace(match.Groups[1].Value,
            @"(?: (?:pra mim|para mim|por favor|agora|ai|no meu (?:pc|computador|notebook)|no (?:pc|windows|computador|notebook)))+$", "");
        return Apps.GetValueOrDefault(name);
    }

    internal static bool LooksLikeOpening(string text) => Regex.IsMatch(Clean(text),
        @"^(?:(?:oi|ei|ola|rian|por favor|voce|pode|poderia|eu|quero|que) )*(?:abre|abra|abrir|inicie|iniciar) ");

    internal static string Spoken(string text)
    {
        foreach (var (word, sound) in new (string, string)[]
        {
            ("Rian", "Rrian"), ("Codex", "Códex"), ("Cursor", "Cârsor"), ("Steam", "Stím"),
            ("YouTube", "Iutúbi"), ("Spotify", "Spotifái"), ("CPU", "cê pê u"),
            ("GPU", "gê pê u"), ("RAM", "râm"), ("USB", "u ésse bê"), ("PC", "pê cê")
        })
            text = Regex.Replace(text, $@"\b{word}\b", sound, RegexOptions.IgnoreCase);
        return text.Replace("%", " por cento", StringComparison.Ordinal);
    }
}
