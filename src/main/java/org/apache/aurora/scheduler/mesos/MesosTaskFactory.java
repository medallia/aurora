/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.aurora.scheduler.mesos;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.protobuf.ByteString;
import org.apache.aurora.GuavaUtils;
import org.apache.aurora.Protobufs;
import org.apache.aurora.codec.ThriftBinaryCodec;
import org.apache.aurora.scheduler.TierManager;
import org.apache.aurora.scheduler.base.JobKeys;
import org.apache.aurora.scheduler.base.SchedulerException;
import org.apache.aurora.scheduler.base.Tasks;
import org.apache.aurora.scheduler.configuration.InstanceVariablesSubstitutor;
import org.apache.aurora.scheduler.configuration.executor.ExecutorSettings;
import org.apache.aurora.scheduler.resources.AcceptedOffer;
import org.apache.aurora.scheduler.resources.ResourceSlot;
import org.apache.aurora.scheduler.resources.Resources;
import org.apache.aurora.scheduler.storage.entities.*;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * A factory to create mesos task objects.
 */
public interface MesosTaskFactory {

  /**
   * Creates a mesos task object.
   *
   * @param task Assigned task to translate into a task object.
   * @param offer Resource offer the task is being assigned to.
   * @return A new task.
   * @throws SchedulerException If the task could not be encoded.
   */
  TaskInfo createFrom(IAssignedTask task, Offer offer) throws SchedulerException;

  // TODO(wfarner): Move this class to its own file to reduce visibility to package private.
  class MesosTaskFactoryImpl implements MesosTaskFactory {
    private static final Logger LOG = LoggerFactory.getLogger(MesosTaskFactoryImpl.class);
    private static final String EXECUTOR_PREFIX = "thermos-";

    @VisibleForTesting
    static final String METADATA_LABEL_PREFIX = "org.apache.aurora.metadata.";

    @VisibleForTesting
    static final String DEFAULT_PORT_PROTOCOL = "TCP";

    private final ExecutorSettings executorSettings;
    private final TierManager tierManager;
    private final IServerInfo serverInfo;

    @Inject
    MesosTaskFactoryImpl(
        ExecutorSettings executorSettings,
        TierManager tierManager,
        IServerInfo serverInfo) {

      this.executorSettings = requireNonNull(executorSettings);
      this.tierManager = requireNonNull(tierManager);
      this.serverInfo = requireNonNull(serverInfo);
    }

    @VisibleForTesting
    static ExecutorID getExecutorId(String taskId) {
      return ExecutorID.newBuilder().setValue(EXECUTOR_PREFIX + taskId).build();
    }

    private static String getJobSourceName(IJobKey jobkey) {
      return String.format("%s.%s.%s", jobkey.getRole(), jobkey.getEnvironment(), jobkey.getName());
    }

    private static String getJobSourceName(ITaskConfig task) {
      return getJobSourceName(task.getJob());
    }

    @VisibleForTesting
    static String getInstanceSourceName(ITaskConfig task, int instanceId) {
      return String.format("%s.%s", getJobSourceName(task), instanceId);
    }

    @VisibleForTesting
    static String getInverseJobSourceName(IJobKey job) {
      return String.format("%s.%s.%s", job.getName(), job.getEnvironment(), job.getRole());
    }

    private static byte[] serializeTask(IAssignedTask task) throws SchedulerException {
      try {
        return ThriftBinaryCodec.encode(task.newBuilder());
      } catch (ThriftBinaryCodec.CodingException e) {
        LOG.error("Unable to serialize task.", e);
        throw new SchedulerException("Internal error.", e);
      }
    }

    @Override
    public TaskInfo createFrom(IAssignedTask task, Offer offer) throws SchedulerException {
      requireNonNull(task);
      requireNonNull(offer);

      ITaskConfig config = task.getTask();
      AcceptedOffer acceptedOffer;
      // TODO(wfarner): Re-evaluate if/why we need to continue handling unset assignedPorts field.
      try {
        acceptedOffer = AcceptedOffer.create(
            offer,
            ResourceSlot.from(config),
            executorSettings.getExecutorOverhead(),
            ImmutableSet.copyOf(task.getAssignedPorts().values()),
            tierManager.getTier(task.getTask()));
      } catch (Resources.InsufficientResourcesException e) {
        throw new SchedulerException(e);
      }
      List<Resource> resources = acceptedOffer.getTaskResources();

      LOG.debug(
          "Setting task resources to {}",
          Iterables.transform(resources, Protobufs::toString));

      TaskInfo.Builder taskBuilder = TaskInfo.newBuilder()
          .setName(JobKeys.canonicalString(Tasks.getJob(task)))
          .setTaskId(TaskID.newBuilder().setValue(task.getTaskId()))
          .setSlaveId(offer.getSlaveId())
          .addAllResources(resources);

      configureTaskLabels(config.getMetadata(), taskBuilder);

      if (executorSettings.shouldPopulateDiscoverInfo()) {
        configureDiscoveryInfos(task, taskBuilder);
      }

      if (config.getContainer().isSetMesos()) {
        configureTaskForNoContainer(task, taskBuilder, acceptedOffer);
      } else if (config.getContainer().isSetDocker()) {
        configureTaskForDockerContainer(task, config, taskBuilder, acceptedOffer);
        return taskBuilder.build();
      } else {
        throw new SchedulerException("Task had no supported container set.");
      }

      if (taskBuilder.hasExecutor()) {
        taskBuilder.setData(ByteString.copyFrom(serializeTask(task)));
        return ResourceSlot.matchResourceTypes(taskBuilder.build());
      } else {
        return taskBuilder.build();
      }
    }

