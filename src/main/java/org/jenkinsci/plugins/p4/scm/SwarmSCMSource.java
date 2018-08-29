package org.jenkinsci.plugins.p4.scm;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.TaskListener;
import jenkins.scm.api.SCMHeadCategory;
import jenkins.scm.impl.ChangeRequestSCMHeadCategory;
import jenkins.scm.impl.UncategorizedSCMHeadCategory;
import jenkins.util.NonLocalizable;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.p4.browsers.P4Browser;
import org.jenkinsci.plugins.p4.browsers.SwarmBrowser;
import org.jenkinsci.plugins.p4.changes.P4ChangeRef;
import org.jenkinsci.plugins.p4.changes.P4Ref;
import org.jenkinsci.plugins.p4.changes.P4RefBuilder;
import org.jenkinsci.plugins.p4.client.ConnectionHelper;
import org.jenkinsci.plugins.p4.client.ViewMapHelper;
import org.jenkinsci.plugins.p4.scm.events.P4BranchScanner;
import org.jenkinsci.plugins.p4.swarmAPI.SwarmHelper;
import org.jenkinsci.plugins.p4.swarmAPI.SwarmProjectAPI;
import org.jenkinsci.plugins.p4.swarmAPI.SwarmReviewAPI;
import org.jenkinsci.plugins.p4.swarmAPI.SwarmReviewsAPI;
import org.jenkinsci.plugins.p4.tasks.CheckoutStatus;
import org.jenkinsci.plugins.p4.workspace.ManualWorkspaceImpl;
import org.jenkinsci.plugins.p4.workspace.Workspace;
import org.jenkinsci.plugins.p4.workspace.WorkspaceSpec;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.jenkinsci.plugins.p4.review.ReviewProp.P4_CHANGE;
import static org.jenkinsci.plugins.p4.review.ReviewProp.SWARM_BRANCH;
import static org.jenkinsci.plugins.p4.review.ReviewProp.SWARM_PATH;
import static org.jenkinsci.plugins.p4.review.ReviewProp.SWARM_PROJECT;
import static org.jenkinsci.plugins.p4.review.ReviewProp.SWARM_STATUS;

public class SwarmSCMSource extends AbstractP4SCMSource {

	private String project;

	transient private SwarmHelper swarm;

	@DataBoundConstructor
	public SwarmSCMSource(String credential, String charset, String format) throws Exception {
		super(credential);

		setCharset(charset);
		setFormat(format);
	}

	@DataBoundSetter
	public void setProject(String project) {
		this.project = project;
	}

	public String getProject() {
		return project;
	}

	public SwarmHelper getSwarm() throws Exception {
		if (swarm == null) {
			try (ConnectionHelper p4 = new ConnectionHelper(getOwner(), credential, null)) {
				this.swarm = new SwarmHelper(p4, "4");
			}
		}
		return swarm;
	}

	public void setSwarm(SwarmHelper swarm) {
		this.swarm = swarm;
	}

