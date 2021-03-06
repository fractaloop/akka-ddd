package pl.newicom.dddd.process

import akka.actor._
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, WordSpecLike}
import pl.newicom.dddd.actor.PassivationConfig
import pl.newicom.dddd.delivery.protocol.alod.Delivered
import pl.newicom.dddd.messaging.MetaData._
import pl.newicom.dddd.messaging.event.EventMessage
import pl.newicom.dddd.office.LocalOffice._
import pl.newicom.dddd.office.Office._
import pl.newicom.dddd.process.SagaSpec._
import pl.newicom.dddd.test.dummy.DummySaga
import pl.newicom.dddd.test.dummy.DummySaga.DummyEvent
import pl.newicom.dddd.utils.UUIDSupport.uuid7

import scala.concurrent.duration._


object SagaSpec {
  implicit val sys: ActorSystem = ActorSystem("SagaSpec")
}

class SagaSpec extends TestKit(sys) with WordSpecLike with ImplicitSender with BeforeAndAfterAll with BeforeAndAfter  {

  implicit object TestSagaActorFactory extends SagaActorFactory[DummySaga] {
    override def props(pc: PassivationConfig): Props = {
      Props(new DummySaga(pc, None) {
        override def receiveUnexpected: Receive = {
          case em: EventMessage =>
            system.eventStream.publish(em.event)
        }
      })
    }
  }

  def processId = uuid7
  val sagaOffice = office[DummySaga]

  after {
    if (sagaOffice != null) {
      ensureActorTerminated(sagaOffice)
    }
  }

  "Saga" should {
    "not process previously processed events" in {
      // Given
      val probe = TestProbe()
      system.eventStream.subscribe(probe.ref, classOf[DummyEvent])

      val em1 = toEventMessage(DummyEvent(processId, 1))

      // When
      sagaOffice ! em1
      sagaOffice ! em1

      // Then
      probe.expectMsgClass(classOf[DummyEvent])
      probe.expectNoMsg(1.seconds)
    }
  }

  "Saga" should {
    "acknowledge previously processed events" in {
      // Given
      val sagaOffice = office[DummySaga]
      val em1 = toEventMessage(DummyEvent(processId, 1))

      // When/Then
      sagaOffice ! em1
      expectMsgClass(classOf[Delivered])

      sagaOffice ! em1
      expectMsgClass(classOf[Delivered])
    }
  }

  def toEventMessage(e: DummyEvent): EventMessage = {
    new EventMessage(e).withMetaData(Map(
      CorrelationId -> processId,
      DeliveryId -> 1L
    ))
  }

  def ensureActorTerminated(actor: ActorRef) = {
    watch(actor)
    actor ! PoisonPill
    fishForMessage(1.seconds) {
      case Terminated(_) =>
        unwatch(actor)
        true
      case _ => false
    }
  }

}
