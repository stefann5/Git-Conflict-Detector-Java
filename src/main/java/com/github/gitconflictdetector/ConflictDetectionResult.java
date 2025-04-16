package com.github.gitconflictdetector;

import java.util.List;

/**
 * Result of the conflict detection
 */
public class ConflictDetectionResult {
    private final List<String> potentialConflicts;
    private final String mergeBaseCommit;
    private final String error;

    /**
     * Creates a new successful result
     *
     * @param potentialConflicts List of files with potential conflicts
     * @param mergeBaseCommit The merge base commit SHA
     */
    public ConflictDetectionResult(List<String> potentialConflicts, String mergeBaseCommit) {
        this.potentialConflicts = potentialConflicts;
        this.mergeBaseCommit = mergeBaseCommit;
        this.error = null;
    }

    /**
     * Creates a new result with an error
     *
     * @param potentialConflicts List of files with potential conflicts (usually empty in error cases)
     * @param mergeBaseCommit The merge base commit SHA (usually empty in error cases)
     * @param error The error message
     */
    public ConflictDetectionResult(List<String> potentialConflicts, String mergeBaseCommit, String error) {
        this.potentialConflicts = potentialConflicts;
        this.mergeBaseCommit = mergeBaseCommit;
        this.error = error;
    }

    public List<String> getPotentialConflicts() {
        return potentialConflicts;
    }

    public String getMergeBaseCommit() {
        return mergeBaseCommit;
    }

    public String getError() {
        return error;
    }

    public boolean hasError() {
        return error != null && !error.isEmpty();
    }
}
