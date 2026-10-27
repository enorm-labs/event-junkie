package de.norm.events.account

import com.github.benmanes.caffeine.cache.Caffeine
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticatorTransport
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.util.exception.WebAuthnException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

private val logger = KotlinLogging.logger {}

/**
 * Passkeys (#3888) on `webauthn4j-core`, behind our own endpoints: Spring Security has no reactive WebAuthn
 * (spring-security#18393, ADR-053 rule 7). When it ships, this class goes.
 *
 * The relying party is this cluster's own origin, so a staging passkey never signs in to production. User verification is
 * required, because a passkey replaces the e-mail link and is then the only factor. Attestation is `none`: the site has
 * no use for the authenticator's make, and the AAGUID is zeroed before the row is stored.
 *
 * Verification is CPU only, so it runs on the caller's coroutine. Every refusal is one outcome to the caller, whatever
 * the cause, and the log carries no credential id.
 */
@Component
class Passkeys(
    private val store: PasskeyStore,
    private val metrics: AccountMetrics,
    private val clock: Clock,
    @Value("\${app.site.origin}") origin: String
) {
    private val origin = Origin(origin)
    private val rpId = requireNotNull(URI(origin).host) { "app.site.origin has no host: $origin" }
    private val converter = ObjectConverter()
    private val credentials = AttestedCredentialDataConverter(converter)
    private val manager = WebAuthnManager.createNonStrictWebAuthnManager(converter)
    private val random = SecureRandom()
    private val requestsPerClient =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(MAX_TRACKED_CLIENTS)
            .build<String, AtomicInteger>()

    /** The options `navigator.credentials.create()` takes, in the JSON form the browser parses. */
    suspend fun creationOptions(account: Account): CreationOptions {
        val handle = store.userHandle(account.id, bytes())
        val challenge = challenge(account)
        return CreationOptions(
            rp = RelyingParty(rpId, RP_NAME),
            user = PasskeyUser(base64(handle), account.email, account.email),
            challenge = base64(challenge),
            excludeCredentials = store.credentialIds(account.id).map { CredentialDescriptor(id = base64(it)) }
        )
    }

    /** Verifies the browser's answer to [creationOptions] and stores the passkey. */
    suspend fun register(
        account: Account,
        name: String,
        credential: Map<String, Any?>
    ): RegisterOutcome {
        val passkey =
            try {
                val data = manager.parseRegistrationResponseJSON(json(credential))
                val challenge = data.collectedClientData?.challenge?.value
                if (challenge == null || !store.consumeChallenge(challenge, account.id, clock.instant())) return RegisterOutcome.INVALID.recorded()
                manager.verify(data, RegistrationParameters(server(challenge), ALGORITHMS, true, true))
                val authenticator = requireNotNull(data.attestationObject).authenticatorData
                val attested = requireNotNull(authenticator.attestedCredentialData)
                StoredPasskey(
                    credentialId = attested.credentialId,
                    credential = credentials.convert(AttestedCredentialData(AAGUID.ZERO, attested.credentialId, attested.coseKey)),
                    signCount = authenticator.signCount,
                    flags = PasskeyFlags(authenticator.isFlagUV, authenticator.isFlagBE, authenticator.isFlagBS),
                    transports = data.transports.orEmpty().map(AuthenticatorTransport::getValue)
                )
            } catch (e: WebAuthnException) {
                logger.info { "Passkey registration refused: ${e.javaClass.simpleName}" }
                return RegisterOutcome.INVALID.recorded()
            }
        if (!store.add(account.id, passkey, name, clock.instant(), MAX_PASSKEYS)) return RegisterOutcome.LIMIT.recorded()
        return RegisterOutcome.REGISTERED.recorded()
    }

    /** The options `navigator.credentials.get()` takes, or null when this client asked too often. */
    suspend fun requestOptions(client: String): RequestOptions? {
        if (requestsPerClient.get(client) { AtomicInteger() }.incrementAndGet() > SIGN_INS_PER_CLIENT_PER_HOUR) {
            record(PasskeyOutcome.THROTTLED)
            return null
        }
        return RequestOptions(challenge = base64(challenge(null)), rpId = rpId)
    }

    /** The account the browser's answer to [requestOptions] signs in, or null. */
    suspend fun signIn(credential: Map<String, Any?>): Account? =
        try {
            val data = manager.parseAuthenticationResponseJSON(json(credential))
            val challenge = data.collectedClientData?.challenge?.value
            val row = data.credentialId?.let { store.find(it) }
            val handle = data.userHandle
            when {
                challenge == null || !store.consumeChallenge(challenge, null, clock.instant()) -> {
                    null
                }

                row == null || (handle != null && !handle.contentEquals(row.userHandle)) -> {
                    null
                }

                else -> {
                    manager.verify(data, AuthenticationParameters(server(challenge), credentialRecord(row.passkey), null, true, true))
                    val authenticator = requireNotNull(data.authenticatorData)
                    store.signedIn(row.id, authenticator.signCount, authenticator.isFlagBS, clock.instant())
                }
            }
        } catch (e: WebAuthnException) {
            logger.info { "Passkey sign-in refused: ${e.javaClass.simpleName}" }
            null
        }.also { record(if (it == null) PasskeyOutcome.SIGN_IN_REFUSED else PasskeyOutcome.SIGNED_IN) }

    private suspend fun challenge(account: Account?): ByteArray =
        bytes().also { store.issueChallenge(it, account?.id, clock.instant().plus(CHALLENGE_VALIDITY)) }

    private fun server(challenge: ByteArray) =
        ServerProperty
            .builder()
            .origin(origin)
            .rpId(rpId)
            .challenge(DefaultChallenge(challenge))
            .build()

    private fun credentialRecord(passkey: StoredPasskey) =
        CredentialRecordImpl(
            NoneAttestationStatement(),
            passkey.flags.uvInitialized,
            passkey.flags.backupEligible,
            passkey.flags.backupState,
            passkey.signCount,
            credentials.convert(passkey.credential),
            null,
            null,
            null,
            passkey.transports.map(AuthenticatorTransport::create).toSet()
        )

    private fun record(outcome: PasskeyOutcome) {
        metrics.record(outcome)
        logger.info { "Passkey: ${outcome.tag}" }
    }

    /** `PublicKeyCredential.toJSON()` as the browser sent it, back to the text webauthn4j parses. */
    private fun json(credential: Map<String, Any?>): String = converter.jsonMapper.writeValueAsString(credential)

    private fun RegisterOutcome.recorded() = also { record(metric) }

    private fun bytes() = ByteArray(CHALLENGE_BYTES).also(random::nextBytes)

    companion object {
        /** A phone, a laptop, a security key and a few more, as for push subscriptions. */
        const val MAX_PASSKEYS = 10L
        const val TIMEOUT_MILLIS = 300_000L
        val CHALLENGE_VALIDITY: Duration = Duration.ofMillis(TIMEOUT_MILLIS)
        private const val RP_NAME = "Event Junkie"
        private const val CHALLENGE_BYTES = 32
        private const val SIGN_INS_PER_CLIENT_PER_HOUR = 30
        private const val MAX_TRACKED_CLIENTS = 10_000L

        /** ES256 first, which every platform authenticator makes; RS256 for Windows Hello. */
        private val COSE_ALGORITHMS = listOf(COSEAlgorithmIdentifier.ES256, COSEAlgorithmIdentifier.RS256)
        private val ALGORITHMS = COSE_ALGORITHMS.map { PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, it) }

        /** The same algorithms, as the options JSON names them. */
        val CREDENTIAL_PARAMETERS = COSE_ALGORITHMS.map { CredentialParameter(it.value.toInt()) }

        private fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

enum class RegisterOutcome(
    val metric: PasskeyOutcome
) {
    REGISTERED(PasskeyOutcome.REGISTERED),

    /** A bad answer, a wrong origin, or a used or expired challenge. A 400. */
    INVALID(PasskeyOutcome.REGISTER_REFUSED),

    /** The account has [Passkeys.MAX_PASSKEYS] already. A 409. */
    LIMIT(PasskeyOutcome.LIMIT)
}

/** `PublicKeyCredentialCreationOptionsJSON` (WebAuthn Level 3), which `PublicKeyCredential.parseCreationOptionsFromJSON()` takes. */
data class CreationOptions(
    val rp: RelyingParty,
    val user: PasskeyUser,
    val challenge: String,
    val excludeCredentials: List<CredentialDescriptor>,
    val pubKeyCredParams: List<CredentialParameter> = Passkeys.CREDENTIAL_PARAMETERS,
    val timeout: Long = Passkeys.TIMEOUT_MILLIS,
    val authenticatorSelection: AuthenticatorSelection = AuthenticatorSelection(),
    val attestation: String = "none"
)

/** `PublicKeyCredentialRequestOptionsJSON`. No `allowCredentials`: the passkey is discoverable, so the browser offers the account's. */
data class RequestOptions(
    val challenge: String,
    val rpId: String,
    val timeout: Long = Passkeys.TIMEOUT_MILLIS,
    val userVerification: String = "required"
)

data class RelyingParty(
    val id: String,
    val name: String
)

/** [id] is the random user handle, base64url. [name] is what the passkey manager lists, the address. */
data class PasskeyUser(
    val id: String,
    val name: String,
    val displayName: String
)

data class CredentialDescriptor(
    val id: String,
    val type: String = "public-key"
)

data class CredentialParameter(
    val alg: Int,
    val type: String = "public-key"
)

data class AuthenticatorSelection(
    val residentKey: String = "required",
    val requireResidentKey: Boolean = true,
    val userVerification: String = "required"
)
