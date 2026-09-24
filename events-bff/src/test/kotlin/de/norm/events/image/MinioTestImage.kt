package de.norm.events.image

import org.testcontainers.utility.DockerImageName

/**
 * The S3 server the image tests run against: our unmodified GHCR copy of Chainguard's MinIO, pinned by
 * digest, because MinIO no longer serves its own images and Chainguard keeps only `latest` (#1855).
 * `mirror-test-images.yml` makes the copy; the same pin is in the other Boot module and `compose.yaml`.
 */
val MINIO_TEST_IMAGE: DockerImageName =
    DockerImageName
        .parse("ghcr.io/enorm-labs/minio@sha256:45f553211dd65f13be15ed9afc3b5b1f7622d45a0d11b5646572d3e11c78d9fb")
        .asCompatibleSubstituteFor("minio/minio")
