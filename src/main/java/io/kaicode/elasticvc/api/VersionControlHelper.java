package io.kaicode.elasticvc.api;

import io.kaicode.elasticvc.domain.Branch;
import io.kaicode.elasticvc.domain.Commit;
import io.kaicode.elasticvc.domain.Entity;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.elasticsearch.repository.ElasticsearchCrudRepository;
import org.springframework.data.util.CloseableIterator;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;

import static io.kaicode.elasticvc.api.VersionControlHelper.ContentSelection.CHANGES_AND_DELETIONS_IN_THIS_COMMIT_ONLY;
import static org.elasticsearch.index.query.QueryBuilders.*;

@Service
public class VersionControlHelper {

	@Autowired
	private BranchService branchService;

	@Autowired
	private ElasticsearchOperations elasticsearchTemplate;

	private final Logger logger = LoggerFactory.getLogger(getClass());

	public QueryBuilder getBranchCriteria(String path) {
		return getBranchCriteria(getBranchOrThrow(path));
	}

	public QueryBuilder getBranchCriteria(Branch branch) {
		return getBranchCriteria(branch, branch.getHead(), branch.getVersionsReplaced(), ContentSelection.STANDARD_SELECTION, null);
	}

	public QueryBuilder getBranchCriteriaBeforeOpenCommit(Commit commit) {
		Branch branch = commit.getBranch();
		return getBranchCriteria(branch, branch.getHead(), branch.getVersionsReplaced(), ContentSelection.STANDARD_SELECTION, commit);
	}

	public QueryBuilder getBranchCriteriaIncludingOpenCommit(Commit commit) {
		return getBranchCriteria(commit.getBranch(), commit.getTimepoint(), commit.getEntityVersionsReplaced(), ContentSelection.STANDARD_SELECTION, commit);
	}

	public QueryBuilder getChangesOnBranchCriteria(String path) {
		final Branch branch = getBranchOrThrow(path);
		return getChangesOnBranchCriteria(branch);
	}

	public QueryBuilder getChangesOnBranchCriteria(Branch branch) {
		return getBranchCriteria(branch, branch.getHead(), branch.getVersionsReplaced(), ContentSelection.CHANGES_ON_THIS_BRANCH_ONLY, null);
	}

	public QueryBuilder getBranchCriteriaChangesWithinOpenCommitOnly(Commit commit) {
		return getBranchCriteria(commit.getBranch(), commit.getTimepoint(), commit.getEntityVersionsReplaced(), ContentSelection.CHANGES_IN_THIS_COMMIT_ONLY, commit);
	}

	public QueryBuilder getBranchCriteriaChangesAndDeletionsWithinOpenCommitOnly(Commit commit) {
		return getBranchCriteria(commit.getBranch(), commit.getTimepoint(), commit.getEntityVersionsReplaced(), CHANGES_AND_DELETIONS_IN_THIS_COMMIT_ONLY, commit);
	}

	public QueryBuilder getBranchCriteriaUnpromotedChangesAndDeletions(Branch branch) {
		return getBranchCriteria(branch, null, null, ContentSelection.UNPROMOTED_CHANGES_AND_DELETIONS_ON_THIS_BRANCH, null);
	}

	public QueryBuilder getBranchCriteriaUnpromotedChanges(Branch branch) {
		return getBranchCriteria(branch, null, null, ContentSelection.UNPROMOTED_CHANGES_ON_THIS_BRANCH, null);
	}

	public BoolQueryBuilder getUpdatesOnBranchDuringRangeCriteria(String path, Date start, Date end) {
		final Branch branch = getBranchOrThrow(path);
		return boolQuery()
				.must(termQuery("path", branch.getPath()))
				.must(boolQuery()
						.should(rangeQuery("start").gte(start.getTime()).lte(end.getTime()))
						.should(rangeQuery("end").gte(start.getTime()).lte(end.getTime()))
				);
	}

	private Branch getBranchOrThrow(String path) {
		final Branch branch = branchService.findLatest(path);
		if (branch == null) {
			throw new IllegalArgumentException("Branch '" + path + "' does not exist.");
		}
		return branch;
	}

