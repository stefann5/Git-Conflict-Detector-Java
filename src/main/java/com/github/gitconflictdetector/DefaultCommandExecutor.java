package com.github.gitconflictdetector;

import java.io.File;
import java.io.IOException;

/**
 * Default implementation of CommandExecutor using ProcessBuilder
 */
class DefaultCommandExecutor implements CommandExecutor {
    @Override
    public Process executeCommand(String workingDir, String command) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(new File(workingDir));

        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            processBuilder.command("cmd.exe", "/c", command);
        } else {
            processBuilder.command("bash", "-c", command);
        }

        return processBuilder.start();
    }
}
