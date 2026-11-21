using System.Collections.Concurrent;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.RegularExpressions;
using Windows.Security.Cryptography;
using Windows.Security.Cryptography.DataProtection;

internal sealed class GoogleRead(IConfiguration config)
{
    private const string Callback = "http://127.0.0.1:48627/google/return";
    private static readonly string[] Scopes = ["gmail.readonly", "calendar.events.readonly",
        "calendar.calendarlist.readonly", "tasks.readonly"];
    private static readonly HttpClient Http = new(new HttpClientHandler { AllowAutoRedirect = false })
        { Timeout = TimeSpan.FromSeconds(8) };
    private static readonly string Scope = string.Join(' ', Scopes.Select(s => "https://www.googleapis.com/auth/" + s));
    private readonly ConcurrentDictionary<string, Enrollment> pending = new();
    private sealed record Enrollment(string Alias, string Email, string Verifier, DateTimeOffset Expires);
    private static string DirectoryPath => Path.Combine(AppContext.BaseDirectory, "google-local");
    private static string CredentialPath => Path.Combine(DirectoryPath, "client.json");
    private static string TokenPath(string alias) => Path.Combine(DirectoryPath, alias + ".dpapi");

    internal static bool Matches(string text) => Regex.IsMatch(VoiceIntent.Clean(text),
        @"^(?:conect(?:ar|a|e) (?:a )?conta (?:pessoal|trabalho)|(?:leia|ler|veja|ver|liste|listar|quais|qual|mostre|fale|resuma|resumir|o que|como esta)\b.*\b(?:e ?mails?|gmail|agenda[s]?|compromissos?|tarefas?|atividades)\b)");

    internal async Task<string> Answer(string text, CancellationToken cancellation)
    {
        var phrase = VoiceIntent.Clean(text);
        var alias = Regex.IsMatch(phrase, @"\btrabalho\b") ? "trabalho" :
            Regex.IsMatch(phrase, @"\bpessoal\b") ? "pessoal" : null;
        if (!File.Exists(CredentialPath)) return "O cliente OAuth do Google ainda precisa ser instalado no agente.";
        try
        {
            if (phrase.StartsWith("conect", StringComparison.Ordinal) && alias != null)
            {
                Start(alias);
                return "Autorize a leitura na janela do Google que abri no Windows.";
            }
            string kind = Regex.IsMatch(phrase, @"\b(tarefas?|atividades)\b") ? "tasks" :
                Regex.IsMatch(phrase, @"\b(agendas?|compromissos?)\b") ? "calendar" : "gmail";
            var aliases = alias == null ? new[] { "pessoal", "trabalho" } : [alias];
            var answers = await Task.WhenAll(aliases.Select(Read));
            return string.Join(" ", answers);

            async Task<string> Read(string account)
            {
                if (!File.Exists(TokenPath(account))) return $"Conta {account} ainda sem autorização.";
                try
                {
                    var blob = CryptographicBuffer.CreateFromByteArray(await File.ReadAllBytesAsync(TokenPath(account), cancellation));
                    var clear = await new DataProtectionProvider().UnprotectAsync(blob);
                    var refresh = CryptographicBuffer.ConvertBinaryToString(BinaryStringEncoding.Utf8, clear);
                    var access = await Exchange(new() { ["grant_type"] = "refresh_token", ["refresh_token"] = refresh }, cancellation);
                    var token = access.GetProperty("access_token").GetString()!;
                    var data = kind switch
                    {
                        "calendar" => await Calendar(token, phrase, cancellation),
                        "tasks" => await Tasks(token, cancellation),
                        _ => await Mail(token, cancellation)
                    };
                    return $"Conta {account}. {data}";
                }
                catch (HttpRequestException error) when (error.StatusCode is System.Net.HttpStatusCode.BadRequest
                    or System.Net.HttpStatusCode.Unauthorized or System.Net.HttpStatusCode.Forbidden)
                { return $"O Google recusou a leitura da conta {account}. Confira a autorização no Google."; }
            }
        }
        catch (Exception error) when (error is IOException or JsonException or HttpRequestException
            or InvalidOperationException or System.ComponentModel.Win32Exception or System.Runtime.InteropServices.COMException)
        { return "Não consegui acessar o Google. Confira a conexão e a configuração local da conta."; }
        catch (OperationCanceledException) when (!cancellation.IsCancellationRequested)
        { return "O Google demorou para responder. Tente de novo."; }
    }

