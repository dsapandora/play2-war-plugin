package com.github.play2war.plugin.it

import java.net.URL
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.scalatest.matchers._
import org.scalatest._
import org.codehaus.cargo.container.InstalledLocalContainer
import org.codehaus.cargo.container.installer.ZipURLInstaller
import org.codehaus.cargo.generic.configuration.DefaultConfigurationFactory
import org.codehaus.cargo.container.ContainerType
import org.codehaus.cargo.container.configuration.ConfigurationType
import org.codehaus.cargo.generic.DefaultContainerFactory
import org.codehaus.cargo.container.configuration.LocalConfiguration
import com.gargoylesoftware.htmlunit._
import com.gargoylesoftware.htmlunit.html._
import com.gargoylesoftware.htmlunit.util._
import org.codehaus.cargo.container.deployable.WAR
import org.codehaus.cargo.container.property._
import org.codehaus.cargo.util.log._
import scala.collection.immutable._
import scala.collection.JavaConverters._
import org.apache.commons.io.FileUtils
import java.io.File

trait WarContext {
  
  def context = "/p2wsample"

}

trait ServletContainer {

  protected val WAR_KEY = "war.servlet"

  def keyServletContainer: String

  def keyWarPath: String = WAR_KEY + keyServletContainer

}

trait Servlet30Container extends ServletContainer {
  def keyServletContainer = "30"
}

trait Servlet25Container extends ServletContainer {
  def keyServletContainer = "25"
}

trait CargoContainerManager extends WarContext {

  var container: InstalledLocalContainer = _

  def getContainer = container

  def setContainer(container: InstalledLocalContainer) = this.container = container

  def containerUrl: String

  def containerFileNameInCloudbeesCache: Option[String] = None

  def containerName: String

  def getJavaVersion: String

  def startContainer(warPath: String, stopOnExit: Boolean) {
    println("WAR file to deploy: " + warPath)

    val containerUrlToDownload: String = containerFileNameInCloudbeesCache.flatMap { c =>
      val path = "/private/play-war/cargo-containers/" + c
      if (new File(path).exists) {
        println("Local container found: " + path)
        Option("file://" + path)
      } else {
        println("Local container not found: " + path)
        None
      }
    }.getOrElse(containerUrl)

    println("Download container " + containerName + " from " + containerUrlToDownload + " ...")
    val installer = new ZipURLInstaller(new URL(containerUrlToDownload))
    println("Download container done")

    Option(System.getenv("http_proxy")).map { systemProxy =>
      println(s"Using system proxy '$systemProxy'")
      val uri = new java.net.URI(systemProxy)
      val proxy = new org.codehaus.cargo.container.installer.Proxy()
      proxy.setHost(uri.getHost)
      proxy.setPort(uri.getPort)
      installer.setProxy(proxy)
    }

    println("Install container ...")
    installer.install()
    println("Install container done")

    val configuration: LocalConfiguration = new DefaultConfigurationFactory().createConfiguration(
      containerName, ContainerType.INSTALLED, ConfigurationType.STANDALONE).asInstanceOf[LocalConfiguration]

    configuration.setProperty(GeneralPropertySet.LOGGING, LoggingLevel.MEDIUM.getLevel)

    getJavaVersion match {
      case "java6" => // Nothing, use current JVM
      case "java7" => {
        val java7Home = Option(System.getProperty("java7.home")).map(p => p).getOrElse(throw new RuntimeException("-Djava7.home not defined"))
        configuration.setProperty(GeneralPropertySet.JAVA_HOME, java7Home)
      }
    }

    val container =
      new DefaultContainerFactory().createContainer(
        containerName, ContainerType.INSTALLED, configuration).asInstanceOf[InstalledLocalContainer]

    println("Configure container")
    container.setHome(installer.getHome)
    container.setLogger(new SimpleLogger)

    val war = new WAR(warPath.toString)
    war.setContext(context)
    configuration.addDeployable(war)

    println("Start the container " + containerName)
    setContainer(container)
    container.start()

    if (stopOnExit) {
      Runtime.getRuntime.addShutdownHook(new Thread() {
        override def run() {
          stopContainer()
        }
      })
    }
  }


  def stopContainer() {
    val maybeContainer = Option(getContainer)
    maybeContainer map { container =>
      println("Stop the container " + container.getHome)
      container.stop()
    } getOrElse {
      println("Container already stopped")
    }
  }
}

trait CargoContainerManagerFixture extends BeforeAndAfterAll with CargoContainerManager {
  self: Suite =>

  def keyWarPath: String

  abstract override def beforeAll(configMap: Map[String, Any]) {
    val warPath = configMap.get(keyWarPath).getOrElse(throw new Exception("no war path defined")).asInstanceOf[String]

    startContainer(warPath, stopOnExit = false)
  }

  abstract override def afterAll() {
    stopContainer()
  }
}

trait JavaVersion {

  def getJavaVersion: String

}

trait Java6 extends JavaVersion {

  override def getJavaVersion = "java6"

}

trait Java7 extends JavaVersion {

  override def getJavaVersion = "java7"

}
