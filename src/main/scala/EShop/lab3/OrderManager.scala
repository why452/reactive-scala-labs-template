package EShop.lab3

import EShop.lab2.{TypedCartActor, TypedCheckout}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}

object OrderManager {

  sealed trait Command
  case class AddItem(id: String, sender: ActorRef[Ack])                                               extends Command
  case class RemoveItem(id: String, sender: ActorRef[Ack])                                            extends Command
  case class SelectDeliveryAndPaymentMethod(delivery: String, payment: String, sender: ActorRef[Ack]) extends Command
  case class Buy(sender: ActorRef[Ack])                                                               extends Command
  case class Pay(sender: ActorRef[Ack])                                                               extends Command
  case class ConfirmCheckoutStarted(checkoutRef: ActorRef[TypedCheckout.Command])                     extends Command
  case class ConfirmPaymentStarted(paymentRef: ActorRef[Payment.Command])                             extends Command
  case object ConfirmPaymentReceived                                                                  extends Command
  case object ConfirmCheckOutClosed                                                                   extends Command
  case object PaymentRejected extends Command
  case object PaymentRestarted extends Command

  sealed trait Ack
  case object Done extends Ack //trivial ACK
}

class OrderManager {

  import OrderManager._
  def start: Behavior[OrderManager.Command] =
    Behaviors.setup { context =>
      val typedCartActor = context.spawnAnonymous(new TypedCartActor().empty)

      open(cartActor = typedCartActor)
    }

  def uninitialized: Behavior[OrderManager.Command] = Behaviors.same

  def open(cartActor: ActorRef[TypedCartActor.Command]): Behavior[OrderManager.Command] =
    Behaviors.receive((context, message) =>
      message match {
        case AddItem(id, sender) =>
          cartActor ! TypedCartActor.AddItem(id)
          sender ! Done
          Behaviors.same

        case RemoveItem(id, sender) =>
          cartActor ! TypedCartActor.RemoveItem(id)
          sender ! Done
          Behaviors.same

        case Buy(sender) =>
          cartActor ! TypedCartActor.StartCheckout(orderManagerRef = context.self)
          inCheckout(
            cartActorRef = cartActor,
            senderRef = sender
          )
      }
    )

  def inCheckout(
                  cartActorRef: ActorRef[TypedCartActor.Command],
                  senderRef: ActorRef[Ack]
                ): Behavior[OrderManager.Command] =
    Behaviors.receiveMessage {
      case ConfirmCheckoutStarted(checkoutRef) =>
        senderRef ! Done
        inCheckout(checkoutActorRef = checkoutRef)
    }

  def inCheckout(checkoutActorRef: ActorRef[TypedCheckout.Command]): Behavior[OrderManager.Command] =
    Behaviors.receive((context, message) =>
      message match {
        case SelectDeliveryAndPaymentMethod(deliveryMethod, payment, sender) =>
          checkoutActorRef ! TypedCheckout.SelectDeliveryMethod(method = deliveryMethod)
          checkoutActorRef ! TypedCheckout.SelectPayment(
            payment = payment,
            orderManagerRef = context.self.ref
          )
          inPayment(senderRef = sender)
      }
    )

  def inPayment(senderRef: ActorRef[Ack]): Behavior[OrderManager.Command] =
    Behaviors.receiveMessage {
      case ConfirmPaymentStarted(paymentRef) =>
        senderRef ! Done
        inPayment(
          paymentActorRef = paymentRef,
          senderRef = senderRef
        )

      case ConfirmPaymentReceived =>
        senderRef ! Done
        finished
    }

  def inPayment(
                 paymentActorRef: ActorRef[Payment.Command],
                 senderRef: ActorRef[Ack]
               ): Behavior[OrderManager.Command] =
    Behaviors.receiveMessage {
      case Pay(sender) =>
        paymentActorRef ! Payment.DoPayment
        inPayment(senderRef = sender)
    }

  def finished: Behavior[OrderManager.Command] = Behaviors.stopped
}
