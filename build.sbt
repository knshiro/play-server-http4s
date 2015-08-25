name := "play-server-http4s"

organization := "me.ugo"

scalaVersion := "2.11.7"

version := "0.1-SNAPSHOT"

resolvers += "Typesafe Releases" at "https://repo.typesafe.com/typesafe/releases/"
resolvers += Resolver.url("Typesafe Ivy Snapshots Repository", url("https://repo.typesafe.com/typesafe/ivy-snapshots"))(Resolver.ivyStylePatterns)

libraryDependencies += "com.typesafe.play" %% "play-server" % "2.4.2"
libraryDependencies += "org.http4s" %% "http4s-dsl" % "0.9.2"
libraryDependencies += "org.http4s" %% "http4s-server" % "0.9.2"
libraryDependencies += "org.http4s" %% "http4s-blaze-server" % "0.9.2"

