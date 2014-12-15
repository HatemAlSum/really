/*
* Copyright (C) 2014-2015 Really Inc. <http://really.io>
*/

package io.really.gorilla

import akka.actor.{ Terminated, Props, ActorSystem }
import akka.persistence.{ Update => PersistenceUpdate }
import com.typesafe.config.ConfigFactory
import play.api.libs.json.Json
import akka.testkit.{ EventFilter, TestProbe, TestActorRef }
import _root_.io.really._
import _root_.io.really.gorilla.SubscriptionManager.{ UpdateSubscriptionFields, Unsubscribe }
import _root_.io.really.protocol.ProtocolFormats.PushMessageWrites.Deleted
import _root_.io.really.gorilla.SubscribeAggregator.Subscribed
import _root_.io.really.gorilla.GorillaEventCenter._
import _root_.io.really.fixture.PersistentModelStoreFixture
import _root_.io.really.model.Model
import _root_.io.really.model.persistent.ModelRegistry.CollectionActorMessage
import _root_.io.really.model.persistent.ModelRegistry.ModelResult
import _root_.io.really.model.CollectionActor.Event.Created
import _root_.io.really.model.persistent.PersistentModelStore

import scala.slick.driver.H2Driver.simple._

class ObjectSubscriberSpec(config: ReallyConfig) extends BaseActorSpecWithMongoDB(config) {

  def this() = this(new ReallyConfig(ConfigFactory.parseString("""
    really.core.akka.loggers = ["akka.testkit.TestEventListener"],
    really.core.akka.loglevel = WARNING
                                                                """).withFallback(TestConf.getConfig().getRawConfig)))

  val caller = TestProbe()
  val requestDelegate = TestProbe()
  val pushChannel = TestProbe()
  val deathPrope = TestProbe()
  val rev: Revision = 1L
  val r: R = R / 'users / 1
  val rSub = RSubscription(ctx, r, None, rev, requestDelegate.ref, pushChannel.ref)
  val userInfo = UserInfo(AuthProvider.Anonymous, R("/_anonymous/1234567"), Application("reallyApp"))
  implicit val session = globals.session

  val models: List[Model] = List(BaseActorSpec.userModel, BaseActorSpec.carModel,
    BaseActorSpec.companyModel, BaseActorSpec.authorModel, BaseActorSpec.postModel)

  override def beforeAll() = {
    super.beforeAll()
    globals.persistentModelStore ! PersistentModelStore.UpdateModels(models)
    globals.persistentModelStore ! PersistentModelStoreFixture.GetState
    expectMsg(models)

    globals.modelRegistry ! PersistenceUpdate(await = true)
    globals.modelRegistry ! CollectionActorMessage.GetModel(BaseActorSpec.userModel.r, self)
    expectMsg(ModelResult.ModelObject(BaseActorSpec.userModel, List.empty))
  }

  "Object Subscriber" should "Initialized Successfully" in {
    val rSub1 = RSubscription(ctx, r, Some(Set("name")), rev, requestDelegate.ref, pushChannel.ref)
    val objectSubscriber = TestActorRef[ObjectSubscriber](globals.objectSubscriberProps(rSub1)).underlyingActor
    objectSubscriber.fields shouldEqual Set("name")
    objectSubscriber.r shouldEqual rSub1.r
    objectSubscriber.logTag shouldEqual s"ObjectSubscriber ${rSub1.pushChannel.path}$$$r"

    val rSub2 = RSubscription(ctx, r, None, rev, requestDelegate.ref, pushChannel.ref)
    val objectSubscriber2 = TestActorRef[ObjectSubscriber](globals.objectSubscriberProps(rSub2))
    objectSubscriber2.underlyingActor.fields.size shouldEqual 0
  }

  it should "handle Unsubscribe successfully during starter receiver and self termination" in {
    val rSub1 = RSubscription(ctx, r, Some(Set("name")), rev, requestDelegate.ref, pushChannel.ref)
    val objectSubscriberActor = TestActorRef[ObjectSubscriber](globals.objectSubscriberProps(rSub1))
    deathPrope.watch(objectSubscriberActor)
    objectSubscriberActor.underlyingActor.fields shouldEqual Set("name")
    objectSubscriberActor.tell(SubscriptionManager.Unsubscribe, caller.ref)
    requestDelegate.expectMsg(SubscriptionManager.Unsubscribe)
    deathPrope.expectTerminated(objectSubscriberActor)
  }

