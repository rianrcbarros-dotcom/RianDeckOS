using System.Text.Json;

internal sealed record AssistedStep(string Id, string Description, string Tool, string[] Dependencies,
    bool RequiresApproval, int TimeoutSeconds, string Success, string State = "Pendente", string? Result = null);
internal sealed record AssistedJob(string Id, string Request, string State, DateTimeOffset Started,
    DateTimeOffset Updated, AssistedStep[] Steps, string? Result = null, bool Approved = false);

internal sealed class AssistedExecutionEngine(WindowsActionExecutor windows, DeckAssistant assistant,
    IConfiguration configuration, string historyPath)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { WriteIndented = true };
    private readonly object gate = new();
    private readonly List<AssistedJob> history = Read(historyPath);
    private CancellationTokenSource? running;
    private bool Enabled => configuration.GetValue("deck-assistant-actions", true);

    private static List<AssistedJob> Read(string path)
    {
        try
        {
            if (!File.Exists(path)) return [];
            return (JsonSerializer.Deserialize<List<AssistedJob>>(File.ReadAllText(path), Json) ?? [])
                .Select(j => j.State is "Executando" or "Validando" or "Aguardando autorização" or "Planejada"
                    ? j with { State = "Cancelada", Result = "O agente foi reiniciado." } : j).ToList();
        }
        catch (Exception e) when (e is IOException or JsonException or UnauthorizedAccessException) { return []; }
    }

    internal AssistedJob? Latest() { lock (gate) return history.FirstOrDefault(); }
    internal AssistedJob[] History() { lock (gate) return history.ToArray(); }

    private void Save(AssistedJob job)
    {
        history.RemoveAll(item => item.Id == job.Id);
        history.Insert(0, job with { Updated = DateTimeOffset.Now });
        if (history.Count > 25) history.RemoveRange(25, history.Count - 25);
        try
        {
            string scratch = historyPath + ".tmp";
            File.WriteAllText(scratch, JsonSerializer.Serialize(history, Json));
            File.Move(scratch, historyPath, true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
    }

    private static AssistedStep Step(string id, string label, string tool, string success,
        int seconds = 35, bool confirm = false, params string[] dependencies) =>
        new(id, label, tool, dependencies, confirm, seconds, success);

    internal string? Request(string text)
    {
        string phrase = VoiceIntent.Clean(text);
        if (phrase is "cancele a tarefa" or "cancele a tarefa atual" or "cancelar tarefa")
            return Cancel(null) ? "Cancelamento solicitado." : "Nenhuma tarefa em andamento.";
        if (phrase is "como esta aquela tarefa" or "como esta a tarefa" or "status da tarefa"
            or "o que voce fez no meu projeto" or "qual foi o ultimo erro")
        {
            var job = Latest();
            return job is null ? "Nenhuma tarefa registrada." : job.State + ". " +
                (job.Result ?? job.Steps.FirstOrDefault(s => s.State is "Executando" or "Validando")?.Description ?? "");
        }
        if (phrase is "continue a tarefa anterior" or "retome a tarefa anterior")
        {
            var last = Latest();
            if (last is null || last.State is not ("Falhou" or "Cancelada")) return "Não há tarefa interrompida para retomar.";
            if (last.Steps.Any(s => s.State == "Concluída"))
                return "A tarefa já executou etapas. Consulte os detalhes e peça apenas a etapa restante.";
            return Plan(last.Request, true);
        }
        return Plan(text, false);
    }

    private string? Plan(string text, bool resume)
    {
        string phrase = VoiceIntent.Clean(text);
        if (phrase.StartsWith("riandeck ")) phrase = phrase[9..];
        else if (phrase.StartsWith("rian deck ")) phrase = phrase[10..];
        phrase = phrase.Replace("code x", "codex").Replace("codecs", "codex");
        if (System.Text.RegularExpressions.Regex.IsMatch(phrase,
            @"\b(?:nao|nunca|apague|exclua|delete|formate|instale|desative|senha|credenciais)\b"))
            return "Esse pedido não será executado como plano automático.";
        if (!System.Text.RegularExpressions.Regex.IsMatch(phrase,
            @"^(?:(?:voce )?(?:pode|poderia) )?(?:abra|abre|abrir|carregue|peca|peco|analise|verifique|organize|mostre) "))
            return phrase.Contains("codex") && phrase.Contains("projeto") ? "Para criar um plano, peça ao Codex para analisar o projeto autorizado." : null;
        var steps = new List<AssistedStep>();
        bool cursor = VoiceIntent.App(text) == "cursor" || phrase.Contains("abra o cursor") || phrase.Contains("abre o cursor");
        bool project = phrase.Contains("meu projeto") || phrase.Contains("projeto riandeckos");
        bool analyze = phrase.Contains("codex") && (phrase.Contains("analis") || phrase.Contains("verific") || phrase.Contains("erros"));
        bool desktop = phrase.Contains("organize minhas janelas") || phrase.Contains("mostre a area de trabalho");
        bool browser = (desktop || cursor || project || analyze) && (phrase.Contains("abra o navegador") || phrase.Contains("abre o navegador"));
        if (!cursor && !project && !analyze && !desktop) return null;
        if (!Enabled) return "As ações de voz estão desativadas no Windows.";
        if (project || analyze) steps.Add(Step("project", "Verificar o projeto autorizado", "Windows", "Diretório configurado existe."));
        if (cursor || (project && !analyze))
            steps.Add(project ? Step("cursor-project", "Abrir o projeto no Cursor", "Cursor", "Janela com o nome do projeto.", 35, false, "project")
                : Step("cursor", "Abrir o Cursor", "Cursor", "Janela do Cursor visível."));
        if (analyze)
            steps.Add(Step("codex", "Analisar código em modo somente leitura", "Codex CLI", "Processo terminou com código zero e retornou análise.",
                95, true, steps.Any(s => s.Id == "cursor-project") ? ["project", "cursor-project"] : ["project"]));
        if (desktop) steps.Add(Step("desktop", "Mostrar a área de trabalho", "Windows", "Desktop em primeiro plano."));
        if (browser) steps.Add(Step("browser", "Abrir o navegador", "Windows", "Janela do navegador encontrada.", 35, false,
            steps.Count == 0 ? [] : [steps[^1].Id]));

        lock (gate)
        {
            if (running != null || history.FirstOrDefault()?.State == "Aguardando autorização")
                return "Já existe uma tarefa em andamento ou aguardando autorização.";
            bool confirm = resume || steps.Any(s => s.RequiresApproval);
            var now = DateTimeOffset.Now;
            var job = new AssistedJob(Guid.NewGuid().ToString("N"), text[..Math.Min(text.Length, 500)],
                confirm ? "Aguardando autorização" : "Planejada", now, now, steps.ToArray());
            Save(job);
            if (!confirm) Start(job);
            return confirm ? "Preparei o plano. Confira as etapas e autorize pelo painel da tarefa." : "Iniciei a tarefa. Você pode acompanhar no painel.";
        }
    }

    internal bool Approve(string id)
    {
        lock (gate)
        {
            if (!Enabled || running != null || history.FirstOrDefault() is not { State: "Aguardando autorização" } job
                || job.Id != id) return false;
            var approved = job with { Approved = true };
            Save(approved);
            Start(approved);
            return true;
        }
    }

    internal bool Cancel(string? id)
    {
        lock (gate)
        {
            var job = history.FirstOrDefault();
            if (job == null || (id != null && job.Id != id)) return false;
            if (running != null) { running.Cancel(); return true; }
            if (job.State != "Aguardando autorização") return false;
            Save(job with { State = "Cancelada", Result = "Cancelada antes da execução." });
            return true;
        }
    }

    private void Start(AssistedJob job)
    {
        var source = new CancellationTokenSource();
        running = source;
        _ = Task.Run(() => Execute(job, source));
    }

    private async Task Execute(AssistedJob job, CancellationTokenSource source)
    {
        var token = source.Token;
        int index = 0;
        void ReplaceStep(AssistedStep item)
        {
            var updated = job.Steps.ToArray();
            updated[index] = item;
            job = job with { Steps = updated };
        }
        void Update(string state, string? result = null)
        {
            job = job with { State = state, Result = result, Steps = job.Steps.ToArray() };
            lock (gate) Save(job);
        }
        try
        {
            for (; index < job.Steps.Length; index++)
            {
                token.ThrowIfCancellationRequested();
                var step = job.Steps[index];
                if (step.RequiresApproval && !job.Approved) throw new InvalidOperationException("Autorização necessária.");
                if (step.Dependencies.Any(id => !job.Steps.Any(s => s.Id == id && s.State == "Concluída")))
                    throw new InvalidOperationException("Uma etapa anterior não foi concluída.");
                ReplaceStep(step with { State = "Executando" });
                Update("Executando");
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
                timeout.CancelAfter(TimeSpan.FromSeconds(step.TimeoutSeconds));
                string result = step.Id switch
                {
                    "project" => $"Diretório autorizado encontrado: {Path.GetFileName(windows.Project())}.",
                    "cursor" => await windows.Cursor(false, timeout.Token),
                    "cursor-project" => await windows.Cursor(true, timeout.Token),
                    "browser" => await windows.Browser(timeout.Token),
                    "desktop" => await windows.Desktop(timeout.Token),
                    "codex" => await assistant.AnalyzeProject(timeout.Token),
                    _ => throw new InvalidOperationException("Etapa fora do catálogo.")
                };
                ReplaceStep(step with { State = "Validando", Result = result });
                Update("Validando", result);
                if (string.IsNullOrWhiteSpace(result)) throw new InvalidOperationException("A etapa não retornou evidência.");
                ReplaceStep(step with { State = "Concluída", Result = result });
                Update("Executando", result);
            }
            Update("Concluída", job.Steps.LastOrDefault()?.Result);
        }
        catch (Exception e) when (e is not OutOfMemoryException)
        {
            bool cancelled = token.IsCancellationRequested;
            string reason = cancelled ? "Cancelada pelo usuário." : e is OperationCanceledException
                ? "A etapa excedeu o tempo limite." : e is InvalidOperationException ? e.Message : "A ferramenta não pôde concluir a etapa.";
            if (index < job.Steps.Length) ReplaceStep(job.Steps[index] with
                { State = cancelled ? "Cancelada" : "Falhou", Result = reason });
            Update(cancelled ? "Cancelada" : "Falhou", reason);
        }
        finally { lock (gate) { running = null; source.Dispose(); } }
    }
}
