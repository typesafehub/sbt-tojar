organization := "com.typesafe.sbt"
version := "0.1"
name := "sbt-tojar"
sbtPlugin := true
publishMavenStyle := false
startYear := Some(2016)
description := "sbt plugin that enables straight-to-jar compilation"
licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html"))
bintrayOrganization := Some("typesafe")
bintrayRepository := "sbt-plugins"
name in bintray := "sbt-tojar"
