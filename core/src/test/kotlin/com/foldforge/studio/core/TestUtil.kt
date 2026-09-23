package com.foldforge.studio.core

import java.io.File
import java.nio.file.Files

fun tempDir(prefix: String = "ff"): File = Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }
