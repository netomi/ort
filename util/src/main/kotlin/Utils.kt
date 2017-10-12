package com.here.ort.util

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

import com.vdurmont.semver4j.Semver

import java.io.File
import java.io.IOException

@Suppress("UnsafeCast")
val log = org.slf4j.LoggerFactory.getLogger({}.javaClass) as ch.qos.logback.classic.Logger

val jsonMapper = ObjectMapper().registerKotlinModule()
val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

/**
 * Return the directory to store user-specific configuration in.
 */
fun getUserConfigDirectory() = File(System.getProperty("user.home"), ".ort")

/**
 * Parse the standard output of a process as JSON.
 */
fun parseJsonProcessOutput(workingDir: File, vararg command: String, multiJson: Boolean = false): JsonNode {
    val process = ProcessCapture(workingDir, *command).requireSuccess()

    // Support parsing multiple lines with one JSON object per line by wrapping the whole output into a JSON array.
    if (multiJson) {
        val array = JsonNodeFactory.instance.arrayNode()
        process.stdoutFile.readLines().forEach { array.add(jsonMapper.readTree(it)) }
        return array
    }

    return jsonMapper.readTree(process.stdout())
}

/**
 * Run a command to get its version.
 */
fun getCommandVersion(command: String, versionArgument: String = "--version",
                      semverType: Semver.SemverType = Semver.SemverType.LOOSE,
                      transform: (String) -> String = { it }): Semver {
    val version = ProcessCapture(command, versionArgument).requireSuccess()

    var versionString = transform(version.stdout().trim())
    if (versionString.isEmpty()) {
        // Fall back to trying to read the version from stderr.
        versionString = version.stderr().trim()
    }

    return Semver(versionString, semverType)
}

/**
 * Run a command to check it for specific version.
 */
fun checkCommandVersion(command: String, expectedVersion: Semver, versionArgument: String = "--version",
                        ignoreActualVersion: Boolean = false, transform: (String) -> String = { it }) {
    val actualVersion = getCommandVersion(command, versionArgument, expectedVersion.type, transform)
    if (actualVersion != expectedVersion) {
        val messagePrefix = "Unsupported $command version $actualVersion, version $expectedVersion is "
        if (ignoreActualVersion) {
            println(messagePrefix + "expected.")
            println("Still continuing because you chose to ignore the actual version.")
        } else {
            throw IOException(messagePrefix + "required.")
        }
    }
}

/**
 * Create all missing intermediate directories without failing if any already exists.
 *
 * @throws IOException if any missing directory could not be created.
 */
fun File.safeMkdirs() {
    if (this.isDirectory || this.mkdirs()) {
        return
    }

    throw IOException("Could not create directory ${this.absolutePath}.")
}
