/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.apollo.broker

import _root_.java.util.concurrent.atomic.AtomicLong
import _root_.org.apache.activemq.util.buffer._
import _root_.org.fusesource.hawtdispatch._
import _root_.org.fusesource.hawtdispatch.ScalaDispatch._

import java.util.HashMap
import collection.JavaConversions
import path.PathMap

object Domain {
  val TOPIC_DOMAIN = new AsciiBuffer("topic");
  val QUEUE_DOMAIN = new AsciiBuffer("queue");
  val TEMP_TOPIC_DOMAIN = new AsciiBuffer("temp-topic");
  val TEMP_QUEUE_DOMAIN = new AsciiBuffer("temp-queue");
}

import Domain._
class Domain {

  val targets = new PathMap[DeliveryTarget]();

  def bind(name:AsciiBuffer, queue:DeliveryTarget) = {
    targets.put(name, queue);
  }

  def unbind(name:AsciiBuffer, queue:DeliveryTarget) = {
    targets.remove(name, queue);
  }

//
//  synchronized public Collection<DeliveryTarget> route(AsciiBuffer name, MessageDelivery delivery) {
//    return targets.get(name);
//  }

}


/**
 * Provides a non-blocking concurrent producer to consumer
 * routing implementation.
 *
 * DeliveryProducers create a route object for each destination
 * they will be producing to.  Once the route is
 * connected to the router, the producer can use
 * the route.targets list without synchronization to
 * get the current set of consumers that are bound
 * to the destination. 
 *
 */
class Router(var queue:DispatchQueue) {
  
  trait DestinationNode {
    var targets = List[DeliveryTarget]()
    var routes = List[DeliveryProducerRoute]()

    def on_bind(x:List[DeliveryTarget]):Unit
    def on_unbind(x:List[DeliveryTarget]):Boolean
    def on_connect(route:DeliveryProducerRoute):Unit
    def on_disconnect(route:DeliveryProducerRoute):Boolean = {
      routes = routes.filterNot({r=> route==r})
      route.disconnected()
      routes == Nil && targets == Nil
    }
  }

  class TopicDestinationNode extends DestinationNode {
    def on_bind(x:List[DeliveryTarget]) =  {
      targets = x ::: targets
      routes.foreach({r=>
        r.bind(x)
      })
    }

    def on_unbind(x:List[DeliveryTarget]):Boolean = {
      targets = targets.filterNot({t=>x.contains(t)})
      routes.foreach({r=>
        r.unbind(x)
      })
      routes == Nil && targets == Nil
    }

    def on_connect(route:DeliveryProducerRoute) = {
      routes = route :: routes
      route.connected(targets)
    }
  }

  class QueueDestinationNode(destination:Destination) extends DestinationNode {
    val queue = new Queue(destination)

    def on_bind(x:List[DeliveryTarget]) =  {
      targets = x ::: targets
      queue.bind(x)
    }

    def on_unbind(x:List[DeliveryTarget]):Boolean = {
      targets = targets.filterNot({t=>x.contains(t)})
      queue.unbind(x)
      routes == Nil && targets == Nil
    }

    def on_connect(route:DeliveryProducerRoute) = {
      routes = route :: routes
      route.connected(queue :: Nil)
    }
  }

  var destinations = new HashMap[Destination, DestinationNode]()

  private def get(destination:Destination):DestinationNode = {
    var result = destinations.get(destination)
    if( result ==null ) {
      if( isTopic(destination) ) {
        result = new TopicDestinationNode
      } else {
        result = new QueueDestinationNode(destination)
      }
      destinations.put(destination, result)
    }
    result
  }

  def bind(destination:Destination, targets:List[DeliveryTarget]) = retaining(targets) {
      get(destination).on_bind(targets)
    } ->: queue

  def unbind(destination:Destination, targets:List[DeliveryTarget]) = releasing(targets) {
      if( get(destination).on_unbind(targets) ) {
        destinations.remove(destination)
      }
    } ->: queue

  def connect(destination:Destination, routeQueue:DispatchQueue, producer:DeliveryProducer)(completed: (DeliveryProducerRoute)=>Unit) = {
    val route = new DeliveryProducerRoute(destination, routeQueue, producer) {
      override def on_connected = {
        completed(this);
      }
    }
    ^ {
      get(destination).on_connect(route)
    } ->: queue
  }

  def isTopic(destination:Destination) = destination.getDomain == TOPIC_DOMAIN
  def isQueue(destination:Destination) = !isTopic(destination)

  def disconnect(route:DeliveryProducerRoute) = releasing(route) {
      get(route.destination).on_disconnect(route)
    } ->: queue


   def each(proc:(Destination, DestinationNode)=>Unit) = {
     import JavaConversions._;
     for( (destination, node) <- destinations ) {
        proc(destination, node)
     }
   }

}

trait Route extends Retained {

  val destination:Destination
  val queue:DispatchQueue
  val metric = new AtomicLong();

  def connected(targets:List[DeliveryTarget]):Unit
  def bind(targets:List[DeliveryTarget]):Unit
  def unbind(targets:List[DeliveryTarget]):Unit
  def disconnected():Unit

}

class DeliveryProducerRoute(val destination:Destination, val queue:DispatchQueue, val producer:DeliveryProducer) extends BaseRetained with Route {


  // Retain the queue while we are retained.
  queue.retain
  setDisposer(^{
    queue.release
  })

  var targets = List[DeliveryTargetSession]()

  def connected(targets:List[DeliveryTarget]) = retaining(targets) {
    internal_bind(targets)
    on_connected
  } ->: queue

  def bind(targets:List[DeliveryTarget]) = retaining(targets) {
    internal_bind(targets)
  } ->: queue

  private def internal_bind(values:List[DeliveryTarget]) = {
    values.foreach{ x=>
      targets = x.open_session(queue) :: targets
    }
  }

  def unbind(targets:List[DeliveryTarget]) = releasing(targets) {
    this.targets = this.targets.filterNot { x=>
      val rc = targets.contains(x.consumer)
      if( rc ) {
        x.close
      }
      rc
    }
  } ->: queue

  def disconnected() = ^ {
    this.targets.foreach { x=>
      x.close
      x.consumer.release
    }    
  } ->: queue

  protected def on_connected = {}
  protected def on_disconnected = {}

}