package de.norm.events.account

import org.springframework.security.authentication.AbstractAuthenticationToken

/** A signed-in visitor, as Spring Security carries it. No authorities: every account may do the same things. */
class AccountAuthentication(
    val account: Account
) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = ""

    override fun getPrincipal(): Account = account
}
