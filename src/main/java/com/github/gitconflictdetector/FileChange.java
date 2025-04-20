package com.github.gitconflictdetector;

/**
 * A file change representation
 */
public class FileChange {
    private final String filename;
    private final String status;
    private final String sha;

    /**
     * Creates a new file change
     *
     * @param filename The path to the file
     * @param status The status of the change (e.g., "M" for modified)
     * @param sha The file's SHA hash
     */
    public FileChange(String filename, String status, String sha) {
        this.filename = filename;
        this.status = status;
        this.sha = sha;
    }

    /**
     * Creates a new file change without SHA information
     * This constructor is kept for backward compatibility
     *
     * @param filename The path to the file
     * @param status The status of the change (e.g., "M" for modified)
     */
    public FileChange(String filename, String status) {
        this(filename, status, null);
    }

    public String getFilename() {
        return filename;
    }

    public String getStatus() {
        return status;
    }

    public String getSha() {
        return sha;
    }

    @Override
    public String toString() {
        return status + " " + filename + (sha != null ? " (" + sha + ")" : "");
    }
}