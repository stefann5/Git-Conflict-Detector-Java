package com.github.gitconflictdetector;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.mockito.Mockito.when;

/**
 * Custom class to handle command execution for testing.
 * This replaces ProcessBuilder which can't be mocked.
 */
class TestCommandExecutor implements CommandExecutor {
    private final Process mockProcess;
    private String[] responses;
    private int currentResponseIndex = 0;

    public TestCommandExecutor(Process mockProcess) {
        this.mockProcess = mockProcess;
    }

    public void setResponses(String... responses) {
        this.responses = responses;
        this.currentResponseIndex = 0;
    }

    public Process executeCommand(String workingDir, String command) throws IOException {
        if (responses != null && currentResponseIndex < responses.length) {
            // Set up the next response
            ByteArrayInputStream inputStream = new ByteArrayInputStream(
                    responses[currentResponseIndex++].getBytes());
            when(mockProcess.getInputStream()).thenReturn(inputStream);
            return mockProcess;
        } else {
            // If we've run out of pre-configured responses, return empty
            ByteArrayInputStream inputStream = new ByteArrayInputStream("".getBytes());
            when(mockProcess.getInputStream()).thenReturn(inputStream);
            return mockProcess;
        }
    }
}