using System.Text.RegularExpressions;

namespace LearnHub.Launcher.Core;

public sealed class LogRedactor
{
    private readonly string[] _secrets;
    public LogRedactor(IReadOnlyDictionary<string, string>? environment = null)
    {
        _secrets = (environment ?? new Dictionary<string, string>())
            .Where(x => Regex.IsMatch(x.Key, "key|secret|pass|token|auth|credential", RegexOptions.IgnoreCase) && x.Value.Length > 0)
            .Select(x => x.Value).Distinct().OrderByDescending(x => x.Length).ToArray();
    }

    public string Redact(string? input)
    {
        var value = input ?? "";
        foreach (var secret in _secrets) value = value.Replace(secret, "[REDACTED]", StringComparison.Ordinal);
        value = Regex.Replace(value, @"(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+", "$1 [REDACTED]");
        value = Regex.Replace(value, @"(?i)\b(sk-(?:proj-)?[A-Za-z0-9_-]{8,})\b", "[REDACTED]");
        value = Regex.Replace(value,
            "(?i)([\\\"']?(?:[A-Za-z0-9_.-]*(?:password|passwd|pass|pwd|secret|token|api[_-]?key|authorization|auth|credential)[A-Za-z0-9_.-]*)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;&}]+)",
            "$1[REDACTED]");
        value = Regex.Replace(value, @"(?i)(https?://)[^\s/@:]+:[^\s/@]+@", "$1[REDACTED]@");
        return value;
    }
}
