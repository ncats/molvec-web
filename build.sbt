name := """molvec-web"""
organization := "gov.nih.ncats"

version := "0.0.1"

lazy val root = (project in file(".")).enablePlugins(PlayJava)

scalaVersion := "2.13.0"

lazy val commonDependencies = Seq(
  guice,
  ehcache,
  javaWs,
  "org.webjars" %% "webjars-play" % "2.7.3",
  "org.webjars" % "jquery" % "3.4.1"
)

libraryDependencies ++= commonDependencies
