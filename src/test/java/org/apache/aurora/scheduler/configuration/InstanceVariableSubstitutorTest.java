package org.apache.aurora.scheduler.configuration;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.apache.aurora.gen.*;
import org.apache.aurora.scheduler.storage.entities.IAssignedTask;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Tests for {@link InstanceVariablesSubstitutor} */
public class InstanceVariableSubstitutorTest {
  
  @Test
  public void testSubstitution() {
    TaskConfig taskConfig = ITaskConfig.build(getTaskConfig()).newBuilder();
    IAssignedTask assignedTask = IAssignedTask.build(new AssignedTask().setInstanceId(1).setTask(taskConfig));
    InstanceVariablesSubstitutor rep = InstanceVariablesSubstitutor.getInstance(assignedTask.getTask(), assignedTask.getInstanceId());
    assertEquals("192.168.1.21", rep.replaceRawString("#{ip}"));
    assertEquals("#{ip}", rep.replaceRawString("##{ip}"));
    assertEquals("# 192.168.1.21", rep.replaceRawString("# #{ip}"));
    assertNull(rep.getCmdLine());
  }
  
  @Test (expected=ConfigurationManager.TaskDescriptionException.class)
  public void testMissingInstanceValue() throws ConfigurationManager.TaskDescriptionException {
    TaskConfig taskConfig = getTaskConfig();
    List<Variable> variables = taskConfig.getInstances().get(1).getVariables();
    variables = variables.stream().limit(1).collect(Collectors.toList());
    taskConfig.getInstances().get(1).setVariables(variables);
    TaskConfig wrapped = ITaskConfig.build(taskConfig).newBuilder();
    IAssignedTask assignedTask = IAssignedTask.build(new AssignedTask().setInstanceId(2).setTask(wrapped));
    InstanceVariablesSubstitutor.validate(assignedTask.getTask());
  }

  @Test (expected=ConfigurationManager.TaskDescriptionException.class)
  public void testMissingInstances() throws ConfigurationManager.TaskDescriptionException {
    TaskConfig wrapped = ITaskConfig.build(getTaskConfig()).newBuilder();
    wrapped.setInstances(ImmutableList.of());
    IAssignedTask assignedTask = IAssignedTask.build(new AssignedTask().setInstanceId(2).setTask(wrapped));
    assertTrue(InstanceVariablesSubstitutor.validate(assignedTask.getTask()));
  }
  
  @Test
  public void testValidMultipleInstances() throws ConfigurationManager.TaskDescriptionException {
    TaskConfig wrapped = ITaskConfig.build(getTaskConfig()).newBuilder();
    wrapped.getContainer().getDocker().setParameters(ImmutableList.of(new DockerParameter("foo", "bar")));
    wrapped.setInstances(ImmutableList.of());
    IAssignedTask assignedTask = IAssignedTask.build(new AssignedTask().setInstanceId(2).setTask(wrapped));
    assertTrue(InstanceVariablesSubstitutor.validate(assignedTask.getTask()));
  }

  @Test
  public void testValid() throws ConfigurationManager.TaskDescriptionException {
    TaskConfig wrapped = ITaskConfig.build(getTaskConfig()).newBuilder();
    IAssignedTask assignedTask = IAssignedTask.build(new AssignedTask().setInstanceId(2).setTask(wrapped));
    assertTrue(InstanceVariablesSubstitutor.validate(assignedTask.getTask()));
  }
  
  @Test (expected=ConfigurationManager.TaskDescriptionException.class)
  public void testMissingVariableTrailingWhitespace() throws ConfigurationManager.TaskDescriptionException {
    testConfigurationWithVariables("ip ");
  }

  @Test (expected=ConfigurationManager.TaskDescriptionException.class)
  public void testMissingVariableLeadingWhitespace() throws ConfigurationManager.TaskDescriptionException {
    testConfigurationWithVariables(" ip");
  }

  @Test (expected=ConfigurationManager.TaskDescriptionException.class)
  public void testMissingVariable() throws ConfigurationManager.TaskDescriptionException {
    testConfigurationWithVariables("missing");
  }
  /** validates a configuration with arbitrary variable names */
  private void testConfigurationWithVariables(String... variablesToInterpolate) throws ConfigurationManager.TaskDescriptionException {
    TaskConfig taskConfig = getTaskConfig();
    List<DockerParameter> parameters = Lists.newArrayList(taskConfig.getContainer().getDocker().getParameters());
    for (String v: variablesToInterpolate) {
      parameters.add(new DockerParameter("label", String.format("#{%s}", v)));  
    }
    taskConfig.getContainer().getDocker().setParameters(parameters);
    TaskConfig wrapped = ITaskConfig.build(taskConfig).newBuilder();
    IAssignedTask assignedTask = IAssignedTask.build(new AssignedTask().setInstanceId(2).setTask(wrapped));
    InstanceVariablesSubstitutor.validate(assignedTask.getTask());
  }

  private TaskConfig getTaskConfig() {
    return new TaskConfig()
            .setJob(new JobKey("Medallia","devel","container-test"))
            .setInstances(ImmutableList.of(
                    new Instance(ImmutableList.of(new Variable("ip", "192.168.1.20"), new Variable("vol", "X"))),
                    new Instance(ImmutableList.of(new Variable("ip", "192.168.1.21"), new Variable("vol", "Y"))),
                    new Instance(ImmutableList.of(new Variable("ip", "192.168.1.22"), new Variable("vol", "Z")))
            ))
            .setContainer(Container.docker(
                    new DockerContainer("testimage")
                            .setParameters(ImmutableList.of(
                                    new DockerParameter("ip-address", "#{ip}"),
                                    new DockerParameter("volume", "#{vol}")))
            ));
  }
  
}
