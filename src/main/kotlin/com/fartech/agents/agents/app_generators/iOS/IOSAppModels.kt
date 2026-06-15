package com.fartech.agents.agents.app_generators.iOS

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.fartech.agents.commons.AttachmentFile
import com.fartech.agents.commons.DEFAULT_LLM_MODEL_CONFIG
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IOSAgentDefinedSettings(
    val modelAssignments: ModelAssignments = ModelAssignments()
) {
    @Serializable
    data class ModelAssignments(
        val default: String? = DEFAULT_LLM_MODEL_CONFIG.model,
        val setup: String? = default,
        val design: String? = default,
        val reviewDesign: String? = default,
        val designArchitecture: String? = default,
        val reviewArchitecture: String? = default,
        val generateFileList: String? = default,
        val generateCode: String? = default,
        val build: String? = default,
        val debug: String? = default,
        val testing: String? = default,
        val generateImage: String? = default
    )
}

@Serializable
@LLMDescription("A structure containing iOS App Project Properties")
data class IOSAppProjectProperties(
    @property:LLMDescription("The name of the iOS application")
    val appName: String? = "appName",
    @property:LLMDescription("The Bundle ID of the iOS application")
    val bundleId: String? = "com.example.appName",
    @property:LLMDescription("The detailed description of the iOS application")
    val description: String? = "This is a sample iOS application that does nothing, only for demo purposes.",
    @property:LLMDescription("The list of supported iOS application languages")
    val appLanguages: List<String>? = listOf("en-us", "zh-Hans"),
    @property:LLMDescription("The minimum iOS version supported by the iOS application")
    val deploymentTarget: String? = "14.0",
    @property:LLMDescription("The organization name of the iOS application")
    val organizationName: String? = "DefaultOrganization",
    @property:LLMDescription("The team ID of the iOS application")
    val teamId: String? = "DefaultTeam",
    @property:LLMDescription("The type of iOS application development used by the iOS application (Native/Flutter/React Native/UniApp/Kotlin Multiplatform)")
    val implementTechnology: ImplementTechnology? = ImplementTechnology.Native,
    @property:LLMDescription("The development language used by the iOS application (Swift/Objective-C)")
    val developmentLanguage: DevelopmentLanguage? = DevelopmentLanguage.Swift,
    @property:LLMDescription("The Swift version used by the iOS application")
    val swiftVersion: String? = "5.0",
    @property:LLMDescription("The type of iOS application UI used by the iOS application (SwiftUI/UIKit, if applicable)")
    val uiMethod: UIMethod? = UIMethod.SwiftUI,
    @property:LLMDescription("Whether to overwrite existing files when generating the iOS application project")
    val overwrite: Boolean? = false,
    @property:LLMDescription("The directory where the iOS application project files will be generated")
    val projectRoot: String? = ".",
    @property:LLMDescription("The list of design files for the iOS application (if applicable)")
    val designFiles: List<AttachmentFile> = emptyList(),
    @property:LLMDescription("The list of third-party dependencies to integrate (CocoaPods format, e.g., 'Alamofire', 'SnapKit ~> 5.0')")
    val thirdPartyDependencies: List<String> = emptyList(),
    @property:LLMDescription("The list of competitor app IDs from App Store for competitive analysis. Format: Apple App Store numeric ID (e.g., '310633997' for WhatsApp). When provided, the system will fetch competitor app information (screenshots, icons, descriptions, ratings) to inform design decisions and create more competitive products. Example: ['310633997', '544007664'] for WhatsApp and Facebook Messenger")
    val competitorAppIds: List<String> = emptyList()
) {
    @Serializable
    @LLMDescription("The development language used by the iOS application")
    enum class DevelopmentLanguage {
        @LLMDescription("Swift")
        Swift,

        @LLMDescription("Objective-C")
        ObjectiveC
    }

    @Serializable
    @LLMDescription("The type of iOS application development used by the iOS application")
    enum class ImplementTechnology {
        @LLMDescription("This project uses native iOS development")
        Native,

        @LLMDescription("This project uses Flutter to develop")
        Flutter,

        @LLMDescription("This project uses React Native to develop")
        ReactNative,

        @LLMDescription("This project uses UniApp to develop")
        UniApp,

        @LLMDescription("This project uses Kotlin Multiplatform to develop")
        KotlinMpp
    }

    @Serializable
    @LLMDescription("The UI framework used by the iOS application")
    enum class UIMethod {
        @LLMDescription("This project uses SwiftUI to develop")
        SwiftUI,

        @LLMDescription("This project uses UIKit to develop")
        UIKit
    }

    override fun toString(): String = """
        IOSAppProjectProperties(
          appName='$appName',
          bundleId='$bundleId',
          description='$description',
          appLanguages=$appLanguages,
          deploymentTarget='$deploymentTarget',
          organizationName='$organizationName',
          teamId='$teamId',
          implementTechnology=$implementTechnology,
          developmentLanguage=$developmentLanguage,
          swiftVersion='$swiftVersion',
          uiMethod=$uiMethod,
          overwrite=$overwrite,
          projectRoot='$projectRoot',
          designFiles=${designFiles.size} file(s),
          thirdPartyDependencies=${thirdPartyDependencies.size} dependencies,
          competitorAppIds=${competitorAppIds.size} competitor(s)
        )
    """.trimIndent()
}

@Serializable
@LLMDescription("A structure describing the files and directories included in the iOS app project")
data class IOSAppProjectFiles(
    @property:LLMDescription("The list of files and directories in the iOS app project")
    val files: List<IOSAppProjectFile> = emptyList()
)

@Serializable
@LLMDescription("Describes a single file or directory in the iOS app project")
data class IOSAppProjectFile(
    @property:LLMDescription("The name of the file or directory")
    val name: String = "NONAME",
    @property:LLMDescription("The path to the file or directory")
    val path: String = "./",
    @property:LLMDescription("Whether the file or directory is a directory")
    val isDir: Boolean = false,
    @property:LLMDescription("The MIME type of the file (if applicable)")
    val mime: String = "text/plain",
    @property:LLMDescription("The description of the file or directory")
    val description: String = "empty text file"
)

@Serializable
@LLMDescription("Defines a function signature in a file")
data class FunctionSignature(
    @property:LLMDescription("Function name")
    val name: String,
    @property:LLMDescription("Function parameters with types, e.g., 'userId: String, age: Int'")
    val parameters: String = "",
    @property:LLMDescription("Return type of the function")
    val returnType: String = "void",
    @property:LLMDescription("Brief description of what this function does")
    val description: String = "",
    @property:LLMDescription("Access modifier (public, private, internal, etc.)")
    val accessModifier: String = "public",
    @property:LLMDescription("Pseudocode or implementation logic outline for this function")
    val implementationLogic: String = "",
    @property:LLMDescription("Usage example showing how this function should be called")
    val usageExample: String = "",
    @property:LLMDescription("Error handling strategy: what exceptions/errors to handle and how")
    val errorHandling: String = ""
)

@Serializable
@LLMDescription("Defines a global variable or constant in a file")
data class GlobalVariable(
    @property:LLMDescription("Variable name")
    val name: String,
    @property:LLMDescription("Variable type")
    val type: String,
    @property:LLMDescription("Brief description")
    val description: String = "",
    @property:LLMDescription("Whether it's a constant (let, const, final)")
    val isConstant: Boolean = false
)

@Serializable
@LLMDescription("Defines a data structure (class/struct/enum/protocol/interface) in a file")
data class DataStructure(
    @property:LLMDescription("Name of the data structure")
    val name: String,
    @property:LLMDescription("Type: class, struct, enum, protocol, interface, etc.")
    val type: String,
    @property:LLMDescription("Property definitions, e.g., 'id: String, name: String, age: Int'")
    val properties: String = "",
    @property:LLMDescription("Brief description of this data structure")
    val description: String = "",
    @property:LLMDescription("Parent class or implemented protocols/interfaces")
    val inheritsFrom: String = "",
    @property:LLMDescription("For enums: list all cases with raw values. For protocols: list all required methods")
    val detailedMembers: String = "",
    @property:LLMDescription("Initialization requirements and example constructors")
    val initializationExample: String = "",
    @property:LLMDescription("Usage example showing how to create and use instances of this type")
    val usageExample: String = ""
)

@Serializable
@LLMDescription("Architecture metadata for a single source file")
data class FileArchitecture(
    @property:LLMDescription("File path (same as in IOSAppProjectFile)")
    val filePath: String,
    @property:LLMDescription("List of function signatures defined in this file")
    val functions: List<FunctionSignature> = emptyList(),
    @property:LLMDescription("List of global variables/constants")
    val globalVariables: List<GlobalVariable> = emptyList(),
    @property:LLMDescription("List of data structures (classes, structs, enums, protocols)")
    val dataStructures: List<DataStructure> = emptyList(),
    @property:LLMDescription("List of dependencies (imports/includes from other files in this project)")
    val dependencies: List<String> = emptyList(),
    @property:LLMDescription("Architectural notes or design decisions for this file")
    val architecturalNotes: String = "",
    @property:LLMDescription("Cross-file interaction patterns: how this file uses types/functions from dependencies")
    val dependencyUsagePatterns: String = "",
    @property:LLMDescription("Complete code example showing typical usage of this file's public API")
    val fileUsageExample: String = "",
    @property:LLMDescription("Whether this is a critical file - failure to generate blocks the entire process. Examples: main entry point (e.g., AppDelegate, main.swift), core data models, essential managers")
    val isCritical: Boolean = false
)

@Serializable
@LLMDescription("Project-wide architecture containing file-level architectures and principles")
data class ProjectArchitecture(
    @property:LLMDescription("List of file architectures for all source code files")
    val fileArchitectures: List<FileArchitecture> = emptyList(),
    @property:LLMDescription("Global architectural principles and patterns")
    val globalPrinciples: String = "",
    @property:LLMDescription("Common naming conventions")
    val namingConventions: String = "",
    @property:LLMDescription("Dependency graph summary")
    val dependencyGraph: String = "",
    @property:LLMDescription("Common code patterns and idioms to use throughout the project")
    val codePatterns: String = "",
    @property:LLMDescription("OPTIONAL: Updated file list when architecture redesign requires new files (e.g., adding ViewModel layer). Only provide if files need to be added/removed during redesign. Leave null for initial design or if no file changes needed.")
    val updatedFileList: IOSAppProjectFiles? = null,
    @property:LLMDescription("Error handling strategy for the entire application")
    val errorHandlingStrategy: String = "",
    @property:LLMDescription("Type system conventions: how to use optionals, generics, type aliases")
    val typeSystemConventions: String = ""
)

@Serializable
@LLMDescription("Unified architecture review result combining quality assessment and completeness check")
data class UnifiedArchitectureReview(
    @property:LLMDescription("Overall quality score from 0 (poor) to 100 (excellent)")
    val qualityScore: Int,
    @property:LLMDescription("Whether the architecture is approved for code generation (true if qualityScore >= threshold AND no missing files)")
    val approved: Boolean,
    @property:LLMDescription("Whether the architecture is functionally complete (all required features are covered)")
    val complete: Boolean,
    @property:LLMDescription("List of design quality issues found (architecture patterns, error handling, performance, etc.)")
    val designIssues: List<DesignIssue> = emptyList(),
    @SerialName("missing_files")
    @property:LLMDescription("List of missing files/directories needed to complete the app functionality")
    val missingFiles: List<IOSAppProjectFile> = emptyList(),
    @property:LLMDescription("List of recommendations for improvement")
    val recommendations: List<String> = emptyList(),
    @property:LLMDescription("Summary of the review covering both quality and completeness aspects")
    val summary: String = ""
)

@Serializable
@LLMDescription("Review result for UI/UX design specification")
data class DesignReviewResult(
    @property:LLMDescription("Overall quality score from 0 to 100")
    val qualityScore: Int = 0,
    @property:LLMDescription("Whether the design spec is approved (meets quality and completeness gates)")
    val approved: Boolean = false,
    @property:LLMDescription("Whether the design is complete enough for slicing")
    val complete: Boolean = true,
    @property:LLMDescription("List of issues found in the design spec")
    val issues: List<String> = emptyList(),
    @property:LLMDescription("List of missing items (screens, components, states)")
    val missingItems: List<String> = emptyList(),
    @property:LLMDescription("Summary of the review")
    val summary: String = ""
)

@Serializable
@LLMDescription("Review result for media/assets manifest completeness and quality")
data class MediaAssetsReviewResult(
    @property:LLMDescription("Overall quality score from 0 to 100")
    val qualityScore: Int = 0,
    @property:LLMDescription("Whether the assets manifest is approved for downstream generation")
    val approved: Boolean = false,
    @property:LLMDescription("Whether critical assets are complete (app icon set, launch screen, required UI assets)")
    val complete: Boolean = true,
    @property:LLMDescription("Missing critical assets or sizes")
    val missingAssets: List<String> = emptyList(),
    @property:LLMDescription("Summary of the review")
    val summary: String = ""
)

// Legacy data classes - kept for backward compatibility during migration
@Serializable
@LLMDescription("Evaluation result for architecture completeness (LEGACY - use UnifiedArchitectureReview)")
data class ArchitectureEvaluation(
    @property:LLMDescription("Whether current layout is sufficient to fully reproduce the app")
    val sufficient: Boolean,
    @SerialName("missing_files")
    @property:LLMDescription("List of missing files/directories needed")
    val missingFiles: List<IOSAppProjectFile> = emptyList(),
    @property:LLMDescription("Reason why the architecture is insufficient (if applicable)")
    val reason: String = ""
)

@Serializable
@LLMDescription("A specific design issue in the architecture")
data class DesignIssue(
    @property:LLMDescription("Severity level: critical (blocks generation), warning (should fix), info (nice to have)")
    val severity: String,
    @property:LLMDescription("Issue category: circular_dependency, poor_api_design, missing_error_handling, performance_concern, maintainability, etc.")
    val category: String,
    @property:LLMDescription("Detailed description of the issue")
    val description: String,
    @property:LLMDescription("List of files or components affected by this issue")
    val affectedFiles: List<String> = emptyList(),
    @property:LLMDescription("Suggested fix for this issue")
    val suggestedFix: String = ""
)

/**
 * Tracks file generation failures for retry mechanism.
 * Key: file path, Value: retry count
 */
internal data class FileGenerationFailures(
    val failures: MutableMap<String, Int> = mutableMapOf()
) {
    fun recordFailure(filePath: String) {
        failures[filePath] = (failures[filePath] ?: 0) + 1
    }

    fun getRetryCount(filePath: String): Int = failures[filePath] ?: 0

    fun getFailedFiles(): List<String> = failures.keys.toList()

    fun clearFile(filePath: String) {
        failures.remove(filePath)
    }
}

@Serializable
@LLMDescription("Result of a lightweight compilation check")
data class CompilationCheckResult(
    @property:LLMDescription("Whether the code compiles successfully")
    val success: Boolean,
    @property:LLMDescription("List of compilation errors found")
    val errors: List<CompilationError> = emptyList(),
    @property:LLMDescription("List of warnings (non-blocking)")
    val warnings: List<String> = emptyList(),
    @property:LLMDescription("Overall assessment of code quality based on compilation")
    val summary: String = ""
)

@Serializable
@LLMDescription("A single compilation error")
data class CompilationError(
    @property:LLMDescription("File path where the error occurred")
    val filePath: String,
    @property:LLMDescription("Line number (if available)")
    val line: Int = 0,
    @property:LLMDescription("Error message")
    val message: String,
    @property:LLMDescription("Error category: syntax, type_mismatch, undefined_symbol, import_error, etc.")
    val category: String = "unknown",
    @property:LLMDescription("Suggested fix for this error")
    val suggestedFix: String = ""
)

@Serializable
@LLMDescription("Fix instructions for compilation errors")
data class CompilationFix(
    @property:LLMDescription("File path to fix")
    val filePath: String,
    @property:LLMDescription("Original code that has errors (for context)")
    val originalCode: String,
    @property:LLMDescription("Fixed code that resolves the compilation errors")
    val fixedCode: String,
    @property:LLMDescription("Explanation of what was fixed")
    val explanation: String
)

// ============================================
// Design Specification Models (Phase 0)
// ============================================

