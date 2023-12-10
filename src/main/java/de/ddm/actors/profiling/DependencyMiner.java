package de.ddm.actors.profiling;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.Terminated;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import de.ddm.actors.patterns.LargeMessageProxy;
import de.ddm.serialization.AkkaSerializable;
import de.ddm.singletons.InputConfigurationSingleton;
import de.ddm.singletons.SystemConfigurationSingleton;
import de.ddm.structures.CandidatePair;
import de.ddm.structures.InclusionDependency;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.File;
import java.lang.reflect.Array;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.Thread.sleep;

public class DependencyMiner extends AbstractBehavior<DependencyMiner.Message> {

	////////////////////
	// Actor Messages //
	////////////////////

	public interface Message extends AkkaSerializable, LargeMessageProxy.LargeMessage {
	}

	@NoArgsConstructor
	public static class StartMessage implements Message {
		private static final long serialVersionUID = -1963913294517850454L;
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class HeaderMessage implements Message {
		private static final long serialVersionUID = -5322425954432915838L;
		int id;
		String[] header;
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class BatchMessage implements Message {
		private static final long serialVersionUID = 4591192372652568030L;
		int id;
		List<String[]> batch;
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class RegistrationMessage implements Message {
		private static final long serialVersionUID = -4025238529984914107L;
		ActorRef<DependencyWorker.Message> dependencyWorker;
	}

	@Getter
	@NoArgsConstructor
	@AllArgsConstructor
	public static class CompletionMessage implements Message {
		private static final long serialVersionUID = -7642425159675583598L;
		ActorRef<DependencyWorker.Message> dependencyWorker;
		int result;

		int firstTableIndex;
		int firstColumnIndex;

		int secondTableIndex;
		int secondColumnIndex;
	}

	////////////////////////
	// Actor Construction //
	////////////////////////

	public static final String DEFAULT_NAME = "dependencyMiner";

	public static final ServiceKey<DependencyMiner.Message> dependencyMinerService = ServiceKey.create(DependencyMiner.Message.class, DEFAULT_NAME + "Service");

	public static Behavior<Message> create() {
		return Behaviors.setup(DependencyMiner::new);
	}

	private DependencyMiner(ActorContext<Message> context) {
		super(context);
		this.discoverNaryDependencies = SystemConfigurationSingleton.get().isHardMode();
		this.inputFiles = InputConfigurationSingleton.get().getInputFiles();
		this.headerLines = new String[this.inputFiles.length][];
		this.data = new ArrayList<>();
		for (int i=0; i < this.inputFiles.length; i++) {
			this.data.add(new ArrayList<>());
		}
		this.taskQueue = new LinkedList<>();

		this.inputReaders = new ArrayList<>(inputFiles.length);
		for (int id = 0; id < this.inputFiles.length; id++)
			this.inputReaders.add(context.spawn(InputReader.create(id, this.inputFiles[id]), InputReader.DEFAULT_NAME + "_" + id));
		this.resultCollector = context.spawn(ResultCollector.create(), ResultCollector.DEFAULT_NAME);
		this.largeMessageProxy = this.getContext().spawn(LargeMessageProxy.create(this.getContext().getSelf().unsafeUpcast()), LargeMessageProxy.DEFAULT_NAME);

		this.dependencyWorkers = new ArrayList<>();

		// In the constructor
		this.finishedHeaders = new boolean[this.inputFiles.length];
		this.finishedLoading = new boolean[this.inputFiles.length];
		Arrays.fill(this.finishedHeaders, false);
		Arrays.fill(this.finishedLoading, false);

		context.getSystem().receptionist().tell(Receptionist.register(dependencyMinerService, context.getSelf()));
	}

	/////////////////
	// Actor State //
	/////////////////

	private long startTime;

	private final boolean discoverNaryDependencies;
	private final File[] inputFiles;
	private final String[][] headerLines;
	private List<List<List<String>>> data;

	private Queue<CandidatePair> taskQueue;
	private boolean[] finishedHeaders;
	private boolean[] finishedLoading;

	private final List<ActorRef<InputReader.Message>> inputReaders;
	private final ActorRef<ResultCollector.Message> resultCollector;
	private final ActorRef<LargeMessageProxy.Message> largeMessageProxy;

	private final List<ActorRef<DependencyWorker.Message>> dependencyWorkers;

	private int currentWorkerIndex = 0;

	////////////////////
	// Actor Behavior //
	////////////////////

	@Override
	public Receive<Message> createReceive() {
		return newReceiveBuilder()
				.onMessage(StartMessage.class, this::handle)
				.onMessage(BatchMessage.class, this::handle)
				.onMessage(HeaderMessage.class, this::handle)
				.onMessage(RegistrationMessage.class, this::handle)
				.onMessage(CompletionMessage.class, this::handle)
				.onSignal(Terminated.class, this::handle)
				.build();
	}

	private Behavior<Message> handle(StartMessage message) {
		for (ActorRef<InputReader.Message> inputReader : this.inputReaders)
			inputReader.tell(new InputReader.ReadHeaderMessage(this.getContext().getSelf()));
		for (ActorRef<InputReader.Message> inputReader : this.inputReaders)
			inputReader.tell(new InputReader.ReadBatchMessage(this.getContext().getSelf()));
		this.startTime = System.currentTimeMillis();
		return this;
	}

	private Behavior<Message> handle(HeaderMessage message) {
		this.headerLines[message.getId()] = message.getHeader();

		this.finishedHeaders[message.getId()] = true;
		checkAndStartTaskDistribution();
		return this;
	}

	private static boolean areAllTrue(boolean[] array)
	{
		for(boolean b : array) if(!b) return false;
		return true;
	}

	private boolean allHeadersReceived() {
		return Arrays.stream(this.headerLines).noneMatch(Objects::isNull);
	}

	private Behavior<Message> handle(BatchMessage message) {
		// Ignoring batch content for now ... but I could do so much with it.

		List<List<String>> table = this.data.get(message.getId());
		table.addAll(message.getBatch().stream().map(Arrays::asList).collect(Collectors.toList()));

		if (!message.getBatch().isEmpty())
			this.inputReaders.get(message.getId()).tell(new InputReader.ReadBatchMessage(this.getContext().getSelf()));
		else {
			this.finishedLoading[message.getId()] = true;
			this.getContext().getLog().info("Finished reading table {}", message.getId());
		}

		checkAndStartTaskDistribution();
		return this;
	}

	private Behavior<Message> handle(RegistrationMessage message) {
		ActorRef<DependencyWorker.Message> dependencyWorker = message.getDependencyWorker();
		if (!this.dependencyWorkers.contains(dependencyWorker)) {
			this.dependencyWorkers.add(dependencyWorker);
			this.getContext().watch(dependencyWorker);
			// The worker should get some work ... let me send her something before I figure out what I actually want from her.
			// I probably need to idle the worker for a while, if I do not have work for it right now ... (see master/worker pattern)

			//dependencyWorker.tell(new DependencyWorker.TaskMessage(this.largeMessageProxy,0,0,null,0,0, null,42));
		}
		return this;
	}

	private Behavior<Message> handle(CompletionMessage message) throws InterruptedException {
		ActorRef<DependencyWorker.Message> dependencyWorker = message.getDependencyWorker();
		// If this was a reasonable result, I would probably do something with it and potentially generate more work ... for now, let's just generate a random, binary IND.
		if (message.getResult() == -1) {
			sleep(1000);
		}
		else{

		}
		if (this.headerLines[0] != null) {
			List<InclusionDependency> inds = generateDependencies(message);
			this.resultCollector.tell(new ResultCollector.ResultMessage(inds));
		}
		// I still don't know what task the worker could help me to solve ... but let me keep her busy.
		// Once I found all unary INDs, I could check if this.discoverNaryDependencies is set to true and try to detect n-ary INDs as well!

		//dependencyWorker.tell(new DependencyWorker.TaskMessage(this.largeMessageProxy,0,0,null,0,0, null,42));

		// At some point, I am done with the discovery. That is when I should call my end method. Because I do not work on a completable task yet, I simply call it after some time.
		if (System.currentTimeMillis() - this.startTime > 2000000)
			this.end();
		return this;
	}

	private void checkAndStartTaskDistribution() {
		if (areAllTrue(this.finishedHeaders) && areAllTrue(this.finishedLoading)) {
			getContext().getLog().info("All headers and batches loaded, starting task distribution.");
			generateAndDistributeUnaryTasks();
		}
	}

	private List<InclusionDependency> generateDependencies(CompletionMessage message) {
		File dependentFile = this.inputFiles[message.firstTableIndex];
		File referencedFile = this.inputFiles[message.secondTableIndex];
		String dependentAttribute = this.headerLines[message.firstTableIndex][message.firstColumnIndex];
		String referencedAttribute = this.headerLines[message.secondTableIndex][message.secondColumnIndex];
		InclusionDependency ind = new InclusionDependency(dependentFile, new String[]{dependentAttribute}, referencedFile, new String[]{referencedAttribute});
		List<InclusionDependency> inds = new ArrayList<>(1);
		inds.add(ind);
		return inds;
	}

	private void end() {
		this.resultCollector.tell(new ResultCollector.FinalizeMessage());
		long discoveryTime = System.currentTimeMillis() - this.startTime;
		this.getContext().getLog().info("Finished mining within {} ms!", discoveryTime);
	}

	private Behavior<Message> handle(Terminated signal) {
		ActorRef<DependencyWorker.Message> dependencyWorker = signal.getRef().unsafeUpcast();
		this.dependencyWorkers.remove(dependencyWorker);
		return this;
	}

	private ActorRef<DependencyWorker.Message> selectWorker() {
		if (dependencyWorkers.isEmpty()) {
			return null; // No workers available
		}
		ActorRef<DependencyWorker.Message> selectedWorker = dependencyWorkers.get(currentWorkerIndex);

		// Update the index for the next selection
		currentWorkerIndex = (currentWorkerIndex + 1) % dependencyWorkers.size();
		this.getContext().getLog().info("Sent work to worker:", currentWorkerIndex);
		return selectedWorker;
	}

	private void generateAndDistributeUnaryTasks() {
		for (int i = 0; i < headerLines.length; i++) {
			for (int j = 0; j < headerLines[i].length; j++) {
				for (int k = 0; k < headerLines.length; k++) {
					for (int l = 0; l < headerLines[k].length; l++) {
						if (!(i == k && j == l)) {
							taskQueue.add(new CandidatePair(i, j, k, l));
						}
					}
				}
			}
		}
		distributeNextTask();
	}

	private List<String> getColumnData(List<List<String>> tableData, int columnIndex) {
		return tableData.stream().map(row -> row.get(columnIndex)).collect(Collectors.toList());
	}
	private void distributeNextTask() {
		int counter = 0;
		while (!taskQueue.isEmpty() && !dependencyWorkers.isEmpty()) {
			CandidatePair pair = taskQueue.poll();
			if (pair != null) {
				ActorRef<DependencyWorker.Message> worker = selectWorker();
				if (worker != null) {
					DependencyWorker.TaskMessage task = new DependencyWorker.TaskMessage(
							largeMessageProxy,
							pair.getFirstTableIndex(),
							pair.getFirstColumnIndex(),
							getColumnData(data.get(pair.getFirstTableIndex()), pair.getFirstColumnIndex()),
							pair.getSecondTableIndex(),
							pair.getSecondColumnIndex(),
							getColumnData(data.get(pair.getSecondTableIndex()), pair.getSecondColumnIndex()),
							counter
							// Task-specific information
					);
					worker.tell(task);
					counter++;
				}
			}
		}
	}
}