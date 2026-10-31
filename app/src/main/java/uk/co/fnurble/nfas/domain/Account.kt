package uk.co.fnurble.nfas.domain

/** Account rules, matching the server (server/api/NfasApi/Auth/AuthEndpoints.cs). */
object AccountRules {
    const val MIN_PASSWORD = 8
    const val MAX_PASSWORD = 128
    private val usernamePattern = Regex("^[A-Za-z0-9_.-]{3,30}$")

    fun isValidUsername(username: String): Boolean = usernamePattern.matches(username.trim())

    /** Problem with a new password (and its confirmation), or null if it's fine. */
    fun newPasswordProblem(password: String, confirm: String): String? = when {
        password.length < MIN_PASSWORD -> "Passwords must be at least $MIN_PASSWORD characters."
        password.length > MAX_PASSWORD -> "Passwords can be at most $MAX_PASSWORD characters."
        password != confirm -> "The two passwords don't match."
        else -> null
    }

    const val USERNAME_HELP = "3 to 30 characters: letters, numbers, dot, dash or underscore. Don't use your real name."
}