	private BoolQueryBuilder getBranchCriteria(Branch branch, Date timepoint, Set<String> versionsReplaced, ContentSelection contentSelection, Commit commit) {
		final BoolQueryBuilder boolQueryShouldClause = boolQuery();
		final BoolQueryBuilder branchCriteria =
				boolQuery().should(boolQueryShouldClause.must(termQuery("path", branch.getPath())));

		switch (contentSelection) {
			case STANDARD_SELECTION:
				// On this branch and started not ended
				boolQueryShouldClause.must(rangeQuery("start").lte(timepoint.getTime()));
				if (commit != null) {
					boolQueryShouldClause.must(
							boolQuery()
									// If there is a commit started then components should either have not ended
									// or should have ended at the time of the current commit
									.should(boolQuery().mustNot(existsQuery("end")))
									.should(termQuery("end", commit.getTimepoint().getTime()))
					);
				} else {
					boolQueryShouldClause.mustNot(existsQuery("end"));
				}
				// Or any parent branch within time constraints
				addParentCriteriaRecursively(branchCriteria, branch, versionsReplaced);
				break;

			case CHANGES_ON_THIS_BRANCH_ONLY:
				// On this branch and started not ended
				boolQueryShouldClause.must(rangeQuery("start").lte(timepoint.getTime()))
						.mustNot(existsQuery("end"));
				break;

			case CHANGES_IN_THIS_COMMIT_ONLY:
				// On this branch and started at commit date, not ended
				boolQueryShouldClause.must(termQuery("start", timepoint.getTime()))
						.mustNot(existsQuery("end"));
				break;

			case CHANGES_AND_DELETIONS_IN_THIS_COMMIT_ONLY:
				// On this branch and started at commit date, not ended
				boolQueryShouldClause.must(boolQuery()
						.should(termQuery("start", timepoint.getTime()))
						.should(termQuery("end", timepoint.getTime())));

				if (commit != null && commit.isRebase()) {

					// A rebase commit also includes all the changes
					// between the previous and new base timepoints on all ancestor branches

					// Collect previous and new base timepoints on all ancestor branches
					List<BranchTimeRange> branchTimeRanges = new ArrayList<>();
					Date tempBase = commit.getRebasePreviousBase();
					String parentPath = branch.getPath();
					while ((parentPath = PathUtil.getParentPath(parentPath)) != null) {
						Branch latestVersionOfParent = branchService.findAtTimepointOrThrow(parentPath, commit.getTimepoint());
						branchTimeRanges.add(new BranchTimeRange(parentPath, tempBase, latestVersionOfParent.getHead()));

						Branch baseVersionOfParent = branchService.findAtTimepointOrThrow(parentPath, tempBase);
						tempBase = baseVersionOfParent.getBase();
					}

					// Add all branch time ranges to selection criteria
					for (BranchTimeRange branchTimeRange : branchTimeRanges) {
						branchCriteria.should(boolQuery()
								.must(termQuery("path", branchTimeRange.getPath()))
								.must(rangeQuery("start").gt(branchTimeRange.getStart().getTime()))
								.must(boolQuery()
										.should(boolQuery().mustNot(existsQuery("end")))
										.should(rangeQuery("end").lte(branchTimeRange.getEnd().getTime()))
								)
						);
					}
				}

				break;

			case UNPROMOTED_CHANGES_AND_DELETIONS_ON_THIS_BRANCH: {
					Date startPoint = branch.getLastPromotion() != null ? branch.getLastPromotion() : branch.getCreation();
					branchCriteria.must(boolQuery()
							.should(rangeQuery("start").gte(startPoint))
							.should(rangeQuery("end").gte(startPoint)));
				}
				break;

			case UNPROMOTED_CHANGES_ON_THIS_BRANCH: {
					Date startPoint = branch.getLastPromotion() != null ? branch.getLastPromotion() : branch.getCreation();
					branchCriteria
							.must(rangeQuery("start").gte(startPoint))
							.mustNot(existsQuery("end"));
				}
				break;
		}
		return branchCriteria;
	}

