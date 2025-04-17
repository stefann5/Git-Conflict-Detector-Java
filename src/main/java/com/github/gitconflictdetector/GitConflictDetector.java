package com.github.gitconflictdetector;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * GitConflictDetector detects potential conflicts between two branches by identifying
 * files modified in both branches since their common ancestor (merge base)
 */
public class GitConflictDetector {
    private final GitConflictDetectorConfig config;
    private final HttpClient httpClient;
    private final CommandExecutor commandExecutor;

    /**
     * Creates a new instance of GitConflictDetector with default HTTP client
     *
     * @param config The configuration object containing repository and branch information
     */
    public GitConflictDetector(GitConflictDetectorConfig config) {
        this(config, new DefaultCommandExecutor(), HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build());
    }

    /**
     * Creates a new instance of GitConflictDetector with custom HTTP client (for testing)
     *
     * @param config The configuration object containing repository and branch information
     * @param commandExecutor CommandExecutor for executing git commands
     * @param httpClient HttpClient for making GitHub API calls
     */
    public GitConflictDetector(GitConflictDetectorConfig config, CommandExecutor commandExecutor, HttpClient httpClient) {
        this.config = config;
        this.commandExecutor = commandExecutor;
        this.httpClient = httpClient;
    }

    /**
     * Find potential conflicts between branches by identifying files changed in both
     * branches since their common ancestor
     *
     * @return ConflictDetectionResult containing potential conflicts and merge base commit
     */
    public CompletableFuture<ConflictDetectionResult> findPotentialConflicts() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Validate config parameters
                String configError = validateConfig();
                if (configError != null) {
                    return new ConflictDetectionResult(new ArrayList<>(), "", configError);
                }

                // Validate local repository and branch existence
                String repoError = validateLocalRepository();
                if (repoError != null) {
                    return new ConflictDetectionResult(new ArrayList<>(), "", repoError);
                }

                // Find common ancestor commit between the two branches
                String mergeBaseCommit = findMergeBaseCommit();

                // Get files changed in local branchB compared to merge base
                List<FileChange> localChanges = getLocalChanges(mergeBaseCommit);

