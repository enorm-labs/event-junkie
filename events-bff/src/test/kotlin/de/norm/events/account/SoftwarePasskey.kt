package de.norm.events.account

import com.webauthn4j.data.AuthenticatorSelectionCriteria
import com.webauthn4j.data.PublicKeyCredentialCreationOptions
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialRequestOptions
import com.webauthn4j.data.PublicKeyCredentialRpEntity
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.PublicKeyCredentialUserEntity
import com.webauthn4j.data.ResidentKeyRequirement
import com.webauthn4j.data.UserVerificationRequirement
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor
import com.webauthn4j.test.client.ClientPlatform
import tools.jackson.databind.JsonNode
import java.util.Base64
import java.util.UUID

/**
 * A browser with a platform authenticator, for the flow test: it takes the BFF's options JSON and answers with what
 * `PublicKeyCredential.toJSON()` would send. Backed by webauthn4j's emulator, which keeps the passkeys it makes.
 */
class SoftwarePasskey(
    origin: String = "https://event-junkie.de"
) {
    /** A model of its own, as a real authenticator has, so a test can see that the stored row drops it. */
    private val client = ClientPlatform(Origin(origin), WebAuthnAuthenticatorAdaptor(NoneAttestationAuthenticator(AAGUID(UUID.randomUUID()))))

    fun create(options: JsonNode): String {
        val credential =
            client.create(
                PublicKeyCredentialCreationOptions(
                    PublicKeyCredentialRpEntity(options["rp"]["id"].asString(), options["rp"]["name"].asString()),
                    PublicKeyCredentialUserEntity(bytes(options["user"]["id"]), options["user"]["name"].asString(), options["user"]["displayName"].asString()),
                    DefaultChallenge(options["challenge"].asString()),
                    listOf(PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
                    null,
                    emptyList(),
                    AuthenticatorSelectionCriteria(null, true, ResidentKeyRequirement.REQUIRED, UserVerificationRequirement.REQUIRED),
                    null,
                    null
                )
            )
        val response = credential.response!!
        return json(
            credential.rawId!!,
            """"clientDataJSON":"${b64(response.clientDataJSON)}","attestationObject":"${b64(response.attestationObject)}","transports":["internal"]"""
        )
    }

    fun get(options: JsonNode): String {
        val credential =
            client.get(
                PublicKeyCredentialRequestOptions(
                    DefaultChallenge(options["challenge"].asString()),
                    null,
                    options["rpId"].asString(),
                    null,
                    UserVerificationRequirement.REQUIRED,
                    null
                )
            )
        val response = credential.response!!
        return json(
            credential.rawId!!,
            """"clientDataJSON":"${b64(response.clientDataJSON)}","authenticatorData":"${b64(response.authenticatorData)}",""" +
                """"signature":"${b64(response.signature)}","userHandle":"${b64(response.userHandle!!)}""""
        )
    }

    private fun json(
        rawId: ByteArray,
        response: String
    ) = """{"id":"${b64(rawId)}","rawId":"${b64(rawId)}","type":"public-key","response":{$response},"clientExtensionResults":{}}"""

    private fun bytes(node: JsonNode) = Base64.getUrlDecoder().decode(node.asString())

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
