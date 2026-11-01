package uk.co.fnurble.nfas.domain

/**
 * Account roles, as held on the server (dbo.Archer.Role). There is one Owner; only the Owner can make
 * or remove Admins. The phone only uses the role to decide what to show: the server checks it on every
 * admin call, so a changed app can't do more than the account is allowed to.
 */
enum class AccountRole(val displayName: String) {
    USER("User"),
    ADMIN("Admin"),
    OWNER("Owner");

    /** Admins and the Owner see the Admin menu. */
    val isAdmin: Boolean get() = this != USER

    companion object {
        fun fromCode(code: String?): AccountRole = entries.firstOrNull { it.name == code } ?: USER
    }
}
