package net.elodina.mesos.zipkin.http

import java.io.{FileInputStream, File}
import javax.servlet.http.{HttpServletResponse, HttpServletRequest, HttpServlet}

import net.elodina.mesos.zipkin.mesos.Scheduler
import net.elodina.mesos.zipkin.utils.Util
import net.elodina.mesos.zipkin.components._
import org.apache.log4j.Logger
import play.api.libs.json.Json
import net.elodina.mesos.zipkin.utils.{Range => URange}

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.collection.JavaConversions._
import scala.util.Try

case class ApiResponse[E <: ZipkinComponent](success: Boolean, message: String, value: Option[List[E]] = None)

object ApiResponse {
  implicit val format = Json.format[ApiResponse]
}

class Servlet extends HttpServlet {

  val logger = Logger.getLogger(HttpServer.getClass)

  override def doPost(request: HttpServletRequest, response: HttpServletResponse): Unit = doGet(request, response)

  override def doGet(request: HttpServletRequest, response: HttpServletResponse): Unit = {
    val url = request.getRequestURL + (if (request.getQueryString != null) "?" + request.getQueryString else "")
    logger.info("handling - " + url)

    try {
      handle(request, response)
      logger.info("finished handling")
    } catch {
      case e: Exception =>
        logger.error("error handling", e)
        response.sendError(500, "" + e)
    }
  }

  def handle(request: HttpServletRequest, response: HttpServletResponse): Unit = {
    val uri = request.getRequestURI

    //handling incoming request
    if (uri.startsWith("/jar/")) downloadFile(HttpServer.jar, response)
    else if (uri.startsWith("/collector/")) downloadFile(HttpServer.collector, response)
    else if (uri.startsWith("/query/")) downloadFile(HttpServer.query, response)
    else if (uri.startsWith("/web/")) downloadFile(HttpServer.web, response)
    else if (uri.startsWith("/collector-conf/")) downloadConfigFile(uri, response)
    else if (uri.startsWith("/query-conf/")) downloadConfigFile(uri, response)
    else if (uri.startsWith("/api")) handleApi(request, response)
    else response.sendError(404)
  }

  def downloadFile(file: File, response: HttpServletResponse) {
    response.setContentType("application/zip")
    response.setHeader("Content-Length", "" + file.length())
    response.setHeader("Content-Disposition", "attachment; filename=\"" + file.getName + "\"")
    Util.copyAndClose(new FileInputStream(file), response.getOutputStream)
  }

  def downloadConfigFile(uri: String, response: HttpServletResponse) {
    val confFileName = uri.split("/").last
    HttpServer.collectorConfigFiles.find(_.getName == confFileName) match {
      case Some(file) => downloadFile(file, response)
      case None => response.sendError(404)
    }
  }

  private def fetchPathPart(request: HttpServletRequest, partToCut: String): String = {
    val uri: String = request.getRequestURI.substring(partToCut.length)
    if (uri.startsWith("/")) uri.substring(1) else uri
  }

  def handleApi(request: HttpServletRequest, response: HttpServletResponse) {
    response.setContentType("application/json; charset=utf-8")

    val uri = fetchPathPart(request, "/api")
    if (uri.startsWith("collector")) handleZipkin(request, response, { Scheduler.cluster.collectors }, {Collector(_)}, "collector")
    else if (uri.startsWith("query")) handleZipkin(request, response, { Scheduler.cluster.queryServices }, { QueryService(_) }, "query")
    else if (uri.startsWith("web")) handleZipkin(request, response, { Scheduler.cluster.webServices }, { WebService(_) }, "web")
    else response.sendError(404)
  }

  def handleZipkin[E <: ZipkinComponent](request: HttpServletRequest,
                                         response: HttpServletResponse,
                                         fetchCollection: => collection.mutable.Buffer[E],
                                         instantiateComponent: String => E,
                                         uriPath: String) {
    val uri = fetchPathPart(request, s"/api/$uriPath")
    if (uri == "list") handleList(request, response, fetchCollection)
    else if (uri == "add") handleAdd(request, response, fetchCollection, instantiateComponent, uriPath)
    else if (uri == "start") handleStart(request, response, fetchCollection, uriPath)
    else if (uri == "stop") handleStop(request, response, fetchCollection, uriPath)
    else if (uri == "remove") handleRemove(request, response, fetchCollection, uriPath)
    else if (uri == "config") handleConfig(request, response, fetchCollection, uriPath)
    else response.sendError(404)
  }

  private def handleAdd[E <: ZipkinComponent](request: HttpServletRequest, response: HttpServletResponse,
                                              fetchCollection: => mutable.Buffer[E], instantiateComponent: String => E, componentName: String) {
    val idExpr = request.getParameter("id")
    val ids = Util.expandIds(idExpr, fetchCollection)
    val cpus = Option(request.getParameter("cpu"))
    val mem = Option(request.getParameter("mem"))
    val constraints = Option(request.getParameter("constraints"))
    val ports = Option(request.getParameter("port"))
    val flags = Option(request.getParameter("flags"))
    val envVariables = Option(request.getParameter("envvariables"))
    val configFile = Option(request.getParameter("configfile"))
    val existing = ids.filter(id => fetchCollection.exists(_.id == id))
    if (existing.nonEmpty) {
      response.getWriter.println(Json.toJson(ApiResponse(success = false, s"Zipkin $componentName instance(s) ${existing.mkString(",")} already exist", None)))
    } else {
      val components = ids.map { id =>
        val component = instantiateComponent(id)
        cpus.foreach(cpus => component.config.cpus = cpus.toDouble)
        mem.foreach(mem => component.config.mem = mem.toDouble)
        ports.foreach(ports => component.config.ports = URange.parseRanges(ports))
        component.constraints ++= Constraint.parse(constraints.getOrElse("hostname=unique"))
        flags.foreach(flags => component.config.flags = Util.parseMap(flags))
        envVariables.foreach(ev => component.config.envVariables = Util.parseMap(ev))
        configFile.foreach(cf => component.config.configFile = Some(cf))
        fetchCollection += component
        component
      }
      Scheduler.cluster.save()
      response.getWriter.println(Json.toJson(ApiResponse(success = true, s"Added servers $idExpr", Some(components))))
    }
  }

