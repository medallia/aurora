package org.apache.aurora.scheduler.state;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import org.apache.aurora.common.stats.StatsProvider;
import org.apache.aurora.scheduler.HostOffer;
import org.apache.aurora.scheduler.TierInfo;
import org.apache.aurora.scheduler.TierManager;
import org.apache.aurora.scheduler.base.TaskGroupKey;
import org.apache.aurora.scheduler.filter.SchedulingFilter;
import org.apache.aurora.scheduler.filter.SchedulingFilter.ResourceRequest;
import org.apache.aurora.scheduler.filter.SchedulingFilter.UnusedResource;
import org.apache.aurora.scheduler.filter.SchedulingFilter.Veto;
import org.apache.aurora.scheduler.filter.SchedulingFilter.VetoGroup;
import org.apache.aurora.scheduler.mesos.MesosTaskFactory;
import org.apache.aurora.scheduler.offers.OfferManager;
import org.apache.aurora.scheduler.state.TaskAssigner.FirstFitTaskAssigner;
import org.apache.aurora.scheduler.storage.Storage.MutableStoreProvider;
import org.apache.aurora.scheduler.storage.entities.IAssignedTask;
import org.apache.aurora.scheduler.updater.UpdateAgentReserver;
import org.apache.mesos.v1.Protos.Offer;
import org.apache.mesos.v1.Protos.TaskInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

import static org.apache.aurora.gen.ScheduleStatus.LOST;
import static org.apache.aurora.gen.ScheduleStatus.PENDING;

/** Custom task assigner. */
@SuppressWarnings("Duplicates") 
public class MauricioTaskAssigner extends FirstFitTaskAssigner {
	
    private static final Logger LOG = LoggerFactory.getLogger(MauricioTaskAssigner.class);
	
	@Inject public MauricioTaskAssigner(StateManager stateManager, SchedulingFilter filter, MesosTaskFactory taskFactory, OfferManager offerManager, TierManager tierManager, UpdateAgentReserver updateAgentReserver, StatsProvider statsProvider) {
		super(stateManager, filter, taskFactory, offerManager, tierManager, updateAgentReserver, statsProvider);
	}
	
	@Override public Set<String> maybeAssign(MutableStoreProvider storeProvider, ResourceRequest resourceRequest, TaskGroupKey groupKey, Iterable<IAssignedTask> tasks, Map<String, TaskGroupKey> preemptionReservations) {
		Set<String> strings = super.maybeAssign(storeProvider, resourceRequest, groupKey, tasks, preemptionReservations);
		return strings;
	}

	@Override IAssignedTask mapAndAssignResources(Offer offer, IAssignedTask task) {
		IAssignedTask iAssignedTask = super.mapAndAssignResources(offer, task);
		return iAssignedTask;
	}
	
	protected boolean evaluateOffer(
        MutableStoreProvider storeProvider,
        TierInfo tierInfo,
        ResourceRequest resourceRequest,
        TaskGroupKey groupKey,
        IAssignedTask task,
        HostOffer offer,
        ImmutableSet.Builder<String> assignmentResult) throws OfferManager.LaunchException {

      String taskId = task.getTaskId();
      Set<Veto> vetoes = filter.filter(
          new UnusedResource(offer.getResourceBag(tierInfo), offer.getAttributes(), offer.getUnavailabilityStart()),
          resourceRequest);
      LOG.debug("Vetoes for %s: %s", taskId, vetoes);
      if (vetoes.isEmpty()) {
        TaskInfo taskInfo = assign(storeProvider, offer.getOffer(), taskId);
        resourceRequest.getJobState().updateAttributeAggregate(offer.getAttributes());

        try {
          offerManager.launchTask(offer.getOffer().getId(), taskInfo);
          assignmentResult.add(taskId);
          return true;
        } catch (OfferManager.LaunchException e) {
          LOG.warn("Failed to launch task.", e);
          launchFailures.incrementAndGet();
          // The attempt to schedule the task failed, so we need to backpedal on the
          // assignment.
          // It is in the LOST state and a new task will move to PENDING to replace it.
          // Should the state change fail due to storage issues, that's okay.  The task will
          // time out in the ASSIGNED state and be moved to LOST.
          stateManager.changeState(storeProvider, taskId, Optional.of(PENDING), LOST, LAUNCH_FAILED_MSG);
          throw e;
        }
      } else {
        if (Veto.identifyGroup(vetoes) == VetoGroup.STATIC) {
          // Never attempt to match this offer/groupKey pair again.
          offerManager.banOffer(offer.getOffer().getId(), groupKey);
        }
        LOG.debug("Agent {} vetoed task {}: {}", offer.getOffer().getHostname(), taskId, vetoes);
      }
      return false;
    }


}
