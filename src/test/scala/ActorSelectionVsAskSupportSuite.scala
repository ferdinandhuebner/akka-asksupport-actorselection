import akka.actor._
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpecLike}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.language.postfixOps

object SuiteWithActorSystem {
  val DefaultConfiguration =
    """
      |akka.loglevel = "INFO"
      |akka.log-dead-letters-during-shutdown = "off"
    """.stripMargin
}

abstract class SuiteWithActorSystem(_system: ActorSystem)
    extends TestKit(_system) with ImplicitSender with WordSpecLike with MustMatchers with BeforeAndAfterAll {
  def this(systemConfig: String = SuiteWithActorSystem.DefaultConfiguration) = this {
    val config = ConfigFactory.parseString(systemConfig)
    ActorSystem.create("test", config)
  }

  override def afterAll() {
    TestKit.shutdownActorSystem(system)
  }

  val MaxWait: FiniteDuration = 3 seconds
  implicit val timeout: Timeout = Timeout(MaxWait)
  implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
}

object TestActor {
  def props(): Props = Props(classOf[TestActor])
}
case class Reply(req: Int)
case class TellRef(req: Int)
case class TellSelection(req: Int)

class TestActor extends Actor with ActorLogging {
  override def receive: Receive = {
    case TellSelection(req) =>
      val destination = context.actorSelection(sender().path)
      log.info(s"replying with ActorSelection (${destination})")
      destination ! Reply(req)
    case TellRef(req) =>
      log.info(s"replying with ActorRef (${sender()})")
      sender ! Reply(req)
  }
}

class ActorSelectionVsAskSupportSuite extends SuiteWithActorSystem {

  "The test actor" must {
    "send a Reply msg after it received a TellRef msg" in {
      val actor = system.actorOf(TestActor.props(), "test-1")
      val req = 1
      val resp = actor ? TellRef(req)
      Await.result(resp, MaxWait) match {
        case r: Reply => r.req mustEqual req
        case other => fail(s"Unexpected answer from actor: $other")
      }
    }
    "send a Reply msg after it received a TellSelection msg" in {
      val actor = system.actorOf(TestActor.props(), "test-2")
      val req = 2
      val resp = actor ? TellSelection(req)
      // this fails with a TimeoutException
      Await.result(resp, MaxWait) match {
        case r: Reply => r.req mustEqual req
        case other => fail(s"Unexpected answer from actor: $other")
      }
    }
  }
}

object AskProxy {
  def props(target: ActorRef): Props = Props(classOf[AskProxy], target)
}
class AskProxy(target: ActorRef) extends Actor with ActorLogging {
  private def withMsgSource(src: ActorRef): Receive = {
    case msg =>
      log.info(s"Sending message from ${sender()} to $src using tell")
      src ! msg
      context stop self
  }

  override def receive: Receive = {
    case msg =>
      log.info(s"Sending message from ${sender()} to $target using tell")
      target ! msg
      context become withMsgSource(sender())
  }
}

class ActorSelectionVsAskSupportWithAskProxySuite extends SuiteWithActorSystem {

  "The test actor, if used with an AskProxy intermediary" must {
    "send a Reply msg after it received a TellRef msg" in {
      val actor = system.actorOf(TestActor.props(), "test-3")
      val askProxy = system.actorOf(AskProxy.props(actor), "askProxy-3")
      val req = 3
      val resp = askProxy ? TellRef(req)
      Await.result(resp, MaxWait) match {
        case r: Reply => r.req mustEqual req
        case other => fail(s"Unexpected answer from actor: $other")
      }
    }
    "send a Reply msg after it received a TellSelection msg" in {
      val actor = system.actorOf(TestActor.props(), "test-4")
      val askProxy = system.actorOf(AskProxy.props(actor), "askProxy-4")
      val req = 4
      val resp = askProxy ? TellSelection(req)
      // this does not fail
      Await.result(resp, MaxWait) match {
        case r: Reply => r.req mustEqual req
        case other => fail(s"Unexpected answer from actor: $other")
      }
    }
  }

}