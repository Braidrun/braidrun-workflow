package com.fartech.agents.agents.app_generators.iOS

import java.io.File

// Common output constraint snippets to keep prompts consistent
internal const val RULE_NO_MARKDOWN_FENCES: String = "- DO NOT include markdown fences in the output"
internal const val RULE_NO_FENCES_OR_COMMENTARY: String =
    "- DO NOT wrap the code in Markdown fences or add any commentary"

// --------------------------------------------
// Technology Stack Initialization Prompts
// --------------------------------------------

/**
 * Creates the prompt for generating project.yml for Native iOS (XcodeGen).
 * This is the initial generation without error context.
 */
internal fun createProjectYamlGenerationPrompt(props: IOSAppProjectProperties, absRoot: String): String {
    return """
        You are a Sub Agent responsible for generating the project.yml configuration file for a native iOS project.
        
        Project Root Directory: $absRoot
        
        Project Properties:
        $props
        
        YOUR TASK:
        Generate a complete and valid project.yml file and save it to: $absRoot/project.yml
        
        YAML CONFIGURATION REQUIREMENTS:
        1. name: ${props.appName}
        2. bundleIdPrefix: ${props.bundleId?.substringBeforeLast('.', props.bundleId) ?: ""}
        3. options:
           - bundleIdPrefix: Use the value above
           - deploymentTarget:
               iOS: "${props.deploymentTarget}"
        
        4. targets:
           - Must include at least one App Target (use appName as the name)
           - type: application
           - platform: iOS
           - deploymentTarget: "${props.deploymentTarget}"
           - sources: [Sources]
           - resources: [Resources]
        
        5. If test targets are needed:
           - type: bundle.unit-test
           - dependencies: Explicitly specify using target and name fields
           - Example:
             dependencies:
               - target: YourAppName
        
        6. schemes:
           - Must include at least one scheme
           - build -> targets: Explicitly specify target names
           - test -> targets: Use mapping format (parallelizable: true)
        
        ⚠️ COMMON ERRORS TO AVOID:
        - testTargets MUST be a list of mappings, NOT a list of strings
        - Correct format:
          testTargets:
            - name: TestTargetName
              parallelizable: true
        - Incorrect format (DO NOT USE):
          testTargets:
            - TestTargetName
            - parallelizable: true  # This is wrong
        
        - DO NOT use the following unsupported keys:
          - coverage
          - parallel (use parallelizable instead)
          - inheritUIConfiguration
          - inheritEnvironment
        
        - All file paths must be relative to the project root directory
        - Directories already created: Sources/, Resources/, Tests/
        
        EXECUTION STEPS:
        1. Generate a complete and valid project.yml content
        2. Use SafeFileTools.writeFile to write to $absRoot/project.yml
        3. WAIT for the write operation to complete
        4. Call the Exit tool to report completion with status message

        ⚠️ CRITICAL RULES:
        - Complete the task ONLY by calling tools
        - DO NOT return any free-form text, only call tools
        - The generated YAML MUST strictly comply with XcodeGen specifications
        - You MUST call the Exit tool at the end with a clear success or failure message
        - If writeFile succeeds, report "SUCCESS: project.yml generated"
        - If writeFile fails, report "FAILURE: <reason>"
    """.trimIndent()
}

/**
 * Creates the prompt for fixing project.yml after xcodegen failure.
 * Includes error context to help the LLM understand what went wrong.
 */
internal fun createProjectYamlFixPrompt(
    props: IOSAppProjectProperties,
    absRoot: String,
    projectYamlFile: File,
    errorMessage: String
): String {
    val currentYamlContent = if (projectYamlFile.exists()) {
        projectYamlFile.readText()
    } else {
        "File does not exist"
    }

    return """
        You are a Sub Agent responsible for fixing the project.yml configuration file for a native iOS project.
        
        Project Root Directory: $absRoot
        
        ❌ xcodegen execution FAILED with the following error:
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        $errorMessage
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        Current project.yml content:
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        $currentYamlContent
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        Project Properties (for reference):
        $props
        
        YOUR TASK:
        1. Carefully analyze the error message to identify the issue
        2. Fix the errors in the project.yml file
        3. Use SafeFileTools.writeFile to write the corrected content to: $absRoot/project.yml
        4. WAIT for the write operation to complete
        5. Call the Exit tool to report completion with a clear status message
        
        COMMON ISSUE SOLUTIONS:
        
        1. If error indicates missing directories (like Sources, Resources):
           - Directories have already been created, ensure sources/resources paths are correct
        
        2. If error indicates YAML format issues:
           - Check indentation (use 2 spaces)
           - Check mapping and list formats
           - testTargets MUST be a list of mappings, cannot mix strings and mappings
        
        3. If error indicates unsupported keys:
           - Remove keys like coverage, parallel, inheritUIConfiguration, inheritEnvironment
           - Use parallelizable instead of parallel
        
        4. If error indicates dependency issues:
           - Ensure dependencies use target and name fields
           - Example:
             dependencies:
               - target: TargetName
        
        5. If error indicates scheme configuration issues:
           - Ensure build.targets explicitly lists target names
           - Ensure test.targets uses correct mapping format
        
        ⚠️ CRITICAL RULES:
        - Complete the task ONLY by calling tools
        - DO NOT return any free-form text
        - You MUST call the Exit tool to end the task
        - If writeFile succeeds, report "SUCCESS: project.yml fixed"
        - If writeFile fails, report "FAILURE: <reason>"
    """.trimIndent()
}


/**
 * Creates the prompt for Flutter project initialization.
 */
internal fun createFlutterInitPrompt(props: IOSAppProjectProperties, absRoot: String): String {
    return """
        You are a Sub Agent responsible for initializing a Flutter project in the following directory:
        $absRoot
        
        YOUR RESPONSIBILITIES:
        1) Check if Flutter SDK is available (run: flutter --version)
        2) If the project directory is empty, execute flutter create to create the project scaffold
        3) Modify pubspec.yaml file to set the correct app name and dependencies
        4) Create base directory structure (lib/models, lib/services, lib/widgets, lib/screens)
        5) Execute flutter pub get to fetch dependencies
        6) If commands fail, analyze errors and retry (maximum 3 attempts)
        7) Report conclusion via Exit tool when complete
        
        PROJECT PROPERTIES:
        - App Name: ${props.appName}
        - Bundle ID: ${props.bundleId}
        - Organization: ${props.organizationName}
        - Description: ${props.description}
        
        FLUTTER INITIALIZATION COMMAND EXAMPLE:
        flutter create --org ${props.organizationName} --project-name ${
        props.appName?.replace(" ", "_")?.lowercase()
    } --platforms ios,android .
        
        CRITICAL pubspec.yaml CONFIGURATION:
        - name: ${props.appName?.replace(" ", "_")?.lowercase()}
        - description: ${props.description}
        - Add common dependencies: provider (state management), http (network requests)
        
        TOOL USAGE:
        - Command line operations: Use ShellTools.executeShellCmd
        - File operations: Use SafeFileTools.writeFile/ReadFileTool
        - Directory inspection: Use ListDirectoryTool
        
        INTERACTION AND OUTPUT RULES:
        - Complete the task ONLY by calling tools; DO NOT return free-form text except final Exit
        - If Flutter SDK is unavailable, explain in Exit message but do not treat as error
        - Upon completion, call Exit and report initialization result and project status
    """.trimIndent()
}

/**
 * Creates the prompt for React Native project initialization.
 */
internal fun createReactNativeInitPrompt(props: IOSAppProjectProperties, absRoot: String): String {
    return """
        You are a Sub Agent responsible for initializing a React Native project in the following directory:
        $absRoot
        
        YOUR RESPONSIBILITIES:
        1) Check if Node.js and npx are available (node --version, npx --version)
        2) If the project directory is empty, use React Native CLI to create the project
        3) Modify package.json to set the correct app name and version
        4) Modify app.json to configure app metadata
        5) Create base directory structure (src/components, src/screens, src/services, src/utils)
        6) Execute npm install to install dependencies
        7) If commands fail, analyze errors and retry (maximum 3 attempts)
        8) Report conclusion via Exit tool when complete
        
        PROJECT PROPERTIES:
        - App Name: ${props.appName}
        - Bundle ID (iOS): ${props.bundleId}
        - Description: ${props.description}
        
        REACT NATIVE INITIALIZATION COMMAND:
        npx react-native@latest init ${props.appName?.replace(" ", "")} --directory .
        (Note: If directory has existing files, may need to create in temporary directory and move)
        
        CRITICAL package.json CONFIGURATION:
        - name: ${props.appName?.replace(" ", "-")?.lowercase()}
        - version: 1.0.0
        - Add common dependencies: @react-navigation/native (navigation), axios (network requests)
        
        app.json CONFIGURATION:
        - name: ${props.appName}
        - displayName: ${props.appName}
        
        TOOL USAGE:
        - Command line operations: Use ShellTools.executeShellCmd
        - File operations: Use SafeFileTools.writeFile/ReadFileTool
        - Directory inspection: Use ListDirectoryTool
        
        INTERACTION AND OUTPUT RULES:
        - Complete the task ONLY by calling tools; DO NOT return free-form text except final Exit
        - If Node.js/npx is unavailable, explain in Exit message but do not treat as error
        - Upon completion, call Exit and report initialization result and project status
    """.trimIndent()
}

/**
 * Creates the prompt for Kotlin Multiplatform project initialization.
 */
internal fun createKotlinMppInitPrompt(props: IOSAppProjectProperties, absRoot: String): String {
    return """
        You are a Sub Agent responsible for initializing a Kotlin Multiplatform (KMP) project in the following directory:
        $absRoot
        
        YOUR RESPONSIBILITIES:
        1) Check environment: Gradle, Java JDK
        2) Create standard KMP project structure
        3) Generate root build.gradle.kts and settings.gradle.kts
        4) Create shared module (commonMain, iosMain, androidMain)
        5) Create iosApp and androidApp directories
        6) Configure Gradle dependencies and plugins
        7) If commands fail, analyze errors and retry (maximum 3 attempts)
        8) Report conclusion via Exit tool when complete
        
        PROJECT PROPERTIES:
        - App Name: ${props.appName}
        - Bundle ID: ${props.bundleId}
        - Organization: ${props.organizationName}
        - Description: ${props.description}
        
        KMP PROJECT STRUCTURE:
        - shared/: Shared Kotlin code
          - src/commonMain/: Common code
          - src/iosMain/: iOS-specific code
          - src/androidMain/: Android-specific code
        - iosApp/: iOS application (SwiftUI)
        - androidApp/: Android application
        
        CRITICAL build.gradle.kts (root) CONFIGURATION:
        - Kotlin plugin version: Latest stable version
        - Android Gradle plugin
        
        CRITICAL shared/build.gradle.kts CONFIGURATION:
        - kotlin("multiplatform")
        - cocoapods or framework export
        - Dependencies: kotlinx-coroutines, ktor-client (optional)
        
        settings.gradle.kts:
        - rootProject.name = "${props.appName}"
        - include(":shared", ":androidApp")
        
        TOOL USAGE:
        - Command line operations: Use ShellTools.executeShellCmd
        - File generation: Use SafeFileTools.writeFile
        - Directory operations: Use ListDirectoryTool
        
        HINT:
        - Refer to Kotlin Multiplatform official template: https://kmp.jetbrains.com/
        - If needed, use WebTools to find latest configuration examples
        
        INTERACTION AND OUTPUT RULES:
        - Complete the task ONLY by calling tools; DO NOT return free-form text except final Exit
        - If Gradle/JDK is unavailable, explain in Exit message
        - Upon completion, call Exit and report initialization result
    """.trimIndent()
}

