using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Xml;
using System.Xml.Linq;

internal sealed record TrendArticle(string Id, string Title, string Source, string Category,
    DateTimeOffset Published, int Score, string Url, string? Image = null);
internal sealed record TrendMarket(string Id, string Name, string Source, string Currency,
    double Value, double? Change, DateTimeOffset Quoted, Dictionary<string,double[]> Series);
internal sealed record TrendEntry<T>(T? Data, DateTimeOffset? Updated, bool Stale, bool Failed);

internal sealed class TrendsService : IDisposable
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private static readonly (string Id, string Name, string Symbol, string Currency, int Minutes)[] Markets =
    [
        ("ai","IA Global · BOTZ","BOTZ","USD",15), ("ibov","Ibovespa","^BVSP","BRL",5),
        ("nasdaq","Nasdaq Composite","^IXIC","USD",5), ("btc","Bitcoin","bitcoin","USD",5)
    ];
    private readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(9), MaxResponseContentBufferSize = 1_000_000 };
    private readonly HttpClient images = new(new HttpClientHandler { AllowAutoRedirect=false })
        { Timeout=TimeSpan.FromSeconds(6), MaxResponseContentBufferSize=800_000 };
    private readonly ConcurrentDictionary<string,(byte[] Bytes,string Type)> thumbnails = new();
    private const int NewsMinutes=60;
    private readonly string root = Path.Combine(AppContext.BaseDirectory,"trends-cache");
    private readonly ConcurrentDictionary<string, DateTimeOffset> retry = new();
    private readonly ConcurrentDictionary<string, SemaphoreSlim> gates = new();
    private readonly ConcurrentDictionary<string, JsonElement> cache = new();
    private readonly string? coinKey;
    private double[]? bitcoinDay, bitcoinWeek, bitcoinMonth;
    private DateTimeOffset bitcoinDayDue, bitcoinMonthDue;

    internal TrendsService(IConfiguration config)
    {
        coinKey = config["deck-trends-coingecko-key"];
        http.DefaultRequestHeaders.UserAgent.ParseAdd("RianDeckOS/5.4");
    }

    internal async Task Stream(HttpContext context)
    {
        if (!context.WebSockets.IsWebSocketRequest) { context.Response.StatusCode=400; return; }
        using var socket = await context.WebSockets.AcceptWebSocketAsync();
        using var stop = CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted);
        var receiver = Watch(socket,stop);
        try
        {
            await Send(Snapshot(), socket, stop.Token);
            while (!stop.IsCancellationRequested)
            {
                var news = Refresh("articles-v2", TimeSpan.FromMinutes(NewsMinutes), News, stop.Token);
                var values = Markets.Select(m => Refresh(m.Id,TimeSpan.FromMinutes(m.Minutes),
                    token => m.Id=="btc" ? Bitcoin(token) : Quote(m,token),stop.Token)).ToArray();
                await Task.WhenAll(Task.WhenAll(values),news);
                await Send(Snapshot(), socket, stop.Token);
                await Task.Delay(TimeSpan.FromSeconds(60),stop.Token);
            }
        }
        catch (Exception error) when (error is OperationCanceledException or WebSocketException or IOException) { }
        finally { stop.Cancel(); await receiver; }
    }

    private static async Task Watch(WebSocket socket, CancellationTokenSource stop)
    {
        try
        {
            byte[] buffer = new byte[128];
            while (!stop.IsCancellationRequested)
                if ((await socket.ReceiveAsync(buffer, stop.Token)).MessageType == WebSocketMessageType.Close) break;
        }
        catch (Exception error) when (error is OperationCanceledException or WebSocketException or IOException) { }
        finally { stop.Cancel(); }
    }

    private static Task Send(object value, WebSocket socket, CancellationToken token) =>
        socket.SendAsync(JsonSerializer.SerializeToUtf8Bytes(value,Json),WebSocketMessageType.Text,true,token);

    private object Snapshot() => new
    {
        news = Read<TrendArticle[]>("articles-v2",NewsMinutes), markets = Markets.Select(m =>
            new { m.Id, m.Name, quote = Read<TrendMarket>(m.Id,m.Minutes) }).ToArray()
    };

    internal bool Open(string id)
    {
        var article = Read<TrendArticle[]>("articles-v2",NewsMinutes).Data?.FirstOrDefault(a=>a.Id==id);
        if (article == null || !SafeUrl(article.Url,out var url)) return false;
        Win32.Open(url.AbsoluteUri);
        return true;
    }

    internal async Task<IResult> Thumbnail(string id,CancellationToken token)
    {
        var articles=Read<TrendArticle[]>("articles-v2",NewsMinutes).Data??[];
        var article=articles.FirstOrDefault(a=>a.Id==id);
        if(!ImageUrl(article?.Image,out var uri)) return Results.NotFound();
        foreach(var key in thumbnails.Keys)
            if(!articles.Any(a=>a.Id==key)) thumbnails.TryRemove(key,out _);
        if(thumbnails.TryGetValue(id,out var cached)) return Results.File(cached.Bytes,cached.Type);
        try
        {
            using var request=new HttpRequestMessage(HttpMethod.Get,uri);
            request.Headers.UserAgent.ParseAdd("RianDeckOS/5.4");
            using var reply=await images.SendAsync(request,token);
            string? type=reply.Content.Headers.ContentType?.MediaType;
            if(!reply.IsSuccessStatusCode || type is not ("image/jpeg" or "image/png" or "image/webp"))
                return Results.NotFound();
            var bytes=await reply.Content.ReadAsByteArrayAsync(token);
            if(bytes.Length==0) return Results.NotFound();
            thumbnails[id]=(bytes,type);
            return Results.File(bytes,type);
        }
        catch(Exception error) when(error is HttpRequestException or OperationCanceledException)
        { return Results.StatusCode(503); }
    }

    private static bool ImageUrl(string? value,out Uri url) =>
        SafeUrl(value,out url) && url.IsDefaultPort && url.IdnHost is "platform.theverge.com" or "cdn.arstechnica.net";

    private static bool SafeUrl(string? value,out Uri url)
    {
        if (Uri.TryCreate(value,UriKind.Absolute,out var candidate) && candidate.Scheme=="https" &&
            !candidate.IsLoopback && candidate.HostNameType==UriHostNameType.Dns && candidate.UserInfo.Length==0)
        { url = candidate; return true; }
        url = null!;
        return false;
    }

    private sealed record Stored<T>(T Data, DateTimeOffset Updated);

    private TrendEntry<T> Read<T>(string id,int minutes)
    {
        try
        {
            if (!cache.TryGetValue(id,out var json))
            {
                string file = Path.Combine(root,id+".json");
                if (File.Exists(file))
                {
                    using var document = JsonDocument.Parse(File.ReadAllBytes(file));
                    json = cache.GetOrAdd(id,document.RootElement.Clone());
                }
            }
            bool failure = retry.ContainsKey(id);
            if (json.ValueKind == JsonValueKind.Undefined) return new(default,null,false,failure);
            var data = json.Deserialize<Stored<T>>(Json)!;
            return new(data.Data,data.Updated,DateTimeOffset.UtcNow-data.Updated>TimeSpan.FromMinutes(minutes+1),failure);
        }
        catch (Exception error) when (error is IOException or JsonException or UnauthorizedAccessException)
        { return new(default,null,false,true); }
    }

    private async Task Refresh<T>(string id,TimeSpan ttl,Func<CancellationToken,Task<T>> load,CancellationToken token)
    {
        var gate = gates.GetOrAdd(id,_=>new SemaphoreSlim(1));
        if (!await gate.WaitAsync(0,token)) return;
        try
        {
            var now = DateTimeOffset.UtcNow;
            var current = Read<T>(id,(int)ttl.TotalMinutes);
            if ((current.Updated is { } last && now-last < ttl) ||
                (retry.TryGetValue(id,out var next) && next>now)) return;
            T data = await load(token);
            var fresh = JsonSerializer.SerializeToElement(new Stored<T>(data,DateTimeOffset.UtcNow),Json);
            cache[id] = fresh;
            retry.TryRemove(id,out _);
            Directory.CreateDirectory(root);
            string path = Path.Combine(root,id+".json");
            await File.WriteAllTextAsync(path+".tmp",fresh.GetRawText(),token);
            File.Move(path+".tmp",path,true);
        }
        catch (OperationCanceledException) when (token.IsCancellationRequested) { }
        catch (Exception error) when (error is HttpRequestException or IOException or JsonException
            or OperationCanceledException or InvalidOperationException or KeyNotFoundException or UnauthorizedAccessException)
        { retry[id] = DateTimeOffset.UtcNow + (retry.ContainsKey(id) ? ttl*2 : ttl); }
        finally { gate.Release(); }
    }

    private async Task<JsonElement> Get(string url,CancellationToken token,bool coin=false)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get,url);
        if (coin && !string.IsNullOrWhiteSpace(coinKey)) request.Headers.Add("x-cg-demo-api-key",coinKey);
        using var response = await http.SendAsync(request,token);
        response.EnsureSuccessStatusCode();
        using var doc = JsonDocument.Parse(await response.Content.ReadAsByteArrayAsync(token));
        return doc.RootElement.Clone();
    }

    private async Task<TrendArticle[]> News(CancellationToken token)
    {
        const string api="https://hacker-news.firebaseio.com/v0/";
        var rss = Task.WhenAll(new[] {"technology","artificial intelligence","cybersecurity","computer hardware"}
            .Select(query=>NewsSignals(query,token)));
        var direct = Task.WhenAll(
            Publisher("https://feeds.arstechnica.com/arstechnica/technology-lab","Ars Technica",token),
            Publisher("https://www.theverge.com/rss/index.xml","The Verge",token));
        int[] ids;
        try { ids = (await Get(api+"topstories.json",token)).EnumerateArray().Take(24).Select(x=>x.GetInt32()).ToArray(); }
        catch (Exception error) when (error is HttpRequestException or JsonException) { ids=[]; }
        var results = await Task.WhenAll(ids.Select(async id =>
        {
            try
            {
                var item=await Get(api+$"item/{id}.json",token);
                if (!item.TryGetProperty("url",out var value) || !SafeUrl(value.GetString(),out var url)) return null;
                string title=item.GetProperty("title").GetString()!;
                var published=DateTimeOffset.FromUnixTimeSeconds(item.GetProperty("time").GetInt64());
                if (!Regex.IsMatch(title,@"\b(ai|llm|software|programming|hardware|computer|security|privacy|database|android|linux|windows|apple|google|microsoft|openai|nvidia|browser|gpu|web|monitor|network|github|cloud|robotics)\b",RegexOptions.IgnoreCase) ||
                    DateTimeOffset.UtcNow-published>TimeSpan.FromHours(48) ||
                    Regex.IsMatch(title,@"^(?:Ask HN|Launch HN|Show HN:.*(?:job|hiring))",RegexOptions.IgnoreCase)) return null;
                return new TrendArticle(id.ToString(),title.Length>180?title[..180]:title,
                    url.IdnHost.Replace("www.","",StringComparison.OrdinalIgnoreCase),Category(title),published,
                    item.GetProperty("score").GetInt32(),url.AbsoluteUri);
            }
            catch (Exception error) when (error is HttpRequestException or JsonException or InvalidOperationException or KeyNotFoundException)
            { return null; }
        }));
        var signals = (await rss).SelectMany(record=>record).Distinct().ToArray();
        var articles = results.OfType<TrendArticle>().Concat((await direct).SelectMany(list=>list))
            .DistinctBy(a=>new Uri(a.Url).GetLeftPart(UriPartial.Path))
            .DistinctBy(a=>Regex.Replace(a.Title.ToLowerInvariant(),@"\W",""))
            .OrderByDescending(a=>
            {
                var words=Terms(a.Title);
                int coverage=signals.Where(s=>s.Source!=a.Source && words.Count>=4 &&
                    Terms(s.Title).Intersect(words).Count()>=Math.Ceiling(words.Count*.7)).Select(s=>s.Source)
                    .Distinct().Take(5).Count();
                return Math.Log(1+Math.Min(a.Score,200))*.35+coverage*.35+(a.Image==null?0:2)
                    -Math.Max(0,(DateTimeOffset.UtcNow-a.Published).TotalHours)/14;
            })
            .Take(4).ToArray();
        if (articles.Length<4) throw new InvalidOperationException("Feed incompleto.");
        return articles;
    }

    private static string Category(string title) =>
        Regex.IsMatch(title,@"\b(ai|llm|artificial intelligence|openai)\b",RegexOptions.IgnoreCase) ? "IA"
        : Regex.IsMatch(title,@"\b(security|privacy|hack|malware|password)\b",RegexOptions.IgnoreCase) ? "Segurança"
        : Regex.IsMatch(title,@"\b(chip|hardware|gpu|cpu|robot|laptop)\b",RegexOptions.IgnoreCase) ? "Hardware"
        : "Tecnologia";

    private async Task<TrendArticle[]> Publisher(string feed,string source,CancellationToken token)
    {
        try
        {
            var xml=await ReadFeed(feed,token);
            var items=new List<TrendArticle>();
            foreach(var node in xml.Descendants().Where(x=>x.Name.LocalName is "item" or "entry").Take(12))
            {
                string? Field(string name)=>node.Elements().FirstOrDefault(x=>x.Name.LocalName==name)?.Value;
                var link=node.Elements().FirstOrDefault(x=>x.Name.LocalName=="link" &&
                    ((string?)x.Attribute("rel") is null or "alternate"));
                string? title=Field("title");
                if(title==null || !SafeUrl((string?)link?.Attribute("href")??link?.Value,out var url) ||
                    !DateTimeOffset.TryParse(Field("pubDate")??Field("published")??Field("updated"),
                        System.Globalization.CultureInfo.InvariantCulture,out var published) ||
                    DateTimeOffset.UtcNow-published>TimeSpan.FromHours(48)) continue;
                var hash=System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(url.AbsoluteUri));
                string? photo=node.Descendants().FirstOrDefault(x=>x.Name.LocalName=="thumbnail")?.Attribute("url")?.Value;
                if(photo==null)
                {
                    var content=Field("content")??Field("description")??"";
                    var image=Regex.Match(content,@"<img\b[^>]*?\bsrc\s*=\s*[""'](?<src>[^""']+)[""']",RegexOptions.IgnoreCase);
                    if(image.Success) photo=System.Net.WebUtility.HtmlDecode(image.Groups["src"].Value);
                }
                if(ImageUrl(photo,out var picture))
                    photo=picture.IdnHost=="platform.theverge.com"
                        ?new UriBuilder(picture){Query="w=256&quality=70"}.Uri.AbsoluteUri
                        :picture.AbsoluteUri;
                else photo=null;
                items.Add(new(BitConverter.ToUInt64(hash).ToString(),title.Length>180?title[..180]:title,
                    source,Category(title),published,0,url.AbsoluteUri,photo));
            }
            return items.ToArray();
        }
        catch(Exception error) when(error is HttpRequestException or XmlException or OperationCanceledException)
        { return []; }
    }

    private async Task<XDocument> ReadFeed(string url,CancellationToken token)
    {
        var content=await http.GetStringAsync(url,token);
        using var text=new StringReader(content);
        using var reader=XmlReader.Create(text,new XmlReaderSettings
            { DtdProcessing=DtdProcessing.Prohibit, MaxCharactersInDocument=1_000_000 });
        return XDocument.Load(reader);
    }

    private static HashSet<string> Terms(string title) => Regex.Matches(title.ToLowerInvariant(),@"[\p{L}\d]{3,}")
        .Select(match=>match.Value).ToHashSet(StringComparer.Ordinal);

    private async Task<(string Title,string Source)[]> NewsSignals(string query,CancellationToken token)
    {
        try
        {
            var xml=await ReadFeed("https://news.google.com/rss/search?q="+
                Uri.EscapeDataString(query+" when:2d")+"&hl=en-US&gl=US&ceid=US:en",token);
            return xml.Descendants("item").Take(12).Select(item=>
            {
                string title=item.Element("title")?.Value??"";
                string source=item.Element("source")?.Attribute("url")?.Value??"";
                return (title,SafeUrl(source,out var url)?url.IdnHost.Replace("www.","",StringComparison.OrdinalIgnoreCase):"");
            }).Where(item=>item.Item1.Length>0&&item.Item2.Length>0).ToArray();
        }
        catch(Exception error) when(error is HttpRequestException or XmlException or OperationCanceledException)
        { return []; }
    }

    private async Task<TrendMarket> Quote((string Id,string Name,string Symbol,string Currency,int Minutes) m,
        CancellationToken token)
    {
        string url="https://query1.finance.yahoo.com/v8/finance/chart/"+Uri.EscapeDataString(m.Symbol);
        var daily=Get(url+"?range=1mo&interval=1d",token);
        var intraday=Get(url+"?range=1d&interval=15m",token);
        try { await Task.WhenAll(daily,intraday); }
        catch (HttpRequestException) when (daily.IsCompletedSuccessfully) { }
        var result=(await daily).GetProperty("chart").GetProperty("result")[0];
        var meta=result.GetProperty("meta");
        var dailyValues=Closes(result);
        var stamps=result.GetProperty("timestamp").EnumerateArray();
        var dailyRaw=result.GetProperty("indicators").GetProperty("quote")[0].GetProperty("close").EnumerateArray();
        long cutoff=DateTimeOffset.FromUnixTimeSeconds(meta.GetProperty("regularMarketTime").GetInt64())
            .AddDays(-7).ToUnixTimeSeconds();
        var week=stamps.Zip(dailyRaw).Where(p=>p.First.GetInt64()>=cutoff && p.Second.ValueKind==JsonValueKind.Number)
            .Select(p=>p.Second.GetDouble()).ToArray();
        var series=new Dictionary<string,double[]> { ["30D"]=dailyValues, ["7D"]=week };
        double value=meta.GetProperty("regularMarketPrice").GetDouble();
        double? change=null;
        if (intraday.IsCompletedSuccessfully)
        {
            var recent=intraday.Result.GetProperty("chart").GetProperty("result")[0];
            var points=Closes(recent);
            if (points.Length>=2) series["1D"]=points;
            var day=recent.GetProperty("meta");
            if (day.TryGetProperty("chartPreviousClose",out var prior) && prior.TryGetDouble(out var previous) && previous>0)
                change=100*(value/previous-1);
        }
        return new(m.Id,m.Name,"Yahoo Finance",m.Id=="ibov"||m.Id=="nasdaq"?"PTS":m.Currency,
            value,change,DateTimeOffset.FromUnixTimeSeconds(meta.GetProperty("regularMarketTime").GetInt64()),series);
    }

    private static double[] Closes(JsonElement value) => value.GetProperty("indicators").GetProperty("quote")[0]
        .GetProperty("close").EnumerateArray().Where(x=>x.ValueKind==JsonValueKind.Number).Select(x=>x.GetDouble()).ToArray();

    private async Task<TrendMarket> Bitcoin(CancellationToken token)
    {
        const string root="https://api.coingecko.com/api/v3/";
        var price=Get(root+"simple/price?ids=bitcoin&vs_currencies=usd&include_24hr_change=true&include_last_updated_at=true",token,true);
        var now=DateTimeOffset.UtcNow;
        var chart=now>=bitcoinDayDue?Get(root+"coins/bitcoin/market_chart?vs_currency=usd&days=1",token,true)
            :Task.FromResult(default(JsonElement));
        var monthly=now>=bitcoinMonthDue?Get(root+"coins/bitcoin/market_chart?vs_currency=usd&days=30",token,true)
            :Task.FromResult(default(JsonElement));
        await Task.WhenAll(price,chart,monthly);
        var info=price.Result.GetProperty("bitcoin");
        if(chart.Result.ValueKind==JsonValueKind.Object)
        {
            bitcoinDay=Points(chart.Result.GetProperty("prices").EnumerateArray());
            bitcoinDayDue=now.AddHours(1);
        }
        if(monthly.Result.ValueKind==JsonValueKind.Object)
        {
            var days=monthly.Result.GetProperty("prices").EnumerateArray().ToArray();
            var recent=days.Where(x=>x[0].GetInt64()>=now.AddDays(-7).ToUnixTimeMilliseconds()).ToArray();
            bitcoinWeek=Points(recent);
            bitcoinMonth=Points(days);
            bitcoinMonthDue=now.AddHours(4);
        }
        return new("btc","Bitcoin","CoinGecko","USD",info.GetProperty("usd").GetDouble(),
            info.GetProperty("usd_24h_change").GetDouble(),
            DateTimeOffset.FromUnixTimeSeconds(info.GetProperty("last_updated_at").GetInt64()),
            new() { ["1D"]=bitcoinDay??[], ["7D"]=bitcoinWeek??[], ["30D"]=bitcoinMonth??[] });
    }

    private static double[] Points(IEnumerable<JsonElement> values)
    {
        var rows=values.ToArray();
        if (rows.Length<=64) return rows.Select(x=>x[1].GetDouble()).ToArray();
        return Enumerable.Range(0,64).Select(i=>rows[i*(rows.Length-1)/63][1].GetDouble()).ToArray();
    }

    public void Dispose() { http.Dispose(); images.Dispose(); }
}
