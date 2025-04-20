package com.github.gitconflictdetector;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
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

                // Get remote branch head commit
                return getRemoteBranchHead(config.getBranchA())
                        .thenCompose(remoteBranchHead -> {
                            // Get files changed in the remote branch between merge base and head
                            // by processing all commits individually
                            return getRemoteBranchChangesByCommits(mergeBaseCommit, remoteBranchHead)
                                    .thenApply(remoteChanges -> {
                                        // Find files modified in both branches (potential conflict candidates)
                                        List<String> potentialConflicts = findCommonChanges(localChanges, remoteChanges);
                                        return new ConflictDetectionResult(potentialConflicts, mergeBaseCommit);
                                    });
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
     * @return List of file changes with filename, status, and SHA
     * @throws Exception if git command fails
     */
    protected List<FileChange> getLocalChanges(String mergeBaseCommit) throws Exception {
        String branchB = config.getBranchB();
        List<FileChange> changes = new ArrayList<>();

        try {
            // Get a list of files changed between merge base and branchB
            String diffOutput = execGitCommand("diff --name-only " + mergeBaseCommit + " " + branchB);

            if (diffOutput.trim().isEmpty()) {
                return changes;
            }

            // Create a set of all changed filenames for quick lookup
            Set<String> changedFiles = new HashSet<>(Arrays.asList(diffOutput.trim().split("\\n")));

            // Get all file SHAs in branchB in one command
            String lsTreeOutput = execGitCommand("ls-tree -r " + branchB);

            // Parse the output of ls-tree command and filter for just the changed files
            for (String line : lsTreeOutput.trim().split("\\n")) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                // Format: <mode> <type> <object> <file>
                // Example: 100644 blob a4b3ef123456... path/to/filename.java
                String[] parts = line.trim().split("\\s+", 4);
                if (parts.length >= 4) {
                    String filename = parts[3];
                    String sha = parts[2];

                    // Only include files that are in our diff list
                    if (changedFiles.contains(filename)) {
                        changes.add(new FileChange(filename, "M", sha)); // Assuming modified status
                        changedFiles.remove(filename); // Remove to track what's left
                    }
                }
            }

            // Any remaining files in changedFiles set were not found in ls-tree output
            // These are likely deleted files
            for (String filename : changedFiles) {
                changes.add(new FileChange(filename, "D", null));
            }

            return changes;
        } catch (Exception e) {
            throw new Exception("Failed to get local changes: " + e.getMessage());
        }
    }

    /**
     * Gets the head commit SHA for the specified remote branch using GitHub API
     *
     * @param branch The name of the remote branch
     * @return CompletableFuture resolving to the branch head commit SHA
     */
    protected CompletableFuture<String> getRemoteBranchHead(String branch) {
        String owner = config.getOwner();
        String repo = config.getRepo();
        String accessToken = config.getAccessToken();

        // Get branch reference using GitHub API
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/branches/" + branch;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
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
                .thenApply(this::parseBranchApiResponse)
                .exceptionally(e -> {
                    throw new RuntimeException("Failed to get branch head: " + e.getMessage());
                });
    }

    /**
     * Parses the branch API response to extract the head commit SHA
     *
     * @param jsonResponse The GitHub API response as JSON string
     * @return The branch head commit SHA
     */
    protected String parseBranchApiResponse(String jsonResponse) {
        try {
            JSONObject json = new JSONObject(jsonResponse);
            return json.getJSONObject("commit").getString("sha");
        } catch (JSONException e) {
            throw new RuntimeException("Failed to parse branch API response: " + e.getMessage());
        }
    }

    /**
     * Gets all files changed between base commit and head commit by processing each individual commit
     * This method always uses the approach of:
     * 1. Getting all commits between base and head
     * 2. Processing each commit individually to collect all file changes
     * 3. Maintaining a set of unique files to avoid duplicates
     *
     * @param baseCommit The base commit SHA
     * @param headCommit The head commit SHA to compare with
     * @return CompletableFuture resolving to list of file changes
     */
    protected CompletableFuture<List<FileChange>> getRemoteBranchChangesByCommits(String baseCommit, String headCommit) {
        // Get all commits between base and head
        return getAllCommitsBetween(baseCommit, headCommit)
                .thenCompose(commitShas -> {
                    // Get files for each commit
                    List<CompletableFuture<List<FileChange>>> futures = new ArrayList<>();

                    for (String commitSha : commitShas) {
                        futures.add(getCommitChanges(commitSha));
                    }

                    return CompletableFuture.allOf(
                            futures.toArray(new CompletableFuture[0])
                    ).thenApply(v -> {
                        // Combine changes from all commits, removing duplicates
                        Set<String> uniqueFiles = new HashSet<>();
                        List<FileChange> allChanges = new ArrayList<>();

                        for (CompletableFuture<List<FileChange>> future : futures) {
                            List<FileChange> commitChanges = future.join();
                            for (FileChange change : commitChanges) {
                                if (!uniqueFiles.contains(change.getFilename())) {
                                    uniqueFiles.add(change.getFilename());
                                    allChanges.add(change);
                                }
                            }
                        }

                        return allChanges;
                    });
                })
                .exceptionally(e -> {
                    throw new RuntimeException("Failed to get branch changes by commits: " + e.getMessage());
                });
    }

    /**
     * Gets all commits between base and head commits using GitHub's Commits API
     * with pagination support to handle large histories
     *
     * @param baseCommit The base commit SHA
     * @param headCommit The head commit SHA
     * @return CompletableFuture resolving to list of commit SHAs
     */
    protected CompletableFuture<List<String>> getAllCommitsBetween(String baseCommit, String headCommit) {
        List<String> allCommits = new ArrayList<>();

        // Start with page 1
        return getCommitsPage(headCommit, 1, allCommits, baseCommit);
    }

    /**
     * Recursively fetches all pages of commits
     *
     * @param headCommit The head commit SHA
     * @param page Current page number
     * @param collectedCommits List to collect commit SHAs
     * @param baseCommit The base commit SHA to stop at
     * @return CompletableFuture resolving to list of all commit SHAs
     */
    private CompletableFuture<List<String>> getCommitsPage(
            String headCommit,
            int page,
            List<String> collectedCommits,
            String baseCommit) {

        String owner = config.getOwner();
        String repo = config.getRepo();
        String accessToken = config.getAccessToken();

        // Get commits using GitHub API
        String url = "https://api.github.com/repos/" + owner + "/" + repo +
                "/commits?sha=" + headCommit + "&per_page=100&page=" + page;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
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
                .thenApply(jsonResponse -> {
                    List<String> pageShas = parseCommitsApiResponse(jsonResponse);

                    // Check if base commit is in this page
                    boolean foundBase = false;
                    List<String> filteredShas = new ArrayList<>();

                    for (String sha : pageShas) {
                        if (sha.equals(baseCommit)) {
                            foundBase = true;
                            break;
                        }
                        filteredShas.add(sha);
                    }

                    // Add the filtered commits to our collection
                    collectedCommits.addAll(filteredShas);

                    // If we found the base commit or no commits in this page, we're done
                    if (foundBase || pageShas.isEmpty()) {
                        return collectedCommits;
                    }

                    return null; // Continue to next page
                })
                .thenCompose(result -> {
                    if (result != null) {
                        // We're done
                        return CompletableFuture.completedFuture(result);
                    } else {
                        // Get next page
                        return getCommitsPage(headCommit, page + 1, collectedCommits, baseCommit);
                    }
                })
                .exceptionally(e -> {
                    throw new RuntimeException("Failed to get commits page: " + e.getMessage());
                });
    }

    /**
     * Parses the commits API response to extract commit SHAs
     *
     * @param jsonResponse The GitHub API response as JSON string
     * @return List of commit SHAs
     */
    protected List<String> parseCommitsApiResponse(String jsonResponse) {
        List<String> commitShas = new ArrayList<>();

        try {
            JSONArray commits = new JSONArray(jsonResponse);

            for (int i = 0; i < commits.length(); i++) {
                JSONObject commit = commits.getJSONObject(i);
                String sha = commit.getString("sha");
                commitShas.add(sha);
            }
        } catch (JSONException e) {
            throw new RuntimeException("Failed to parse commits API response: " + e.getMessage());
        }

        return commitShas;
    }

    /**
     * Gets files changed in a specific commit using GitHub API
     *
     * @param commitSha The commit SHA to retrieve
     * @return CompletableFuture resolving to list of file changes
     */
    protected CompletableFuture<List<FileChange>> getCommitChanges(String commitSha) {
        String owner = config.getOwner();
        String repo = config.getRepo();
        String accessToken = config.getAccessToken();

        // Get commit using GitHub API
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/commits/" + commitSha;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
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
                .thenApply(this::parseCommitFilesApiResponse)
                .thenCompose(changes -> {
                    // Check if commit has pagination (more than 300 files)
                    if (changes.size() >= 300) {
                        return getCommitFilesPaginated(commitSha, 2, changes);
                    }
                    return CompletableFuture.completedFuture(changes);
                })
                .exceptionally(e -> {
                    throw new RuntimeException("Failed to get commit details: " + e.getMessage());
                });
    }

    /**
     * Gets additional pages of files for a commit with pagination
     *
     * @param commitSha The commit SHA
     * @param page The page number to fetch
     * @param collectedChanges List of already collected changes
     * @return CompletableFuture resolving to complete list of file changes
     */
    private CompletableFuture<List<FileChange>> getCommitFilesPaginated(
            String commitSha,
            int page,
            List<FileChange> collectedChanges) {

        String owner = config.getOwner();
        String repo = config.getRepo();
        String accessToken = config.getAccessToken();

        // Get commit using GitHub API with pagination
        String url = "https://api.github.com/repos/" + owner + "/" + repo +
                "/commits/" + commitSha + "?page=" + page + "&per_page=300";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
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
                .thenApply(jsonResponse -> {
                    List<FileChange> pageChanges = parseCommitFilesApiResponse(jsonResponse);

                    // Add the page changes to our collection
                    collectedChanges.addAll(pageChanges);

                    return pageChanges.size();
                })
                .thenCompose(pageSize -> {
                    if (pageSize < 300 || page >= 10) {  // GitHub limits to 3000 files (10 pages of 300)
                        // We're done
                        return CompletableFuture.completedFuture(collectedChanges);
                    } else {
                        // Get next page
                        return getCommitFilesPaginated(commitSha, page + 1, collectedChanges);
                    }
                })
                .exceptionally(e -> {
                    throw new RuntimeException("Failed to get paginated commit files: " + e.getMessage());
                });
    }

    /**
     * Parses the commit API response to extract file changes with SHA information
     *
     * @param jsonResponse The GitHub API response as JSON string
     * @return List of file changes with filename, status, and SHA
     */
    protected List<FileChange> parseCommitFilesApiResponse(String jsonResponse) {
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

                // Extract SHA if available
                String sha = null;
                if (file.has("sha")) {
                    sha = file.getString("sha");
                }

                changes.add(new FileChange(filename, status, sha));
            }
        } catch (JSONException e) {
            throw new RuntimeException("Failed to parse commit API response: " + e.getMessage());
        }

        return changes;
    }

    /**
     * Finds files that were changed in both local and remote branches,
     * excluding files that have the same SHA hash (meaning the changes are identical)
     *
     * @param localChanges List of local file changes
     * @param remoteChanges List of remote file changes
     * @return List of filenames that were changed in both branches with different content
     */
    protected List<String> findCommonChanges(List<FileChange> localChanges, List<FileChange> remoteChanges) {
        // Create a map of local files with their SHA values
        Map<String, String> localFileToSha = new HashMap<>();

        for (FileChange change : localChanges) {
            localFileToSha.put(change.getFilename(), change.getSha());
        }

        List<String> potentialConflicts = new ArrayList<>();

        for (FileChange remoteChange : remoteChanges) {
            String filename = remoteChange.getFilename();
            String remoteSha = remoteChange.getSha();

            // If the file exists in both branches
            if (localFileToSha.containsKey(filename)) {
                String localSha = localFileToSha.get(filename);

                // Add to conflicts only if SHAs are different or if SHA information is missing
                if (localSha == null || !localSha.equals(remoteSha)) {
                    potentialConflicts.add(filename);
                }
                // If SHAs are the same, it means the same changes were made in both branches,
                // so no conflict will occur for this file
            }
        }

        return potentialConflicts;
    }

    /**
     * Parses git diff output into FileChange objects
     *
     * @param output The git diff command output with --full-index option
     * @return List of FileChange objects with filename, status, and SHA
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

            // With --full-index, the output format is:
            // STATUS SHA1..SHA2 FILENAME
            // Or for simple changes:
            // STATUS FILENAME
            String[] parts = line.trim().split("\\t", -1);
            if (parts.length < 2) {
                continue;
            }

            String status = parts[0].trim();
            String filename = parts[parts.length - 1];
            String sha = null;

            // If we have SHA information (should be the case with --full-index)
            if (parts.length >= 3 && parts[1].contains("..")) {
                String[] shaInfo = parts[1].split("\\.\\.");
                if (shaInfo.length == 2) {
                    // Use the destination SHA (what the file changed to)
                    sha = shaInfo[1];
                }
            }

            changes.add(new FileChange(filename, status, sha));
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