    private void configureTaskForNoContainer(
        IAssignedTask task,
        TaskInfo.Builder taskBuilder,
        AcceptedOffer acceptedOffer) {

      taskBuilder.setExecutor(configureTaskForExecutor(task, acceptedOffer).build());
    }

    private ContainerInfo getDockerContainerInfo(IDockerContainer config) {
        Iterable<Protos.Parameter> parameters = Iterables.transform(config.getParameters(),
                item -> Protos.Parameter.newBuilder().setKey(item.getName())
                        .setValue(item.getValue()).build());

        ContainerInfo.DockerInfo.Builder dockerBuilder = ContainerInfo.DockerInfo.newBuilder()
                .setImage(config.getImage()).addAllParameters(parameters);
        return ContainerInfo.newBuilder()
                .setType(ContainerInfo.Type.DOCKER)
                .setDocker(dockerBuilder.build())
                .addAllVolumes(executorSettings.getExecutorConfig().getVolumeMounts())
                .build();
    }

    private void configureTaskForDockerContainer(
        IAssignedTask task,
        ITaskConfig taskConfig,
        TaskInfo.Builder taskBuilder,
        AcceptedOffer acceptedOffer) {
      LOG.info("Setting DOCKER Task. {}", taskConfig.getExecutorConfig().getData());
      LOG.info("Instance. {}", taskConfig.getInstances());

      // build variable substitutor.
      InstanceVariablesSubstitutor instanceVariablesSubstitutor = InstanceVariablesSubstitutor.getInstance(task.getTask(),
              task.getInstanceId());

      IDockerContainer config = taskConfig.getContainer().getDocker();
      Iterable<Protos.Parameter> parameters = instanceVariablesSubstitutor.getDockerParameters();

      ContainerInfo.DockerInfo.Builder dockerBuilder = ContainerInfo.DockerInfo.newBuilder()
          .setImage(config.getImage()).addAllParameters(parameters);
      ContainerInfo.Builder containerBuilder = ContainerInfo.newBuilder()
          .setType(ContainerInfo.Type.DOCKER)
          .setDocker(dockerBuilder.build());

      Protos.Environment.Builder envBuilder = Protos.Environment.newBuilder();
      envBuilder.addVariables(Protos.Environment.Variable.newBuilder().setName("AURORA_TASK_ID")
              .setValue(task.getTaskId()).build());
      envBuilder.addVariables(Protos.Environment.Variable.newBuilder().setName("AURORA_TASK_INSTANCE")
              .setValue(Integer.toString(task.getInstanceId())).build());
      envBuilder.addVariables(Protos.Environment.Variable.newBuilder().setName("AURORA_JOB_NAME")
              .setValue(task.getTask().getJob().getName()).build());

      taskBuilder.setContainer(containerBuilder.build());

      CommandInfo.Builder cmd = CommandInfo.newBuilder();
      String command = instanceVariablesSubstitutor.getCmdLine();
      LOG.info("Using CMD: {}", command);
      cmd.addUris(CommandInfo.URI.newBuilder().setValue("file:///root/.dockercfg").build());
      if (!Strings.isNullOrEmpty(command)) {
        cmd.setValue(command).setShell(true);
      } else {
        cmd.setShell(false); // if no cmdline present, just run the docker entrypoint.
      }
      cmd.setEnvironment(envBuilder.build());
      taskBuilder.setCommand(cmd.build());
    }

   private ExecutorInfo.Builder configureTaskForExecutor(
        IAssignedTask task,
        AcceptedOffer acceptedOffer) {

      ExecutorInfo.Builder builder = executorSettings.getExecutorConfig().getExecutor().toBuilder()
          .setExecutorId(getExecutorId(task.getTaskId()))
          .setSource(getInstanceSourceName(task.getTask(), task.getInstanceId()));
      List<Resource> executorResources = acceptedOffer.getExecutorResources();
      LOG.debug(
          "Setting executor resources to {}",
          Iterables.transform(executorResources, Protobufs::toString));
      builder.clearResources().addAllResources(executorResources);
      return builder;
    }

    private void configureTaskLabels(Set<IMetadata> metadata, TaskInfo.Builder taskBuilder) {
      ImmutableSet<Label> labels = metadata.stream()
          .map(m -> Label.newBuilder()
              .setKey(METADATA_LABEL_PREFIX + m.getKey())
              .setValue(m.getValue())
              .build())
          .collect(GuavaUtils.toImmutableSet());

      if (!labels.isEmpty()) {
        taskBuilder.setLabels(Labels.newBuilder().addAllLabels(labels));
      }
    }

    private void configureDiscoveryInfos(IAssignedTask task, TaskInfo.Builder taskBuilder) {
      DiscoveryInfo.Builder builder = taskBuilder.getDiscoveryBuilder();
      builder.setVisibility(DiscoveryInfo.Visibility.CLUSTER);
      builder.setName(getInverseJobSourceName(task.getTask().getJob()));
      builder.setEnvironment(task.getTask().getJob().getEnvironment());
      // A good sane choice for default location is current Aurora cluster name.
      builder.setLocation(serverInfo.getClusterName());
      for (Map.Entry<String, Integer> entry : task.getAssignedPorts().entrySet()) {
        builder.getPortsBuilder().addPorts(
            Port.newBuilder()
                .setName(entry.getKey())
                .setNumber(entry.getValue())
                .setProtocol(DEFAULT_PORT_PROTOCOL)
        );
      }
    }
  }
}
