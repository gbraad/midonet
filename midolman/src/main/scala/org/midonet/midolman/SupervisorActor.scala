/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman

import akka.actor._
import akka.util.Timeout
import com.google.inject.Inject

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.pattern.ask

import org.midonet.midolman.logging.ActorLogWithoutPath

/**
 * This actor is responsible for the supervision strategy of all the
 * top-level (Referenceable derived, well-known actors) actors in
 * midolman. All such actors are launched as children of the SupervisorActor
 */
object SupervisorActor extends Referenceable {
    override val Name = supervisorName

    override protected def path: String = Referenceable.getSupervisorPath(Name)

    case class StartChild(props: Props, name: String)
}

class SupervisorActor extends Actor with ActorLogWithoutPath {

    import SupervisorActor._

    @Inject
    override val supervisorStrategy: SupervisorStrategy = null

    override def postStop() {
        log.info("Supervisor actor is shutting down")
    }

    def receive = {
        case StartChild(props, name) =>
            val result = try {
                val actor = context.actorOf(props, name)
                implicit val timeout = new Timeout(10 seconds)
                Await.result(actor ? Identify(null), timeout.duration)
                actor
            } catch {
                case t: Throwable =>
                    log.error(s"could not start actor $name", t)
                    Status.Failure(t)
            }
            sender ! result

        case unknown => log.info(s"received unknown message $unknown")
    }
}
