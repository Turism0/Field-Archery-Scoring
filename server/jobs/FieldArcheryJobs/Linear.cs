namespace FieldArcheryJobs;

/// <summary>Small dense linear algebra (the fit's fixed part has about 100 unknowns).</summary>
public static class Linear
{
    /// <summary>Solves a x = b by Gaussian elimination with partial pivoting (a and b are not changed).</summary>
    public static double[] Solve(double[,] a, double[] b)
    {
        var n = b.Length;
        var m = (double[,])a.Clone(); var x = (double[])b.Clone();
        for (var k = 0; k < n; k++)
        {
            var piv = k;
            for (var i = k + 1; i < n; i++) if (Math.Abs(m[i, k]) > Math.Abs(m[piv, k])) piv = i;
            if (piv != k)
            {
                for (var j = 0; j < n; j++) (m[k, j], m[piv, j]) = (m[piv, j], m[k, j]);
                (x[k], x[piv]) = (x[piv], x[k]);
            }
            for (var i = k + 1; i < n; i++)
            {
                var f = m[i, k] / m[k, k];
                if (f == 0) continue;
                for (var j = k; j < n; j++) m[i, j] -= f * m[k, j];
                x[i] -= f * x[k];
            }
        }
        for (var i = n - 1; i >= 0; i--)
        {
            var s = x[i];
            for (var j = i + 1; j < n; j++) s -= m[i, j] * x[j];
            x[i] = s / m[i, i];
        }
        return x;
    }

    /// <summary>Cholesky factor L (lower) of a symmetric positive definite matrix, a = L Lᵀ.</summary>
    public static double[,] Cholesky(double[,] a)
    {
        var n = a.GetLength(0); var l = new double[n, n];
        for (var i = 0; i < n; i++)
        {
            for (var j = 0; j <= i; j++)
            {
                var s = a[i, j];
                for (var k = 0; k < j; k++) s -= l[i, k] * l[j, k];
                if (i == j)
                {
                    if (s <= 0) throw new InvalidOperationException("The fit's equations aren't solvable (matrix not positive definite).");
                    l[i, i] = Math.Sqrt(s);
                }
                else l[i, j] = s / l[j, j];
            }
        }
        return l;
    }

    /// <summary>Solves L Lᵀ x = b from the Cholesky factor.</summary>
    public static double[] CholeskySolve(double[,] l, double[] b)
    {
        var n = b.Length; var y = new double[n]; var x = new double[n];
        for (var i = 0; i < n; i++) { var s = b[i]; for (var k = 0; k < i; k++) s -= l[i, k] * y[k]; y[i] = s / l[i, i]; }
        for (var i = n - 1; i >= 0; i--) { var s = y[i]; for (var k = i + 1; k < n; k++) s -= l[k, i] * x[k]; x[i] = s / l[i, i]; }
        return x;
    }

    /// <summary>Percentile with linear interpolation (numpy's default).</summary>
    public static double Percentile(IEnumerable<double> values, double pct)
    {
        var v = values.OrderBy(x => x).ToArray();
        if (v.Length == 0) return double.NaN;
        var pos = (v.Length - 1) * pct / 100.0;
        var lo = (int)Math.Floor(pos); var hi = (int)Math.Ceiling(pos);
        return v[lo] + (v[hi] - v[lo]) * (pos - lo);
    }
}
