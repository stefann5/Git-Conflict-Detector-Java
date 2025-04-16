package com.github.gitconflictdetector;

/**
 * A file change representation
 */
public class FileChange {
    private final String filename;
    private final String status;

    /**
     * Creates a new file change
     *
     * @param filename The path to the file
     * @param status The status of the change (e.g., "M" for modified)
     */
    public FileChange(String filename, String status) {
        this.filename = filename;
        this.status = status;
    }

    public String getFilename() {
        return filename;
    }

    public String getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return status + " " + filename;
    }
}
