package org.apache.aurora.scheduler.configuration;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import org.apache.aurora.scheduler.storage.entities.IDockerContainer;
import org.apache.aurora.scheduler.storage.entities.IInstance;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;
import org.apache.aurora.scheduler.storage.entities.IVariable;
import org.apache.commons.lang3.text.StrLookup;
import org.apache.commons.lang3.text.StrSubstitutor;
import org.apache.mesos.Protos;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Creates a decorator for a given assigned task. */
public class InstanceVariablesSubstitutor {

  private static final String PREFIX = "#{";
  private static final String SUFFIX = "}";
  private static final char ESCAPE = '#';
  private static final Logger LOG = LoggerFactory.getLogger(InstanceVariablesSubstitutor.class);

  private final StrSubstitutor substitutor;
  private final ITaskConfig task;

  public static InstanceVariablesSubstitutor getInstance(ITaskConfig taskConfig, int instanceID) {
    LOG.info("## Task Instances: {}", taskConfig.getInstances());
    if (taskConfig.getInstances().isEmpty()) {
      return new InstanceVariablesSubstitutor(taskConfig, new StrSubstitutor(StrLookup.noneLookup()));
    } else {
      IInstance instance = taskConfig.getInstances().get(instanceID);
      Map<String, String> variables = instance.getVariables().stream().collect(Collectors.toMap(IVariable::getName,
              IVariable::getValue));

      StrSubstitutor substitutor = new StrSubstitutor(new StrLookup<String>() {
        @Override
        public String lookup(String key) {
          if (variables.containsKey(key)) {
            return variables.get(key);
          } else {
            throw new IllegalArgumentException(String.format("The variable '%s' is not present in the task variables %s",
                    key, variables));
          }
        }
      }, PREFIX, SUFFIX, ESCAPE);
      return new InstanceVariablesSubstitutor(taskConfig, substitutor);
    }
  }
  
  private InstanceVariablesSubstitutor(ITaskConfig task, StrSubstitutor substitutor) {
    this.task = task;
    this.substitutor = substitutor;
  }
  
  /** 
   * @throws org.apache.aurora.scheduler.configuration.ConfigurationManager.TaskDescriptionException if not all
   * the instances contains {@link IVariable}s for the specified in the docker parameters or cmdline. 
   */
  public static boolean validate(final ITaskConfig taskConfig) throws ConfigurationManager.TaskDescriptionException {
    
    final List<Set<String>> variablesPerInstance = Lists.newArrayList();
    for (IInstance instance : taskConfig.getInstances()) {
      Set<String> instanceVariables = instance.getVariables().stream().map(IVariable::getName).collect(Collectors.toSet());
      variablesPerInstance.add(instanceVariables);
    }
    
    InstanceVariablesSubstitutor in = new InstanceVariablesSubstitutor(taskConfig, new StrSubstitutor(new StrLookup<String>() {
      @Override public String lookup(String key) {
        if (variablesPerInstance.isEmpty()) {
          throw new InstanceVariablesSubstitutorException("No Instances were provided in the job definition but " +
                  String.format("the variable '%s' was specified.", key));
        }
        for (int i = 0; i < variablesPerInstance.size(); i++) {
          if (!variablesPerInstance.get(i).contains(key)) {
            throw new InstanceVariablesSubstitutorException(
              String.format("Instance %s doesn't contain a value for the variable named '%s'", i, key));
          }
        }
        return null;
      }
    }, PREFIX, SUFFIX, ESCAPE));
    try {
      LOG.info("Validating Job {}. {}", taskConfig.getJob().getName(), variablesPerInstance);
      if (taskConfig.getContainer().isSetDocker()) {
        in.getDockerParameters();
        in.getCmdLine();
      }
      return true;
    } catch (InstanceVariablesSubstitutorException e) {
      throw new ConfigurationManager.TaskDescriptionException(e.getMessage());
    }
  }
  
  private static class InstanceVariablesSubstitutorException extends RuntimeException {
    public InstanceVariablesSubstitutorException(String msg) {
      super(msg);
    }
  }

  @VisibleForTesting
  public String replaceRawString(String rawValue) {
    return substitutor.replace(rawValue);
  }
  
  /** @return a list of {@link org.apache.mesos.Protos.Parameter} with the values interpolated. */
  public Iterable<Protos.Parameter> getDockerParameters() {
    IDockerContainer config = this.task.getContainer().getDocker();
    return config.getParameters().stream().map(
      item -> Protos.Parameter.newBuilder().setKey(item.getName()).setValue(replaceRawString
              (item.getValue())).build()).collect(Collectors.toList());
  }

  /** @return The interpolated cmdLine used in the first process the {@link ITaskConfig} or null if it can't be extracted. */
  public String getCmdLine() {
    JsonFactory factory = new JsonFactory(); 
    ObjectMapper mapper = new ObjectMapper(factory); 
    TypeReference<HashMap<String,Object>> typeRef = new TypeReference<HashMap<String,Object>>() {};
    if (this.task.getExecutorConfig() == null) {
      return null;
    }
    try {
      HashMap<String,Object> o = mapper.readValue(this.task.getExecutorConfig().getData(), typeRef);
      @SuppressWarnings("unchecked")
      Map<String, Object> task = (Map<String, Object>) ((List) ((HashMap) o.get("task")).get("processes")).get(0);
      String cmdLine = (String) task.get("cmdline");
      return replaceRawString(cmdLine);
    } catch (IOException e) {
      LOG.error("Invalid Task Executor Config", e);
      return null;
    }
  }
}
