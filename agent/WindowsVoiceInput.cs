using System.Runtime.InteropServices;

internal static class WindowsVoiceInput
{
    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    private struct Format
    {
        internal ushort Tag, Channels;
        internal uint Rate, ByteRate;
        internal ushort Align, Bits, Extra;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Header
    {
        internal IntPtr Data;
        internal uint Length, Recorded;
        internal UIntPtr User;
        internal uint Flags, Loops;
        internal IntPtr Next;
        internal UIntPtr Reserved;
    }

    [DllImport("winmm.dll")] private static extern uint waveInOpen(out IntPtr device, uint source, ref Format format,
        IntPtr callback, IntPtr instance, uint flags);
    [DllImport("winmm.dll")] private static extern uint waveInPrepareHeader(IntPtr device, IntPtr header, uint size);
    [DllImport("winmm.dll")] private static extern uint waveInUnprepareHeader(IntPtr device, IntPtr header, uint size);
    [DllImport("winmm.dll")] private static extern uint waveInAddBuffer(IntPtr device, IntPtr header, uint size);
    [DllImport("winmm.dll")] private static extern uint waveInStart(IntPtr device);
    [DllImport("winmm.dll")] private static extern uint waveInReset(IntPtr device);
    [DllImport("winmm.dll")] private static extern uint waveInClose(IntPtr device);

    private static void Check(uint result)
    {
        if (result != 0) throw new IOException("Entrada de áudio Windows indisponível.");
    }

    internal static async Task<string> Test(CancellationToken token)
    {
        using var stop = CancellationTokenSource.CreateLinkedTokenSource(token);
        stop.CancelAfter(TimeSpan.FromSeconds(2));
        bool read = false;
        try
        {
            await Capture(() => Task.CompletedTask, pcm =>
            {
                read = pcm.Length > 0;
                stop.Cancel();
                return Task.CompletedTask;
            }, stop.Token);
        }
        catch (IOException) { return "A entrada padrão do Windows não está disponível."; }
        catch (OperationCanceledException) when (stop.IsCancellationRequested) { }
        return read ? "O Windows retornou amostras de áudio da entrada padrão." : "O Windows não entregou amostras de áudio.";
    }

    internal static async Task Capture(Func<Task> ready, Func<byte[], Task> send, CancellationToken token)
    {
        var format = new Format { Tag = 1, Channels = 1, Rate = 24000, ByteRate = 48000, Align = 2, Bits = 16 };
        Check(waveInOpen(out var device, uint.MaxValue, ref format, IntPtr.Zero, IntPtr.Zero, 0));
        const int chunk = 1920;
        uint size = (uint)Marshal.SizeOf<Header>();
        var buffers = new List<(IntPtr Header, IntPtr Data, bool Prepared)>(4);
        try
        {
            for (int i = 0; i < 4; i++)
            {
                var pointer = Marshal.AllocHGlobal((int)size);
                var data = Marshal.AllocHGlobal(chunk);
                buffers.Add((pointer, data, false));
                Marshal.StructureToPtr(new Header { Data = data, Length = chunk }, pointer, false);
                Check(waveInPrepareHeader(device, pointer, size));
                buffers[i] = (pointer, data, true);
                Check(waveInAddBuffer(device, pointer, size));
            }
            Check(waveInStart(device));
            await ready();
            int next = 0;
            while (!token.IsCancellationRequested)
            {
                var buffer = buffers[next];
                var header = Marshal.PtrToStructure<Header>(buffer.Header);
                if ((header.Flags & 1) == 0) { await Task.Delay(12, token); continue; }
                if (header.Recorded is > 0 and <= chunk)
                {
                    var pcm = new byte[header.Recorded];
                    Marshal.Copy(buffer.Data, pcm, 0, pcm.Length);
                    Check(waveInAddBuffer(device, buffer.Header, size));
                    await send(pcm);
                }
                else Check(waveInAddBuffer(device, buffer.Header, size));
                next = (next + 1) % buffers.Count;
            }
        }
        finally
        {
            waveInReset(device);
            foreach (var buffer in buffers)
            {
                if (buffer.Prepared) waveInUnprepareHeader(device, buffer.Header, size);
                Marshal.FreeHGlobal(buffer.Header);
                Marshal.FreeHGlobal(buffer.Data);
            }
            waveInClose(device);
        }
    }
}