  private def handleStart[E <: ZipkinComponent](request: HttpServletRequest, response: HttpServletResponse,
                                                fetchCollection: => mutable.Buffer[E], componentName: String) {
    processExistingInstances(request, response, fetchCollection, componentName, { (ids, idExpr) =>
      val timeout = Duration(Option(request.getParameter("timeout")).getOrElse("60s"))
      val components = ids.flatMap { id =>
        fetchCollection.find(_.id == id).map { component =>
          if (component.state == Added) {
            component.state = Stopped
            logger.info(s"Starting $componentName instance $id")
          } else logger.warn(s"Zipkin $componentName instance $id already started")
          component
        }
      }

      if (timeout.toMillis > 0) {
        val ok = fetchCollection.forall(_.waitFor(Running, timeout))
        if (ok) response.getWriter.println(Json.toJson(ApiResponse(success = true, s"Started $componentName instance(s) $idExpr", Some(components))))
        else response.getWriter.println(Json.toJson(ApiResponse(success = false, s"Start $componentName instance(s) $idExpr timed out after $timeout", None)))
      }
    })
  }

  private def handleStop[E <: ZipkinComponent](request: HttpServletRequest, response: HttpServletResponse,
                                               fetchCollection: => mutable.Buffer[E], componentName: String) {
    processExistingInstances(request, response, fetchCollection, componentName, { (ids, idExpr) =>
      val components = ids.flatMap { id =>
        fetchCollection.find(_.id == id).map(Scheduler.stopInstance)
      }
      Scheduler.cluster.save()
      response.getWriter.println(Json.toJson(ApiResponse(success = true, s"Stopped $componentName instance(s) $idExpr", Some(components))))
    })
  }

  private def handleRemove[E <: ZipkinComponent](request: HttpServletRequest, response: HttpServletResponse,
                                                 fetchCollection: => mutable.Buffer[E], componentName: String) {
    processExistingInstances(request, response, fetchCollection, componentName, { (ids, idExpr) =>
      val components = ids.flatMap { id =>
        fetchCollection.find(_.id == id).map {
          Scheduler.stopInstance(_)
          fetchCollection -= _
        }
      }
      Scheduler.cluster.save()
      response.getWriter.println(Json.toJson(ApiResponse(success = true, s"Removed $componentName instance(s) $idExpr", Some(components))))
    })
  }

  private def handleConfig[E <: ZipkinComponent](request: HttpServletRequest, response: HttpServletResponse,
                                                 fetchCollection: => mutable.Buffer[E], componentName: String) {
    processExistingInstances(request, response, fetchCollection, componentName, { (ids, idExpr) =>
      val components = ids.flatMap { id =>
        fetchCollection.find(_.id == id).map { component =>
          request.getParameterMap.toMap.foreach {
            //TODO: parsing error handling
            case ("constraints", values) => component.constraints ++= Try(Constraint.parse(values.head)).getOrElse(Map())
            case ("port", Array(ports)) => component.config.ports = URange.parseRanges(ports)
            case ("cpu", values) => component.config.cpus = Try(values.head.toDouble).getOrElse(component.config.cpus)
            case ("mem", values) => component.config.mem = Try(values.head.toDouble).getOrElse(component.config.mem)
            case ("flags", values) => component.config.flags = Try(Util.parseMap(values.head)).getOrElse(component.config.flags)
            case ("envVariables", values) => component.config.envVariables = Try(Util.parseMap(values.head)).getOrElse(component.config.envVariables)
            case ("configFile", values) => component.config.configFile = Some(values.head)
            case other => logger.debug(s"Got invalid configuration value: $other")
          }
          component
        }
      }

      Scheduler.cluster.save()
      response.getWriter.println(Json.toJson(ApiResponse(success = true, s"Updated configuration for Zipkin $componentName instance(s) $idExpr", Some(components))))
    })
  }

  private def processExistingInstances[E <: ZipkinComponent](request: HttpServletRequest,
                                                             response: HttpServletResponse,
                                                             fetchCollection: => mutable.Buffer[E],
                                                             componentName: String,
                                                             action: (List[String], String) => Unit) {
    val idExpr = request.getParameter("id")
    val ids = Util.expandIds(idExpr, fetchCollection)
    val missing = ids.filter(id => !fetchCollection.exists(id == _.id))
    if (missing.nonEmpty) response.getWriter.println(Json.toJson(ApiResponse(success = false,
      s"Zipkin $componentName instance(s) ${missing.mkString(",")} do not exist", None)))
    else action(ids, idExpr)
  }

  private def handleList[E <: ZipkinComponent](request: HttpServletRequest, response: HttpServletResponse,
                                               fetchCollection: => mutable.Buffer[E]) {
    response.getWriter.println(Json.toJson(ApiResponse(success = true, "", Some(fetchCollection.toList))))
  }
}