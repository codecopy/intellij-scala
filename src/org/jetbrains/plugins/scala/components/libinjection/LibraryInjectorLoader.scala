package org.jetbrains.plugins.scala.components.libinjection

import java.io._
import java.net.URL
import java.util
import javax.swing.event.HyperlinkEvent

import com.intellij.ide.plugins.cl.PluginClassLoader
import com.intellij.notification._
import com.intellij.openapi.application.{ApplicationManager, ModalityState}
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module._
import com.intellij.openapi.project.{DumbService, Project}
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.{Library, LibraryTablesRegistrar}
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.{VirtualFile, VirtualFileManager}
import org.jetbrains.plugins.scala.components.ScalaPluginVersionVerifier
import org.jetbrains.plugins.scala.components.ScalaPluginVersionVerifier.Version
import org.jetbrains.plugins.scala.debugger.evaluation.ScalaEvaluatorCompileHelper
import org.jetbrains.plugins.scala.util.ScalaUtil

case class InjectorPersistentCache(pluginVersion: Version, cache: java.util.HashMap[String, JarManifest])

class LibraryInjectorLoader(val project: Project) extends ProjectComponent {

  class DynamicClassLoader(urls: Array[URL], parent: ClassLoader) extends java.net.URLClassLoader(urls, parent) {
    def addUrl(url: URL) = {
      super.addURL(url)
    }
  }

  type AttributedManifest = (JarManifest, Seq[InjectorDescriptor])
  type ManifestToDescriptors = Seq[AttributedManifest]

  val MAX_JARS               = 16 // dirty hack to avoid slowdowns on enormous libs such as scala-plugin's unmanaged-jars
  val HELPER_LIBRARY_NAME    = "scala-plugin-dev"
  val INJECTOR_MANIFEST_NAME = "intellij-compat.xml"
  val INJECTOR_MODULE_NAME   = "ijscala-plugin-injector-compile.iml" // TODO: use UUID
  val myInjectorCacheDir     = new File(ScalaUtil.getScalaPluginSystemPath + "injectorCache/")
  val myInjectorCacheIndex   = new File(ScalaUtil.getScalaPluginSystemPath + "injectorCache/libs.index")
  private val myClassLoader  = new DynamicClassLoader(Array(myInjectorCacheDir.toURI.toURL), this.getClass.getClassLoader)
  private val LOG = Logger.getInstance(getClass)
  private val GROUP = new NotificationGroup("Injector", NotificationDisplayType.STICKY_BALLOON, false)

  // reset cache if plugin has been updated
  // cache: jarFilePath -> jarManifest
  private var jarCache: InjectorPersistentCache = null

  override def projectClosed(): Unit = {
    saveJarCache(jarCache, myInjectorCacheIndex)
  }

  override def projectOpened(): Unit = {
    jarCache = verifyLibraryCache(loadJarCache(myInjectorCacheIndex))
    loadCachedInjectors()
    DumbService.getInstance(project).smartInvokeLater(toRunnable(rescanAllJars()))
//    invokeLater {
//      rescanAllJars()
//    }
  }

  override def initComponent(): Unit = {
    myInjectorCacheDir.mkdirs()
  }

  override def disposeComponent(): Unit = {

  }

  override def getComponentName: String = "ScalaLibraryInjectorLoader"

  @inline def invokeLater(f: => Unit) = ApplicationManager.getApplication.invokeLater(toRunnable(f))

  @inline def toRunnable(f: => Unit) = new Runnable { override def run(): Unit = f }

  @inline def inReadAction(f: => Unit) = ApplicationManager.getApplication.runReadAction(toRunnable(f))

  @inline def inWriteAction[T](f: => T) = invokeLater(ApplicationManager.getApplication.runWriteAction(toRunnable(f)))

  private def loadJarCache(f: File): InjectorPersistentCache = {
    try {
      val stream = new ObjectInputStream(new BufferedInputStream(new FileInputStream(f)))
      stream.readObject().asInstanceOf[InjectorPersistentCache]
    } catch {
      case e: Throwable =>
        LOG.warn(s"Failed to load injector cache, continuing with empty(${e.getMessage})")
        InjectorPersistentCache(ScalaPluginVersionVerifier.getPluginVersion.get, new util.HashMap())
    }
  }

