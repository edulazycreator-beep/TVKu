package com.tvku.core.security

import java.util.UUID

/**
 * Identity of this installation towards the TVKu backend.
 *
 * [installationUuid] is a random UUID (public: it may appear in a WhatsApp
 * donation message). [secret] is a random 256-bit value that proves ownership
 * of the UUID; the server stores only its SHA-256. Neither is derived from any
 * hardware identifier.
 */
class InstallationCredentials(val installationUuid: UUID, val secret: String) {
    override fun toString(): String = "InstallationCredentials(installationUuid=$installationUuid, secret=<redacted>)"
    override fun equals(other: Any?) =
        other is InstallationCredentials && other.installationUuid == installationUuid && other.secret == secret
    override fun hashCode() = 31 * installationUuid.hashCode() + secret.hashCode()
}
