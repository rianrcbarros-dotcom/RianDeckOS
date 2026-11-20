using System.Diagnostics;
using System.Globalization;

internal sealed class GpuTemperature : BackgroundService
{
    private double reading = double.NaN;
    internal double? Reading
    {
        get
        {
            double value = Volatile.Read(ref reading);
            return double.IsFinite(value) ? value : null;
        }
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var executable = Path.Combine(Environment.SystemDirectory, "nvidia-smi.exe");
        if (!File.Exists(executable)) return;
        using var interval = new PeriodicTimer(TimeSpan.FromSeconds(15));
        do { Volatile.Write(ref reading, await Read(executable, stoppingToken)); }
        while (await interval.WaitForNextTickAsync(stoppingToken));
    }

    private static async Task<double> Read(string path, CancellationToken cancellation)
    {
        var request = new ProcessStartInfo(path)
        {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true
        };
        request.ArgumentList.Add("--query-gpu=temperature.gpu");
        request.ArgumentList.Add("--format=csv,noheader,nounits");
        try
        {
            using var process = Process.Start(request);
            if (process is null) return double.NaN;
            using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            deadline.CancelAfter(TimeSpan.FromSeconds(4));
            try
            {
                var output = process.StandardOutput.ReadToEndAsync(deadline.Token);
                await process.WaitForExitAsync(deadline.Token);
                var result = await output;
                if (process.ExitCode != 0) return double.NaN;
                var values = result.Split('\n').Select(line =>
                    double.TryParse(line, NumberStyles.Float, CultureInfo.InvariantCulture, out var value) && value is >= 0 and <= 150
                    ? value : double.NaN);
                return values.Where(double.IsFinite).DefaultIfEmpty(double.NaN).Max();
            }
            catch (OperationCanceledException)
            {
                if (!process.HasExited) process.Kill();
                return double.NaN;
            }
        }
        catch (Exception error) when (error is System.ComponentModel.Win32Exception or InvalidOperationException)
        { return double.NaN; }
    }
}