@Serializable
@LLMDescription("Complete design specification for an iOS app")
data class DesignSpecification(
    @property:LLMDescription("App name")
    val appName: String = "",
    @property:LLMDescription("App icon specification")
    val appIcon: AppIconSpec = AppIconSpec(),
    @property:LLMDescription("Launch screen specification")
    val launchScreen: LaunchScreenSpec = LaunchScreenSpec(),
    @property:LLMDescription("Color palette for the app")
    val colorPalette: ColorPalette = ColorPalette(),
    @property:LLMDescription("Typography specifications")
    val typography: Typography = Typography(),
    @property:LLMDescription("List of screen specifications")
    val screens: List<ScreenSpec> = emptyList(),
    @property:LLMDescription("List of asset specifications")
    val assets: List<AssetSpec> = emptyList(),
    @property:LLMDescription("Animation specifications")
    val animations: List<AnimationSpec> = emptyList(),
    @property:LLMDescription("Semantic theme specification for light/dark modes and color tokens")
    val theme: ThemeSpec? = null,
    @property:LLMDescription("Design tokens including typography, spacing, radii, elevation, strokes")
    val tokens: DesignTokens? = null,
    @property:LLMDescription("Accessibility specification including WCAG contrast checks and Dynamic Type policy")
    val accessibility: AccessibilitySpec? = null,
    @property:LLMDescription("Component library specification including states and variants for key controls")
    val components: ComponentLibrarySpec? = null,
    @property:LLMDescription("Spacing, grid, radius, elevation, divider, and touch target specifications")
    val spacingAndGrid: SpacingAndGridSpec? = null,
    @property:LLMDescription("Tab Bar specification including items, colors, and safe area behavior")
    val tabBar: TabBarSpec? = null
)

@Serializable
@LLMDescription("App icon design specification")
data class AppIconSpec(
    @property:LLMDescription("Icon style: minimalist/skeuomorphic/flat/gradient")
    val style: String = "",
    @property:LLMDescription("Primary color (hex format)")
    val primaryColor: String = "",
    @property:LLMDescription("Secondary color (hex format)")
    val secondaryColor: String = "",
    @property:LLMDescription("Symbol or concept: crown/star/heart/abstract/letter")
    val symbol: String = "",
    @property:LLMDescription("Detailed description for icon generation")
    val description: String = ""
)

@Serializable
@LLMDescription("Launch screen design specification")
data class LaunchScreenSpec(
    @property:LLMDescription("Background color (hex format)")
    val backgroundColor: String = "",
    @property:LLMDescription("Logo position: center/top/bottom")
    val logoPosition: String = "",
    @property:LLMDescription("Tagline or app slogan")
    val tagline: String = "",
    @property:LLMDescription("Animation duration in seconds")
    val animationDuration: Double = 0.0,
    @property:LLMDescription("Detailed description for launch screen")
    val description: String = ""
)

@Serializable
@LLMDescription("Color palette for the app")
data class ColorPalette(
    @property:LLMDescription("Primary color (hex)")
    val primary: String = "",
    @property:LLMDescription("Secondary color (hex)")
    val secondary: String = "",
    @property:LLMDescription("Accent color (hex)")
    val accent: String = "",
    @property:LLMDescription("Background color (hex)")
    val background: String = "",
    @property:LLMDescription("Surface color (hex)")
    val surface: String = "",
    @property:LLMDescription("Error color (hex)")
    val error: String = "",
    @property:LLMDescription("Primary text color (hex)")
    val textPrimary: String = "",
    @property:LLMDescription("Secondary text color (hex)")
    val textSecondary: String = ""
)

@Serializable
@LLMDescription("Typography specifications")
data class Typography(
    @property:LLMDescription("Heading font family")
    val headingFont: String = "",
    @property:LLMDescription("Body font family")
    val bodyFont: String = "",
    @property:LLMDescription("Heading font size in points")
    val headingSize: Int = 0,
    @property:LLMDescription("Body font size in points")
    val bodySize: Int = 0
)

@Serializable
@LLMDescription("Screen specification in the app")
data class ScreenSpec(
    @property:LLMDescription("Screen name")
    val name: String,
    @property:LLMDescription("Purpose of the screen")
    val purpose: String,
    @property:LLMDescription("Layout type: single_column/grid/list/tabs")
    val layout: String,
    @property:LLMDescription("UI elements in the screen")
    val elements: List<UIElementSpec>
)

@Serializable
@LLMDescription("UI element specification")
data class UIElementSpec(
    @property:LLMDescription("Element type: button/image/text/input/icon")
    val type: String,
    @property:LLMDescription("Position description")
    val position: String,
    @property:LLMDescription("Size description")
    val size: String,
    @property:LLMDescription("Detailed description")
    val description: String,
    @property:LLMDescription("Whether this element requires an asset")
    val assetRequired: Boolean
)

@Serializable
@LLMDescription("Asset specification in design")
data class AssetSpec(
    @property:LLMDescription("Asset name")
    val name: String,
    @property:LLMDescription("Asset type: icon/image/background/button")
    val type: String,
    @property:LLMDescription("Width in pixels")
    val width: Int,
    @property:LLMDescription("Height in pixels")
    val height: Int,
    @property:LLMDescription("Purpose of the asset")
    val purpose: String,
    @property:LLMDescription("Detailed description for generation")
    val detailedDescription: String,
    @property:LLMDescription("Scale factors to export, e.g., ['1x','2x','3x']")
    val scales: List<String>? = null,
    @property:LLMDescription("Whether a vector source (PDF/SVG) is provided")
    val vector: Boolean? = null,
    @property:LLMDescription("Naming convention for exported files")
    val namingConvention: String? = null,
    @property:LLMDescription("Export formats, e.g., ['PNG','PDF','SVG']")
    val exportFormats: List<String>? = null,
    @property:LLMDescription("Compression strategy, e.g., 'lossless', 'webp-85'")
    val compression: String? = null,
    @property:LLMDescription("Theme variants provided, e.g., ['light','dark']")
    val themeVariants: List<String>? = null
)

@Serializable
@LLMDescription("Animation specification")
data class AnimationSpec(
    @property:LLMDescription("Animation trigger event")
    val trigger: String,
    @property:LLMDescription("Animation type")
    val type: String,
    @property:LLMDescription("Duration in seconds")
    val duration: Double,
    @property:LLMDescription("Description of the animation")
    val description: String
)

// ============================================
// Media Assets Manifest Models (Phase 1)
// ============================================

@Serializable
@LLMDescription("Complete manifest of all media assets required for the app")
data class MediaAssetsManifest(
    @property:LLMDescription("List of all media asset specifications")
    val assets: List<MediaAssetSpec>
)

@Serializable
@LLMDescription("Specification for a single media asset")
data class MediaAssetSpec(
    @property:LLMDescription("Asset file name")
    val name: String,
    @property:LLMDescription("Full file path relative to project root")
    val path: String,
    @property:LLMDescription("Asset type: icon/image/video/audio")
    val type: String,
    @property:LLMDescription("Width in pixels")
    val width: Int,
    @property:LLMDescription("Height in pixels")
    val height: Int,
    @property:LLMDescription("Purpose: app_icon/launch_screen/background/button/etc")
    val purpose: String,
    @property:LLMDescription("Extremely detailed description for accurate generation")
    val detailedDescription: String,
    @property:LLMDescription("Asset group: .imageset/.appiconset/null")
    val group: String? = null,
    @property:LLMDescription("Scale factor: @2x/@3x/null")
    val scale: String? = null
)

private val exampleMissingAssetsCatalog = IOSAppProjectFile(
    name = "Assets.xcassets",
    path = "Resources/Assets.xcassets",
    isDir = true,
    mime = "application/octet-stream",
    description = "Asset catalog for app icons and images"
)

private val exampleMissingLaunchScreen = IOSAppProjectFile(
    name = "LaunchScreen.storyboard",
    path = "Resources/LaunchScreen.storyboard",
    isDir = false,
    mime = "text/plain",
    description = "Launch screen for iOS app"
)

// ============================================
// Example Data for Structured Outputs
// ============================================

// Example 1: Native iOS SwiftUI Project
internal val exampleIOSAppProjectFiles_NativeSwiftUI = IOSAppProjectFiles(
    files = listOf(
        // ========== Core Application Files ==========
        IOSAppProjectFile(
            name = "AppNameApp.swift",
            path = "Sources/AppNameApp.swift",
            isDir = false,
            mime = "text/plain",
            description = """
                SwiftUI App entry point with @main annotation. This is the application lifecycle entry:

                import SwiftUI

                @main
                struct AppNameApp: App {
                    var body: some Scene {
                        WindowGroup {
                            ContentView()
                        }
                    }
                }
            """.trimIndent()
        ),
        IOSAppProjectFile(
            name = "ContentView.swift",
            path = "Sources/ContentView.swift",
            isDir = false,
            mime = "text/plain",
            description = "Main view using SwiftUI, displays a list of items with navigation. Includes #Preview for live preview support"
        ),

        // ========== Data Models ==========
        IOSAppProjectFile(
            name = "ItemModel.swift",
            path = "Sources/Models/ItemModel.swift",
            isDir = false,
            mime = "text/plain",
            description = "Data model representing an item with id, title, description properties. Conforms to Codable and Identifiable"
        ),

        // ========== Services ==========
        IOSAppProjectFile(
            name = "NetworkService.swift",
            path = "Sources/Services/NetworkService.swift",
            isDir = false,
            mime = "text/plain",
            description = "Service class for handling API requests using URLSession and Combine publishers"
        ),

        // ========== Asset Catalog ==========
        IOSAppProjectFile(
            name = "Assets.xcassets",
            path = "Resources/Assets.xcassets",
            isDir = true,
            mime = "application/octet-stream",
            description = "Asset catalog containing app icons, colors, and images"
        ),
        IOSAppProjectFile(
            name = "Contents.json",
            path = "Resources/Assets.xcassets/Contents.json",
            isDir = false,
            mime = "application/json",
            description = """
                Asset catalog root configuration:
                {
                  "info" : {
                    "author" : "xcode",
                    "version" : 1
                  }
                }
            """.trimIndent()
        ),

        // ========== App Icon ==========
        IOSAppProjectFile(
            name = "AppIcon.appiconset",
            path = "Resources/Assets.xcassets/AppIcon.appiconset",
            isDir = true,
            mime = "application/octet-stream",
            description = "App icon asset set directory"
        ),
        IOSAppProjectFile(
            name = "Contents.json",
            path = "Resources/Assets.xcassets/AppIcon.appiconset/Contents.json",
            isDir = false,
            mime = "application/json",
            description = """
                App icon asset configuration for iOS:
                {
                  "images" : [
                    {
                      "filename" : "Icon.png",
                      "idiom" : "universal",
                      "platform" : "ios",
                      "size" : "1024x1024"
                    }
                  ],
                  "info" : {
                    "author" : "xcode",
                    "version" : 1
                  }
                }
            """.trimIndent()
        ),
        IOSAppProjectFile(
            name = "Icon.png",
            path = "Resources/Assets.xcassets/AppIcon.appiconset/Icon.png",
            isDir = false,
            mime = "image/png",
            description = "App icon: A square icon with rounded corners (20pt radius). Background is a gradient from #4A90E2 (top) to #357ABD (bottom). Contains a centered white letter 'A' in SF Pro Display font, size 48pt. Dimensions: 1024x1024 points."
        ),

        // ========== Accent Color ==========
        IOSAppProjectFile(
            name = "AccentColor.colorset",
            path = "Resources/Assets.xcassets/AccentColor.colorset",
            isDir = true,
            mime = "application/octet-stream",
            description = "App accent color for SwiftUI tint and theme customization"
        ),
        IOSAppProjectFile(
            name = "Contents.json",
            path = "Resources/Assets.xcassets/AccentColor.colorset/Contents.json",
            isDir = false,
            mime = "application/json",
            description = """
                Accent color configuration with optional light/dark mode variants:
                {
                  "colors" : [
                    {
                      "idiom" : "universal"
                    }
                  ],
                  "info" : {
                    "author" : "xcode",
                    "version" : 1
                  }
                }
            """.trimIndent()
        ),

        // ========== Preview Content ==========
        IOSAppProjectFile(
            name = "Preview Content",
            path = "Sources/Preview Content",
            isDir = true,
            mime = "application/octet-stream",
            description = "Directory for SwiftUI preview-only assets (excluded from release builds)"
        ),
        IOSAppProjectFile(
            name = "Preview Assets.xcassets",
            path = "Sources/Preview Content/Preview Assets.xcassets",
            isDir = true,
            mime = "application/octet-stream",
            description = "Asset catalog for preview resources"
        ),
        IOSAppProjectFile(
            name = "Contents.json",
            path = "Sources/Preview Content/Preview Assets.xcassets/Contents.json",
            isDir = false,
            mime = "application/json",
            description = """
                Preview assets configuration:
                {
                  "info" : {
                    "author" : "xcode",
                    "version" : 1
                  }
                }
            """.trimIndent()
        ),

        // ========== Unit Tests ==========
        IOSAppProjectFile(
            name = "AppNameTests",
            path = "AppNameTests",
            isDir = true,
            mime = "application/octet-stream",
            description = "Unit test target directory"
        ),
        IOSAppProjectFile(
            name = "AppNameTests.swift",
            path = "AppNameTests/AppNameTests.swift",
            isDir = false,
            mime = "text/plain",
            description = """
                Unit test suite using XCTest framework:

                import XCTest
                @testable import AppName

                final class AppNameTests: XCTestCase {
                    func testExample() throws {
                        // Test implementation
                        XCTAssertTrue(true)
                    }
                }
            """.trimIndent()
        ),

        // ========== UI Tests ==========
        IOSAppProjectFile(
            name = "AppNameUITests",
            path = "AppNameUITests",
            isDir = true,
            mime = "application/octet-stream",
            description = "UI test target directory"
        ),
        IOSAppProjectFile(
            name = "AppNameUITests.swift",
            path = "AppNameUITests/AppNameUITests.swift",
            isDir = false,
            mime = "text/plain",
            description = """
                UI test suite using XCUITest framework:

                import XCTest

                final class AppNameUITests: XCTestCase {
                    func testLaunch() throws {
                        let app = XCUIApplication()
                        app.launch()
                        XCTAssertTrue(app.exists)
                    }
                }
            """.trimIndent()
        ),
        IOSAppProjectFile(
            name = "AppNameUITestsLaunchTests.swift",
            path = "AppNameUITests/AppNameUITestsLaunchTests.swift",
            isDir = false,
            mime = "text/plain",
            description = """
                Launch test for capturing app screenshots:

                import XCTest

                final class AppNameUITestsLaunchTests: XCTestCase {
                    func testLaunch() throws {
                        let app = XCUIApplication()
                        app.launch()

                        let attachment = XCTAttachment(screenshot: app.screenshot())
                        attachment.name = "Launch Screen"
                        attachment.lifetime = .keepAlways
                        add(attachment)
                    }
                }
            """.trimIndent()
        ),

        // ========== Documentation ==========
        IOSAppProjectFile(
            name = "README.md",
            path = "README.md",
            isDir = false,
            mime = "text/markdown",
            description = "Project documentation with build instructions, architecture overview, and XcodeGen usage"
        )
    )
)

// Example 2: Flutter Project
internal val exampleIOSAppProjectFiles_Flutter = IOSAppProjectFiles(
    files = listOf(
        IOSAppProjectFile(
            name = "main.dart",
            path = "lib/main.dart",
            isDir = false,
            mime = "text/plain",
            description = "Flutter app entry point with MaterialApp and home screen setup"
        ),
        IOSAppProjectFile(
            name = "home_screen.dart",
            path = "lib/screens/home_screen.dart",
            isDir = false,
            mime = "text/plain",
            description = "Home screen StatefulWidget with ListView and navigation"
        ),
        IOSAppProjectFile(
            name = "user_model.dart",
            path = "lib/models/user_model.dart",
            isDir = false,
            mime = "text/plain",
            description = "User data model with fromJson/toJson serialization methods"
        ),
        IOSAppProjectFile(
            name = "api_service.dart",
            path = "lib/services/api_service.dart",
            isDir = false,
            mime = "text/plain",
            description = "API service using http package for REST API calls"
        ),
        IOSAppProjectFile(
            name = "app_provider.dart",
            path = "lib/providers/app_provider.dart",
            isDir = false,
            mime = "text/plain",
            description = "State management provider using ChangeNotifier"
        ),
        IOSAppProjectFile(
            name = "app_icon.png",
            path = "assets/images/app_icon.png",
            isDir = false,
            mime = "image/png",
            description = "App icon: A colorful gradient background with app logo in the center. Dimensions: 1024x1024 pixels."
        ),
        IOSAppProjectFile(
            name = "splash_logo.png",
            path = "assets/images/splash_logo.png",
            isDir = false,
            mime = "image/png",
            description = "Splash screen logo: Brand logo centered on transparent background. Dimensions: 256x256 pixels."
        ),
        IOSAppProjectFile(
            name = "pubspec.yaml",
            path = "pubspec.yaml",
            isDir = false,
            mime = "text/yaml",
            description = "Flutter project configuration file with dependencies, version, and asset declarations"
        )
    )
)

