using Microsoft.Data.SqlClient;

namespace NfasApi.Data;

/// <summary>Opens connections to the NfasScoring database (SQL Server on Mimir).</summary>
public sealed class Db(string connectionString)
{
    public async Task<SqlConnection> OpenAsync(CancellationToken ct)
    {
        var conn = new SqlConnection(connectionString);
        await conn.OpenAsync(ct);
        return conn;
    }

    /// <summary>True if the error is a unique key violation (e.g. username already taken).</summary>
    public static bool IsDuplicateKey(SqlException ex) => ex.Number is 2627 or 2601;
}
