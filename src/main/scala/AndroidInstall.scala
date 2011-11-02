import proguard.{Configuration=>ProGuardConfiguration, ProGuard, ConfigurationParser}

import sbt._
import Keys._
import AndroidKeys._
import AndroidHelpers._

import java.io.{File => JFile}

object AndroidInstall {

  private def installTask(emulator: Boolean) = (dbPath, packageApkPath) map { (dp, p) =>
    adbTask(dp.absolutePath, emulator, "install -r "+p.absolutePath)
  }

  private def uninstallTask(emulator: Boolean) = (dbPath, manifestPackage) map { (dp, m) =>
    adbTask(dp.absolutePath, emulator, "uninstall "+m)
  }

  private def aaptPackageTask: Project.Initialize[Task[File]] =
  (aaptPath, manifestPath, mainResPath, mainAssetsPath, jarPath, resourcesApkPath) map {
    (apPath, manPath, rPath, assetPath, jPath, resApkPath) =>

    Seq(apPath.absolutePath, "package", "--auto-add-overlay", "-f",
        "-M", manPath.absolutePath,
        "-S", rPath.absolutePath,
        "-A", assetPath.absolutePath,
        "-I", jPath.absolutePath,
        "-F", resApkPath.absolutePath) !

    resApkPath
  }

  private def dxTask: Project.Initialize[Task[File]] =
    (scalaInstance, dxJavaOpts, dxPath, classDirectory,
     proguardInJars, proguard, proguardOptimizations, classesDexPath, streams) map {
    (scalaInstance, dxJavaOpts, dxPath, classDirectory,
     proguardInJars, proguard, proguardOptimizations, classesDexPath, streams) =>

      val inputs:PathFinder = proguard match {
        case Some(file) => file
        case None       => classDirectory +++ proguardInJars --- scalaInstance.libraryJar
      }
      val uptodate = classesDexPath.exists &&
        !(inputs +++ (classDirectory ** "*.class") get).exists (_.lastModified > classesDexPath.lastModified)

      if (!uptodate) {
        val noLocals = if (proguardOptimizations.isEmpty) "" else "--no-locals"
        val dxCmd = (Seq(dxPath.absolutePath,
                        dxMemoryParameter(dxJavaOpts),
                        "--dex", noLocals,
                        "--output="+classesDexPath.absolutePath) ++
                        inputs.get.map(_.absolutePath)).filter(_.length > 0)
        streams.log.debug(dxCmd.mkString(" "))
        streams.log.info("Dexing "+classesDexPath)
        streams.log.debug(dxCmd !!)
      } else streams.log.debug("dex file uptodate, skipping")

      classesDexPath
    }

  private def proguardTask: Project.Initialize[Task[Option[File]]] =
    (useProguard, proguardOptimizations, classDirectory, proguardInJars, streams,
     classesMinJarPath, libraryJarPath, manifestPackage, proguardOption) map {
    (useProguard, proguardOptimizations, classDirectory, proguardInJars, streams,
     classesMinJarPath, libraryJarPath, manifestPackage, proguardOption) =>
      useProguard match {
        case true =>
          val optimizationOptions = {
            if (proguardOptimizations.isEmpty) List("-dontoptimize")
            else proguardOptimizations
          }.toList
          val manifestr = List("!META-INF/MANIFEST.MF", "R.class", "R$*.class",
                               "TR.class", "TR$.class", "ER.class",
                               "ER$.class", "library.properties")
          val sep = JFile.pathSeparator
          val inJars = ("\"" + classDirectory.absolutePath + "\"") +:
                       proguardInJars.map("\""+_+"\""+manifestr.mkString("(", ",!**/", ")"))

          val proguardBaseArgs = (
            "-injars" :: inJars.mkString(sep) ::
            "-outjars" :: "\""+classesMinJarPath.absolutePath+"\"" ::
            "-libraryjars" :: libraryJarPath.map("\""+_+"\"").mkString(sep) ::
            Nil
          )
          val defaultProguardArgs = List (
              "-dontwarn",
              "-dontobfuscate",
              "-dontnote scala.Enumeration",
              "-dontnote org.xml.sax.EntityResolver",
              "-keep public class * extends android.app.Activity",
              "-keep public class * extends android.app.Service",
              "-keep public class * extends android.appwidget.AppWidgetProvider",
              "-keep public class * extends android.content.BroadcastReceiver",
              "-keep public class * extends android.content.ContentProvider",
              "-keep public class * extends android.view.View",
              "-keep public class * extends android.app.Application",
              "-keep public class * implements junit.framework.Test { public void test*(); }",
              proguardOption
            )

          val cfgFile = new File("proguard.cfg")
          val config = new ProGuardConfiguration
          val allArgs: Array[String] = {
            "-keep public class " + manifestPackage + ".** { public protected *; }" ::
            optimizationOptions ++ {
              if (cfgFile.exists) {
                streams.log.info("using ProGuard configuration " + cfgFile.getAbsolutePath)
                proguardBaseArgs ++ parseCfg(cfgFile)
              }
              else {
                streams.log.info("using default ProGuard options")
                proguardBaseArgs ++ defaultProguardArgs
              }
            }
          }.toArray
          val parser = new ConfigurationParser(allArgs)
          parser parse config

          streams.log.debug("executing ProGuard with " + allArgs)
          new ProGuard(config).execute
          Some(classesMinJarPath)
        case false =>
          streams.log.info("Skipping ProGuard")
          None
      }
    }

  private def parseCfg(file: File): List[String] = {
    val lines = scala.io.Source.fromFile(file).getLines.toList
    lines.map(_.trim).map(_.split("#").head).filterNot(_.isEmpty)
  }

  private def packageTask(debug: Boolean):Project.Initialize[Task[File]] = (packageConfig, streams) map { (c, s) =>
    val builder = new ApkBuilder(c, debug)
    builder.build.fold(s.log.error(_), s.log.info(_))
    s.log.debug(builder.outputStream.toString)
    c.packageApkPath
  }

  lazy val installerTasks = Seq (
    installEmulator <<= installTask(emulator = true),
    installDevice <<= installTask(emulator = false)
  )

  lazy val settings: Seq[Setting[_]] = inConfig(Android) (installerTasks ++ Seq (
    uninstallEmulator <<= uninstallTask(emulator = true),
    uninstallDevice <<= uninstallTask(emulator = false),

    makeAssetPath <<= directory(mainAssetsPath),

    aaptPackage <<= aaptPackageTask,
    aaptPackage <<= aaptPackage dependsOn (makeAssetPath, dx),
    dx <<= dxTask,
    dx <<= dx dependsOn proguard,

    cleanApk <<= (packageApkPath) map (IO.delete(_)),

    proguard <<= proguardTask,
    proguard <<= proguard dependsOn (compile in Compile),

    packageConfig <<=
      (toolsPath, packageApkPath, resourcesApkPath, classesDexPath,
       nativeLibrariesPath, classesMinJarPath, resourceDirectory)
      (ApkConfig(_, _, _, _, _, _, _)),

    packageDebug <<= packageTask(true),
    packageRelease <<= packageTask(false)
  ) ++ Seq(packageDebug, packageRelease).map {
    t => t <<= t dependsOn (cleanApk, aaptPackage)
  })
}
