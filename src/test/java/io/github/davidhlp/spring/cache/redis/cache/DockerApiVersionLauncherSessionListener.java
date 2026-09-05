package io.github.davidhlp.spring.cache.redis.cache;



import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Runs {@link DockerApiVersionResolver} once at the very start of the JUnit
 * Platform session — before any test class is loaded and before Testcontainers
 * initialises its Docker client singleton.
 *
 * <p>Registered via {@code META-INF/services} so it fires for every test
 * execution path (Surefire, IDE, CLI), independent of which base class an
 * integration test happens to extend. Without this, only tests that inherit
 * a specific base class would trigger the negotiation, and Testcontainers'
 * singleton client could be cached as failed before the resolver runs.
 */
public final class DockerApiVersionLauncherSessionListener
        implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        DockerApiVersionResolver.negotiateIfNeeded();
    }
}
