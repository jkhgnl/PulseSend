namespace PulseSend.Core.Models;

public sealed record DeviceIdentity
{
    public string Id { get; init; } = Guid.NewGuid().ToString("N");
    public string Name { get; init; } = Environment.MachineName;
    public string Platform { get; init; } = "windows";
}