  it should "handle Unsubscribe during withModel and self termination" in {
    val rSub1 = RSubscription(ctx, r, None, rev, requestDelegate.ref, pushChannel.ref)
    val objectSubscriberActor = TestActorRef[ObjectSubscriber](globals.objectSubscriberProps(rSub1))
    deathPrope.watch(objectSubscriberActor)
    val replayer = system.actorOf(Props(new Replayer(globals, objectSubscriberActor, rSub1, Some(rev))))
    objectSubscriberActor ! ReplayerSubscribed(replayer)
    Thread.sleep(1000)
    objectSubscriberActor.underlyingActor.fields.size shouldEqual 2
    objectSubscriberActor.tell(SubscriptionManager.Unsubscribe, caller.ref)
    requestDelegate.expectMsg(SubscriptionManager.Unsubscribe)
    deathPrope.expectTerminated(objectSubscriberActor)
  }

  it should "update Internal field List Successfully" in {
    val rSub1 = RSubscription(ctx, r, Some(Set("name")), rev, requestDelegate.ref, pushChannel.ref)
    val objectSubscriberActor = TestActorRef[ObjectSubscriber](globals.objectSubscriberProps(rSub1))
    val replayer = system.actorOf(Props(new Replayer(globals, objectSubscriberActor, rSub1, Some(rev))))
    objectSubscriberActor ! ReplayerSubscribed(replayer)
    objectSubscriberActor ! UpdateSubscriptionFields(Set("age"))
    objectSubscriberActor.underlyingActor.fields shouldEqual Set("name", "age")
  }

  it should "pass delete updates to push channel actor correctly and then terminates" in {
    val rSub1 = RSubscription(ctx, r, Some(Set("name")), rev, requestDelegate.ref, pushChannel.ref)
    val objectSubscriberActor = TestActorRef[ObjectSubscriber](globals.objectSubscriberProps(rSub1))
    val replayer = system.actorOf(Props(new Replayer(globals, objectSubscriberActor, rSub1, Some(rev))))
    objectSubscriberActor.tell(ReplayerSubscribed(replayer), caller.ref)
    caller.expectNoMsg()
    objectSubscriberActor.underlyingActor.fields shouldEqual Set("name")
    objectSubscriberActor ! GorillaLogDeletedEntry(r, rev, 1l, userInfo)
    pushChannel.expectMsg(Deleted.toJson(userInfo.userR, r))
    deathPrope.watch(objectSubscriberActor)
    deathPrope.expectTerminated(objectSubscriberActor)
  }

  it should "for empty list subscription change the internal state from empty list to all model fields" in {
    val rSub2 = RSubscription(ctx, r, None, rev, requestDelegate.ref, pushChannel.ref)
    val objectSubscriber2 = TestActorRef[ObjectSubscriber](globals.objectSubscriberProps(rSub2))
    val replayer = system.actorOf(Props(new Replayer(globals, objectSubscriber2, rSub2, Some(rev))))
    objectSubscriber2.tell(ReplayerSubscribed(replayer), caller.ref)
    Thread.sleep(3000)
    objectSubscriber2.underlyingActor.fields shouldEqual Set("name", "age")
  }

  it should "log a warning when receiving a create push update" in {
    val rSub1 = RSubscription(ctx, r, None, rev, requestDelegate.ref, pushChannel.ref)
    val objectSubscriberActor = TestActorRef[ObjectSubscriber](globals.objectSubscriberProps(rSub1))
    deathPrope.watch(objectSubscriberActor)
    val replayer = system.actorOf(Props(new Replayer(globals, objectSubscriberActor, rSub1, Some(rev))))
    objectSubscriberActor ! ReplayerSubscribed(replayer)
    val userObject = Json.obj("_r" -> r.toString, "_rev" -> 1L, "name" -> "Hatem", "age" -> 30)
    val createdEvent = GorillaLogCreatedEntry(r, userObject, 1L, 1L, ctx.auth)
    //Test unexpected behaviour
    EventFilter.warning(message = s"Object Subscriber got an `Created` event which doesn't make any sense!: $createdEvent") intercept {
      objectSubscriberActor ! createdEvent
    }
  }

  ignore should "filter the hidden fields from the an empty list subscription and sent the rest of model fields" in {}

  ignore should "filter the hidden fields from the the subscription list and sent the rest of the subscription list " in {}

  ignore should "pass nothing if the model.executeOnGet evaluated to Terminated" in {}

  ignore should "in case of subscription failure, log and acknowledge the delegate and then stop" in {}

  ignore should "handle associated replayer termination" in {}

  ignore should "handle ModelUpdated correctly" in {}

  ignore should "handle ModelDeleted, send subscription failed and terminates" in {}

  ignore should "handle if the pushed update model version is not equal to the state version" in {}

}
