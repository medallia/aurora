package org.apache.aurora.scheduler.mesos;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import org.apache.aurora.gen.ExecutorConfig;
import org.apache.aurora.scheduler.configuration.InstanceVariablesSubstitutor;
import org.apache.aurora.scheduler.resources.AcceptedOffer;
import org.apache.aurora.scheduler.storage.entities.IAssignedTask;
import org.apache.aurora.scheduler.storage.entities.IDockerContainer;
import org.apache.aurora.scheduler.storage.entities.IServerInfo;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;
import org.apache.mesos.v1.Protos;
import org.apache.mesos.v1.Protos.CommandInfo;
import org.apache.mesos.v1.Protos.ContainerInfo;
import org.apache.mesos.v1.Protos.DurationInfo;
import org.apache.mesos.v1.Protos.HealthCheck;
import org.apache.mesos.v1.Protos.KillPolicy;
import org.apache.mesos.v1.Protos.TaskInfo.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/** Configures a Mesos task to use docker more seamlessly. */
public class DockerContainerTasks {
    private static final Logger LOG = LoggerFactory.getLogger(DockerContainerTasks.class);

	public static void configureTask(
			IAssignedTask task,
			ITaskConfig taskConfig,
			Builder taskBuilder,
			AcceptedOffer acceptedOffer, IServerInfo serverInfo) {
		LOG.info("Setting DOCKER Task. {}", taskConfig.getExecutorConfig().getData());
		LOG.info("Instance. {}", taskConfig.getInstances());

		// build variable substitutor.
		InstanceVariablesSubstitutor instanceVariablesSubstitutor = InstanceVariablesSubstitutor.getInstance(task.getTask(),
				task.getInstanceId());

		 
				
		taskBuilder.setHealthCheck(HealthCheck.newBuilder()
				.setCommand(CommandInfo.newBuilder().setValue("curl www.google.com").build())
				.setConsecutiveFailures(3)
				.setDelaySeconds(5)
				.build());

		IDockerContainer config = taskConfig.getContainer().getDocker();
		Iterable<Protos.Parameter> parameters = instanceVariablesSubstitutor.getDockerParameters();

		ContainerInfo.DockerInfo.Builder dockerBuilder = ContainerInfo.DockerInfo.newBuilder()
				.setImage(config.getImage()).addAllParameters(parameters);
		ContainerInfo.Builder containerBuilder = ContainerInfo.newBuilder()
				.setType(ContainerInfo.Type.DOCKER)
				.setDocker(dockerBuilder.build());

		Protos.Environment.Builder envBuilder = Protos.Environment.newBuilder();

		ImmutableMap<String, String> envVariables = ImmutableMap.of(
				"AURORA_TASK_ID", task.getTaskId(),
				"AURORA_TASK_INSTANCE", Integer.toString(task.getInstanceId()),
				"AURORA_JOB_NAME", task.getTask().getJob().getName(),
				"AURORA_CLUSTER", serverInfo.getClusterName());

		envVariables.forEach((name, value) ->
				envBuilder.addVariables(Protos.Environment.Variable.newBuilder().setName(name).setValue(value).build()));

		taskBuilder.setContainer(containerBuilder.build());

		CommandInfo.Builder cmd = CommandInfo.newBuilder();
		String command = instanceVariablesSubstitutor.getCmdLine();
		LOG.info("Using CMD: {}", command);

		// add resources
		cmd.addUris(CommandInfo.URI.newBuilder().setValue("file:///root/.dockercfg").build());
		
		if (!Strings.isNullOrEmpty(command)) {
			cmd.setValue(command).setShell(true);
		} else {
			cmd.setShell(false); // if no cmdline present, just run the docker entrypoint.
		}
		cmd.setEnvironment(envBuilder.build());
		taskBuilder.setCommand(cmd.build());

		// set kill policy
		if (taskConfig.isSetKillPolicy()) {
			long killGracePeriodNanos = Duration.ofSeconds(taskConfig.getKillPolicy().getGracePeriodSecs()).toNanos();
			KillPolicy.Builder killPolicyBuilder = KillPolicy.newBuilder()
					.setGracePeriod(DurationInfo.newBuilder().setNanoseconds(killGracePeriodNanos).build());
			taskBuilder.setKillPolicy(killPolicyBuilder.build());
		}
	}

}
