package com.github.gitconflictdetector;

/**
 * Configuration for the GitConflictDetector
 */
public class GitConflictDetectorConfig {
    private final String owner;
    private final String repo;
    private final String accessToken;
    private final String localRepoPath;
    private final String branchA;
    private final String branchB;

    private GitConflictDetectorConfig(Builder builder) {
        this.owner = builder.owner;
        this.repo = builder.repo;
        this.accessToken = builder.accessToken;
        this.localRepoPath = builder.localRepoPath;
        this.branchA = builder.branchA;
        this.branchB = builder.branchB;
    }

    /**
     * Builder pattern for creating GitConflictDetectorConfig instances
     */
    public static class Builder {
        private String owner;
        private String repo;
        private String accessToken;
        private String localRepoPath;
        private String branchA;
        private String branchB;

        public Builder owner(String owner) {
            this.owner = owner;
            return this;
        }

        public Builder repo(String repo) {
            this.repo = repo;
            return this;
        }

        public Builder accessToken(String accessToken) {
            this.accessToken = accessToken;
            return this;
        }

        public Builder localRepoPath(String localRepoPath) {
            this.localRepoPath = localRepoPath;
            return this;
        }

        public Builder branchA(String branchA) {
            this.branchA = branchA;
            return this;
        }

        public Builder branchB(String branchB) {
            this.branchB = branchB;
            return this;
        }

        public GitConflictDetectorConfig build() {
            return new GitConflictDetectorConfig(this);
        }
    }

    public String getOwner() {
        return owner;
    }

    public String getRepo() {
        return repo;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getLocalRepoPath() {
        return localRepoPath;
    }

    public String getBranchA() {
        return branchA;
    }

    public String getBranchB() {
        return branchB;
    }
}
