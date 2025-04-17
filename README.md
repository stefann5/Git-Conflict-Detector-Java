# Git Conflict Detector

A Java library and CLI tool that identifies potential merge conflicts between a local branch and a remote branch without fetching the remote branch.

## Overview

Git Conflict Detector helps developers identify potential merge conflicts before they happen. It finds files that have been modified in both a local branch and a remote branch since their common ancestor (merge base commit). By detecting these conflicts early, you can address them proactively and avoid surprises during actual merges.

## Features

- Identifies files modified in both local and remote branches since their common merge base
- Uses GitHub API to get remote branch changes without fetching the branch
- Provides both a library API and a CLI interface
- Supports JSON and text output formats
- Comprehensive error handling and validation

## Installation

### Prerequisites

- Java 11 or higher
- Git installed and available on your PATH
- A GitHub personal access token with repo permissions

### Building from Source

```bash
git clone https://github.com/yourusername/git-conflict-detector.git
cd git-conflict-detector
./mvnw clean package
```

## Usage

### Command Line Interface

```bash
java -jar git-conflict-detector.jar --owner <owner> --repo <repo> --branch-a <remote-branch> --branch-b <local-branch> --token <github-token>
```

#### Options

```
--owner <name>        GitHub repository owner (required)
--repo <name>         GitHub repository name (required)
--token <token>       GitHub Personal Access Token
--token-file <path>   Path to file containing GitHub Personal Access Token
--path <path>         Path to local Git repository (defaults to current directory)
--branch-a <name>     Remote branch name (required)
--branch-b <name>     Local branch name (required)
--output <format>     Output format: 'json' or 'text' (defaults to 'text')
--output-file <path>  Path to output file (if not specified, will print to stdout)
--help, -h            Show this help message
```

#### Example

```bash
java -jar git-conflict-detector.jar --owner octocat --repo hello-world --branch-a main --branch-b feature-branch --token ghp_123456789abcdef --output json
```

### Java Library Usage

```java
import com.github.gitconflictdetector.*;

// Create configuration
GitConflictDetectorConfig config = new GitConflictDetectorConfig.Builder()
    .owner("octocat")
    .repo("hello-world")
    .accessToken("ghp_123456789abcdef")
    .localRepoPath("/path/to/local/repo")
    .branchA("main")  // Remote branch
    .branchB("feature-branch")  // Local branch
    .build();

// Create detector
GitConflictDetector detector = new GitConflictDetector(config);

// Find potential conflicts
detector.findPotentialConflicts()
    .thenAccept(result -> {
        if (result.hasError()) {
            System.err.println("Error: " + result.getError());
            return;
        }
        
        System.out.println("Merge base commit: " + result.getMergeBaseCommit());
        System.out.println("Potential conflicts:");
        for (String file : result.getPotentialConflicts()) {
            System.out.println("- " + file);
        }
    });
```

## How It Works

1. Validates the provided configuration and local repository
2. Finds the common ancestor (merge base) between the two branches
3. Gets the list of files changed in the local branch compared to the merge base
4. Uses the GitHub API to get files changed in the remote branch compared to the merge base
5. Identifies files that were modified in both branches (potential conflicts)

## Key Components

- `GitConflictDetector`: Main class that performs the conflict detection logic
- `GitConflictDetectorConfig`: Configuration class using builder pattern
- `ConflictDetectionResult`: Result class containing potential conflicts and merge base information
- `CommandExecutor`: Interface for executing Git commands (allows for testing)
- `FileChange`: Represents a changed file with filename and status

## Error Handling

The library implements thorough error handling:

- Configuration validation
- Repository validation
- Git command execution error handling
- GitHub API error handling
- Thread safety with CompletableFuture


## License

MIT License
