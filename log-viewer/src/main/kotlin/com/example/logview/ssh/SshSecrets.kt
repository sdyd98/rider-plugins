package com.example.logview.ssh

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Stores SSH passwords / key passphrases in the IDE's secure [PasswordSafe] (the OS keychain or the
 * user's encrypted master store) — never in the serialized [SshProfile] or anywhere on disk in
 * plaintext. Keyed by the profile id so a secret follows its profile.
 */
object SshSecrets {

    private fun attributes(profileId: String): CredentialAttributes =
        CredentialAttributes(generateServiceName("LogViewer SSH", profileId))

    fun setSecret(profileId: String, secret: String?) {
        val attrs = attributes(profileId)
        if (secret.isNullOrEmpty()) {
            PasswordSafe.instance.set(attrs, null)
        } else {
            PasswordSafe.instance.set(attrs, Credentials(profileId, secret))
        }
    }

    fun getSecret(profileId: String): String? = PasswordSafe.instance.getPassword(attributes(profileId))

    fun clear(profileId: String) = setSecret(profileId, null)
}