// Example 3: React Native Project
internal val exampleIOSAppProjectFiles_ReactNative = IOSAppProjectFiles(
    files = listOf(
        IOSAppProjectFile(
            name = "App.tsx",
            path = "src/App.tsx",
            isDir = false,
            mime = "text/plain",
            description = "Root React component with navigation container and stack navigator"
        ),
        IOSAppProjectFile(
            name = "HomeScreen.tsx",
            path = "src/screens/HomeScreen.tsx",
            isDir = false,
            mime = "text/plain",
            description = "Home screen functional component with FlatList and TouchableOpacity"
        ),
        IOSAppProjectFile(
            name = "types.ts",
            path = "src/types/types.ts",
            isDir = false,
            mime = "text/plain",
            description = "TypeScript type definitions for navigation and data models"
        ),
        IOSAppProjectFile(
            name = "apiClient.ts",
            path = "src/services/apiClient.ts",
            isDir = false,
            mime = "text/plain",
            description = "Axios-based API client with interceptors and error handling"
        ),
        IOSAppProjectFile(
            name = "AppContext.tsx",
            path = "src/context/AppContext.tsx",
            isDir = false,
            mime = "text/plain",
            description = "React Context for global state management"
        ),
        IOSAppProjectFile(
            name = "app_icon.png",
            path = "assets/images/app_icon.png",
            isDir = false,
            mime = "image/png",
            description = "App icon: A modern icon with sharp edges and vibrant colors. Dimensions: 1024x1024 pixels."
        ),
        IOSAppProjectFile(
            name = "splash.png",
            path = "assets/images/splash.png",
            isDir = false,
            mime = "image/png",
            description = "Splash screen background: Full-screen gradient from #1E3A8A (top) to #3B82F6 (bottom) with app name in white text. Dimensions: 1242x2208 pixels."
        ),
        IOSAppProjectFile(
            name = "package.json",
            path = "package.json",
            isDir = false,
            mime = "application/json",
            description = "Node.js project configuration with dependencies, scripts, and app metadata"
        ),
        IOSAppProjectFile(
            name = "app.json",
            path = "app.json",
            isDir = false,
            mime = "application/json",
            description = "React Native app configuration with display name, icon path, and platform-specific settings"
        )
    )
)

// Example 1: Native SwiftUI Architecture
internal val exampleProjectArchitecture_SwiftUI = ProjectArchitecture(
    fileArchitectures = listOf(
        FileArchitecture(
            filePath = "Sources/ContentView.swift",
            functions = listOf(
                FunctionSignature(
                    name = "body",
                    parameters = "",
                    returnType = "some View",
                    description = "SwiftUI view body returning navigation and list components",
                    accessModifier = "public",
                    implementationLogic = """
                        1. Check if isLoading is true, show ProgressView
                        2. Otherwise, show NavigationView containing:
                           - List iterating over items array
                           - Each row displays item.title and item.description
                           - Navigation link to DetailView for each item
                        3. Add .onAppear modifier to call loadItems()
                        4. Add .navigationTitle("Items")
                    """.trimIndent(),
                    usageExample = """
                        struct MyApp: App {
                            var body: some Scene {
                                WindowGroup {
                                    ContentView()
                                }
                            }
                        }
                    """.trimIndent(),
                    errorHandling = "No direct error handling in body. Errors from loadItems() are caught and stored in @State var errorMessage"
                ),
                FunctionSignature(
                    name = "loadItems",
                    parameters = "",
                    returnType = "void",
                    description = "Fetches items from network service and updates state",
                    accessModifier = "private",
                    implementationLogic = """
                        1. Set isLoading = true
                        2. Call NetworkService.shared.fetchItems()
                        3. Use .sink(receiveCompletion:receiveValue:) to handle response
                        4. On success: update items array, set isLoading = false
                        5. On failure: set errorMessage, set isLoading = false
                        6. Store cancellable in Set<AnyCancellable>
                    """.trimIndent(),
                    usageExample = """
                        // Called automatically in .onAppear modifier
                        ContentView()
                            .onAppear {
                                loadItems()
                            }
                    """.trimIndent(),
                    errorHandling = """
                        Handle Combine publisher errors:
                        - Network errors (URLError): Display "Network connection failed"
                        - Decoding errors (DecodingError): Display "Invalid data format"
                        - Store error message in @State var errorMessage for display in alert
                    """.trimIndent()
                )
            ),
            globalVariables = listOf(
                GlobalVariable(
                    name = "items",
                    type = "[ItemModel]",
                    description = "Published array of items displayed in the list",
                    isConstant = false
                ),
                GlobalVariable(
                    name = "isLoading",
                    type = "Bool",
                    description = "Published loading state indicator",
                    isConstant = false
                )
            ),
            dataStructures = listOf(
                DataStructure(
                    name = "ContentView",
                    type = "struct",
                    properties = "",
                    description = "Main view struct conforming to View protocol",
                    inheritsFrom = "View",
                    detailedMembers = "var body: some View { get }",
                    initializationExample = "let view = ContentView() // No parameters needed",
                    usageExample = """
                        @main
                        struct MyApp: App {
                            var body: some Scene {
                                WindowGroup {
                                    ContentView()
                                }
                            }
                        }
                    """.trimIndent()
                )
            ),
            dependencies = listOf("SwiftUI", "Combine", "NetworkService", "ItemModel"),
            architecturalNotes = "Uses SwiftUI declarative syntax with @State and @Published for reactive UI updates. Follows MVVM pattern.",
            dependencyUsagePatterns = """
                - NetworkService.shared.fetchItems() returns AnyPublisher<[ItemModel], Error>
                - ItemModel instances are displayed in List using ForEach(items) { item in ... }
                - Combine framework used for async/reactive data flow
            """.trimIndent(),
            fileUsageExample = """
                // 1. Import the view
                import SwiftUI

                // 2. Use in App struct
                @main
                struct MyApp: App {
                    var body: some Scene {
                        WindowGroup {
                            ContentView()
                        }
                    }
                }

                // 3. Or use in another view
                struct ParentView: View {
                    var body: some View {
                        NavigationView {
                            ContentView()
                        }
                    }
                }
            """.trimIndent(),
            isCritical = true
        ),
        FileArchitecture(
            filePath = "Sources/Models/ItemModel.swift",
            functions = emptyList(),
            globalVariables = emptyList(),
            dataStructures = listOf(
                DataStructure(
                    name = "ItemModel",
                    type = "struct",
                    properties = "id: UUID, title: String, description: String, createdAt: Date",
                    description = "Data model representing a single item",
                    inheritsFrom = "Codable, Identifiable",
                    detailedMembers = """
                        Required properties:
                        - id: UUID (auto-generated, serves as Identifiable identifier)
                        - title: String (item display title)
                        - description: String (detailed item description)
                        - createdAt: Date (timestamp when item was created)
                    """.trimIndent(),
                    initializationExample = """
                        // Initialize with all properties
                        let item = ItemModel(
                            id: UUID(),
                            title: "Sample Item",
                            description: "This is a sample item for demonstration",
                            createdAt: Date()
                        )

                        // Decode from JSON
                        let jsonData = try JSONDecoder().decode(ItemModel.self, from: data)
                    """.trimIndent(),
                    usageExample = """
                        // Use in SwiftUI List
                        List(items) { item in
                            VStack(alignment: .leading) {
                                Text(item.title).font(.headline)
                                Text(item.description).font(.subheadline)
                            }
                        }

                        // Sort by creation date
                        let sortedItems = items.sorted { $0.createdAt < $1.createdAt }
                    """.trimIndent()
                )
            ),
            dependencies = listOf("Foundation"),
            architecturalNotes = "Immutable value type following Swift best practices",
            dependencyUsagePatterns = "Uses Foundation's UUID, Date, and Codable protocol for JSON serialization",
            fileUsageExample = """
                import Foundation

                // Create instance
                let item = ItemModel(id: UUID(), title: "My Item", description: "Details", createdAt: Date())

                // Encode to JSON
                let encoder = JSONEncoder()
                encoder.dateEncodingStrategy = .iso8601
                let jsonData = try encoder.encode(item)

                // Decode from JSON
                let decoder = JSONDecoder()
                decoder.dateDecodingStrategy = .iso8601
                let decodedItem = try decoder.decode(ItemModel.self, from: jsonData)
            """.trimIndent(),
            isCritical = true
        ),
        FileArchitecture(
            filePath = "Sources/Services/NetworkService.swift",
            functions = listOf(
                FunctionSignature(
                    name = "fetchItems",
                    parameters = "",
                    returnType = "AnyPublisher<[ItemModel], Error>",
                    description = "Fetches items from API endpoint using URLSession",
                    accessModifier = "public",
                    implementationLogic = """
                        1. Construct URL from baseURL + "/items"
                        2. Create URLRequest with GET method
                        3. Use URLSession.dataTaskPublisher(for: request)
                        4. Map response data using JSONDecoder
                        5. Decode to [ItemModel]
                        6. Handle errors with mapError
                        7. Return AnyPublisher
                    """.trimIndent(),
                    usageExample = """
                        NetworkService.shared.fetchItems()
                            .sink(
                                receiveCompletion: { completion in
                                    switch completion {
                                    case .finished: print("Fetch completed")
                                    case .failure(let error): print("Error: \(error)")
                                    }
                                },
                                receiveValue: { items in
                                    self.items = items
                                }
                            )
                            .store(in: &cancellables)
                    """.trimIndent(),
                    errorHandling = """
                        Handle multiple error types:
                        - URLError.notConnectedToInternet: Retry or show offline mode
                        - URLError.timedOut: Increase timeout or retry
                        - DecodingError: Log error, show "Invalid data" message
                        - HTTP status codes (401, 403, 404, 500): Map to custom errors
                    """.trimIndent()
                ),
                FunctionSignature(
                    name = "createItem",
                    parameters = "item: ItemModel",
                    returnType = "AnyPublisher<ItemModel, Error>",
                    description = "Creates a new item via POST request",
                    accessModifier = "public",
                    implementationLogic = """
                        1. Construct URL from baseURL + "/items"
                        2. Create URLRequest with POST method
                        3. Encode item to JSON using JSONEncoder
                        4. Set request body and Content-Type header
                        5. Use URLSession.dataTaskPublisher
                        6. Validate HTTP response (200-299)
                        7. Decode response to ItemModel
                        8. Return AnyPublisher
                    """.trimIndent(),
                    usageExample = """
                        let newItem = ItemModel(id: UUID(), title: "New", description: "Item", createdAt: Date())

                        NetworkService.shared.createItem(item: newItem)
                            .sink(
                                receiveCompletion: { completion in
                                    if case .failure(let error) = completion {
                                        print("Create failed: \(error)")
                                    }
                                },
                                receiveValue: { createdItem in
                                    print("Created: \(createdItem.id)")
                                }
                            )
                            .store(in: &cancellables)
                    """.trimIndent(),
                    errorHandling = """
                        Handle creation-specific errors:
                        - EncodingError: Show "Invalid item data"
                        - HTTP 400: Show validation errors from response
                        - HTTP 409: Show "Item already exists"
                        - Network errors: Offer retry option
                    """.trimIndent()
                )
            ),
            globalVariables = listOf(
                GlobalVariable(
                    name = "baseURL",
                    type = "String",
                    description = "API base URL",
                    isConstant = true
                )
            ),
            dataStructures = listOf(
                DataStructure(
                    name = "NetworkService",
                    type = "class",
                    properties = "session: URLSession",
                    description = "Singleton service for network operations",
                    inheritsFrom = "",
                    detailedMembers = """
                        static let shared = NetworkService()
                        private let session: URLSession
                        private let baseURL: String

                        Methods:
                        - fetchItems() -> AnyPublisher<[ItemModel], Error>
                        - createItem(item:) -> AnyPublisher<ItemModel, Error>
                        - updateItem(item:) -> AnyPublisher<ItemModel, Error>
                        - deleteItem(id:) -> AnyPublisher<Void, Error>
                    """.trimIndent(),
                    initializationExample = """
                        // Access singleton
                        let service = NetworkService.shared

                        // Private initializer prevents external instantiation
                        // private init() {
                        //     self.session = URLSession.shared
                        //     self.baseURL = "https://api.example.com"
                        // }
                    """.trimIndent(),
                    usageExample = """
                        // Fetch items
                        NetworkService.shared.fetchItems()
                            .sink(receiveCompletion: { _ in }, receiveValue: { items in
                                print("Received \(items.count) items")
                            })
                            .store(in: &cancellables)

                        // Chain multiple requests
                        NetworkService.shared.createItem(item: newItem)
                            .flatMap { _ in NetworkService.shared.fetchItems() }
                            .sink(receiveCompletion: { _ in }, receiveValue: { items in
                                print("Updated list: \(items)")
                            })
                            .store(in: &cancellables)
                    """.trimIndent()
                )
            ),
            dependencies = listOf("Foundation", "Combine", "ItemModel"),
            architecturalNotes = "Singleton pattern with Combine publishers for async operations",
            dependencyUsagePatterns = """
                - Uses URLSession from Foundation for HTTP requests
                - Returns Combine publishers for reactive data flow
                - Depends on ItemModel for type-safe encoding/decoding
                - All network calls are asynchronous and cancellable
            """.trimIndent(),
            fileUsageExample = """
                import Foundation
                import Combine

                class ViewModel {
                    private var cancellables = Set<AnyCancellable>()
                    @Published var items: [ItemModel] = []

                    func loadData() {
                        NetworkService.shared.fetchItems()
                            .receive(on: DispatchQueue.main)
                            .sink(
                                receiveCompletion: { completion in
                                    // Handle completion
                                },
                                receiveValue: { [weak self] items in
                                    self?.items = items
                                }
                            )
                            .store(in: &cancellables)
                    }
                }
            """.trimIndent(),
            isCritical = true
        )
    ),
    globalPrinciples = "Follow MVVM architecture pattern. Use Combine for reactive programming. Prefer value types (struct) over reference types (class). Use protocols for abstraction and testability.",
    namingConventions = "Use camelCase for functions and variables. Use PascalCase for types. Prefix private properties with underscore when needed. Use descriptive names that convey intent.",
    dependencyGraph = "ContentView depends on ItemModel and NetworkService. NetworkService depends on ItemModel. All components depend on Foundation and Combine frameworks.",
    codePatterns = """
        1. SwiftUI View Pattern:
           - Use @State for local view state
           - Use @Published in ViewModels for reactive updates
           - Prefer computed properties for derived state

        2. Combine Pattern:
           - Chain operators: map, flatMap, compactMap
           - Handle errors with catch or mapError
           - Always store subscriptions in Set<AnyCancellable>

        3. Error Handling:
           - Use Result type for synchronous operations
           - Use Combine publishers for async operations
           - Always provide user-facing error messages

        4. Networking:
           - Centralize network calls in service classes
           - Use Codable for JSON serialization
           - Implement retry logic for transient failures
    """.trimIndent(),
    errorHandlingStrategy = """
        Global error handling approach:
        1. Network Layer: Catch and map URLError to custom AppError enum
        2. View Layer: Display errors using @State errorMessage and .alert modifier
        3. Logging: Log all errors to console in debug, analytics in production
        4. Recovery: Offer retry for network errors, refresh for data errors
        5. User Communication: Show friendly error messages, avoid technical jargon
    """.trimIndent(),
    typeSystemConventions = """
        1. Optionals:
           - Use optional chaining (?.) and nil coalescing (??) liberally
           - Avoid force unwrapping (!) unless guaranteed safe
           - Use guard let for early returns

        2. Generics:
           - Create generic network methods: func fetch<T: Codable>() -> AnyPublisher<T, Error>
           - Use type constraints when needed: where T: Identifiable

        3. Type Aliases:
           - Create meaningful aliases: typealias ItemID = UUID
           - Use for complex closure types: typealias CompletionHandler = (Result<Data, Error>) -> Void
    """.trimIndent()
)

