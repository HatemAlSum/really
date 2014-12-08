/*
* Copyright (C) 2014-2015 Really Inc. <http://really.io>
*/

package io.really.gorilla

import akka.actor.{ Props }
import akka.testkit.{ TestProbe, TestActorRef }
import akka.persistence.{ Update => PersistenceUpdate }
import io.really._
import _root_.io.really.model.FieldKey
import _root_.io.really.model._
import _root_.io.really.model.persistent.ModelRegistry.CollectionActorMessage
import _root_.io.really.model.persistent.ModelRegistry.ModelResult
import _root_.io.really.model.persistent.PersistentModelStore
import _root_.io.really.fixture.PersistentModelStoreFixture
import scala.slick.driver.H2Driver.simple._

class SubscriptionManagerSpec extends BaseActorSpecWithMongoDB {

  override lazy val globals = new TestReallyGlobals(config, system) {
    override def objectSubscriberProps(rSubscription: RSubscription): Props =
      Props(classOf[TestObjectSubscriber], rSubscription, this)
  }

  val caller = TestProbe()
  val requestDelegate = TestProbe()
  val pushChannel = TestProbe()
  val rev: Revision = 1L
  val r: R = R / 'users / 1
  val rSub = RSubscription(ctx, r, Some(Set("name")), rev, requestDelegate.ref, pushChannel.ref)

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

  "Object Subscription" should "handle new subscription, create an ObjectSubscriptionActor and update the internal" +
    " state" in {
      val subscriptionManger = TestActorRef[SubscriptionManager](globals.subscriptionManagerProps)
      subscriptionManger.underlyingActor.rSubscriptions.isEmpty shouldBe true
      subscriptionManger.tell(SubscriptionManager.SubscribeOnR(rSub), caller.ref)
      caller.expectMsg(SubscriptionManager.SubscriptionDone)
      subscriptionManger.underlyingActor.rSubscriptions.isEmpty shouldBe false
      val internalSub = subscriptionManger.underlyingActor.rSubscriptions.get(rSub.pushChannel.path).map {
        rsub =>
          rsub.r shouldEqual rSub.r
          rsub.subscriptionActor.tell(GetFieldList, caller.ref)
          val msg = caller.expectMsgType[scala.collection.mutable.Set[FieldKey]]
          msg shouldEqual Set("name")
      }
    }

  it should "dosen't add same caller twice to subscriptionList" in {
    val subscriptionManger = TestActorRef[SubscriptionManager](globals.subscriptionManagerProps)
    subscriptionManger.underlyingActor.rSubscriptions.isEmpty shouldBe true
    subscriptionManger.tell(SubscriptionManager.SubscribeOnR(rSub), caller.ref)
    caller.expectMsg(SubscriptionManager.SubscriptionDone)
    val rcount = subscriptionManger.underlyingActor.rSubscriptions.size
    rcount shouldEqual 1
    subscriptionManger.tell(SubscriptionManager.SubscribeOnR(rSub), caller.ref)
    caller.expectNoMsg()
    subscriptionManger.underlyingActor.rSubscriptions.size.shouldEqual(rcount)
    rcount shouldEqual 1
  }

  it should "handle Unsubscribe, send it to the ObjectSubscriptionActor and remove it from the internal state" in {
    val subscriptionManger = TestActorRef[SubscriptionManager](globals.subscriptionManagerProps)
    subscriptionManger.underlyingActor.rSubscriptions.isEmpty shouldBe true
    subscriptionManger.tell(SubscriptionManager.SubscribeOnR(rSub), caller.ref)
    caller.expectMsg(SubscriptionManager.SubscriptionDone)
    subscriptionManger.underlyingActor.rSubscriptions.isEmpty shouldBe false
    subscriptionManger.tell(SubscriptionManager.UnsubscribeFromR(rSub), caller.ref)
    caller.expectNoMsg()
    subscriptionManger.underlyingActor.rSubscriptions.isEmpty shouldBe true
  }

  it should "handle Update subscription fields" in {
    val rSub1 = RSubscription(ctx, r, Some(Set("name")), rev, requestDelegate.ref, pushChannel.ref)
    val rSub2 = RSubscription(ctx, r, Some(Set("age", "name")), rev, requestDelegate.ref, pushChannel.ref)
    val subscriptionManger = TestActorRef[SubscriptionManager](globals.subscriptionManagerProps)
    subscriptionManger.underlyingActor.rSubscriptions.isEmpty shouldBe true
    subscriptionManger.tell(SubscriptionManager.SubscribeOnR(rSub1), caller.ref)
    caller.expectMsg(SubscriptionManager.SubscriptionDone)
    val rcount = subscriptionManger.underlyingActor.rSubscriptions.size
    subscriptionManger.tell(SubscriptionManager.SubscribeOnR(rSub2), caller.ref)
    caller.expectNoMsg()
    subscriptionManger.underlyingActor.rSubscriptions.size.shouldEqual(rcount)
    val internalSub = subscriptionManger.underlyingActor.rSubscriptions.get(rSub1.pushChannel.path).map {
      rsub =>
        rsub.r shouldEqual rSub1.r
        rsub.subscriptionActor.tell(GetFieldList, caller.ref)
        val msg = caller.expectMsgType[scala.collection.mutable.Set[FieldKey]]
        msg shouldEqual Set("name", "age")
    }
  }
}

case object GetFieldList

class TestObjectSubscriber(rSubscription: RSubscription, globals: ReallyGlobals) extends ObjectSubscriber(rSubscription, globals) {

  override def receive: Receive = testOps orElse super.receive

  override def commonHandler: Receive = testOps orElse super.commonHandler

  override def waitingModel: Receive = testOps orElse super.waitingModel

  override def withModel(model: Model): Receive = testOps orElse super.withModel(model)

  override def starterReceive: Receive = testOps orElse super.starterReceive

  def testOps: Receive = {
    case GetFieldList =>
      sender() ! this.fields
  }
}

