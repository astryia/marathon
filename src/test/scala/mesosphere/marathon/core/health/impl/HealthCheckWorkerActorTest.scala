package mesosphere.marathon
package core.health.impl

import java.net.{ InetAddress, ServerSocket }

import akka.Done
import akka.actor.Props
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.{ ImplicitSender, TestActorRef }
import mesosphere.AkkaUnitTest
import mesosphere.marathon.core.health._
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.instance.{ Instance, LegacyAppInstance, TestTaskBuilder }
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.state.{ AppDefinition, PathId, PortDefinition, UnreachableStrategy }

import scala.collection.immutable.Seq
import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._

class HealthCheckWorkerActorTest extends AkkaUnitTest with ImplicitSender {

  import HealthCheckWorker._

  "HealthCheckWorkerActor" should {
    "A TCP health check should correctly resolve the hostname" in {
      val socket = new ServerSocket(0)
      val socketPort: Int = socket.getLocalPort

      val res = Future {
        socket.accept().close()
      }

      val appId = PathId("/test_id")
      val app = AppDefinition(id = appId, portDefinitions = Seq(PortDefinition(0)))
      val hostName = InetAddress.getLocalHost.getCanonicalHostName
      val agentInfo = AgentInfo(host = hostName, agentId = Some("agent"), region = None, zone = None, attributes = Nil)
      val task = {
        val t: Task = TestTaskBuilder.Helper.runningTaskForApp(appId)
        val hostPorts = Seq(socketPort)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts, ipAddresses = Nil)))
      }
      val instance = LegacyAppInstance(task, agentInfo, UnreachableStrategy.default())

      val ref = TestActorRef[HealthCheckWorkerActor](Props(classOf[HealthCheckWorkerActor], mat))
      ref ! HealthCheckJob(app, instance, MarathonTcpHealthCheck(portIndex = Some(PortReference(0))))

      try { res.futureValue }
      finally { socket.close() }

      expectMsgPF(patienceConfig.timeout) {
        case Healthy(_, _, _, _) => ()
      }
    }

    "A health check worker should shut itself down" in {
      val socket = new ServerSocket(0)
      val socketPort: Int = socket.getLocalPort

      val res = Future {
        socket.accept().close()
      }

      val appId = PathId("/test_id")
      val app = AppDefinition(id = appId, portDefinitions = Seq(PortDefinition(0)))
      val hostName = InetAddress.getLocalHost.getCanonicalHostName
      val agentInfo = AgentInfo(host = hostName, agentId = Some("agent"), region = None, zone = None, attributes = Nil)
      val task = {
        val t: Task = TestTaskBuilder.Helper.runningTaskForApp(appId)
        val hostPorts = Seq(socketPort)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts, ipAddresses = Nil)))
      }
      val instance = LegacyAppInstance(task, agentInfo, UnreachableStrategy.default())

      val ref = TestActorRef[HealthCheckWorkerActor](Props(classOf[HealthCheckWorkerActor], mat))
      ref ! HealthCheckJob(app, instance, MarathonTcpHealthCheck(portIndex = Some(PortReference(0))))

      try { res.futureValue }
      finally { socket.close() }

      expectMsgPF(patienceConfig.timeout) {
        case _: HealthResult => ()
      }

      watch(ref)
      expectTerminated(ref)
    }

    "A HTTP health check should work as expected" in {

      import akka.http.scaladsl.server.Directives._

      val promise = Promise[String]()

      val route =
        path("health") {
          get {
            promise.success("success")
            complete(StatusCodes.OK)
          }
        }

      val binding = Http().bindAndHandle(route, "localhost", 0).futureValue

      val port = binding.localAddress.getPort

      val hostName = "localhost"
      val appId = PathId("/test_id")
      val app = AppDefinition(id = appId, portDefinitions = Seq(PortDefinition(0)))
      val agentInfo = AgentInfo(host = hostName, agentId = Some("agent"), region = None, zone = None, attributes = Nil)
      val task = {
        val t: Task = TestTaskBuilder.Helper.runningTaskForApp(appId)
        val hostPorts = Seq(port)
        t.copy(status = t.status.copy(networkInfo = NetworkInfo(hostName, hostPorts, ipAddresses = Nil)))
      }
      val since = task.status.startedAt.getOrElse(task.status.stagedAt)
      val unreachableStrategy = UnreachableStrategy.default()
      val tasksMap = Map(task.taskId -> task)
      val state = Instance.InstanceState(None, tasksMap, since, unreachableStrategy)

      val instance = Instance(task.taskId.instanceId, agentInfo, state, tasksMap, task.runSpecVersion, unreachableStrategy, None)

      val ref = system.actorOf(Props(classOf[HealthCheckWorkerActor], mat))
      ref ! HealthCheckJob(app, instance, MarathonHttpHealthCheck(port = Some(port), path = Some("/health")))

      promise.future.futureValue shouldEqual "success"

    }
  }
}