// Example 2: Flutter Architecture
internal val exampleProjectArchitecture_Flutter = ProjectArchitecture(
    fileArchitectures = listOf(
        FileArchitecture(
            filePath = "lib/screens/home_screen.dart",
            functions = listOf(
                FunctionSignature(
                    name = "build",
                    parameters = "context: BuildContext",
                    returnType = "Widget",
                    description = "Builds the widget tree for home screen",
                    accessModifier = "public"
                ),
                FunctionSignature(
                    name = "_fetchUsers",
                    parameters = "",
                    returnType = "Future<void>",
                    description = "Fetches user list from API service",
                    accessModifier = "private"
                )
            ),
            globalVariables = listOf(
                GlobalVariable(
                    name = "_users",
                    type = "List<UserModel>",
                    description = "List of users displayed in the screen",
                    isConstant = false
                ),
                GlobalVariable(
                    name = "_isLoading",
                    type = "bool",
                    description = "Loading state indicator",
                    isConstant = false
                )
            ),
            dataStructures = listOf(
                DataStructure(
                    name = "HomeScreen",
                    type = "class",
                    properties = "",
                    description = "Stateful widget for home screen",
                    inheritsFrom = "StatefulWidget"
                ),
                DataStructure(
                    name = "_HomeScreenState",
                    type = "class",
                    properties = "",
                    description = "State class for HomeScreen widget",
                    inheritsFrom = "State<HomeScreen>"
                )
            ),
            dependencies = listOf("flutter/material.dart", "UserModel", "ApiService", "Provider"),
            architecturalNotes = "StatefulWidget with async data fetching in initState. Uses ListView.builder for efficient rendering."
        ),
        FileArchitecture(
            filePath = "lib/models/user_model.dart",
            functions = listOf(
                FunctionSignature(
                    name = "fromJson",
                    parameters = "json: Map<String, dynamic>",
                    returnType = "UserModel",
                    description = "Creates UserModel instance from JSON map",
                    accessModifier = "public"
                ),
                FunctionSignature(
                    name = "toJson",
                    parameters = "",
                    returnType = "Map<String, dynamic>",
                    description = "Converts UserModel to JSON map",
                    accessModifier = "public"
                )
            ),
            globalVariables = emptyList(),
            dataStructures = listOf(
                DataStructure(
                    name = "UserModel",
                    type = "class",
                    properties = "id: int, name: String, email: String, avatar: String",
                    description = "User data model with serialization",
                    inheritsFrom = ""
                )
            ),
            dependencies = emptyList(),
            architecturalNotes = "Immutable data class with JSON serialization support"
        )
    ),
    globalPrinciples = "Follow Flutter BLoC/Provider pattern for state management. Separate UI from business logic. Use immutable data models. Implement async/await for asynchronous operations.",
    namingConventions = "Use snake_case for file names. Use camelCase for variables and functions. Use PascalCase for classes. Prefix private members with underscore.",
    dependencyGraph = "HomeScreen depends on UserModel, ApiService, and Provider. ApiService depends on UserModel and http package.",
    codePatterns = """
        1. Widget Building:
           - Extract reusable widgets into separate classes
           - Use const constructors for performance
           - Prefer StatelessWidget over StatefulWidget when possible

        2. State Management:
           - Use Provider for dependency injection
           - Implement ChangeNotifier for reactive state
           - Call notifyListeners() after state changes

        3. Async Operations:
           - Use async/await for readability
           - Handle Future errors with try-catch
           - Show loading indicators during async operations

        4. Navigation:
           - Use named routes for maintainability
           - Pass data via route arguments
           - Use Navigator.pushReplacement for replacing screens
    """.trimIndent(),
    errorHandlingStrategy = """
        1. Network errors: Wrap http calls in try-catch, show SnackBar
        2. Parsing errors: Validate JSON structure, log parsing failures
        3. State errors: Use if-mounted checks before setState
        4. Widget errors: Implement ErrorWidget.builder for custom error display
        5. Global errors: Use runZonedGuarded for catching uncaught errors
    """.trimIndent(),
    typeSystemConventions = """
        1. Null Safety:
           - Use nullable types (?) only when necessary
           - Use null-aware operators (?., ??, !)
           - Initialize late variables before use

        2. Type Annotations:
           - Always annotate function return types
           - Use var/final for local variables with obvious types
           - Explicitly type class properties

        3. Generics:
           - Use generics for lists: List<UserModel>
           - Create generic widgets: CustomList<T>
    """.trimIndent()
)

// Example 3: React Native TypeScript Architecture
internal val exampleProjectArchitecture_ReactNative = ProjectArchitecture(
    fileArchitectures = listOf(
        FileArchitecture(
            filePath = "src/screens/HomeScreen.tsx",
            functions = listOf(
                FunctionSignature(
                    name = "HomeScreen",
                    parameters = "{ navigation }: HomeScreenProps",
                    returnType = "JSX.Element",
                    description = "Functional component rendering home screen with navigation",
                    accessModifier = "public"
                ),
                FunctionSignature(
                    name = "loadData",
                    parameters = "",
                    returnType = "Promise<void>",
                    description = "Async function to fetch data from API",
                    accessModifier = "private"
                )
            ),
            globalVariables = listOf(
                GlobalVariable(
                    name = "styles",
                    type = "StyleSheet",
                    description = "StyleSheet object containing component styles",
                    isConstant = true
                )
            ),
            dataStructures = listOf(
                DataStructure(
                    name = "HomeScreenProps",
                    type = "interface",
                    properties = "navigation: NavigationProp<RootStackParamList>",
                    description = "Props interface for HomeScreen component",
                    inheritsFrom = ""
                )
            ),
            dependencies = listOf("react", "react-native", "NavigationProp", "RootStackParamList", "apiClient"),
            architecturalNotes = "Functional component using React hooks (useState, useEffect). Follows React Navigation v6 patterns."
        ),
        FileArchitecture(
            filePath = "src/types/types.ts",
            functions = emptyList(),
            globalVariables = emptyList(),
            dataStructures = listOf(
                DataStructure(
                    name = "User",
                    type = "interface",
                    properties = "id: number, name: string, email: string, avatarUrl: string",
                    description = "User data interface",
                    inheritsFrom = ""
                ),
                DataStructure(
                    name = "RootStackParamList",
                    type = "type",
                    properties = "Home: undefined, Profile: { userId: number }, Settings: undefined",
                    description = "Navigation stack parameter list",
                    inheritsFrom = ""
                )
            ),
            dependencies = emptyList(),
            architecturalNotes = "Type definitions for navigation and data models"
        ),
        FileArchitecture(
            filePath = "src/services/apiClient.ts",
            functions = listOf(
                FunctionSignature(
                    name = "getUsers",
                    parameters = "",
                    returnType = "Promise<User[]>",
                    description = "Fetches list of users from API",
                    accessModifier = "public"
                ),
                FunctionSignature(
                    name = "getUserById",
                    parameters = "userId: number",
                    returnType = "Promise<User>",
                    description = "Fetches single user by ID",
                    accessModifier = "public"
                )
            ),
            globalVariables = listOf(
                GlobalVariable(
                    name = "apiClient",
                    type = "AxiosInstance",
                    description = "Configured axios instance with base URL and interceptors",
                    isConstant = true
                )
            ),
            dataStructures = emptyList(),
            dependencies = listOf("axios", "User"),
            architecturalNotes = "Axios client with request/response interceptors for authentication and error handling"
        )
    ),
    globalPrinciples = "Use functional components with hooks. Implement TypeScript for type safety. Follow React Navigation patterns. Separate API logic into services. Use Context API for global state when needed.",
    namingConventions = "Use PascalCase for components and types. Use camelCase for functions and variables. Use UPPER_CASE for constants. Prefix interfaces with 'I' only when necessary.",
    dependencyGraph = "HomeScreen depends on apiClient and types. apiClient depends on axios and User type. All screens depend on navigation types.",
    codePatterns = """
        1. Component Pattern:
           - Use functional components exclusively
           - Extract custom hooks for reusable logic
           - Memoize expensive computations with useMemo

        2. Hooks Pattern:
           - Use useState for local state
           - Use useEffect for side effects
           - Use useCallback for memoized callbacks
           - Use useContext for global state access

        3. Styling:
           - Use StyleSheet.create for performance
           - Define styles at component bottom
           - Use shared style constants for consistency

        4. Type Safety:
           - Define prop types with interfaces
           - Type all function parameters and returns
           - Use discriminated unions for complex states
    """.trimIndent(),
    errorHandlingStrategy = """
        1. API errors: Catch in try-catch, show Alert or Toast
        2. Navigation errors: Validate routes exist before navigation
        3. Rendering errors: Use Error Boundaries for component errors
        4. TypeScript errors: Enable strict mode, fix all type issues
        5. Runtime errors: Log to error tracking service (Sentry, etc.)
    """.trimIndent(),
    typeSystemConventions = """
        1. Props and State:
           - Define prop interfaces: interface MyProps { name: string }
           - Use type for simple types, interface for objects
           - Make optional props explicit with ?

        2. Generics:
           - Type hooks: useState<User[]>([])
           - Type navigation: NavigationProp<RootStackParamList>

        3. Utility Types:
           - Use Pick, Omit, Partial for type manipulation
           - Define discriminated unions: type Status = 'loading' | 'success' | 'error'
    """.trimIndent()
)

// ============================================
// Additional Example Data for Missing Models
// ============================================

// Example: CompilationCheckResult with errors
internal val exampleCompilationCheck_Success = CompilationCheckResult(
    success = true,
    errors = emptyList(),
    warnings = listOf(
        "Variable 'tempValue' is never used in NetworkService.swift:45",
        "Import of 'Combine' is unused in ItemModel.swift"
    ),
    summary = "Code compiles successfully with 2 minor warnings. Warnings do not affect functionality."
)

internal val exampleCompilationCheck_Failure = CompilationCheckResult(
    success = false,
    errors = listOf(
        CompilationError(
            filePath = "Sources/ContentView.swift",
            line = 23,
            message = "Cannot find 'ItemModel' in scope",
            category = "undefined_symbol",
            suggestedFix = "Add 'import ItemModel' at the top of the file, or ensure ItemModel.swift is included in the target"
        ),
        CompilationError(
            filePath = "Sources/Services/NetworkService.swift",
            line = 67,
            message = "Value of type '[ItemModel]' has no member 'compactMap'",
            category = "type_mismatch",
            suggestedFix = "Check if you meant to call compactMap on the array. If ItemModel is not imported, add import statement."
        ),
        CompilationError(
            filePath = "Sources/ContentView.swift",
            line = 45,
            message = "Missing return in closure expected to return 'some View'",
            category = "syntax",
            suggestedFix = "Add explicit return statement or ensure last expression returns a View"
        ),
        CompilationError(
            filePath = "Sources/AppNameApp.swift",
            line = 0,
            message = "No such module 'SwiftUI'",
            category = "import_error",
            suggestedFix = "Ensure deployment target is iOS 13.0 or later. Check project build settings."
        )
    ),
    warnings = listOf(
        "Immutable value 'items' was never used in ContentView.swift:12"
    ),
    summary = "Compilation failed with 4 errors. Most errors are related to missing imports and type resolution issues."
)

// Example: CompilationFix
internal val exampleCompilationFix = CompilationFix(
    filePath = "Sources/ContentView.swift",
    originalCode = """
        import SwiftUI

        struct ContentView: View {
            @State private var items = []

            var body: some View {
                List(items) { item in
                    Text(item.title)
                }
            }
        }
    """.trimIndent(),
    fixedCode = """
        import SwiftUI

        struct ContentView: View {
            @State private var items: [ItemModel] = []

            var body: some View {
                List(items) { item in
                    Text(item.title)
                }
            }
        }
    """.trimIndent(),
    explanation = "Added explicit type annotation '[ItemModel]' to items property. Swift type inference cannot determine the type of an empty array literal, causing compilation errors when using 'item.title'."
)

// Example: MediaAssetsManifest
internal val exampleMediaAssetsManifest = MediaAssetsManifest(
    assets = listOf(
        MediaAssetSpec(
            name = "AppIcon.png",
            path = "Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png",
            type = "icon",
            width = 1024,
            height = 1024,
            purpose = "app_icon",
            detailedDescription = "Primary app icon for FitTrack Pro. Gradient background transitioning from vibrant coral red (#FF6B6B) at the top to cool turquoise (#4ECDC4) at the bottom, creating a modern and energetic feel. In the center, a white dumbbell icon (512x512pt) rendered in a clean, minimalist geometric style. The dumbbell features two circular weights connected by a straight bar. The entire icon has rounded corners with a 180pt radius to match iOS design guidelines. Apply a subtle drop shadow: offset (0px, 4px), blur radius 8px, color rgba(0,0,0,0.2) for depth.",
            group = ".appiconset",
            scale = null
        ),
        MediaAssetSpec(
            name = "WorkoutIcon.png",
            path = "Resources/Assets.xcassets/WorkoutIcon.imageset/WorkoutIcon.png",
            type = "icon",
            width = 100,
            height = 100,
            purpose = "button",
            detailedDescription = "Navigation icon for Workouts section. Dumbbell icon rendered in outline style with coral red (#FF6B6B) stroke. Stroke width: 3pt. The icon features a simplified geometric dumbbell shape: two circles (20pt diameter each) connected by a 40pt horizontal bar (6pt height). Transparent background. The icon should be crisp and clear at all sizes. Used in dashboard grid and tab bar.",
            group = ".imageset",
            scale = "@2x"
        ),
        MediaAssetSpec(
            name = "WorkoutIcon@3x.png",
            path = "Resources/Assets.xcassets/WorkoutIcon.imageset/WorkoutIcon@3x.png",
            type = "icon",
            width = 150,
            height = 150,
            purpose = "button",
            detailedDescription = "3x scale version of WorkoutIcon. Identical design to @2x version but rendered at 150x150pt for high-resolution displays. Dumbbell icon in coral red (#FF6B6B) outline style, 4.5pt stroke width (scaled up proportionally). Transparent background.",
            group = ".imageset",
            scale = "@3x"
        ),
        MediaAssetSpec(
            name = "CardioThumbnail.png",
            path = "Resources/Assets.xcassets/CardioThumbnail.imageset/CardioThumbnail.png",
            type = "image",
            width = 400,
            height = 300,
            purpose = "background",
            detailedDescription = "Thumbnail image for cardio workouts. Features a stylized, minimalist illustration of a person running in profile view. Background: diagonal gradient from coral red (#FF6B6B) top-left to lighter coral (#FF8E8E) bottom-right. The runner silhouette is rendered in dark navy (#1A1A2E), positioned in center-right, taking up approximately 60% of the image height. Add 3-4 motion lines behind the runner (2pt width, white with 0.3 opacity) to convey movement. Runner's pose: mid-stride with one leg forward, one arm forward, suggesting dynamic motion. Flat design style, no texture or gradients on the silhouette. Modern and energetic aesthetic.",
            group = ".imageset",
            scale = "@2x"
        ),
        MediaAssetSpec(
            name = "StrengthThumbnail.png",
            path = "Resources/Assets.xcassets/StrengthThumbnail.imageset/StrengthThumbnail.png",
            type = "image",
            width = 400,
            height = 300,
            purpose = "background",
            detailedDescription = "Thumbnail image for strength training workouts. Features a minimalist illustration of a person lifting dumbbells. Background: diagonal gradient from turquoise (#4ECDC4) top-left to lighter turquoise (#6ED9D0) bottom-right. The person silhouette is rendered in dark navy (#1A1A2E), positioned at center, taking up approximately 70% of image height. Person is in squat position, holding dumbbell in each hand at shoulder level. Dumbbells rendered as simple shapes in white (#FFFFFF) for contrast. Flat design style with clean lines. Conveys strength and stability. No additional decorative elements to maintain minimalist aesthetic.",
            group = ".imageset",
            scale = "@2x"
        ),
        MediaAssetSpec(
            name = "NutritionIcon.png",
            path = "Resources/Assets.xcassets/NutritionIcon.imageset/NutritionIcon.png",
            type = "icon",
            width = 100,
            height = 100,
            purpose = "button",
            detailedDescription = "Navigation icon for Nutrition section. Apple icon rendered in outline style with turquoise (#4ECDC4) stroke. Stroke width: 3pt. The apple is a classic shape: circular body (50pt diameter) with a small indent at top (10pt depth), and a small stem (3pt width, 12pt height) with a single leaf (15pt x 8pt, tilted 45 degrees). Transparent background. Clean, recognizable silhouette. Used in dashboard grid and navigation.",
            group = ".imageset",
            scale = "@2x"
        ),
        MediaAssetSpec(
            name = "LaunchLogo.png",
            path = "Resources/LaunchScreen/LaunchLogo.png",
            type = "image",
            width = 512,
            height = 512,
            purpose = "launch_screen",
            detailedDescription = "Logo for launch screen. Same dumbbell icon as app icon but on transparent background. White dumbbell (same geometric design: two 256pt circles connected by 256pt bar) rendered at 512x512pt. Clean, crisp edges. No background, no shadow. This logo will be displayed on the dark navy (#1A1A2E) launch screen background and will scale/fade during launch animation.",
            group = null,
            scale = null
        )
    )
)


