using System.ComponentModel.DataAnnotations;

namespace MegaStream.Server.Contracts;

public sealed class DeviceExperienceRequest
{
    [StringLength(17)]
    [RegularExpression(@"[0-9a-fA-F]{2}([:-])[0-9a-fA-F]{2}(\1[0-9a-fA-F]{2}){4}")]
    public string? MacAddress { get; init; }
}

public sealed record DeviceExperienceResponse(bool AllowSubscriptionDetails,
    IReadOnlyList<DeviceNotificationResponse> Notifications, string? MacAddress);
public sealed record DeviceExperienceV2Response(bool AllowSubscriptionDetails,
    IReadOnlyList<DeviceNotificationResponse> Notifications, string? MacAddress, string? UiStyle);
public sealed record DeviceNotificationResponse(string Id, string Title, string Message, string CreatedAt);
