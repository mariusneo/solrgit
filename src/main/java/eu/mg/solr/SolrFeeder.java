package eu.mg.solr;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Simple application which reads the commit logs from a git repository and posts them
 * to solr.
 * <p>
 * The application should be called in the following format:
 * <pre>
 *     solrfeeder [git-repo-path] [solr-core-url]
 * </pre>
 */
public class SolrFeeder {
    private static final String TICKET_REGEX = "^([A-Z]+-\\d+).*";
    private static final Pattern TICKET_REGEX_PATTERN = Pattern.compile(TICKET_REGEX);
    private static Logger LOGGER = LoggerFactory.getLogger(SolrFeeder.class);

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Syntax: solrfeeder git-repo-path solr-core-url");
            System.exit(1);
        }

        String gitDirPath = args[0];
        String solrUrl = args[1];

        File repoDir = new File(gitDirPath);

        // now open the resulting repository with a FileRepositoryBuilder
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        try (Repository repository = builder.setGitDir(repoDir)
                .readEnvironment() // scan environment GIT_* variables
                .findGitDir() // scan up the file system tree
                .build()) {
            LOGGER.info("Using repository: " + repository.getDirectory());

            Git git = new Git(repository);
            RevWalk rw = new RevWalk(repository);

            DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
            df.setRepository(repository);
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);

            Iterable<RevCommit> logs = git.log().all().call();
            int count = 0;
            SolrClient solr = new HttpSolrClient.Builder(solrUrl).build();
            for (RevCommit rev : logs) {
                SolrInputDocument document = new SolrInputDocument();
                document.addField("id", rev.getId().getName());
                String fullMessage = rev.getFullMessage();
                if (!StringUtils.isEmpty(fullMessage)) {
                    Matcher regexMatcher = TICKET_REGEX_PATTERN.matcher(fullMessage);
                    if (regexMatcher.find()) {
                        String ticket = regexMatcher.group(1);
                        document.addField("ticket", ticket);
                    }

                }
                document.addField("message", fullMessage);
                document.addField("commit_date", rev.getCommitterIdent().getWhen());
                document.addField("commiter_name", rev.getCommitterIdent().getName());
                document.addField("commiter_email", rev.getCommitterIdent().getEmailAddress());
                document.addField("author_name", rev.getAuthorIdent().getName());
                document.addField("author_email", rev.getAuthorIdent().getEmailAddress());

                List<String> parentCommitIds = Arrays.stream(rev.getParents())
                        .map(revCommit -> revCommit.getId().getName())
                        .collect(Collectors.toList());

                document.addField("parents_ids", parentCommitIds);

                // FIXME currently it is assumed that there is also a parent commit,
                // but in git there can be multiple parents
                if (rev.getParentCount() > 0) {
                    RevCommit parent = rw.parseCommit(rev.getParent(0).getId());

                    List<DiffEntry> diffs = df.scan(parent.getTree(), rev.getTree());
                    List<String> addedFiles = new ArrayList<>();
                    List<String> modifiedFiles = new ArrayList<>();
                    List<String> deletedFiles = new ArrayList<>();
                    List<String> renamedFiles = new ArrayList<>();
                    List<String> copiedFiles = new ArrayList<>();
                    for (DiffEntry diff : diffs) {
                        switch (diff.getChangeType()) {
                            case DELETE:
                                deletedFiles.add(diff.getOldPath());
                                break;
                            case ADD:
                                addedFiles.add(diff.getNewPath());
                                break;
                            case MODIFY:
                                modifiedFiles.add(diff.getNewPath());
                                break;
                            case RENAME:
                                renamedFiles.add(diff.getNewPath());
                                break;
                            case COPY:
                                copiedFiles.add(diff.getNewPath());
                                break;
                        }
                    }
                    document.addField("added_files", addedFiles);
                    document.addField("modified_files", modifiedFiles);
                    document.addField("deleted_files", deletedFiles);
                    document.addField("renamed_files", renamedFiles);
                    document.addField("copied_files", copiedFiles);
                }
                UpdateResponse response = solr.add(document);
                count++;
                if (count % 100 == 0) {
                    solr.commit();
                    System.out.println(new Date());
                }
            }

        }
    }
}
