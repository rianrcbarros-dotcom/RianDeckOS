using System.Diagnostics;
using System.Net.NetworkInformation;

internal sealed class NetworkMetrics
{
    private readonly Dictionary<string, (long Received, long Sent)> previous = new();
    private long previousTime;

    internal object? Sample()
    {
        long now = Stopwatch.GetTimestamp();
        double elapsed = (now - previousTime) / (double)Stopwatch.Frequency;
        var current = new Dictionary<string, (long Received, long Sent)>();
        long received = 0, sent = 0;
        bool sampled = false;
        foreach (var adapter in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (adapter.OperationalStatus != OperationalStatus.Up ||
                adapter.NetworkInterfaceType is not (NetworkInterfaceType.Ethernet or NetworkInterfaceType.Wireless80211))
                continue;
            try
            {
                var statistics = adapter.GetIPStatistics();
                current[adapter.Id] = (statistics.BytesReceived, statistics.BytesSent);
                if (!previous.TryGetValue(adapter.Id, out var sample)) continue;
                received += Math.Max(0, statistics.BytesReceived - sample.Received);
                sent += Math.Max(0, statistics.BytesSent - sample.Sent);
                sampled = true;
            }
            catch (NetworkInformationException) { }
        }
        previousTime = now;
        previous.Clear();
        foreach (var sample in current) previous.Add(sample.Key, sample.Value);
        return !sampled || elapsed <= 0 ? null : new { down = received / elapsed, up = sent / elapsed };
    }
}
