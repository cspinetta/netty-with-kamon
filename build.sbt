
import scala.concurrent.duration._

name := "netty-with-kamon"
scalaVersion := "2.12.3"

enablePlugins(JavaAgent)

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding", "UTF-8",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:postfixOps",
  "-unchecked",
  "-Xlint",
  "-Yno-adapted-args",
//  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
//  "-Ywarn-value-discard",
  "-Xfuture")

fork in run := true

resolvers += Resolver.bintrayRepo("kamon-io", "releases")
resolvers += Resolver.bintrayRepo("kamon-io", "snapshots")

libraryDependencies ++= Seq(
  "io.kamon"              %% "kamon-core"                     % "1.0.0-RC1-670f32d19a30283a39a0519a74fc5c6a0efd379b",
  "io.kamon"              %% "kamon-testkit"                  % "1.0.0-RC1-670f32d19a30283a39a0519a74fc5c6a0efd379b" % "test",
  "io.kamon"              %% "kamon-netty"                    % "1.0.0-RC1-68d4d11e13e7e0dcfed0590ff1163b6296594eab",
  "io.kamon"              %% "kamon-jaeger"                   % "1.0.0-RC1-9eec74a0c7f4332336928431852104cc9ad19373"  exclude("io.kamon", "kamon-core_2.12"),
  "io.kamon"              %% "agent-scala-extension"          % "0.0.3-experimental",
  "com.github.pureconfig" %% "pureconfig"                     % "0.7.1",
  "io.netty"              %  "netty-all"                      % "4.0.50.Final",
  "io.netty"              %  "netty-transport-native-epoll"   % "4.0.50.Final"    classifier "linux-x86_64",
  "ch.qos.logback"        %  "logback-classic"                % "1.0.13"
)

javaAgents += "org.aspectj" % "aspectjweaver"  % "1.8.10"  % "compile;test;runtime"

mainClass := Some("playground.server.ServerStart")