    private void Start(string alias)
    {
        string email = config[$"deck-google-accounts:{alias}"] ?? throw new InvalidOperationException();
        var (id, _) = Credentials();
        var now = DateTimeOffset.UtcNow;
        foreach (var entry in pending)
            if (entry.Value.Expires <= now || entry.Value.Alias == alias) pending.TryRemove(entry.Key, out _);
        string state = RandomId();
        string verifier = RandomId();
        pending[state] = new(alias, email, verifier, now.AddMinutes(5));
        var parameters = new Dictionary<string, string>
        {
            ["client_id"] = id, ["redirect_uri"] = Callback, ["response_type"] = "code",
            ["scope"] = Scope, ["access_type"] = "offline", ["prompt"] = "consent",
            ["state"] = state, ["login_hint"] = email,
            ["code_challenge"] = Base64Url(SHA256.HashData(System.Text.Encoding.ASCII.GetBytes(verifier))),
            ["code_challenge_method"] = "S256"
        };
        Win32.Open("https://accounts.google.com/o/oauth2/v2/auth?" +
            string.Join('&', parameters.Select(p => p.Key + "=" + Uri.EscapeDataString(p.Value))));
    }

    internal async Task<IResult> Complete(HttpContext context)
    {
        var query = context.Request.Query;
        if (!pending.TryRemove(query["state"].ToString(), out var enrollment) || enrollment.Expires <= DateTimeOffset.UtcNow)
            return Results.Text("Sessão inválida ou expirada. Inicie a conexão novamente.", statusCode: 400);
        if (!query.TryGetValue("code", out var code)) return Results.Text("Autorização cancelada.", statusCode: 400);
        try
        {
            var result = await Exchange(new() { ["grant_type"] = "authorization_code", ["code"] = code.ToString(),
                ["redirect_uri"] = Callback, ["code_verifier"] = enrollment.Verifier }, context.RequestAborted);
            var scopes = Field(result, "scope").Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (!scopes.ToHashSet(StringComparer.Ordinal).SetEquals(Scope.Split(' ')))
                return Results.Text("Autorize exatamente as permissões de leitura solicitadas.", statusCode: 403);
            var profile = await Get("https://gmail.googleapis.com/gmail/v1/users/me/profile",
                Field(result, "access_token"), context.RequestAborted);
            if (!string.Equals(Field(profile, "emailAddress"), enrollment.Email, StringComparison.OrdinalIgnoreCase))
                return Results.Text("A conta selecionada no Google é diferente da conta solicitada.", statusCode: 403);
            var refresh = Field(result, "refresh_token");
            if (refresh.Length == 0) return Results.Text("O Google não retornou autorização persistente.", statusCode: 400);
            var cipher = await new DataProtectionProvider("LOCAL=user").ProtectAsync(
                CryptographicBuffer.ConvertStringToBinary(refresh, BinaryStringEncoding.Utf8));
            CryptographicBuffer.CopyToByteArray(cipher, out var bytes);
            string file = TokenPath(enrollment.Alias);
            string temp = file + "." + RandomId() + ".tmp";
            try
            {
                await File.WriteAllBytesAsync(temp, bytes, context.RequestAborted);
                File.Move(temp, file, overwrite: true);
            }
            finally { File.Delete(temp); }
            return Results.Text("Conta conectada em modo somente leitura. Pode fechar esta aba.");
        }
        catch (Exception error) when (error is HttpRequestException or JsonException or IOException
            or InvalidOperationException or OperationCanceledException or System.Runtime.InteropServices.COMException)
        { return Results.Text("Falha na autorização. Verifique a conta e tente novamente.", statusCode: 502); }
    }

