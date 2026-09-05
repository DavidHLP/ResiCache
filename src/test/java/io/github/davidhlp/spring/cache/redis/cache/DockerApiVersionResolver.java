package io.github.davidhlp.spring.cache.redis.cache;



import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Dynamically negotiates the Docker Engine API version so that the bundled
 * docker-java client speaks a version the local daemon accepts.
 *
 * <p>Problem: Testcontainers' bundled docker-java ships with a default API
 * version that may be rejected by the daemon — too old (daemon rejects,
 * "client version X is too old") or too new (daemon doesn't know that
 * version yet). Previously this was papered over by pinning a specific
 * Testcontainers BOM version, but that just shifts the incompatibility to a
 * different daemon. This resolver eliminates the hardcoding by asking the
 * daemon itself for the oldest API version it supports and configuring
 * docker-java to use exactly that.
 *
 * <p>Detection: runs {@code docker version --format '{{.Server.MinAPIVersion}}'}
 * — the daemon's own minimum is always within its supported range, so it is
 * universally compatible regardless of daemon version.
 *
 * <p>Application: sets the detected version via the {@code api.version} system
 * property (lowercase) — the channel docker-java's shaded
 * {@code DefaultDockerClientConfig} reads via
 * {@code overrideDockerPropertiesWithSystemProperties}, and the highest
 * precedence in its config-merge chain. Must run <em>before</em> Testcontainers
 * initialises its Docker client; {@code DockerApiVersionLauncherSessionListener}
 * guarantees this at JUnit Platform session start, ahead of any
 * {@code @Container} start.
 *
 * <p>Graceful degradation: if the {@code docker} CLI is unavailable, or
 * detection fails for any reason, the resolver does nothing — Testcontainers'
 * own fallback / {@code disabledWithoutDocker} semantics apply.
 */
final class DockerApiVersionResolver {

    private static final String ENV_VAR = "DOCKER_API_VERSION";

    /**
     * The system-property key that docker-java's shaded
     * {@code DefaultDockerClientConfig} reads via
     * {@code overrideDockerPropertiesWithSystemProperties}. Despite the env-var
     * being {@code DOCKER_API_VERSION}, the properties key is the lowercase
     * {@code api.version} — not {@code docker.api.version} or
     * {@code DOCKER_API_VERSION}.
     */
    private static final String SYS_PROP = "api.version";

    private DockerApiVersionResolver() {
    }

    /**
     * If no API version is already set explicitly, detect the daemon's minimum
     * and apply it. Safe to call multiple times; idempotent once applied.
     */
    static void negotiateIfNeeded() {
        if (System.getProperty(SYS_PROP) != null
                || System.getenv(ENV_VAR) != null) {
            return;
        }
        String minApiVersion = detectMinApiVersion();
        if (minApiVersion != null) {
            apply(minApiVersion);
        }
    }

    /**
     * Query the Docker daemon for its minimum supported API version.
     *
     * @return the version string (e.g. {@code "1.40"}), or {@code null} if the
     *         daemon is unreachable or the output is unparseable.
     */
    private static String detectMinApiVersion() {
        Process process;
        try {
            process = new ProcessBuilder(
                    "docker", "version", "--format", "{{.Server.MinAPIVersion}}")
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            return null;
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line != null) {
                    line = line.trim();
                }
                if (line != null && line.matches("\\d+\\.\\d+")) {
                    return line;
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        } finally {
            process.destroyForcibly();
        }
        return null;
    }

    /**
     * Push the version through the system-property channel that docker-java's
     * shaded {@code DefaultDockerClientConfig} reads via
     * {@code overrideDockerPropertiesWithSystemProperties}. The property key
     * is {@code api.version} (lowercase) — not {@code docker.api.version} or
     * {@code DOCKER_API_VERSION}. System properties have the highest
     * precedence in the config-merge chain.
     */
    private static void apply(String apiVersion) {
        System.setProperty(SYS_PROP, apiVersion);
    }
}
