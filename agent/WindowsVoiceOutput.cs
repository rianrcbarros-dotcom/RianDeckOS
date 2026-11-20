using System.Runtime.InteropServices;

internal static class WindowsVoiceOutput
{
    [DllImport("winmm.dll", EntryPoint = "PlaySoundW", SetLastError = false)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PlaySound(IntPtr sound, IntPtr module, uint flags);

    internal static async Task Play(byte[] pcm, double volume, CancellationToken stop)
    {
        using var data = new MemoryStream(pcm.Length + 44);
        using (var wav = new BinaryWriter(data, System.Text.Encoding.ASCII, leaveOpen: true))
        {
            wav.Write("RIFF"u8); wav.Write(36 + pcm.Length); wav.Write("WAVEfmt "u8);
            wav.Write(16); wav.Write((short)1); wav.Write((short)1); wav.Write(24000); wav.Write(48000);
            wav.Write((short)2); wav.Write((short)16); wav.Write("data"u8); wav.Write(pcm.Length);
            for (int i = 0; i + 1 < pcm.Length; i += 2)
                wav.Write((short)((short)(pcm[i] | pcm[i + 1] << 8) * volume));
        }
        stop.ThrowIfCancellationRequested();
        var pinned = GCHandle.Alloc(data.GetBuffer(), GCHandleType.Pinned);
        try
        {
            const uint asyncMemoryNoDefault = 0x0001 | 0x0002 | 0x0004;
            if (!PlaySound(pinned.AddrOfPinnedObject(), IntPtr.Zero, asyncMemoryNoDefault))
                throw new IOException("A saída de áudio padrão do Windows está indisponível.");
            await Task.Delay(TimeSpan.FromSeconds(pcm.Length / 48000.0) + TimeSpan.FromMilliseconds(150), stop);
        }
        finally
        {
            PlaySound(IntPtr.Zero, IntPtr.Zero, 0);
            pinned.Free();
        }
    }
}
