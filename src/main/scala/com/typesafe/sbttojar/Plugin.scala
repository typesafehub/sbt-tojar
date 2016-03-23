package com.typesafe.sbttojar
import sbt._
import sbt.Keys._
import sbt.compiler.MixedAnalyzingCompiler
import sbt.inc.Analysis

/*
  The way in which this plugin operates is the following:

  - set classDirectory directly to the destination jar,
    as it would be used by the standard packageBin. The
    Scala compiler will take that as a hint that it should
    compile directly to a jar file

  - set incOptions so that if any source files are touched,
    then everything is recompiled. Name hashing is set to
    "true" since that is a default value used by
    Artifact.Empty (below)

  - exportJars is set to true, as there is no classfiles
    directory to export

  - packageBin is redefined to return directly whatever
    was produced by the compilation step; it is made to
    depend explicitly on compile, in order to make sure
    that the jar exists once packageBin returns.
    Note that packageBin is defined as a dynamic task;
    the reason is that if packageBin is left to depend
    on its default definition, the default packaging
    task will always be invoked beforehand, which in
    this case will lead to an empty directory being
    packaged (no classes), and therefore an empty jar
    to appear. By using a dynamic task, the default
    is never invoked when straightToJar is true.

  - The last important part is modifying the clean
    task. The regular Analysis calculation algorithm
    within sbt will normally link the classfiles to
    the original source files, in order to be able
    to operate the incremental compiler. Since there
    are no classfiles here, that dependency section
    will be empty, and even if the jar file is deleted
    sbt will think it need not recompile the source
    files again. Here, we clean the cached Analysis
    file, replacing with a fresh empty one; that
    will force a new recompilation to take place upon
    the next request.

    This plugin depends on the change introduced in
    commit 3e50fdc825837f4dc10be1657af135387168e810
    in the sbt code base; therefore, it will not work
    on 0.13.10-RC1, or previous version of sbt.

    Instead 0.13.10-RC2 or more recent will be needed.

--

    In order to support mixed Java/Scala compilation,
    the Java compiler is called after the Scala compiler;
    if this plugin is in use, however, the jar file has
    already been generated at that point.

    We let javac compile as normal, generating further
    classfiles, and we then insert these newer files
    into the existing jar.

    If we are running under JDK7 or more recent, we use
    a zip-based virtual file system, using the existing
    jar file and adding new entries. On JDK6 and older,
    however, such support is not available; in that case,
    an alternate implementation creates a new jar that
    contains the old entries plus the newer ones.

    The default value of the class directory is passed
    to the Java compiler via a separate key.
*/

// TODO: add support for straight-to-jar for tests as well
object ToJar extends AutoPlugin {
  object autoImport {
    val straightToJar = settingKey[Boolean]("Enables straight-to-jar compilation")
  }
  val defaultClassDir = settingKey[File]("Default class output directory")
  import autoImport._
  override def globalSettings = Seq(
    straightToJar := false
  )
  override lazy val projectSettings = Seq(
    incOptions := {
      val standardIncOptions = incOptions.value
      if (straightToJar.value)
        standardIncOptions.withRecompileAllFraction(0.0).withNameHashing(true)
      else standardIncOptions
    },
    exportJars := exportJars.value || straightToJar.value,
    classDirectory in Compile := {
      if (straightToJar.value) {
        val jar = (artifactPath in (Compile,packageBin)).value
        IO.createDirectory(jar.getParentFile)
        jar
      } else {
        (classDirectory in Compile).value
      }
    },
    packageBin in Compile := Def.taskDyn {
      if (straightToJar.value) Def.task {
        val z = (compile in Compile).value
        (classDirectory in Compile).value
      } else Def.task {
        (packageBin in Compile).value
      }
    }.value,
    clean := {
      if (straightToJar.value) {
        val analysisFile = (streams in (Compile,compileIncSetup)).value.cacheDirectory /
          (compileAnalysisFilename in Compile).value
        val store =
          MixedAnalyzingCompiler.staticCachedStore(analysisFile)
        store.get.foreach {
          case (a,s) => store.set(Analysis.Empty,s)
        }
      }
      clean.value
    },
    compilers := {
      case class Y(in:sbt.compiler.JavaTool) extends sbt.compiler.JavaTool {
        def compile(contract: sbt.compiler.JavacContract,sources: Seq[java.io.File],classpath: Seq[java.io.File],
                  outputDirectory: java.io.File,options: Seq[String])(implicit log: sbt.Logger): Unit = {
          // TODO: defaultClassDir could be in Compile or Test, or something else
          val newDir = defaultClassDir.value
          newDir.mkdirs()
          in.compile(contract, sources, classpath, newDir, options)(log)
        }
        def onArgs(f: Seq[String] => Unit): sbt.compiler.JavaTool = {
          Y(in.onArgs(f))
        }
      }
      val x = compilers.value
      if (straightToJar.value) {
        x.copy(javac=Y(x.javac))
      } else x
    },
    manipulateBytecode in Compile := {
      if (straightToJar.value) {
        val classDir = defaultClassDir.value
        val analysisResult: Compiler.CompileResult = (manipulateBytecode in Compile).value
        if (analysisResult.hasModified) {
          // now, append the java classfiles to the scalac jar
          val jarFile = (classDirectory in Compile).value
          injectJar(jarFile, classDir)
        }
        analysisResult
      } else (manipulateBytecode in Compile).value
    },
    defaultClassDir := new File(((crossTarget in Compile).value /
((if ((configuration in Compile).value.name == Configurations.Compile.name) "" else (configuration in Compile).value.name + "-") + "classes")).getCanonicalPath)
  )

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  //--------------------------------------------

