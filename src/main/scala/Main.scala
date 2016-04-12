import scala.collection.JavaConverters._
import scala.collection.immutable.TreeMap
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ Future, blocking }
import scala.io.Source._
import scala.util.Random.nextInt

import java.io.{ File, IOException, PrintWriter }
import java.nio.channels.FileChannel
import java.nio.file.{ Paths, Files, OpenOption, StandardOpenOption }
import java.nio.charset.StandardCharsets
import java.time.{ Clock, Instant, ZonedDateTime }
import java.time.format.{ DateTimeFormatter, DateTimeParseException }
import java.time.temporal.ChronoUnit
import java.util.concurrent.{ ConcurrentMap, ConcurrentHashMap, TimeUnit }

import com.typesafe.config._

import akka.actor.{ Actor, ActorRef, ActorSystem, ActorSelection, DeadLetter, Props, Terminated, OneForOneStrategy, AllForOneStrategy }
import akka.actor.Status._
import akka.actor.SupervisorStrategy._
import akka.event.Logging
import akka.pattern.pipe
import akka.persistence._
import akka.util.Timeout

import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import org.apache.commons.io.input.{ Tailer, TailerListener, TailerListenerAdapter }

/**
class WriterActor extends Actor {
  def receive = {
    case PrintAverage => 
      context.actorSelection("/user/riyad/dataActor") ! GetAverageDegree
  }
}

class StdoutWriterActor extends Actor {
  def receive = {
    case PrintAverage => 
      context.actorSelection("/user/riyad/dataActor") ! GetAverageDegree
    case CurrentAverageDegree (avg) =>
      println("Average: %.2f".format(avg))
  }
}
*/
case object Done

object FileWriterActor {
}

class FileWriterActor(path: String) extends Actor {
  import DataActor._
  import FileWriterActor._

  val log = Logging(context.system, this)
  var outputString = ""

  override def preStart(): Unit = {
  }

  def receive = {
    case CurrentAverageDegree (avg) =>
      //fileChannel.write("%.2f".format(avg).getBytes("utf-8"))
      //f.appendAll("%.2f\n".format(avg))
      //writer.write("%.2f\n".format(avg))
      outputString += "%.2f\n".format(avg)
      println("%.2f".format(avg))
      sender ! Done
      //log.info("Sent SinkDone")
  }

  override def postStop() {
    //f.close()
    new PrintWriter(path) { write(outputString); close() }
  }
}

object DataActor {
  case class Tweet (createdAt: ZonedDateTime, hashtagset: Set[String])
  case class CurrentAverageDegree (avg: Double)
  case object UpdateDone
}

case class Cmd(tweet: DataActor.Tweet)
case class Evt(tweet: DataActor.Tweet)

case class GraphState(var edgesMap: Map[(String, String), ZonedDateTime], var degreeMap: Map[String, Int], var lowerBoundWindow: ZonedDateTime, var upperBoundWindow: ZonedDateTime) {
  def flatMapSublists[A,B](ls: List[A])(f: (List[A]) => List[B]): List[B] = 
    ls match {
      case Nil => Nil
      case sublist@(_ :: tail) => f(sublist) ::: flatMapSublists(tail)(f)
    }
  
  def combinations[A](ls: List[A], n: Int): List[List[A]] =
    if (n == 0) List(Nil)
    else flatMapSublists(ls) { sl =>
      combinations(sl.tail, n - 1) map {sl.head :: _}
    }

  implicit def dateTimeOrdering: Ordering[ZonedDateTime] = Ordering.fromLessThan(_ isBefore _)

  def updated(evt: Evt): GraphState = {
    val DataActor.Tweet(createdAt, hashtagset) = evt.tweet
    if ((createdAt isAfter lowerBoundWindow) && hashtagset.size > 1) {
      // get edges
      val sortedTags = hashtagset.toList.sorted
      val edges = combinations(sortedTags, 2)
      val edgeTuples = edges.map(l => (l(0), l(1)))
      val kvs = edgeTuples.map((_ -> createdAt))
      val allEdges = edgesMap.keys.toSet
      val newEdges = edgeTuples.filter(!allEdges.contains(_))
      edgesMap = edgesMap ++ kvs
      val inc = newEdges.flatMap(x => List(x._1, x._2)).groupBy(identity).mapValues(_.size)
      // increment degree for each vertices
      degreeMap = degreeMap ++ inc.map( kv => ( kv._1 -> (degreeMap.getOrElse(kv._1, 0)+kv._2) ) )
    }
    if (createdAt isAfter upperBoundWindow) {
      upperBoundWindow = createdAt
      lowerBoundWindow = upperBoundWindow.minusSeconds(60)
      val (removedEdgesMap, updatedEdgesMap) = edgesMap.partition(p => lowerBoundWindow.compareTo(p._2) > 0)
      val removedEdges = removedEdgesMap.map(p => p._1).toList
      val dec = removedEdges.flatMap(x => List(x._1, x._2)).groupBy(identity).mapValues(_.size)
      // decrement degress for removed vertices
      degreeMap = degreeMap ++ dec.map( kv => ( kv._1 -> (degreeMap(kv._1) - kv._2) ) )
      // remove unconnected vertices
      degreeMap = degreeMap.filter(_._2 > 0)
      // remove tweets falls out of window
      edgesMap = updatedEdgesMap
    }
    this
  }
}

