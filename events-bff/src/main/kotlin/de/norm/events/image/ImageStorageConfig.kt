package de.norm.events.image

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * Builds the S3 client the serving route reads through, or does not.
 *
 * **A null bean rather than a conditional one**, mirroring the importer's [ImageStorageConfig] twin:
 * without credentials there is nothing to build, and a context that refused to start would make
 * running the BFF locally depend on having bucket access. The route answers 503 while the client is
 * null, which is the truth — the images exist and this instance cannot reach them.
 *
 * `@EnableConfigurationProperties` rather than a scan, because this is the only binding class the
 * BFF has and a scan would be a wider change than one module needs.
 */
@Configuration
@EnableConfigurationProperties(ImageServingProperties::class)
class ImageStorageConfig {
    @Bean
    fun s3AsyncClient(properties: ImageServingProperties): S3AsyncClient? {
        if (!properties.storage.isConfigured()) {
            logger.info { "No object storage credentials; cached images cannot be served by this instance" }
            return null
        }

        return S3AsyncClient
            .builder()
            .endpointOverride(URI(properties.storage.endpoint))
            .region(Region.of(properties.storage.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.storage.accessKey, properties.storage.secretKey)
                )
            )
            // Path style, because Hetzner's endpoint does not serve virtual-host buckets. The SDK
            // defaults to `bucket.endpoint`, which resolves to nothing here and fails as a DNS error
            // rather than as a configuration one.
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
    }
}
