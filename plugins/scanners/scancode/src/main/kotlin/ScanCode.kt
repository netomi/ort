/*
 * Copyright (C) 2017 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.plugins.scanners.scancode

import java.io.File
import java.time.Instant

import kotlin.math.max

import org.apache.logging.log4j.kotlin.logger

import org.ossreviewtoolkit.model.ScanSummary
import org.ossreviewtoolkit.model.ScannerDetails
import org.ossreviewtoolkit.model.config.PluginConfiguration
import org.ossreviewtoolkit.model.config.ScannerConfiguration
import org.ossreviewtoolkit.scanner.CommandLinePathScannerWrapper
import org.ossreviewtoolkit.scanner.ScanContext
import org.ossreviewtoolkit.scanner.ScanResultsStorage
import org.ossreviewtoolkit.scanner.ScannerMatcher
import org.ossreviewtoolkit.scanner.ScannerWrapperConfig
import org.ossreviewtoolkit.scanner.ScannerWrapperFactory
import org.ossreviewtoolkit.utils.common.Options
import org.ossreviewtoolkit.utils.common.Os
import org.ossreviewtoolkit.utils.common.ProcessCapture
import org.ossreviewtoolkit.utils.common.safeDeleteRecursively
import org.ossreviewtoolkit.utils.common.splitOnWhitespace
import org.ossreviewtoolkit.utils.common.withoutPrefix
import org.ossreviewtoolkit.utils.ort.createOrtTempDir

import org.semver4j.RangesList
import org.semver4j.RangesListFactory
import org.semver4j.Semver

/**
 * A wrapper for [ScanCode](https://github.com/nexB/scancode-toolkit).
 *
 * This scanner can be configured in [ScannerConfiguration.config] using the key "ScanCode". It offers the following
 * configuration [options][PluginConfiguration.options]:
 *
 * * **"commandLine":** Command line options that modify the result. These are added to the [ScannerDetails] when
 *   looking up results from the [ScanResultsStorage]. Defaults to [DEFAULT_CONFIGURATION_OPTIONS].
 * * **"commandLineNonConfig":** Command line options that do not modify the result and should therefore not be
 *   considered in [configuration], like "--processes". Defaults to [DEFAULT_NON_CONFIGURATION_OPTIONS].
 */
class ScanCode internal constructor(
    name: String,
    config: ScanCodeConfig,
    private val wrapperConfig: ScannerWrapperConfig
) : CommandLinePathScannerWrapper(name) {
    // This constructor is required by the `RequirementsCommand`.
    constructor(name: String, wrapperConfig: ScannerWrapperConfig) : this(name, ScanCodeConfig.EMPTY, wrapperConfig)

    companion object {
        const val SCANNER_NAME = "ScanCode"

        private const val LICENSE_REFERENCES_OPTION_VERSION = "32.0.0"
        private const val OUTPUT_FORMAT = "json-pp"
        internal const val TIMEOUT = 300

        /**
         * Configuration options that are relevant for [configuration] because they change the result file.
         */
        private val DEFAULT_CONFIGURATION_OPTIONS = listOf(
            "--copyright",
            "--license",
            "--info",
            "--strip-root",
            "--timeout", TIMEOUT.toString()
        )

        /**
         * Configuration options that are not relevant for [configuration] because they do not change the result
         * file.
         */
        private val DEFAULT_NON_CONFIGURATION_OPTIONS = listOf(
            "--processes", max(1, Runtime.getRuntime().availableProcessors() - 1).toString()
        )

        private val OUTPUT_FORMAT_OPTION = if (OUTPUT_FORMAT.startsWith("json")) {
            "--$OUTPUT_FORMAT"
        } else {
            "--output-$OUTPUT_FORMAT"
        }
    }

    class Factory : ScannerWrapperFactory<ScanCodeConfig>(SCANNER_NAME) {
        override fun create(config: ScanCodeConfig, wrapperConfig: ScannerWrapperConfig) =
            ScanCode(type, config, wrapperConfig)

        override fun parseConfig(options: Options, secrets: Options) = ScanCodeConfig.create(options)
    }

    override val matcher by lazy { ScannerMatcher.create(details, wrapperConfig.matcherConfig) }

    override val readFromStorage by lazy { wrapperConfig.readFromStorageWithDefault(matcher) }

    override val writeToStorage by lazy { wrapperConfig.writeToStorageWithDefault(matcher) }

    override val configuration by lazy {
        buildList {
            addAll(configurationOptions)
            add(OUTPUT_FORMAT_OPTION)
        }.joinToString(" ")
    }

    private val configurationOptions = config.commandLine?.splitOnWhitespace() ?: DEFAULT_CONFIGURATION_OPTIONS
    private val nonConfigurationOptions = config.commandLineNonConfig?.splitOnWhitespace()
        ?: DEFAULT_NON_CONFIGURATION_OPTIONS

    internal fun getCommandLineOptions(version: String) =
        buildList {
            addAll(configurationOptions)
            addAll(nonConfigurationOptions)

            if (Semver(version).isGreaterThanOrEqualTo(LICENSE_REFERENCES_OPTION_VERSION)) {
                // Required to be able to map ScanCode license keys to SPDX IDs.
                add("--license-references")
            }
        }

    val commandLineOptions by lazy { getCommandLineOptions(version) }

    override fun command(workingDir: File?) =
        listOfNotNull(workingDir, if (Os.isWindows) "scancode.bat" else "scancode").joinToString(File.separator)

    override fun getVersion(workingDir: File?): String =
        // The release candidate version names lack a hyphen in between the minor version and the extension, e.g.
        // 3.2.1rc2. Insert that hyphen for compatibility with Semver.
        super.getVersion(workingDir).let {
            val index = it.indexOf("rc")
            if (index != -1) {
                "${it.substring(0, index)}-${it.substring(index)}"
            } else {
                it
            }
        }

    override fun getVersionRequirement(): RangesList = RangesListFactory.create(">=3.0.0")

    override fun transformVersion(output: String): String {
        // On first use, the output is prefixed by "Configuring ScanCode for first use...". The version string can be
        // something like:
        // ScanCode version 2.0.1.post1.fb67a181
        // ScanCode version: 31.0.0b4
        return output.lineSequence().firstNotNullOfOrNull { line ->
            line.withoutPrefix("ScanCode version")?.removePrefix(":")?.trim()
        }.orEmpty()
    }

    override fun runScanner(path: File, context: ScanContext): String {
        val resultFile = createOrtTempDir().resolve("result.json")
        val process = runScanCode(path, resultFile)

        return with(process) {
            if (stderr.isNotBlank()) logger.debug { stderr }

            // Do not throw yet if the process exited with an error as some errors might turn out to be tolerable during
            // parsing.

            resultFile.readText().also { resultFile.parentFile.safeDeleteRecursively(force = true) }
        }
    }

    override fun createSummary(result: String, startTime: Instant, endTime: Instant): ScanSummary =
        parseResult(result).toScanSummary()

    /**
     * Execute ScanCode with the configured arguments to scan the given [path] and produce [resultFile].
     */
    internal fun runScanCode(path: File, resultFile: File) =
        ProcessCapture(
            command(),
            *commandLineOptions.toTypedArray(),
            path.absolutePath,
            OUTPUT_FORMAT_OPTION,
            resultFile.absolutePath
        )
}
