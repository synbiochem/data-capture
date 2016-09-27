package manchester.synbiochem.datacapture;

import static java.util.Collections.sort;
import static javax.ws.rs.core.Response.created;
import static javax.ws.rs.core.Response.ok;
import static javax.ws.rs.core.Response.seeOther;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static manchester.synbiochem.datacapture.Constants.JSON;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriBuilder;
import javax.ws.rs.core.UriInfo;

import manchester.synbiochem.datacapture.SeekConnector.Assay;
import manchester.synbiochem.datacapture.SeekConnector.Project;
import manchester.synbiochem.datacapture.SeekConnector.Study;
import manchester.synbiochem.datacapture.SeekConnector.User;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Class and bean that implements the application's user-facing interface.
 *
 * @author Donal Fellows
 */
@Path("/")
@RolesAllowed("ROLE_USER")
public class Application implements Interface {
	@Autowired
	SeekConnector seek;
	@Autowired
	TaskStore tasks;
	@Autowired
	DirectoryLister lister;
	@Autowired
	InformationSource infoSource;
	private Log log = LogFactory.getLog(getClass());

	@Override
	public String status() {
		return "OK";
	}

	@Override
	public Description describe(UriInfo ui) {
		UriBuilder ub = ui.getAbsolutePathBuilder().path("{piece}");
		Description d = new Description();
		d.assays = ub.build("assays");
		d.directories = ub.build("directories");
		d.tasks = ub.build("tasks");
		d.users = ub.build("users");
		return d;
	}

	@Override
	public UserList users() {
		UserList ul = new UserList();
		ul.users = new ArrayList<>(infoSource.getUsers());
		return ul;
	}

	@Override
	public ProjectList projects() {
		ProjectList pl = new ProjectList();
		pl.projects = new ArrayList<>(infoSource.getProjects());
		return pl;
	}

	@Override
	public StudyList studies() {
		StudyList sl = new StudyList();
		sl.studies = new ArrayList<>(seek.getStudies());
		return sl;
	}

	@Override
	public AssayList assays() {
		AssayList al = new AssayList();
		al.assays = new ArrayList<>(seek.getAssays());
		return al;
	}

	@Override
	public DirectoryList dirs() {
		DirectoryList dl = new DirectoryList();
		for (String name : lister.getSubdirectories())
			dl.dirs.add(new DirectoryEntry(name));
		return dl;
	}

	@Override
	public Response dirs(String root, UriInfo ui) {
		UriBuilder ub = ui.getBaseUriBuilder().path("list");
		try {
			DirectoryList dl = new DirectoryList();
			for (File f : lister.getListing(root))
				dl.dirs.add(new DirectoryEntry(f, ub));
			return Response.ok(dl, JSON).build();
		} catch (IOException e) {
			return Response.status(Status.NOT_FOUND).type("text/plain").entity(e.getMessage()).build();
		}
	}

	private static final Comparator<ArchiveTask> taskComparator = new Comparator<ArchiveTask>() {
		@Override
		public int compare(ArchiveTask o1, ArchiveTask o2) {
			return o1.id.compareTo(o2.id);
		}
	};

	@Override
	public ArchiveTaskList tasks(UriInfo ui) {
		UriBuilder ub = ui.getAbsolutePathBuilder().path("{id}");
		ArchiveTaskList atl = new ArchiveTaskList();
		atl.tasks = new ArrayList<>();
		for (String id : tasks.list())
			atl.tasks.add(tasks.describeTask(id, ub));
		sort(atl.tasks, taskComparator);
		return atl;
	}

	@Override
	public Response task(String id) {
		if (id == null || id.isEmpty())
			throw new BadRequestException("bad id");
		try {
			return ok(tasks.describeTask(id, null), JSON).build();
		} catch (NotFoundException e) {
			/*
			 * This case is normal enough that we don't want an exception in the
			 * log.
			 */
			return Response.status(NOT_FOUND).build();
		}
	}

	@Override
	@RolesAllowed("ROLE_USER")
	public Response createTask(ArchiveTask proposedTask, UriInfo ui) {
		if (proposedTask == null)
			throw new BadRequestException("bad task");

		User u0 = proposedTask.submitter;
		if (u0 == null)
			throw new BadRequestException("no user specified");
		if (u0.url == null)
			throw new BadRequestException("no user url");
		User user = seek.getUser(u0.url);

		Assay a0 = proposedTask.assay;
		Study s0 = proposedTask.study;
		if (a0 == null && s0 == null)
			throw new BadRequestException("no assay or study specified");
		if (a0 != null && s0 != null)
			throw new BadRequestException("must not specify both assay and study");
		if (a0 != null && a0.url == null)
			throw new BadRequestException("no assay url");
		if (s0 != null && s0.url == null)
			throw new BadRequestException("no study url");

		List<DirectoryEntry> d0 = proposedTask.directory;
		if (d0 == null)
			throw new BadRequestException("bad directory");
		List<String> dirs = lister.getSubdirectories(d0);
		if (dirs.isEmpty())
			throw new BadRequestException(
					"need at least one directory to archive");

		Project project = proposedTask.project;
		if (project.name == null || project.name.isEmpty())
			throw new BadRequestException("project name must be non-empty");

		String notes = proposedTask.notes;
		if (notes == null)
			notes = "";

		String id;
		if (a0 != null)
			id = createTask(user, a0, dirs, project, notes.trim());
		else
			id = createTask(user, s0, dirs, project, notes.trim());
		log.info("created task " + id + " to archive " + dirs.get(0));
		UriBuilder ub = ui.getAbsolutePathBuilder().path("{id}");
		return created(ub.build(id)).entity(tasks.describeTask(id, ub))
				.type("application/json").build();
	}

	private String createTask(User user, Assay a0, List<String> dirs,
			Project project, String notes) {
		Assay assay = seek.getAssay(a0.url);
		log.info("creating task for " + user.name + " to work on assay "
				+ assay.url + " (" + assay.name + ")");
		return tasks.newTask(user, assay, dirs, project.name, notes);
	}

	private String createTask(User user, Study s0, List<String> dirs,
			Project project, String notes) {
		Study study = seek.getStudy(s0.url);
		log.info("creating task for " + user.name + " to work on study "
				+ study.url + " (" + study.name + ")");
		return tasks.newTask(user, study, dirs, project.name, notes);
	}

	@Override
	public Response deleteTask(String id, UriInfo ui) {
		if (id == null || id.isEmpty())
			throw new BadRequestException("bad id");
		try {
			tasks.deleteTask(id);
		} catch (InterruptedException | ExecutionException e) {
			throw new InternalServerErrorException("problem when cancelling task", e);
		}
		return seeOther(ui.getBaseUriBuilder().path("tasks").build()).build();
	}
}
