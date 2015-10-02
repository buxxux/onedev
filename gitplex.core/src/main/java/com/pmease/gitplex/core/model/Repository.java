package com.pmease.gitplex.core.model;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;

import javax.annotation.Nullable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.Lob;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import org.apache.commons.lang3.SerializationUtils;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.RepositoryCache.FileKey;
import org.eclipse.jgit.revwalk.LastCommitsOfChildren;
import org.eclipse.jgit.revwalk.LastCommitsOfChildren.Value;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.OrTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.io.NullOutputStream;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.validator.constraints.NotEmpty;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.pmease.commons.git.Blob;
import com.pmease.commons.git.BlobIdent;
import com.pmease.commons.git.Commit;
import com.pmease.commons.git.Git;
import com.pmease.commons.git.GitUtils;
import com.pmease.commons.git.Submodule;
import com.pmease.commons.git.exception.NotFileException;
import com.pmease.commons.git.exception.ObjectNotExistException;
import com.pmease.commons.hibernate.AbstractEntity;
import com.pmease.commons.loader.AppLoader;
import com.pmease.commons.util.FileUtils;
import com.pmease.commons.util.LockUtils;
import com.pmease.commons.util.StringUtils;
import com.pmease.commons.wicket.editable.annotation.Editable;
import com.pmease.commons.wicket.editable.annotation.Markdown;
import com.pmease.gitplex.core.GitPlex;
import com.pmease.gitplex.core.gatekeeper.AndGateKeeper;
import com.pmease.gitplex.core.gatekeeper.GateKeeper;
import com.pmease.gitplex.core.listeners.RepositoryListener;
import com.pmease.gitplex.core.manager.StorageManager;
import com.pmease.gitplex.core.permission.object.ProtectedObject;
import com.pmease.gitplex.core.permission.object.UserBelonging;
import com.pmease.gitplex.core.validation.RepositoryName;

@Entity
@Table(uniqueConstraints={@UniqueConstraint(columnNames={"owner", "name"})})
@Cache(usage=CacheConcurrencyStrategy.READ_WRITE)
@Editable
@SuppressWarnings("serial")
public class Repository extends AbstractEntity implements UserBelonging {

	private static final String FQN_SEPARATOR = "/";
	
	public static final String REFS_GITPLEX = "refs/gitplex/";
	
	private static final int LAST_COMMITS_CACHE_THRESHOLD = 1000;
	
	private static final int MAX_READ_BLOB_SIZE = 5*1024*1024;
	
	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(nullable=false)
	private User owner;

	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(nullable=true)
	private Repository forkedFrom;

	@Column(nullable=false)
	private String name;
	
	private String description;
	
	@Lob
	@Column(nullable=false, length=65535)
	private ArrayList<GateKeeper> gateKeepers = new ArrayList<>();
	
	@Lob
	@Column(nullable=false, length=65535)
	private ArrayList<IntegrationPolicy> integrationPolicies = new ArrayList<>();
	
	@Column(nullable=false)
	private Date createdAt = new Date();

	@OneToMany(mappedBy="targetRepo")
	@OnDelete(action=OnDeleteAction.CASCADE)
	private Collection<PullRequest> incomingRequests = new ArrayList<>();
	
	@OneToMany(mappedBy="sourceRepo")
	private Collection<PullRequest> outgoingRequests = new ArrayList<>();
	
	@OneToMany(mappedBy="repository")
	@OnDelete(action=OnDeleteAction.CASCADE)
	private Collection<Authorization> authorizations = new ArrayList<>();

    @OneToMany(mappedBy="forkedFrom")
	private Collection<Repository> forks = new ArrayList<>();
    
    private transient Map<BlobIdent, Blob> blobCache;
    
    private transient Map<DiffKey, List<DiffEntry>> diffCache;
    
    private transient Map<String, Commit> commitCache;
    
    private transient Map<String, Optional<ObjectId>> objectIdCache;
    
