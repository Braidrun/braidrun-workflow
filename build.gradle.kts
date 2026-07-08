plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
    application
    `maven-publish`
}

repositories {
    mavenCentral()
    mavenLocal()
    google()
    gradlePluginPortal()
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("https://maven.aliyun.com/repository/public") }
}

application {
    mainClass.set("com.fartech.agents.cli.BraidrunWorkflowCliKt")
}

distributions {
    main {
        contents {
            val cliSlf4jNop = (dependencies.create("org.slf4j:slf4j-nop:2.0.17") as org.gradle.api.artifacts.ExternalModuleDependency).apply {
                exclude(mapOf("group" to "org.slf4j", "module" to "slf4j-api"))
            }
            from(configurations.detachedConfiguration(cliSlf4jNop)) {
                into("lib")
            }
        }
    }
}

tasks.named<org.gradle.jvm.application.tasks.CreateStartScripts>("startScripts") {
    doLast {
        unixScript.writeText(
            """
            #!/bin/sh
            set -e
            SCRIPT_DIR=${'$'}(dirname "${'$'}0")
            APP_HOME=${'$'}(cd "${'$'}SCRIPT_DIR/.." && pwd -P)
            if [ -n "${'$'}{JAVA_HOME:-}" ]; then
              JAVA_EXE="${'$'}JAVA_HOME/bin/java"
            else
              JAVA_EXE="java"
            fi
            exec "${'$'}JAVA_EXE" -cp "${'$'}APP_HOME/lib/*" com.fartech.agents.cli.BraidrunWorkflowCliKt "${'$'}@"
            """.trimIndent() + "\n"
        )
        unixScript.setExecutable(true)
        runCatching {
            ProcessBuilder("xattr", "-c", unixScript.absolutePath)
                .inheritIO()
                .start()
                .waitFor()
        }
        windowsScript.writeText(
            """
            @echo off
            set APP_HOME=%~dp0..
            if defined JAVA_HOME (
              set JAVA_EXE=%JAVA_HOME%\bin\java.exe
            ) else (
              set JAVA_EXE=java.exe
            )
            "%JAVA_EXE%" -cp "%APP_HOME%\lib\*" com.fartech.agents.cli.BraidrunWorkflowCliKt %*
            exit /b %ERRORLEVEL%
            """.trimIndent().replace("\n", "\r\n") + "\r\n"
        )
    }
}

// Inherited from the braidrun root build when this lived there as a module:
// -Xno-source-debug-extension avoids JDWP SDE parsing assertion noise.
// -jvm-default=disable restores pre-Kotlin-2.2 DefaultImpls interface
// compilation, which KMongo's ClassMappingTypeService codec resolution
// relies on; without it codec setup throws NPE/ClassCastException.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xno-source-debug-extension",
            "-jvm-default=disable"
        )
    }
}

// Koog 1.0.0 ships two streams (see release notes):
//   - STABLE (`1.0.0`):  agents-core / agents-features-memory / -snapshot /
//                        -tokenizer / -trace, embeddings-*, prompt-*, rag-base,
//                        a2a-*, koog-agents umbrella.
//   - BETA   (`1.0.0-beta-preview7`):  agents-features-longterm-memory,
//                        agents-features-opentelemetry, rag-vector, agents-planner.
// We pull the umbrella at STABLE so most APIs are locked; the few beta-stream
// modules we depend on (LTM, Langfuse via OpenTelemetry, vector RAG) ride the
// preview7 train until they get promoted.
val koogVersion = "1.0.0"
val koogBetaVersion = "1.0.0-beta-preview7"
val ktorVersion = "3.4.1"
val vertxVersion = "4.5.18"

