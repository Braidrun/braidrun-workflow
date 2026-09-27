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
            val cliSlf4jNop = (dependencies.create("org.slf4j:slf4j-nop:2.0.20") as ExternalModuleDependency).apply {
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

// Koog publishes every module on exactly ONE of two release streams, and each
// artifact only exists at its own stream's version (requesting a stable module
// at `-beta` or vice versa fails resolution, or silently resolves through a
// transitive edge). Check maven-metadata.xml before moving a module between
// the two constants below.
//   - STABLE (`koogVersion` = 1.3.0): koog-agents umbrella, agents-features-
//     snapshot / -tokenizer / -trace / -opentelemetry, embeddings-*,
//     rag-base, http-client-*, prompt-processor, prompt-tokenizer,
//     prompt-cache-files / -model, prompt-executor-model / -cached, and the
//     openai / openai-base / anthropic / openrouter / bedrock / ollama clients.
//   - BETA (`koogBetaVersion` = 1.3.0-beta): agents-ext, agents-mcp, a2a-*,
//     agents-features-a2a-server, agents-features-longterm-memory,
//     prompt-cache-redis, and the google / deepseek / mistralai clients.
// Not used on purpose: `ai.koog:skills` (beta-only, Agent Skills catalog).
// Braidrun's skill system stays in-house (SkillManager; see docs/SKILLS.md).
val koogVersion = "1.3.0"
val koogBetaVersion = "1.3.0-beta"
val ktorVersion = "3.6.0"
val jacksonVersion = "2.22.3"
val poiVersion = "5.5.1"
val commonmarkVersion = "0.30.0"
val dockerJavaVersion = "3.7.1"
val slf4jVersion = "2.0.20"

dependencies {
    // `api` marks what braidrun-web (and any other consumer) gets from this
    // library: every dependency both projects use is declared here once, so
    // the consumer never pins its own, possibly diverging, version. The two
    // platforms below also align the Ktor and Netty modules the consumer adds
    // on top (ktor-server-*), keeping one Ktor / one Netty across the stack.
    api(platform("io.ktor:ktor-bom:$ktorVersion"))
    // Ktor pulls Netty 4.2.x and Lettuce / the consumer's AWS SDK request
    // 4.1.x; pin one patched Netty (4.2.17 fixed the last of ~20 published
    // CVEs in Ktor's default 4.2.9: HTTP/1.1 request smuggling, HTTP/2 DoS).
    api(platform("io.netty:netty-bom:4.2.18.Final"))

    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Kotlinx IO for Path support in Koog attachments DSL and the MCP stdio server.
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.1")

    // YAML parsing for workflow definitions
    api("com.charleskorn.kaml:kaml:0.104.0")

    // Koog agents, using the Ktor versions resolved by Koog itself.
    api("ai.koog:koog-agents:${koogVersion}")
    // agents-ext (BETA stream) — homes the built-in tools (ExitTool,
    // ReadFileTool, ListDirectoryTool, EditFileTool, WriteFileTool,
    // ExecuteShellCommandTool, etc.) that are not part of the stable umbrella;
    // declared explicitly so ToolRegistryBuilder.kt can register them.
    implementation("ai.koog:agents-ext:${koogBetaVersion}")
    // agents-mcp (BETA stream) — McpToolRegistryProvider + stdio/SSE/Streamable
    // HTTP transports for talking to external MCP servers. Not part of the
    // stable koog-agents umbrella.
    implementation("ai.koog:agents-mcp:${koogBetaVersion}")
    // A2A server (AgentA2A.kt) — all A2A modules are on the BETA stream.
    api("ai.koog:a2a-server:${koogBetaVersion}")
    api("ai.koog:agents-features-a2a-server:${koogBetaVersion}")
    // HTTP JSON-RPC transport, served by the CIO engine.
    api("ai.koog:a2a-transport-server-jsonrpc-http:${koogBetaVersion}")
    api("io.ktor:ktor-server-cio")

    // Testing. Test dependencies are not inherited by consumers, so
    // braidrun-web declares the same set at the same versions.
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test"))
    // Ktor MockEngine for HTTP client tests (TypeSafe Jev client).
    testImplementation("io.ktor:ktor-client-mock")
    // Logging
    api("org.slf4j:slf4j-api:$slf4jVersion")
    api("io.github.oshai:kotlin-logging-jvm:8.0.4")
    testRuntimeOnly("org.slf4j:slf4j-simple:$slf4jVersion")
    // POI logs through the Log4j API; route it to SLF4J instead of Log4j's
    // "could not find a logging provider" status error.
    runtimeOnly("org.apache.logging.log4j:log4j-to-slf4j:2.26.1")
    implementation(kotlin("reflect"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    // Apache POI for Office documents (Word, Excel, PowerPoint — OOXML plus
    // legacy .xls via HSSF in the core artifact).
    implementation("org.apache.poi:poi:$poiVersion")
    implementation("org.apache.poi:poi-ooxml:$poiVersion")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.apache.commons:commons-csv:1.14.1")
    implementation("org.commonmark:commonmark:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-gfm-tables:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:$commonmarkVersion")

    // Apple plist parser
    implementation("com.googlecode.plist:dd-plist:1.30")

    // Lettuce client backing Koog's RedisPromptCache (`prompt_cache=redis`).
    implementation("io.lettuce:lettuce-core:7.8.0.RELEASE")

    // MongoDB: KMongo for the agent storage/state helpers, and the sync driver
    // itself (MongoDocumentStore, braidrun-web's Mongock changelogs).
    implementation("org.litote.kmongo:kmongo:5.12.0")
    api("org.mongodb:mongodb-driver-sync:5.13.0")

    // Jsoup for HTML parsing and web scraping
    implementation("org.jsoup:jsoup:1.23.2")

    // Microsoft Playwright for browser automation
    implementation("com.microsoft.playwright:playwright:1.63.0")

    // Jakarta Mail (Eclipse Angus implementation) for the email tools
    // (SMTP/IMAP) and braidrun-web's transactional email.
    api("org.eclipse.angus:angus-mail:2.0.5")

    // JDBC drivers for the database tools. SQLite also backs SqliteDocumentStore;
    // PostgreSQL / MySQL are only reached through user-supplied JDBC URLs.
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    runtimeOnly("org.postgresql:postgresql:42.7.13")
    runtimeOnly("com.mysql:mysql-connector-j:26.7.0")

    // Koog RAG: embeddings + the storage interfaces (`rag-base`, STABLE).
    implementation("ai.koog:embeddings-base:${koogVersion}")
    implementation("ai.koog:embeddings-llm:${koogVersion}")

    // Koog LLM client modules (not bundled in the koog-agents umbrella, so
    // apps pay only for the providers they use). We use:
    //   STABLE (1.3.0):
    //     openai-client, openai-client-base (AbstractOpenAILLMClient base),
    //     anthropic-client (used for Claude direct + via OpenRouter mirror),
    //     openrouter-client, bedrock-client, ollama-client.
    //   BETA (1.3.0-beta):
    //     google-client (Gemini), deepseek-client (DeepSeek V3.1/V4),
    //     mistralai-client (Mistral large).
    // Qwen (DashScope) is reached through its OpenAI-compatible endpoint, so
    // it needs no dedicated client. The bundled `prompt-executor-llms-all` BoM
    // is BETA-only, so we stay on per-provider deps to keep the stable
    // umbrella consistent.
    implementation("ai.koog:prompt-executor-openai-client:${koogVersion}")
    implementation("ai.koog:prompt-executor-openai-client-base:${koogVersion}")
    implementation("ai.koog:prompt-executor-anthropic-client:${koogVersion}")
    implementation("ai.koog:prompt-executor-openrouter-client:${koogVersion}")
    implementation("ai.koog:prompt-executor-bedrock-client:${koogVersion}")
    implementation("ai.koog:prompt-executor-ollama-client:${koogVersion}")
    implementation("ai.koog:prompt-executor-google-client:${koogBetaVersion}")
    implementation("ai.koog:prompt-executor-deepseek-client:${koogBetaVersion}")
    implementation("ai.koog:prompt-executor-mistralai-client:${koogBetaVersion}")
    // STABLE LLM executor surface (CachedPromptExecutor + MultiLLMPromptExecutor).
    // MultiLLMPromptExecutor / RoutingLLMPromptExecutor / LLMClientRouter live
    // under `ai.koog.prompt.executor.llms.*` but are published in
    // prompt-executor-model (the old `prompt-executor-llms` artifact is gone).
    // The cached executor (CachedPromptExecutor) keeps its own artifact.
    implementation("ai.koog:prompt-executor-model:${koogVersion}")
    implementation("ai.koog:prompt-executor-cached:${koogVersion}")
    // Prompt-cache backends: in-memory + file (STABLE) and Redis (BETA).
    implementation("ai.koog:prompt-cache-files:${koogVersion}")
    implementation("ai.koog:prompt-cache-model:${koogVersion}")
    implementation("ai.koog:prompt-cache-redis:${koogBetaVersion}")
    // KoogHttpClient pluggable factory + Ktor-backed default (auto-discovered
    // via ServiceLoader in HttpClientFactoryResolver — required at runtime so
    // LLM clients constructed without an explicit factory work out of the box).
    implementation("ai.koog:http-client-core:${koogVersion}")
    runtimeOnly("ai.koog:http-client-ktor:${koogVersion}")

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
    //     opt-in per workflow via `long_term_memory_enabled=true`. braidrun-web
    //     implements its Mongo-backed storage adapter against this API.
    //   - prompt-processor (STABLE): `ResponseProcessor` (constructor arg on
    //     AIAgent) — the hook weaker providers need to correct malformed
    //     tool-call JSON before the agent loop sees it; also hosts the bundled
    //     `LLMBasedToolCallFixProcessor`.
    //   - rag-base (STABLE): storage interfaces + `JVMFileSystemProvider`
    //     already in use by `AssistantDocsKnowledgeBaseService`; bumped
    //     explicitly so the long-term memory + local file memory providers
    //     resolve.
    //   - agents-features-snapshot (STABLE): `Persistence` feature for
    //     checkpoint/restore — required for the `runFromCheckpoint` flow.
    //   - agents-features-opentelemetry (STABLE): OpenTelemetry feature with
    //     Langfuse / Weave / DataDog exporters. It has never had a `-beta`
    //     build (an earlier `koogBetaVersion` request only resolved through the
    //     umbrella's transitive stable version). It is multiplatform, so the
    //     JVM-only extensions live in a separate `-jvm` artifact (resolved
    //     transitively).
    api("ai.koog:agents-features-longterm-memory:${koogBetaVersion}")
    implementation("ai.koog:agents-features-snapshot:${koogVersion}")
    implementation("ai.koog:agents-features-opentelemetry:${koogVersion}")
    implementation("ai.koog:prompt-processor:${koogVersion}")
    implementation("ai.koog:rag-base:${koogVersion}")

    // MCP SDK — split into client + server artifacts (the umbrella
    // `kotlin-sdk` jar is metadata-only). We host an MCP server **and** call
    // external MCP servers via `AgentMcpUtils`, so we need both:
    //   - `kotlin-sdk-server-jvm` — `Server`, `ServerOptions`, `ServerCapabilities`,
    //     `StdioServerTransport` used by AgentMcpServer to expose tool groups.
    //   - `kotlin-sdk-client-jvm` — `StdioClientTransport`, `SseClientTransport`,
    //     `WebSocketClientTransport`, `StreamableHttpClientTransport` used by
    //     AgentMcpUtils to consume external MCP servers.
    // Streamable HTTP is the primary transport.
    implementation("io.modelcontextprotocol:kotlin-sdk-server-jvm:0.15.0")
    implementation("io.modelcontextprotocol:kotlin-sdk-client-jvm:0.15.0")

    // Ktor client pieces used by local HttpAccess (versions from ktor-bom).
    api("io.ktor:ktor-client-core")
    api("io.ktor:ktor-client-okhttp")
    api("io.ktor:ktor-client-content-negotiation")
    implementation("io.ktor:ktor-serialization-jackson")
    api("io.ktor:ktor-serialization-kotlinx-json")

    // Docker client for sandbox execution (Phase 3b: Docker-per-step)
    implementation("com.github.docker-java:docker-java-core:$dockerJavaVersion")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:$dockerJavaVersion")

    // Console output utilities (line reader, terminal, ANSI). The bundle
    // ships its own native terminal providers.
    implementation("org.jline:jline:4.4.6")
}

group = "com.fartech.braidrun"
version = "1.4.0"
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

tasks.named<Copy>("processResources") {
    from("workflows/templates") {
        include("*.yaml", "*.yml", "resources-index.txt")
        into("workflows/templates")
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
    // Tests parse the public examples/templates and the workflow guide's snippets from disk;
    // declare them so editing only those files does not leave `test` UP-TO-DATE.
    inputs.dir("examples/workflows").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("workflows/templates").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("docs/WORKFLOW_GUIDE.md").withPathSensitivity(PathSensitivity.RELATIVE)
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
