package com.tj.crypto.integration.mysql;

import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.MySQLContainer;

/** Pinned MySQL image with explicit Testcontainers compatibility metadata. */
public final class MySqlTestImage {
    private static final DockerImageName PINNED = DockerImageName.parse(
            "mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b")
            .asCompatibleSubstituteFor("mysql");

    private MySqlTestImage() {}

    public static DockerImageName pinned() {
        return PINNED;
    }

    public static MySQLContainer<?> container() {
        return new MySQLContainer<>(PINNED)
                .withCommand("--log-bin-trust-function-creators=1");
    }
}