dependencies {
    // Kotlinx Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    // kotlinx-datetime: pinned to 0.4.0 because KotlinxDateTimeModule uses kotlinx.datetime.Instant
    // which was removed in 0.7.x (replaced by kotlin.time.Instant in Kotlin 2.1+).
    // Koog transitively requests ≥0.7.1 via aws.smithy.kotlin, but at runtime the
    // APIs it exercises are still present in 0.4.0.
    implementation("org.jetbrains.kotlinx:kotlinx-datetime") {
        version {
            strictly("0.4.0")
        }
    }

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Keep compatibility with shared commons helpers that expose Vert.x JsonObject/JsonArray.
    implementation("io.vertx:vertx-core:$vertxVersion")

    // YAML parsing for workflow definitions
    implementation("com.charleskorn.kaml:kaml:0.71.0")

    // Koog agents, using the Ktor versions resolved by Koog itself.
    implementation("ai.koog:koog-agents:${koogVersion}")
    // agents-ext (BETA stream) — homes built-in tools that 0.8.0 shipped under
    // agents-core: ExitTool, ReadFileTool, ListDirectoryTool, EditFileTool,
    // WriteFileTool, ExecuteShellCommandTool, etc. The 1.0.0 reorg moved them
    // out of the stable umbrella; we add the dependency explicitly so
    // ToolRegistryBuilder.kt can keep registering them.
    implementation("ai.koog:agents-ext:${koogBetaVersion}")
    // agents-mcp (BETA stream) — McpToolRegistryProvider + stdio/SSE/Streamable
    // HTTP transports for talking to external MCP servers. Lives outside the
    // koog-agents:1.0.0 umbrella because the SDK upgrade (0.8.1 → 0.11.1)
    // shipped on the beta train.
    implementation("ai.koog:agents-mcp:${koogBetaVersion}")
    // Core A2A server library — A2A modules live on the BETA stream in 1.0.0.
    api("ai.koog:a2a-server:${koogBetaVersion}")
    api("ai.koog:agents-features-a2a-server:${koogBetaVersion}")
    api("ai.koog:agents-features-a2a-client:${koogBetaVersion}")

    // HTTP JSON-RPC transport (most common) — also BETA stream.
    api("ai.koog:a2a-transport-server-jsonrpc-http:${koogBetaVersion}")
    api("ai.koog:a2a-transport-client-jsonrpc-http:${koogBetaVersion}")

    // Ktor server engine (choose one that fits your needs)
    api("io.ktor:ktor-server-cio:${ktorVersion}")
    api("io.ktor:ktor-client-cio:${ktorVersion}")

    // OpenAI client for OpenRouter
    implementation("com.aallam.openai:openai-client:3.6.0")

    // Kotlinx IO for Path support in Koog attachments DSL
    implementation("org.jetbrains.kotlinx:kotlinx-io-core-jvm:0.3.1")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.jetbrains.kotlinx:kotlinx-datetime") {
        version {
            strictly("0.4.0")
        }
    }
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("io.github.microutils:kotlin-logging:3.0.5")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.9")
    implementation(kotlin("reflect"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.2")

    // Apache POI for Office documents (Word, Excel, PowerPoint)
    implementation("org.apache.poi:poi:5.4.0")
    implementation("org.apache.poi:poi-ooxml:5.4.0")
    implementation("org.apache.poi:poi-scratchpad:5.4.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.2")
    implementation("org.apache.commons:commons-csv:1.10.0")
    implementation("org.commonmark:commonmark:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.22.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.22.0")

    // Apple plist parser
    implementation("com.googlecode.plist:dd-plist:1.26")

    // Lettuce for Redis client
    implementation("io.lettuce:lettuce-core:6.3.2.RELEASE")


    // MongoDB (KMongo) support used by shared agent storage/state code.
    implementation("org.litote.kmongo:kmongo:4.11.0")

    // Redis (Jedis) for CLI — used by AgentDatabase.kt for blocking Redis access
    implementation("redis.clients:jedis:5.2.0")

    // Jsoup for HTML parsing and web scraping
    implementation("org.jsoup:jsoup:1.18.1")

    // Microsoft Playwright for browser automation
    implementation("com.microsoft.playwright:playwright:1.49.0")

    // Jakarta Mail for email tools (SMTP/IMAP)
    implementation("com.sun.mail:jakarta.mail:2.0.2")

    // SQLite JDBC driver for database tools
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.mysql:mysql-connector-j:9.3.0")

    // Koog RAG: embeddings + vector storage
    // Note: `vector-storage` was renamed to `rag-vector` in Koog 0.8.0.
    // `rag-vector` rides the BETA stream in 1.0.0 (still preview7); the rest
    // of the RAG surface (`rag-base`, `embeddings-*`) is in STABLE.
    implementation("ai.koog:embeddings-base:${koogVersion}")
    implementation("ai.koog:embeddings-llm:${koogVersion}")
    implementation("ai.koog:rag-vector:${koogBetaVersion}")

    // Koog LLM client modules (1.0.0 stripped these out of the koog-agents
    // umbrella so apps pay only for the providers they use). We use:
    //   STABLE (1.0.0):
    //     openai-client, openai-client-base (AbstractOpenAILLMClient base),
    //     anthropic-client (used for Claude direct + via OpenRouter mirror),
    //     openrouter-client, bedrock-client (future), ollama-client.
    //   BETA (1.0.0-beta-preview7):
    //     google-client (Gemini), deepseek-client (DeepSeek V3.1/V4),
    //     mistralai-client (Mistral large), dashscope-client (Qwen direct),
    //     litert-client (Google LiteRT local — new in 1.0.0; opt-in).
    // The bundled `prompt-executor-llms-all` BoM is BETA-only, so we stay on
    // per-provider deps to keep the stable umbrella consistent.
    implementation("ai.koog:prompt-executor-openai-client:${koogVersion}")
    implementation("ai.koog:prompt-executor-openai-client-base:${koogVersion}")
    implementation("ai.koog:prompt-executor-anthropic-client:${koogVersion}")
    implementation("ai.koog:prompt-executor-openrouter-client:${koogVersion}")
    implementation("ai.koog:prompt-executor-bedrock-client:${koogVersion}")
    implementation("ai.koog:prompt-executor-ollama-client:${koogVersion}")
    implementation("ai.koog:prompt-executor-google-client:${koogBetaVersion}")
    implementation("ai.koog:prompt-executor-deepseek-client:${koogBetaVersion}")
    implementation("ai.koog:prompt-executor-mistralai-client:${koogBetaVersion}")
    implementation("ai.koog:prompt-executor-dashscope-client:${koogBetaVersion}")
    // Phase-11 (Koog 1.0.0) — new: LiteRT local model client. Lets workflow
    // authors run small on-device Google models without an external API.
    implementation("ai.koog:prompt-executor-litert-client:${koogBetaVersion}")
    // STABLE LLM executor surface (CachedPromptExecutor + MultiLLMPromptExecutor).
    // Koog 1.0.0 merged `prompt-executor-llms` into `prompt-executor-model`;
    // MultiLLMPromptExecutor / RoutingLLMPromptExecutor / LLMClientRouter now
    // live under `ai.koog.prompt.executor.llms.*` but are published in
    // prompt-executor-model. The cached executor (CachedPromptExecutor) keeps
    // its own artifact.
    implementation("ai.koog:prompt-executor-model:${koogVersion}")
    implementation("ai.koog:prompt-executor-cached:${koogVersion}")
    // Prompt-cache backends: in-memory (STABLE 1.0.0) + Redis (BETA preview7).
    implementation("ai.koog:prompt-cache-files:${koogVersion}")
    implementation("ai.koog:prompt-cache-model:${koogVersion}")
    implementation("ai.koog:prompt-cache-redis:${koogBetaVersion}")
    // KoogHttpClient pluggable factory + Ktor-backed default (auto-discovered
    // via ServiceLoader in HttpClientFactoryResolver — required at runtime so
    // LLM clients constructed without an explicit factory work out of the box).
    implementation("ai.koog:http-client-core:${koogVersion}")
    implementation("ai.koog:http-client-ktor:${koogVersion}")

    // Koog observability + token accounting features (Tier-1 adoption, 2026-04):
    //   - agents-features-tokenizer: client-side token estimation facility exposed
    //     via `AIAgentContext.tokenizer()`; used for pre-request budget gating.
    //   - agents-features-trace: dev-time execution trace writer (file / log / SSE
    //     remote). Installed conditionally at agent bootstrap when the parameters
    //     declare `tracing_enabled=true` (defaults: off).
    //   - prompt-tokenizer: provides `SimpleRegexBasedTokenizer` / `Tokenizer` API
    //     consumed by `agents-features-tokenizer`.
    implementation("ai.koog:agents-features-tokenizer:${koogVersion}")
    implementation("ai.koog:agents-features-trace:${koogVersion}")
    implementation("ai.koog:prompt-tokenizer:${koogVersion}")

    // Koog Tier-2 features (2026-04):
    //   - agents-features-longterm-memory (BETA): semantic retrieval over past
    //     conversations ("the assistant remembered what I asked last week"),
    //     opt-in per workflow via `long_term_memory_enabled=true`.
    //   - agents-features-memory (STABLE): structured-facts memory keyed by
    //     subject/concept/scope, opt-in via `agent_memory_enabled=true`.
    //     Plain text store by default; optional AES-256-GCM at rest.
    //   - prompt-processor (STABLE): `ResponseProcessor` (constructor arg on
    //     AIAgent) — the hook weaker providers need to correct malformed
    //     tool-call JSON before the agent loop sees it; also hosts the bundled
    //     `LLMBasedToolCallFixProcessor`.
    //   - rag-base (STABLE): storage interfaces + `JVMFileSystemProvider`
    //     already in use by `AssistantDocsKnowledgeBaseService`; bumped
    //     explicitly so the long-term memory + local file memory providers
    //     resolve.
    //   - agents-features-snapshot (STABLE): `Persistence` feature for
    //     checkpoint/restore — required for the `runFromCheckpoint` flow
    //     adopted in the Koog 1.0.0 audit (Phase 11).
    //   - agents-features-opentelemetry (BETA): OpenTelemetry feature with
    //     Langfuse / Weave / DataDog exporters; now multiplatform in 1.0.0
    //     so the JVM-only extensions live in a separate `-jvm` artifact
    //     (resolved transitively via -opentelemetry).
    implementation("ai.koog:agents-features-longterm-memory:${koogBetaVersion}")
    implementation("ai.koog:agents-features-memory:${koogVersion}")
    implementation("ai.koog:agents-features-snapshot:${koogVersion}")
    implementation("ai.koog:agents-features-opentelemetry:${koogBetaVersion}")
    implementation("ai.koog:prompt-processor:${koogVersion}")
    implementation("ai.koog:rag-base:${koogVersion}")

    // MCP SDK 0.11.1 — split into client + server artifacts (the umbrella
    // `kotlin-sdk` jar is metadata-only). We host an MCP server **and** call
    // external MCP servers via `AgentMcpUtils`, so we need both:
    //   - `kotlin-sdk-server-jvm` — `Server`, `ServerOptions`, `ServerCapabilities`,
    //     `StdioServerTransport` used by AgentMcpServer to expose tool groups.
    //   - `kotlin-sdk-client-jvm` — `StdioClientTransport`, `SseClientTransport`,
    //     `WebSocketClientTransport`, `StreamableHttpClientTransport` used by
    //     AgentMcpUtils to consume external MCP servers.
    // Bumped 0.8.1 → 0.11.1 alongside the Koog 1.0.0 upgrade — the koog-agents
    // 1.0.0 release advertises Streamable HTTP as the primary transport.
    implementation("io.modelcontextprotocol:kotlin-sdk-server-jvm:0.11.1")
    implementation("io.modelcontextprotocol:kotlin-sdk-client-jvm:0.11.1")

    // Ktor client pieces used by local HttpAccess
    implementation("io.ktor:ktor-client-core:${ktorVersion}")
    implementation("io.ktor:ktor-client-okhttp:${ktorVersion}")
    implementation("io.ktor:ktor-client-content-negotiation:${ktorVersion}")
    implementation("io.ktor:ktor-serialization-jackson:${ktorVersion}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${ktorVersion}")

    // Docker client for sandbox execution (Phase 3b: Docker-per-step)
    implementation("com.github.docker-java:docker-java-core:3.7.1")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.7.1")

    // Console output utilities
    implementation("org.jline:jline:3.30.6")
    implementation("org.jline:jline-terminal-jansi:3.30.6")
}

group = "com.fartech.braidrun"
version = "1.0.4"
description = "braidrun-workflow"

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Implementation-Title" to "braidrun-workflow",
            "Implementation-Version" to project.version.toString()
        )
    }
}

java.sourceCompatibility = JavaVersion.VERSION_21

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        java {
            setSrcDirs(listOf("src/main/kotlin"))
        }
        resources {
            setSrcDirs(listOf("src/main/resources"))
        }
    }
    test {
        java {
            setSrcDirs(listOf("src/test/kotlin"))
        }
    }
}
tasks.test {
    useJUnitPlatform()
    maxParallelForks = findProperty("test.maxParallelForks")
        ?.toString()
        ?.toIntOrNull()
        ?.coerceAtLeast(1)
        ?: minOf(2, Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
    systemProperty("braidrun.quietConsole", "true")
    reports.html.required.set(false)
    reports.junitXml.required.set(true)
    // Surface failing test names + stack traces on the console (CI logs only
    // show "N failed" otherwise — the details would be buried in the HTML
    // report artifact).
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
        showCauses = true
        showExceptions = true
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("braidrun-workflow")
                description.set(
                    "Kotlin/JVM library for building and running LLM agent workflows, built on Koog"
                )
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}
