using Windows.Media.Control;
using Windows.Media;

internal sealed class MediaSession
{
    private readonly SemaphoreSlim gate = new(1);
    private GlobalSystemMediaTransportControlsSessionManager? manager;
    private Windows.Storage.Streams.IRandomAccessStreamReference? artwork;
    private byte[]? cover;
    private string? trackKey;

    internal async Task<object> Read()
    {
        await gate.WaitAsync();
        try
        {
            manager ??= await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            var session = manager.GetCurrentSession();
            if (session is null) { Clear(); return new { active = false }; }
            var properties = await session.TryGetMediaPropertiesAsync();
            if (properties is null || string.IsNullOrWhiteSpace(properties.Title))
            { Clear(); return new { active = false }; }

            var playback = session.GetPlaybackInfo();
            var timeline = session.GetTimelineProperties();
            bool playing = playback.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
            double duration = Math.Max(0, (timeline.EndTime - timeline.StartTime).TotalSeconds);
            double position = (timeline.Position - timeline.StartTime).TotalSeconds;
            if (playing && timeline.LastUpdatedTime > DateTimeOffset.UnixEpoch)
                position += Math.Max(0, (DateTimeOffset.UtcNow - timeline.LastUpdatedTime).TotalSeconds);

            string key = string.Join("|", session.SourceAppUserModelId, properties.Title, properties.Artist, properties.AlbumTitle);
            if (key != trackKey)
            {
                trackKey = key;
                cover = null;
                artwork = properties.Thumbnail;
            }
            return new { active = true, key, title = Clip(properties.Title), artist = Clip(properties.Artist),
                playing, position = Math.Clamp(position, 0, duration), duration, artwork = artwork is not null,
                seek = duration > 0 && playback.Controls.IsPlaybackPositionEnabled,
                shuffle = playback.Controls.IsShuffleEnabled && playback.IsShuffleActive.HasValue,
                shuffled = playback.IsShuffleActive,
                repeat = playback.Controls.IsRepeatEnabled && playback.AutoRepeatMode.HasValue,
                repeatMode = playback.AutoRepeatMode?.ToString() };
        }
        catch (Exception error) when (error is not OutOfMemoryException)
        {
            Clear();
            return new { active = false };
        }
        finally { gate.Release(); }
    }

    internal async Task<bool> Change(string command, double? fraction)
    {
        await gate.WaitAsync();
        try
        {
            manager ??= await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            var session = manager.GetCurrentSession();
            if (session is null) return false;
            var state = session.GetPlaybackInfo();
            switch (command)
            {
                case "seek" when state.Controls.IsPlaybackPositionEnabled &&
                    fraction is >= 0 and <= 1 && double.IsFinite(fraction.Value):
                    var timeline = session.GetTimelineProperties();
                    if (timeline.EndTime <= timeline.StartTime) return false;
                    long position = timeline.StartTime.Ticks + (long)((timeline.EndTime - timeline.StartTime).Ticks * fraction.Value);
                    if (timeline.MaxSeekTime > timeline.MinSeekTime)
                        position = Math.Clamp(position, timeline.MinSeekTime.Ticks, timeline.MaxSeekTime.Ticks);
                    return await session.TryChangePlaybackPositionAsync(position);
                case "shuffle" when state.Controls.IsShuffleEnabled && state.IsShuffleActive is bool active:
                    return await session.TryChangeShuffleActiveAsync(!active);
                case "repeat" when state.Controls.IsRepeatEnabled && state.AutoRepeatMode is { } mode:
                    var next = mode == MediaPlaybackAutoRepeatMode.None ? MediaPlaybackAutoRepeatMode.List
                        : mode == MediaPlaybackAutoRepeatMode.List ? MediaPlaybackAutoRepeatMode.Track
                        : MediaPlaybackAutoRepeatMode.None;
                    return await session.TryChangeAutoRepeatModeAsync(next);
                default: return false;
            }
        }
        catch (Exception error) when (error is not OutOfMemoryException) { return false; }
        finally { gate.Release(); }
    }

    internal async Task<byte[]?> Cover()
    {
        await gate.WaitAsync();
        try
        {
            if (cover is not null || artwork is null) return cover;
            using var image = await artwork.OpenReadAsync();
            if (image.Size == 0 || image.Size > 1024 * 1024) return null;
            using var reader = new Windows.Storage.Streams.DataReader(image.GetInputStreamAt(0));
            uint length = await reader.LoadAsync((uint)image.Size);
            if (length != image.Size) return null;
            cover = new byte[length];
            reader.ReadBytes(cover);
            return cover;
        }
        catch (Exception error) when (error is not OutOfMemoryException) { return null; }
        finally { gate.Release(); }
    }

    private void Clear() { trackKey = null; artwork = null; cover = null; }

    private static string Clip(string? text) => text is null ? "" : text[..Math.Min(text.Length, 180)];
}