// Additional example structured data instances to ensure ≥3 examples per type

// Extra Examples: MediaAssetsManifest
internal val exampleMediaAssetsManifest_Finance = MediaAssetsManifest(
    assets = listOf(
        MediaAssetSpec(
            name = "AppIcon.png",
            path = "Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png",
            type = "icon",
            width = 1024,
            height = 1024,
            purpose = "app_icon",
            detailedDescription = "Flat blue background with centered white wallet symbol.",
            group = ".appiconset",
            scale = null
        ),
        MediaAssetSpec(
            name = "Plus@2x.png",
            path = "Resources/Assets.xcassets/Plus.imageset/Plus@2x.png",
            type = "icon",
            width = 96,
            height = 96,
            purpose = "button",
            detailedDescription = "+ sign inside blue circle, 2pt white stroke.",
            group = ".imageset",
            scale = "@2x"
        ),
        MediaAssetSpec(
            name = "CategorySpend@3x.png",
            path = "Resources/Assets.xcassets/CategorySpend.imageset/CategorySpend@3x.png",
            type = "icon",
            width = 192,
            height = 192,
            purpose = "button",
            detailedDescription = "Red spend category icon, white arrow down, transparent bg.",
            group = ".imageset",
            scale = "@3x"
        )
    )
)

internal val exampleMediaAssetsManifest_Travel = MediaAssetsManifest(
    assets = listOf(
        MediaAssetSpec(
            name = "AppIcon.png",
            path = "Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png",
            type = "icon",
            width = 1024,
            height = 1024,
            purpose = "app_icon",
            detailedDescription = "Teal to blue gradient with white paper-plane symbol.",
            group = ".appiconset",
            scale = null
        ),
        MediaAssetSpec(
            name = "Calendar@2x.png",
            path = "Resources/Assets.xcassets/Calendar.imageset/Calendar@2x.png",
            type = "icon",
            width = 88,
            height = 88,
            purpose = "button",
            detailedDescription = "Calendar outline icon in teal stroke, transparent bg.",
            group = ".imageset",
            scale = "@2x"
        ),
        MediaAssetSpec(
            name = "MapBackground.png",
            path = "Resources/Assets.xcassets/MapBackground.imageset/MapBackground.png",
            type = "image",
            width = 1242,
            height = 2688,
            purpose = "background",
            detailedDescription = "Neutral map snapshot style background with subtle blur.",
            group = ".imageset",
            scale = null
        )
    )
)

// Extra Examples: UnifiedArchitectureReview
internal val exampleUnifiedArchitectureReview_Pass = UnifiedArchitectureReview(
    qualityScore = 86,
    approved = true,
    complete = true,
    designIssues = listOf(
        DesignIssue(
            "warning",
            "maintainability",
            "Consider extracting networking layer to separate module for better testability",
            listOf("Sources/Services/NetworkService.swift"),
            "Create NetworkKit module and inject via protocol"
        )
    ),
    missingFiles = emptyList(),
    recommendations = listOf(
        "Add unit tests for ViewModels",
        "Integrate crash reporting"
    ),
    summary = "Architecture is solid and complete. Minor improvements suggested for testability."
)

internal val exampleUnifiedArchitectureReview_FailMissing = UnifiedArchitectureReview(
    qualityScore = 62,
    approved = false,
    complete = false,
    designIssues = listOf(
        DesignIssue(
            "critical",
            "missing_error_handling",
            "Networking code lacks retry and timeout handling",
            listOf("Sources/Services/NetworkService.swift"),
            "Add URLSession configuration with timeout, implement retry with backoff"
        ),
        DesignIssue(
            "warning",
            "performance_concern",
            "Large image decoding on main thread",
            listOf("Sources/Views/ContentView.swift"),
            "Move decoding to background queue"
        )
    ),
    missingFiles = listOf(exampleMissingAssetsCatalog, exampleMissingLaunchScreen),
    recommendations = listOf(
        "Add LaunchScreen and Assets catalog",
        "Introduce error handling middleware"
    ),
    summary = "Architecture not approved due to missing critical assets and error handling gaps."
)

internal val exampleUnifiedArchitectureReview_PassWithWarnings = UnifiedArchitectureReview(
    qualityScore = 78,
    approved = true,
    complete = true,
    designIssues = listOf(
        DesignIssue(
            "info",
            "code_style",
            "Inconsistent naming in models",
            listOf("Sources/Models"),
            "Apply Swift naming guidelines"
        )
    ),
    missingFiles = emptyList(),
    recommendations = listOf("Standardize naming conventions across modules"),
    summary = "Approved with minor warnings related to naming consistency."
)

// Extra Example: CompilationCheckResult
internal val exampleCompilationCheck_WarningsHeavy = CompilationCheckResult(
    success = true,
    errors = emptyList(),
    warnings = listOf(
        "'var' could be 'let' in ItemModel.swift:12",
        "Force unwrapping detected in ContentView.swift:88",
        "Long function (120 lines) in NetworkService.swift: fetchData()"
    ),
    summary = "Code compiles with several warnings that should be addressed for code quality."
)

// Extra Examples: CompilationFix
internal val exampleCompilationFix_MissingImport = CompilationFix(
    filePath = "Sources/NetworkService.swift",
    originalCode = """
        import Foundation

        class NetworkService {
            func fetchUsers() -> AnyPublisher<[User], Error> { /* ... */ }
        }
    """.trimIndent(),
    fixedCode = """
        import Foundation
        import Combine

        class NetworkService {
            func fetchUsers() -> AnyPublisher<[User], Error> { /* ... */ }
        }
    """.trimIndent(),
    explanation = "Added missing 'import Combine' required for AnyPublisher usage."
)

internal val exampleCompilationFix_Syntax = CompilationFix(
    filePath = "Sources/ItemModel.swift",
    originalCode = """
        struct ItemModel: Identifiable, Codable {
            let id: UUID
            let title String
        }
    """.trimIndent(),
    fixedCode = """
        struct ItemModel: Identifiable, Codable {
            let id: UUID
            let title: String
        }
    """.trimIndent(),
    explanation = "Fixed missing colon in property declaration 'title: String'."
)


// Extra Examples: DesignReviewResult
internal val exampleDesignReviewResult_Pass = DesignReviewResult(
    qualityScore = 88,
    approved = true,
    complete = true,
    issues = listOf(
        "Minor alignment variance on Settings header is acceptable."
    ),
    missingItems = emptyList(),
    summary = "Design is clear, consistent, and complete. Ready for slicing."
)

internal val exampleDesignReviewResult_FailIncomplete = DesignReviewResult(
    qualityScore = 54,
    approved = false,
    complete = false,
    issues = listOf(
        "Typography sizes inconsistent between Home and Details",
        "Insufficient contrast for primary buttons on dark backgrounds"
    ),
    missingItems = listOf(
        "Empty state for Favorites",
        "Loading skeleton for ListView",
        "Error state for network failure",
        "Icon specs for Settings tab"
    ),
    summary = "Design not approved due to incompleteness and accessibility concerns."
)

internal val exampleDesignReviewResult_PassWithNotes = DesignReviewResult(
    qualityScore = 76,
    approved = true,
    complete = true,
    issues = listOf(
        "Consider increasing tap targets to 44pt min on small icons.",
        "Provide grid documentation for spacing system"
    ),
    missingItems = emptyList(),
    summary = "Approved with notes to improve usability and documentation."
)

// Extra Examples: MediaAssetsReviewResult
internal val exampleMediaAssetsReviewResult_Pass = MediaAssetsReviewResult(
    qualityScore = 90,
    approved = true,
    complete = true,
    missingAssets = emptyList(),
    summary = "All required icon sizes present; launch image and UI assets complete."
)

internal val exampleMediaAssetsReviewResult_FailMissing = MediaAssetsReviewResult(
    qualityScore = 48,
    approved = false,
    complete = false,
    missingAssets = listOf(
        "AppIcon 60x60@3x",
        "AppIcon 76x76@2x",
        "Launch screen background 1242x2688",
        "Tab bar icons @3x"
    ),
    summary = "Missing critical icon sizes and launch asset; not production ready."
)

internal val exampleMediaAssetsReviewResult_PassWithNotes = MediaAssetsReviewResult(
    qualityScore = 74,
    approved = true,
    complete = true,
    missingAssets = listOf(
        "Optional marketing icon 1024x1024 alternate"
    ),
    summary = "Approved; optional assets may improve App Store presentation."
)


// ============================================
// Extended Design Specification Models (Real-world coverage)
// ============================================

@Serializable
@LLMDescription("Semantic theme specification for light/dark modes and color tokens")
data class ThemeSpec(
    @property:LLMDescription("Light mode semantic colors")
    val light: SemanticColors,
    @property:LLMDescription("Dark mode semantic colors")
    val dark: SemanticColors
)

@Serializable
@LLMDescription("Semantic color tokens for a theme")
data class SemanticColors(
    @property:LLMDescription("Primary color")
    val primary: String,
    @property:LLMDescription("Text/icon color displayed on primary")
    val onPrimary: String? = null,
    @property:LLMDescription("Secondary color")
    val secondary: String? = null,
    @property:LLMDescription("Text/icon color displayed on secondary")
    val onSecondary: String? = null,
    @property:LLMDescription("Background color")
    val background: String? = null,
    @property:LLMDescription("Text/icon color displayed on background")
    val onBackground: String? = null,
    @property:LLMDescription("Surface color for cards/sheets")
    val surface: String? = null,
    @property:LLMDescription("Text/icon color displayed on surface")
    val onSurface: String? = null,
    @property:LLMDescription("Primary text color")
    val textPrimary: String? = null,
    @property:LLMDescription("Secondary text color")
    val textSecondary: String? = null,
    @property:LLMDescription("Divider color")
    val divider: String? = null,
    @property:LLMDescription("Success color")
    val success: String? = null,
    @property:LLMDescription("Warning color")
    val warning: String? = null,
    @property:LLMDescription("Error color")
    val error: String? = null,
    @property:LLMDescription("Disabled color")
    val disabled: String? = null,
    @property:LLMDescription("State colors for interactive components")
    val states: StateColors? = null
)

@Serializable
@LLMDescription("Interactive state colors")
data class StateColors(
    @property:LLMDescription("Hover state color")
    val hover: String? = null,
    @property:LLMDescription("Pressed state color")
    val pressed: String? = null,
    @property:LLMDescription("Focused state color")
    val focused: String? = null,
    @property:LLMDescription("Selected state color")
    val selected: String? = null,
    @property:LLMDescription("Disabled state color")
    val disabled: String? = null,
    @property:LLMDescription("Activated state color")
    val activated: String? = null
)

@Serializable
@LLMDescription("Design tokens including typography, spacing, radius, elevation and strokes")
data class DesignTokens(
    @property:LLMDescription("Typography scale and text styles")
    val typography: TypographyScale? = null,
    @property:LLMDescription("Spacing scale values")
    val spacing: SpacingScale? = null,
    @property:LLMDescription("Corner radius scale")
    val radii: RadiusScale? = null,
    @property:LLMDescription("Elevation/shadow scale")
    val elevation: ElevationScale? = null,
    @property:LLMDescription("Stroke width and colors")
    val stroke: StrokeScale? = null
)

@Serializable
@LLMDescription("Typography scale and styles with Dynamic Type mapping")
data class TypographyScale(
    @property:LLMDescription("Font family presets for heading/body/mono")
    val fontFamilies: FontFamilies = FontFamilies(),
    @property:LLMDescription("List of named text styles (H1/H2/Body/Caption)")
    val textStyles: List<TextStyleSpec> = emptyList(),
    @property:LLMDescription("Mapping from style name to iOS Dynamic Type class (e.g., Body -> .body)")
    val dynamicTypeMapping: Map<String, String>? = null,
    @property:LLMDescription("Accessibility breakpoints (XS/S/M/L/XL/XXL/AX1/AX2…)")
    val accessibilityBreakpoints: List<String>? = null
)

@Serializable
@LLMDescription("Font family presets")
data class FontFamilies(
    @property:LLMDescription("Heading font family")
    val heading: String = "",
    @property:LLMDescription("Body font family")
    val body: String = "",
    @property:LLMDescription("Monospace font family, if used")
    val monospace: String? = null
)

@Serializable
@LLMDescription("Named text style specification")
data class TextStyleSpec(
    @property:LLMDescription("Style name, e.g., H1, H2, Title, Body, Caption")
    val name: String,
    @property:LLMDescription("Font family to use")
    val fontFamily: String,
    @property:LLMDescription("Font size in points")
    val size: Double,
    @property:LLMDescription("Line height in points")
    val lineHeight: Double? = null,
    @property:LLMDescription("Font weight (e.g., Regular/Semibold/Bold or 400/600/700)")
    val weight: String? = null,
    @property:LLMDescription("Letter spacing (tracking) in points")
    val letterSpacing: Double? = null,
    @property:LLMDescription("Paragraph spacing in points")
    val paragraphSpacing: Double? = null,
    @property:LLMDescription("iOS Dynamic Type style name, e.g., .title, .body")
    val dynamicType: String? = null
)

@Serializable
@LLMDescription("Spacing, grid, radius, elevation, strokes, divider and touch targets")
data class SpacingAndGridSpec(
    @property:LLMDescription("Base unit for spacing scale (typically 4)")
    val baseUnit: Int = 4,
    @property:LLMDescription("Named spacing values (e.g., xxs/xs/s/m/l/xl/xxl)")
    val spacingScale: SpacingScale = SpacingScale(),
    @property:LLMDescription("Layout grid specification")
    val grid: GridSpec? = null,
    @property:LLMDescription("Corner radius scale")
    val radiusScale: RadiusScale? = null,
    @property:LLMDescription("Elevation/shadow scale")
    val elevationScale: ElevationScale? = null,
    @property:LLMDescription("Stroke widths and colors")
    val strokeScale: StrokeScale? = null,
    @property:LLMDescription("Divider style")
    val divider: DividerSpec? = null,
    @property:LLMDescription("Minimum touch target sizes")
    val touchTarget: TouchTargetSpec? = null
)

@Serializable
@LLMDescription("Named spacing values (name -> points)")
data class SpacingScale(
    @property:LLMDescription("Spacing map, e.g., xxs=2, xs=4, s=8, m=12, l=16, xl=24, xxl=32")
    val values: Map<String, Int> = mapOf(
        "xxs" to 2, "xs" to 4, "s" to 8, "m" to 12, "l" to 16, "xl" to 24, "xxl" to 32
    )
)

@Serializable
@LLMDescription("Grid layout specification")
data class GridSpec(
    @property:LLMDescription("Number of columns")
    val columns: Int = 12,
    @property:LLMDescription("Gutter width in points")
    val gutter: Int = 8,
    @property:LLMDescription("Horizontal page margin in points")
    val marginH: Int = 16,
    @property:LLMDescription("Vertical page margin in points")
    val marginV: Int = 16
)

@Serializable
@LLMDescription("Corner radius scale")
data class RadiusScale(
    @property:LLMDescription("Radius values map (name -> points)")
    val values: Map<String, Int> = mapOf("s" to 4, "m" to 8, "l" to 12, "full" to 999)
)