  private def saveJarCache(c: InjectorPersistentCache, f: File) = {
    val stream = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(f)))
    try {
      stream.writeObject(c)
    } catch {
      case e: Throwable =>
        LOG.error("Failed to save injector cache", e)
    } finally {
      stream.close()
    }
  }

  private def verifyLibraryCache(cache: InjectorPersistentCache): InjectorPersistentCache = {
    if (ScalaPluginVersionVerifier.getPluginVersion.exists(_ != cache.pluginVersion))
      InjectorPersistentCache(ScalaPluginVersionVerifier.getPluginVersion.get, new util.HashMap())
    else
      cache
  }

  private def loadCachedInjectors() = {
    import scala.collection.JavaConversions._
    val libs = LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraries
    val allProjectJars = libs.flatMap(getJarsFromLibrary).map(_.getPath).toSet
    val cachedProjectJars = jarCache.cache.filter(c=>allProjectJars.contains(c._1.substring(0, c._1.length-1)+"!/")).values
    for (manifest <- cachedProjectJars) {
      if (isJarCacheUpToDate(manifest))
        loadInjectors(manifest)
      else
        jarCache.cache.remove(manifest.jarPath)
    }
  }

  private def rescanAllJars() = {
    import scala.collection.JavaConversions._
    val libs = LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraries
    val allJars = libs.flatMap(getJarsFromLibrary)
    val jarsWithManifest = allJars.flatMap(l => extractLibraryManifest(l))
    val outdatedJars = jarsWithManifest.filterNot(lm => isJarCacheUpToDate(lm))
    val candidates = outdatedJars.map(manifest => manifest -> findMatchingInjectors(manifest))
    askUser(candidates)
  }

  def getJarsFromLibrary(library: Library): Seq[VirtualFile] = {
    val files = library.getFiles(OrderRootType.CLASSES)
    if (files.length < MAX_JARS)
      files.toSeq
    else
      Seq.empty
  }

  def isJarCacheUpToDate(manifest: JarManifest): Boolean = {
    val jarFile = new File(manifest.jarPath)
    jarFile.exists() &&
      jarFile.isFile &&
      (jarFile.lastModified() == manifest.modTimeStamp) &&
      getLibraryCacheDir(jarFile).list().nonEmpty
  }

  def extractLibraryManifest(jar: VirtualFile, skipIncompatible: Boolean = true): Option[JarManifest] = {
    val manifestFile = Option(jar.findFileByRelativePath(s"META-INF/$INJECTOR_MANIFEST_NAME"))
    manifestFile
      .map(JarManifest.deserialize(_, jar))
      .filterNot(m => skipIncompatible && findMatchingInjectors(m).isEmpty)
  }

  private def compileInjectorFromLibrary(sources: Seq[File], outDir: File, m: Module): Seq[File] = {
    val res = sources.flatMap(ScalaEvaluatorCompileHelper.instance(project).compile(_, m, outDir)).map(_._1)
    val parentDir = sources.headOption
    sources.foreach(_.delete())
    parentDir.foreach(_.delete())
    res
  }

  private def loadInjectors(jarManifest: JarManifest) = {
    myClassLoader.addUrl(getLibraryCacheDir(new File(jarManifest.jarPath)).toURI.toURL)

    // TODO
  }

  private def findMatchingInjectors(libraryManifest: JarManifest): Seq[InjectorDescriptor] = {
    val curVer = ScalaPluginVersionVerifier.getPluginVersion
    libraryManifest.pluginDescriptors
      // FIXME: fix debug version(VERSION) handling
//      .find(d => curVer.get > d.since && curVer.get < d.until)
//        .map(_.injectors).getOrElse(Seq.empty)
      .flatMap(_.injectors)
  }

  // don't forget to remove temp directory after compilation
  private def extractInjectorSources(jar: File, injectorDescriptor: InjectorDescriptor): Seq[File] = {
    val tmpDir = File.createTempFile("inject", "")
    def copyToTmpDir(virtualFile: VirtualFile): File = {
      val target = new File(tmpDir, virtualFile.getName)
      val targetStream = new BufferedOutputStream(new FileOutputStream(target))
      try {
        target.createNewFile()
        FileUtil.copy(virtualFile.getInputStream, targetStream)
        target
      } finally {
        targetStream.close()
      }
    }
    if (tmpDir.delete() && tmpDir.mkdir()) {
      val root = VirtualFileManager.getInstance().findFileByUrl("jar://"+jar.getAbsolutePath+"!/")
      if (root != null) {
        injectorDescriptor.sources.flatMap(path => {
          Option(root.findFileByRelativePath(path)).map { f =>
            if (f.isDirectory)
              f.getChildren.filter(!_.isDirectory).map(copyToTmpDir).toSeq
            else
              Seq(copyToTmpDir(f))
          }.getOrElse(Seq.empty)
        })
      } else {
        LOG.error(s"Failed to locate source jar file - $jar")
        Seq.empty
      }
    } else {
      LOG.error(s"Failed to extract injector sources for ${injectorDescriptor.impl} - failed to create directory $tmpDir")
      Seq.empty
    }
  }

  private def askUser(candidates: ManifestToDescriptors) = {
    val message = s"Some of your project's libraries have IDEA support features.\nWould you like to load them?" +
      s"""<p/><a href="Yes">Yes</a>""" +
      s"""<p/><a href="No">No</a>"""
    val listener = new NotificationListener {
      override def hyperlinkUpdate(notification: Notification, event: HyperlinkEvent): Unit = {
        notification.expire()
        if (event.getDescription == "Yes")
          compile(showReviewDialogAndFilter(candidates))
      }
    }
    GROUP.createNotification("ASDAF", message, NotificationType.INFORMATION, listener).notify(project)
  }

  private def showReviewDialogAndFilter(candidates: ManifestToDescriptors): ManifestToDescriptors  = {
    candidates.filter { a=>
      val dialog = new InjectorReviewDialog(project, a)
      dialog.showAndGet()
    }
  }

  private def compile(data: ManifestToDescriptors) = {
    withHelperModule { module =>
      for ((manifest, injectors) <- data) {
        for (injectorDescriptor <- injectors) {
          try {
            compileInjectorFromLibrary(
              extractInjectorSources(new File(manifest.jarPath), injectorDescriptor),
              getLibraryCacheDir(new File(manifest.jarPath.dropRight(1))),
              module
            )
            loadInjectors(manifest)
            jarCache.cache.put(manifest.jarPath, manifest)
          } catch {
            case e: Throwable =>
              LOG.error("Failed to compile injector", e)
          }
        }
      }
    }
  }

  def getLibraryCacheDir(jar: File): File = {
    val f = new File(myInjectorCacheDir,
      (jar.getName + ScalaPluginVersionVerifier.getPluginVersion.get.toString).replaceAll("\\.", "_"))
    f.mkdir()
    f
  }

  def createIdeaModule(): Module = {
    import org.jetbrains.plugins.scala.project._

    import scala.collection.JavaConversions._

    val model  = ModuleManager.getInstance(project).getModifiableModel
    val module = model.newModule(INJECTOR_MODULE_NAME, JavaModuleType.getModuleType.getId)
    val urls   = this.getClass.getClassLoader.asInstanceOf[PluginClassLoader].getUrls.map(u=>s"jar://${u.getFile}!/")
    // get application classloader urls using reflection :(
    val parentUrls = ApplicationManager.getApplication.getClass.getClassLoader.getClass.getDeclaredMethods
      .find(_.getName == "getUrls")
      .map(_.invoke(ApplicationManager.getApplication.getClass.getClassLoader)
        .asInstanceOf[java.util.List[URL]])
    parentUrls.map(_.map(u=>s"jar://${u.getFile}!/")).foreach(urls.addAll(_))
    val lib    = module.createLibraryFromJar(urls, HELPER_LIBRARY_NAME)
    module.configureScalaCompilerSettingsFrom("Default", Seq())
    module.attach(lib)
    module.attach(project.modulesWithScala.head.scalaSdk.get)
    module.libraries
    model.commit()
    module
  }

  private def removeIdeaModule() = {
    // TODO: remove libraries as well
    val model  = ModuleManager.getInstance(project).getModifiableModel
    val module = model.findModuleByName(INJECTOR_MODULE_NAME)
    if (module != null) {
      model.disposeModule(module)
      val libsModel = LibraryTablesRegistrar.getInstance().getLibraryTable(project).getModifiableModel
      val library   = libsModel.getLibraryByName(HELPER_LIBRARY_NAME)
      if (library == null) {
        libsModel.removeLibrary(library)
        libsModel.commit()
      } else {
        LOG.warn(s"Failed to remove helper library - $HELPER_LIBRARY_NAME not found")
      }
    } else {
      LOG.warn(s"Failed to remove helper module - $INJECTOR_MODULE_NAME not found")
    }
  }

  private def withHelperModule[T](f: Module => T) = {
    var module: Module = null
    var res: Any = null
    ApplicationManager.getApplication.invokeAndWait(
      toRunnable(inWriteAction {
        module = createIdeaModule()
        res = f(module)
        removeIdeaModule()
      }), ModalityState.NON_MODAL
    )
    res.asInstanceOf[T]
  }

}