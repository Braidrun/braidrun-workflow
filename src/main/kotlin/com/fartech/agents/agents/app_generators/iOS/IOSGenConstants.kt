package com.fartech.agents.agents.app_generators.iOS

/**
 * Centralized iOS generator constants.
 *
 * Shared defaults like file/media generation batch size and parallelism are defined in
 * `com.fartech.agents.commons.Defaults`. This file only holds iOS-specific defaults
 * that are not generic to all agents.
 */
object IOSGenConstants {
    /** Maximum retry attempts for media generation tasks (image, icon, splash, etc.). */
    const val DEFAULT_MEDIA_RETRY_MAX_ATTEMPTS: Int = 6

    /** Maximum retries for running xcodegen to generate Xcode project from project.yml. */
    const val DEFAULT_XCODEGEN_MAX_RETRIES: Int = 3

    const val DEFAULT_ARCHITECTURE_DESIGN_MAX_RETRIES = 3

    /** Maximum retry attempts for architecture quality review (redesign loop). */
    const val DEFAULT_ARCHITECTURE_REVIEW_MAX_RETRIES = 2

    /** Minimum quality score threshold for architecture review to pass (0-100). */
    const val ARCHITECTURE_QUALITY_SCORE_THRESHOLD = 70

    /** Maximum retry attempts for design review (re-generate design spec). */
    const val DEFAULT_DESIGN_REVIEW_MAX_RETRIES = 2

    /** Maximum retry attempts for slicing (assets) review (re-slice). */
    const val DEFAULT_SLICING_REVIEW_MAX_RETRIES = 2

    // Parallel file generation settings
    const val DEFAULT_FILE_GENERATION_BATCH_SIZE = 5
    const val DEFAULT_FILE_GENERATION_PARALLELISM = 5
    const val DEFAULT_MEDIA_GENERATION_PARALLELISM = 2

    /** Maximum retry attempts for source file generation. */
    const val DEFAULT_SOURCE_FILE_MAX_RETRIES = 3
}
