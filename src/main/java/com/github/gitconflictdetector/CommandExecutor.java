package com.github.gitconflictdetector;

import java.io.IOException;

/**
 * Interface for command execution to allow for testing
 */
public interface CommandExecutor {
    Process executeCommand(String workingDir, String command) throws IOException;
}

