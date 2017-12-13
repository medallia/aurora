package org.apache.aurora.scheduler.mesos;


import com.google.common.collect.ImmutableList;
import org.apache.aurora.gen.Label;
import org.apache.aurora.scheduler.configuration.InstanceVariablesSubstitutor;
import org.apache.aurora.scheduler.storage.entities.*;
import org.apache.mesos.v1.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MesosContainerTask {
  private static final Logger LOG = LoggerFactory.getLogger(MesosContainerTask.class);

  public static void configureTask(IAssignedTask task,
                                   ITaskConfig taskConfig,
                                   Protos.TaskInfo.Builder taskBuilder) {
    LOG.info("About to launch a Mesos container!");

    InstanceVariablesSubstitutor instanceVariablesSubstitutor = InstanceVariablesSubstitutor.getInstance(task.getTask(),
            task.getInstanceId());

    IMesosContainer mesosContainer = taskConfig.getContainer().getMesos();
    ImmutableList<ILabel> labels = mesosContainer.getLabels();
    
    if (labels.isEmpty()) {
      LOG.info("Labels map is empty :(");
    }
    labels.forEach((label) -> LOG.info("Found a mesos label!!!"));

    Protos.Image.Builder imageBuilder = Protos.Image.newBuilder();
    IDockerImage dockerImage = mesosContainer.getImage().getDocker();

    imageBuilder.setType(Protos.Image.Type.DOCKER);
    imageBuilder.setDocker(Protos.Image.Docker.newBuilder()
            .setName(dockerImage.getName() + ":" + dockerImage.getTag()));


    Protos.ContainerInfo.MesosInfo.Builder mesosContainerBuilder = Protos.ContainerInfo.MesosInfo.newBuilder();

    Protos.NetworkInfo.Builder networkInfoBuilder = Protos.NetworkInfo.newBuilder()
            .setName("test")
            .addIpAddresses(Protos.NetworkInfo.IPAddress.newBuilder().setIpAddress("10.15.20.5").build())
            .addPortMappings(Protos.NetworkInfo.PortMapping.newBuilder().setHostPort(8000).setContainerPort(5000).build())
            .addGroups("A group")
            .addGroups("Other group")
            .setLabels(Protos.Labels.newBuilder()
                    .addLabels(Protos.Label.newBuilder().setKey("key").setValue("value").build())
                    .addLabels(Protos.Label.newBuilder().setKey("clave").setValue("valor")).build());

    Protos.ContainerInfo.Builder containerInfo = Protos.ContainerInfo.newBuilder()
            .setType(Protos.ContainerInfo.Type.MESOS)
            .setMesos(mesosContainerBuilder)
            .addNetworkInfos(networkInfoBuilder);

    taskBuilder.setContainer(containerInfo.build());


    Protos.CommandInfo.Builder cmd = Protos.CommandInfo.newBuilder();
    String command = instanceVariablesSubstitutor.getCmdLine();
    LOG.info("(Mesos) Using CMD: {}", command);
    cmd.setValue(command).setShell(true);
    taskBuilder.setCommand(cmd.build());

    if (taskBuilder.hasExecutor()) {
      LOG.info("taskBuilder executor is: {}", taskBuilder.getExecutor().getName());
    } else {
      LOG.info("taskBuilder with no executor?!?!");
    }
  }


}
