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

    private void setupGitHubApiResponse(String branchSha, String... commitFiles) {
        // Create branch API response
        HttpResponse<String> branchResponse = mock(HttpResponse.class);
        when(branchResponse.statusCode()).thenReturn(200);
        when(branchResponse.body()).thenReturn(String.format("{\"commit\": {\"sha\": \"%s\"}}", branchSha));

        // Create first page of commits response with the base commit included
        // By including the merge base commit in the response, we force the recursion to stop
        HttpResponse<String> commitsResponse = mock(HttpResponse.class);
        when(commitsResponse.statusCode()).thenReturn(200);
        StringBuilder commitsArrayBuilder = new StringBuilder("[");

        // Add all your test commits
        for (int i = 0; i < commitFiles.length; i++) {
            if (i > 0) commitsArrayBuilder.append(",");
            commitsArrayBuilder.append(String.format("{\"sha\": \"commit%d\"}", i + 1));
        }

        // Add the merge base commit - this is crucial to stop the recursion
        if (commitFiles.length > 0) {
            commitsArrayBuilder.append(",");
        }
        commitsArrayBuilder.append("{\"sha\": \"mergebasecommithash123\"}");

        commitsArrayBuilder.append("]");
        when(commitsResponse.body()).thenReturn(commitsArrayBuilder.toString());

        // Create commit detail responses
        List<HttpResponse<String>> commitDetailResponses = new ArrayList<>();
        for (String commitFile : commitFiles) {
            HttpResponse<String> commitResponse = mock(HttpResponse.class);
            when(commitResponse.statusCode()).thenReturn(200);
            when(commitResponse.body()).thenReturn(commitFile);
            commitDetailResponses.add(commitResponse);
        }

        // Setup the sendAsync mocks with a simpler approach
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    String uri = request.uri().toString();

                    if (uri.endsWith("/branches/branchA")) {
                        return CompletableFuture.completedFuture(branchResponse);
                    } else if (uri.contains("/commits?sha=")) {
                        // All commits pages return the same response with merge base included
                        // This ensures recursion stops after the first page
                        return CompletableFuture.completedFuture(commitsResponse);
                    } else {
                        // Match commit details requests
                        for (int i = 0; i < commitFiles.length; i++) {
                            if (uri.endsWith("/commits/commit" + (i + 1))) {
                                return CompletableFuture.completedFuture(commitDetailResponses.get(i));
                            }
                        }

                        // Default response for any other request
                        HttpResponse<String> defaultResponse = mock(HttpResponse.class);
                        when(defaultResponse.statusCode()).thenReturn(200);
                        when(defaultResponse.body()).thenReturn("{}");
                        return CompletableFuture.completedFuture(defaultResponse);
                    }
                });
    }
    @Test
    void findPotentialConflicts_withValidSetup_shouldReturnConflicts() throws Exception {
        // Setup command responses
        commandExecutor.setResponses(
                "true",                         // rev-parse response
                "* branchA\n  branchB",         // branch list response
                "mergebasecommithash123",       // merge-base response for origin/branchA and branchB
                "src/file1.js\nsrc/file2.js\nsrc/file3.js", // diff response
                "100644 blob sha123 src/file1.js\n100644 blob sha456 src/file2.js" // ls-tree response
        );

        // Use the helper to set up GitHub API responses
        setupGitHubApiResponse(
                "headcommit123",
                "{\"files\": [{\"filename\": \"src/file1.js\", \"status\": \"modified\", \"sha\": \"remote-sha123\"}, " +
                        "{\"filename\": \"src/file4.js\", \"status\": \"added\", \"sha\": \"remote-sha789\"}]}"
        );

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
        verify(commandExecutor, atLeastOnce()).executeCommand(eq(defaultConfig.getLocalRepoPath()), contains("ls-tree"));
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

//    @Test
//    void findPotentialConflicts_withGitHubApiError_shouldReturnError() throws Exception {
//        // Setup command responses
//        commandExecutor.setResponses(
//                "true",                         // rev-parse response
//                "* branchA\n  branchB",         // branch list response
//                "mergebasecommithash123",       // merge-base response
//                "src/file1.js\nsrc/file2.js\nsrc/file3.js", // diff response
//                "100644 blob sha123 src/file1.js\n100644 blob sha456 src/file2.js" // ls-tree response
//        );
//
//        // Setup mock error response
//        HttpResponse<String> errorResponse = mock(HttpResponse.class);
//        when(errorResponse.statusCode()).thenReturn(404);
//        when(errorResponse.body()).thenReturn("{\"message\": \"Not Found\"}");
//
//        when(mockHttpClient.sendAsync(any(HttpRequest.class), any()))
//                .thenReturn(CompletableFuture.completedFuture(errorResponse));
//
//        // Execute the method under test
//        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();
//        ConflictDetectionResult result = resultFuture.get();
//
//        // Verify error is returned
//        assertTrue(result.getError().contains("GitHub API error (404)"));
//        assertTrue(result.getPotentialConflicts().isEmpty());
//    }

    @Test
    void findPotentialConflicts_withNoCommonChanges_shouldReturnEmptyList() throws Exception {
        // Setup command responses
        commandExecutor.setResponses(
                "true",                       // rev-parse response
                "* branchA\n  branchB",       // branch list response
                "mergebasecommithash123",     // merge-base response
                "src/file5.js\nsrc/file6.js", // No overlap with remote changes
                "100644 blob sha789 src/file5.js\n100644 blob sha101 src/file6.js" // ls-tree response
        );

        // Use the helper to set up GitHub API responses
        setupGitHubApiResponse(
                "headcommit123",
                "{\"files\": [{\"filename\": \"src/file1.js\", \"status\": \"modified\", \"sha\": \"remote-sha123\"}, " +
                        "{\"filename\": \"src/file4.js\", \"status\": \"added\", \"sha\": \"remote-sha789\"}]}"
        );

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
                "src/file1.js\nsrc/file2.js\nsrc/file4.js", // Multiple overlaps
                "100644 blob sha123 src/file1.js\n100644 blob sha456 src/file2.js\n100644 blob sha789 src/file4.js" // ls-tree response
        );

        // Use the helper to set up GitHub API responses
        setupGitHubApiResponse(
                "headcommit123",
                "{\"files\": [" +
                        "{\"filename\": \"src/file1.js\", \"status\": \"modified\", \"sha\": \"remote-sha123\"}, " +
                        "{\"filename\": \"src/file2.js\", \"status\": \"modified\", \"sha\": \"remote-sha456\"}, " +
                        "{\"filename\": \"src/file4.js\", \"status\": \"modified\", \"sha\": \"remote-sha999\"}" +
                        "]}"
        );

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
                "mergebasecommithash123",        // merge-base response
                "src/file1.js",              // diff response
                "100644 blob sha123 src/file1.js" // ls-tree response
        );

        // Setup mock HTTP client to simulate network failure
        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(new IOException("Network connection failed")));

        // Execute the method under test
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
                "src/file1.js\nsrc/file2.js\nsrc/file3.js", // Different status types
                "100644 blob sha123 src/file1.js\n100644 blob sha456 src/file2.js" // ls-tree response
        );

        // Use the helper to set up GitHub API responses
        setupGitHubApiResponse(
                "headcommit123",
                "{\"files\": [" +
                        "{\"filename\": \"src/file1.js\", \"status\": \"modified\", \"sha\": \"remote-sha123\"}, " +
                        "{\"filename\": \"src/file2.js\", \"status\": \"added\", \"sha\": \"remote-sha456\"}, " +
                        "{\"filename\": \"src/file3.js\", \"status\": \"removed\", \"sha\": \"remote-sha456\"}, " +
                        "{\"filename\": \"src/file4.js\", \"status\": \"modified\", \"sha\": \"remote-sha789\"}" +
                        "]}"
        );

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
                "src/file1.js\nsrc/file2.js", // diff response
                "100644 blob sha123 src/file1.js\n100644 blob sha456 src/file2.js" // ls-tree response
        );

        // Use the helper to set up GitHub API responses
        setupGitHubApiResponse(
                "headcommit123",
                "{\"files\": []}"
        );

        // Execute the method under test
        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();
        ConflictDetectionResult result = resultFuture.get();

        // Verify results
        assertNull(result.getError());
        assertEquals("mergebasecommithash123", result.getMergeBaseCommit());
        assertTrue(result.getPotentialConflicts().isEmpty()); // No conflicts with empty remote files
    }

    @Test
    void findPotentialConflicts_withLargeNumberOfFiles_shouldHandleEfficiently() throws Exception {
        // Generate a smaller but still substantial diff response for testing
        StringBuilder localDiffBuilder = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            if (i > 1) localDiffBuilder.append("\n");
            localDiffBuilder.append("src/file").append(i).append(".js");
        }

        // Generate ls-tree response for local files
        StringBuilder lsTreeBuilder = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            if (i > 1) lsTreeBuilder.append("\n");
            lsTreeBuilder.append("100644 blob sha").append(i)
                    .append(" src/file").append(i).append(".js");
        }

        // Setup all command responses together
        commandExecutor.setResponses(
                "true",                         // rev-parse response
                "* branchA\n  branchB",         // branch list response
                "mergebasecommithash123",       // merge-base response
                localDiffBuilder.toString(),    // large diff response
                lsTreeBuilder.toString()        // ls-tree response
        );

        // Generate remote files response
        StringBuilder remoteFilesBuilder = new StringBuilder("{\"files\": [");
        for (int i = 1; i <= 50; i += 2) { // Just add odd-numbered files for simplicity
            if (i > 1) remoteFilesBuilder.append(", ");
            remoteFilesBuilder.append("{\"filename\": \"src/file").append(i)
                    .append(".js\", \"status\": \"modified")
                    .append("\", \"sha\": \"remote-sha").append(i).append("\"}");
        }
        remoteFilesBuilder.append("]}");

        // Use the helper to set up GitHub API responses
        setupGitHubApiResponse(
                "headcommit123",
                remoteFilesBuilder.toString()
        );

        // Execute the method under test
        CompletableFuture<ConflictDetectionResult> resultFuture = detector.findPotentialConflicts();
        ConflictDetectionResult result = resultFuture.get(30, TimeUnit.SECONDS);

        // Verify results
        assertNull(result.getError());
        assertEquals("mergebasecommithash123", result.getMergeBaseCommit());

        // We expect ~25 conflicts (every odd-numbered file from 1 to 49)
        assertTrue(result.getPotentialConflicts().size() > 0);

        // Check a few specific files to verify correctness
        assertTrue(result.getPotentialConflicts().contains("src/file1.js"));
        assertTrue(result.getPotentialConflicts().contains("src/file25.js"));
        assertTrue(result.getPotentialConflicts().contains("src/file49.js"));

        // Verify files that should NOT be in the conflict list
        assertFalse(result.getPotentialConflicts().contains("src/file2.js"));
        assertFalse(result.getPotentialConflicts().contains("src/file50.js"));
    }
}