  import sbt.IO._
  import sbt.DirectoryFilter
  import collection.JavaConversions._

  import java.io.File
  import java.io.InputStream
  import java.io.BufferedInputStream
  import java.io.BufferedOutputStream
  import java.io.FileInputStream
  import java.io.FileOutputStream
  import java.io.PrintWriter

  // Two versions of injectJar are provided.
  // The first is old-style, and creates a new jar file in order
  // to be able to append to it, copying the old content first.
  // The second version uses the nio zip filesystem, available w/ Java 7.

  abstract class Injector {
    // injects all of the files in dir into the jar
    def injectJar(jar: File, dir: File): Unit
  }

  val injector = {
    // We have separate classes for JDK6 and JDK7; if we run on
    // JDK6, the JDK7 injector class is never initialized (which
    // is good, since required classes would be missing from the
    // classpath).

    class InjectorJDK6 extends Injector {
      import java.util.jar.JarInputStream
      import java.util.jar.JarOutputStream
      import java.util.jar.JarFile
      import java.util.jar.JarEntry

      def injectJar(jar: File, dir: File) = {
        val files = dir.**(-DirectoryFilter).get
        if (!files.isEmpty) withTemporaryFile("inject", "tempJar") { temp =>
          val out = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))

          // Initially we copy the old jar content, and later we append the new entries
          val bufferSize = 131072
          val buffer = new Array[Byte](bufferSize)
          def writeEntry(where: JarEntry, source: InputStream) = {
            out.putNextEntry(where)
            Stream.continually(source.read(buffer, 0, bufferSize)).takeWhile(_ != -1).
              foreach { size =>
                out.write(buffer, 0, size)
              }
          }

          val in = new JarFile(jar)
          //
          // The jar may contain duplicate entries (even though it shouldn't);
          // for example, the scalap jar in Scala 2.11.4 is broken.
          // Rather than aborting, we print a warning and try to continue
          val list = in.entries.toSeq
          val uniques = list.foldLeft(Map[String, JarEntry]()) { (map, entry) =>
            if (map.isDefinedAt(entry.getName)) {
              map
            } else
              map.updated(entry.getName, entry)
          }

          val targets = files.map(relativize(dir, _).getOrElse("Internal error while relativizing, please report."))

          // Copy all the content, skipping the entries that will be replaced
          uniques.valuesIterator.foreach { entry =>
            if (!targets.contains(entry.getName())) {
              writeEntry(entry, in.getInputStream(entry))
            }
          }

          // Finally, insert the new entries at the appropriate target locations
          files.zip(targets).foreach {
            case (file, target) =>
              writeEntry(new JarEntry(target), new BufferedInputStream(new FileInputStream(file)))
          }
          in.close()
          out.flush()
          out.close()

          // Time to move the temporary file back to the original location
          move(temp, jar)
        }
      }
    }

    class InjectorJDK7 extends Injector {
      import java.util.{ Map => JMap, HashMap => JHashMap, _ }
      import java.net.URI
      import java.nio.file.Path
      import java.nio.file._

      def injectJar(jar: File, dir: File) = {
        val files = dir.**(-DirectoryFilter).get
        if (!files.isEmpty) {
          val targets = files.map("/" + relativize(dir, _).getOrElse("Internal error while relativizing, please report."))
          val env: JMap[String, String] = new JHashMap[String, String]()
          env.put("create", "false")
          val uri = URI.create("jar:" + jar.toURI) // for escaping blanks&symbols
          //      val fs = FileSystems.getFileSystem(uri)
          val fs = FileSystems.newFileSystem(uri, env, null)
          try {
            val fileDirs = files.map { f => Option(f.getCanonicalFile().getParentFile()) }.distinct.flatten
            fileDirs.map { d =>
              val targetPath = fs.getPath("/" + (relativize(dir, d).getOrElse("")))
              Files.createDirectories(targetPath)
            }
            files.zip(targets).foreach {
              case (file, target) =>
                val entryPath = fs.getPath(target)
                Files.copy(file.toPath, entryPath, StandardCopyOption.REPLACE_EXISTING)
            }
          } finally {
            fs.close()
          }
        }
      }
    }

    val required = VersionNumber("1.7")
    val current = VersionNumber(sys.props("java.specification.version"))
    val hasZipFS = current.numbers.zip(required.numbers).foldRight(required.numbers.size <= current.numbers.size)((a, b) => (a._1 > a._2) || (a._1 == a._2 && b))
    if (hasZipFS) (new InjectorJDK7) else (new InjectorJDK6)
  }

  def injectJar(jar: File, dir: File) = {
    injector.injectJar(jar, dir)
  }
}