    private transient Map<String, Map<String, Ref>> refsCache;
    
    private transient Collection<String> branches;
    
    private transient String defaultBranch;
    
	public User getOwner() {
		return owner;
	}

	public void setOwner(User owner) {
		this.owner = owner;
	}

	@Editable(order=100, description=
			"Specify name of the repository. It will be used to identify the repository when accessing via Git.")
	@RepositoryName
	@NotEmpty
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	@Editable(order=200, description="Specify description of the repository.")
	@Markdown
	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

    @NotNull
	@Valid
	public List<GateKeeper> getGateKeepers() {
		return gateKeepers;
	}

	public void setGateKeepers(ArrayList<GateKeeper> gateKeepers) {
		this.gateKeepers = gateKeepers;
	}

	@Valid
	public List<IntegrationPolicy> getIntegrationPolicies() {
		return integrationPolicies;
	}

	public void setIntegrationPolicies(ArrayList<IntegrationPolicy> integrationPolicies) {
		this.integrationPolicies = integrationPolicies;
	}

	public Date getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Date createdAt) {
		this.createdAt = createdAt;
	}

    @Override
	public User getUser() {
		return getOwner();
	}

	public Collection<PullRequest> getIncomingRequests() {
		return incomingRequests;
	}

	public void setIncomingRequests(Collection<PullRequest> incomingRequests) {
		this.incomingRequests = incomingRequests;
	}

	public Collection<PullRequest> getOutgoingRequests() {
		return outgoingRequests;
	}

	public void setOutgoingRequests(Collection<PullRequest> outgoingRequests) {
		this.outgoingRequests = outgoingRequests;
	}

	public Collection<Authorization> getAuthorizations() {
		return authorizations;
	}

	public void setAuthorizations(Collection<Authorization> authorizations) {
		this.authorizations = authorizations;
	}

	public Repository getForkedFrom() {
		return forkedFrom;
	}

	public void setForkedFrom(Repository forkedFrom) {
		this.forkedFrom = forkedFrom;
	}

	public Collection<Repository> getForks() {
		return forks;
	}

	public void setForks(Collection<Repository> forks) {
		this.forks = forks;
	}

	public Collection<String> getBranches() {
		if (branches == null)
			branches = git().listBranches();
        return branches;
    }

    @Override
	public boolean has(ProtectedObject object) {
		if (object instanceof Repository) {
			Repository repository = (Repository) object;
			return repository.getId().equals(getId());
		} else {
			return false;
		}
	}

	public String getFQN() {
		return getOwner().getName() + FQN_SEPARATOR + getName();
	}
	
	public static String getNameByFQN(String repositoryFQN) {
		return StringUtils.substringAfterLast(repositoryFQN, FQN_SEPARATOR);
	}
	
	public static String getUserNameByFQN(String repositoryFQN) {
		return StringUtils.substringBeforeLast(repositoryFQN, FQN_SEPARATOR);
	}
	
	@Override
	public String toString() {
		return getFQN();
	}
	
	public Git git() {
		return new Git(AppLoader.getInstance(StorageManager.class).getRepoDir(this));
	}
	
	/**
	 * Whether or not specified git represents a valid repository git. This can be used to tell 
	 * apart a GitPlex repository git from some other Git repositories.
	 * 
	 * @return
	 * 			<tt>true</tt> if valid; <tt>false</tt> otherwise
	 */
	public boolean isValid() {
        File updateHook = new File(git().repoDir(), "hooks/update");
        if (!updateHook.exists()) 
        	return false;
        
        try {
			String content = FileUtils.readFileToString(updateHook);
			return content.contains("GITPLEX_USER_ID");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		
	}
	
	public GateKeeper getGateKeeper() {
		AndGateKeeper andGateKeeper = new AndGateKeeper();
		for (GateKeeper each: getGateKeepers())
			andGateKeeper.getGateKeepers().add(each);
		return andGateKeeper;
	}

	/**
	 * Find fork root of this repository. 
	 * 
	 * @return
	 * 			fork root of this repository, or <tt>null</tt> if the repository is not 
	 * 			forked from any other repository  
	 */
	public @Nullable Repository findForkRoot() {
		if (forkedFrom != null) {
			Repository forkedRoot = forkedFrom.findForkRoot();
			if (forkedRoot != null)
				return forkedRoot;
			else
				return forkedFrom;
		} else {
			return null;
		}
	}
	
	/**
	 * Find all descendant repositories forking from current repository.
	 * 
	 * @return
	 * 			all descendant repositories forking from current repository
	 */
	public List<Repository> findForkDescendants() {
		List<Repository> descendants = new ArrayList<>();
		for (Repository fork: getForks()) { 
			descendants.add(fork);
			descendants.addAll(fork.findForkDescendants());
		}
		
		return descendants;
	}
	
	/**
	 * Find all comparable repositories of current repository. Comparable repositories can 
	 * be connected via forks, and can be compared/pulled. 
	 * 
	 * @return
	 * 			comparable repositories of current repository, with current repository also 
	 * 			included in the collection
	 */
	public List<Repository> findAffinals() {
		List<Repository> affinals = new ArrayList<Repository>();
		Repository forkRoot = findForkRoot();
		if (forkRoot != null) {
			affinals.add(forkRoot);
			affinals.addAll(forkRoot.findForkDescendants());
		} else {
			affinals.add(this);
			affinals.addAll(findForkDescendants());
		}
		return affinals;
	}

	public String getUrl() {
		return GitPlex.getInstance().guessServerUrl() + "/" + getFQN();
	}
	
	public String getDefaultBranch() {
		if (defaultBranch == null)
			defaultBranch = git().resolveDefaultBranch();
		return defaultBranch;
	}
	
	public String defaultBranchIfNull(@Nullable String revision) {
		if (revision != null)
			return revision;
		else
			return getDefaultBranch();
	}
	
	private Blob readBlob(ObjectLoader objectLoader, BlobIdent ident) {
		long blobSize = objectLoader.getSize();
		if (blobSize > MAX_READ_BLOB_SIZE) {
			try (InputStream is = objectLoader.openStream()) {
				byte[] bytes = new byte[MAX_READ_BLOB_SIZE];
				is.read(bytes);
				return new Blob(ident, bytes, blobSize);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		} else {
			return new Blob(ident, objectLoader.getCachedBytes());
		}
	}
	
	private Map<BlobIdent, Blob> getBlobCache() {
		if (blobCache == null) {
			synchronized(this) {
				if (blobCache == null)
					blobCache = new ConcurrentHashMap<>();
			}
		}
		return blobCache;
	}
	
	/**
	 * Read blob content and cache result in repository in case the same blob 
	 * content is requested again. 
	 * 
	 * We made this method thread-safe as we are using ForkJoinPool to calculate 
	 * diffs of multiple blob changes concurrently, and this method will be 
	 * accessed concurrently in that special case.
	 * 
	 * @param blobIdent
	 * 			ident of the blob
	 * @return
	 * 			blob of specified blob ident
	 * @throws
	 * 			ObjectNotExistException if blob of specified ident can not be found in repository 
	 * 			
	 */
	public Blob getBlob(BlobIdent blobIdent) {
		Preconditions.checkArgument(blobIdent.revision!=null && blobIdent.path!=null && blobIdent.mode!=null, 
				"Revision, path and mode of ident param should be specified");
		
		Blob blob = getBlobCache().get(blobIdent);
		if (blob == null) {
			if (blobIdent.id != null) {
				try (FileRepository jgitRepo = openAsJGitRepo()) {
					if (blobIdent.isGitLink()) {
						String url = getSubmodules(blobIdent.revision).get(blobIdent.path);
						if (url == null)
							throw new ObjectNotExistException("Unable to find submodule '" + blobIdent.path + "' in .gitmodules");
						blob = new Blob(blobIdent, new Submodule(url, blobIdent.id).toString().getBytes());
					} else if (blobIdent.isTree()) {
						throw new NotFileException("Path '" + blobIdent.path + "' is a tree");
					} else {
						ObjectLoader objectLoader = jgitRepo.open(ObjectId.fromString(blobIdent.id), Constants.OBJ_BLOB);
						blob = readBlob(objectLoader, blobIdent);
					}
					getBlobCache().put(blobIdent, blob);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			} else {
				try (FileRepository jgitRepo = openAsJGitRepo(); RevWalk revWalk = new RevWalk(jgitRepo)) {
					ObjectId commitId = getObjectId(blobIdent.revision);
					RevTree revTree = revWalk.parseCommit(commitId).getTree();
					TreeWalk treeWalk = TreeWalk.forPath(jgitRepo, blobIdent.path, revTree);
					if (treeWalk != null) {
						if (blobIdent.isGitLink()) {
							String url = getSubmodules(blobIdent.revision).get(blobIdent.path);
							if (url == null)
								throw new ObjectNotExistException("Unable to find submodule '" + blobIdent.path + "' in .gitmodules");
							String hash = treeWalk.getObjectId(0).name();
							blob = new Blob(blobIdent, new Submodule(url, hash).toString().getBytes());
						} else if (blobIdent.isTree()) {
							throw new NotFileException("Path '" + blobIdent.path + "' is a tree");
						} else {
							ObjectLoader objectLoader = treeWalk.getObjectReader().open(treeWalk.getObjectId(0));
							blob = readBlob(objectLoader, blobIdent);
						}
						getBlobCache().put(blobIdent, blob);
					} else {
						throw new ObjectNotExistException("Unable to find blob path '" + blobIdent.path + "' in revision '" + blobIdent.revision + "'");
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}
		return blob;
	}
	
	public InputStream getInputStream(BlobIdent ident) {
		try (FileRepository jgitRepo = openAsJGitRepo(); RevWalk revWalk = new RevWalk(jgitRepo)) {
			ObjectId commitId = getObjectId(ident.revision);
			RevTree revTree = revWalk.parseCommit(commitId).getTree();
			TreeWalk treeWalk = TreeWalk.forPath(jgitRepo, ident.path, revTree);
			if (treeWalk != null) {
				ObjectLoader objectLoader = treeWalk.getObjectReader().open(treeWalk.getObjectId(0));
				return objectLoader.openStream();
			} else {
				throw new ObjectNotExistException("Unable to find blob path '" + ident.path + "' in revision '" + ident.revision + "'");
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Get cached object id of specified revision.
	 * 
	 * @param revision
	 * 			revision to resolve object id for
	 * @param mustExist
	 * 			true to have the method throwing exception instead 
	 * 			of returning null if the revision does not exist
	 * @return
	 * 			object id of specified revision, or <tt>null</tt> if revision 
	 * 			does not exist and mustExist is specified as false
	 */
	@Nullable
	public ObjectId getObjectId(String revision, boolean mustExist) {
		if (objectIdCache == null)
			objectIdCache = new HashMap<>();
		
		Optional<ObjectId> optional = objectIdCache.get(revision);
		if (optional == null) {
			try (FileRepository jgitRepo = openAsJGitRepo()) {
				ObjectId objectId = jgitRepo.resolve(revision);
				optional = Optional.fromNullable(objectId);
				objectIdCache.put(revision, optional);
			} catch (RevisionSyntaxException | IOException e) {
				throw new RuntimeException(e);
			}
		}
		if (mustExist && !optional.isPresent())
			throw new ObjectNotExistException("Unable to find revision '" + revision + "'");
		return optional.orNull();
	}
	
	public ObjectId getObjectId(String revision) {
		return getObjectId(revision, true);
	}
	
	public void cacheObjectId(String revision, @Nullable ObjectId objectId) {
		if (objectIdCache == null)
			objectIdCache = new HashMap<>();
		
		objectIdCache.put(revision, Optional.fromNullable(objectId));
	}
	
	public List<DiffEntry> getDiffs(String oldRev, String newRev, boolean detectRenames, String...paths) {
		if (diffCache == null)
			diffCache = new HashMap<>();
		
		DiffKey key = new DiffKey(oldRev, newRev, detectRenames, paths);
		List<DiffEntry> diffs = diffCache.get(key);
		if (diffs == null) {
			try (	FileRepository jgitRepo = openAsJGitRepo();
					DiffFormatter diffFormatter = new DiffFormatter(NullOutputStream.INSTANCE);) {
		    	diffFormatter.setRepository(jgitRepo);
		    	diffFormatter.setDetectRenames(detectRenames);
				AnyObjectId oldCommitId = getObjectId(oldRev);
				AnyObjectId newCommitId = getObjectId(newRev);
				if (paths.length >= 2) {
					List<TreeFilter> pathFilters = new ArrayList<>();
					for (String path: paths)
						pathFilters.add(PathFilter.create(path));
					diffFormatter.setPathFilter(OrTreeFilter.create(pathFilters));
				} else if (paths.length == 1) {
					diffFormatter.setPathFilter(PathFilter.create(paths[0]));
				}
				diffs = new ArrayList<>();
		    	for (DiffEntry entry: diffFormatter.scan(oldCommitId, newCommitId)) {
		    		if (!Objects.equal(entry.getOldPath(), entry.getNewPath())
		    				|| !Objects.equal(entry.getOldMode(), entry.getNewMode())
		    				|| entry.getOldId()==null || !entry.getOldId().isComplete()
		    				|| entry.getNewId()== null || !entry.getNewId().isComplete()
		    				|| !entry.getOldId().equals(entry.getNewId())) {
		    			diffs.add(entry);
		    		}
		    	}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}			
			diffCache.put(key, diffs);
		}
		return diffs;
	}
	
	public Commit getCommit(String commitHash) {
		if (commitCache == null)
			commitCache = new HashMap<>();
		
		Commit commit = commitCache.get(commitHash);
		if (commit == null) {
			commit = git().showRevision(commitHash);
			commitCache.put(commitHash, commit);
		}
		return commit;
	}
	
	public void cacheCommits(List<Commit> commits) {
		if (commitCache == null)
			commitCache = new HashMap<>();
		
		for (Commit commit: commits)
			commitCache.put(commit.getHash(), commit);
	}

	public FileRepository openAsJGitRepo() {
		try {
			return (FileRepository) RepositoryCache.open(FileKey.lenient(git().repoDir(), FS.DETECTED), true);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public LastCommitsOfChildren getLastCommitsOfChildren(String revision, @Nullable String path) {
		if (path == null)
			path = "";
		
		final File cacheDir = new File(
				GitPlex.getInstance(StorageManager.class).getCacheDir(this), 
				"last_commits/" + path + "/gitplex_last_commits");
		
		final ReadWriteLock lock;
		try {
			lock = LockUtils.getReadWriteLock(cacheDir.getCanonicalPath());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		
		final Set<ObjectId> commitIds = new HashSet<>(); 
		
		lock.readLock().lock();
		try {
			if (cacheDir.exists()) {
				for (String each: cacheDir.list()) 
					commitIds.add(ObjectId.fromString(each));
			} 	
		} finally {
			lock.readLock().unlock();
		}
		
		org.eclipse.jgit.revwalk.LastCommitsOfChildren.Cache cache;
		if (!commitIds.isEmpty()) {
			cache = new org.eclipse.jgit.revwalk.LastCommitsOfChildren.Cache() {
	
				@SuppressWarnings("unchecked")
				@Override
				public Map<String, Value> getLastCommitsOfChildren(ObjectId commitId) {
					if (commitIds.contains(commitId)) {
						lock.readLock().lock();
						try {
							byte[] bytes = FileUtils.readFileToByteArray(new File(cacheDir, commitId.name()));
							return (Map<String, Value>) SerializationUtils.deserialize(bytes);
						} catch (IOException e) {
							throw new RuntimeException(e);
						} finally {
							lock.readLock().unlock();
						}
					} else {
						return null;
					}
				}
				
			};
		} else {
			cache = null;
		}

		final AnyObjectId commitId = getObjectId(revision);
		
		try (FileRepository jgitRepo = openAsJGitRepo()) {
			long time = System.currentTimeMillis();
			LastCommitsOfChildren lastCommits = new LastCommitsOfChildren(jgitRepo, commitId, path, cache);
			long elapsed = System.currentTimeMillis()-time;
			if (elapsed > LAST_COMMITS_CACHE_THRESHOLD) {
				lock.writeLock().lock();
				try {
					if (!cacheDir.exists())
						FileUtils.createDir(cacheDir);
					FileUtils.writeByteArrayToFile(
							new File(cacheDir, commitId.name()), 
							SerializationUtils.serialize(lastCommits));
				} catch (IOException e) {
					throw new RuntimeException(e);
				} finally {
					lock.writeLock().unlock();
				}
			}
			return lastCommits;
		}
	}

	public Map<String, Ref> getRefs(String prefix) {
		if (refsCache == null)
			refsCache = new HashMap<>();
		
		Map<String, Ref> cached = refsCache.get(prefix);
		if (cached == null) {
			try (FileRepository jgitRepo = openAsJGitRepo()) {
				cached = jgitRepo.getRefDatabase().getRefs(prefix); 
				refsCache.put(prefix, cached);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		return cached;
	}
	
	public Map<String, String> getSubmodules(String revision) {
		Map<String, String> submodules = new HashMap<>();
		
		Blob blob = getBlob(new BlobIdent(revision, ".gitmodules", FileMode.REGULAR_FILE.getBits()));
		String content = new String(blob.getBytes());
		
		String path = null;
		String url = null;
		
		for (String line: StringUtils.splitAndTrim(content, "\r\n")) {
			if (line.startsWith("[") && line.endsWith("]")) {
				if (path != null && url != null)
					submodules.put(path, url);
				
				path = url = null;
			} else if (line.startsWith("path")) {
				path = StringUtils.substringAfter(line, "=").trim();
			} else if (line.startsWith("url")) {
				url = StringUtils.substringAfter(line, "=").trim();
			}
		}
		if (path != null && url != null)
			submodules.put(path, url);
		
		return submodules;
	}

    public String getBranchFQN(String branch) {
    	return getFQN() + ":" + branch;
    }

    public void deleteBranch(String branch) {
		git().deleteBranch(branch);
		for (RepositoryListener listener: GitPlex.getExtensions(RepositoryListener.class))
			listener.onRefUpdate(this, GitUtils.branch2ref(branch), null);
    }
    
	private static class DiffKey implements Serializable {
		String oldRev;
		
		String newRev;
		
		String[] paths;
		
		boolean detectRenames;
		
		DiffKey(String oldRev, String newRev, boolean detectRenames, String...paths) {
			this.oldRev = oldRev;
			this.newRev = newRev;
			this.detectRenames = detectRenames;
			this.paths = paths;
		}
		
		public boolean equals(Object other) {
			if (!(other instanceof DiffKey))
				return false;
			if (this == other)
				return true;
			DiffKey otherKey = (DiffKey) other;
			return Objects.equal(oldRev, otherKey.oldRev) 
					&& Objects.equal(newRev, otherKey.newRev) 
					&& Objects.equal(paths, otherKey.paths)
					&& Objects.equal(detectRenames, otherKey.detectRenames);
		}

		public int hashCode() {
			return Objects.hashCode(oldRev, newRev, paths, detectRenames);
		}
		
	}
	
}
