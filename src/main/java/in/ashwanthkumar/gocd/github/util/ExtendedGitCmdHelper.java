package in.ashwanthkumar.gocd.github.util;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tw.go.plugin.cmd.Console;
import com.tw.go.plugin.cmd.ConsoleResult;
import com.tw.go.plugin.cmd.InMemoryConsumer;
import com.tw.go.plugin.cmd.ProcessOutputStreamConsumer;
import com.tw.go.plugin.git.GitCmdHelper;
import com.tw.go.plugin.git.GitModificationParser;
import com.tw.go.plugin.model.GitConfig;
import com.tw.go.plugin.model.Revision;
import org.apache.commons.exec.CommandLine;

public class ExtendedGitCmdHelper extends GitCmdHelper {

    private static final Pattern GIT_DIFF_TREE_PATTERN = Pattern.compile("^(.{1,3})\\s+(.+)$");

    public ExtendedGitCmdHelper(GitConfig gitConfig, File workingDir) {
        super(gitConfig, workingDir);
    }

    public ExtendedGitCmdHelper(GitConfig gitConfig, File workingDir, ProcessOutputStreamConsumer stdOut,
            ProcessOutputStreamConsumer stdErr) {
        super(gitConfig, workingDir, stdOut, stdErr);
    }

    public void checkoutNewBranch(String branchName) {
        CommandLine gitCheckout = Console.createCommand("checkout", "-B", branchName);
        Console.runOrBomb(gitCheckout, workingDir, stdOut, stdErr);
    }

    public List<Revision> getRevisionsUntilHeadSince(String revision) {
        String[] logArgs = new String[]{
                "log", "--date=iso", "--pretty=medium", "--no-decorate", "--no-color", String.format("%s..", revision)
        };
        return this.gitLog(logArgs);
    }

    private List<Revision> gitLog(String... args) {
        CommandLine gitLog = Console.createCommand(args);
        List<String> gitLogOutput = this.runAndGetOutput(gitLog).stdOut();

        List<Revision> revisions = new GitModificationParser().parse(gitLogOutput);
        for (Revision revision : revisions) {
            addModifiedFiles(revision);
        }

        return revisions;
    }

    private ConsoleResult runAndGetOutput(CommandLine gitCmd) {
        return this.runAndGetOutput(gitCmd, this.workingDir);
    }

    private ConsoleResult runAndGetOutput(CommandLine gitCmd, File workingDir) {
        return this.runAndGetOutput(gitCmd, workingDir, new ProcessOutputStreamConsumer(new InMemoryConsumer()), new ProcessOutputStreamConsumer(new InMemoryConsumer()));
    }

    private ConsoleResult runAndGetOutput(CommandLine gitCmd, File workingDir, ProcessOutputStreamConsumer stdOut, ProcessOutputStreamConsumer stdErr) {
        return Console.runOrBomb(gitCmd, workingDir, stdOut, stdErr);
    }

    private void addModifiedFiles(Revision revision) {
        List<String> diffTreeOutput = diffTree(revision.getRevision()).stdOut();

        for (String resultLine : diffTreeOutput) {
            // First line is the node
            if (resultLine.equals(revision.getRevision())) {
                continue;
            }

            Matcher m = matchResultLine(resultLine);
            if (!m.find()) {
                throw new RuntimeException(String.format("Unable to parse git-diff-tree output line: %s%nFrom output:%n %s", resultLine, String.join(System.lineSeparator(), diffTreeOutput)));
            }
            revision.createModifiedFile(m.group(2), parseGitAction(m.group(1).charAt(0)));
        }
    }

    private ConsoleResult diffTree(String node) {
        CommandLine gitCmd = Console.createCommand("diff-tree", "--name-status", "--root", "-r", "-c", node);
        return runAndGetOutput(gitCmd);
    }

    private Matcher matchResultLine(String resultLine) {
        return GIT_DIFF_TREE_PATTERN.matcher(resultLine);
    }

    private String parseGitAction(char action) {
        switch (action) {
            case 'A':
                return "added";
            case 'M':
                return "modified";
            case 'D':
                return "deleted";
            default:
                return "unknown";
        }
    }
}
