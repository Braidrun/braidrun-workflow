package com.fartech.agents.runner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/** Export only after Gradle has configured the existing Test runtime (no early configuration resolution). */
class RunnerFixtureClasspathTest {
    @Test fun `export resolved test-only launcher classpath`() {
        val classpath = requireNotNull(System.getProperty("runner.test.runtimeClasspath"))
        assertTrue(classpath.contains("ktor-client-mock"))
        val target = Path.of("build/runner-http-fixture.classpath")
        Files.createDirectories(target.parent); Files.writeString(target, classpath)
    }
}
