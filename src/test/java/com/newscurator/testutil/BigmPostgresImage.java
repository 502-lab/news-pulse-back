package com.newscurator.testutil;

import java.nio.file.Path;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

/**
 * V9 migration requires pg_bigm extension.
 * All integration tests that run Flyway must use this image.
 * The "postgres-bigm-test" image is built once and cached by Docker layer cache.
 */
public final class BigmPostgresImage {

    public static final DockerImageName NAME;

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            String resolved = new ImageFromDockerfile("postgres-bigm-test", false)
                    .withDockerfile(Path.of("src/test/resources/postgres-bigm/Dockerfile"))
                    .get();
            NAME = DockerImageName.parse(resolved).asCompatibleSubstituteFor("postgres");
        } else {
            NAME = DockerImageName.parse("postgres:16-alpine");
        }
    }

    private BigmPostgresImage() {}
}
