package de.norm.events.image

import io.github.oshai.kotlinlogging.KotlinLogging
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
 * Builds the S3 client, or does not.
 *
 * **A null bean rather than a conditional one.** Without credentials there is nothing to build, and
 * a context that refuses to start would make running the importer locally depend on having bucket
 * access. [ImageStorage] treats null as "store nothing", which is the same state
 * `app.images.fetch-enabled: false` leaves the module in.
 */
@Configuration
class ImageStorageConfig {
    @Bean
    fun s3AsyncClient(properties: ImageStorageProperties): S3AsyncClient? {
        if (!properties.isConfigured()) {
            logger.info { "No object storage credentials; cached images will be recorded but not stored" }
            return null
        }

        return S3AsyncClient
            .builder()
            .endpointOverride(URI(properties.endpoint))
            .region(Region.of(properties.region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey, properties.secretKey))
            )
            // Path style, because Hetzner's endpoint does not serve virtual-host buckets. The SDK
            // defaults to `bucket.endpoint`, which resolves to nothing here and fails as a DNS
            // error rather than as a configuration one.
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
    }
}
