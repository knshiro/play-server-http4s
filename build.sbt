name := "play-server-http4s"

organization := "me.ugo"

scalaVersion := "2.11.7"

scalacOptions ++= Seq("-feature","-unchecked")

resolvers += "Typesafe Releases" at "https://repo.typesafe.com/typesafe/releases/"
resolvers += Resolver
  .url("Typesafe Ivy Snapshots Repository", url("https://repo.typesafe.com/typesafe/ivy-snapshots"))(Resolver
    .ivyStylePatterns)

val playVersion = "2.4.2"
val http4sVersion = "0.9.2"
val specsVersion = "3.6.2"

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-server" % playVersion,
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-server" % http4sVersion,
  "org.http4s" %% "http4s-blaze-server" % http4sVersion,
  "com.typesafe.play" %% "play-specs2" % playVersion % "test",
  "com.typesafe.play" %% "play-ws" % playVersion % "test"
)



licenses +=("WTFPL", url("http://www.wtfpl.net/txt/copying/"))
