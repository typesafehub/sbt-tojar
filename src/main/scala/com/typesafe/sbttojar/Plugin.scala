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
*/

object ToJar extends AutoPlugin {
  object autoImport {
    val straightToJar = settingKey[Boolean]("Enables straight-to-jar compilation")
  }
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
    }
  )
  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin
}
