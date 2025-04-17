package com.github.gitconflictdetector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitConflictDetectorTest {

    private GitConflictDetectorConfig defaultConfig;

    @TempDir
    Path tempDir;

    @Mock
    private Process mockProcess;

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockHttpResponse;

    // Create a CommandExecutor to replace ProcessBuilder mock
    private TestCommandExecutor commandExecutor;

    private GitConflictDetector detector;

    @BeforeEach
    void setUp() throws Exception {
        // Setup default config with temp directory
        String localRepoPath = tempDir.toFile().getAbsolutePath();
        defaultConfig = new GitConflictDetectorConfig.Builder()
                .owner("test-owner")
                .repo("test-repo")
                .accessToken("test-token")
                .localRepoPath(localRepoPath)
                .branchA("branchA")
                .branchB("branchB")
                .build();

        // Initialize command executor with mock process
        commandExecutor = spy(new TestCommandExecutor(mockProcess));

        // Create detector with command executor
        detector = new GitConflictDetector(defaultConfig, commandExecutor, mockHttpClient);
    }

    @Test
    void findPotentialConflicts_withValidSetup_shouldReturnConflicts() throws Exception {
        // Setup command responses
        commandExecutor.setResponses(
                "true",                         // rev-parse response
                "* branchA\n  branchB",         // branch list response
                "mergebasecommithash123",       // merge-base response
                "M\tsrc/file1.js\nA\tsrc/file2.js\nD\tsrc/file3.js" // diff response
        );


        // Setup mock HTTP client behavior for getRemoteChanges
        setupGithubApiMock(200,
                "{\"files\": [{\"filename\": \"src/file1.js\", \"status\": \"modified\"}, " +
                        "{\"filename\": \"src/file4.js\", \"status\": \"added\"}]}");

        // Execute the method under test
        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();
        ConflictDetectionResult result = resultFuture.get();

        // Verify results
        assertNull(result.getError());
        assertEquals("mergebasecommithash123", result.getMergeBaseCommit());
        assertEquals(1, result.getPotentialConflicts().size());
        assertEquals("src/file1.js", result.getPotentialConflicts().get(0));

        // Verify correct commands were executed
        verify(commandExecutor, atLeastOnce()).executeCommand(eq(defaultConfig.getLocalRepoPath()), contains("rev-parse"));
        verify(commandExecutor, atLeastOnce()).executeCommand(eq(defaultConfig.getLocalRepoPath()), contains("branch"));
        verify(commandExecutor, atLeastOnce()).executeCommand(eq(defaultConfig.getLocalRepoPath()), contains("merge-base"));
        verify(commandExecutor, atLeastOnce()).executeCommand(eq(defaultConfig.getLocalRepoPath()), contains("diff"));
    }

    @Test
    void findPotentialConflicts_withInvalidConfig_shouldReturnError() throws Exception {
        // Create a configuration with missing owner
        GitConflictDetectorConfig invalidConfig = new GitConflictDetectorConfig.Builder()
                .owner("")
                .repo("test-repo")
                .accessToken("test-token")
                .localRepoPath(tempDir.toFile().getAbsolutePath())
                .branchA("branchA")
                .branchB("branchB")
                .build();

        // Create detector with invalid config
        GitConflictDetector invalidDetector = new GitConflictDetector(invalidConfig, commandExecutor, mockHttpClient);

        // Execute the method under test
        CompletableFuture<ConflictDetectionResult> resultFuture = invalidDetector.findPotentialConflicts();
        ConflictDetectionResult result = resultFuture.get();

        // Verify error is returned
        assertEquals("Owner is required", result.getError());
        assertTrue(result.getPotentialConflicts().isEmpty());
        assertEquals("", result.getMergeBaseCommit());
    }

    @Test
    void findPotentialConflicts_withInvalidRepo_shouldReturnError() throws Exception {
        // Setup command executor to throw exception for git command
        doThrow(new IOException("Not a git repository"))
                .when(commandExecutor).executeCommand(anyString(), anyString());

        // Execute the method under test
        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();
        ConflictDetectionResult result = resultFuture.get();

        // Verify error is returned
        assertTrue(result.getError().contains("Invalid git repository"));
        assertTrue(result.getPotentialConflicts().isEmpty());
    }

    @Test
    void findPotentialConflicts_withMissingBranch_shouldReturnError() throws Exception {
        // Setup command responses - valid git repo but missing branch
        commandExecutor.setResponses(
                "true",               // rev-parse is successful
                "* master\n  develop" // But branch listing doesn't include branchB
        );

        // Execute the method under test
        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();
        ConflictDetectionResult result = resultFuture.get();

        // Verify error is returned
        assertTrue(result.getError().contains("Branch 'branchB' does not exist locally"));
        assertTrue(result.getPotentialConflicts().isEmpty());
    }

    @Test
    void findPotentialConflicts_withEmptyMergeBase_shouldReturnError() throws Exception {
        // Setup command responses - valid repo and branch but empty merge base
        commandExecutor.setResponses(
                "true",                 // rev-parse response
                "* branchA\n  branchB", // branch list response
                ""                      // empty merge-base response
        );

        // Execute the method under test
        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();
        ConflictDetectionResult result = resultFuture.get();

        // Verify error is returned
        assertTrue(result.getError().contains("Failed to find merge base"));
        assertTrue(result.getPotentialConflicts().isEmpty());
    }

    @Test
    void findPotentialConflicts_withGitHubApiError_shouldReturnError() throws Exception {
        // Setup command responses
        commandExecutor.setResponses(
                "true",                         // rev-parse response
                "* branchA\n  branchB",         // branch list response
                "mergebasecommithash123",       // merge-base response
                "M\tsrc/file1.js\nA\tsrc/file2.js\nD\tsrc/file3.js" // diff response
        );


        // Setup mock HTTP client to return error
        setupGithubApiMock(404, "{\"message\": \"Not Found\"}");

        // Execute the method under test
        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();
        ConflictDetectionResult result = resultFuture.get();

        // Verify error is returned
        assertTrue(result.getError().contains("GitHub API error (404)"));
        assertTrue(result.getPotentialConflicts().isEmpty());
    }

    @Test
    void findPotentialConflicts_withNoCommonChanges_shouldReturnEmptyList() throws Exception {
        // Setup command responses
        commandExecutor.setResponses(
                "true",                       // rev-parse response
                "* branchA\n  branchB",       // branch list response
                "mergebasecommithash123",     // merge-base response
                "M\tsrc/file5.js\nA\tsrc/file6.js" // No overlap with remote changes
        );


        // Setup mock HTTP client with remote changes that don't overlap with local
        setupGithubApiMock(200,
                "{\"files\": [{\"filename\": \"src/file1.js\", \"status\": \"modified\"}, " +
                        "{\"filename\": \"src/file4.js\", \"status\": \"added\"}]}");

        // Execute the method under test
        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();
        ConflictDetectionResult result = resultFuture.get();

        // Verify no conflicts are found
        assertNull(result.getError());
        assertEquals("mergebasecommithash123", result.getMergeBaseCommit());
        assertTrue(result.getPotentialConflicts().isEmpty());
    }

    @Test
    void findPotentialConflicts_withMultipleConflicts_shouldReturnAllConflicts() throws Exception {
        // Setup command responses
        commandExecutor.setResponses(
                "true",                         // rev-parse response
                "* branchA\n  branchB",         // branch list response
                "mergebasecommithash123",       // merge-base response
                "M\tsrc/file1.js\nA\tsrc/file2.js\nM\tsrc/file4.js" // Multiple overlaps
        );


        // Setup mock HTTP client with multiple overlapping files
        setupGithubApiMock(200,
                "{\"files\": [{\"filename\": \"src/file1.js\", \"status\": \"modified\"}, " +
                        "{\"filename\": \"src/file2.js\", \"status\": \"modified\"}, " +
                        "{\"filename\": \"src/file4.js\", \"status\": \"modified\"}]}");

        // Execute the method under test
        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();
        ConflictDetectionResult result = resultFuture.get();

        // Verify multiple conflicts are found
        assertNull(result.getError());
        assertEquals("mergebasecommithash123", result.getMergeBaseCommit());
        assertEquals(3, result.getPotentialConflicts().size());
        assertTrue(result.getPotentialConflicts().contains("src/file1.js"));
        assertTrue(result.getPotentialConflicts().contains("src/file2.js"));
        assertTrue(result.getPotentialConflicts().contains("src/file4.js"));
    }

    @Test
    void findPotentialConflicts_withGitCommandFailure_shouldReturnError() throws Exception {
        // Setup command responses for first command
        commandExecutor.setResponses("true"); // First command succeeds

        // Setup mock process for first command
        setupMockProcess(0);

        // Make the second command fail
        when(mockProcess.waitFor()).thenReturn(0).thenReturn(1); // First success, then failure
        ByteArrayInputStream errorStream = new ByteArrayInputStream("Command failed with error".getBytes());
        when(mockProcess.getErrorStream()).thenReturn(errorStream);

        // Execute the method under test
        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();
        ConflictDetectionResult result = resultFuture.get();

        // Verify error is returned
        assertTrue(result.getError().contains("Git command failed"));
    }

    // Helper methods

    private void setupMockProcess(int exitCode) throws Exception {
        when(mockProcess.waitFor()).thenReturn(exitCode);
        ByteArrayInputStream errorStream = new ByteArrayInputStream("".getBytes());
        when(mockProcess.getErrorStream()).thenReturn(errorStream);
    }

    private void setupGithubApiMock(int statusCode, String responseBody) {
        CompletableFuture<HttpResponse<String>> future = CompletableFuture.completedFuture(mockHttpResponse);
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockHttpResponse));
        when(mockHttpResponse.statusCode()).thenReturn(statusCode);
        when(mockHttpResponse.body()).thenReturn(responseBody);
    }
}

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