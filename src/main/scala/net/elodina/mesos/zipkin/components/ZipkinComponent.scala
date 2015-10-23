package net.elodina.mesos.zipkin.components

import java.util.{UUID, Date}
import com.google.protobuf.ByteString
import net.elodina.mesos.zipkin.Config
import net.elodina.mesos.zipkin.http.HttpServer
import net.elodina.mesos.zipkin.utils.{Util, Range}
import org.apache.mesos.Protos
import org.apache.mesos.Protos._
import play.api.libs.functional.syntax._
import play.api.libs.json.{JsValue, Json, Writes, _}
import play.api.libs.json.Writes._
import play.api.libs.json.Reads._
import scala.collection.{mutable, Map}
import scala.collection.immutable.{Map => IMap}
import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration

case class TaskConfig(var cpus: Double = 1, var mem: Double = 256, var ports: List[Range] = Nil,
                      var env: IMap[String, String] = IMap(),
                      var flags: IMap[String, String] = IMap(),
                      var configFile: Option[String] = None,
                      var hostname: String = "")

object TaskConfig {

  implicit val reader = (
      (__ \ 'cpu).read[Double] and
      (__ \ 'mem).read[Double] and
      (__ \ 'ports).read[String].map(Range.parseRanges) and
      (__ \ 'env).read[IMap[String, String]] and
      (__ \ 'flags).read[IMap[String, String]] and
      (__ \ 'configFile).readNullable[String] and
      (__ \ 'hostname).read[String])(TaskConfig.apply _)

  implicit val writer = new Writes[TaskConfig] {
    def writes(tc: TaskConfig): JsValue = {
      Json.obj(
        "cpu" -> tc.cpus,
        "mem" -> tc.mem,
        "ports" -> tc.ports.mkString(","),
        "env" -> tc.env,
        "flags" -> tc.flags,
        "configFile" -> tc.configFile,
        "hostname" -> tc.hostname
      )
    }
  }
}

sealed abstract class ZipkinComponent(val id: String = "0") {

  var state: State = Added
  private[zipkin] val constraints: mutable.Map[String, List[Constraint]] = new mutable.HashMap[String, List[Constraint]]
  @volatile var task: Task = null
  var config = setDefaultConfig()

  def setDefaultConfig(): TaskConfig

  def componentName: String

  def configurePort(port: Long): Unit

  def fetchPort(): Option[String]

  /* Whatever should happen with task configuration after it is configured by user should go here. */
  def postConfig(): Unit

  def url: String = s"http://${config.hostname}:${fetchPort().getOrElse("")}"

  def createTask(offer: Offer): TaskInfo = {
    val port = getPort(offer).getOrElse(throw new IllegalStateException("No suitable port"))

    val name = s"zipkin-$componentName-${this.id}"
    val id = nextTaskId
    this.config.hostname = offer.getHostname
    configurePort(port)

    val taskId = TaskID.newBuilder().setValue(id).build
    TaskInfo.newBuilder().setName(name).setTaskId(taskId).setSlaveId(offer.getSlaveId)
      .setExecutor(newExecutor(s"$componentName-${this.id}"))
      .setData(ByteString.copyFromUtf8(Json.stringify(Json.toJson(this.config))))
      .addResources(Protos.Resource.newBuilder().setName("cpus").setType(Protos.Value.Type.SCALAR).setScalar(Protos.Value.Scalar.newBuilder().setValue(this.config.cpus)))
      .addResources(Protos.Resource.newBuilder().setName("mem").setType(Protos.Value.Type.SCALAR).setScalar(Protos.Value.Scalar.newBuilder().setValue(this.config.mem)))
      .addResources(Protos.Resource.newBuilder().setName("ports").setType(Protos.Value.Type.RANGES).setRanges(
        Protos.Value.Ranges.newBuilder().addRange(Protos.Value.Range.newBuilder().setBegin(port).setEnd(port))
      )).build
  }

  private[zipkin] def newExecutor(id: String): ExecutorInfo = {
    val cmd = s"java -cp ${HttpServer.jar.getName}${if (Config.debug) " -Ddebug" else ""} net.elodina.mesos.zipkin.mesos.Executor"

    val commandBuilder = CommandInfo.newBuilder()
    commandBuilder
      .addUris(CommandInfo.URI.newBuilder().setValue(s"${Config.getApi}/collector/" + HttpServer.collector.getName))
      .addUris(CommandInfo.URI.newBuilder().setValue(s"${Config.getApi}/query/" + HttpServer.query.getName))
      .addUris(CommandInfo.URI.newBuilder().setValue(s"${Config.getApi}/web/" + HttpServer.web.getName))
      .addUris(CommandInfo.URI.newBuilder().setValue(s"${Config.getApi}/jar/" + HttpServer.jar.getName))
      .addUris(CommandInfo.URI.newBuilder().setValue(s"${Config.getApi}/web-resources/" + HttpServer.webResources.getName).setExtract(true))
      .setValue(cmd)

    HttpServer.collectorConfigFiles.foreach { f =>
      commandBuilder.addUris(CommandInfo.URI.newBuilder().setValue(s"${Config.getApi}/collector-conf/" + f.getName))
    }

    HttpServer.queryConfigFiles.foreach { f =>
      commandBuilder.addUris(CommandInfo.URI.newBuilder().setValue(s"${Config.getApi}/query-conf/" + f.getName))
    }

    ExecutorInfo.newBuilder()
      .setExecutorId(ExecutorID.newBuilder().setValue(s"$componentName-$id"))
      .setCommand(commandBuilder)
      .setName(s"zipkin-$componentName-$id")
      .build
  }

  def nextTaskId: String = s"zipkin-$componentName-$id-${UUID.randomUUID()}"

  def isReconciling: Boolean = this.state == Reconciling

  def matches(offer: Offer, now: Date = new Date(), otherAttributes: String => List[String] = _ => Nil): Option[String] = {

    val offerResources = offer.getResourcesList.toList.map(res => res.getName -> res).toMap

    if (getPort(offer).isEmpty) return Some("no suitable port")

    offerResources.get("cpus") match {
      case Some(cpusResource) => if (cpusResource.getScalar.getValue < config.cpus) return Some(s"cpus ${cpusResource.getScalar.getValue} < ${config.cpus}")
      case None => return Some("no cpus")
    }

    offerResources.get("mem") match {
      case Some(memResource) => if (memResource.getScalar.getValue < config.mem) return Some(s"mem ${memResource.getScalar.getValue} < ${config.mem}")
      case None => return Some("no mem")
    }

    val offerAttributes = offer.getAttributesList.toList.foldLeft(Map("hostname" -> offer.getHostname)) { case (attributes, attribute) =>
      if (attribute.hasText) attributes.updated(attribute.getName, attribute.getText.getValue)
      else attributes
    }

    for ((name, constraints) <- constraints) {
      for (constraint <- constraints) {
        offerAttributes.get(name) match {
          case Some(attribute) => if (!constraint.matches(attribute, otherAttributes(name))) return Some(s"$name doesn't match $constraint")
          case None => return Some(s"no $name")
        }
      }
    }

    None
  }

  private[zipkin] def getPort(offer: Offer): Option[Long] = {
    val ports = Util.getRangeResources(offer, "ports").map(r => Range(r.getBegin.toInt, r.getEnd.toInt))

    if (this.config.ports == Nil) ports.headOption.map(_.start)
    else ports.flatMap(range => this.config.ports.flatMap(range.overlap)).headOption.map(_.start)
  }

  def waitFor(state: State, timeout: Duration): Boolean = {
    var t = timeout.toMillis
    while (t > 0 && this.state != state) {
      val delay = Math.min(100, t)
      Thread.sleep(delay)
      t -= delay
    }

    this.state == state
  }
}

case class Task(id: String, slaveId: String, executorId: String, attributes: IMap[String, String])

object Task {

  implicit val fmt = Json.format[Task]
}

object ZipkinComponent {

  def idFromTaskId(taskId: String): String = {
    val parts: Array[String] = taskId.split("-")
    if (parts.length < 3) throw new IllegalArgumentException(taskId)
    parts(2)
  }

  def getComponentFromTaskId(taskId: String): String = {
    val parts: Array[String] = taskId.split("-")
    if (parts.length < 3) throw new IllegalArgumentException(s"Illegal task id specified: $taskId")
    parts(1)
  }

  def writeJson[E <: ZipkinComponent](zc: E): JsValue = {
    Json.obj(
      "id" -> zc.id,
      "state" -> zc.state.toString,
      "constraints" -> Util.formatConstraints(zc.constraints),
      "task" -> Option(zc.task),
      "config" -> zc.config
    )
  }

  def configureInstance[E <: ZipkinComponent](zc: E, task: Option[Task], constraints: Map[String, List[Constraint]],
                                              state: String, config: TaskConfig): E = {
    state match {
      case "Added" => zc.state = Added
      case "Stopped" => zc.state = Stopped
      case "Staging" => zc.state = Staging
      case "Running" => zc.state = Running
      case "Reconciling" => zc.state = Reconciling
    }
    zc.task = task.orNull
    constraints.foreach(zc.constraints += _)
    zc.config.cpus = config.cpus
    zc.config.mem = config.mem
    zc.config.ports = config.ports
    zc.config.flags = config.flags
    zc.config.env = config.env
    zc.config.hostname = config.hostname
    zc.config.configFile = config.configFile
    zc
  }

  def readJson = (__ \ 'id).read[String] and
    (__ \ 'task).readNullable[Task] and
    (__ \ 'constraints).read[String].map(Constraint.parse) and
    (__ \ 'state).read[String] and
    (__ \ 'config).read[TaskConfig]
}

case class Collector(override val id: String = "0") extends ZipkinComponent(id) {

  override def componentName = "collector"

  override def setDefaultConfig(): TaskConfig = {
    TaskConfig(0.5, 256, Nil, IMap(), IMap(), Some("collector-dev.scala"))
  }

  override def configurePort(port: Long): Unit = {
    this.config.env = this.config.env + ("COLLECTOR_PORT" -> port.toString)
  }

  override def fetchPort() = this.config.env.get("COLLECTOR_PORT")

  override def postConfig(): Unit = {}
}

case class QueryService(override val id: String = "0") extends ZipkinComponent(id) {

  override def componentName = "query"

  override def setDefaultConfig(): TaskConfig = {
    TaskConfig(0.5, 256, Nil, IMap(), IMap(), Some("query-dev.scala"))
  }

  override def configurePort(port: Long): Unit = {
    this.config.env = this.config.env + ("QUERY_PORT" -> port.toString)
  }

  override def fetchPort() = this.config.env.get("QUERY_PORT")

  override def postConfig(): Unit = {}
}

case class WebService(override val id: String = "0") extends ZipkinComponent(id) {

  override def componentName = "web"

  override def setDefaultConfig(): TaskConfig = {
    TaskConfig(0.5, 256, Nil, IMap(), IMap())
  }

  override def configurePort(port: Long): Unit = {
    this.config.flags = this.config.flags + ("zipkin.web.port" -> s":${port.toString}")
  }

  override def fetchPort() = this.config.flags.get("zipkin.web.port")

  override def postConfig(): Unit = { this.config.flags = this.config.flags + ("zipkin.web.resourcesRoot" -> "resources") }
}

object Collector {
  implicit val writer = new Writes[Collector] {
    def writes(zc: Collector): JsValue = ZipkinComponent.writeJson(zc)
  }

  implicit val reader = ZipkinComponent.readJson((id, task, constraints, state, config) => {
    ZipkinComponent.configureInstance(Collector(id), task, constraints, state, config)
  })
}

object QueryService {
  implicit val writer = new Writes[QueryService] {
    def writes(zc: QueryService): JsValue = ZipkinComponent.writeJson(zc)
  }

  implicit val reader = ZipkinComponent.readJson((id, task, constraints, state, config) => {
    ZipkinComponent.configureInstance(QueryService(id), task, constraints, state, config)
  })
}

object WebService {
  implicit val writer = new Writes[WebService] {
    def writes(zc: WebService): JsValue = ZipkinComponent.writeJson(zc)
  }

  implicit val reader = ZipkinComponent.readJson((id, task, constraints, state, config) => {
    ZipkinComponent.configureInstance(WebService(id), task, constraints, state, config)
  })
}