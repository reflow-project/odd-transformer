package io.piveau.transforming.repositories;

import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GitRepository {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final String uri;
    private final String branch;

    private final Path localPath;

    private final String username;
    private final String token;

    public static GitRepository open(String uri, String branch) {
        return open(uri, null, null, branch);
    }

    public static GitRepository open(String uri, String username, String token, String branch) {
        if (uri != null) {
            if (branch != null && !branch.isEmpty()) {
                return new GitRepository(uri, username , token, branch);
            } else {
                return new GitRepository(uri, username, token, "master");
            }
        } else {
            return null;
        }
    }

    private GitRepository(String uri, String username, String token, String branch) {
        this.uri = uri;
        this.branch = branch;
        this.username = username;
        this.token = token;

        localPath = Paths.get("repositories").resolve(uri.replaceAll("[^\\w\\s]", "")).resolve(branch);
        if (localPath.toFile().exists()) {
            log.debug("pulling remote repo from {}", uri);
            pullRepo();
        } else {
            // Clone branch if not existent on disk
            log.debug("cloning remote repo from {} to {}", uri, localPath);
            cloneRepo();
        }
    }

    public Path resolve(String path) {
        return localPath.resolve(path);
    }

    private void cloneRepo() {
        CloneCommand cloneCommand = Git.cloneRepository()
                .setURI(uri)
                .setBranch(branch)
                .setDirectory(localPath.toFile());

        if (token != null) {
            cloneCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, token));
        }

        try (Git git = cloneCommand.call()) {
            log.trace("clone command successful");
        } catch (GitAPIException e) {
            log.error("calling clone command", e);
        }
    }

    private void pullRepo() {
        try (Repository localRepo = new FileRepository(localPath + File.separator + ".git")) {
            try (Git repo = new Git(localRepo)) {
                PullCommand pullCommand = repo.pull();

                if (token != null) {
                    pullCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, token));
                }

                PullResult pResult = pullCommand.call();
                MergeResult mResult = pResult.getMergeResult();

                if (!mResult.getMergeStatus().isSuccessful()) {
                    log.warn("could not merge repository: {}", mResult);
                }
            }
        } catch (IOException | GitAPIException e) {
            log.error("calling pull command", e);
        }
    }

}
