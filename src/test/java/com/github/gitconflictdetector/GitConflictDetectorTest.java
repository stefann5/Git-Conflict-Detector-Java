package com.github.gitconflictdetector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    @Test
    void findPotentialConflicts_withNetworkFailure_shouldReturnError() throws Exception {
        // Setup command responses
        commandExecutor.setResponses(
                "true",                         // rev-parse response
                "* branchA\n  branchB",         // branch list response
                "mergebasecommithash123"        // merge-base response
        );

        // Setup mock HTTP client to simulate network failure
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IOException("Network connection failed")));

        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();
        ConflictDetectionResult result = resultFuture.get();

        // Verify error is returned
        assertNotNull(result.getError());
        assertTrue(result.getError().contains("Error getting remote changes") ||
                result.getError().contains("Network connection failed"));
        assertTrue(result.getPotentialConflicts().isEmpty());
    }

    @Test
    void findPotentialConflicts_withDifferentFileStatuses_shouldIdentifyCorrectly() throws Exception {
        // Setup command responses with different status types (Modified, Added, Deleted)
        commandExecutor.setResponses(
                "true",                         // rev-parse response
                "* branchA\n  branchB",         // branch list response
                "mergebasecommithash123",       // merge-base response
                "M\tsrc/file1.js\nA\tsrc/file2.js\nD\tsrc/file3.js" // Different status types
        );

        // Setup mock HTTP client with various statuses
        setupGithubApiMock(200,
                "{\"files\": [" +
                        "{\"filename\": \"src/file1.js\", \"status\": \"modified\"}, " +
                        "{\"filename\": \"src/file2.js\", \"status\": \"added\"}, " +
                        "{\"filename\": \"src/file3.js\", \"status\": \"removed\"}, " +
                        "{\"filename\": \"src/file4.js\", \"status\": \"modified\"}" +
                        "]}");

        // Execute the method under test
        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();
        ConflictDetectionResult result = resultFuture.get();

        // Verify results
        assertNull(result.getError());
        assertEquals("mergebasecommithash123", result.getMergeBaseCommit());
        assertEquals(3, result.getPotentialConflicts().size());
        assertTrue(result.getPotentialConflicts().contains("src/file1.js"));
        assertTrue(result.getPotentialConflicts().contains("src/file2.js"));
        assertTrue(result.getPotentialConflicts().contains("src/file3.js"));
        assertFalse(result.getPotentialConflicts().contains("src/file4.js")); // Only in remote
    }

    @Test
    void findPotentialConflicts_withEmptyFilesFromGitHub_shouldHandleGracefully() throws Exception {
        // Setup command responses
        commandExecutor.setResponses(
                "true",                         // rev-parse response
                "* branchA\n  branchB",         // branch list response
                "mergebasecommithash123",       // merge-base response
                "M\tsrc/file1.js\nA\tsrc/file2.js" // diff response
        );

        // Setup mock HTTP client with empty files array
        setupGithubApiMock(200, "{\"files\": []}");

        // Execute the method under test
        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();
        ConflictDetectionResult result = resultFuture.get();

        // Verify results
        assertNull(result.getError());
        assertEquals("mergebasecommithash123", result.getMergeBaseCommit());
        assertTrue(result.getPotentialConflicts().isEmpty()); // No conflicts with empty remote files
    }

    @Test
    void findPotentialConflicts_with1000Files_shouldHandleLargeNumberOfFiles() throws Exception {
        // Generate a large diff response with 1000 files
        StringBuilder localDiffBuilder = new StringBuilder();
        for (int i = 1; i <= 1000; i++) {
            String status = (i % 3 == 0) ? "M" : (i % 3 == 1) ? "A" : "D";
            localDiffBuilder.append(status).append("\tsrc/file").append(i).append(".js\n");
        }

        // Setup all command responses together
        commandExecutor.setResponses(
                "true",                         // rev-parse response
                "* branchA\n  branchB",         // branch list response
                "mergebasecommithash123",       // merge-base response
                localDiffBuilder.toString()     // large diff response
        );

        // Generate GitHub API response with 1000 files
        // Make 500 of them overlap with local changes (every other file)
        StringBuilder githubResponseBuilder = new StringBuilder("{\"files\": [");
        for (int i = 1; i <= 1000; i += 2) { // Every odd-numbered file will be in both sets
            String status = (i % 3 == 0) ? "modified" : (i % 3 == 1) ? "added" : "removed";
            githubResponseBuilder.append("{\"filename\": \"src/file").append(i).append(".js\", \"status\": \"")
                    .append(status).append("\"}");

            if (i < 999) {
                githubResponseBuilder.append(", ");
            }
        }
        githubResponseBuilder.append("]}");

        // Setup mock HTTP client with the large response
        setupGithubApiMock(200, githubResponseBuilder.toString());

        // Execute the method under test
        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();
        ConflictDetectionResult result = resultFuture.get();

        // Verify results
        assertNull(result.getError(), "There should be no error when processing 1000 files");
        assertEquals("mergebasecommithash123", result.getMergeBaseCommit());

        // We expect 500 conflicts (every odd-numbered file from 1 to 999)
        assertEquals(500, result.getPotentialConflicts().size(),
                "Should identify exactly 500 potential conflicts");

        // Check a few specific files to verify correctness
        assertTrue(result.getPotentialConflicts().contains("src/file1.js"));
        assertTrue(result.getPotentialConflicts().contains("src/file501.js"));
        assertTrue(result.getPotentialConflicts().contains("src/file999.js"));

        // Verify files that should NOT be in the conflict list
        assertFalse(result.getPotentialConflicts().contains("src/file2.js"));
        assertFalse(result.getPotentialConflicts().contains("src/file500.js"));
        assertFalse(result.getPotentialConflicts().contains("src/file1000.js"));

        // Verify the correct commands were executed
        verify(commandExecutor, atLeastOnce()).executeCommand(eq(defaultConfig.getLocalRepoPath()), contains("rev-parse"));
        verify(commandExecutor, atLeastOnce()).executeCommand(eq(defaultConfig.getLocalRepoPath()), contains("branch"));
        verify(commandExecutor, atLeastOnce()).executeCommand(eq(defaultConfig.getLocalRepoPath()), contains("merge-base"));
        verify(commandExecutor, atLeastOnce()).executeCommand(eq(defaultConfig.getLocalRepoPath()), contains("diff"));
    }

    @Test
    void findPotentialConflicts_with100000FilesInBoth_shouldHandleVeryLargeNumberOfFiles() throws Exception {
        // Generate 100,000 files for the local diff response
        StringBuilder localDiffBuilder = new StringBuilder();
        for (int i = 1; i <= 100000; i++) {
            String status = (i % 3 == 0) ? "M" : (i % 3 == 1) ? "A" : "D";
            localDiffBuilder.append(status).append("\tsrc/file").append(i).append(".js\n");
        }

        // Setup all command responses together
        commandExecutor.setResponses(
                "true",                         // rev-parse response
                "* branchA\n  branchB",         // branch list response
                "mergebasecommithash123",       // merge-base response
                localDiffBuilder.toString()     // very large diff response with 100,000 files
        );


        // Start with opening of JSON
        String jsonStart = "{\"files\": [";

        int expectedConflictCount = 0;

        // Create a smaller sample of expected conflicts for verification
        List<Integer> sampleConflictFiles = new ArrayList<>();

        // Generate the mock HTTP response - every file with even number will conflict
        StringBuilder mockResponseBuilder = new StringBuilder(jsonStart);

        for (int i = 1; i <= 100000; i++) {
            // For even-numbered files, use the same file number to create conflicts
            int fileNum = (i % 2 == 0) ? i : i + 100000;

            // Count expected conflicts
            if (i % 2 == 0) {
                expectedConflictCount++;

                // Add some sample files for explicit verification
                if (i == 2 || i == 50000 || i == 100000) {
                    sampleConflictFiles.add(i);
                }
            }

            String status = (i % 3 == 0) ? "modified" : (i % 3 == 1) ? "added" : "removed";

            mockResponseBuilder.append("{\"filename\": \"src/file")
                    .append(fileNum)
                    .append(".js\", \"status\": \"")
                    .append(status)
                    .append("\"}");

            // Add comma if not the last element
            if (i < 100000) {
                mockResponseBuilder.append(", ");
            }
        }

        mockResponseBuilder.append("]}");

        // Setup mock HTTP client with the very large response
        setupGithubApiMock(200, mockResponseBuilder.toString());

        // Execute the method under test with a timeout to prevent hanging
        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();

        // Set a timeout to prevent test from hanging if there are performance issues
        ConflictDetectionResult result = resultFuture.get(60, TimeUnit.SECONDS);

        // Verify results
        assertNull(result.getError(), "There should be no error when processing 100,000 files");
        assertEquals("mergebasecommithash123", result.getMergeBaseCommit());

        // We expect exactly 50,000 conflicts (every even-numbered file)
        assertEquals(expectedConflictCount, result.getPotentialConflicts().size(),
                "Should identify exactly " + expectedConflictCount + " potential conflicts");

        // Check sample files to verify correctness
        for (Integer fileNum : sampleConflictFiles) {
            String filename = "src/file" + fileNum + ".js";
            assertTrue(result.getPotentialConflicts().contains(filename),
                    "Expected conflict file missing: " + filename);
        }

        // Verify files that should NOT be in the conflict list
        assertFalse(result.getPotentialConflicts().contains("src/file100001.js"));
        assertFalse(result.getPotentialConflicts().contains("src/file199999.js"));

        // Verify the correct commands were executed
        verify(commandExecutor, atLeastOnce()).executeCommand(eq(defaultConfig.getLocalRepoPath()), contains("rev-parse"));
        verify(commandExecutor, atLeastOnce()).executeCommand(eq(defaultConfig.getLocalRepoPath()), contains("branch"));
        verify(commandExecutor, atLeastOnce()).executeCommand(eq(defaultConfig.getLocalRepoPath()), contains("merge-base"));
        verify(commandExecutor, atLeastOnce()).executeCommand(eq(defaultConfig.getLocalRepoPath()), contains("diff"));
    }

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

