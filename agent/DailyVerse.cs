using System.Net;
using System.Text.RegularExpressions;

internal sealed record DailyVerse(string Reference, string Passage)
{
    private const string Url = "https://biblia.com/bible/bb-sbb-ra/verseoftheday";
    private static readonly Regex Citation = new("data-citation=\"([^\"]+)\"", RegexOptions.CultureInvariant);
    private static readonly Regex Text = new("data-passage-text=\"([^\"]+)\"", RegexOptions.CultureInvariant);

    internal static async Task<DailyVerse?> ReadAsync(HttpClient client, string? edition, CancellationToken cancellation)
    {
        var source = edition == "esv" ? "https://biblia.com/verseoftheday" : Url;
        using var response = await client.GetAsync(source, cancellation);
        response.EnsureSuccessStatusCode();
        var html = await response.Content.ReadAsStringAsync(cancellation);
        var citation = Citation.Match(html);
        var text = Text.Match(html);
        return citation.Success && text.Success
            ? new DailyVerse(WebUtility.HtmlDecode(citation.Groups[1].Value), WebUtility.HtmlDecode(text.Groups[1].Value))
            : null;
    }
}
