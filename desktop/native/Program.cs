using System.Text.Json;
using Windows.Media.Control;

var output = Console.Out;
var manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
var currentSession = manager.GetCurrentSession();
Console.CancelKeyPress += (_, eventArgs) => { eventArgs.Cancel = true; };
_ = Task.Run(async () =>
{
    while (true)
    {
        var command = await Console.In.ReadLineAsync();
        if (command is null) break;
        var session = manager.GetCurrentSession();
        if (session is null) continue;
        try
        {
            switch (command.Trim())
            {
                case "toggle": await session.TryTogglePlayPauseAsync(); break;
                case "next": await session.TrySkipNextAsync(); break;
                case "prev": await session.TrySkipPreviousAsync(); break;
            }
        }
        catch { }
    }
});

while (true)
{
    try
    {
        var session = manager.GetCurrentSession();
        if (session is null)
        {
            await output.WriteLineAsync(JsonSerializer.Serialize(new { type = "media", available = false, title = "", artist = "", playing = false }));
        }
        else
        {
            var props = await session.TryGetMediaPropertiesAsync();
            var playing = session.GetPlaybackInfo()?.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing;
            var snapshot = new { type = "media", available = true, title = props?.Title ?? "", artist = props?.Artist ?? "", playing };
            await output.WriteLineAsync(JsonSerializer.Serialize(snapshot));
        }
        await output.FlushAsync();
    }
    catch
    {
        await output.WriteLineAsync("{\"type\":\"media\",\"available\":false,\"title\":\"\",\"artist\":\"\",\"playing\":false}");
        await output.FlushAsync();
    }
    await Task.Delay(900);
}
