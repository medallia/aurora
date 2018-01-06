package org.apache.aurora.scheduler.mesos;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import org.apache.aurora.scheduler.configuration.InstanceVariablesSubstitutor;
import org.apache.aurora.scheduler.storage.entities.IAssignedTask;
import org.apache.aurora.scheduler.storage.entities.IDockerContainer;
import org.apache.aurora.scheduler.storage.entities.IHealthCheck;
import org.apache.aurora.scheduler.storage.entities.IServerInfo;
import org.apache.mesos.v1.Protos;
import org.apache.mesos.v1.Protos.CommandInfo;
import org.apache.mesos.v1.Protos.ContainerInfo;
import org.apache.mesos.v1.Protos.DurationInfo;
import org.apache.mesos.v1.Protos.HealthCheck;
import org.apache.mesos.v1.Protos.HealthCheck.HTTPCheckInfo;
import org.apache.mesos.v1.Protos.KillPolicy;
import org.apache.mesos.v1.Protos.TaskInfo.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/** Configures a Mesos task to use docker more seamlessly. */
public class DockerContainerTasks {
    private static final Logger LOG = LoggerFactory.getLogger(DockerContainerTasks.class);

	/** Configure the {@link Builder} based on the given {@link org.apache.aurora.scheduler.storage.entities.IAssignedTask */
	public static void configureTask(
			IAssignedTask task,
			Builder taskBuilder,
			IServerInfo serverInfo) {
		LOG.info("Setting DOCKER Task. {}. Instances {}", task.getTask().getExecutorConfig().getData(), 
				task.getTask().getInstances());

		// build variable substitutor.
		InstanceVariablesSubstitutor instanceVariablesSubstitutor = InstanceVariablesSubstitutor.getInstance(task.getTask(),
				task.getInstanceId());

		IDockerContainer config = task.getTask().getContainer().getDocker();
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
		if (task.getTask().isSetKillPolicy()) {
			long killGracePeriodNanos = Duration.ofSeconds(task.getTask().getKillPolicy().getGracePeriodSecs()).toNanos();
			KillPolicy.Builder killPolicyBuilder = KillPolicy.newBuilder()
					.setGracePeriod(DurationInfo.newBuilder().setNanoseconds(killGracePeriodNanos).build());
			taskBuilder.setKillPolicy(killPolicyBuilder.build());
		}
		
		// set health check
		if (task.getTask().isSetHealthCheck()) {
			IHealthCheck healthCheck = task.getTask().getHealthCheck();
			HealthCheck.Builder mesosHealthCheck = HealthCheck.newBuilder()
					.setConsecutiveFailures(healthCheck.getConsecutiveFailures())
					.setDelaySeconds(healthCheck.getDelaySeconds())
					.setGracePeriodSeconds(healthCheck.getGracePeriodSeconds())
					.setIntervalSeconds(healthCheck.getIntervalSeconds())
					.setTimeoutSeconds(healthCheck.getTimeoutSeconds());
			if (healthCheck.isSetShell()) {
				mesosHealthCheck.setCommand(CommandInfo.newBuilder()
						.setValue(healthCheck.getShell().getCommand()).build());
			} else if (healthCheck.isSetHttp()) {
				mesosHealthCheck.setHttp(HTTPCheckInfo.newBuilder()
						.setPath(healthCheck.getHttp().getEndpoint())
						.setPort(healthCheck.getHttp().getPort())
						.build());
			}
			taskBuilder.setHealthCheck(mesosHealthCheck.build());
		}
	}

}
