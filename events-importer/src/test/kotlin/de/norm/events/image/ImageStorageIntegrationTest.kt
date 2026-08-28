package de.norm.events.image

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MinIOContainer
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest

/**
 * [ImageStorage] against a real S3 API.
 *
 * **A container rather than a mock, deliberately** (ADR-019 §2.9). Key encoding, content type and
 * the path-style addressing Hetzner requires are exactly the things a mock agrees with and a server
 * does not, and they would otherwise surface only after a deploy.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImageStorageIntegrationTest {
    private val minio = MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z")

    private lateinit var client: S3AsyncClient
    private val properties = ImageStorageProperties(bucket = "images", prefix = "staging")

    @BeforeAll
    fun start(): Unit =
        runBlocking {
            minio.start()
            client =
                ImageStorageConfig().s3AsyncClient(
                    properties.copy(endpoint = minio.s3URL, accessKey = minio.userName, secretKey = minio.password)
                )!!
            client.createBucket(CreateBucketRequest.builder().bucket("images").build()).await()
        }

    @AfterAll
    fun stop() = minio.stop()

    private fun storage() = ImageStorage(client, properties)

    @Test
    fun `stores an original where its key says it does`() =
        runTest {
            val bytes = byteArrayOf(1, 2, 3, 4)

            val key = storage().storeOriginal("abc123", "image/png", bytes)

            key shouldBe "staging/originals/abc123"
            fetch(key!!) shouldBe bytes.toList()
        }

    @Test
    fun `stores a derivative under the hash, so one image's family shares a prefix`() =
        runTest {
            // The sweep lists by prefix to find everything belonging to one original. A flat layout
            // would make it reconstruct the width and format set instead.
            val key = storage().storeDerivative("abc123", width = 192, format = "avif", bytes = byteArrayOf(7))

            key shouldBe "staging/derived/abc123/192.avif"
            fetch(key!!) shouldBe listOf<Byte>(7)
        }

    @Test
    fun `reports a failure as null rather than throwing`() =
        runTest {
            // A bucket that does not exist stands in for every permission, signature and transport
            // fault. One bad object must not stop a pass.
            val wrongBucket = ImageStorage(client, properties.copy(bucket = "no-such-bucket"))

            wrongBucket.storeOriginal("abc123", "image/png", byteArrayOf(1)).shouldBeNull()
        }

    @Test
    fun `is enabled when it has a client`() {
        storage().isEnabled() shouldBe true
    }

    private suspend fun fetch(key: String): List<Byte> =
        client
            .getObject(
                GetObjectRequest
                    .builder()
                    .bucket("images")
                    .key(key)
                    .build(),
                AsyncResponseTransformer.toBytes()
            ).await()
            .asByteArray()
            .toList()
}
