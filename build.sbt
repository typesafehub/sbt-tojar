organization := "com.typesafe.sbt"
version := "0.2"
name := "sbt-tojar"
sbtPlugin := true
publishMavenStyle := false
startYear := Some(2016)
description := "sbt plugin that enables straight-to-jar compilation"
licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html"))
bintrayOrganization := Some("typesafe")
bintrayRepository := "sbt-plugins"
name in bintray := "sbt-tojar"
scalacOptions += "-target:jvm-1.6"
initialize := {
   val _ = initialize.value // run the previous initialization
   val required = VersionNumber("1.7")
   val current = VersionNumber(sys.props("java.specification.version"))
   val javaOK = current.numbers.zip(required.numbers).foldRight(required.numbers.size<=current.numbers.size)((a,b) => (a._1 > a._2) || (a._1==a._2 && b))
   assert(javaOK, "Java version "+required+" or greater is needed to compile this plugin (it will run under JDK6 too, however).")
}