    private static string RandomId() => Base64Url(RandomNumberGenerator.GetBytes(32));
    private static string Base64Url(byte[] bytes) => Convert.ToBase64String(bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    private static string Field(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString()! : "";
    private static IEnumerable<JsonElement> Items(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.Array ? value.EnumerateArray().ToArray() : [];
    private static string Short(string text, int max = 130) =>
        string.Concat(Regex.Replace(text, @"[\s\p{C}]+", " ").Trim().Take(max));

    private static (string Id, string Secret) Credentials()
    {
        using var doc = JsonDocument.Parse(File.ReadAllBytes(CredentialPath));
        if (!doc.RootElement.TryGetProperty("installed", out var installed)) throw new InvalidOperationException();
        var id = Field(installed, "client_id");
        var secret = Field(installed, "client_secret");
        if (!id.EndsWith(".apps.googleusercontent.com", StringComparison.Ordinal) || secret.Length == 0)
            throw new InvalidOperationException();
        return (id, secret);
    }

    private static async Task<JsonElement> Exchange(Dictionary<string, string> form, CancellationToken cancellation)
    {
        var (id, secret) = Credentials();
        form["client_id"] = id;
        form["client_secret"] = secret;
        using var content = new FormUrlEncodedContent(form);
        using var response = await Http.PostAsync("https://oauth2.googleapis.com/token", content, cancellation);
        return await Json(response, cancellation);
    }

    private static async Task<JsonElement> Get(string url, string token, CancellationToken cancellation)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        using var response = await Http.SendAsync(request, cancellation);
        return await Json(response, cancellation);
    }

    private static async Task<JsonElement> Json(HttpResponseMessage response, CancellationToken cancellation)
    {
        response.EnsureSuccessStatusCode();
        using var doc = JsonDocument.Parse(await response.Content.ReadAsByteArrayAsync(cancellation));
        return doc.RootElement.Clone();
    }

    private static async Task<string> Mail(string token, CancellationToken cancellation)
    {
        const string url = "https://gmail.googleapis.com/gmail/v1/users/me/messages";
        var list = await Get(url + "?labelIds=INBOX&maxResults=3", token, cancellation);
        var messages = await Task.WhenAll(Items(list, "messages").Take(3).Select(item =>
            Get(url + "/" + Uri.EscapeDataString(Field(item, "id")) +
            "?format=full&fields=snippet,payload(headers)", token, cancellation)));
        return messages.Length == 0 ? "A caixa de entrada está vazia." :
            string.Join(" ", messages.Select(message =>
            {
                var subject = Items(message.GetProperty("payload"), "headers")
                    .FirstOrDefault(h => Field(h, "name").Equals("Subject", StringComparison.OrdinalIgnoreCase));
                return (subject.ValueKind == JsonValueKind.Object ? Short(Field(subject, "value")) : "Sem assunto") +
                    ". " + Short(System.Net.WebUtility.HtmlDecode(Field(message, "snippet")), 150) + ".";
            }));
    }

    private static async Task<string> Calendar(string token, string phrase, CancellationToken cancellation)
    {
        var day = DateTime.Today.AddDays(Regex.IsMatch(phrase, @"\bamanha\b") ? 1 : 0);
        string from = Uri.EscapeDataString(new DateTimeOffset(day).ToString("O"));
        string to = Uri.EscapeDataString(new DateTimeOffset(day.AddDays(Regex.IsMatch(phrase, @"\bsemana\b") ? 7 : 1)).ToString("O"));
        const string root = "https://www.googleapis.com/calendar/v3/";
        var lists = await Get(root + "users/me/calendarList?maxResults=10&minAccessRole=reader", token, cancellation);
        var groups = await Task.WhenAll(Items(lists, "items").Take(10).Select(calendar =>
            Get(root + "calendars/" + Uri.EscapeDataString(Field(calendar, "id")) +
                $"/events?singleEvents=true&orderBy=startTime&maxResults=5&timeMin={from}&timeMax={to}", token, cancellation)));
        var events = groups.SelectMany(group => Items(group, "items"))
            .Where(item => Field(item, "status") != "cancelled" && item.TryGetProperty("start", out _))
            .Select(item =>
            {
                var start = item.GetProperty("start");
                bool timed = DateTimeOffset.TryParse(Field(start, "dateTime"), out var time);
                if (!timed) DateTimeOffset.TryParse(Field(start, "date"), out time);
                return (time, timed, title: Short(Field(item, "summary")));
            }).Distinct().OrderBy(item => item.time).Take(5).ToArray();
        return events.Length == 0 ? "Nenhum compromisso encontrado no período." :
            string.Join(" ", events.Select(item => $"Dia {item.time.ToLocalTime():dd/MM}" +
                (item.timed ? $", às {item.time.ToLocalTime():HH:mm}" : ", dia inteiro") + $": {item.title}."));
    }

    private static async Task<string> Tasks(string token, CancellationToken cancellation)
    {
        const string root = "https://tasks.googleapis.com/tasks/v1/";
        var lists = await Get(root + "users/@me/lists?maxResults=10", token, cancellation);
        var groups = await Task.WhenAll(Items(lists, "items").Take(10).Select(list =>
            Get(root + "lists/" + Uri.EscapeDataString(Field(list, "id")) +
                "/tasks?showCompleted=false&maxResults=5", token, cancellation)));
        var tasks = groups.SelectMany(group => Items(group, "items")).Take(5)
            .Select(item => Short(Field(item, "title"))).ToArray();
        return tasks.Length == 0 ? "Nenhuma atividade pendente encontrada." : string.Join(". ", tasks) + ".";
    }
}