class DataActor(sink: ActorRef) extends PersistentActor {
  import akka.pattern.ask
  import scala.concurrent.duration._
  import DataActor._
  import FileWriterActor._

  implicit val timeout = Timeout(100.seconds)
  
  //override def preStart() = println("Yo, I am alive!")
  //override def postStop() = println("Goodbye world!")
  override def preRestart(reason: Throwable, message: Option[Any]) = {
    //println("Yo, I am restarting...")
    super.preRestart(reason, message)
  }

  override def postRestart(reason: Throwable) = {
    //println("...restart completed!")
    super.postRestart(reason)
  }

  override def persistenceId = "data-actor"
  val log = Logging(context.system, this)
  
  // key: id of tweet value: hastags
  var edgesMap = Map[(String, String), ZonedDateTime]()
  // key: hastag value: degree
  var degreeMap = Map[String, Int]()

  var upperBoundWindow = ZonedDateTime.now().minusYears(30)
  var lowerBoundWindow = upperBoundWindow.minusSeconds(60)
  var state = GraphState(edgesMap, degreeMap, upperBoundWindow, upperBoundWindow.minusSeconds(60))

  def updateState(event: Evt): Unit =
    state = state.updated(event)
  
  val receiveRecover: Receive = {
    case evt: Evt                                 => updateState(evt)
    case SnapshotOffer(_, snapshot: GraphState)   => state = snapshot
  }

  val receiveCommand: Receive = {
    case Cmd(tweet) =>
      persist(Evt(tweet))(updateState)
      persist(Evt(tweet)) { event =>
        updateState(event)
        context.system.eventStream.publish(event)
      }
      //log.debug(s"Recieved command")
      val future = (sink ?
        { if (state.degreeMap.size == 0) CurrentAverageDegree(0.0) 
          else CurrentAverageDegree(state.degreeMap.values.foldLeft(0)(_ + _).toDouble / state.degreeMap.size.toDouble) }
        )
      future pipeTo sender
    case "snap"  => saveSnapshot(state)
    case "print" => println(state)
  }
}

object TweetDistributorActor {
  case class JsonObject(json: JsValue)
}

class TweetDistributorActor(dataActor: ActorRef, displayActor: ActorRef) extends Actor {
  import akka.pattern.ask
  import scala.concurrent.duration._
  import TweetDistributorActor._
  import DataActor._
  import FileWriterActor._
  
  implicit val timeout = Timeout(100.seconds)
  val log = Logging(context.system, this)

  def receive = {
    case JsonObject(json) =>
      try {
        val formatter = DateTimeFormatter.ofPattern("E MMM dd HH:mm:ss Z yyyy");
        val hashtagset = (json \ "entities" \ "hashtags" \\ "text").map(e => e.toString).toSet
        val created_at = ZonedDateTime.parse((json \ "created_at").get.toString.replace("\"", ""), formatter);
        (dataActor ? Cmd(Tweet(created_at, hashtagset))) pipeTo sender
        //log.debug("Add new tweet to graph")
      } catch {
        case e: Exception => log.warning("Exception: " + e + "\n" + json)
      }
      context.stop(self)
  }
}

object CleanerActor {
  case class Line(line: String)
}

class CleanerActor(dataActor: ActorRef, displayActor: ActorRef) extends Actor {
  import CleanerActor._
  import TweetDistributorActor._

  val log = Logging(context.system, this)
  val watched = scala.collection.mutable.ArrayBuffer.empty[ActorRef]
  var count = 0

  def receive = {
    case Line(line) =>
      try {
        val json = Json.parse(line)
        val limitOption = (json \ "limit").asOpt[JsObject]
        if (!limitOption.isDefined) {
          // Creating new actor for every tweet, great for concurrency, may be has overhead of creating new actor
          val distributor = context.actorOf(Props(classOf[TweetDistributorActor], dataActor, displayActor))
          context.watch(distributor)
          watched += distributor
          distributor ! JsonObject(json)
          count = count + 1
          sender() ! Done
          //log.debug(s"Got tweet: $count")
        }
      } catch {
        case e: Exception => log.warning("Exception: " + e)
      }
    case Terminated(who) =>
      watched -= who
      if (watched.isEmpty) {
        //context.stop(self)
        log.info("ALL DEAD")
      }
  }
}

