using System.Text.Json.Serialization;

namespace Goals_Windows.Models.Api;

[JsonConverter(typeof(JsonStringEnumConverter<GoalType>))]
public enum GoalType
{
    [JsonStringEnumMemberName("daily")] Daily,
    [JsonStringEnumMemberName("weekly_x")] WeeklyX
}
