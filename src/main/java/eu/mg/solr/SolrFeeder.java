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
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Simple application which reads the commit logs from a git repository and posts them
 * to solr.
 * <p>
 * Special attention is being given to the git merge operations to distinguish whether
 * the files were added/modified during this type of operations.
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
                Date commitDate = rev.getCommitterIdent().getWhen();
                Calendar commitDateCalendar = Calendar.getInstance();
                commitDateCalendar.setTime(commitDate);
                document.addField("commit_date", commitDate);
                LocalDate commitLocalDate = Instant.ofEpochMilli(commitDate.getTime()).atOffset(ZoneOffset.UTC)
                        .toLocalDate();
                document.addField("commit_day", Date.from(commitLocalDate.atStartOfDay(ZoneId.of("UTC")).toInstant()));
                document.addField("commit_month", Date.from(commitLocalDate.atStartOfDay(ZoneId.of("UTC"))
                        .withDayOfMonth(1).toInstant()));
                document.addField("commit_year", commitDateCalendar.get(Calendar.YEAR));

                document.addField("commiter_name", rev.getCommitterIdent().getName());
                document.addField("commiter_email", rev.getCommitterIdent().getEmailAddress());
                document.addField("author_name", rev.getAuthorIdent().getName());
                document.addField("author_email", rev.getAuthorIdent().getEmailAddress());

                List<String> parentCommitIds = Arrays.stream(rev.getParents())
                        .map(revCommit -> revCommit.getId().getName())
                        .collect(Collectors.toList());

                document.addField("parents_ids", parentCommitIds);
                document.addField("parents_count", parentCommitIds.size());

                Set<String> addedFiles = new LinkedHashSet<>();
                Set<String> modifiedFiles = new LinkedHashSet<>();
                Set<String> deletedFiles = new LinkedHashSet<>();
                Set<String> renamedFiles = new LinkedHashSet<>();
                Set<String> copiedFiles = new LinkedHashSet<>();


                if (rev.getParentCount() == 0) {
                    // first commit
                    List<DiffEntry> diffs = df.scan(null, rev.getTree());
                    diffs.forEach(diff -> extractFiles(diff, addedFiles, modifiedFiles, renamedFiles, copiedFiles,
                            deletedFiles));
                } else if (rev.getParentCount() == 1) {
                    RevCommit parent = rev.getParent(0);
                    List<DiffEntry> diffs = df.scan(parent.getTree(), rev.getTree());
                    diffs.forEach(diff -> extractFiles(diff, addedFiles, modifiedFiles, renamedFiles,
                            copiedFiles, deletedFiles));
                } else {
                    // merge operations
                    List<List<DiffEntry>> revisionsDiffs = new ArrayList<>();
                    for (RevCommit parent : rev.getParents()) {
                        List<DiffEntry> diffs = df.scan(parent.getTree(), rev.getTree());
                        revisionsDiffs.add(new ArrayList<>(diffs));
                    }
                    for (int i = 0; i < revisionsDiffs.size(); i++) {
                        List<DiffEntry> revisionDiffs = revisionsDiffs.get(i);

                        for (Iterator<DiffEntry> revisionDiffsIterator = revisionDiffs.iterator();
                             revisionDiffsIterator.hasNext(); ) {
                            DiffEntry revision1Diff = revisionDiffsIterator.next();

                            DiffEntry.ChangeType change1Type = revision1Diff.getChangeType();
                            if (change1Type == DiffEntry.ChangeType.ADD
                                    || change1Type == DiffEntry.ChangeType.DELETE
                                    || change1Type == DiffEntry.ChangeType.RENAME
                                    || change1Type == DiffEntry.ChangeType.COPY) {
                                int sameChangeCount = 0;
                                for (int j = 0; j < revisionsDiffs.size(); j++) {
                                    if (j == i) continue;

                                    boolean found = revisionsDiffs.get(j).stream()
                                            .anyMatch(diffEntry -> diffEntry.getChangeType() == change1Type
                                                    && diffEntry.getNewPath().equals(revision1Diff.getNewPath()));
                                    if (found) {
                                        sameChangeCount++;
                                    } else {
                                        break;
                                    }
                                }
                                // check to see whether the file was added/ removed during the merge operation
                                if (sameChangeCount != rev.getParentCount()) {
                                    revisionDiffsIterator.remove();
                                }
                            } else if (change1Type == DiffEntry.ChangeType.MODIFY) {
                                // only merge conflicts are being taken into account or situations where
                                // changes come from all branches
                                int changeCount = 0;
                                for (int j = 0; j < revisionsDiffs.size(); j++) {
                                    if (j == i) continue;

                                    boolean changeFound = revisionsDiffs.get(j).stream()
                                            .anyMatch(revision2Diff -> revision2Diff.getChangeType() == change1Type &&
                                                    revision2Diff.getOldPath().equals(revision1Diff.getOldPath()) &&
                                                    !revision2Diff.getOldId().equals(revision1Diff.getOldId()));
                                    if (changeFound) {
                                        changeCount++;
                                    } else {
                                        break;
                                    }
                                }

                                if (changeCount != rev.getParentCount() - 1) {
                                    revisionDiffsIterator.remove();
                                }
                            }
                        }
                    }
                }


                document.addField("added_files", addedFiles);
                document.addField("modified_files", modifiedFiles);
                document.addField("deleted_files", deletedFiles);
                document.addField("renamed_files", renamedFiles);
                document.addField("copied_files", copiedFiles);

                UpdateResponse response = solr.add(document);
                count++;
                if (count % 100 == 0) {
                    solr.commit();
                    LOGGER.info("Imported {} commits", count);
                }
            }
        }
    }

    private static void extractFiles(DiffEntry diff, Set<String> addedFiles, Set<String> modifiedFiles,
                                     Set<String> renamedFiles, Set<String> copiedFiles, Set<String> deletedFiles) {
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
}