object ReaderActor {
  case class FilePath (path: String)
}

class StreamingReaderActor(dataActor: ActorRef, displayActor: ActorRef) extends Actor {
  import CleanerActor._
  import ReaderActor._

  val log = Logging(context.system, this)
  val child = context.actorOf(Props(classOf[CleanerActor], dataActor, displayActor))
  context.watch(child)
  
  def receive = {
    case FilePath(path) =>
      val file = new File(path)
      val listener = new TailerListenerAdapter {
        override def handle(line: String): Unit = {
          child ! Line(line)
        }
      }
      val tailer = Tailer.create(file, listener, 20, false);
    case Terminated(who) =>
      context.stop(self)
  }
}

class ReaderActor(dataActor: ActorRef, displayActor: ActorRef) extends Actor {
  import CleanerActor._
  import ReaderActor._

  val log = Logging(context.system, this)
  val child = context.actorOf(Props(classOf[CleanerActor], dataActor, displayActor))
  context.watch(child)
  var count = 0

  def receive = {
    case FilePath(path) =>
      fromFile(path)
        .getLines
        .foreach { line => child ! Line(line); count = count + 1 }
      child ! akka.actor.PoisonPill
      //log.debug("Sent all lines")
    case Done =>
      count = count - 1
      if (count == 0) {
        //log.debug(s"${self.path.name} Processed all tweets")
        //context.stop(self)
      }
      //log.debug(s"Remaining: $count")
    case Terminated(who) =>
      //context.stop(self)
  }
}

object SupervisorActor {
  case object TheSecretFateOfAllLife
}

class SupervisorActor(inputPath: String, outputPath: String) extends Actor {
/**
  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
      case _: ArithmeticException      => Resume
      case _: NullPointerException     => Restart
      case _: IllegalArgumentException => Stop
      case _: NoSuchElementException   => Resume
      //case _: NoSuchElementException   => Escalate
      case _: Exception                => Escalate
    }
*/
  import ReaderActor._
  import SupervisorActor._

  val log = Logging(context.system, this)
  val watched = scala.collection.mutable.ArrayBuffer.empty[ActorRef]
  
  def receive = {
    case TheSecretFateOfAllLife =>
      val displayActor = context.actorOf(Props(classOf[FileWriterActor], outputPath), name = "displayActor")
      val dataActor = context.actorOf(Props(classOf[DataActor], displayActor), name = "dataActor")
      val readerActor = context.actorOf(Props(classOf[ReaderActor], dataActor, displayActor), name = "reader")
      //val readerActor = context.actorOf(Props(classOf[StreamingReaderActor], dataActor, displayActor), name = "reader")
      val listener = context.actorOf(Props(classOf[Listener]), name = "deadMansChest")
      context.system.eventStream.subscribe(listener, classOf[DeadLetter])
      readerActor ! FilePath(inputPath)

      context.watch(readerActor)
      context.watch(dataActor)
      context.watch(displayActor)
      log.info("Started all top actors")

    case Terminated(who) =>
      log.warning("Terminated " + who)
      //watched -= who
      //if (watched.isEmpty) {
        context.stop(self)
        log.info("ALL DEAD")
        context.system.shutdown()
      //}
    case _ => //context.system.shutdown() //context.stop(self)
  }
}

class Listener extends Actor {
  def receive = {
    case d: DeadLetter =>
      println(s"Another dead letter: $d")
  }
}

object Main extends App {
  import SupervisorActor._

  if (args.length != 2) {
    println("")
  }
  val config = ConfigFactory.load()
     //.withValue("akka.loglevel", ConfigValueFactory.fromAnyRef("INFO"))
     //.withValue("akka.stdout-loglevel", ConfigValueFactory.fromAnyRef("INFO"))
     .withValue("akka.loglevel", ConfigValueFactory.fromAnyRef("DEBUG"))
     .withValue("akka.stdout-loglevel", ConfigValueFactory.fromAnyRef("DEBUG"))
     .withValue("akka.persistence.journal.plugin", ConfigValueFactory.fromAnyRef("akka.persistence.journal.leveldb"))
     .withValue("akka.persistence.snapshot-store.plugin", ConfigValueFactory.fromAnyRef("akka.persistence.snapshot-store.local"))
     .withValue("akka.persistence.journal.leveldb.dir", ConfigValueFactory.fromAnyRef("target/example/journal"))
     .withValue("akka.persistence.snapshot-store.local.dir", ConfigValueFactory.fromAnyRef("target/example/snapshots"))
     
  val system = ActorSystem("HashGraphSystem", config)
  // default Actor constructor
  val supervisorActor = system.actorOf(Props(classOf[SupervisorActor], args(0), args(1)), name = "riyad")
  supervisorActor ! TheSecretFateOfAllLife
  //system.terminate
}
