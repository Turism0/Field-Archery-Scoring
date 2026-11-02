namespace NfasApi.Rounds;

// JSON shape of a round, as sent by the app (camelCase). Codes match the app's enums and the database checks.

public sealed record RoundDto(
    DateOnly ShootDate,
    int TargetCount,
    string ArcherClass,          // L or G
    string AgeGroup,             // ADULT / JUNIOR / CUB
    string BowStyle,             // AFB, BB, ... UL
    string Status,               // COMPLETED (the app only uploads finished rounds) or IN_PROGRESS
    int TotalPoints,
    DateTime ClientCreatedUtc,
    DateTime ClientUpdatedUtc,
    IReadOnlyList<TargetDto> Targets,
    // Shoot location (schema v3). Optional: older apps don't send it, and rounds without signal or
    // GPS have none. An unknown id is stored as no location rather than refusing the round.
    Guid? LocationId = null,
    // BIG_GAME (default, and what older apps mean) or WOODSMAN (schema v5).
    string? RoundType = null);

public sealed record TargetDto(
    int TargetNo,
    string Type,                 // STANDARD / PREDATOR_PREY / FAN
    int? OverridePoints,
    int Points,
    IReadOnlyList<AnimalDto> Animals,
    // Woodsman targets: arrows in order, W = wound, K = kill, M = miss (e.g. "WK"). Big Game: null.
    string? Arrows = null);

public sealed record AnimalDto(
    int Index,                   // 0 = first animal (the predator on a predator/prey)
    string State,                // PENDING / MISSED / SCORED
    int? ArrowNo,
    string? Hit,                 // INNER_KILL / KILL / WOUND
    int Points);

/// <summary>A round as returned by GET, with the server's own fields.</summary>
public sealed record StoredRoundDto(
    Guid RoundId,
    DateOnly ShootDate,
    int TargetCount,
    string ArcherClass,
    string AgeGroup,
    string BowStyle,
    string Status,
    int TotalPoints,
    bool IsDeleted,
    DateTime ClientCreatedUtc,
    DateTime ClientUpdatedUtc,
    DateTime ServerUpdatedUtc,
    IReadOnlyList<TargetDto> Targets,
    Guid? LocationId,
    string? LocationName,
    string RoundType);