@Serializable
@LLMDescription("Elevation/shadow scale")
data class ElevationScale(
    @property:LLMDescription("Shadow presets (name -> shadow spec)")
    val shadows: Map<String, ShadowSpec> = emptyMap()
)

@Serializable
@LLMDescription("Shadow specification")
data class ShadowSpec(
    @property:LLMDescription("Shadow color (hex)")
    val color: String = "#000000",
    @property:LLMDescription("Opacity (0..1)")
    val opacity: Double = 0.12,
    @property:LLMDescription("Offset X in points")
    val x: Int = 0,
    @property:LLMDescription("Offset Y in points")
    val y: Int = 1,
    @property:LLMDescription("Blur radius in points")
    val blur: Int = 4,
    @property:LLMDescription("Spread in points")
    val spread: Int = 0
)

@Serializable
@LLMDescription("Stroke widths and colors")
data class StrokeScale(
    @property:LLMDescription("Stroke widths (name -> points)")
    val widths: Map<String, Int> = mapOf("hairline" to 1, "thin" to 2),
    @property:LLMDescription("Stroke colors (name -> hex)")
    val colors: Map<String, String> = emptyMap()
)

@Serializable
@LLMDescription("Divider specification")
data class DividerSpec(
    @property:LLMDescription("Divider color (hex)")
    val color: String = "",
    @property:LLMDescription("Divider thickness in points")
    val thickness: Int = 1,
    @property:LLMDescription("Leading inset in points")
    val inset: Int = 0
)

@Serializable
@LLMDescription("Minimum touch target sizes")
data class TouchTargetSpec(
    @property:LLMDescription("Minimum width in points (Apple HIG recommends >= 44)")
    val minWidth: Int = 44,
    @property:LLMDescription("Minimum height in points (Apple HIG recommends >= 44)")
    val minHeight: Int = 44
)

@Serializable
@LLMDescription("Component library specification including key UI components and their states")
data class ComponentLibrarySpec(
    @property:LLMDescription("Button specifications by size/variant and states")
    val button: ButtonSpec? = null,
    @property:LLMDescription("Text field/input specifications and states")
    val textField: TextFieldSpec? = null,
    @property:LLMDescription("Toggle (switch) specifications and states")
    val toggle: ToggleSpec? = null,
    @property:LLMDescription("Slider specifications and states")
    val slider: SliderSpec? = null,
    @property:LLMDescription("Segmented control specifications and states")
    val segmentedControl: SegmentedControlSpec? = null,
    @property:LLMDescription("Card container specification")
    val card: CardSpec? = null,
    @property:LLMDescription("Toast variants")
    val toast: ToastSpec? = null,
    @property:LLMDescription("Sheet (bottom sheet) specification")
    val sheet: SheetSpec? = null,
    @property:LLMDescription("Badge variants")
    val badge: BadgeSpec? = null,
    @property:LLMDescription("Tag/Chip variants")
    val tag: TagSpec? = null,
    @property:LLMDescription("Standard list cell spec")
    val listCell: ListCellSpec? = null
)

@Serializable
@LLMDescription("Per-state styles for a control")
data class ControlStateStyles(
    @property:LLMDescription("Normal/default state style")
    val normal: ControlStyle,
    @property:LLMDescription("Highlighted state style")
    val highlighted: ControlStyle? = null,
    @property:LLMDescription("Pressed state style")
    val pressed: ControlStyle? = null,
    @property:LLMDescription("Disabled state style")
    val disabled: ControlStyle? = null,
    @property:LLMDescription("Loading state style")
    val loading: ControlStyle? = null,
    @property:LLMDescription("Focused state style")
    val focused: ControlStyle? = null,
    @property:LLMDescription("Error state style")
    val error: ControlStyle? = null
)

@Serializable
@LLMDescription("Reusable style tokens for a control state")
data class ControlStyle(
    @property:LLMDescription("Background color (hex)")
    val backgroundColor: String? = null,
    @property:LLMDescription("Foreground (text/icon) color (hex)")
    val foregroundColor: String? = null,
    @property:LLMDescription("Border color (hex)")
    val borderColor: String? = null,
    @property:LLMDescription("Border width in points")
    val borderWidth: Int? = null,
    @property:LLMDescription("Corner radius in points")
    val cornerRadius: Int? = null,
    @property:LLMDescription("Shadow spec reference or inline value")
    val shadow: ShadowSpec? = null,
    @property:LLMDescription("Icon name (SF Symbols or asset)")
    val icon: String? = null,
    @property:LLMDescription("Typography style name (reference to TextStyleSpec.name)")
    val typography: String? = null
)

@Serializable
@LLMDescription("Button spec with sizes and variants")
data class ButtonSpec(
    @property:LLMDescription("Size presets (e.g., small/medium/large)")
    val sizes: Map<String, SizeSpec> = emptyMap(),
    @property:LLMDescription("Variants (e.g., primary/secondary/tertiary) and their state styles")
    val variants: Map<String, ControlStateStyles> = emptyMap()
)

@Serializable
@LLMDescription("Size/padding/corner radius preset")
data class SizeSpec(
    @property:LLMDescription("Height in points")
    val height: Int,
    @property:LLMDescription("Horizontal padding in points")
    val paddingH: Int,
    @property:LLMDescription("Vertical padding in points")
    val paddingV: Int,
    @property:LLMDescription("Icon size in points if applicable")
    val iconSize: Int? = null,
    @property:LLMDescription("Corner radius in points if overrides default")
    val cornerRadius: Int? = null
)

@Serializable
@LLMDescription("Text field specification and states")
data class TextFieldSpec(
    @property:LLMDescription("Text field visual states")
    val states: ControlStateStyles,
    @property:LLMDescription("Placeholder text style")
    val placeholderStyle: ControlStyle? = null,
    @property:LLMDescription("Helper text style (caption/error/help)")
    val helperTextStyle: ControlStyle? = null,
    @property:LLMDescription("Content padding")
    val contentPadding: SizeSpec? = null
)

@Serializable
@LLMDescription("Toggle (switch) specification")
data class ToggleSpec(
    @property:LLMDescription("Toggle visual states")
    val states: ControlStateStyles
)

@Serializable
@LLMDescription("Slider specification")
data class SliderSpec(
    @property:LLMDescription("Slider visual states")
    val states: ControlStateStyles
)

@Serializable
@LLMDescription("Segmented control specification")
data class SegmentedControlSpec(
    @property:LLMDescription("Segmented control visual states")
    val states: ControlStateStyles
)

@Serializable
@LLMDescription("Card container style")
data class CardSpec(
    @property:LLMDescription("Card base style")
    val style: ControlStyle,
    @property:LLMDescription("Content padding inside card")
    val contentPadding: SizeSpec? = null
)

@Serializable
@LLMDescription("Toast variants")
data class ToastSpec(
    @property:LLMDescription("Toast variants (info/success/warning/error)")
    val variants: Map<String, ControlStyle> = emptyMap()
)

@Serializable
@LLMDescription("Sheet (bottom sheet) style")
data class SheetSpec(
    @property:LLMDescription("Sheet background style")
    val background: ControlStyle,
    @property:LLMDescription("Grabber handle style")
    val grabber: ControlStyle? = null
)

@Serializable
@LLMDescription("Badge variants")
data class BadgeSpec(
    @property:LLMDescription("Badge variants (number/dot/custom)")
    val variants: Map<String, ControlStyle> = emptyMap()
)

@Serializable
@LLMDescription("Tag/Chip variants")
data class TagSpec(
    @property:LLMDescription("Tag variants (default/outline/filled)")
    val variants: Map<String, ControlStyle> = emptyMap()
)

@Serializable
@LLMDescription("List cell specification")
data class ListCellSpec(
    @property:LLMDescription("Title text style name")
    val titleStyle: String? = null,
    @property:LLMDescription("Subtitle text style name")
    val subtitleStyle: String? = null,
    @property:LLMDescription("Accessory icon name (SF Symbols or asset)")
    val accessoryIcon: String? = null,
    @property:LLMDescription("Divider style for list cell")
    val divider: DividerSpec? = null,
    @property:LLMDescription("Content padding for list cell")
    val contentPadding: SizeSpec? = null
)

@Serializable
@LLMDescription("Tab Bar specification including items and appearance")
data class TabBarSpec(
    @property:LLMDescription("Tab items list")
    val items: List<TabItemSpec> = emptyList(),
    @property:LLMDescription("Selected item color (hex)")
    val selectedColor: String? = null,
    @property:LLMDescription("Unselected item color (hex)")
    val unselectedColor: String? = null,
    @property:LLMDescription("Badge style for tab items")
    val badgeStyle: ControlStyle? = null,
    @property:LLMDescription("Icon size in points (recommend 24)")
    val iconSize: Int = 24,
    @property:LLMDescription("Whether tab bar respects safe area")
    val respectsSafeArea: Boolean = true
)

@Serializable
@LLMDescription("Single tab bar item specification")
data class TabItemSpec(
    @property:LLMDescription("Tab item title")
    val title: String,
    @property:LLMDescription("Icon name (SF Symbols or custom asset)")
    val icon: String,
    @property:LLMDescription("Selected state icon name if different")
    val selectedIcon: String? = null
)

@Serializable
@LLMDescription("Accessibility specification including WCAG checks and Dynamic Type policy")
data class AccessibilitySpec(
    @property:LLMDescription("Target WCAG level, e.g., 'AA' or 'AAA'")
    val wcagLevel: String = "AA",
    @property:LLMDescription("List of contrast check results")
    val contrastChecks: List<ContrastCheck> = emptyList(),
    @property:LLMDescription("Whether Dynamic Type scaling is enabled")
    val dynamicTypeEnabled: Boolean = true
)

@Serializable
@LLMDescription("Single foreground/background contrast check result")
data class ContrastCheck(
    @property:LLMDescription("Foreground color (hex)")
    val foreground: String,
    @property:LLMDescription("Background color (hex)")
    val background: String,
    @property:LLMDescription("Computed contrast ratio")
    val ratio: Double,
    @property:LLMDescription("Whether the check passes target WCAG level")
    val pass: Boolean,
    @property:LLMDescription("Additional notes")
    val notes: String? = null
)


// ============================================
// Extended DesignSpecification Examples (comprehensive, multi-category)
// ============================================

