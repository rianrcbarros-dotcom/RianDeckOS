using System.Text.RegularExpressions;

internal static class VoiceIntent
{
    private static readonly Dictionary<string, string> Apps = new()
    {
        ["navegador"] = "browser", ["browser"] = "browser", ["internet"] = "browser", ["google"] = "browser",
        ["navega dor"] = "browser", ["navegador padrao"] = "browser", ["navegador de internet"] = "browser",
        ["youtube"] = "youtube", ["you tube"] = "youtube", ["iutube"] = "youtube",
        ["linkedin"] = "linkedin", ["linked in"] = "linkedin", ["linquedin"] = "linkedin",
        ["instagram"] = "instagram", ["insta"] = "instagram",
        ["gmail"] = "gmail", ["g mail"] = "gmail", ["e mail"] = "gmail", ["email"] = "gmail",
        ["agenda"] = "calendar", ["google agenda"] = "calendar", ["google calendar"] = "calendar",
        ["agenda do google"] = "calendar", ["agenda do gmail"] = "calendar",
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
            @"^(?:(?:oi|ei|ola) |por favor )?(?:(?:voce )?(?:pode|poderia) |(?:eu )?quero que (?:voce )?)?(?:abre|abra|abrir|inicie|iniciar|a brir) (?:o |a |os |as |meu |minha )?(.+?)$");
        if (!match.Success) return null;
        string name = Regex.Replace(match.Groups[1].Value,
            @"(?: (?:pra mim|para mim|por favor|agora|ai|no meu (?:pc|computador|notebook)|no (?:pc|windows|computador|notebook)))+$", "");
        return Apps.GetValueOrDefault(name);
    }

    private static readonly Regex OpenFolder = new(
        @"^(?:(?:voce )?(?:pode|poderia) )?(?:abre|abra|abrir) (?:(?:o|a|minha|meus) )?(?:pasta )?(?<folder>.+?)(?: (?:pra mim|para mim|por favor))?$");

    internal static string? Folder(string text)
    {
        var match = OpenFolder.Match(Clean(text));
        if (!match.Success) return null;
        string label = match.Groups["folder"].Value;
        if (label.StartsWith("de ") || label.StartsWith("dos ")) label = label[(label.IndexOf(' ') + 1)..];
        return label switch
        {
            "download" or "downloads" or "arquivos baixados" => "downloads",
            "documento" or "documentos" => "documentos",
            "imagens" or "fotos" => "imagens",
            "videos" => "videos",
            "musicas" => "musicas",
            "area de trabalho" or "desktop" => "desktop",
            _ => Clean(text).Contains(" pasta ") ? label : null
        };
    }

    private static readonly Regex WebSearch = new(
        @"^\s*(?:(?:voc[eê]\s+)?(?:pode|poderia)\s+)?(?:(?:abre|abra|abrir)\s+(?:o\s+)?(?:navegador|google)\s+e\s+)?(?:pesquisa|pesquise|pesquisar|procure|procurar|busca|busque|buscar)\s+(?:por\s+|sobre\s+)?(?<query>.+?)\s*[.!?]*\s*$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    internal static string? Search(string text)
    {
        var match = WebSearch.Match(text);
        if (!match.Success) return null;
        string query = Regex.Replace(match.Groups["query"].Value.Trim(),
            @"\s+(?:no google|na internet|no navegador|por favor|pra mim|para mim)$", "", RegexOptions.IgnoreCase);
        string normalized = Clean(query);
        if (query.Length is < 2 or > 180 ||
            Regex.IsMatch(normalized, @"\b(?:senha|credencial|token|chave privada|meus arquivos|na pasta|no meu computador)\b"))
            return null;
        return query;
    }

    internal static (string Service, string Account)? GoogleAccount(string text)
    {
        if (!LooksLikeOpening(text)) return null;
        var match = Regex.Match(text.Trim().TrimEnd('.', '!', '?'),
            @"\b(?<service>g\s?mail|agenda(?: do (?:google|gmail))?)\s+(?:(?:da|na|com a)\s+conta\s+|conta\s+)(?<account>.+?)(?:\s+(?:pra mim|para mim|por favor))?$",
            RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
        if (!match.Success) return null;
        return (match.Groups["service"].Value.StartsWith("agenda", StringComparison.OrdinalIgnoreCase)
            ? "calendar" : "gmail", match.Groups["account"].Value);
    }

    internal static bool IsDirect(string text) => App(text) != null || GoogleAccount(text) != null
        || Folder(text) != null || Search(text) != null;

    internal static bool LooksLikeOpening(string text) => Regex.IsMatch(Clean(text),
        @"^(?:(?:oi|ei|ola|por favor|voce|pode|poderia|eu|quero|que) )*(?:abre|abra|abrir|inicie|iniciar) ");

    internal static string Spoken(string text)
    {
        foreach (var (word, sound) in new (string, string)[]
        {
            ("Codex", "Códex"), ("Cursor", "Cârsor"), ("Steam", "Stím"),
            ("YouTube", "Iutúbi"), ("Spotify", "Spotifái"), ("CPU", "cê pê u"),
            ("GPU", "gê pê u"), ("RAM", "râm"), ("USB", "u ésse bê"), ("PC", "pê cê")
        })
            text = Regex.Replace(text, $@"\b{word}\b", sound, RegexOptions.IgnoreCase);
        return text.Replace("%", " por cento", StringComparison.Ordinal);
    }
}
