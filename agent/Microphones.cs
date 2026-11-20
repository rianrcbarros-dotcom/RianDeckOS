using System.Runtime.InteropServices;

internal static class Microphones
{
    private static readonly object Gate = new();
    private static readonly Guid Enumerator = new("BCDE0395-E52F-467C-8E3D-C4579291692E");
    internal static bool? State() => ReadOrToggle(false);
    internal static bool? Toggle() => ReadOrToggle(true);

    private static bool? ReadOrToggle(bool toggle)
    {
        if (!OperatingSystem.IsWindows()) return null;
        lock (Gate)
        {
            object? enumerator = null;
            DeviceCollection? collection = null;
            var endpoints = new List<EndpointVolume>();
            try
            {
                enumerator = Activator.CreateInstance(Type.GetTypeFromCLSID(Enumerator, true)!);
                ((DeviceEnumerator)enumerator!).EnumAudioEndpoints(1, 1, out collection);
                collection.GetCount(out var count);
                if (count == 0) return null;
                var volumeId = typeof(EndpointVolume).GUID;
                for (uint index = 0; index < count; index++)
                {
                    collection.Item(index, out var device);
                    try
                    {
                        device.Activate(ref volumeId, 23, IntPtr.Zero, out var volume);
                        endpoints.Add((EndpointVolume)volume);
                    }
                    finally { Marshal.ReleaseComObject(device); }
                }
                var allMuted = true;
                foreach (var endpoint in endpoints)
                {
                    endpoint.GetMute(out var muted);
                    allMuted &= muted;
                }
                if (!toggle) return allMuted;
                var target = !allMuted;
                foreach (var endpoint in endpoints) endpoint.SetMute(target, Guid.Empty);
                return target;
            }
            catch (COMException) { return null; }
            finally
            {
                foreach (var endpoint in endpoints) Marshal.ReleaseComObject(endpoint);
                if (collection is not null) Marshal.ReleaseComObject(collection);
                if (enumerator is not null) Marshal.ReleaseComObject(enumerator);
            }
        }
    }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface DeviceEnumerator
    {
        void EnumAudioEndpoints(int flow, uint state, out DeviceCollection collection);
    }

    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface DeviceCollection
    {
        void GetCount(out uint count);
        void Item(uint index, out Device device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface Device
    {
        void Activate(ref Guid iid, uint context, IntPtr parameters, [MarshalAs(UnmanagedType.IUnknown)] out object value);
    }

    [ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface EndpointVolume
    {
        void RegisterControlChangeNotify(IntPtr callback);
        void UnregisterControlChangeNotify(IntPtr callback);
        void GetChannelCount(out uint count);
        void SetMasterVolumeLevel(float level, in Guid context);
        void SetMasterVolumeLevelScalar(float level, in Guid context);
        void GetMasterVolumeLevel(out float level);
        void GetMasterVolumeLevelScalar(out float level);
        void SetChannelVolumeLevel(uint channel, float level, in Guid context);
        void SetChannelVolumeLevelScalar(uint channel, float level, in Guid context);
        void GetChannelVolumeLevel(uint channel, out float level);
        void GetChannelVolumeLevelScalar(uint channel, out float level);
        void SetMute([MarshalAs(UnmanagedType.Bool)] bool muted, in Guid context);
        void GetMute([MarshalAs(UnmanagedType.Bool)] out bool muted);
    }
}