internal val exampleDesignSpecification_FitnessApp_Extended = DesignSpecification(
    appName = "FitTrack Pro",
    appIcon = AppIconSpec(
        style = "gradient",
        primaryColor = "#FF6B6B",
        secondaryColor = "#4ECDC4",
        symbol = "dumbbell",
        description = "Modern fitness gradient icon with white dumbbell"
    ),
    launchScreen = LaunchScreenSpec(
        backgroundColor = "#1A1A2E",
        logoPosition = "center",
        tagline = "Track Your Fitness Journey",
        animationDuration = 2.0,
        description = "Dark navy background with scaling logo and fade-in tagline"
    ),
    colorPalette = ColorPalette(
        primary = "#FF6B6B",
        secondary = "#4ECDC4",
        accent = "#FFE66D",
        background = "#F7FFF7",
        surface = "#FFFFFF",
        error = "#FF3B30",
        textPrimary = "#1A1A2E",
        textSecondary = "#6C6C80"
    ),
    typography = Typography(
        headingFont = "SF Pro Display Bold",
        bodyFont = "SF Pro Text Regular",
        headingSize = 24,
        bodySize = 16
    ),
    screens = listOf(
        ScreenSpec(
            name = "Home Dashboard",
            purpose = "Daily summary and quick actions",
            layout = "grid",
            elements = listOf(
                UIElementSpec("text", "top", "large", "Welcome title", false),
                UIElementSpec("image", "top-right", "48x48", "Profile photo (circular)", true),
                UIElementSpec(
                    "button",
                    "center-grid",
                    "150x150",
                    "Cards for Workouts/Nutrition/Progress/Settings",
                    true
                ),
                UIElementSpec("text", "bottom", "medium", "Today's stats row", false)
            )
        )
    ),
    assets = listOf(
        AssetSpec(
            name = "app_icon",
            type = "icon",
            width = 1024,
            height = 1024,
            purpose = "app_icon",
            detailedDescription = "Gradient coral→turquoise with white dumbbell",
            scales = listOf("1x", "2x", "3x"),
            vector = true,
            namingConvention = "fittrack_{name}@{scale}",
            exportFormats = listOf("PNG", "PDF"),
            compression = "lossless",
            themeVariants = listOf("light", "dark")
        )
    ),
    animations = listOf(
        AnimationSpec("app_launch", "scale_fade", 2.0, "Logo scale 0.8→1.0 + fade"),
        AnimationSpec("button_tap", "scale", 0.2, "Tap down 0.95 then back")
    ),
    theme = ThemeSpec(
        light = SemanticColors(
            primary = "#FF6B6B",
            onPrimary = "#FFFFFF",
            secondary = "#4ECDC4",
            onSecondary = "#0B2C2A",
            background = "#F7FFF7",
            onBackground = "#1A1A2E",
            surface = "#FFFFFF",
            onSurface = "#1A1A2E",
            textPrimary = "#111111",
            textSecondary = "#6C6C80",
            divider = "#E6E6EF",
            success = "#34C759",
            warning = "#FFCC00",
            error = "#FF3B30",
            disabled = "#C8C8D0",
            states = StateColors(
                hover = "#FF8181",
                pressed = "#E65555",
                focused = "#4ECDC4",
                selected = "#FF6B6B",
                disabled = "#EAEAF2"
            )
        ),
        dark = SemanticColors(
            primary = "#FF6B6B",
            onPrimary = "#0A0A0A",
            secondary = "#4ECDC4",
            onSecondary = "#0A0A0A",
            background = "#0E0E15",
            onBackground = "#F2F2F5",
            surface = "#161622",
            onSurface = "#FFFFFF",
            textPrimary = "#FFFFFF",
            textSecondary = "#C6C6D2",
            divider = "#2A2A3A",
            success = "#30D158",
            warning = "#FFD60A",
            error = "#FF453A",
            disabled = "#3C3C4C",
            states = StateColors(
                hover = "#FF7F7F",
                pressed = "#CC5555",
                focused = "#4ECDC4",
                selected = "#FF6B6B",
                disabled = "#2E2E3A"
            )
        )
    ),
    tokens = DesignTokens(
        typography = TypographyScale(
            fontFamilies = FontFamilies(heading = "SF Pro Display", body = "SF Pro Text", monospace = "SF Mono"),
            textStyles = listOf(
                TextStyleSpec("H1", "SF Pro Display", 34.0, 41.0, "Bold", -0.5, 8.0, ".largeTitle"),
                TextStyleSpec("H2", "SF Pro Display", 28.0, 34.0, "Semibold", -0.3, 6.0, ".title"),
                TextStyleSpec("Body", "SF Pro Text", 17.0, 22.0, "Regular", 0.0, 4.0, ".body"),
                TextStyleSpec("Caption", "SF Pro Text", 12.0, 16.0, "Regular", 0.1, 2.0, ".caption")
            ),
            dynamicTypeMapping = mapOf("Body" to ".body", "H1" to ".largeTitle"),
            accessibilityBreakpoints = listOf("XS", "S", "M", "L", "XL", "XXL", "AX1", "AX2")
        ),
        spacing = SpacingScale(
            values = mapOf(
                "xxs" to 2,
                "xs" to 4,
                "s" to 8,
                "m" to 12,
                "l" to 16,
                "xl" to 24,
                "xxl" to 32
            )
        ),
        radii = RadiusScale(values = mapOf("s" to 6, "m" to 10, "l" to 14, "full" to 999)),
        elevation = ElevationScale(
            shadows = mapOf(
                "level1" to ShadowSpec(color = "#000000", opacity = 0.12, x = 0, y = 2, blur = 8, spread = 0),
                "level2" to ShadowSpec(color = "#000000", opacity = 0.16, x = 0, y = 4, blur = 16, spread = 0)
            )
        ),
        stroke = StrokeScale(
            widths = mapOf("hairline" to 1, "thin" to 2, "thick" to 3),
            colors = mapOf("neutral" to "#E6E6EF", "primary" to "#FF6B6B")
        )
    ),
    accessibility = AccessibilitySpec(
        wcagLevel = "AA",
        contrastChecks = listOf(
            ContrastCheck("#111111", "#FFFFFF", ratio = 12.6, pass = true, notes = "Body on surface"),
            ContrastCheck("#FFFFFF", "#FF6B6B", ratio = 4.7, pass = true, notes = "OnPrimary text")
        ),
        dynamicTypeEnabled = true
    ),
    components = ComponentLibrarySpec(
        button = ButtonSpec(
            sizes = mapOf(
                "small" to SizeSpec(height = 32, paddingH = 12, paddingV = 6, iconSize = 16, cornerRadius = 8),
                "medium" to SizeSpec(height = 44, paddingH = 16, paddingV = 10, iconSize = 20, cornerRadius = 10),
                "large" to SizeSpec(height = 52, paddingH = 20, paddingV = 12, iconSize = 24, cornerRadius = 12)
            ),
            variants = mapOf(
                "primary" to ControlStateStyles(
                    normal = ControlStyle(
                        backgroundColor = "#FF6B6B",
                        foregroundColor = "#FFFFFF",
                        typography = "Body",
                        shadow = ShadowSpec(y = 2, blur = 8)
                    ),
                    pressed = ControlStyle(backgroundColor = "#E65555", foregroundColor = "#FFFFFF"),
                    disabled = ControlStyle(backgroundColor = "#EAEAF2", foregroundColor = "#C8C8D0")
                ),
                "secondary" to ControlStateStyles(
                    normal = ControlStyle(
                        backgroundColor = "#FFFFFF",
                        foregroundColor = "#FF6B6B",
                        borderColor = "#FF6B6B",
                        borderWidth = 2,
                        cornerRadius = 12
                    ),
                    pressed = ControlStyle(backgroundColor = "#FFF2F2"),
                    disabled = ControlStyle(foregroundColor = "#C8C8D0", borderColor = "#E6E6EF")
                )
            )
        ),
        textField = TextFieldSpec(
            states = ControlStateStyles(
                normal = ControlStyle(
                    backgroundColor = "#FFFFFF",
                    foregroundColor = "#1A1A2E",
                    borderColor = "#E6E6EF",
                    borderWidth = 1,
                    cornerRadius = 12
                ),
                focused = ControlStyle(borderColor = "#4ECDC4", borderWidth = 2),
                error = ControlStyle(borderColor = "#FF3B30", borderWidth = 2),
                disabled = ControlStyle(backgroundColor = "#F5F6FB", foregroundColor = "#6C6C80")
            ),
            placeholderStyle = ControlStyle(foregroundColor = "#9AA0AE", typography = "Caption"),
            helperTextStyle = ControlStyle(foregroundColor = "#6C6C80", typography = "Caption"),
            contentPadding = SizeSpec(height = 44, paddingH = 12, paddingV = 10)
        ),
        toggle = ToggleSpec(
            states = ControlStateStyles(
                normal = ControlStyle(backgroundColor = "#34C759"),
                disabled = ControlStyle(backgroundColor = "#C8C8D0")
            )
        ),
        slider = SliderSpec(states = ControlStateStyles(normal = ControlStyle(backgroundColor = "#FF6B6B"))),
        segmentedControl = SegmentedControlSpec(
            states = ControlStateStyles(
                normal = ControlStyle(
                    backgroundColor = "#FFFFFF",
                    borderColor = "#E6E6EF",
                    borderWidth = 1,
                    cornerRadius = 10
                ), highlighted = ControlStyle(backgroundColor = "#FF6B6B", foregroundColor = "#FFFFFF")
            )
        ),
        card = CardSpec(
            style = ControlStyle(
                backgroundColor = "#FFFFFF",
                cornerRadius = 16,
                shadow = ShadowSpec(y = 4, blur = 16)
            ), contentPadding = SizeSpec(height = 0, paddingH = 16, paddingV = 16)
        ),
        toast = ToastSpec(
            variants = mapOf(
                "info" to ControlStyle(backgroundColor = "#1E90FF", foregroundColor = "#FFFFFF"),
                "success" to ControlStyle(backgroundColor = "#34C759", foregroundColor = "#FFFFFF"),
                "warning" to ControlStyle(backgroundColor = "#FFCC00", foregroundColor = "#0A0A0A"),
                "error" to ControlStyle(backgroundColor = "#FF3B30", foregroundColor = "#FFFFFF")
            )
        ),
        sheet = SheetSpec(
            background = ControlStyle(backgroundColor = "#FFFFFF"),
            grabber = ControlStyle(backgroundColor = "#E6E6EF")
        ),
        badge = BadgeSpec(
            variants = mapOf(
                "default" to ControlStyle(
                    backgroundColor = "#FF6B6B",
                    foregroundColor = "#FFFFFF"
                )
            )
        ),
        tag = TagSpec(
            variants = mapOf(
                "filled" to ControlStyle(
                    backgroundColor = "#4ECDC4",
                    foregroundColor = "#0B2C2A"
                ), "outline" to ControlStyle(borderColor = "#4ECDC4", borderWidth = 2, foregroundColor = "#4ECDC4")
            )
        ),
        listCell = ListCellSpec(
            titleStyle = "Body",
            subtitleStyle = "Caption",
            accessoryIcon = "chevron.right",
            divider = DividerSpec(color = "#E6E6EF", thickness = 1, inset = 16),
            contentPadding = SizeSpec(height = 0, paddingH = 16, paddingV = 12)
        )
    ),
    spacingAndGrid = SpacingAndGridSpec(
        baseUnit = 4,
        spacingScale = SpacingScale(
            values = mapOf(
                "xxs" to 2,
                "xs" to 4,
                "s" to 8,
                "m" to 12,
                "l" to 16,
                "xl" to 24,
                "xxl" to 32
            )
        ),
        grid = GridSpec(columns = 12, gutter = 8, marginH = 16, marginV = 16),
        radiusScale = RadiusScale(values = mapOf("s" to 8, "m" to 12, "l" to 16, "full" to 999)),
        elevationScale = ElevationScale(shadows = mapOf("card" to ShadowSpec(opacity = 0.12, y = 4, blur = 16))),
        strokeScale = StrokeScale(widths = mapOf("hairline" to 1, "thin" to 2), colors = mapOf("neutral" to "#E6E6EF")),
        divider = DividerSpec(color = "#E6E6EF", thickness = 1, inset = 16),
        touchTarget = TouchTargetSpec(minWidth = 44, minHeight = 44)
    ),
    tabBar = TabBarSpec(
        items = listOf(
            TabItemSpec("Home", "house.fill"),
            TabItemSpec("Workouts", "figure.strengthtraining.traditional"),
            TabItemSpec("Nutrition", "leaf"),
            TabItemSpec("Profile", "person.crop.circle")
        ),
        selectedColor = "#FF6B6B",
        unselectedColor = "#6C6C80",
        badgeStyle = ControlStyle(backgroundColor = "#FF3B30", foregroundColor = "#FFFFFF"),
        iconSize = 24,
        respectsSafeArea = true
    )
)

internal val exampleDesignSpecification_FinanceApp_Extended = DesignSpecification(
    appName = "MoneyMaster",
    appIcon = AppIconSpec(
        style = "flat",
        primaryColor = "#2E86DE",
        secondaryColor = "#10AC84",
        symbol = "wallet",
        description = "Flat wallet icon"
    ),
    launchScreen = LaunchScreenSpec(
        backgroundColor = "#FFFFFF",
        logoPosition = "center",
        tagline = "Manage. Save. Grow.",
        animationDuration = 1.2,
        description = "Fade & slide"
    ),
    colorPalette = ColorPalette(
        primary = "#2E86DE",
        secondary = "#10AC84",
        accent = "#FBC531",
        background = "#F5F7FA",
        surface = "#FFFFFF",
        error = "#E74C3C",
        textPrimary = "#2D3436",
        textSecondary = "#636E72"
    ),
    typography = Typography(
        headingFont = "SF Pro Display Semibold",
        bodyFont = "SF Pro Text Regular",
        headingSize = 22,
        bodySize = 15
    ),
    screens = emptyList(),
    assets = listOf(
        AssetSpec(
            "app_icon",
            "icon",
            1024,
            1024,
            "app_icon",
            "Blue wallet icon",
            scales = listOf("1x", "2x", "3x"),
            vector = true,
            exportFormats = listOf("PNG", "PDF")
        )
    ),
    animations = listOf(AnimationSpec("tap", "bounce", 0.18, "Card bounce")),
    theme = ThemeSpec(
        light = SemanticColors(
            primary = "#2E86DE",
            onPrimary = "#FFFFFF",
            secondary = "#10AC84",
            onSecondary = "#FFFFFF",
            background = "#F5F7FA",
            onBackground = "#1E1E24",
            surface = "#FFFFFF",
            onSurface = "#1E1E24",
            textPrimary = "#1E1E24",
            textSecondary = "#5C6370",
            divider = "#E9ECF1",
            success = "#2ECC71",
            warning = "#F1C40F",
            error = "#E74C3C",
            disabled = "#C9CED6",
            states = StateColors(hover = "#4A9AF0", pressed = "#2568B5", focused = "#10AC84")
        ),
        dark = SemanticColors(
            primary = "#2E86DE",
            onPrimary = "#0A0A0A",
            secondary = "#10AC84",
            onSecondary = "#0A0A0A",
            background = "#0E1116",
            onBackground = "#EDEFF3",
            surface = "#151922",
            onSurface = "#FFFFFF",
            textPrimary = "#FFFFFF",
            textSecondary = "#B8C0CC",
            divider = "#2A2F38",
            success = "#27AE60",
            warning = "#F39C12",
            error = "#C0392B",
            disabled = "#3A3F48"
        )
    ),
    tokens = DesignTokens(
        typography = TypographyScale(
            fontFamilies = FontFamilies(heading = "SF Pro Display", body = "SF Pro Text"),
            textStyles = listOf(
                TextStyleSpec("Title", "SF Pro Display", 22.0, 28.0, "Semibold", -0.2, 6.0, ".title2"),
                TextStyleSpec("Body", "SF Pro Text", 15.0, 20.0, "Regular", 0.0, 4.0, ".body")
            )
        ),
        spacing = SpacingScale(values = mapOf("xs" to 4, "s" to 8, "m" to 12, "l" to 16, "xl" to 24)),
        radii = RadiusScale(values = mapOf("s" to 8, "m" to 12, "l" to 16)),
        elevation = ElevationScale(shadows = mapOf("level1" to ShadowSpec(opacity = 0.12, y = 2, blur = 8)))
    ),
    accessibility = AccessibilitySpec(
        wcagLevel = "AA",
        contrastChecks = listOf(
            ContrastCheck("#1E1E24", "#FFFFFF", 12.0, true, "Body on white"),
            ContrastCheck("#FFFFFF", "#2E86DE", 5.0, true, "OnPrimary")
        )
    ),
    components = ComponentLibrarySpec(
        button = ButtonSpec(
            sizes = mapOf("medium" to SizeSpec(44, 16, 10, 20, 12)),
            variants = mapOf(
                "primary" to ControlStateStyles(
                    normal = ControlStyle("#2E86DE", "#FFFFFF", typography = "Body"),
                    pressed = ControlStyle("#2568B5", "#FFFFFF")
                ),
                "secondary" to ControlStateStyles(
                    normal = ControlStyle("#FFFFFF", "#2E86DE", "#2E86DE", 2, 12),
                    pressed = ControlStyle("#EAF3FF")
                )
            )
        ),
        textField = TextFieldSpec(
            states = ControlStateStyles(
                normal = ControlStyle(
                    "#FFFFFF",
                    "#1E1E24",
                    "#E9ECF1",
                    1,
                    12
                ), focused = ControlStyle(borderColor = "#2E86DE", borderWidth = 2)
            ), contentPadding = SizeSpec(44, 12, 10)
        )
    ),
    spacingAndGrid = SpacingAndGridSpec(
        grid = GridSpec(12, 8, 16, 16),
        divider = DividerSpec("#E9ECF1", 1, 16),
        touchTarget = TouchTargetSpec(44, 44)
    ),
    tabBar = TabBarSpec(
        items = listOf(
            TabItemSpec("Overview", "rectangle.grid.2x2"),
            TabItemSpec("Transactions", "list.bullet"),
            TabItemSpec("Budget", "chart.pie")
        ), selectedColor = "#2E86DE", unselectedColor = "#5C6370"
    )
)

internal val exampleDesignSpecification_TravelApp_Extended = DesignSpecification(
    appName = "TripWise",
    appIcon = AppIconSpec(
        style = "gradient",
        primaryColor = "#00B894",
        secondaryColor = "#0984E3",
        symbol = "plane",
        description = "Teal→Blue plane"
    ),
    launchScreen = LaunchScreenSpec(
        backgroundColor = "#F0F9FF",
        logoPosition = "center",
        tagline = "Plan. Book. Explore.",
        animationDuration = 1.6,
        description = "Dotted path animation"
    ),
    colorPalette = ColorPalette(
        primary = "#0984E3",
        secondary = "#00B894",
        accent = "#FDCB6E",
        background = "#F0F9FF",
        surface = "#FFFFFF",
        error = "#D63031",
        textPrimary = "#2D3436",
        textSecondary = "#636E72"
    ),
    typography = Typography(headingFont = "SF Pro Display", bodyFont = "SF Pro Text", headingSize = 22, bodySize = 16),
    screens = emptyList(),
    assets = emptyList(),
    animations = listOf(AnimationSpec("screen_transition", "slide", 0.3, "Slide push")),
    theme = ThemeSpec(
        light = SemanticColors(
            primary = "#0984E3",
            onPrimary = "#FFFFFF",
            secondary = "#00B894",
            onSecondary = "#0A0A0A",
            background = "#F0F9FF",
            onBackground = "#0A0A0A",
            surface = "#FFFFFF",
            onSurface = "#0A0A0A",
            textPrimary = "#0A0A0A",
            textSecondary = "#637080",
            divider = "#E6ECF5",
            success = "#2ECC71",
            warning = "#F1C40F",
            error = "#E74C3C"
        ),
        dark = SemanticColors(
            primary = "#61A7FF",
            onPrimary = "#0A0A0A",
            secondary = "#26D3AE",
            onSecondary = "#0A0A0A",
            background = "#0B1018",
            onBackground = "#F2F5FA",
            surface = "#101726",
            onSurface = "#FFFFFF",
            textPrimary = "#FFFFFF",
            textSecondary = "#C2CAD6",
            divider = "#233047"
        )
    ),
    tokens = DesignTokens(
        typography = TypographyScale(
            textStyles = listOf(
                TextStyleSpec(
                    "Title",
                    "SF Pro Display",
                    22.0,
                    28.0,
                    "Semibold",
                    -0.2,
                    6.0,
                    ".title2"
                ), TextStyleSpec("Body", "SF Pro Text", 16.0, 22.0, "Regular", 0.0, 4.0, ".body")
            )
        ),
        spacing = SpacingScale(values = mapOf("s" to 8, "m" to 12, "l" to 16, "xl" to 24)),
        radii = RadiusScale(values = mapOf("s" to 8, "m" to 12, "l" to 16))
    ),
    spacingAndGrid = SpacingAndGridSpec(grid = GridSpec(12, 8, 20, 16)),
    tabBar = TabBarSpec(
        items = listOf(
            TabItemSpec("Discover", "globe"),
            TabItemSpec("Trips", "airplane"),
            TabItemSpec("Profile", "person")
        ), selectedColor = "#0984E3", unselectedColor = "#637080"
    )
)

