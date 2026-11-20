using System.Runtime.InteropServices;

internal sealed class GpuMetrics : IDisposable
{
    private const uint DoubleFormat = 0x200;
    private const uint MoreData = 0x800007D2;
    private IntPtr query;
    private IntPtr counter;

    internal GpuMetrics()
    {
        if (PdhOpenQueryW(null, 0, out query) != 0) return;
        if (PdhAddEnglishCounterW(query, @"\GPU Engine(*)\Utilization Percentage", 0, out counter) != 0)
            counter = IntPtr.Zero;
    }

    internal double? Sample()
    {
        if (counter == IntPtr.Zero || PdhCollectQueryData(query) != 0) return null;
        uint bytes = 0;
        if (PdhGetFormattedCounterArrayW(counter, DoubleFormat, ref bytes, out _, IntPtr.Zero) != MoreData
            || bytes == 0 || bytes > 4 * 1024 * 1024) return null;
        var buffer = Marshal.AllocHGlobal((int)bytes);
        try
        {
            if (PdhGetFormattedCounterArrayW(counter, DoubleFormat, ref bytes, out var count, buffer) != 0)
                return null;
            var totals = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
            int size = Marshal.SizeOf<CounterItem>();
            for (int index = 0; index < count; index++)
            {
                var item = Marshal.PtrToStructure<CounterItem>(IntPtr.Add(buffer, index * size));
                if (item.Status > 1 || !double.IsFinite(item.Value)) continue;
                var instance = Marshal.PtrToStringUni(item.Name);
                if (instance is null) continue;
                int start = instance.IndexOf("_luid_", StringComparison.OrdinalIgnoreCase);
                if (start < 0) continue;
                var engine = instance[start..];
                totals[engine] = totals.GetValueOrDefault(engine) + item.Value;
            }
            return totals.Count == 0 ? null : Math.Clamp(totals.Values.Max(), 0, 100);
        }
        finally { Marshal.FreeHGlobal(buffer); }
    }

    public void Dispose()
    {
        if (query != IntPtr.Zero) PdhCloseQuery(query);
        query = IntPtr.Zero;
        counter = IntPtr.Zero;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CounterItem
    {
        internal IntPtr Name;
        internal uint Status;
        internal double Value;
    }

    [DllImport("pdh.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern uint PdhOpenQueryW(string? dataSource, nuint data, out IntPtr query);
    [DllImport("pdh.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern uint PdhAddEnglishCounterW(IntPtr query, string path, nuint data, out IntPtr counter);
    [DllImport("pdh.dll", ExactSpelling = true)]
    private static extern uint PdhCollectQueryData(IntPtr query);
    [DllImport("pdh.dll", ExactSpelling = true)]
    private static extern uint PdhCloseQuery(IntPtr query);
    [DllImport("pdh.dll", ExactSpelling = true)]
    private static extern uint PdhGetFormattedCounterArrayW(IntPtr counter, uint format, ref uint bytes,
        out uint count, IntPtr buffer);
}