	@Override
	public P4Browser getBrowser() {
		try {
			return new SwarmBrowser(getSwarm().getBaseUrl());
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public List<P4SCMHead> getTags(@NonNull TaskListener listener) throws Exception {

		List<P4SCMHead> list = new ArrayList<>();

		List<SwarmReviewsAPI.Reviews> reviews = getSwarm().getActiveReviews(project);
		for (SwarmReviewsAPI.Reviews review : reviews) {
			String reviewID = String.valueOf(review.getId());

			List<String> branches = getBranchesInReview(reviewID, project);
			for (String branch : branches) {
				// Get first Swarm path; it MUST include the Jenkinsfile
				P4SwarmPath swarmPath = getPathsInBranch(branch, project);

				String trgName = branch + "-" + reviewID;
				P4SCMHead target = new P4SCMHead(trgName, swarmPath);
				P4ChangeRequestSCMHead tag = new P4ChangeRequestSCMHead(trgName, reviewID, swarmPath, target);
				list.add(tag);
			}
		}

		return list;
	}

	@Override
	public List<P4SCMHead> getHeads(@NonNull TaskListener listener) throws Exception {

		List<P4SCMHead> list = new ArrayList<>();

		List<SwarmProjectAPI.Branch> branches = getSwarm().getBranchesInProject(project);
		for (SwarmProjectAPI.Branch branch : branches) {
			// Get first Swarm path; it MUST include the Jenkinsfile
			P4SwarmPath swarmPath = branch.getPath();

			P4SCMHead head = new P4SCMHead(branch.getId(), swarmPath);
			list.add(head);
		}

		return list;
	}

	@Override
	public P4SCMRevision getRevision(P4SCMHead head, TaskListener listener) throws Exception {
		if (head instanceof P4ChangeRequestSCMHead) {
			P4ChangeRequestSCMHead changeRequest = (P4ChangeRequestSCMHead) head;
			String review = changeRequest.getReview();
			long change = getLastChangeInReview(review);

			P4SCMRevision revision = new P4SCMRevision(head, new P4ChangeRef(change));
			return revision;
		}
		return super.getRevision(head, listener);
	}

	/**
	 * A specific revision based on the Event Payload.
	 *
	 * @param payload JSON payload from an external Event
	 * @return the change as a P4SCMRevision object or null if no match.
	 */
	@Override
	public P4SCMRevision getRevision(JSONObject payload) {

		// Verify Change is set in JSON
		String change = getProperty(payload, P4_CHANGE);
		if(change == null) {
			return null;
		}

		// Verify Project is set in JSON and matches Source
		String project = getProperty(payload, SWARM_PROJECT);

		// If project is not defined; try probing with non-Swarm event on Swarm Source
		if (project == null || !project.equalsIgnoreCase(getProject())) {

			P4Ref ref = P4RefBuilder.get(change);

			P4BranchScanner scanner = getScanner(ref);
			String path = scanner.getProjectRoot();
			String branch = scanner.getBranch();

			return P4SCMRevision.swarmBuilder(path, branch, ref);
		}

		// Project is defined so look up in Swarm
		String branch = getProperty(payload, SWARM_BRANCH);
		String path = getProperty(payload, SWARM_PATH);
		String status = getProperty(payload, SWARM_STATUS);

		if (branch == null || path == null || status == null ) {
			return null;
		}

		CheckoutStatus checkoutStatus = CheckoutStatus.parse(status);
		switch (checkoutStatus) {
			case SUBMITTED:
			case COMMITTED:
				P4Ref ref = new P4ChangeRef(Long.parseLong(change));
				return P4SCMRevision.builder(path, branch, ref);

			case SHELVED:
				// TODO Use traits to add Swarm review information
				return null;

			default:
				return null;
		}
	}

	@Override
	protected boolean findInclude(String path) {
		// Scan Swarm to see if path is in a project branch
		List<SwarmProjectAPI.Branch> branches;
		try {
			branches = getSwarm().getBranchesInProject(project);
		} catch (Exception e) {
			return false;
		}

		for (SwarmProjectAPI.Branch branch : branches) {
			for(String p : branch.getPaths() ) {
				if(p.startsWith(path)) {
					return true;
				}
			}
		}

		return false;
	}

	@Override
	public Workspace getWorkspace(P4Path path) {
		if (path == null || !(path instanceof P4SwarmPath)) {
			throw new IllegalArgumentException("missing Swarm path");
		}

		P4SwarmPath swarmPath = (P4SwarmPath) path;

		String client = getFormat();
		String jenkinsPath = path.getPath() + "/" + getScriptPathOrDefault();
		String jenkinsView = ViewMapHelper.getClientView(jenkinsPath, client);
		String mappingsView = ViewMapHelper.getClientView(swarmPath.getMappings(), client);
		String view = jenkinsView + "\n" + mappingsView;

		WorkspaceSpec spec = new WorkspaceSpec(view, null);
		return new ManualWorkspaceImpl(getCharset(), false, client, spec);
	}

	protected boolean isCategoryEnabled(@NonNull SCMHeadCategory category) {
		return true;
	}

	private List<String> getBranchesInReview(String review, String project) throws Exception {
		SwarmReviewAPI api = getSwarm().getSwarmReview(review);

		HashMap<String, List<String>> projects = api.getReview().getProjects();
		List<String> branches = projects.get(project);

		return branches;
	}

	private long getLastChangeInReview(String review) throws Exception {
		SwarmReviewAPI api = getSwarm().getSwarmReview(review);

		List<Long> changes = api.getReview().getChanges();

		long lastChange = 0;
		for (Long change : changes) {
			if (change > lastChange) {
				lastChange = change;
			}
		}
		return lastChange;
	}

	private P4SwarmPath getPathsInBranch(String id, String project) throws Exception {

		List<SwarmProjectAPI.Branch> branches = getSwarm().getBranchesInProject(project);

		for (SwarmProjectAPI.Branch branch : branches) {
			if (id.equals(branch.getId())) {
				P4SwarmPath swarmPath = branch.getPath();
				return swarmPath;
			}
		}
		return null;
	}

	@Extension
	@Symbol("multiSwarm")
	public static final class DescriptorImpl extends P4SCMSourceDescriptor {

		@Override
		public String getDisplayName() {
			return "Helix Swarm";
		}

		@NonNull
		@Override
		protected SCMHeadCategory[] createCategories() {
			return new SCMHeadCategory[]{
					new UncategorizedSCMHeadCategory(new NonLocalizable("Branches")),
					new ChangeRequestSCMHeadCategory(new NonLocalizable("Reviews"))
			};
		}
	}
}