	private void addParentCriteriaRecursively(BoolQueryBuilder branchCriteria, Branch branch, Set<String> versionsReplaced) {
		String parentPath = PathUtil.getParentPath(branch.getPath());
		if (parentPath != null) {
			final Branch parentBranch = branchService.findAtTimepointOrThrow(parentPath, branch.getBase());
			versionsReplaced = new HashSet<>(versionsReplaced);
			versionsReplaced.addAll(parentBranch.getVersionsReplaced());
			final Date base = branch.getBase();
			branchCriteria.should(boolQuery()
					.must(termQuery("path", parentBranch.getPath()))
					.must(rangeQuery("start").lte(base.getTime()))
					.must(boolQuery()
							.should(boolQuery().mustNot(existsQuery("end")))
							.should(rangeQuery("end").gt(base.getTime())))
					.mustNot(termsQuery("_id", versionsReplaced))
			);
			addParentCriteriaRecursively(branchCriteria, parentBranch, versionsReplaced);
		}
	}

	<T extends Entity> void endOldVersions(Commit commit, String idField, Class<T> clazz, Collection<? extends Object> ids, ElasticsearchCrudRepository repository) {
		// End versions of the entity on this path by setting end date
		final NativeSearchQuery query = new NativeSearchQueryBuilder()
				.withQuery(
						new BoolQueryBuilder()
								.must(termQuery("path", commit.getBranch().getPath()))
								.must(rangeQuery("start").lt(commit.getTimepoint().getTime()))
								.mustNot(existsQuery("end"))
				)
				.withFilter(
						new BoolQueryBuilder()
								.must(termsQuery(idField, ids))
				)
				.build();

		List<T> toSave = new ArrayList<>();
		try (final CloseableIterator<T> localVersionsToEnd = elasticsearchTemplate.stream(query, clazz)) {
			localVersionsToEnd.forEachRemaining(version -> {
				version.setEnd(commit.getTimepoint());
				toSave.add(version);
			});
		}
		if (!toSave.isEmpty()) {
			repository.saveAll(toSave);
			logger.debug("Ended {} {} {}", toSave.size(), clazz.getSimpleName(), toSave.stream().map(Entity::getInternalId).collect(Collectors.toList()));
			toSave.clear();
		}

		// Hide versions of the entity on other paths from this branch
		final NativeSearchQuery query2 = new NativeSearchQueryBuilder()
				.withQuery(
						new BoolQueryBuilder()
								.must(getBranchCriteriaIncludingOpenCommit(commit))
								.must(rangeQuery("start").lt(commit.getTimepoint().getTime()))
				)
				.withFilter(
						new BoolQueryBuilder()
								.must(termsQuery(idField, ids))
				)
				.build();

		Set<String> versionsReplaced = new HashSet<>();
		try (final CloseableIterator<T> replacedVersions = elasticsearchTemplate.stream(query2, clazz)) {
			replacedVersions.forEachRemaining(version -> {
				versionsReplaced.add(version.getInternalId());
			});
		}
		commit.addVersionsReplaced(versionsReplaced);

		logger.debug("Replaced {} {} {}", versionsReplaced.size(), clazz.getSimpleName(), versionsReplaced);
	}

	void setEntityMeta(Entity entity, Commit commit) {
		Assert.notNull(entity, "Entity must not be null");
		Assert.notNull(commit, "Commit must not be null");
		doSetEntityMeta(commit, entity);
	}

	void setEntityMeta(Collection<? extends Entity> entities, Commit commit) {
		Assert.notNull(entities, "Entities must not be null");
		Assert.notNull(commit, "Commit must not be null");
		for (Entity entity : entities) {
			doSetEntityMeta(commit, entity);
		}
	}

	private void doSetEntityMeta(Commit commit, Entity entity) {
		entity.setPath(commit.getBranch().getPath());
		entity.setStart(commit.getTimepoint());
		entity.setEnd(null);
		entity.clearInternalId();
	}

	enum ContentSelection {
		STANDARD_SELECTION,
		CHANGES_ON_THIS_BRANCH_ONLY,
		CHANGES_IN_THIS_COMMIT_ONLY,
		CHANGES_AND_DELETIONS_IN_THIS_COMMIT_ONLY,
		UNPROMOTED_CHANGES_AND_DELETIONS_ON_THIS_BRANCH,
		UNPROMOTED_CHANGES_ON_THIS_BRANCH;
	}

	private static final class BranchTimeRange {

		private String path;
		private Date start;
		private Date end;

		BranchTimeRange(String path, Date start, Date end) {
			this.path = path;
			this.start = start;
			this.end = end;
		}

		public String getPath() {
			return path;
		}

		public Date getStart() {
			return start;
		}

		public Date getEnd() {
			return end;
		}
	}
}
