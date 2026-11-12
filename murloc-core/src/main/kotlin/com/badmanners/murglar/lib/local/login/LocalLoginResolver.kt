package com.badmanners.murglar.lib.local.login

import com.badmanners.murglar.lib.core.login.CredentialLoginStep
import com.badmanners.murglar.lib.core.login.CredentialsLoginVariant
import com.badmanners.murglar.lib.core.login.LoginResolver
import com.badmanners.murglar.lib.core.login.WebLoginVariant
import com.badmanners.murglar.lib.core.webview.WebViewProvider
import com.badmanners.murglar.lib.local.localization.LocalMessages


/**
 * Login resolver for a local-files service - no login is required.
 *
 * Always reports 'logged in', so that nodes stay available, but declares no login variants,
 * so that the host doesn't show any login UI ([LoginResolver.isLoginSupported] == false).
 */
class LocalLoginResolver(
    private val messages: LocalMessages
) : LoginResolver {

    override val isLogged = true

    override val loginInfo: String
        get() = messages.loginNotRequired

    override val webLoginVariants = emptyList<WebLoginVariant>()

    override suspend fun webLogin(loginVariantId: String, webViewProvider: WebViewProvider): Boolean =
        throw UnsupportedOperationException("webLogin")

    override val credentialsLoginVariants = emptyList<CredentialsLoginVariant>()

    override suspend fun credentialsLogin(loginVariantId: String, args: Map<String, String>): CredentialLoginStep =
        throw UnsupportedOperationException("credentialsLogin")

    override fun logout() {}
}