// --------------------------------------------
// Project file list prompt
// --------------------------------------------
internal fun createProjectFilesPrompt(
    projectProperties: IOSAppProjectProperties,
    existingStructure: String
): String {
    val technologySpecificGuidance = when (projectProperties.implementTechnology) {
        IOSAppProjectProperties.ImplementTechnology.Native -> {
            val dependenciesNote = if (projectProperties.thirdPartyDependencies.isNotEmpty()) {
                "* Required pods: ${projectProperties.thirdPartyDependencies.joinToString(", ")}"
            } else {
                "* Ready for adding third-party libraries"
            }
            """
            Technology: Native iOS (Swift/SwiftUI or UIKit)

            REQUIRED COMPONENTS:
            - App Delegate and Scene Delegate (if using UIKit)
            - SwiftUI App entry point (if using SwiftUI)
            - View Models, Models, Services
            - Network layer (API clients)
            - Persistence (UserDefaults, CoreData if needed)
            - UI components (Views, ViewControllers)
            - Assets (Images in Assets.xcassets)
            - ⚠️  CRITICAL: Podfile (CocoaPods dependency management file)
              * Essential for modern iOS development
              * Include platform, target, and pod dependencies
              $dependenciesNote
            """.trimIndent()
        }

        IOSAppProjectProperties.ImplementTechnology.Flutter -> """
            Technology: Flutter
            
            REQUIRED COMPONENTS:
            - lib/main.dart
            - lib/models/, lib/services/, lib/widgets/, lib/screens/
            - pubspec.yaml updates for assets
            - assets/images/ with required images
        """.trimIndent()

        IOSAppProjectProperties.ImplementTechnology.ReactNative -> """
            Technology: React Native
            
            REQUIRED COMPONENTS:
            - src/components/, src/screens/, src/services/, src/utils/
            - app.json configuration
            - assets/images/
        """.trimIndent()

        IOSAppProjectProperties.ImplementTechnology.UniApp -> """
            Technology: UniApp
            
            REQUIRED COMPONENTS:
            - pages/ directory
            - static/ assets
            - main.js, App.vue
        """.trimIndent()

        IOSAppProjectProperties.ImplementTechnology.KotlinMpp -> """
            Technology: Kotlin Multiplatform
            
            REQUIRED COMPONENTS:
            - shared module with common code
            - iosApp with SwiftUI UI
            - androidApp
            - API clients
            - README.md
        """.trimIndent()

        else -> """
            Technology: General iOS app project
            
            Create a clear list of additional files needed for the application.
        """.trimIndent()
    }

    val designFilesGuidance = if (projectProperties.designFiles.isNotEmpty()) {
        val assetOrganizationGuidance = when (projectProperties.implementTechnology) {
            IOSAppProjectProperties.ImplementTechnology.Native -> """
                - Organize assets in Assets.xcassets directory (iOS Asset Catalog)
                - Include different resolutions (@1x, @2x, @3x) for each asset
            """.trimIndent()

            IOSAppProjectProperties.ImplementTechnology.Flutter -> """
                - Organize assets in assets/images/ directory
                - Reference assets in pubspec.yaml
                - Include different resolutions (1.0x, 2.0x, 3.0x) in subdirectories
            """.trimIndent()

            IOSAppProjectProperties.ImplementTechnology.ReactNative -> """
                - Organize assets in assets/images/ or src/assets/ directory
                - Include different resolutions (@1x, @2x, @3x) following React Native conventions
            """.trimIndent()

            IOSAppProjectProperties.ImplementTechnology.UniApp -> """
                - Organize assets in static/ directory
                - UniApp will automatically handle different screen densities
            """.trimIndent()

            IOSAppProjectProperties.ImplementTechnology.KotlinMpp -> """
                - Organize shared assets in shared/src/commonMain/resources/
                - Platform-specific assets in iosApp/Assets.xcassets (for iOS)
            """.trimIndent()

            else -> """
                - Organize assets in a standard assets directory suitable for the chosen technology
                - Provide multiple resolutions where applicable
            """.trimIndent()
        }

        """

            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            DESIGN FILES - IMPORTANT NOTICE
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

            Design files were provided and have already been processed in the SliceAssets phase.

            ⚠️ CRITICAL - DO NOT GENERATE MEDIA FILES:
            1. ALL visual assets (icons, backgrounds, buttons, illustrations, logos) have ALREADY been extracted
            2. These assets are defined in a separate MediaAssetsManifest
            3. Your task is to generate SOURCE CODE that REFERENCES these existing assets
            4. DO NOT include image/icon files in your file list
            5. Focus on generating Swift/Kotlin/JS files that will LOAD and USE the assets

            ASSET REFERENCE GUIDELINES:
            $assetOrganizationGuidance

            When writing code that uses assets, reference them by name (e.g., Image("logo"), UIImage(named: "background"))
        """.trimIndent()
    } else {
        ""
    }

    return """
        You are designing the complete file and directory structure for an iOS app project.
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        PROJECT PROPERTIES
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        $projectProperties
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        EXISTING PROJECT STRUCTURE
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        The following files and directories already exist:
        
        $existingStructure
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        YOUR TASK
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        Create a comprehensive list of ADDITIONAL files and directories needed to complete the project.

        MANDATORY REQUIREMENTS:
        1. File list MUST be specific to the chosen technology: ${projectProperties.implementTechnology}
        2. For EACH file or directory, provide a SHORT but CLEAR description of its purpose and contents
        3. ONLY list files that DO NOT already exist in the structure shown above
        4. Focus on functional code that implements the app's features and UI
        5. The list should be structured to allow developers to easily find and access required files
        6. For each file, provide a clear description that will guide the code generation process

        ⚠️ IMPORTANT - MEDIA FILES HANDLING:
        7. DO NOT include media files (images, icons, app icons) in your file list
        8. Media assets have ALREADY been defined in a separate media manifest through design analysis
        9. Your task is to generate ONLY the SOURCE CODE files that will USE these media assets
        10. Focus on: Swift/Kotlin/JS source files, configuration files, build files, etc.
        11. Any media asset references in code should point to the assets defined in the media manifest
        
        $technologySpecificGuidance$designFilesGuidance
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        OUTPUT FORMAT
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        STRICT RULES:
        - Respond STRICTLY in JSON that matches the provided schema
        - DO NOT include any extra text, comments, or Markdown code fences
        - Return ONLY the JSON object
    """.trimIndent()
}


/**
 * Generates the prompt for asking and refining project requirements at the beginning.
 * This ensures all requirements are gathered before project initialization and file generation.
 */
internal fun createInitialRequirementsPrompt(projectProperties: IOSAppProjectProperties): String {
    return """
        You are starting an iOS app development project.
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        INITIAL PROJECT PROPERTIES
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        $projectProperties
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        YOUR TASK
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        1. Review these project properties carefully
        2. Use EnhancedAskUser tool to ask the user if they want to add, modify, or clarify any requirements
        3. Focus on important details that would help create a better app:
           - Are there specific features or functionality they want?
           - Any specific technical requirements or constraints?
           - Any third-party services or APIs to integrate?
           - Any specific design patterns or architectural preferences?
        4. Keep questions concise and batch related questions together
        5. DO NOT upload or analyze design files yet - that will happen later
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        INTERACTION RULES
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        STRICT REQUIREMENTS:
        - Respond ONLY by calling tools; DO NOT send free-form assistant messages
        - Use EnhancedAskUser for questions, Exit when done
        - If user has no additional requirements, that's fine - just call Exit
        - DO NOT proceed to implementation until all requirements are clarified
    """.trimIndent()
}

/**
 * Generates a specialized prompt for Podfile creation.
 */
internal fun inferCocoaPodsFromArchitecture(architecture: ProjectArchitecture?): List<String> {
    if (architecture == null) return emptyList()

    fun collectText(): String = buildString {
        appendLine(architecture.globalPrinciples)
        appendLine(architecture.namingConventions)
        appendLine(architecture.dependencyGraph)
        appendLine(architecture.codePatterns)
        appendLine(architecture.errorHandlingStrategy)
        appendLine(architecture.typeSystemConventions)
        architecture.fileArchitectures.forEach { fa ->
            appendLine(fa.architecturalNotes)
            appendLine(fa.dependencyUsagePatterns)
            appendLine(fa.fileUsageExample)
            fa.dependencies.forEach { appendLine(it) }
        }
    }.lowercase()

    val text = collectText()

    // Directly extract any explicit pod declarations (preserve version specs)
    val podLineRegex = Regex("(?m)^\\s*pod\\s+['\"]([^'\"]+)['\"](?:\\s*,\\s*['\"]([^'\"]+)['\"])?.*$")
    val explicitPods = podLineRegex.findAll(text).map { m ->
        val name = m.groupValues.getOrNull(1)?.trim() ?: return@map null
        val ver = m.groupValues.getOrNull(2)?.trim().orEmpty()
        if (ver.isNotEmpty()) "pod '$name', '$ver'" else "pod '$name'"
    }.filterNotNull().toMutableSet()

    // Known library keyword -> CocoaPods name mapping
    val knownLibs = mapOf(
        "alamofire" to "Alamofire",
        "snapkit" to "SnapKit",
        "kingfisher" to "Kingfisher",
        "sdwebimage" to "SDWebImage",
        "moya" to "Moya",
        "rxswift" to "RxSwift",
        "rxcocoa" to "RxCocoa",
        "swiftyjson" to "SwiftyJSON",
        "charts" to "Charts",
        "lottie" to "lottie-ios",
        "promisekit" to "PromiseKit",
        "realmswift" to "RealmSwift",
        "realm" to "RealmSwift",
        "sqlite.swift" to "SQLite.swift",
        "afnetworking" to "AFNetworking",
        // Firebase variants
        "firebaseanalytics" to "Firebase/Analytics",
        "firebase messaging" to "Firebase/Messaging",
        "firebase-messaging" to "Firebase/Messaging",
        "firebasemessaging" to "Firebase/Messaging",
        "firebase" to "Firebase/Analytics",
        // Google Mobile Ads
        "google mobile ads" to "Google-Mobile-Ads-SDK",
        "google-mobile-ads" to "Google-Mobile-Ads-SDK",
        "admob" to "Google-Mobile-Ads-SDK"
    )

    val inferred = mutableSetOf<String>()
    knownLibs.forEach { (key, podName) ->
        if (text.contains(key)) {
            inferred.add(podName)
        }
    }

    // Return explicit pod lines as-is, plus simple names for inferred pods
    return (explicitPods + inferred).toList()
}