// Additional multi-category examples (eCommerce, Social, Health, Productivity, News, Education)
internal val exampleDesignSpecification_ECommerceApp = DesignSpecification(
    appName = "ShopSphere",
    appIcon = AppIconSpec(
        style = "flat",
        primaryColor = "#7F56D9",
        secondaryColor = "#22C55E",
        symbol = "bag",
        description = "Purple bag icon"
    ),
    launchScreen = LaunchScreenSpec(
        backgroundColor = "#FFFFFF",
        logoPosition = "center",
        tagline = "Find more, pay less",
        animationDuration = 1.0,
        description = "Simple fade"
    ),
    colorPalette = ColorPalette(
        primary = "#7F56D9",
        secondary = "#22C55E",
        accent = "#F59E0B",
        background = "#FAFAFB",
        surface = "#FFFFFF",
        error = "#EF4444",
        textPrimary = "#111827",
        textSecondary = "#6B7280"
    ),
    typography = Typography(headingFont = "SF Pro Display", bodyFont = "SF Pro Text", headingSize = 24, bodySize = 16),
    theme = ThemeSpec(
        light = SemanticColors(
            primary = "#7F56D9",
            onPrimary = "#FFFFFF",
            secondary = "#22C55E",
            onSecondary = "#0A0A0A",
            background = "#FAFAFB",
            onBackground = "#111827",
            surface = "#FFFFFF",
            onSurface = "#111827",
            textPrimary = "#111827",
            textSecondary = "#6B7280",
            divider = "#E5E7EB",
            success = "#22C55E",
            warning = "#F59E0B",
            error = "#EF4444",
            states = StateColors(hover = "#9472E0", pressed = "#6C3FD2", selected = "#7F56D9")
        ),
        dark = SemanticColors(
            primary = "#9A7AE6",
            onPrimary = "#0A0A0A",
            secondary = "#34D399",
            onSecondary = "#0A0A0A",
            background = "#0F1117",
            onBackground = "#E5E7EB",
            surface = "#171923",
            onSurface = "#F9FAFB",
            textPrimary = "#F9FAFB",
            textSecondary = "#CBD5E1",
            divider = "#2A2F3A",
            success = "#34D399",
            warning = "#F59E0B",
            error = "#F87171"
        )
    ),
    tokens = DesignTokens(
        typography = TypographyScale(
            textStyles = listOf(
                TextStyleSpec(
                    "H1",
                    "SF Pro Display",
                    30.0,
                    36.0,
                    "Bold",
                    -0.5,
                    8.0,
                    ".title"
                ), TextStyleSpec("Body", "SF Pro Text", 16.0, 22.0, "Regular", 0.0, 4.0, ".body")
            )
        ),
        spacing = SpacingScale(values = mapOf("xs" to 4, "s" to 8, "m" to 12, "l" to 16, "xl" to 24, "xxl" to 32)),
        radii = RadiusScale(values = mapOf("s" to 8, "m" to 12, "l" to 16, "xl" to 24)),
        elevation = ElevationScale(shadows = mapOf("card" to ShadowSpec(opacity = 0.14, y = 6, blur = 20)))
    ),
    components = ComponentLibrarySpec(
        button = ButtonSpec(
            sizes = mapOf("medium" to SizeSpec(44, 16, 10, 20, 12)),
            variants = mapOf(
                "primary" to ControlStateStyles(
                    normal = ControlStyle("#7F56D9", "#FFFFFF", typography = "Body"),
                    pressed = ControlStyle("#6C3FD2", "#FFFFFF")
                ),
                "link" to ControlStateStyles(
                    normal = ControlStyle(backgroundColor = null, foregroundColor = "#7F56D9"),
                    pressed = ControlStyle(backgroundColor = "#F5F3FF")
                )
            )
        ),
        listCell = ListCellSpec(
            titleStyle = "Body",
            subtitleStyle = "Caption",
            accessoryIcon = "chevron.right",
            divider = DividerSpec("#E5E7EB", 1, 16)
        )
    ),
    spacingAndGrid = SpacingAndGridSpec(
        grid = GridSpec(12, 12, 20, 20),
        divider = DividerSpec("#E5E7EB", 1, 16),
        touchTarget = TouchTargetSpec()
    ),
    tabBar = TabBarSpec(
        items = listOf(
            TabItemSpec("Home", "house.fill"),
            TabItemSpec("Search", "magnifyingglass"),
            TabItemSpec("Cart", "cart.fill"),
            TabItemSpec("Account", "person")
        ), selectedColor = "#7F56D9", unselectedColor = "#6B7280"
    ),
    accessibility = AccessibilitySpec(
        contrastChecks = listOf(
            ContrastCheck(
                "#111827",
                "#FFFFFF",
                13.2,
                true,
                "Text on surface"
            )
        )
    )
)

internal val exampleDesignSpecification_SocialApp = DesignSpecification(
    appName = "LinkUp",
    appIcon = AppIconSpec(
        style = "gradient",
        primaryColor = "#FF3B30",
        secondaryColor = "#FF9F0A",
        symbol = "message",
        description = "Chat bubble"
    ),
    launchScreen = LaunchScreenSpec(
        backgroundColor = "#0A0A0A",
        logoPosition = "center",
        tagline = "Share moments",
        animationDuration = 1.3,
        description = "Bubble pop animation"
    ),
    colorPalette = ColorPalette(
        primary = "#FF3B30",
        secondary = "#FF9F0A",
        accent = "#34C759",
        background = "#0A0A0A",
        surface = "#161616",
        error = "#FF453A",
        textPrimary = "#FFFFFF",
        textSecondary = "#BFBFBF"
    ),
    theme = ThemeSpec(
        light = SemanticColors(
            primary = "#FF3B30",
            onPrimary = "#FFFFFF",
            secondary = "#FF9F0A",
            onSecondary = "#0A0A0A",
            background = "#FFFFFF",
            onBackground = "#111111",
            surface = "#FFFFFF",
            onSurface = "#111111",
            textPrimary = "#111111",
            textSecondary = "#6B7280",
            divider = "#E5E7EB"
        ),
        dark = SemanticColors(
            primary = "#FF3B30",
            onPrimary = "#0A0A0A",
            secondary = "#FF9F0A",
            onSecondary = "#0A0A0A",
            background = "#0A0A0A",
            onBackground = "#F5F5F5",
            surface = "#161616",
            onSurface = "#FFFFFF",
            textPrimary = "#FFFFFF",
            textSecondary = "#BFBFBF",
            divider = "#292929"
        )
    ),
    tokens = DesignTokens(
        typography = TypographyScale(
            textStyles = listOf(
                TextStyleSpec(
                    "Title",
                    "SF Pro Display",
                    24.0,
                    30.0,
                    "Bold",
                    -0.4,
                    8.0,
                    ".title"
                ), TextStyleSpec("Body", "SF Pro Text", 16.0, 22.0, "Regular", 0.0, 4.0, ".body")
            )
        )
    ),
    components = ComponentLibrarySpec(
        button = ButtonSpec(
            variants = mapOf(
                "primary" to ControlStateStyles(
                    normal = ControlStyle(
                        "#FF3B30",
                        "#FFFFFF"
                    ), pressed = ControlStyle("#CC2F27", "#FFFFFF")
                )
            )
        ),
        card = CardSpec(
            style = ControlStyle(
                "#161616",
                cornerRadius = 16,
                shadow = ShadowSpec(opacity = 0.3, y = 6, blur = 24)
            )
        )
    ),
    tabBar = TabBarSpec(
        items = listOf(
            TabItemSpec("Feed", "rectangle.stack"),
            TabItemSpec("Post", "plus.circle.fill"),
            TabItemSpec("Inbox", "bubble.left.and.bubble.right"),
            TabItemSpec("Profile", "person.crop.circle")
        ), selectedColor = "#FF3B30", unselectedColor = "#BFBFBF"
    )
)

internal val exampleDesignSpecification_HealthMedicalApp = DesignSpecification(
    appName = "CareBuddy",
    appIcon = AppIconSpec(
        style = "flat",
        primaryColor = "#10B981",
        secondaryColor = "#06B6D4",
        symbol = "cross",
        description = "Health cross"
    ),
    launchScreen = LaunchScreenSpec(
        backgroundColor = "#FFFFFF",
        logoPosition = "center",
        tagline = "Your health, simplified",
        animationDuration = 1.4,
        description = "Heartbeat pulse"
    ),
    colorPalette = ColorPalette(
        primary = "#10B981",
        secondary = "#06B6D4",
        accent = "#F59E0B",
        background = "#F8FAFC",
        surface = "#FFFFFF",
        error = "#EF4444",
        textPrimary = "#0F172A",
        textSecondary = "#475569"
    ),
    theme = ThemeSpec(
        light = SemanticColors(
            primary = "#10B981",
            onPrimary = "#FFFFFF",
            secondary = "#06B6D4",
            onSecondary = "#0A0A0A",
            background = "#F8FAFC",
            onBackground = "#0F172A",
            surface = "#FFFFFF",
            onSurface = "#0F172A",
            success = "#16A34A",
            warning = "#F59E0B",
            error = "#DC2626"
        ),
        dark = SemanticColors(
            primary = "#22D3A3",
            onPrimary = "#0A0A0A",
            secondary = "#22D3EE",
            onSecondary = "#0A0A0A",
            background = "#0B1220",
            onBackground = "#E2E8F0",
            surface = "#101826",
            onSurface = "#E2E8F0"
        )
    ),
    tokens = DesignTokens(
        typography = TypographyScale(
            textStyles = listOf(
                TextStyleSpec(
                    "Title",
                    "SF Pro Display",
                    24.0,
                    30.0,
                    "Bold"
                ), TextStyleSpec("Body", "SF Pro Text", 16.0, 22.0, "Regular")
            )
        )
    ),
    components = ComponentLibrarySpec(
        textField = TextFieldSpec(
            states = ControlStateStyles(
                normal = ControlStyle(
                    "#FFFFFF",
                    "#0F172A",
                    "#E2E8F0",
                    1,
                    12
                ),
                focused = ControlStyle(borderColor = "#06B6D4", borderWidth = 2),
                error = ControlStyle(borderColor = "#EF4444", borderWidth = 2)
            )
        )
    ),
    spacingAndGrid = SpacingAndGridSpec(touchTarget = TouchTargetSpec()),
    tabBar = TabBarSpec(
        items = listOf(
            TabItemSpec("Home", "heart.fill"),
            TabItemSpec("Records", "doc.text"),
            TabItemSpec("Care", "cross.case"),
            TabItemSpec("Profile", "person")
        ), selectedColor = "#10B981", unselectedColor = "#64748B"
    ),
    accessibility = AccessibilitySpec(
        contrastChecks = listOf(
            ContrastCheck(
                "#0F172A",
                "#FFFFFF",
                12.0,
                true,
                "Text on surface"
            )
        )
    )
)

internal val exampleDesignSpecification_ProductivityApp = DesignSpecification(
    appName = "TaskFlow",
    appIcon = AppIconSpec(
        style = "gradient",
        primaryColor = "#2563EB",
        secondaryColor = "#10B981",
        symbol = "checkmark.seal",
        description = "Checkmark"
    ),
    launchScreen = LaunchScreenSpec(
        backgroundColor = "#F8FAFF",
        logoPosition = "center",
        tagline = "Plan. Focus. Achieve.",
        animationDuration = 1.1,
        description = "Checklist draw-in"
    ),
    colorPalette = ColorPalette(
        primary = "#2563EB",
        secondary = "#10B981",
        accent = "#F59E0B",
        background = "#F8FAFF",
        surface = "#FFFFFF",
        error = "#EF4444",
        textPrimary = "#0F172A",
        textSecondary = "#475569"
    ),
    tokens = DesignTokens(spacing = SpacingScale(values = mapOf("s" to 8, "m" to 12, "l" to 16, "xl" to 24))),
    components = ComponentLibrarySpec(
        button = ButtonSpec(
            variants = mapOf(
                "primary" to ControlStateStyles(
                    normal = ControlStyle(
                        "#2563EB",
                        "#FFFFFF"
                    ), pressed = ControlStyle("#1E40AF", "#FFFFFF")
                )
            )
        )
    ),
    tabBar = TabBarSpec(
        items = listOf(
            TabItemSpec("Today", "sun.max.fill"),
            TabItemSpec("Projects", "folder.fill"),
            TabItemSpec("Focus", "target"),
            TabItemSpec("Profile", "person")
        ), selectedColor = "#2563EB", unselectedColor = "#64748B"
    )
)

internal val exampleDesignSpecification_NewsApp = DesignSpecification(
    appName = "DailyPulse",
    appIcon = AppIconSpec(
        style = "flat",
        primaryColor = "#111827",
        secondaryColor = "#EF4444",
        symbol = "newspaper",
        description = "Newspaper"
    ),
    launchScreen = LaunchScreenSpec(
        backgroundColor = "#111827",
        logoPosition = "center",
        tagline = "Stay informed",
        animationDuration = 1.0,
        description = "News ticker"
    ),
    colorPalette = ColorPalette(
        primary = "#EF4444",
        secondary = "#22D3EE",
        accent = "#FBBF24",
        background = "#F8FAFC",
        surface = "#FFFFFF",
        error = "#DC2626",
        textPrimary = "#111827",
        textSecondary = "#6B7280"
    ),
    theme = ThemeSpec(
        light = SemanticColors(
            primary = "#EF4444",
            onPrimary = "#FFFFFF",
            background = "#FFFFFF",
            onBackground = "#111827",
            surface = "#FFFFFF",
            onSurface = "#111827",
            divider = "#E5E7EB"
        ),
        dark = SemanticColors(
            primary = "#F87171",
            onPrimary = "#0A0A0A",
            background = "#0B0F19",
            onBackground = "#E5E7EB",
            surface = "#111827",
            onSurface = "#F9FAFB",
            divider = "#222833"
        )
    ),
    tokens = DesignTokens(
        typography = TypographyScale(
            textStyles = listOf(
                TextStyleSpec(
                    "Headline",
                    "SF Pro Display",
                    26.0,
                    32.0,
                    "Bold"
                ), TextStyleSpec("Body", "SF Pro Text", 16.0, 22.0, "Regular")
            )
        )
    ),
    spacingAndGrid = SpacingAndGridSpec(grid = GridSpec(12, 12, 20, 16), divider = DividerSpec("#E5E7EB", 1, 16)),
    tabBar = TabBarSpec(
        items = listOf(
            TabItemSpec("Top", "chart.bar.fill"),
            TabItemSpec("Latest", "bolt.fill"),
            TabItemSpec("Topics", "tag.fill"),
            TabItemSpec("Profile", "person")
        ), selectedColor = "#EF4444", unselectedColor = "#6B7280"
    )
)

internal val exampleDesignSpecification_EducationApp = DesignSpecification(
    appName = "Learnly",
    appIcon = AppIconSpec(
        style = "flat",
        primaryColor = "#0EA5E9",
        secondaryColor = "#22C55E",
        symbol = "book",
        description = "Book icon"
    ),
    launchScreen = LaunchScreenSpec(
        backgroundColor = "#F0F9FF",
        logoPosition = "center",
        tagline = "Learn something new",
        animationDuration = 1.2,
        description = "Page flip"
    ),
    colorPalette = ColorPalette(
        primary = "#0EA5E9",
        secondary = "#22C55E",
        accent = "#F59E0B",
        background = "#F8FAFC",
        surface = "#FFFFFF",
        error = "#EF4444",
        textPrimary = "#0F172A",
        textSecondary = "#475569"
    ),
    theme = ThemeSpec(
        light = SemanticColors(
            primary = "#0EA5E9",
            onPrimary = "#FFFFFF",
            secondary = "#22C55E",
            onSecondary = "#0A0A0A",
            background = "#F8FAFC",
            onBackground = "#0F172A",
            surface = "#FFFFFF",
            onSurface = "#0F172A",
            divider = "#E5E7EB"
        ),
        dark = SemanticColors(
            primary = "#38BDF8",
            onPrimary = "#0A0A0A",
            secondary = "#34D399",
            onSecondary = "#0A0A0A",
            background = "#0B1018",
            onBackground = "#E2E8F0",
            surface = "#101826",
            onSurface = "#E2E8F0",
            divider = "#233047"
        )
    ),
    tokens = DesignTokens(
        typography = TypographyScale(
            textStyles = listOf(
                TextStyleSpec(
                    "Title",
                    "SF Pro Display",
                    24.0,
                    30.0,
                    "Bold"
                ), TextStyleSpec("Body", "SF Pro Text", 16.0, 22.0, "Regular")
            )
        ), spacing = SpacingScale(values = mapOf("s" to 8, "m" to 12, "l" to 16, "xl" to 24))
    ),
    components = ComponentLibrarySpec(
        segmentedControl = SegmentedControlSpec(
            states = ControlStateStyles(
                normal = ControlStyle(
                    "#FFFFFF",
                    "#0F172A",
                    "#E5E7EB",
                    1,
                    10
                ), highlighted = ControlStyle("#0EA5E9", "#FFFFFF")
            )
        )
    ),
    tabBar = TabBarSpec(
        items = listOf(
            TabItemSpec("Courses", "graduationcap.fill"),
            TabItemSpec("Explore", "magnifyingglass"),
            TabItemSpec("Profile", "person")
        ), selectedColor = "#0EA5E9", unselectedColor = "#64748B"
    )
)