                // Get files changed in remote branchA compared to merge base using GitHub API
                return getRemoteChanges(mergeBaseCommit)
                        .thenApply(remoteChanges -> {
                            // Find files modified in both branches (potential conflict candidates)
                            List<String> potentialConflicts = findCommonChanges(localChanges, remoteChanges);
                            return new ConflictDetectionResult(potentialConflicts, mergeBaseCommit);
                        })
                        .exceptionally(e -> {
                            return new ConflictDetectionResult(new ArrayList<>(), "",
                                    "Error getting remote changes: " + e.getMessage());
                        })
                        .join();
            } catch (Exception e) {
                return new ConflictDetectionResult(new ArrayList<>(), "", e.getMessage());
            }
        });
    }

    /**
     * Validates the configuration parameters
     *
     * @return Error message string if validation fails, null if valid
     */
    protected String validateConfig() {
        String owner = config.getOwner();
        String repo = config.getRepo();
        String accessToken = config.getAccessToken();
        String localRepoPath = config.getLocalRepoPath();
        String branchA = config.getBranchA();
        String branchB = config.getBranchB();

        if (owner == null || owner.trim().isEmpty()) return "Owner is required";
        if (repo == null || repo.trim().isEmpty()) return "Repository name is required";
        if (accessToken == null || accessToken.trim().isEmpty()) return "Access token is required";
        if (localRepoPath == null || localRepoPath.trim().isEmpty()) return "Local repository path is required";
        if (branchA == null || branchA.trim().isEmpty()) return "Branch A name is required";
        if (branchB == null || branchB.trim().isEmpty()) return "Branch B name is required";

        File repoDir = new File(localRepoPath);
        if (!repoDir.exists()) {
            return "Local repository path does not exist: " + localRepoPath;
        }

        return null;
    }

    /**
     * Validates that the local repository is valid and contains the required branch
     *
     * @return Error message string if validation fails, null if valid
     */
    protected String validateLocalRepository() {
        String localRepoPath = config.getLocalRepoPath();
        String branchB = config.getBranchB();

        try {
            // Check if it's a git repository
            execGitCommand("rev-parse --is-inside-work-tree");

            // Check if branchB exists locally
            String branchOutput = execGitCommand("branch --list");
            Set<String> branches = new HashSet<>();

            for (String branch : branchOutput.split("\\n")) {
                String trimmed = branch.trim().replaceFirst("^\\*\\s*", "");
                if (!trimmed.isEmpty()) {
                    branches.add(trimmed);
                }
            }

            if (!branches.contains(branchB)) {
                return "Branch '" + branchB + "' does not exist locally";
            }

            return null;
        } catch (Exception e) {
            return "Invalid git repository at " + localRepoPath + ": " + e.getMessage();
        }
    }

    /**
     * Finds the common ancestor commit between branchA (remote) and branchB (local)
     *
     * @return The merge base commit SHA
     * @throws Exception if merge base cannot be found
     */
    protected String findMergeBaseCommit() throws Exception {
        String branchA = config.getBranchA();
        String branchB = config.getBranchB();

        try {
            // Get the remote reference for branchA
            String remoteRef = "origin/" + branchA;

            // Find merge base between remote branchA and local branchB
            String mergeBase = execGitCommand("merge-base " + remoteRef + " " + branchB).trim();

            if (mergeBase.isEmpty()) {
                throw new Exception("Could not find merge base between " + remoteRef + " and " + branchB);
            }

            return mergeBase;
        } catch (Exception e) {
            throw new Exception("Failed to find merge base: " + e.getMessage());
        }
    }

    /**
     * Gets files changed in local branchB compared to merge base
     *
     * @param mergeBaseCommit The merge base commit SHA
     * @return List of file changes with filename and status
     * @throws Exception if git command fails
     */
    protected List<FileChange> getLocalChanges(String mergeBaseCommit) throws Exception {
        String branchB = config.getBranchB();

        try {
            // Get a list of files changed between merge base and branchB
            String output = execGitCommand("diff --name-status " + mergeBaseCommit + " " + branchB);

            return parseGitDiffOutput(output);
        } catch (Exception e) {
            throw new Exception("Failed to get local changes: " + e.getMessage());
        }
    }

    /**
     * Gets files changed in remote branchA compared to merge base using GitHub API
     *
     * @param mergeBaseCommit The merge base commit SHA
     * @return CompletableFuture resolving to list of file changes
     */
    protected CompletableFuture<List<FileChange>> getRemoteChanges(String mergeBaseCommit) {
        String owner = config.getOwner();
        String repo = config.getRepo();
        String accessToken = config.getAccessToken();
        String branchA = config.getBranchA();

        // Compare commits using GitHub API
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/compare/" + mergeBaseCommit + "..." + branchA;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    int statusCode = response.statusCode();
                    if (statusCode < 200 || statusCode >= 300) {
                        throw new RuntimeException("GitHub API error (" + statusCode + "): " + response.body());
                    }
                    return response.body();
                })
                .thenApply(this::parseGitHubApiResponse)
                .exceptionally(e -> {
                    throw new RuntimeException("Failed to get remote changes: " + e.getMessage());
                });
    }

    /**
     * Parses GitHub API response to extract file changes
     *
     * @param jsonResponse The GitHub API response as JSON string
     * @return List of file changes with filename and status
     */
    protected List<FileChange> parseGitHubApiResponse(String jsonResponse) {
        List<FileChange> changes = new ArrayList<>();

        try {
            JSONObject json = new JSONObject(jsonResponse);

            if (!json.has("files")) {
                return changes;
            }

            JSONArray files = json.getJSONArray("files");

            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i);
                String filename = file.getString("filename");
                String status = file.getString("status");

                changes.add(new FileChange(filename, status));
            }
        } catch (JSONException e) {
            throw new RuntimeException("Failed to parse GitHub API response: " + e.getMessage());
        }

        return changes;
    }

    /**
     * Finds files that were changed in both local and remote branches
     *
     * @param localChanges List of local file changes
     * @param remoteChanges List of remote file changes
     * @return List of filenames that were changed in both branches
     */
    protected List<String> findCommonChanges(List<FileChange> localChanges, List<FileChange> remoteChanges) {
        Set<String> localFiles = localChanges.stream()
                .map(FileChange::getFilename)
                .collect(Collectors.toSet());

        return remoteChanges.stream()
                .map(FileChange::getFilename)
                .filter(localFiles::contains)
                .collect(Collectors.toList());
    }

    /**
     * Parses git diff output into FileChange objects
     *
     * @param output The git diff command output
     * @return List of FileChange objects with filename and status
     */
    protected List<FileChange> parseGitDiffOutput(String output) {
        List<FileChange> changes = new ArrayList<>();

        if (output == null || output.trim().isEmpty()) {
            return changes;
        }

        for (String line : output.trim().split("\\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }

            // Split by tab, preserving filenames with tabs
            String[] parts = line.trim().split("\\t", 2);
            if (parts.length < 2) {
                continue;
            }

            String status = parts[0].trim();
            String filename = parts[1];

            changes.add(new FileChange(filename, status));
        }

        return changes;
    }

    /**
     * Executes a git command in the local repository directory
     *
     * @param command The git command to execute (without the 'git' prefix)
     * @return The command output as a string
     * @throws Exception if the git command fails
     */
    private String execGitCommand(String command) throws Exception {
        try {
            Process process = commandExecutor.executeCommand(config.getLocalRepoPath(), "git " + command);

            // Read the output
            byte[] bytes = process.getInputStream().readAllBytes();
            String output = new String(bytes, StandardCharsets.UTF_8);

            // Wait for the process to complete and check exit code
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                byte[] errorBytes = process.getErrorStream().readAllBytes();
                String errorOutput = new String(errorBytes, StandardCharsets.UTF_8);
                throw new Exception("Git command failed with exit code " + exitCode + ": " + errorOutput);
            }

            return output;
        } catch (IOException | InterruptedException e) {
            throw new Exception("Git command failed: git " + command + " - " + e.getMessage());
        }
    }
}