internal fun mergePodDependencies(userDeps: List<String>, inferredDeps: List<String>): List<String> {
    // Normalize to deduplicate while preserving order (userDeps first)
    fun keyOf(s: String): String {
        val trimmed = s.trim()
        // Extract name from possible forms: pod 'Name', 'ver' | Name [ver]
        val podDecl = Regex("^pod\\s+['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE).find(trimmed)
        val name = podDecl?.groupValues?.getOrNull(1)
            ?: trimmed.split(Regex("[\t ,]+")).firstOrNull()?.trim('"', '\'', ',')
        return (name ?: trimmed).lowercase()
    }

    val result = mutableListOf<String>()
    val seen = mutableSetOf<String>()

    userDeps.forEach { dep ->
        val k = keyOf(dep)
        if (k.isNotBlank() && seen.add(k)) result.add(dep)
    }
    inferredDeps.forEach { dep ->
        val k = keyOf(dep)
        if (k.isNotBlank() && seen.add(k)) result.add(dep)
    }

    return result
}

// Original function continues below
internal fun createPodfileGenerationPrompt(
    file: IOSAppProjectFile,
    projectProperties: IOSAppProjectProperties,
    effectiveDependencies: List<String>? = null
): String {
    val targetName = projectProperties.appName ?: "MyApp"
    val targetNameSafe = targetName.replace(Regex("[^A-Za-z0-9_-]"), "_")
    val platform = "ios"
    val deploymentTarget = projectProperties.deploymentTarget ?: "14.0"

    val depsList = effectiveDependencies ?: projectProperties.thirdPartyDependencies
    val dependenciesSection = if (depsList.isNotEmpty()) {
        buildString {
            appendLine("  # Required third-party dependencies")
            depsList.forEach { depRaw ->
                val dep = depRaw.trim()
                val depLine = when {
                    // Normalize any line that already starts with a CocoaPods declaration
                    // Examples accepted:
                    //  - pod 'Alamofire'
                    //  - pod\t'Alamofire', '~> 5.6'
                    //  - pod    'SnapKit', '~> 5.0'
                    dep.startsWith("pod ") || dep.startsWith("pod\t") || dep.startsWith("pod\n") || dep.startsWith("pod\r") -> {
                        val remainder = dep.replace(Regex("^pod\\s+"), "").trimStart()
                        "  pod $remainder"
                    }

                    else -> {
                        // Parse simple forms: "Name" or "Name <version spec>"
                        val parts = dep.split(Regex("\\s+"))
                        val name = parts.firstOrNull()
                            ?.trim('"', '\'')
                            ?.trim()
                            ?.trimEnd(',')
                            .orEmpty()
                        val versionSpec = parts.drop(1)
                            .joinToString(" ")
                            .trim()
                            .trimStart(',')
                            .trim()
                        if (versionSpec.isNotEmpty()) {
                            "  pod '$name', '${versionSpec}'"
                        } else {
                            "  pod '$name'"
                        }
                    }
                }
                appendLine(depLine)
            }
        }
    } else {
        """
  # Add your third-party dependencies here
  # Example:
  # pod 'Alamofire', '~> 5.6'
  # pod 'SnapKit', '~> 5.0'
  # pod 'Kingfisher', '~> 7.0'
        """.trimIndent()
    }

    return """
        Generate a Podfile for iOS project: ${file.path}
        Description: ${file.description}

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        PODFILE GENERATION REQUIREMENTS
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        PROJECT CONFIGURATION:
        - App Name: $targetNameSafe
        - Platform: $platform
        - Deployment Target: $deploymentTarget
        - Development Language: ${projectProperties.developmentLanguage}
        - UI Framework: ${projectProperties.uiMethod}

        REQUIRED PODFILE STRUCTURE:
        # Uncomment the next line to define a global platform for your project
        platform :$platform, '$deploymentTarget'

        target '$targetNameSafe' do
          # Comment the next line if you don't want to use dynamic frameworks
          use_frameworks!

          # Pods for $targetNameSafe
$dependenciesSection

        end

        post_install do |installer|
          installer.pods_project.targets.each do |target|
            target.build_configurations.each do |config|
              config.build_settings['IPHONEOS_DEPLOYMENT_TARGET'] = '$deploymentTarget'
            end
          end
        end

        CRITICAL REQUIREMENTS:
        1. Use proper Ruby syntax
        2. Set platform to :$platform with deployment target '$deploymentTarget'
        3. Use 'use_frameworks!' for Swift compatibility
        4. Include post_install hook to set deployment target for all pods
        5. Target name MUST match the app name (sanitized): '$targetNameSafe'
        ${
        if (depsList.isNotEmpty())
            "6. Include ALL specified dependencies: ${depsList.joinToString(", ")}"
        else
            "6. Include helpful comments showing how to add dependencies"
    }

        OUTPUT FORMAT:
        - Write the complete Podfile content
        - Use proper indentation (2 spaces)
        - Include helpful comments
        $RULE_NO_MARKDOWN_FENCES
        - Output ONLY the Podfile content, ready to save

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        WRITE THE PODFILE CONTENT BELOW (no markdown, just the raw file content):
    """.trimIndent()
}

/**
 * Generates the prompt for file source code generation with architecture context.
 */
internal fun createFileGenerationPrompt(
    file: IOSAppProjectFile,
    architecture: ProjectArchitecture?,
    projectProperties: IOSAppProjectProperties? = null,
    designSpec: DesignSpecification? = null,
    mediaManifest: MediaAssetsManifest? = null
): String {
    // Special handling for Podfile generation
    val isPodfile = file.name.equals("Podfile", ignoreCase = true)

    if (isPodfile && projectProperties != null) {
        val inferred = inferCocoaPodsFromArchitecture(architecture)
        val merged = mergePodDependencies(projectProperties.thirdPartyDependencies, inferred)
        return createPodfileGenerationPrompt(file, projectProperties, merged)
    }

    val architectureContent = if (architecture != null) {
        val fileArch = findFileArchitecture(architecture.fileArchitectures, file.path)
        val fileArchSection = if (fileArch != null) {
            buildString {
                appendLine("═══════════════════════════════════════════════════════")
                appendLine("ARCHITECTURE SPECIFICATION")
                appendLine("═══════════════════════════════════════════════════════")
                appendLine()

                if (fileArch.dataStructures.isNotEmpty()) {
                    appendLine("DATA STRUCTURES TO IMPLEMENT:")
                    fileArch.dataStructures.forEach { ds ->
                        append("- ${ds.type} ${ds.name}")
                        if (ds.inheritsFrom.isNotEmpty()) append(": ${ds.inheritsFrom}")
                        appendLine()
                        if (ds.properties.isNotEmpty()) {
                            appendLine("  Properties: ${ds.properties}")
                        }
                        appendLine("  Description: ${ds.description}")
                        if (ds.detailedMembers.isNotEmpty()) {
                            appendLine("  Detailed Members: ${ds.detailedMembers}")
                        }
                        if (ds.initializationExample.isNotEmpty()) {
                            appendLine("  Initialization Example: ${ds.initializationExample}")
                        }
                        if (ds.usageExample.isNotEmpty()) {
                            appendLine("  Usage Example:")
                            appendLine("    ${ds.usageExample.replace("\n", "\n    ")}")
                        }
                    }
                    appendLine()
                }

                if (fileArch.functions.isNotEmpty()) {
                    appendLine("FUNCTIONS TO IMPLEMENT:")
                    fileArch.functions.forEach { func ->
                        append("- ${func.accessModifier} func ${func.name}(${func.parameters})")
                        if (func.returnType != "void") append(" -> ${func.returnType}")
                        appendLine()
                        appendLine("  Description: ${func.description}")
                        if (func.implementationLogic.isNotEmpty()) {
                            appendLine("  Implementation Logic:")
                            appendLine("    ${func.implementationLogic.replace("\n", "\n    ")}")
                        }
                        if (func.usageExample.isNotEmpty()) {
                            appendLine("  Usage Example: ${func.usageExample}")
                        }
                        if (func.errorHandling.isNotEmpty()) {
                            appendLine("  Error Handling: ${func.errorHandling}")
                        }
                    }
                    appendLine()
                }

                if (fileArch.globalVariables.isNotEmpty()) {
                    appendLine("GLOBAL VARIABLES:")
                    fileArch.globalVariables.forEach { gv ->
                        val keyword = if (gv.isConstant) "let/const" else "var"
                        appendLine("- $keyword ${gv.name}: ${gv.type} - ${gv.description}")
                    }
                    appendLine()
                }

                if (fileArch.dependencies.isNotEmpty()) {
                    appendLine("DEPENDENCIES (import these):")
                    fileArch.dependencies.forEach { dep ->
                        appendLine("- $dep")
                    }
                    appendLine()
                }

                if (fileArch.architecturalNotes.isNotEmpty()) {
                    appendLine("ARCHITECTURAL NOTES:")
                    appendLine(fileArch.architecturalNotes)
                    appendLine()
                }

                if (fileArch.dependencyUsagePatterns.isNotEmpty()) {
                    appendLine("DEPENDENCY USAGE PATTERNS:")
                    appendLine(fileArch.dependencyUsagePatterns)
                    appendLine()
                }

                if (fileArch.fileUsageExample.isNotEmpty()) {
                    appendLine("FILE USAGE EXAMPLE:")
                    appendLine(fileArch.fileUsageExample.prependIndent("  "))
                    appendLine()
                }
            }
        } else ""

        val globalSection = buildString {
            if (architecture.globalPrinciples.isNotEmpty()) {
                appendLine("═══════════════════════════════════════════════════════")
                appendLine("GLOBAL PRINCIPLES")
                appendLine("═══════════════════════════════════════════════════════")
                appendLine(architecture.globalPrinciples)
                appendLine()
            }

            if (architecture.namingConventions.isNotEmpty()) {
                appendLine("═══════════════════════════════════════════════════════")
                appendLine("NAMING CONVENTIONS")
                appendLine("═══════════════════════════════════════════════════════")
                appendLine(architecture.namingConventions)
                appendLine()
            }

            if (architecture.codePatterns.isNotEmpty()) {
                appendLine("═══════════════════════════════════════════════════════")
                appendLine("CODE PATTERNS & IDIOMS")
                appendLine("═══════════════════════════════════════════════════════")
                appendLine(architecture.codePatterns)
                appendLine()
            }

            if (architecture.errorHandlingStrategy.isNotEmpty()) {
                appendLine("═══════════════════════════════════════════════════════")
                appendLine("ERROR HANDLING STRATEGY")
                appendLine("═══════════════════════════════════════════════════════")
                appendLine(architecture.errorHandlingStrategy)
                appendLine()
            }

            if (architecture.typeSystemConventions.isNotEmpty()) {
                appendLine("═══════════════════════════════════════════════════════")
                appendLine("TYPE SYSTEM CONVENTIONS")
                appendLine("═══════════════════════════════════════════════════════")
                appendLine(architecture.typeSystemConventions)
                appendLine()
            }
        }

        fileArchSection + globalSection
    } else ""

    // Resource & Design usage guidance to ensure correct references in generated code
    val uiMethod = projectProperties?.uiMethod
    val resourceGuidance = buildString {
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("RESOURCES & ASSETS INTEGRATION GUIDELINES")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine()
        appendLine("ASSET CATALOG (Images/Icons):")
        appendLine("- All images/icons are located in Assets.xcassets under Resources/.")
        appendLine("- Reference images by asset name ONLY (no file extensions, no scale suffix like @2x/@3x).")
        when (uiMethod) {
            IOSAppProjectProperties.UIMethod.SwiftUI -> {
                appendLine("- SwiftUI: use Image(\"AssetName\") for bitmap assets, and .resizable()/.renderingMode(.original) as needed.")
                appendLine("  Example: Image(\"app_logo\").resizable().aspectRatio(contentMode: .fit)")
            }

            IOSAppProjectProperties.UIMethod.UIKit -> {
                appendLine("- UIKit: use UIImage(named: \"AssetName\") or UIImageView(image: UIImage(named: \"AssetName\")).")
                appendLine("  Example: let image = UIImage(named: \"app_logo\")")
            }

            else -> {
                appendLine("- Use the appropriate API of your UI framework to load images by asset name.")
            }
        }
        appendLine()
        appendLine("BUNDLE RESOURCES (JSON/Plists/Audio/Video):")
        appendLine("- Place non-image resources under Resources/ and include them in the build.")
        appendLine("- Load via Bundle APIs; DO NOT hardcode absolute file paths or use FileManager to reach outside the bundle.")
        appendLine("  JSON Example:")
        appendLine("    if let url = Bundle.main.url(forResource: \"cities\", withExtension: \"json\"),")
        appendLine("       let data = try? Data(contentsOf: url) { /* decode */ }")
        appendLine("  Audio Example (AVFoundation):")
        appendLine("    if let url = Bundle.main.url(forResource: \"click\", withExtension: \"mp3\") { /* AVAudioPlayer(url: url) */ }")
        appendLine("  Video Example (AVKit):")
        appendLine("    if let url = Bundle.main.url(forResource: \"intro\", withExtension: \"mp4\") { /* AVPlayer(url: url) */ }")
        appendLine()
        appendLine("LOCALIZATION (if applicable):")
        appendLine("- Use NSLocalizedString(\"key\", comment: \"\") or Text(LocalizedStringKey(\"key\")) for SwiftUI.")
        appendLine()
        appendLine("DESIGN ADHERENCE:")
        appendLine("- Follow the DesignSpecification for colors/typography when referenced elsewhere in the project.")
        appendLine("- Prefer system semantic colors where appropriate; otherwise create Color/UIColor extensions mapped to the palette.")
        appendLine()
        appendLine("STRICT DON’TS:")
        appendLine("- Do NOT reference assets via relative disk paths (e.g., \"Resources/Assets.xcassets/...\").")
        appendLine("- Do NOT hardcode sandbox/absolute paths; always use asset names or Bundle APIs.")
        appendLine()
    }

    // Inject concrete design tokens if provided
    val designTokensSection = if (designSpec != null) buildString {
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("DESIGN TOKENS (from DesignSpecification)")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        if (designSpec.colorPalette != ColorPalette()) {
            appendLine("Colors (hex):")
            val cp = designSpec.colorPalette
            fun addColor(name: String, value: String) {
                if (value.isNotBlank()) appendLine("- $name: $value")
            }
            addColor("primary", cp.primary)
            addColor("secondary", cp.secondary)
            addColor("accent", cp.accent)
            addColor("background", cp.background)
            addColor("surface", cp.surface)
            addColor("error", cp.error)
            addColor("textPrimary", cp.textPrimary)
            addColor("textSecondary", cp.textSecondary)
            appendLine()
        }
        if (designSpec.typography != Typography()) {
            appendLine("Typography:")
            val tp = designSpec.typography
            if (tp.headingFont.isNotBlank()) appendLine("- Heading font: ${tp.headingFont} (${tp.headingSize}pt)")
            if (tp.bodyFont.isNotBlank()) appendLine("- Body font: ${tp.bodyFont} (${tp.bodySize}pt)")
            appendLine()
        }
        if (designSpec.appIcon != AppIconSpec()) {
            val ai = designSpec.appIcon
            appendLine("App Icon style: ${ai.style}")
            if (ai.primaryColor.isNotBlank()) appendLine("- Primary color: ${ai.primaryColor}")
            if (ai.secondaryColor.isNotBlank()) appendLine("- Secondary color: ${ai.secondaryColor}")
            if (ai.symbol.isNotBlank()) appendLine("- Symbol: ${ai.symbol}")
            appendLine()
        }
    } else ""

    // Inject concrete available resources inventory if provided
    val resourcesInventorySection = if (mediaManifest != null && mediaManifest.assets.isNotEmpty()) buildString {
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLine("AVAILABLE RESOURCES INVENTORY (from slicing)")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        val assets = mediaManifest.assets

        // Derive asset names for images in asset catalogs
        data class InventoryItem(val displayName: String, val type: String, val purpose: String)

        fun inferAssetName(path: String, name: String, group: String?, purpose: String): String {
            // Prefer folder name inside .xcassets for imagesets/appiconsets
            val imagesetIndex = path.indexOf(".imageset")
            if (imagesetIndex > 0) {
                val start = path.lastIndexOf('/', imagesetIndex) + 1
                if (start in 1..imagesetIndex) return path.substring(start, imagesetIndex)
            }
            val appiconIndex = path.indexOf(".appiconset")
            if (appiconIndex > 0) {
                val start = path.lastIndexOf('/', appiconIndex) + 1
                if (start in 1..appiconIndex) return path.substring(start, appiconIndex)
            }
            if (group == ".imageset" || group == ".appiconset") {
                // Fallback: strip extension
                return name.substringBefore('.')
            }
            // For non-asset-catalog resources, use filename without extension
            return name.substringBefore('.')
        }

        val imageItems = mutableListOf<InventoryItem>()
        val bundleItems = mutableListOf<InventoryItem>()
        assets.forEach { a ->
            val t = a.type.lowercase()
            when {
                t == "icon" || t == "image" -> {
                    val assetName = inferAssetName(a.path, a.name, a.group, a.purpose)
                    imageItems.add(InventoryItem(assetName, t, a.purpose))
                }

                else -> {
                    val baseName = a.name.substringBefore('.')
                    bundleItems.add(InventoryItem(baseName, t, a.purpose))
                }
            }
        }
        if (imageItems.isNotEmpty()) {
            appendLine("Images (use by asset name, e.g., Image(\"Name\") / UIImage(named: \"Name\")):")
            imageItems.forEach { it ->
                appendLine("- ${it.displayName} (${it.purpose})")
            }
            appendLine()
        }
        if (bundleItems.isNotEmpty()) {
            appendLine("Bundle resources (load via Bundle.main, use base name + extension):")
            bundleItems.forEach { it ->
                appendLine("- ${it.displayName} [${it.type}] (${it.purpose})")
            }
            appendLine()
        }
    } else ""

    return """
        Generate source code for: ${file.path}
        Description: ${file.description}
        
        $architectureContent
        $designTokensSection
        $resourcesInventorySection
        $resourceGuidance
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        CODE GENERATION REQUIREMENTS
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        MANDATORY:
        - Fully functional, production-ready code (NO placeholders like "// TODO" or "// Implementation here")
        - Implement ALL functions and data structures specified in the architecture
        - Follow the exact function signatures (names, parameters, return types)
        - Use the specified dependencies
        - Include essential comments for clarity
        - Ensure the code is compilable and follows best practices
        - Provide complete implementations for all methods
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        OUTPUT FORMAT
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        STRICT RULES:
        $RULE_NO_FENCES_OR_COMMENTARY
        - Use SafeFileTools.writeFile(path="${file.path}", content=<complete code>) to write the file
        - Call Exit tool after writing the file
        - If this is a streaming request (no tools available): output ONLY the complete raw file content with no fences, headers, or explanations
    """.trimIndent()
}

/**
 * Creates a prompt for generating IMAGE files using MultimediaGenerationTools (LLM-based image generation).
 * The LLM will call MultimediaGenerationTools.generateImage() to write the image directly to the specified path.
 */
internal fun createImageGenerationPromptWithDownload(
    file: IOSAppProjectFile,
    projectProperties: IOSAppProjectProperties,
    referenceImagePath: String? = null,
    hasDesignAttachments: Boolean = false
): String {
    val designFilesNote = if (hasDesignAttachments) {
        """

            ⚠️ CRITICAL REQUIREMENTS - DESIGN REFERENCE ATTACHED:
            - The attached design files show the EXACT visual style and design language of the app
            - Analyze the attached design screenshots carefully to understand:
              * Color palette and visual theme
              * Icon style and design patterns
              * Typography and spacing conventions
              * Overall UI aesthetic and brand identity
            - Generate this asset to MATCH the design style shown in the attachments
            - The description above was extracted from the original design files
            - Ensure the generated asset is optimized and suitable for iOS
        """.trimIndent()
    } else if (projectProperties.designFiles.isNotEmpty()) {
        """

            ⚠️ CRITICAL REQUIREMENTS:
            - The description above was extracted from the original design files
            - Generate EXACTLY as described, including colors, dimensions, and visual characteristics
            - Ensure the generated asset is optimized and suitable for iOS
        """.trimIndent()
    } else ""

    return """
        You are a Sub Agent responsible for generating an image file using MultimediaGenerationTools.

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        FILE INFORMATION
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        - File Name: ${file.name}
        - File Path: ${file.path}
        - MIME Type: ${file.mime}
${if (referenceImagePath != null) "\n        - Reference Image (MUST USE): $referenceImagePath\n" else ""}
        DETAILED DESCRIPTION:
        ${file.description}$designFilesNote

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        YOUR TASK
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        1. ${if (hasDesignAttachments) "FIRST, carefully examine the attached design files to understand the app's visual style, color scheme, and design patterns" else ""}
        2. Call MultimediaGenerationTools.generateImage() with a detailed prompt based on the description${if (referenceImagePath != null) ", and PASS the referenceImages parameter set to a single-element list containing the exact path above" else ""}
           - Create a clear, detailed English prompt for the image generation
           ${if (hasDesignAttachments) "- Incorporate visual style elements from the attached design files into your prompt" else ""}
           - Choose appropriate aspectRatio based on the description
           - IMPORTANT: Pass imagePath set to exactly "${file.path}". The tool will WRITE the generated image directly to this path.
${if (referenceImagePath != null) "\n        IMPORTANT: The generated image MUST be absolutely identical to the reference image in content, colors, shapes, and composition. The ONLY allowed difference is the final size/resolution. Use the prompt only to reinforce that strict identity requirement.\n" else ""}
        3. After the tool call completes, verify the file exists at the target path and then output in this exact format:
           FILE_WRITTEN: ${file.path}

        4. Call Exit tool to complete the task

        EXAMPLE OUTPUT:
        FILE_WRITTEN: ${file.path}

        ⚠️ CRITICAL RULES:
        - ${if (hasDesignAttachments) "Use the attached design files as visual reference to ensure style consistency" else ""}
        - Complete the task by calling MultimediaGenerationTools.generateImage()
        - DO NOT manually write or download the image; the tool writes the file directly to disk
        - Output ONLY "FILE_WRITTEN: ${file.path}"
        - You MUST call the Exit tool at the end
    """.trimIndent()
}

/**
 * Creates a Sub Agent prompt that generates Base64 media content and writes it to disk.
 * Used for non-image media files (audio, video, etc.).
 *
 * @deprecated For image files, use createImageGenerationPromptWithDownload instead
 */
internal fun createMediaGenerationPromptWithWrite(
    file: IOSAppProjectFile,
    projectProperties: IOSAppProjectProperties
): String {
    val designFilesNote = if (projectProperties.designFiles.isNotEmpty()) {
        """

            ⚠️ CRITICAL REQUIREMENTS:
            - The description above was extracted from the original design files
            - Generate EXACTLY as described, including colors, dimensions, and visual characteristics
            - Ensure the generated asset is optimized and suitable for iOS
        """.trimIndent()
    } else ""

    return """
        You are a Sub Agent responsible for generating a media file and saving it to disk.

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        FILE INFORMATION
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        - File Name: ${file.name}
        - File Path: ${file.path}
        - MIME Type: ${file.mime}

        DETAILED DESCRIPTION:
        ${file.description}$designFilesNote

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        YOUR TASK
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        1. Generate the media file content based on the description (Base64 encoded)
        2. Use SafeFileTools.writeBinaryFile to decode and write the Base64 content to a binary file
        3. File path: ${file.path}
        4. Call Exit tool to report the result when complete

        TOOL USAGE:
        - Binary file writing: SafeFileTools.writeBinaryFile(path="${file.path}", base64Content=<pure Base64 string>)
        - Completion report: Exit

        ⚠️ CRITICAL RULES:
        - Complete the task ONLY by calling tools; DO NOT return free-form text
        - Base64 content MUST be a pure string without prefixes (e.g., NO 'data:image/png;base64,' prefix)
        - Base64 string MUST NOT contain newlines or spaces
        - Use writeBinaryFile NOT writeFile, as this is a binary media file
        - You MUST call the Exit tool at the end
    """.trimIndent()
}

/**
 * Generates the prompt for the build and debug cycle (used by Sub Agent).
 */
internal fun createBuildDebugPrompt(
    projectProperties: IOSAppProjectProperties,
    retryTimes: Int,
    projectStructure: String
): String {
    val buildCommandReference = when (projectProperties.implementTechnology) {
        IOSAppProjectProperties.ImplementTechnology.Native -> """
            - Native iOS: Use xcodebuild or open .xcodeproj/.xcworkspace
            - Example: xcodebuild -project "Project.xcodeproj" -scheme "SchemeName" -configuration Debug
        """.trimIndent()

        IOSAppProjectProperties.ImplementTechnology.Flutter -> """
            - Flutter: Use flutter build
            - Example: flutter build ios --debug
        """.trimIndent()

        IOSAppProjectProperties.ImplementTechnology.ReactNative -> """
            - React Native: Use npx react-native run-ios
            - Example: npx react-native run-ios --configuration Debug
        """.trimIndent()

        IOSAppProjectProperties.ImplementTechnology.KotlinMpp -> """
            - Kotlin Multiplatform: Use Gradle
            - Example: ./gradlew :iosApp:build
        """.trimIndent()

        else -> """
            - Select appropriate build command based on project type
        """.trimIndent()
    }

    return """
        You are a Sub Agent responsible for building and debugging an iOS application project.
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        PROJECT INFORMATION
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        Project Root Directory: ${projectProperties.projectRoot}
        
        PROJECT STRUCTURE SUMMARY:
        $projectStructure
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        YOUR RESPONSIBILITIES
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        1) Select the appropriate build command based on the technology stack (${projectProperties.implementTechnology})
        2) Execute the build command (e.g., xcodebuild, flutter build, npm run build, etc.)
        3) **CRITICAL**: Wait for the build command to COMPLETE execution, then check the output and exit code
        4) If build fails (non-zero exit code or error output):
           - Carefully read the error messages
           - Use ReadFileTool to read relevant source code files
           - Use SafeFileTools.writeFile to fix the errors
           - Re-execute the build command
        5) Repeat steps 2-4 until build succeeds or maximum retry limit ($retryTimes attempts) is reached
        6) Report conclusion via Exit tool (success/failure, reason summary, exit code)
        
        ⚠️ CRITICAL WARNINGS:
        - DO NOT call Exit while build command is still executing
        - MUST wait for command to complete and see full output before deciding next step
        - If output appears truncated, command may still be executing - wait longer
        
        BUILD COMMAND REFERENCE:
        $buildCommandReference
        
        TOOL USAGE:
        - Command execution: Use ShellTools.executeShellCmd
        - File reading: Use ReadFileTool to view error-related files
        - File modification: Use SafeFileTools.writeFile to fix errors
        - User input: Use EnhancedAskUser if user input is needed
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        INTERACTION AND OUTPUT RULES
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        STRICT REQUIREMENTS:
        - Complete the task ONLY by calling tools; DO NOT return free-form text except final Exit
        - If build fails, read error output, fix, and retry
        - If maximum retry limit is reached with failures, explain reason in Exit message and suggest manual inspection
        - Upon completion, call Exit and report build result and last command exit code
    """.trimIndent()
}

/**
 * Generates the prompt for the testing phase (used by Sub Agent).
 */
internal fun createTestingPrompt(
    projectProperties: IOSAppProjectProperties,
    retryTimes: Int,
    projectStructure: String
): String {
    val testCommandReference = when (projectProperties.implementTechnology) {
        IOSAppProjectProperties.ImplementTechnology.Native -> """
            - Native iOS: Use xcodebuild test or swift test
            - Example: xcodebuild test -project "Project.xcodeproj" -scheme "SchemeName" -destination 'platform=iOS Simulator,name=iPhone 14'
        """.trimIndent()

        IOSAppProjectProperties.ImplementTechnology.Flutter -> """
            - Flutter: Use flutter test
            - Example: flutter test
        """.trimIndent()

        IOSAppProjectProperties.ImplementTechnology.ReactNative -> """
            - React Native: Use npm test or jest
            - Example: npm test
        """.trimIndent()

        IOSAppProjectProperties.ImplementTechnology.KotlinMpp -> """
            - Kotlin Multiplatform: Use Gradle test
            - Example: ./gradlew test
        """.trimIndent()

        else -> """
            - Select appropriate test command based on project type
        """.trimIndent()
    }

    return """
        You are a Sub Agent responsible for generating and running tests for an iOS application project.
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        PROJECT INFORMATION
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        Project Root Directory: ${projectProperties.projectRoot}
        
        PROJECT STRUCTURE SUMMARY:
        $projectStructure
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        YOUR RESPONSIBILITIES
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        1) Check if test files already exist in the project
        2) If no tests exist, create basic unit tests for key components
        3) Select the appropriate test command based on the technology stack (${projectProperties.implementTechnology})
        4) **CRITICAL**: Execute the test command and wait for it to COMPLETE, then check the output and exit code
        5) If tests fail (non-zero exit code or failed test cases):
           - Carefully read the test failure messages
           - Use ReadFileTool to read relevant source code or test files
           - Use SafeFileTools.writeFile to fix the issues
           - Re-run the test command
        6) Repeat steps 3-5 until all tests pass or maximum retry limit ($retryTimes attempts) is reached
        7) Report conclusion via Exit tool (test result summary: passed/failed counts)
        
        ⚠️ CRITICAL WARNINGS:
        - DO NOT call Exit while test command is still executing
        - MUST wait for command to complete and see full output before deciding next step
        - If output appears truncated, command may still be executing - wait longer
        
        TEST COMMAND REFERENCE:
        $testCommandReference
        
        TEST FILE CREATION GUIDELINES:
        - Create tests for core business logic
        - Test key data models and service classes
        - Ensure tests cover main functionality paths
        - Use appropriate testing framework for the project's technology stack (XCTest/Flutter Test/Jest etc.)
        
        TOOL USAGE:
        - Command execution: Use ShellTools.executeShellCmd
        - File reading: Use ReadFileTool to view existing code and tests
        - File creation/modification: Use SafeFileTools.writeFile
        - User input: Use EnhancedAskUser if user input is needed
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        INTERACTION AND OUTPUT RULES
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        STRICT REQUIREMENTS:
        - Complete the task ONLY by calling tools; DO NOT return free-form text except final Exit
        - If tests fail, read error output, fix, and retry
        - If maximum retry limit is reached with failures, explain reason in Exit message
        - Upon completion, call Exit and report test results (passed/failed counts, covered components, etc.)
    """.trimIndent()
}

/**
 * Generates the prompt for the summary phase.
 *
 * @param projectProperties The iOS app project properties
 * @param builtAndTested Whether the project was built and tested (true on macOS, false on non-macOS)
 */
internal fun createSummaryPrompt(projectProperties: IOSAppProjectProperties, builtAndTested: Boolean): String {
    val platformNote = if (!builtAndTested) {
        """
            
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            ⚠️ NON-MACOS PLATFORM NOTE
            ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            
            This project was generated on a non-macOS system.
            
            The build and testing phases were SKIPPED as they require macOS with Xcode.
            
            The project structure and source files have been created and are ready to be built on a Mac.
        """.trimIndent()
    } else ""

    val testingSection = if (builtAndTested) {
        """
            5. **TESTING**
               - Summary of tests created
               - How to run tests
               - Test results
        """.trimIndent()
    } else {
        """
            5. **TESTING**
               - Testing was not performed (requires macOS)
               - Suggestions for tests that should be created
               - How to run tests once on macOS
        """.trimIndent()
    }

    val nextStepsAddition = if (!builtAndTested) {
        "   - Steps to build and test on macOS\n"
    } else ""

    val buildInstructionNote = if (!builtAndTested) {
        "   - **IMPORTANT:** Building requires macOS with Xcode installed\n"
    } else ""

    return """
        Generate a comprehensive summary of the iOS app project that was just created.$platformNote
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        PROJECT INFORMATION
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        Project Root: ${projectProperties.projectRoot}
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        SUMMARY REQUIREMENTS
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        Your summary MUST include the following sections:
        
        1. **PROJECT OVERVIEW**
           - App Name: ${projectProperties.appName}
           - Bundle ID: ${projectProperties.bundleId}
           - Description: ${projectProperties.description}
           - Technology Stack: ${projectProperties.implementTechnology}
           - Programming Language: ${projectProperties.developmentLanguage}
           - UI Framework: ${projectProperties.uiMethod}
        
        2. **PROJECT STRUCTURE**
           - List the main directories and key files
           - Explain the purpose of important components
           - Highlight the overall architecture
        
        3. **KEY FEATURES**
           - Describe the main features implemented in the app
           - Highlight any notable functionalities
           - Mention any third-party integrations
        
        4. **BUILD INSTRUCTIONS**
           - How to open the project in Xcode or relevant IDE
           - Required dependencies and how to install them
           - Build and run instructions
$buildInstructionNote
        
        $testingSection
        
        6. **NEXT STEPS**
           - Suggested improvements or additional features
           - Known limitations or issues
$nextStepsAddition           - Deployment considerations
           - Recommendations for production readiness
        
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        OUTPUT RULES
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        MANDATORY REQUIREMENTS:
        - Present the summary in a clear, well-formatted manner
        - Make it helpful for developers taking over this project
        - Provide only the requested summary content
        - DO NOT include code fences or extraneous commentary
        - Call Exit tool when you have completed the summary
    """.trimIndent()
}

// ============================================
// Phase 0: Design Specification Generation
// ============================================

/**
 * Creates prompt for generating design specification when no design files are provided.
 * Optionally includes competitor analysis if competitor apps are provided.
 */
internal fun createDesignSpecificationPrompt(
    props: IOSAppProjectProperties,
    competitorApps: List<com.fartech.agents.tools.AppleAppInfo> = emptyList()
): String {
    val competitorAnalysis = formatCompetitorAnalysisForDesign(competitorApps)

    return """
        You are an expert product designer and UI/UX specialist for iOS applications.
        Generate a comprehensive design specification for an iOS app based on the following requirements.

        PROJECT REQUIREMENTS:
        $props$competitorAnalysis

        YOUR TASK:
        Create a complete design specification that includes:

        1. APP ICON DESIGN:
           - Choose an appropriate visual style (minimalist/flat/gradient/skeuomorphic)
           - Define color scheme (primary and secondary colors in hex format)
           - Describe the central symbol or concept
           - Provide a detailed description for generation

        2. LAUNCH SCREEN DESIGN:
           - Background color and overall aesthetic
           - Logo positioning and size
           - Optional tagline or branding text
           - Animation specifications (if any)

        3. COLOR PALETTE:
           - Primary, secondary, and accent colors
           - Background and surface colors
           - Text colors (primary and secondary)
           - Error state color
           - All colors in hex format (e.g., #4A90E2)

        4. TYPOGRAPHY:
           - Heading font family (e.g., SF Pro Display, Helvetica Neue)
           - Body font family
           - Standard sizes for headings and body text

        5. SCREEN SPECIFICATIONS:
           For each major screen in the app:
           - Screen name and purpose
           - Layout type (single_column/grid/list/tabs)
           - UI elements with:
             * Type (button/image/text/input/icon)
             * Position and size descriptions
             * Whether each element requires a custom asset

        6. ASSET SPECIFICATIONS:
           List all required visual assets:
           - Name and type (icon/image/background/button)
           - Exact dimensions (width x height in pixels)
           - Purpose and usage context
           - Extremely detailed description for accurate generation

        7. ANIMATIONS (if applicable):
           - Trigger events
           - Animation types
           - Duration in seconds
           - Detailed descriptions

        DESIGN PRINCIPLES:
        - Follow iOS Human Interface Guidelines
        - Ensure accessibility (contrast ratios, touch targets)
        - Consider both light and dark modes
        - Use native iOS design patterns where appropriate
        - Maintain consistency across all screens

        OUTPUT FORMAT:
        Provide a complete DesignSpecification structure as defined in the schema.
        Do not include markdown fences or explanatory text.

        CRITICAL REQUIREMENTS:
        - Every visual element must be specified in detail
        - Asset descriptions must be precise enough for AI image generation
        - Color values must be valid hex codes
        - Dimensions must be appropriate for iOS devices
        - The specification must be comprehensive enough for developers to implement without ambiguity
    """.trimIndent()
}

// ============================================
// Phase 1: Asset Slicing Prompts
// ============================================

/**
 * Creates prompt for slicing assets from user-provided design files.
 * Optionally includes competitor analysis if competitor apps are provided.
 */
internal fun createSliceAssetsFromDesignFilesPrompt(
    props: IOSAppProjectProperties,
    competitorApps: List<com.fartech.agents.tools.AppleAppInfo> = emptyList()
): String {
    val competitorAnalysis = formatCompetitorAnalysisForAssets(competitorApps)

    return """
        You are an expert iOS asset manager and designer specializing in extracting assets from design files.

        PROJECT INFORMATION:
        $props

        The user has provided ${props.designFiles.size} design file(s) (attached to this message).$competitorAnalysis

        YOUR TASK:
        Analyze the attached design files and generate a COMPLETE manifest of ALL required media assets.

        MANDATORY ASSETS (iOS App Requirements):

        1. APP ICON (.appiconset):
           You MUST include all these sizes:
           - Icon.png (1024x1024) - App Store icon
           - Icon-60@2x.png (120x120) - iPhone App icon @2x
           - Icon-60@3x.png (180x180) - iPhone App icon @3x
           - Icon-76.png (76x76) - iPad App icon @1x
           - Icon-76@2x.png (152x152) - iPad App icon @2x
           - Icon-83.5@2x.png (167x167) - iPad Pro App icon @2x
           - Icon-40@2x.png (80x80) - Spotlight @2x
           - Icon-40@3x.png (120x120) - Spotlight @3x
           - Icon-29@2x.png (58x58) - Settings @2x
           - Icon-29@3x.png (87x87) - Settings @3x
           - Icon-20@2x.png (40x40) - Notification @2x
           - Icon-20@3x.png (60x60) - Notification @3x

           All app icons should have:
           - group: "AppIcon.appiconset"
           - purpose: "app_icon"
           - Detailed description based on the design

        2. LAUNCH SCREEN ASSETS:
           - At least one launch image or logo
           - Background images if specified in design
           - purpose: "launch_screen"

        3. UI ASSETS FROM DESIGN:
           - All custom images, icons, logos visible in the design
           - Background images
           - Button graphics (if custom)
           - Decorative elements
           - Tab bar icons
           - Navigation bar images

        FOR EACH ASSET, PROVIDE:
        - name: Exact filename (e.g., "Icon.png", "background.png")
        - path: Full path following iOS conventions
          Examples:
          * "Resources/Assets.xcassets/AppIcon.appiconset/Icon.png"
          * "Resources/Assets.xcassets/Background.imageset/Background@2x.png"
        - type: "icon" or "image"
        - width: Exact width in pixels
        - height: Exact height in pixels
        - purpose: Specific purpose (app_icon/launch_screen/background/button/tab_icon/etc)
        - detailedDescription: Extremely detailed description extracted from the design file, including:
          * Visual composition (shapes, symbols, text if any)
          * Color scheme (exact hex values if visible)
          * Style (flat/gradient/3D/minimalist/etc)
          * Any effects (shadows, glows, gradients)
          * Context of usage
        - group: Asset catalog group (e.g., ".appiconset", ".imageset") or null
        - scale: "@2x", "@3x", or null (for @1x/universal)

        ASSET GROUPING RULES (iOS Asset Catalogs):
        - App icons: All in "AppIcon.appiconset"
        - Regular images: Each in its own ".imageset" (e.g., "Logo.imageset")
        - For @2x/@3x variants: Same name but different scale suffixes

        CRITICAL REQUIREMENTS:
        - DO NOT miss any visual element from the design
        - Descriptions must be detailed enough for AI image generation without the original design file
        - Include ALL required app icon sizes (iOS requirement)
        - Follow iOS asset catalog naming conventions strictly
        - Ensure all paths are relative to project root

        OUTPUT FORMAT:
        Provide a MediaAssetsManifest structure as defined in the schema.
        Do not include markdown fences or explanatory text.
    """.trimIndent()
}

/**
 * Creates prompt for slicing assets from generated design specification.
 * Optionally includes competitor analysis if competitor apps are provided.
 */
internal fun createSliceAssetsFromDesignSpecPrompt(
    props: IOSAppProjectProperties,
    designSpec: DesignSpecification,
    competitorApps: List<com.fartech.agents.tools.AppleAppInfo> = emptyList()
): String {
    val screensInfo = designSpec.screens.joinToString("\n") { screen ->
        "- ${screen.name}: ${screen.elements.size} UI elements (${screen.elements.count { it.assetRequired }} require assets)"
    }

    val assetsInfo = designSpec.assets.joinToString("\n") { asset ->
        "- ${asset.name} (${asset.width}x${asset.height}): ${asset.purpose}"
    }

    val competitorAnalysis = formatCompetitorAnalysisForAssets(competitorApps)

    return """
        You are an expert iOS asset manager specializing in converting design specifications into media asset manifests.

        PROJECT INFORMATION:
        $props$competitorAnalysis

        DESIGN SPECIFICATION PROVIDED:

        App Icon Design:
        - Style: ${designSpec.appIcon.style}
        - Primary Color: ${designSpec.appIcon.primaryColor}
        - Secondary Color: ${designSpec.appIcon.secondaryColor}
        - Symbol: ${designSpec.appIcon.symbol}
        - Description: ${designSpec.appIcon.description}

        Launch Screen:
        - Background: ${designSpec.launchScreen.backgroundColor}
        - Logo Position: ${designSpec.launchScreen.logoPosition}
        - Description: ${designSpec.launchScreen.description}

        Color Palette:
        - Primary: ${designSpec.colorPalette.primary}
        - Accent: ${designSpec.colorPalette.accent}
        - Background: ${designSpec.colorPalette.background}

        Screens Defined:
        $screensInfo

        Assets Defined in Spec:
        $assetsInfo

        YOUR TASK:
        Convert this design specification into a COMPLETE media asset manifest.

        MANDATORY ASSETS (iOS Requirements):

        1. APP ICON (.appiconset) - Generate based on appIcon specification:
           Include ALL these sizes (iOS requirement):
           - Icon.png (1024x1024) - App Store
           - Icon-60@2x.png (120x120), Icon-60@3x.png (180x180)
           - Icon-76.png (76x76), Icon-76@2x.png (152x152)
           - Icon-83.5@2x.png (167x167)
           - Icon-40@2x.png (80x80), Icon-40@3x.png (120x120)
           - Icon-29@2x.png (58x58), Icon-29@3x.png (87x87)
           - Icon-20@2x.png (40x40), Icon-20@3x.png (60x60)

           For each icon, use this detailed description based on the spec:
           "${designSpec.appIcon.description}. Style: ${designSpec.appIcon.style}. Primary color: ${designSpec.appIcon.primaryColor}, secondary color: ${designSpec.appIcon.secondaryColor}. Central symbol: ${designSpec.appIcon.symbol}."

        2. LAUNCH SCREEN ASSETS:
           Based on launchScreen specification:
           - Logo or branding image
           - Background image if needed
           - Use description: "${designSpec.launchScreen.description}"

        3. UI ASSETS:
           For each asset in designSpec.assets, create corresponding MediaAssetSpec entries
           For elements marked assetRequired=true in screens, generate appropriate assets

        FOR EACH ASSET:
        - name: Filename with appropriate scale suffix
        - path: Full iOS-convention path (Resources/Assets.xcassets/...)
        - type: "icon" or "image"
        - width & height: Exact dimensions
        - purpose: Specific usage
        - detailedDescription: Comprehensive description combining all design spec information
        - group: Asset catalog group (.appiconset/.imageset)
        - scale: @2x/@3x or null

        OUTPUT FORMAT:
        Provide a MediaAssetsManifest structure as defined in the schema.
        Do not include markdown fences or explanatory text.

        CRITICAL:
        - DO NOT miss any asset mentioned in the design specification
        - Include ALL required app icon sizes
        - Descriptions must be detailed enough for AI image generation
        - Follow iOS asset catalog conventions strictly
    """.trimIndent()
}

/**
 * Fallback prompt when neither design files nor design spec are available.
 */
internal fun createSliceAssetsFallbackPrompt(props: IOSAppProjectProperties): String {
    return """
        You are an expert iOS asset manager.

        PROJECT INFORMATION:
        $props

        WARNING: No design files or design specifications were provided.
        You must generate a reasonable set of media assets based on the app description and typical iOS app requirements.

        YOUR TASK:
        Create a media asset manifest for a standard iOS application.

        MANDATORY ASSETS:

        1. APP ICON (.appiconset) - Design based on app name and description:
           Include ALL required sizes:
           - Icon.png (1024x1024)
           - Icon-60@2x.png (120x120), Icon-60@3x.png (180x180)
           - Icon-76.png (76x76), Icon-76@2x.png (152x152)
           - Icon-83.5@2x.png (167x167)
           - Icon-40@2x.png (80x80), Icon-40@3x.png (120x120)
           - Icon-29@2x.png (58x58), Icon-29@3x.png (87x87)
           - Icon-20@2x.png (40x40), Icon-20@3x.png (60x60)

        2. LAUNCH SCREEN:
           - At least a logo or app name text on solid background

        3. COMMON UI ASSETS (if applicable to app type):
           - Placeholder images
           - Tab bar icons (if app uses tabs)
           - Common button graphics

        Generate creative but professional descriptions based on the app's purpose.

        OUTPUT FORMAT:
        Provide a MediaAssetsManifest structure.
        Do not include markdown fences or explanatory text.
    """.trimIndent()
}

// ============================================
// Phase 2: Architecture Design with Media
// ============================================

/**
 * Creates prompt for architecture design that integrates media assets manifest.
 */
internal fun createArchitectureDesignPromptWithMedia(
    projectProperties: IOSAppProjectProperties,
    fileList: IOSAppProjectFiles,
    mediaManifest: MediaAssetsManifest
): String {
    val filesBullet = if (fileList.files.isNotEmpty()) fileList.files.mapIndexed { idx, f ->
        "${idx + 1}. name='${f.name}', path='${f.path}', isDir=${f.isDir}, mime='${f.mime}'"
    }.joinToString("\n") else "(no source files yet)"

    val mediaAssetsSummary = mediaManifest.assets.groupBy { it.purpose }.map { (purpose, assets) ->
        "$purpose: ${assets.size} assets"
    }.joinToString(", ")

    val appIconAssets = mediaManifest.assets.filter { it.purpose.contains("app_icon") }
    val launchAssets = mediaManifest.assets.filter { it.purpose.contains("launch") }
    val uiAssets = mediaManifest.assets.filter { !it.purpose.contains("app_icon") && !it.purpose.contains("launch") }

    return """
        You are a senior iOS architect with expertise in ${projectProperties.implementTechnology} and ${projectProperties.uiMethod}.

        PROJECT PROPERTIES:
        $projectProperties

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        SOURCE CODE FILES TO GENERATE
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        Total Source Files: ${fileList.files.count { !it.isDir && !it.isMedia() }} files

        $filesBullet

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        MEDIA ASSETS MANIFEST (already defined from design slicing)
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        Total Assets: ${mediaManifest.assets.size}
        - App Icons: ${appIconAssets.size} (all sizes for .appiconset)
        - Launch Screen Assets: ${launchAssets.size}
        - UI Assets: ${uiAssets.size}
        Summary by Purpose: $mediaAssetsSummary

        IMPORTANT: The media assets have been precisely defined through design analysis.
        Your task is to design the SOURCE CODE architecture that correctly integrates and uses these assets.

        YOUR TASK:
        Design a complete software architecture for ALL source code files listed above.

        ⚠️ CRITICAL REQUIREMENT - COMPLETENESS:
        - You MUST provide architecture for EVERY SINGLE source file listed above
        - Missing even ONE file is considered a FAILURE
        - Count the source files above: There are ${fileList.files.count { !it.isDir && !it.isMedia() }} source files
        - Your output MUST contain architecture definitions for ALL of them
        - DO NOT skip any file, even if it seems simple (like constants, utilities, extensions)

        FOR EACH SOURCE FILE, PROVIDE:

        1. FUNCTION SIGNATURES (with detailed specifications):
           - Function name
           - Parameters with types (e.g., "userId: String, age: Int")
           - Return type
           - Brief description of functionality
           - Access modifier (public/private/internal)
           ⭐ NEW - REQUIRED:
           - Implementation logic: Pseudocode outline of the function body (2-5 lines showing key steps)
           - Usage example: How to call this function with sample parameters
           - Error handling: What errors to catch and how to handle them

        2. DATA STRUCTURES (with comprehensive details):
           - Name of the struct/class/enum/protocol
           - Type designation
           - Properties with types (e.g., "id: UUID, title: String")
           - Description of purpose
           - Inheritance/conformance (e.g., "Codable, Identifiable")
           ⭐ NEW - REQUIRED:
           - Detailed members: For enums, list ALL cases. For protocols, list ALL required methods with signatures
           - Initialization example: Show how to create instances (e.g., "User(id: UUID(), name: \"John\")")
           - Usage example: Show typical usage pattern for this type

        3. GLOBAL VARIABLES/CONSTANTS:
           - Variable name
           - Type
           - Description
           - Whether it's a constant (let/const/final)

        4. DEPENDENCIES:
           - List of imports/includes from other files in THIS project
           - Framework dependencies (Foundation, SwiftUI, UIKit, etc.)
           - How this file uses the media assets (if applicable)
           ⭐ NEW - REQUIRED:
           - Dependency usage patterns: Explain HOW this file uses types/functions from dependencies
             Example: "Uses QRCodeModel from Models/QRCodeModel.swift to store scan results"

        5. ARCHITECTURAL NOTES:
           - Design patterns used (MVVM, MVC, etc.)
           - How assets are loaded and referenced
           - Key implementation considerations

        6. ⭐ FILE USAGE EXAMPLE (CRITICAL - NEW REQUIREMENT):
           - Provide a complete code example (5-15 lines) showing typical usage of this file's public API
           - Show how external code would import and use this file
           - Include realistic parameter values and return value handling

        7. ⭐ CRITICAL FILE MARKING (NEW REQUIREMENT):
           - Set isCritical=true for files that are ESSENTIAL for the app to function
           - Critical files include:
             * Main entry points (AppDelegate.swift, @main App struct, main.swift)
             * Core data models that are used throughout the app
             * Essential managers/coordinators (e.g., NavigationCoordinator, DataManager)
             * Key view controllers/views that are the primary screens
           - Set isCritical=false for supporting files (helpers, extensions, utilities)
           - If a critical file fails to generate after retries, the entire build will be blocked

        ASSET INTEGRATION GUIDELINES:
        - App Icons: Automatically loaded by system from AppIcon.appiconset
        - Launch Screen: Referenced in launch storyboard or SwiftUI
        - UI Images: Load using Image("name") in SwiftUI or UIImage(named:) in UIKit
        - Ensure asset names match the media manifest

        GLOBAL ARCHITECTURE ELEMENTS (Enhanced Requirements):
        - Define overall architectural principles and patterns (MVVM, Clean Architecture, etc.)
        - Establish naming conventions (camelCase, PascalCase, prefixes, suffixes)
        - Document dependency relationships (module hierarchy, data flow)
        - Specify how different modules interact (event handling, data passing, navigation)
        ⭐ NEW - REQUIRED:
        - Code patterns: Common coding patterns and idioms to use throughout the project
          Example: "Use @Published properties for reactive state updates"
        - Error handling strategy: How to handle errors consistently across the application
          Example: "Use Result<T, Error> for async operations, throw errors in synchronous functions"
        - Type system conventions: How to use optionals, generics, type aliases
          Example: "Use non-optional types by default, optionals only when value can be missing"

        OUTPUT FORMAT:
        Provide a ProjectArchitecture structure as defined in the schema.
        Do not include markdown fences or explanatory text.

        ⚠️ CRITICAL VALIDATION REQUIREMENTS:
        - Your output MUST contain ${fileList.files.count { !it.isDir && !it.isMedia() }} fileArchitectures (one for each source file)
        - Missing even ONE file will result in incomplete code generation
        - Before submitting, COUNT your fileArchitectures array to ensure it matches the required count
        - Design architecture for EVERY source file listed above (no exceptions)
        - Show how media assets are integrated into the code
        - Ensure consistency across all files
        - Follow best practices for the chosen technology stack
        - Make sure file dependencies are correctly specified
        ⭐ ENHANCED REQUIREMENTS FOR QUALITY:
        - Provide COMPLETE implementation logic pseudocode for all functions
        - Include USAGE EXAMPLES for all data structures and functions
        - Document ERROR HANDLING for all functions that can fail
        - Explain DEPENDENCY USAGE PATTERNS (how files use their dependencies)
        - Add FILE USAGE EXAMPLES showing how to use each file's public API

        These detailed specifications will ensure code generation quality WITHOUT needing to attach generated source code as reference.
    """.trimIndent()
}

/**
 * Creates prompt for architecture redesign based on previous review feedback.
 * This is used when architecture quality review fails and we need to improve the design.
 */
internal fun createArchitectureRedesignPromptWithFeedback(
    projectProperties: IOSAppProjectProperties,
    fileList: IOSAppProjectFiles,
    mediaManifest: MediaAssetsManifest,
    previousArchitecture: ProjectArchitecture,
    reviewFeedback: UnifiedArchitectureReview
): String {
    val filesBullet = if (fileList.files.isNotEmpty()) fileList.files.mapIndexed { idx, f ->
        "${idx + 1}. name='${f.name}', path='${f.path}', isDir=${f.isDir}, mime='${f.mime}'"
    }.joinToString("\n") else "(no source files yet)"

    val mediaAssetsSummary = mediaManifest.assets.groupBy { it.purpose }.map { (purpose, assets) ->
        "$purpose: ${assets.size} assets"
    }.joinToString(", ")

    val previousArchSummary = buildString {
        appendLine("Previous Architecture (Score: ${reviewFeedback.qualityScore}/100 - NOT APPROVED):")
        previousArchitecture.fileArchitectures.forEach { fa ->
            appendLine("- ${fa.filePath}: ${fa.functions.size} functions, ${fa.dataStructures.size} types")
        }
    }

    val issuesSummary = buildString {
        appendLine("CRITICAL ISSUES TO FIX:")
        reviewFeedback.designIssues.filter { it.severity == "critical" }.forEach { issue ->
            appendLine("❌ [${issue.category}] ${issue.description}")
            if (issue.affectedFiles.isNotEmpty()) {
                appendLine("   Affected: ${issue.affectedFiles.joinToString(", ")}")
            }
            if (issue.suggestedFix.isNotEmpty()) {
                appendLine("   Fix: ${issue.suggestedFix}")
            }
        }

        if (reviewFeedback.designIssues.any { it.severity == "warning" }) {
            appendLine("\nWARNINGS TO ADDRESS:")
            reviewFeedback.designIssues.filter { it.severity == "warning" }.forEach { issue ->
                appendLine("⚠️  [${issue.category}] ${issue.description}")
                if (issue.suggestedFix.isNotEmpty()) {
                    appendLine("   Fix: ${issue.suggestedFix}")
                }
            }
        }
    }

    val recommendationsSummary = if (reviewFeedback.recommendations.isNotEmpty()) {
        buildString {
            appendLine("RECOMMENDATIONS:")
            reviewFeedback.recommendations.forEach { rec ->
                appendLine("• $rec")
            }
        }
    } else ""

    return """
        You are a senior iOS architect REDESIGNING the architecture based on quality review feedback.

        PROJECT PROPERTIES:
        $projectProperties

        SOURCE CODE FILES TO GENERATE:
        $filesBullet

        MEDIA ASSETS MANIFEST:
        Total Assets: ${mediaManifest.assets.size}
        Summary: $mediaAssetsSummary

        $previousArchSummary

        QUALITY REVIEW FEEDBACK:
        Score: ${reviewFeedback.qualityScore}/100 (Threshold: ${IOSGenConstants.ARCHITECTURE_QUALITY_SCORE_THRESHOLD})
        Status: ❌ NOT APPROVED
        Summary: ${reviewFeedback.summary}

        $issuesSummary

        $recommendationsSummary

        YOUR TASK:
        REDESIGN the architecture to address ALL the issues identified above.

        ⭐ TOKEN OPTIMIZATION - PARTIAL ARCHITECTURE ALLOWED:
        - You MAY return ONLY the files that need modifications (incremental changes)
        - Unchanged files will be automatically merged from the previous architecture
        - This saves tokens while maintaining full architecture integrity
        - Example: If only 15 files need fixes, you can return just those 15 files
        - If you prefer to return the complete architecture, that's also acceptable

        ⭐ ADDING NEW FILES (IMPORTANT):
        - If fixing the issues requires NEW files (e.g., adding ViewModel layer, error enums, protocols):
          * You MUST provide the complete updated file list in the "updatedFileList" field
          * Include ALL files: existing + new ones
          * Provide proper descriptions for new files
          * ⚠️ CRITICAL: When you provide updatedFileList, you MUST also provide architecture definitions for ALL source files (not just the new ones)
        - If no new files are needed, leave "updatedFileList" as null
        - Example: Adding ViewModels to fix MVVM issues requires new files, so updatedFileList is mandatory and fileArchitectures must cover all files

        CRITICAL REQUIREMENTS FOR REDESIGN:
        1. Fix ALL critical issues - these are BLOCKING problems
        2. Address as many warnings as possible
        3. Implement the recommendations provided
        4. Focus on files affected by the issues (you may omit unchanged files in fileArchitectures)
        5. If adding new files, provide updatedFileList with complete file list
        6. Keep the overall structure but improve the design quality

        FOCUS AREAS FOR IMPROVEMENT:
        - API Design: Ensure clean, intuitive interfaces
        - Dependencies: Fix circular dependencies, improve layering
        - Error Handling: Add comprehensive error handling strategies
        - Performance: Consider scalability and efficiency
        - Maintainability: Improve code organization and documentation

        OUTPUT FORMAT:
        Provide a REDESIGNED ProjectArchitecture structure that addresses the feedback.
        Follow the same format as before with ALL required fields.

        ⚠️  IMPORTANT:
        - Your redesign MUST score >= ${IOSGenConstants.ARCHITECTURE_QUALITY_SCORE_THRESHOLD} to pass the next review
        - Focus on fixing the specific issues mentioned in the feedback
        - Don't just make cosmetic changes - address the root problems
        - Ensure the architecture is production-ready

        Do not include markdown fences or explanatory text.
    """.trimIndent()
}

// ============================================
// Phase 3: Unified Architecture Review
// ============================================

/**
 * Creates a unified prompt for reviewing BOTH architecture quality AND completeness.
 * Combines quality assessment (patterns, error handling, performance) with completeness check (missing files, functionality coverage).
 */
internal fun createUnifiedArchitectureReviewPrompt(
    projectProperties: IOSAppProjectProperties,
    fileList: IOSAppProjectFiles,
    architecture: ProjectArchitecture,
    mediaManifest: MediaAssetsManifest,
    existingStructureSummary: String
): String {
    val filesBullet = if (fileList.files.isNotEmpty()) fileList.files.mapIndexed { idx, f ->
        "${idx + 1}. name='${f.name}', path='${f.path}', isDir=${f.isDir}, mime='${f.mime}'"
    }.joinToString("\n") else "(no files)"

    val archSummary = buildString {
        appendLine("FILES: ${architecture.fileArchitectures.size}")
        appendLine("TOTAL FUNCTIONS: ${architecture.fileArchitectures.sumOf { it.functions.size }}")
        appendLine("TOTAL DATA STRUCTURES: ${architecture.fileArchitectures.sumOf { it.dataStructures.size }}")
        appendLine()
        appendLine("KEY FILE DETAILS:")
        architecture.fileArchitectures.take(15).forEach { fa ->
            val criticalMarker = if (fa.isCritical) " [CRITICAL]" else ""
            appendLine("- ${fa.filePath}$criticalMarker")
            appendLine("  Functions: ${fa.functions.joinToString(", ") { it.name }}")
            appendLine("  Data: ${fa.dataStructures.joinToString(", ") { it.name }}")
            appendLine("  Dependencies: ${fa.dependencies.joinToString(", ")}")
        }
    }

    val mediaSummary = "${mediaManifest.assets.size} assets: " +
            "${mediaManifest.assets.count { it.purpose.contains("app_icon") }} icons, " +
            "${mediaManifest.assets.count { it.purpose.contains("launch") }} launch, " +
            "${mediaManifest.assets.count { !it.purpose.contains("app_icon") && !it.purpose.contains("launch") }} UI"

    val criticalFileCount = architecture.fileArchitectures.count { it.isCritical }

    return """
        You are a SENIOR iOS architect with 10+ years of experience.
        Your task is to perform a COMPREHENSIVE review covering BOTH quality AND completeness.

        PROJECT PROPERTIES:
        $projectProperties

        CURRENT FILE LIST:
        $filesBullet

        EXISTING LOCAL PROJECT STRUCTURE (REFERENCE ONLY):
        $existingStructureSummary

        ARCHITECTURE SUMMARY:
        $archSummary
        Critical Files: $criticalFileCount

        MEDIA ASSETS:
        $mediaSummary

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        YOUR TASK: UNIFIED QUALITY + COMPLETENESS REVIEW
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        Evaluate the architecture across TWO dimensions:

        ═══════════════════════════════════════════════════
        PART A: ARCHITECTURE QUALITY (60% weight)
        ═══════════════════════════════════════════════════

        1. ERROR HANDLING & RESILIENCE:
           - Are errors properly typed and handled?
           - Do functions use Result<T, Error> or throws for operations that can fail?
           - Is there a unified error handling strategy?
           - Are edge cases considered?

        2. API DESIGN & INTERFACES:
           - Are APIs clean, intuitive, and consistent?
           - Do functions have appropriate signatures?
           - Are there proper abstractions (protocols/interfaces)?
           - Is dependency injection considered?

        3. ARCHITECTURE PATTERNS:
           - Does it follow MVVM, MVC, or appropriate pattern?
           - Is there proper separation of concerns?
           - Are layers (presentation/business/data) well-defined?
           - Are design patterns appropriately applied?

        4. DEPENDENCIES & COUPLING:
           - Are circular dependencies avoided?
           - Is the dependency graph clean?
           - Are there appropriate abstraction layers?
           - Is coupling minimized?

        5. PERFORMANCE & OPTIMIZATION:
           - Are performance considerations addressed?
           - Is there caching where appropriate?
           - Are expensive operations handled asynchronously?
           - Is pagination/lazy loading considered for lists?

        6. MAINTAINABILITY & EXTENSIBILITY:
           - Is the code organization logical?
           - Can new features be easily added?
           - Are there appropriate utility/helper files?
           - Is the architecture documented?

        7. BEST PRACTICES & CONVENTIONS:
           - Does it follow platform conventions?
           - Are naming conventions consistent?
           - Is there appropriate use of language features?
           - Are security considerations addressed?

        ═══════════════════════════════════════════════════
        PART B: FUNCTIONAL COMPLETENESS (40% weight)
        ═══════════════════════════════════════════════════

        1. FEATURE COVERAGE:
           - Are ALL features from the app description implemented?
           - Are all screens/views present?
           - Is navigation/routing complete?
           - Are all user interactions handled?

        2. ESSENTIAL COMPONENTS:
           - Entry point (AppDelegate, main.swift, etc.) ✓
           - All required views/screens ✓
           - Data models for all entities ✓
           - Services (networking, storage, etc.) ✓
           - View models or controllers ✓
           - Configuration files ✓

        3. MEDIA INTEGRATION:
           - Are all ${mediaManifest.assets.size} media assets properly integrated?
           - Is there code to load and display assets?
           - Are app icons configured?
           - Is launch screen set up?

        4. CRITICAL FILES:
           - Are critical files correctly identified?
           - Are any critical files missing?

        5. TECHNOLOGY-SPECIFIC REQUIREMENTS:
           ${
        when (projectProperties.implementTechnology) {
            IOSAppProjectProperties.ImplementTechnology.Native -> """
               - AppDelegate or SwiftUI App struct
               - All view files (SwiftUI/UIKit)
               - Asset catalog integration
               - Proper iOS patterns
               """.trimIndent()

            IOSAppProjectProperties.ImplementTechnology.Flutter -> """
               - main.dart with MaterialApp
               - All screen widgets
               - pubspec.yaml with assets
               - Proper widget composition
               """.trimIndent()

            else -> "- Technology-appropriate files and patterns"
        }
    }

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        OUTPUT FORMAT
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        {
          "qualityScore": <0-100 based on Part A quality assessment>,
          "approved": <true if qualityScore >= ${IOSGenConstants.ARCHITECTURE_QUALITY_SCORE_THRESHOLD} AND complete=true>,
          "complete": <true if Part B shows no missing functionality>,
          "designIssues": [
            {
              "severity": "critical" | "warning" | "info",
              "category": "missing_error_handling" | "poor_api_design" | "circular_dependency" | "performance_concern" | "maintainability" | etc,
              "description": "Detailed issue description",
              "affectedFiles": ["file1.swift", "file2.swift"],
              "suggestedFix": "Specific fix recommendation"
            }
          ],
          "missingFiles": [
            {
              "name": "FileName.swift",
              "path": "ProjectName/Category/FileName.swift",
              "isDir": false,
              "mime": "text/x-swift",
              "description": "Detailed description of what this file should contain and why it's needed"
            }
          ],
          "recommendations": [
            "Overall improvement suggestion 1",
            "Overall improvement suggestion 2"
          ],
          "summary": "Comprehensive summary covering both quality (score X/100) and completeness (Y missing files). Key issues: ... Key strengths: ..."
        }

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        SCORING GUIDELINES
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        - 90-100: Excellent - Production-ready, follows all best practices
        - 75-89: Good - Minor improvements recommended, can proceed
        - ${IOSGenConstants.ARCHITECTURE_QUALITY_SCORE_THRESHOLD}-74: Acceptable - Some improvements needed
        - 50-69: Poor - Significant issues, redesign recommended
        - 0-49: Critical - Must redesign

        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        CRITICAL INSTRUCTIONS
        ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

        - Be STRICT but FAIR
        - List ALL critical issues in designIssues
        - List ALL missing files in missingFiles
        - Provide ACTIONABLE feedback
        - Be specific: cite actual file names and function signatures
        - If qualityScore < ${IOSGenConstants.ARCHITECTURE_QUALITY_SCORE_THRESHOLD}, set approved=false
        - If missingFiles is not empty, set complete=false and approved=false
        - Only suggest missing files that are ESSENTIAL for functionality
        - IMPORTANT: Do NOT mark as missing any file that already exists in the EXISTING LOCAL PROJECT STRUCTURE shown above
        - IMPORTANT: The architecture is expected to cover ONLY the files in CURRENT FILE LIST (files to be generated). Pre-existing local files are OUT OF SCOPE for architecture coverage and must NOT reduce completeness
        - Consider the technology stack's best practices

        DO NOT include markdown fences or explanatory text.
        Return ONLY the JSON structure.
    """.trimIndent()
}

// ============================================
// Lightweight Compilation Check (New - 2025)
// ============================================

/**
 * Creates a prompt for performing a lightweight compilation check on generated source files.
 * This uses static analysis to identify obvious compilation errors without actually compiling.
 */
internal fun createCompilationCheckPrompt(
    projectProperties: IOSAppProjectProperties,
    sourceFiles: List<Pair<String, String>>, // List of (filePath, fileContent)
    architecture: ProjectArchitecture
): String {
    val filesContent = sourceFiles.joinToString("\n\n" + "=".repeat(80) + "\n\n") { (path, content) ->
        """
        FILE: $path
        ${"=".repeat(80)}
        $content
        """.trimIndent()
    }

    return """
        You are an expert ${projectProperties.developmentLanguage} compiler and static analyzer.
        Your task is to perform a LIGHTWEIGHT compilation check on the generated source code.

        PROJECT PROPERTIES:
        - Language: ${projectProperties.developmentLanguage}
        - Technology: ${projectProperties.implementTechnology}
        - UI Method: ${projectProperties.uiMethod}
        - Deployment Target: ${projectProperties.deploymentTarget}

        ARCHITECTURE SUMMARY:
        ${
        architecture.fileArchitectures.joinToString("\n") { fa ->
            "- ${fa.filePath}: ${fa.functions.size} functions, ${fa.dataStructures.size} types"
        }
    }

        SOURCE FILES TO CHECK:
        $filesContent

        YOUR TASK:
        Perform static analysis to identify compilation errors. Check for:

        1. SYNTAX ERRORS:
           - Missing braces, parentheses, brackets
           - Invalid syntax for the language
           - Malformed statements

        2. TYPE ERRORS:
           - Type mismatches in assignments
           - Incorrect function parameter types
           - Invalid return types
           - Missing type annotations (if required)

        3. UNDEFINED SYMBOLS:
           - Use of undefined variables
           - Calls to undefined functions
           - References to undefined types
           - Missing imports

        4. IMPORT/DEPENDENCY ERRORS:
           - Missing import statements
           - Incorrect import paths
           - Circular dependencies

        5. LANGUAGE-SPECIFIC ISSUES:
           For Swift:
           - Missing 'override' keyword
           - Protocol conformance issues
           - Optional handling issues
           - Access control problems

           For Kotlin:
           - Missing 'override' keyword
           - Nullable type issues
           - Visibility modifiers

           For JavaScript/TypeScript:
           - Missing exports/imports
           - Type annotation errors (TS)
           - Async/await issues

        OUTPUT FORMAT:
        Provide a CompilationCheckResult structure with:
        - success: true if no errors (warnings are OK), false if any errors found
        - errors: List of CompilationError for each error found
        - warnings: List of non-blocking warnings
        - summary: Brief assessment (e.g., "Found 3 syntax errors and 2 undefined symbols")

        For each error, provide:
        - filePath: Which file has the error
        - line: Line number (estimate if needed)
        - message: Clear error description
        - category: Error type (syntax, type_mismatch, undefined_symbol, import_error)
        - suggestedFix: How to fix it

        IMPORTANT GUIDELINES:
        - Be STRICT: Flag any obvious errors that would prevent compilation
        - Be PRACTICAL: Don't flag stylistic issues or minor warnings as errors
        - Be HELPFUL: Provide clear, actionable fix suggestions
        - Focus on REAL compilation errors, not code quality issues
        - If a file imports from another project file, assume that file exists and is correct

        DO NOT include markdown fences or explanatory text.
        Return ONLY the JSON structure.
    """.trimIndent()
}

/**
 * Creates a prompt for fixing compilation errors in a specific file.
 */
internal fun createCompilationFixPrompt(
    projectProperties: IOSAppProjectProperties,
    filePath: String,
    fileContent: String,
    errors: List<CompilationError>,
    architecture: ProjectArchitecture
): String {
    val fileArch = findFileArchitecture(architecture.fileArchitectures, filePath)
    val errorsDescription = errors.joinToString("\n") { error ->
        "- Line ${error.line}: [${error.category}] ${error.message}"
    }

    return """
        You are an expert ${projectProperties.developmentLanguage} developer fixing compilation errors.

        FILE TO FIX: $filePath

        FILE ARCHITECTURE (intended design):
        - Functions: ${fileArch?.functions?.joinToString(", ") { it.name } ?: "N/A"}
        - Data Structures: ${fileArch?.dataStructures?.joinToString(", ") { it.name } ?: "N/A"}
        - Dependencies: ${fileArch?.dependencies?.joinToString(", ") ?: "N/A"}

        CURRENT FILE CONTENT (with errors):
        $fileContent

        COMPILATION ERRORS TO FIX:
        $errorsDescription

        YOUR TASK:
        Fix ALL compilation errors while preserving the intended functionality and architecture.

        REQUIREMENTS:
        1. Fix all syntax errors
        2. Resolve all type mismatches
        3. Add missing imports
        4. Define missing symbols or fix incorrect references
        5. Ensure the fixed code matches the architecture design
        6. Preserve all comments and documentation
        7. Maintain code style and formatting

        OUTPUT FORMAT:
        Provide a CompilationFix structure with:
        - filePath: "${filePath}"
        - originalCode: The current code (copy from above)
        - fixedCode: The corrected code with all errors fixed
        - explanation: Brief description of what was fixed

        CRITICAL:
        - The fixedCode must be COMPLETE and READY to write to file
        - Fix ALL errors, not just some
        - Don't change functionality, only fix compilation issues
        - Ensure imports are correct for ${projectProperties.implementTechnology}

        DO NOT include markdown fences or explanatory text.
        Return ONLY the JSON structure.
    """.trimIndent()
}

// ============================================
// Competitor Analysis Helper Functions
// ============================================

/**
 * Formats competitor app information for use in design prompts.
 * This helper function takes raw competitor data from AppleAppInfoTools
 * and formats it into a readable analysis section.
 */
internal fun formatCompetitorAnalysisForDesign(
    competitorApps: List<com.fartech.agents.tools.AppleAppInfo>
): String {
    if (competitorApps.isEmpty()) {
        return ""
    }

    val analysisBuilder = StringBuilder()
    analysisBuilder.append("\n\n=== COMPETITOR ANALYSIS ===\n")
    analysisBuilder.append("Analyze the following competitor apps to inform your design decisions:\n\n")

    competitorApps.forEachIndexed { index, app ->
        analysisBuilder.append("COMPETITOR ${index + 1}: ${app.trackName ?: "Unknown App"}\n")
        analysisBuilder.append("---\n")

        // App metadata
        app.bundleId?.let { analysisBuilder.append("Bundle ID: $it\n") }
        app.primaryGenreName?.let { analysisBuilder.append("Category: $it\n") }
        app.description?.let { desc ->
            val truncated = if (desc.length > 500) desc.take(500) + "..." else desc
            analysisBuilder.append("Description: $truncated\n")
        }

        // Ratings and reviews
        app.averageUserRating?.let {
            analysisBuilder.append("Average Rating: $it/5.0")
            app.userRatingCount?.let { count -> analysisBuilder.append(" ($count ratings)") }
            analysisBuilder.append("\n")
        }

        // Visual assets
        app.artworkUrl512?.let { analysisBuilder.append("App Icon (512x512): $it\n") }
        app.screenshotUrls?.let { screenshots ->
            if (screenshots.isNotEmpty()) {
                analysisBuilder.append("Screenshots (${screenshots.size} available):\n")
                screenshots.take(5).forEachIndexed { idx, url ->
                    analysisBuilder.append("  Screenshot ${idx + 1}: $url\n")
                }
                if (screenshots.size > 5) {
                    analysisBuilder.append("  ... and ${screenshots.size - 5} more\n")
                }
            }
        }

        // Features and supported devices
        app.features?.let { features ->
            if (features.isNotEmpty()) {
                analysisBuilder.append("Key Features: ${features.joinToString(", ")}\n")
            }
        }

        // Release info
        app.version?.let { analysisBuilder.append("Current Version: $it\n") }
        app.releaseNotes?.let { notes ->
            val truncated = if (notes.length > 200) notes.take(200) + "..." else notes
            analysisBuilder.append("Recent Updates: $truncated\n")
        }

        analysisBuilder.append("\n")
    }

    analysisBuilder.append(
        """
        DESIGN INSIGHTS FROM COMPETITORS:
        - Analyze competitor app icons: color schemes, styles, symbolism
        - Study screenshot compositions: layouts, visual hierarchy, color usage
        - Note rating patterns: what users appreciate (high ratings) or criticize
        - Identify design trends in this category
        - Consider how to differentiate while meeting user expectations

        Your design should:
        ✓ Match or exceed competitor visual quality
        ✓ Differentiate with unique but familiar elements
        ✓ Address gaps or weaknesses in competitor designs
        ✓ Follow modern iOS design guidelines

    """.trimIndent()
    )

    return analysisBuilder.toString()
}

/**
 * Formats competitor app information for use in media asset generation prompts.
 * Focuses on visual elements and asset specifications.
 */
internal fun formatCompetitorAnalysisForAssets(
    competitorApps: List<com.fartech.agents.tools.AppleAppInfo>
): String {
    if (competitorApps.isEmpty()) {
        return ""
    }

    val analysisBuilder = StringBuilder()
    analysisBuilder.append("\n\n=== COMPETITOR VISUAL ANALYSIS ===\n")
    analysisBuilder.append("Reference these competitor apps for visual asset generation:\n\n")

    competitorApps.forEachIndexed { index, app ->
        analysisBuilder.append("COMPETITOR ${index + 1}: ${app.trackName ?: "Unknown App"}\n")

        // Icon assets
        analysisBuilder.append("\nApp Icon Analysis:\n")
        app.artworkUrl512?.let {
            analysisBuilder.append("  - 512x512 Icon: $it\n")
            analysisBuilder.append("    Study: color scheme, icon style (flat/gradient/3D), symbolism\n")
        }
        app.artworkUrl100?.let { analysisBuilder.append("  - 100x100 Icon: $it\n") }
        app.artworkUrl60?.let { analysisBuilder.append("  - 60x60 Icon: $it\n") }

        // Screenshots
        app.screenshotUrls?.let { screenshots ->
            if (screenshots.isNotEmpty()) {
                analysisBuilder.append("\nScreen Design References (${screenshots.size} screenshots):\n")
                screenshots.take(3).forEachIndexed { idx, url ->
                    analysisBuilder.append("  - Screenshot ${idx + 1}: $url\n")
                    analysisBuilder.append("    Analyze: UI layout, color palette, imagery style, typography\n")
                }
                if (screenshots.size > 3) {
                    analysisBuilder.append("  - Plus ${screenshots.size - 3} more screenshots\n")
                }
            }
        }

        // iPad screenshots if available
        app.ipadScreenshotUrls?.let { ipadScreenshots ->
            if (ipadScreenshots.isNotEmpty()) {
                analysisBuilder.append("\niPad Design References: ${ipadScreenshots.size} screenshots available\n")
            }
        }

        app.primaryGenreName?.let {
            analysisBuilder.append("\nCategory: $it (consider genre-specific visual conventions)\n")
        }

        analysisBuilder.append("\n")
    }

    analysisBuilder.append(
        """
        ASSET GENERATION GUIDELINES BASED ON COMPETITOR ANALYSIS:

        1. APP ICON DESIGN:
           - Analyze competitor icon styles: Are they minimalist, detailed, gradient-based?
           - Note color palette trends: Bright vs. muted, monochromatic vs. multicolor
           - Identify common symbols or metaphors in this app category
           - DIFFERENTIATE: Create a unique icon that stands out while fitting category expectations

        2. SCREENSHOT & UI ASSETS:
           - Study competitor color schemes and apply modern, competitive palette
           - Note UI element styles: buttons, cards, icons (flat, rounded, shadows)
           - Analyze imagery style: illustrations vs. photos, realistic vs. abstract
           - Consider visual hierarchy and spacing patterns

        3. VISUAL QUALITY STANDARDS:
           - Your assets must match or exceed competitor visual quality
           - Use high resolution, clean designs
           - Follow iOS Human Interface Guidelines
           - Ensure consistency across all assets

        4. COMPETITIVE ADVANTAGES:
           - Identify visual weaknesses in competitor designs (outdated styles, poor contrast, etc.)
           - Use modern design trends that competitors may lack
           - Create memorable, distinctive visual identity

    """.trimIndent()
    )

    return analysisBuilder.toString()
}


// ============================================
// Design & Slicing Review Prompts
// ============================================

internal fun createDesignReviewPrompt(
    projectProperties: IOSAppProjectProperties,
    designSpec: DesignSpecification
): String {
    val screenCount = designSpec.screens.size
    val assetCount = designSpec.assets.size
    val sampleScreens = designSpec.screens.take(8).joinToString("\n") { s ->
        "- ${s.name} (layout=${s.layout}) elements=${s.elements.size}"
    }.ifBlank { "(no screens)" }

    return """
        You are a senior mobile product designer and UI/UX reviewer.
        Review the following iOS app design specification for QUALITY and COMPLETENESS.

        PROJECT PROPERTIES:
        $projectProperties

        DESIGN SUMMARY:
        Screens: $screenCount
        Assets: $assetCount
        Sample screens:
        $sampleScreens

        REQUIREMENTS FOR APPROVAL:
        - Clarity: specs must be unambiguous and implementation-ready
        - Completeness: all key screens/components/states are covered
        - Consistency: typography, spacing, and color usage is consistent
        - Accessibility: basic contrast and tappable area rules considered
        - Assets: every referenced image/icon/background is specified with size

        OUTPUT: Structure matching DesignReviewResult
        - qualityScore: Int (0-100)
        - approved: Boolean (true only if quality and completeness are acceptable)
        - complete: Boolean
        - issues: List<String>
        - missingItems: List<String>
        - summary: String

        Return ONLY the JSON structure without extra commentary.
    """.trimIndent()
}

internal fun createMediaAssetsReviewPrompt(
    projectProperties: IOSAppProjectProperties,
    manifest: MediaAssetsManifest
): String {
    val total = manifest.assets.size
    val icons = manifest.assets.count { it.purpose.contains("app_icon") }
    val launches = manifest.assets.count { it.purpose.contains("launch") }
    val others = total - icons - launches

    return """
        You are an expert iOS visual asset reviewer.
        Review the media assets manifest for COMPLETENESS and PRODUCTION READINESS.

        PROJECT PROPERTIES:
        $projectProperties

        MANIFEST SUMMARY:
        Total assets=$total, icons=$icons, launch=$launches, other UI=$others

        CRITICAL REQUIREMENTS:
        - App Icon: include ALL required sizes for an .appiconset (at least 12 sizes)
        - Launch Screen: at least one suitable launch image or storyboard background asset
        - UI Assets: every referenced visual element exists with exact dimensions and purpose
        - No obvious omissions based on typical iOS app needs

        OUTPUT: Structure matching MediaAssetsReviewResult
        - qualityScore: Int (0-100)
        - approved: Boolean (true only if critical requirements are met)
        - complete: Boolean
        - missingAssets: List<String>
        - summary: String

        Return ONLY the JSON structure without extra commentary.
    """.trimIndent()
}
