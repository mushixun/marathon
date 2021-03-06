package mesosphere.marathon
package core.task.tracker.impl

import java.time.Clock
import java.util.concurrent.TimeoutException

import akka.Done
import akka.actor.ActorRef
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import mesosphere.marathon.core.instance.{Goal, Instance}
import mesosphere.marathon.core.instance.update.{InstanceUpdateEffect, InstanceUpdateOperation}
import mesosphere.marathon.core.task.tracker.{InstanceTracker, InstanceTrackerConfig}
import mesosphere.marathon.metrics.Metrics
import mesosphere.marathon.metrics.deprecated.ServiceMetric
import mesosphere.marathon.state.{PathId, Timestamp}
import org.apache.mesos

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal

/**
  * Provides a [[InstanceTracker]] interface to [[InstanceTrackerActor]].
  *
  * This is used for the "global" InstanceTracker trait and it is also
  * is used internally in this package to communicate with the InstanceTracker.
  */
private[tracker] class InstanceTrackerDelegate(
    metrics: Metrics,
    clock: Clock,
    config: InstanceTrackerConfig,
    instanceTrackerRef: ActorRef) extends InstanceTracker {

  override def instancesBySpecSync: InstanceTracker.InstancesBySpec = {
    import scala.concurrent.ExecutionContext.Implicits.global
    Await.result(instancesBySpec(), instanceTrackerQueryTimeout.duration)
  }

  override def instancesBySpec()(implicit ec: ExecutionContext): Future[InstanceTracker.InstancesBySpec] =
    oldTasksByAppTimeMetric {
      newTasksByAppTimeMetric {
        (instanceTrackerRef ? InstanceTrackerActor.List).mapTo[InstanceTracker.InstancesBySpec].recover {
          case e: AskTimeoutException =>
            throw new TimeoutException(
              "timeout while calling list. If you know what you are doing, you can adjust the timeout " +
                s"with --${config.internalTaskTrackerRequestTimeout.name}."
            )
        }
      }
    }

  // TODO(jdef) support pods when counting launched instances
  override def countActiveSpecInstances(appId: PathId): Future[Int] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    instancesBySpec().map(_.specInstances(appId).count(instance => instance.isActive || (instance.isReserved && !instance.isReservedTerminal)))
  }

  override def hasSpecInstancesSync(appId: PathId): Boolean = instancesBySpecSync.hasSpecInstances(appId)
  override def hasSpecInstances(appId: PathId)(implicit ec: ExecutionContext): Future[Boolean] =
    instancesBySpec().map(_.hasSpecInstances(appId))

  override def specInstancesSync(appId: PathId): Seq[Instance] =
    instancesBySpecSync.specInstances(appId)
  override def specInstances(appId: PathId)(implicit ec: ExecutionContext): Future[Seq[Instance]] =
    instancesBySpec().map(_.specInstances(appId))

  override def instance(taskId: Instance.Id): Future[Option[Instance]] =
    (instanceTrackerRef ? InstanceTrackerActor.Get(taskId)).mapTo[Option[Instance]]

  private[this] val oldTasksByAppTimeMetric = metrics.deprecatedTimer(ServiceMetric, getClass, "tasksByApp")
  private[this] val newTasksByAppTimeMetric =
    metrics.timer("debug.instance-tracker.resolve-tasks-by-app-duration")

  implicit val instanceTrackerQueryTimeout: Timeout = config.internalTaskTrackerRequestTimeout().milliseconds

  override def process(stateOp: InstanceUpdateOperation): Future[InstanceUpdateEffect] = {
    import akka.pattern.ask

    import scala.concurrent.ExecutionContext.Implicits.global

    val instanceId: Instance.Id = stateOp.instanceId
    val deadline = clock.now + instanceTrackerQueryTimeout.duration
    val op = InstanceTrackerActor.UpdateContext(deadline, stateOp)
    (instanceTrackerRef ? op).mapTo[InstanceUpdateEffect].recover {
      case NonFatal(e) =>
        throw new RuntimeException(s"while asking for $op on runSpec [${instanceId.runSpecId}] and $instanceId", e)
    }
  }

  override def launchEphemeral(instance: Instance): Future[Done] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    process(InstanceUpdateOperation.LaunchEphemeral(instance)).map(_ => Done)
  }

  override def revert(instance: Instance): Future[Done] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    process(InstanceUpdateOperation.Revert(instance)).map(_ => Done)
  }

  override def forceExpunge(instanceId: Instance.Id): Future[Done] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    process(InstanceUpdateOperation.ForceExpunge(instanceId)).map(_ => Done)
  }

  override def updateStatus(instance: Instance, mesosStatus: mesos.Protos.TaskStatus, updateTime: Timestamp): Future[Done] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    process(InstanceUpdateOperation.MesosUpdate(instance, mesosStatus, updateTime)).map(_ => Done)
  }

  override def reservationTimeout(instanceId: Instance.Id): Future[Done] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    process(InstanceUpdateOperation.ReservationTimeout(instanceId)).map(_ => Done)
  }

  override def setGoal(instanceId: Instance.Id, goal: Goal): Future[Done] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    process(InstanceUpdateOperation.GoalChange(instanceId, goal)).map(_ => Done)
  }
}
