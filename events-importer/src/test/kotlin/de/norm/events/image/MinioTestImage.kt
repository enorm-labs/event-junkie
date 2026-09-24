package de.norm.events.image

import org.testcontainers.utility.DockerImageName

/**
 * The S3 server the image tests run against, pinned by digest. MinIO stopped serving its own images
 * anonymously, and Chainguard publishes only `latest` (#1855). The same pin is in the other Boot
 * module and in `compose.yaml`.
 */
val MINIO_TEST_IMAGE: DockerImageName =
    DockerImageName
        .parse("cgr.dev/chainguard/minio@sha256:bd014394a80898e68c149f2311fdf8d5a2c2f3bb2c33b9327ae6d02b4b065ae1")
        .asCompatibleSubstituteFor("minio/minio")
