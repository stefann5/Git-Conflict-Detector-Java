package com.github.gitconflictdetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.json.JSONObject;

/**
 * Command line interface for GitConflictDetector
 */
public class GitConflictDetectorCli {

    public static void main(String[] args) {
        try {
            CliArgs cliArgs = parseArgs(args);

            if (cliArgs.isHelp()) {
                printHelp();
                return;
            }

            validateArgs(cliArgs);

            // Get GitHub token from file if provided
            String accessToken = cliArgs.getToken();
            if (cliArgs.getTokenFile() != null) {
                try {
                    accessToken = Files.readString(Paths.get(cliArgs.getTokenFile())).trim();
                } catch (IOException e) {
                    System.err.println("Error reading token file: " + e.getMessage());
                    System.exit(1);
                }
            }

            // Create detector instance
            GitConflictDetectorConfig config = new GitConflictDetectorConfig.Builder()
                    .owner(cliArgs.getOwner())
                    .repo(cliArgs.getRepo())
                    .accessToken(accessToken)
                    .localRepoPath(cliArgs.getPath())
                    .branchA(cliArgs.getBranchA())
                    .branchB(cliArgs.getBranchB())
                    .build();

            GitConflictDetector detector = new GitConflictDetector(config);

            // Find potential conflicts
            CompletableFuture<ConflictDetectionResult> futureResult = detector.findPotentialConflicts();
            ConflictDetectionResult result = futureResult.join();

            if (result.hasError()) {
                System.err.println("Error: " + result.getError());
                System.exit(1);
            }

            // Format output
            String output;
            if ("json".equals(cliArgs.getOutputFormat())) {
                output = formatJsonOutput(result);
            } else {
                output = formatTextOutput(result);
            }

            // Write or print output
            if (cliArgs.getOutputFile() != null) {
                Files.writeString(Paths.get(cliArgs.getOutputFile()), output);
                System.out.println("Output written to " + cliArgs.getOutputFile());
            } else {
                System.out.println(output);
            }

        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static CliArgs parseArgs(String[] args) {
        CliArgs cliArgs = new CliArgs();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            if ("--help".equals(arg) || "-h".equals(arg)) {
                cliArgs.setHelp(true);
                return cliArgs;
            } else if ("--owner".equals(arg) && i + 1 < args.length) {
                cliArgs.setOwner(args[++i]);
            } else if ("--repo".equals(arg) && i + 1 < args.length) {
                cliArgs.setRepo(args[++i]);
            } else if ("--token".equals(arg) && i + 1 < args.length) {
                cliArgs.setToken(args[++i]);
            } else if ("--token-file".equals(arg) && i + 1 < args.length) {
                cliArgs.setTokenFile(args[++i]);
            } else if ("--path".equals(arg) && i + 1 < args.length) {
                cliArgs.setPath(args[++i]);
            } else if ("--branch-a".equals(arg) && i + 1 < args.length) {
                cliArgs.setBranchA(args[++i]);
            } else if ("--branch-b".equals(arg) && i + 1 < args.length) {
                cliArgs.setBranchB(args[++i]);
            } else if ("--output".equals(arg) && i + 1 < args.length) {
                cliArgs.setOutputFormat(args[++i]);
            } else if ("--output-file".equals(arg) && i + 1 < args.length) {
                cliArgs.setOutputFile(args[++i]);
            }
        }

        return cliArgs;
    }

    private static void validateArgs(CliArgs args) {
        if (args.getOwner() == null) {
            System.err.println("Error: --owner is required");
            printHelp();
            System.exit(1);
        }

        if (args.getRepo() == null) {
            System.err.println("Error: --repo is required");
            printHelp();
            System.exit(1);
        }

        if (args.getToken() == null && args.getTokenFile() == null) {
            System.err.println("Error: Either --token or --token-file must be provided");
            printHelp();
            System.exit(1);
        }

        if (args.getBranchA() == null) {
            System.err.println("Error: --branch-a is required");
            printHelp();
            System.exit(1);
        }

        if (args.getBranchB() == null) {
            System.err.println("Error: --branch-b is required");
            printHelp();
            System.exit(1);
        }

        String outputFormat = args.getOutputFormat();
        if (outputFormat != null && !outputFormat.equals("json") && !outputFormat.equals("text")) {
            System.err.println("Error: --output must be either 'json' or 'text'");
            printHelp();
            System.exit(1);
        }
    }

    private static void printHelp() {
        System.out.println("Usage: git-conflict-detector [options]");
        System.out.println("Options:");
        System.out.println("  --owner <name>        GitHub repository owner (required)");
        System.out.println("  --repo <name>         GitHub repository name (required)");
        System.out.println("  --token <token>       GitHub Personal Access Token");
        System.out.println("  --token-file <path>   Path to file containing GitHub Personal Access Token");
        System.out.println("  --path <path>         Path to local Git repository (defaults to current directory)");
        System.out.println("  --branch-a <name>     Remote branch name (required)");
        System.out.println("  --branch-b <name>     Local branch name (required)");
        System.out.println("  --output <format>     Output format: 'json' or 'text' (defaults to 'text')");
        System.out.println("  --output-file <path>  Path to output file (if not specified, will print to stdout)");
        System.out.println("  --help, -h            Show this help message");
    }

    private static String formatJsonOutput(ConflictDetectionResult result) {
        JSONObject json = new JSONObject();
        json.put("mergeBaseCommit", result.getMergeBaseCommit());
        json.put("potentialConflicts", result.getPotentialConflicts());
        return json.toString(2);
    }

    private static String formatTextOutput(ConflictDetectionResult result) {
        StringBuilder sb = new StringBuilder();

        sb.append("Merge base commit: ").append(result.getMergeBaseCommit()).append("\n");

        List<String> conflicts = result.getPotentialConflicts();
        sb.append("Found ").append(conflicts.size()).append(" potential conflicts:\n");

        if (conflicts.isEmpty()) {
            sb.append("No potential conflicts found.\n");
        } else {
            for (String file : conflicts) {
                sb.append("- ").append(file).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Inner class to hold command-line arguments
     */
    private static class CliArgs {
        private String owner;
        private String repo;
        private String token;
        private String tokenFile;
        private String path = System.getProperty("user.dir"); // Current directory by default
        private String branchA;
        private String branchB;
        private String outputFormat = "text"; // Default to text
        private String outputFile;
        private boolean help;

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public String getRepo() {
            return repo;
        }

        public void setRepo(String repo) {
            this.repo = repo;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getTokenFile() {
            return tokenFile;
        }

        public void setTokenFile(String tokenFile) {
            this.tokenFile = tokenFile;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getBranchA() {
            return branchA;
        }

        public void setBranchA(String branchA) {
            this.branchA = branchA;
        }

        public String getBranchB() {
            return branchB;
        }

        public void setBranchB(String branchB) {
            this.branchB = branchB;
        }

        public String getOutputFormat() {
            return outputFormat;
        }

        public void setOutputFormat(String outputFormat) {
            this.outputFormat = outputFormat;
        }

        public String getOutputFile() {
            return outputFile;
        }

        public void setOutputFile(String outputFile) {
            this.outputFile = outputFile;
        }

        public boolean isHelp() {
            return help;
        }

        public void setHelp(boolean help) {
            this.help = help;
        }
    }
}
